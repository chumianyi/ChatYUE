package com.chatyue.chat

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import io.noties.markwon.Markwon
import com.chatyue.chat.databinding.ItemMessageAssistantBinding
import com.chatyue.chat.databinding.ItemMessageSummaryBinding
import com.chatyue.chat.databinding.ItemMessageThinkingBinding
import com.chatyue.chat.databinding.ItemMessageUserBinding
import com.chatyue.chat.databinding.ItemTimeDividerBinding

/** 消息底部的操作按钮。 */
enum class MessageAction {
    COPY,
    LIKE,
    DISLIKE,
    SPEAK,
}

class ChatMessageAdapter(
    private val markwon: Markwon,
    private val onAction: (MessageAction, ChatMessage) -> Unit,
    private val onLongPress: (ChatMessage, View) -> Unit = { _, _ -> },
    private val onSelectionCountChanged: (Int) -> Unit = {},
    private val onReplyQuoteClick: (ChatMessage) -> Unit = {},
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    /** 列表行：消息与按天分隔的时间条混排，提交时由 ChatTimeDivider 统一生成。 */
    private val rows = mutableListOf<ChatRow>()
    private val collapsedThinkingIds = mutableSetOf<Long>()

    /** 当前行中的消息列表（仅消息行，按列表顺序）。 */
    private val messages: List<ChatMessage>
        get() = rows.filterIsInstance<ChatRow.Message>().map { it.message }

    /** 当前正在流式输出的回答 id，它的操作栏暂时不显示。 */
    private var streamingMessageId: Long? = null

    /** 点赞 / 点踩的本地状态（互斥）。 */
    private val likedIds = mutableSetOf<Long>()
    private val dislikedIds = mutableSetOf<Long>()

    /** 正在朗读的消息 id；同一条消息再点一次操作栏即停止。 */
    private var speakingMessageId: Long? = null

    /** 消息多选模式：进入后点击消息即切换选中，长按菜单与操作栏停用。 */
    private var selectionMode = false
    private val selectedIds = mutableSetOf<Long>()

    /** 定位高亮中的消息 id：点击引用块跳到被引用消息时短暂点亮整行。 */
    private var highlightMessageId: Long? = null

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = when (val row = rows[position]) {
        is ChatRow.Message -> row.message.id
        is ChatRow.Divider -> row.id
    }

    override fun getItemViewType(position: Int): Int = when (val row = rows[position]) {
        is ChatRow.Message -> when (row.message.role) {
            MessageRole.USER -> VIEW_TYPE_USER
            MessageRole.ASSISTANT -> VIEW_TYPE_ASSISTANT
            MessageRole.SYSTEM -> VIEW_TYPE_SUMMARY
            MessageRole.THINKING_ACTIVE,
            MessageRole.THINKING_FINISHED -> VIEW_TYPE_THINKING
        }

        is ChatRow.Divider -> VIEW_TYPE_DIVIDER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_USER -> {
                UserMessageViewHolder(
                    ItemMessageUserBinding.inflate(inflater, parent, false),
                    onLongPress,
                    ::handleItemClick,
                    onReplyQuoteClick,
                )
            }

            VIEW_TYPE_THINKING -> {
                ThinkingMessageViewHolder(
                    ItemMessageThinkingBinding.inflate(inflater, parent, false),
                    ::toggleThinking,
                )
            }

            VIEW_TYPE_SUMMARY -> {
                SummaryMessageViewHolder(
                    ItemMessageSummaryBinding.inflate(inflater, parent, false),
                    markwon,
                )
            }

            VIEW_TYPE_DIVIDER -> {
                TimeDividerViewHolder(ItemTimeDividerBinding.inflate(inflater, parent, false))
            }

            else -> {
                AssistantMessageViewHolder(
                    ItemMessageAssistantBinding.inflate(inflater, parent, false),
                    markwon,
                    onAction,
                    onLongPress,
                    ::handleItemClick,
                    onReplyQuoteClick,
                )
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is ChatRow.Message -> bindMessageRow(holder, row.message, position)

            is ChatRow.Divider -> (holder as TimeDividerViewHolder).bind(row.label)
        }
    }

    private fun bindMessageRow(holder: RecyclerView.ViewHolder, message: ChatMessage, position: Int) {
        when (holder) {
            is UserMessageViewHolder -> holder.bind(
                message,
                selectionMode = selectionMode,
                selected = selectedIds.contains(message.id),
                highlighted = message.id == highlightMessageId,
            )

            is AssistantMessageViewHolder -> {
                val previousRow = rows.getOrNull(position - 1)
                val followsThinking = previousRow is ChatRow.Message &&
                    (previousRow.message.role == MessageRole.THINKING_FINISHED ||
                        previousRow.message.role == MessageRole.THINKING_ACTIVE)
                holder.bind(
                    message = message,
                    showLogo = !followsThinking,
                    // 多选模式下助手操作栏停用，整行只保留选中交互。
                    showActions = message.id != streamingMessageId && !selectionMode,
                    liked = likedIds.contains(message.id),
                    disliked = dislikedIds.contains(message.id),
                    speaking = message.id == speakingMessageId,
                    selectionMode = selectionMode,
                    selected = selectedIds.contains(message.id),
                    highlighted = message.id == highlightMessageId,
                )
            }

            is ThinkingMessageViewHolder -> {
                holder.bind(message, collapsedThinkingIds.contains(message.id))
            }

            is SummaryMessageViewHolder -> holder.bind(message)

            is TimeDividerViewHolder -> Unit
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>,
    ) {
        when (val row = rows[position]) {
            is ChatRow.Message -> bindMessageRowPayload(holder, row.message, position, payloads)

            is ChatRow.Divider -> {
                // 分隔条只有文字，没有局部刷新诉求，交给整行重绑。
                super.onBindViewHolder(holder, position, payloads)
            }
        }
    }

    private fun bindMessageRowPayload(
        holder: RecyclerView.ViewHolder,
        message: ChatMessage,
        position: Int,
        payloads: MutableList<Any>,
    ) {
        // 局部刷新：点赞 / 点踩变化或选中状态变化时，避免重新解析 Markdown，防止长回答卡顿。
        if (payloads.isNotEmpty()) {
            var handled = false
            if (holder is AssistantMessageViewHolder) {
                holder.bindActions(
                    showActions = message.id != streamingMessageId && !selectionMode,
                    liked = likedIds.contains(message.id),
                    disliked = dislikedIds.contains(message.id),
                    speaking = message.id == speakingMessageId,
                )
                handled = true
            }
            if (payloads.contains(PAYLOAD_SELECTION)) {
                val selected = selectedIds.contains(message.id)
                when (holder) {
                    is UserMessageViewHolder ->
                        holder.bindSelection(message, selectionMode, selected)

                    is AssistantMessageViewHolder ->
                        holder.bindSelection(message, selectionMode, selected)
                }
                handled = true
            }
            if (payloads.contains(PAYLOAD_HIGHLIGHT)) {
                when (holder) {
                    is UserMessageViewHolder ->
                        holder.bindHighlight(message, message.id == highlightMessageId)
                    is AssistantMessageViewHolder ->
                        holder.bindHighlight(message, message.id == highlightMessageId)
                }
                handled = true
            }
            if (handled) return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun getItemCount(): Int = rows.size

    fun submitMessages(newMessages: List<ChatMessage>) {
        val oldRows = rows.toList()
        val newRows = ChatTimeDivider.buildRows(newMessages)
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldRows.size

            override fun getNewListSize(): Int = newRows.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val old = oldRows[oldItemPosition]
                val new = newRows[newItemPosition]
                return when {
                    old is ChatRow.Message && new is ChatRow.Message ->
                        old.message.id == new.message.id

                    old is ChatRow.Divider && new is ChatRow.Divider -> old.id == new.id
                    else -> false
                }
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                oldRows[oldItemPosition] == newRows[newItemPosition]
        })
        rows.clear()
        rows.addAll(newRows)
        // 被删除的消息如果还在选中集合里，一并清掉，避免统计虚高。
        selectedIds.retainAll(newMessages.map { it.id }.toSet())
        collapsedThinkingIds.retainAll(
            newMessages
                .filter {
                    it.role == MessageRole.THINKING_ACTIVE ||
                        it.role == MessageRole.THINKING_FINISHED
                }
                .map { it.id }
                .toSet(),
        )
        diff.dispatchUpdatesTo(this)
    }

    /** 记录正在流式输出的回答 id，流式期间它的操作栏不显示。 */
    fun setStreamingMessageId(messageId: Long?) {
        if (streamingMessageId == messageId) return
        streamingMessageId = messageId
        // 只有 AI 回答带操作栏，逐项刷新即可，不必整表重绑。
        rows.forEachIndexed { index, row ->
            if (row is ChatRow.Message && row.message.role == MessageRole.ASSISTANT) {
                notifyItemChanged(index, PAYLOAD_ACTIONS)
            }
        }
    }

    /**
     * 标记正在朗读的消息 id：它的朗读图标变成停止，其余消息恢复原状。
     *
     * 只刷新新旧两条助手消息，避免整表重绑。
     */
    fun setSpeakingMessageId(messageId: Long?) {
        if (speakingMessageId == messageId) return
        val previous = speakingMessageId
        speakingMessageId = messageId
        listOfNotNull(previous, messageId).forEach { id ->
            val position = rowIndexOf(id)
            if (position >= 0) notifyItemChanged(position, PAYLOAD_ACTIONS)
        }
    }

    /** 切换点赞，与点踩互斥；返回切换后的状态。 */
    fun toggleLike(messageId: Long): Boolean {
        val liked = if (likedIds.add(messageId)) {
            dislikedIds.remove(messageId)
            true
        } else {
            likedIds.remove(messageId)
            false
        }
        notifyMessage(messageId)
        return liked
    }

    /** 切换点踩，与点赞互斥；返回切换后的状态。 */
    fun toggleDislike(messageId: Long): Boolean {
        val disliked = if (dislikedIds.add(messageId)) {
            likedIds.remove(messageId)
            true
        } else {
            dislikedIds.remove(messageId)
            false
        }
        notifyMessage(messageId)
        return disliked
    }

    /** 同步某条消息的收藏状态到列表数据并刷新该行。 */
    fun updateStarred(messageId: Long, starred: Boolean) {
        val position = rowIndexOf(messageId)
        if (position < 0) return
        val row = rows[position] as? ChatRow.Message ?: return
        // 直接改写该行携带的 message 副本，避免依赖上层重载整张列表。
        rows[position] = ChatRow.Message(row.message.copy(starred = starred))
        // 整行重绑以刷新 ViewHolder 内持有的 current，确保下次长按菜单拿到最新 starred。
        notifyItemChanged(position)
    }

    // ---------------- 消息多选 ----------------
    /** 是否处于多选模式。 */
    fun isSelectionMode(): Boolean = selectionMode

    /** 进入多选模式，并默认选中触发入口的那条消息。 */
    fun enterSelectionMode(messageId: Long) {
        if (selectionMode) return
        selectionMode = true
        selectedIds.clear()
        if (messages.any { it.id == messageId && isSelectable(it) }) {
            selectedIds.add(messageId)
        }
        refreshSelectionUi()
    }

    /** 退出多选模式并清空选中。 */
    fun exitSelectionMode() {
        if (!selectionMode) return
        selectionMode = false
        selectedIds.clear()
        refreshSelectionUi()
    }

    /** 全选当前可参与多选的消息（用户与助手消息；思考与摘要除外）。 */
    fun selectAllSelectable() {
        if (!selectionMode) return
        selectedIds.clear()
        messages.filter { isSelectable(it) }.forEach { selectedIds.add(it.id) }
        refreshSelectionUi()
    }

    /** 取当前选中的消息（按列表顺序，便于复制 / 删除时保持阅读顺序）。 */
    fun getSelectedMessages(): List<ChatMessage> = messages.filter { it.id in selectedIds }

    private fun toggleSelection(messageId: Long) {
        if (!selectionMode) return
        if (!selectedIds.add(messageId)) {
            selectedIds.remove(messageId)
        }
        notifyMessage(messageId)
        onSelectionCountChanged(selectedIds.size)
    }

    private fun handleItemClick(message: ChatMessage) {
        if (!selectionMode || !isSelectable(message)) return
        toggleSelection(message.id)
    }

    private fun isSelectable(message: ChatMessage): Boolean =
        message.role == MessageRole.USER || message.role == MessageRole.ASSISTANT

    private fun refreshSelectionUi() {
        if (rows.isEmpty()) {
            onSelectionCountChanged(selectedIds.size)
            return
        }
        notifyItemRangeChanged(0, rows.size, PAYLOAD_SELECTION)
        onSelectionCountChanged(selectedIds.size)
    }

    /** 消息在行列表中的位置（分隔条也占位置）；不存在返回 -1。 */
    private fun rowIndexOf(messageId: Long): Int =
        rows.indexOfFirst { row -> row is ChatRow.Message && row.message.id == messageId }

    /** 对外暴露的消息定位查询：供点击引用块后滚动到被引用消息。 */
    fun findRowPositionOfMessage(messageId: Long): Int = rowIndexOf(messageId)

    /**
     * 点亮 / 熄灭定位高亮：只刷新新旧两条消息，避免整表重绑。
     * 熄灭由主页计时器触发，与点亮走同一条通道。
     */
    fun setHighlightMessageId(messageId: Long?) {
        if (highlightMessageId == messageId) return
        val previous = highlightMessageId
        highlightMessageId = messageId
        listOfNotNull(previous, messageId).forEach { id ->
            val position = rowIndexOf(id)
            if (position >= 0) notifyItemChanged(position, PAYLOAD_HIGHLIGHT)
        }
    }

    private fun notifyMessage(messageId: Long) {
        val position = rowIndexOf(messageId)
        if (position >= 0) notifyItemChanged(position, PAYLOAD_ACTIONS)
    }

    private fun toggleThinking(messageId: Long) {
        if (!collapsedThinkingIds.add(messageId)) {
            collapsedThinkingIds.remove(messageId)
        }
        val position = rowIndexOf(messageId)
        if (position >= 0) notifyItemChanged(position)
    }

    private class UserMessageViewHolder(
        private val binding: ItemMessageUserBinding,
        private val onLongPress: (ChatMessage, View) -> Unit,
        private val onItemClick: (ChatMessage) -> Unit,
        private val onReplyQuoteClick: (ChatMessage) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        private var current: ChatMessage? = null

        init {
            binding.root.setOnClickListener {
                current?.let(onItemClick)
            }
            binding.root.setOnLongClickListener {
                val message = current ?: return@setOnLongClickListener false
                onLongPress(message, binding.messageText)
                true
            }
            // 点击引用块：定位到被引用的消息。
            binding.replyQuote.root.setOnClickListener {
                current?.let(onReplyQuoteClick)
            }
        }

        fun bind(
            message: ChatMessage,
            selectionMode: Boolean,
            selected: Boolean,
            highlighted: Boolean,
        ) {
            current = message
            binding.messageText.text = message.content
            binding.timeLabel.text = MessageTimeFormatter.format(
                message.createdAt, System.currentTimeMillis(),
            )
            bindReplyQuote(message)
            bindSelection(message, selectionMode, selected)
            bindHighlight(message, highlighted)
        }

        /** 多选视觉：指示器显隐 + 气泡选中态。 */
        fun bindSelection(message: ChatMessage, selectionMode: Boolean, selected: Boolean) {
            val showIndicator = selectionMode &&
                (message.role == MessageRole.USER || message.role == MessageRole.ASSISTANT)
            binding.messageSelectIndicator.visibility =
                if (showIndicator) View.VISIBLE else View.GONE
            binding.messageText.isActivated = selected
        }

        /** 定位高亮：整行短暂泛起浅蓝带，未高亮时恢复透明。 */
        fun bindHighlight(message: ChatMessage, highlighted: Boolean) {
            binding.root.setBackgroundResource(
                if (highlighted) R.drawable.bg_message_flash else 0,
            )
        }

        /** 有引用才显示引用块，无引用时彻底隐藏，不占位。 */
        private fun bindReplyQuote(message: ChatMessage) {
            val quoted = message.repliedToId != null &&
                message.repliedRole != null &&
                !message.repliedPreview.isNullOrEmpty()
            binding.replyQuote.root.visibility = if (quoted) View.VISIBLE else View.GONE
            if (!quoted) return
            binding.replyQuote.replyRole.setText(
                if (message.repliedRole == MessageRole.USER) {
                    R.string.role_user
                } else {
                    R.string.role_assistant
                },
            )
            binding.replyQuote.replyPreview.text = message.repliedPreview
        }
    }

    private class AssistantMessageViewHolder(
        private val binding: ItemMessageAssistantBinding,
        private val markwon: Markwon,
        private val onAction: (MessageAction, ChatMessage) -> Unit,
        private val onLongPress: (ChatMessage, View) -> Unit,
        private val onItemClick: (ChatMessage) -> Unit,
        private val onReplyQuoteClick: (ChatMessage) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        private var current: ChatMessage? = null

        /** 复制成功后把图标恢复成复制。 */
        private val resetCopyIcon = Runnable {
            binding.copyButton.setImageResource(R.drawable.ic_action_copy)
        }

        init {
            binding.root.setOnClickListener {
                current?.let(onItemClick)
            }
            binding.root.setOnLongClickListener {
                val message = current ?: return@setOnLongClickListener false
                onLongPress(message, binding.messageActions)
                true
            }
            binding.copyButton.setOnClickListener {
                val message = current ?: return@setOnClickListener
                onAction(MessageAction.COPY, message)
                // 复制完成后图标变成勾，稍后自动恢复。
                binding.copyButton.setImageResource(R.drawable.ic_action_check)
                binding.copyButton.removeCallbacks(resetCopyIcon)
                binding.copyButton.postDelayed(resetCopyIcon, COPY_FEEDBACK_MILLIS)
            }
            binding.likeButton.setOnClickListener {
                current?.let { onAction(MessageAction.LIKE, it) }
            }
            binding.dislikeButton.setOnClickListener {
                current?.let { onAction(MessageAction.DISLIKE, it) }
            }
            binding.speakButton.setOnClickListener {
                current?.let { onAction(MessageAction.SPEAK, it) }
            }
            // 点击引用块：定位到被引用的消息。
            binding.replyQuote.root.setOnClickListener {
                current?.let(onReplyQuoteClick)
            }
        }

        fun bind(
            message: ChatMessage,
            showLogo: Boolean,
            showActions: Boolean,
            liked: Boolean,
            disliked: Boolean,
            speaking: Boolean,
            selectionMode: Boolean,
            selected: Boolean,
            highlighted: Boolean,
        ) {
            current = message
            binding.assistantLogo.visibility = if (showLogo) View.VISIBLE else View.GONE
            val extraction = CodeBlockExtractor.extract(message.content)
            markwon.setMarkdown(binding.messageText, extraction.remainingText)
            renderCodePreviews(extraction.blocks)
            bindReplyQuote(message)

            // 复用视图时清掉上一次的复制反馈。
            binding.copyButton.removeCallbacks(resetCopyIcon)
            binding.copyButton.setImageResource(R.drawable.ic_action_copy)

            bindActions(showActions, liked, disliked, speaking)
            bindSelection(message, selectionMode, selected)
            bindHighlight(message, highlighted)
            binding.timeLabel.text = MessageTimeFormatter.format(
                message.createdAt, System.currentTimeMillis(),
            )
        }

        /** 把可预览代码块渲染成独立卡片；无块时隐藏容器并回收旧 WebView。 */
        private fun renderCodePreviews(blocks: List<CodePreviewBlock>) {
            repeat(binding.codePreviewContainer.childCount) { index ->
                (binding.codePreviewContainer.getChildAt(index) as? CodePreviewCard)?.destroy()
            }
            binding.codePreviewContainer.removeAllViews()
            if (blocks.isEmpty()) {
                binding.codePreviewContainer.visibility = View.GONE
                return
            }
            binding.codePreviewContainer.visibility = View.VISIBLE
            blocks.forEach { block ->
                binding.codePreviewContainer.addView(
                    CodePreviewCard(binding.root.context, markwon).apply { bind(block) },
                )
            }
        }

        /** 多选视觉：指示器显隐 + 文本选中态。 */
        fun bindSelection(message: ChatMessage, selectionMode: Boolean, selected: Boolean) {
            val showIndicator = selectionMode &&
                (message.role == MessageRole.USER || message.role == MessageRole.ASSISTANT)
            binding.messageSelectIndicator.visibility =
                if (showIndicator) View.VISIBLE else View.GONE
            binding.messageText.isActivated = selected
        }

        /** 定位高亮：整行短暂泛起浅蓝带，未高亮时恢复透明。 */
        fun bindHighlight(message: ChatMessage, highlighted: Boolean) {
            binding.root.setBackgroundResource(
                if (highlighted) R.drawable.bg_message_flash else 0,
            )
        }

        /** 只更新操作栏（点赞 / 点踩 / 朗读状态变化时调用，不重新渲染正文）。 */
        fun bindActions(
            showActions: Boolean,
            liked: Boolean,
            disliked: Boolean,
            speaking: Boolean,
        ) {
            binding.messageActions.visibility = if (showActions) View.VISIBLE else View.GONE
            tintAction(binding.likeButton, liked)
            tintAction(binding.dislikeButton, disliked)
            binding.speakButton.setImageResource(
                if (speaking) R.drawable.ic_stop else R.drawable.ic_volume,
            )
            binding.speakButton.contentDescription = binding.root.context.getString(
                if (speaking) R.string.action_stop_speak else R.string.action_speak,
            )
            binding.likeButton.contentDescription = binding.root.context.getString(
                if (liked) R.string.action_like_done else R.string.action_like,
            )
        }

        private fun tintAction(
            button: androidx.appcompat.widget.AppCompatImageButton,
            active: Boolean,
        ) {
            val color = ContextCompat.getColor(
                binding.root.context,
                if (active) R.color.ds_blue else R.color.ds_text_secondary,
            )
            button.imageTintList = ColorStateList.valueOf(color)
        }

        /** 有引用才显示引用块，无引用时彻底隐藏，不占位。 */
        private fun bindReplyQuote(message: ChatMessage) {
            val quoted = message.repliedToId != null &&
                message.repliedRole != null &&
                !message.repliedPreview.isNullOrEmpty()
            binding.replyQuote.root.visibility = if (quoted) View.VISIBLE else View.GONE
            if (!quoted) return
            binding.replyQuote.replyRole.setText(
                if (message.repliedRole == MessageRole.USER) {
                    R.string.role_user
                } else {
                    R.string.role_assistant
                },
            )
            binding.replyQuote.replyPreview.text = message.repliedPreview
        }
    }

    private class SummaryMessageViewHolder(
        private val binding: ItemMessageSummaryBinding,
        private val markwon: Markwon,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(message: ChatMessage) {
            markwon.setMarkdown(binding.summaryText, message.content)
        }
    }

    private class ThinkingMessageViewHolder(
        private val binding: ItemMessageThinkingBinding,
        private val onToggle: (Long) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        private var messageId: Long = 0L

        init {
            binding.thinkingHeader.setOnClickListener {
                onToggle(messageId)
            }
            binding.thinkingToggle.setOnClickListener {
                onToggle(messageId)
            }
        }

        fun bind(message: ChatMessage, isCollapsed: Boolean) {
            messageId = message.id
            val isActive = message.role == MessageRole.THINKING_ACTIVE
            binding.thinkingTitle.setText(
                if (isActive) {
                    R.string.thinking_in_progress
                } else {
                    R.string.thinking_finished
                },
            )
            binding.thinkingText.text = message.content
            binding.thinkingBody.visibility =
                if (isCollapsed) View.GONE else View.VISIBLE
            binding.thinkingToggle.setImageResource(
                if (isCollapsed) {
                    R.drawable.ic_chevron_down
                } else {
                    R.drawable.ic_chevron_up
                },
            )
            binding.thinkingToggle.contentDescription = binding.root.context.getString(
                if (isCollapsed) {
                    R.string.expand_thinking
                } else {
                    R.string.collapse_thinking
                },
            )
        }
    }

    private class TimeDividerViewHolder(
        private val binding: ItemTimeDividerBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(label: String) {
            binding.dividerText.text = label
        }
    }

    companion object {
        /** 复制成功后打勾图标的停留时长。 */
        private const val COPY_FEEDBACK_MILLIS = 1500L

        /** 只刷新操作栏的局部更新标记。 */
        private val PAYLOAD_ACTIONS = Any()

        /** 只刷新多选选中态的局部更新标记。 */
        private val PAYLOAD_SELECTION = Any()

        /** 只刷新定位高亮（点击引用块跳到被引用消息）的局部更新标记。 */
        private val PAYLOAD_HIGHLIGHT = Any()

        private const val VIEW_TYPE_USER = 1
        private const val VIEW_TYPE_ASSISTANT = 2
        private const val VIEW_TYPE_THINKING = 3
        private const val VIEW_TYPE_SUMMARY = 4

        /** 按天分隔的时间条。 */
        private const val VIEW_TYPE_DIVIDER = 5
    }
}
