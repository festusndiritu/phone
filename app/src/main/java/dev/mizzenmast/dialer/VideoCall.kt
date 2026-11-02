package dev.mizzenmast.dialer

import android.Manifest
import android.app.Activity
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.PhoneInTalk
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

/**
 * The video call screen: the remote picture fills the screen and yours floats in a draggable window. Tapping that window
 * swaps the two, and tapping the picture shows or hides the controls.
 */
@Composable
internal fun VideoCallScreen(call: CallInfo, audio: AudioUi?, inPip: Boolean, onMinimize: (() -> Unit)?) {
    val ui by CallManager.video.collectAsState()
    val container = LocalWindowInfo.current.containerSize
    val landscape = container.width > container.height
    VideoCallDefaults(call, audio)

    val remoteAspect = rememberRemoteAspect(ui, landscape)
    val localAspect = if (landscape) 1f / ui.previewAspect else ui.previewAspect
    val localAvailable = !inPip && call.sendingVideo && ui.camOn && !ui.switching
    var swapped by rememberSaveable { mutableStateOf(false) }
    val localIsBig = swapped && localAvailable
    var bigSurface by remember { mutableStateOf<Surface?>(null) }
    var smallSurface by remember { mutableStateOf<Surface?>(null) }
    BindVideoSurfaces(call.id, localIsBig, bigSurface, smallSurface)

    val bigAspect = if (localIsBig) localAspect else remoteAspect
    val smallAspect = if (localIsBig) remoteAspect else localAspect
    // Fill (crop, never stretch) when the picture has the screen's orientation; otherwise show it whole.
    val bigFit = if ((bigAspect >= 1f) == landscape) VideoFit.Fill else VideoFit.Fit

    val autoHide = rememberAutoHide()
    val controlsShown = autoHide.visible && !inPip
    var headerHeight by remember { mutableIntStateOf(0) }
    var controlsHeight by remember { mutableIntStateOf(0) }
    val pip = rememberPipPlacement(container, headerHeight, controlsHeight, controlsShown)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        VideoSurface(
            Modifier.fillMaxSize(),
            contentAspect = bigAspect,
            fit = bigFit,
            mirror = localIsBig && ui.front,
            onSurface = { bigSurface = it }
        )
        if (!inPip) TapToToggle(autoHide::toggle)
        ControlsFade(controlsShown, Modifier.align(Alignment.TopStart)) {
            VideoHeader(call, onMinimize, Modifier.onSizeChanged { headerHeight = it.height })
        }
        ControlsFade(controlsShown, Modifier.align(Alignment.BottomCenter)) {
            VideoControls(call, ui, audio, Modifier.onSizeChanged { controlsHeight = it.height }, onTouch = autoHide::keepAlive)
        }
        if (localAvailable) {
            SelfView(
                pip, container, smallAspect,
                mirror = !localIsBig && ui.front,
                onSurface = { smallSurface = it },
                onSwap = { swapped = !swapped }
            )
        }
    }
}

/** Video calls start on the loudspeaker, and the provider is told which way the screen is turned. */
@Composable
private fun VideoCallDefaults(call: CallInfo, audio: AudioUi?) {
    val ctx = LocalContext.current
    val orientation = LocalConfiguration.current.orientation
    LaunchedEffect(call.id) { if (audio?.route == AudioUi.EARPIECE) CallManager.setRoute(AudioUi.SPEAKER) }
    LaunchedEffect(call.id, orientation) {
        val rotation = (ctx as? Activity)?.display?.rotation ?: 0
        CallManager.setOrientation(call.id, rotation * 90)
    }
}

/** The remote picture's shape. The last good size is kept so a momentary 0x0 report doesn't reshape the picture. */
@Composable
private fun rememberRemoteAspect(ui: CallManager.VideoUi, landscape: Boolean): Float {
    val reported = if (ui.peerW > 0 && ui.peerH > 0) ui.peerW.toFloat() / ui.peerH else 0f
    var last by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(reported) { if (reported > 0f) last = reported }
    return if (last > 0f) last else if (landscape) 4f / 3f else 3f / 4f
}

/**
 * Two surfaces live in two fixed slots (big and small). Swapping only re-points the provider's display and preview
 * surfaces at the other slot, so no view is rebuilt.
 */
@Composable
private fun BindVideoSurfaces(callId: Int, localIsBig: Boolean, big: Surface?, small: Surface?) {
    DisposableEffect(callId, localIsBig, big, small) {
        CallManager.setDisplaySurface(callId, if (localIsBig) small else big)
        CallManager.setPreviewSurface(callId, if (localIsBig) big else small)
        onDispose {
            CallManager.setDisplaySurface(callId, null)
            CallManager.setPreviewSurface(callId, null)
        }
    }
}

@Stable
private class AutoHide {
    var visible by mutableStateOf(true)
    var touches by mutableIntStateOf(0)

    fun toggle() { visible = !visible }
    fun keepAlive() { touches++ }
}

/** Controls hide themselves a few seconds after the last touch. */
@Composable
private fun rememberAutoHide(): AutoHide {
    val state = remember { AutoHide() }
    LaunchedEffect(state.visible, state.touches) {
        if (state.visible) { delay(4500.milliseconds); state.visible = false }
    }
    return state
}

@Composable
private fun ControlsFade(visible: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    val motion = rememberMotionEnabled()
    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = if (motion) fadeIn() else androidx.compose.animation.EnterTransition.None,
        exit = if (motion) fadeOut() else androidx.compose.animation.ExitTransition.None
    ) { content() }
}

@Composable
private fun TapToToggle(onToggle: () -> Unit) {
    Box(
        Modifier.fillMaxSize()
            .semantics { onClick("Show or hide call controls") { onToggle(); true } }
            .pointerInput(Unit) { detectTapGestures(onTap = { onToggle() }) }
    )
}

@Composable
private fun VideoHeader(call: CallInfo, onMinimize: (() -> Unit)?, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)))
            .statusBarsPadding().padding(20.dp)
    ) {
        if (onMinimize != null) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White.copy(alpha = 0.18f))
                    .clickable(role = Role.Button, onClickLabel = "Minimize video", onClick = onMinimize)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.PictureInPictureAlt, null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Minimize", color = Color.White, fontSize = 13.sp)
            }
            Spacer(Modifier.height(14.dp))
        }
        Text(
            call.title(),
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 200.dp)
        )
        CallClock(call.connectedAt)
    }
}

@Composable
private fun VideoControls(call: CallInfo, ui: CallManager.VideoUi, audio: AudioUi?, modifier: Modifier = Modifier, onTouch: () -> Unit) {
    val c = LocalColors.current
    val speaker = audio?.route == AudioUi.SPEAKER
    val muted = audio?.muted == true
    Column(
        modifier.fillMaxWidth()
            // Any touch restarts the auto-hide timer without being consumed.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) { awaitPointerEvent(PointerEventPass.Initial); onTouch() } } }
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
            .navigationBarsPadding().padding(start = 12.dp, end = 12.dp, top = 48.dp, bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Top) {
            VideoBtn(
                Modifier.weight(1f),
                if (muted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                if (muted) "Unmute" else "Mute",
                muted
            ) { CallManager.setMuted(!muted) }
            if (call.sendingVideo) {
                VideoBtn(
                    Modifier.weight(1f),
                    if (ui.camOn) Icons.Rounded.Videocam else Icons.Rounded.VideocamOff,
                    if (ui.camOn) "Stop video" else "Start video",
                    !ui.camOn
                ) { CallManager.toggleCamera() }
                // Always present (dimmed when the camera is off or mid-swap) so the row never shifts around.
                VideoBtn(
                    Modifier.weight(1f),
                    Icons.Rounded.Cameraswitch,
                    if (ui.switching) "Switching…" else if (ui.front) "Rear camera" else "Front camera",
                    false,
                    enabled = ui.camOn && !ui.switching
                ) { CallManager.flipCamera() }
            }
            VideoBtn(
                Modifier.weight(1f),
                Icons.AutoMirrored.Rounded.VolumeUp,
                if (speaker) "Speaker on" else "Speaker off",
                speaker
            ) { CallManager.setRoute(if (speaker) AudioUi.EARPIECE else AudioUi.SPEAKER) }
            VideoBtn(Modifier.weight(1f), Icons.Rounded.PhoneInTalk, "Voice only", false) { CallManager.requestVideo(call.id, false) }
        }
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier
                .fillMaxWidth(0.7f)
                .heightIn(min = 52.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(c.decline)
                .clickable(role = Role.Button, onClickLabel = "End call") { CallManager.disconnect(call.id) },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.CallEnd, null, tint = Color.White, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text("End call", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
    }
}

private const val PIP_END = 1
private const val PIP_BOTTOM = 2

/** Where the small window is: resting in a corner, or following a finger. */
@Stable
private class PipPlacement {
    var size by mutableStateOf(IntSize.Zero)
    var corner by mutableIntStateOf(PIP_END)
    var placed by mutableStateOf(false)
    var dragging by mutableStateOf<Offset?>(null)
    val resting = Animatable(Offset.Zero, Offset.VectorConverter)

    val position: Offset get() = dragging ?: resting.value

    fun startDrag() { dragging = resting.value }

    fun dragBy(amount: Offset, container: IntSize) {
        val next = (dragging ?: resting.value) + amount
        dragging = Offset(
            next.x.coerceIn(0f, (container.width - size.width).toFloat().coerceAtLeast(0f)),
            next.y.coerceIn(0f, (container.height - size.height).toFloat().coerceAtLeast(0f))
        )
    }

    /** Lets go of the window; it settles into the corner nearest to where it was dropped. */
    suspend fun drop(container: IntSize) {
        val at = dragging ?: return
        resting.snapTo(at)
        val cx = at.x + size.width / 2f
        val cy = at.y + size.height / 2f
        corner = (if (cx > container.width / 2f) PIP_END else 0) or (if (cy > container.height / 2f) PIP_BOTTOM else 0)
        dragging = null
    }
}

/**
 * Keeps the small window in its corner, clear of the header and the control bar while those are showing.
 * Only the top-left corner has to clear the header.
 */
@Composable
private fun rememberPipPlacement(container: IntSize, headerHeight: Int, controlsHeight: Int, controlsShown: Boolean): PipPlacement {
    val density = LocalDensity.current
    val motion = rememberMotionEnabled()
    val topInset = WindowInsets.statusBars.getTop(density)
    val bottomInset = WindowInsets.navigationBars.getBottom(density)
    val margin = with(density) { 16.dp.roundToPx() }
    val pip = remember { PipPlacement() }

    val clearBottom = if (controlsShown) maxOf(controlsHeight, bottomInset) else bottomInset
    val clearTop = if (controlsShown) maxOf(headerHeight, topInset) else topInset
    val atEnd = pip.corner and PIP_END != 0
    val target = Offset(
        if (atEnd) (container.width - pip.size.width - margin).toFloat() else margin.toFloat(),
        if (pip.corner and PIP_BOTTOM != 0) (container.height - pip.size.height - clearBottom - margin).toFloat()
        else ((if (atEnd) topInset else clearTop) + margin).toFloat()
    )
    LaunchedEffect(target, pip.dragging == null, pip.size.width > 0) {
        if (pip.dragging != null || pip.size.width == 0) return@LaunchedEffect
        if (!pip.placed || !motion) { pip.resting.snapTo(target); pip.placed = true }
        else pip.resting.animateTo(target, spring(stiffness = Spring.StiffnessMediumLow))
    }
    return pip
}

/**
 * Your picture in a small window. It takes the exact shape of the picture inside (nothing cropped or stretched),
 * can be dragged anywhere, and a tap swaps it with the big picture.
 */
@Composable
private fun SelfView(
    pip: PipPlacement,
    container: IntSize,
    aspect: Float,
    mirror: Boolean,
    onSurface: (Surface?) -> Unit,
    onSwap: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val swap by rememberUpdatedState(onSwap)
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier
            .offset { IntOffset(pip.position.x.roundToInt(), pip.position.y.roundToInt()) }
            .alpha(if (pip.placed) 1f else 0f)
            .width(if (aspect >= 1f) 150.dp else 108.dp).aspectRatio(aspect)
            .onSizeChanged { pip.size = it }
            .clip(shape)
            .border(1.dp, Color.White.copy(alpha = 0.25f), shape)
    ) {
        VideoSurface(Modifier.matchParentSize(), contentAspect = aspect, fit = VideoFit.Fill, mirror = mirror, onSurface = onSurface)
        Box(
            Modifier.matchParentSize()
                .semantics { onClick("Swap the two videos") { swap(); true } }
                .pointerInput(Unit) { detectTapGestures(onTap = { swap() }) }
                .pointerInput(container) {
                    detectDragGestures(
                        onDragStart = { pip.startDrag() },
                        onDragEnd = { scope.launch { pip.drop(container) } },
                        onDragCancel = { scope.launch { pip.drop(container) } },
                        onDrag = { change, amount -> change.consume(); pip.dragBy(amount, container) }
                    )
                }
        )
    }
}

/** A TextureView whose Surface is handed to the carrier's video provider (null again when it goes away). */
@Composable
private fun VideoSurface(modifier: Modifier, contentAspect: Float, fit: VideoFit, mirror: Boolean = false, onSurface: (Surface?) -> Unit) {
    val latest by rememberUpdatedState(onSurface)
    val look by rememberUpdatedState(Triple(contentAspect, fit, mirror))
    AndroidView(modifier = modifier, factory = { ctx ->
        TextureView(ctx).apply {
            isOpaque = true // no alpha blending, a little cheaper per frame
            addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> (v as TextureView).applyAspect(look.first, look.second, look.third) }
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                private var surface: Surface? = null
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) { surface = Surface(st); latest(surface) }
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { latest(null); surface?.release(); surface = null; return true }
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
        }
    }, update = { it.applyAspect(contentAspect, fit, mirror) })
}

/** Round icon with a plain-words label under it, so you can tell what a tap will do. Highlighted = the feature is currently engaged. */
@Composable
private fun VideoBtn(modifier: Modifier = Modifier, icon: ImageVector, label: String, on: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val tick = rememberTick()
    Column(
        modifier
            .alpha(if (enabled) 1f else 0.38f)
            .clip(RoundedCornerShape(16.dp))
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClickLabel = label
            ) { tick(); onClick() }
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(54.dp).clip(CircleShape).background(if (on) Color.White else Color.White.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) { Icon(icon, null, tint = if (on) Color.Black else Color.White) }
        Spacer(Modifier.height(6.dp))
        Text(label, color = Color.White, fontSize = 12.sp, maxLines = 1, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun VideoRequestDialog(call: CallInfo) {
    val c = LocalColors.current
    val accept = rememberCameraGate { ok -> CallManager.answerVideoRequest(call.id, true, receiveOnly = !ok) }
    Dialog(onDismissRequest = { CallManager.answerVideoRequest(call.id, false) }) {
        Column(
            Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(c.surface)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Rounded.Videocam, null, tint = c.accent, modifier = Modifier.size(32.dp))
            Spacer(Modifier.height(12.dp))
            Text(
                "${call.title()} wants to switch to video",
                color = c.ink,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextPill("Decline", { CallManager.answerVideoRequest(call.id, false) })
                TextPill("Accept", { accept() }, filled = true)
            }
        }
    }
}

/** Runs [onResult] with true if the camera permission is (or just became) granted. */
@Composable
internal fun rememberCameraGate(onResult: (Boolean) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val latest by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { latest(it) }
    return remember(launcher) { { if (ctx.granted(Manifest.permission.CAMERA)) latest(true) else launcher.launch(Manifest.permission.CAMERA) } }
}

private enum class VideoFit { Fit, Fill }

/**
 * A TextureView stretches whatever the provider draws to the view's size, which is what squashed faces.
 * This corrects it with a matrix so the picture always keeps [contentAspect] (width / height):
 * Fit = whole picture with bars, Fill = fills the view and crops the overflow.
 */
private fun TextureView.applyAspect(contentAspect: Float, fit: VideoFit, mirror: Boolean) {
    val w = width.toFloat(); val h = height.toFloat()
    if (w <= 0f || h <= 0f || contentAspect <= 0f) { setTransform(null); return }
    val wider = contentAspect > w / h
    val cw: Float; val ch: Float
    if (wider == (fit == VideoFit.Fit)) { cw = w; ch = w / contentAspect } else { ch = h; cw = h * contentAspect }
    setTransform(Matrix().apply { setScale((if (mirror) -cw else cw) / w, ch / h, w / 2f, h / 2f) })
}
