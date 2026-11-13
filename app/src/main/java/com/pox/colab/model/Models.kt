package com.pox.colab.model

import java.util.UUID

enum class HardwareAccelerator(val label: String, val chipCode: String) {
    NONE("CPU (Standard)", "cpu"),
    T4("NVIDIA T4 Tensor Core", "t4"),
    V100("NVIDIA V100 Tensor Core", "v100"),
    A100("NVIDIA A100-SXM4-40GB", "a100")
}

enum class TunnelProvider(val label: String) {
    CLOUDFLARE("Cloudflare Quick Tunnel"),
    BORE("Bore TCP Tunnel"),
    NGROK("Ngrok TCP"),
    CUSTOM("Custom / Direct Host")
}

enum class ResilientProtocol(val label: String) {
    TMUX_SSH("Resilient tmux-SSH"),
    MOSH_UDP("Mosh (UDP Mobile Shell)")
}

enum class SSHAuthMethod {
    PASSWORD,
    PUBLIC_KEY
}

enum class VMStatus {
    STOPPED,
    STARTING,
    RUNNING,
    RECONNECTING,
    ERROR
}

data class SSHSessionConfig(
    val host: String = "",
    val port: Int = 22,
    val username: String = "root",
    val password: String = "colab-root",
    val authMethod: SSHAuthMethod = SSHAuthMethod.PASSWORD,
    val privateKey: String? = null,
    val resilientProtocol: ResilientProtocol = ResilientProtocol.TMUX_SSH,
    val tmuxSessionName: String = "colab_session"
)

data class VMMetrics(
    val cpuUsagePercent: Float = 0f,
    val ramUsedBytes: Long = 0L,
    val ramTotalBytes: Long = 0L,
    val diskUsedBytes: Long = 0L,
    val diskTotalBytes: Long = 0L,
    val gpuUsagePercent: Float = 0f,
    val gpuVramUsedBytes: Long = 0L,
    val gpuVramTotalBytes: Long = 0L,
    val gpuModel: String? = null,
    val gpuTemperatureCelsius: Int = 0,
    val uptimeSeconds: Long = 0L
) {
    val ramUsagePercent: Float
        get() = if (ramTotalBytes > 0) (ramUsedBytes.toFloat() / ramTotalBytes.toFloat()) * 100f else 0f

    val diskUsagePercent: Float
        get() = if (diskTotalBytes > 0) (diskUsedBytes.toFloat() / diskTotalBytes.toFloat()) * 100f else 0f

    fun formattedRam(): String {
        val usedGb = ramUsedBytes / (1024.0 * 1024.0 * 1024.0)
        val totalGb = ramTotalBytes / (1024.0 * 1024.0 * 1024.0)
        return String.format("%.1f / %.1f GB", usedGb, totalGb)
    }

    fun formattedDisk(): String {
        val usedGb = diskUsedBytes / (1024.0 * 1024.0 * 1024.0)
        val totalGb = diskTotalBytes / (1024.0 * 1024.0 * 1024.0)
        return String.format("%.1f / %.1f GB", usedGb, totalGb)
    }

    fun formattedGpuVram(): String {
        val usedGb = gpuVramUsedBytes / (1024.0 * 1024.0 * 1024.0)
        val totalGb = gpuVramTotalBytes / (1024.0 * 1024.0 * 1024.0)
        return String.format("%.1f / %.1f GB", usedGb, totalGb)
    }
}

data class ColabVM(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val accountId: String,
    val hardwareAccelerator: HardwareAccelerator = HardwareAccelerator.T4,
    val tunnelProvider: TunnelProvider = TunnelProvider.CLOUDFLARE,
    val tunnelUrl: String? = null,
    val sshConfig: SSHSessionConfig = SSHSessionConfig(),
    val status: VMStatus = VMStatus.STOPPED,
    val metrics: VMMetrics = VMMetrics(),
    val bootstrapScript: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

data class ColabAccount(
    val id: String = UUID.randomUUID().toString(),
    val email: String,
    val displayName: String,
    val tokenOrCookie: String,
    val oauthToken: String = tokenOrCookie,
    val tier: String = "Free", // "Free" or "Pro"
    val isEnabled: Boolean = true,
    val isActive: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

data class RemoteFile(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val permissions: String = "-rw-r--r--",
    val modifiedTime: String = ""
)
