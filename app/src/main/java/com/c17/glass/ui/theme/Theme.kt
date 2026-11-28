package com.c17.glass.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** 主题模式：跟随系统 / 强制浅色 / 强制深色。 */
enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色")
}

object ThemeState {
    var mode by mutableStateOf(ThemeMode.SYSTEM)

    val isDark: Boolean
        @Composable get() = when (mode) {
            ThemeMode.SYSTEM -> isSystemInDarkTheme()
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }
}

data class GlassColors(
    val isDark: Boolean,
    val bgTop: Color,
    val bgBottom: Color,
    val accent: Color,
    val accentDeep: Color,
    val onAccent: Color,
    val ink: Color,
    val inkMuted: Color,
    val inkDim: Color,
    val cardSurface: Color,
    val chipSurface: Color,
    val separator: Color,
    val success: Color,
    val warn: Color,
    val danger: Color,
    val glowA: Color,
    val glowB: Color,
    val glowC: Color
)

/**
 * 蓝白主题：中性浅灰背景，卡片/面板纯白，蓝色只做按钮、选中态和少量强调。
 */
private val LightColors = GlassColors(
    isDark = false,
    bgTop = Color(0xFFF7F7F8),
    bgBottom = Color(0xFFEFEFF2),
    accent = Color(0xFF2F6BFF),
    accentDeep = Color(0xFF1D4ED8),
    onAccent = Color(0xFFFFFFFF),
    ink = Color(0xFF101828),
    inkMuted = Color(0xFF667085),
    inkDim = Color(0xFF98A2B3),
    cardSurface = Color(0xFFFFFFFF),
    chipSurface = Color(0xFFFFFFFF),
    separator = Color(0x1A000000),
    success = Color(0xFF12B76A),
    warn = Color(0xFFF79009),
    danger = Color(0xFFF04438),
    glowA = Color(0xFFEDEDF0),
    glowB = Color(0xFFF2F2F4),
    glowC = Color(0xFFE8E8EC)
)

/**
 * 深色主题：中性深灰背景、深灰卡片/面板，蓝色只做按钮、选中态和强调。
 */
private val DarkColors = GlassColors(
    isDark = true,
    bgTop = Color(0xFF000000),
    bgBottom = Color(0xFF000000),
    accent = Color(0xFF5B8DFF),
    accentDeep = Color(0xFF3D77E8),
    onAccent = Color(0xFFFFFFFF),
    ink = Color(0xFFFFFFFF),
    inkMuted = Color(0xFFA3A3A3),
    inkDim = Color(0xFF7A7A7A),
    cardSurface = Color(0xFF242424),
    chipSurface = Color(0xFF2C2C2C),
    separator = Color(0xFF2E2E2E),
    success = Color(0xFF3DDC97),
    warn = Color(0xFFF3B24A),
    danger = Color(0xFFFF7B72),
    glowA = Color(0xFF1A1A1D),
    glowB = Color(0xFF202024),
    glowC = Color(0xFF26262A)
)

val LocalGlassColors = staticCompositionLocalOf { LightColors }

object Glass {
    val colors: GlassColors
        @Composable get() = LocalGlassColors.current
}

@Composable
fun C17GlassTheme(content: @Composable () -> Unit) {
    val dark = ThemeState.isDark
    CompositionLocalProvider(
        LocalGlassColors provides if (dark) DarkColors else LightColors,
        content = content
    )
}
