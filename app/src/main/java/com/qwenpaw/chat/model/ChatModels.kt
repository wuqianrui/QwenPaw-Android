package com.qwenpaw.chat.model

data class ChatMessage(
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class ChatRequest(
    val input: List<MessageContent>,
    val session_id: String,
    val user_id: String,
    val channel: String = "console"
)

data class MessageContent(
    val role: String,
    val content: List<TextContent>
)

data class TextContent(
    val type: String = "text",
    val text: String
)

data class ChatResponse(
    val sequence_number: Int,
    val `object`: String,
    val status: String,
    val output: List<AssistantMessage>?,
    val error: ErrorInfo?,
    val session_id: String?,
    val usage: UsageInfo?
)

data class AssistantMessage(
    val role: String,
    val content: List<TextContent>
)

data class ErrorInfo(
    val message: String,
    val code: String?
)

data class UsageInfo(
    val prompt_tokens: Int?,
    val completion_tokens: Int?,
    val total_tokens: Int?
)