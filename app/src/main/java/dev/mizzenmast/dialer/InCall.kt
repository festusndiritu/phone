package dev.mizzenmast.dialer

import android.telecom.Call
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallMerge
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.Headset
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PhoneInTalk
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material.icons.rounded.SwapCalls
import androidx.compose.material.icons.rounded.Videocam
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

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
    val ctx = LocalContext.current
    val hints by Prefs.gestureHints.collectAsState()
    var replying by remember { mutableStateOf(false) }
    val canReply = call.number != null && call.presentation == android.telecom.TelecomManager.PRESENTATION_ALLOWED
    val sendReply = { t: String ->
        val n = call.number
        if (Prefs.replyViaWhatsApp.value && n != null) { CallManager.reject(call.id); openWhatsApp(ctx, n, t) }
        else CallManager.reject(call.id, t)
    }
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
        if (replying && canReply) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                QuickReplies.forEach { t -> TextPill(t, { sendReply(t) }) }
                TextPill("Cancel", { replying = false })
            }
        } else if (others.isNotEmpty()) {
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
                if (canReply) TextPill("Reply", { replying = true })
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

private val QuickReplies = listOf("Can't talk, will call you back", "In a meeting, will call later", "Please text me instead")

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
    Column(Modifier.width(80.dp).alpha(if (ctl.enabled) 1f else 0.38f), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(68.dp).clip(CircleShape).background(if (ctl.on) c.ink else c.surface)
                .clickable(enabled = ctl.enabled, role = Role.Button, onClick = { tick(); ctl.onClick() }),
            contentAlignment = Alignment.Center
        ) { Icon(ctl.icon, null, tint = if (ctl.on) c.bg else c.ink, modifier = Modifier.size(28.dp)) }
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
        Call.STATE_DISCONNECTED -> call.endLabel ?: endText(call.endCode)
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
                    Ctl(
                        if (muted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                        "Mute",
                        muted,
                        live
                    ) { CallManager.setMuted(!muted) },
                    Ctl(Icons.Rounded.Dialpad, "Keypad", false, connected || call.state == Call.STATE_DIALING) { keypad = true },
                    Ctl(routeIcon, routeLabel, speaker || route == AudioUi.BLUETOOTH, live) {
                        if (multi) routes = true
                        else CallManager.setRoute(if (speaker) AudioUi.EARPIECE else AudioUi.SPEAKER)
                    },
                    Ctl(if (call.state == Call.STATE_HOLDING) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                        if (call.state == Call.STATE_HOLDING) "Resume" else "Hold", call.state == Call.STATE_HOLDING,
                        call.canHold && connected) { CallManager.toggleHold(call.id) },
                    Ctl(Icons.Rounded.PersonAdd, "Add call", false, connected && others.isEmpty(), onAddCall),
                    if (call.canVideo && !call.isVideo) Ctl(
                        Icons.Rounded.Videocam,
                        if (vui.pendingUp) "Asking…" else "Video",
                        vui.pendingUp,
                        call.state == Call.STATE_ACTIVE && !vui.pendingUp
                    ) { askVideo() } else null,
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
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                    controls.chunked(3).forEach { row ->
                        // Full rows spread across the width; a short last row is centered.
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (row.size == 3) Arrangement.SpaceEvenly else Arrangement.spacedBy(32.dp, Alignment.CenterHorizontally)) { row.forEach { CtlButton(it) } }
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

/** Own composable so the once-a-second tick doesn't recompose the whole video screen. */
@Composable
internal fun CallClock(connectedAt: Long) {
    val visible by CallManager.uiVisible.collectAsState()
    val now by produceState(
        System.currentTimeMillis(),
        visible
    ) { while (visible) {
        value = System.currentTimeMillis()
        delay(1000.milliseconds) }
    }
    Text(
        if (connectedAt > 0) fmtDur((now - connectedAt) / 1000) else "Connected",
        color = Color.White.copy(alpha = 0.75f),
        fontSize = 14.sp
    )
}
