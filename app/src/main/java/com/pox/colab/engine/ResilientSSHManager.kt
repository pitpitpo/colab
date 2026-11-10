package com.pox.colab.engine

import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.pox.colab.model.ColabVM
import com.pox.colab.model.SSHAuthMethod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

data class TerminalLine(
    val id: Long = System.nanoTime(),
    val text: String,
    val isSystemNotice: Boolean = false
)

class ResilientSSHManager(
    private val vm: ColabVM,
    private val scope: CoroutineScope
) {
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
        appendSystemNotice("==================================================")
        appendSystemNotice(" Colab Resilient Terminal (${vm.sshConfig.resilientProtocol.name})")
        appendSystemNotice(" Target: ${vm.name} | Host: ${vm.sshConfig.host}:${vm.sshConfig.port}")
        appendSystemNotice(" Resilient Tmux Session: ${vm.sshConfig.tmuxSessionName}")
        appendSystemNotice("==================================================")
    }

    fun connect() {
        if (vm.sshConfig.host.isBlank() || vm.sshConfig.host == "localhost" && vm.tunnelUrl.isNullOrBlank()) {
            _connectionState.value = SSHConnectionState.DISCONNECTED
            _statusMessage.value = "Tunnel URL required to connect"
            appendSystemNotice("[!] No active tunnel host specified for VM '${vm.name}'.")
            appendSystemNotice("[!] Run the generated bootstrap script in Google Colab to obtain a live tunnel URL.")
            return
        }

        isUserIntentionallyDisconnected = false
        supervisorJob?.cancel()
        supervisorJob = scope.launch(Dispatchers.IO) {
            runConnectionSupervisor()
        }
    }

    fun disconnect() {
        isUserIntentionallyDisconnected = true
        supervisorJob?.cancel()
        readerJob?.cancel()
        closeStreamsAndSession()
        _connectionState.value = SSHConnectionState.DISCONNECTED
        _statusMessage.value = "Disconnected"
        appendSystemNotice("[!] Disconnected.")
    }

    fun forceReconnect() {
        appendSystemNotice("[*] Reconnecting...")
        isUserIntentionallyDisconnected = false
        scope.launch(Dispatchers.IO) {
            closeStreamsAndSession()
            _connectionState.value = SSHConnectionState.RECONNECTING
            attemptConnect()
        }
    }

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
            } catch (e: Exception) {
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
            } catch (e: Exception) {
                triggerAutoReconnect()
            }
        }
    }

    fun clearScreen() {
        _terminalOutput.value = emptyList()
    }

    private suspend fun runConnectionSupervisor() {
        while (scope.isActive && !isUserIntentionallyDisconnected) {
            if (_connectionState.value == SSHConnectionState.DISCONNECTED ||
                _connectionState.value == SSHConnectionState.RECONNECTING ||
                _connectionState.value == SSHConnectionState.ERROR
            ) {
                attemptConnect()
            }

            delay((vm.sshConfig.keepAliveIntervalSec * 1000L).coerceAtLeast(5000L))

            if (_connectionState.value == SSHConnectionState.CONNECTED) {
                checkConnectionHealth()
            }
        }
    }

    private suspend fun attemptConnect() {
        withContext(Dispatchers.IO) {
            try {
                _connectionState.value = SSHConnectionState.CONNECTING
                _statusMessage.value = "Connecting to ${vm.sshConfig.host}:${vm.sshConfig.port}..."
                appendSystemNotice("[*] Connecting to ${vm.sshConfig.host}:${vm.sshConfig.port}...")

                val session = jsch.getSession(vm.sshConfig.username, vm.sshConfig.host, vm.sshConfig.port)
                if (vm.sshConfig.authMethod == SSHAuthMethod.PASSWORD) {
                    session.setPassword(vm.sshConfig.password)
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

                _connectionState.value = SSHConnectionState.CONNECTED
                _statusMessage.value = "Connected"
                appendSystemNotice("[+] Attached to session: '${vm.sshConfig.tmuxSessionName}'")

                // Auto attach tmux session
                val attachCmd = "tmux new-session -A -s ${vm.sshConfig.tmuxSessionName}\n"
                outputStream.write(attachCmd.toByteArray())
                outputStream.flush()

                startStreamReader(inputStream)

            } catch (e: Exception) {
                _connectionState.value = SSHConnectionState.ERROR
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

    private fun triggerAutoReconnect() {
        if (isUserIntentionallyDisconnected) return
        closeStreamsAndSession()
        _connectionState.value = SSHConnectionState.RECONNECTING
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
        val current = _terminalOutput.value.toMutableList()
        lines.forEach { line ->
            if (line.isNotEmpty()) {
                current.add(TerminalLine(text = line, isSystemNotice = false))
            }
        }
        if (current.size > 2000) {
            _terminalOutput.value = current.takeLast(1500)
        } else {
            _terminalOutput.value = current
        }
    }

    private fun appendSystemNotice(notice: String) {
        val current = _terminalOutput.value.toMutableList()
        current.add(TerminalLine(text = notice, isSystemNotice = true))
        _terminalOutput.value = current
    }
}
