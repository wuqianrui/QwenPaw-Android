package com.qwenpaw.chat.data

import com.qwenpaw.chat.model.ChatRequest
import com.qwenpaw.chat.model.ImageContent
import com.qwenpaw.chat.model.MessageContent
import com.qwenpaw.chat.model.TextContent
import com.qwenpaw.chat.network.SSEClient
import com.qwenpaw.chat.network.SSEEvent
import kotlinx.coroutines.flow.Flow

/**
 * 聊天数据仓库
 *
 * 历史记录不再落本地数据库:进入会话时直接通过
 * GET /api/chats/{chatId} 拉取服务端历史,发送仍走 SSE 流式接口。
 */
class ChatRepository(
    private val baseUrl: String,
    private val agentId: String,
    private val authToken: String? = null,
    private val userId: String = "default",
    private val sessionId: String
) {
    private val sseClient = SSEClient(baseUrl, agentId, authToken)

    /**
     * 发送消息(可附带图片)。
     * @param content 文本内容,纯图片发送时可为空
     * @param imageUrl 图片的服务端存储路径(/api/console/upload 上传返回的 url),为 null 表示纯文本
     */
    suspend fun sendMessage(content: String, imageUrl: String? = null): Flow<SSEEvent> {
        // content 数组按 文本 → 图片 顺序混排,与 console 端一致(见 docs/qwenpaw/08 upload.md)
        val contents = mutableListOf<Any>()
        if (content.isNotEmpty()) {
            contents.add(TextContent(text = content))
        }
        if (imageUrl != null) {
            contents.add(ImageContent(image_url = imageUrl))
        }
        val request = ChatRequest(
            input = listOf(
                MessageContent(
                    role = "user",
                    content = contents
                )
            ),
            session_id = sessionId,
            user_id = userId,
            channel = "console"
        )

        return sseClient.sendMessage(request)
    }
}
