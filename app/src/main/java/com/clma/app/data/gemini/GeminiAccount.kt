package com.clma.app.data.gemini

import kotlinx.serialization.Serializable

/**
 * One signed-in Google account usable against Cloud Code Assist.
 *
 * [projectId] is the `cloudaicompanionProject` resolved once at sign-in through
 * loadCodeAssist / onboardUser. Every generate request has to carry it, so it is stored with the
 * tokens rather than rediscovered per request.
 */
@Serializable
data class GeminiAccount(
    val id: String,
    val name: String,
    val email: String = "",
    val projectId: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val enabled: Boolean = true,
    val tokenStatus: GeminiTokenStatus = GeminiTokenStatus.UNKNOWN,
    val usage: GeminiUsageSnapshot? = null,
)

/**
 * What is left of the account's Code Assist quota.
 *
 * Cloud Code Assist reports quota per model rather than per account, so each window here is the
 * scarcest reading across every model the account can reach: that is the one that will actually
 * stop a request.
 */
@Serializable
data class GeminiUsageSnapshot(
    val daily: GeminiUsageWindow? = null,
    val weekly: GeminiUsageWindow? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Serializable
data class GeminiUsageWindow(
    val remainingFraction: Double,
    val resetsAt: Long? = null, // epoch seconds
)

@Serializable
enum class GeminiTokenStatus {
    UNKNOWN,
    AVAILABLE,
    EXPIRED,
    INVALID,
}

internal fun GeminiAccount.isAvailable(): Boolean =
    enabled && tokenStatus != GeminiTokenStatus.INVALID

/**
 * True when this window has nothing left and its reset has not arrived yet.
 *
 * A zero fraction with no reported [GeminiUsageWindow.resetsAt] counts as exhausted: requests are
 * already being refused and there is no other evidence the window recovered.
 */
internal fun GeminiUsageWindow.isExhausted(nowMillis: Long = System.currentTimeMillis()): Boolean =
    remainingFraction <= 0.0 && (resetsAt == null || resetsAt * 1000L > nowMillis)

/**
 * True when the account's last snapshot says one of its windows is spent.
 *
 * The snapshot keeps the scarcest reading across every model the account can reach, so this is a
 * hint for *ordering* accounts, never proof that the model a caller is about to ask for is spent -
 * it must not be used to exclude an account outright.
 */
internal fun GeminiAccount.isQuotaExhausted(nowMillis: Long = System.currentTimeMillis()): Boolean =
    usage?.let { snapshot ->
        snapshot.daily?.isExhausted(nowMillis) == true || snapshot.weekly?.isExhausted(nowMillis) == true
    } ?: false
