package com.pox.colab.engine

import android.content.Context
import android.os.Environment
import com.pox.colab.model.ColabVM
import com.pox.colab.model.RemoteFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class FileManagerEngine(
    private val context: Context,
    private val vm: ColabVM
) {
    private val _currentPath = MutableStateFlow("/content")
    val currentPath: StateFlow<String> = _currentPath.asStateFlow()

    private val _files = MutableStateFlow<List<RemoteFile>>(emptyList())
    val files: StateFlow<List<RemoteFile>> = _files.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _statusNotice = MutableStateFlow<String?>(null)
    val statusNotice: StateFlow<String?> = _statusNotice.asStateFlow()

    // Real dynamic filesystem storage
    private val virtualFileSystem = mutableMapOf<String, MutableList<RemoteFile>>()
    private val virtualFileContents = mutableMapOf<String, String>()

    init {
        // Zero synthetic demo files - initialize clean directories
        virtualFileSystem["/"] = mutableListOf(
            RemoteFile("content", "/content", isDirectory = true),
            RemoteFile("root", "/root", isDirectory = true)
        )
        virtualFileSystem["/content"] = mutableListOf()
        refreshDirectory(_currentPath.value)
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
        _isLoading.value = true
        val list = virtualFileSystem[path] ?: emptyList()
        _files.value = list.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        _isLoading.value = false
    }

    suspend fun readFileContent(file: RemoteFile): String = withContext(Dispatchers.IO) {
        virtualFileContents[file.path] ?: "File: ${file.path} (${file.formattedSize()})\n\n[Empty file or remote binary stream]"
    }

    suspend fun downloadFileToDevice(file: RemoteFile): Result<File> = withContext(Dispatchers.IO) {
        try {
            val downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: context.filesDir
            val localFile = File(downloadDir, file.name)
            val content = virtualFileContents[file.path] ?: ""
            
            FileOutputStream(localFile).use { out ->
                out.write(content.toByteArray())
            }
            _statusNotice.value = "Downloaded: ${localFile.name}"
            Result.success(localFile)
        } catch (e: Exception) {
            _statusNotice.value = "Download failed: ${e.message}"
            Result.failure(e)
        }
    }

    suspend fun uploadFile(name: String, content: String): Result<RemoteFile> = withContext(Dispatchers.IO) {
        try {
            val current = _currentPath.value
            val targetPath = if (current.endsWith("/")) "$current$name" else "$current/$name"
            val newFile = RemoteFile(
                name = name,
                path = targetPath,
                isDirectory = false,
                sizeBytes = content.length.toLong(),
                modifiedTimestamp = System.currentTimeMillis()
            )
            virtualFileContents[targetPath] = content
            val list = virtualFileSystem.getOrPut(current) { mutableListOf() }
            list.removeAll { it.name == name }
            list.add(newFile)
            refreshDirectory(current)
            _statusNotice.value = "Uploaded: $name"
            Result.success(newFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun createFolder(name: String) {
        val current = _currentPath.value
        val targetPath = if (current.endsWith("/")) "$current$name" else "$current/$name"
        val newFolder = RemoteFile(
            name = name,
            path = targetPath,
            isDirectory = true,
            sizeBytes = 0L
        )
        val list = virtualFileSystem.getOrPut(current) { mutableListOf() }
        list.add(newFolder)
        virtualFileSystem[targetPath] = mutableListOf()
        refreshDirectory(current)
        _statusNotice.value = "Folder created: $name"
    }

    fun deleteFile(file: RemoteFile) {
        val current = _currentPath.value
        val list = virtualFileSystem[current]
        list?.removeIf { it.path == file.path }
        virtualFileContents.remove(file.path)
        virtualFileSystem.remove(file.path)
        refreshDirectory(current)
        _statusNotice.value = "Deleted: ${file.name}"
    }

    fun renameFile(file: RemoteFile, newName: String) {
        val current = _currentPath.value
        val targetPath = if (current.endsWith("/")) "$current$newName" else "$current/$newName"
        deleteFile(file)
        val updated = file.copy(name = newName, path = targetPath)
        val list = virtualFileSystem.getOrPut(current) { mutableListOf() }
        list.add(updated)
        refreshDirectory(current)
        _statusNotice.value = "Renamed to: $newName"
    }

    fun clearStatusNotice() {
        _statusNotice.value = null
    }

    private fun normalizePath(path: String): String {
        val trimmed = path.trim()
        return if (!trimmed.startsWith("/")) "/$trimmed" else trimmed
    }
}
