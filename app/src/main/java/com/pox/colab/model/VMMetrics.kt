package com.pox.colab.model

data class VMMetrics(
    val cpuUsagePercent: Float = 0f,
    val ramUsedBytes: Long = 0L,
    val ramTotalBytes: Long = 13_600_000_000L, // ~13.6 GB standard Colab RAM
    val gpuModel: String? = null,
    val gpuVramUsedBytes: Long = 0L,
    val gpuVramTotalBytes: Long = 16_100_000_000L, // ~16 GB for T4
    val gpuTemperatureCelsius: Int = 0,
    val diskUsedBytes: Long = 25_000_000_000L,
    val diskTotalBytes: Long = 110_000_000_000L,
    val loadAverage: String = "0.15, 0.22, 0.18"
) {
    val ramUsagePercent: Float
        get() = if (ramTotalBytes > 0) (ramUsedBytes.toFloat() / ramTotalBytes.toFloat()) * 100f else 0f

    val gpuUsagePercent: Float
        get() = if (gpuVramTotalBytes > 0 && gpuModel != null) (gpuVramUsedBytes.toFloat() / gpuVramTotalBytes.toFloat()) * 100f else 0f

    val diskUsagePercent: Float
        get() = if (diskTotalBytes > 0) (diskUsedBytes.toFloat() / diskTotalBytes.toFloat()) * 100f else 0f

    fun formattedRam(): String {
        val usedGb = ramUsedBytes / 1_000_000_000.0
        val totalGb = ramTotalBytes / 1_000_000_000.0
        return String.format("%.1f / %.1f GB", usedGb, totalGb)
    }

    fun formattedGpuVram(): String {
        if (gpuModel == null) return "No GPU"
        val usedGb = gpuVramUsedBytes / 1_000_000_000.0
        val totalGb = gpuVramTotalBytes / 1_000_000_000.0
        return String.format("%.1f / %.1f GB", usedGb, totalGb)
    }

    fun formattedDisk(): String {
        val usedGb = diskUsedBytes / 1_000_000_000.0
        val totalGb = diskTotalBytes / 1_000_000_000.0
        return String.format("%.1f / %.1f GB", usedGb, totalGb)
    }
}
