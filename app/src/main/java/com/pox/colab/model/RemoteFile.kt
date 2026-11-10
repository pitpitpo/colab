package com.pox.colab.model

data class RemoteFile(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long = 0L,
    val permissions: String = "rw-r--r--",
    val modifiedTimestamp: Long = System.currentTimeMillis(),
    val mimeType: String = "application/octet-stream"
) {
    fun formattedSize(): String {
        if (isDirectory) return "--"
        if (sizeBytes < 1024) return "$sizeBytes B"
        val kb = sizeBytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format("%.2f GB", gb)
    }

    val isTextOrCode: Boolean
        get() {
            val ext = name.substringAfterLast('.', "").lowercase()
            return ext in listOf("py", "sh", "txt", "md", "json", "yaml", "yml", "csv", "log", "conf", "ini", "ipynb")
        }
}
