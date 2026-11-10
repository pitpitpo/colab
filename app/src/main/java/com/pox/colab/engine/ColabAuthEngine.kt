package com.pox.colab.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object ColabAuthEngine {

    // Official OAuth2 client metadata bundled with google-colab-cli (colab_cli/oauth_config.json)
    const val CLIENT_ID = "764086051850-6qr4p6gpi6hn506pt8ejuq83di341hur.apps.googleusercontent.com"
    const val CLIENT_SECRET = "d-FL95Q19q7MQmFpd7hHD0Ty"
    const val AUTH_URI = "https://accounts.google.com/o/oauth2/auth"
    const val TOKEN_URI = "https://oauth2.googleapis.com/token"
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
     * for a valid Google OAuth2 Token (access_token, refresh_token, id_token).
     */
    suspend fun exchangeAuthCode(authCode: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val formBody = FormBody.Builder()
                .add("code", authCode.trim())
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
                val token = json.optString("access_token", body)
                Result.success(token)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
