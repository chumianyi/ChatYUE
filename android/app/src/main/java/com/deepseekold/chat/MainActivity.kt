package com.deepseekold.chat

import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.PopupMenu
import androidx.activity.OnBackPressedCallback
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
import com.deepseekold.chat.backend.BackendErrorType
import com.deepseekold.chat.backend.BackendFinishReason
import com.deepseekold.chat.backend.BackendMessage
import com.deepseekold.chat.backend.BackendRole
import com.deepseekold.chat.backend.ChatRequest
import com.deepseekold.chat.databinding.ActivityMainBinding
import com.deepseekold.chat.tools.EssayActivity
import com.deepseekold.chat.tools.IdiomChainActivity
import com.deepseekold.chat.tools.TranslationActivity
import com.deepseekold.chat.tools.TurtleSoupActivity
import io.noties.markwon.Markwon
import kotlin.math.min

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: ChatViewModel
    private lateinit var repository: LocalChatRepository
    private lateinit var messageAdapter: ChatMessageAdapter
    private lateinit var conversationAdapter: ConversationAdapter

    private var currentConversationId: Long? = null
    private var followStreaming = true
    private var isMessageListScrolling = false
    private var pendingStreamingState: GenerationState.Active? = null
    private var pendingMessageReloadConversationId: Long? = null
    private var pendingMessageListScroll: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(
            STATE_CONVERSATION_ID,
            currentConversationId ?: NO_CONVERSATION_ID,
        )
    }

    override fun onDestroy() {
        if (!isChangingConfigurations) {
            viewModel.cancelGeneration()
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
            showCompatibilityNotice(R.string.attachment_unavailable)
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
        binding.toolSoup.setOnClickListener {
            openTool(TurtleSoupActivity::class.java)
        }
        binding.toolIdiom.setOnClickListener {
            openTool(IdiomChainActivity::class.java)
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
                        getString(R.string.context_compress_failed, event.message),
                        android.widget.Toast.LENGTH_LONG,
                    )
                    viewModel.acknowledgeContextEvent()
                }
            }
        }
    }

    private fun sendMessage() {
        val content = binding.messageInput.text?.toString()?.trim().orEmpty()
        if (content.isEmpty()) return
        if (!hasUsableNetwork()) {
            CompatToast.show(this, R.string.network_unavailable, android.widget.Toast.LENGTH_LONG)
            return
        }

        val wasNewConversation = currentConversationId == null
        val conversationId = currentConversationId ?: repository.createConversation(
            ConversationFormatter.titleFrom(content),
        ).also {
            currentConversationId = it
            binding.chatTitle.text = ConversationFormatter.titleFrom(content)
        }

        repository.addMessage(conversationId, MessageRole.USER, content)
        if (wasNewConversation) {
            repository.updateConversationTitle(
                conversationId,
                ConversationFormatter.titleFrom(content),
            )
        }
        binding.messageInput.text?.clear()
        followStreaming = true
        pendingStreamingState = null
        pendingMessageReloadConversationId = null

        val request = ChatRequest(
            conversationId = conversationId.toString(),
            messages = repository.getMessages(conversationId)
                .mapNotNull { message ->
                    when (message.role) {
                        MessageRole.USER -> BackendMessage(BackendRole.USER, message.content)
                        MessageRole.ASSISTANT -> {
                            BackendMessage(BackendRole.ASSISTANT, message.content)
                        }
                        MessageRole.SYSTEM -> {
                            BackendMessage(BackendRole.SYSTEM, message.content)
                        }
                        MessageRole.THINKING_ACTIVE,
                        MessageRole.THINKING_FINISHED -> null
                    }
                },
        )
        viewModel.sendMessage(request)
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

    private fun showMoreMenu() {
        PopupMenu(this, binding.moreButton).apply {
            menu.add(0, MENU_ABOUT, 0, R.string.about_menu)
            menu.add(0, MENU_CLEAR_HISTORY, 1, R.string.clear_history)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
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
        binding.sendButton.isEnabled =
            isGenerating || !binding.messageInput.text.isNullOrBlank()
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
        CompatToast.show(this, message, android.widget.Toast.LENGTH_LONG)
    }

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
        private const val MENU_CLEAR_HISTORY = 1
        private const val MENU_ABOUT = 2
        private const val STREAM_THINKING_ID = -1L
        private const val STREAM_ASSISTANT_ID = -2L
    }
}
