package com.qwenpaw.chat.model

import java.util.UUID

/**
 * 多账号数据模型
 *
 * 字段:
 * - id: 账号唯一标识(UUID),用于在数据库中关联消息记录
 * - alias: 用户自定义的别名(便于区分不同账号)
 * - baseUrl: 该账号的登录/服务器地址(不同账号可以不同)
 * - username: 登录用户名
 * - password: 登录密码(本地保存以便快速切换/自动登录)
 * - token: 当前会话的鉴权 token
 * - colorName: 环境标识色名称(对应 [AccountColor]),null/空表示尚未分配
 * - createdAt: 账号创建时间
 */
data class Account(
    val id: String = UUID.randomUUID().toString(),
    var alias: String = "",
    var baseUrl: String = "",
    var username: String = "",
    var password: String = "",
    var token: String = "",
    var colorName: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    /** 用于在 UI 上展示的默认别名:优先用 alias,否则用 username */
    fun displayName(): String =
        if (alias.isNotBlank()) alias else username

    /** 账号已分配的标识色,未分配时为 null */
    fun accountColor(): AccountColor? = AccountColor.fromName(colorName)

    /** 是否已分配标识色 */
    fun hasColor(): Boolean = accountColor() != null
}
