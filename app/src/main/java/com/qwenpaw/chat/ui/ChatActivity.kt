package com.qwenpaw.chat.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.qwenpaw.chat.R
import com.qwenpaw.chat.data.ChatRepository
import com.qwenpaw.chat.db.AppDatabase
import com.qwenpaw.chat.model.ChatMessage
import com.qwenpaw.chat.network.AuthService
import com.qwenpaw.chat.network.SSEEvent
import com.qwenpaw.chat.util.TokenManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch

class ChatActivity : AppCompatActivity() {

    private lateinit var repository: ChatRepository
    private lateinit var adapter: ChatAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var editTextMessage: EditText
    private lateinit var buttonSend: ImageButton
    private lateinit var progressBar: ProgressBar
    private lateinit var tokenManager: TokenManager
    private lateinit var authService: AuthService

    private var chatJob: Job? = null

    private val agentId = "default"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        tokenManager = TokenManager.getInstance(this)
        authService = AuthService()

        if (!tokenManager.hasCredentials()) {
            navigateToLogin()
            return
        }

        refreshToken()
    }

    private fun refreshToken() {
        val baseUrl = tokenManager.getBaseUrl()
        val username = tokenManager.getUsername()
        val password = tokenManager.getPassword()

        if (baseUrl == null || username == null || password == null) {
            navigateToLogin()
            return
        }

        lifecycleScope.launch {
            val result = authService.login(baseUrl, username, password)

            result.onSuccess { response ->
                tokenManager.saveToken(response.token!!)
                initChat(baseUrl, response.token!!)
            }

            result.onFailure {
                runOnUiThread {
                    Toast.makeText(this@ChatActivity, "Token刷新失败，请重新登录", Toast.LENGTH_SHORT).show()
                    navigateToLogin()
                }
            }
        }
    }

    private fun initChat(baseUrl: String, token: String) {
        val database = AppDatabase.getDatabase(applicationContext)
        repository = ChatRepository(baseUrl, agentId, token, database.messageDao())

        recyclerView = findViewById(R.id.recyclerViewMessages)
        editTextMessage = findViewById(R.id.editTextMessage)
        buttonSend = findViewById(R.id.buttonSend)
        progressBar = findViewById(R.id.progressBar)

        setupRecyclerView()
        setupClickListeners()
        loadMessages()
    }

    private fun setupRecyclerView() {
        adapter = ChatAdapter()
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
    }

    private fun setupClickListeners() {
        buttonSend.setOnClickListener {
            val message = editTextMessage.text.toString().trim()
            if (message.isNotEmpty()) {
                sendMessage(message)
            }
        }
    }

    private fun loadMessages() {
        lifecycleScope.launch {
            val messages = repository.getMessages()
            messages.forEach { message ->
                adapter.addMessage(message)
            }
            recyclerView.scrollToPosition(adapter.itemCount - 1)
        }
    }

    private fun sendMessage(message: String) {
        chatJob?.cancel()
        editTextMessage.text.clear()
        setLoading(true)

        adapter.addMessage(ChatMessage(role = "user", content = message))
        recyclerView.scrollToPosition(adapter.itemCount - 1)

        chatJob = lifecycleScope.launch {
            repository.sendMessage(message)
                .catch { e ->
                    showError("发送失败: ${e.message}")
                    setLoading(false)
                }
                .onCompletion {
                    setLoading(false)
                }
                .collect { event ->
                    when (event) {
                        is SSEEvent.Created -> {
                            adapter.addMessage(ChatMessage(role = "assistant", content = ""))
                            recyclerView.scrollToPosition(adapter.itemCount - 1)
                        }
                        is SSEEvent.InProgress -> {
                            if (event.text.isNotEmpty()) {
                                if (adapter.itemCount > 0) {
                                    adapter.updateLastMessage(event.text)
                                    recyclerView.scrollToPosition(adapter.itemCount - 1)
                                    repository.updateLastAssistantMessage(event.text)
                                }
                            }
                        }
                        is SSEEvent.Completed -> {
                            if (event.text.isNotEmpty()) {
                                adapter.updateLastMessage(event.text)
                                recyclerView.scrollToPosition(adapter.itemCount - 1)
                                repository.updateLastAssistantMessage(event.text)
                            }
                        }
                        is SSEEvent.Error -> {
                            showError(event.message)
                            adapter.updateLastMessage("错误: ${event.message}")
                            repository.updateLastAssistantMessage("错误: ${event.message}")
                        }
                        is SSEEvent.Unknown -> {
                            val text = event.response.output?.firstOrNull()
                                ?.content?.firstOrNull()?.text ?: ""
                            if (text.isNotEmpty()) {
                                adapter.updateLastMessage(text)
                                repository.updateLastAssistantMessage(text)
                            }
                        }
                    }
                }
        }
    }

    private fun setLoading(loading: Boolean) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        buttonSend.isEnabled = !loading
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun navigateToLogin() {
        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        chatJob?.cancel()
    }
}