package com.pox.colab.model

import java.util.UUID

enum class VMStatus {
    STOPPED,
    STARTING,
    RUNNING,
    RECONNECTING,
    ERROR
}

enum class HardwareAccelerator(val label: String, val hasGpu: Boolean) {
    NONE_CPU("Standard CPU", false),
    T4_GPU("NVIDIA Tesla T4 (16GB)", true),
    A100_GPU("NVIDIA A100 SXM4 (40GB)", true),
    V100_GPU("NVIDIA Tesla V100 (16GB)", true),
    TPU_V2("Google Cloud TPU v2", false)
}

enum class TunnelProvider(val label: String) {
    CLOUDFLARE("Cloudflare Tunnel (Quick / No-login)"),
    NGROK("Ngrok Tunnel"),
    BORE("Bore TCP Tunnel"),
    CUSTOM("Direct / Custom Tunnel")
}

data class ColabVM(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val accountId: String,
    val status: VMStatus = VMStatus.STOPPED,
    val hardwareAccelerator: HardwareAccelerator = HardwareAccelerator.T4_GPU,
    val highRam: Boolean = true,
    val alpineVersion: String = "Alpine Linux 3.19 (Minimal Rootfs)",
    val colabCliVersion: String = "v1.4.2",
    val tunnelProvider: TunnelProvider = TunnelProvider.CLOUDFLARE,
    val tunnelUrl: String? = null,
    val sshConfig: SSHSessionConfig = SSHSessionConfig(),
    val metrics: VMMetrics = VMMetrics(),
    val uptimeSeconds: Long = 0L,
    val lastConnectedTimestamp: Long = 0L,
    val bootstrapScript: String = ""
)
