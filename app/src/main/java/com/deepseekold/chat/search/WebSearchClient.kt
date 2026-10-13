package com.deepseekold.chat.search

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 轻量联网搜索。
 *
 * 说明：本应用的发请求链路只走固定接口，没有可用的搜索 API Key，
 * 因此这里使用**免密钥**的公开数据源拼装搜索结果：
 * 1. DuckDuckGo Instant Answer（摘要 + 相关条目）
 * 2. 维基百科搜索 API（补充条目摘要）
 *
 * 结果会作为 system 上下文注入到提问中，模型据此回答。
 * 若后续接入正式的搜索 API，只需要替换 [search] 的实现。
 */
object WebSearchClient {
    private const val MAX_RESULTS = 6
    private const val MAX_SNIPPET_LENGTH = 220

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    data class SearchResult(
        val title: String,
        val url: String,
        val snippet: String,
    )

    /** 阻塞式搜索，请放在子线程执行。 */
    fun search(query: String): List<SearchResult> {
        val normalized = query.trim()
        if (normalized.isEmpty()) return emptyList()

        val results = mutableListOf<SearchResult>()
        runCatching { duckDuckGo(normalized) }.getOrNull()?.let(results::addAll)
        if (results.size < 3) {
            runCatching { wikipedia(normalized) }.getOrNull()?.let(results::addAll)
        }
        return results
            .filter { it.snippet.isNotBlank() }
            .distinctBy { it.url }
            .take(MAX_RESULTS)
    }

    /** 把搜索结果拼成给模型的上下文。 */
    fun buildContext(query: String, results: List<SearchResult>): String {
        if (results.isEmpty()) return ""
        return buildString {
            append("以下是联网搜索到的公开资料，请优先结合这些信息回答用户的问题：\n")
            append("搜索关键词：").append(query).append('\n')
            results.forEachIndexed { index, result ->
                append("${index + 1}. ${result.title}\n")
                append("   摘要：${result.snippet}\n")
                if (result.url.isNotBlank()) {
                    append("   来源：${result.url}\n")
                }
            }
            append("如果以上资料与问题无关，请忽略它们并直接回答。")
        }
    }

    private fun duckDuckGo(query: String): List<SearchResult> {
        val url = "https://api.duckduckgo.com/?q=${encode(query)}" +
            "&format=json&no_html=1&skip_disambig=1&t=deepseekold"
        val json = JSONObject(get(url))
        val results = mutableListOf<SearchResult>()

        val abstract = json.optString("AbstractText")
        if (abstract.isNotBlank()) {
            results += SearchResult(
                title = json.optString("Heading").ifBlank { query },
                url = json.optString("AbstractURL"),
                snippet = abstract.take(MAX_SNIPPET_LENGTH),
            )
        }
        collectTopics(json.optJSONArray("RelatedTopics"), results)
        return results
    }

    private fun collectTopics(array: JSONArray?, into: MutableList<SearchResult>) {
        if (array == null || into.size >= MAX_RESULTS) return
        for (index in 0 until array.length()) {
            if (into.size >= MAX_RESULTS) return
            val item = array.optJSONObject(index) ?: continue
            val nested = item.optJSONArray("Topics")
            if (nested != null) {
                collectTopics(nested, into)
                continue
            }
            val text = item.optString("Text")
            if (text.isBlank()) continue
            into += SearchResult(
                title = text.substringBefore(" - ").take(60),
                url = item.optString("FirstURL"),
                snippet = text.take(MAX_SNIPPET_LENGTH),
            )
        }
    }

    private fun wikipedia(query: String): List<SearchResult> {
        val url = "https://zh.wikipedia.org/w/api.php?action=query&list=search" +
            "&srsearch=${encode(query)}&srlimit=5&format=json&utf8=1"
        val json = JSONObject(get(url))
        val search = json.optJSONObject("query")?.optJSONArray("search") ?: return emptyList()
        return buildList {
            for (index in 0 until search.length()) {
                val item = search.optJSONObject(index) ?: continue
                val title = item.optString("title")
                val snippet = stripHtml(item.optString("snippet"))
                if (title.isBlank() || snippet.isBlank()) continue
                val pageId = item.optLong("pageid")
                add(
                    SearchResult(
                        title = title,
                        url = if (pageId > 0) {
                            "https://zh.wikipedia.org/?curid=$pageId"
                        } else {
                            ""
                        },
                        snippet = snippet.take(MAX_SNIPPET_LENGTH),
                    ),
                )
            }
        }
    }

    private fun get(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            return response.body?.string().orEmpty()
        }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8")

    private fun stripHtml(value: String): String =
        value.replace(Regex("<[^>]*>"), "")
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .trim()
}
