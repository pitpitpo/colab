package com.pox.colab.engine

import com.google.gson.Gson
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.net.URLEncoder
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Process-wide shared HTTP and JSON infrastructure.
 *
 * All derived [OkHttpClient] instances are created via [OkHttpClient.newBuilder], so they
 * share a single [ConnectionPool] and [Dispatcher] while allowing per-engine timeout tuning.
 * This maximizes HTTP/2 multiplexing and TLS session reuse while avoiding leaked thread pools.
 */
object HttpClientProvider {

    val gson: Gson by lazy { Gson() }

    private val sharedDispatcher: Dispatcher by lazy {
        Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 12
        }
    }

    private val sharedConnectionPool: ConnectionPool by lazy {
        ConnectionPool(8, 5, TimeUnit.MINUTES)
    }

    /**
     * Separate bare client used solely for direct-IP DNS-over-HTTPS fallback queries
     * (`1.1.1.1` / `8.8.8.8`) so DNS fallback never recursively calls [ResilientDns].
     */
    private val dohClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .writeTimeout(3, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    /**
     * Uses Android's system DNS first, and automatically falls back to direct-IP DNS-over-HTTPS
     * (Cloudflare 1.1.1.1 -> Google 8.8.8.8) if system DNS fails with [UnknownHostException]
     * (e.g., broken carrier IPv6 DNS, Private DNS misconfiguration, or emulator DNS hiccups).
     */
    private val resilientDns: Dns = object : Dns {
        private val fallbackCache = ConcurrentHashMap<String, Pair<Long, List<InetAddress>>>()
        private val cacheTtlMs = 5 * 60 * 1000L

        override fun lookup(hostname: String): List<InetAddress> {
            return try {
                Dns.SYSTEM.lookup(hostname)
            } catch (systemError: UnknownHostException) {
                if (isLocalOrIpLiteral(hostname)) {
                    throw systemError
                }

                val now = System.currentTimeMillis()
                fallbackCache[hostname]?.let { (cachedAt, cachedAddrs) ->
                    if (now - cachedAt < cacheTtlMs && cachedAddrs.isNotEmpty()) {
                        return cachedAddrs
                    }
                }

                val resolved = resolveViaDirectIpDoh(hostname)
                if (resolved.isNotEmpty()) {
                    fallbackCache[hostname] = now to resolved
                    resolved
                } else {
                    throw systemError
                }
            }
        }

        private fun isLocalOrIpLiteral(host: String): Boolean {
            val lower = host.lowercase(Locale.ROOT)
            if (lower == "localhost" || lower.endsWith(".local") || lower.contains(":")) return true
            return lower.all { it.isDigit() || it == '.' }
        }

        private fun resolveViaDirectIpDoh(hostname: String): List<InetAddress> {
            val encoded = URLEncoder.encode(hostname, StandardCharsets.UTF_8.name())
            val endpoints = listOf(
                "https://1.1.1.1/dns-query?name=$encoded&type=A",
                "https://8.8.8.8/resolve?name=$encoded&type=A"
            )
            for (url in endpoints) {
                try {
                    val req = Request.Builder()
                        .url(url)
                        .header("Accept", "application/dns-json")
                        .get()
                        .build()
                    dohClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@use
                        val body = resp.body?.string().orEmpty()
                        val json = JSONObject(body)
                        val answers = json.optJSONArray("Answer") ?: return@use
                        val addrs = ArrayList<InetAddress>(answers.length())
                        for (i in 0 until answers.length()) {
                            val entry = answers.optJSONObject(i) ?: continue
                            if (entry.optInt("type", -1) != 1) continue
                            val ip = entry.optString("data", "").trim()
                            if (ip.isNotEmpty()) {
                                runCatching { InetAddress.getByName(ip) }.getOrNull()?.let { addrs.add(it) }
                            }
                        }
                        if (addrs.isNotEmpty()) return addrs
                    }
                } catch (_: Exception) {
                    // Try next DoH resolver
                }
            }
            return emptyList()
        }
    }

    private val baseClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dispatcher(sharedDispatcher)
            .connectionPool(sharedConnectionPool)
            .dns(resilientDns)
            .retryOnConnectionFailure(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /** Standard Colab REST + Jupyter WebSocket client. */
    val apiClient: OkHttpClient by lazy {
        baseClient.newBuilder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    /** OAuth2 token exchange, UserInfo, and CCU quota detection client. */
    val authClient: OkHttpClient by lazy {
        baseClient.newBuilder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(18, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /** Fast-timeout client for tunnel rendezvous polling and GitHub release checks. */
    val fastClient: OkHttpClient by lazy {
        baseClient.newBuilder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .writeTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    /** Low-latency non-retrying client for periodic HTTP telemetry probes. */
    val telemetryClient: OkHttpClient by lazy {
        baseClient.newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .writeTimeout(3, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }
}
