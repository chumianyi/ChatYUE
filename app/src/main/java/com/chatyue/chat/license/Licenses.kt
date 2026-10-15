package com.chatyue.chat.license

import android.content.Context

/**
 * 一个开源组件的许可信息。
 *
 * @param assetPath assets 目录下的许可证全文；为空时详情页只显示摘要。
 */
data class LicenseEntry(
    val name: String,
    val version: String,
    val license: String,
    val holder: String,
    val url: String,
    val assetPath: String,
    /** 是否为本项目自身。 */
    val self: Boolean = false,
)

/**
 * 应用内使用的全部开源组件清单。
 *
 * 版本号与 `gradle/libs.versions.toml` 保持一致，升级依赖时同步更新这里。
 */
object Licenses {

    private const val APACHE = "licenses/apache-2.0.txt"
    private const val GPL3 = "licenses/gpl-3.0.txt"
    private const val AOSP = "The Android Open Source Project"

    /** 返回本项目的许可信息（放在列表第一位）。 */
    fun selfEntry(context: Context): LicenseEntry {
        return LicenseEntry(
            name = "ChatYUE",
            version = appVersion(context),
            license = "GPL-3.0",
            holder = "ChatYUE 开发者",
            url = "https://github.com/chumianyi/ChatYUE",
            assetPath = GPL3,
            self = true,
        )
    }

    /** 第三方组件清单。 */
    fun thirdPartyEntries(): List<LicenseEntry> {
        return listOf(
            LicenseEntry(
                name = "AndroidX Core KTX",
                version = "1.10.1",
                license = "Apache-2.0",
                holder = AOSP,
                url = "https://developer.android.com/jetpack/androidx/releases/core",
                assetPath = APACHE,
            ),
            LicenseEntry(
                name = "AndroidX AppCompat",
                version = "1.7.1",
                license = "Apache-2.0",
                holder = AOSP,
                url = "https://developer.android.com/jetpack/androidx/releases/appcompat",
                assetPath = APACHE,
            ),
            LicenseEntry(
                name = "AndroidX RecyclerView",
                version = "1.3.2",
                license = "Apache-2.0",
                holder = AOSP,
                url = "https://developer.android.com/jetpack/androidx/releases/recyclerview",
                assetPath = APACHE,
            ),
            LicenseEntry(
                name = "AndroidX DrawerLayout",
                version = "1.2.0",
                license = "Apache-2.0",
                holder = AOSP,
                url = "https://developer.android.com/jetpack/androidx/releases/drawerlayout",
                assetPath = APACHE,
            ),
            LicenseEntry(
                name = "AndroidX Lifecycle",
                version = "2.8.7",
                license = "Apache-2.0",
                holder = AOSP,
                url = "https://developer.android.com/jetpack/androidx/releases/lifecycle",
                assetPath = APACHE,
            ),
            LicenseEntry(
                name = "Kotlin 标准库",
                version = "随 Android Gradle Plugin 内置",
                license = "Apache-2.0",
                holder = "JetBrains s.r.o.",
                url = "https://kotlinlang.org",
                assetPath = APACHE,
            ),
            LicenseEntry(
                name = "OkHttp",
                version = "4.12.0",
                license = "Apache-2.0",
                holder = "Square, Inc.",
                url = "https://square.github.io/okhttp/",
                assetPath = APACHE,
            ),
            LicenseEntry(
                name = "Markwon",
                version = "4.6.2",
                license = "Apache-2.0",
                holder = "Dimitry Ivanov",
                url = "https://github.com/noties/Markwon",
                assetPath = APACHE,
            ),
        )
    }

    /** 全部条目（本项目 + 第三方）。 */
    fun all(context: Context): List<LicenseEntry> {
        return listOf(selfEntry(context)) + thirdPartyEntries()
    }

    private fun appVersion(context: Context): String {
        return runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        }.getOrDefault("").ifBlank { "1.0.0" }
    }
}
