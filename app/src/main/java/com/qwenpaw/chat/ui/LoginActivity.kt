package com.qwenpaw.chat.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.qwenpaw.chat.R
import com.qwenpaw.chat.network.AuthService
import com.qwenpaw.chat.util.TokenManager
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var editTextBaseUrl: EditText
    private lateinit var editTextUsername: EditText
    private lateinit var editTextPassword: EditText
    private lateinit var buttonLogin: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var textViewError: TextView
    private lateinit var tokenManager: TokenManager
    private lateinit var authService: AuthService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        tokenManager = TokenManager.getInstance(this)
        authService = AuthService()

        initViews()
        loadSavedBaseUrl()
        loadSavedAccount()
        setupListeners()


    }

    private fun initViews() {
        editTextBaseUrl = findViewById(R.id.editTextBaseUrl)
        editTextUsername = findViewById(R.id.editTextUsername)
        editTextPassword = findViewById(R.id.editTextPassword)
        buttonLogin = findViewById(R.id.buttonLogin)
        progressBar = findViewById(R.id.progressBar)
        textViewError = findViewById(R.id.textViewError)
    }

    private fun setupListeners() {
        buttonLogin.setOnClickListener {
            val baseUrl = editTextBaseUrl.text.toString().trim()
            val username = editTextUsername.text.toString().trim()
            val password = editTextPassword.text.toString().trim()

            if (baseUrl.isEmpty()) {
                editTextBaseUrl.error = "请输入服务器地址"
                return@setOnClickListener
            }
            if (username.isEmpty()) {
                editTextUsername.error = "请输入用户名"
                return@setOnClickListener
            }
            if (password.isEmpty()) {
                editTextPassword.error = "请输入密码"
                return@setOnClickListener
            }

            login(baseUrl, username, password)
        }
    }

    private fun loadSavedBaseUrl() {
        val savedBaseUrl = tokenManager.getBaseUrl()
        if (!savedBaseUrl.isNullOrEmpty()) {
            editTextBaseUrl.setText(savedBaseUrl)
        }
    }

    private fun loadSavedAccount(){
        val account = tokenManager.getUsername();
        val password = tokenManager.getPassword();
        if(account.isNotEmpty() && password.isNotEmpty()){
            editTextUsername.setText(account)
            editTextPassword.setText(password)
        }
    }

    private fun login(baseUrl: String, username: String, password: String) {
        setLoading(true)
        textViewError.visibility = View.GONE

        lifecycleScope.launch {
            val result = authService.login(baseUrl, username, password)

            result.onSuccess { response ->
                tokenManager.saveToken(response.token!!)
                tokenManager.saveCredentials(username, password, baseUrl)

                runOnUiThread {
                    Toast.makeText(this@LoginActivity, "登录成功", Toast.LENGTH_SHORT).show()
                    navigateToChat()
                }
            }

            result.onFailure { error ->
                runOnUiThread {
                    textViewError.text = error.message ?: "登录失败"
                    textViewError.visibility = View.VISIBLE
                    setLoading(false)
                }
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        buttonLogin.isEnabled = !loading
        editTextBaseUrl.isEnabled = !loading
        editTextUsername.isEnabled = !loading
        editTextPassword.isEnabled = !loading
    }

    private fun navigateToChat() {
        val intent = Intent(this, ChatActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}