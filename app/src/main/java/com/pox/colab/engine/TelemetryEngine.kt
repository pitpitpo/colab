package com.pox.colab.engine

import com.pox.colab.model.VMMetrics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

object TelemetryEngine {

    private val client get() = HttpClientProvider.telemetryClient

    /**
     * Attempts to query the live metrics agent over HTTP on the specified host and port (defaults to 8080).
     */
    suspend fun fetchHttpMetrics(host: String, port: Int = 8080): VMMetrics? = withContext(Dispatchers.IO) {
        val trimmedHost = host.trim().trimEnd('/')
        if (trimmedHost.isBlank()) return@withContext null
        try {
            val url = if (trimmedHost.startsWith("http://") || trimmedHost.startsWith("https://")) {
                "$trimmedHost/api/status"
            } else {
                "http://$trimmedHost:$port/api/status"
            }
            val request = Request.Builder()
                .url(url)
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@withContext null
                    return@withContext parseMetricsJson(body)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Server not reachable or tunnel does not forward telemetry HTTP port
        }
        return@withContext null
    }

    /**
     * Parses real telemetry JSON produced by the Colab telemetry agent daemon (/tmp/colab_metrics.json or /api/status).
     */
    fun parseMetricsJson(jsonStr: String): VMMetrics {
        return try {
            val obj = JSONObject(jsonStr)
            val loadStr = obj.optString("load_average", "0.00, 0.00, 0.00")
            val rawCpu = obj.optDouble("cpu_percent", -1.0)
            val cpuPct = if (rawCpu >= 0.0) {
                rawCpu.toFloat()
            } else {
                val firstLoad = loadStr.split(",").firstOrNull()?.trim()?.toFloatOrNull() ?: 0f
                (firstLoad * 20f).coerceIn(0f, 100f)
            }

            val vramTotal = obj.optLong("gpu_vram_total_bytes", 0L)
            val vramUsed = obj.optLong("gpu_vram_used_bytes", 0L)
            val rawGpuUsage = obj.optDouble("gpu_usage_percent", -1.0)
            val gpuUsage = if (rawGpuUsage >= 0.0) {
                rawGpuUsage.toFloat()
            } else if (vramTotal > 0L) {
                ((vramUsed.toDouble() / vramTotal.toDouble()) * 100.0).toFloat().coerceIn(0f, 100f)
            } else {
                0f
            }

            val ts = obj.optLong("timestamp", 0L).let { if (it > 0L) it else System.currentTimeMillis() }

            VMMetrics(
                cpuUsagePercent = cpuPct,
                loadAverage = loadStr,
                ramTotalBytes = obj.optLong("ram_total_bytes", 0L),
                ramUsedBytes = obj.optLong("ram_used_bytes", 0L),
                diskTotalBytes = obj.optLong("disk_total_bytes", 0L),
                diskUsedBytes = obj.optLong("disk_used_bytes", 0L),
                gpuModel = obj.optString("gpu_model").takeIf { it.isNotBlank() && it != "null" },
                gpuUsagePercent = gpuUsage,
                gpuVramTotalBytes = vramTotal,
                gpuVramUsedBytes = vramUsed,
                gpuTemperatureCelsius = obj.optInt("gpu_temperature_celsius", 0),
                uptimeSeconds = obj.optLong("uptime_seconds", 0L),
                tunnelEndpoint = obj.optString("tunnel_endpoint").takeIf { it.isNotBlank() && it != "null" },
                timestamp = ts
            )
        } catch (_: Exception) {
            VMMetrics()
        }
    }
}
