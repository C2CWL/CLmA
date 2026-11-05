package com.clma.app.data.datastore

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 侧边栏（聊天页左侧抽屉）样式。
 *
 * [CARD]「助手卡片」是默认：顶部一张卡片放当前助手，下面一排可横向滑动的其他助手头像，
 * 用户资料放在最底部。[RAIL]「助手导轨」：左边一条竖向可滚动的助手导轨，右边是会话面板。
 * 两种样式共用同一套会话列表、文件夹、多选、搜索和菜单入口，只是排布不同。
 */
@Serializable
enum class DrawerStyle {
    @SerialName("card")
    CARD,

    @SerialName("rail")
    RAIL,
}
