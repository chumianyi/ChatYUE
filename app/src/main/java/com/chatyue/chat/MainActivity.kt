package com.chatyue.chat

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.PopupMenu
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
import com.chatyue.chat.backend.ChatRequest
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

        restoreConversation(savedInstanceState)
        refreshConversationList()
        initialized = true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(
            STATE_CONVERSATION_ID,
            currentConversationId ?: NO_CONVERSATION_ID,
        )
    }

    override fun onDestroy() {
        // initialized 为 false 说明本次 onCreate 直接跳去了协议页，此时还没创建 ViewModel。
        if (initialized && !isChangingConfigurations) {
            viewModel.cancelGeneration()
        }
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
        messageAdapter = ChatMessageAdapter(Markwon.create(this))
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

        conversationAdapter = ConversationAdapter(::selectConversation)
        binding.conversationList.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = conversationAdapter
            setHasFixedSize(true)
        }
    }

    private fun configureComposer() {
        // 思考模式固定开启，不可切换，这里只做视觉标识。
        binding.thinkingModeLabel.isSelected = true
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
            showNewConversation()
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
        viewModel.sendMessage(ChatRequest(conversationId.toString(), messages))
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
        )
    }

    private fun showMessages(
        messages: List<ChatMessage>,
        scrollToEnd: Boolean = true,
        guardScrollForStreaming: Boolean = false,
    ) {
        val hasMessages = messages.isNotEmpty()
        binding.welcomeContainer.visibility = if (hasMessages) View.GONE else View.VISIBLE
        binding.messageList.visibility = if (hasMessages) View.VISIBLE else View.GONE
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
        val conversations = repository.getConversations(query)
        val items = mutableListOf<DrawerItem>()
        var previousSection: ConversationSection? = null
        conversations.forEach { conversation ->
            val section = ConversationFormatter.sectionFor(conversation.updatedAt)
            if (section != previousSection) {
                items += DrawerItem.Section(section)
                previousSection = section
            }
            items += DrawerItem.Entry(conversation)
        }
        conversationAdapter.submitItems(items, currentConversationId)
    }

    // ---------------- 菜单 ----------------

    private fun showMoreMenu() {
        PopupMenu(this, binding.moreButton).apply {
            menu.add(0, MENU_CHANGELOG, 0, R.string.menu_changelog)
            menu.add(0, MENU_CHECK_UPDATE, 1, R.string.menu_check_update)
            menu.add(0, MENU_ABOUT, 2, R.string.about_menu)
            menu.add(0, MENU_CLEAR_HISTORY, 3, R.string.clear_history)
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

                    MENU_ABOUT -> {
                        startActivity(Intent(this@MainActivity, AboutActivity::class.java))
                        true
                    }

                    MENU_CLEAR_HISTORY -> {
                        confirmClearHistory()
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
        private const val MENU_ABOUT = 3
        private const val MENU_CLEAR_HISTORY = 4
        private const val STREAM_THINKING_ID = -1L
        private const val STREAM_ASSISTANT_ID = -2L
    }
}
