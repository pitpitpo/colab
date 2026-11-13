package com.pox.colab

import com.pox.colab.engine.TelemetryEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TelemetryEngineTest {

    @Test
    fun parseMetricsJson_parsesValidMetricsCorrectly() {
        val sampleJson = """
            {
                "status": "RUNNING",
                "cpu_percent": 14.5,
                "load_average": "0.45, 0.32, 0.18",
                "ram_total_bytes": 13653770240,
                "ram_used_bytes": 2453893120,
                "disk_total_bytes": 115658190848,
                "disk_used_bytes": 28493824000,
                "gpu_model": "Tesla T4",
                "gpu_vram_total_bytes": 15843721216,
                "gpu_vram_used_bytes": 1289748480,
                "gpu_temperature_celsius": 52,
                "gpu_usage_percent": 28.0,
                "uptime_seconds": 18452,
                "timestamp": 1700000000.0
            }
        """.trimIndent()

        val metrics = TelemetryEngine.parseMetricsJson(sampleJson)
        assertNotNull(metrics)
        assertEquals(14.5f, metrics.cpuUsagePercent, 0.01f)
        assertEquals("0.45, 0.32, 0.18", metrics.loadAverage)
        assertEquals(13653770240L, metrics.ramTotalBytes)
        assertEquals(2453893120L, metrics.ramUsedBytes)
        assertEquals(115658190848L, metrics.diskTotalBytes)
        assertEquals(28493824000L, metrics.diskUsedBytes)
        assertEquals("Tesla T4", metrics.gpuModel)
        assertEquals(15843721216L, metrics.gpuVramTotalBytes)
        assertEquals(1289748480L, metrics.gpuVramUsedBytes)
        assertEquals(52, metrics.gpuTemperatureCelsius)
        assertEquals(28.0f, metrics.gpuUsagePercent, 0.01f)
        assertEquals(18452L, metrics.uptimeSeconds)
    }

    @Test
    fun parseMetricsJson_handlesMissingGpuAndFallbackUptime() {
        val jsonNoGpu = """
            {
                "status": "RUNNING",
                "load_average": "1.20, 0.80, 0.50",
                "ram_total_bytes": 8589934592,
                "ram_used_bytes": 4294967296,
                "uptime_seconds": 3600
            }
        """.trimIndent()

        val metrics = TelemetryEngine.parseMetricsJson(jsonNoGpu)
        assertNotNull(metrics)
        assertNull(metrics.gpuModel)
        assertEquals(0f, metrics.gpuUsagePercent, 0.01f)
        assertEquals(3600L, metrics.uptimeSeconds)
        // First load is 1.20 -> 1.20 * 20 = 24.0%
        assertEquals(24.0f, metrics.cpuUsagePercent, 0.01f)
    }

    @Test
    fun parseMetricsJson_handlesMalformedJsonGracefully() {
        val emptyOrCorrupt = "{ invalid json"
        val metrics = TelemetryEngine.parseMetricsJson(emptyOrCorrupt)
        assertNotNull(metrics)
        assertEquals(0L, metrics.ramTotalBytes)
        assertEquals(0L, metrics.uptimeSeconds)
    }
}
