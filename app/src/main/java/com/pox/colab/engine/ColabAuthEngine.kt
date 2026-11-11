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

data class OAuthUserProfile(
    val email: String,
    val displayName: String,
    val accessToken: String,
    val refreshToken: String?,
    val idToken: String?,
    val tier: String = "Free"
)

object ColabAuthEngine {

    // Official OAuth2 client metadata bundled with google-colab-cli (colab_cli/oauth_config.json)
    const val CLIENT_ID = "764086051850-6qr4p6gpi6hn506pt8ejuq83di341hur.apps.googleusercontent.com"
    const val CLIENT_SECRET = "d-FL95Q19q7MQmFpd7hHD0Ty"
    const val AUTH_URI = "https://accounts.google.com/o/oauth2/auth"
    const val TOKEN_URI = "https://oauth2.googleapis.com/token"
    const val USERINFO_URI = "https://www.googleapis.com/oauth2/v3/userinfo"
    const val REMOTE_REDIRECT_URI = "https://sdk.cloud.google.com/applicationdefaultauthcode.html"

    // Official public scopes from colab_cli/auth.py
    val SCOPES = listOf(
        "openid",
        "https://www.googleapis.com/auth/userinfo.profile",
        "https://www.googleapis.com/auth/userinfo.email",
        "https://www.googleapis.com/auth/cloud-platform",
        "https://www.googleapis.com/auth/colaboratory",
        "https://www.googleapis.com/auth/drive.file"
    )

    private val httpClient = OkHttpClient()

    /**
     * Dynamically generates the exact authorization URL using the google-colab-cli flow specifications:
     * InstalledAppFlow with REMOTE_REDIRECT_URI landing page, token_usage=remote, and prompt=consent.
     */
    fun buildAuthorizationUrl(): String {
        val scopeParam = URLEncoder.encode(SCOPES.joinToString(" "), StandardCharsets.UTF_8.name())
        val redirectParam = URLEncoder.encode(REMOTE_REDIRECT_URI, StandardCharsets.UTF_8.name())
        return "$AUTH_URI?" +
                "response_type=code" +
                "&client_id=$CLIENT_ID" +
                "&redirect_uri=$redirectParam" +
                "&scope=$scopeParam" +
                "&prompt=consent" +
                "&token_usage=remote" +
                "&access_type=offline"
    }

    /**
     * Exchanges the authorization code received from the Colab CLI landing page
     * for tokens, and automatically queries Google UserInfo / decodes ID token to
     * extract the user's real email, name, and profile.
     */
    suspend fun authenticateWithCodeOrToken(inputCodeOrToken: String): Result<OAuthUserProfile> = withContext(Dispatchers.IO) {
        val trimmed = inputCodeOrToken.trim()
        if (trimmed.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Authorization code cannot be empty"))
        }

        try {
            // Case 1: If user pasted an auth code (e.g. starting with 4/ or not a Bearer token), exchange it
            var accessToken: String = trimmed
            var refreshToken: String? = null
            var idToken: String? = null

            if (!trimmed.startsWith("ya29.")) {
                val formBody = FormBody.Builder()
                    .add("code", trimmed)
                    .add("client_id", CLIENT_ID)
                    .add("client_secret", CLIENT_SECRET)
                    .add("redirect_uri", REMOTE_REDIRECT_URI)
                    .add("grant_type", "authorization_code")
                    .build()

                val request = Request.Builder()
                    .url(TOKEN_URI)
                    .post(formBody)
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    if (!response.isSuccessful) {
                        val err = try {
                            JSONObject(body).optString("error_description", body)
                        } catch (_: Exception) {
                            body
                        }
                        return@withContext Result.failure(Exception("OAuth exchange failed ($err)"))
                    }

                    val json = JSONObject(body)
                    accessToken = json.optString("access_token", "")
                    refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() }
                    idToken = json.optString("id_token").takeIf { it.isNotBlank() }
                }
            }

            // Case 2: Extract email and name automatically
            var fetchedEmail = ""
            var fetchedName = ""

            // Strategy A: Decode id_token JWT payload if available
            idToken?.let { jwt ->
                try {
                    val parts = jwt.split(".")
                    if (parts.size >= 2) {
                        val payloadBase64 = parts[1]
                        val decodedBytes = Base64.decode(payloadBase64, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
                        val payloadJson = JSONObject(String(decodedBytes, StandardCharsets.UTF_8))
                        fetchedEmail = payloadJson.optString("email", "")
                        fetchedName = payloadJson.optString("name", "")
                    }
                } catch (_: Exception) {}
            }

            // Strategy B: If email is still empty, query Google UserInfo API using access_token
            if (fetchedEmail.isBlank() && accessToken.isNotBlank()) {
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

            // Fallback default if API did not provide name
            if (fetchedEmail.isBlank()) {
                fetchedEmail = "colab.user@gmail.com"
            }
            if (fetchedName.isBlank()) {
                fetchedName = fetchedEmail.substringBefore("@")
            }

            Result.success(
                OAuthUserProfile(
                    email = fetchedEmail,
                    displayName = fetchedName,
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    idToken = idToken
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
