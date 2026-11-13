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
    private val vm: ColabVM,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
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

    init {
        refreshDirectory(_currentPath.value)
    }

    fun resolveHostAndPort(): Pair<String, Int> {
        val raw = if (vm.sshConfig.host.isNotBlank() && vm.sshConfig.host != "localhost") {
            "${vm.sshConfig.host}:${vm.sshConfig.port}"
        } else {
            vm.tunnelUrl ?: ""
        }
        return VMRepository.parseEndpoint(raw)
    }

    private suspend fun getConnectedSftp(): ChannelSftp = withContext(Dispatchers.IO) {
        val existingChannel = sftpChannel
        val existingSession = sftpSession
        if (existingChannel != null && existingChannel.isConnected && existingSession != null && existingSession.isConnected) {
            return@withContext existingChannel
        }

        close()

        val (host, port) = resolveHostAndPort()
        if (host.isBlank()) {
            throw IllegalStateException("Tunnel host not configured. Please set the live tunnel endpoint.")
        }

        val session = jsch.getSession(vm.sshConfig.username, host, port)
        if (vm.sshConfig.authMethod == SSHAuthMethod.PASSWORD) {
            session.setPassword(vm.sshConfig.password)
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

    fun refreshDirectory(path: String = _currentPath.value) {
        val (host, _) = resolveHostAndPort()
        if (host.isBlank()) {
            _statusNotice.value = "Remote SFTP Disconnected. Please configure tunnel host in Dashboard."
            _files.value = emptyList()
            return
        }

        _isLoading.value = true
        scope.launch(Dispatchers.IO) {
            try {
                val sftp = getConnectedSftp()
                val targetPath = normalizePath(path)
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

                _files.value = resultList.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            } catch (e: Exception) {
                _statusNotice.value = "SFTP: ${e.message ?: "Failed to list directory"}"
                _files.value = emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }

    suspend fun readFileContent(file: RemoteFile): String = withContext(Dispatchers.IO) {
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
        try {
            val sftp = getConnectedSftp()
            val current = _currentPath.value
            val targetPath = if (current.endsWith("/")) "$current$name" else "$current/$name"
            val bytes = content.toByteArray(Charsets.UTF_8)
            ByteArrayInputStream(bytes).use { inStream ->
                sftp.put(inStream, targetPath)
            }
            refreshDirectory(current)
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
            try {
                val sftp = getConnectedSftp()
                val current = _currentPath.value
                val targetPath = if (current.endsWith("/")) "$current$name" else "$current/$name"
                sftp.mkdir(targetPath)
                refreshDirectory(current)
                _statusNotice.value = "Folder created: $name"
            } catch (e: Exception) {
                _statusNotice.value = "Create folder failed: ${e.message}"
            }
        }
    }

    fun deleteFile(file: RemoteFile) {
        scope.launch(Dispatchers.IO) {
            try {
                val sftp = getConnectedSftp()
                if (file.isDirectory) {
                    sftp.rmdir(file.path)
                } else {
                    sftp.rm(file.path)
                }
                refreshDirectory(_currentPath.value)
                _statusNotice.value = "Deleted: ${file.name}"
            } catch (e: Exception) {
                _statusNotice.value = "Delete failed: ${e.message}"
            }
        }
    }

    fun renameFile(file: RemoteFile, newName: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val sftp = getConnectedSftp()
                val parent = file.path.substringBeforeLast('/', "")
                val targetPath = if (parent.isEmpty()) "/$newName" else "$parent/$newName"
                sftp.rename(file.path, targetPath)
                refreshDirectory(_currentPath.value)
                _statusNotice.value = "Renamed to: $newName"
            } catch (e: Exception) {
                _statusNotice.value = "Rename failed: ${e.message}"
            }
        }
    }

    fun clearStatusNotice() {
        _statusNotice.value = null
    }

    fun close() {
        try {
            scope.cancel()
        } catch (_: Exception) {}

        try {
            sftpChannel?.disconnect()
        } catch (_: Exception) {}
        sftpChannel = null

        try {
            sftpSession?.disconnect()
        } catch (_: Exception) {}
        sftpSession = null
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
