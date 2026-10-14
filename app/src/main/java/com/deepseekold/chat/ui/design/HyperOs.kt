package com.deepseekold.chat.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 小米 HyperOS（Miuix）设计语言的轻量实现。
 *
 * 说明：Miuix 官方组件库（top.yukonga.miuix.kmp）最低要求 Android 6.0（API 23），
 * 为保证本应用可安装在 Android 5.0 设备上，这里按同样的设计规范自行实现组件，
 * 仅依赖 Compose 基础库（foundation），不引入任何额外依赖。
 */
data class Colors(
    val primary: Color,
    val onPrimary: Color,
    val background: Color,
    val onBackground: Color,
    val onBackgroundVariant: Color,
    val surface: Color,
    val onSurface: Color,
    val card: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val error: Color,
    val errorContainer: Color,
    val onErrorContainer: Color,
    val onSurfaceVariantSummary: Color,
    val outline: Color,
    val dividerLine: Color,
    val windowDimming: Color,
    val isDark: Boolean,
)

val LightColors = Colors(
    primary = Color(0xFF3482FF),
    onPrimary = Color(0xFFFFFFFF),
    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF191919),
    onBackgroundVariant = Color(0x99191919),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF191919),
    card = Color(0xFFFFFFFF),
    secondaryContainer = Color(0x1A3482FF),
    onSecondaryContainer = Color(0xFF2B6BD6),
    error = Color(0xFFE94634),
    errorContainer = Color(0x1AE94634),
    onErrorContainer = Color(0xFFC73828),
    onSurfaceVariantSummary = Color(0x99191919),
    outline = Color(0x1F000000),
    dividerLine = Color(0x14000000),
    windowDimming = Color(0x66000000),
    isDark = false,
)

val DarkColors = Colors(
    primary = Color(0xFF4D96FF),
    onPrimary = Color(0xFFFFFFFF),
    background = Color(0xFF000000),
    onBackground = Color(0xFFE6E6E6),
    onBackgroundVariant = Color(0x99E6E6E6),
    surface = Color(0xFF1A1A1A),
    onSurface = Color(0xFFE6E6E6),
    card = Color(0xFF1F1F1F),
    secondaryContainer = Color(0x334D96FF),
    onSecondaryContainer = Color(0xFF8FBEFF),
    error = Color(0xFFF12522),
    errorContainer = Color(0x33F12522),
    onErrorContainer = Color(0xFFFF8A80),
    onSurfaceVariantSummary = Color(0x99E6E6E6),
    outline = Color(0x33FFFFFF),
    dividerLine = Color(0x1FFFFFFF),
    windowDimming = Color(0x99000000),
    isDark = true,
)

private val LocalColors = staticCompositionLocalOf { LightColors }

/** 与 Miuix 同名，便于组件用法保持一致。 */
object MiuixTheme {
    val colorScheme: Colors
        @Composable get() = LocalColors.current
}

@Composable
fun MiuixTheme(
    colors: Colors = if (isSystemInDarkTheme()) DarkColors else LightColors,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalColors provides colors) {
        content()
    }
}

// ---------------- 组件 ----------------

@Composable
fun Card(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 16.dp,
    insideMargin: PaddingValues = PaddingValues(14.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(MiuixTheme.colorScheme.card)
            .padding(insideMargin),
        content = content,
    )
}

@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    maxLines: Int = Int.MAX_VALUE,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(
            color = if (color == Color.Unspecified) MiuixTheme.colorScheme.onSurface else color,
            fontSize = if (fontSize == TextUnit.Unspecified) 15.sp else fontSize,
            fontWeight = fontWeight,
            textAlign = textAlign,
            lineHeight = if (lineHeight == TextUnit.Unspecified) TextUnit.Unspecified else lineHeight,
        ),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun SmallTitle(
    text: String,
    modifier: Modifier = Modifier,
    textColor: Color = MiuixTheme.colorScheme.onBackgroundVariant,
) {
    Text(
        text = text,
        modifier = modifier,
        color = textColor,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    cornerRadius: Dp = 14.dp,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(
                if (enabled) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.primary.copy(alpha = 0.4f)
                },
            )
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
fun TextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (enabled) {
                    MiuixTheme.colorScheme.secondaryContainer
                } else {
                    MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
                },
            )
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (enabled) {
                MiuixTheme.colorScheme.onSecondaryContainer
            } else {
                MiuixTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.5f)
            },
            fontSize = 14.sp,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun TextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "",
    useLabelAsPlaceholder: Boolean = false,
    enabled: Boolean = true,
    maxLines: Int = 1,
    minLines: Int = 1,
) {
    val colors = MiuixTheme.colorScheme
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        textStyle = TextStyle(color = colors.onSurface, fontSize = 15.sp),
        singleLine = maxLines == 1,
        maxLines = maxLines,
        minLines = minLines,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = KeyboardType.Text,
        ),
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.secondaryContainer)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                if (value.isEmpty() && label.isNotEmpty() && useLabelAsPlaceholder) {
                    Text(
                        text = label,
                        color = colors.onSecondaryContainer.copy(alpha = 0.6f),
                        maxLines = 1,
                    )
                }
                innerTextField()
            }
        },
    )
}

@Composable
fun Scaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    containerColor: Color = MiuixTheme.colorScheme.background,
    content: @Composable (PaddingValues) -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(containerColor),
    ) {
        Box(modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
            topBar()
        }
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            content(PaddingValues(0.dp))
        }
    }
}

@Composable
fun SmallTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    color: Color = MiuixTheme.colorScheme.surface,
    titleColor: Color = MiuixTheme.colorScheme.onSurface,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title,
            modifier = Modifier
                .widthIn(max = 200.dp)
                .padding(horizontal = 4.dp),
            color = titleColor,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.CenterStart),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            navigationIcon()
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.CenterEnd),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
            content = actions,
        )
    }
}
