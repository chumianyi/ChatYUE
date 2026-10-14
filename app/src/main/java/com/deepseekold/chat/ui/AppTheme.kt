package com.deepseekold.chat.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * 全局主题：接入 Miuix（小米 HyperOS 设计语言，Apache-2.0）。
 *
 * 深色/浅色跟随系统，主色使用 Miuix 默认的小米蓝 #3482FF。
 */
@Composable
fun DeepSeekOldTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    MiuixTheme(
        colors = colors,
        content = content,
    )
}
