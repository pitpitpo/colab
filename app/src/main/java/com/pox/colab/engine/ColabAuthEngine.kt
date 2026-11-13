package com.pox.colab.engine

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

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

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var activeSession: ColabAuthSession? = null

    /**
     * Creates a new cryptographically authentic Colab CLI OAuth 2.0 PKCE Session
     * exactly identical to google_auth_oauthlib.flow.InstalledAppFlow in google-colab-cli.
     */
    fun createAuthSession(): ColabAuthSession {
        // RFC 7636 PKCE code_verifier (64 random bytes, URL safe, no padding)
        val verifierBytes = ByteArray(64)
        SecureRandom().nextBytes(verifierBytes)
        val codeVerifier = Base64.encodeToString(verifierBytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP).trim()

        // S256 code_challenge = base64url(sha256(code_verifier))
        val sha256Digest = MessageDigest.getInstance("SHA-256").digest(codeVerifier.toByteArray(StandardCharsets.US_ASCII))
        val codeChallenge = Base64.encodeToString(sha256Digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP).trim()

        // Random state parameter
        val stateBytes = ByteArray(24)
        SecureRandom().nextBytes(stateBytes)
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
        activeSession = session
        return session
    }

    fun getOrNewAuthSession(): ColabAuthSession {
        return activeSession ?: createAuthSession()
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
     * Exchanges the authorization code received from Google's applicationdefaultauthcode.html
     * landing page for OAuth credentials using PKCE verification, then automatically fetches
     * Google user info and detects Colab compute unit subscription tier without manual entry.
     */
    suspend fun authenticateWithCodeOrToken(
        inputCodeOrToken: String,
        session: ColabAuthSession? = null
    ): Result<OAuthUserProfile> = withContext(Dispatchers.IO) {
        val trimmed = inputCodeOrToken.trim()
        if (trimmed.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Authorization code cannot be empty"))
        }

        val verifier = session?.codeVerifier ?: activeSession?.codeVerifier

        try {
            var accessToken = trimmed
            var refreshToken: String? = null
            var idToken: String? = null

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
                }
            }

            if (accessToken.isBlank()) {
                return@withContext Result.failure(Exception("No valid access token received from Google OAuth"))
            }

            // Step 2: Automatically extract identity from ID token and/or Google UserInfo API
            var fetchedEmail = ""
            var fetchedName = ""

            // Strategy A: Decode id_token JWT payload if available
            idToken?.let { jwt ->
                try {
                    val parts = jwt.split(".")
                    if (parts.size >= 2) {
                        val payloadBytes = Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
                        val payloadJson = JSONObject(String(payloadBytes, StandardCharsets.UTF_8))
                        fetchedEmail = payloadJson.optString("email", "")
                        fetchedName = payloadJson.optString("name", "")
                    }
                } catch (_: Exception) {}
            }

            // Strategy B: Query Google UserInfo API using access_token
            if (fetchedEmail.isBlank()) {
                try {
                    val userinfoRequest = Request.Builder()
                        .url(USERINFO_URI)
                        .header("Authorization", "Bearer $accessToken")
                        .get()
                        .build()

                    httpClient.newCall(userinfoRequest).execute().use { userinfoResp ->
                        if (userinfoResp.isSuccessful) {
                            val uBody = userinfoResp.body?.string() ?: ""
                            val uJson = JSONObject(uBody)
                            fetchedEmail = uJson.optString("email", "")
                            if (fetchedName.isBlank()) {
                                fetchedName = uJson.optString("name", "")
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            if (fetchedEmail.isBlank()) {
                fetchedEmail = "google.colab.user@gmail.com"
            }
            if (fetchedName.isBlank()) {
                fetchedName = fetchedEmail.substringBefore("@")
            }

            // Step 3: Automatically detect Colab tier via CCU endpoint (/tun/m/ccu-info)
            var detectedTier = "Free"
            try {
                val ccuRequest = Request.Builder()
                    .url("$COLAB_DOMAIN/tun/m/ccu-info?authuser=0")
                    .header("Authorization", "Bearer $accessToken")
                    .header("Accept", "application/json")
                    .header("X-Colab-Client-Agent", "colab-cli")
                    .get()
                    .build()

                httpClient.newCall(ccuRequest).execute().use { ccuResp ->
                    if (ccuResp.isSuccessful) {
                        var ccuBody = ccuResp.body?.string() ?: ""
                        // Strip Google XSSI prefix ")]}'\n"
                        if (ccuBody.startsWith(")]}'\n")) {
                            ccuBody = ccuBody.substring(")]}'\n".length)
                        } else if (ccuBody.startsWith(")]}'")) {
                            ccuBody = ccuBody.substring(4).trimStart()
                        }

                        val ccuJson = JSONObject(ccuBody)
                        val balance = ccuJson.optDouble("currentBalance", 0.0)
                        val rate = ccuJson.optDouble("consumptionRateHourly", 0.0)

                        detectedTier = when {
                            balance >= 500.0 -> "Colab Pro+"
                            balance > 0.0 || rate > 0.0 -> "Colab Pro"
                            else -> "Free"
                        }
                    }
                }
            } catch (_: Exception) {
                // Default to Free tier if network or quota check is inconclusive
                detectedTier = "Free"
            }

            // Step 4: Serialize in authentic ~/.config/colab-cli/token.json format
            val tokenJsonObj = JSONObject().apply {
                put("token", accessToken)
                put("refresh_token", refreshToken ?: "")
                put("token_uri", TOKEN_URI)
                put("client_id", CLIENT_ID)
                put("client_secret", CLIENT_SECRET)
                put("scopes", org.json.JSONArray(SCOPES))
                put("universe_domain", "googleapis.com")
                put("account", fetchedEmail)
            }

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
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
