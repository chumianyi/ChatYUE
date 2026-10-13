package com.deepseekold.chat

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.deepseekold.chat.databinding.ActivityChangelogBinding
import com.deepseekold.chat.remote.RemoteConfigRepository
import io.noties.markwon.Markwon
import java.util.concurrent.Executors

/**
 * 更新日志：内容来自 GitHub Pages 上的 `start.json`，可随时更新而无需发版。
 */
class ChangelogActivity : AppCompatActivity() {
    private lateinit var binding: ActivityChangelogBinding
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "deepseek-changelog").apply { isDaemon = true }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChangelogBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.backButton.setOnClickListener { finish() }

        val markwon = Markwon.create(this)
        executor.execute {
            val result = runCatching { RemoteConfigRepository.load(this) }
            runOnUiThread {
                result
                    .onSuccess { config ->
                        val markdown = renderMarkdown(config.changelog)
                        if (markdown.isBlank()) {
                            binding.changelogText.setText(R.string.changelog_empty)
                        } else {
                            markwon.setMarkdown(binding.changelogText, markdown)
                        }
                    }
                    .onFailure { error ->
                        binding.changelogText.text = getString(
                            R.string.changelog_failed,
                            HumanizeError.humanize(error.message.orEmpty().ifBlank { "未知错误" }),
                        )
                    }
            }
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun renderMarkdown(entries: List<com.deepseekold.chat.remote.ChangelogEntry>): String {
        if (entries.isEmpty()) return ""
        return buildString {
            entries.forEach { entry ->
                val title = buildString {
                    append("## ")
                    append(entry.versionName)
                    if (entry.releaseDate.isNotBlank()) {
                        append("  ")
                        append("`")
                        append(entry.releaseDate)
                        append("`")
                    }
                }
                append(title).append('\n')
                entry.items.forEach { item ->
                    append("- ").append(item).append('\n')
                }
                append('\n')
            }
        }
    }
}
