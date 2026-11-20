package com.pox.colab.engine

import com.pox.colab.model.ColabAccount
import com.pox.colab.model.HardwareAccelerator
import com.pox.colab.model.RemoteFile
import com.pox.colab.model.VMMetrics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// ---------------------------------------------------------------------------
// Domain models
// ---------------------------------------------------------------------------

/** Colab hardware accelerator types accepted by the runtime-allocation endpoint. */
enum class ColabAccelerator(val apiValue: String) {
    NONE("NONE"),
    T4("T4"),
    V100("V100"),
    A100("A100")
}

/** Colab runtime hardware variant sent as `variant=` query parameter on `/tun/m/assign`. */
enum class ColabVariant(val apiValue: String) {
    DEFAULT("DEFAULT"),
    GPU("GPU"),
    TPU("TPU")
}

/** Mapping helper between app HardwareAccelerator and ColabAccelerator. */
fun HardwareAccelerator.toColabAccelerator(): ColabAccelerator = when (this) {
    HardwareAccelerator.NONE -> ColabAccelerator.NONE
    HardwareAccelerator.T4 -> ColabAccelerator.T4
    HardwareAccelerator.V100 -> ColabAccelerator.V100
    HardwareAccelerator.A100 -> ColabAccelerator.A100
}

/** Runtime session returned after successful allocation or assignment discovery. */
data class ColabRuntime(
    /** Colab session / endpoint ID (e.g. `m-s-xxxxxxxx`). */
    val sessionId: String,
    /** Runtime proxy URL (e.g. `https://<id>.prod.colab.dev`) or fallback control-plane URL. */
    val endpointUrl: String,
    /** Datalab / GKE backend URL if present. */
    val datalabBackendUrl: String,
    /** Raw assignment response body for diagnostics. */
    val rawResponse: String = "",
    /** Runtime proxy authorization token (`X-Colab-Runtime-Proxy-Token`). */
    val proxyToken: String = "",
    /** Hardware accelerator provisioned. */
    val accelerator: ColabAccelerator = ColabAccelerator.T4,
    /** Epoch millis when [proxyToken] expires, or 0 if unknown/non-expiring. */
    val proxyTokenExpiresAtMs: Long = 0L,
    /** Runtime variant (`GPU`, `TPU`, `DEFAULT`). */
    val variant: String = "",
    /** Machine shape (`hm` for High-RAM, `standard` or blank otherwise). */
    val machineShape: String = ""
) {
    /** Extracts the Colab endpoint identifier (`m-s-...`) from [sessionId] or [endpointUrl]. */
    val endpointId: String
        get() {
            if (sessionId.isNotBlank()) return sessionId
            val marker = "/tun/m/"
            val idx = endpointUrl.indexOf(marker)
            return if (idx >= 0) {
                endpointUrl.substring(idx + marker.length)
                    .substringBefore('/')
                    .substringBefore('?')
            } else {
                ""
            }
        }

    /** True when [proxyToken] is missing or will expire within [bufferMs]. */
    fun isProxyTokenExpiringSoon(
        bufferMs: Long = 120_000L,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (proxyToken.isBlank()) return true
        if (proxyTokenExpiresAtMs <= 0L) return false
        return nowMs + bufferMs >= proxyTokenExpiresAtMs
    }

    /**
     * True when this runtime lacks a resolved proxy URL/token (for example, right after
     * `GET /tun/m/assignments`) or its proxy token is near expiry.
     */
    fun needsProxyResolution(nowMs: Long = System.currentTimeMillis()): Boolean {
        val isControlPlaneFallback = endpointUrl.isBlank() ||
            endpointUrl.contains("colab.research.google.com/tun/m/")
        return isControlPlaneFallback || isProxyTokenExpiringSoon(nowMs = nowMs)
    }
}

/** Result of running a cell / script on the allocated runtime. */
data class CellExecutionResult(
    val success: Boolean,
    val stdout: String,
    val stderr: String,
    /** HTTP status code from execution endpoint, or -1 if network-level failure. */
    val httpStatus: Int = 200,
    /** Raw response or diagnostic output from execution endpoint. */
    val rawResponse: String = ""
)

/** Runtime health / state as returned by checkRuntimeStatus. */
data class RuntimeStatus(
    val isAlive: Boolean,
    val state: String,
    val sessionId: String,
    val kernels: Int = 0,
    val connections: Int = 0,
    val lastActivity: String = ""
)

/** Compute unit balance, consumption rate, and GPU eligibility from Colab CCU API. */
data class ColabCcuInfo(
    val currentBalance: Double = 0.0,
    val consumptionRateHourly: Double = 0.0,
    val assignmentsCount: Int = 0,
    val eligibleGpus: List<String> = emptyList(),
    val eligibleTpus: List<String> = emptyList(),
    val remainingTokens: String = "",
    val nextRefillTimestampSec: Long = 0L
)

/** Jupyter kernel descriptor returned by `GET /api/kernels` or `POST /api/kernels`. */
data class ColabKernel(
    val id: String,
    val name: String = "python3",
    val executionState: String = "idle",
    val lastActivity: String = "",
    val connections: Int = 0
)

/** Jupyter terminal descriptor returned by `GET /api/terminals` or `POST /api/terminals`. */
data class ColabTerminal(
    val name: String,
    val lastActivity: String = ""
)

// ---------------------------------------------------------------------------
// Engine
// ---------------------------------------------------------------------------

/**
 * Authentic Google Colab REST & WebSocket API client with full `google-colab-cli` parity.
 *
 * Implements Google Colab's control-plane and runtime-proxy protocols:
 *  - Control plane (`/tun/m/assign` 2-step XSRF with `variant`/`accelerator`/`shape`,
 *    `/tun/m/assignments`, `/tun/m/runtime-proxy-token` hydration & TTL auto-refresh,
 *    `/tun/m/unassign/{endpoint}` 2-step XSRF teardown, `/tun/m/{endpoint}/keep-alive/`,
 *    `/tun/m/ccu-info`).
 *  - Runtime proxy (`/api/status`, `/api/kernels` full lifecycle, Jupyter v5.3 WebSocket
 *    code/shell execution with stale-kernel recovery, `/api/terminals`, and `/api/contents`
 *    text + binary base64 filesystem operations).
 *
 * [httpClientProvider] and [colabBaseUrl] exist as test seams; production uses the shared
 * [HttpClientProvider.apiClient] and `https://colab.research.google.com`.
 */
class ColabApiEngine(
    private val httpClientProvider: (() -> OkHttpClient)? = null,
    private val colabBaseUrl: String = COLAB_BASE
) {

    companion object {
        val INSTANCE: ColabApiEngine by lazy { ColabApiEngine() }

        fun getInstance(): ColabApiEngine = INSTANCE

        /** XSSI anti-json-hijacking prefix Google prepends to Colab API responses. */
        private const val XSSI_PREFIX = ")]}'\n"

        /** Base URL of the Colab tunnelling/assignment control plane. */
        private const val COLAB_BASE = "https://colab.research.google.com"

        /** Standard client-agent header expected by Colab's assignment/proxy endpoints. */
        private const val CLIENT_AGENT = "colab-cli"

        /** Tunnel header required by Colab keep-alive & proxy-token endpoints. */
        private const val COLAB_TUNNEL_HEADER = "Google"

        /** Default runtime proxy token TTL (1 hour) when backend omits explicit TTL. */
        private const val DEFAULT_PROXY_TOKEN_TTL_MS = 3_600_000L

        /** JSON media type. */
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /**
         * Converts a UUID to Google Colab's web-safe base64 notebook hash (nbh) format.
         */
        fun uuidToWebSafeBase64(uuid: UUID = UUID.randomUUID()): String {
            val str = uuid.toString().replace("-", "_")
            val padding = ".".repeat((44 - str.length).coerceAtLeast(0))
            return str + padding
        }

        /**
         * Unpacks a raw Bearer token from either a plain OAuth access token or a JSON token blob.
         */
        fun extractRawToken(raw: String): String {
            val candidate = raw.trim()
            if (candidate.startsWith("{")) {
                try {
                    val json = JSONObject(candidate)
                    val token = json.optString("token", "").ifBlank {
                        json.optString("access_token", "")
                    }
                    if (token.isNotBlank()) return token
                } catch (_: Exception) {}
            }
            return candidate
        }

        /**
         * Extracts the raw Bearer token from a [ColabAccount], unpacking JSON token blobs if necessary.
         */
        fun extractBearerToken(account: ColabAccount): String {
            val candidate = account.oauthToken.ifBlank { account.tokenOrCookie }
            return extractRawToken(candidate)
        }

        /**
         * Parses a Colab TTL value (e.g. `"3600s"`, `"3600"`, `3600`) into whole seconds.
         */
        internal fun parseTtlSeconds(raw: Any?): Long {
            return when (raw) {
                is Number -> raw.toLong().coerceAtLeast(0L)
                is String -> {
                    val clean = raw.trim().removeSuffix("s").removeSuffix("S").trim()
                    clean.toLongOrNull()?.coerceAtLeast(0L)
                        ?: clean.toDoubleOrNull()?.toLong()?.coerceAtLeast(0L)
                        ?: 0L
                }
                else -> 0L
            }
        }

        /**
         * Returns true when [expiryRaw] is blank, unparseable, or expires within [bufferSeconds].
         */
        internal fun isTokenExpiringSoon(expiryRaw: String, bufferSeconds: Long = 120L): Boolean {
            val trimmed = expiryRaw.trim()
            if (trimmed.isBlank()) return true
            val expiryInstant = try {
                val numeric = trimmed.toLongOrNull()
                when {
                    numeric != null && numeric > 10_000_000_000L -> Instant.ofEpochMilli(numeric)
                    numeric != null -> Instant.ofEpochSecond(numeric)
                    else -> Instant.parse(trimmed)
                }
            } catch (_: Exception) {
                return true
            }
            return Instant.now().plusSeconds(bufferSeconds).isAfter(expiryInstant)
        }

        /**
         * Percent-encodes each path segment for Jupyter Contents API URLs while preserving slashes.
         */
        internal fun encodeContentPath(path: String): String {
            val clean = path.trim().trimStart('/')
            if (clean.isEmpty()) return ""
            return clean.split('/')
                .filter { it.isNotEmpty() }
                .joinToString("/") { segment ->
                    URLEncoder.encode(segment, StandardCharsets.UTF_8.name())
                        .replace("+", "%20")
                }
        }

        /**
         * Refreshes an expired or near-expiry Google OAuth access token using its refresh_token
         * if present in [account]. Skips the network round-trip when the cached token is still fresh.
         * Updates [account] in place and returns the active access token string.
         */
        suspend fun refreshOAuthTokenIfNeeded(
            account: ColabAccount,
            client: OkHttpClient = HttpClientProvider.authClient,
            forceRefresh: Boolean = false
        ): String = withContext(Dispatchers.IO) {
            val rawToken = account.oauthToken.ifBlank { account.tokenOrCookie }.trim()
            if (!rawToken.startsWith("{")) return@withContext extractBearerToken(account)

            try {
                val json = JSONObject(rawToken)
                val existingToken = json.optString("token", "").ifBlank { json.optString("access_token", "") }
                val expiryStr = json.optString("expiry", "")

                if (!forceRefresh && existingToken.isNotBlank() && !isTokenExpiringSoon(expiryStr)) {
                    return@withContext existingToken
                }

                val refreshToken = json.optString("refresh_token", "")
                val clientId = json.optString("client_id", "")
                val clientSecret = json.optString("client_secret", "")
                val tokenUri = json.optString("token_uri", "https://oauth2.googleapis.com/token")

                if (refreshToken.isNotBlank() && clientId.isNotBlank()) {
                    val formBuilder = FormBody.Builder()
                        .add("client_id", clientId)
                        .add("grant_type", "refresh_token")
                        .add("refresh_token", refreshToken)

                    if (clientSecret.isNotBlank()) {
                        formBuilder.add("client_secret", clientSecret)
                    }

                    val req = Request.Builder()
                        .url(tokenUri)
                        .post(formBuilder.build())
                        .header("Accept", "application/json")
                        .build()

                    client.newCall(req).execute().use { resp ->
                        val respBody = resp.body?.string() ?: ""
                        if (resp.isSuccessful) {
                            val respJson = JSONObject(respBody)
                            val newAccessToken = respJson.optString("access_token", "")
                            if (newAccessToken.isNotBlank()) {
                                val expiresIn = respJson.optLong("expires_in", 3600L).coerceAtLeast(60L)
                                val newExpiry = Instant.now()
                                    .plusSeconds(expiresIn)
                                    .truncatedTo(ChronoUnit.SECONDS)
                                    .toString()
                                json.put("token", newAccessToken)
                                json.put("access_token", newAccessToken)
                                json.put("expiry", newExpiry)
                                val updatedJson = json.toString()
                                account.oauthToken = updatedJson
                                account.tokenOrCookie = newAccessToken
                                return@withContext newAccessToken
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}

            return@withContext extractBearerToken(account)
        }

        /**
         * Formats a raw script (e.g. bash bootstrapper) for safe execution in a Colab notebook cell.
         * Automatically prepends `%%bash\n` if the script contains bash commands and lacks cell magic.
         */
        fun formatScriptForExecution(rawScript: String): String {
            val trimmed = rawScript.trim()
            if (trimmed.isEmpty()) return ""
            if (trimmed.startsWith("%%bash") || trimmed.startsWith("import ") ||
                trimmed.startsWith("from ") || trimmed.startsWith("%")
            ) {
                return trimmed
            }
            return "%%bash\n$trimmed"
        }
    }

    private val controlPlaneBase: String = colabBaseUrl.trimEnd('/')

    private val client: OkHttpClient by lazy {
        httpClientProvider?.invoke() ?: HttpClientProvider.apiClient
    }

    /** Caches discovered/created Jupyter kernel IDs per normalized runtime endpoint URL. */
    private val kernelIdCache = ConcurrentHashMap<String, String>()

    fun invalidateKernelCache(endpointUrl: String) {
        val key = endpointUrl.trimEnd('/')
        if (key.isNotBlank()) {
            kernelIdCache.remove(key)
        }
    }

    internal fun getCachedKernelId(endpointUrl: String): String? =
        kernelIdCache[endpointUrl.trimEnd('/')]

    private fun Request.Builder.applyControlPlaneHeaders(
        cleanToken: String,
        xsrfToken: String? = null,
        includeTunnelHeader: Boolean = false
    ): Request.Builder {
        if (cleanToken.isNotBlank()) {
            header("Authorization", "Bearer $cleanToken")
        }
        header("Accept", "application/json")
        header("X-Colab-Client-Agent", CLIENT_AGENT)
        if (includeTunnelHeader) {
            header("X-Colab-Tunnel", COLAB_TUNNEL_HEADER)
        }
        if (!xsrfToken.isNullOrBlank()) {
            header("X-Goog-Colab-Token", xsrfToken)
        }
        return this
    }

    private fun Request.Builder.applyProxyAuthHeaders(
        runtime: ColabRuntime,
        bearerToken: String = ""
    ): Request.Builder {
        val cleanBearer = extractRawToken(bearerToken)
        if (runtime.proxyToken.isNotBlank()) {
            header("Authorization", "Bearer ${runtime.proxyToken}")
            header("X-Colab-Runtime-Proxy-Token", runtime.proxyToken)
        } else if (cleanBearer.isNotBlank()) {
            header("Authorization", "Bearer $cleanBearer")
        }
        header("X-Colab-Client-Agent", CLIENT_AGENT)
        header("Accept", "application/json")
        return this
    }

    // ---------------------------------------------------------------------------
    // Control Plane API (Colab Assignment, Proxy Token, Unassign, Keep-Alive, CCU)
    // ---------------------------------------------------------------------------

    /**
     * Allocates a new Google Colab runtime using [bearerToken] via the 2-step XSRF
     * `/tun/m/assign` handshake (matching `google-colab-cli` / `colab-vscode`).
     *
     * Automatically sends `variant=GPU` (or `TPU`) alongside `accelerator=` and optional
     * `shape=hm` for High-RAM runtimes. Handles HTTP 412 quota-cap and outcome denials.
     */
    suspend fun allocateRuntime(
        bearerToken: String,
        accelerator: ColabAccelerator = ColabAccelerator.T4,
        authUser: Int = 0,
        variant: ColabVariant? = null,
        acceleratorOverride: String? = null,
        highMemory: Boolean = false
    ): Result<ColabRuntime> = withContext(Dispatchers.IO) {
        try {
            val cleanToken = extractRawToken(bearerToken)
            val nbh = uuidToWebSafeBase64(UUID.randomUUID())
            val overrideAcc = acceleratorOverride?.trim()?.uppercase()?.takeIf { it.isNotBlank() }

            val resolvedVariant = variant ?: when {
                overrideAcc != null -> {
                    if (overrideAcc.startsWith("V2") || overrideAcc.startsWith("V3") ||
                        overrideAcc.startsWith("V5") || overrideAcc.startsWith("TPU")
                    ) {
                        ColabVariant.TPU
                    } else {
                        ColabVariant.GPU
                    }
                }
                accelerator != ColabAccelerator.NONE -> ColabVariant.GPU
                else -> ColabVariant.DEFAULT
            }

            val resolvedAccelerator = overrideAcc ?: accelerator.apiValue.takeIf { accelerator != ColabAccelerator.NONE }

            val queryParts = mutableListOf(
                "nbh=${URLEncoder.encode(nbh, StandardCharsets.UTF_8.name())}",
                "authuser=$authUser"
            )
            if (resolvedVariant != ColabVariant.DEFAULT) {
                queryParts.add("variant=${resolvedVariant.apiValue}")
            }
            if (!resolvedAccelerator.isNullOrBlank()) {
                queryParts.add("accelerator=${URLEncoder.encode(resolvedAccelerator, StandardCharsets.UTF_8.name())}")
            }
            if (highMemory) {
                queryParts.add("shape=hm")
            }

            val assignUrl = "$controlPlaneBase/tun/m/assign?${queryParts.joinToString("&")}"

            // Step 1: GET /tun/m/assign to acquire XSRF token or retrieve existing assignment
            val getRequest = Request.Builder()
                .url(assignUrl)
                .get()
                .applyControlPlaneHeaders(cleanToken)
                .build()

            client.newCall(getRequest).execute().use { getResp ->
                val getRaw = getResp.body?.string() ?: ""
                if (getResp.code == 412) {
                    return@withContext Result.failure(
                        Exception("Too many active Colab runtimes (HTTP 412). Unassign an existing runtime before allocating a new one.")
                    )
                }
                if (!getResp.isSuccessful) {
                    val errMsg = extractErrorMessage(getRaw)
                    return@withContext Result.failure(
                        Exception("Colab runtime assignment pre-check failed (HTTP ${getResp.code}): $errMsg")
                    )
                }

                val getBody = stripXssiPrefix(getRaw)
                val getJson = try { JSONObject(getBody) } catch (_: Exception) { JSONObject() }

                validateAssignOutcome(getJson)?.let { denial ->
                    return@withContext Result.failure(Exception(denial))
                }

                // If already assigned with endpoint & runtimeProxyInfo, return immediately
                if (getJson.has("endpoint") &&
                    (getJson.has("runtimeProxyInfo") || getJson.has("runtime_proxy_info"))
                ) {
                    return@withContext Result.success(parseAssignResponse(getBody, getRaw))
                }

                val xsrfToken = getJson.optString("token", "")
                if (xsrfToken.isBlank()) {
                    return@withContext Result.failure(
                        Exception("Colab assignment pre-check did not return XSRF token: $getRaw")
                    )
                }

                // Step 2: POST /tun/m/assign with X-Goog-Colab-Token to provision runtime
                val postRequest = Request.Builder()
                    .url(assignUrl)
                    .post("".toRequestBody(null))
                    .applyControlPlaneHeaders(cleanToken, xsrfToken = xsrfToken)
                    .build()

                client.newCall(postRequest).execute().use { postResp ->
                    val postRaw = postResp.body?.string() ?: ""
                    if (postResp.code == 412) {
                        return@withContext Result.failure(
                            Exception("Too many active Colab runtimes (HTTP 412). Unassign an existing runtime before allocating a new one.")
                        )
                    }
                    if (!postResp.isSuccessful) {
                        val errMsg = extractErrorMessage(postRaw)
                        return@withContext Result.failure(
                            Exception("Colab runtime allocation failed (HTTP ${postResp.code}): $errMsg")
                        )
                    }

                    val postBody = stripXssiPrefix(postRaw)
                    val postJson = try { JSONObject(postBody) } catch (_: Exception) { JSONObject() }
                    validateAssignOutcome(postJson)?.let { denial ->
                        return@withContext Result.failure(Exception(denial))
                    }

                    Result.success(parseAssignResponse(postBody, postRaw))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(Exception("allocateRuntime network error: ${e.message}", e))
        }
    }

    /**
     * Fetches a fresh runtime proxy URL (`*.prod.colab.dev`) and proxy authorization token
     * from `GET /tun/m/runtime-proxy-token?endpoint={id}&port={port}&authuser={authUser}`.
     *
     * Required to hydrate runtimes returned by [listAssignments] and to refresh expiring
     * 1-hour runtime proxy tokens during long-lived sessions.
     */
    suspend fun refreshRuntimeProxyToken(
        bearerToken: String,
        runtime: ColabRuntime,
        port: Int = 8080,
        authUser: Int = 0
    ): Result<ColabRuntime> = withContext(Dispatchers.IO) {
        try {
            val cleanToken = extractRawToken(bearerToken)
            val epId = runtime.endpointId
            if (cleanToken.isBlank()) {
                return@withContext Result.failure(Exception("refreshRuntimeProxyToken requires a valid bearer token"))
            }
            if (epId.isBlank()) {
                return@withContext Result.failure(Exception("refreshRuntimeProxyToken requires a non-blank runtime endpoint ID"))
            }

            val encodedEp = URLEncoder.encode(epId, StandardCharsets.UTF_8.name())
            val url = "$controlPlaneBase/tun/m/runtime-proxy-token?endpoint=$encodedEp&port=$port&authuser=$authUser"

            val request = Request.Builder()
                .url(url)
                .get()
                .applyControlPlaneHeaders(cleanToken, includeTunnelHeader = true)
                .build()

            client.newCall(request).execute().use { response ->
                val rawBody = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    val errMsg = extractErrorMessage(rawBody)
                    return@withContext Result.failure(
                        Exception("refreshRuntimeProxyToken failed (HTTP ${response.code}): $errMsg")
                    )
                }

                val body = stripXssiPrefix(rawBody)
                val json = JSONObject(body)
                val token = json.optString("token", "")
                val proxyUrl = json.optString("url", "")
                    .ifBlank { json.optString("proxyUrl", "") }
                    .ifBlank { json.optString("proxy_url", "") }

                if (token.isBlank() && proxyUrl.isBlank()) {
                    return@withContext Result.failure(
                        Exception("refreshRuntimeProxyToken returned empty token and URL: $rawBody")
                    )
                }

                val ttlSec = parseTtlSeconds(
                    json.opt("tokenTtl")
                        ?: json.opt("token_ttl")
                        ?: json.opt("tokenExpiresInSeconds")
                        ?: json.opt("token_expires_in_seconds")
                )
                val expiresAtMs = if (ttlSec > 0L) {
                    System.currentTimeMillis() + ttlSec * 1000L
                } else {
                    System.currentTimeMillis() + DEFAULT_PROXY_TOKEN_TTL_MS
                }

                val updated = runtime.copy(
                    endpointUrl = proxyUrl.ifBlank { runtime.endpointUrl },
                    proxyToken = token.ifBlank { runtime.proxyToken },
                    proxyTokenExpiresAtMs = expiresAtMs
                )
                Result.success(updated)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(Exception("refreshRuntimeProxyToken network error: ${e.message}", e))
        }
    }

    /**
     * Ensures [runtime] has a valid runtime proxy URL and a non-expiring proxy token,
     * refreshing via [refreshRuntimeProxyToken] only when needed.
     */
    suspend fun ensureRuntimeProxyReady(
        bearerToken: String,
        runtime: ColabRuntime,
        port: Int = 8080,
        authUser: Int = 0
    ): Result<ColabRuntime> {
        if (!runtime.needsProxyResolution()) {
            return Result.success(runtime)
        }
        val cleanToken = extractRawToken(bearerToken)
        if (cleanToken.isBlank() || runtime.endpointId.isBlank()) {
            return Result.success(runtime)
        }
        return refreshRuntimeProxyToken(cleanToken, runtime, port, authUser)
    }

    /**
     * Unassigns (releases) a Colab runtime via the authentic 2-step XSRF flow:
     *  1. `GET /tun/m/unassign/{endpointId}?authuser={authUser}` -> extracts XSRF `token`
     *  2. `POST /tun/m/unassign/{endpointId}?authuser={authUser}` with `X-Goog-Colab-Token`
     */
    suspend fun unassignRuntime(
        bearerToken: String,
        endpointId: String,
        authUser: Int = 0
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val cleanToken = extractRawToken(bearerToken)
            val cleanEp = endpointId.trim()
            if (cleanToken.isBlank()) {
                return@withContext Result.failure(Exception("unassignRuntime requires a valid bearer token"))
            }
            if (cleanEp.isBlank()) {
                return@withContext Result.failure(Exception("unassignRuntime requires a non-blank endpointId"))
            }

            val encodedEp = URLEncoder.encode(cleanEp, StandardCharsets.UTF_8.name())
            val unassignUrl = "$controlPlaneBase/tun/m/unassign/$encodedEp?authuser=$authUser"

            val getReq = Request.Builder()
                .url(unassignUrl)
                .get()
                .applyControlPlaneHeaders(cleanToken)
                .build()

            val xsrfToken: String = client.newCall(getReq).execute().use { getResp ->
                if (getResp.code == 404) {
                    return@withContext Result.success(Unit)
                }
                val getRaw = getResp.body?.string() ?: ""
                if (!getResp.isSuccessful) {
                    val errMsg = extractErrorMessage(getRaw)
                    return@withContext Result.failure(
                        Exception("unassignRuntime pre-check failed (HTTP ${getResp.code}): $errMsg")
                    )
                }
                val getJson = try { JSONObject(stripXssiPrefix(getRaw)) } catch (_: Exception) { JSONObject() }
                getJson.optString("token", "")
            }

            if (xsrfToken.isBlank()) {
                return@withContext Result.failure(
                    Exception("unassignRuntime pre-check did not return XSRF token for $cleanEp")
                )
            }

            val postReq = Request.Builder()
                .url(unassignUrl)
                .post("".toRequestBody(null))
                .applyControlPlaneHeaders(cleanToken, xsrfToken = xsrfToken)
                .build()

            client.newCall(postReq).execute().use { postResp ->
                if (postResp.isSuccessful || postResp.code == 404) {
                    return@withContext Result.success(Unit)
                }
                val errRaw = postResp.body?.string() ?: ""
                Result.failure(
                    Exception("unassignRuntime failed (HTTP ${postResp.code}): ${extractErrorMessage(errRaw)}")
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(Exception("unassignRuntime network error: ${e.message}", e))
        }
    }

    /**
     * Sends a keep-alive heartbeat to `GET /tun/m/{endpointId}/keep-alive/?authuser={authUser}`
     * with `X-Colab-Tunnel: Google` so Google Colab does not reclaim an idle runtime.
     */
    suspend fun keepAlive(
        bearerToken: String,
        runtime: ColabRuntime,
        authUser: Int = 0
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val cleanToken = extractRawToken(bearerToken)
            val epId = runtime.endpointId
            if (cleanToken.isBlank() || epId.isBlank()) {
                return@withContext Result.failure(Exception("keepAlive requires bearerToken and runtime endpointId"))
            }

            val encodedEp = URLEncoder.encode(epId, StandardCharsets.UTF_8.name())
            val url = "$controlPlaneBase/tun/m/$encodedEp/keep-alive/?authuser=$authUser"

            val req = Request.Builder()
                .url(url)
                .get()
                .applyControlPlaneHeaders(cleanToken, includeTunnelHeader = true)
                .build()

            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) {
                    val raw = response.body?.string() ?: ""
                    return@withContext Result.failure(
                        Exception("keepAlive failed (HTTP ${response.code}): ${extractErrorMessage(raw)}")
                    )
                }
                Result.success(true)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(Exception("keepAlive network error: ${e.message}", e))
        }
    }

    /**
     * Terminates an active runtime session and evicts cached kernel IDs.
     *
     * When [bearerToken] is provided, executes the authentic 2-step XSRF control-plane
     * unassign (`GET` + `POST /tun/m/unassign/{endpointId}`) so Google Colab releases the VM
     * and stops consuming compute units. Falls back to proxy `DELETE {endpointUrl}/session/{id}`
     * when only proxy credentials are available.
     */
    suspend fun terminateRuntime(
        bearerToken: String,
        runtime: ColabRuntime,
        authUser: Int = 0
    ): Result<Unit> = withContext(Dispatchers.IO) {
        invalidateKernelCache(runtime.endpointUrl)
        val cleanToken = extractRawToken(bearerToken)
        val epId = runtime.endpointId

        if (cleanToken.isNotBlank() && epId.isNotBlank()) {
            return@withContext unassignRuntime(cleanToken, epId, authUser)
        }

        try {
            val endpoint = runtime.endpointUrl.trimEnd('/')
            if (endpoint.isBlank()) {
                return@withContext Result.failure(Exception("terminateRuntime: endpointUrl is blank"))
            }
            val terminateUrl = "$endpoint/session/${runtime.sessionId}"
            val request = Request.Builder()
                .url(terminateUrl)
                .delete()
                .applyProxyAuthHeaders(runtime, cleanToken)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful && response.code != 404) {
                    val errBody = response.body?.string() ?: ""
                    return@withContext Result.failure(
                        Exception("terminateRuntime failed (HTTP ${response.code}): $errBody")
                    )
                }
                Result.success(Unit)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(Exception("terminateRuntime network error: ${e.message}", e))
        }
    }

    /**
     * Lists active Colab runtime assignments for the authenticated Google account.
     * Calls `GET /tun/m/assignments?authuser={authUser}`.
     *
     * Parses both `{"assignments": [...]}` and top-level JSON array `[...]` payloads.
     * When [hydrateProxyTokens] is true, resolves each assignment via [ensureRuntimeProxyReady]
     * so returned runtimes include live `*.prod.colab.dev` URLs and fresh proxy tokens.
     */
    suspend fun listAssignments(
        bearerToken: String,
        authUser: Int = 0,
        hydrateProxyTokens: Boolean = false
    ): Result<List<ColabRuntime>> = withContext(Dispatchers.IO) {
        try {
            val cleanToken = extractRawToken(bearerToken)
            val url = "$controlPlaneBase/tun/m/assignments?authuser=$authUser"
            val request = Request.Builder()
                .url(url)
                .get()
                .applyControlPlaneHeaders(cleanToken)
                .build()

            val parsedRuntimes = client.newCall(request).execute().use { response ->
                val rawBody = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        Exception("listAssignments failed (HTTP ${response.code}): ${extractErrorMessage(rawBody)}")
                    )
                }
                val body = stripXssiPrefix(rawBody).trim()
                val assignmentsArray: JSONArray? = when {
                    body.startsWith("[") -> JSONArray(body)
                    body.startsWith("{") -> JSONObject(body).optJSONArray("assignments")
                    else -> null
                }
                val list = mutableListOf<ColabRuntime>()
                if (assignmentsArray != null) {
                    for (i in 0 until assignmentsArray.length()) {
                        val item = assignmentsArray.optJSONObject(i) ?: continue
                        val serialized = item.toString()
                        list.add(parseAssignResponse(serialized, serialized))
                    }
                }
                list
            }

            if (!hydrateProxyTokens) {
                return@withContext Result.success(parsedRuntimes)
            }

            val hydrated = parsedRuntimes.map { runtime ->
                ensureRuntimeProxyReady(cleanToken, runtime, authUser = authUser).getOrDefault(runtime)
            }
            Result.success(hydrated)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(Exception("listAssignments network error: ${e.message}", e))
        }
    }

    /**
     * Queries compute units balance, hourly consumption rate, and accelerator eligibility.
     * Calls `GET /tun/m/ccu-info?authuser={authUser}`.
     */
    suspend fun getCcuInfo(
        bearerToken: String,
        authUser: Int = 0
    ): Result<ColabCcuInfo> = withContext(Dispatchers.IO) {
        try {
            val cleanToken = extractRawToken(bearerToken)
            val url = "$controlPlaneBase/tun/m/ccu-info?authuser=$authUser"
            val req = Request.Builder()
                .url(url)
                .get()
                .applyControlPlaneHeaders(cleanToken)
                .build()

            client.newCall(req).execute().use { response ->
                val rawBody = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        Exception("getCcuInfo failed (HTTP ${response.code}): ${extractErrorMessage(rawBody)}")
                    )
                }
                Result.success(parseCcuInfo(rawBody))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(Exception("getCcuInfo network error: ${e.message}", e))
        }
    }

    // ---------------------------------------------------------------------------
    // Runtime Proxy: Status, Kernels, Terminals, Execution
    // ---------------------------------------------------------------------------

    /**
     * Queries the status of an existing runtime session.
     *
     * Calls `GET {endpointUrl}/api/status` (Jupyter Server / Colab proxy standard) and falls
     * back to `GET {endpointUrl}/status` if `/api/status` returns 404.
     */
    suspend fun checkRuntimeStatus(
        bearerToken: String,
        runtime: ColabRuntime
    ): Result<RuntimeStatus> = withContext(Dispatchers.IO) {
        try {
            val endpoint = runtime.endpointUrl.trimEnd('/')
            if (endpoint.isBlank()) {
                return@withContext Result.failure(Exception("checkRuntimeStatus: endpointUrl is blank"))
            }

            val apiStatusReq = Request.Builder()
                .url("$endpoint/api/status")
                .get()
                .applyProxyAuthHeaders(runtime, bearerToken)
                .build()

            client.newCall(apiStatusReq).execute().use { response ->
                if (response.code != 404) {
                    val rawBody = response.body?.string() ?: ""
                    val body = stripXssiPrefix(rawBody)
                    return@withContext Result.success(
                        parseRuntimeStatus(body, runtime.sessionId, response.isSuccessful)
                    )
                }
            }

            val legacyReq = Request.Builder()
                .url("$endpoint/status")
                .get()
                .applyProxyAuthHeaders(runtime, bearerToken)
                .build()

            client.newCall(legacyReq).execute().use { response ->
                val rawBody = response.body?.string() ?: ""
                val body = stripXssiPrefix(rawBody)
                Result.success(parseRuntimeStatus(body, runtime.sessionId, response.isSuccessful))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(Exception("checkRuntimeStatus network error: ${e.message}", e))
        }
    }

    /** Lists active Jupyter kernels on [runtime] (`GET /api/kernels`). */
    suspend fun listKernels(
        runtime: ColabRuntime,
        bearerToken: String = ""
    ): Result<List<ColabKernel>> = withContext(Dispatchers.IO) {
        try {
            val endpoint = runtime.endpointUrl.trimEnd('/')
            if (endpoint.isBlank()) return@withContext Result.failure(Exception("endpointUrl is blank"))

            val req = Request.Builder()
                .url("$endpoint/api/kernels")
                .get()
                .applyProxyAuthHeaders(runtime, bearerToken)
                .build()

            client.newCall(req).execute().use { response ->
                val rawBody = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("listKernels failed (HTTP ${response.code}): $rawBody"))
                }
                val array = JSONArray(stripXssiPrefix(rawBody))
                val kernels = ArrayList<ColabKernel>(array.length())
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val id = obj.optString("id", "")
                    if (id.isBlank()) continue
                    kernels.add(parseKernel(obj))
                }
                Result.success(kernels)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(Exception("listKernels network error: ${e.message}", e))
        }
    }

    /** Creates a new Jupyter kernel on [runtime] (`POST /api/kernels`). */
    suspend fun createKernel(
        runtime: ColabRuntime,
        name: String = "python3",
        bearerToken: String = ""
    ): Result<ColabKernel> = withContext(Dispatchers.IO) {
        try {
            val endpoint = runtime.endpointUrl.trimEnd('/')
            if (endpoint.isBlank()) return@withContext Result.failure(Exception("endpointUrl is blank"))

            val payload = JSONObject().put("name", name).toString()
            val req = Request.Builder()
                .url("$endpoint/api/kernels")
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .applyProxyAuthHeaders(runtime, bearerToken)
                .build()

            client.newCall(req).execute().use { response ->
                val rawBody = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("createKernel failed (HTTP ${response.code}): $rawBody"))
                }
                val kernel = parseKernel(JSONObject(stripXssiPrefix(rawBody)))
                if (kernel.id.isNotBlank()) {
                    kernelIdCache[endpoint] = kernel.id
                }
                Result.success(kernel)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(Exception("createKernel network error: ${e.message}", e))
        }
    }

    /** Sends an interrupt signal to a running kernel (`POST /api/kernels/{id}/interrupt`). */
    suspend fun interruptKernel(
        runtime: ColabRuntime,
        kernelId: String? = null,
        bearerToken: String = ""
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val endpoint = runtime.endpointUrl.trimEnd('/')
            if (endpoint.isBlank()) return@withContext Result.failure(Exception("endpointUrl is blank"))
            val targetId = kernelId?.takeIf { it.isNotBlank() }
                ?: kernelIdCache[endpoint]
                ?: listKernels(runtime, bearerToken).getOrNull()?.firstOrNull()?.id
                ?: return@withContext Result.failure(Exception("No active kernel found to interrupt"))

            val req = Request.Builder()
                .url("$endpoint/api/kernels/$targetId/interrupt")
                .post("".toRequestBody(null))
                .applyProxyAuthHeaders(runtime, bearerToken)
                .build()

            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) {
                    val rawBody = response.body?.string() ?: ""
                    return@withContext Result.failure(Exception("interruptKernel failed (HTTP ${response.code}): $rawBody"))
                }
                Result.success(Unit)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(Exception("interruptKernel network error: ${e.message}", e))
        }
    }

    /** Restarts a Jupyter kernel (`POST /api/kernels/{id}/restart`). */
    suspend fun restartKernel(
        runtime: ColabRuntime,
        kernelId: String? = null,
        bearerToken: String = ""
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val endpoint = runtime.endpointUrl.trimEnd('/')
            if (endpoint.isBlank()) return@withContext Result.failure(Exception("endpointUrl is blank"))
            val targetId = kernelId?.takeIf { it.isNotBlank() }
                ?: kernelIdCache[endpoint]
                ?: listKernels(runtime, bearerToken).getOrNull()?.firstOrNull()?.id
                ?: return@withContext Result.failure(Exception("No active kernel found to restart"))

            val req = Request.Builder()
                .url("$endpoint/api/kernels/$targetId/restart")
                .post("".toRequestBody(null))
                .applyProxyAuthHeaders(runtime, bearerToken)
                .build()

            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) {
                    val rawBody = response.body?.string() ?: ""
                    return@withContext Result.failure(Exception("restartKernel failed (HTTP ${response.code}): $rawBody"))
                }
                Result.success(Unit)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(Exception("restartKernel network error: ${e.message}", e))
        }
    }

    /** Deletes/shuts down a Jupyter kernel (`DELETE /api/kernels/{id}`) and evicts cache if matched. */
    suspend fun deleteKernel(
        runtime: ColabRuntime,
        kernelId: String,
        bearerToken: String = ""
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val endpoint = runtime.endpointUrl.trimEnd('/')
            if (endpoint.isBlank()) return@withContext Result.failure(Exception("endpointUrl is blank"))
            if (kernelId.isBlank()) return@withContext Result.failure(Exception("kernelId is blank"))

            if (kernelIdCache[endpoint] == kernelId) {
                kernelIdCache.remove(endpoint)
            }

            val req = Request.Builder()
                .url("$endpoint/api/kernels/$kernelId")
                .delete()
                .applyProxyAuthHeaders(runtime, bearerToken)
                .build()

            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful && response.code != 404) {
                    val rawBody = response.body?.string() ?: ""
                    return@withContext Result.failure(Exception("deleteKernel failed (HTTP ${response.code}): $rawBody"))
                }
                Result.success(Unit)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(Exception("deleteKernel network error: ${e.message}", e))
        }
    }

    /** Lists active Jupyter terminals (`GET /api/terminals`). */
    suspend fun listTerminals(
        runtime: ColabRuntime,
        bearerToken: String = ""
    ): Result<List<ColabTerminal>> = withContext(Dispatchers.IO) {
        try {
            val endpoint = runtime.endpointUrl.trimEnd('/')
            if (endpoint.isBlank()) return@withContext Result.failure(Exception("endpointUrl is blank"))

            val req = Request.Builder()
                .url("$endpoint/api/terminals")
                .get()
                .applyProxyAuthHeaders(runtime, bearerToken)
                .build()

            client.newCall(req).execute().use { response ->
                val rawBody = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("listTerminals failed (HTTP ${response.code}): $rawBody"))
                }
                val array = JSONArray(stripXssiPrefix(rawBody))
                val terminals = ArrayList<ColabTerminal>(array.length())
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val name = obj.optString("name", "")
                    if (name.isBlank()) continue
                    terminals.add(ColabTerminal(name = name, lastActivity = obj.optString("last_activity", "")))
                }
                Result.success(terminals)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(Exception("listTerminals network error: ${e.message}", e))
        }
    }

    /** Creates a new Jupyter terminal session (`POST /api/terminals`). */
    suspend fun createTerminal(
        runtime: ColabRuntime,
        bearerToken: String = ""
    ): Result<ColabTerminal> = withContext(Dispatchers.IO) {
        try {
            val endpoint = runtime.endpointUrl.trimEnd('/')
            if (endpoint.isBlank()) return@withContext Result.failure(Exception("endpointUrl is blank"))

            val req = Request.Builder()
                .url("$endpoint/api/terminals")
                .post("{}".toRequestBody(JSON_MEDIA_TYPE))
                .applyProxyAuthHeaders(runtime, bearerToken)
                .build()

            client.newCall(req).execute().use { response ->
                val rawBody = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("createTerminal failed (HTTP ${response.code}): $rawBody"))
                }
                val obj = JSONObject(stripXssiPrefix(rawBody))
                Result.success(
                    ColabTerminal(
                        name = obj.optString("name", ""),
                        lastActivity = obj.optString("last_activity", "")
                    )
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(Exception("createTerminal network error: ${e.message}", e))
        }
    }

    /** Deletes a Jupyter terminal session (`DELETE /api/terminals/{name}`). */
    suspend fun deleteTerminal(
        runtime: ColabRuntime,
        name: String,
        bearerToken: String = ""
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val endpoint = runtime.endpointUrl.trimEnd('/')
            if (endpoint.isBlank()) return@withContext Result.failure(Exception("endpointUrl is blank"))
            if (name.isBlank()) return@withContext Result.failure(Exception("terminal name is blank"))

            val encodedName = URLEncoder.encode(name, StandardCharsets.UTF_8.name())
            val req = Request.Builder()
                .url("$endpoint/api/terminals/$encodedName")
                .delete()
                .applyProxyAuthHeaders(runtime, bearerToken)
                .build()

            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful && response.code != 404) {
                    val rawBody = response.body?.string() ?: ""
                    return@withContext Result.failure(Exception("deleteTerminal failed (HTTP ${response.code}): $rawBody"))
                }
                Result.success(Unit)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(Exception("deleteTerminal network error: ${e.message}", e))
        }
    }

    private suspend fun resolveOrCreateKernelId(
        runtime: ColabRuntime,
        bearerToken: String,
        forceRefresh: Boolean
    ): Pair<String, Boolean> {
        val endpoint = runtime.endpointUrl.trimEnd('/')
        if (!forceRefresh) {
            kernelIdCache[endpoint]?.takeIf { it.isNotBlank() }?.let { cached ->
                return Pair(cached, true)
            }
        }

        val existingKernels = listKernels(runtime, bearerToken).getOrNull().orEmpty()
        val chosen = existingKernels.firstOrNull { it.executionState == "idle" }
            ?: existingKernels.firstOrNull()
        if (chosen != null && chosen.id.isNotBlank()) {
            kernelIdCache[endpoint] = chosen.id
            return Pair(chosen.id, false)
        }

        val created = createKernel(runtime, name = "python3", bearerToken = bearerToken).getOrNull()
        if (created != null && created.id.isNotBlank()) {
            kernelIdCache[endpoint] = created.id
            return Pair(created.id, false)
        }

        val fallback = runtime.sessionId.ifBlank { UUID.randomUUID().toString() }
        return Pair(fallback, false)
    }

    private suspend fun executeOnKernelWebSocket(
        bearerToken: String,
        runtime: ColabRuntime,
        kernelId: String,
        code: String,
        timeoutMs: Long
    ): Pair<CellExecutionResult, Boolean> {
        val endpoint = runtime.endpointUrl.trimEnd('/')
        val wsBase = endpoint
            .replace("https://", "wss://")
            .replace("http://", "ws://")
        val sessionId = UUID.randomUUID().toString()
        val wsUrl = if (runtime.proxyToken.isNotBlank()) {
            val encodedToken = URLEncoder.encode(runtime.proxyToken, StandardCharsets.UTF_8.name())
            "$wsBase/api/kernels/$kernelId/channels?session_id=$sessionId&token=$encodedToken&colab-runtime-proxy-token=$encodedToken"
        } else {
            "$wsBase/api/kernels/$kernelId/channels?session_id=$sessionId"
        }

        val wsReq = Request.Builder()
            .url(wsUrl)
            .applyProxyAuthHeaders(runtime, bearerToken)
            .build()

        val msgId = UUID.randomUUID().toString()
        val execRequest = JSONObject().apply {
            put("header", JSONObject().apply {
                put("msg_id", msgId)
                put("username", "colab")
                put("session", sessionId)
                put("msg_type", "execute_request")
                put("version", "5.3")
            })
            put("parent_header", JSONObject())
            put("metadata", JSONObject())
            put("content", JSONObject().apply {
                put("code", code)
                put("silent", false)
                put("store_history", true)
                put("user_expressions", JSONObject())
                put("allow_stdin", false)
                put("stop_on_error", true)
            })
            put("channel", "shell")
        }

        val deferredResult = CompletableDeferred<Pair<CellExecutionResult, Boolean>>()
        val bufferLock = Any()
        val stdoutBuilder = StringBuilder()
        val stderrBuilder = StringBuilder()
        var hasKernelError = false

        val webSocket = client.newWebSocket(wsReq, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(execRequest.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val msg = JSONObject(text)
                    val msgType = msg.optString("msg_type", "")
                    val content = msg.optJSONObject("content") ?: JSONObject()
                    val parentMsgId = msg.optJSONObject("parent_header")?.optString("msg_id", "") ?: ""

                    if (parentMsgId == msgId || parentMsgId.isBlank()) {
                        when (msgType) {
                            "stream" -> {
                                val name = content.optString("name", "stdout")
                                val textOut = content.optString("text", "")
                                synchronized(bufferLock) {
                                    if (name == "stderr") {
                                        stderrBuilder.append(textOut)
                                    } else {
                                        stdoutBuilder.append(textOut)
                                    }
                                }
                            }
                            "execute_result", "display_data" -> {
                                val data = content.optJSONObject("data")
                                val plainText = data?.optString("text/plain", "") ?: ""
                                if (plainText.isNotBlank()) {
                                    synchronized(bufferLock) {
                                        stdoutBuilder.append(plainText).append("\n")
                                    }
                                }
                            }
                            "error" -> {
                                val ename = content.optString("ename", "Error")
                                val evalue = content.optString("evalue", "")
                                val tbArray = content.optJSONArray("traceback")
                                val tb = if (tbArray != null && tbArray.length() > 0) {
                                    (0 until tbArray.length()).joinToString("\n") { tbArray.getString(it) }
                                } else "$ename: $evalue"
                                synchronized(bufferLock) {
                                    hasKernelError = true
                                    stderrBuilder.append(tb).append("\n")
                                }
                            }
                            "execute_reply" -> {
                                if (content.optString("status", "") == "error") {
                                    val ename = content.optString("ename", "Error")
                                    val evalue = content.optString("evalue", "")
                                    synchronized(bufferLock) {
                                        hasKernelError = true
                                        if (stderrBuilder.isEmpty()) {
                                            stderrBuilder.append("$ename: $evalue\n")
                                        }
                                    }
                                }
                            }
                            "status" -> {
                                val state = content.optString("execution_state", "")
                                if (state == "idle" && parentMsgId == msgId) {
                                    webSocket.close(1000, "Done")
                                    if (!deferredResult.isCompleted) {
                                        val result = synchronized(bufferLock) {
                                            CellExecutionResult(
                                                success = !hasKernelError,
                                                stdout = stdoutBuilder.toString().trim(),
                                                stderr = stderrBuilder.toString().trim(),
                                                httpStatus = 200,
                                                rawResponse = text
                                            )
                                        }
                                        deferredResult.complete(Pair(result, false))
                                    }
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!deferredResult.isCompleted) {
                    kernelIdCache.remove(endpoint, kernelId)
                    val result = synchronized(bufferLock) {
                        val existingErr = stderrBuilder.toString().trim()
                        val combinedErr = if (existingErr.isBlank()) {
                            "WebSocket failure: ${t.message}"
                        } else {
                            "WebSocket failure: ${t.message}\n$existingErr"
                        }
                        CellExecutionResult(
                            success = false,
                            stdout = stdoutBuilder.toString().trim(),
                            stderr = combinedErr,
                            httpStatus = response?.code ?: 500,
                            rawResponse = t.message ?: ""
                        )
                    }
                    deferredResult.complete(Pair(result, true))
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!deferredResult.isCompleted) {
                    val result = synchronized(bufferLock) {
                        CellExecutionResult(
                            success = !hasKernelError && stderrBuilder.isEmpty(),
                            stdout = stdoutBuilder.toString().trim(),
                            stderr = stderrBuilder.toString().trim(),
                            httpStatus = 200,
                            rawResponse = reason
                        )
                    }
                    deferredResult.complete(Pair(result, false))
                }
            }
        })

        return try {
            withTimeoutOrNull(timeoutMs) {
                deferredResult.await()
            } ?: run {
                webSocket.cancel()
                kernelIdCache.remove(endpoint)
                val timedOut = synchronized(bufferLock) {
                    CellExecutionResult(
                        success = false,
                        stdout = stdoutBuilder.toString().trim(),
                        stderr = "Execution timed out waiting for Colab kernel",
                        httpStatus = 504,
                        rawResponse = ""
                    )
                }
                Pair(timedOut, false)
            }
        } catch (e: CancellationException) {
            webSocket.cancel()
            throw e
        }
    }

    /**
     * Runs [code] on the allocated [runtime] via standard Jupyter Kernel v5.3 WebSocket protocol.
     *
     * Automatically reuses a cached kernel ID and retries once with a newly discovered/created
     * kernel if the cached kernel has died or was culled on Colab.
     */
    suspend fun runCode(
        bearerToken: String,
        runtime: ColabRuntime,
        code: String,
        timeoutMs: Long = 60_000L
    ): Result<CellExecutionResult> = withContext(Dispatchers.IO) {
        try {
            val endpoint = runtime.endpointUrl.trimEnd('/')
            if (endpoint.isBlank()) {
                return@withContext Result.failure(Exception("Cannot run code: endpointUrl is blank in ColabRuntime"))
            }

            val (kernelId, wasCached) = resolveOrCreateKernelId(runtime, bearerToken, forceRefresh = false)
            val (firstResult, isTransportFailure) = executeOnKernelWebSocket(
                bearerToken = bearerToken,
                runtime = runtime,
                kernelId = kernelId,
                code = code,
                timeoutMs = timeoutMs
            )

            // If a previously cached kernel ID died (e.g. 404/reset), re-discover/create once and retry
            if (wasCached && isTransportFailure && !firstResult.success) {
                kernelIdCache.remove(endpoint)
                val (freshKernelId, _) = resolveOrCreateKernelId(runtime, bearerToken, forceRefresh = true)
                val (retryResult, _) = executeOnKernelWebSocket(
                    bearerToken = bearerToken,
                    runtime = runtime,
                    kernelId = freshKernelId,
                    code = code,
                    timeoutMs = timeoutMs
                )
                return@withContext Result.success(retryResult)
            }

            Result.success(firstResult)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(Exception("runCode network error: ${e.message}", e))
        }
    }

    /**
     * Executes a shell command on [runtime] (matching `colab-cli runtime exec` / `%%bash`).
     */
    suspend fun executeShellCommand(
        bearerToken: String,
        runtime: ColabRuntime,
        command: String,
        timeoutMs: Long = 60_000L
    ): Result<CellExecutionResult> {
        val cleanCmd = command.trim()
        if (cleanCmd.isEmpty()) {
            return Result.success(CellExecutionResult(success = true, stdout = "", stderr = ""))
        }
        val bashCell = if (cleanCmd.startsWith("%%bash")) cleanCmd else "%%bash\n$cleanCmd"
        return runCode(bearerToken, runtime, bashCell, timeoutMs)
    }

    /**
     * Runs [bootstrapScript] on an already allocated [runtime], automatically hydrating
     * the runtime proxy token if needed.
     */
    suspend fun runBootstrapScript(
        bearerToken: String,
        runtime: ColabRuntime,
        bootstrapScript: String
    ): Result<Pair<ColabRuntime, CellExecutionResult>> = withContext(Dispatchers.IO) {
        val readyRuntime = ensureRuntimeProxyReady(bearerToken, runtime).getOrDefault(runtime)
        val formattedCode = formatScriptForExecution(bootstrapScript)
        runCode(bearerToken, readyRuntime, formattedCode).map { execResult ->
            Pair(readyRuntime, execResult)
        }
    }

    /**
     * Convenience wrapper — allocates a runtime with [allocateRuntime] and
     * immediately runs [bootstrapScript] on it.
     */
    suspend fun runBootstrapScript(
        bearerToken: String,
        bootstrapScript: String,
        accelerator: ColabAccelerator = ColabAccelerator.T4,
        authUser: Int = 0
    ): Result<Pair<ColabRuntime, CellExecutionResult>> = withContext(Dispatchers.IO) {
        val runtimeResult = allocateRuntime(bearerToken, accelerator, authUser)
        runtimeResult.fold(
            onSuccess = { runtime ->
                runBootstrapScript(bearerToken, runtime, bootstrapScript)
            },
            onFailure = { e -> Result.failure(e) }
        )
    }

    // ---------------------------------------------------------------------------
    // Runtime Proxy: Jupyter Contents API (Files, Binary Transfers, Telemetry)
    // ---------------------------------------------------------------------------

    /**
     * Lists files and subdirectories at [path] using the native Jupyter Contents API on the Colab proxy.
     */
    suspend fun listFiles(
        runtime: ColabRuntime,
        path: String = "/content"
    ): Result<List<RemoteFile>> = withContext(Dispatchers.IO) {
        try {
            val endpoint = runtime.endpointUrl.trimEnd('/')
            if (endpoint.isBlank()) {
                return@withContext Result.failure(Exception("endpointUrl is blank"))
            }
            val encodedPath = encodeContentPath(path)
            val url = if (encodedPath.isEmpty()) "$endpoint/api/contents" else "$endpoint/api/contents/$encodedPath"

            val req = Request.Builder()
                .url(url)
                .get()
                .applyProxyAuthHeaders(runtime)
                .build()

            client.newCall(req).execute().use { response ->
                val rawBody = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("listFiles failed (HTTP ${response.code}): $rawBody"))
                }
                Result.success(parseContentsList(rawBody))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(Exception("listFiles network error: ${e.message}", e))
        }
    }

    /**
     * Parses the JSON payload from the Jupyter Contents API into a list of [RemoteFile].
     */
    internal fun parseContentsList(jsonStr: String): List<RemoteFile> {
        val json = try { JSONObject(jsonStr) } catch (_: Exception) { return emptyList() }
        val contentArray = json.optJSONArray("content") ?: JSONArray()
        val list = mutableListOf<RemoteFile>()
        for (i in 0 until contentArray.length()) {
            val item = contentArray.optJSONObject(i) ?: continue
            val name = item.optString("name", "")
            val itemPath = "/" + item.optString("path", "").trimStart('/')
            val type = item.optString("type", "file")
            val isDir = type == "directory"
            val size = item.optLong("size", 0L)
            val lastModifiedStr = item.optString("last_modified", "")
            val timestamp = try {
                Instant.parse(lastModifiedStr).toEpochMilli()
            } catch (_: Exception) {
                0L
            }
            list.add(
                RemoteFile(
                    name = name,
                    path = itemPath,
                    isDirectory = isDir,
                    sizeBytes = size,
                    modifiedTime = lastModifiedStr,
                    modifiedTimestamp = timestamp
                )
            )
        }
        return list.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    /**
     * Reads a file's text content from the Colab instance via the Jupyter Contents API.
     * Automatically decodes base64 payloads if the server returns `"format": "base64"`.
     */
    suspend fun readFile(
        runtime: ColabRuntime,
        path: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val endpoint = runtime.endpointUrl.trimEnd('/')
            if (endpoint.isBlank()) return@withContext Result.failure(Exception("endpointUrl is blank"))
            val encodedPath = encodeContentPath(path)
            val url = "$endpoint/api/contents/$encodedPath"

            val req = Request.Builder()
                .url(url)
                .get()
                .applyProxyAuthHeaders(runtime)
                .build()

            client.newCall(req).execute().use { response ->
                val rawBody = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("readFile failed (HTTP ${response.code}): $rawBody"))
                }
                val json = JSONObject(rawBody)
                val format = json.optString("format", "text")
                val rawContent = json.optString("content", "")
                val decoded = if (format.equals("base64", ignoreCase = true) && rawContent.isNotEmpty()) {
                    try {
                        String(Base64.getMimeDecoder().decode(rawContent), StandardCharsets.UTF_8)
                    } catch (_: Exception) {
                        rawContent
                    }
                } else {
                    rawContent
                }
                Result.success(decoded)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(Exception("readFile network error: ${e.message}", e))
        }
    }

    /**
     * Reads a file's raw bytes from the Colab instance using Jupyter Contents API base64 mode
     * so binary artifacts (`.pt`, `.png`, `.zip`, etc.) transfer without corruption.
     */
    suspend fun readFileBytes(
        runtime: ColabRuntime,
        path: String
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        try {
            val endpoint = runtime.endpointUrl.trimEnd('/')
            if (endpoint.isBlank()) return@withContext Result.failure(Exception("endpointUrl is blank"))
            val encodedPath = encodeContentPath(path)
            val url = "$endpoint/api/contents/$encodedPath?type=file&format=base64&content=1"

            val req = Request.Builder()
                .url(url)
                .get()
                .applyProxyAuthHeaders(runtime)
                .build()

            client.newCall(req).execute().use { response ->
                val rawBody = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("readFileBytes failed (HTTP ${response.code}): $rawBody"))
                }
                val json = JSONObject(rawBody)
                val format = json.optString("format", "text")
                val rawContent = json.optString("content", "")
                val bytes = if (format.equals("base64", ignoreCase = true)) {
                    Base64.getMimeDecoder().decode(rawContent)
                } else {
                    rawContent.toByteArray(StandardCharsets.UTF_8)
                }
                Result.success(bytes)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(Exception("readFileBytes network error: ${e.message}", e))
        }
    }

    /**
     * Creates or updates a text file on the Colab instance via the Jupyter Contents API.
     */
    suspend fun writeFile(
        runtime: ColabRuntime,
        path: String,
        content: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val endpoint = runtime.endpointUrl.trimEnd('/')
            if (endpoint.isBlank()) return@withContext Result.failure(Exception("endpointUrl is blank"))
            val encodedPath = encodeContentPath(path)
            val url = "$endpoint/api/contents/$encodedPath"

            val payload = JSONObject().apply {
                put("type", "file")
                put("format", "text")
                put("content", content)
            }

            val req = Request.Builder()
                .url(url)
                .put(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .applyProxyAuthHeaders(runtime)
                .build()

            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) {
                    val rawBody = response.body?.string() ?: ""
                    return@withContext Result.failure(Exception("writeFile failed (HTTP ${response.code}): $rawBody"))
                }
                Result.success(Unit)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(Exception("writeFile network error: ${e.message}", e))
        }
    }

    /**
     * Creates or updates a binary file on the Colab instance via the Jupyter Contents API
     * using base64 encoding.
     */
    suspend fun writeFileBytes(
        runtime: ColabRuntime,
        path: String,
        bytes: ByteArray
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val endpoint = runtime.endpointUrl.trimEnd('/')
            if (endpoint.isBlank()) return@withContext Result.failure(Exception("endpointUrl is blank"))
            val encodedPath = encodeContentPath(path)
            val url = "$endpoint/api/contents/$encodedPath"

            val encodedBase64 = Base64.getEncoder().encodeToString(bytes)
            val payload = JSONObject().apply {
                put("type", "file")
                put("format", "base64")
                put("content", encodedBase64)
            }

            val req = Request.Builder()
                .url(url)
                .put(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .applyProxyAuthHeaders(runtime)
                .build()

            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) {
                    val rawBody = response.body?.string() ?: ""
                    return@withContext Result.failure(Exception("writeFileBytes failed (HTTP ${response.code}): $rawBody"))
                }
                Result.success(Unit)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(Exception("writeFileBytes network error: ${e.message}", e))
        }
    }

    /**
     * Deletes a file or directory from the Colab instance via the Jupyter Contents API.
     */
    suspend fun deleteFile(
        runtime: ColabRuntime,
        path: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val endpoint = runtime.endpointUrl.trimEnd('/')
            if (endpoint.isBlank()) return@withContext Result.failure(Exception("endpointUrl is blank"))
            val cleanPath = path.trim().trimStart('/')
            val encodedPath = encodeContentPath(cleanPath)
            val url = "$endpoint/api/contents/$encodedPath"

            val req = Request.Builder()
                .url(url)
                .delete()
                .applyProxyAuthHeaders(runtime)
                .build()

            client.newCall(req).execute().use { response ->
                if (response.isSuccessful || response.code == 404 || response.code in 200..204) {
                    return@withContext Result.success(Unit)
                }
                if (response.code == 400) {
                    val fullShellPath = if (cleanPath.startsWith("/")) cleanPath else "/$cleanPath"
                    val escapedPath = fullShellPath.replace("'", "'\\''")
                    val fallbackRes = runCode(runtime.proxyToken, runtime, "!rm -rf '$escapedPath'")
                    if (fallbackRes.isSuccess) {
                        return@withContext Result.success(Unit)
                    }
                }
                val rawBody = response.body?.string() ?: ""
                return@withContext Result.failure(Exception("deleteFile failed (HTTP ${response.code}): $rawBody"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(Exception("deleteFile network error: ${e.message}", e))
        }
    }

    /**
     * Creates a new directory on the Colab instance via the Jupyter Contents API.
     * Uses `POST /api/contents/<parent>` with `{"type": "directory"}` followed by
     * `PATCH /api/contents/<created_path>` to rename it to [folderName].
     */
    suspend fun createDirectory(
        runtime: ColabRuntime,
        parentPath: String,
        folderName: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val endpoint = runtime.endpointUrl.trimEnd('/')
            if (endpoint.isBlank()) return@withContext Result.failure(Exception("endpointUrl is blank"))
            val cleanParent = parentPath.trim().trimStart('/')
            val encodedParent = encodeContentPath(cleanParent)
            val postUrl = if (encodedParent.isEmpty()) "$endpoint/api/contents" else "$endpoint/api/contents/$encodedParent"

            val postPayload = JSONObject().put("type", "directory")
            val postReq = Request.Builder()
                .url(postUrl)
                .post(postPayload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .applyProxyAuthHeaders(runtime)
                .build()

            val createdPath: String = client.newCall(postReq).execute().use { response ->
                if (!response.isSuccessful) {
                    val rawBody = response.body?.string() ?: ""
                    return@withContext Result.failure(Exception("createDirectory failed (HTTP ${response.code}): $rawBody"))
                }
                val body = response.body?.string() ?: ""
                JSONObject(body).optString("path", "Untitled Folder")
            }

            val targetPath = if (cleanParent.isEmpty()) folderName else "$cleanParent/$folderName"
            val encodedCreated = encodeContentPath(createdPath)
            val patchUrl = "$endpoint/api/contents/$encodedCreated"
            val patchPayload = JSONObject().put("path", targetPath)

            val patchReq = Request.Builder()
                .url(patchUrl)
                .patch(patchPayload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .applyProxyAuthHeaders(runtime)
                .build()

            client.newCall(patchReq).execute().use { response ->
                if (!response.isSuccessful) {
                    val rawBody = response.body?.string() ?: ""
                    return@withContext Result.failure(Exception("rename directory failed (HTTP ${response.code}): $rawBody"))
                }
                Result.success(Unit)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(Exception("createDirectory network error: ${e.message}", e))
        }
    }

    /**
     * Renames a file or directory on the Colab instance via the Jupyter Contents API.
     */
    suspend fun renameFile(
        runtime: ColabRuntime,
        oldPath: String,
        newName: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val endpoint = runtime.endpointUrl.trimEnd('/')
            if (endpoint.isBlank()) return@withContext Result.failure(Exception("endpointUrl is blank"))
            val cleanOld = oldPath.trim().trimStart('/')
            val parent = cleanOld.substringBeforeLast('/', "")
            val targetPath = if (parent.isEmpty()) newName else "$parent/$newName"

            val encodedOld = encodeContentPath(cleanOld)
            val url = "$endpoint/api/contents/$encodedOld"
            val payload = JSONObject().put("path", targetPath)

            val req = Request.Builder()
                .url(url)
                .patch(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .applyProxyAuthHeaders(runtime)
                .build()

            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) {
                    val rawBody = response.body?.string() ?: ""
                    return@withContext Result.failure(Exception("renameFile failed (HTTP ${response.code}): $rawBody"))
                }
                Result.success(Unit)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(Exception("renameFile network error: ${e.message}", e))
        }
    }

    /**
     * Reads real-time telemetry metrics directly from the daemon on the Colab instance.
     */
    suspend fun fetchMetrics(
        runtime: ColabRuntime
    ): Result<VMMetrics> = withContext(Dispatchers.IO) {
        readFile(runtime, "tmp/colab_metrics.json").fold(
            onSuccess = { content ->
                Result.success(TelemetryEngine.parseMetricsJson(content))
            },
            onFailure = { err ->
                Result.failure(err)
            }
        )
    }

    // ---------------------------------------------------------------------------
    // Public API - ColabAccount Overloads
    // ---------------------------------------------------------------------------

    suspend fun getCcuInfo(
        account: ColabAccount,
        authUser: Int = 0
    ): Result<ColabCcuInfo> = getCcuInfo(extractBearerToken(account), authUser)

    suspend fun allocateRuntime(
        account: ColabAccount,
        accelerator: ColabAccelerator = ColabAccelerator.T4,
        authUser: Int = 0,
        variant: ColabVariant? = null,
        acceleratorOverride: String? = null,
        highMemory: Boolean = false
    ): Result<ColabRuntime> = allocateRuntime(
        bearerToken = extractBearerToken(account),
        accelerator = accelerator,
        authUser = authUser,
        variant = variant,
        acceleratorOverride = acceleratorOverride,
        highMemory = highMemory
    )

    suspend fun allocateRuntime(
        account: ColabAccount,
        accelerator: HardwareAccelerator,
        authUser: Int = 0
    ): Result<ColabRuntime> = allocateRuntime(extractBearerToken(account), accelerator.toColabAccelerator(), authUser)

    suspend fun refreshRuntimeProxyToken(
        account: ColabAccount,
        runtime: ColabRuntime,
        port: Int = 8080,
        authUser: Int = 0
    ): Result<ColabRuntime> = refreshRuntimeProxyToken(extractBearerToken(account), runtime, port, authUser)

    suspend fun ensureRuntimeProxyReady(
        account: ColabAccount,
        runtime: ColabRuntime,
        port: Int = 8080,
        authUser: Int = 0
    ): Result<ColabRuntime> = ensureRuntimeProxyReady(extractBearerToken(account), runtime, port, authUser)

    suspend fun unassignRuntime(
        account: ColabAccount,
        endpointId: String,
        authUser: Int = 0
    ): Result<Unit> = unassignRuntime(extractBearerToken(account), endpointId, authUser)

    suspend fun keepAlive(
        account: ColabAccount,
        runtime: ColabRuntime,
        authUser: Int = 0
    ): Result<Boolean> = keepAlive(extractBearerToken(account), runtime, authUser)

    suspend fun runCode(
        account: ColabAccount,
        runtime: ColabRuntime,
        code: String,
        timeoutMs: Long = 60_000L
    ): Result<CellExecutionResult> = runCode(extractBearerToken(account), runtime, code, timeoutMs)

    suspend fun executeShellCommand(
        account: ColabAccount,
        runtime: ColabRuntime,
        command: String,
        timeoutMs: Long = 60_000L
    ): Result<CellExecutionResult> = executeShellCommand(extractBearerToken(account), runtime, command, timeoutMs)

    suspend fun runBootstrapScript(
        account: ColabAccount,
        bootstrapScript: String,
        accelerator: ColabAccelerator = ColabAccelerator.T4,
        authUser: Int = 0
    ): Result<Pair<ColabRuntime, CellExecutionResult>> =
        runBootstrapScript(extractBearerToken(account), bootstrapScript, accelerator, authUser)

    suspend fun runBootstrapScript(
        account: ColabAccount,
        bootstrapScript: String,
        accelerator: HardwareAccelerator,
        authUser: Int = 0
    ): Result<Pair<ColabRuntime, CellExecutionResult>> =
        runBootstrapScript(extractBearerToken(account), bootstrapScript, accelerator.toColabAccelerator(), authUser)

    suspend fun checkRuntimeStatus(
        account: ColabAccount,
        runtime: ColabRuntime
    ): Result<RuntimeStatus> = checkRuntimeStatus(extractBearerToken(account), runtime)

    suspend fun terminateRuntime(
        account: ColabAccount,
        runtime: ColabRuntime,
        authUser: Int = 0
    ): Result<Unit> = terminateRuntime(extractBearerToken(account), runtime, authUser)

    suspend fun listAssignments(
        account: ColabAccount,
        authUser: Int = 0,
        hydrateProxyTokens: Boolean = false
    ): Result<List<ColabRuntime>> = listAssignments(extractBearerToken(account), authUser, hydrateProxyTokens)

    // ---------------------------------------------------------------------------
    // Internal parsing helpers
    // ---------------------------------------------------------------------------

    /** Strips Google's XSSI anti-hijacking prefix from a response body. */
    internal fun stripXssiPrefix(body: String): String {
        return when {
            body.startsWith(XSSI_PREFIX) -> body.substring(XSSI_PREFIX.length)
            body.startsWith(")]}'") -> body.substring(4).trimStart('\n', '\r', ' ')
            else -> body
        }
    }

    /**
     * Checks Colab's `outcome` field on `/tun/m/assign` responses and returns an actionable
     * denial message if Google rejected the allocation (quota exhausted, denylisted, etc.).
     */
    internal fun validateAssignOutcome(json: JSONObject): String? {
        val assignResp = json.optJSONObject("assign_response") ?: json
        val rawOutcome = assignResp.opt("outcome") ?: return null
        val normalized = when (rawOutcome) {
            is Number -> when (rawOutcome.toInt()) {
                1, 2 -> return null // SUCCESS / ALREADY_ASSIGNED
                4 -> "OUTCOME_QUOTA_DENIED"
                5 -> "OUTCOME_DENYLISTED"
                else -> rawOutcome.toString()
            }
            is String -> rawOutcome.trim().uppercase()
            else -> return null
        }
        return when {
            normalized.contains("QUOTA_DENIED") || normalized.contains("QUOTA_EXCEEDED") ->
                "Colab allocation denied ($normalized): GPU/compute quota exceeded for this account."
            normalized.contains("DENYLISTED") || normalized.contains("BLOCKED") ->
                "Colab allocation denied ($normalized): Account is temporarily restricted from allocating runtimes."
            normalized.contains("UNAVAILABLE") ->
                "Colab allocation denied ($normalized): Requested accelerator is currently unavailable."
            else -> null
        }
    }

    /** Parses the runtime assignment response JSON into a [ColabRuntime]. */
    internal fun parseAssignResponse(body: String, rawResponse: String = ""): ColabRuntime {
        return try {
            val json = JSONObject(body)
            val assignResp = json.optJSONObject("assign_response") ?: json

            val sessionId = assignResp.optString("session_id")
                .ifBlank { assignResp.optString("sessionId", "") }
                .ifBlank { assignResp.optString("endpoint", "") }

            val proxyInfo = assignResp.optJSONObject("runtimeProxyInfo")
                ?: assignResp.optJSONObject("runtime_proxy_info")
            val proxyUrl = proxyInfo?.optString("url")
                ?.ifBlank { proxyInfo.optString("proxyUrl", "") }
                ?: ""
            val proxyToken = proxyInfo?.optString("token") ?: ""

            val proxyTtlSec = if (proxyInfo != null) {
                parseTtlSeconds(
                    proxyInfo.opt("tokenTtl")
                        ?: proxyInfo.opt("token_ttl")
                        ?: proxyInfo.opt("tokenExpiresInSeconds")
                        ?: proxyInfo.opt("token_expires_in_seconds")
                )
            } else {
                0L
            }
            val proxyExpiresAtMs = when {
                proxyTtlSec > 0L -> System.currentTimeMillis() + proxyTtlSec * 1000L
                proxyToken.isNotBlank() -> System.currentTimeMillis() + DEFAULT_PROXY_TOKEN_TTL_MS
                else -> 0L
            }

            val endpointUrl = assignResp.optString("endpoint_url")
                .ifBlank { assignResp.optString("endpointUrl", "") }
                .ifBlank { proxyUrl }
                .let { url ->
                    if (url.isBlank()) {
                        if (sessionId.isNotBlank()) "$controlPlaneBase/tun/m/$sessionId" else ""
                    } else url
                }

            val datalabBackendUrl = assignResp.optString("datalab_backend_url")
                .ifBlank { assignResp.optString("datalabBackendUrl", "") }

            val acceleratorStr = assignResp.optString("accelerator", "")
            val accelerator = when (acceleratorStr.uppercase()) {
                "NONE", "CPU" -> ColabAccelerator.NONE
                "T4" -> ColabAccelerator.T4
                "V100" -> ColabAccelerator.V100
                "A100" -> ColabAccelerator.A100
                else -> ColabAccelerator.T4
            }

            val variantStr = assignResp.optString("variant", "")
            val machineShape = assignResp.optString("machineShape", "")
                .ifBlank { assignResp.optString("shape", "") }

            ColabRuntime(
                sessionId = sessionId,
                endpointUrl = endpointUrl,
                datalabBackendUrl = datalabBackendUrl,
                rawResponse = rawResponse,
                proxyToken = proxyToken,
                accelerator = accelerator,
                proxyTokenExpiresAtMs = proxyExpiresAtMs,
                variant = variantStr,
                machineShape = machineShape
            )
        } catch (_: Exception) {
            ColabRuntime(sessionId = "", endpointUrl = "", datalabBackendUrl = "", rawResponse = rawResponse)
        }
    }

    /** Parses the JSON payload from the Colab CCU API into [ColabCcuInfo]. */
    internal fun parseCcuInfo(body: String): ColabCcuInfo {
        val stripped = stripXssiPrefix(body)
        val json = try { JSONObject(stripped) } catch (_: Exception) { return ColabCcuInfo() }
        val eligibleGpus = mutableListOf<String>()
        json.optJSONArray("eligibleGpus")?.let { arr ->
            for (i in 0 until arr.length()) eligibleGpus.add(arr.optString(i))
        }
        val eligibleTpus = mutableListOf<String>()
        json.optJSONArray("eligibleTpus")?.let { arr ->
            for (i in 0 until arr.length()) eligibleTpus.add(arr.optString(i))
        }
        val freeQuota = json.optJSONObject("freeCcuQuotaInfo")
        return ColabCcuInfo(
            currentBalance = json.optDouble("currentBalance", 0.0),
            consumptionRateHourly = json.optDouble("consumptionRateHourly", 0.0),
            assignmentsCount = json.optInt("assignmentsCount", 0),
            eligibleGpus = eligibleGpus,
            eligibleTpus = eligibleTpus,
            remainingTokens = freeQuota?.optString("remainingTokens", "") ?: "",
            nextRefillTimestampSec = freeQuota?.optLong("nextRefillTimestampSec", 0L) ?: 0L
        )
    }

    private fun parseKernel(obj: JSONObject): ColabKernel = ColabKernel(
        id = obj.optString("id", ""),
        name = obj.optString("name", "python3"),
        executionState = obj.optString("execution_state", "idle"),
        lastActivity = obj.optString("last_activity", ""),
        connections = obj.optInt("connections", 0)
    )

    /** Parses a cell-execution response body into a [CellExecutionResult]. */
    internal fun parseCellExecutionResult(body: String, httpStatus: Int): CellExecutionResult {
        return try {
            val json = JSONObject(body)
            val success = json.optBoolean("success", httpStatus in 200..299)
            var stdout = collectTextOutputs(json, "stdout")
            var stderr = collectTextOutputs(json, "stderr")

            if (stdout.isBlank() && stderr.isBlank()) {
                val outputs = json.optJSONArray("outputs")
                if (outputs != null) {
                    val outBuilder = StringBuilder()
                    val errBuilder = StringBuilder()
                    for (i in 0 until outputs.length()) {
                        val outObj = outputs.optJSONObject(i) ?: continue
                        val outputType = outObj.optString("output_type", "")
                        if (outputType == "stream") {
                            val name = outObj.optString("name", "stdout")
                            val text = outObj.optString("text", "")
                            if (name == "stderr") {
                                if (errBuilder.isNotEmpty()) errBuilder.append("\n")
                                errBuilder.append(text)
                            } else {
                                if (outBuilder.isNotEmpty()) outBuilder.append("\n")
                                outBuilder.append(text)
                            }
                        } else if (outObj.has("data")) {
                            val dataObj = outObj.optJSONObject("data")
                            val plainText = dataObj?.optString("text/plain", "") ?: ""
                            if (plainText.isNotBlank()) {
                                if (outBuilder.isNotEmpty()) outBuilder.append("\n")
                                outBuilder.append(plainText)
                            }
                        } else if (outputType == "error") {
                            val ename = outObj.optString("ename", "Error")
                            val evalue = outObj.optString("evalue", "")
                            if (errBuilder.isNotEmpty()) errBuilder.append("\n")
                            errBuilder.append("$ename: $evalue")
                        }
                    }
                    if (outBuilder.isNotEmpty()) stdout = outBuilder.toString()
                    if (errBuilder.isNotEmpty()) stderr = errBuilder.toString()
                }
            }

            CellExecutionResult(success = success, stdout = stdout, stderr = stderr, httpStatus = httpStatus)
        } catch (_: Exception) {
            CellExecutionResult(
                success = httpStatus in 200..299,
                stdout = body,
                stderr = "",
                httpStatus = httpStatus
            )
        }
    }

    /** Flattens an optional JSONArray or plain string field under [key] in [json]. */
    private fun collectTextOutputs(json: JSONObject, key: String): String {
        val raw = json.opt(key) ?: return ""
        return when (raw) {
            is String -> raw
            is JSONArray -> buildString {
                for (i in 0 until raw.length()) {
                    if (isNotEmpty()) append("\n")
                    append(raw.optString(i))
                }
            }
            else -> raw.toString()
        }
    }

    /** Parses a status-check response (`/api/status` or `/status`) into a [RuntimeStatus]. */
    internal fun parseRuntimeStatus(body: String, sessionId: String, httpOk: Boolean): RuntimeStatus {
        return try {
            val json = JSONObject(body)
            val explicitState = json.optString("state", "")
            val state = if (explicitState.isNotBlank()) {
                explicitState
            } else if (httpOk) {
                "RUNNING"
            } else {
                "STOPPED"
            }
            val kernels = json.optInt("kernels", 0)
            val connections = json.optInt("connections", 0)
            val lastActivity = json.optString("last_activity", "")
            RuntimeStatus(
                isAlive = httpOk && state.uppercase() !in listOf("STOPPED", "TERMINATED", "ERROR"),
                state = state,
                sessionId = sessionId,
                kernels = kernels,
                connections = connections,
                lastActivity = lastActivity
            )
        } catch (_: Exception) {
            RuntimeStatus(isAlive = httpOk, state = if (httpOk) "RUNNING" else "STOPPED", sessionId = sessionId)
        }
    }

    /** Extracts a human-readable error message from an error response body. */
    private fun extractErrorMessage(body: String): String {
        return try {
            val stripped = stripXssiPrefix(body)
            val json = JSONObject(stripped)
            json.optString("error_description")
                .ifBlank { json.optString("message") }
                .ifBlank { json.optString("error") }
                .ifBlank { body.take(200) }
        } catch (_: Exception) {
            body.take(200)
        }
    }
}
