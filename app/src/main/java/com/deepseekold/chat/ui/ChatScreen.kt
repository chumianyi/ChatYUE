package com.deepseekold.chat.ui

import android.net.Uri
import android.widget.TextView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Observer
import com.deepseekold.chat.AgreementStore
import com.deepseekold.chat.ChatMessage
import com.deepseekold.chat.ChatViewModel
import com.deepseekold.chat.Conversation
import com.deepseekold.chat.ConversationFormatter
import com.deepseekold.chat.ContextBudget
import com.deepseekold.chat.ContextEvent
import com.deepseekold.chat.GenerationState
import com.deepseekold.chat.HumanizeError
import com.deepseekold.chat.LocalChatRepository
import com.deepseekold.chat.MessageRole
import com.deepseekold.chat.R
import com.deepseekold.chat.backend.BackendFinishReason
import com.deepseekold.chat.backend.BackendMessage
import com.deepseekold.chat.backend.BackendRole
import com.deepseekold.chat.backend.ChatRequest
import com.deepseekold.chat.isContextMessage
import io.noties.markwon.Markwon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.deepseekold.chat.ui.design.Card
import com.deepseekold.chat.ui.design.MiuixTheme
import com.deepseekold.chat.ui.design.Scaffold
import com.deepseekold.chat.ui.design.SmallTitle
import com.deepseekold.chat.ui.design.SmallTopAppBar
import com.deepseekold.chat.ui.design.Text
import com.deepseekold.chat.ui.design.TextButton
import com.deepseekold.chat.ui.design.TextField

private const val STREAM_THINKING_ID = -1L
private const val STREAM_ASSISTANT_ID = -2L

/** 待发送的附件。 */
data class AttachmentUi(
    val imageUri: Uri? = null,
    val imageName: String? = null,
    val textName: String? = null,
    val textContent: String? = null,
) {
    val isEmpty: Boolean get() = imageUri == null && textContent == null
}

/** 检查更新结果。 */
data class UpdateInfo(
    val versionName: String,
    val downloadUrl: String,
)

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    repository: LocalChatRepository,
    attachment: AttachmentUi,
    onPickImage: () -> Unit,
    onPickText: () -> Unit,
    onClearAttachment: () -> Unit,
    onOpenActivity: (Class<*>) -> Unit,
) {
    val context = LocalContext.current
    val markwon = remember { Markwon.create(context) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var generationState by remember { mutableStateOf<GenerationState>(GenerationState.Idle) }
    var contextEvent by remember { mutableStateOf<ContextEvent?>(null) }
    DisposableEffect(viewModel) {
        val generationObserver = Observer<GenerationState> { generationState = it }
        val contextObserver = Observer<ContextEvent?> { contextEvent = it }
        viewModel.generationState.observeForever(generationObserver)
        viewModel.contextEvent.observeForever(contextObserver)
        onDispose {
            viewModel.generationState.removeObserver(generationObserver)
            viewModel.contextEvent.removeObserver(contextObserver)
        }
    }

    var currentConversationId by rememberSaveable { mutableStateOf<Long?>(null) }
    var title by remember { mutableStateOf(context.getString(R.string.new_chat)) }
    var reloadTick by remember { mutableIntStateOf(0) }
    var drawerOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    var collapsedThinking by remember { mutableStateOf(emptySet<Long>()) }
    var showAttachmentMenu by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var aboutToSend by remember { mutableStateOf(false) }

    val isGenerating = generationState is GenerationState.Active
    val storedMessages = remember(currentConversationId, reloadTick) {
        currentConversationId?.let { repository.getMessages(it) } ?: emptyList()
    }
    val conversations = remember(reloadTick, searchQuery) {
        repository.getConversations(searchQuery)
    }
    val contextPercent = remember(storedMessages) {
        if (storedMessages.isEmpty()) {
            0
        } else {
            ContextBudget.percentOf(storedMessages.filter { it.role.isContextMessage })
        }
    }

    LaunchedEffect(generationState) {
        when (val state = generationState) {
            is GenerationState.Completed -> {
                reloadTick++
                when (state.message.finishReason) {
                    BackendFinishReason.LENGTH ->
                        toast(context, context.getString(R.string.response_truncated))

                    BackendFinishReason.CONTENT_FILTER ->
                        toast(context, context.getString(R.string.response_filtered))

                    BackendFinishReason.STOP -> Unit
                }
                viewModel.acknowledgeTerminal(state.eventId)
            }

            is GenerationState.Failed -> {
                reloadTick++
                toast(context, HumanizeError.humanize(state.error.message))
                viewModel.acknowledgeTerminal(state.eventId)
            }

            // 流式过程中不需要额外副作用，界面直接读取 state 渲染
            is GenerationState.Active -> Unit

            GenerationState.Idle -> Unit
        }
    }

    LaunchedEffect(contextEvent) {
        when (val event = contextEvent) {
            null -> Unit

            is ContextEvent.CompressionStarted ->
                toast(context, context.getString(R.string.context_compressing, event.percent))

            is ContextEvent.CompressionFinished -> {
                reloadTick++
                toast(context, context.getString(R.string.context_compressed, event.percent))
                viewModel.acknowledgeContextEvent()
            }

            is ContextEvent.CompressionFailed -> {
                toast(
                    context,
                    context.getString(
                        R.string.context_compress_failed,
                        HumanizeError.humanize(event.message),
                    ),
                )
                viewModel.acknowledgeContextEvent()
            }
        }
    }

    val displayMessages = remember(storedMessages, generationState) {
        buildDisplayMessages(storedMessages, generationState, context)
    }

    LaunchedEffect(displayMessages.size, displayMessages.lastOrNull()?.content) {
        if (displayMessages.isNotEmpty()) {
            listState.scrollToItem(displayMessages.lastIndex)
        }
    }

    fun startConversation(conversation: Conversation?) {
        if (conversation == null) {
            currentConversationId = null
            title = context.getString(R.string.new_chat)
        } else {
            currentConversationId = conversation.id
            title = conversation.title
        }
        input = ""
        onClearAttachment()
        reloadTick++
        drawerOpen = false
    }

    fun send() {
        val raw = input.trim()
        if (raw.isEmpty() && attachment.isEmpty) return
        if (aboutToSend) return
        aboutToSend = true

        val block = buildString {
            attachment.imageName?.let { name ->
                append("\n\n[图片附件] ").append(name)
                    .append("（本模型仅支持识别文本，无法识别图片内容）")
            }
            attachment.textContent?.let { content ->
                append("\n\n[文本附件] ").append(attachment.textName.orEmpty())
                append('\n').append(content).append("\n[/文本附件]")
            }
        }
        val content = (raw + block).trim()

        val createdNow = currentConversationId == null
        val conversationId = currentConversationId
            ?: repository.createConversation(ConversationFormatter.titleFrom(content)).also {
                currentConversationId = it
            }
        repository.addMessage(conversationId, MessageRole.USER, content)
        if (createdNow) {
            repository.updateConversationTitle(conversationId, ConversationFormatter.titleFrom(content))
        }
        title = ConversationFormatter.titleFrom(content)
        input = ""
        onClearAttachment()
        reloadTick++

        val messages = repository.getMessages(conversationId).mapNotNull { message ->
            when (message.role) {
                MessageRole.USER -> BackendMessage(BackendRole.USER, message.content)
                MessageRole.ASSISTANT -> BackendMessage(BackendRole.ASSISTANT, message.content)
                MessageRole.SYSTEM -> BackendMessage(BackendRole.SYSTEM, message.content)
                MessageRole.THINKING_ACTIVE, MessageRole.THINKING_FINISHED -> null
            }
        }
        viewModel.sendMessage(ChatRequest(conversationId.toString(), messages))
        aboutToSend = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                SmallTopAppBar(
                    title = title,
                    navigationIcon = {
                        Image(
                            painter = painterResource(R.drawable.ic_menu),
                            contentDescription = context.getString(R.string.open_history),
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(22.dp))
                                .clickable {
                                    searchQuery = ""
                                    reloadTick++
                                    drawerOpen = true
                                }
                                .padding(10.dp),
                        )
                    },
                    actions = {
                        ContextChip(percent = contextPercent)
                        Spacer(modifier = Modifier.width(4.dp))
                        Image(
                            painter = painterResource(R.drawable.ic_new_chat),
                            contentDescription = context.getString(R.string.create_new_chat),
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(22.dp))
                                .clickable { startConversation(null) }
                                .padding(10.dp),
                        )
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding(),
            ) {
                if (displayMessages.isEmpty()) {
                    WelcomePanel()
                    Spacer(modifier = Modifier.weight(1f))
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(items = displayMessages, key = { it.id }) { message ->
                            MessageRow(
                                message = message,
                                markwon = markwon,
                                collapsed = collapsedThinking.contains(message.id),
                                onToggleThinking = {
                                    collapsedThinking = if (collapsedThinking.contains(message.id)) {
                                        collapsedThinking - message.id
                                    } else {
                                        collapsedThinking + message.id
                                    }
                                },
                            )
                        }
                    }
                }
                Composer(
                    input = input,
                    onInputChange = { input = it },
                    attachment = attachment,
                    isGenerating = isGenerating,
                    onAttachmentClick = { showAttachmentMenu = true },
                    onClearAttachment = onClearAttachment,
                    onSend = {
                        if (isGenerating) {
                            viewModel.cancelGeneration()
                        } else {
                            send()
                        }
                    },
                )
            }
        }

        if (drawerOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MiuixTheme.colorScheme.windowDimming)
                    .clickable { drawerOpen = false },
            )
        }

        if (drawerOpen) {
            DrawerPanel(
                conversations = conversations,
                searchQuery = searchQuery,
                onSearchChange = {
                    searchQuery = it
                    reloadTick++
                },
                currentConversationId = currentConversationId,
                onSelect = { startConversation(it) },
                onOpenActivity = {
                    drawerOpen = false
                    onOpenActivity(it)
                },
                onClearHistory = {
                    drawerOpen = false
                    showClearConfirm = true
                },
                onCheckUpdate = {
                    drawerOpen = false
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching { com.deepseekold.chat.remote.RemoteConfigRepository.load(context) }
                        }
                        result
                            .onSuccess { config ->
                                if (config.versionCode > currentVersionCode(context)) {
                                    updateInfo = UpdateInfo(config.versionName, config.downloadUrl)
                                } else {
                                    toast(
                                        context,
                                        context.getString(
                                            R.string.update_latest,
                                            currentVersionName(context),
                                        ),
                                    )
                                }
                            }
                            .onFailure { error ->
                                toast(
                                    context,
                                    context.getString(
                                        R.string.update_check_failed,
                                        HumanizeError.humanize(error.message.orEmpty().ifBlank { "网络异常" }),
                                    ),
                                )
                            }
                    }
                },
            )
        }
    }

    if (showAttachmentMenu) {
        SimpleDialog(
            onDismiss = { showAttachmentMenu = false },
            content = {
                Text(
                    text = context.getString(R.string.attach_title),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(12.dp))
                DialogAction(context.getString(R.string.attach_image)) {
                    showAttachmentMenu = false
                    onPickImage()
                }
                DialogAction(context.getString(R.string.attach_text)) {
                    showAttachmentMenu = false
                    onPickText()
                }
                DialogAction(context.getString(R.string.attach_cancel)) {
                    showAttachmentMenu = false
                }
            },
        )
    }

    if (showClearConfirm) {
        SimpleDialog(
            onDismiss = { showClearConfirm = false },
            content = {
                Text(
                    text = context.getString(R.string.clear_history_title),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = context.getString(R.string.clear_history_message),
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(modifier = Modifier.height(12.dp))
                DialogAction(context.getString(R.string.clear)) {
                    showClearConfirm = false
                    viewModel.cancelGeneration()
                    repository.clearAll()
                    startConversation(null)
                }
                DialogAction(context.getString(R.string.cancel)) {
                    showClearConfirm = false
                }
            },
        )
    }

    updateInfo?.let { info ->
        SimpleDialog(
            onDismiss = { updateInfo = null },
            content = {
                Text(
                    text = context.getString(R.string.update_found_title, info.versionName),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = context.getString(
                        R.string.update_found_message,
                        currentVersionName(context),
                        info.versionName,
                    ),
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(modifier = Modifier.height(12.dp))
                DialogAction(context.getString(R.string.update_download)) {
                    updateInfo = null
                    openUrl(context, info.downloadUrl)
                }
                DialogAction(context.getString(R.string.update_later)) {
                    updateInfo = null
                }
            },
        )
    }
}

// ---------------- 组件 ----------------

@Composable
private fun ContextChip(percent: Int) {
    val colors = MiuixTheme.colorScheme
    val high = percent >= ContextBudget.COMPRESS_THRESHOLD_PERCENT
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (high) colors.errorContainer else colors.secondaryContainer)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            text = if (percent == 0) {
                "上下文 —"
            } else {
                "上下文 $percent%"
            },
            fontSize = 12.sp,
            color = if (high) colors.onErrorContainer else colors.onSecondaryContainer,
        )
    }
}

@Composable
private fun WelcomePanel() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 80.dp, start = 24.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_deepseek_logo),
            contentDescription = null,
            modifier = Modifier.size(56.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = LocalContext.current.getString(R.string.welcome_title),
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
            color = MiuixTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = LocalContext.current.getString(R.string.welcome_subtitle),
            fontSize = 13.sp,
            color = MiuixTheme.colorScheme.onBackgroundVariant,
        )
    }
}

@Composable
private fun MessageRow(
    message: ChatMessage,
    markwon: Markwon,
    collapsed: Boolean,
    onToggleThinking: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    when (message.role) {
        MessageRole.USER -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.secondaryContainer)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(
                    text = message.content,
                    fontSize = 15.sp,
                    color = colors.onSecondaryContainer,
                )
            }
        }

        MessageRole.ASSISTANT -> Card(modifier = Modifier.fillMaxWidth()) {
            MarkdownText(text = message.content, markwon = markwon)
        }

        MessageRole.SYSTEM -> Card(modifier = Modifier.fillMaxWidth()) {
            SmallTitle(text = LocalContext.current.getString(R.string.summary_title))
            Spacer(modifier = Modifier.height(6.dp))
            MarkdownText(text = message.content, markwon = markwon)
        }

        MessageRole.THINKING_ACTIVE,
        MessageRole.THINKING_FINISHED -> Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleThinking() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SmallTitle(
                    text = LocalContext.current.getString(
                        if (message.role == MessageRole.THINKING_ACTIVE) {
                            R.string.thinking_in_progress
                        } else {
                            R.string.thinking_finished
                        },
                    ),
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = if (collapsed) "展开" else "收起",
                    fontSize = 12.sp,
                    color = colors.primary,
                )
            }
            if (!collapsed) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = message.content,
                    fontSize = 13.sp,
                    color = colors.onSurfaceVariantSummary,
                )
            }
        }
    }
}

@Composable
private fun MarkdownText(text: String, markwon: Markwon) {
    val color = MiuixTheme.colorScheme.onSurface.toArgb()
    AndroidView(
        modifier = Modifier.fillMaxWidth(),
        factory = { context ->
            TextView(context).apply {
                setTextColor(color)
                textSize = 15f
                setLineSpacing(4f, 1f)
            }
        },
        update = { view ->
            runCatching { markwon.setMarkdown(view, text) }
                .onFailure { view.text = text }
        },
    )
}

@Composable
private fun Composer(
    input: String,
    onInputChange: (String) -> Unit,
    attachment: AttachmentUi,
    isGenerating: Boolean,
    onAttachmentClick: () -> Unit,
    onClearAttachment: () -> Unit,
    onSend: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth()) {
        if (!attachment.isEmpty) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = buildString {
                        if (attachment.imageName != null) {
                            append(LocalContext.current.getString(R.string.attachment_image_label))
                            append(" · ")
                            append(attachment.imageName)
                        } else {
                            append(LocalContext.current.getString(R.string.attachment_text_label))
                            append(" · ")
                            append(attachment.textName.orEmpty())
                        }
                    },
                    fontSize = 12.sp,
                    color = colors.primary,
                    modifier = Modifier.weight(1f),
                )
                Image(
                    painter = painterResource(R.drawable.ic_close),
                    contentDescription = LocalContext.current.getString(R.string.attach_remove),
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onClearAttachment() }
                        .padding(7.dp),
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            TextButton(
                text = LocalContext.current.getString(R.string.attach_title),
                onClick = onAttachmentClick,
                enabled = !isGenerating,
                modifier = Modifier.width(76.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            TextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f),
                label = LocalContext.current.getString(R.string.message_hint),
                useLabelAsPlaceholder = true,
                enabled = !isGenerating,
                maxLines = 5,
            )
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(
                text = LocalContext.current.getString(
                    if (isGenerating) R.string.stop_generation else R.string.send_message,
                ),
                onClick = onSend,
                modifier = Modifier.width(76.dp),
            )
        }
    }
}

@Composable
private fun DrawerPanel(
    conversations: List<Conversation>,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    currentConversationId: Long?,
    onSelect: (Conversation?) -> Unit,
    onOpenActivity: (Class<*>) -> Unit,
    onClearHistory: () -> Unit,
    onCheckUpdate: () -> Unit,
) {
    val context = LocalContext.current
    val colors = MiuixTheme.colorScheme
    Box(
        modifier = Modifier
            .width(300.dp)
            .fillMaxHeight()
            .background(colors.surface)
            .padding(top = 12.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = context.getString(R.string.tools_title),
                fontSize = 12.sp,
                color = colors.onBackgroundVariant,
                modifier = Modifier.padding(start = 18.dp, bottom = 4.dp),
            )
            DrawerRow(context.getString(R.string.tool_translate)) {
                onOpenActivity(com.deepseekold.chat.tools.TranslationActivity::class.java)
            }
            DrawerRow(context.getString(R.string.tool_essay)) {
                onOpenActivity(com.deepseekold.chat.tools.EssayActivity::class.java)
            }
            Divider()
            Text(
                text = "设置",
                fontSize = 12.sp,
                color = colors.onBackgroundVariant,
                modifier = Modifier.padding(start = 18.dp, top = 10.dp, bottom = 4.dp),
            )
            DrawerRow(context.getString(R.string.menu_changelog)) {
                onOpenActivity(com.deepseekold.chat.ChangelogActivity::class.java)
            }
            DrawerRow(context.getString(R.string.menu_check_update)) { onCheckUpdate() }
            DrawerRow(context.getString(R.string.about_menu)) {
                onOpenActivity(com.deepseekold.chat.AboutActivity::class.java)
            }
            DrawerRow(context.getString(R.string.clear_history)) { onClearHistory() }
            Spacer(modifier = Modifier.height(8.dp))
            Divider()
            TextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                label = context.getString(R.string.search_conversations),
                useLabelAsPlaceholder = true,
                maxLines = 1,
            )
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(items = conversations, key = { it.id }) { conversation ->
                    ConversationRow(
                        conversation = conversation,
                        selected = conversation.id == currentConversationId,
                        onClick = { onSelect(conversation) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DrawerRow(
    text: String,
    onClick: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 11.dp),
    ) {
        Text(text = text, fontSize = 15.sp, color = colors.onSurface)
    }
}

@Composable
private fun ConversationRow(
    conversation: Conversation,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) colors.secondaryContainer else colors.surface)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = conversation.title,
            fontSize = 14.sp,
            color = if (selected) colors.onSecondaryContainer else colors.onSurface,
        )
    }
}

@Composable
private fun Divider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .height(1.dp)
            .background(MiuixTheme.colorScheme.dividerLine),
    )
}

@Composable
private fun DialogAction(
    text: String,
    onClick: () -> Unit,
) {
    TextButton(
        text = text,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(modifier = Modifier.height(6.dp))
}

@Composable
private fun SimpleDialog(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth()) {
            content()
        }
    }
}

// ---------------- 工具函数 ----------------

private fun buildDisplayMessages(
    stored: List<ChatMessage>,
    state: GenerationState,
    context: android.content.Context,
): List<ChatMessage> {
    val list = stored.toMutableList()
    if (state !is GenerationState.Active) return list

    val hasThinking = state.thinking.isNotEmpty()
    if (hasThinking) {
        list += ChatMessage(
            id = STREAM_THINKING_ID,
            conversationId = state.conversationId,
            role = MessageRole.THINKING_ACTIVE,
            content = state.thinking,
            createdAt = Long.MAX_VALUE - 1,
        )
    }
    if (state.content.isNotEmpty()) {
        list += ChatMessage(
            id = STREAM_ASSISTANT_ID,
            conversationId = state.conversationId,
            role = MessageRole.ASSISTANT,
            content = state.content,
            createdAt = Long.MAX_VALUE,
        )
    } else if (!hasThinking) {
        list += ChatMessage(
            id = STREAM_ASSISTANT_ID,
            conversationId = state.conversationId,
            role = MessageRole.ASSISTANT,
            content = context.getString(R.string.waiting_for_response),
            createdAt = Long.MAX_VALUE,
        )
    }
    return list
}

private fun toast(context: android.content.Context, message: String) {
    android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
}

private fun openUrl(context: android.content.Context, url: String) {
    if (url.isBlank()) return
    runCatching {
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure { toast(context, url) }
}

private fun currentVersionName(context: android.content.Context): String {
    return runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("1.0").ifBlank { "1.0" }
}

private fun currentVersionCode(context: android.content.Context): Int {
    return runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode
        }
    }.getOrDefault(1)
}
