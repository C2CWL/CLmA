package com.clma.app.connectors

import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** GitHub App device flow: permissions are limited by the App installation, not a repo-wide PAT. */
class GitHubConnector(private val store: ConnectorStore, private val http: ConnectorHttp) {
    fun clientId(): String = store.get(CLIENT_KEY).orEmpty()

    fun setClientId(value: String) {
        require(value.matches(Regex("[A-Za-z0-9._-]{10,100}")))
        require(account() == null || value == clientId()) { "github_disconnect_before_changing_client" }
        store.put(CLIENT_KEY, value)
    }

    fun connection(): GitHubConnection? = account()?.let { GitHubConnection(it.login, it.allowWrites) }

    fun disconnect() = store.remove(ACCOUNT_KEY)

    fun setWritesEnabled(enabled: Boolean) {
        val account = account() ?: throw ConnectorException("github_not_connected")
        store.put(ACCOUNT_KEY, Json.encodeToString(account.copy(allowWrites = enabled)))
    }

    suspend fun beginAuthorization(): GitHubDeviceCode {
        val client = clientId().ifBlank { throw ConnectorException("github_client_id_required") }
        val result = oauth("device/code", FormBody.Builder().add("client_id", client).build())
        val expires = result["expires_in"]?.jsonPrimitive?.longOrNull
            ?: throw ConnectorException("connector_invalid_response")
        val uri = result["verification_uri"]?.jsonPrimitive?.content
            ?: throw ConnectorException("connector_invalid_response")
        // Never turn a provider response into an arbitrary URL launch.
        if (uri != "https://github.com/login/device") throw ConnectorException("connector_invalid_response")
        return GitHubDeviceCode(
            deviceCode = result["device_code"]?.jsonPrimitive?.content ?: throw ConnectorException("connector_invalid_response"),
            userCode = result["user_code"]?.jsonPrimitive?.content ?: throw ConnectorException("connector_invalid_response"),
            verificationUri = uri,
            expiresAtMs = System.currentTimeMillis() + expires * 1000,
            intervalSeconds = result["interval"]?.jsonPrimitive?.longOrNull ?: 5,
        )
    }

    suspend fun completeAuthorization(code: GitHubDeviceCode, allowWrites: Boolean) {
        val client = clientId().ifBlank { throw ConnectorException("github_client_id_required") }
        var interval = code.intervalSeconds.coerceAtLeast(5)
        while (System.currentTimeMillis() < code.expiresAtMs) {
            delay(interval * 1000)
            val result = oauth("oauth/access_token", FormBody.Builder()
                .add("client_id", client)
                .add("device_code", code.deviceCode)
                .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code").build())
            when (result["error"]?.jsonPrimitive?.content) {
                "authorization_pending" -> continue
                "slow_down" -> { interval += 5; continue }
                "access_denied" -> throw ConnectorException("github_authorization_denied")
                "expired_token" -> throw ConnectorException("github_authorization_expired")
                null -> Unit
                else -> throw ConnectorException("github_authorization_failed")
            }
            val token = result["access_token"]?.jsonPrimitive?.content
                ?: throw ConnectorException("github_authorization_failed")
            val profile = request(token, listOf("user")).jsonObject
            val login = profile["login"]?.jsonPrimitive?.content
                ?: throw ConnectorException("connector_invalid_response")
            val expires = result["expires_in"]?.jsonPrimitive?.longOrNull
            store.put(ACCOUNT_KEY, Json.encodeToString(GitHubAccount(
                login = login,
                token = token,
                allowWrites = allowWrites,
                expiresAtMs = expires?.let { System.currentTimeMillis() + it * 1000 },
            )))
            return
        }
        throw ConnectorException("github_authorization_expired")
    }

    suspend fun repositories(limit: Int, page: Int): JsonObject = list(
        listOf("user", "repos"), limit, page, mapOf("sort" to "updated"), ::repository,
    )

    suspend fun repository(owner: String, repo: String): JsonObject = untrusted(
        repository(request(token(), repoPath(owner, repo)).jsonObject),
    )

    suspend fun issues(owner: String, repo: String, state: String, limit: Int, page: Int, prs: Boolean): JsonObject {
        require(state in setOf("open", "closed", "all"))
        return list(repoPath(owner, repo) + if (prs) "pulls" else "issues", limit, page,
            mapOf("state" to state), ::issue)
    }

    suspend fun issue(owner: String, repo: String, number: Int, pr: Boolean): JsonObject {
        require(number > 0)
        val path = repoPath(owner, repo) + listOf(if (pr) "pulls" else "issues", number.toString())
        return untrusted(issue(request(token(), path).jsonObject))
    }

    suspend fun createIssue(account: String, owner: String, repo: String, title: String, body: String): JsonObject {
        require(title.isNotBlank() && title.length <= 256 && body.length <= 20_000)
        val response = request(writeToken(account), repoPath(owner, repo) + "issues", buildJsonObject {
            put("title", title)
            put("body", body)
        }).jsonObject
        return untrusted(issue(response))
    }

    suspend fun comment(account: String, owner: String, repo: String, number: Int, body: String): JsonObject {
        require(number > 0 && body.isNotBlank() && body.length <= 20_000)
        val response = request(writeToken(account), repoPath(owner, repo) + listOf("issues", number.toString(), "comments"),
            buildJsonObject { put("body", body) }).jsonObject
        return untrusted(buildJsonObject {
            response["id"]?.let { put("id", it) }
            response["html_url"]?.let { put("html_url", it) }
            put("body", response["body"]?.jsonPrimitive?.content.orEmpty().take(12_000))
        })
    }

    private fun account(): GitHubAccount? = store.get(ACCOUNT_KEY)?.let {
        runCatching { Json.decodeFromString<GitHubAccount>(it) }.getOrNull()
    }

    private fun token(): String {
        val account = account() ?: throw ConnectorException("github_not_connected")
        if (account.expiresAtMs?.let { it <= System.currentTimeMillis() } == true) {
            throw ConnectorException("github_authorization_expired_reconnect")
        }
        return account.token
    }

    private fun writeToken(reviewedAccount: String): String {
        val account = account() ?: throw ConnectorException("github_not_connected")
        if (account.login != reviewedAccount) throw ConnectorException("github_account_changed_review_again")
        if (!account.allowWrites) throw ConnectorException("github_writes_not_enabled")
        return token()
    }

    private fun repoPath(owner: String, repo: String): List<String> {
        require(owner.matches(Regex("[A-Za-z0-9-]{1,100}")))
        require(repo.matches(Regex("[A-Za-z0-9_.-]{1,100}")) && repo !in setOf(".", ".."))
        return listOf("repos", owner, repo)
    }

    private suspend fun list(
        path: List<String>, limit: Int, page: Int, query: Map<String, String>, project: (JsonObject) -> JsonObject,
    ): JsonObject {
        require(page in 1..1000)
        val size = limit.coerceIn(1, 30)
        val response = request(token(), path, query = query + mapOf("per_page" to "$size", "page" to "$page")).jsonArray
        return untrusted(buildJsonObject {
            put("items", JsonArray(response.map { project(it.jsonObject) }))
            put("page", page)
            put("may_have_more", response.size == size)
        })
    }

    private fun repository(value: JsonObject): JsonObject = buildJsonObject {
        listOf("full_name", "html_url", "private", "default_branch", "open_issues_count", "updated_at").forEach { key ->
            value[key]?.let { put(key, it) }
        }
        put("description", value["description"]?.jsonPrimitive?.contentOrNull.orEmpty().take(2000))
    }

    private fun issue(value: JsonObject): JsonObject = buildJsonObject {
        listOf("number", "title", "state", "html_url", "updated_at", "draft", "merged", "mergeable").forEach { key ->
            value[key]?.let { put(key, it) }
        }
        put("body", value["body"]?.jsonPrimitive?.contentOrNull.orEmpty().take(12_000))
        put("body_may_be_truncated", value["body"]?.jsonPrimitive?.contentOrNull.orEmpty().length > 12_000)
        value["user"]?.jsonObject?.get("login")?.let { put("author", it) }
        put("is_pull_request", "pull_request" in value || "head" in value)
        listOf("head", "base").forEach { key ->
            value[key]?.jsonObject?.get("ref")?.let { put("${key}_ref", it) }
        }
    }

    private fun untrusted(value: JsonObject): JsonObject = JsonObject(value + ("untrusted_content" to JsonPrimitive(true)))

    private suspend fun request(
        token: String, path: List<String>, body: JsonObject? = null, query: Map<String, String> = emptyMap(),
    ): JsonElement {
        val url = "https://api.github.com/".toHttpUrl().newBuilder().apply {
            path.forEach { addPathSegment(it) }
            query.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        return http.json(Request.Builder().url(url).header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "CLmA-Connectors").apply {
                body?.let { post(it.toString().toRequestBody("application/json; charset=utf-8".toMediaType())) }
            }.build())
    }

    private suspend fun oauth(path: String, body: FormBody): JsonObject = http.json(
        Request.Builder().url("https://github.com/login/$path").header("Accept", "application/json")
            .header("User-Agent", "CLmA-Connectors").post(body).build(),
    ).jsonObject

    private companion object {
        const val ACCOUNT_KEY = "github.account"
        const val CLIENT_KEY = "github.client_id"
    }
}
