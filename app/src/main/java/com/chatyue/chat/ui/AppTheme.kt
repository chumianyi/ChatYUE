package com.chatyue.chat.ui

import androidx.compose.runtime.Composable
import com.chatyue.chat.ui.design.MiuixTheme

/**
 * 全局主题：HyperOS（Miuix）设计语言，深浅色跟随系统。
 *
 * 组件实现见 [com.chatyue.chat.ui.design]，完全基于 Compose 基础库，
 * 不引入需要 Android 6.0 的第三方 UI 库，确保可安装在 Android 5.0。
 */
@Composable
fun ChatYUETheme(content: @Composable () -> Unit) {
    MiuixTheme(content = content)
}
