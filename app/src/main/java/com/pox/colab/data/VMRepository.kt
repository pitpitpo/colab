package com.pox.colab.data

import android.content.Context
import com.pox.colab.engine.AlpineAppContainerManager
import com.pox.colab.engine.AlpineBootstrapGenerator
import com.pox.colab.model.ColabAccount
import com.pox.colab.model.ColabVM
import com.pox.colab.model.HardwareAccelerator
import com.pox.colab.model.ResilientProtocol
import com.pox.colab.model.TunnelProvider
import com.pox.colab.model.VMMetrics
import com.pox.colab.model.VMStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class VMRepository(
    private val preferences: AppPreferences,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
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
        _vms.value = loadedVMs
    }

    fun addAccount(
        context: Context,
        email: String,
        displayName: String,
        tokenOrCookie: String,
        tier: String
    ): ColabAccount {
        val account = ColabAccount(
            email = email,
            displayName = displayName.ifEmpty { email.substringBefore("@") },
            tokenOrCookie = tokenOrCookie,
            oauthToken = tokenOrCookie,
            tier = tier,
            isActive = _accounts.value.isEmpty()
        )
        _accounts.update { current ->
            val updated = current + account
            preferences.saveAccounts(updated)
            updated
        }

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
        _accounts.update { current ->
            val updated = current.filter { it.id != id }
            preferences.saveAccounts(updated)
            updated
        }

        if (_activeAccount.value?.id == id) {
            val nextActive = _accounts.value.firstOrNull()
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
        _accounts.update { current ->
            val updated = current.map { it.copy(isActive = it.id == id) }
            preferences.saveAccounts(updated)
            updated
        }
    }

    fun toggleAccountEnabled(id: String, isEnabled: Boolean) {
        _accounts.update { current ->
            val updated = current.map { if (it.id == id) it.copy(isEnabled = isEnabled) else it }
            preferences.saveAccounts(updated)
            updated
        }
    }

    fun updateAccountTier(id: String, tier: String) {
        _accounts.update { current ->
            val updated = current.map { if (it.id == id) it.copy(tier = tier) else it }
            preferences.saveAccounts(updated)
            updated
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
        val account = _accounts.value.find { it.id == accountId }
        val initialVM = ColabVM(
            name = name,
            accountId = accountId,
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
        val completeVM = initialVM.copy(
            bootstrapScript = AlpineBootstrapGenerator.generateBootstrapScript(initialVM, account?.tokenOrCookie)
        )
        _vms.update { current ->
            val updated = current + completeVM
            preferences.saveVMs(updated)
            updated
        }
        return completeVM
    }

    fun updateVM(vm: ColabVM) {
        _vms.update { current ->
            val updated = current.map { if (it.id == vm.id) vm else it }
            preferences.saveVMs(updated)
            updated
        }
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
        _vms.update { current ->
            val updated = current.filter { it.id != id }
            preferences.saveVMs(updated)
            updated
        }
    }

    fun getVMById(id: String): ColabVM? = _vms.value.find { it.id == id }

    fun updateMetrics(vmId: String, metrics: VMMetrics) {
        val target = getVMById(vmId) ?: return
        updateVM(target.copy(metrics = metrics))
    }

    fun spinUpAlpineVM(vmId: String) {
        val target = getVMById(vmId) ?: return
        updateVM(target.copy(status = VMStatus.RUNNING))
    }

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
