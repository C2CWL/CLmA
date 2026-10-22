package me.rerere.rikkahub.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 头像。作为 [Assistant.avatar] / DisplaySetting.userAvatar 被持久化到 DataStore 和备份文件中，
 * 多态判别字段 `type` 的值默认是子类的全限定类名。
 *
 * 这里显式用 @SerialName 固定为**当前**的全限定名，保证以后包名迁移（me.rerere.* -> com.clma.*）
 * 或类重命名后，已存储的 JSON 仍然可以解码。这些字符串一旦发布就不能再改，否则旧助手列表会解码失败，
 * 而 PreferencesStore 的回退逻辑会静默写回空列表。
 */
@Serializable
sealed class Avatar {
    @Serializable
    @SerialName("me.rerere.rikkahub.data.model.Avatar.Dummy")
    data object Dummy : Avatar()

    @Serializable
    @SerialName("me.rerere.rikkahub.data.model.Avatar.Emoji")
    data class Emoji(val content: String) : Avatar()

    @Serializable
    @SerialName("me.rerere.rikkahub.data.model.Avatar.Image")
    data class Image(val url: String) : Avatar()
}
