package me.rerere.rikkahub.data.sync.s3

import kotlinx.serialization.Serializable
import me.rerere.rikkahub.data.sync.BackupItem

@Serializable
data class S3Config(
    val endpoint: String = "",
    val accessKeyId: String = "",
    val secretAccessKey: String = "",
    val bucket: String = "",
    val region: String = "auto",
    val pathStyle: Boolean = true,
    // Backed-up slices. New installs default to everything; a pre-granular `["DATABASE","FILES"]`
    // still decodes because BackupItem keeps the legacy FILES value, and is expanded on the next
    // write by BackupItem.normalize.
    val items: List<BackupItem> = BackupItem.selectable,
) {
    val host: String
        get() = endpoint
            .removePrefix("https://")
            .removePrefix("http://")
            .trimEnd('/')

    val isHttps: Boolean
        get() = endpoint.startsWith("https://")

    fun bucketUrl(): String {
        return if (pathStyle) {
            "${endpoint.trimEnd('/')}/$bucket"
        } else {
            val scheme = if (isHttps) "https://" else "http://"
            "$scheme$bucket.$host"
        }
    }
}
