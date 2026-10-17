package com.chatyue.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import androidx.core.content.FileProvider
import com.chatyue.chat.databinding.ItemCodePreviewBinding
import io.noties.markwon.Markwon
import java.io.File

/**
 * 代码预览卡片：把一条可预览代码块渲染成独立卡片。
 *
 * - 顶部可切换「预览 / 代码」两种视图；
 * - 预览：HTML 用 WebView 渲染，Markdown 用 Markwon 渲染；
 * - 代码：只展示前 [CodePreviewTruncator.MAX_LINES] 行，其余省略；
 * - 复制按钮取完整代码写入剪贴板；
 * - 下载按钮把完整代码存到本地并调起系统分享。
 */
class CodePreviewCard(
    context: Context,
    private val markwon: Markwon,
) : LinearLayout(context) {

    private val binding = ItemCodePreviewBinding.inflate(
        LayoutInflater.from(context), this, true,
    )
    private var block: CodePreviewBlock? = null

    init {
        orientation = VERTICAL
        binding.modePreview.setOnClickListener { switchMode(preview = true) }
        binding.modeCode.setOnClickListener { switchMode(preview = false) }
        binding.copyButton.setOnClickListener { copyCode() }
        binding.downloadButton.setOnClickListener { downloadAndShare() }
        switchMode(preview = true)
    }

    fun bind(block: CodePreviewBlock) {
        this.block = block
        binding.languageLabel.text = when {
            block.isHtml -> context.getString(R.string.code_preview_html)
            block.isMarkdown -> context.getString(R.string.code_preview_markdown)
            else -> block.language
        }
        if (block.isPlainCode) {
            // 普通代码：没有预览，只给代码视图，并隐藏预览切换。
            binding.modeRow.visibility = View.GONE
            binding.previewContainer.visibility = View.GONE
            binding.codeContainer.visibility = View.VISIBLE
        } else {
            binding.modeRow.visibility = View.VISIBLE
            if (block.isHtml) {
                binding.previewWebView.loadDataWithBaseURL(
                    null, block.code, "text/html", "utf-8", null,
                )
                binding.previewMarkdown.visibility = View.GONE
                binding.previewWebView.visibility = View.VISIBLE
            } else {
                markwon.setMarkdown(binding.previewMarkdown, block.code)
                binding.previewMarkdown.visibility = View.VISIBLE
                binding.previewWebView.visibility = View.GONE
            }
            switchMode(preview = true)
        }
        val truncated = CodePreviewTruncator.firstLines(block.code)
        binding.codeText.text = truncated.visible
        if (truncated.truncated) {
            binding.hiddenHint.visibility = View.VISIBLE
            binding.hiddenHint.text = context.getString(
                R.string.code_preview_hidden_lines, truncated.hiddenLineCount,
            )
        } else {
            binding.hiddenHint.visibility = View.GONE
        }
    }

    private fun switchMode(preview: Boolean) {
        binding.previewContainer.visibility = if (preview) View.VISIBLE else View.GONE
        binding.codeContainer.visibility = if (preview) View.GONE else View.VISIBLE
        binding.modePreview.isSelected = preview
        binding.modeCode.isSelected = !preview
    }

    private fun copyCode() {
        val code = block?.code ?: return
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("code", code))
        binding.copyButton.setImageResource(R.drawable.ic_action_check)
        binding.copyButton.postDelayed({
            binding.copyButton.setImageResource(R.drawable.ic_action_copy)
        }, COPY_FEEDBACK_MILLIS)
    }

    private fun downloadAndShare() {
        val block = block ?: return
        val (ext, mime) = when {
            block.isHtml -> "html" to "text/html"
            block.isMarkdown -> "md" to "text/markdown"
            else -> block.language to "text/plain"
        }
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "code_${System.currentTimeMillis()}.$ext")
        file.writeText(block.code)
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(
                Intent.createChooser(intent, context.getString(R.string.code_preview_share_title)),
            )
        } catch (_: Exception) {
            // 没有可分享的应用时静默失败，不阻塞预览。
        }
    }

    /** 视图回收时销毁内部 WebView，避免长列表滚动时内存堆积。 */
    fun destroy() {
        binding.previewWebView.destroy()
    }

    companion object {
        private const val COPY_FEEDBACK_MILLIS = 1200L
    }
}
