package com.qwenpaw.chat.ui

import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.qwenpaw.chat.R
import com.qwenpaw.chat.model.Account
import com.qwenpaw.chat.network.AuthService
import com.qwenpaw.chat.util.TokenManager
import kotlinx.coroutines.launch

/**
 * 登录界面
 *
 * 职责:
 * 1. 显示已保存的账号列表,支持快速选择/删除
 * 2. 支持新增账号(地址 + 用户名 + 密码)
 * 3. 支持编辑账号别名
 * 4. 已存在当前账号时,进入界面尝试自动登录
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var editTextUsername: EditText
    private lateinit var editTextPassword: EditText
    private lateinit var buttonLogin: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var textViewError: TextView
    private lateinit var textViewBaseUrl: TextView
    private lateinit var textViewCurrentAccount: TextView
    private lateinit var textViewCurrentAccountLabel: TextView
    private lateinit var layoutAliasRow: LinearLayout
    private lateinit var textViewAlias: TextView
    private lateinit var buttonEditAlias: ImageView
    private lateinit var containerAccounts: LinearLayout
    private lateinit var textViewEmptyAccounts: TextView
    private lateinit var buttonAddAccount: TextView

    private lateinit var tokenManager: TokenManager
    private lateinit var authService: AuthService

    // 当前在登录表单中操作的账号(若为 null 表示新增)
    private var editingAccountId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        tokenManager = TokenManager.getInstance(this)
        authService = AuthService()

        initViews()
        setupListeners()
        loadInitialState()
        tryAutoLogin()
    }

    override fun onResume() {
        super.onResume()
        renderAccountList()
    }

    private fun initViews() {
        editTextUsername = findViewById(R.id.editTextUsername)
        editTextPassword = findViewById(R.id.editTextPassword)
        buttonLogin = findViewById(R.id.buttonLogin)
        progressBar = findViewById(R.id.progressBar)
        textViewError = findViewById(R.id.textViewError)
        textViewBaseUrl = findViewById(R.id.textViewBaseUrl)
        textViewCurrentAccount = findViewById(R.id.textViewCurrentAccount)
        textViewCurrentAccountLabel = findViewById(R.id.textViewCurrentAccountLabel)
        layoutAliasRow = findViewById(R.id.layoutAliasRow)
        textViewAlias = findViewById(R.id.textViewAlias)
        buttonEditAlias = findViewById(R.id.buttonEditAlias)
        containerAccounts = findViewById(R.id.containerAccounts)
        textViewEmptyAccounts = findViewById(R.id.textViewEmptyAccounts)
        buttonAddAccount = findViewById(R.id.buttonAddAccount)
    }

    private fun setupListeners() {
        buttonLogin.setOnClickListener {
            val username = editTextUsername.text.toString().trim()
            val password = editTextPassword.text.toString().trim()

            if (username.isEmpty()) {
                editTextUsername.error = "请输入用户名"
                return@setOnClickListener
            }
            if (password.isEmpty()) {
                editTextPassword.error = "请输入密码"
                return@setOnClickListener
            }
            login(username, password)
        }

        textViewBaseUrl.setOnClickListener {
            showEditUrlDialog()
        }

        buttonEditAlias.setOnClickListener {
            val current = currentEditingAccount() ?: return@setOnClickListener
            showEditAliasDialog(current)
        }

        buttonAddAccount.setOnClickListener {
            switchToNewAccountForm()
        }
    }

    private fun loadInitialState() {
        renderAccountList()
        // 默认展示当前账号(若存在)的信息
        val current = tokenManager.getCurrentAccount()
        if (current != null) {
            editingAccountId = current.id
            renderCurrentAccount(current)
        } else {
            editingAccountId = null
            renderCurrentAccount(null)
            // 没有当前账号时,如果有草稿 URL,展示出来
            val draft = tokenManager.getDraftBaseUrl()
            if (!draft.isNullOrEmpty()) {
                textViewBaseUrl.text = "托管地址: $draft"
            }
        }
    }

    private fun tryAutoLogin() {
        val current = tokenManager.getCurrentAccount() ?: return
        if (current.baseUrl.isEmpty() ||
            current.username.isEmpty() ||
            current.password.isEmpty()
        ) return
        // 把表单预填好,并自动登录
        editTextUsername.setText(current.username)
        editTextPassword.setText(current.password)
        autoLogin(current.baseUrl, current.username, current.password, current.id)
    }

    // ---------------- 账号列表渲染 ----------------

    private fun renderAccountList() {
        containerAccounts.removeAllViews()
        val accounts = tokenManager.getAccounts()
        if (accounts.isEmpty()) {
            textViewEmptyAccounts.visibility = View.VISIBLE
            return
        }
        textViewEmptyAccounts.visibility = View.GONE
        val currentId = tokenManager.getCurrentAccount()?.id

        accounts.forEach { account ->
            val itemView = LayoutInflater.from(this)
                .inflate(R.layout.item_account, containerAccounts, false)
            itemView.findViewById<TextView>(R.id.textViewAccountAlias).text =
                if (account.alias.isNotBlank()) account.alias else account.username
            itemView.findViewById<TextView>(R.id.textViewAccountUsername).text =
                "@${account.username}"
            itemView.findViewById<TextView>(R.id.textViewAccountBaseUrl).text = account.baseUrl

            val isCurrent = account.id == currentId
            itemView.alpha = if (isCurrent) 0.5f else 1.0f

            itemView.setOnClickListener { onAccountItemClicked(account) }
            itemView.findViewById<ImageView>(R.id.imageViewDelete)
                .setOnClickListener { confirmDeleteAccount(account) }

            containerAccounts.addView(itemView)
        }
    }

    private fun onAccountItemClicked(account: Account) {
        // 切换到该账号的登录信息(不自动登录,让用户点登录按钮)
        editingAccountId = account.id
        tokenManager.setCurrentAccountId(account.id)
        // 切到已有账号时,草稿 URL 不再需要
        tokenManager.clearDraftBaseUrl()
        renderCurrentAccount(account)
        editTextUsername.setText(account.username)
        editTextPassword.setText(account.password)
        textViewBaseUrl.text = "托管地址: ${account.baseUrl}"
        renderAccountList()
    }

    private fun switchToNewAccountForm() {
        editingAccountId = null
        tokenManager.clearCurrentAccount()
        renderCurrentAccount(null)
        editTextUsername.setText("")
        editTextPassword.setText("")
        // 保留草稿 URL(用户可能正在录入新账号但 URL 已经填过了)
        val draft = tokenManager.getDraftBaseUrl()
        textViewBaseUrl.text = if (!draft.isNullOrEmpty())
            "托管地址: $draft"
        else
            "托管地址"
        renderAccountList()
    }

    private fun confirmDeleteAccount(account: Account) {
        AlertDialog.Builder(this)
            .setTitle("删除账号")
            .setMessage("确认删除账号「${account.displayName()}」吗?该账号的聊天记录也会一并清除。")
            .setPositiveButton("删除") { _: DialogInterface, _: Int ->
                val database = com.qwenpaw.chat.db.AppDatabase.getDatabase(applicationContext)
                lifecycleScope.launch {
                    database.messageDao().deleteAllMessagesByAccount(account.id)
                    tokenManager.removeAccount(account.id)
                    if (tokenManager.getCurrentAccount() == null) {
                        editingAccountId = null
                        renderCurrentAccount(null)
                    }
                    renderAccountList()
                    Toast.makeText(this@LoginActivity, "账号已删除", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun renderCurrentAccount(account: Account?) {
        if (account == null) {
            textViewCurrentAccountLabel.visibility = View.GONE
            textViewCurrentAccount.visibility = View.GONE
            layoutAliasRow.visibility = View.GONE
            return
        }
        textViewCurrentAccountLabel.visibility = View.VISIBLE
        textViewCurrentAccount.visibility = View.VISIBLE
        textViewCurrentAccount.text = "${account.displayName()} (@${account.username})"
        layoutAliasRow.visibility = View.VISIBLE
        textViewAlias.text = if (account.alias.isNotBlank())
            "别名:${account.alias}"
        else
            "别名:未设置(点击右侧编辑)"
        textViewBaseUrl.text = "托管地址: ${account.baseUrl}"
    }

    private fun currentEditingAccount(): Account? = editingAccountId?.let { tokenManager.getAccountById(it) }

    // ---------------- 登录流程 ----------------

    private fun autoLogin(baseUrl: String, username: String, password: String, accountId: String) {
        setLoading(true)
        textViewError.visibility = View.GONE
        lifecycleScope.launch {
            val result = authService.login(baseUrl, username, password)
            result.onSuccess { response ->
                val account = tokenManager.upsertAccountOnLogin(
                    baseUrl = baseUrl,
                    username = username,
                    password = password,
                    token = response.token.orEmpty(),
                    existingAccountId = accountId
                )
                // 首次登录的账号分配环境标识色(已有颜色则跳过)
                tokenManager.ensureAccountColor(account.id)
                navigateToSessionList()
            }
            result.onFailure {
                runOnUiThread {
                    setLoading(false)
                    textViewError.text = "自动登录失败:${it.message}"
                    textViewError.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun login(username: String, password: String) {
        val baseUrl = tokenManager.getBaseUrl()
        if (baseUrl.isNullOrEmpty()) {
            textViewError.text = "请先设置托管地址"
            textViewError.visibility = View.VISIBLE
            return
        }
        setLoading(true)
        textViewError.visibility = View.GONE

        lifecycleScope.launch {
            val result = authService.login(baseUrl, username, password)
            result.onSuccess { response ->
                val account = tokenManager.upsertAccountOnLogin(
                    baseUrl = baseUrl,
                    username = username,
                    password = password,
                    token = response.token.orEmpty(),
                    existingAccountId = editingAccountId
                )
                // 首次登录的账号分配环境标识色(已有颜色则跳过)
                tokenManager.ensureAccountColor(account.id)
                // 登录成功,草稿 URL 使命完成
                tokenManager.clearDraftBaseUrl()
                runOnUiThread {
                    Toast.makeText(this@LoginActivity, "登录成功", Toast.LENGTH_SHORT).show()
                    navigateToSessionList()
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

    private fun showEditUrlDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_url, null)
        val editTextUrl = dialogView.findViewById<EditText>(R.id.editTextDialogBaseUrl)

        // 优先展示当前编辑账号的地址,否则展示当前激活账号的地址
        val currentUrl = currentEditingAccount()?.baseUrl
            ?: tokenManager.getBaseUrl()
        if (!currentUrl.isNullOrEmpty()) {
            editTextUrl.setText(currentUrl)
        }

        AlertDialog.Builder(this)
            .setTitle("设置托管地址")
            .setView(dialogView)
            .setPositiveButton("保存") { _: DialogInterface, _: Int ->
                val newUrl = editTextUrl.text.toString().trim()
                if (newUrl.isNotEmpty()) {
                    val account = currentEditingAccount()
                    if (account != null) {
                        account.baseUrl = newUrl
                        tokenManager.saveAccount(account)
                        textViewBaseUrl.text = "托管地址: $newUrl"
                    } else {
                        // 没有具体账号上下文,只更新当前账号(若存在)的地址
                        tokenManager.saveBaseUrl(newUrl)
                        textViewBaseUrl.text = "托管地址: $newUrl"
                    }
                    Toast.makeText(this, "托管地址已更新", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showEditAliasDialog(account: Account) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_alias, null)
        val editTextAlias = dialogView.findViewById<EditText>(R.id.editTextDialogAlias)
        editTextAlias.setText(account.alias)
        editTextAlias.setSelection(editTextAlias.text.length)

        AlertDialog.Builder(this)
            .setTitle("编辑账号别名")
            .setView(dialogView)
            .setPositiveButton("保存") { _: DialogInterface, _: Int ->
                val newAlias = editTextAlias.text.toString().trim()
                tokenManager.updateAlias(account.id, newAlias)
                val updated = tokenManager.getAccountById(account.id)
                if (updated != null) {
                    renderCurrentAccount(updated)
                }
                renderAccountList()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun setLoading(loading: Boolean) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        buttonLogin.isEnabled = !loading
        editTextUsername.isEnabled = !loading
        editTextPassword.isEnabled = !loading
        textViewBaseUrl.isEnabled = !loading
    }

    private fun navigateToSessionList() {
        val intent = Intent(this, SessionListActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}
