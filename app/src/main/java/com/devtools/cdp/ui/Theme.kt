package com.devtools.cdp.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * CdpTheme —— DevTools 风格配色。
 *
 * 参考 Chrome DevTools 的深色面板：以深灰蓝为底，蓝/绿/橙作强调色，
 * 让 Console/Network 的状态色（红=错误、橙=警告、绿=成功）与面板协调。
 * 默认走深色（DevTools 习惯），同时尊重系统深色模式。
 */

// DevTools 风格调色板
private val CdpDark = darkColorScheme(
    primary = Color(0xFF8AB4F8),          // 蓝
    onPrimary = Color(0xFF0A1120),
    primaryContainer = Color(0xFF1B3A5C),
    secondary = Color(0xFF81C995),        // 绿
    onSecondary = Color(0xFF04210F),
    tertiary = Color(0xFFF9AB6B),         // 橙
    background = Color(0xFF1B1B1F),       // DevTools 深灰
    onBackground = Color(0xFFE8EAED),
    surface = Color(0xFF202124),
    onSurface = Color(0xFFE8EAED),
    surfaceVariant = Color(0xFF2D2D33),
    onSurfaceVariant = Color(0xFFBDC1C6),
    error = Color(0xFFF28B82),
    onError = Color(0xFF601410),
    outline = Color(0xFF3C4043)
)

private val CdpLight = lightColorScheme(
    primary = Color(0xFF1A73E8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD3E3FD),
    secondary = Color(0xFF1E8E3E),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFFE37400),
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF1B1B1F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFFEFF0F1),
    onSurfaceVariant = Color(0xFF444746),
    error = Color(0xFFD93025),
    onError = Color(0xFFFFFFFF),
    outline = Color(0xFFDADCE0)
)

private val CdpTypography = Typography(
    bodySmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelSmall = TextStyle(
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Medium
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium
    )
)

@Composable
fun CdpTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) CdpDark else CdpLight,
        typography = CdpTypography,
        content = content
    )
}
