package com.pox.colab.engine

import com.google.gson.Gson
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
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

    private val baseClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dispatcher(sharedDispatcher)
            .connectionPool(sharedConnectionPool)
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
