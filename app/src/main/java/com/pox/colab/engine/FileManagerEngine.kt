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

    // In-memory mock filesystem storage for offline / quick preview
    private val virtualFileSystem = mutableMapOf<String, MutableList<RemoteFile>>()
    private val virtualFileContents = mutableMapOf<String, String>()

    init {
        initializeVirtualFileSystem()
        refreshDirectory(_currentPath.value)
    }

    private fun initializeVirtualFileSystem() {
        virtualFileSystem["/"] = mutableListOf(
            RemoteFile("content", "/content", isDirectory = true),
            RemoteFile("root", "/root", isDirectory = true),
            RemoteFile("etc", "/etc", isDirectory = true),
            RemoteFile("usr", "/usr", isDirectory = true),
            RemoteFile("tmp", "/tmp", isDirectory = true),
            RemoteFile("var", "/var", isDirectory = true)
        )

        virtualFileSystem["/content"] = mutableListOf(
            RemoteFile("alpine_rootfs", "/content/alpine_rootfs", isDirectory = true),
            RemoteFile("sample_data", "/content/sample_data", isDirectory = true),
            RemoteFile("checkpoints", "/content/checkpoints", isDirectory = true),
            RemoteFile("train_model.py", "/content/train_model.py", isDirectory = false, sizeBytes = 2450L),
            RemoteFile("config.yaml", "/content/config.yaml", isDirectory = false, sizeBytes = 850L),
            RemoteFile("dataset.csv", "/content/dataset.csv", isDirectory = false, sizeBytes = 14_200_000L),
            RemoteFile("training.log", "/content/training.log", isDirectory = false, sizeBytes = 42_500L),
            RemoteFile("colab_notebook.ipynb", "/content/colab_notebook.ipynb", isDirectory = false, sizeBytes = 18_900L)
        )

        virtualFileSystem["/content/sample_data"] = mutableListOf(
            RemoteFile("anscombe.json", "/content/sample_data/anscombe.json", isDirectory = false, sizeBytes = 1697L),
            RemoteFile("california_housing_train.csv", "/content/sample_data/california_housing_train.csv", isDirectory = false, sizeBytes = 1706430L),
            RemoteFile("mnist_test.csv", "/content/sample_data/mnist_test.csv", isDirectory = false, sizeBytes = 18289443L)
        )

        virtualFileSystem["/content/checkpoints"] = mutableListOf(
            RemoteFile("model_epoch_10.pt", "/content/checkpoints/model_epoch_10.pt", isDirectory = false, sizeBytes = 445_000_000L),
            RemoteFile("best_weights.safetensors", "/content/checkpoints/best_weights.safetensors", isDirectory = false, sizeBytes = 880_000_000L)
        )

        virtualFileContents["/content/train_model.py"] = """
            # PyTorch Deep Learning Training Script
            import torch
            import torch.nn as nn
            from colab_cli import log_metrics

            print(f"CUDA Available: {torch.cuda.is_available()}")
            if torch.cuda.is_available():
                print(f"Device: {torch.cuda.get_device_name(0)}")

            class SimpleModel(nn.Module):
                def __init__(self):
                    super().__init__()
                    self.fc = nn.Linear(512, 10)

                def forward(self, x):
                    return self.fc(x)

            model = SimpleModel().cuda()
            print("Model initialized on Alpine container!")
        """.trimIndent()

        virtualFileContents["/content/config.yaml"] = """
            experiment:
              name: alpine-colab-run
              epochs: 100
              batch_size: 64
              optimizer:
                type: adamw
                lr: 0.0003
              resilient_ssh:
                protocol: mosh
                auto_reconnect: true
        """.trimIndent()

        virtualFileContents["/content/training.log"] = """
            [Epoch 1/100] loss: 2.3412, acc: 0.124 - val_loss: 2.102
            [Epoch 2/100] loss: 1.8920, acc: 0.358 - val_loss: 1.745
            [Epoch 3/100] loss: 1.4521, acc: 0.582 - val_loss: 1.321
            [Epoch 4/100] loss: 1.1204, acc: 0.710 - val_loss: 1.050
            [Epoch 5/100] loss: 0.8923, acc: 0.812 - val_loss: 0.880
            [Epoch 6/100] loss: 0.7102, acc: 0.865 - val_loss: 0.720
            [INFO] Resilient connection kept session alive during network shift.
        """.trimIndent()
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
        virtualFileContents[file.path] ?: "File content preview for ${file.name} (${file.formattedSize()})\n\n[Binary or Large File Data]"
    }

    suspend fun downloadFileToDevice(file: RemoteFile): Result<File> = withContext(Dispatchers.IO) {
        try {
            val downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: context.filesDir
            val localFile = File(downloadDir, file.name)
            val content = virtualFileContents[file.path] ?: "Generated Colab Download Payload for ${file.name}"
            
            FileOutputStream(localFile).use { out ->
                out.write(content.toByteArray())
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
