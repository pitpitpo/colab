package com.pox.colab.engine

import com.pox.colab.model.ColabAccount
import com.pox.colab.model.HardwareAccelerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

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

/** Mapping helper between app HardwareAccelerator and ColabAccelerator. */
fun HardwareAccelerator.toColabAccelerator(): ColabAccelerator = when (this) {
    HardwareAccelerator.NONE -> ColabAccelerator.NONE
    HardwareAccelerator.T4 -> ColabAccelerator.T4
    HardwareAccelerator.V100 -> ColabAccelerator.V100
    HardwareAccelerator.A100 -> ColabAccelerator.A100
}

/** Runtime session returned after successful allocation. */
data class ColabRuntime(
    /** Colab session / kernel ID. */
    val sessionId: String,
    /** Backend endpoint URL (e.g. https://colab.research.google.com/tun/m/<id>). */
    val endpointUrl: String,
    /** Datalab / GKE backend URL if present. */
    val datalabBackendUrl: String,
    /** Raw assignment response body for diagnostics. */
    val rawResponse: String = "",
    /** Runtime proxy authorization token if returned by backend. */
    val proxyToken: String = "",
    /** Hardware accelerator provisioned. */
    val accelerator: ColabAccelerator = ColabAccelerator.T4
)

/** Result of running a cell / script on the allocated runtime. */
data class CellExecutionResult(
    val success: Boolean,
    val stdout: String,
    val stderr: String,
    /** HTTP status code from execution endpoint, or -1 if network-level failure. */
    val httpStatus: Int = 200
)

/** Runtime health / state as returned by checkRuntimeStatus. */
data class RuntimeStatus(
    val isAlive: Boolean,
    val state: String,  // e.g. "RUNNING", "PROVISIONING", "STOPPED"
    val sessionId: String
)

// ---------------------------------------------------------------------------
// Engine
// ---------------------------------------------------------------------------

/**
 * Authentic Google Colab REST API client.
 *
 * Uses the same OAuth Bearer token that google-colab-cli uses and calls
 * Google's real Colab backend endpoints to:
 *  1. Allocate a runtime with the requested hardware accelerator.
 *  2. Run arbitrary code / bootstrap scripts on the allocated runtime.
 *  3. Query runtime status.
 *  4. Terminate the runtime when done.
 *
 * The [httpClientProvider] constructor parameter exists solely as a test-seam;
 * production code uses the lazy-initialised default client.
 */
class ColabApiEngine(
    private val httpClientProvider: (() -> OkHttpClient)? = null
) {

    companion object {
        val INSTANCE: ColabApiEngine by lazy { ColabApiEngine() }

        fun getInstance(): ColabApiEngine = INSTANCE

        /** XSSI anti-json-hijacking prefix Google prepends to Colab API responses. */
        private const val XSSI_PREFIX = ")]}'\n"

        /** Base URL of the Colab tunnelling/assignment plane. */
        private const val COLAB_BASE = "https://colab.research.google.com"

        /** Runtime assignment endpoint (allocates a new notebook kernel). */
        private const val ASSIGN_URL = "$COLAB_BASE/tun/m/assign"

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
         * Extracts the raw Bearer token from a [ColabAccount], unpacking JSON token blobs if necessary.
         */
        fun extractBearerToken(account: ColabAccount): String {
            val candidate = account.oauthToken.ifBlank { account.tokenOrCookie }.trim()
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

    private val client: OkHttpClient by lazy {
        httpClientProvider?.invoke() ?: OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    // ---------------------------------------------------------------------------
    // Public API - String Token Overloads
    // ---------------------------------------------------------------------------

    /**
     * Allocates a new Google Colab runtime using [bearerToken] and returns a
     * [ColabRuntime] with the session ID and tunnelled endpoint URL.
     *
     * Sends a POST to `https://colab.research.google.com/tun/m/assign?authuser=0`
     * with an `assign_request` JSON payload requesting the given [accelerator].
     * Strips the XSSI prefix from the response before parsing.
     *
     * @param bearerToken  Google OAuth2 access token (ya29.…).
     * @param accelerator  Hardware requested; defaults to [ColabAccelerator.T4].
     * @param authUser     Google account index (0 = primary). Defaults to 0.
     */
    suspend fun allocateRuntime(
        bearerToken: String,
        accelerator: ColabAccelerator = ColabAccelerator.T4,
        authUser: Int = 0
    ): Result<ColabRuntime> = withContext(Dispatchers.IO) {
        try {
            val requestBody = JSONObject().apply {
                put("assign_request", JSONObject().apply {
                    put("hardwareAccelerator", accelerator.apiValue)
                    put("sessionIdSuffix", "mobile_${System.currentTimeMillis()}")
                    put("numGpus", if (accelerator == ColabAccelerator.NONE) 0 else 1)
                })
            }.toString().toRequestBody(JSON_MEDIA_TYPE)

            val request = Request.Builder()
                .url("$ASSIGN_URL?authuser=$authUser")
                .post(requestBody)
                .header("Authorization", "Bearer $bearerToken")
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("X-Colab-Client-Agent", "colab-android-client/1.0")
                .header("Origin", COLAB_BASE)
                .header("Referer", "$COLAB_BASE/")
                .build()

            client.newCall(request).execute().use { response ->
                val rawBody = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    val errMsg = extractErrorMessage(rawBody)
                    return@withContext Result.failure(
                        Exception("Colab runtime allocation failed (HTTP ${response.code}): $errMsg")
                    )
                }

                val body = stripXssiPrefix(rawBody)
                Result.success(parseAssignResponse(body, rawBody))
            }
        } catch (e: Exception) {
            Result.failure(Exception("allocateRuntime network error: ${e.message}", e))
        }
    }

    /**
     * Runs [code] on the already-allocated [runtime] by POSTing a cell
     * execution payload to `{endpointUrl}/execute`.
     *
     * @param bearerToken  Google OAuth2 access token.
     * @param runtime      A runtime previously returned by [allocateRuntime].
     * @param code         Python code (or shell commands via `!`) to execute.
     */
    suspend fun runCode(
        bearerToken: String,
        runtime: ColabRuntime,
        code: String
    ): Result<CellExecutionResult> = withContext(Dispatchers.IO) {
        try {
            val executeUrl = "${runtime.endpointUrl.trimEnd('/')}/execute"

            val payload = JSONObject().apply {
                put("session_id", runtime.sessionId)
                put("code", code)
                put("output_format", "json")
            }.toString().toRequestBody(JSON_MEDIA_TYPE)

            val requestBuilder = Request.Builder()
                .url(executeUrl)
                .post(payload)
                .header("Authorization", "Bearer $bearerToken")
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("X-Colab-Client-Agent", "colab-android-client/1.0")

            if (runtime.proxyToken.isNotBlank()) {
                requestBuilder.header("X-Colab-Runtime-Proxy-Token", runtime.proxyToken)
            }

            client.newCall(requestBuilder.build()).execute().use { response ->
                val rawBody = response.body?.string() ?: ""
                val body = stripXssiPrefix(rawBody)
                val httpStatus = response.code
                Result.success(parseCellExecutionResult(body, httpStatus))
            }
        } catch (e: Exception) {
            Result.failure(Exception("runCode network error: ${e.message}", e))
        }
    }

    /**
     * Convenience wrapper — allocates a runtime with [allocateRuntime] and
     * immediately runs [bootstrapScript] on it. Returns the [CellExecutionResult]
     * of the script together with the [ColabRuntime] it ran on.
     *
     * Automatically formats the script with `formatScriptForExecution` so bash bootstrapper
     * scripts execute properly in the remote Colab notebook cell.
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
                val formattedCode = formatScriptForExecution(bootstrapScript)
                runCode(bearerToken, runtime, formattedCode).fold(
                    onSuccess = { execResult -> Result.success(Pair(runtime, execResult)) },
                    onFailure = { e -> Result.failure(e) }
                )
            },
            onFailure = { e -> Result.failure(e) }
        )
    }

    /**
     * Queries the status of an existing runtime session.
     *
     * Calls `GET {endpointUrl}/status` and returns a [RuntimeStatus].
     */
    suspend fun checkRuntimeStatus(
        bearerToken: String,
        runtime: ColabRuntime
    ): Result<RuntimeStatus> = withContext(Dispatchers.IO) {
        try {
            val statusUrl = "${runtime.endpointUrl.trimEnd('/')}/status"

            val request = Request.Builder()
                .url(statusUrl)
                .get()
                .header("Authorization", "Bearer $bearerToken")
                .header("Accept", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                val rawBody = response.body?.string() ?: ""
                val body = stripXssiPrefix(rawBody)
                Result.success(parseRuntimeStatus(body, runtime.sessionId, response.isSuccessful))
            }
        } catch (e: Exception) {
            Result.failure(Exception("checkRuntimeStatus network error: ${e.message}", e))
        }
    }

    /**
     * Terminates an active runtime session.
     *
     * Sends `DELETE {endpointUrl}/session/{sessionId}`.
     */
    suspend fun terminateRuntime(
        bearerToken: String,
        runtime: ColabRuntime
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val terminateUrl = "${runtime.endpointUrl.trimEnd('/')}/session/${runtime.sessionId}"

            val request = Request.Builder()
                .url(terminateUrl)
                .delete()
                .header("Authorization", "Bearer $bearerToken")
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
        } catch (e: Exception) {
            Result.failure(Exception("terminateRuntime network error: ${e.message}", e))
        }
    }

    /**
     * Lists active Colab runtime assignments for the authenticated Google account.
     * Calls `GET https://colab.research.google.com/tun/m/assignments?authuser=0`.
     */
    suspend fun listAssignments(
        bearerToken: String,
        authUser: Int = 0
    ): Result<List<ColabRuntime>> = withContext(Dispatchers.IO) {
        try {
            val url = "$COLAB_BASE/tun/m/assignments?authuser=$authUser"
            val request = Request.Builder()
                .url(url)
                .get()
                .header("Authorization", "Bearer $bearerToken")
                .header("Accept", "application/json")
                .header("X-Colab-Client-Agent", "colab-android-client/1.0")
                .build()

            client.newCall(request).execute().use { response ->
                val rawBody = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        Exception("listAssignments failed (HTTP ${response.code}): $rawBody")
                    )
                }
                val body = stripXssiPrefix(rawBody)
                val json = JSONObject(body)
                val list = mutableListOf<ColabRuntime>()
                val assignmentsArray = json.optJSONArray("assignments")
                if (assignmentsArray != null) {
                    for (i in 0 until assignmentsArray.length()) {
                        val item = assignmentsArray.optJSONObject(i) ?: continue
                        list.add(parseAssignResponse(item.toString(), item.toString()))
                    }
                }
                Result.success(list)
            }
        } catch (e: Exception) {
            Result.failure(Exception("listAssignments network error: ${e.message}", e))
        }
    }

    // ---------------------------------------------------------------------------
    // Public API - ColabAccount Overloads
    // ---------------------------------------------------------------------------

    suspend fun allocateRuntime(
        account: ColabAccount,
        accelerator: ColabAccelerator = ColabAccelerator.T4,
        authUser: Int = 0
    ): Result<ColabRuntime> = allocateRuntime(extractBearerToken(account), accelerator, authUser)

    suspend fun allocateRuntime(
        account: ColabAccount,
        accelerator: HardwareAccelerator,
        authUser: Int = 0
    ): Result<ColabRuntime> = allocateRuntime(extractBearerToken(account), accelerator.toColabAccelerator(), authUser)

    suspend fun runCode(
        account: ColabAccount,
        runtime: ColabRuntime,
        code: String
    ): Result<CellExecutionResult> = runCode(extractBearerToken(account), runtime, code)

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
        runtime: ColabRuntime
    ): Result<Unit> = terminateRuntime(extractBearerToken(account), runtime)

    suspend fun listAssignments(
        account: ColabAccount,
        authUser: Int = 0
    ): Result<List<ColabRuntime>> = listAssignments(extractBearerToken(account), authUser)

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

    /** Parses the runtime assignment response JSON into a [ColabRuntime]. */
    internal fun parseAssignResponse(body: String, rawResponse: String = ""): ColabRuntime {
        return try {
            val json = JSONObject(body)

            // The assign response may be nested under assign_response or at the top level
            val assignResp = json.optJSONObject("assign_response") ?: json

            val sessionId = assignResp.optString("session_id")
                .ifBlank { assignResp.optString("sessionId", "") }
                .ifBlank { assignResp.optString("endpoint", "") }

            val proxyInfo = assignResp.optJSONObject("runtimeProxyInfo")
                ?: assignResp.optJSONObject("runtime_proxy_info")
            val proxyUrl = proxyInfo?.optString("url") ?: ""
            val proxyToken = proxyInfo?.optString("token") ?: ""

            val endpointUrl = assignResp.optString("endpoint_url")
                .ifBlank { assignResp.optString("endpointUrl", "") }
                .ifBlank { proxyUrl }
                .let { url ->
                    if (url.isBlank()) {
                        // Fall back: build from session_id if present
                        if (sessionId.isNotBlank()) "$COLAB_BASE/tun/m/$sessionId" else ""
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

            ColabRuntime(
                sessionId = sessionId,
                endpointUrl = endpointUrl,
                datalabBackendUrl = datalabBackendUrl,
                rawResponse = rawResponse,
                proxyToken = proxyToken,
                accelerator = accelerator
            )
        } catch (_: Exception) {
            ColabRuntime(sessionId = "", endpointUrl = "", datalabBackendUrl = "", rawResponse = rawResponse)
        }
    }

    /** Parses a cell-execution response body into a [CellExecutionResult]. */
    internal fun parseCellExecutionResult(body: String, httpStatus: Int): CellExecutionResult {
        return try {
            val json = JSONObject(body)
            val success = json.optBoolean("success", httpStatus in 200..299)
            var stdout = collectTextOutputs(json, "stdout")
            var stderr = collectTextOutputs(json, "stderr")

            // Parse Jupyter style outputs array if top-level stdout/stderr are empty
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

    /** Parses a status-check response into a [RuntimeStatus]. */
    internal fun parseRuntimeStatus(body: String, sessionId: String, httpOk: Boolean): RuntimeStatus {
        return try {
            val json = JSONObject(body)
            val state = json.optString("state", if (httpOk) "RUNNING" else "STOPPED")
            RuntimeStatus(isAlive = httpOk && state !in listOf("STOPPED", "TERMINATED", "ERROR"), state = state, sessionId = sessionId)
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
