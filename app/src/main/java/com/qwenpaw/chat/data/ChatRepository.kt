package com.qwenpaw.chat.data

import com.qwenpaw.chat.db.MessageDao
import com.qwenpaw.chat.db.MessageEntity
import com.qwenpaw.chat.model.ChatMessage
import com.qwenpaw.chat.model.ChatRequest
import com.qwenpaw.chat.model.MessageContent
import com.qwenpaw.chat.model.TextContent
import com.qwenpaw.chat.network.SSEClient
import com.qwenpaw.chat.network.SSEEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ChatRepository(
    private val baseUrl: String,
    private val agentId: String,
    private val authToken: String? = null,
    private val messageDao: MessageDao
) {
    private val sseClient = SSEClient(baseUrl, agentId, authToken)
    private val userId = "default"
    private val sessionId: String = "default_session"

    suspend fun sendMessage(content: String): Flow<SSEEvent> {
        if (content.isNotEmpty()) {
            messageDao.insertMessage(
                MessageEntity(
                    role = "user",
                    content = content,
                    sessionId = sessionId
                )
            )
        }

        val request = ChatRequest(
            input = listOf(
                MessageContent(
                    role = "user",
                    content = listOf(TextContent(text = content))
                )
            ),
            session_id = sessionId,
            user_id = userId,
            channel = "console"
        )

        return sseClient.sendMessage(request)
    }

    suspend fun addAssistantMessage(content: String) {
        if (content.isNotEmpty()) {
            messageDao.insertMessage(
                MessageEntity(
                    role = "assistant",
                    content = content,
                    sessionId = sessionId
                )
            )
        }
    }

    suspend fun updateLastAssistantMessage(content: String) {
        if (content.isNotEmpty()) {
            val messages = messageDao.getMessagesBySessionSync(sessionId)
            if (messages.isNotEmpty() && messages.last().role == "assistant") {
            } else {
                messageDao.insertMessage(
                    MessageEntity(
                        role = "assistant",
                        content = content,
                        sessionId = sessionId
                    )
                )
            }
        }
    }

    fun getMessagesFlow(): Flow<List<ChatMessage>> {
        return messageDao.getMessagesBySession(sessionId).map { entities ->
            entities.map { entity ->
                ChatMessage(
                    role = entity.role,
                    content = entity.content,
                    timestamp = entity.timestamp
                )
            }
        }
    }

    suspend fun getMessages(): List<ChatMessage> {
        return messageDao.getMessagesBySessionSync(sessionId).map { entity ->
            ChatMessage(
                role = entity.role,
                content = entity.content,
                timestamp = entity.timestamp
            )
        }
    }

    suspend fun clearMessages() {
        messageDao.deleteMessagesBySession(sessionId)
    }
}