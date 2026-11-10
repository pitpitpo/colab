package com.pox.colab.data

import com.pox.colab.engine.AlpineBootstrapGenerator
import com.pox.colab.model.AuthType
import com.pox.colab.model.ColabAccount
import com.pox.colab.model.ColabVM
import com.pox.colab.model.HardwareAccelerator
import com.pox.colab.model.ResilientProtocol
import com.pox.colab.model.SSHSessionConfig
import com.pox.colab.model.TunnelProvider
import com.pox.colab.model.VMMetrics
import com.pox.colab.model.VMStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

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
        loadInitialData()
    }

    private fun loadInitialData() {
        val loadedAccounts = preferences.loadAccounts().toMutableList()
        if (loadedAccounts.isEmpty()) {
            // Seed sample accounts for immediate usability
            val acc1 = ColabAccount(
                id = UUID.randomUUID().toString(),
                email = "developer@gmail.com",
                displayName = "Primary Research",
                isActive = true,
                tier = "Colab Pro+",
                computeUnitsRemaining = 485.5
            )
            val acc2 = ColabAccount(
                id = UUID.randomUUID().toString(),
                email = "ai-lab@university.edu",
                displayName = "AI Lab Student",
                isActive = false,
                tier = "Colab Pro",
                computeUnitsRemaining = 120.0
            )
            loadedAccounts.add(acc1)
            loadedAccounts.add(acc2)
            preferences.saveAccounts(loadedAccounts)
            preferences.setActiveAccountId(acc1.id)
        }

        _accounts.value = loadedAccounts
        val activeId = preferences.getActiveAccountId() ?: loadedAccounts.firstOrNull()?.id
        _activeAccount.value = loadedAccounts.find { it.id == activeId } ?: loadedAccounts.firstOrNull()

        val loadedVMs = preferences.loadVMs().toMutableList()
        if (loadedVMs.isEmpty() && loadedAccounts.isNotEmpty()) {
            val starterAcc = loadedAccounts.first()
            val sampleVM = ColabVM(
                id = UUID.randomUUID().toString(),
                name = "alpine-t4-prod",
                accountId = starterAcc.id,
                status = VMStatus.RUNNING,
                hardwareAccelerator = HardwareAccelerator.T4_GPU,
                highRam = true,
                alpineVersion = "Alpine Linux 3.19.1",
                tunnelProvider = TunnelProvider.CLOUDFLARE,
                tunnelUrl = "ssh://colab-alpine-v3.trycloudflare.com",
                uptimeSeconds = 9840L,
                metrics = VMMetrics(
                    cpuUsagePercent = 28.4f,
                    ramUsedBytes = 4_200_000_000L,
                    ramTotalBytes = 15_800_000_000L,
                    gpuModel = "NVIDIA Tesla T4 (16GB)",
                    gpuVramUsedBytes = 3_450_000_000L,
                    gpuVramTotalBytes = 16_106_127_360L,
                    gpuTemperatureCelsius = 44,
                    diskUsedBytes = 24_500_000_000L,
                    diskTotalBytes = 115_000_000_000L
                ),
                sshConfig = SSHSessionConfig(
                    host = "colab-alpine-v3.trycloudflare.com",
                    port = 22,
                    username = "root",
                    resilientProtocol = ResilientProtocol.MOSH
                )
            )
            loadedVMs.add(sampleVM.copy(bootstrapScript = AlpineBootstrapGenerator.generateBootstrapScript(sampleVM)))
            preferences.saveVMs(loadedVMs)
        }

        _vms.value = loadedVMs
    }

    fun addAccount(email: String, displayName: String, tokenOrCookie: String, tier: String = "Colab Pro"): ColabAccount {
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

    fun addVM(
        name: String,
        accountId: String,
        hardwareAccelerator: HardwareAccelerator,
        tunnelProvider: TunnelProvider,
        resilientProtocol: ResilientProtocol
    ): ColabVM {
        val initialVM = ColabVM(
            name = name,
            accountId = accountId,
            hardwareAccelerator = hardwareAccelerator,
            tunnelProvider = tunnelProvider,
            sshConfig = SSHSessionConfig(
                resilientProtocol = resilientProtocol,
                port = 22
            )
        )
        val completeVM = initialVM.copy(
            bootstrapScript = AlpineBootstrapGenerator.generateBootstrapScript(initialVM)
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
            uptimeSeconds = 120L,
            lastConnectedTimestamp = System.currentTimeMillis(),
            metrics = VMMetrics(
                cpuUsagePercent = 14.5f,
                ramUsedBytes = 2_100_000_000L,
                ramTotalBytes = 15_800_000_000L,
                gpuModel = if (target.hardwareAccelerator.hasGpu) target.hardwareAccelerator.label else null,
                gpuVramUsedBytes = if (target.hardwareAccelerator.hasGpu) 1_200_000_000L else 0L,
                gpuVramTotalBytes = if (target.hardwareAccelerator.hasGpu) 16_106_127_360L else 0L,
                gpuTemperatureCelsius = if (target.hardwareAccelerator.hasGpu) 41 else 0,
                diskUsedBytes = 21_000_000_000L,
                diskTotalBytes = 115_000_000_000L
            )
        )
        updateVM(runningVM)
    }

    fun stopVM(id: String) {
        val target = getVMById(id) ?: return
        updateVM(target.copy(status = VMStatus.STOPPED))
    }
}
