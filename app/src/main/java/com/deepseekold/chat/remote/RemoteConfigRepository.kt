package com.deepseekold.chat.remote

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 拉取远程配置：优先请求 GitHub Pages 上的 `start.json`，
 * 失败时退回安装包内置的 `assets/start.json`，保证离线也能看到版本与更新日志。
 */
object RemoteConfigRepository {
    private const val ASSET_NAME = "start.json"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /** 阻塞式加载，请放在子线程执行。 */
    fun load(context: Context): RemoteConfig {
        val remote = runCatching { loadRemote() }.getOrNull()
        if (remote != null) return remote
        return loadBundled(context)
    }

    private fun loadRemote(): RemoteConfig {
        val request = Request.Builder()
            .url(RemoteConfig.START_JSON_URL)
            .header("Accept", "application/json")
            .header("Cache-Control", "no-cache")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            return parse(body)
        }
    }

    fun loadBundled(context: Context): RemoteConfig {
        val text = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        return parse(text)
    }

    fun parse(text: String): RemoteConfig {
        val json = JSONObject(text)
        return RemoteConfig(
            versionName = json.optString("versionName").ifBlank { "1.0" },
            versionCode = json.optInt("versionCode", 0),
            releaseDate = json.optString("releaseDate"),
            downloadUrl = json.optString("downloadUrl")
                .ifBlank { RemoteConfig.DOWNLOAD_URL },
            agreementUrl = json.optString("agreementUrl")
                .ifBlank { RemoteConfig.AGREEMENT_URL },
            privacyUrl = json.optString("privacyUrl")
                .ifBlank { RemoteConfig.PRIVACY_URL },
            changelog = parseChangelog(json.optJSONArray("changelog")),
        )
    }

    private fun parseChangelog(array: JSONArray?): List<ChangelogEntry> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val entry = array.optJSONObject(index) ?: continue
                val items = entry.optJSONArray("items") ?: JSONArray()
                add(
                    ChangelogEntry(
                        versionName = entry.optString("versionName"),
                        releaseDate = entry.optString("releaseDate"),
                        items = buildList {
                            for (itemIndex in 0 until items.length()) {
                                val item = items.optString(itemIndex)
                                if (item.isNotBlank()) add(item)
                            }
                        },
                    ),
                )
            }
        }
    }
}
