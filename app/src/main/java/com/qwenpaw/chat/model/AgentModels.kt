package com.qwenpaw.chat.model

// ---------------- Agent 列表(GET /api/agents) ----------------

// GET /api/agents 响应体
data class AgentsResponse(
    val agents: List<AgentInfo>
)

// Agent 条目(参考 docs/qwenpaw/05 agent.md)
data class AgentInfo(
    val id: String,
    val name: String?,
    val description: String?,
    val workspace_dir: String?,
    val enabled: Boolean?,
    val active_model: ActiveModel?
)

// Agent 当前激活模型
data class ActiveModel(
    val provider_id: String?,
    val model: String?
)

// ---------------- 会话列表(GET /api/chats) ----------------

// Agent 的会话条目(参考 docs/qwenpaw/06 agent's chathistory.md)
data class ChatSession(
    val id: String,
    val name: String?,
    val session_id: String?,
    val user_id: String?,
    val channel: String?,
    val created_at: String?,
    val updated_at: String?,
    val status: String?,
    val pinned: Boolean?,
    val source: String?
)

// ---------------- 会话历史(GET /api/chats/{chatId}) ----------------

// GET /api/chats/{chatId} 响应体(参考 docs/qwenpaw/07 single chat history.md)
data class ChatHistoryResponse(
    val messages: List<HistoryMessage>?,
    val status: String?
)

// 历史消息条目:type 为 message(正文) / plugin_call(工具调用) / plugin_call_output(工具结果)
data class HistoryMessage(
    val id: String?,
    val type: String?,
    val role: String?,
    val content: List<HistoryContent>?,
    val status: String?,
    val metadata: HistoryMetadata?
)

// 消息内容片段:正文消息取 type=text 的 text 字段,图片消息取 type=image 的 image_url 字段,
// plugin_call/plugin_call_output 取 type=data 的 data 字段
data class HistoryContent(
    val type: String?,
    val text: String?,
    // type=image 时为服务端存储绝对路径(上传返回的 url)
    val image_url: String?,
    val data: HistoryContentData?
)

// data 片段:工具调用名与参数(plugin_call)或执行结果(plugin_call_output)
data class HistoryContentData(
    val call_id: String?,
    val name: String?,
    val arguments: String?,
    val output: String?
)

data class HistoryMetadata(
    val original_id: String?,
    val original_name: String?,
    val metadata: HistoryInnerMetadata?,
    val timestamp: String?
)

data class HistoryInnerMetadata(
    val qwenpaw_turn_usage: TurnUsageResponse?
)
