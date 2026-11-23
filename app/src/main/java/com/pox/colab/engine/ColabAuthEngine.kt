package com.pox.colab.engine

import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

data class OAuthUserProfile(
    val email: String,
    val displayName: String,
    val accessToken: String,
    val refreshToken: String?,
    val idToken: String?,
    val tier: String = "Free",
    val tokenJson: String = ""
)

data class ColabAuthSession(
    val authUrl: String,
    val codeVerifier: String,
    val codeChallenge: String,
    val state: String,
    val createdAt: Long = System.currentTimeMillis()
)

object ColabAuthEngine {

    // Official OAuth2 client metadata from google-colab-cli (colab_cli/oauth_config.json)
    const val CLIENT_ID = "764086051850-6qr4p6gpi6hn506pt8ejuq83di341hur.apps.googleusercontent.com"
    const val CLIENT_SECRET = "d-FL95Q19q7MQmFpd7hHD0Ty"
    const val AUTH_URI = "https://accounts.google.com/o/oauth2/auth"
    const val TOKEN_URI = "https://oauth2.googleapis.com/token"
    const val USERINFO_URI = "https://www.googleapis.com/oauth2/v3/userinfo"
    const val REMOTE_REDIRECT_URI = "https://sdk.cloud.google.com/applicationdefaultauthcode.html"
    const val COLAB_DOMAIN = "https://colab.research.google.com"

    // Standard public scopes from colab_cli/auth.py
    val SCOPES = listOf(
        "openid",
        "https://www.googleapis.com/auth/userinfo.profile",
        "https://www.googleapis.com/auth/userinfo.email",
        "https://www.googleapis.com/auth/cloud-platform",
        "https://www.googleapis.com/auth/colaboratory"
    )

    private val httpClient get() = HttpClientProvider.authClient
    private val secureRandom = SecureRandom()
    private val sessionMap = ConcurrentHashMap<String, ColabAuthSession>()

    /**
     * Creates a new cryptographically authentic Colab CLI OAuth 2.0 PKCE Session
     * identical to google_auth_oauthlib.flow.InstalledAppFlow in google-colab-cli.
     */
    fun createAuthSession(): ColabAuthSession {
        cleanExpiredSessions()

        val verifierBytes = ByteArray(64)
        secureRandom.nextBytes(verifierBytes)
        val codeVerifier = Base64.encodeToString(verifierBytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP).trim()

        val sha256Digest = MessageDigest.getInstance("SHA-256").digest(codeVerifier.toByteArray(StandardCharsets.US_ASCII))
        val codeChallenge = Base64.encodeToString(sha256Digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP).trim()

        val stateBytes = ByteArray(24)
        secureRandom.nextBytes(stateBytes)
        val state = Base64.encodeToString(stateBytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP).trim()

        val scopeParam = URLEncoder.encode(SCOPES.joinToString(" "), StandardCharsets.UTF_8.name())
        val redirectParam = URLEncoder.encode(REMOTE_REDIRECT_URI, StandardCharsets.UTF_8.name())
        val challengeParam = URLEncoder.encode(codeChallenge, StandardCharsets.UTF_8.name())
        val stateParam = URLEncoder.encode(state, StandardCharsets.UTF_8.name())

        val url = "$AUTH_URI?" +
                "response_type=code" +
                "&client_id=$CLIENT_ID" +
                "&redirect_uri=$redirectParam" +
                "&scope=$scopeParam" +
                "&state=$stateParam" +
                "&code_challenge=$challengeParam" +
                "&code_challenge_method=S256" +
                "&prompt=consent" +
                "&token_usage=remote" +
                "&access_type=offline"

        val session = ColabAuthSession(
            authUrl = url,
            codeVerifier = codeVerifier,
            codeChallenge = codeChallenge,
            state = state
        )
        sessionMap[state] = session
        return session
    }

    fun getOrNewAuthSession(): ColabAuthSession {
        cleanExpiredSessions()
        return sessionMap.values.maxByOrNull { it.createdAt } ?: createAuthSession()
    }

    fun getSessionByState(state: String): ColabAuthSession? {
        return sessionMap[state]
    }

    fun cleanExpiredSessions() {
        val now = System.currentTimeMillis()
        sessionMap.entries.removeIf { now - it.value.createdAt > 3600_000L }
    }

    /**
     * Backward-compatible helper returning the active session URL.
     */
    fun buildAuthorizationUrl(): String {
        return getOrNewAuthSession().authUrl
    }

    /**
     * Backward-compatible alias used by SettingsScreen.
     */
    suspend fun exchangeCodeForProfile(
        code: String,
        session: ColabAuthSession? = null
    ): OAuthUserProfile {
        return authenticateWithCodeOrToken(code, session).getOrThrow()
    }

    /**
     * Extracts the OAuth authorization code or bearer token from either raw pasted text
     * or a full Google redirect URL containing `?code=...`.
     */
    internal fun extractCodeOrToken(rawInput: String): String {
        val trimmed = rawInput.trim()
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            val query = trimmed.substringAfter("?", missingDelimiterValue = "").substringBefore("#")
            if (query.isNotEmpty()) {
                for (pair in query.split("&")) {
                    val eqIdx = pair.indexOf('=')
                    if (eqIdx > 0) {
                        val key = pair.substring(0, eqIdx)
                        if (key == "code") {
                            val rawVal = pair.substring(eqIdx + 1)
                            return runCatching {
                                java.net.URLDecoder.decode(rawVal, StandardCharsets.UTF_8.name())
                            }.getOrDefault(rawVal).trim()
                        }
                    }
                }
            }
        }
        return trimmed
    }

    /**
     * Exchanges the authorization code received from Google's applicationdefaultauthcode.html
     * landing page for OAuth credentials using PKCE verification, then concurrently fetches
     * Google user info (when needed) and detects Colab compute unit subscription tier.
     */
    suspend fun authenticateWithCodeOrToken(
        inputCodeOrToken: String,
        session: ColabAuthSession? = null
    ): Result<OAuthUserProfile> = withContext(Dispatchers.IO) {
        val trimmed = extractCodeOrToken(inputCodeOrToken)
        if (trimmed.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Authorization code cannot be empty"))
        }

        val verifier = session?.codeVerifier ?: sessionMap.values.maxByOrNull { it.createdAt }?.codeVerifier

        try {
            var accessToken = trimmed
            var refreshToken: String? = null
            var idToken: String? = null
            var expiresInSec = 3600L

            // If not a pre-existing bearer token (ya29...), perform OAuth2 token exchange
            if (!trimmed.startsWith("ya29.")) {
                val formBuilder = FormBody.Builder()
                    .add("code", trimmed)
                    .add("client_id", CLIENT_ID)
                    .add("client_secret", CLIENT_SECRET)
                    .add("redirect_uri", REMOTE_REDIRECT_URI)
                    .add("grant_type", "authorization_code")

                if (!verifier.isNullOrBlank()) {
                    formBuilder.add("code_verifier", verifier)
                }

                val request = Request.Builder()
                    .url(TOKEN_URI)
                    .post(formBuilder.build())
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    if (!response.isSuccessful) {
                        val errMsg = try {
                            val json = JSONObject(body)
                            json.optString("error_description", json.optString("error", body))
                        } catch (_: Exception) {
                            body
                        }
                        return@withContext Result.failure(Exception("OAuth exchange error: $errMsg"))
                    }

                    val json = JSONObject(body)
                    accessToken = json.optString("access_token", "")
                    refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() }
                    idToken = json.optString("id_token").takeIf { it.isNotBlank() }
                    expiresInSec = json.optLong("expires_in", 3600L).coerceAtLeast(60L)
                }
            }

            if (accessToken.isBlank()) {
                return@withContext Result.failure(Exception("No valid access token received from Google OAuth"))
            }

            // Step 2: Decode identity from id_token JWT payload if available
            var jwtEmail = ""
            var jwtName = ""
            idToken?.let { jwt ->
                try {
                    val parts = jwt.split(".")
                    if (parts.size >= 2) {
                        val payloadBytes = Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
                        val payloadJson = JSONObject(String(payloadBytes, StandardCharsets.UTF_8))
                        jwtEmail = payloadJson.optString("email", "")
                        jwtName = payloadJson.optString("name", "")
                    }
                } catch (_: Exception) {}
            }

            // Step 2B & Step 3 in parallel: UserInfo lookup (only if JWT lacked email) + CCU tier probe
            val (userInfoResult, detectedTier) = coroutineScope {
                val userInfoDeferred = async(Dispatchers.IO) {
                    if (jwtEmail.isNotBlank()) {
                        jwtEmail to jwtName
                    } else {
                        fetchUserInfo(accessToken, jwtName)
                    }
                }
                val tierDeferred = async(Dispatchers.IO) {
                    detectColabTier(accessToken)
                }
                userInfoDeferred.await() to tierDeferred.await()
            }

            val fetchedEmail = userInfoResult.first.ifBlank { "google.colab.user@gmail.com" }
            val fetchedName = userInfoResult.second.ifBlank { fetchedEmail.substringBefore("@") }

            // Step 4: Serialize in authentic ~/.config/colab-cli/token.json format with RFC3339 expiry
            val expiryRfc3339 = Instant.now()
                .plusSeconds(expiresInSec)
                .truncatedTo(ChronoUnit.SECONDS)
                .toString()

            val tokenJsonObj = JSONObject().apply {
                put("token", accessToken)
                put("refresh_token", refreshToken ?: "")
                put("token_uri", TOKEN_URI)
                put("client_id", CLIENT_ID)
                put("client_secret", CLIENT_SECRET)
                put("scopes", JSONArray(SCOPES))
                put("universe_domain", "googleapis.com")
                put("account", fetchedEmail)
                put("expiry", expiryRfc3339)
            }

            // Remove consumed session from cache
            session?.state?.let { sessionMap.remove(it) }

            Result.success(
                OAuthUserProfile(
                    email = fetchedEmail,
                    displayName = fetchedName,
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    idToken = idToken,
                    tier = detectedTier,
                    tokenJson = tokenJsonObj.toString()
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: java.net.UnknownHostException) {
            Result.failure(
                Exception("Cannot reach oauth2.googleapis.com — no internet or DNS access on this device. Connect to Wi-Fi or mobile data and tap Connect again.")
            )
        } catch (_: java.net.SocketTimeoutException) {
            Result.failure(
                Exception("Connection to Google OAuth timed out. Check your network connection and try again.")
            )
        } catch (_: java.net.ConnectException) {
            Result.failure(
                Exception("Unable to connect to Google OAuth servers. Check your internet connection or VPN/firewall settings.")
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun fetchUserInfo(accessToken: String, fallbackName: String): Pair<String, String> {
        return try {
            val userinfoRequest = Request.Builder()
                .url(USERINFO_URI)
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()

            httpClient.newCall(userinfoRequest).execute().use { userinfoResp ->
                if (userinfoResp.isSuccessful) {
                    val uBody = userinfoResp.body?.string() ?: ""
                    val uJson = JSONObject(uBody)
                    val email = uJson.optString("email", "")
                    val name = fallbackName.ifBlank { uJson.optString("name", "") }
                    email to name
                } else {
                    "" to fallbackName
                }
            }
        } catch (_: Exception) {
            "" to fallbackName
        }
    }

    private fun detectColabTier(accessToken: String): String {
        return try {
            val ccuRequest = Request.Builder()
                .url("$COLAB_DOMAIN/tun/m/ccu-info?authuser=0")
                .header("Authorization", "Bearer $accessToken")
                .header("Accept", "application/json")
                .header("X-Colab-Client-Agent", "colab-cli")
                .get()
                .build()

            httpClient.newCall(ccuRequest).execute().use { ccuResp ->
                if (!ccuResp.isSuccessful) return "Free"
                var ccuBody = ccuResp.body?.string() ?: ""
                if (ccuBody.startsWith(")]}'\n")) {
                    ccuBody = ccuBody.substring(")]}'\n".length)
                } else if (ccuBody.startsWith(")]}'")) {
                    ccuBody = ccuBody.substring(4).trimStart()
                }

                val ccuJson = JSONObject(ccuBody)
                val balance = ccuJson.optDouble("currentBalance", 0.0)
                val rate = ccuJson.optDouble("consumptionRateHourly", 0.0)
                if (balance > 0.0 || rate > 0.0) "Pro" else "Free"
            }
        } catch (_: Exception) {
            "Free"
        }
    }
}
