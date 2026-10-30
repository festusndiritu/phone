package dev.mizzenmast.dialer

import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Block
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.window.DialogProperties
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
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
    // Sized in bytes: 60 entries of a large thumbnail was an unbounded amount of heap.
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    suspend fun load(ctx: Context, uri: String): Bitmap? {
        cache.get(uri)?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                ctx.contentResolver.openInputStream(uri.toUri())?.use { BitmapFactory.decodeStream(it) }?.also { cache.put(uri, it) }
            } catch (e: Exception) { Diag.fail("photo_decode", e); null }
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
fun Avatar(name: String?, photo: String?, size: Dp, modifier: Modifier = Modifier, green: Boolean = false) {
    val c = LocalColors.current
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, photo) { value = if (photo != null) Photos.load(ctx, photo) else null }
    val ini = initialsOf(name)
    val dark = c.bg.luminance() < 0.5f
    val hue = remember(name) { ((name?.hashCode() ?: 0).toLong().let { if (it < 0) -it else it } % 360).toFloat() }
    val tint = if (ini.isEmpty()) c.surfaceHigh else if (dark) Color.hsv(hue, 0.45f, 0.36f) else Color.hsv(hue, 0.22f, 0.93f)
    val ink = if (dark) Color.hsv(hue, 0.25f, 0.96f) else Color.hsv(hue, 0.65f, 0.34f)
    // [green]: every initial circle in the accent hue (contact cards) instead of a per-name colour.
    val fill = if (green && ini.isNotEmpty()) (if (dark) lerp(c.surface, c.accent, 0.5f) else c.accent) else tint
    val letterInk = if (green && ini.isNotEmpty()) (if (dark) c.ink else c.onAccent) else ink
    Box(modifier.size(size).clip(CircleShape).background(fill), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) {
            Image(remember(b) { b.asImageBitmap() }, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            if (ini.isNotEmpty()) Text(ini, color = letterInk, fontSize = (size.value * 0.38f).sp, fontWeight = FontWeight.Medium)
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

/** "SIM 1" in a small pill: the trailing metadata of list rows. */
@Composable
fun SimTag(slot: Int, modifier: Modifier = Modifier) {
    val c = LocalColors.current
    Text("SIM $slot", color = c.inkDim, fontSize = 10.sp, fontWeight = FontWeight.Medium,
        modifier = modifier.clip(RoundedCornerShape(8.dp)).background(c.surfaceHigh).padding(horizontal = 6.dp, vertical = 2.dp))
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

/** Filter chip: filled with the accent when selected, a quiet outline when idle. */
@Composable
fun FilterChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalColors.current
    val shape = RoundedCornerShape(16.dp)
    // The outer box keeps a 40dp touch target while the visible chip is only 32dp tall.
    Box(modifier.heightIn(min = 40.dp).selectable(selected, role = Role.Tab, onClick = onClick), contentAlignment = Alignment.Center) {
        Box(
            Modifier.height(32.dp).clip(shape)
                .then(if (selected) Modifier.background(c.accent) else Modifier.background(c.surface).border(1.dp, c.line, shape))
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(text, color = if (selected) c.onAccent else c.inkDim, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        }
    }
}

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
        catch (e: Exception) { Diag.fail("motion_setting", e); true }
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

/** Plain 48dp icon button (toolbar action). The description doubles as the accessibility label. */
@Composable
fun IconAction(icon: ImageVector, desc: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = LocalColors.current.ink) {
    Box(
        modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = desc, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, desc, tint = tint, modifier = Modifier.size(24.dp)) }
}

/**
 * Contextual top bar shown while selecting: close on the left, the count, then [actions] on the right.
 * Same height as [ScreenTitle] so the list doesn't jump when selection starts.
 */
@Composable
fun ContextBar(count: Int, onClose: () -> Unit, actions: @Composable RowScope.() -> Unit) {
    val c = LocalColors.current
    Row(
        Modifier.fillMaxWidth().background(c.surface).padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconAction(Icons.Rounded.Close, "Cancel selection", onClose)
        Text("$count selected", color = c.ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
        actions()
    }
}

/** Flat dialog action (no pill, no fill), the way system dialogs do it. */
@Composable
fun DialogButton(text: String, onClick: () -> Unit, danger: Boolean = false) {
    val c = LocalColors.current
    Box(
        Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = if (danger) c.decline else c.accent, fontSize = 15.sp, fontWeight = FontWeight.Medium) }
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

/** One-line messages for the user, drawn as a snackbar by [NoticeHost]. Safe to call from any thread; dropped when no screen is showing. */
object Notices {
    private val _flow = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 8)
    val flow: kotlinx.coroutines.flow.SharedFlow<String> = _flow
    fun show(message: String) { _flow.tryEmit(message) }
}

@Composable
fun NoticeHost(modifier: Modifier = Modifier) {
    val c = LocalColors.current
    val state = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { Notices.flow.collect { state.showSnackbar(it, duration = SnackbarDuration.Short) } }
    SnackbarHost(state, modifier) { data ->
        Box(
            Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(16.dp)).background(c.ink)
                .padding(horizontal = 18.dp, vertical = 14.dp).semantics { liveRegion = LiveRegionMode.Polite }
        ) { Text(data.visuals.message, color = c.bg, fontSize = 14.sp) }
    }
}

/** Puts [number] on the clipboard. Android 13+ shows its own "copied" confirmation, so only older versions get a toast. */
fun copyNumber(ctx: Context, number: String) {
    ctx.getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(android.content.ClipData.newPlainText("Phone number", number))
    if (android.os.Build.VERSION.SDK_INT < 33) Notices.show("Number copied")
}

/** Blocks or unblocks [number]. Returns the blocked state afterwards; when it can't change, says why and returns [blocked] as it was. */
suspend fun toggleBlocked(ctx: Context, number: String, blocked: Boolean): Boolean {
    val ok = withContext(Dispatchers.IO) {
        if (!BlockedRepo.canBlock(ctx)) null
        else if (blocked) BlockedRepo.unblock(ctx, number) else BlockedRepo.block(ctx, number)
    }
    when (ok) {
        null -> Notices.show("Set as default phone app to block numbers")
        false -> Notices.show("Couldn't update block list")
        true -> {}
    }
    return if (ok == true) !blocked else blocked
}

/** Long-press menu for a phone number: who it is, then edit, copy, block, and (for unsaved numbers) add contact. */
@Composable
fun NumberMenu(number: String, name: String?, photo: String?, onEdit: () -> Unit, onAddContact: (() -> Unit)?, onDismiss: () -> Unit) {
    val c = LocalColors.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var blocked by remember(number) { mutableStateOf(false) }
    LaunchedEffect(number) { blocked = withContext(Dispatchers.IO) { BlockedRepo.isBlocked(ctx, number) } }
    @Composable
    fun Item(icon: ImageVector, label: String, onClick: () -> Unit) {
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = c.ink, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(18.dp))
            Text(label, color = c.ink, fontSize = 16.sp)
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.clip(RoundedCornerShape(28.dp)).background(c.surface).padding(bottom = 8.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(name, photo, 44.dp)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        if (name != null) Text(name, color = c.ink, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(pretty(number) ?: number, color = c.inkDim, fontSize = 14.sp)
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
                Item(Icons.Rounded.Edit, "Edit number") { onDismiss(); onEdit() }
                Item(Icons.Rounded.ContentCopy, "Copy number") { copyNumber(ctx, number); onDismiss() }
                Item(Icons.Rounded.Block, if (blocked) "Unblock number" else "Block number") {
                    scope.launch { toggleBlocked(ctx, number, blocked); onDismiss() }
                }
                if (onAddContact != null) Item(Icons.Rounded.PersonAdd, "Add contact") { onDismiss(); onAddContact() }
            }
            Box(
                Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(28.dp)).background(c.surface).clickable(role = Role.Button, onClick = onDismiss),
                contentAlignment = Alignment.Center
            ) { Text("Cancel", color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium) }
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
                DialogButton("Cancel", onDismiss)
                DialogButton(confirm, onConfirm, danger = true)
            }
        }
    }
}

/** Number of destinations in [BottomBar]. The raised keypad button only looks right in the middle of 5; below that it's a [DialerFab]. */
const val BOTTOM_DESTINATIONS = 3

@Composable
fun DialerFab(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = LocalColors.current
    Box(
        modifier.size(60.dp).shadow(6.dp, CircleShape).clip(CircleShape).background(c.accent)
            .clickable(role = Role.Button, onClickLabel = "Open keypad", onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(Icons.Rounded.Dialpad, "Keypad", tint = c.onAccent, modifier = Modifier.size(28.dp)) }
}

/**
 * The destinations. With 5 of them the keypad button sits raised in the middle slot (it isn't a destination itself);
 * with fewer, the bar is just the tabs and the keypad opener is a [DialerFab] over the content.
 */
@Composable
fun BottomBar(selected: Int, onSelect: (Int) -> Unit, onDial: () -> Unit) {
    val c = LocalColors.current
    val tabs = listOf(Icons.Rounded.History to "Recents", Icons.Rounded.Contacts to "Contacts", Icons.Rounded.Settings to "Settings")
    val raisedMiddle = tabs.size >= 5
    Column(Modifier.fillMaxWidth().background(c.surface).navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(if (raisedMiddle) 72.dp else 64.dp), verticalAlignment = Alignment.CenterVertically) {
            tabs.forEachIndexed { i, (icon, label) ->
                if (raisedMiddle && i == tabs.size / 2) Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.offset(y = (-6).dp).size(56.dp).shadow(6.dp, CircleShape).clip(CircleShape).background(c.accent)
                            .clickable(role = Role.Button, onClickLabel = "Open keypad", onClick = onDial),
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Rounded.Dialpad, "Keypad", tint = c.onAccent, modifier = Modifier.size(26.dp)) }
                }
                val on = i == selected
                Column(
                    Modifier.weight(1f).fillMaxHeight().selectable(on, role = Role.Tab, onClick = { onSelect(i) }),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
                ) {
                    Icon(icon, null, tint = if (on) c.accent else c.inkDim, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.height(4.dp))
                    Text(label, color = if (on) c.accent else c.inkDim, fontSize = 11.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
    }
}

private val KEYS = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#")
private val LETTERS = mapOf("2" to "ABC", "3" to "DEF", "4" to "GHI", "5" to "JKL", "6" to "MNO", "7" to "PQRS", "8" to "TUV", "9" to "WXYZ", "0" to "+")

/** With [spread] the rows fill the width and the keys are spaced evenly across it (the dialer); otherwise they sit close together (in-call). */
@Composable
fun Keypad(keySize: Dp, onKey: (Char) -> Unit, modifier: Modifier = Modifier, onLongZero: (() -> Unit)? = null, spread: Boolean = false) {
    val c = LocalColors.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        KEYS.chunked(3).forEach { row ->
            Row(
                if (spread) Modifier.fillMaxWidth() else Modifier,
                horizontalArrangement = if (spread) Arrangement.SpaceBetween else Arrangement.spacedBy(22.dp)
            ) {
                row.forEach { k ->
                    Column(
                        Modifier.size(keySize).clip(CircleShape).background(c.surfaceHigh)
                            .combinedClickable(onClick = { onKey(k[0]) }, onLongClick = if (k == "0") onLongZero else null),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
                    ) {
                        Text(k, fontSize = 28.sp, color = c.ink)
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

/**
 * The app's own popup menu: a rounded card that grows out of its top-right corner. Put it inside the Box that holds its
 * button (48dp) and it opens just under it, right edges aligned. Items are [PopItem]s.
 */
@Composable
fun PopMenu(expanded: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    if (!expanded) return
    val c = LocalColors.current
    val motion = rememberMotionEnabled()
    val density = LocalDensity.current
    val shape = RoundedCornerShape(22.dp)
    val visible = remember { MutableTransitionState(!motion).apply { targetState = true } }
    androidx.compose.ui.window.Popup(
        alignment = Alignment.TopEnd,
        // the 10dp of padding below leaves room for the shadow; the offset cancels it so the card sits right under the button
        offset = with(density) { IntOffset(10.dp.roundToPx(), 36.dp.roundToPx()) },
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.PopupProperties(focusable = true)
    ) {
        androidx.compose.animation.AnimatedVisibility(
            visibleState = visible,
            enter = androidx.compose.animation.fadeIn(tween(120)) +
                    androidx.compose.animation.scaleIn(tween(170, easing = FastOutSlowInEasing), initialScale = 0.82f, transformOrigin = TransformOrigin(1f, 0f)),
            exit = androidx.compose.animation.ExitTransition.None
        ) {
            Column(
                Modifier.padding(10.dp).shadow(14.dp, shape).clip(shape).background(c.surfaceHigh)
                    .widthIn(min = 210.dp, max = 280.dp).padding(vertical = 6.dp),
                content = content
            )
        }
    }
}

/** One row of a [PopMenu]. [selected] gets a tinted pill and a check; [danger] is red. */
@Composable
fun PopItem(label: String, onClick: () -> Unit, icon: ImageVector? = null, selected: Boolean = false, danger: Boolean = false, enabled: Boolean = true) {
    val c = LocalColors.current
    val tint = when { danger -> c.decline; selected -> c.accent; else -> c.ink }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp).clip(RoundedCornerShape(16.dp))
            .background(if (selected) c.accent.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp).padding(horizontal = 12.dp).alpha(if (enabled) 1f else 0.38f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (selected || danger) tint else c.inkDim, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
        }
        Text(label, color = tint, fontSize = 16.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
        if (selected) {
            Spacer(Modifier.width(10.dp))
            Icon(Icons.Rounded.Check, null, tint = c.accent, modifier = Modifier.size(18.dp))
        }
    }
}

/** Filter button for inside a search field. Tinted when a filter is on. [menu] builds the [PopItem]s and gets a dismiss function. */
@Composable
fun FilterButton(active: Boolean, desc: String, menu: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit) {
    val c = LocalColors.current
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = desc) { open = true },
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Rounded.FilterList, desc, tint = if (active) c.accent else c.inkDim, modifier = Modifier.size(22.dp)) }
        PopMenu(open, { open = false }) { menu { open = false } }
    }
}
