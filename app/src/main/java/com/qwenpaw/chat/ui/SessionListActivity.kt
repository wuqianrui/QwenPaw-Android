package com.qwenpaw.chat.ui

import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.qwenpaw.chat.R
import com.qwenpaw.chat.model.Account
import com.qwenpaw.chat.model.AgentInfo
import com.qwenpaw.chat.model.ChatSession
import com.qwenpaw.chat.network.AgentService
import com.qwenpaw.chat.network.AuthService
import com.qwenpaw.chat.util.TokenManager
import kotlinx.coroutines.launch

/**
 * 会话列表主页(登录后进入)
 *
 * - Toolbar 显示当前 agent 名字,点击弹出 agent 切换列表(GET /api/agents)
 * - 列表展示当前 agent 的所有会话(GET /api/chats),点击进入对话页
 * - 会话条目左滑露出「删除」菜单,确认后删除会话(DELETE /api/chats/{chatId})
 * - FAB 新建对话
 * - 侧边抽屉:账号信息/切换账号/新增账号/切换模型/退出账号
 */
class SessionListActivity : AppCompatActivity() {

    private lateinit var toolbar: Toolbar
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var agentSelector: View
    private lateinit var textViewAgentName: TextView
    private lateinit var textViewToolbarSubtitle: TextView
    private lateinit var recyclerViewSessions: RecyclerView
    private lateinit var textViewEmptySessions: TextView
    private lateinit var progressBarSessions: ProgressBar
    private lateinit var buttonNewChat: ImageButton
    private lateinit var sessionAdapter: SessionAdapter

    // 抽屉中的视图
    private lateinit var textViewDrawerAlias: TextView
    private lateinit var textViewDrawerUsername: TextView
    private lateinit var textViewDrawerBaseUrl: TextView
    private lateinit var buttonDrawerEditAlias: ImageView
    private lateinit var containerDrawerAccounts: LinearLayout
    private lateinit var textViewDrawerEmptyAccounts: TextView
    private lateinit var menuAddAccount: LinearLayout
    private lateinit var menuSwitchModel: LinearLayout
    private lateinit var menuLogout: LinearLayout

    private lateinit var tokenManager: TokenManager
    private lateinit var authService: AuthService

    private var currentAccountId: String? = null

    // 当前环境的 agents 与选中的 agent
    private var agents: List<AgentInfo> = emptyList()
    private var currentAgent: AgentInfo? = null

    // 标记首次加载流程是否完成(onResume 时据此决定是否自动刷新列表)
    private var initialized = false

    // 当前已渲染的会话列表,用于在标题修改回传时进行就地更新
    private var currentSessions: List<ChatSession> = emptyList()

    /**
     * 进入对话页的 launcher:用于接收 ChatActivity 回传的结果,
     * 当会话标题被修改后刷新当前会话列表。
     */
    private val chatLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data ?: return@registerForActivityResult
            val changedChatId = data.getStringExtra(ChatActivity.EXTRA_RESULT_CHAT_ID)
            val newName = data.getStringExtra(ChatActivity.EXTRA_RESULT_NEW_NAME)
            if (!changedChatId.isNullOrEmpty() && !newName.isNullOrEmpty()) {
                onSessionRenamed(changedChatId, newName)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_session_list)

        tokenManager = TokenManager.getInstance(this)
        authService = AuthService()

        if (!tokenManager.hasCredentials()) {
            navigateToLogin()
            return
        }

        initViews()
        setupToolbar()
        setupDrawerMenu()
        setupSessionList()
        refreshTokenAndEnter()
    }

    override fun onResume() {
        super.onResume()
        // 抽屉中的账号列表与头部信息需要随账号变化更新
        renderDrawerAccountInfo()
        renderDrawerAccountList()
        // 从对话页返回时刷新会话列表(首次进入由 refreshTokenAndEnter 驱动)
        val agent = currentAgent
        if (initialized && agent != null) {
            loadChats(agent.id)
        }
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbar)
        drawerLayout = findViewById(R.id.drawerLayout)
        agentSelector = findViewById(R.id.agentSelector)
        textViewAgentName = findViewById(R.id.textViewAgentName)
        textViewToolbarSubtitle = findViewById(R.id.textViewToolbarSubtitle)
        recyclerViewSessions = findViewById(R.id.recyclerViewSessions)
        textViewEmptySessions = findViewById(R.id.textViewEmptySessions)
        progressBarSessions = findViewById(R.id.progressBarSessions)
        buttonNewChat = findViewById(R.id.buttonNewChat)

        textViewDrawerAlias = findViewById(R.id.textViewDrawerAlias)
        textViewDrawerUsername = findViewById(R.id.textViewDrawerUsername)
        textViewDrawerBaseUrl = findViewById(R.id.textViewDrawerBaseUrl)
        buttonDrawerEditAlias = findViewById(R.id.buttonDrawerEditAlias)
        containerDrawerAccounts = findViewById(R.id.containerDrawerAccounts)
        textViewDrawerEmptyAccounts = findViewById(R.id.textViewDrawerEmptyAccounts)
        menuAddAccount = findViewById(R.id.menuAddAccount)
        menuSwitchModel = findViewById(R.id.menuSwitchModel)
        menuLogout = findViewById(R.id.menuLogout)
    }

    private fun setupToolbar() {
        toolbar.setNavigationOnClickListener { drawerLayout.openDrawer(GravityCompat.START) }
        agentSelector.setOnClickListener { showSwitchAgentDialog() }
    }

    private fun setupDrawerMenu() {
        menuLogout.setOnClickListener { logoutCurrentAccount() }
        menuAddAccount.setOnClickListener { goAddAccount() }
        menuSwitchModel.setOnClickListener { showSwitchModelDialog() }
        buttonDrawerEditAlias.setOnClickListener {
            val current = tokenManager.getCurrentAccount() ?: return@setOnClickListener
            showEditAliasDialog(current)
        }
    }

    private fun setupSessionList() {
        sessionAdapter = SessionAdapter(
            onSessionClick = { session -> onSessionClicked(session) },
            onSessionDelete = { session -> confirmDeleteSession(session) }
        )
        recyclerViewSessions.layoutManager = LinearLayoutManager(this)
        recyclerViewSessions.adapter = sessionAdapter
        // 列表滚动时收起已展开的左滑菜单
        recyclerViewSessions.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    sessionAdapter.closeOpenMenu()
                }
            }
        })
        buttonNewChat.setOnClickListener { startNewChat() }
    }

    // ---------------- Agent 加载与切换 ----------------

    /**
     * 加载环境下的所有 agents,并确定当前 agent:
     * 优先恢复该账号上一次使用的 agent;没有记忆或已不存在时,默认取接口第一个。
     */
    private fun loadAgents() {
        val account = currentAccountId ?: return
        val baseUrl = tokenManager.getBaseUrl() ?: return
        val service = AgentService(baseUrl = baseUrl, authToken = tokenManager.getToken())

        setLoading(true)
        lifecycleScope.launch {
            val result = service.getAgents()
            result.onSuccess { list ->
                agents = list
                if (list.isEmpty()) {
                    setLoading(false)
                    showError("当前环境没有可用的 agent")
                    return@onSuccess
                }
                val savedId = tokenManager.getCurrentAgentId(account)
                val agent = list.firstOrNull { it.id == savedId } ?: list.first()
                if (agent.id != savedId) {
                    tokenManager.setCurrentAgentId(account, agent.id)
                }
                currentAgent = agent
                renderAgentTitle(agent)
                loadChats(agent.id)
            }
            result.onFailure { e ->
                setLoading(false)
                showError("加载 agent 失败: ${e.message}")
            }
        }
    }

    /** Agent 切换弹窗,选中后记忆并重新加载会话列表 */
    private fun showSwitchAgentDialog() {
        if (agents.isEmpty()) {
            Toast.makeText(this, "Agent 列表尚未加载", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = agents.map { agent ->
            val model = agent.active_model?.model
            if (!model.isNullOrEmpty()) "${agent.name ?: agent.id} · $model"
            else (agent.name ?: agent.id)
        }.toTypedArray()
        val checked = agents.indexOfFirst { it.id == currentAgent?.id }

        AlertDialog.Builder(this)
            .setTitle("切换 Agent")
            .setSingleChoiceItems(labels, checked) { dialog: DialogInterface, which: Int ->
                dialog.dismiss()
                val agent = agents[which]
                if (agent.id == currentAgent?.id) return@setSingleChoiceItems
                currentAgent = agent
                currentAccountId?.let { tokenManager.setCurrentAgentId(it, agent.id) }
                renderAgentTitle(agent)
                loadChats(agent.id)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun renderAgentTitle(agent: AgentInfo) {
        textViewAgentName.text = agent.name?.takeIf { it.isNotBlank() } ?: agent.id
    }

    // ---------------- 会话列表 ----------------

    private fun loadChats(agentId: String) {
        val baseUrl = tokenManager.getBaseUrl() ?: return
        val service = AgentService(baseUrl = baseUrl, authToken = tokenManager.getToken())

        setLoading(true)
        textViewEmptySessions.visibility = View.GONE
        lifecycleScope.launch {
            val result = service.getChats(agentId)
            result.onSuccess { sessions ->
                setLoading(false)
                // 置顶优先,其余按更新时间倒序
                val sorted = sessions.sortedWith(
                    compareByDescending<ChatSession> { it.pinned == true }
                        .thenByDescending { it.updated_at ?: it.created_at ?: "" }
                )
                renderSessions(sorted)
            }
            result.onFailure { e ->
                setLoading(false)
                showError("加载会话列表失败: ${e.message}")
            }
        }
    }

    private fun renderSessions(sessions: List<ChatSession>) {
        currentSessions = sessions
        sessionAdapter.submitList(sessions)
        textViewEmptySessions.visibility = if (sessions.isEmpty()) View.VISIBLE else View.GONE
        initialized = true
    }

    private fun onSessionClicked(session: ChatSession) {
        val agent = currentAgent ?: return
        val intent = Intent(this, ChatActivity::class.java).apply {
            putExtra("extra_agent_id", agent.id)
            putExtra("extra_chat_id", session.id)
            putExtra("extra_session_id", session.session_id)
            putExtra("extra_session_name", session.name)
        }
        chatLauncher.launch(intent)
    }

    private fun startNewChat() {
        val agent = currentAgent ?: return
        val intent = Intent(this, ChatActivity::class.java).apply {
            putExtra("extra_agent_id", agent.id)
        }
        chatLauncher.launch(intent)
    }

    /**
     * 收到 ChatActivity 的回传:在当前已加载的列表里就地更新该会话的标题,
     * 避免重新走一次接口。失败时回退为重新拉取。
     */
    private fun onSessionRenamed(chatId: String, newName: String) {
        val updated = currentSessions.map { s ->
            if (s.id == chatId) s.copy(name = newName) else s
        }
        if (updated.any { it.id == chatId }) {
            val sorted = updated.sortedWith(
                compareByDescending<ChatSession> { it.pinned == true }
                    .thenByDescending { it.updated_at ?: it.created_at ?: "" }
            )
            renderSessions(sorted)
        } else {
            currentAgent?.id?.let { loadChats(it) }
        }
    }

    // ---------------- 删除会话 ----------------

    /** 左滑菜单的「删除」→ 二次确认;弹窗无论确认、取消还是直接消失,条目都收起恢复原样 */
    private fun confirmDeleteSession(session: ChatSession) {
        val name = session.name?.takeIf { it.isNotBlank() } ?: "未命名对话"
        AlertDialog.Builder(this)
            .setTitle("删除会话")
            .setMessage("确定删除「$name」?删除后该会话将不在列表中展示。")
            .setPositiveButton("删除") { _: DialogInterface, _: Int -> deleteSession(session) }
            .setNegativeButton("取消", null)
            .setOnDismissListener { _ -> sessionAdapter.closeOpenMenu() }
            .show()
    }

    /** 调用 DELETE /api/chats/{chatId},成功后从当前列表移除该会话 */
    private fun deleteSession(session: ChatSession) {
        val agent = currentAgent ?: return
        val baseUrl = tokenManager.getBaseUrl() ?: return
        val service = AgentService(baseUrl = baseUrl, authToken = tokenManager.getToken())

        lifecycleScope.launch {
            val result = service.deleteChat(agent.id, session.id)
            result.onSuccess {
                Toast.makeText(this@SessionListActivity, "已删除", Toast.LENGTH_SHORT).show()
                renderSessions(currentSessions.filterNot { it.id == session.id })
            }
            result.onFailure { e ->
                showError("删除会话失败: ${e.message}")
            }
        }
    }

    // ---------------- 切换模型(scope=当前 agent) ----------------

    /** 切换模型弹窗:仅展示各供应商的已激活模型,实现见 [ModelSwitchDialog] */
    private fun showSwitchModelDialog() {
        ModelSwitchDialog.show(this, currentAgent?.id ?: "default")
    }

    // ---------------- 抽屉渲染与账号操作 ----------------

    private fun renderDrawerAccountInfo() {
        val current = tokenManager.getCurrentAccount()
        if (current == null) {
            textViewDrawerAlias.text = "未登录"
            textViewDrawerUsername.text = ""
            textViewDrawerBaseUrl.text = ""
            return
        }
        textViewDrawerAlias.text = current.displayName()
        textViewDrawerUsername.text = "@${current.username}"
        textViewDrawerBaseUrl.text = current.baseUrl
    }

    private fun renderDrawerAccountList() {
        containerDrawerAccounts.removeAllViews()
        val current = tokenManager.getCurrentAccount()
        val accounts = tokenManager.getAccounts()
            .filter { it.id != current?.id }
        if (accounts.isEmpty()) {
            textViewDrawerEmptyAccounts.visibility = View.VISIBLE
            return
        }
        textViewDrawerEmptyAccounts.visibility = View.GONE
        accounts.forEach { account ->
            val itemView = LayoutInflater.from(this)
                .inflate(android.R.layout.simple_list_item_2, containerDrawerAccounts, false)
            val text1 = itemView.findViewById<TextView>(android.R.id.text1)
            val text2 = itemView.findViewById<TextView>(android.R.id.text2)
            text1.text = account.displayName()
            text2.text = "${account.username} · ${account.baseUrl}"
            itemView.setOnClickListener { switchToAccount(account) }
            containerDrawerAccounts.addView(itemView)
        }
    }

    private fun switchToAccount(account: Account) {
        drawerLayout.closeDrawer(GravityCompat.START)
        tokenManager.setCurrentAccountId(account.id)
        currentAccountId = account.id
        // 切换环境后 agent/会话列表全部作废,重新走加载流程
        agents = emptyList()
        currentAgent = null
        initialized = false

        updateToolbarBadge(account)
        renderDrawerAccountInfo()
        renderDrawerAccountList()
        refreshTokenAndEnter()
    }

    private fun goAddAccount() {
        drawerLayout.closeDrawer(GravityCompat.START)
        tokenManager.clearCurrentAccount()
        navigateToLogin()
    }

    private fun logoutCurrentAccount() {
        val current = tokenManager.getCurrentAccount()
        if (current == null) {
            navigateToLogin()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("退出当前账号")
            .setMessage("退出后,「${current.displayName()}」的账号信息仍会保留,你可以稍后重新登录。")
            .setPositiveButton("退出") { _: DialogInterface, _: Int ->
                tokenManager.clearCurrentAccount()
                navigateToLogin()
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
                    renderDrawerAccountInfo()
                    updateToolbarBadge(updated)
                }
                renderDrawerAccountList()
                Toast.makeText(this, "别名已更新", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------------- 登录态 & 环境徽标 ----------------

    private fun refreshTokenAndEnter() {
        val baseUrl = tokenManager.getBaseUrl()
        val username = tokenManager.getUsername()
        val password = tokenManager.getPassword()
        val current = tokenManager.getCurrentAccount()

        if (baseUrl == null || username == null || password == null || current == null) {
            navigateToLogin()
            return
        }
        currentAccountId = current.id
        updateToolbarBadge(current)

        lifecycleScope.launch {
            val result = authService.login(baseUrl, username, password)
            result.onSuccess { response ->
                tokenManager.updateToken(current.id, response.token.orEmpty())
                loadAgents()
            }
            result.onFailure {
                Toast.makeText(this@SessionListActivity, "Token刷新失败,请重新登录", Toast.LENGTH_SHORT).show()
                navigateToLogin()
            }
        }
    }

    /**
     * 更新工具栏右侧的环境徽标。
     *
     * 徽标为小圆角矩形,背景取当前账号分配的环境标识色(未分配时回退主色),
     * 白色 2dp 描边,内容为别名(或用户名)的首字,用于快速区分当前所处环境。
     */
    private fun updateToolbarBadge(account: Account) {
        val density = resources.displayMetrics.density
        val badgeColor = ContextCompat.getColor(
            this,
            account.accountColor()?.colorRes ?: R.color.primary_color
        )
        textViewToolbarSubtitle.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(badgeColor)
            setStroke((2 * density).toInt(), Color.WHITE)
            cornerRadius = 4 * density
        }
        textViewToolbarSubtitle.text = account.displayName().take(1).uppercase()
    }

    // ---------------- 通用 ----------------

    private fun setLoading(loading: Boolean) {
        progressBarSessions.visibility = if (loading) View.VISIBLE else View.GONE
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
}
