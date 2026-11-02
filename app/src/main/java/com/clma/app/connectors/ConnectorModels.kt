package com.clma.app.connectors

import kotlinx.serialization.Serializable

@Serializable
data class GmailAccount(
    val email: String,
    val scopes: Set<String>,
    val allowSend: Boolean = false,
    val allowRemoteDrafts: Boolean = false,
)

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

@Serializable
data class MailDraft(
    val id: String,
    val account: String,
    val to: String,
    val cc: String = "",
    val subject: String,
    val body: String,
    val threadId: String? = null,
    val inReplyTo: String? = null,
    val references: String? = null,
    val status: String = "local",
    val remoteId: String? = null,
)
