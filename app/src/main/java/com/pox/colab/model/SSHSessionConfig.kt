package com.pox.colab.model

enum class SSHAuthMethod {
    PASSWORD,
    PRIVATE_KEY
}

enum class ResilientProtocol(val label: String, val description: String) {
    TMUX_SSH("SSH + Tmux Supervisor", "Interactive SSH2 terminal attached to persistent tmux session with auto-reconnection supervisor"),
    DIRECT_SSH("Standard SSH2", "Direct interactive SSH2 shell channel")
}

data class SSHSessionConfig(
    val host: String = "",
    val port: Int = 22,
    val username: String = "root",
    val authMethod: SSHAuthMethod = SSHAuthMethod.PASSWORD,
    val password: String = "",
    val privateKey: String? = null,
    val resilientProtocol: ResilientProtocol = ResilientProtocol.TMUX_SSH,
    val tmuxSessionName: String = "colab_session",
    val autoReconnect: Boolean = true,
    val keepAliveIntervalSec: Int = 10,
    val maxReconnectRetries: Int = 999
)

