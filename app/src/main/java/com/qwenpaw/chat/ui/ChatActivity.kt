package com.qwenpaw.chat.ui

import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.Toolbar
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.qwenpaw.chat.R
import com.qwenpaw.chat.data.ChatRepository
import com.qwenpaw.chat.model.ChatMessage
import com.qwenpaw.chat.model.HistoryMessage
import com.qwenpaw.chat.model.MessageSegment
import com.qwenpaw.chat.model.TurnUsageResponse
import com.qwenpaw.chat.network.AgentService
import com.qwenpaw.chat.network.AuthService
import com.qwenpaw.chat.network.ModelService
import com.qwenpaw.chat.network.SSEEvent
import com.qwenpaw.chat.network.UploadService
import com.qwenpaw.chat.util.ImageDecode
import com.qwenpaw.chat.util.LocalMediaCache
import com.qwenpaw.chat.util.ModelIcons
import com.qwenpaw.chat.util.TokenManager
import com.qwenpaw.chat.widget.AIFaceBackdrop
import com.qwenpaw.chat.widget.AIFaceView
import com.qwenpaw.chat.widget.BotEmotion
import com.slientwhale.library.core.concurrent._delay
import com.slientwhale.library.core.kotlin.toast
import com.slientwhale.library.core.utils.TimeFormatUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 对话页面(从会话列表进入)
 *
 * - 通过 intent 参数携带 agentId / chatId / sessionId,历史记录直接经
 *   GET /api/chats/{chatId} 从服务端加载,不再读取本地数据库
 * - Toolbar 显示会话名,左侧返回会话列表,右侧环境徽标
 * - 历史中的 plugin_call 渲染为工具调用行;reasoning 渲染为思考行;
 *   plugin_call_output(工具执行结果)不展示
 */
class ChatActivity : AppCompatActivity() {

    private lateinit var repository: ChatRepository
    private lateinit var agentService: AgentService
    private lateinit var uploadService: UploadService
    // 模型列表服务:查询当前激活模型的 supports_image
    private var modelService: ModelService? = null
    private lateinit var adapter: ChatAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var editTextMessage: AppCompatEditText
    private lateinit var buttonSend: ImageButton
    private lateinit var buttonAttach: ImageButton
    private lateinit var attachmentPreviewContainer: FrameLayout
    private lateinit var imageViewAttachmentPreview: ImageView
    private lateinit var buttonRemoveAttachment: ImageButton
    private lateinit var progressBar: ProgressBar
    private lateinit var toolbar: Toolbar
    private lateinit var imageViewToolbarModel: ImageView
    // 多媒体模型眼睛角标(支持图片输入时显示)
    private lateinit var imageViewEyeBadge: ImageView

    private lateinit var aiFaceView: AIFaceView

    private lateinit var tokenManager: TokenManager
    private lateinit var authService: AuthService

    private var chatJob: Job? = null

    // ---------------- 图片附件(Photo Picker → 上传 → 随消息发送) ----------------

    // 系统相册选图:设备不支持 Photo Picker 时自动回退到系统文件选择
    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { onImagePicked(it) } }

    // 待发送图片:本地缓存文件(气泡渲染用);上传完成前 pendingImageUrl 为 null
    private var pendingImageFile: File? = null
    // 上传返回的服务端路径(image_url,随对话请求发送)
    private var pendingImageUrl: String? = null
    private var imageUploading = false
    private var uploadJob: Job? = null

    // 编辑状态 决定是否显示发送按钮或者AI表情
    private var isEditState = MutableLiveData(false)
    // 决定表情的舞台背景
    private var isChatState = MutableLiveData(false)
    // AI emotion
    private var botState = MutableLiveData(BotEmotion.WAKING)


    // intent 参数:当前 agent 与会话
    private val agentId: String by lazy {
        intent.getStringExtra(EXTRA_AGENT_ID) ?: "default"
    }
    // 当前标题(可变):首次进入取 intent 传入的值,重命名后更新
    private var sessionName: String = ""
    private val initialSessionName: String by lazy {
        intent.getStringExtra(EXTRA_SESSION_NAME)?.takeIf { it.isNotBlank() } ?: "新对话"
    }

    // 会话条目 id(服务端 chat 的 UUID,用于 GET /api/chats/{chatId} 拉取历史)
    private val chatId: String? by lazy {
        intent.getStringExtra(EXTRA_CHAT_ID)?.takeIf { it.isNotBlank() }
    }

    // 会话 id:从列表进入时为服务端 session_id,新建对话时本地生成
    private val sessionId: String by lazy {
        intent.getStringExtra(EXTRA_SESSION_ID)?.takeIf { it.isNotBlank() } ?: generateSessionId()
    }

    // 新建对话(服务端尚无记录)时无需拉取历史
    private val isNewSession: Boolean by lazy {
        intent.getStringExtra(EXTRA_SESSION_ID).isNullOrBlank()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        tokenManager = TokenManager.getInstance(this)
        authService = AuthService()

        if (!tokenManager.hasCredentials()) {
            navigateToLogin()
            return
        }

        initViews()
        setupToolbar()
        refreshTokenAndEnter()
        setupAIFace()
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbar)
        imageViewToolbarModel = findViewById(R.id.imageViewToolbarModel)
        imageViewEyeBadge = findViewById(R.id.imageViewEyeBadge)
        aiFaceView = findViewById<AIFaceView>(R.id.ai_face)
        recyclerView = findViewById(R.id.recyclerViewMessages)
        editTextMessage = findViewById(R.id.editTextMessage)
        buttonSend = findViewById(R.id.buttonSend)
        buttonAttach = findViewById(R.id.buttonAttach)
        attachmentPreviewContainer = findViewById(R.id.attachmentPreviewContainer)
        imageViewAttachmentPreview = findViewById(R.id.imageViewAttachmentPreview)
        buttonRemoveAttachment = findViewById(R.id.buttonRemoveAttachment)
        progressBar = findViewById(R.id.progressBar)
        editTextMessage.addTextChangedListener(afterTextChanged = {
            text ->
            refreshEditState()
        })
    }

    private fun setupToolbar() {
        sessionName = initialSessionName
        toolbar.title = sessionName
        toolbar.setNavigationOnClickListener { finish() }
        // 点击工具栏标题可编辑会话名(仅对已存在的会话生效)
        toolbar.setOnClickListener { onToolbarTitleClicked() }
    }

    /**
     * 设置 AI face 状态
     * */
    private fun setupAIFace(){
        isEditState.observeForever {
            state->
            aiFaceView.visibility = if (state)  View.GONE  else View.VISIBLE
            buttonSend.visibility = if (state)  View.VISIBLE  else View.GONE
        }
        isChatState.observeForever {
            state->
            // 如果是对话请求阶段就显示流光状态
            if(state){
                aiFaceView.setBackdropMode(AIFaceBackdrop.MODE_AURORA)
            }else{
                aiFaceView.setBackdropMode(AIFaceBackdrop.MODE_SOLID)
            }
        }
        botState.observeForever {
            aiFaceView.setState(it)
            // 如果是情绪化的表情，5秒之后置空为待命状态
            if(it == BotEmotion.HAPPY || it == BotEmotion.SAD){
                _delay(3000L){
                    botState.value = BotEmotion.LISTENING
                }
            }
        }
    }



    // ---------------- 会话标题修改(PUT /api/chats/{chatId}) ----------------

    /**
     * 点击工具栏标题:仅当存在 chatId(非新建会话)时弹出编辑对话框。
     * 保存时调用 PUT /api/chats/{chatId} 更新标题,成功后刷新标题并 setResult 通知列表页。
     */
    private fun onToolbarTitleClicked() {
        val targetChatId = chatId ?: run {
            Toast.makeText(this, "新建会话暂无标题可编辑", Toast.LENGTH_SHORT).show()
            return
        }
        showRenameSessionDialog(targetChatId)
    }

    private fun showRenameSessionDialog(targetChatId: String) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_session_name, null)
        val editTextName = dialogView.findViewById<EditText>(R.id.editTextDialogSessionName)
        editTextName.setText(sessionName)
        editTextName.setSelection(editTextName.text.length)

        AlertDialog.Builder(this)
            .setTitle("编辑会话标题")
            .setView(dialogView)
            .setPositiveButton("保存") { _: DialogInterface, _: Int ->
                val newName = editTextName.text.toString().trim()
                if (newName.isEmpty() || newName == sessionName) return@setPositiveButton
                renameSession(targetChatId, newName)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun renameSession(targetChatId: String, newName: String) {
        val token = tokenManager.getToken()
        val baseUrl = tokenManager.getBaseUrl()
        if (token.isNullOrEmpty() || baseUrl.isNullOrEmpty()) {
            showError("缺少登录信息,无法修改标题")
            return
        }
        val service = AgentService(baseUrl = baseUrl, authToken = token)

        Toast.makeText(this, "正在保存...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = service.renameChat(agentId, targetChatId, newName)
            result.onSuccess {
                sessionName = newName
                toolbar.title = newName
                Toast.makeText(this@ChatActivity, "标题已更新", Toast.LENGTH_SHORT).show()
                // 通知列表页刷新(同时带回新标题)
                val data = Intent().apply {
                    putExtra(EXTRA_RESULT_CHAT_ID, targetChatId)
                    putExtra(EXTRA_RESULT_NEW_NAME, newName)
                }
                setResult(Activity.RESULT_OK, data)
            }
            result.onFailure { e ->
                showError("修改标题失败: ${e.message}")
            }
        }
    }

    // ---------------- 登录态 & 数据加载 ----------------

    private fun refreshTokenAndEnter() {
        val baseUrl = tokenManager.getBaseUrl()
        val username = tokenManager.getUsername()
        val password = tokenManager.getPassword()
        val current = tokenManager.getCurrentAccount()

        if (baseUrl == null || username == null || password == null || current == null) {
            navigateToLogin()
            return
        }

        lifecycleScope.launch {
            val result = authService.login(baseUrl, username, password)
            result.onSuccess { response ->
                tokenManager.updateToken(current.id, response.token.orEmpty())
                initChat(baseUrl, response.token.orEmpty())
            }
            result.onFailure {
                Toast.makeText(this@ChatActivity, "Token刷新失败,请重新登录", Toast.LENGTH_SHORT).show()
                navigateToLogin()
            }
        }
    }

    /**
     * 读取当前 agent 的 active_model(GET /api/agents),按模型名匹配厂商标标显示在工具栏右侧;
     * 再经 GET /api/models 匹配 supports_image:
     * - 支持图片输入 → 显示附件按钮与工具栏右下角眼睛角标
     * - 接口失败或未匹配到时显示通用模型图标,不显示眼睛
     */
    private fun loadCurrentModelIcon() {
        lifecycleScope.launch {
            agentService.getAgents()
                .onSuccess { list ->
                    val active = list.firstOrNull { it.id == agentId }?.active_model
                    imageViewToolbarModel.setImageResource(
                        ModelIcons.of(active?.provider_id, active?.model)
                    )
                    // 匹配当前激活模型的 supports_image(供应商 id + 模型 id 双重匹配)
                    val supportsImage = modelService?.getModels()
                        ?.getOrNull()
                        ?.firstOrNull { it.id == active?.provider_id }
                        ?.extra_models.orEmpty()
                        .firstOrNull { it.id == active?.model }
                        ?.supports_image == true
                    refreshImageSupport(supportsImage)
                }
                .onFailure {
                    imageViewToolbarModel.setImageResource(ModelIcons.FALLBACK)
                    refreshImageSupport(false)
                }
        }
    }

    /** 当前激活模型是否支持图片输入:控制附件按钮显隐与工具栏眼睛角标 */
    private fun refreshImageSupport(supportsImage: Boolean) {
        imageViewEyeBadge.visibility = if (supportsImage) View.VISIBLE else View.GONE
        buttonAttach.visibility = if (supportsImage) View.VISIBLE else View.GONE
        // 当前模型不支持图片时清除已选附件
        if (!supportsImage && hasPendingImage()) {
            clearPendingImage()
        }
    }

    private fun initChat(baseUrl: String, token: String) {
        agentService = AgentService(baseUrl, token)
        uploadService = UploadService(baseUrl, token)
        modelService = ModelService(baseUrl, token)
        repository = ChatRepository(
            baseUrl = baseUrl,
            agentId = agentId,
            authToken = token,
            sessionId = sessionId
        )
        setupRecyclerView(this)
        setupClickListeners()
        loadCurrentModelIcon()
        loadMessages()
    }

    private fun setupRecyclerView(context: Context) {
        adapter = ChatAdapter(context = context)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
        // 流式回复时同一条消息会被高频 notifyItemChanged,关闭 change 交叉淡入淡出动画避免逐字更新闪烁
        (recyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
    }

    private fun setupClickListeners() {
        buttonSend.setOnClickListener {
            val message = editTextMessage.text.toString().trim()
            // 文本与图片至少有其一才可发送
            if (message.isNotEmpty() || hasPendingImage()) {
                sendMessage(message)
            }
        }
        // 点击模型图标切换当前 agent 的激活模型,成功后同步图标与眼睛角标/附件按钮
        imageViewToolbarModel.setOnClickListener {
            ModelSwitchDialog.show(this, agentId) { providerId, model ->
                imageViewToolbarModel.setImageResource(
                    ModelIcons.of(providerId, model.name ?: model.id)
                )
                refreshImageSupport(model.supports_image == true)
            }
        }
        buttonAttach.setOnClickListener {
            if (imageUploading) {
                showError("图片上传中,请稍候")
                return@setOnClickListener
            }
            pickImageLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }
        buttonRemoveAttachment.setOnClickListener { clearPendingImage() }
    }

    // ---------------- 图片附件:选图 → 本地缓存 → 上传(docs/qwenpaw/08 upload.md) ----------------

    private fun hasPendingImage(): Boolean = pendingImageFile != null

    /** 文本非空或已选图片时进入编辑态(显示发送按钮) */
    private fun refreshEditState() {
        val hasText = !editTextMessage.text.isNullOrEmpty()
        isEditState.value = hasText || hasPendingImage()
    }

    /** 刷新输入栏上方的待发送图片缩略图 */
    private fun refreshAttachmentPreview() {
        // 缩略图圆角裁剪,与消息气泡图片样式一致
        imageViewAttachmentPreview.clipToOutline = true
        imageViewAttachmentPreview.background =
            androidx.core.content.ContextCompat.getDrawable(this, R.drawable.bg_image_bubble)
        val file = pendingImageFile
        if (file != null && file.exists()) {
            attachmentPreviewContainer.visibility = View.VISIBLE
            imageViewAttachmentPreview.setImageBitmap(ImageDecode.decodeSampled(file.absolutePath, 256))
        } else {
            attachmentPreviewContainer.visibility = View.GONE
            imageViewAttachmentPreview.setImageBitmap(null)
        }
    }

    /**
     * 选中图片:复制到本地缓存(pending- 临时名)并立即上传,
     * 成功后按服务端文件名(<hash>_<原名>)落盘,供历史消息回查渲染。
     */
    private fun onImagePicked(uri: Uri) {
        // 已有待发送图片时直接替换(取消在途上传并删除临时文件)
        clearPendingImage()
        lifecycleScope.launch(Dispatchers.IO) {
            val displayName = queryDisplayName(uri)
            val ext = displayName?.substringAfterLast('.', "")?.lowercase().orEmpty()
                .takeIf { it.length in 2..5 } ?: "png"
            val temp = File(LocalMediaCache.mediaDir(this@ChatActivity), "pending-${System.currentTimeMillis()}.$ext")
            val copied = runCatching {
                contentResolver.openInputStream(uri)?.use { input ->
                    temp.outputStream().use { output -> input.copyTo(output) }
                } != null && temp.length() > 0
            }.getOrDefault(false)

            withContext(Dispatchers.Main) {
                if (!copied) {
                    temp.delete()
                    showError("图片读取失败")
                    return@withContext
                }
                pendingImageFile = temp
                pendingImageUrl = null
                refreshAttachmentPreview()
                refreshEditState()
                uploadPendingImage(temp, displayName?.takeIf { it.isNotBlank() } ?: temp.name)
            }
        }
    }

    /** 上传待发送图片,成功后记录服务端路径(image_url)并把文件改名为服务端文件名 */
    private fun uploadPendingImage(file: File, fileName: String) {
        imageUploading = true
        uploadJob = lifecycleScope.launch {
            uploadService.upload(file, fileName)
                .onSuccess { response ->
                    imageUploading = false
                    val serverUrl = response.url
                    if (serverUrl.isNullOrBlank()) {
                        // 期间未被移除才提示失败
                        if (pendingImageFile == file) {
                            file.delete()
                            clearPendingImage()
                            showError("上传失败: 响应缺少 url")
                        }
                        return@onSuccess
                    }
                    // 以服务端文件名落盘,历史图片按同名回查本地缓存
                    val finalFile = File(LocalMediaCache.mediaDir(this@ChatActivity), LocalMediaCache.basenameOf(serverUrl))
                    if (file.exists() && file.absolutePath != finalFile.absolutePath) {
                        file.renameTo(finalFile)
                    }
                    if (pendingImageFile == file) {
                        pendingImageFile = finalFile.takeIf { it.exists() } ?: file
                        pendingImageUrl = serverUrl
                        refreshAttachmentPreview()
                        toast("图片已上传")
                    }
                }
                .onFailure { e ->
                    imageUploading = false
                    if (pendingImageFile == file) {
                        clearPendingImage()
                        showError("图片上传失败: ${e.message}")
                    }
                }
        }
    }

    /** 移除待发送图片:取消在途上传,删除未完成的临时文件;已落盘的 <hash>_<name> 缓存保留(历史渲染可回查) */
    private fun clearPendingImage() {
        uploadJob?.cancel()
        uploadJob = null
        imageUploading = false
        pendingImageFile?.takeIf { it.name.startsWith("pending-") }?.delete()
        pendingImageFile = null
        pendingImageUrl = null
        refreshAttachmentPreview()
        refreshEditState()
    }

    /** 读取相册文件的原始文件名(OpenableColumns.DISPLAY_NAME),失败返回 null */
    private fun queryDisplayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    } catch (e: Exception) {
        null
    }

    /** 进入会话时经 GET /api/chats/{chatId} 拉取服务端历史;新建对话直接跳过 */
    private fun loadMessages() {

        isChatState.value = true
        botState.value = BotEmotion.CONFUSED

        if (isNewSession) return
        val targetChatId = chatId ?: return
        lifecycleScope.launch {
            agentService.getChatHistory(agentId, targetChatId)
                .onSuccess { history ->
                    botState.value = BotEmotion.HAPPY
                    buildAggregatedMessages(history).forEach { adapter.addMessage(it) }
                    if (adapter.itemCount > 0) {
                        recyclerView.scrollToPosition(adapter.itemCount - 1)
                    }
                    isChatState.value = false
                }
                .onFailure { e ->
                    botState.value = BotEmotion.SAD
                    showError("历史加载失败: ${e.message}")
                    isChatState.value = false
                }
        }
    }

    /**
     * 把服务端历史聚合成 UI 消息:
     * - 连续的 assistant 消息(正文/思考/工具调用)合并为一条,时间与调用量取最后一条,时间只显示一次
     * - plugin_call 渲染为工具调用行(图标 + 参数),reasoning 渲染为思考行,
     *   plugin_call_output(工具执行结果)不展示
     */
    private fun buildAggregatedMessages(history: List<HistoryMessage>): List<ChatMessage> {
        val result = mutableListOf<ChatMessage>()
        val segments = mutableListOf<MessageSegment>()
        var timestamp = 0L
        var usage: String? = null

        fun flush() {
            if (segments.isEmpty()) return
            val plainText = segments.filterIsInstance<MessageSegment.Text>()
                .joinToString("\n\n") { it.text }
            result.add(
                ChatMessage(
                    role = "assistant",
                    content = plainText,
                    timestamp = timestamp,
                    usage = usage,
                    segments = segments.toList()
                )
            )
            segments.clear()
            usage = null
        }

        history.forEach { item ->
            when (item.role) {
                "user" -> {
                    flush()
                    val text = item.content
                        ?.filter { it.type == "text" }
                        ?.joinToString("\n") { it.text.orEmpty() }
                        .orEmpty()
                    // 历史图片:取 type=image 的 image_url(服务端绝对路径),
                    // 本地缓存命中(filesDir/media/<hash>_<原名>)则直接渲染,未命中显示占位图
                    val imageUrl = item.content
                        ?.firstOrNull { it.type == "image" && !it.image_url.isNullOrBlank() }
                        ?.image_url
                    if (text.isNotEmpty() || imageUrl != null) {
                        result.add(
                            ChatMessage(
                                role = "user",
                                content = text,
                                timestamp = TimeFormatUtils.formatUTC( item.metadata?.timestamp ,"yyyy-MM-dd'T'HH:mm:ss"),
                                imageServerPath = imageUrl,
                                imageLocalPath = imageUrl?.let { LocalMediaCache.resolve(this, it) }
                            )
                        )
                    }
                }
                "assistant" -> {
                    timestamp = TimeFormatUtils.formatUTC(item.metadata?.timestamp, "yyyy-MM-dd'T'HH:mm:ss")
                    item.metadata?.metadata?.qwenpaw_turn_usage
                        ?.let { formatTurnUsage(it) }
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { usage = it }
                    when (item.type) {
                        "message" -> {
                            val text = item.content
                                ?.filter { it.type == "text" }
                                ?.joinToString("\n") { it.text.orEmpty() }
                                .orEmpty()
                            if (text.isNotEmpty()) {
                                segments.add(MessageSegment.Text(text))
                            }
                        }
                        // 思考过程(type=reasoning):渲染为思考行,点击弹窗查看全文
                        "reasoning" -> {
                            val text = item.content
                                ?.filter { it.type == "text" }
                                ?.joinToString("\n") { it.text.orEmpty() }
                                .orEmpty()
                            if (text.isNotEmpty()) {
                                segments.add(MessageSegment.Reasoning(text))
                            }
                        }
                        "plugin_call" -> {
                            val data = item.content?.firstOrNull { it.type == "data" }?.data
                            if (data != null && !data.name.isNullOrBlank()) {
                                val params = parseToolParams(data.arguments)
                                if (params.isEmpty()) {
                                    val raw = data.arguments.orEmpty().trim()
                                    if (raw == "{}" || raw.isBlank()) {
                                        // 无参数工具(如 get_current_time):气泡仅显示工具名
                                        segments.add(
                                            MessageSegment.ToolCall(
                                                toolName = data.name,
                                                paramText = data.name,
                                                paramFull = "无参数"
                                            )
                                        )
                                    } else {
                                        // arguments 非 JSON 时兜底直接展示原文
                                        val flat = raw.replace('\n', ' ')
                                        segments.add(
                                            MessageSegment.ToolCall(
                                                toolName = data.name,
                                                paramText = truncate(flat, 50),
                                                paramFull = flat
                                            )
                                        )
                                    }
                                } else {
                                    val (firstKey, firstValue) = params.first()
                                    segments.add(
                                        MessageSegment.ToolCall(
                                            toolName = data.name,
                                            // 气泡内单行展示首个参数,换行替换为空格并截断到 50 字符
                                            paramText =
                                "$firstKey - ${truncate(firstValue.replace('\n', ' '), 50)}",
                                            // 弹窗展示全部参数(如 write_file 的 file_path 与 content),支持复制
                                            paramFull = params.joinToString("\n") { (k, v) -> "$k - $v" }
                                        )
                                    )
                                }
                            }
                        }
                        // plugin_call_output(工具执行结果)不展示
                    }
                }
            }
        }
        flush()
        return result
    }

    /** 解析 plugin_call 的 arguments(JSON 字符串),返回全部 参数名 到 参数值(保留原始换行) */
    private fun parseToolParams(arguments: String?): List<Pair<String, String>> {
        if (arguments.isNullOrBlank()) return emptyList()
        return try {
            val obj = JSONObject(arguments)
            obj.keys().asSequence().map { key ->
                key to obj.optString(key)
            }.toList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 超过 max 长度时截断并以 ... 结尾 */
    private fun truncate(text: String, max:Int): String =
        if (text.length <= max) text else text.take(max) + "..."

    private fun sendMessage(message: String) {
        // 图片上传未完成时阻断发送
        if (imageUploading) {
            showError("图片上传中,请稍候")
            return
        }
        val imageUrl = pendingImageUrl
        val imageLocalPath = pendingImageFile?.absolutePath
        if (message.isEmpty() && imageUrl == null) return

        chatJob?.cancel()
        editTextMessage.text?.clear()
        // 附件已随本条消息消费,清除待发送状态(已落盘缓存保留)
        pendingImageFile = null
        pendingImageUrl = null
        refreshAttachmentPreview()
        refreshEditState()
        setLoading(true)

        isChatState.value = true
        botState.value = BotEmotion.SEARCHING

        adapter.addMessage(
            ChatMessage(
                role = "user",
                content = message,
                imageLocalPath = imageLocalPath,
                imageServerPath = imageUrl
            )
        )
        recyclerView.scrollToPosition(adapter.itemCount - 1)

        chatJob = lifecycleScope.launch {
            repository.sendMessage(message, imageUrl)
                .catch { e ->
                    showError("发送失败: ${e.message}")
                    setLoading(false)
                }
                .onCompletion {
                    setLoading(false)
                    isChatState.value = false
                    botState.value = BotEmotion.HAPPY
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
                                }
                            }
                        }
                        is SSEEvent.Completed -> {
                            if (event.text.isNotEmpty()) {
                                adapter.updateLastMessage(event.text)
                                recyclerView.scrollToPosition(adapter.itemCount - 1)
                            }
                        }
                        is SSEEvent.TurnUsage -> {
                            // 2.0 版本 turn_usage 调用量，显示在 Agent 气泡下方小字
                            val usageText = formatTurnUsage(event.usage)
                            if (usageText.isNotEmpty()) {
                                adapter.updateLastMessageUsage(usageText)
                            }
                        }
                        is SSEEvent.Error -> {
                            showError(event.message)
                            adapter.updateLastMessage("错误: ${event.message}")
                        }
                        is SSEEvent.Unknown -> {
                            val text = event.response.output?.firstOrNull()
                                ?.content?.firstOrNull()?.text ?: ""
                            if (text.isNotEmpty()) {
                                adapter.updateLastMessage(text)
                            }
                        }
                    }
                }
        }
    }

    /** 格式化 turn_usage 调用量，例如: MiniMax-M3 · 输入 27752 / 输出 31 / 共 27783 tokens · 上下文 25.0% */
    private fun formatTurnUsage(response: TurnUsageResponse): String {
        val sb = StringBuilder()
        response.usage?.let { usage ->
            if (!usage.model_name.isNullOrEmpty()) {
                sb.append(usage.model_name).append(" · ")
            }
            sb.append("输入 ${usage.prompt_tokens ?: 0} / 输出 ${usage.completion_tokens ?: 0} / 共 ${usage.total_tokens ?: 0} tokens")
        }
        response.context_usage?.context_usage_ratio?.let { ratio ->
            sb.append(" · 上下文 ").append(String.format(Locale.getDefault(), "%.1f%%", ratio))
        }
        return sb.toString()
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
        // 防泄漏：回调持有 Activity 引用
        aiFaceView.destroyView()
    }

    companion object {
        private const val EXTRA_AGENT_ID = "extra_agent_id"
        private const val EXTRA_CHAT_ID = "extra_chat_id"
        private const val EXTRA_SESSION_ID = "extra_session_id"
        private const val EXTRA_SESSION_NAME = "extra_session_name"

        // setResult 回传:被修改标题的会话与新名称
        const val EXTRA_RESULT_CHAT_ID = "extra_result_chat_id"
        const val EXTRA_RESULT_NEW_NAME = "extra_result_new_name"

        /**
         * 进入对话页。
         *
         * @param chatId 会话条目 id(服务端 chat 的 UUID),用于 GET /api/chats/{chatId} 拉取历史;新建对话时传 null
         * @param sessionId 服务端会话 id;传 null 表示新建对话(由本地生成会话 id)
         */
        fun start(
            context: Context,
            agentId: String,
            chatId: String?,
            sessionId: String?,
            sessionName: String?
        ) {
            val intent = Intent(context, ChatActivity::class.java).apply {
                putExtra(EXTRA_AGENT_ID, agentId)
                putExtra(EXTRA_CHAT_ID, chatId)
                putExtra(EXTRA_SESSION_ID, sessionId)
                putExtra(EXTRA_SESSION_NAME, sessionName)
            }
            context.startActivity(intent)
        }

        /** 生成风格与服务端一致的会话 id: <时间戳>-<6位随机小写字母> */
        private fun generateSessionId(): String {
            val suffix = (1..6).map { ('a'..'z').random() }.joinToString("")
            return "${System.currentTimeMillis()}-$suffix"
        }
    }
}
