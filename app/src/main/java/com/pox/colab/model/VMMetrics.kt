package com.pox.colab.model

data class VMMetrics(
    val cpuUsagePercent: Float = 0f,
    val ramUsedBytes: Long = 0L,
    val ramTotalBytes: Long = 0L,
    val gpuModel: String? = null,
    val gpuVramUsedBytes: Long = 0L,
    val gpuVramTotalBytes: Long = 0L,
    val gpuTemperatureCelsius: Int = 0,
    val diskUsedBytes: Long = 0L,
    val diskTotalBytes: Long = 0L,
    val loadAverage: String = "0.00, 0.00, 0.00"
) {
    val ramUsagePercent: Float
        get() = if (ramTotalBytes > 0) (ramUsedBytes.toFloat() / ramTotalBytes.toFloat()) * 100f else 0f

    val gpuUsagePercent: Float
        get() = if (gpuVramTotalBytes > 0 && gpuModel != null) (gpuVramUsedBytes.toFloat() / gpuVramTotalBytes.toFloat()) * 100f else 0f

    val diskUsagePercent: Float
        get() = if (diskTotalBytes > 0) (diskUsedBytes.toFloat() / diskTotalBytes.toFloat()) * 100f else 0f

    fun formattedRam(): String {
        if (ramTotalBytes <= 0) return "-- / -- GB"
        val usedGb = ramUsedBytes / 1_000_000_000.0
        val totalGb = ramTotalBytes / 1_000_000_000.0
        return String.format("%.1f / %.1f GB", usedGb, totalGb)
    }

    fun formattedGpuVram(): String {
        if (gpuModel == null || gpuVramTotalBytes <= 0) return "No GPU"
        val usedGb = gpuVramUsedBytes / 1_000_000_000.0
        val totalGb = gpuVramTotalBytes / 1_000_000_000.0
        return String.format("%.1f / %.1f GB", usedGb, totalGb)
    }

    fun formattedDisk(): String {
        if (diskTotalBytes <= 0) return "-- / -- GB"
        val usedGb = diskUsedBytes / 1_000_000_000.0
        val totalGb = diskTotalBytes / 1_000_000_000.0
        return String.format("%.1f / %.1f GB", usedGb, totalGb)
    }
}
