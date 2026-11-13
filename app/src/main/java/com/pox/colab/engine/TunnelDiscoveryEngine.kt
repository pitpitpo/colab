package com.pox.colab.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Autonomous rendezvous engine that discovers tunnel endpoints announced by the
 * Colab bootstrap script. The script automatically publishes its live endpoint
 * to the discovery topic upon startup, allowing the app to abstract all networking
 * configuration and connect directly to the VM without manual user intervention.
 */
object TunnelDiscoveryEngine {

    var httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun getDiscoveryTopic(vmId: String): String {
        return "colab_vm_${vmId.replace("-", "").lowercase()}"
    }

    fun getDiscoveryUrl(vmId: String): String {
        return "https://ntfy.sh/${getDiscoveryTopic(vmId)}/raw?poll=1"
    }

    /**
     * Polls the discovery channel for an announced tunnel endpoint.
     * Returns the clean host:port or URL if published, or null if not yet available.
     */
    suspend fun pollDiscoveredEndpoint(vmId: String): String? = withContext(Dispatchers.IO) {
        if (vmId.isBlank()) return@withContext null
        try {
            val request = Request.Builder()
                .url(getDiscoveryUrl(vmId))
                .header("User-Agent", "ColabClient/1.0")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()?.trim() ?: ""
                    if (body.isNotBlank() && (body.contains(":") || body.contains("."))) {
                        // Take the most recent published endpoint line
                        val candidate = body.lines().lastOrNull { line ->
                            val trimmed = line.trim()
                            trimmed.isNotBlank() && (trimmed.contains(":") || trimmed.contains("."))
                        }?.trim()
                        return@withContext candidate
                    }
                }
            }
        } catch (_: Exception) {}
        return@withContext null
    }

    /**
     * Starts background polling for the VM's tunnel endpoint, invoking [onEndpointDiscovered]
     * immediately once detected.
     */
    fun startAutoDiscovery(
        vmId: String,
        scope: CoroutineScope,
        maxAttempts: Int = 120, // 4 minutes at 2s interval
        intervalMs: Long = 2000L,
        onEndpointDiscovered: (String) -> Unit
    ): Job {
        return scope.launch(Dispatchers.IO) {
            for (attempt in 1..maxAttempts) {
                if (!isActive) break
                val discovered = pollDiscoveredEndpoint(vmId)
                if (!discovered.isNullOrBlank()) {
                    withContext(Dispatchers.Main) {
                        onEndpointDiscovered(discovered)
                    }
                    break
                }
                delay(intervalMs)
            }
        }
    }
}

