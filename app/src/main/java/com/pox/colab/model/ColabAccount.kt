package com.pox.colab.model

import java.util.UUID

enum class AuthType {
    GOOGLE_OAUTH,
    COOKIE_SESSION,
    API_KEY
}

data class ColabAccount(
    val id: String = UUID.randomUUID().toString(),
    val email: String,
    val displayName: String = email.substringBefore("@"),
    val avatarUrl: String? = null,
    val authType: AuthType = AuthType.GOOGLE_OAUTH,
    val tokenOrCookie: String = "",
    val isActive: Boolean = false,
    val computeUnitsRemaining: Double = 100.0,
    val tier: String = "Colab Pro",
    val createdAt: Long = System.currentTimeMillis()
)
