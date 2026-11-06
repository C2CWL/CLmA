package com.clma.app.connectors

import kotlinx.serialization.Serializable

@Serializable
internal data class GitHubAccount(val login: String, val token: String, val allowWrites: Boolean = false, val expiresAtMs: Long? = null)

data class GitHubConnection(val login: String, val allowWrites: Boolean)

data class GitHubDeviceCode(
    internal val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresAtMs: Long,
    val intervalSeconds: Long,
)
