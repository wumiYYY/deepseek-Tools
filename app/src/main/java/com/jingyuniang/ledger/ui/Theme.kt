package com.jingyuniang.ledger.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 「深海女仆工坊」配色：海军蓝 + 香槟金 + 纸白，衬线标题 + 无衬线正文。
 * 取自 @smalltailqwq/dsh-client-ui-skin-maid-atelier 的实际用色。
 */
data class WhalePalette(
    val primary: Color,
    val primarySoft: Color,
    val expense: Color,
    val income: Color,
    val bg: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val text: Color,
    val sub: Color,
    val divider: Color,
    val gold: Color,
    val navy: Color,
    val navyDeep: Color,
    val isDark: Boolean
)

val DarkPalette = WhalePalette(
    primary = Color(0xFFC5A468),
    primarySoft = Color(0xFF2A3459),
    expense = Color(0xFFEE7A87),
    income = Color(0xFF7FD9B0),
    bg = Color(0xFF0A1430),
    surface = Color(0xFF132657),
    surfaceAlt = Color(0xFF1B2E5C),
    text = Color(0xFFF7F3E8),
    sub = Color(0xFF9AAEDC),
    divider = Color(0xFF24325F),
    gold = Color(0xFFEED299),
    navy = Color(0xFF132657),
    navyDeep = Color(0xFF071234),
    isDark = true
)

val LightPalette = WhalePalette(
    primary = Color(0xFFA87F33),
    primarySoft = Color(0xFFF6EBD2),
    expense = Color(0xFFC94F63),
    income = Color(0xFF2E9E6B),
    bg = Color(0xFFFFFDF8),
    surface = Color(0xFFFFFFFF),
    surfaceAlt = Color(0xFFF7F0E2),
    text = Color(0xFF1B2440),
    sub = Color(0xFF6F7A93),
    divider = Color(0xFFEADFC9),
    gold = Color(0xFFC5A468),
    navy = Color(0xFF132657),
    navyDeep = Color(0xFF0A1430),
    isDark = false
)

val LocalPalette = staticCompositionLocalOf { DarkPalette }

object WhaleTheme {
    val palette: WhalePalette
        @Composable get() = LocalPalette.current
}

/** 衬线（工坊感）：数字与大标题用衬线，正文仍用无衬线保证小字清晰 */
val WhaleSerif = FontFamily.Serif

private fun whaleTypography(): Typography {
    val base = Typography()
    return base.copy(
        displayLarge = TextStyle(fontFamily = WhaleSerif, fontWeight = FontWeight.Bold, fontSize = 40.sp),
        headlineLarge = TextStyle(fontFamily = WhaleSerif, fontWeight = FontWeight.Bold, fontSize = 30.sp),
        headlineMedium = base.headlineMedium.copy(fontFamily = WhaleSerif, fontWeight = FontWeight.Bold),
        headlineSmall = base.headlineSmall.copy(fontFamily = WhaleSerif, fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontFamily = WhaleSerif, fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        bodyMedium = base.bodyMedium.copy(fontSize = 14.sp),
        labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium)
    )
}

@Composable
fun WhaleMoneyTheme(darkMode: Int, content: @Composable () -> Unit) {
    val dark = when (darkMode) {
        1 -> false
        2 -> true
        else -> true // 默认夜色工坊（可在「我的」里切浅色）
    }
    val palette = if (dark) DarkPalette else LightPalette
    val scheme = if (dark) {
        darkColorScheme(
            primary = palette.primary,
            secondary = palette.gold,
            background = palette.bg,
            surface = palette.surface,
            onPrimary = Color(0xFF0A1430),
            onSurface = palette.text,
            onBackground = palette.text,
            error = palette.expense
        )
    } else {
        lightColorScheme(
            primary = palette.primary,
            secondary = palette.gold,
            background = palette.bg,
            surface = palette.surface,
            onPrimary = Color.White,
            onSurface = palette.text,
            onBackground = palette.text,
            error = palette.expense
        )
    }
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(colorScheme = scheme, typography = whaleTypography(), content = content)
    }
}
