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
import android.speech.RecognizerIntent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.Toast
import java.io.File
import java.util.Locale
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
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
import com.chatyue.chat.databinding.DialogContextPanelBinding
import com.chatyue.chat.databinding.ItemAttachmentThumbBinding
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
    private lateinit var draftStore: DraftStore

    /** 启动消息搜索页，返回用户选中的会话并打开，可选定位到命中的消息。 */
    private val searchLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val id = result.data
                ?.getLongExtra(MessageSearchActivity.EXTRA_OPEN_CONVERSATION, -1L)
                ?: -1L
            val messageId = result.data
                ?.getLongExtra(MessageSearchActivity.EXTRA_OPEN_MESSAGE, -1L)
                ?: -1L
            if (id > 0) openConversationById(id, messageId.takeIf { it > 0 })
        }
    }

    /** 打开快捷指令库，返回用户选中的指令正文并填入输入框。 */
    private val promptLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val content = result.data
            ?.getStringExtra(PromptLibraryActivity.EXTRA_PROMPT_CONTENT)
            .orEmpty()
        if (content.isBlank()) return@registerForActivityResult
        val title = result.data
            ?.getStringExtra(PromptLibraryActivity.EXTRA_PROMPT_TITLE)
            .orEmpty()
        insertPromptContent(content)
        showKeyboard(binding.messageInput)
        CompatToast.show(this, getString(R.string.prompt_inserted, title))
    }

    /**
     * 启动系统语音识别，把识别出的文字追加到输入框。
     *
     * 走系统的 `ACTION_RECOGNIZE_SPEECH`，不需要录音权限；
     * 追加而非覆盖，避免冲掉输入框里已经写好的内容。
     */
    private val speechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val spoken = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            .orEmpty()
        if (spoken.isNotBlank()) insertPromptContent(spoken)
    }

    /** onCreate 可能因「未同意协议」提前返回，用它避免 onDestroy 访问未初始化的字段。 */
    private var initialized = false

    private var currentConversationId: Long? = null
    private var followStreaming = true
    private var isMessageListScrolling = false
    private var pendingStreamingState: GenerationState.Active? = null
    private var pendingMessageReloadConversationId: Long? = null
    private var pendingMessageListScroll: Runnable? = null

    /** 「回到底部」未读计数：逻辑抽成纯类，便于单测。 */
    private val bottomArrivals = BottomArrivalCounter()

    /** 「上下文管理」面板：压缩事件到来时就地刷新。 */
    private var contextPanelDialog: AlertDialog? = null
    private var contextPanelBinding: DialogContextPanelBinding? = null
    private var contextPanelConversationId: Long? = null
    private var contextPanelCompressing = false

    /**
     * 待发送的图片附件，按用户选择顺序排列。
     * 上限为 [AttachmentBlockBuilder.MAX_IMAGE_ATTACHMENTS] 张。
     */
    private val pendingImages = mutableListOf<PendingAttachment>()
    private var pendingTextName: String? = null
    private var pendingTextContent: String? = null

    /** 新会话的默认记忆模式（2.0：新建会话时由用户选择，默认全局）。 */
    private var pendingMemoryMode: ConversationMemoryMode = ConversationMemoryMode.GLOBAL

    /** 待发送消息引用的目标消息；null 表示不引用。 */
    private var pendingReplyTarget: ChatMessage? = null

    /** 消息多选模式是否激活（顶栏显示多选操作栏）。 */
    private var messageSelectionActive = false

    /** 消息朗读（TTS）与当前正在朗读的消息 id。 */
    private var messageSpeaker: MessageSpeaker? = null
    private var speakingMessageId: Long? = null

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
        // 2.0 多选协议：Uri 列表 + 文件名列表，按选择顺序对应。
        val uris = data.getParcelableArrayListExtra<Uri>(
            ImageGalleryActivity.EXTRA_IMAGE_URIS,
        )
        val names = data.getStringArrayListExtra(ImageGalleryActivity.EXTRA_IMAGE_NAMES)
        if (!uris.isNullOrEmpty()) {
            uris.forEachIndexed { index, uri ->
                addImageAttachment(uri, names?.getOrNull(index).orEmpty())
            }
            return@registerForActivityResult
        }
        // 兼容旧协议（单图字段）。
        val uri = data.data ?: return@registerForActivityResult
        addImageAttachment(
            uri,
            data.getStringExtra(ImageGalleryActivity.EXTRA_IMAGE_NAME).orEmpty(),
        )
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
        draftStore = DraftStore(this)

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

        // 朗读引擎跟随主界面生命周期，退出时统一释放。
        messageSpeaker = MessageSpeaker(this, AppConfig.speechRate(this).rate)
    }

    override fun onResume() {
        super.onResume()
        // 从设置页回来时同步朗读语速，让新档位立即生效。
        messageSpeaker?.updateSpeechRate(AppConfig.speechRate(this).rate)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(
            STATE_CONVERSATION_ID,
            currentConversationId ?: NO_CONVERSATION_ID,
        )
        // 引用目标只存 id，恢复时随会话消息一起从库里找回。
        outState.putLong(
            STATE_REPLY_TO_ID,
            pendingReplyTarget?.id ?: NO_REPLY_TO_ID,
        )
    }

    override fun onStart() {
        super.onStart()
        // 2.0：每次回到前台都静默补检一次版本。
        versionGuard?.start()
    }

    override fun onPause() {
        super.onPause()
        // 退到后台前把未发送输入存进草稿箱，防止进程被回收后丢内容。
        if (initialized) saveCurrentDraft()
    }

    override fun onDestroy() {
        // initialized 为 false 说明本次 onCreate 直接跳去了协议页，此时还没创建 ViewModel。
        if (initialized && !isChangingConfigurations) {
            viewModel.cancelGeneration()
        }
        stopSpeaking(silent = true)
        messageSpeaker?.release()
        messageSpeaker = null
        contextPanelDialog?.dismiss()
        contextPanelDialog = null
        contextPanelBinding = null
        versionGuard?.dismiss()
        versionGuard = null
        ioExecutor.shutdownNow()
        if (initialized) {
            binding.messageList.removeCallbacks(clearHighlightRunnable)
        }
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
        messageAdapter = ChatMessageAdapter(
            Markwon.create(this),
            ::handleMessageAction,
            ::handleMessageLongPress,
            { count ->
                updateSelectionTitle(count)
            },
            // 点击消息里的引用块：滚动定位到被引用的消息并短暂高亮。
            onReplyQuoteClick = ::jumpToRepliedMessage,
        )
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
                            // 回到底部：未读提示清零，补上被跳过的增量。
                            bottomArrivals.clearOnReturnToBottom()
                            applyPendingMessageUpdate()
                        }
                        updateJumpToBottom()
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
                    updateJumpToBottom()
                }
            })
        }

        binding.jumpToBottomBar.setOnClickListener {
            followStreaming = true
            bottomArrivals.clearOnReturnToBottom()
            cancelPendingMessageListScroll(binding.messageList)
            applyPendingMessageUpdate()
            scrollMessageListToBottom()
            // 先隐藏按钮：滚动到位前 canScrollVertically 仍旧为 true，避免闪烁。
            updateJumpToBottom(forceVisible = false)
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
        // 顶栏上下文 chip 可点击，打开「上下文管理」面板。
        binding.contextPercent.setOnClickListener { showContextPanel() }
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
        binding.promptButton.setOnClickListener {
            promptLauncher.launch(Intent(this, PromptLibraryActivity::class.java))
        }
        binding.voiceButton.setOnClickListener {
            startVoiceInput()
        }
        binding.moreButton.setOnClickListener {
            showMoreMenu()
        }
        binding.replyBarClose.setOnClickListener {
            cancelReply()
        }
        binding.selectionClose.setOnClickListener {
            exitMessageSelection()
        }
        binding.selectionSelectAll.setOnClickListener {
            messageAdapter.selectAllSelectable()
        }
        binding.selectionCopy.setOnClickListener {
            copySelectedMessages()
        }
        binding.selectionShare.setOnClickListener {
            shareSelectedMessages()
        }
        binding.selectionDelete.setOnClickListener {
            confirmSelectionDelete()
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
                    when {
                        // 多选模式下返回键先退出多选，不直接退出应用。
                        messageSelectionActive -> exitMessageSelection()
                        binding.drawerLayout.isDrawerOpen(GravityCompat.START) ->
                            binding.drawerLayout.closeDrawer(GravityCompat.START)

                        else -> finish()
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

    /**
     * 追加一张图片附件：去重、限流，随后重渲染缩略图行与汇总文案。
     *
     * 模型只识别文本，提示用户图片将以附件说明的形式发送。
     */
    private fun addImageAttachment(uri: Uri, name: String) {
        if (pendingImages.any { it.uri == uri }) {
            CompatToast.show(this, R.string.attachment_image_duplicate)
            return
        }
        if (pendingImages.size >= AttachmentBlockBuilder.MAX_IMAGE_ATTACHMENTS) {
            CompatToast.show(
                this,
                getString(
                    R.string.attachment_image_limit,
                    AttachmentBlockBuilder.MAX_IMAGE_ATTACHMENTS,
                ),
                android.widget.Toast.LENGTH_LONG,
            )
            return
        }
        val displayName = name.ifBlank { getString(R.string.attachment_image_label) }
        pendingImages += PendingAttachment(uri, displayName)
        renderImageAttachments()
        CompatToast.show(this, R.string.image_text_only_notice, android.widget.Toast.LENGTH_LONG)
        updateSendButton()
    }

    /** 把待发送的图片渲染成输入区缩略图行（每张可单独移除）。 */
    private fun renderImageAttachments() {
        binding.attachmentThumbRow.removeAllViews()
        if (pendingImages.isEmpty()) {
            binding.attachmentThumbRow.visibility = View.GONE
            binding.attachmentBar.visibility =
                if (hasAttachment()) View.VISIBLE else View.GONE
            return
        }
        pendingImages.forEachIndexed { index, attachment ->
            val itemBinding = ItemAttachmentThumbBinding.inflate(
                layoutInflater,
                binding.attachmentThumbRow,
                false,
            )
            itemBinding.attachmentThumbRemove.tag = index
            itemBinding.attachmentThumbRemove.setOnClickListener { view ->
                pendingImages.removeAt(view.tag as Int)
                renderImageAttachments()
                updateSendButton()
            }
            com.chatyue.chat.attachment.ImageLoader.load(
                context = this,
                uri = attachment.uri,
                targetSizePx = dpToPx(72),
                imageView = itemBinding.attachmentThumbImage,
            )
            binding.attachmentThumbRow.addView(itemBinding.root)
        }
        binding.attachmentThumbRow.visibility = View.VISIBLE
        binding.attachmentBar.visibility = View.VISIBLE
    }

    /** 附件条汇总文案：多图显示张数，单图显示文件名，文本附件附字数。 */
    private fun updateAttachmentSummary() {
        val parts = mutableListOf<String>()
        if (pendingImages.isNotEmpty()) {
            parts += if (pendingImages.size == 1) {
                getString(R.string.attachment_image_label) + " · " + pendingImages.first().name
            } else {
                getString(R.string.attachment_image_many, pendingImages.size)
            }
        }
        pendingTextName?.takeIf { pendingTextContent != null }?.let { textName ->
            parts += getString(R.string.attachment_text_label) + " · " + textName +
                "（" + (pendingTextContent?.length ?: 0) + " 字）"
        }
        binding.attachmentName.text = parts.joinToString(" · ")
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
                        updateAttachmentSummary()
                        binding.attachmentBar.visibility = View.VISIBLE
                        updateSendButton()
                    }
                }
            }
        }
    }

    private fun clearAttachments() {
        pendingImages.clear()
        pendingTextName = null
        pendingTextContent = null
        binding.attachmentThumbRow.removeAllViews()
        binding.attachmentThumbRow.visibility = View.GONE
        binding.attachmentBar.visibility = View.GONE
        updateSendButton()
    }

    private fun hasAttachment(): Boolean =
        pendingImages.isNotEmpty() || pendingTextContent != null

    /** 把附件内容拼进用户消息：图片只做说明（多图列出各文件名），txt 内容直接内联给模型读取。 */
    private fun buildAttachmentBlock(): String =
        AttachmentBlockBuilder.build(
            imageNames = pendingImages.map { it.name },
            textName = pendingTextName,
            textContent = pendingTextContent,
        )

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
                    refreshContextPanel()
                    viewModel.acknowledgeContextEvent()
                }

                is ContextEvent.CompressionFailed -> {
                    CompatToast.show(
                        this,
                        getString(R.string.context_compress_failed, humanize(event.message)),
                        android.widget.Toast.LENGTH_LONG,
                    )
                    refreshContextPanel()
                    viewModel.acknowledgeContextEvent()
                }
            }
        }
    }

    // ---------------- 草稿箱 ----------------

    /** 把当前输入框内容存为当前会话的草稿（空内容即清键）。 */
    private fun saveCurrentDraft() {
        if (!initialized) return
        draftStore.save(
            currentConversationId,
            binding.messageInput.text?.toString().orEmpty(),
        )
    }

    /** 恢复当前会话的草稿到输入框，光标落到末尾。 */
    private fun restoreDraft() {
        val draft = draftStore.load(currentConversationId)
        binding.messageInput.setText(draft)
        binding.messageInput.setSelection(draft.length)
        updateSendButton()
    }

    // ---------------- 快捷指令 ----------------

    /**
     * 把从指令库选中的正文接进输入框：已有内容时用换行衔接，
     * 光标移到末尾并让发送按钮重新评估可用性。
     */
    private fun insertPromptContent(content: String) {
        val edit = binding.messageInput
        val existing = edit.text?.toString().orEmpty()
        val needsSeparator = existing.isNotBlank() && !existing.endsWith("\n")
        val merged = if (needsSeparator) "$existing\n$content" else existing + content
        edit.setText(merged)
        edit.setSelection(merged.length)
        updateSendButton()
    }

    /**
     * 调起系统语音识别面板（自由格式、跟随系统语言）。
     *
     * 部分机型没有可用的识别器：启动前先探测，冷启动也兜住
     * `ActivityNotFoundException`，两种情况都只提示不崩溃。
     */
    private fun startVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_input_prompt))
        }
        if (intent.resolveActivity(packageManager) == null) {
            CompatToast.show(this, R.string.voice_input_unavailable)
            return
        }
        try {
            speechLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            CompatToast.show(this, R.string.voice_input_unavailable)
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

        val replyTarget = pendingReplyTarget
        repository.addMessage(
            conversationId = conversationId,
            role = MessageRole.USER,
            content = userContent,
            repliedToId = replyTarget?.id,
            repliedRole = replyTarget?.role,
            repliedPreview = replyTarget?.let { ReplyFormatter.preview(it.content) },
        )
        if (wasNewConversation) {
            repository.updateConversationTitle(
                conversationId,
                ConversationFormatter.titleFrom(userContent),
            )
        }
        binding.messageInput.text?.clear()
        clearAttachments()
        clearReply()
        // 草稿已转为正式消息，清掉该会话的草稿键。
        draftStore.clear(conversationId)
        followStreaming = true
        pendingStreamingState = null
        pendingMessageReloadConversationId = null
        bottomArrivals.reset()
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
            restoreReplyTarget(savedInstanceState)
            // 回到上次会话时把草稿放回输入框（旋转、杀进程重进都适用）。
            restoreDraft()
        }
    }

    /** 旋转屏幕后恢复「引用回复」目标：只带了 id，回库里按 id 找回消息。 */
    private fun restoreReplyTarget(savedInstanceState: Bundle?) {
        val replyToId = savedInstanceState
            ?.getLong(STATE_REPLY_TO_ID, NO_REPLY_TO_ID)
            ?.takeIf { it != NO_REPLY_TO_ID }
            ?: return
        val conversationId = currentConversationId ?: return
        pendingReplyTarget = repository.getMessages(conversationId)
            .firstOrNull { it.id == replyToId }
        updateReplyBar()
    }

    private fun showNewConversation() {
        // 先把旧会话的未发送输入存成草稿，再切到新会话。
        saveCurrentDraft()
        stopGeneration(showNotice = false)
        currentConversationId = null
        followStreaming = true
        pendingStreamingState = null
        pendingMessageReloadConversationId = null
        bottomArrivals.reset()
        binding.chatTitle.setText(R.string.new_chat)
        binding.messageInput.text?.clear()
        clearAttachments()
        clearReply()
        exitMessageSelection()
        restoreDraft()
        showMessages(emptyList())
        hideKeyboard()
        refreshConversationList(binding.searchInput.text?.toString().orEmpty())
        stopSpeaking(silent = true)
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

    private fun openConversationById(conversationId: Long, locateMessageId: Long? = null) {
        val conversation = repository.getConversation(conversationId) ?: return
        selectConversation(conversation)
        binding.drawerLayout.closeDrawer(GravityCompat.START)
        // 从搜索结果进来时，等消息列表布局稳定后定位到命中的那条消息。
        if (locateMessageId != null) locateMessageInCurrentConversation(locateMessageId)
    }

    /**
     * 在刚打开的会话里定位到指定消息：滚动过去并短暂高亮。
     *
     * 与点击引用块的定位共用一套高亮通道；消息可能已被删除，此时只提示不滚动。
     */
    private fun locateMessageInCurrentConversation(messageId: Long) {
        binding.messageList.post {
            val position = messageAdapter.findRowPositionOfMessage(messageId)
            if (position < 0) {
                CompatToast.show(this, R.string.reply_jump_missing)
                return@post
            }
            binding.messageList.removeCallbacks(clearHighlightRunnable)
            binding.messageList.smoothScrollToPosition(position)
            messageAdapter.setHighlightMessageId(messageId)
            binding.messageList.postDelayed(clearHighlightRunnable, HIGHLIGHT_MILLIS)
            CompatToast.show(this, R.string.message_search_located)
        }
    }

    private fun selectConversation(conversation: Conversation) {
        // 切走前保存旧会话草稿，进入后恢复新会话草稿。
        saveCurrentDraft()
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
        clearReply()
        exitMessageSelection()
        restoreDraft()
        stopSpeaking(silent = true)
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
        trackAwayArrivals(messages)
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
     * 未读消息追踪：用户不在底部时，把新到达的消息计入未读计数，
     * 「回到底部」按钮随之显示条数；待在底部或列表为空时清零。
     */
    private fun trackAwayArrivals(messages: List<ChatMessage>) {
        val atBottom = followStreaming
        bottomArrivals.onRender(messages.lastOrNull()?.id, atBottom)
        updateJumpToBottom(forceVisible = if (atBottom) false else true)
    }

    /**
     * 刷新「回到底部」按钮：离开底部出现，带回未读条数；回到底部或没有消息时隐藏。
     *
     * @param forceVisible 非空时直接指定显隐，用于消息到达等视图尚未布局完成的时刻。
     */
    private fun updateJumpToBottom(forceVisible: Boolean? = null) {
        if (binding.messageList.childCount == 0 && messageAdapter.itemCount == 0) {
            binding.jumpToBottomBar.visibility = View.GONE
            binding.jumpToBottomCount.visibility = View.GONE
            return
        }
        val visible = forceVisible
            ?: (binding.messageList.childCount > 0 && binding.messageList.canScrollVertically(1))
        val unread = bottomArrivals.unreadCount
        if (visible && unread > 0) {
            binding.jumpToBottomBar.contentDescription =
                getString(R.string.jump_to_bottom_unread, unread)
            binding.jumpToBottomCount.visibility = View.VISIBLE
            binding.jumpToBottomCount.text =
                getString(R.string.jump_to_bottom_unread, unread)
        } else {
            binding.jumpToBottomBar.contentDescription = getString(R.string.jump_to_bottom)
            binding.jumpToBottomCount.visibility = View.GONE
        }
        binding.jumpToBottomBar.visibility = if (visible) View.VISIBLE else View.GONE
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

    /**
     * 顶栏上下文 chip 点击：打开「上下文管理」面板。
     *
     * 展示当前会话的 token 用量与消息构成，支持在用量达到阈值前主动压缩历史。
     */
    private fun showContextPanel() {
        val conversationId = currentConversationId
        if (conversationId == null) {
            CompatToast.show(this, R.string.context_panel_no_conversation)
            return
        }
        val panelBinding = DialogContextPanelBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setView(panelBinding.root)
            .create()
        contextPanelDialog?.dismiss()
        contextPanelDialog = dialog
        contextPanelBinding = panelBinding
        contextPanelConversationId = conversationId
        contextPanelCompressing = false

        panelBinding.contextCompressButton.setOnClickListener {
            contextPanelCompressing = true
            updateContextPanelContent(panelBinding, conversationId)
            viewModel.compressContextNow(conversationId)
        }
        panelBinding.contextPanelClose.setOnClickListener { dialog.dismiss() }
        updateContextPanelContent(panelBinding, conversationId)
        dialog.show()
    }

    /** 压缩完成后就地刷新「上下文管理」面板（若还开着且仍是同一会话）。 */
    private fun refreshContextPanel() {
        val dialog = contextPanelDialog ?: return
        if (!dialog.isShowing) return
        val conversationId = currentConversationId ?: return
        if (conversationId != contextPanelConversationId) return
        val binding = contextPanelBinding ?: return
        contextPanelCompressing = false
        updateContextPanelContent(binding, conversationId)
    }

    /** 把当前会话的上下文用量快照填进面板控件。 */
    private fun updateContextPanelContent(
        panelBinding: DialogContextPanelBinding,
        conversationId: Long,
    ) {
        val snapshot = ContextBudget.snapshot(repository.getMessages(conversationId))
        panelBinding.contextUsagePercent.text = getString(R.string.context_usage, snapshot.percent)
        panelBinding.contextUsageBar.progress = snapshot.percent
        panelBinding.contextUsageTokens.text = getString(
            R.string.context_panel_used_tokens,
            formatContextTokens(snapshot.tokens),
            formatContextTokens(ContextBudget.MAX_TOKENS),
            formatContextTokens(snapshot.remainingTokens),
        )
        panelBinding.contextMessageCount.text = snapshot.messageCount.toString()
        panelBinding.contextSummaryCount.text = snapshot.summaryCount.toString()
        panelBinding.contextCharacterCount.text = formatCharacterCount(snapshot.characterCount)
        panelBinding.contextPanelHint.text = getString(
            if (snapshot.atCompressionThreshold) {
                R.string.context_panel_hint_threshold
            } else {
                R.string.context_panel_auto_compress
            },
        )
        val compressible = snapshot.messageCount >= ChatViewModel.MIN_MESSAGES_TO_COMPRESS
        panelBinding.contextCompressButton.isEnabled =
            !contextPanelCompressing && compressible
        panelBinding.contextCompressButton.text = getString(
            if (contextPanelCompressing) {
                R.string.context_panel_compressing
            } else {
                R.string.context_panel_compress_now
            },
        )
    }

    /** token 数格式化为「万」口径，一万以下按整数。 */
    private fun formatContextTokens(tokens: Double): String {
        return if (tokens >= 10_000.0) {
            String.format("%.1f 万", tokens / 10_000.0)
        } else {
            String.format("%.0f", tokens)
        }
    }

    /** 字符数格式化为「万」口径。 */
    private fun formatCharacterCount(count: Int): String {
        return if (count >= 10_000) {
            String.format("%.1f 万", count / 10_000.0)
        } else {
            count.toString()
        }
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
            menu.add(0, MENU_CONV_EXPORT, 2, R.string.action_export_conversation)
            menu.add(0, MENU_CONV_DELETE, 3, R.string.action_delete_conversation)
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

                    MENU_CONV_EXPORT -> {
                        exportConversation(conversation.id)
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
                // 删除会话后其草稿一并清掉，避免磁盘里残留无效键。
                draftStore.clear(conversation.id)
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
            menu.add(0, MENU_STATS, 6, R.string.menu_stats)
            menu.add(0, MENU_EXPORT_CONVERSATION, 7, R.string.menu_export_conversation)
            menu.add(0, MENU_PROMPT_LIBRARY, 8, R.string.menu_prompt_library)
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

                    MENU_STATS -> {
                        startActivity(Intent(this@MainActivity, StatsActivity::class.java))
                        true
                    }

                    MENU_EXPORT_CONVERSATION -> {
                        exportCurrentConversation()
                        true
                    }

                    MENU_PROMPT_LIBRARY -> {
                        promptLauncher.launch(
                            Intent(this@MainActivity, PromptLibraryActivity::class.java),
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
                draftStore.clearAll()
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
            MessageAction.SPEAK -> toggleSpeak(message)
        }
    }

    // ---------------- 消息朗读 ----------------

    /** 点击朗读按钮：正在朗读该条则停止，否则清洗 Markdown 后朗读。 */
    private fun toggleSpeak(message: ChatMessage) {
        if (speakingMessageId == message.id) {
            stopSpeaking()
            return
        }
        val speechText = MarkdownSpeechStripper.toSpeechText(message.content)
        if (speechText.isBlank()) {
            CompatToast.show(this, R.string.speak_empty)
            return
        }
        val speaker = messageSpeaker
        if (speaker == null) {
            CompatToast.show(this, R.string.speak_unsupported)
            return
        }
        speakingMessageId = message.id
        messageAdapter.setSpeakingMessageId(message.id)
        speaker.speak(speechText)
        CompatToast.show(this, R.string.speak_started)
    }

    /** 停止朗读并复位图标；[silent] 用于生命周期或切页等无需提示的场景。 */
    private fun stopSpeaking(silent: Boolean = false) {
        if (speakingMessageId == null) return
        speakingMessageId = null
        messageSpeaker?.stop()
        messageAdapter.setSpeakingMessageId(null)
        if (!silent) {
            CompatToast.show(this, R.string.speak_stopped)
        }
    }

    /** 长按消息气泡：复制 / 引用回复 / 编辑 / 多选 / 重新生成 / 分享 / 删除。 */
    private fun handleMessageLongPress(message: ChatMessage, anchor: View) {
        // 多选模式下长按不再弹菜单，避免两种交互打架。
        if (messageAdapter.isSelectionMode()) return
        PopupMenu(this, anchor).apply {
            menu.add(0, MENU_COPY_ITEM, 0, R.string.action_copy)
            menu.add(0, MENU_REPLY_ITEM, 1, R.string.action_reply)
            // 编辑只对用户提问有意义：改自己的话，或改完让它重新回答。
            if (message.role == MessageRole.USER) {
                menu.add(0, MENU_EDIT_ITEM, 2, R.string.action_edit)
            }
            menu.add(0, MENU_MULTI_SELECT_ITEM, 3, R.string.action_multi_select)
            menu.add(0, MENU_REGENERATE_ITEM, 4, R.string.action_regenerate)
            menu.add(0, MENU_SHARE_ITEM, 5, R.string.action_share)
            menu.add(0, MENU_DELETE_ITEM, 6, R.string.action_delete)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_COPY_ITEM -> {
                        copyMessage(message)
                        true
                    }

                    MENU_REPLY_ITEM -> {
                        startReplyTo(message)
                        true
                    }

                    MENU_EDIT_ITEM -> {
                        showEditMessageDialog(message)
                        true
                    }

                    MENU_MULTI_SELECT_ITEM -> {
                        enterMessageSelection(message)
                        true
                    }

                    MENU_REGENERATE_ITEM -> {
                        regenerateFrom(message)
                        true
                    }

                    MENU_SHARE_ITEM -> {
                        shareMessage(message)
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

    // ---------------- 消息多选 ----------------

    /** 进入消息多选：显示顶部操作栏，停用输入区，默认选中触发消息。 */
    private fun enterMessageSelection(message: ChatMessage) {
        if (isGenerating()) {
            CompatToast.show(this, R.string.selection_busy_generating)
            return
        }
        messageSelectionActive = true
        messageAdapter.enterSelectionMode(message.id)
        binding.selectionBar.visibility = View.VISIBLE
        updateSelectionTitle(messageAdapter.getSelectedMessages().size)
        setComposerEnabled(false)
        hideKeyboard()
    }

    /** 退出消息多选：隐藏操作栏，恢复输入区。 */
    private fun exitMessageSelection() {
        if (!messageSelectionActive) return
        messageSelectionActive = false
        messageAdapter.exitSelectionMode()
        binding.selectionBar.visibility = View.GONE
        setComposerEnabled(true)
    }

    private fun updateSelectionTitle(count: Int) {
        binding.selectionTitle.text = getString(R.string.selection_title_count, count)
    }

    /** 多选期间冻结输入区，退出后恢复正常。 */
    private fun setComposerEnabled(enabled: Boolean) {
        binding.messageInput.isEnabled = enabled
        binding.addButton.isEnabled = enabled
        binding.thinkingModeLabel.isEnabled = enabled
        // 语音识别会往输入框里写文字，跟随输入框的可用态。
        binding.voiceButton.isEnabled = enabled
        updateSendButton()
    }

    /** 批量复制选中消息：按「我：/ ChatYUE：」前缀逐条拼接，保持会话阅读顺序。 */
    private fun copySelectedMessages() {
        val selected = messageAdapter.getSelectedMessages()
        if (selected.isEmpty()) {
            CompatToast.show(this, R.string.selection_empty)
            return
        }
        val text = selected.joinToString(separator = "\n\n") { message ->
            val speaker = if (message.role == MessageRole.USER) {
                getString(R.string.role_user)
            } else {
                getString(R.string.role_assistant)
            }
            "$speaker：${message.content}"
        }
        val clipboard = getSystemService<ClipboardManager>() ?: return
        clipboard.setPrimaryClip(
            ClipData.newPlainText(getString(R.string.app_name), text),
        )
        CompatToast.show(this, getString(R.string.selection_copy_done, selected.size))
    }

    /** 长按单条消息分享：带角色前缀与来源署名的纯文本，交给系统分享面板。 */
    private fun shareMessage(message: ChatMessage) {
        if (message.content.isEmpty()) {
            CompatToast.show(this, R.string.share_empty)
            return
        }
        shareText(MessageShareFormatter.format(message))
    }

    /** 多选消息分享：按阅读顺序拼接全部选中消息。 */
    private fun shareSelectedMessages() {
        val selected = messageAdapter.getSelectedMessages()
        if (selected.isEmpty()) {
            CompatToast.show(this, R.string.selection_empty)
            return
        }
        shareText(MessageShareFormatter.formatAll(selected))
    }

    /** 弹出系统分享面板；找不到可分享应用时不崩溃，仅提示。 */
    private fun shareText(text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val chooser = Intent.createChooser(send, getString(R.string.action_share))
        if (chooser.resolveActivity(packageManager) != null) {
            startActivity(chooser)
        } else {
            CompatToast.show(this, R.string.share_no_app)
        }
    }

    private fun confirmSelectionDelete() {
        val count = messageAdapter.getSelectedMessages().size
        if (count == 0) {
            CompatToast.show(this, R.string.selection_empty)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.selection_delete_title)
            .setMessage(getString(R.string.selection_delete_message, count))
            .setPositiveButton(R.string.confirm_delete_positive) { _, _ ->
                performSelectionDelete()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun performSelectionDelete() {
        val selectedIds = messageAdapter.getSelectedMessages().map { it.id }
        if (selectedIds.isEmpty()) return
        val conversationId = currentConversationId
        repository.deleteMessages(selectedIds)
        if (conversationId != null) {
            loadMessages(conversationId)
        }
        refreshConversationList(binding.searchInput.text?.toString().orEmpty())
        exitMessageSelection()
        CompatToast.show(this, getString(R.string.selection_delete_done, selectedIds.size))
    }

    // ---------------- 引用回复 ----------------

    /**
     * 进入引用回复：把目标消息挂到待发送状态，输入区上方显示引用预览条。
     *
     * 再次引用其他消息会直接替换引用目标，不会叠加。
     */
    private fun startReplyTo(message: ChatMessage) {
        if (message.content.isBlank()) {
            CompatToast.show(this, R.string.reply_quote_empty)
            return
        }
        pendingReplyTarget = message
        updateReplyBar()
        // 唤起键盘，让用户直接接着输入回复内容。
        binding.messageInput.requestFocus()
        showKeyboard(binding.messageInput)
    }

    /** 取消引用，隐藏预览条；不影响已输入的正文。 */
    private fun cancelReply() {
        if (pendingReplyTarget == null) return
        pendingReplyTarget = null
        updateReplyBar()
    }

    /** 刷新输入区上方的引用预览条。 */
    private fun updateReplyBar() {
        val target = pendingReplyTarget
        if (target == null) {
            binding.replyBar.visibility = View.GONE
            return
        }
        binding.replyBar.visibility = View.VISIBLE
        val speaker = if (target.role == MessageRole.USER) {
            getString(R.string.role_user)
        } else {
            getString(R.string.role_assistant)
        }
        binding.replyBarTitle.text = getString(R.string.reply_bar_to_user, speaker)
        binding.replyBarPreview.text = ReplyFormatter.preview(target.content)
    }

    /** 切换会话 / 新会话时清掉引用，避免把引用带到别的会话里。 */
    private fun clearReply() {
        pendingReplyTarget = null
        if (initialized) {
            updateReplyBar()
            // 换会话后旧高亮失去意义，连同熄灭计时器一起清掉。
            binding.messageList.removeCallbacks(clearHighlightRunnable)
            messageAdapter.setHighlightMessageId(null)
        }
    }

    // ---------------- 引用块定位 ----------------

    /**
     * 点击消息里的引用块：滚动到被引用的消息并短暂高亮。
     *
     * 被引用消息可能已被删除、或不在当前会话里，此时只提示不滚动。
     */
    private fun jumpToRepliedMessage(message: ChatMessage) {
        val targetId = message.repliedToId
        if (targetId == null) return
        // 多选模式下点击只负责切换选中，不触发跳转。
        if (messageAdapter.isSelectionMode()) return
        val position = messageAdapter.findRowPositionOfMessage(targetId)
        if (position < 0) {
            CompatToast.show(this, R.string.reply_jump_missing)
            return
        }
        // 取消上一次还没熄灭的高亮，避免两条消息同时亮着。
        binding.messageList.removeCallbacks(clearHighlightRunnable)
        binding.messageList.smoothScrollToPosition(position)
        messageAdapter.setHighlightMessageId(targetId)
        binding.messageList.postDelayed(clearHighlightRunnable, HIGHLIGHT_MILLIS)
        CompatToast.show(this, R.string.reply_jump_done)
    }

    /** 高亮熄灭：由主页计时器触发，与点亮走同一条局部刷新通道。 */
    private val clearHighlightRunnable = Runnable {
        messageAdapter.setHighlightMessageId(null)
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
        saveCurrentDraft()
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
        restoreDraft()
        stopSpeaking(silent = true)
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

    /**
     * 编辑用户消息：正文可改，附件块原样保留（用户看不见、也不需要动）。
     *
     * 「仅保存」只更新库里的正文；「保存并重新生成」以编辑后的提问为基准，
     * 删掉这条消息之后的内容并让助手重新回答。
     */
    private fun showEditMessageDialog(message: ChatMessage) {
        if (isGenerating()) {
            CompatToast.show(this, R.string.selection_busy_generating)
            return
        }
        val parts = EditMessageText.split(message.content)
        val input = EditText(this).apply {
            setText(parts.body)
            setSelection(text.length)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 6
            minHeight = (96 * resources.displayMetrics.density).toInt()
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.edit_message_title)
            .setView(input)
            .setPositiveButton(R.string.edit_message_save_only) { _, _ ->
                saveEditedMessage(
                    message = message,
                    parts = parts,
                    editedBody = input.text?.toString().orEmpty(),
                    regenerate = false,
                )
            }
            .setNeutralButton(R.string.edit_message_save_regenerate) { _, _ ->
                saveEditedMessage(
                    message = message,
                    parts = parts,
                    editedBody = input.text?.toString().orEmpty(),
                    regenerate = true,
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun saveEditedMessage(
        message: ChatMessage,
        parts: EditMessageText.Parts,
        editedBody: String,
        regenerate: Boolean,
    ) {
        val newContent = EditMessageText.merge(editedBody, parts.attachmentBlock)
        if (newContent == message.content) {
            CompatToast.show(this, R.string.edit_message_unchanged)
            return
        }
        repository.updateMessageContent(message.id, newContent)
        if (regenerate) {
            // 复用「重新生成」链路：以编辑后的提问为基准删掉后续内容并重新请求。
            regenerateFrom(message.copy(content = newContent))
        } else {
            loadMessages(message.conversationId)
            CompatToast.show(this, R.string.edit_message_saved)
        }
    }

    private fun performDeleteMessage(message: ChatMessage) {
        val conversationId = message.conversationId
        // 删掉的正是正在朗读的消息时，先停掉朗读，避免图标停在失效消息上。
        if (speakingMessageId == message.id) stopSpeaking(silent = true)
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

    // ---------------- 会话导出 ----------------

    /** 导出当前正在查看的会话。 */
    private fun exportCurrentConversation() {
        val conversationId = currentConversationId ?: run {
            CompatToast.show(this, R.string.export_no_conversation)
            return
        }
        exportConversation(conversationId)
    }

    /**
     * 把指定会话渲染成 Markdown，写入缓存后调起系统分享。
     *
     * 落盘走 IO 线程，分享面板回主线程拉起。
     */
    private fun exportConversation(conversationId: Long) {
        val conversation = repository.getConversation(conversationId) ?: return
        val messages = repository.getMessages(conversationId)
        val hasExportable = messages.any {
            it.role == MessageRole.USER ||
                it.role == MessageRole.ASSISTANT ||
                it.role == MessageRole.SYSTEM
        }
        if (!hasExportable) {
            CompatToast.show(this, R.string.export_empty_conversation)
            return
        }
        val markdown = ConversationExporter.toMarkdown(conversation, messages)
        val fileName = ConversationExporter.fileNameFor(conversation, "md")
        ioExecutor.execute {
            val file = ConversationExportWriter.write(this, fileName, markdown)
            runOnUiThread {
                shareExportFile(file, conversation.title)
            }
        }
    }

    /** 通过 FileProvider 把导出稿分享给其他应用。 */
    private fun shareExportFile(file: File, title: String) {
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.export_share_title)))
        } catch (_: ActivityNotFoundException) {
            CompatToast.show(this, R.string.export_no_share_app)
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
        // 多选模式下输入区保持冻结，即使生成状态变化也不解冻。
        val composerEnabled = !isGenerating && !messageSelectionActive
        binding.messageInput.isEnabled = composerEnabled
        binding.addButton.isEnabled = composerEnabled
        binding.sendButton.setImageResource(
            if (isGenerating) R.drawable.ic_stop else R.drawable.ic_send,
        )
        binding.sendButton.contentDescription = getString(
            if (isGenerating) R.string.stop_generation else R.string.send_message,
        )
        updateSendButton()
    }

    private fun updateSendButton() {
        // 多选期间发送键一律禁用，避免误触发送。
        if (messageSelectionActive) {
            binding.sendButton.isEnabled = false
            return
        }
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

    /** 拉起软键盘，用于「引用回复」后直接接着输入。 */
    private fun showKeyboard(view: View) {
        getSystemService<InputMethodManager>()?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
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
        private const val STATE_REPLY_TO_ID = "state_reply_to_id"
        private const val NO_REPLY_TO_ID = -1L
        private const val MENU_CHANGELOG = 1
        private const val MENU_CHECK_UPDATE = 2
        private const val MENU_SETTINGS = 5
        private const val MENU_ABOUT = 3
        private const val MENU_CLEAR_HISTORY = 4
        private const val MENU_COPY_ITEM = 100
        private const val MENU_REGENERATE_ITEM = 101
        private const val MENU_DELETE_ITEM = 102
        private const val MENU_REPLY_ITEM = 103
        private const val MENU_MULTI_SELECT_ITEM = 104
        private const val MENU_SHARE_ITEM = 105
        private const val MENU_EDIT_ITEM = 106
        private const val MENU_CONV_PIN = 200
        private const val MENU_CONV_RENAME = 201
        private const val MENU_CONV_EXPORT = 203
        private const val MENU_CONV_DELETE = 202
        private const val MENU_SEARCH_MESSAGES = 6
        private const val MENU_STATS = 8
        private const val MENU_EXPORT_CONVERSATION = 7
        private const val MENU_PROMPT_LIBRARY = 9

        /** 定位高亮的停留时长：跳到被引用消息后亮这么久再熄灭。 */
        private const val HIGHLIGHT_MILLIS = 1600L
        private const val STREAM_THINKING_ID = -1L
        private const val STREAM_ASSISTANT_ID = -2L

        /** 全局记忆最多携带的历史消息条数（调用预算保护）。 */
        private const val GLOBAL_MEMORY_MAX_MESSAGES = 60
    }
}

/** 待发送的一张图片附件。2.0 起输入区支持多图，按选择顺序排列。 */
private data class PendingAttachment(
    val uri: Uri,
    val name: String,
)
