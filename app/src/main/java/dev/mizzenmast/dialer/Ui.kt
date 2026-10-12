@file:OptIn(ExperimentalFoundationApi::class)
@file:Suppress("DEPRECATION")

package dev.mizzenmast.dialer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.graphics.luminance
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

object Photos {
    private val cache = LruCache<String, Bitmap>(60)
    suspend fun load(ctx: Context, uri: String): Bitmap? {
        cache.get(uri)?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                ctx.contentResolver.openInputStream(uri.toUri())?.use { BitmapFactory.decodeStream(it) }?.also { cache.put(uri, it) }
            } catch (e: Exception) { null }
        }
    }
}

fun initialsOf(name: String?): String {
    if (name.isNullOrBlank()) return ""
    val parts = name.trim().split(Regex("\\s+")).filter { it.first().isLetter() }
    return when {
        parts.isEmpty() -> ""
        parts.size == 1 -> parts[0].take(1).uppercase()
        else -> (parts[0].take(1) + parts.last().take(1)).uppercase()
    }
}

@Composable
fun rememberTick(): () -> Unit {
    val h = LocalHapticFeedback.current
    val on by Prefs.haptics.collectAsState()
    return { if (on) h.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
}

@Composable
fun Avatar(name: String?, photo: String?, size: Dp, modifier: Modifier = Modifier) {
    val c = LocalColors.current
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, photo) { value = if (photo != null) Photos.load(ctx, photo) else null }
    val ini = initialsOf(name)
    val dark = c.bg.luminance() < 0.5f
    val hue = remember(name) { ((name?.hashCode() ?: 0).toLong().let { if (it < 0) -it else it } % 360).toFloat() }
    val tint = if (ini.isEmpty()) c.surfaceHigh else if (dark) Color.hsv(hue, 0.45f, 0.36f) else Color.hsv(hue, 0.22f, 0.93f)
    val ink = if (dark) Color.hsv(hue, 0.25f, 0.96f) else Color.hsv(hue, 0.65f, 0.34f)
    Box(modifier.size(size).clip(CircleShape).background(tint), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) {
            Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            if (ini.isNotEmpty()) Text(ini, color = ink, fontSize = (size.value * 0.38f).sp, fontWeight = FontWeight.Medium)
            else Icon(Icons.Rounded.Person, null, tint = c.inkDim, modifier = Modifier.size(size * 0.5f))
        }
    }
}

@Composable
fun SimChip(label: String, modifier: Modifier = Modifier, compact: Boolean = false) {
    val c = LocalColors.current

    // LogEntry.simText is the 1-based physical slot when known; otherwise it
    // falls back to the phone-account/carrier label. Never guess a slot from a
    // carrier name, since labels differ across devices and operators.
    val slot = when (label.trim().uppercase()) {
        "1", "SIM 1", "SIM1" -> 1
        "2", "SIM 2", "SIM2" -> 2
        else -> null
    }

    // Hide unknown/carrier labels instead of displaying a misleading SIM chip.
    if (slot == null) return

    Row(
        modifier.clip(RoundedCornerShape(10.dp)).background(c.surfaceHigh)
            .padding(horizontal = if (compact) 4.dp else 8.dp, vertical = if (compact) 2.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Rounded.SimCard,
            contentDescription = "SIM $slot",
            tint = c.accent,
            modifier = Modifier.size(if (compact) 16.dp else 20.dp)
        )
        Spacer(Modifier.width(3.dp))
        Text(
            slot.toString(),
            color = c.inkDim,
            fontSize = if (compact) 11.sp else 13.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * Monotone SIM-card glyph with the slot number drawn inside (res/drawable/ic_sim_1, ic_sim_2).
 * Deliberately not the accent colour: it's metadata, so it sits at the same ink as the rest of the row's secondary text.
 */
@Composable
fun SimIcon(slot: Int, modifier: Modifier = Modifier, size: Dp = 16.dp, tint: Color = LocalColors.current.inkDim) {
    Icon(
        painterResource(if (slot == 2) R.drawable.ic_sim_2 else R.drawable.ic_sim_1),
        contentDescription = "SIM $slot", tint = tint, modifier = modifier.size(size)
    )
}

/** Quiet filter chip: small, outlined when idle, a soft fill when selected. Louder than nothing, quieter than [TextPill]. */
@Composable
fun FilterChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalColors.current
    // The outer box keeps a 40dp touch target while the visible chip is only 30dp tall.
    Box(modifier.heightIn(min = 40.dp).selectable(selected, role = Role.Tab, onClick = onClick), contentAlignment = Alignment.Center) {
        Box(
            Modifier.height(30.dp).clip(RoundedCornerShape(15.dp))
                .then(if (selected) Modifier.background(c.surfaceHigh) else Modifier.border(1.dp, c.line, RoundedCornerShape(15.dp)))
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(text, color = if (selected) c.ink else c.inkDim, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        }
    }
}

/** Placeholder rows shown while a list loads: same geometry as a real row, so nothing jumps when the data arrives. */
@Composable
fun ListSkeleton(modifier: Modifier = Modifier, rows: Int = 9) {
    val c = LocalColors.current
    val motion = rememberMotionEnabled()
    val breathe = if (motion) rememberInfiniteTransition(label = "skeleton")
        .animateFloat(0.45f, 1f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "alpha") else null
    Column(modifier.fillMaxSize().graphicsLayer { alpha = breathe?.value ?: 0.7f }.padding(top = 12.dp)) {
        repeat(rows) { i ->
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(CircleShape).background(c.surfaceHigh))
                Spacer(Modifier.width(14.dp))
                Column {
                    Box(Modifier.width((120 + (i * 37) % 90).dp).height(14.dp).clip(RoundedCornerShape(7.dp)).background(c.surfaceHigh))
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.width((80 + (i * 23) % 70).dp).height(11.dp).clip(RoundedCornerShape(6.dp)).background(c.surfaceHigh))
                }
            }
        }
    }
}

/** False when the user turned system animations off (Settings > Accessibility > Remove animations). */
@Composable
fun rememberMotionEnabled(): Boolean {
    val ctx = LocalContext.current
    return remember {
        try { android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f }
        catch (e: Exception) { true }
    }
}

/** Avatar with soft ripples spreading out of it and a gentle breathing pulse. Draws outside its own bounds, so layout is unchanged. */
@Composable
fun RingingAvatar(name: String?, photo: String?, diameter: Dp, modifier: Modifier = Modifier) {
    val c = LocalColors.current
    val motion = rememberMotionEnabled()
    val radius0 = with(LocalDensity.current) { diameter.toPx() / 2f }
    // The States are read inside the draw / graphicsLayer lambdas below, so a frame never recomposes this function.
    val t = rememberInfiniteTransition(label = "ringing")
    val phase = if (motion) t.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "ripple") else remember { mutableFloatStateOf(0f) }
    val pulse = if (motion) t.animateFloat(1f, 1.05f, infiniteRepeatable(tween(650, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse") else remember { mutableFloatStateOf(1f) }
    Box(modifier.size(diameter), contentAlignment = Alignment.Center) {
        if (motion) Canvas(Modifier.requiredSize(diameter * 1.7f)) {
            val maxR = this.size.minDimension / 2f
            for (i in 0 until 3) {
                val p = (phase.value + i / 3f) % 1f
                val r = radius0 + (maxR - radius0) * p
                drawCircle(c.accent.copy(alpha = 0.22f * (1f - p) * (1f - p)), radius = r)
            }
        }
        Avatar(name, photo, diameter, Modifier.graphicsLayer { scaleX = pulse.value; scaleY = pulse.value })
    }
}

@Composable
fun RoundButton(icon: ImageVector, desc: String, bg: Color, tint: Color, size: Dp = 64.dp, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(if (enabled) bg else bg.copy(alpha = 0.4f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = desc },
        contentAlignment = Alignment.Center
    ) { Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.42f)) }
}

@Composable
fun TextPill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, danger: Boolean = false, filled: Boolean = false) {
    val c = LocalColors.current
    Box(
        modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp))
            .background(if (filled) c.accent else c.surfaceHigh)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (filled) c.onAccent else if (danger) c.decline else c.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun StateMessage(icon: ImageVector, title: String, body: String? = null, action: String? = null, onAction: (() -> Unit)? = null) {
    val c = LocalColors.current
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, null, tint = c.inkDim, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(16.dp))
        Text(title, color = c.ink, fontSize = 20.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
        if (body != null) {
            Spacer(Modifier.height(8.dp))
            Text(body, color = c.inkDim, fontSize = 14.sp, textAlign = TextAlign.Center)
        }
        if (action != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            TextPill(action, onAction, filled = true)
        }
    }
}

@Composable
fun ScreenTitle(text: String, subtitle: String? = null, trailing: (@Composable () -> Unit)? = null) {
    val c = LocalColors.current
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(text, style = T.title, color = c.ink)
            if (subtitle != null) Text(subtitle, color = c.inkDim, fontSize = 15.sp, modifier = Modifier.padding(top = 2.dp))
        }
        trailing?.invoke()
    }
}

@Composable
fun SectionLabel(text: String) {
    val c = LocalColors.current
    Text(text, style = T.label, color = c.inkDim, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 8.dp))
}

/**
 * Switch. Pass [onChange] for a standalone switch; pass null when the surrounding row is the toggleable
 * (one touch target and one accessibility node for the whole row).
 */
@Composable
fun Toggle(checked: Boolean, onChange: ((Boolean) -> Unit)?) {
    val c = LocalColors.current
    val x by animateDpAsState(if (checked) 22.dp else 2.dp, label = "toggle")
    val onTrack = if (c.bg.luminance() < 0.5f) lerp(c.bg, c.accent, 0.65f) else c.accent // softer on dark, like the settings mockup
    val track by animateColorAsState(if (checked) onTrack else c.surfaceHigh, label = "toggleTrack")
    Box(
        Modifier.size(48.dp, 28.dp).clip(RoundedCornerShape(14.dp)).background(track)
            .then(if (onChange != null) Modifier.toggleable(checked, role = Role.Switch, onValueChange = onChange) else Modifier)
    ) {
        Box(Modifier.offset(x, 2.dp).size(24.dp).clip(CircleShape).background(if (checked) Color.White else c.inkDim))
    }
}

/** Long-press menu for a phone number: copy it, or drop it into the dialer to edit before calling. */
@Composable
fun NumberMenu(number: String, onEdit: () -> Unit, onDismiss: () -> Unit) {
    val c = LocalColors.current
    val ctx = LocalContext.current
    @Composable
    fun Item(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = c.ink, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(16.dp))
            Text(label, color = c.ink, fontSize = 16.sp)
        }
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.clip(RoundedCornerShape(24.dp)).background(c.surface).padding(vertical = 12.dp)) {
            Text(pretty(number) ?: number, color = c.inkDim, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            Item(Icons.Rounded.ContentCopy, "Copy number") {
                ctx.getSystemService(android.content.ClipboardManager::class.java)
                    ?.setPrimaryClip(android.content.ClipData.newPlainText("Phone number", number))
                if (android.os.Build.VERSION.SDK_INT < 33) android.widget.Toast.makeText(ctx, "Number copied", android.widget.Toast.LENGTH_SHORT).show()
                onDismiss()
            }
            Item(Icons.Rounded.Edit, "Edit before call") { onDismiss(); onEdit() }
        }
    }
}

@Composable
fun ConfirmDialog(title: String, body: String?, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val c = LocalColors.current
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.clip(RoundedCornerShape(24.dp)).background(c.surface).padding(24.dp)) {
            Text(title, color = c.ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            if (body != null) {
                Spacer(Modifier.height(8.dp))
                Text(body, color = c.inkDim, fontSize = 14.sp)
            }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextPill("Cancel", onDismiss)
                Spacer(Modifier.width(8.dp))
                TextPill(confirm, onConfirm, danger = true)
            }
        }
    }
}

@Composable
fun DialerFab(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = LocalColors.current
    Box(
        modifier.size(60.dp).shadow(6.dp, CircleShape).clip(CircleShape).background(c.accent)
            .clickable(role = Role.Button, onClickLabel = "Open keypad", onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(Icons.Rounded.Dialpad, "Keypad", tint = c.onAccent, modifier = Modifier.size(28.dp)) }
}

@Composable
fun BottomBar(selected: Int, onSelect: (Int) -> Unit) {
    val c = LocalColors.current
    val tabs = listOf(Icons.Rounded.History to "Recents", Icons.Rounded.Contacts to "Contacts", Icons.Rounded.Settings to "Settings")
    Column(Modifier.fillMaxWidth().background(c.surface).navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(64.dp)) {
            tabs.forEachIndexed { i, (icon, label) ->
                val on = i == selected
                Column(
                    Modifier.weight(1f).fillMaxHeight().selectable(on, role = Role.Tab, onClick = { onSelect(i) }),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
                ) {
                    Box(Modifier.width(24.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(if (on) c.accent else Color.Transparent))
                    Spacer(Modifier.height(6.dp))
                    Icon(icon, null, tint = if (on) c.accent else c.inkDim, modifier = Modifier.size(24.dp))
                    Text(label, color = if (on) c.accent else c.inkDim, fontSize = 11.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
    }
}

private val KEYS = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#")
private val LETTERS = mapOf("2" to "ABC", "3" to "DEF", "4" to "GHI", "5" to "JKL", "6" to "MNO", "7" to "PQRS", "8" to "TUV", "9" to "WXYZ", "0" to "+")

@Composable
fun Keypad(keySize: Dp, onKey: (Char) -> Unit, modifier: Modifier = Modifier, onLongZero: (() -> Unit)? = null) {
    val c = LocalColors.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        KEYS.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                row.forEach { k ->
                    Column(
                        Modifier.size(keySize).clip(CircleShape).background(c.surface)
                            .combinedClickable(onClick = { onKey(k[0]) }, onLongClick = if (k == "0") onLongZero else null),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
                    ) {
                        Text(k, fontSize = 28.sp, fontWeight = FontWeight.Light, color = c.ink)
                        LETTERS[k]?.let { Text(it, fontSize = 9.sp, letterSpacing = 1.5.sp, color = c.inkDim) }
                    }
                }
            }
        }
    }
}

/** Drag the thumb right to answer, left to decline. The end targets are also tappable (accessibility). */
@Composable
fun SwipeDecision(key: Any, onAnswer: () -> Unit, onDecline: () -> Unit) {
    val c = LocalColors.current
    val density = LocalDensity.current
    val tick = rememberTick()
    var offset by remember(key) { mutableFloatStateOf(0f) }
    var done by remember(key) { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxWidth().height(88.dp).clip(RoundedCornerShape(44.dp)).background(c.surface)) {
        val thumb = 72.dp
        val travel = with(density) { ((maxWidth - thumb - 16.dp) / 2).toPx() }
        val frac = (offset / travel).coerceIn(-1f, 1f)
        // Idle hint: two chevrons per side light up in sequence, travelling away from the thumb. They fade out as you drag.
        val motion = rememberMotionEnabled()
        val wave = if (motion) rememberInfiniteTransition(label = "swipeHint")
            .animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "wave").value else 0.5f
        val idle = (1f - abs(frac) * 2f).coerceIn(0f, 1f)
        @Composable
        fun Chevrons(right: Boolean, tint: Color) {
            Row(
                Modifier.align(Alignment.Center).offset(x = if (right) 56.dp else (-56).dp).alpha(if (done) 0f else idle),
                horizontalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                for (i in 0 until 2) {
                    val step = if (right) i else 1 - i // 0 = nearest the thumb
                    val lit = (1f - abs(wave * 3f - (step + 0.5f)) / 1.2f).coerceIn(0f, 1f)
                    Icon(if (right) Icons.Rounded.ChevronRight else Icons.Rounded.ChevronLeft, null, tint = tint.copy(alpha = 0.25f + 0.6f * lit), modifier = Modifier.size(16.dp))
                }
            }
        }
        Box(
            Modifier.align(Alignment.CenterStart).padding(start = 8.dp).size(thumb).clip(CircleShape)
                .background(c.decline.copy(alpha = 0.16f))
                .clickable(role = Role.Button, onClickLabel = "Decline call") { if (!done) { done = true; tick(); onDecline() } },
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Rounded.CallEnd, "Decline", tint = c.decline) }
        Box(
            Modifier.align(Alignment.CenterEnd).padding(end = 8.dp).size(thumb).clip(CircleShape)
                .background(c.answer.copy(alpha = 0.16f))
                .clickable(role = Role.Button, onClickLabel = "Answer call") { if (!done) { done = true; tick(); onAnswer() } },
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Rounded.Phone, "Answer", tint = c.answer) }
        Chevrons(right = false, tint = c.decline)
        Chevrons(right = true, tint = c.answer)
        Box(
            Modifier.align(Alignment.Center).offset { IntOffset(offset.roundToInt(), 0) }.size(thumb).clip(CircleShape)
                .background(lerp(c.ink, if (frac >= 0) c.answer else c.decline, abs(frac)))
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { d -> if (!done) offset = (offset + d).coerceIn(-travel, travel) },
                    onDragStopped = {
                        if (!done) {
                            when {
                                offset > travel * 0.7f -> { done = true; tick(); onAnswer() }
                                offset < -travel * 0.7f -> { done = true; tick(); onDecline() }
                                else -> animate(offset, 0f) { v, _ -> offset = v }
                            }
                        }
                    }
                ),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Rounded.Phone, null, tint = c.bg) }
    }
}