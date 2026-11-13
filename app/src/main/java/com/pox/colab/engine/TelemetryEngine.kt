package com.pox.colab.engine

import com.pox.colab.model.VMMetrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object TelemetryEngine {

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    /**
     * Attempts to query the live metrics agent over HTTP on the specified host and port (defaults to 8080).
     */
    suspend fun fetchHttpMetrics(host: String, port: Int = 8080): VMMetrics? = withContext(Dispatchers.IO) {
        if (host.isBlank()) return@withContext null
        try {
            val url = if (host.startsWith("http://") || host.startsWith("https://")) {
                "$host/api/status"
            } else {
                "http://$host:$port/api/status"
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
        } catch (_: Exception) {
            // Server not reachable or tunnel does not forward port 8080
        }
        return@withContext null
    }

    /**
     * Parses real telemetry JSON produced by the Alpine agent daemon (/tmp/colab_metrics.json or /api/status).
     */
    fun parseMetricsJson(jsonStr: String): VMMetrics {
        val obj = JSONObject(jsonStr)
        val loadStr = obj.optString("load_average", "0.00, 0.00, 0.00")
        val rawCpu = obj.optDouble("cpu_percent", -1.0)
        val cpuPct = if (rawCpu >= 0.0) {
            rawCpu.toFloat()
        } else {
            val firstLoad = loadStr.split(",").firstOrNull()?.trim()?.toFloatOrNull() ?: 0f
            (firstLoad * 20f).coerceIn(0f, 100f)
        }

        return VMMetrics(
            cpuUsagePercent = cpuPct,
            loadAverage = loadStr,
            ramTotalBytes = obj.optLong("ram_total_bytes", 0L),
            ramUsedBytes = obj.optLong("ram_used_bytes", 0L),
            diskTotalBytes = obj.optLong("disk_total_bytes", 0L),
            diskUsedBytes = obj.optLong("disk_used_bytes", 0L),
            gpuModel = obj.optString("gpu_model").takeIf { it.isNotBlank() && it != "null" },
            gpuVramTotalBytes = obj.optLong("gpu_vram_total_bytes", 0L),
            gpuVramUsedBytes = obj.optLong("gpu_vram_used_bytes", 0L),
            gpuTemperatureCelsius = obj.optInt("gpu_temperature_celsius", 0),
            uptimeSeconds = obj.optLong("uptime_seconds", 0L)
        )
    }
}
