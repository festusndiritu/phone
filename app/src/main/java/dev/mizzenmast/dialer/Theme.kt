package dev.mizzenmast.dialer

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
    bg = Color(0xFFF2F0E8), surface = Color(0xFFFAF9F4), surfaceHigh = Color(0xFFE8E6DB),
    ink = Color(0xFF16181A), inkDim = Color(0xFF666B70), line = Color(0xFFDAD7CB),
    accent = Color(0xFF1E5B48), onAccent = Color.White,
    answer = Color(0xFF14804A), decline = Color(0xFFD93B3B), missed = Color(0xFFC62F2F), hold = Color(0xFFA86A12),
    outgoing = Color(0xFF2F6FDB)
)

/**
 * Dark palette. The three surface steps (bg < surface < surfaceHigh) and the divider are spaced far enough apart that cards and
 * rows read as separate layers instead of one dark mass. Missed (salmon) and decline (red) are deliberately different hues so a
 * missed call never looks like the End button. The accent is a touch calmer than before to sit closer to the light theme's green.
 */
val DarkColors = DialerColors(
    bg = Color(0xFF0B0D0E), surface = Color(0xFF16191B), surfaceHigh = Color(0xFF242A2D),
    ink = Color(0xFFF1EFEA), inkDim = Color(0xFF9FA5A9), line = Color(0xFF343A3E),
    accent = Color(0xFF45C2A3), onAccent = Color(0xFF04201A),
    answer = Color(0xFF34C77B), decline = Color(0xFFFF5C5C), missed = Color(0xFFFF9378), hold = Color(0xFFE0A33E),
    outgoing = Color(0xFF6FA8FF)
)

val LocalColors = staticCompositionLocalOf { LightColors }

/**
 * Corner radii: one small scale instead of a dozen near-identical values. Pills (anything whose height is about twice its
 * radius) use [pill]. Where a shape sits inside another with a gap between them, use [inner] so the corners stay concentric:
 * inner radius = outer radius - gap, so the two curves share a center and the margin looks even all the way around.
 */
object Radii {
    val xs = 8.dp      // small tiles and thumbnails
    val sm = 12.dp     // tappable rows, small buttons
    val md = 16.dp     // inner cards, tiles, primary buttons
    val lg = 24.dp     // cards, sections, dialogs, menus
    val sheet = 28.dp  // top corners of bottom sheets
    val pill = RoundedCornerShape(50)
    fun inner(outer: Dp, gap: Dp): Dp = (outer - gap).coerceAtLeast(0.dp)
}

/** The type scale. New and reworked screens use these instead of one-off sizes; sizes are in sp so system font scaling applies. */
object T {
    val title = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp)
    val heading = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal)
    val bodyStrong = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium)
    val secondary = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal)
    val caption = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal)
    val label = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)
}

@Composable
fun DialerTheme(mode: Int, content: @Composable () -> Unit) {
    val dark = when (mode) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    val base = if (dark) DarkColors else LightColors
    val dynamic by Prefs.dynamicColor.collectAsState()
    val ctx = LocalContext.current
    // Wallpaper colors (Android 12+) only replace the accent; surfaces, text and the call colors keep the app's own tuned values.
    val c = if (dynamic && Build.VERSION.SDK_INT >= 31) {
        val ds = if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        DialerColors(
            bg = base.bg, surface = base.surface, surfaceHigh = base.surfaceHigh, ink = base.ink, inkDim = base.inkDim, line = base.line,
            accent = ds.primary, onAccent = ds.onPrimary,
            answer = base.answer, decline = base.decline, missed = base.missed, hold = base.hold, outgoing = base.outgoing
        )
    } else base
    val scheme = if (dark) darkColorScheme(primary = c.accent, onPrimary = c.onAccent, background = c.bg, surface = c.surface, onSurface = c.ink, onBackground = c.ink)
    else lightColorScheme(primary = c.accent, onPrimary = c.onAccent, background = c.bg, surface = c.surface, onSurface = c.ink, onBackground = c.ink)
    CompositionLocalProvider(LocalColors provides c) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
