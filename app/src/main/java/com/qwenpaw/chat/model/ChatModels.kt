package com.qwenpaw.chat.model

// 聚合气泡内的段落:正文文本、思考过程或工具调用行(历史加载时把连续的 assistant 消息按原顺序聚合)
sealed class MessageSegment {
    data class Text(val text: String) : MessageSegment()

    /**
     * 思考过程段落(历史消息 type=reasoning,模型内部推理文本)
     * @param text 思考全文,气泡行内显示截断预览,点击弹窗查看全文
     */
    data class Reasoning(val text: String) : MessageSegment()

    /**
     * @param toolName 工具名(如 Skill / execute_shell_command),用于匹配图标
     * @param paramText 气泡内单行显示的参数文本(参数值截断)
     * @param paramFull 完整参数文本,点击弹窗展示并支持复制
     */
    data class ToolCall(
        val toolName: String,
        val paramText: String,
        val paramFull: String
    ) : MessageSegment()
}

data class ChatMessage(
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    // 本轮对话的调用量信息(仅内存态展示，不入库)，为 null 时不显示
    val usage: String? = null,
    // 聚合段落(历史加载):非空时按顺序渲染文本段与工具调用行,content 仅作纯文本兜底
    val segments: List<MessageSegment> = emptyList(),
    // 用户消息附带的图片:本地缓存文件路径(可渲染,发送时上传成功后有值/历史命中本地缓存时有值)
    val imageLocalPath: String? = null,
    // 用户消息附带图片的服务端路径(上传返回的 url 或历史 image_url),
    // 服务端 media 不提供 HTTP 访问,无本地缓存时仅作占位展示
    val imageServerPath: String? = null
)

data class ChatRequest(
    val input: List<MessageContent>,
    val session_id: String,
    val user_id: String,
    val channel: String = "console"
)

data class MessageContent(
    val role: String,
    // 内容片段:发送时为 TextContent(文本) / ImageContent(图片) 混排,由 Gson 按运行时类型序列化
    val content: List<Any>
)

// 图片内容片段:先经 /api/console/upload 上传,以服务端绝对路径引用(AgentScope ImageContent 协议,
// 见 docs/qwenpaw/08 upload.md)
data class ImageContent(
    val type: String = "image",
    val image_url: String,
    val status: String = "created"
)

// POST /api/console/upload 响应:url 为服务端存储绝对路径,<hash>_<原文件名> 命名
data class UploadResponse(
    val url: String?,
    val file_name: String?,
    val size: Long?
)

// 文本内容片段:请求构造时只填 text;SSE 流式事件中增量在 delta(text 可能为 null/空)
data class TextContent(
    val type: String = "text",
    val text: String?,
    // 流式增量片段(in_progress 事件携带,完成时为 null);历史接口中该字段是 boolean,故声明为 Any 仅在使用处取 String
    val delta: Any? = null
)

data class ChatResponse(
    val sequence_number: Int,
    val `object`: String,
    val status: String?,
    val output: List<AssistantMessage>?,
    // message 级事件(object=message)把 content 直接放在顶层,流式增量在其 delta 字段;
    // response 级事件(object=response)的内容在 output 里,顶层 content 为 null
    val content: List<TextContent>?,
    val error: ErrorInfo?,
    val session_id: String?,
    var type: String?,
    val usage: UsageInfo?
)

// 顶层 Bean，对应当前 JSON
data class TurnUsageResponse(
    var type: String?,
    val session_id: String?,
    val usage: TurnUsageInfo?,
    val context_usage: ContextUsageInfo?
)

// 对应 usage 字段
data class TurnUsageInfo(
    val provider_id: String?,
    val model_name: String?,
    val prompt_tokens: Int?,
    val completion_tokens: Int?,
    val total_tokens: Int?
)

// 对应 context_usage 字段
data class ContextUsageInfo(
    val total_messages: Int?,
    val compressed_summary_tokens: Int?,
    val messages_tokens: Int?,
    val estimated_tokens: Int?,
    val max_input_length: Int?,
    val context_usage_ratio: Double?,
    val latest_assistant_tokens: Int?
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

// ---------------- 模型列表(GET /api/models) ----------------

// 模型条目(对应 models / extra_models 中的元素)
data class ModelInfo(
    val id: String?,
    val name: String?,
    val supports_multimodal: Boolean?,
    val supports_image: Boolean?,
    val supports_video: Boolean?,
    val is_free: Boolean?,
    val max_tokens: Int?,
    val max_input_length: Int?
)

// 模型供应商(对应响应数组中的元素，extra_models 为已激活模型)
data class ModelProvider(
    val id: String?,
    val name: String?,
    val models: List<ModelInfo>?,
    val extra_models: List<ModelInfo>?
)

// 切换模型请求体(PUT /api/models/active)
data class SwitchModelRequest(
    val provider_id: String,
    val model: String,
    val scope: String = "agent",
    val agent_id: String
)