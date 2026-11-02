package com.clma.app.connectors

import android.accounts.Account
import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.Scope
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class GmailConnector(context: Context, private val store: ConnectorStore, private val http: ConnectorHttp) {
    val authorizationClient = Identity.getAuthorizationClient(context.applicationContext)
    private val draftMutex = Mutex()

    enum class Access { READ, SEND, REMOTE_DRAFTS }

    fun account(): GmailAccount? = store.get(ACCOUNT_KEY)?.let {
        runCatching { Json.decodeFromString<GmailAccount>(it) }.getOrNull()
    }

    suspend fun authorize(access: Access): AuthorizationResult {
        val scopes = listOf(READ_SCOPE) + when (access) {
            Access.READ -> emptyList()
            Access.SEND -> listOf(SEND_SCOPE)
            Access.REMOTE_DRAFTS -> listOf(COMPOSE_SCOPE)
        }
        return authorizationClient.authorize(request(scopes)).await()
    }

    suspend fun finishAuthorization(result: AuthorizationResult, access: Access) {
        val token = result.accessToken ?: throw ConnectorException("gmail_authorization_required")
        val profile = api(token, listOf("profile"))
        val email = profile["emailAddress"]?.jsonPrimitive?.content
            ?: throw ConnectorException("connector_invalid_response")
        val previous = account()
        if (previous != null && previous.email != email) throw ConnectorException("gmail_account_mismatch")
        val granted = result.grantedScopes.toSet()
        val required = when (access) {
            Access.READ -> READ_SCOPE
            Access.SEND -> SEND_SCOPE
            Access.REMOTE_DRAFTS -> COMPOSE_SCOPE
        }
        if (READ_SCOPE !in granted || required !in granted) throw ConnectorException("gmail_scope_not_granted")
        store.put(ACCOUNT_KEY, Json.encodeToString(GmailAccount(
            email = email,
            scopes = granted,
            allowSend = previous?.allowSend == true || access == Access.SEND,
            allowRemoteDrafts = previous?.allowRemoteDrafts == true || access == Access.REMOTE_DRAFTS,
        )))
    }

    fun disableAccess(access: Access) {
        val account = requireAccount()
        val updated = when (access) {
            Access.READ -> throw IllegalArgumentException("Disconnect the account to disable reading")
            Access.SEND -> account.copy(allowSend = false)
            Access.REMOTE_DRAFTS -> account.copy(allowRemoteDrafts = false)
        }
        store.put(ACCOUNT_KEY, Json.encodeToString(updated))
    }

    suspend fun disconnect(revoke: Boolean) = draftMutex.withLock {
        val account = account()
        if (revoke && account != null) {
            authorizationClient.revokeAccess(RevokeAccessRequest.builder()
                .setAccount(Account(account.email, "com.google"))
                .setScopes(account.scopes.map(::Scope)).build()).await()
        }
        store.keys(DRAFT_PREFIX).forEach(store::remove)
        store.remove(ACCOUNT_KEY)
    }

    suspend fun search(query: String, limit: Int, pageToken: String?): JsonObject {
        require(query.length <= 2000)
        require(pageToken == null || pageToken.length <= 2000)
        val response = api(token(READ_SCOPE), listOf("messages"), query = buildMap {
            put("q", query)
            put("maxResults", limit.coerceIn(1, 50).toString())
            pageToken?.let { put("pageToken", it) }
        })
        return buildJsonObject {
            put("untrusted_content", true)
            response["messages"]?.let { put("messages", it) }
            response["nextPageToken"]?.let { put("next_page_token", it) }
            response["resultSizeEstimate"]?.let { put("result_size_estimate", it) }
        }
    }

    suspend fun read(messageId: String): JsonObject = GmailMime.message(message(messageId))

    suspend fun createDraft(to: String, cc: String, subject: String, body: String, replyToId: String?): MailDraft {
        val account = requireAccount()
        var draft = GmailMime.validate(MailDraft(UUID.randomUUID().toString(), account.email, to, cc, subject, body))
        if (replyToId != null) {
            val message = message(replyToId)
            val payload = message["payload"]?.jsonObject ?: throw ConnectorException("connector_invalid_response")
            val messageId = GmailMime.header(payload, "Message-ID")
                ?: throw ConnectorException("gmail_reply_message_id_missing")
            val originalSubject = GmailMime.header(payload, "Subject").orEmpty()
            // Gmail threading requires a matching Subject, plus References and In-Reply-To.
            require(subject.removePrefix("Re: ").equals(originalSubject.removePrefix("Re: "), ignoreCase = true))
            draft = GmailMime.validate(draft.copy(
                threadId = message["threadId"]?.jsonPrimitive?.content,
                inReplyTo = messageId,
                references = messageId,
            ))
        }
        return draftMutex.withLock {
            if (requireAccount().email != draft.account) throw ConnectorException("gmail_account_mismatch")
            if (store.keys(DRAFT_PREFIX).size >= 100) throw ConnectorException("gmail_local_draft_limit")
            save(draft)
            draft
        }
    }

    fun drafts(): List<MailDraft> = store.keys(DRAFT_PREFIX).mapNotNull { key ->
        store.get(key)?.let { runCatching { Json.decodeFromString<MailDraft>(it) }.getOrNull() }
    }

    fun draft(id: String): MailDraft {
        require(ID_PATTERN.matches(id))
        return store.get(DRAFT_PREFIX + id)?.let { Json.decodeFromString<MailDraft>(it) }
            ?: throw ConnectorException("gmail_draft_not_found")
    }

    suspend fun updateDraft(id: String, to: String, cc: String, subject: String, body: String) = draftMutex.withLock {
        val draft = draft(id)
        if (draft.account != requireAccount().email) throw ConnectorException("gmail_account_mismatch")
        if (draft.status != "local") throw ConnectorException("gmail_draft_already_attempted")
        require(draft.inReplyTo == null || subject == draft.subject) { "gmail_reply_subject_must_match" }
        save(GmailMime.validate(draft.copy(to = to, cc = cc, subject = subject, body = body)))
    }

    suspend fun deleteDraft(id: String) = draftMutex.withLock {
        draft(id)
        store.remove(DRAFT_PREFIX + id)
    }

    /** The approval card carries this entire snapshot, not just an opaque draft id. */
    fun preview(draft: MailDraft): JsonObject = buildJsonObject {
        put("account", draft.account)
        put("to", draft.to)
        put("cc", draft.cc)
        put("subject", draft.subject)
        put("body", draft.body)
    }

    suspend fun uploadDraft(id: String, reviewed: JsonObject): JsonObject = draftMutex.withLock {
        val draft = checkedDraft(id, reviewed)
        val account = requireAccount()
        if (!account.allowRemoteDrafts) throw ConnectorException("gmail_compose_not_enabled")
        if (draft.status != "local") throw ConnectorException("gmail_draft_already_attempted")
        val token = token(COMPOSE_SCOPE)
        save(draft.copy(status = "uploading"))
        try {
            val response = api(token, listOf("drafts"), jsonBody = buildJsonObject { put("message", rawMessage(draft)) })
            val remoteId = response["id"]?.jsonPrimitive?.content ?: throw ConnectorException("connector_invalid_response")
            save(draft.copy(status = "remote_saved", remoteId = remoteId))
            buildJsonObject { put("draft_id", id); put("remote_draft_id", remoteId); put("sent", false) }
        } catch (e: Exception) {
            save(draft.copy(status = "upload_unknown"))
            if (e is CancellationException) throw e
            throw ConnectorException("gmail_draft_upload_uncertain_check_gmail")
        }
    }

    suspend fun send(id: String, reviewed: JsonObject): JsonObject = draftMutex.withLock {
        val draft = checkedDraft(id, reviewed)
        val account = requireAccount()
        if (!account.allowSend) throw ConnectorException("gmail_send_not_enabled")
        if (draft.status !in setOf("local", "remote_saved")) throw ConnectorException("gmail_draft_already_attempted")
        val token = token(SEND_SCOPE)
        // Persist before POST: a timeout/process death must never silently resend this draft.
        save(draft.copy(status = "sending"))
        try {
            // Send the reviewed snapshot, never a remotely edited draft body.
            val response = api(token, listOf("messages", "send"), jsonBody = rawMessage(draft))
            val messageId = response["id"]?.jsonPrimitive?.content ?: throw ConnectorException("connector_invalid_response")
            save(draft.copy(status = "sent", remoteId = messageId))
            buildJsonObject {
                put("sent", true)
                put("message_id", messageId)
                put("remote_draft_may_remain", draft.status == "remote_saved")
            }
        } catch (e: Exception) {
            save(draft.copy(status = "send_unknown"))
            if (e is CancellationException) throw e
            throw ConnectorException("gmail_send_uncertain_check_sent_folder")
        }
    }

    private fun checkedDraft(id: String, reviewed: JsonObject): MailDraft {
        val draft = draft(id)
        if (draft.account != requireAccount().email) throw ConnectorException("gmail_account_mismatch")
        if (preview(draft) != reviewed) throw ConnectorException("gmail_draft_changed_review_again")
        return draft
    }

    private fun save(draft: MailDraft) = store.put(DRAFT_PREFIX + draft.id, Json.encodeToString(draft))

    private suspend fun message(id: String): JsonObject {
        require(ID_PATTERN.matches(id))
        return api(token(READ_SCOPE), listOf("messages", id), query = mapOf("format" to "full"))
    }

    private fun requireAccount(): GmailAccount = account() ?: throw ConnectorException("gmail_not_connected")

    private fun request(scopes: List<String>): AuthorizationRequest = AuthorizationRequest.builder().apply {
        setRequestedScopes(scopes.map(::Scope))
        account()?.let { setAccount(Account(it.email, "com.google")) }
    }.build()

    private suspend fun token(scope: String): String {
        requireAccount()
        val result = authorizationClient.authorize(request(listOf(scope))).await()
        if (result.hasResolution() || scope !in result.grantedScopes) throw ConnectorException("gmail_authorization_required")
        return result.accessToken ?: throw ConnectorException("gmail_authorization_required")
    }

    private fun rawMessage(draft: MailDraft): JsonObject = buildJsonObject {
        put("raw", GmailMime.raw(draft))
        draft.threadId?.let { put("threadId", it) }
    }

    private suspend fun api(
        token: String,
        path: List<String>,
        query: Map<String, String> = emptyMap(),
        jsonBody: JsonObject? = null,
    ): JsonObject {
        val url = "https://gmail.googleapis.com/gmail/v1/users/me/".toHttpUrl().newBuilder().apply {
            path.forEach { addPathSegment(it) }
            query.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").apply {
            jsonBody?.let { post(it.toString().toRequestBody("application/json; charset=utf-8".toMediaType())) }
        }.build()
        return http.json(request).jsonObject
    }

    companion object {
        const val READ_SCOPE = "https://www.googleapis.com/auth/gmail.readonly"
        const val SEND_SCOPE = "https://www.googleapis.com/auth/gmail.send"
        const val COMPOSE_SCOPE = "https://www.googleapis.com/auth/gmail.compose"
        private const val ACCOUNT_KEY = "gmail.account"
        private const val DRAFT_PREFIX = "gmail.draft."
        private val ID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
    }
}
