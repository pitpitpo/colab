package com.pox.colab.engine

import android.content.Context
import android.os.Environment
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.pox.colab.data.VMRepository
import com.pox.colab.model.ColabVM
import com.pox.colab.model.RemoteFile
import com.pox.colab.model.SSHAuthMethod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Vector
import java.util.concurrent.ConcurrentHashMap

class FileManagerEngine(
    private val context: Context,
    vm: ColabVM,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    @Volatile
    private var vm: ColabVM = vm

    private val _currentPath = MutableStateFlow("/content")
    val currentPath: StateFlow<String> = _currentPath.asStateFlow()

    private val _files = MutableStateFlow<List<RemoteFile>>(emptyList())
    val files: StateFlow<List<RemoteFile>> = _files.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _statusNotice = MutableStateFlow<String?>(null)
    val statusNotice: StateFlow<String?> = _statusNotice.asStateFlow()

    @Volatile private var sftpSession: Session? = null
    @Volatile private var sftpChannel: ChannelSftp? = null
    private val jsch = JSch()
    private val sftpMutex = Mutex()
    @Volatile private var refreshJob: Job? = null

    private val dirCache = ConcurrentHashMap<String, Pair<Long, List<RemoteFile>>>()

    init {
        refreshDirectory(_currentPath.value)
    }

    fun invalidateCache(path: String? = null) {
        if (path == null) {
            dirCache.clear()
        } else {
            dirCache.remove(normalizePath(path))
        }
    }

    private fun putInCache(path: String, entries: List<RemoteFile>) {
        if (dirCache.size >= MAX_CACHED_DIRS && !dirCache.containsKey(path)) {
            val oldestKey = dirCache.entries.minByOrNull { it.value.first }?.key
            if (oldestKey != null) dirCache.remove(oldestKey)
        }
        dirCache[path] = Pair(System.currentTimeMillis(), entries)
    }

    fun resolveHostAndPort(): Pair<String, Int> {
        val latestVm = try { com.pox.colab.ColabApp.instance.repository.getVMById(vm.id) ?: vm } catch (_: Exception) { vm }
        vm = latestVm
        val raw: String = if (VMRepository.isValidTunnelEndpoint(latestVm.tunnelUrl)) {
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

    fun hasDirectRuntime(): Boolean {
        val repo = try { com.pox.colab.ColabApp.instance.repository } catch (_: Exception) { null }
        val rt = repo?.getActiveRuntime(vm.id)
        return rt != null && rt.endpointUrl.isNotBlank()
    }

    private suspend fun <T> withSftp(block: (ChannelSftp) -> T): T = sftpMutex.withLock {
        val sftp = getConnectedSftpLocked()
        try {
            block(sftp)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // If channel dropped mid-operation, clear cached handle so next call reconnects cleanly
            if (sftpChannel?.isConnected != true || sftpSession?.isConnected != true) {
                disconnectSftp()
            }
            throw e
        }
    }

    private suspend fun getConnectedSftpLocked(): ChannelSftp = withContext(Dispatchers.IO) {
        val existingChannel = sftpChannel
        val existingSession = sftpSession
        if (existingChannel != null && existingChannel.isConnected && existingSession != null && existingSession.isConnected) {
            return@withContext existingChannel
        }

        disconnectSftp()

        var (host, port) = resolveHostAndPort()
        if (host.isBlank()) {
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
                val (newHost, newPort) = VMRepository.parseEndpoint(ep)
                vm = vm.copy(
                    tunnelUrl = ep,
                    sshConfig = vm.sshConfig.copy(host = newHost, port = newPort),
                    status = com.pox.colab.model.VMStatus.RUNNING
                )
                try {
                    com.pox.colab.ColabApp.instance.repository.updateTunnelEndpoint(vm.id, ep)
                } catch (_: Exception) {}
                host = newHost
                port = newPort
            }
        }
        if (host.isBlank()) {
            throw IllegalStateException("Tunnel host not yet announced. Please run the bootstrap script in Colab.")
        }

        var session: Session? = null
        var channel: ChannelSftp? = null
        try {
            val currentVm = vm
            jsch.removeAllIdentity()
            if (currentVm.sshConfig.authMethod == SSHAuthMethod.PUBLIC_KEY) {
                val key = currentVm.sshConfig.privateKey
                if (!key.isNullOrBlank()) {
                    try {
                        jsch.addIdentity("sftp_${currentVm.id}_key", key.toByteArray(Charsets.UTF_8), null, null)
                    } catch (_: Exception) {}
                }
            }

            session = jsch.getSession(currentVm.sshConfig.username, host, port).apply {
                if (currentVm.sshConfig.authMethod == SSHAuthMethod.PASSWORD) {
                    setPassword(currentVm.sshConfig.password)
                }
                setConfig("StrictHostKeyChecking", "no")
                setConfig("PreferredAuthentications", "password,publickey,keyboard-interactive")
                setServerAliveInterval(10_000)
                timeout = 10_000
                connect(10_000)
            }

            channel = (session.openChannel("sftp") as ChannelSftp).apply {
                connect(10_000)
            }

            sftpSession = session
            sftpChannel = channel
            channel
        } catch (e: Exception) {
            try { channel?.disconnect() } catch (_: Exception) {}
            try { session?.disconnect() } catch (_: Exception) {}
            throw e
        }
    }

    fun navigateTo(path: String) {
        val normalized = normalizePath(path)
        _currentPath.value = normalized
        refreshDirectory(normalized)
    }

    fun navigateUp() {
        val current = _currentPath.value
        if (current == "/" || current.isEmpty()) return
        val parent = current.substringBeforeLast('/', "")
        navigateTo(if (parent.isEmpty()) "/" else parent)
    }

    fun refreshDirectory(path: String = _currentPath.value, forceRefresh: Boolean = false) {
        val targetPath = normalizePath(path)
        val cached = dirCache[targetPath]
        val now = System.currentTimeMillis()
        if (!forceRefresh && cached != null && (now - cached.first) < CACHE_TTL_MS) {
            if (_currentPath.value == targetPath) {
                _files.value = cached.second
                _statusNotice.value = null
                _isLoading.value = false
            }
            return
        }

        // Cancel any superseded directory listing so slow responses cannot overwrite newer navigation
        refreshJob?.cancel()
        _isLoading.value = true
        refreshJob = scope.launch(Dispatchers.IO) {
            try {
                val repository = try { com.pox.colab.ColabApp.instance.repository } catch (_: Exception) { null }
                val runtime = repository?.getActiveRuntime(vm.id) ?: repository?.resolveActiveRuntime(vm.id)

                if (runtime != null && runtime.endpointUrl.isNotBlank()) {
                    val result = ColabApiEngine.getInstance().listFiles(runtime, targetPath)
                    if (result.isSuccess) {
                        val list = result.getOrNull() ?: emptyList()
                        putInCache(targetPath, list)
                        if (_currentPath.value == targetPath) {
                            _files.value = list
                            _statusNotice.value = null
                        }
                        return@launch
                    }
                }

                val (host, _) = resolveHostAndPort()
                if (host.isBlank()) {
                    if (_currentPath.value == targetPath) {
                        _statusNotice.value = "Remote Disconnected. Tunnel endpoint or Colab runtime not active."
                        if (cached == null) _files.value = emptyList()
                    }
                    return@launch
                }

                val sorted = withSftp { sftp ->
                    @Suppress("UNCHECKED_CAST")
                    val vector = sftp.ls(targetPath) as Vector<ChannelSftp.LsEntry>
                    val resultList = ArrayList<RemoteFile>(vector.size)

                    for (entry in vector) {
                        val name = entry.filename
                        if (name == "." || name == "..") continue
                        val fullPath = if (targetPath.endsWith("/")) "$targetPath$name" else "$targetPath/$name"
                        resultList.add(
                            RemoteFile(
                                name = name,
                                path = fullPath,
                                isDirectory = entry.attrs.isDir,
                                sizeBytes = entry.attrs.size,
                                modifiedTimestamp = entry.attrs.mTime.toLong() * 1000L
                            )
                        )
                    }
                    resultList.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                }

                putInCache(targetPath, sorted)
                if (_currentPath.value == targetPath) {
                    _files.value = sorted
                    _statusNotice.value = null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_currentPath.value == targetPath) {
                    if (cached == null) {
                        _files.value = emptyList()
                    }
                    _statusNotice.value = "Filesystem error: ${e.message ?: "Failed to list directory"}"
                }
            } finally {
                if (_currentPath.value == targetPath) {
                    _isLoading.value = false
                }
            }
        }
    }

    suspend fun readFileContent(file: RemoteFile): String = withContext(Dispatchers.IO) {
        val repository = try { com.pox.colab.ColabApp.instance.repository } catch (_: Exception) { null }
        val runtime = repository?.getActiveRuntime(vm.id) ?: repository?.resolveActiveRuntime(vm.id)
        if (runtime != null && runtime.endpointUrl.isNotBlank()) {
            val res = ColabApiEngine.getInstance().readFile(runtime, file.path)
            if (res.isSuccess) {
                return@withContext res.getOrNull()?.ifEmpty { "[Empty File]" } ?: "[Empty File]"
            }
        }

        try {
            val bytes = withSftp { sftp ->
                val out = ByteArrayOutputStream()
                sftp.get(file.path).use { inStream ->
                    val buffer = ByteArray(16 * 1024)
                    var read: Int
                    var total = 0
                    while (inStream.read(buffer).also { read = it } != -1) {
                        val toWrite = minOf(read, MAX_PREVIEW_BYTES - total)
                        if (toWrite > 0) {
                            out.write(buffer, 0, toWrite)
                            total += toWrite
                        }
                        if (total >= MAX_PREVIEW_BYTES) break
                    }
                }
                out.toByteArray()
            }
            if (bytes.isEmpty()) return@withContext "[Empty File]"
            bytes.toString(Charsets.UTF_8)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Error loading remote file: ${e.message}"
        }
    }

    suspend fun downloadFileToDevice(file: RemoteFile): Result<File> = withContext(Dispatchers.IO) {
        val safeName = File(file.name).name.ifBlank { "download" }
        val downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        val localFile = File(downloadDir, safeName)

        val repository = try { com.pox.colab.ColabApp.instance.repository } catch (_: Exception) { null }
        val runtime = repository?.getActiveRuntime(vm.id) ?: repository?.resolveActiveRuntime(vm.id)
        if (runtime != null && runtime.endpointUrl.isNotBlank()) {
            val res = ColabApiEngine.getInstance().readFile(runtime, file.path)
            if (res.isSuccess) {
                val content = res.getOrNull() ?: ""
                localFile.writeText(content, Charsets.UTF_8)
                _statusNotice.value = "Downloaded to: ${localFile.name}"
                return@withContext Result.success(localFile)
            }
        }

        try {
            withSftp { sftp ->
                FileOutputStream(localFile).use { outStream ->
                    sftp.get(file.path, outStream)
                }
            }
            _statusNotice.value = "Downloaded to: ${localFile.name}"
            Result.success(localFile)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _statusNotice.value = "Download failed: ${e.message}"
            Result.failure(e)
        }
    }

    suspend fun uploadFile(name: String, content: String): Result<RemoteFile> = withContext(Dispatchers.IO) {
        val current = _currentPath.value
        val targetPath = if (current.endsWith("/")) "$current$name" else "$current/$name"
        val bytes = content.toByteArray(Charsets.UTF_8)

        val repository = try { com.pox.colab.ColabApp.instance.repository } catch (_: Exception) { null }
        val runtime = repository?.getActiveRuntime(vm.id) ?: repository?.resolveActiveRuntime(vm.id)
        if (runtime != null && runtime.endpointUrl.isNotBlank()) {
            val res = ColabApiEngine.getInstance().writeFile(runtime, targetPath, content)
            if (res.isSuccess) {
                invalidateCache(current)
                refreshDirectory(current, forceRefresh = true)
                _statusNotice.value = "Saved: $name"
                return@withContext Result.success(
                    RemoteFile(
                        name = name,
                        path = targetPath,
                        isDirectory = false,
                        sizeBytes = bytes.size.toLong(),
                        modifiedTimestamp = System.currentTimeMillis()
                    )
                )
            }
        }

        try {
            withSftp { sftp ->
                ByteArrayInputStream(bytes).use { inStream ->
                    sftp.put(inStream, targetPath)
                }
            }
            invalidateCache(current)
            refreshDirectory(current, forceRefresh = true)
            _statusNotice.value = "Uploaded: $name"
            Result.success(
                RemoteFile(
                    name = name,
                    path = targetPath,
                    isDirectory = false,
                    sizeBytes = bytes.size.toLong(),
                    modifiedTimestamp = System.currentTimeMillis()
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _statusNotice.value = "Upload failed: ${e.message}"
            Result.failure(e)
        }
    }

    fun createFolder(name: String) {
        scope.launch(Dispatchers.IO) {
            val current = _currentPath.value
            val repository = try { com.pox.colab.ColabApp.instance.repository } catch (_: Exception) { null }
            val runtime = repository?.getActiveRuntime(vm.id) ?: repository?.resolveActiveRuntime(vm.id)
            if (runtime != null && runtime.endpointUrl.isNotBlank()) {
                val res = ColabApiEngine.getInstance().createDirectory(runtime, current, name)
                if (res.isSuccess) {
                    invalidateCache(current)
                    refreshDirectory(current, forceRefresh = true)
                    _statusNotice.value = "Folder created: $name"
                    return@launch
                }
            }

            try {
                val targetPath = if (current.endsWith("/")) "$current$name" else "$current/$name"
                withSftp { sftp -> sftp.mkdir(targetPath) }
                invalidateCache(current)
                refreshDirectory(current, forceRefresh = true)
                _statusNotice.value = "Folder created: $name"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _statusNotice.value = "Create folder failed: ${e.message}"
            }
        }
    }

    fun deleteFile(file: RemoteFile) {
        scope.launch(Dispatchers.IO) {
            val current = _currentPath.value
            val repository = try { com.pox.colab.ColabApp.instance.repository } catch (_: Exception) { null }
            val runtime = repository?.getActiveRuntime(vm.id) ?: repository?.resolveActiveRuntime(vm.id)
            if (runtime != null && runtime.endpointUrl.isNotBlank()) {
                val res = ColabApiEngine.getInstance().deleteFile(runtime, file.path)
                if (res.isSuccess) {
                    invalidateCache(current)
                    invalidateCache(file.path)
                    refreshDirectory(current, forceRefresh = true)
                    _statusNotice.value = "Deleted: ${file.name}"
                    return@launch
                }
            }

            try {
                withSftp { sftp ->
                    if (file.isDirectory) {
                        deleteDirectoryRecursive(sftp, file.path)
                    } else {
                        sftp.rm(file.path)
                    }
                }
                invalidateCache(current)
                invalidateCache(file.path)
                refreshDirectory(current, forceRefresh = true)
                _statusNotice.value = "Deleted: ${file.name}"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _statusNotice.value = "Delete failed: ${e.message}"
            }
        }
    }

    private fun deleteDirectoryRecursive(sftp: ChannelSftp, dirPath: String) {
        @Suppress("UNCHECKED_CAST")
        val entries = sftp.ls(dirPath) as Vector<ChannelSftp.LsEntry>
        for (entry in entries) {
            val name = entry.filename
            if (name == "." || name == "..") continue
            val childPath = if (dirPath.endsWith("/")) "$dirPath$name" else "$dirPath/$name"
            if (entry.attrs.isDir) {
                deleteDirectoryRecursive(sftp, childPath)
            } else {
                sftp.rm(childPath)
            }
        }
        sftp.rmdir(dirPath)
    }

    fun renameFile(file: RemoteFile, newName: String) {
        scope.launch(Dispatchers.IO) {
            val current = _currentPath.value
            val repository = try { com.pox.colab.ColabApp.instance.repository } catch (_: Exception) { null }
            val runtime = repository?.getActiveRuntime(vm.id) ?: repository?.resolveActiveRuntime(vm.id)
            if (runtime != null && runtime.endpointUrl.isNotBlank()) {
                val res = ColabApiEngine.getInstance().renameFile(runtime, file.path, newName)
                if (res.isSuccess) {
                    invalidateCache(current)
                    refreshDirectory(current, forceRefresh = true)
                    _statusNotice.value = "Renamed to: $newName"
                    return@launch
                }
            }

            try {
                val parent = file.path.substringBeforeLast('/', "")
                val targetPath = if (parent.isEmpty()) "/$newName" else "$parent/$newName"
                withSftp { sftp -> sftp.rename(file.path, targetPath) }
                invalidateCache(current)
                refreshDirectory(current, forceRefresh = true)
                _statusNotice.value = "Renamed to: $newName"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _statusNotice.value = "Rename failed: ${e.message}"
            }
        }
    }

    fun clearStatusNotice() {
        _statusNotice.value = null
    }

    /**
     * Drops the cached SFTP channel/session so the next operation reconnects, without
     * cancelling [scope]. Safe to call from inside a coroutine on that scope.
     */
    private fun disconnectSftp() {
        try { sftpChannel?.disconnect() } catch (_: Exception) {}
        sftpChannel = null
        try { sftpSession?.disconnect() } catch (_: Exception) {}
        sftpSession = null
    }

    fun close() {
        refreshJob?.cancel()
        refreshJob = null
        disconnectSftp()
        try {
            scope.cancel()
        } catch (_: Exception) {}
    }

    private fun normalizePath(path: String): String {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return "/"
        val normalized = if (!trimmed.startsWith("/")) "/$trimmed" else trimmed
        return if (normalized.length > 1 && normalized.endsWith("/")) {
            normalized.dropLast(1)
        } else {
            normalized
        }
    }

    companion object {
        private const val CACHE_TTL_MS = 30_000L
        private const val MAX_CACHED_DIRS = 50
        private const val MAX_PREVIEW_BYTES = 1024 * 1024 // 1 MB preview limit
    }
}
