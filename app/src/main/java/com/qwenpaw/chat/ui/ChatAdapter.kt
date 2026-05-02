package com.qwenpaw.chat.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
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
        holder.bind(message, dateFormat)
    }

    override fun getItemCount(): Int = messages.size

    class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textViewMessage: TextView = itemView.findViewById(R.id.textViewMessage)
        private val textViewMessageTime: TextView = itemView.findViewById(R.id.textViewMessageTime)
        private val textViewAssistant: TextView = itemView.findViewById(R.id.textViewAssistant)
        private val textViewAssistantTime: TextView = itemView.findViewById(R.id.textViewAssistantTime)

        fun bind(message: ChatMessage, dateFormat: SimpleDateFormat) {
            val timeText = dateFormat.format(Date(message.timestamp))
            when (message.role) {
                "user" -> {
                    textViewMessage.visibility = View.VISIBLE
                    textViewMessageTime.visibility = View.VISIBLE
                    textViewAssistant.visibility = View.GONE
                    textViewAssistantTime.visibility = View.GONE
                    textViewMessage.text = message.content
                    textViewMessageTime.text = timeText
                }
                "assistant" -> {
                    textViewMessage.visibility = View.GONE
                    textViewMessageTime.visibility = View.GONE
                    textViewAssistant.visibility = View.VISIBLE
                    textViewAssistantTime.visibility = View.VISIBLE
                    textViewAssistant.text = message.content
                    textViewAssistantTime.text = timeText
                }
            }
        }
    }
}