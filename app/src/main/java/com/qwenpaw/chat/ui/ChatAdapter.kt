package com.qwenpaw.chat.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.qwenpaw.chat.R
import com.qwenpaw.chat.model.ChatMessage
import com.qwenpaw.chat.model.MessageSegment
import com.qwenpaw.chat.util.ImageDecode
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.syntax.SyntaxHighlightPlugin
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class ChatAdapter(
    private val context: Context
) : RecyclerView.Adapter<ChatAdapter.MessageViewHolder>() {

    private val messages = mutableListOf<ChatMessage>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).apply {
        timeZone = TimeZone.getTimeZone("Asia/Shanghai")
    }

    private val markwon = Markwon.builder(context)
        .usePlugin(TablePlugin.create(context))
        .usePlugin(StrikethroughPlugin.create())
        .usePlugin(TaskListPlugin.create(context))
//        .usePlugin(SyntaxHighlightPlugin.create(Prism4jThemeMonokaiExtend()))
        .build()

    fun addMessage(message: ChatMessage) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }

    fun updateLastMessage(content: String) {
        if (messages.isNotEmpty() && messages.last().role == "assistant") {
            val lastIndex = messages.lastIndex
            messages[lastIndex] = messages[lastIndex].copy(content = content)
            notifyItemChanged(lastIndex)
        }
    }

    fun updateLastMessageUsage(usage: String) {
        if (messages.isNotEmpty() && messages.last().role == "assistant") {
            val lastIndex = messages.lastIndex
            messages[lastIndex] = messages[lastIndex].copy(usage = usage)
            notifyItemChanged(lastIndex)
        }
    }

    fun getLastMessage(): ChatMessage? {
        return if (messages.isNotEmpty()) messages.last() else null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = messages[position]
        holder.bind(message, dateFormat, markwon)
    }

    override fun getItemCount(): Int = messages.size

    class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val userContainer: LinearLayout = itemView.findViewById(R.id.userContainer)
        private val imageViewUserImage: ImageView = itemView.findViewById(R.id.imageViewUserImage)
        private val textViewMessage: TextView = itemView.findViewById(R.id.textViewMessage)
        private val textViewMessageTime: TextView = itemView.findViewById(R.id.textViewMessageTime)
        private val assistantContainer: LinearLayout = itemView.findViewById(R.id.assistantContainer)
        private val textViewAssistant: TextView = itemView.findViewById(R.id.textViewAssistant)
        private val textViewAssistantTime: TextView = itemView.findViewById(R.id.textViewAssistantTime)
        private val textViewAssistantUsage: TextView = itemView.findViewById(R.id.textViewAssistantUsage)

        fun bind(message: ChatMessage, dateFormat: SimpleDateFormat, markwon: Markwon) {
            val timeText = dateFormat.format(Date(message.timestamp))
            when (message.role) {
                "user" -> {
                    userContainer.visibility = View.VISIBLE
                    assistantContainer.visibility = View.GONE
                    textViewAssistantTime.visibility = View.GONE
                    textViewAssistantUsage.visibility = View.GONE
                    bindUserImage(message)
                    if (message.content.isEmpty()) {
                        // 纯图片消息:不显示文本气泡
                        textViewMessage.visibility = View.GONE
                    } else {
                        textViewMessage.visibility = View.VISIBLE
                        textViewMessage.text = message.content
                    }
                    textViewMessageTime.text = timeText
                }
                "assistant" -> {
                    userContainer.visibility = View.GONE
                    textViewMessage.visibility = View.GONE
                    textViewMessageTime.visibility = View.GONE
                    assistantContainer.visibility = View.VISIBLE
                    textViewAssistantTime.visibility = View.VISIBLE
                    if (message.segments.isNotEmpty()) {
                        // 历史聚合消息:按原顺序渲染正文段与工具调用行
                        bindSegments(message.segments, markwon)
                    } else {
                        clearDynamicSegments()
                        textViewAssistant.visibility = View.VISIBLE
                        markwon.setMarkdown(textViewAssistant, message.content)
                    }
                    textViewAssistantTime.text = timeText
                    if (message.usage.isNullOrEmpty()) {
                        textViewAssistantUsage.visibility = View.GONE
                    } else {
                        textViewAssistantUsage.visibility = View.VISIBLE
                        textViewAssistantUsage.text = message.usage
                    }
                }
            }
        }

        /**
         * 用户消息图片:
         * - 本地缓存命中(发送时的文件/历史同名缓存) → 解码展示原图,圆角裁剪,点击查看大图
         * - 仅有服务端路径(服务端 media 不提供 HTTP 访问,本地无缓存) → 占位图,点击弹窗查看文件路径
         */
        private fun bindUserImage(message: ChatMessage) {
            // 图片四周圆角裁剪,避免直角超出气泡区域
            imageViewUserImage.background = ContextCompat.getDrawable(
                imageViewUserImage.context, R.drawable.bg_image_bubble
            )
            imageViewUserImage.clipToOutline = true
            val bitmap = message.imageLocalPath?.let { ImageDecode.decodeSampled(it, 720) }
            when {
                bitmap != null -> {
                    imageViewUserImage.visibility = View.VISIBLE
                    imageViewUserImage.setImageBitmap(bitmap)
                    // 文本气泡与图片之间的间距
                    (textViewMessage.layoutParams as? LinearLayout.LayoutParams)?.topMargin =
                        dp(imageViewUserImage.context, 6)
                    imageViewUserImage.setOnClickListener { view ->
                        showImageDialog(view.context, message)
                    }
                }
                message.imageServerPath != null -> {
                    imageViewUserImage.visibility = View.VISIBLE
                    imageViewUserImage.setImageResource(R.drawable.ic_image)
                    (textViewMessage.layoutParams as? LinearLayout.LayoutParams)?.topMargin =
                        dp(imageViewUserImage.context, 6)
                    imageViewUserImage.setOnClickListener { view ->
                        showTextDialog(view.context, "图片文件", message.imageServerPath!!)
                    }
                }
                else -> {
                    imageViewUserImage.visibility = View.GONE
                    imageViewUserImage.setImageBitmap(null)
                    (textViewMessage.layoutParams as? LinearLayout.LayoutParams)?.topMargin = 0
                    imageViewUserImage.setOnClickListener(null)
                }
            }
        }

        /** 点击图片气泡查看大图:全屏对话框,黑色背景,点击任意处关闭 */
        private fun showImageDialog(context: Context, message: ChatMessage) {
            val localPath = message.imageLocalPath ?: return
            val imageView = ImageView(context).apply {
                adjustViewBounds = true
                // 大图解码上限放大到 1280,兼顾清晰度与内存
                setImageBitmap(ImageDecode.decodeSampled(localPath, 1280))
                // 允许长按复制图片路径
                setOnLongClickListener {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText("image_path", message.imageServerPath ?: localPath)
                    )
                    Toast.makeText(context, "已复制图片路径", Toast.LENGTH_SHORT).show()
                    true
                }
            }
            AlertDialog.Builder(context)
                .setView(imageView)
                .setPositiveButton("关闭", null)
                .show()
        }

        /** 渲染聚合段落:正文段复用 AI 气泡样式,工具调用行与思考行显示图标与预览(点击弹窗看全文) */
        private fun bindSegments(segments: List<MessageSegment>, markwon: Markwon) {
            clearDynamicSegments()
            textViewAssistant.visibility = View.GONE
            val context = itemView.context
            segments.forEach { segment ->
                when (segment) {
                    is MessageSegment.Text -> {
                        val textView = TextView(context).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply { topMargin = dp(context, 6) }
                            setBackgroundResource(R.drawable.message_background_assistant)
                            setPadding(dp(context, 16), dp(context, 11), dp(context, 16), dp(context, 11))
                            setTextColor(
                                ContextCompat.getColor(context, R.color.assistant_message_text_color)
                            )
                            textSize = 15f
                            setTextIsSelectable(true)
                        }
                        markwon.setMarkdown(textView, segment.text)
                        assistantContainer.addView(textView)
                    }
                    is MessageSegment.Reasoning -> {
                        // 思考行:灯泡图标 + 斜体预览,点击弹窗查看思考全文
                        val row = LayoutInflater.from(context)
                            .inflate(R.layout.item_tool_call, assistantContainer, false)
                        (row.layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(context, 6)
                        row.findViewById<ImageView>(R.id.imageToolIcon)
                            .setImageResource(R.drawable.ic_reasoning)
                        row.findViewById<TextView>(R.id.textToolParams).apply {
                            text = preview(segment.text)
                            paint.textSkewX = -0.25f
                        }
                        row.setOnClickListener { showTextDialog(context, "思考过程", segment.text) }
                        assistantContainer.addView(row)
                    }
                    is MessageSegment.ToolCall -> {
                        val row = LayoutInflater.from(context)
                            .inflate(R.layout.item_tool_call, assistantContainer, false)
                        (row.layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(context, 6)
                        row.findViewById<ImageView>(R.id.imageToolIcon)
                            .setImageResource(toolIconRes(segment.toolName))
                        row.findViewById<TextView>(R.id.textToolParams).text = segment.paramText
                        row.setOnClickListener { showTextDialog(context, "工具详情", segment.paramText) }
                        assistantContainer.addView(row)
                    }
                }

            }

        }

        /** 长文本单行预览:换行替换为空格并截断到 50 字符,超长以 ... 结尾 */
        private fun preview(text: String, max: Int = 50): String {
            val flat = text.replace('\n', ' ')
            return if (flat.length <= max) flat else flat.take(max) + "..."
        }

        /** 点击段落行:弹窗显示完整文本,底部提供复制按钮 */
        private fun showTextDialog(context: Context, title: String, fullText: String) {
            val dialogView = LayoutInflater.from(context)
                .inflate(R.layout.dialog_tool_params, null)
            dialogView.findViewById<TextView>(R.id.textToolParamsFull).text = fullText
            AlertDialog.Builder(context)
                .setTitle(title)
                .setView(dialogView)
                .setPositiveButton("复制") { _, _ ->
                    val clipboard =
                        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText(title, fullText))
                    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("关闭", null)
                .show()
        }

        /** 清理上一次绑定追加的动态段落(保留布局中静态的 textViewAssistant) */
        private fun clearDynamicSegments() {
            for (i in assistantContainer.childCount - 1 downTo 0) {
                if (assistantContainer.getChildAt(i) !== textViewAssistant) {
                    assistantContainer.removeViewAt(i)
                }
            }
        }

        private fun dp(context: Context, value: Int): Int =
            (value * context.resources.displayMetrics.density + 0.5f).toInt()

        /** 工具名 → 图标(历史 plugin_call 段落) */
        private fun toolIconRes(toolName: String): Int = when (toolName.trim().lowercase()) {
            "skill" -> R.drawable.ic_tool_skill
            "execute_shell_command" -> R.drawable.ic_tool_terminal
            "write_file" -> R.drawable.ic_tool_write
            "read_file" -> R.drawable.ic_tool_read
            "edit_file" -> R.drawable.ic_tool_edit
            "get_current_time" -> R.drawable.ic_tool_time
            else -> R.drawable.ic_tool_generic
        }
    }
}