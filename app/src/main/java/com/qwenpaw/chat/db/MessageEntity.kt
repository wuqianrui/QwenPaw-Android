package com.qwenpaw.chat.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 消息实体
 *
 * - accountId: 关联的账号 id(用于按账号隔离聊天记录)
 * - sessionId : 会话 id(目前每账号下只用一个 session,后续可扩展多会话)
 *
 * 索引: (accountId, sessionId) 复合索引,加快按账号+会话查询的速度
 */
@Entity(
    tableName = "messages",
    indices = [Index(value = ["accountId", "sessionId"])]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val sessionId: String,
    @ColumnInfo(name = "accountId")
    val accountId: String
)
