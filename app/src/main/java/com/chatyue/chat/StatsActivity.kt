package com.chatyue.chat

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.chatyue.chat.databinding.ActivityStatsBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 聊天统计页（2.0 新增）。
 *
 * 汇总全部会话的消息规模、活跃天数、近 7 天分布与消息构成，
 * 数据量大时放 IO 线程计算，避免统计卡住主界面。
 */
class StatsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityStatsBinding
    private val repository by lazy { LocalChatRepository(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStatsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.backButton.setOnClickListener { finish() }

        binding.progress.visibility = View.VISIBLE
        Thread {
            val conversations = repository.getConversations()
            val messages = repository.getAllMessages()
            val stats = ChatStatsCalculator.calculate(conversations, messages)
            val weekly = ChatStatsCalculator.dailyCounts(messages)
            runOnUiThread {
                binding.progress.visibility = View.GONE
                render(stats, weekly)
            }
        }.start()
    }

    private fun render(stats: ChatStats, weekly: List<DailyCount>) {
        binding.statValueConversations.text = stats.conversationCount.toString()
        binding.statValueMessages.text = stats.totalMessageCount.toString()
        binding.statValueActiveDays.text = stats.activeDayCount.toString()
        binding.statValueCharacters.text = compactCount(stats.totalCharacterCount)

        binding.weeklyChart.submit(weekly)

        val total = stats.totalMessageCount
        val userPercent = if (total == 0) 0 else stats.userMessageCount * 100 / total
        val assistantPercent = if (total == 0) 0 else 100 - userPercent
        binding.statUserLine.text =
            getString(R.string.stats_user_line, stats.userMessageCount, userPercent)
        binding.statAssistantLine.text =
            getString(R.string.stats_assistant_line, stats.assistantMessageCount, assistantPercent)
        // 占比条按消息数分配权重，任一侧为 0 时通过最小权重保证可见。
        val userWeight = stats.userMessageCount.coerceAtLeast(1).toFloat()
        val assistantWeight = stats.assistantMessageCount.coerceAtLeast(1).toFloat()
        (binding.statUserBar.layoutParams as android.widget.LinearLayout.LayoutParams)
            .weight = userWeight
        (binding.statAssistantBar.layoutParams as android.widget.LinearLayout.LayoutParams)
            .weight = assistantWeight

        binding.statAverageLine.text =
            getString(R.string.stats_average_line, stats.averageUserCharacters)
        binding.statLongestLine.text =
            getString(R.string.stats_longest_line, stats.longestUserCharacters)
        binding.statEarliestLine.text = stats.earliestMessageAt
            ?.let { getString(R.string.stats_earliest_line, DATE_FORMAT.format(Date(it))) }
            ?: getString(R.string.stats_earliest_never)
    }

    /** 大数字紧凑显示：不足一万原样返回，以上按「万」收敛。 */
    private fun compactCount(value: Int): String = when {
        value >= 10_000 -> String.format(Locale.CHINA, "%.1f万", value / 10_000f)
        else -> value.toString()
    }

    private companion object {
        val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
    }
}
