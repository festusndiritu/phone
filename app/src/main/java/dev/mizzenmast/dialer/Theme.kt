package dev.mizzenmast.dialer

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** App-owned palette. Material 3 is only the plumbing; the visual identity lives here. */
@Immutable
class DialerColors(
    val bg: Color, val surface: Color, val surfaceHigh: Color,
    val ink: Color, val inkDim: Color, val line: Color,
    val accent: Color, val onAccent: Color,
    val answer: Color, val decline: Color, val missed: Color, val hold: Color,
    val outgoing: Color
)

val LightColors = DialerColors(
    bg = Color(0xFFF5F3EE), surface = Color(0xFFFFFFFF), surfaceHigh = Color(0xFFEAE7DF),
    ink = Color(0xFF16181A), inkDim = Color(0xFF666B70), line = Color(0xFFDDD9CF),
    accent = Color(0xFF0F6B5C), onAccent = Color.White,
    answer = Color(0xFF1B9A58), decline = Color(0xFFD93B3B), missed = Color(0xFFC62F2F), hold = Color(0xFFA86A12),
    outgoing = Color(0xFF2F6FDB)
)

val DarkColors = DialerColors(
    bg = Color(0xFF0D0F10), surface = Color(0xFF17191C), surfaceHigh = Color(0xFF23272A),
    ink = Color(0xFFF1EFEA), inkDim = Color(0xFF9AA0A4), line = Color(0xFF2A2F32),
    accent = Color(0xFF4FD1B0), onAccent = Color(0xFF04201A),
    answer = Color(0xFF34C77B), decline = Color(0xFFFF5C5C), missed = Color(0xFFFF6B6B), hold = Color(0xFFE0A33E),
    outgoing = Color(0xFF6FA8FF)
)

val LocalColors = staticCompositionLocalOf { LightColors }

object T {
    val title = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp)
    val label = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)
}

@Composable
fun DialerTheme(mode: Int, content: @Composable () -> Unit) {
    val dark = when (mode) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    val c = if (dark) DarkColors else LightColors
    val scheme = if (dark) darkColorScheme(primary = c.accent, onPrimary = c.onAccent, background = c.bg, surface = c.surface, onSurface = c.ink, onBackground = c.ink)
    else lightColorScheme(primary = c.accent, onPrimary = c.onAccent, background = c.bg, surface = c.surface, onSurface = c.ink, onBackground = c.ink)
    CompositionLocalProvider(LocalColors provides c) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
