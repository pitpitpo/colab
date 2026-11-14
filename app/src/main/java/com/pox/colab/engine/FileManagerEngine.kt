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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Vector

import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class FileManagerEngine(
    private val context: Context,
    vm: ColabVM,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    private var vm: ColabVM = vm
    private val _currentPath = MutableStateFlow("/content")
    val currentPath: StateFlow<String> = _currentPath.asStateFlow()

    private val _files = MutableStateFlow<List<RemoteFile>>(emptyList())
    val files: StateFlow<List<RemoteFile>> = _files.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _statusNotice = MutableStateFlow<String?>(null)
    val statusNotice: StateFlow<String?> = _statusNotice.asStateFlow()

    private var sftpSession: Session? = null
    private var sftpChannel: ChannelSftp? = null
    private val jsch = JSch()

    private val dirCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<RemoteFile>>>()
    private val CACHE_TTL_MS = 30_000L

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

    fun resolveHostAndPort(): Pair<String, Int> {
        val latestVm = try { com.pox.colab.ColabApp.instance.repository.getVMById(vm.id) ?: vm } catch (_: Exception) { vm }
        vm = latestVm
        val raw: String = if (!latestVm.tunnelUrl.isNullOrBlank()) {
            latestVm.tunnelUrl
        } else if (latestVm.sshConfig.host.isNotBlank() && latestVm.sshConfig.host != "localhost") {
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

    private suspend fun getConnectedSftp(): ChannelSftp = withContext(Dispatchers.IO) {
        val existingChannel = sftpChannel
        val existingSession = sftpSession
        if (existingChannel != null && existingChannel.isConnected && existingSession != null && existingSession.isConnected) {
            return@withContext existingChannel
        }

        disconnectSftp()

        var (host, port) = resolveHostAndPort()
        if (host.isBlank()) {
            val ep = TunnelDiscoveryEngine.pollDiscoveredEndpoint(vm.id)
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

        val session = jsch.getSession(vm.sshConfig.username, host, port)
        if (vm.sshConfig.authMethod == SSHAuthMethod.PASSWORD) {
            session.setPassword(vm.sshConfig.password)
        } else if (vm.sshConfig.authMethod == SSHAuthMethod.PUBLIC_KEY) {
            val key = vm.sshConfig.privateKey
            if (!key.isNullOrBlank()) {
                try {
                    jsch.addIdentity("sftp_${vm.id}_key", key.toByteArray(Charsets.UTF_8), null, null)
                } catch (_: Exception) {}
            }
        }
        session.setConfig("StrictHostKeyChecking", "no")
        session.setConfig("PreferredAuthentications", "password,publickey,keyboard-interactive")
        session.timeout = 10000
        session.connect(10000)

        val channel = session.openChannel("sftp") as ChannelSftp
        channel.connect(10000)

        sftpSession = session
        sftpChannel = channel
        channel
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
            _files.value = cached.second
            _statusNotice.value = null
            _isLoading.value = false
            return
        }

        _isLoading.value = true
        scope.launch(Dispatchers.IO) {
            try {
                val repository = try { com.pox.colab.ColabApp.instance.repository } catch (_: Exception) { null }
                val runtime = repository?.getActiveRuntime(vm.id) ?: repository?.resolveActiveRuntime(vm.id)

                if (runtime != null && runtime.endpointUrl.isNotBlank()) {
                    val result = ColabApiEngine.getInstance().listFiles(runtime, targetPath)
                    if (result.isSuccess) {
                        val list = result.getOrNull() ?: emptyList()
                        dirCache[targetPath] = Pair(System.currentTimeMillis(), list)
                        _files.value = list
                        _statusNotice.value = null
                        _isLoading.value = false
                        return@launch
                    }
                }

                val (host, _) = resolveHostAndPort()
                if (host.isBlank()) {
                    _statusNotice.value = "Remote Disconnected. Tunnel endpoint or Colab runtime not active."
                    if (cached == null) _files.value = emptyList()
                    _isLoading.value = false
                    return@launch
                }

                val sftp = getConnectedSftp()
                @Suppress("UNCHECKED_CAST")
                val vector = sftp.ls(targetPath) as Vector<ChannelSftp.LsEntry>
                val resultList = mutableListOf<RemoteFile>()

                for (entry in vector) {
                    val name = entry.filename
                    if (name == "." || name == "..") continue
                    val fullPath = if (targetPath.endsWith("/")) "$targetPath$name" else "$targetPath/$name"
                    val isDir = entry.attrs.isDir
                    val size = entry.attrs.size
                    val mTime = entry.attrs.mTime.toLong() * 1000L

                    resultList.add(
                        RemoteFile(
                            name = name,
                            path = fullPath,
                            isDirectory = isDir,
                            sizeBytes = size,
                            modifiedTimestamp = mTime
                        )
                    )
                }

                val sorted = resultList.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                dirCache[targetPath] = Pair(System.currentTimeMillis(), sorted)
                _files.value = sorted
                _statusNotice.value = null
            } catch (e: Exception) {
                if (cached == null) {
                    _files.value = emptyList()
                }
                _statusNotice.value = "Filesystem error: ${e.message ?: "Failed to list directory"}"
            } finally {
                _isLoading.value = false
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
            val sftp = getConnectedSftp()
            val out = java.io.ByteArrayOutputStream()
            sftp.get(file.path).use { inStream ->
                val buffer = ByteArray(8192)
                var read: Int
                var total = 0
                val maxPreviewBytes = 1024 * 1024 // 1 MB preview limit
                while (inStream.read(buffer).also { read = it } != -1) {
                    out.write(buffer, 0, read)
                    total += read
                    if (total >= maxPreviewBytes) break
                }
            }
            val bytes = out.toByteArray()
            if (bytes.isEmpty()) return@withContext "[Empty File]"
            bytes.toString(Charsets.UTF_8)
        } catch (e: Exception) {
            "Error loading remote file: ${e.message}"
        }
    }

    suspend fun downloadFileToDevice(file: RemoteFile): Result<File> = withContext(Dispatchers.IO) {
        val repository = try { com.pox.colab.ColabApp.instance.repository } catch (_: Exception) { null }
        val runtime = repository?.getActiveRuntime(vm.id) ?: repository?.resolveActiveRuntime(vm.id)
        if (runtime != null && runtime.endpointUrl.isNotBlank()) {
            val res = ColabApiEngine.getInstance().readFile(runtime, file.path)
            if (res.isSuccess) {
                val content = res.getOrNull() ?: ""
                val downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
                val localFile = File(downloadDir, file.name)
                localFile.writeText(content, Charsets.UTF_8)
                _statusNotice.value = "Downloaded to: ${localFile.name}"
                return@withContext Result.success(localFile)
            }
        }

        try {
            val sftp = getConnectedSftp()
            val downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
            val localFile = File(downloadDir, file.name)
            FileOutputStream(localFile).use { outStream ->
                sftp.get(file.path, outStream)
            }
            _statusNotice.value = "Downloaded to: ${localFile.name}"
            Result.success(localFile)
        } catch (e: Exception) {
            _statusNotice.value = "Download failed: ${e.message}"
            Result.failure(e)
        }
    }

    suspend fun uploadFile(name: String, content: String): Result<RemoteFile> = withContext(Dispatchers.IO) {
        val current = _currentPath.value
        val targetPath = if (current.endsWith("/")) "$current$name" else "$current/$name"
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
                        sizeBytes = content.toByteArray(Charsets.UTF_8).size.toLong(),
                        modifiedTimestamp = System.currentTimeMillis()
                    )
                )
            }
        }

        try {
            val sftp = getConnectedSftp()
            val bytes = content.toByteArray(Charsets.UTF_8)
            ByteArrayInputStream(bytes).use { inStream ->
                sftp.put(inStream, targetPath)
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
        } catch (e: Exception) {
            _statusNotice.value = "Upload failed: ${e.message}"
            Result.failure(e)
        }
    }

    fun createFolder(name: String) {
        scope.launch(Dispatchers.IO) {
            val repository = try { com.pox.colab.ColabApp.instance.repository } catch (_: Exception) { null }
            val runtime = repository?.getActiveRuntime(vm.id) ?: repository?.resolveActiveRuntime(vm.id)
            if (runtime != null && runtime.endpointUrl.isNotBlank()) {
                val res = ColabApiEngine.getInstance().createDirectory(runtime, _currentPath.value, name)
                if (res.isSuccess) {
                    invalidateCache(_currentPath.value)
                    refreshDirectory(_currentPath.value, forceRefresh = true)
                    _statusNotice.value = "Folder created: $name"
                    return@launch
                }
            }

            try {
                val sftp = getConnectedSftp()
                val current = _currentPath.value
                val targetPath = if (current.endsWith("/")) "$current$name" else "$current/$name"
                sftp.mkdir(targetPath)
                invalidateCache(current)
                refreshDirectory(current, forceRefresh = true)
                _statusNotice.value = "Folder created: $name"
            } catch (e: Exception) {
                _statusNotice.value = "Create folder failed: ${e.message}"
            }
        }
    }

    fun deleteFile(file: RemoteFile) {
        scope.launch(Dispatchers.IO) {
            val repository = try { com.pox.colab.ColabApp.instance.repository } catch (_: Exception) { null }
            val runtime = repository?.getActiveRuntime(vm.id) ?: repository?.resolveActiveRuntime(vm.id)
            if (runtime != null && runtime.endpointUrl.isNotBlank()) {
                val res = ColabApiEngine.getInstance().deleteFile(runtime, file.path)
                if (res.isSuccess) {
                    invalidateCache(_currentPath.value)
                    refreshDirectory(_currentPath.value, forceRefresh = true)
                    _statusNotice.value = "Deleted: ${file.name}"
                    return@launch
                }
            }

            try {
                val sftp = getConnectedSftp()
                if (file.isDirectory) {
                    sftp.rmdir(file.path)
                } else {
                    sftp.rm(file.path)
                }
                invalidateCache(_currentPath.value)
                refreshDirectory(_currentPath.value, forceRefresh = true)
                _statusNotice.value = "Deleted: ${file.name}"
            } catch (e: Exception) {
                _statusNotice.value = "Delete failed: ${e.message}"
            }
        }
    }

    fun renameFile(file: RemoteFile, newName: String) {
        scope.launch(Dispatchers.IO) {
            val repository = try { com.pox.colab.ColabApp.instance.repository } catch (_: Exception) { null }
            val runtime = repository?.getActiveRuntime(vm.id) ?: repository?.resolveActiveRuntime(vm.id)
            if (runtime != null && runtime.endpointUrl.isNotBlank()) {
                val res = ColabApiEngine.getInstance().renameFile(runtime, file.path, newName)
                if (res.isSuccess) {
                    invalidateCache(_currentPath.value)
                    refreshDirectory(_currentPath.value, forceRefresh = true)
                    _statusNotice.value = "Renamed to: $newName"
                    return@launch
                }
            }

            try {
                val sftp = getConnectedSftp()
                val parent = file.path.substringBeforeLast('/', "")
                val targetPath = if (parent.isEmpty()) "/$newName" else "$parent/$newName"
                sftp.rename(file.path, targetPath)
                invalidateCache(_currentPath.value)
                refreshDirectory(_currentPath.value, forceRefresh = true)
                _statusNotice.value = "Renamed to: $newName"
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
        try {
            sftpChannel?.disconnect()
        } catch (_: Exception) {}
        sftpChannel = null

        try {
            sftpSession?.disconnect()
        } catch (_: Exception) {}
        sftpSession = null
    }

    fun close() {
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
}
