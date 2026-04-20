package com.checkmate.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val BgDark      = Color(0xFF08080F)
val BgCard      = Color(0xFF11111C)
val BgCardAlt   = Color(0xFF16161F)
val AccentGreen = Color(0xFF00C896)
val AccentRed   = Color(0xFFFF4757)
val AccentAmber = Color(0xFFFFB347)
val AccentBlue  = Color(0xFF4A9EFF)
val White90     = Color(0xFFE8E8F0)
val White60     = Color(0xFF9090A8)
val White30     = Color(0xFF505068)
val White10     = Color(0xFF1E1E2E)

private val CheckmateDarkColors = darkColorScheme(
    primary          = AccentGreen,
    onPrimary        = Color.Black,
    secondary        = AccentBlue,
    background       = BgDark,
    surface          = BgCard,
    onBackground     = White90,
    onSurface        = White90,
    error            = AccentRed,
)

@Composable
fun CheckmateTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CheckmateDarkColors,
        content     = content
    )
}
