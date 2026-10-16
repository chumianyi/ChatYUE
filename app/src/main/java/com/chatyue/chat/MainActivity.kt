package com.chatyue.chat

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnNextLayout
import androidx.core.widget.doAfterTextChanged
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.chatyue.chat.attachment.ImageGalleryActivity
import com.chatyue.chat.attachment.MediaImageStore
import com.chatyue.chat.backend.BackendErrorType
import com.chatyue.chat.backend.BackendFinishReason
import com.chatyue.chat.backend.BackendMessage
import com.chatyue.chat.backend.BackendRole
import com.chatyue.chat.backend.ChatMode
import com.chatyue.chat.backend.ChatRequest
import com.chatyue.chat.backend.ModelCatalog
import com.chatyue.chat.databinding.ActivityMainBinding
import com.chatyue.chat.remote.RemoteConfigRepository
import com.chatyue.chat.tools.EssayActivity
import com.chatyue.chat.tools.TranslationActivity
import io.noties.markwon.Markwon
import java.util.concurrent.Executors
import kotlin.math.min

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: ChatViewModel
    private lateinit var repository: LocalChatRepository
    private lateinit var messageAdapter: ChatMessageAdapter
    private lateinit var conversationAdapter: ConversationAdapter

    /** 启动消息搜索页，返回用户选中的会话并打开。 */
    private val searchLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val id = result.data
                ?.getLongExtra(MessageSearchActivity.EXTRA_OPEN_CONVERSATION, -1L)
                ?: -1L
            if (id > 0) openConversationById(id)
        }
    }

    /** onCreate 可能因「未同意协议」提前返回，用它避免 onDestroy 访问未初始化的字段。 */
    private var initialized = false

    private var currentConversationId: Long? = null
    private var followStreaming = true
    private var isMessageListScrolling = false
    private var pendingStreamingState: GenerationState.Active? = null
    private var pendingMessageReloadConversationId: Long? = null
    private var pendingMessageListScroll: Runnable? = null

    private var pendingImageUri: Uri? = null
    private var pendingImageName: String? = null
    private var pendingTextName: String? = null
    private var pendingTextContent: String? = null

    /** 新会话的默认记忆模式（2.0：新建会话时由用户选择，默认全局）。 */
    private var pendingMemoryMode: ConversationMemoryMode = ConversationMemoryMode.GLOBAL

    /** 强制升级守护（2.0）。 */
    private var versionGuard: VersionGuard? = null

    private val ioExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "deepseek-io").apply { isDaemon = true }
    }

    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val uri = data.data ?: return@registerForActivityResult
        val name = data.getStringExtra(ImageGalleryActivity.EXTRA_IMAGE_NAME)
            ?: getString(R.string.attachment_image_label)
        setImageAttachment(uri, name)
    }

    private val textFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) readTextAttachment(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 首次启动必须先同意用户协议与隐私政策。
        if (!AgreementStore.isAccepted(this)) {
            startActivity(Intent(this, AgreementActivity::class.java))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(this)[ChatViewModel::class.java]
        repository = viewModel.repository

        configureSystemBars()
        configureDrawer()
        configureLists()
        configureComposer()
        configureActions()
        configureBackNavigation()
        observeGeneration()
        observeContextEvents()

        applyGlobalBackground()
        registerAppConfigListeners()

        restoreConversation(savedInstanceState)
        refreshConversationList()
        initialized = true

        // 2.0：强制版本更新守护，静默常驻。
        versionGuard = VersionGuard(this).also { it.start() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(
            STATE_CONVERSATION_ID,
            currentConversationId ?: NO_CONVERSATION_ID,
        )
    }

    override fun onStart() {
        super.onStart()
        // 2.0：每次回到前台都静默补检一次版本。
        versionGuard?.start()
    }

    override fun onDestroy() {
        // initialized 为 false 说明本次 onCreate 直接跳去了协议页，此时还没创建 ViewModel。
        if (initialized && !isChangingConfigurations) {
            viewModel.cancelGeneration()
        }
        versionGuard?.dismiss()
        versionGuard = null
        ioExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun configureSystemBars() {
        val usesForcedEdgeToEdge = Build.VERSION.SDK_INT >= 35
        WindowCompat.setDecorFitsSystemWindows(window, !usesForcedEdgeToEdge)
        WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
            isAppearanceLightNavigationBars = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1
        }
        if (usesForcedEdgeToEdge) {
            ViewCompat.setOnApplyWindowInsetsListener(binding.drawerLayout) { view, insets ->
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
                view.setPadding(
                    0,
                    systemBars.top,
                    0,
                    maxOf(systemBars.bottom, ime.bottom),
                )
                insets
            }
        }
    }

    private fun configureDrawer() {
        val screenWidth = resources.displayMetrics.widthPixels
        val preferredWidth = dpToPx(320)
        val edgeVisibleWidth = dpToPx(40)
        val drawerWidth = min(preferredWidth, screenWidth - edgeVisibleWidth)
        val layoutParams = binding.drawerContent.layoutParams as DrawerLayout.LayoutParams
        layoutParams.width = drawerWidth
        binding.drawerContent.layoutParams = layoutParams
        binding.drawerLayout.setScrimColor(getColorCompat(R.color.ds_scrim))
    }

    private fun configureLists() {
        messageAdapter = ChatMessageAdapter(Markwon.create(this), ::handleMessageAction, ::handleMessageLongPress)
        binding.messageList.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = messageAdapter
            setHasFixedSize(false)
            itemAnimator = null
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(
                    recyclerView: RecyclerView,
                    newState: Int,
                ) {
                    isMessageListScrolling = newState != RecyclerView.SCROLL_STATE_IDLE
                    if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                        followStreaming = false
                        cancelPendingMessageListScroll(recyclerView)
                    } else if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        followStreaming = !recyclerView.canScrollVertically(1)
                        if (followStreaming) {
                            applyPendingMessageUpdate()
                        }
                    }
                }

                override fun onScrolled(
                    recyclerView: RecyclerView,
                    dx: Int,
                    dy: Int,
                ) {
                    if (recyclerView.scrollState != RecyclerView.SCROLL_STATE_IDLE) {
                        followStreaming = !recyclerView.canScrollVertically(1)
                    }
                }
            })
        }

        conversationAdapter = ConversationAdapter(::selectConversation, ::onConversationLongPress)
        binding.conversationList.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = conversationAdapter
            setHasFixedSize(true)
        }
    }

    private fun configureComposer() {
        // 2.0：双模型双模式，点击输入框上方的模式标签可切换（思考/快速）。
        binding.thinkingModeLabel.isSelected = true
        updateModeLabel()
        binding.thinkingModeLabel.setOnClickListener { showModeSwitchDialog() }
        binding.attachmentRemove.setOnClickListener { clearAttachments() }

        binding.messageInput.doAfterTextChanged {
            updateSendButton()
        }
        binding.searchInput.doAfterTextChanged {
            refreshConversationList(it?.toString().orEmpty())
        }
        updateSendButton()
        updateContextIndicator(emptyList())
    }

    private fun configureActions() {
        binding.menuButton.setOnClickListener {
            refreshConversationList(binding.searchInput.text?.toString().orEmpty())
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }
        binding.newChatButton.setOnClickListener {
            showNewConversationModeDialog()
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        }
        binding.sendButton.setOnClickListener {
            if (isGenerating()) {
                stopGeneration(showNotice = true)
            } else {
                sendMessage()
            }
        }
        binding.addButton.setOnClickListener {
            showAttachmentMenu()
        }
        binding.moreButton.setOnClickListener {
            showMoreMenu()
        }
        binding.toolTranslate.setOnClickListener {
            openTool(TranslationActivity::class.java)
        }
        binding.toolEssay.setOnClickListener {
            openTool(EssayActivity::class.java)
        }
    }

    private fun openTool(activity: Class<*>) {
        binding.drawerLayout.closeDrawer(GravityCompat.START)
        startActivity(Intent(this, activity))
    }

    private fun configureBackNavigation() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                        binding.drawerLayout.closeDrawer(GravityCompat.START)
                    } else {
                        finish()
                    }
                }
            },
        )
    }

    // ---------------- 附件 ----------------

    private fun showAttachmentMenu() {
        val options = arrayOf(
            getString(R.string.attach_image),
            getString(R.string.attach_text),
            getString(R.string.attach_cancel),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.attach_title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> galleryLauncher.launch(
                        Intent(this, ImageGalleryActivity::class.java),
                    )

                    1 -> textFileLauncher.launch(arrayOf("text/plain"))

                    else -> Unit
                }
            }
            .show()
    }

    private fun setImageAttachment(uri: Uri, name: String) {
        pendingImageUri = uri
        pendingImageName = name
        binding.attachmentThumb.visibility = View.VISIBLE
        binding.attachmentThumb.setImageDrawable(null)
        com.chatyue.chat.attachment.ImageLoader.load(
            context = this,
            uri = uri,
            targetSizePx = dpToPx(72),
            imageView = binding.attachmentThumb,
        )
        binding.attachmentName.text = getString(R.string.attachment_image_label) + " · " + name
        binding.attachmentBar.visibility = View.VISIBLE
        // 需求：上传图片时自动提示模型只支持文本。
        CompatToast.show(this, R.string.image_text_only_notice, android.widget.Toast.LENGTH_LONG)
        updateSendButton()
    }

    private fun readTextAttachment(uri: Uri) {
        CompatToast.show(this, R.string.attachment_reading)
        ioExecutor.execute {
            val name = MediaImageStore.displayName(this, uri)
            val content = MediaImageStore.readText(this, uri)
            runOnUiThread {
                when {
                    content == null -> CompatToast.show(this, R.string.attachment_too_large)
                    content.isEmpty() -> CompatToast.show(this, R.string.attachment_read_failed)
                    else -> {
                        pendingTextName = name
                        pendingTextContent = content
                        binding.attachmentThumb.visibility = View.GONE
                        binding.attachmentName.text =
                            getString(R.string.attachment_text_label) + " · " + name +
                                "（" + content.length + " 字）"
                        binding.attachmentBar.visibility = View.VISIBLE
                        updateSendButton()
                    }
                }
            }
        }
    }

    private fun clearAttachments() {
        pendingImageUri = null
        pendingImageName = null
        pendingTextName = null
        pendingTextContent = null
        binding.attachmentThumb.setImageDrawable(null)
        binding.attachmentBar.visibility = View.GONE
        updateSendButton()
    }

    private fun hasAttachment(): Boolean =
        pendingImageUri != null || pendingTextContent != null

    /** 把附件内容拼进用户消息：图片只做说明，txt 内容直接内联给模型读取。 */
    private fun buildAttachmentBlock(): String {
        val block = StringBuilder()
        pendingImageName?.let { name ->
            block.append("\n\n[图片附件] ")
                .append(name)
                .append("（本模型仅支持识别文本，无法识别图片内容）")
        }
        pendingTextContent?.let { content ->
            block.append("\n\n[文本附件] ")
                .append(pendingTextName ?: "")
                .append('\n')
                .append(content)
                .append("\n[/文本附件]")
        }
        return block.toString()
    }

    // ---------------- 生成 ----------------

    private fun observeGeneration() {
        viewModel.generationState.observe(this) { state ->
            when (state) {
                GenerationState.Idle -> {
                    updateComposer(false)
                    pendingStreamingState = null
                    currentConversationId?.let { conversationId ->
                        if (isMessageListScrolling || !followStreaming) {
                            pendingMessageReloadConversationId = conversationId
                        } else if (pendingMessageReloadConversationId == null) {
                            loadMessages(conversationId)
                        }
                    }
                }

                is GenerationState.Active -> {
                    updateComposer(true)
                    if (currentConversationId == state.conversationId) {
                        if (isMessageListScrolling || !followStreaming) {
                            pendingStreamingState = state
                        } else {
                            pendingStreamingState = null
                            showStreamingMessages(state)
                        }
                    }
                }

                is GenerationState.Completed -> {
                    updateComposer(false)
                    if (currentConversationId == state.conversationId) {
                        pendingStreamingState = null
                        if (isMessageListScrolling || !followStreaming) {
                            pendingMessageReloadConversationId = state.conversationId
                        } else {
                            loadMessages(state.conversationId, scrollToEnd = followStreaming)
                        }
                    }
                    refreshConversationList(binding.searchInput.text?.toString().orEmpty())
                    showCompletionNotice(state.message.finishReason)
                    viewModel.acknowledgeTerminal(state.eventId)
                }

                is GenerationState.Failed -> {
                    updateComposer(false)
                    state.conversationId?.let { conversationId ->
                        if (currentConversationId == conversationId) {
                            pendingStreamingState = null
                            if (isMessageListScrolling || !followStreaming) {
                                pendingMessageReloadConversationId = conversationId
                            } else {
                                loadMessages(conversationId, scrollToEnd = followStreaming)
                            }
                        }
                    }
                    showBackendError(state.error.type, state.error.message)
                    viewModel.acknowledgeTerminal(state.eventId)
                }
            }
        }
    }

    private fun observeContextEvents() {
        viewModel.contextEvent.observe(this) { event ->
            when (event) {
                null -> Unit

                is ContextEvent.CompressionStarted -> {
                    CompatToast.show(
                        this,
                        getString(R.string.context_compressing, event.percent),
                        android.widget.Toast.LENGTH_LONG,
                    )
                }

                is ContextEvent.CompressionFinished -> {
                    CompatToast.show(
                        this,
                        getString(R.string.context_compressed, event.percent),
                        android.widget.Toast.LENGTH_LONG,
                    )
                    currentConversationId?.let { conversationId ->
                        if (viewModel.generationState.value !is GenerationState.Active) {
                            loadMessages(conversationId, scrollToEnd = followStreaming)
                        } else {
                            pendingMessageReloadConversationId = conversationId
                        }
                    }
                    viewModel.acknowledgeContextEvent()
                }

                is ContextEvent.CompressionFailed -> {
                    CompatToast.show(
                        this,
                        getString(R.string.context_compress_failed, humanize(event.message)),
                        android.widget.Toast.LENGTH_LONG,
                    )
                    viewModel.acknowledgeContextEvent()
                }
            }
        }
    }

    private fun sendMessage() {
        val rawInput = binding.messageInput.text?.toString()?.trim().orEmpty()
        if (rawInput.isEmpty() && !hasAttachment()) return
        if (!hasUsableNetwork()) {
            CompatToast.show(this, R.string.network_unavailable, android.widget.Toast.LENGTH_LONG)
            return
        }

        val userContent = (rawInput + buildAttachmentBlock()).trim()
        val wasNewConversation = currentConversationId == null
        val conversationId = currentConversationId ?: repository.createConversation(
            ConversationFormatter.titleFrom(userContent),
            pendingMemoryMode,
        ).also {
            currentConversationId = it
            binding.chatTitle.text = ConversationFormatter.titleFrom(userContent)
        }

        repository.addMessage(conversationId, MessageRole.USER, userContent)
        if (wasNewConversation) {
            repository.updateConversationTitle(
                conversationId,
                ConversationFormatter.titleFrom(userContent),
            )
        }
        binding.messageInput.text?.clear()
        clearAttachments()
        followStreaming = true
        pendingStreamingState = null
        pendingMessageReloadConversationId = null
        loadMessages(conversationId)

        dispatchChatRequest(conversationId)
    }

    private fun dispatchChatRequest(conversationId: Long) {
        val messages = buildList {
            // 2.0 全局记忆：全局模式会话会注入其他全局会话的历史作为 system 记忆。
            val conversation = repository.getConversation(conversationId)
            if (conversation?.memoryMode == ConversationMemoryMode.GLOBAL) {
                val memory = buildGlobalMemoryTranscript(
                    repository.getGlobalMemoryMessages(conversationId),
                )
                if (!memory.isNullOrBlank()) {
                    add(BackendMessage(BackendRole.SYSTEM, memory))
                }
            }
            repository.getMessages(conversationId).forEach { message ->
                when (message.role) {
                    MessageRole.USER -> add(BackendMessage(BackendRole.USER, message.content))
                    MessageRole.ASSISTANT ->
                        add(BackendMessage(BackendRole.ASSISTANT, message.content))

                    MessageRole.SYSTEM -> add(BackendMessage(BackendRole.SYSTEM, message.content))
                    MessageRole.THINKING_ACTIVE,
                    MessageRole.THINKING_FINISHED -> Unit
                }
            }
        }
        viewModel.sendMessage(
            ChatRequest(
                conversationId = conversationId.toString(),
                messages = messages,
                mode = AppConfig.chatMode(this),
            ),
        )
    }

    /** 把其他全局会话的历史整理成一段 system 记忆文本（限量传入，避免超预算）。 */
    private fun buildGlobalMemoryTranscript(messages: List<ChatMessage>): String? {
        if (messages.isEmpty()) return null
        val recent = messages.takeLast(GLOBAL_MEMORY_MAX_MESSAGES)
        val transcript = recent.joinToString(separator = "\n\n") { message ->
            val speaker = when (message.role) {
                MessageRole.USER -> "用户"
                MessageRole.ASSISTANT -> "助手"
                else -> return@joinToString ""
            }
            "$speaker：${message.content}"
        }.trim()
        if (transcript.isBlank()) return null
        return Prompts.globalMemorySystem(transcript)
    }

    private fun restoreConversation(savedInstanceState: Bundle?) {
        val restoredId = savedInstanceState
            ?.getLong(STATE_CONVERSATION_ID, NO_CONVERSATION_ID)
            ?.takeIf { it != NO_CONVERSATION_ID }
        val restoredConversation = restoredId?.let { id ->
            repository.getConversations().firstOrNull { it.id == id }
        }

        if (restoredConversation == null) {
            showNewConversation()
        } else {
            currentConversationId = restoredConversation.id
            binding.chatTitle.text = restoredConversation.title
            val state = viewModel.generationState.value
            if (state is GenerationState.Active &&
                state.conversationId == restoredConversation.id
            ) {
                showStreamingMessages(state)
            } else {
                loadMessages(restoredConversation.id)
            }
        }
    }

    private fun showNewConversation() {
        stopGeneration(showNotice = false)
        currentConversationId = null
        followStreaming = true
        pendingStreamingState = null
        pendingMessageReloadConversationId = null
        binding.chatTitle.setText(R.string.new_chat)
        binding.messageInput.text?.clear()
        clearAttachments()
        showMessages(emptyList())
        hideKeyboard()
        refreshConversationList(binding.searchInput.text?.toString().orEmpty())
    }

    /**
     * 2.0：新建会话时的记忆模式选择。
     * 全局对话 = 接入全域记忆，与所有历史会话数据互通；
     * 单独对话 = 本会话独立隔离，不参与全局记忆。
     */
    private fun showNewConversationModeDialog() {
        val options = arrayOf(
            getString(R.string.memory_mode_global_title) + "  ·  " +
                getString(R.string.memory_mode_global_summary),
            getString(R.string.memory_mode_isolated_title) + "  ·  " +
                getString(R.string.memory_mode_isolated_summary),
        )
        val checked = if (pendingMemoryMode == ConversationMemoryMode.ISOLATED) 1 else 0
        AlertDialog.Builder(this)
            .setTitle(R.string.memory_mode_dialog_title)
            .setSingleChoiceItems(options, checked) { dialog, which ->
                pendingMemoryMode =
                    if (which == 1) ConversationMemoryMode.ISOLATED else ConversationMemoryMode.GLOBAL
                dialog.dismiss()
                showNewConversation()
            }
            .setNegativeButton(R.string.memory_mode_dialog_cancel, null)
            .show()
    }

    /** 2.0：思考/快速模式切换，两种模式各自绑定固定模型，不可混用。 */
    private fun showModeSwitchDialog() {
        val modes = ChatMode.entries.toTypedArray()
        val labels = modes.map { mode ->
            "${ModelCatalog.displayName(mode)}  ·  ${ModelCatalog.description(mode)}"
        }.toTypedArray()
        val checked = modes.indexOf(AppConfig.chatMode(this)).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.mode_switch_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                AppConfig.setChatMode(this, modes[which])
                updateModeLabel()
                dialog.dismiss()
                CompatToast.show(
                    this,
                    getString(R.string.mode_switched, ModelCatalog.displayName(modes[which])),
                    Toast.LENGTH_SHORT,
                )
            }
            .setNegativeButton(R.string.memory_mode_dialog_cancel, null)
            .show()
    }

    private fun updateModeLabel() {
        binding.thinkingModeLabel.text = ModelCatalog.displayName(AppConfig.chatMode(this))
    }

    /** 2.0 全局背景：把用户设置的纯色/图片背景应用到主内容区最底层。 */
    private fun applyGlobalBackground() {
        AppearanceStore.applyTo(this, binding.mainContent)
    }

    /** 2.0：界面尺寸档位变化时即时重建界面。 */
    private fun registerAppConfigListeners() {
        AppConfig.registerListener(object : AppConfig.Listener {
            override fun onUiScaleChanged(scale: AppConfig.UiScale) {
                if (!isFinishing) recreate()
            }
        })
    }

    private fun openConversationById(conversationId: Long) {
        val conversation = repository.getConversation(conversationId) ?: return
        selectConversation(conversation)
        binding.drawerLayout.closeDrawer(GravityCompat.START)
    }

    private fun selectConversation(conversation: Conversation) {
        stopGeneration(showNotice = false)
        currentConversationId = conversation.id
        followStreaming = true
        pendingStreamingState = null
        pendingMessageReloadConversationId = null
        binding.chatTitle.text = conversation.title
        loadMessages(conversation.id)
        hideKeyboard()
        binding.drawerLayout.closeDrawer(GravityCompat.START)
        refreshConversationList(binding.searchInput.text?.toString().orEmpty())
    }

    private fun loadMessages(
        conversationId: Long,
        scrollToEnd: Boolean = true,
    ) {
        showMessages(repository.getMessages(conversationId), scrollToEnd)
    }

    private fun showStreamingMessages(state: GenerationState.Active) {
        val messages = repository.getMessages(state.conversationId).toMutableList()
        val hasThinking = state.thinking.isNotEmpty()
        if (hasThinking) {
            messages += ChatMessage(
                id = STREAM_THINKING_ID,
                conversationId = state.conversationId,
                role = MessageRole.THINKING_ACTIVE,
                content = state.thinking,
                createdAt = Long.MAX_VALUE - 1,
            )
        }
        if (state.content.isNotEmpty()) {
            messages += ChatMessage(
                id = STREAM_ASSISTANT_ID,
                conversationId = state.conversationId,
                role = MessageRole.ASSISTANT,
                content = state.content,
                createdAt = Long.MAX_VALUE,
            )
        } else if (!hasThinking) {
            messages += ChatMessage(
                id = STREAM_ASSISTANT_ID,
                conversationId = state.conversationId,
                role = MessageRole.ASSISTANT,
                content = getString(R.string.waiting_for_response),
                createdAt = Long.MAX_VALUE,
            )
        }
        showMessages(
            messages,
            scrollToEnd = followStreaming,
            guardScrollForStreaming = true,
            // 流式输出期间先不显示操作栏，回答结束后再出现。
            streamingMessageId = STREAM_ASSISTANT_ID,
        )
    }

    private fun showMessages(
        messages: List<ChatMessage>,
        scrollToEnd: Boolean = true,
        guardScrollForStreaming: Boolean = false,
        streamingMessageId: Long? = null,
    ) {
        val hasMessages = messages.isNotEmpty()
        binding.welcomeContainer.visibility = if (hasMessages) View.GONE else View.VISIBLE
        binding.messageList.visibility = if (hasMessages) View.VISIBLE else View.GONE
        messageAdapter.setStreamingMessageId(streamingMessageId)
        messageAdapter.submitMessages(messages)
        updateContextIndicator(messages)
        cancelPendingMessageListScroll(binding.messageList)
        if (hasMessages && scrollToEnd) {
            postMessageListScroll {
                if (!guardScrollForStreaming || followStreaming) {
                    scrollMessageListToBottom()
                }
            }
        }
    }

    /**
     * 右上角上下文百分比：按字符估算 token（中文 ×1.5，英文 ×0.25），上限 128K。
     */
    private fun updateContextIndicator(messages: List<ChatMessage>) {
        if (messages.isEmpty()) {
            binding.contextPercent.isActivated = false
            binding.contextPercent.text = getString(R.string.context_usage_zero)
            binding.contextPercent.setTextColor(
                ContextCompat.getColor(this, R.color.ds_text_secondary),
            )
            return
        }
        val percent = ContextBudget.percentOf(messages.filter { it.role.isContextMessage })
        binding.contextPercent.text = getString(R.string.context_usage, percent)
        val high = percent >= ContextBudget.COMPRESS_THRESHOLD_PERCENT
        binding.contextPercent.isActivated = high
        binding.contextPercent.setTextColor(
            ContextCompat.getColor(
                this,
                if (high) R.color.ds_context_warning else R.color.ds_text_secondary,
            ),
        )
    }

    private fun applyPendingMessageUpdate() {
        if (isMessageListScrolling || !followStreaming) return

        val reloadConversationId = pendingMessageReloadConversationId
        if (reloadConversationId != null) {
            pendingMessageReloadConversationId = null
            pendingStreamingState = null
            if (currentConversationId == reloadConversationId) {
                loadMessages(reloadConversationId, scrollToEnd = followStreaming)
            }
            return
        }

        val streamingState = pendingStreamingState ?: return
        pendingStreamingState = null
        if (currentConversationId == streamingState.conversationId) {
            showStreamingMessages(streamingState)
        }
    }

    private fun postMessageListScroll(action: () -> Unit) {
        val runnable = Runnable {
            pendingMessageListScroll = null
            if (!isMessageListScrolling) {
                action()
            }
        }
        pendingMessageListScroll = runnable
        binding.messageList.post(runnable)
    }

    private fun scrollMessageListToBottom() {
        if (messageAdapter.itemCount == 0) return
        binding.messageList.doOnNextLayout { recyclerView ->
            if (!isMessageListScrolling && followStreaming) {
                recyclerView.scrollBy(0, Int.MAX_VALUE)
            }
        }
    }

    private fun cancelPendingMessageListScroll(recyclerView: RecyclerView) {
        pendingMessageListScroll?.let(recyclerView::removeCallbacks)
        pendingMessageListScroll = null
    }

    private fun refreshConversationList(query: String = "") {
        // 仓储层已按「置顶优先 + 更新时间倒序」返回，这里再把置顶会话归入独立分组。
        val conversations = repository.getConversations(query)
        val items = mutableListOf<DrawerItem>()
        val pinned = conversations.filter { it.pinned }
        val rest = conversations.filterNot { it.pinned }
        if (pinned.isNotEmpty()) {
            items += DrawerItem.Section(ConversationSection.PINNED)
            pinned.forEach { items += DrawerItem.Entry(it) }
        }
        var previousSection: ConversationSection? = null
        rest.forEach { conversation ->
            val section = ConversationFormatter.sectionFor(conversation.updatedAt)
            if (section != previousSection) {
                items += DrawerItem.Section(section)
                previousSection = section
            }
            items += DrawerItem.Entry(conversation)
        }
        conversationAdapter.submitItems(items, currentConversationId)
    }

    /** 长按侧边栏会话：置顶 / 取消置顶、重命名、删除。 */
    private fun onConversationLongPress(conversation: Conversation, anchor: View) {
        val pinLabel = if (conversation.pinned) {
            R.string.action_unpin
        } else {
            R.string.action_pin
        }
        PopupMenu(this, anchor).apply {
            menu.add(0, MENU_CONV_PIN, 0, pinLabel)
            menu.add(0, MENU_CONV_RENAME, 1, R.string.action_rename_conversation)
            menu.add(0, MENU_CONV_DELETE, 2, R.string.action_delete_conversation)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_CONV_PIN -> {
                        togglePin(conversation)
                        true
                    }

                    MENU_CONV_RENAME -> {
                        showRenameDialog(conversation)
                        true
                    }

                    MENU_CONV_DELETE -> {
                        confirmDeleteConversation(conversation)
                        true
                    }

                    else -> false
                }
            }
        }.show()
    }

    private fun togglePin(conversation: Conversation) {
        val pinned = !conversation.pinned
        repository.setConversationPinned(conversation.id, pinned)
        refreshConversationList(binding.searchInput.text?.toString().orEmpty())
        CompatToast.show(
            this,
            if (pinned) R.string.toast_conversation_pinned else R.string.toast_conversation_unpinned,
        )
    }

    private fun showRenameDialog(conversation: Conversation) {
        val input = EditText(this).apply {
            setText(conversation.title)
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.rename_conversation_title)
            .setMessage(R.string.rename_conversation_hint)
            .setView(input)
            .setPositiveButton(R.string.action_confirm) { _, _ ->
                val title = input.text?.toString()?.trim().orEmpty()
                if (title.isEmpty()) {
                    CompatToast.show(this, R.string.rename_empty_title)
                    return@setPositiveButton
                }
                repository.updateConversationTitle(conversation.id, title)
                if (currentConversationId == conversation.id) {
                    binding.chatTitle.text = title
                }
                refreshConversationList(binding.searchInput.text?.toString().orEmpty())
                CompatToast.show(this, R.string.toast_conversation_renamed)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteConversation(conversation: Conversation) {
        AlertDialog.Builder(this)
            .setTitle(R.string.confirm_delete_conversation_title)
            .setMessage(R.string.confirm_delete_conversation)
            .setPositiveButton(R.string.action_delete_conversation) { _, _ ->
                repository.deleteConversation(conversation.id)
                // 若删除的正是当前会话，回到新建对话状态。
                if (currentConversationId == conversation.id) {
                    showNewConversation()
                }
                refreshConversationList(binding.searchInput.text?.toString().orEmpty())
                CompatToast.show(this, R.string.toast_conversation_deleted)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------------- 菜单 ----------------

    private fun showMoreMenu() {
        PopupMenu(this, binding.moreButton).apply {
            menu.add(0, MENU_CHANGELOG, 0, R.string.menu_changelog)
            menu.add(0, MENU_CHECK_UPDATE, 1, R.string.menu_check_update)
            menu.add(0, MENU_SETTINGS, 2, R.string.menu_settings)
            menu.add(0, MENU_ABOUT, 3, R.string.about_menu)
            menu.add(0, MENU_CLEAR_HISTORY, 4, R.string.clear_history)
            menu.add(0, MENU_SEARCH_MESSAGES, 5, R.string.menu_search_messages)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_CHANGELOG -> {
                        startActivity(Intent(this@MainActivity, ChangelogActivity::class.java))
                        true
                    }

                    MENU_CHECK_UPDATE -> {
                        checkUpdate()
                        true
                    }

                    MENU_SETTINGS -> {
                        startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                        true
                    }

                    MENU_ABOUT -> {
                        startActivity(Intent(this@MainActivity, AboutActivity::class.java))
                        true
                    }

                    MENU_CLEAR_HISTORY -> {
                        confirmClearHistory()
                        true
                    }

                    MENU_SEARCH_MESSAGES -> {
                        searchLauncher.launch(
                            Intent(this@MainActivity, MessageSearchActivity::class.java),
                        )
                        true
                    }

                    else -> false
                }
            }
        }.show()
    }

    private fun checkUpdate() {
        CompatToast.show(this, R.string.update_checking)
        ioExecutor.execute {
            val result = runCatching { RemoteConfigRepository.load(this) }
            runOnUiThread {
                result
                    .onSuccess { config ->
                        if (config.versionCode > currentVersionCode()) {
                            AlertDialog.Builder(this)
                                .setTitle(
                                    getString(R.string.update_found_title, config.versionName),
                                )
                                .setMessage(
                                    getString(
                                        R.string.update_found_message,
                                        currentVersionName(),
                                        config.versionName,
                                    ),
                                )
                                .setPositiveButton(R.string.update_download) { _, _ ->
                                    openUrl(config.downloadUrl)
                                }
                                .setNegativeButton(R.string.update_later, null)
                                .show()
                        } else {
                            CompatToast.show(
                                this,
                                getString(R.string.update_latest, currentVersionName()),
                                android.widget.Toast.LENGTH_LONG,
                            )
                        }
                    }
                    .onFailure { error ->
                        CompatToast.show(
                            this,
                            getString(
                                R.string.update_check_failed,
                                humanize(error.message.orEmpty().ifBlank { "网络异常" }),
                            ),
                            android.widget.Toast.LENGTH_LONG,
                        )
                    }
            }
        }
    }

    private fun currentVersionName(): String {
        return runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        }.getOrDefault("1.0").ifBlank { "1.0" }
    }

    private fun currentVersionCode(): Int {
        return runCatching {
            val info = packageManager.getPackageInfo(packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                info.versionCode
            }
        }.getOrDefault(1)
    }

    private fun openUrl(url: String) {
        if (url.isBlank()) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            CompatToast.show(this, url, android.widget.Toast.LENGTH_LONG)
        }
    }

    private fun confirmClearHistory() {
        AlertDialog.Builder(this)
            .setTitle(R.string.clear_history_title)
            .setMessage(R.string.clear_history_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.clear) { _, _ ->
                stopGeneration(showNotice = false)
                repository.clearAll()
                binding.searchInput.text?.clear()
                showNewConversation()
                binding.drawerLayout.closeDrawer(GravityCompat.START)
            }
            .show()
    }

    // ---------------- 消息操作栏 ----------------

    /** 回答底部的复制 / 点赞 / 点踩。 */
    private fun handleMessageAction(action: MessageAction, message: ChatMessage) {
        when (action) {
            MessageAction.COPY -> copyMessage(message)
            MessageAction.LIKE -> {
                if (messageAdapter.toggleLike(message.id)) {
                    CompatToast.show(this, R.string.action_like_done)
                }
            }
            MessageAction.DISLIKE -> {
                if (messageAdapter.toggleDislike(message.id)) {
                    CompatToast.show(this, R.string.action_dislike_done)
                }
            }
        }
    }

    /** 长按消息气泡：复制 / 重新生成 / 删除。 */
    private fun handleMessageLongPress(message: ChatMessage, anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, MENU_COPY_ITEM, 0, R.string.action_copy)
            menu.add(0, MENU_REGENERATE_ITEM, 1, R.string.action_regenerate)
            menu.add(0, MENU_DELETE_ITEM, 2, R.string.action_delete)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_COPY_ITEM -> {
                        copyMessage(message)
                        true
                    }

                    MENU_REGENERATE_ITEM -> {
                        regenerateFrom(message)
                        true
                    }

                    MENU_DELETE_ITEM -> {
                        confirmDeleteMessage(message)
                        true
                    }

                    else -> false
                }
            }
        }.show()
    }

    /**
     * 重新生成：以该消息对应的提问为基准，删掉其后的全部内容并重新请求。
     *
     * 长按用户消息 → 以该用户消息为基准；长按助手消息 → 回溯到它之前的最后一条用户提问。
     */
    private fun regenerateFrom(message: ChatMessage) {
        if (isGenerating()) {
            stopGeneration(showNotice = false)
        }
        val conversationId = message.conversationId
        val messages = repository.getMessages(conversationId)
        val anchorId = if (message.role == MessageRole.USER) {
            message.id
        } else {
            messages.lastOrNull {
                it.role == MessageRole.USER && it.id < message.id
            }?.id ?: run {
                CompatToast.show(this, R.string.regenerate_no_user_message)
                return
            }
        }
        if (!hasUsableNetwork()) {
            CompatToast.show(
                this,
                R.string.network_unavailable,
                Toast.LENGTH_LONG,
            )
            return
        }
        repository.deleteMessagesAfter(anchorId)
        currentConversationId = conversationId
        followStreaming = true
        pendingStreamingState = null
        pendingMessageReloadConversationId = null
        loadMessages(conversationId)
        dispatchChatRequest(conversationId)
        CompatToast.show(this, R.string.action_regenerate_started)
    }

    private fun confirmDeleteMessage(message: ChatMessage) {
        AlertDialog.Builder(this)
            .setTitle(R.string.confirm_delete_message_title)
            .setMessage(R.string.confirm_delete_message)
            .setPositiveButton(R.string.confirm_delete_positive) { _, _ ->
                performDeleteMessage(message)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun performDeleteMessage(message: ChatMessage) {
        val conversationId = message.conversationId
        repository.deleteMessage(message.id)
        if (currentConversationId == conversationId) {
            loadMessages(conversationId)
        }
        refreshConversationList(binding.searchInput.text?.toString().orEmpty())
        CompatToast.show(this, R.string.action_delete_done)
    }

    private fun copyMessage(message: ChatMessage) {
        if (message.content.isEmpty()) return
        val clipboard = getSystemService<ClipboardManager>() ?: return
        clipboard.setPrimaryClip(
            ClipData.newPlainText(getString(R.string.app_name), message.content),
        )
        // Android 13 起系统会自带「已复制」提示，避免重复弹两条。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            CompatToast.show(this, R.string.action_copy_done)
        }
    }

    // ---------------- 状态与提示 ----------------

    private fun stopGeneration(showNotice: Boolean) {
        if (!isGenerating()) return
        viewModel.cancelGeneration()
        if (showNotice) {
            CompatToast.show(this, R.string.generation_stopped)
        }
    }

    private fun isGenerating(): Boolean {
        return viewModel.generationState.value is GenerationState.Active
    }

    private fun updateComposer(isGenerating: Boolean) {
        binding.messageInput.isEnabled = !isGenerating
        binding.addButton.isEnabled = !isGenerating
        binding.sendButton.setImageResource(
            if (isGenerating) R.drawable.ic_stop else R.drawable.ic_send,
        )
        binding.sendButton.contentDescription = getString(
            if (isGenerating) R.string.stop_generation else R.string.send_message,
        )
        updateSendButton()
    }

    private fun updateSendButton() {
        val isGenerating = isGenerating()
        val hasInput = !binding.messageInput.text.isNullOrBlank() || hasAttachment()
        binding.sendButton.isEnabled = isGenerating || hasInput
    }

    private fun showCompletionNotice(finishReason: BackendFinishReason) {
        when (finishReason) {
            BackendFinishReason.LENGTH -> {
                CompatToast.show(this, R.string.response_truncated, android.widget.Toast.LENGTH_LONG)
            }

            BackendFinishReason.CONTENT_FILTER -> {
                CompatToast.show(this, R.string.response_filtered, android.widget.Toast.LENGTH_LONG)
            }

            BackendFinishReason.STOP -> Unit
        }
    }

    private fun showBackendError(type: BackendErrorType, message: String) {
        // 技术性报错统一转成人能看懂的提示。
        CompatToast.show(this, humanize(message), android.widget.Toast.LENGTH_LONG)
    }

    private fun humanize(message: String): String = HumanizeError.humanize(message)

    private fun showCompatibilityNotice(@StringRes messageRes: Int) {
        CompatToast.show(this, messageRes)
    }

    private fun hideKeyboard() {
        currentFocus?.let { focusedView ->
            getSystemService<InputMethodManager>()
                ?.hideSoftInputFromWindow(focusedView.windowToken, 0)
            focusedView.clearFocus()
        }
    }

    @Suppress("DEPRECATION")
    private fun hasUsableNetwork(): Boolean {
        val connectivityManager = getSystemService<ConnectivityManager>() ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return connectivityManager.activeNetworkInfo?.isConnected == true
        }

        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun getColorCompat(colorRes: Int): Int {
        return ContextCompat.getColor(this, colorRes)
    }

    companion object {
        private const val STATE_CONVERSATION_ID = "state_conversation_id"
        private const val NO_CONVERSATION_ID = -1L
        private const val MENU_CHANGELOG = 1
        private const val MENU_CHECK_UPDATE = 2
        private const val MENU_SETTINGS = 5
        private const val MENU_ABOUT = 3
        private const val MENU_CLEAR_HISTORY = 4
        private const val MENU_COPY_ITEM = 100
        private const val MENU_REGENERATE_ITEM = 101
        private const val MENU_DELETE_ITEM = 102
        private const val MENU_CONV_PIN = 200
        private const val MENU_CONV_RENAME = 201
        private const val MENU_CONV_DELETE = 202
        private const val MENU_SEARCH_MESSAGES = 6
        private const val STREAM_THINKING_ID = -1L
        private const val STREAM_ASSISTANT_ID = -2L

        /** 全局记忆最多携带的历史消息条数（调用预算保护）。 */
        private const val GLOBAL_MEMORY_MAX_MESSAGES = 60
    }
}
