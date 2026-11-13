package com.pox.colab.engine

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.pox.colab.data.VMRepository
import com.pox.colab.model.ColabVM
import com.pox.colab.model.ResilientProtocol
import com.pox.colab.model.SSHAuthMethod
import com.pox.colab.model.VMMetrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

enum class SSHConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    ERROR
}

private val globalTerminalLineSeq = java.util.concurrent.atomic.AtomicLong(1L)

data class TerminalLine(
    val id: Long = globalTerminalLineSeq.incrementAndGet(),
    val text: String,
    val isSystemNotice: Boolean = false
)

class ResilientSSHManager(
    vm: ColabVM,
    private val scope: CoroutineScope,
    var onMetricsUpdated: ((VMMetrics) -> Unit)? = null,
    var onConnectionStateChanged: ((SSHConnectionState) -> Unit)? = null,
    private val tmuxSessionOverride: String? = null
) {
    var vm: ColabVM = vm
        private set
    private val _connectionState = MutableStateFlow(SSHConnectionState.DISCONNECTED)
    val connectionState: StateFlow<SSHConnectionState> = _connectionState.asStateFlow()

    private val _terminalOutput = MutableStateFlow<List<TerminalLine>>(emptyList())
    val terminalOutput: StateFlow<List<TerminalLine>> = _terminalOutput.asStateFlow()

    private val _statusMessage = MutableStateFlow("Disconnected")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private var jschSession: Session? = null
    private var shellChannel: ChannelShell? = null
    private var shellOutputStream: OutputStream? = null
    private var readerJob: Job? = null
    private var supervisorJob: Job? = null
    private var isUserIntentionallyDisconnected = false

    private val jsch = JSch()

    init {
        val (h, p) = resolveHostAndPort()
        val tmuxName = tmuxSessionOverride ?: vm.sshConfig.tmuxSessionName
        appendSystemNotice("==================================================")
        appendSystemNotice(" Colab Resilient Terminal (${vm.sshConfig.resilientProtocol.label})")
        appendSystemNotice(" Target: ${vm.name} | Host: ${if (h.isNotBlank()) "$h:$p" else "[Unconfigured]"}")
        if (vm.sshConfig.resilientProtocol == ResilientProtocol.TMUX_SSH) {
            appendSystemNotice(" Persistent Tmux Session: $tmuxName")
        } else {
            appendSystemNotice(" [!] Mosh/UDP transport is unavailable in this build.")
            appendSystemNotice("     Falling back to a plain SSH2 shell (no tmux persistence).")
        }
        appendSystemNotice("==================================================")
    }

    fun resolveHostAndPort(): Pair<String, Int> {
        val raw = if (vm.sshConfig.host.isNotBlank() && vm.sshConfig.host != "localhost") {
            "${vm.sshConfig.host}:${vm.sshConfig.port}"
        } else {
            vm.tunnelUrl ?: ""
        }
        return VMRepository.parseEndpoint(raw)
    }

    fun connect() {
        val (host, _) = resolveHostAndPort()
        if (host.isBlank()) {
            setConnectionState(SSHConnectionState.CONNECTING)
            _statusMessage.value = "Auto-discovering tunnel..."
            appendSystemNotice("[*] Tunnel endpoint not yet configured.")
            appendSystemNotice("[*] Listening for Colab script tunnel announcement on topic '${TunnelDiscoveryEngine.getDiscoveryTopic(vm.id)}'...")
            appendSystemNotice("[*] Run the bootstrap script in Colab to connect automatically.")

            isUserIntentionallyDisconnected = false
            supervisorJob?.cancel()
            supervisorJob = scope.launch(Dispatchers.IO) {
                var discoveredEndpoint: String? = null
                for (attempt in 1..120) {
                    if (!isActive || isUserIntentionallyDisconnected) break
                    val ep = TunnelDiscoveryEngine.pollDiscoveredEndpoint(vm.id)
                    if (!ep.isNullOrBlank()) {
                        discoveredEndpoint = ep
                        break
                    }
                    delay(2000)
                }

                if (!discoveredEndpoint.isNullOrBlank()) {
                    appendSystemNotice("[+] Discovered live tunnel endpoint: $discoveredEndpoint")
                    appendSystemNotice("[*] Connecting SSH session...")
                    val (newHost, newPort) = VMRepository.parseEndpoint(discoveredEndpoint)
                    vm = vm.copy(
                        tunnelUrl = discoveredEndpoint,
                        sshConfig = vm.sshConfig.copy(host = newHost, port = newPort),
                        status = com.pox.colab.model.VMStatus.RUNNING
                    )
                    try {
                        com.pox.colab.ColabApp.instance.repository.updateTunnelEndpoint(vm.id, discoveredEndpoint)
                    } catch (_: Exception) {}

                    runConnectionSupervisor()
                } else if (!isUserIntentionallyDisconnected) {
                    setConnectionState(SSHConnectionState.DISCONNECTED)
                    _statusMessage.value = "Discovery timed out"
                    appendSystemNotice("[!] Tunnel auto-discovery timed out. Please ensure the Colab cell is running.")
                }
            }
            return
        }

        isUserIntentionallyDisconnected = false
        supervisorJob?.cancel()
        readerJob?.cancel()
        closeStreamsAndSession()
        supervisorJob = scope.launch(Dispatchers.IO) {
            runConnectionSupervisor()
        }
    }

    fun disconnect() {
        isUserIntentionallyDisconnected = true
        supervisorJob?.cancel()
        readerJob?.cancel()
        closeStreamsAndSession()
        setConnectionState(SSHConnectionState.DISCONNECTED)
        _statusMessage.value = "Disconnected"
        appendSystemNotice("[!] Disconnected.")
    }

    fun forceReconnect() {
        appendSystemNotice("[*] Reconnecting...")
        isUserIntentionallyDisconnected = false
        scope.launch(Dispatchers.IO) {
            closeStreamsAndSession()
            setConnectionState(SSHConnectionState.RECONNECTING)
            attemptConnect()
        }
    }

    fun sendCommand(input: String) = sendInput(input)

    fun sendInput(input: String) {
        scope.launch(Dispatchers.IO) {
            try {
                shellOutputStream?.let { out ->
                    out.write(input.toByteArray(StandardCharsets.UTF_8))
                    out.flush()
                } ?: run {
                    appendSystemNotice("[!] Not connected to remote host.")
                }
            } catch (e: Exception) {
                appendSystemNotice("[!] Transmission error: ${e.message}")
                triggerAutoReconnect()
            }
        }
    }

    fun sendCtrlC() {
        scope.launch(Dispatchers.IO) {
            try {
                shellOutputStream?.let { out ->
                    out.write(byteArrayOf(3))
                    out.flush()
                }
            } catch (_: Exception) {
                triggerAutoReconnect()
            }
        }
    }

    fun sendSpecialKey(key: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val bytes = when (key) {
                    "TAB" -> byteArrayOf(9)
                    "ESC" -> byteArrayOf(27)
                    "UP" -> byteArrayOf(27, 91, 65)
                    "DOWN" -> byteArrayOf(27, 91, 66)
                    "RIGHT" -> byteArrayOf(27, 91, 67)
                    "LEFT" -> byteArrayOf(27, 91, 68)
                    "ENTER" -> byteArrayOf(13)
                    else -> key.toByteArray()
                }
                shellOutputStream?.let {
                    it.write(bytes)
                    it.flush()
                }
            } catch (_: Exception) {
                triggerAutoReconnect()
            }
        }
    }

    fun clearOutput() = clearScreen()

    fun clearScreen() {
        _terminalOutput.value = emptyList()
    }

    private suspend fun runConnectionSupervisor() {
        var cycle = 0
        while (scope.isActive && !isUserIntentionallyDisconnected) {
            if (_connectionState.value == SSHConnectionState.DISCONNECTED ||
                _connectionState.value == SSHConnectionState.RECONNECTING ||
                _connectionState.value == SSHConnectionState.ERROR
            ) {
                attemptConnect()
            }

            delay((vm.sshConfig.keepAliveIntervalSec * 1000L).coerceAtLeast(4000L))

            if (_connectionState.value == SSHConnectionState.CONNECTED) {
                checkConnectionHealth()
                cycle++
                // Poll real metrics over SSH exec channel every ~8 seconds
                if (cycle % 2 == 0) {
                    pollTelemetryOverSSH()
                }
            }
        }
    }

    private fun setConnectionState(newState: SSHConnectionState) {
        _connectionState.value = newState
        onConnectionStateChanged?.invoke(newState)
    }

    private suspend fun attemptConnect() {
        withContext(Dispatchers.IO) {
            try {
                val (targetHost, targetPort) = resolveHostAndPort()
                if (targetHost.isBlank()) {
                    setConnectionState(SSHConnectionState.DISCONNECTED)
                    _statusMessage.value = "Tunnel endpoint required"
                    return@withContext
                }

                setConnectionState(SSHConnectionState.CONNECTING)
                _statusMessage.value = "Connecting to $targetHost:$targetPort..."
                appendSystemNotice("[*] Connecting to $targetHost:$targetPort...")

                val session = jsch.getSession(vm.sshConfig.username, targetHost, targetPort)
                if (vm.sshConfig.authMethod == SSHAuthMethod.PASSWORD) {
                    session.setPassword(vm.sshConfig.password)
                } else if (vm.sshConfig.authMethod == SSHAuthMethod.PUBLIC_KEY) {
                    val key = vm.sshConfig.privateKey
                    if (!key.isNullOrBlank()) {
                        try {
                            jsch.addIdentity("vm_${vm.id}_key", key.toByteArray(StandardCharsets.UTF_8), null, null)
                        } catch (ke: Exception) {
                            appendSystemNotice("[!] Failed to load private key: ${ke.message}")
                        }
                    }
                }
                session.setConfig("StrictHostKeyChecking", "no")
                session.setConfig("PreferredAuthentications", "password,publickey,keyboard-interactive")
                session.setServerAliveInterval(vm.sshConfig.keepAliveIntervalSec * 1000)
                session.setServerAliveCountMax(999)
                session.timeout = 10000

                session.connect(10000)
                jschSession = session

                val channel = session.openChannel("shell") as ChannelShell
                channel.setPtyType("xterm-256color")

                val outputStream = channel.outputStream
                val inputStream = channel.inputStream

                channel.connect(5000)
                shellChannel = channel
                shellOutputStream = outputStream

                setConnectionState(SSHConnectionState.CONNECTED)
                _statusMessage.value = "Connected"

                val tmuxName = tmuxSessionOverride ?: vm.sshConfig.tmuxSessionName
                if (vm.sshConfig.resilientProtocol == ResilientProtocol.TMUX_SSH) {
                    appendSystemNotice("[+] Attached to persistent tmux session: '$tmuxName'")
                    val attachCmd = "tmux new-session -A -s $tmuxName\n"
                    outputStream.write(attachCmd.toByteArray())
                    outputStream.flush()
                } else {
                    appendSystemNotice("[+] Interactive SSH2 shell established (Mosh unavailable, tmux disabled).")
                }

                startStreamReader(inputStream)

            } catch (e: Exception) {
                setConnectionState(SSHConnectionState.ERROR)
                _statusMessage.value = "Connection failed: ${e.message ?: "Host unreachable"}"
                appendSystemNotice("[!] Connection failed: ${e.message ?: "Host unreachable"}")
            }
        }
    }

    private fun startStreamReader(inputStream: InputStream) {
        readerJob?.cancel()
        readerJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(4096)
            try {
                while (isActive) {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead == -1) break
                    val chunk = String(buffer, 0, bytesRead, StandardCharsets.UTF_8)
                    appendRawOutput(chunk)
                }
            } catch (e: Exception) {
                appendSystemNotice("[!] Stream disconnected: ${e.message}")
            } finally {
                triggerAutoReconnect()
            }
        }
    }

    private fun checkConnectionHealth() {
        try {
            val session = jschSession
            if (session == null || !session.isConnected) {
                triggerAutoReconnect()
                return
            }
            session.sendKeepAliveMsg()
        } catch (e: Exception) {
            appendSystemNotice("[!] Keep-alive failed: ${e.message}")
            triggerAutoReconnect()
        }
    }

    private suspend fun pollTelemetryOverSSH() = withContext(Dispatchers.IO) {
        try {
            val session = jschSession
            if (session == null || !session.isConnected) return@withContext
            val execChannel = session.openChannel("exec") as ChannelExec
            execChannel.setCommand("cat /tmp/colab_metrics.json 2>/dev/null || true")
            val inStream = execChannel.inputStream
            execChannel.connect(3000)
            val jsonStr = inStream.bufferedReader().use { it.readText() }.trim()
            execChannel.disconnect()

            if (jsonStr.isNotBlank() && jsonStr.startsWith("{") && jsonStr.contains("ram_total_bytes")) {
                val metrics = TelemetryEngine.parseMetricsJson(jsonStr)
                onMetricsUpdated?.invoke(metrics)
            }
        } catch (_: Exception) {
            // Non-fatal telemetry polling exception
        }
    }

    private fun triggerAutoReconnect() {
        if (isUserIntentionallyDisconnected) return
        closeStreamsAndSession()
        setConnectionState(SSHConnectionState.RECONNECTING)
        _statusMessage.value = "Reconnecting..."
        appendSystemNotice("[~] Reconnecting to preserve session...")
    }

    private fun closeStreamsAndSession() {
        try {
            shellOutputStream?.close()
        } catch (_: Exception) {}
        shellOutputStream = null

        try {
            shellChannel?.disconnect()
        } catch (_: Exception) {}
        shellChannel = null

        try {
            jschSession?.disconnect()
        } catch (_: Exception) {}
        jschSession = null
    }

    private fun appendRawOutput(chunk: String) {
        val lines = chunk.split("\r\n", "\n")
        _terminalOutput.update { prev ->
            val next = prev.toMutableList()
            lines.forEach { line ->
                if (line.isNotEmpty()) {
                    next.add(TerminalLine(text = line, isSystemNotice = false))
                }
            }
            if (next.size > 2000) next.takeLast(1500) else next
        }
    }

    private fun appendSystemNotice(notice: String) {
        _terminalOutput.update { prev ->
            val next = prev.toMutableList()
            next.add(TerminalLine(text = notice, isSystemNotice = true))
            if (next.size > 2000) next.takeLast(1500) else next
        }
    }
}
