
package dev.mizzenmast.dialer

import android.Manifest
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.util.Rational
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.Brush
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.viewinterop.AndroidView
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.telecom.Call
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.automirrored.rounded.CallMerge
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.Headset
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PhoneInTalk
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SwapCalls
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

class InCallActivity : ComponentActivity() {
    private var proxLock: PowerManager.WakeLock? = null
    private var keepOn = false
    private var inPip by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Core.init(this)
        val cid = CallManager.currentCallId()
        Diag.log("activity_created", cid, "recreated=${savedInstanceState != null}")

        enableEdgeToEdge()

        setContent {
            val mode by Prefs.theme.collectAsState()
            DialerTheme(mode) { InCallRoot(onAddCall = ::openDialpad, inPip = inPip, onMinimize = if (canPip()) ::enterPip else null) }
        }
        Diag.log("content_set", cid)
        if (savedInstanceState == null) consumeIntent(intent)

        // Keep the PiP window's shape and auto-enter flag in step with the call.
        lifecycleScope.launch {
            combine(CallManager.calls, CallManager.video) { l, v -> l to v }.collect {
                if (canPip()) try { setPictureInPictureParams(pipParams()) } catch (e: Exception) { Diag.fail("pip_params", e) }
            }
        }
        lifecycleScope.launch {
            combine(CallManager.calls, CallManager.audio) { l, a -> l to a }.collect { (list, audio) ->
                if (list.isEmpty()) finishAndRemoveTask() else { updateProximity(list, audio); updateKeepOn(list) }
            }
        }
    }

    private fun canPip() = packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun videoCallActive() = CallManager.calls.value.any { it.parentId == null && !it.isEnded && it.isVideo && it.state == Call.STATE_ACTIVE }

    private fun pipParams(): PictureInPictureParams {
        val v = CallManager.video.value
        val ratio = (if (v.peerW > 0 && v.peerH > 0) v.peerW.toFloat() / v.peerH else 3f / 4f).coerceIn(0.45f, 2.3f)
        val b = PictureInPictureParams.Builder().setAspectRatio(Rational((ratio * 1000).toInt(), 1000))
        if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(videoCallActive())
        return b.build()
    }

    /** Shrinks the call into a floating window so video keeps flowing while you use other apps. */
    private fun enterPip() {
        if (canPip() && videoCallActive()) try { enterPictureInPictureMode(pipParams()) } catch (e: Exception) { Diag.fail("enter_pip", e) }
    }

    // Android 12+ enters PiP by itself (auto-enter); older versions need this nudge when you press Home or swipe away.
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < 31) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Diag.log("activity_new_intent", CallManager.currentCallId())
        consumeIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        CallNotifications.uiResumed = true
        CallManager.setUiVisible(true)
        Diag.log("activity_resumed", CallManager.currentCallId())
    }

    private fun consumeIntent(i: Intent?) {
        if (i?.getStringExtra(CallNotifications.EXTRA_ACTION) == "answer") {
            i.removeExtra(CallNotifications.EXTRA_ACTION)
            CallManager.ringing()?.let { CallManager.answer(it.id) }
        }
    }

    private fun openDialpad() {
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_DIALER, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun updateProximity(list: List<CallInfo>, audio: AudioUi?) {
        val active = list.any { !it.isEnded && (it.state == Call.STATE_ACTIVE || it.state == Call.STATE_DIALING || it.state == Call.STATE_CONNECTING) }
        val videoOn = list.any { it.isVideo && !it.isEnded } // face is not at the ear during video
        val need = active && !videoOn && audio?.route == AudioUi.EARPIECE
        val pm = getSystemService(PowerManager::class.java)
        if (need) {
            if (proxLock == null && pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                proxLock = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "dialer:proximity").also { it.acquire(4 * 60 * 60 * 1000L) }
            }
        } else releaseProximity()
    }

    /**
     * Ringing and video always keep the screen on. Otherwise, only when "Power button ends call" is on (a timed-out screen could
     * then only be woken by hanging up), which is also assumed when the setting can't be read.
     */
    private fun updateKeepOn(list: List<CallInfo>) {
        val live = list.filter { !it.isEnded }
        val special = live.any { it.state == Call.STATE_RINGING || it.isVideo }
        val need = live.isNotEmpty() && (special || powerButtonEndsCall())
        if (need == keepOn) return
        keepOn = need
        Diag.log("keep_screen_on", CallManager.currentCallId(), "on=$need powerButtonEndsCall=${powerButtonEndsCall()}")
        if (need) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** Settings.Secure.INCALL_POWER_BUTTON_BEHAVIOR is hidden from the SDK; its key is "incall_power_button_behavior", 1 = screen off, 2 = hang up. */
    private fun powerButtonEndsCall(): Boolean = try {
        android.provider.Settings.Secure.getInt(contentResolver, "incall_power_button_behavior", 1) == 2
    } catch (e: Exception) { Diag.fail("power_button_ends_call", e); true }

    private fun releaseProximity() {
        proxLock?.let { if (it.isHeld) it.release() }
        proxLock = null
    }

    override fun onPause() {
        CallNotifications.uiResumed = false
        // The camera is NOT released here: an activity in picture-in-picture is paused but still on screen.
        Diag.log("activity_paused", CallManager.currentCallId(), "finishing=$isFinishing")
        super.onPause()
    }

    override fun onStop() {
        CallManager.setUiVisible(false) // screen really off (not PiP): release the camera
        Diag.log("activity_stopped", CallManager.currentCallId(), "pip=$inPip")
        super.onStop()
    }

    override fun onDestroy() {
        Diag.log("activity_destroyed", CallManager.currentCallId(), "finishing=$isFinishing")
        releaseProximity()
        super.onDestroy()
    }
}

@Composable
fun InCallRoot(onAddCall: () -> Unit, inPip: Boolean = false, onMinimize: (() -> Unit)? = null) {
    val calls by CallManager.calls.collectAsState()
    val audio by CallManager.audio.collectAsState()
    val c = LocalColors.current
    val top = calls.filter { it.parentId == null }
    val live = top.filter { !it.isEnded }
    val ringing = live.firstOrNull { it.state == Call.STATE_RINGING }
    val primary = CallManager.pickPrimary(live.ifEmpty { top })
    val showVideo = ringing == null && primary != null && primary.isVideo && primary.state == Call.STATE_ACTIVE
    Box(Modifier.fillMaxSize().background(if (showVideo) Color.Black else c.bg).then(if (showVideo) Modifier else Modifier.systemBarsPadding())) {
        when {
            ringing != null -> IncomingScreen(ringing, live.filter { it.id != ringing.id })
            primary != null && showVideo -> VideoCallScreen(primary, audio, inPip, onMinimize)
            primary != null -> OngoingScreen(primary, top, calls, audio, onAddCall)
        }
        primary?.takeIf { it.videoRequest != 0 && !it.isEnded }?.let { VideoRequestDialog(it) }
        NoticeHost(Modifier.align(Alignment.TopCenter).padding(top = 12.dp).then(if (showVideo) Modifier.statusBarsPadding() else Modifier))
    }
}

@Composable
fun SimChoice(label: String, onClick: () -> Unit) {
    val c = LocalColors.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).clip(RoundedCornerShape(20.dp)).background(c.surface)
            .clickable(role = Role.Button, onClickLabel = "Call with $label", onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(c.accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.SimCard, null, tint = c.accent, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(14.dp))
        Text(label, color = c.ink, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Icon(Icons.Rounded.ChevronRight, null, tint = c.inkDim)
    }
}

@Composable
private fun IncomingScreen(call: CallInfo, others: List<CallInfo>) {
    val c = LocalColors.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val hints by Prefs.gestureHints.collectAsState()
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(24.dp))
        Text(when { others.isNotEmpty() -> "CALL WAITING"; call.isVideo -> "INCOMING VIDEO CALL"; else -> "INCOMING CALL" }, style = T.label, color = c.accent)
        Spacer(Modifier.height(28.dp))
        RingingAvatar(call.name, call.photo, 132.dp)
        Spacer(Modifier.height(24.dp))
        Text(call.title(), color = c.ink, fontSize = 34.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (call.name != null && call.number != null) {
            Spacer(Modifier.height(6.dp))
            Text(pretty(call.number) ?: call.number, color = c.inkDim, fontSize = 18.sp)
        }
        call.sim?.let { Spacer(Modifier.height(10.dp)); SimChip(it) }
        Spacer(Modifier.weight(1f))
        if (others.isNotEmpty()) {
            Text("On a call with ${others.first().title()}", color = c.inkDim, fontSize = 14.sp, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextPill("End & answer", { CallManager.endAndAnswer(call.id) })
                TextPill("Silence", { CallManager.silence(ctx) })
            }
        } else {
            val answerVideo = rememberCameraGate { ok -> CallManager.answerVideo(call.id, ok) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextPill("Silence", { CallManager.silence(ctx) })
                if (call.isVideo) TextPill("Answer with video", { answerVideo() }, filled = true)
            }
        }
        Spacer(Modifier.height(24.dp))
        SwipeDecision(call.id, onAnswer = { CallManager.answer(call.id) }, onDecline = { CallManager.reject(call.id) })
        if (hints) {
            Spacer(Modifier.height(12.dp))
            Text(
                if (others.isEmpty()) "Swipe right to answer, left to decline" else "Right: hold & answer · Left: decline",
                color = c.inkDim, fontSize = 12.sp, textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

private fun fmtDur(sec: Long): String {
    val s = if (sec < 0) 0 else sec
    val h = s / 3600; val m = (s % 3600) / 60; val r = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, r) else "%02d:%02d".format(m, r)
}

private class Ctl(val icon: ImageVector, val label: String, val on: Boolean, val enabled: Boolean, val onClick: () -> Unit)

@Composable
private fun CtlButton(ctl: Ctl) {
    val c = LocalColors.current
    val tick = rememberTick()
    Column(Modifier.width(96.dp).alpha(if (ctl.enabled) 1f else 0.38f), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(78.dp).clip(CircleShape).background(if (ctl.on) c.ink else c.surface)
                .clickable(enabled = ctl.enabled, role = Role.Button, onClick = { tick(); ctl.onClick() }),
            contentAlignment = Alignment.Center
        ) { Icon(ctl.icon, null, tint = if (ctl.on) c.bg else c.ink, modifier = Modifier.size(34.dp)) }
        Spacer(Modifier.height(8.dp))
        Text(ctl.label, color = c.inkDim, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
private fun OngoingScreen(call: CallInfo, top: List<CallInfo>, all: List<CallInfo>, audio: AudioUi?, onAddCall: () -> Unit) {
    val c = LocalColors.current
    var keypad by rememberSaveable { mutableStateOf(false) }
    var routes by remember { mutableStateOf(false) }
    var typed by remember(call.id) { mutableStateOf("") }
    val isActive = call.state == Call.STATE_ACTIVE
    // The clock only ticks while the call screen is on screen: a call that runs for an hour behind another app must not wake the CPU every second.
    val visible by CallManager.uiVisible.collectAsState()
    val now by produceState(System.currentTimeMillis(), isActive, visible) {
        while (isActive && visible) { value = System.currentTimeMillis(); delay(1000.milliseconds) }
    }

    val ctx = androidx.compose.ui.platform.LocalContext.current
    val askVideo = rememberCameraGate { ok ->
        if (ok) CallManager.requestVideo(call.id, true) else Notices.show("Camera permission is needed for video")
    }
    val vui by CallManager.video.collectAsState()
    val others = top.filter { it.id != call.id && !it.isEnded }
    val status = when (call.state) {
        Call.STATE_ACTIVE -> if (call.connectedAt > 0) fmtDur((now - call.connectedAt) / 1000) else "Connected"
        Call.STATE_HOLDING -> "On hold"
        Call.STATE_DIALING -> "Calling…"
        Call.STATE_CONNECTING -> "Connecting…"
        Call.STATE_SELECT_PHONE_ACCOUNT -> "Choose a SIM"
        Call.STATE_DISCONNECTING -> "Ending…"
        Call.STATE_DISCONNECTED -> endText(call.endCode)
        else -> "Preparing…"
    }
    val statusColor = when (call.state) {
        Call.STATE_ACTIVE -> c.answer
        Call.STATE_HOLDING -> c.hold
        Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTING -> c.decline
        else -> c.inkDim
    }
    val live = call.state != Call.STATE_DISCONNECTED && call.state != Call.STATE_DISCONNECTING
    val connected = call.state == Call.STATE_ACTIVE || call.state == Call.STATE_HOLDING

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        others.forEach { o ->
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(16.dp)).background(c.surface).padding(start = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                    Text(o.title(), color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (o.state == Call.STATE_HOLDING) "On hold" else stateName(o.state).lowercase().replaceFirstChar { it.uppercase() }, color = c.hold, fontSize = 12.sp)
                }
                if (o.state == Call.STATE_HOLDING) TextPill("Swap", { CallManager.swapTo(o.id) }, Modifier.padding(end = 8.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(status, color = statusColor, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
        Spacer(Modifier.height(16.dp))
        Avatar(call.name, call.photo, 104.dp)
        Spacer(Modifier.height(14.dp))
        Text(call.title(), color = c.ink, fontSize = 28.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (call.name != null && call.number != null) Text(pretty(call.number) ?: call.number, color = c.inkDim, fontSize = 15.sp)
        call.sim?.let { Spacer(Modifier.height(8.dp)); SimChip(it) }
        if (call.isConference) {
            Spacer(Modifier.height(8.dp))
            call.childIds.mapNotNull { id -> all.firstOrNull { it.id == id } }.forEach { ch ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(ch.title(), color = c.inkDim, fontSize = 14.sp)
                    if (call.canSplit || ch.canSplit) TextPill("Split", { CallManager.split(ch.id) }, Modifier.padding(start = 8.dp))
                }
            }
        }
        Spacer(Modifier.weight(1f))

        when {
            call.state == Call.STATE_SELECT_PHONE_ACCOUNT -> {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("CALL WITH", style = T.label, color = c.accent, modifier = Modifier.align(Alignment.Start).padding(start = 4.dp))
                    call.sims.forEach { sim -> SimChoice(sim.label) { CallManager.selectSim(call.id, sim.handle) } }
                    if (call.sims.isEmpty()) Text("No SIM available", color = c.inkDim)
                    Spacer(Modifier.height(4.dp))
                    TextPill("Cancel", { CallManager.disconnect(call.id) }, danger = true)
                    Spacer(Modifier.height(8.dp))
                }
            }
            keypad && live -> {
                Text(typed.takeLast(18), color = c.ink, fontSize = 26.sp, fontWeight = FontWeight.Light, modifier = Modifier.height(36.dp))
                Keypad(60.dp, onKey = { ch ->
                    typed += ch
                    CallManager.dtmf(call.id, ch)
                }, modifier = Modifier)
                // Telecom needs an explicit stop after each tone
                LaunchedEffect(typed) { if (typed.isNotEmpty()) { delay(140.milliseconds); CallManager.dtmfStop(call.id) } }
                Spacer(Modifier.height(8.dp))
                TextPill("Hide keypad", { keypad = false })
            }
            else -> {
                val muted = audio?.muted == true
                val route = audio?.route ?: AudioUi.EARPIECE
                val mask = audio?.mask ?: 0
                val speaker = route == AudioUi.SPEAKER
                val multi = (mask and (AudioUi.BLUETOOTH or AudioUi.WIRED)) != 0
                val btName = if (route == AudioUi.BLUETOOTH) audio?.btName else null
                val (routeIcon, routeLabel) = when (route) {
                    AudioUi.SPEAKER -> Icons.AutoMirrored.Rounded.VolumeUp to "Speaker"
                    AudioUi.BLUETOOTH -> Icons.Rounded.Bluetooth to (btName?.takeIf { it.isNotBlank() }
                        ?.let { if (it.length > 11) it.take(10) + "…" else it } ?: "Bluetooth")
                    AudioUi.WIRED -> Icons.Rounded.Headset to "Headset"
                    else -> Icons.Rounded.PhoneInTalk to if (multi) "Audio" else "Speaker"
                }

                val heldOther = others.firstOrNull { it.state == Call.STATE_HOLDING }
                val controls = listOfNotNull(
                    Ctl(if (muted) Icons.Rounded.MicOff else Icons.Rounded.Mic, "Mute", muted, live) { CallManager.setMuted(!muted) },
                    Ctl(Icons.Rounded.Dialpad, "Keypad", false, connected || call.state == Call.STATE_DIALING) { keypad = true },
                    Ctl(routeIcon, routeLabel, speaker || route == AudioUi.BLUETOOTH, live) {
                        if (multi) routes = true
                        else CallManager.setRoute(if (speaker) AudioUi.EARPIECE else AudioUi.SPEAKER)
                    },
                    Ctl(if (call.state == Call.STATE_HOLDING) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                        if (call.state == Call.STATE_HOLDING) "Resume" else "Hold", call.state == Call.STATE_HOLDING,
                        call.canHold && connected) { CallManager.toggleHold(call.id) },
                    Ctl(Icons.Rounded.PersonAdd, "Add call", false, connected && others.isEmpty(), onAddCall),
                    if (call.canVideo && !call.isVideo) Ctl(Icons.Rounded.Videocam, if (vui.pendingUp) "Asking…" else "Video", vui.pendingUp, call.state == Call.STATE_ACTIVE && !vui.pendingUp) { askVideo() } else null,
                    when {
                        call.canMerge && connected -> Ctl(Icons.AutoMirrored.Rounded.CallMerge, "Merge",
                            on = false,
                            enabled = true
                        ) { CallManager.merge(call.id) }
                        heldOther != null && isActive -> Ctl(Icons.Rounded.SwapCalls, "Swap",
                            on = false,
                            enabled = true
                        ) { CallManager.swapTo(heldOther.id) }
                        call.canSwap -> Ctl(Icons.Rounded.SwapCalls, "Swap",
                            on = false,
                            enabled = true
                        ) { CallManager.swapTo(call.id) }
                        else -> null
                    }
                )
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    controls.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { row.forEach { CtlButton(it) } }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        if (live) RoundButton(Icons.Rounded.CallEnd, "End call", c.decline, Color.White, 72.dp) { CallManager.disconnect(call.id) }
        else Spacer(Modifier.height(72.dp))
        Spacer(Modifier.height(8.dp))
    }

    if (routes) {
        val mask = audio?.mask ?: 0
        val cur = audio?.route ?: 0
        Dialog(onDismissRequest = { routes = false }) {
            Column(Modifier.clip(RoundedCornerShape(24.dp)).background(c.surface).padding(vertical = 12.dp)) {
                listOf(
                    Triple(AudioUi.EARPIECE, "Phone", Icons.Rounded.PhoneInTalk),
                    Triple(AudioUi.SPEAKER, "Speaker", Icons.AutoMirrored.Rounded.VolumeUp),
                    Triple(AudioUi.BLUETOOTH, "Bluetooth", Icons.Rounded.Bluetooth),
                    Triple(AudioUi.WIRED, "Wired headset", Icons.Rounded.Headset)
                ).filter { (mask and it.first) != 0 }.forEach { (r, label, icon) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { CallManager.setRoute(r); routes = false }.padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(icon, null, tint = if (cur == r) c.accent else c.inkDim)
                        Spacer(Modifier.width(16.dp))
                        Text(label, color = if (cur == r) c.accent else c.ink, fontSize = 17.sp, fontWeight = if (cur == r) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
        }
    }
}

/** Runs [onResult] with true if the camera permission is (or just became) granted. */
@Composable
private fun rememberCameraGate(onResult: (Boolean) -> Unit): () -> Unit {
    val ctx = androidx.compose.ui.platform.LocalContext.current
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
        modifier.alpha(if (enabled) 1f else 0.38f).clip(RoundedCornerShape(16.dp)).clickable(enabled = enabled, role = Role.Button, onClickLabel = label) { tick(); onClick() }.padding(vertical = 4.dp),
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

/** Own composable so the once-a-second tick doesn't recompose the whole video screen. */
@Composable
private fun CallClock(connectedAt: Long) {
    val visible by CallManager.uiVisible.collectAsState()
    val now by produceState(System.currentTimeMillis(), visible) { while (visible) { value = System.currentTimeMillis(); delay(1000.milliseconds) } }
    Text(if (connectedAt > 0) fmtDur((now - connectedAt) / 1000) else "Connected", color = Color.White.copy(alpha = 0.75f), fontSize = 14.sp)
}

@Composable
private fun VideoCallScreen(call: CallInfo, audio: AudioUi?, inPip: Boolean, onMinimize: (() -> Unit)?) {
    val c = LocalColors.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val cfg = LocalConfiguration.current
    val ui by CallManager.video.collectAsState()
    val speaker = audio?.route == AudioUi.SPEAKER
    val muted = audio?.muted == true
    val remoteAspect = if (ui.peerW > 0 && ui.peerH > 0) ui.peerW.toFloat() / ui.peerH else 0f
    // Keep the last good size so a momentary 0x0 report doesn't snap the picture to a different shape.
    val lockedRemote = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(remoteAspect) { if (remoteAspect > 0f) lockedRemote.floatValue = remoteAspect }
    val landscape = cfg.screenWidthDp > cfg.screenHeightDp
    val previewAspect = if (landscape) 1f / ui.previewAspect else ui.previewAspect // camera capabilities are reported portrait-first

    // Video calls start on the loudspeaker, like Google Phone.
    LaunchedEffect(call.id) { if (audio?.route == AudioUi.EARPIECE) CallManager.setRoute(AudioUi.SPEAKER) }
    LaunchedEffect(call.id, cfg.orientation) {
        val rotation = (ctx as? android.app.Activity)?.windowManager?.defaultDisplay?.rotation ?: 0
        CallManager.setOrientation(call.id, rotation * 90)
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        VideoSurface(
            Modifier.fillMaxSize(),
            contentAspect = if (lockedRemote.floatValue > 0f) lockedRemote.floatValue else if (landscape) 4f / 3f else 3f / 4f,
            fit = VideoFit.Fit,
            onSurface = { CallManager.setDisplaySurface(call.id, it) }
        )
        if (!inPip) Column(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(20.dp)) {
            if (onMinimize != null) {
                Row(
                    Modifier.clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = 0.18f))
                        .clickable(role = Role.Button, onClickLabel = "Minimize video", onClick = onMinimize).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.PictureInPictureAlt, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Minimize", color = Color.White, fontSize = 13.sp)
                }
                Spacer(Modifier.height(14.dp))
            }
            Text(call.title(), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp))
            CallClock(call.connectedAt)
        }
        if (!inPip && call.sendingVideo && ui.camOn && !ui.switching) {
            // Fixed-size self view: the picture is cropped to fit, never stretched, and the box doesn't resize on flip.
            VideoSurface(
                Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(16.dp)
                    .width(if (landscape) 150.dp else 100.dp).aspectRatio(if (landscape) 4f / 3f else 3f / 4f)
                    .clip(RoundedCornerShape(16.dp)),
                contentAspect = previewAspect, fit = VideoFit.Fill, mirror = ui.front,
                onSurface = { CallManager.setPreviewSurface(call.id, it) }
            )
        }
        if (!inPip) Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                .navigationBarsPadding().padding(start = 12.dp, end = 12.dp, top = 48.dp, bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Top) {
                VideoBtn(Modifier.weight(1f), if (muted) Icons.Rounded.MicOff else Icons.Rounded.Mic, if (muted) "Unmute" else "Mute", muted) { CallManager.setMuted(!muted) }
                if (call.sendingVideo) {
                    VideoBtn(Modifier.weight(1f), if (ui.camOn) Icons.Rounded.Videocam else Icons.Rounded.VideocamOff, if (ui.camOn) "Stop video" else "Start video", !ui.camOn ) { CallManager.toggleCamera() }
                    // Always present (dimmed when the camera is off or mid-swap) so the row never shifts around.
                    VideoBtn(Modifier.weight(1f), Icons.Rounded.Cameraswitch, if (ui.switching) "Switching…" else if (ui.front) "Rear camera" else "Front camera", false , enabled = ui.camOn && !ui.switching) { CallManager.flipCamera() }
                }
                VideoBtn(Modifier.weight(1f), Icons.AutoMirrored.Rounded.VolumeUp, if (speaker) "Speaker on" else "Speaker off", speaker) {
                    CallManager.setRoute(if (speaker) AudioUi.EARPIECE else AudioUi.SPEAKER)
                }
                VideoBtn(Modifier.weight(1f), Icons.Rounded.PhoneInTalk, "Voice only", false) { CallManager.requestVideo(call.id, false) }
            }
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(0.7f).heightIn(min = 52.dp).clip(RoundedCornerShape(26.dp)).background(c.decline)
                    .clickable(role = Role.Button, onClickLabel = "End call") { CallManager.disconnect(call.id) },
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.CallEnd, null, tint = Color.White, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text("End call", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun VideoRequestDialog(call: CallInfo) {
    val c = LocalColors.current
    val accept = rememberCameraGate { ok -> CallManager.answerVideoRequest(call.id, true, receiveOnly = !ok) }
    Dialog(onDismissRequest = { CallManager.answerVideoRequest(call.id, false) }) {
        Column(Modifier.clip(RoundedCornerShape(24.dp)).background(c.surface).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.Videocam, null, tint = c.accent, modifier = Modifier.size(32.dp))
            Spacer(Modifier.height(12.dp))
            Text("${call.title()} wants to switch to video", color = c.ink, fontSize = 18.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextPill("Decline", { CallManager.answerVideoRequest(call.id, false) })
                TextPill("Accept", { accept() }, filled = true)
            }
        }
    }
}
