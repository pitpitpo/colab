package com.pox.colab.model

enum class SSHAuthMethod {
    PASSWORD,
    PRIVATE_KEY
}

enum class ResilientProtocol {
    MOSH,       // Mobile Shell (State Synchronization Protocol over UDP - roams and survives disconnection)
    TMUX_SSH,   // SSH attached to tmux session with auto-reconnection daemon
    AUTOSSH     // Tunnel monitored with heartbeat probes
}

data class SSHSessionConfig(
    val host: String = "localhost",
    val port: Int = 22,
    val username: String = "root",
    val authMethod: SSHAuthMethod = SSHAuthMethod.PASSWORD,
    val password: String = "alpine",
    val privateKey: String? = null,
    val resilientProtocol: ResilientProtocol = ResilientProtocol.MOSH,
    val moshPort: Int = 60001,
    val tmuxSessionName: String = "colab_persistent",
    val autoReconnect: Boolean = true,
    val keepAliveIntervalSec: Int = 10,
    val maxReconnectRetries: Int = 999
)
