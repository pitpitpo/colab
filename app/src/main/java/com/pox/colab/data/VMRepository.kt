package com.pox.colab.data

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class VMRepository(
    private val preferences: AppPreferences,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
    private val _accounts = MutableStateFlow<List<ColabAccount>>(emptyList())
    val accounts: StateFlow<List<ColabAccount>> = _accounts.asStateFlow()

    private val _activeAccount = MutableStateFlow<ColabAccount?>(null)
    val activeAccount: StateFlow<ColabAccount?> = _activeAccount.asStateFlow()

    private val _vms = MutableStateFlow<List<ColabVM>>(emptyList())
    val vms: StateFlow<List<ColabVM>> = _vms.asStateFlow()

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

    fun addAccount(email: String, displayName: String, tokenOrCookie: String, tier: String): ColabAccount {
        val account = ColabAccount(
            email = email,
            displayName = displayName.ifEmpty { email.substringBefore("@") },
            tokenOrCookie = tokenOrCookie,
            tier = tier,
            isActive = _accounts.value.isEmpty()
        )
        val updated = _accounts.value + account
        _accounts.value = updated
        preferences.saveAccounts(updated)
        if (_activeAccount.value == null || account.isActive) {
            setActiveAccount(account.id)
        }
        return account
    }

    fun removeAccount(id: String) {
        val updated = _accounts.value.filter { it.id != id }
        _accounts.value = updated
        preferences.saveAccounts(updated)
        if (_activeAccount.value?.id == id) {
            val nextActive = updated.firstOrNull()
            setActiveAccount(nextActive?.id)
        }
    }

    fun setActiveAccount(id: String?) {
        preferences.setActiveAccountId(id)
        val target = _accounts.value.find { it.id == id }
        _activeAccount.value = target
        val updated = _accounts.value.map { it.copy(isActive = it.id == id) }
        _accounts.value = updated
        preferences.saveAccounts(updated)
    }

    fun toggleAccountEnabled(id: String, isEnabled: Boolean) {
        val updated = _accounts.value.map { if (it.id == id) it.copy(isEnabled = isEnabled) else it }
        _accounts.value = updated
        preferences.saveAccounts(updated)
    }

    fun updateAccountTier(id: String, tier: String) {
        val updated = _accounts.value.map { if (it.id == id) it.copy(tier = tier) else it }
        _accounts.value = updated
        preferences.saveAccounts(updated)
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
        val updated = _vms.value + completeVM
        _vms.value = updated
        preferences.saveVMs(updated)
        return completeVM
    }

    fun updateVM(vm: ColabVM) {
        val updated = _vms.value.map { if (it.id == vm.id) vm else it }
        _vms.value = updated
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

    fun updateMetrics(id: String, metrics: VMMetrics) {
        val target = getVMById(id) ?: return
        val isAlive = metrics.cpuUsagePercent > 0f || metrics.ramTotalBytes > 0L || metrics.diskTotalBytes > 0L
        val updated = target.copy(
            metrics = metrics,
            status = if (isAlive && target.status == VMStatus.STOPPED) VMStatus.RUNNING else target.status,
            lastConnectedTimestamp = System.currentTimeMillis()
        )
        updateVM(updated)
    }

    fun deleteVM(id: String) {
        val updated = _vms.value.filter { it.id != id }
        _vms.value = updated
        preferences.saveVMs(updated)
    }

    fun getVMById(id: String): ColabVM? {
        return _vms.value.find { it.id == id }
    }

    fun spinUpAlpineVM(id: String) {
        val target = getVMById(id) ?: return
        val runningVM = target.copy(
            status = VMStatus.RUNNING,
            uptimeSeconds = 0L,
            lastConnectedTimestamp = System.currentTimeMillis()
        )
        updateVM(runningVM)
    }

    fun stopVM(id: String) {
        val target = getVMById(id) ?: return
        updateVM(target.copy(status = VMStatus.STOPPED))
    }

    companion object {
        fun parseEndpoint(raw: String): Pair<String, Int> {
            var s = raw.trim()
            if (s.isBlank()) return Pair("", 22)
            if (s.startsWith("tcp://", ignoreCase = true)) s = s.substring(6)
            if (s.startsWith("ssh://", ignoreCase = true)) s = s.substring(6)
            if (s.startsWith("http://", ignoreCase = true)) s = s.substring(7)
            if (s.startsWith("https://", ignoreCase = true)) s = s.substring(8)
            s = s.substringBefore("/").trim()
            return if (s.contains(":")) {
                val host = s.substringBefore(":")
                val port = s.substringAfter(":").toIntOrNull() ?: 22
                Pair(host, port)
            } else {
                Pair(s, 22)
            }
        }
    }
}

