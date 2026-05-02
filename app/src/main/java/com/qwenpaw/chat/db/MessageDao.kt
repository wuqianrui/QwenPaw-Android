package com.qwenpaw.chat.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Insert
    suspend fun insertMessage(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId AND content != '' ORDER BY timestamp ASC")
    fun getMessagesBySession(sessionId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId AND content != '' ORDER BY timestamp ASC")
    suspend fun getMessagesBySessionSync(sessionId: String): List<MessageEntity>

    @Query("DELETE FROM messages WHERE content = ''")
    suspend fun deleteEmptyMessages()

    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    suspend fun deleteMessagesBySession(sessionId: String)

    @Query("DELETE FROM messages")
    suspend fun deleteAllMessages()
}