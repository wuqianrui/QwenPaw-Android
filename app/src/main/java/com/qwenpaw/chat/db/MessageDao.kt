package com.qwenpaw.chat.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Insert
    suspend fun insertMessage(message: MessageEntity)

    @Update
    suspend fun updateMessage(message: MessageEntity)

    /** 按账号 + 会话查询消息(账号隔离) */
    @Query("SELECT * FROM messages WHERE accountId = :accountId AND sessionId = :sessionId AND content != '' ORDER BY timestamp ASC")
    fun getMessagesByAccountAndSession(accountId: String, sessionId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE accountId = :accountId AND sessionId = :sessionId AND content != '' ORDER BY timestamp ASC")
    suspend fun getMessagesByAccountAndSessionSync(accountId: String, sessionId: String): List<MessageEntity>

    /** 按账号查询所有消息(用于账号切换前清空 UI / 跨账号统计) */
    @Query("SELECT * FROM messages WHERE accountId = :accountId AND content != '' ORDER BY timestamp ASC")
    suspend fun getAllMessagesByAccount(accountId: String): List<MessageEntity>

    @Query("DELETE FROM messages WHERE content = ''")
    suspend fun deleteEmptyMessages()

    @Query("DELETE FROM messages WHERE accountId = :accountId AND sessionId = :sessionId")
    suspend fun deleteMessagesByAccountAndSession(accountId: String, sessionId: String)

    @Query("DELETE FROM messages WHERE accountId = :accountId")
    suspend fun deleteAllMessagesByAccount(accountId: String)

    @Query("DELETE FROM messages")
    suspend fun deleteAllMessages()
}
