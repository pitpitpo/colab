package com.pox.colab.data

import android.content.Context
import com.pox.colab.engine.AlpineAppContainerManager
import com.pox.colab.engine.AlpineBootstrapGenerator
import com.pox.colab.engine.ColabAccelerator
import com.pox.colab.engine.ColabApiEngine
import com.pox.colab.engine.ColabBootstrapGenerator
import com.pox.colab.engine.ColabCcuInfo
import com.pox.colab.engine.ColabRuntime
import com.pox.colab.engine.RuntimeStatus
import com.pox.colab.engine.TelemetryEngine
import com.pox.colab.engine.TunnelDiscoveryEngine
import com.pox.colab.model.ColabAccount
import com.pox.colab.model.ColabVM
import com.pox.colab.model.HardwareAccelerator
import com.pox.colab.model.ResilientProtocol
import com.pox.colab.model.RuntimeEngineMode
import com.pox.colab.model.TunnelProvider
import com.pox.colab.model.VMMetrics
import com.pox.colab.model.VMStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

data class RemoteSyncResult(
    val syncedAccounts: Int = 0,
    val activeRuntimes: Int = 0,
    val updatedVMs: Int = 0,
    val errorMessage: String? = null
)

class VMRepository(
    private val preferences: AppPreferences,
    private val context: Context? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val colabApiEngine: ColabApiEngine = ColabApiEngine.INSTANCE
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

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _activeRuntimes = ConcurrentHashMap<String, ColabRuntime>()
    private val spinUpJobs = ConcurrentHashMap<String, Job>()
    private val discoveryJobs = ConcurrentHashMap<String, Job>()
    private val inFlightRuntimeLookups = ConcurrentHashMap<String, Deferred<ColabRuntime?>>()
    private val lastNegativeRuntimeLookup = ConcurrentHashMap<String, Long>()

    fun getActiveRuntime(vmId: String): ColabRuntime? = _activeRuntimes[vmId]

    fun setActiveRuntime(vmId: String, runtime: ColabRuntime) {
        _activeRuntimes[vmId] = runtime
        lastNegativeRuntimeLookup.remove(vmId)
    }

    private suspend fun getFreshAccountToken(account: ColabAccount): String {
        val prevOauth = account.oauthToken
        val prevToken = account.tokenOrCookie
        val token = ColabApiEngine.refreshOAuthTokenIfNeeded(account)
        if (account.oauthToken != prevOauth || account.tokenOrCookie != prevToken) {
            val persisted = _accounts.updateAndGet { list ->
                list.map { a -> if (a.id == account.id) account.copy() else a }
            }
            preferences.saveAccounts(persisted)
        }
        return token
    }

    /**
     * Returns an active runtime whose proxy URL and proxy token are ready for immediate use,
     * refreshing an expiring proxy token or querying Colab assignments when needed.
     */
    suspend fun ensureFreshRuntime(vmId: String): ColabRuntime? {
        val cached = _activeRuntimes[vmId]
        if (cached != null && !cached.needsProxyResolution()) {
            return cached
        }
        return resolveActiveRuntime(vmId)
    }

    /**
     * Sends a Colab control-plane keep-alive heartbeat (`/tun/m/{endpoint}/keep-alive/`)
     * for [vmId] if it has an active runtime and valid OAuth credentials.
     */
    suspend fun sendKeepAlive(vmId: String): Boolean = withContext(Dispatchers.IO) {
        val runtime = _activeRuntimes[vmId] ?: return@withContext false
        val vm = getVMById(vmId) ?: return@withContext false
        val account = _accounts.value.find { it.id == vm.accountId }
            ?: _activeAccount.value
            ?: return@withContext false
        val token = getFreshAccountToken(account)
        if (token.isBlank()) return@withContext false
        colabApiEngine.keepAlive(token, runtime).getOrDefault(false)
    }

    /** Queries live Google Colab compute units balance and GPU eligibility for [accountId]. */
    suspend fun getAccountCcuInfo(accountId: String): Result<ColabCcuInfo> = withContext(Dispatchers.IO) {
        val account = _accounts.value.find { it.id == accountId }
            ?: return@withContext Result.failure(Exception("Account not found"))
        val token = getFreshAccountToken(account)
        if (token.isBlank()) {
            return@withContext Result.failure(Exception("Missing OAuth token for ${account.email}"))
        }
        colabApiEngine.getCcuInfo(token)
    }

    /** Interrupts the active Jupyter kernel on the Colab runtime backing [vmId]. */
    suspend fun interruptKernel(vmId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val runtime = ensureFreshRuntime(vmId)
            ?: return@withContext Result.failure(Exception("No active Colab runtime found"))
        colabApiEngine.interruptKernel(runtime)
    }

    /** Restarts the active Jupyter kernel on the Colab runtime backing [vmId]. */
    suspend fun restartKernel(vmId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val runtime = ensureFreshRuntime(vmId)
            ?: return@withContext Result.failure(Exception("No active Colab runtime found"))
        colabApiEngine.restartKernel(runtime)
    }

    /** Probes `/api/status` on the Colab runtime backing [vmId]. */
    suspend fun checkRuntimeHealth(vmId: String): Result<RuntimeStatus> = withContext(Dispatchers.IO) {
        val runtime = ensureFreshRuntime(vmId)
            ?: return@withContext Result.failure(Exception("No active Colab runtime found"))
        val vm = getVMById(vmId)
        val account = vm?.let { v -> _accounts.value.find { it.id == v.accountId } } ?: _activeAccount.value
        val token = account?.let { getFreshAccountToken(it) } ?: ""
        colabApiEngine.checkRuntimeStatus(token, runtime)
    }

    /** Re-runs the bootstrap TCP tunnel script directly on the active Colab runtime for [vmId]. */
    suspend fun redeployTunnel(vmId: String): Result<String> = withContext(Dispatchers.IO) {
        val vm = getVMById(vmId)
            ?: return@withContext Result.failure(Exception("Instance not found"))
        val account = _accounts.value.find { it.id == vm.accountId } ?: _activeAccount.value
            ?: return@withContext Result.failure(Exception("No authenticated Google account found"))
        val token = getFreshAccountToken(account)
        val runtime = ensureFreshRuntime(vmId)
            ?: return@withContext Result.failure(Exception("No active Colab runtime connected"))

        val script = ColabBootstrapGenerator.generateBootstrapScript(vm, token)
        val result = colabApiEngine.runBootstrapScript(
            bearerToken = token,
            runtime = runtime,
            bootstrapScript = script
        )
        if (result.isSuccess) {
            val (_, execResult) = result.getOrThrow()
            val discovered = parseDiscoveredEndpoint(execResult.stdout)
            if (!discovered.isNullOrBlank()) {
                updateTunnelEndpoint(vmId, discovered)
                return@withContext Result.success(discovered)
            }
        }
        startTunnelDiscovery(vmId)
        Result.success("Tunnel discovery initialized")
    }

    suspend fun resolveActiveRuntime(vmId: String): ColabRuntime? {
        _activeRuntimes[vmId]?.takeIf { !it.needsProxyResolution() }?.let { return it }
        val lastMiss = lastNegativeRuntimeLookup[vmId]
        if (_activeRuntimes[vmId] == null && lastMiss != null && System.currentTimeMillis() - lastMiss < NEGATIVE_LOOKUP_COOLDOWN_MS) {
            return null
        }

        val deferred = inFlightRuntimeLookups.computeIfAbsent(vmId) {
            scope.async(Dispatchers.IO) {
                try {
                    val existing = _activeRuntimes[vmId]
                    if (existing != null && !existing.needsProxyResolution()) {
                        return@async existing
                    }
                    val vm = getVMById(vmId) ?: return@async existing
                    val account = _accounts.value.find { it.id == vm.accountId }
                        ?: _activeAccount.value
                        ?: return@async existing
                    val token = getFreshAccountToken(account)
                    if (token.isBlank()) return@async existing

                    if (existing != null) {
                        val refreshed = colabApiEngine.ensureRuntimeProxyReady(token, existing).getOrNull()
                        if (refreshed != null) {
                            _activeRuntimes[vmId] = refreshed
                            lastNegativeRuntimeLookup.remove(vmId)
                            return@async refreshed
                        }
                    }

                    val assignments = colabApiEngine.listAssignments(token, hydrateProxyTokens = true).getOrNull()
                    val runtime = assignments?.firstOrNull { it.endpointUrl.isNotBlank() }
                    if (runtime != null) {
                        val hydrated = colabApiEngine.ensureRuntimeProxyReady(token, runtime).getOrDefault(runtime)
                        _activeRuntimes[vmId] = hydrated
                        lastNegativeRuntimeLookup.remove(vmId)
                        hydrated
                    } else {
                        lastNegativeRuntimeLookup[vmId] = System.currentTimeMillis()
                        existing
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    lastNegativeRuntimeLookup[vmId] = System.currentTimeMillis()
                    _activeRuntimes[vmId]
                } finally {
                    inFlightRuntimeLookups.remove(vmId)
                }
            }
        }
        return deferred.await()
    }

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
            val validAccountIds = loadedAccounts.mapTo(HashSet(loadedAccounts.size)) { it.id }
            loadedVMs.filter { vm ->
                vm.accountId.isNotBlank() && vm.accountId in validAccountIds
            }
        }
        // Sanitize any corrupted tunnelUrl or sshConfig host pointing to colab.research.google.com or trycloudflare.com
        val sanitizedVMs = validVMs.map { vm ->
            val isBadTunnel = vm.tunnelUrl?.let { it.contains("colab.research.google.com") || it.contains("trycloudflare.com") || !isValidTunnelEndpoint(it) } ?: false
            val isBadHost = vm.sshConfig.host.isNotBlank() && (
                vm.sshConfig.host.contains("colab.research.google.com") ||
                vm.sshConfig.host.contains("trycloudflare.com") ||
                vm.sshConfig.host == "localhost"
            )
            if (isBadTunnel || isBadHost) {
                vm.copy(
                    tunnelUrl = if (isBadTunnel) null else vm.tunnelUrl,
                    sshConfig = if (isBadHost) vm.sshConfig.copy(host = "", port = 22) else vm.sshConfig
                )
            } else {
                vm
            }
        }
        if (sanitizedVMs != loadedVMs) {
            preferences.saveVMs(sanitizedVMs)
        }
        _vms.value = sanitizedVMs
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

        val shouldBeActive = _activeAccount.value == null || _accounts.value.isEmpty()
        val account = ColabAccount(
            email = email,
            displayName = displayName.ifEmpty { email.substringBefore("@") },
            tokenOrCookie = cleanToken,
            oauthToken = oauthToken,
            tier = tier,
            isActive = shouldBeActive
        )
        val updated = _accounts.updateAndGet { current ->
            if (shouldBeActive) {
                current.map { it.copy(isActive = false) } + account
            } else {
                current + account
            }
        }
        preferences.saveAccounts(updated)

        if (shouldBeActive) {
            preferences.setActiveAccountId(account.id)
            _activeAccount.value = account
        }

        // Spawn a dedicated CLI instance inside the single Alpine container
        scope.launch {
            AlpineAppContainerManager.getInstance(context).createCliInstanceForAccount(account)
        }

        return account
    }

    fun removeAccount(context: Context, id: String) {
        val removedAccount = _accounts.value.find { it.id == id }
        val removedVmIds = _vms.value.filter { it.accountId == id }.map { it.id }
        val runtimesToRelease = removedVmIds.mapNotNull { _activeRuntimes[it] }.distinctBy { it.endpointId }

        val updatedAccounts = _accounts.updateAndGet { current ->
            current.filter { it.id != id }
        }
        preferences.saveAccounts(updatedAccounts)

        // Cancel background jobs and evict caches for VMs cascade-deleted with this account
        removedVmIds.forEach { cleanupVmResources(it) }

        // Best-effort remote unassign for any active runtimes owned by this account
        if (removedAccount != null && runtimesToRelease.isNotEmpty()) {
            scope.launch(Dispatchers.IO) {
                val token = ColabApiEngine.extractBearerToken(removedAccount)
                runtimesToRelease.forEach { rt ->
                    try {
                        colabApiEngine.terminateRuntime(token, rt)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {}
                }
            }
        }

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
        val clean = rawEndpoint.trim()
        if (!isValidTunnelEndpoint(clean)) return
        val target = getVMById(id) ?: return
        val (host, port) = parseEndpoint(clean)
        if (host.isBlank() || !isValidTunnelEndpoint(clean)) return
        val updated = target.copy(
            tunnelUrl = clean,
            sshConfig = target.sshConfig.copy(
                host = host,
                port = port
            )
        )
        updateVM(updated)
    }

    private fun cleanupVmResources(vmId: String) {
        spinUpJobs.remove(vmId)?.cancel()
        discoveryJobs.remove(vmId)?.cancel()
        inFlightRuntimeLookups.remove(vmId)?.cancel()
        lastNegativeRuntimeLookup.remove(vmId)
        _activeRuntimes.remove(vmId)?.let { runtime ->
            colabApiEngine.invalidateKernelCache(runtime.endpointUrl)
        }
    }

    fun deleteVM(id: String) {
        val activeRuntime = _activeRuntimes[id]
        val target = getVMById(id)
        cleanupVmResources(id)
        val updated = _vms.updateAndGet { current ->
            current.filter { it.id != id }
        }
        preferences.saveVMs(updated)
        if (activeRuntime != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val account = target?.let { vm ->
                        _accounts.value.find { it.id == vm.accountId }
                    } ?: _activeAccount.value
                    val token = account?.let { getFreshAccountToken(it) } ?: ""
                    colabApiEngine.terminateRuntime(token, activeRuntime)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {}
            }
        }
    }

    fun getVMById(id: String): ColabVM? = _vms.value.find { it.id == id }

    fun updateMetrics(vmId: String, metrics: VMMetrics) {
        _vms.update { current ->
            val index = current.indexOfFirst { it.id == vmId }
            if (index < 0) return@update current
            val existing = current[index]
            val derivedStatus = if (metrics.timestamp > 0L && existing.status != VMStatus.ERROR) {
                VMStatus.RUNNING
            } else {
                existing.status
            }
            if (existing.metrics == metrics && existing.status == derivedStatus) {
                return@update current
            }
            current.toMutableList().apply {
                this[index] = existing.copy(metrics = metrics, status = derivedStatus)
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
        // Cancel any previous spin-up for this VM so rapid taps cannot race
        spinUpJobs.remove(vmId)?.cancel()
        lastNegativeRuntimeLookup.remove(vmId)
        updateVM(target.copy(status = VMStatus.STARTING))

        val job = scope.launch(Dispatchers.IO) {
            try {
                val mode = preferences.getRuntimeEngineMode()
                val account = _accounts.value.find { it.id == target.accountId } ?: _activeAccount.value
                val token = account?.let {
                    val prevOauth = it.oauthToken
                    val prevToken = it.tokenOrCookie
                    val freshToken = ColabApiEngine.refreshOAuthTokenIfNeeded(it)
                    if (it.oauthToken != prevOauth || it.tokenOrCookie != prevToken) {
                        val updatedAccounts = _accounts.updateAndGet { list ->
                            list.map { a -> if (a.id == it.id) it.copy() else a }
                        }
                        preferences.saveAccounts(updatedAccounts)
                    }
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

                // Start deduplicated asynchronous rendezvous tunnel discovery
                startTunnelDiscovery(vmId)

                val executeNativeApi: suspend () -> Unit = {
                    val accelerator = mapToColabAccelerator(target.hardwareAccelerator)

                    // 1. Check in-memory active runtime cache
                    var runtime: ColabRuntime? = getActiveRuntime(vmId)

                    // 2. If not cached, check existing Colab assignments with auto-retry and proxy hydration
                    if (runtime == null || runtime.endpointUrl.isBlank()) {
                        for (retry in 1..3) {
                            val existingList = colabApiEngine.listAssignments(token, hydrateProxyTokens = true).getOrNull()
                            val found = existingList?.firstOrNull { it.sessionId.isNotBlank() }
                            if (found != null) {
                                runtime = found
                                break
                            }
                            if (retry < 3) kotlinx.coroutines.delay(1000L * retry)
                        }
                    }

                    // 3. If still no active runtime, allocate with auto-retry
                    if (runtime == null || runtime.endpointUrl.isBlank()) {
                        for (retry in 1..3) {
                            val allocResult = colabApiEngine.allocateRuntime(token, accelerator)
                            if (allocResult.isSuccess) {
                                runtime = allocResult.getOrNull()
                                break
                            }
                            if (retry < 3) kotlinx.coroutines.delay(1500L * retry)
                        }
                    }

                    if (runtime != null) {
                        val hydratedRuntime = colabApiEngine.ensureRuntimeProxyReady(token, runtime).getOrDefault(runtime)
                        runtime = hydratedRuntime
                        setActiveRuntime(vmId, hydratedRuntime)
                        val current = getVMById(vmId)
                        if (current != null) {
                            val cleanTunnel = current.tunnelUrl?.takeIf { isValidTunnelEndpoint(it) }
                            updateVM(current.copy(
                                status = VMStatus.STARTING,
                                tunnelUrl = cleanTunnel
                            ))
                        }

                        // Ensure tunnel auto-discovery is listening
                        startTunnelDiscovery(vmId)

                        // 4. Run bootstrap script with auto-retry (up to 3 attempts with backoff)
                        var bootstrapSuccess = false
                        for (attempt in 1..3) {
                            val result = colabApiEngine.runBootstrapScript(
                                bearerToken = token,
                                runtime = runtime,
                                bootstrapScript = bootstrapScript
                            )
                            if (result.isSuccess) {
                                val (_, execResult) = result.getOrThrow()

                                // Auto-Set Tunnel: Channel 1 - Parse from cell execution stdout
                                val discovered = parseDiscoveredEndpoint(execResult.stdout)
                                if (!discovered.isNullOrBlank()) {
                                    updateTunnelEndpoint(vmId, discovered)
                                } else {
                                    // Auto-Set Tunnel: Channel 2 - Read from /tmp/colab_tunnel_endpoint.txt
                                    try {
                                        val fileRes = colabApiEngine.readFile(runtime, "tmp/colab_tunnel_endpoint.txt")
                                        val fileEp = fileRes.getOrNull()?.trim()
                                        if (isValidTunnelEndpoint(fileEp)) {
                                            updateTunnelEndpoint(vmId, fileEp!!)
                                        }
                                    } catch (_: Exception) {}
                                }

                                if (execResult.success || execResult.httpStatus == 200) {
                                    bootstrapSuccess = true
                                    break
                                }
                            }
                            if (attempt < 3) kotlinx.coroutines.delay(2000L * attempt)
                        }

                        // Fast follow-up: poll for tunnel file if not yet discovered
                        if (!isValidTunnelEndpoint(getVMById(vmId)?.tunnelUrl)) {
                            for (pollAttempt in 1..8) {
                                kotlinx.coroutines.delay(1500L)
                                try {
                                    val fileRes = colabApiEngine.readFile(runtime, "tmp/colab_tunnel_endpoint.txt")
                                    val fileEp = fileRes.getOrNull()?.trim()
                                    if (isValidTunnelEndpoint(fileEp)) {
                                        updateTunnelEndpoint(vmId, fileEp!!)
                                        break
                                    }
                                } catch (_: Exception) {}
                            }
                        }

                        val cur = getVMById(vmId)
                        if (cur != null) {
                            val hasValidTunnel = isValidTunnelEndpoint(cur.tunnelUrl)
                            if (bootstrapSuccess || hasValidTunnel) {
                                updateVM(cur.copy(status = VMStatus.RUNNING))
                            }
                        }
                    } else {
                        android.util.Log.e("VMRepository", "Colab allocation failed after retries for VM $vmId")
                        val current = getVMById(vmId)
                        if (current != null && current.status != VMStatus.RUNNING && !isValidTunnelEndpoint(current.tunnelUrl)) {
                            updateVM(current.copy(status = VMStatus.ERROR))
                        }
                    }
                }

                when (mode) {
                    RuntimeEngineMode.NATIVE_API -> {
                        executeNativeApi()
                    }

                    RuntimeEngineMode.ALPINE_PROOT -> {
                        // Option B: Embedded Alpine Container with PRoot + colab-cli
                        val containerMgr = appContext?.let { AlpineAppContainerManager.getInstance(it) }
                        if (containerMgr != null &&
                            containerMgr.isRootfsInstalled() &&
                            java.io.File(containerMgr.findProotBinary()).let { it.exists() && it.canExecute() }
                        ) {
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val current = getVMById(vmId)
                if (current != null && current.status != VMStatus.RUNNING && current.tunnelUrl.isNullOrBlank()) {
                    updateVM(current.copy(status = VMStatus.ERROR))
                }
            }
        }
        spinUpJobs[vmId] = job
        job.invokeOnCompletion { spinUpJobs.remove(vmId, job) }
        return job
    }

    /**
     * Initiates deduplicated asynchronous tunnel auto-discovery for the VM by monitoring
     * the rendezvous channel announced by the Colab bootstrap script.
     */
    fun startTunnelDiscovery(vmId: String, onDiscovered: ((String) -> Unit)? = null): Job {
        if (onDiscovered == null) {
            discoveryJobs[vmId]?.takeIf { it.isActive }?.let { return it }
        } else {
            discoveryJobs.remove(vmId)?.cancel()
        }

        val job = TunnelDiscoveryEngine.startAutoDiscovery(vmId, scope) { endpoint ->
            updateTunnelEndpoint(vmId, endpoint)
            val vm = getVMById(vmId)
            if (vm != null && vm.status != VMStatus.RUNNING) {
                updateVM(vm.copy(status = VMStatus.RUNNING))
            }
            onDiscovered?.invoke(endpoint)
        }
        discoveryJobs[vmId] = job
        job.invokeOnCompletion { discoveryJobs.remove(vmId, job) }
        return job
    }

    @Deprecated("Use spinUpVM instead", ReplaceWith("spinUpVM(vmId)"))
    fun spinUpAlpineVM(vmId: String) = spinUpVM(vmId)

    fun stopVM(vmId: String) {
        val activeRuntime = _activeRuntimes[vmId]
        val target = getVMById(vmId)
        cleanupVmResources(vmId)
        if (target != null) {
            updateVM(target.copy(status = VMStatus.STOPPED))
        }
        if (activeRuntime != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val account = target?.let { vm ->
                        _accounts.value.find { it.id == vm.accountId }
                    } ?: _activeAccount.value
                    val token = account?.let { getFreshAccountToken(it) } ?: ""
                    colabApiEngine.terminateRuntime(token, activeRuntime)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Manually synchronizes enabled Google Colab accounts, active remote runtimes,
     * live telemetry metrics, and tunnel endpoints with the remote Colab API.
     */
    suspend fun syncWithRemoteApi(): RemoteSyncResult = withContext(Dispatchers.IO) {
        if (!_isRefreshing.compareAndSet(false, true)) {
            return@withContext RemoteSyncResult()
        }
        try {
            lastNegativeRuntimeLookup.clear()
            val enabledAccounts = _accounts.value.filter { it.isEnabled }
            if (enabledAccounts.isEmpty()) {
                return@withContext RemoteSyncResult(
                    syncedAccounts = 0,
                    activeRuntimes = 0,
                    updatedVMs = 0,
                    errorMessage = null
                )
            }

            val accountsChanged = AtomicBoolean(false)
            val vmsByAccount = _vms.value.groupBy { it.accountId }

            data class AccountSyncOutcome(
                val synced: Boolean,
                val activeRuntimes: Int,
                val updatedVMs: Int,
                val error: String?
            )

            val outcomes = coroutineScope {
                enabledAccounts.map { account ->
                    async(Dispatchers.IO) {
                        val prevOauth = account.oauthToken
                        val prevToken = account.tokenOrCookie
                        val token = ColabApiEngine.refreshOAuthTokenIfNeeded(account)
                        if (account.oauthToken != prevOauth || account.tokenOrCookie != prevToken) {
                            accountsChanged.set(true)
                        }
                        if (token.isBlank()) {
                            return@async AccountSyncOutcome(
                                synced = false,
                                activeRuntimes = 0,
                                updatedVMs = 0,
                                error = "Missing OAuth token for ${account.email}"
                            )
                        }

                        val assignmentsResult = colabApiEngine.listAssignments(token, hydrateProxyTokens = true)
                        val accountVMs = vmsByAccount[account.id].orEmpty()
                        var updatedCount = 0

                        if (assignmentsResult.isSuccess) {
                            val runtimes = assignmentsResult.getOrNull()
                                .orEmpty()
                                .filter { it.endpointUrl.isNotBlank() }
                            val primaryRuntime = runtimes.firstOrNull()

                            if (primaryRuntime != null) {
                                val metrics = colabApiEngine.fetchMetrics(primaryRuntime).getOrNull()
                                val discoveredTunnel = metrics?.tunnelEndpoint?.takeIf { isValidTunnelEndpoint(it) }
                                    ?: run {
                                        val needsTunnel = accountVMs.any { !isValidTunnelEndpoint(it.tunnelUrl) }
                                        if (needsTunnel) {
                                            try {
                                                colabApiEngine.readFile(primaryRuntime, "tmp/colab_tunnel_endpoint.txt")
                                                    .getOrNull()
                                                    ?.trim()
                                                    ?.takeIf { isValidTunnelEndpoint(it) }
                                            } catch (e: CancellationException) {
                                                throw e
                                            } catch (_: Exception) {
                                                null
                                            }
                                        } else {
                                            null
                                        }
                                    }

                                for (vm in accountVMs) {
                                    setActiveRuntime(vm.id, primaryRuntime)
                                    val latest = getVMById(vm.id) ?: continue
                                    val targetTunnel = discoveredTunnel ?: latest.tunnelUrl
                                    val updatedSsh = if (discoveredTunnel != null) {
                                        val (host, port) = parseEndpoint(discoveredTunnel)
                                        if (host.isNotBlank()) latest.sshConfig.copy(host = host, port = port) else latest.sshConfig
                                    } else {
                                        latest.sshConfig
                                    }
                                    val updatedVm = latest.copy(
                                        status = VMStatus.RUNNING,
                                        tunnelUrl = targetTunnel,
                                        sshConfig = updatedSsh,
                                        metrics = if (metrics != null && metrics.timestamp > 0L) metrics else latest.metrics
                                    )
                                    if (updatedVm != latest) {
                                        updateVM(updatedVm)
                                        updatedCount++
                                    }
                                }
                            } else {
                                // Remote API reports no active Colab assignments for this account.
                                for (vm in accountVMs) {
                                    val latest = getVMById(vm.id) ?: continue
                                    if (latest.status == VMStatus.STARTING) continue

                                    val tunnelEp = latest.tunnelUrl?.takeIf { isValidTunnelEndpoint(it) }
                                        ?: latest.sshConfig.host.takeIf { it.isNotBlank() && it != "localhost" && isValidTunnelEndpoint(it) }
                                            ?.let { "$it:${latest.sshConfig.port}" }
                                    val httpMetrics = if (tunnelEp != null) {
                                        val (host, _) = parseEndpoint(tunnelEp)
                                        TelemetryEngine.fetchHttpMetrics(host)
                                    } else {
                                        null
                                    }

                                    if (httpMetrics != null) {
                                        updateMetrics(latest.id, httpMetrics)
                                        updatedCount++
                                    } else {
                                        _activeRuntimes.remove(latest.id)?.let { stale ->
                                            colabApiEngine.invalidateKernelCache(stale.endpointUrl)
                                        }
                                        if (latest.status == VMStatus.RUNNING || latest.status == VMStatus.RECONNECTING) {
                                            updateVM(latest.copy(status = VMStatus.STOPPED))
                                            updatedCount++
                                        }
                                    }
                                }
                            }

                            AccountSyncOutcome(
                                synced = true,
                                activeRuntimes = runtimes.size,
                                updatedVMs = updatedCount,
                                error = null
                            )
                        } else {
                            // Fall back to direct HTTP telemetry probe if a VM already has a tunnel.
                            for (vm in accountVMs) {
                                val tunnelEp = vm.tunnelUrl?.takeIf { isValidTunnelEndpoint(it) } ?: continue
                                val (host, _) = parseEndpoint(tunnelEp)
                                val httpMetrics = TelemetryEngine.fetchHttpMetrics(host)
                                if (httpMetrics != null) {
                                    updateMetrics(vm.id, httpMetrics)
                                    updatedCount++
                                }
                            }
                            AccountSyncOutcome(
                                synced = false,
                                activeRuntimes = 0,
                                updatedVMs = updatedCount,
                                error = assignmentsResult.exceptionOrNull()?.message ?: "Colab API sync failed"
                            )
                        }
                    }
                }.awaitAll()
            }

            if (accountsChanged.get()) {
                val refreshedById = enabledAccounts.associateBy { it.id }
                val updatedAccounts = _accounts.updateAndGet { list ->
                    list.map { acc -> refreshedById[acc.id]?.copy() ?: acc }
                }
                preferences.saveAccounts(updatedAccounts)
            }

            val syncedCount = outcomes.count { it.synced }
            val activeCount = outcomes.sumOf { it.activeRuntimes }
            val updatedVMsCount = outcomes.sumOf { it.updatedVMs }
            val firstError = outcomes.firstOrNull { it.error != null }?.error

            RemoteSyncResult(
                syncedAccounts = syncedCount,
                activeRuntimes = activeCount,
                updatedVMs = updatedVMsCount,
                errorMessage = if (syncedCount == 0) firstError else null
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RemoteSyncResult(errorMessage = e.message ?: "Remote sync failed")
        } finally {
            _isRefreshing.value = false
        }
    }

    companion object {
        private const val NEGATIVE_LOOKUP_COOLDOWN_MS = 5_000L

        private val READY_REGEX = Regex("""TUNNEL READY:\s*([^\s\r\n]+)""")
        private val BORE_REGEX = Regex("""(bore\.pub:[0-9]+)""")
        private val PINGGY_REGEX = Regex("""((?:tcp://)?[a-zA-Z0-9.-]+\.pinggy\.(?:io|link):[0-9]+)""")
        private val SERVEO_REGEX = Regex("""(serveo\.net:[0-9]+)""")
        private val NGROK_REGEX = Regex("""([a-zA-Z0-9.-]+\.ngrok(?:-free)?\.(?:io|app):[0-9]+)""")

        fun isValidTunnelEndpoint(ep: String?): Boolean {
            if (ep.isNullOrBlank()) return false
            val clean = ep.trim().lowercase()
            if (clean.contains("colab.research.google.com") ||
                clean.contains("trycloudflare.com") ||
                clean == "localhost" ||
                clean.startsWith("127.")) return false
            return clean.contains(":")
        }

        fun parseDiscoveredEndpoint(text: String): String? {
            if (text.isBlank()) return null
            // 1. Explicit marker: TUNNEL READY: <endpoint>
            READY_REGEX.find(text)?.let {
                val candidate = it.groupValues[1].trim()
                if (isValidTunnelEndpoint(candidate)) return candidate
            }
            // 2. Bore TCP tunnel: bore.pub:<port>
            BORE_REGEX.find(text)?.let {
                val candidate = it.groupValues[1].trim()
                if (isValidTunnelEndpoint(candidate)) return candidate
            }
            // 3. Pinggy TCP tunnel: [subdomain.]pinggy.io:[port] or pinggy.link:[port]
            PINGGY_REGEX.find(text)?.let {
                val candidate = it.groupValues[1].trim()
                if (isValidTunnelEndpoint(candidate)) return candidate
            }
            // 4. Serveo TCP tunnel: serveo.net:[port]
            SERVEO_REGEX.find(text)?.let {
                val candidate = it.groupValues[1].trim()
                if (isValidTunnelEndpoint(candidate)) return candidate
            }
            // 5. Ngrok TCP tunnel
            NGROK_REGEX.find(text)?.let {
                val candidate = it.groupValues[1].trim()
                if (isValidTunnelEndpoint(candidate)) return candidate
            }
            return null
        }

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
