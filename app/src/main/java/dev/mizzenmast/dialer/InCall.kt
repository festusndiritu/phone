@file:Suppress("DEPRECATION")

package dev.mizzenmast.dialer

import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.telecom.Call
import android.telecom.CallAudioState
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.CallMerge
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
import androidx.compose.material.icons.rounded.VolumeUp
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Core.init(this)
        val cid = CallManager.currentCallId()
        Diag.log("activity_created", cid, "recreated=${savedInstanceState != null}")

        // Lock-screen presentation via documented APIs (manifest attrs cover API 27+, this covers older + is explicit)
        setShowWhenLocked(true); setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()

        setContent {
            val mode by Prefs.theme.collectAsState()
            DialerTheme(mode) { InCallRoot(onAddCall = ::openDialpad) }
        }
        Diag.log("content_set", cid)
        if (savedInstanceState == null) consumeIntent(intent)

        lifecycleScope.launch {
            combine(CallManager.calls, CallManager.audio) { l, a -> l to a }.collect { (list, audio) ->
                if (list.isEmpty()) finishAndRemoveTask() else updateProximity(list, audio)
            }
        }
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

    private fun updateProximity(list: List<CallInfo>, audio: CallAudioState?) {
        val active = list.any { !it.isEnded && (it.state == Call.STATE_ACTIVE || it.state == Call.STATE_DIALING || it.state == Call.STATE_CONNECTING) }
        val need = active && audio?.route == CallAudioState.ROUTE_EARPIECE
        val pm = getSystemService(PowerManager::class.java)
        if (need) {
            if (proxLock == null && pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                proxLock = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "dialer:proximity").also { it.acquire(4 * 60 * 60 * 1000L) }
            }
        } else releaseProximity()
    }

    private fun releaseProximity() {
        proxLock?.let { if (it.isHeld) it.release() }
        proxLock = null
    }

    override fun onPause() {
        CallNotifications.uiResumed = false
        Diag.log("activity_paused", CallManager.currentCallId(), "finishing=$isFinishing")
        super.onPause()
    }

    override fun onDestroy() {
        Diag.log("activity_destroyed", CallManager.currentCallId(), "finishing=$isFinishing")
        releaseProximity()
        super.onDestroy()
    }
}

@Composable
fun InCallRoot(onAddCall: () -> Unit) {
    val calls by CallManager.calls.collectAsState()
    val audio by CallManager.audio.collectAsState()
    val c = LocalColors.current
    val top = calls.filter { it.parentId == null }
    val live = top.filter { !it.isEnded }
    val ringing = live.firstOrNull { it.state == Call.STATE_RINGING }
    val primary = CallManager.pickPrimary(live.ifEmpty { top })
    Box(Modifier.fillMaxSize().background(c.bg).systemBarsPadding()) {
        when {
            ringing != null -> IncomingScreen(ringing, live.filter { it.id != ringing.id })
            primary != null -> OngoingScreen(primary, top, calls, audio, onAddCall)
        }
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
        Text(if (others.isEmpty()) "INCOMING CALL" else "CALL WAITING", style = T.label, color = c.accent)
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
            TextPill("Silence", { CallManager.silence(ctx) })
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
    Column(Modifier.width(88.dp).alpha(if (ctl.enabled) 1f else 0.38f), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(if (ctl.on) c.ink else c.surface)
                .clickable(enabled = ctl.enabled, role = Role.Button, onClick = { tick(); ctl.onClick() }),
            contentAlignment = Alignment.Center
        ) { Icon(ctl.icon, null, tint = if (ctl.on) c.bg else c.ink) }
        Spacer(Modifier.height(6.dp))
        Text(ctl.label, color = c.inkDim, fontSize = 12.sp)
    }
}

@Composable
private fun OngoingScreen(call: CallInfo, top: List<CallInfo>, all: List<CallInfo>, audio: CallAudioState?, onAddCall: () -> Unit) {
    val c = LocalColors.current
    var keypad by rememberSaveable { mutableStateOf(false) }
    var routes by remember { mutableStateOf(false) }
    var typed by remember(call.id) { mutableStateOf("") }
    val isActive = call.state == Call.STATE_ACTIVE
    val now by produceState(System.currentTimeMillis(), isActive) {
        while (isActive) { value = System.currentTimeMillis(); delay(1000.milliseconds) }
    }

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
                // Stop tone shortly after each press (Telecom requires an explicit stop)
                LaunchedEffect(typed) { if (typed.isNotEmpty()) { delay(140.milliseconds); CallManager.dtmfStop(call.id) } }
                Spacer(Modifier.height(8.dp))
                TextPill("Hide keypad", { keypad = false })
            }
            else -> {
                val muted = audio?.isMuted == true
                val route = audio?.route ?: CallAudioState.ROUTE_EARPIECE
                val mask = audio?.supportedRouteMask ?: 0
                val speaker = route == CallAudioState.ROUTE_SPEAKER
                val multi = (mask and (CallAudioState.ROUTE_BLUETOOTH or CallAudioState.ROUTE_WIRED_HEADSET)) != 0
                val btName = remember(audio) {
                    if (route == CallAudioState.ROUTE_BLUETOOTH) try { audio?.activeBluetoothDevice?.name } catch (e: Exception) { null } else null
                }
                val (routeIcon, routeLabel) = when (route) {
                    CallAudioState.ROUTE_SPEAKER -> Icons.Rounded.VolumeUp to "Speaker"
                    CallAudioState.ROUTE_BLUETOOTH -> Icons.Rounded.Bluetooth to (btName?.takeIf { it.isNotBlank() }?.let { if (it.length > 11) it.take(10) + "…" else it } ?: "Bluetooth")
                    CallAudioState.ROUTE_WIRED_HEADSET -> Icons.Rounded.Headset to "Headset"
                    else -> Icons.Rounded.PhoneInTalk to if (multi) "Audio" else "Speaker"
                }
                val heldOther = others.firstOrNull { it.state == Call.STATE_HOLDING }
                val controls = listOfNotNull(
                    Ctl(if (muted) Icons.Rounded.MicOff else Icons.Rounded.Mic, "Mute", muted, live) { CallManager.setMuted(!muted) },
                    Ctl(Icons.Rounded.Dialpad, "Keypad", false, connected || call.state == Call.STATE_DIALING) { keypad = true },
                    Ctl(routeIcon, routeLabel, speaker || route == CallAudioState.ROUTE_BLUETOOTH, live) {
                        if (multi) routes = true
                        else CallManager.setRoute(if (speaker) CallAudioState.ROUTE_EARPIECE else CallAudioState.ROUTE_SPEAKER)
                    },
                    Ctl(if (call.state == Call.STATE_HOLDING) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                        if (call.state == Call.STATE_HOLDING) "Resume" else "Hold", call.state == Call.STATE_HOLDING,
                        call.canHold && connected) { CallManager.toggleHold(call.id) },
                    Ctl(Icons.Rounded.PersonAdd, "Add call", false, connected && others.isEmpty(), onAddCall),
                    when {
                        call.canMerge && connected -> Ctl(Icons.Rounded.CallMerge, "Merge", false, true) { CallManager.merge(call.id) }
                        heldOther != null && isActive -> Ctl(Icons.Rounded.SwapCalls, "Swap", false, true) { CallManager.swapTo(heldOther.id) }
                        call.canSwap -> Ctl(Icons.Rounded.SwapCalls, "Swap", false, true) { CallManager.swapTo(call.id) }
                        else -> null
                    }
                )
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    controls.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { row.forEach { CtlButton(it) } }
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
        val mask = audio?.supportedRouteMask ?: 0
        val cur = audio?.route ?: 0
        Dialog(onDismissRequest = { routes = false }) {
            Column(Modifier.clip(RoundedCornerShape(24.dp)).background(c.surface).padding(vertical = 12.dp)) {
                listOf(
                    Triple(CallAudioState.ROUTE_EARPIECE, "Phone", Icons.Rounded.PhoneInTalk),
                    Triple(CallAudioState.ROUTE_SPEAKER, "Speaker", Icons.Rounded.VolumeUp),
                    Triple(CallAudioState.ROUTE_BLUETOOTH, "Bluetooth", Icons.Rounded.Bluetooth),
                    Triple(CallAudioState.ROUTE_WIRED_HEADSET, "Wired headset", Icons.Rounded.Headset)
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