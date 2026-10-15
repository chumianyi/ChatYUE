package com.chatyue.chat.remote

/**
 * 由 GitHub Pages 托管的远程配置（`start.json`）。
 *
 * 「检查更新」与「更新日志」都基于这个文件，因此不重新发版也能更新文案与日志。
 */
data class RemoteConfig(
    val versionName: String,
    val versionCode: Int,
    val releaseDate: String,
    val downloadUrl: String,
    val agreementUrl: String,
    val privacyUrl: String,
    val changelog: List<ChangelogEntry>,
) {
    companion object {
        /** 页面基址（GitHub Pages）。 */
        const val BASE_URL = "https://chumianyi.github.io/ChatYUE/"

        const val START_JSON_URL = BASE_URL + "start.json"
        const val AGREEMENT_URL = BASE_URL + "agreement.html"
        const val PRIVACY_URL = BASE_URL + "privacy.html"
        const val DOWNLOAD_URL = "https://github.com/chumianyi/ChatYUE/releases/latest"
    }
}

data class ChangelogEntry(
    val versionName: String,
    val releaseDate: String,
    val items: List<String>,
)
