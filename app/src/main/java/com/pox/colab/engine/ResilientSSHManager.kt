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
import kotlinx.coroutines.CancellationException
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class SSHConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    ERROR
}

private val globalTerminalLineSeq = AtomicLong(1L)

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
    @Volatile
    var vm: ColabVM = vm
        private set

    private val _connectionState = MutableStateFlow(SSHConnectionState.DISCONNECTED)
    val connectionState: StateFlow<SSHConnectionState> = _connectionState.asStateFlow()

    private val _terminalOutput = MutableStateFlow<List<TerminalLine>>(emptyList())
    val terminalOutput: StateFlow<List<TerminalLine>> = _terminalOutput.asStateFlow()

    private val _statusMessage = MutableStateFlow("Disconnected")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    @Volatile private var jschSession: Session? = null
    @Volatile private var shellChannel: ChannelShell? = null
    @Volatile private var shellOutputStream: OutputStream? = null
    @Volatile private var readerJob: Job? = null
    @Volatile private var supervisorJob: Job? = null
    @Volatile private var isUserIntentionallyDisconnected = false

    private val jsch = JSch()
    private val sessionLock = Any()
    private val writeLock = Any()

    // High-throughput frame-paced terminal line ring buffer
    private val outputLock = Any()
    private val lineBuffer = ArrayDeque<TerminalLine>(MAX_TERMINAL_LINES + 64)
    private val partialLine = StringBuilder()
    private val flushScheduled = AtomicBoolean(false)
    @Volatile private var flushJob: Job? = null

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
        val latestVm = try {
            com.pox.colab.ColabApp.instance.repository.getVMById(vm.id) ?: vm
        } catch (_: Exception) {
            vm
        }
        vm = latestVm
        val raw = if (VMRepository.isValidTunnelEndpoint(latestVm.tunnelUrl)) {
            latestVm.tunnelUrl ?: ""
        } else if (latestVm.sshConfig.host.isNotBlank() &&
            latestVm.sshConfig.host != "localhost" &&
            VMRepository.isValidTunnelEndpoint(latestVm.sshConfig.host)
        ) {
            "${latestVm.sshConfig.host}:${latestVm.sshConfig.port}"
        } else {
            ""
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
                    val repo = try { com.pox.colab.ColabApp.instance.repository } catch (_: Exception) { null }
                    val repoEp = repo?.getVMById(vm.id)?.tunnelUrl?.takeIf { VMRepository.isValidTunnelEndpoint(it) }
                    val runtime = if (repoEp == null) {
                        repo?.getActiveRuntime(vm.id) ?: repo?.resolveActiveRuntime(vm.id)
                    } else null
                    val fileEp = if (repoEp == null && runtime != null && runtime.endpointUrl.isNotBlank()) {
                        try {
                            ColabApiEngine.getInstance().readFile(runtime, "tmp/colab_tunnel_endpoint.txt")
                                .getOrNull()?.trim()?.takeIf { VMRepository.isValidTunnelEndpoint(it) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) { null }
                    } else null
                    val ntfyEp = if (fileEp == null && repoEp == null) {
                        TunnelDiscoveryEngine.pollDiscoveredEndpoint(vm.id)?.takeIf { VMRepository.isValidTunnelEndpoint(it) }
                    } else null
                    val ep = repoEp ?: fileEp ?: ntfyEp
                    if (!ep.isNullOrBlank()) {
                        discoveredEndpoint = ep
                        break
                    }
                    delay(if (attempt <= 20) 1500L else 3000L)
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
        supervisorJob = null
        readerJob?.cancel()
        readerJob = null
        flushJob?.cancel()
        flushJob = null
        closeStreamsAndSession()
        setConnectionState(SSHConnectionState.DISCONNECTED)
        _statusMessage.value = "Disconnected"
        appendSystemNotice("[!] Disconnected.")
    }

    fun forceReconnect() {
        appendSystemNotice("[*] Reconnecting...")
        isUserIntentionallyDisconnected = false
        supervisorJob?.cancel()
        readerJob?.cancel()
        closeStreamsAndSession()
        setConnectionState(SSHConnectionState.RECONNECTING)
        supervisorJob = scope.launch(Dispatchers.IO) {
            runConnectionSupervisor()
        }
    }

    fun sendCommand(input: String) = sendInput(input)

    fun sendInput(input: String) {
        scope.launch(Dispatchers.IO) {
            writeBytesToShell(input.toByteArray(StandardCharsets.UTF_8), notifyIfDisconnected = true)
        }
    }

    fun sendCtrlC() {
        scope.launch(Dispatchers.IO) {
            writeBytesToShell(byteArrayOf(3), notifyIfDisconnected = false)
        }
    }

    fun sendSpecialKey(key: String) {
        scope.launch(Dispatchers.IO) {
            val bytes = when (key) {
                "TAB" -> byteArrayOf(9)
                "ESC" -> byteArrayOf(27)
                "UP" -> byteArrayOf(27, 91, 65)
                "DOWN" -> byteArrayOf(27, 91, 66)
                "RIGHT" -> byteArrayOf(27, 91, 67)
                "LEFT" -> byteArrayOf(27, 91, 68)
                "ENTER" -> byteArrayOf(13)
                else -> key.toByteArray(StandardCharsets.UTF_8)
            }
            writeBytesToShell(bytes, notifyIfDisconnected = false)
        }
    }

    private fun writeBytesToShell(bytes: ByteArray, notifyIfDisconnected: Boolean) {
        try {
            synchronized(writeLock) {
                val out = shellOutputStream
                if (out == null) {
                    if (notifyIfDisconnected) {
                        appendSystemNotice("[!] Not connected to remote host.")
                    }
                    return
                }
                out.write(bytes)
                out.flush()
            }
        } catch (e: Exception) {
            if (notifyIfDisconnected) {
                appendSystemNotice("[!] Transmission error: ${e.message}")
            }
            triggerAutoReconnect()
        }
    }

    fun clearOutput() = clearScreen()

    fun clearScreen() {
        synchronized(outputLock) {
            lineBuffer.clear()
            partialLine.clear()
        }
        _terminalOutput.value = emptyList()
    }

    private suspend fun runConnectionSupervisor() {
        var cycle = 0
        var consecutiveFailures = 0
        while (scope.isActive && !isUserIntentionallyDisconnected) {
            val state = _connectionState.value
            if (state == SSHConnectionState.DISCONNECTED ||
                state == SSHConnectionState.RECONNECTING ||
                state == SSHConnectionState.ERROR
            ) {
                val connected = attemptConnect()
                if (connected) {
                    consecutiveFailures = 0
                    cycle = 0
                } else {
                    consecutiveFailures++
                }
            }

            val isConn = _connectionState.value == SSHConnectionState.CONNECTED
            val sleepMs = if (isConn) {
                (vm.sshConfig.keepAliveIntervalSec * 1000L).coerceAtLeast(3000L)
            } else {
                // Exponential backoff: 2s -> 4s -> 8s -> max 15s
                (2000L shl (consecutiveFailures - 1).coerceIn(0, 3)).coerceAtMost(15_000L)
            }
            delay(sleepMs)

            if (_connectionState.value == SSHConnectionState.CONNECTED) {
                checkConnectionHealth()
                cycle++
                if (cycle % 2 == 0) {
                    pollTelemetryOverSSH()
                }
            }
        }
    }

    private fun setConnectionState(newState: SSHConnectionState) {
        if (_connectionState.value != newState) {
            _connectionState.value = newState
            onConnectionStateChanged?.invoke(newState)
        }
    }

    /**
     * Opens a new JSch session and interactive PTY shell channel, guaranteeing clean teardown
     * if either handshake fails.
     */
    private fun openShellSession(targetHost: String, targetPort: Int): InputStream {
        var session: Session? = null
        var channel: ChannelShell? = null
        try {
            val currentVm = vm
            synchronized(sessionLock) {
                jsch.removeAllIdentity()
                if (currentVm.sshConfig.authMethod == SSHAuthMethod.PUBLIC_KEY) {
                    val key = currentVm.sshConfig.privateKey
                    if (!key.isNullOrBlank()) {
                        try {
                            jsch.addIdentity("vm_${currentVm.id}_key", key.toByteArray(StandardCharsets.UTF_8), null, null)
                        } catch (ke: Exception) {
                            appendSystemNotice("[!] Failed to load private key: ${ke.message}")
                        }
                    }
                }
            }

            session = jsch.getSession(currentVm.sshConfig.username, targetHost, targetPort).apply {
                if (currentVm.sshConfig.authMethod == SSHAuthMethod.PASSWORD) {
                    setPassword(currentVm.sshConfig.password)
                }
                setConfig("StrictHostKeyChecking", "no")
                setConfig("PreferredAuthentications", "password,publickey,keyboard-interactive")
                setConfig("compression.s2c", "none")
                setConfig("compression.c2s", "none")
                setServerAliveInterval(5000)
                setServerAliveCountMax(3)
                timeout = 10_000
                connect(10_000)
            }

            channel = (session.openChannel("shell") as ChannelShell).apply {
                setPtyType("xterm-256color")
            }

            val outputStream = channel.outputStream
            val inputStream = channel.inputStream
            channel.connect(5_000)

            synchronized(sessionLock) {
                jschSession = session
                shellChannel = channel
                shellOutputStream = outputStream
            }

            setConnectionState(SSHConnectionState.CONNECTED)
            _statusMessage.value = "Connected"

            val tmuxName = tmuxSessionOverride ?: currentVm.sshConfig.tmuxSessionName
            if (currentVm.sshConfig.resilientProtocol == ResilientProtocol.TMUX_SSH) {
                appendSystemNotice("[+] Attached to persistent tmux session: '$tmuxName'")
                val attachCmd = "tmux new-session -A -s $tmuxName\n"
                synchronized(writeLock) {
                    outputStream.write(attachCmd.toByteArray(StandardCharsets.UTF_8))
                    outputStream.flush()
                }
            } else {
                appendSystemNotice("[+] Interactive SSH2 shell established (Mosh unavailable, tmux disabled).")
            }

            return inputStream
        } catch (e: Exception) {
            try { channel?.disconnect() } catch (_: Exception) {}
            try { session?.disconnect() } catch (_: Exception) {}
            throw e
        }
    }

    private suspend fun attemptConnect(): Boolean = withContext(Dispatchers.IO) {
        val (targetHost, targetPort) = resolveHostAndPort()
        if (targetHost.isBlank()) {
            setConnectionState(SSHConnectionState.DISCONNECTED)
            _statusMessage.value = "Tunnel endpoint required"
            return@withContext false
        }

        try {
            setConnectionState(SSHConnectionState.CONNECTING)
            _statusMessage.value = "Connecting to $targetHost:$targetPort..."
            appendSystemNotice("[*] Connecting to $targetHost:$targetPort...")

            val inputStream = openShellSession(targetHost, targetPort)
            startStreamReader(inputStream)
            return@withContext true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Autonomous dynamic tunnel re-synchronization via Colab runtime metrics
            try {
                val repo = com.pox.colab.ColabApp.instance.repository
                val runtime = repo.getActiveRuntime(vm.id) ?: repo.resolveActiveRuntime(vm.id)
                if (runtime != null && runtime.endpointUrl.isNotBlank()) {
                    val metrics = ColabApiEngine.getInstance().fetchMetrics(runtime).getOrNull()
                    val freshEndpoint = metrics?.tunnelEndpoint
                    if (!freshEndpoint.isNullOrBlank() &&
                        VMRepository.isValidTunnelEndpoint(freshEndpoint) &&
                        freshEndpoint != vm.tunnelUrl
                    ) {
                        appendSystemNotice("[~] Dynamic tunnel change detected: $freshEndpoint")
                        repo.updateTunnelEndpoint(vm.id, freshEndpoint)
                        val (newH, newP) = resolveHostAndPort()
                        if (newH.isNotBlank() && (newH != targetHost || newP != targetPort)) {
                            appendSystemNotice("[*] Retrying with updated endpoint $newH:$newP...")
                            val retryIn = openShellSession(newH, newP)
                            startStreamReader(retryIn)
                            return@withContext true
                        }
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {}

            setConnectionState(SSHConnectionState.ERROR)
            _statusMessage.value = "Connection failed: ${e.message ?: "Host unreachable"}"
            appendSystemNotice("[!] Connection failed: ${e.message ?: "Host unreachable"}")
            return@withContext false
        }
    }

    private fun startStreamReader(inputStream: InputStream) {
        readerJob?.cancel()
        readerJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(8192)
            var disconnectedUnexpectedly = false
            try {
                while (isActive) {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead == -1) {
                        disconnectedUnexpectedly = !isUserIntentionallyDisconnected
                        break
                    }
                    val chunk = String(buffer, 0, bytesRead, StandardCharsets.UTF_8)
                    appendRawOutput(chunk)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isUserIntentionallyDisconnected) {
                    disconnectedUnexpectedly = true
                    appendSystemNotice("[!] Stream disconnected: ${e.message}")
                }
            } finally {
                if (disconnectedUnexpectedly && !isUserIntentionallyDisconnected) {
                    triggerAutoReconnect()
                }
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
        var execChannel: ChannelExec? = null
        try {
            val session = jschSession
            if (session == null || !session.isConnected) return@withContext
            execChannel = session.openChannel("exec") as ChannelExec
            execChannel.setCommand("cat /tmp/colab_metrics.json 2>/dev/null || true")
            val inStream = execChannel.inputStream
            execChannel.connect(3000)
            val jsonStr = inStream.bufferedReader().use { it.readText() }.trim()

            if (jsonStr.isNotBlank() && jsonStr.startsWith("{") && jsonStr.contains("ram_total_bytes")) {
                val metrics = TelemetryEngine.parseMetricsJson(jsonStr)
                onMetricsUpdated?.invoke(metrics)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Non-fatal telemetry polling exception
        } finally {
            try { execChannel?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun triggerAutoReconnect() {
        if (isUserIntentionallyDisconnected) return
        if (_connectionState.value == SSHConnectionState.RECONNECTING) return
        closeStreamsAndSession()
        setConnectionState(SSHConnectionState.RECONNECTING)
        _statusMessage.value = "Reconnecting..."
        appendSystemNotice("[~] Reconnecting to preserve session...")
    }

    private fun closeStreamsAndSession() {
        synchronized(sessionLock) {
            synchronized(writeLock) {
                try { shellOutputStream?.close() } catch (_: Exception) {}
                shellOutputStream = null
            }
            try { shellChannel?.disconnect() } catch (_: Exception) {}
            shellChannel = null
            try { jschSession?.disconnect() } catch (_: Exception) {}
            jschSession = null
        }
    }

    internal fun appendRawOutput(chunk: String) {
        if (chunk.isEmpty()) return
        synchronized(outputLock) {
            val normalized = chunk.replace("\r\n", "\n")
            var start = 0
            while (start < normalized.length) {
                val nlIdx = normalized.indexOf('\n', start)
                if (nlIdx == -1) {
                    // Partial trailing text without newline: hold in partialLine and show immediately
                    partialLine.append(normalized, start, normalized.length)
                    break
                } else {
                    partialLine.append(normalized, start, nlIdx)
                    val completed = partialLine.toString().replace("\r", "")
                    partialLine.setLength(0)
                    if (completed.isNotEmpty()) {
                        pushLineLocked(TerminalLine(text = completed, isSystemNotice = false))
                    }
                    start = nlIdx + 1
                }
            }
        }
        scheduleFlush()
    }

    private fun appendSystemNotice(notice: String) {
        synchronized(outputLock) {
            if (partialLine.isNotEmpty()) {
                pushLineLocked(TerminalLine(text = partialLine.toString().replace("\r", ""), isSystemNotice = false))
                partialLine.setLength(0)
            }
            pushLineLocked(TerminalLine(text = notice, isSystemNotice = true))
        }
        flushOutputNow()
    }

    private fun pushLineLocked(line: TerminalLine) {
        lineBuffer.addLast(line)
        while (lineBuffer.size > MAX_TERMINAL_LINES) {
            lineBuffer.removeFirst()
        }
    }

    private fun scheduleFlush() {
        if (flushScheduled.compareAndSet(false, true)) {
            flushJob = scope.launch(Dispatchers.Default) {
                delay(FLUSH_INTERVAL_MS)
                flushScheduled.set(false)
                flushOutputNow()
            }
        }
    }

    private fun flushOutputNow() {
        val snapshot = synchronized(outputLock) {
            val list = ArrayList<TerminalLine>(lineBuffer.size + 1)
            list.addAll(lineBuffer)
            if (partialLine.isNotEmpty()) {
                val prompt = partialLine.toString().replace("\r", "")
                if (prompt.isNotEmpty()) {
                    list.add(TerminalLine(id = PARTIAL_LINE_ID, text = prompt, isSystemNotice = false))
                }
            }
            list
        }
        _terminalOutput.value = snapshot
    }

    companion object {
        internal const val MAX_TERMINAL_LINES = 1500
        private const val FLUSH_INTERVAL_MS = 16L
        private const val PARTIAL_LINE_ID = -1L
    }
}
