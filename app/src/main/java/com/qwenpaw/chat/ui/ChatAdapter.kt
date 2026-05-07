package com.qwenpaw.chat.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.qwenpaw.chat.R
import com.qwenpaw.chat.model.ChatMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class ChatAdapter : RecyclerView.Adapter<ChatAdapter.MessageViewHolder>() {

    private val messages = mutableListOf<ChatMessage>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).apply {
        timeZone = TimeZone.getTimeZone("Asia/Shanghai")
    }

    fun addMessage(message: ChatMessage) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }

    fun updateLastMessage(content: String) {
        if (messages.isNotEmpty() && messages.last().role == "assistant") {
            val lastIndex = messages.lastIndex
            messages[lastIndex] = ChatMessage(
                role = "assistant",
                content = content,
                timestamp = messages[lastIndex].timestamp
            )
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
        holder.bind(message, dateFormat) { showContextMenu(it, message.content) }
    }

    override fun getItemCount(): Int = messages.size

    class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textViewMessage: TextView = itemView.findViewById(R.id.textViewMessage)
        private val textViewMessageTime: TextView = itemView.findViewById(R.id.textViewMessageTime)
        private val textViewAssistant: TextView = itemView.findViewById(R.id.textViewAssistant)
        private val textViewAssistantTime: TextView = itemView.findViewById(R.id.textViewAssistantTime)

        fun bind(message: ChatMessage, dateFormat: SimpleDateFormat, onLongClick: (View) -> Unit) {
            val timeText = dateFormat.format(Date(message.timestamp))

            when (message.role) {
                "user" -> {
                    textViewMessage.visibility = View.VISIBLE
                    textViewMessageTime.visibility = View.VISIBLE
                    textViewAssistant.visibility = View.GONE
                    textViewAssistantTime.visibility = View.GONE
                    textViewMessage.text = message.content
                    textViewMessageTime.text = timeText

                    textViewMessage.setOnLongClickListener { view ->
                        onLongClick(view)
                        true
                    }
                }
                "assistant" -> {
                    textViewMessage.visibility = View.GONE
                    textViewMessageTime.visibility = View.GONE
                    textViewAssistant.visibility = View.VISIBLE
                    textViewAssistantTime.visibility = View.VISIBLE
                    textViewAssistant.text = message.content
                    textViewAssistantTime.text = timeText

                    textViewAssistant.setOnLongClickListener { view ->
                        onLongClick(view)
                        true
                    }
                }
            }
        }

        private fun showContextMenu(anchorView: View, content: String) {
            val context = anchorView.context
            val popupMenu = PopupMenu(context, anchorView, Gravity.CENTER)

            popupMenu.menuInflater.inflate(R.menu.message_context_menu, popupMenu.menu)

            popupMenu.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.action_copy -> {
                        copyToClipboard(context, content)
                        true
                    }
                    else -> false
                }
            }

            popupMenu.show()
        }

        private fun copyToClipboard(context: Context, text: String) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("message", text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
        }
    }
}