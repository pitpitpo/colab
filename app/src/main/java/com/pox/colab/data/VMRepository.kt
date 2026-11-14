package com.pox.colab.data

import android.content.Context
import com.pox.colab.engine.AlpineAppContainerManager
import com.pox.colab.engine.AlpineBootstrapGenerator
import com.pox.colab.engine.ColabAccelerator
import com.pox.colab.engine.ColabApiEngine
import com.pox.colab.engine.TunnelDiscoveryEngine
import com.pox.colab.model.ColabAccount
import com.pox.colab.model.ColabVM
import com.pox.colab.model.HardwareAccelerator
import com.pox.colab.model.ResilientProtocol
import com.pox.colab.model.RuntimeEngineMode
import com.pox.colab.model.TunnelProvider
import com.pox.colab.model.VMMetrics
import com.pox.colab.model.VMStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch

class VMRepository(
    private val preferences: AppPreferences,
    private val context: Context? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val colabApiEngine: ColabApiEngine = ColabApiEngine()
) {
    private val appContext: Context?
        get() = context ?: try { com.pox.colab.ColabApp.instance } catch (_: Exception) { null }

    private val _accounts = MutableStateFlow<List<ColabAccount>>(emptyList())
    val accounts: StateFlow<List<ColabAccount>> = _accounts.asStateFlow()

    private val _activeAccount = MutableStateFlow<ColabAccount?>(null)
    val activeAccount: StateFlow<ColabAccount?> = _activeAccount.asStateFlow()

    private val _vms = MutableStateFlow<List<ColabVM>>(emptyList())
    val vms: StateFlow<List<ColabVM>> = _vms.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        loadData()
    }

    private fun loadData() {
        val loadedAccounts = preferences.loadAccounts()
        _accounts.value = loadedAccounts
        val activeId = preferences.getActiveAccountId() ?: loadedAccounts.firstOrNull()?.id
        _activeAccount.value = loadedAccounts.find { it.id == activeId } ?: loadedAccounts.firstOrNull()

        val loadedVMs = preferences.loadVMs()
        // Strictly purge legacy unlinked/dummy VMs that have no authenticated Google account
        val validVMs = if (loadedAccounts.isEmpty()) {
            emptyList()
        } else {
            loadedVMs.filter { vm ->
                vm.accountId.isNotBlank() && loadedAccounts.any { it.id == vm.accountId }
            }
        }
        if (validVMs.size != loadedVMs.size) {
            preferences.saveVMs(validVMs)
        }
        _vms.value = validVMs
    }

    fun addAccount(
        context: Context,
        email: String,
        displayName: String,
        tokenOrCookie: String,
        tier: String,
        oauthToken: String = tokenOrCookie
    ): ColabAccount {
        // Extract raw token string if tokenOrCookie is serialized JSON
        val cleanToken = if (tokenOrCookie.trim().startsWith("{")) {
            try {
                org.json.JSONObject(tokenOrCookie).optString("token", "").ifBlank {
                    org.json.JSONObject(tokenOrCookie).optString("access_token", tokenOrCookie)
                }
            } catch (_: Exception) {
                tokenOrCookie
            }
        } else {
            tokenOrCookie
        }

        val account = ColabAccount(
            email = email,
            displayName = displayName.ifEmpty { email.substringBefore("@") },
            tokenOrCookie = cleanToken,
            oauthToken = oauthToken,
            tier = tier,
            isActive = _accounts.value.isEmpty()
        )
        val updated = _accounts.updateAndGet { current ->
            current + account
        }
        preferences.saveAccounts(updated)

        if (_activeAccount.value == null || account.isActive) {
            setActiveAccount(account.id)
        }

        // Spawn a dedicated CLI instance inside the single Alpine container
        scope.launch {
            AlpineAppContainerManager.getInstance(context).createCliInstanceForAccount(account)
        }

        return account
    }

    fun removeAccount(context: Context, id: String) {
        val updatedAccounts = _accounts.updateAndGet { current ->
            current.filter { it.id != id }
        }
        preferences.saveAccounts(updatedAccounts)

        // Cascade delete VMs associated with this removed account to prevent orphans
        val updatedVMs = _vms.updateAndGet { current ->
            current.filter { it.accountId != id }
        }
        preferences.saveVMs(updatedVMs)

        if (_activeAccount.value?.id == id) {
            val nextActive = updatedAccounts.firstOrNull()
            setActiveAccount(nextActive?.id)
        }

        // Clean up CLI instance in Alpine container
        scope.launch {
            AlpineAppContainerManager.getInstance(context).deleteCliInstanceForAccount(id)
        }
    }

    fun setActiveAccount(id: String?) {
        preferences.setActiveAccountId(id)
        val target = _accounts.value.find { it.id == id }
        _activeAccount.value = target
        val updated = _accounts.updateAndGet { current ->
            current.map { it.copy(isActive = it.id == id) }
        }
        preferences.saveAccounts(updated)
    }

    fun toggleAccountEnabled(id: String, isEnabled: Boolean, context: Context? = null) {
        val updated = _accounts.updateAndGet { current ->
            current.map { if (it.id == id) it.copy(isEnabled = isEnabled) else it }
        }
        preferences.saveAccounts(updated)

        val target = updated.find { it.id == id }
        val ctx = context ?: appContext
        if (target != null && ctx != null) {
            scope.launch {
                AlpineAppContainerManager.getInstance(ctx).updateAccountStatus(id, isEnabled, target.tier)
            }
        }
    }

    fun updateAccountTier(id: String, tier: String, context: Context? = null) {
        val updated = _accounts.updateAndGet { current ->
            current.map { if (it.id == id) it.copy(tier = tier) else it }
        }
        preferences.saveAccounts(updated)

        val target = updated.find { it.id == id }
        val ctx = context ?: appContext
        if (target != null && ctx != null) {
            scope.launch {
                AlpineAppContainerManager.getInstance(ctx).updateAccountStatus(id, target.isEnabled, tier)
            }
        }
    }

    fun addVM(
        name: String,
        accountId: String,
        hardwareAccelerator: HardwareAccelerator,
        tunnelProvider: TunnelProvider,
        resilientProtocol: ResilientProtocol,
        password: String,
        tunnelEndpoint: String = ""
    ): ColabVM {
        val (host, port) = parseEndpoint(tunnelEndpoint)
        val targetAccount = _accounts.value.find { it.id == accountId }
            ?: _activeAccount.value
            ?: throw IllegalStateException("Cannot create Colab VM: An authenticated Google Colab account is required. Please connect your Google account first.")

        val resolvedAccountId = targetAccount.id
        val initialVM = ColabVM(
            name = name,
            accountId = resolvedAccountId,
            hardwareAccelerator = hardwareAccelerator,
            tunnelProvider = tunnelProvider,
            tunnelUrl = tunnelEndpoint.trim().takeIf { it.isNotBlank() },
            sshConfig = com.pox.colab.model.SSHSessionConfig(
                host = host,
                port = port,
                resilientProtocol = resilientProtocol,
                password = password
            )
        )
        val token = ColabApiEngine.extractBearerToken(targetAccount)
        val completeVM = initialVM.copy(
            bootstrapScript = AlpineBootstrapGenerator.generateBootstrapScript(initialVM, token)
        )
        val updated = _vms.updateAndGet { current ->
            current + completeVM
        }
        preferences.saveVMs(updated)

        // Automatically start tunnel auto-discovery if no manual endpoint was provided
        if (tunnelEndpoint.isBlank()) {
            startTunnelDiscovery(completeVM.id)
        }

        return completeVM
    }

    fun updateVM(vm: ColabVM) {
        val updated = _vms.updateAndGet { current ->
            current.map { if (it.id == vm.id) vm else it }
        }
        preferences.saveVMs(updated)
    }

    fun updateTunnelEndpoint(id: String, rawEndpoint: String) {
        val target = getVMById(id) ?: return
        val (host, port) = parseEndpoint(rawEndpoint)
        val updated = target.copy(
            tunnelUrl = rawEndpoint.trim(),
            sshConfig = target.sshConfig.copy(
                host = host,
                port = port
            )
        )
        updateVM(updated)
    }

    fun deleteVM(id: String) {
        val updated = _vms.updateAndGet { current ->
            current.filter { it.id != id }
        }
        preferences.saveVMs(updated)
    }

    fun getVMById(id: String): ColabVM? = _vms.value.find { it.id == id }

    fun updateMetrics(vmId: String, metrics: VMMetrics) {
        _vms.update { current ->
            current.map {
                if (it.id == vmId) {
                    val derivedStatus = if (metrics.timestamp > 0L && it.status != VMStatus.ERROR) VMStatus.RUNNING else it.status
                    it.copy(metrics = metrics, status = derivedStatus)
                } else it
            }
        }
    }

    private fun mapToColabAccelerator(acc: HardwareAccelerator): ColabAccelerator = when (acc) {
        HardwareAccelerator.NONE -> ColabAccelerator.NONE
        HardwareAccelerator.T4 -> ColabAccelerator.T4
        HardwareAccelerator.V100 -> ColabAccelerator.V100
        HardwareAccelerator.A100 -> ColabAccelerator.A100
    }

    fun spinUpVM(vmId: String): Job {
        val target = getVMById(vmId) ?: return scope.launch {}
        updateVM(target.copy(status = VMStatus.STARTING))

        return scope.launch(Dispatchers.IO) {
            try {
                val mode = preferences.getRuntimeEngineMode()
                val account = _accounts.value.find { it.id == target.accountId } ?: _activeAccount.value
                val token = account?.let {
                    val freshToken = ColabApiEngine.refreshOAuthTokenIfNeeded(it)
                    _accounts.update { list ->
                        list.map { a -> if (a.id == it.id) it.copy() else a }
                    }
                    preferences.saveAccounts(_accounts.value)
                    freshToken
                } ?: ""

                if (account == null || token.isBlank()) {
                    android.util.Log.e("VMRepository", "Authentication failure: VM ${target.name} ($vmId) has no valid authenticated Google Colab account or OAuth token.")
                    val current = getVMById(vmId)
                    if (current != null) {
                        updateVM(current.copy(status = VMStatus.ERROR))
                    }
                    return@launch
                }

                // Ensure bootstrap script is generated
                val bootstrapScript = if (target.bootstrapScript.isNotBlank()) {
                    target.bootstrapScript
                } else {
                    val script = AlpineBootstrapGenerator.generateBootstrapScript(target, token)
                    updateVM(target.copy(bootstrapScript = script))
                    script
                }

                // Start asynchronous rendezvous tunnel discovery so the VM announces its tunnel
                startTunnelDiscovery(vmId)

                val executeNativeApi: suspend () -> Unit = {
                    val accelerator = mapToColabAccelerator(target.hardwareAccelerator)

                    // Check for existing active Colab assignment on this account (fast reconnect)
                    val existingList = colabApiEngine.listAssignments(token).getOrNull()
                    val activeRuntime = existingList?.firstOrNull { it.sessionId.isNotBlank() }

                    val runtimeResult = if (activeRuntime != null) {
                        Result.success(activeRuntime)
                    } else {
                        colabApiEngine.allocateRuntime(token, accelerator)
                    }

                    runtimeResult.fold(
                        onSuccess = { runtime ->
                            val current = getVMById(vmId)
                            if (current != null) {
                                val assignedUrl = runtime.endpointUrl.ifBlank { "https://colab.research.google.com/tun/m/${runtime.sessionId}" }
                                updateVM(current.copy(
                                    status = VMStatus.STARTING,
                                    tunnelUrl = current.tunnelUrl?.takeIf { it.isNotBlank() } ?: assignedUrl
                                ))
                            }

                            val result = colabApiEngine.runBootstrapScript(
                                bearerToken = token,
                                runtime = runtime,
                                bootstrapScript = bootstrapScript
                            )

                            result.fold(
                                onSuccess = { (_, execResult) ->
                                    if (!execResult.success && execResult.httpStatus >= 400) {
                                        val cur = getVMById(vmId)
                                        if (cur != null && cur.status != VMStatus.RUNNING && cur.tunnelUrl.isNullOrBlank()) {
                                            updateVM(cur.copy(status = VMStatus.ERROR))
                                        }
                                    }
                                },
                                onFailure = {
                                    // Keep STARTING while tunnel discovery is listening
                                }
                            )
                        },
                        onFailure = { allocErr ->
                            android.util.Log.e("VMRepository", "Colab allocation failed: ${allocErr.message}", allocErr)
                            val current = getVMById(vmId)
                            if (current != null && current.status != VMStatus.RUNNING && current.tunnelUrl.isNullOrBlank()) {
                                updateVM(current.copy(status = VMStatus.ERROR))
                            }
                        }
                    )
                }

                when (mode) {
                    RuntimeEngineMode.NATIVE_API -> {
                        executeNativeApi()
                    }

                    RuntimeEngineMode.ALPINE_PROOT -> {
                        // Option B: Embedded Alpine Container with PRoot + colab-cli
                        val ctx = appContext
                        val containerMgr = ctx?.let { AlpineAppContainerManager.getInstance(it) }
                        val prootBinary = containerMgr?.findProotBinary() ?: "proot"
                        val prootFile = java.io.File(prootBinary)
                        val prootCanRun = prootFile.exists() && prootFile.canExecute() && containerMgr?.isRootfsInstalled() == true

                        if (prootCanRun && ctx != null && containerMgr != null) {
                            val execResult = containerMgr.executeColabCli(
                                target.accountId,
                                "runtime",
                                "run",
                                "--code",
                                bootstrapScript
                            )
                            if (execResult.exitCode != 0) {
                                val current = getVMById(vmId)
                                if (current != null && current.status != VMStatus.RUNNING && current.tunnelUrl.isNullOrBlank()) {
                                    updateVM(current.copy(status = VMStatus.ERROR))
                                }
                            }
                        } else {
                            android.util.Log.i("VMRepository", "Alpine PRoot environment not initialized on device. Seamlessly routing execution through Direct Colab REST/WebSocket API.")
                            executeNativeApi()
                        }
                    }
                }
            } catch (e: Exception) {
                val current = getVMById(vmId)
                if (current != null && current.status != VMStatus.RUNNING && current.tunnelUrl.isNullOrBlank()) {
                    updateVM(current.copy(status = VMStatus.ERROR))
                }
            }
        }
    }

    /**
     * Initiates asynchronous tunnel auto-discovery for the VM by monitoring the
     * rendezvous channel announced by the Colab bootstrap script.
     */
    fun startTunnelDiscovery(vmId: String, onDiscovered: ((String) -> Unit)? = null): kotlinx.coroutines.Job {
        return TunnelDiscoveryEngine.startAutoDiscovery(vmId, scope) { endpoint ->
            updateTunnelEndpoint(vmId, endpoint)
            val vm = getVMById(vmId)
            if (vm != null && vm.status != VMStatus.RUNNING) {
                updateVM(vm.copy(status = VMStatus.RUNNING))
            }
            onDiscovered?.invoke(endpoint)
        }
    }

    @Deprecated("Use spinUpVM instead", ReplaceWith("spinUpVM(vmId)"))
    inline fun spinUpAlpineVM(vmId: String) = spinUpVM(vmId)

    fun stopVM(vmId: String) {
        val target = getVMById(vmId) ?: return
        updateVM(target.copy(status = VMStatus.STOPPED))
    }

    companion object {
        fun parseEndpoint(raw: String): Pair<String, Int> {
            val clean = raw.trim()
                .removePrefix("ssh://")
                .removePrefix("tcp://")
                .removePrefix("https://")
                .removePrefix("http://")
            return if (clean.contains(":")) {
                val host = clean.substringBefore(":")
                val port = clean.substringAfter(":").filter { it.isDigit() }.toIntOrNull() ?: 22
                Pair(host, port)
            } else {
                Pair(clean, 22)
            }
        }
    }
}
