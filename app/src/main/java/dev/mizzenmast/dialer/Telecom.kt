package dev.mizzenmast.dialer

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.CallScreeningService
import android.telecom.DisconnectCause
import android.telecom.InCallService
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import kotlin.time.Duration.Companion.milliseconds

data class SimOption(val handle: PhoneAccountHandle, val label: String)

/** Immutable snapshot of one Telecom Call. The real Call objects stay inside CallManager. */
data class CallInfo(
    val id: Int,
    val state: Int,
    val incoming: Boolean,
    val number: String?,
    val name: String?,
    val photo: String?,
    val presentation: Int,
    val connectedAt: Long,
    val canHold: Boolean,
    val canMerge: Boolean,
    val canSwap: Boolean,
    val canSplit: Boolean,
    val isConference: Boolean,
    val parentId: Int?,
    val childIds: List<Int>,
    val endCode: Int,
    val sims: List<SimOption>,
    val sim: String? = null
) {
    val isEnded get() = state == Call.STATE_DISCONNECTED
    val isLive get() = state != Call.STATE_DISCONNECTED && state != Call.STATE_DISCONNECTING
}

fun stateName(s: Int) = when (s) {
    Call.STATE_NEW -> "NEW"
    Call.STATE_DIALING -> "DIALING"
    Call.STATE_RINGING -> "RINGING"
    Call.STATE_HOLDING -> "HOLDING"
    Call.STATE_ACTIVE -> "ACTIVE"
    Call.STATE_DISCONNECTED -> "DISCONNECTED"
    Call.STATE_SELECT_PHONE_ACCOUNT -> "SELECT_ACCOUNT"
    Call.STATE_CONNECTING -> "CONNECTING"
    Call.STATE_DISCONNECTING -> "DISCONNECTING"
    else -> "STATE_$s"
}

@Suppress("DEPRECATION")
private fun stateOf(c: Call): Int = if (Build.VERSION.SDK_INT >= 31) c.details.state else c.state

private fun Call.has(cap: Int) = (details.callCapabilities and cap) != 0

private fun numberOf(c: Call): String? =
    c.details.handle?.schemeSpecificPart?.let { Uri.decode(it) }?.takeIf { it.isNotBlank() }

/**
 * Single source of truth for calls. All mutation happens on the main thread
 * (InCallService callbacks are delivered there). UI observes [calls] and [audio].
 */
object CallManager {
    private class Resolved(val name: String?, val photo: String?)

    private val refs = LinkedHashMap<Int, Call>()
    private val idOf = HashMap<Call, Int>()
    private val callbacks = HashMap<Int, Call.Callback>()
    private val incoming = HashMap<Int, Boolean>()
    private val resolved = HashMap<Int, Resolved>()
    private val resolvedFor = HashMap<Int, String>()
    private val simLabels = HashMap<Int, String>()
    private val simHandleFor = HashMap<Int, PhoneAccountHandle>()
    private val lingering = LinkedHashMap<Int, CallInfo>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var service: WeakReference<InCallService>? = null
    /** Derived from the weakly-held service, so this singleton never keeps a Context alive. */
    private val appCtx: Context? get() = service?.get()?.applicationContext

    private val _calls = MutableStateFlow<List<CallInfo>>(emptyList())
    val calls: StateFlow<List<CallInfo>> = _calls
    private val _audio = MutableStateFlow<CallAudioState?>(null)
    val audio: StateFlow<CallAudioState?> = _audio

    // ---- lifecycle (called by DialerInCallService) ----

    @Suppress("DEPRECATION")
    fun attach(svc: InCallService) {
        service = WeakReference(svc)
        _audio.value = svc.callAudioState
    }

    fun detach() {
        val ctx = appCtx // capture before the service reference is dropped
        refs.clear(); idOf.clear(); callbacks.clear(); incoming.clear(); resolved.clear(); resolvedFor.clear(); lingering.clear(); simLabels.clear(); simHandleFor.clear()
        service = null
        _audio.value = null
        publish(ctx)
        ctx?.let { CallNotifications.clearAll(it) }
    }

    fun setAudio(s: CallAudioState?) { _audio.value = s }

    fun onAdded(svc: DialerInCallService, call: Call) {
        val id = Prefs.nextCallId()
        idOf[call] = id
        refs[id] = call
        val st = stateOf(call)
        incoming[id] = st == Call.STATE_RINGING
        Diag.log("call_added", id, "state=${stateName(st)}")
        val cb = Cb(id)
        callbacks[id] = cb
        call.registerCallback(cb)
        publish() // posts the incoming notification immediately, before any lookup work
        if (call.parent == null) {
            val ctx = svc.applicationContext
            if (st == Call.STATE_RINGING) {
                if (Prefs.directLaunch.value) {
                    CallNotifications.launchUi(ctx, id, "ringing")
                    // If the system dropped that launch (OEM background-start limits), fall back to the loud heads-up / full-screen alert.
                    scope.launch {
                        delay(700.milliseconds)
                        val stillRinging = refs[id]?.let { stateOf(it) == Call.STATE_RINGING } == true
                        if (stillRinging && !CallNotifications.uiResumed && CallNotifications.escalate(ctx, id)) publish()
                    }
                }
            } else {
                CallNotifications.launchUi(ctx, id, "new_call")
            }
        }
        lookup(id, call)
        loadSim(id, call)
    }

    fun onRemoved(call: Call) {
        val id = idOf[call] ?: return
        val snap = build(id, call)
        callbacks.remove(id)?.let { call.unregisterCallback(it) }
        refs.remove(id)
        idOf.remove(call)
        Diag.log("call_removed", id, "cause=${snap.endCode}")
        if (snap.parentId == null) {
            lingering[id] = snap.copy(state = Call.STATE_DISCONNECTED, childIds = emptyList(), sims = emptyList())
            publish()
            scope.launch {
                delay(1200.milliseconds) // brief "Call ended / Busy / Declined" feedback, then the screen goes away
                lingering.remove(id)
                resolved.remove(id); resolvedFor.remove(id); incoming.remove(id); simLabels.remove(id); simHandleFor.remove(id)
                publish()
            }
        } else publish()
    }

    private class Cb(val id: Int) : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            Diag.log("state_${stateName(state)}", id)
            publish()
        }
        override fun onDetailsChanged(call: Call, details: Call.Details) { lookup(id, call); loadSim(id, call); publish() }
        override fun onParentChanged(call: Call, parent: Call?) = publish()
        override fun onChildrenChanged(call: Call, children: List<Call>) = publish()
        override fun onConferenceableCallsChanged(call: Call, calls: List<Call>) = publish()
    }

    // ---- snapshot building ----

    private fun publish(ctx: Context? = appCtx) {
        val live = refs.map { (id, c) -> build(id, c) }
        val list = (live + lingering.values).sortedBy { it.id }
        _calls.value = list
        ctx?.let { CallNotifications.sync(it, list, service?.get()) }
    }

    private fun build(id: Int, call: Call): CallInfo {
        val d = call.details
        val st = stateOf(call)
        val res = resolved[id]
        return CallInfo(
            id = id, state = st, incoming = incoming[id] == true,
            number = numberOf(call),
            name = res?.name ?: d.callerDisplayName?.takeIf { it.isNotBlank() },
            photo = res?.photo,
            presentation = d.handlePresentation,
            connectedAt = d.connectTimeMillis,
            canHold = call.has(Call.Details.CAPABILITY_HOLD) || call.has(Call.Details.CAPABILITY_SUPPORT_HOLD),
            canMerge = call.has(Call.Details.CAPABILITY_MERGE_CONFERENCE) || call.conferenceableCalls.isNotEmpty(),
            canSwap = call.has(Call.Details.CAPABILITY_SWAP_CONFERENCE),
            canSplit = call.has(Call.Details.CAPABILITY_SEPARATE_FROM_CONFERENCE),
            isConference = d.hasProperty(Call.Details.PROPERTY_CONFERENCE),
            parentId = call.parent?.let { idOf[it] },
            childIds = call.children.mapNotNull { idOf[it] },
            endCode = d.disconnectCause?.code ?: -1,
            sims = if (st == Call.STATE_SELECT_PHONE_ACCOUNT) simOptions(call) else emptyList(),
            sim = simLabels[id]
        )
    }

    private fun lookup(id: Int, call: Call) {
        val n = numberOf(call) ?: return
        if (resolvedFor[id] == n) return
        resolvedFor[id] = n
        val ctx = appCtx ?: return
        scope.launch(Dispatchers.IO) {
            val r = ContactsRepo.lookup(ctx, n)
            withContext(Dispatchers.Main) {
                if (r != null && refs.containsKey(id)) {
                    resolved[id] = Resolved(r.name, r.photo)
                    publish()
                }
            }
        }
    }

    /** Off the main thread, after the incoming UI is already on its way: only dual-SIM phones get a label. */
    private fun loadSim(id: Int, call: Call) {
        val ctx = appCtx ?: return
        val h = call.details.accountHandle ?: return
        if (simHandleFor[id] == h) return
        simHandleFor[id] = h
        scope.launch(Dispatchers.IO) {
            val label = if (SimLabels.multiSim(ctx)) SimLabels.label(ctx, h) else null
            withContext(Dispatchers.Main) {
                if (label != null && refs.containsKey(id) && simLabels[id] != label) { simLabels[id] = label; publish() }
            }
        }
    }

    // Default dialer may call this without READ_PHONE_STATE; SecurityException is caught below.
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun simOptions(call: Call): List<SimOption> {
        val ctx = appCtx ?: return emptyList()
        val tm = ctx.getSystemService(TelecomManager::class.java) ?: return emptyList()
        val extras: Bundle? = call.details.extras
        var handles: List<PhoneAccountHandle> = try {
            val key = "selectPhoneAccountAccounts" // Call.AVAILABLE_PHONE_ACCOUNTS
            (if (Build.VERSION.SDK_INT >= 33) extras?.getParcelableArrayList(key, PhoneAccountHandle::class.java)
            else extras?.getParcelableArrayList(key)) ?: emptyList()
        } catch (e: Exception) { emptyList() }
        if (handles.isEmpty()) handles = try { tm.callCapablePhoneAccounts } catch (e: SecurityException) { emptyList() }
        return handles.map { h -> SimOption(h, try { tm.getPhoneAccount(h)?.label?.toString() } catch (e: Exception) { null } ?: "SIM") }
    }

    // ---- queries ----

    fun topLevel() = _calls.value.filter { it.parentId == null }
    fun ringing(): CallInfo? = topLevel().firstOrNull { it.state == Call.STATE_RINGING }
    fun currentCallId(): Int = pickPrimary(topLevel())?.id ?: 0

    /** Ringing first, then outgoing/connecting, then active, then held. Ended calls only if nothing else. */
    fun pickPrimary(top: List<CallInfo>): CallInfo? {
        val live = top.filter { !it.isEnded }
        if (live.isEmpty()) return top.firstOrNull()
        return live.firstOrNull { it.state == Call.STATE_RINGING }
            ?: live.firstOrNull { it.state == Call.STATE_DIALING || it.state == Call.STATE_CONNECTING || it.state == Call.STATE_SELECT_PHONE_ACCOUNT }
            ?: live.firstOrNull { it.state == Call.STATE_ACTIVE }
            ?: live.firstOrNull { it.state == Call.STATE_HOLDING }
            ?: live.first()
    }

    // ---- operations: every one goes to the real Telecom Call ----

    fun answer(id: Int) { Diag.log("answer_tapped", id); refs[id]?.answer(VideoProfile.STATE_AUDIO_ONLY) }
    fun reject(id: Int) { Diag.log("decline_tapped", id); refs[id]?.reject(false, null) }
    fun rejectRinging(source: String) {
        ringing()?.let { Diag.log("decline_source", it.id, "source=$source"); reject(it.id) }
    }
    fun disconnect(id: Int) { refs[id]?.disconnect() }
    fun endPrimary() { pickPrimary(topLevel())?.let { disconnect(it.id) } }

    fun endAndAnswer(ringingId: Int) {
        topLevel().filter { it.id != ringingId && it.isLive }.forEach { disconnect(it.id) }
        answer(ringingId)
    }

    fun toggleHold(id: Int) {
        val c = refs[id] ?: return
        if (stateOf(c) == Call.STATE_HOLDING) c.unhold() else c.hold()
    }

    fun swapTo(id: Int) {
        val c = refs[id] ?: return
        if (c.has(Call.Details.CAPABILITY_SWAP_CONFERENCE)) c.swapConference() else c.unhold()
    }

    fun merge(id: Int) {
        val c = refs[id] ?: return
        if (c.has(Call.Details.CAPABILITY_MERGE_CONFERENCE)) c.mergeConference()
        else c.conferenceableCalls.firstOrNull()?.let { c.conference(it) }
    }

    fun split(childId: Int) { refs[childId]?.splitFromConference() }

    fun dtmf(id: Int, ch: Char) { refs[id]?.playDtmfTone(ch) }
    fun dtmfStop(id: Int) { refs[id]?.stopDtmfTone() }

    fun selectSim(id: Int, h: PhoneAccountHandle) { refs[id]?.phoneAccountSelected(h, false) }

    @Suppress("DEPRECATION")
    fun setMuted(m: Boolean) { service?.get()?.setMuted(m) }

    @Suppress("DEPRECATION")
    fun setRoute(route: Int) { service?.get()?.setAudioRoute(route) }

    // Lint flags MODIFY_PHONE_STATE (a system-only permission). Telecom also accepts the default dialer
    // for silenceRinger, so this works at runtime when we hold the role; SecurityException is caught otherwise.
    @SuppressLint("MissingPermission")
    fun silence(ctx: Context) {
        try { ctx.getSystemService(TelecomManager::class.java)?.silenceRinger() } catch (_: SecurityException) { }
    }
}

class DialerInCallService : InCallService() {
    private var boundAt = 0L
    fun sinceBind(): Long = if (boundAt == 0L) -1 else SystemClock.elapsedRealtime() - boundAt

    override fun onCreate() {
        super.onCreate()
        Core.init(this)
        Diag.log("service_created")
        CallNotifications.clearAll(this) // anything left from a previous process is stale by definition
        CallManager.attach(this)
    }

    override fun onBind(intent: Intent?): IBinder? {
        boundAt = SystemClock.elapsedRealtime()
        Diag.log("service_bound")
        return super.onBind(intent)
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        CallManager.onAdded(this, call)
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        CallManager.onRemoved(call)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        CallManager.setAudio(audioState)
    }

    override fun onBringToForeground(showDialpad: Boolean) {
        Diag.log("bring_to_foreground", CallManager.currentCallId())
        CallNotifications.launchUi(applicationContext, CallManager.currentCallId(), "bring_to_foreground")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Diag.log("service_unbound")
        CallManager.detach()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Diag.log("service_destroyed")
        CallNotifications.clearAll(this)
        super.onDestroy()
    }
}

object CallNotifications {
    const val EXTRA_ACTION = "dialer_action"
    const val ACTION_DECLINE = "dev.mizzenmast.dialer.DECLINE"
    const val ACTION_HANGUP = "dev.mizzenmast.dialer.HANGUP"
    private const val CH_INCOMING = "incoming_call_v1"
    private const val CH_INCOMING_QUIET = "incoming_call_quiet_v1"
    private const val CH_ONGOING = "ongoing_call_v1"
    private const val ID_INCOMING = 4101
    private const val ID_ONGOING = 4102
    private val posted = HashSet<Int>()

    /** True while the call screen is on screen (set by InCallActivity). */
    @Volatile var uiResumed = false

    /** Per ringing call: true = we open the call screen ourselves, so the notification must not also pop a heads-up. Decided once per call. */
    private val quiet = HashMap<Int, Boolean>()

    private var fgOn = false
    private var useFg = true // flips off if Android refuses to promote the service
    private const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    fun uiIntent(ctx: Context) = Intent(ctx, InCallActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /** Best-effort. On Android 10+ background launches may be silently dropped; the full-screen notification is the documented path. */
    fun launchUi(ctx: Context, callId: Int, reason: String) {
        try {
            ctx.startActivity(uiIntent(ctx).addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION))
            Diag.log("activity_launch_attempt", callId, "reason=$reason")
        } catch (e: Exception) {
            Diag.log("activity_launch_failed", callId, e.javaClass.simpleName)
        }
    }

    private fun ensureChannels(nm: NotificationManager) {
        if (nm.getNotificationChannel(CH_INCOMING) == null) {
            nm.createNotificationChannel(NotificationChannel(CH_INCOMING, "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Full-screen incoming call alerts. Ringtone is played by the system."
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })
        }
        if (nm.getNotificationChannel(CH_INCOMING_QUIET) == null) {
            nm.createNotificationChannel(NotificationChannel(CH_INCOMING_QUIET, "Incoming calls (screen on)", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown quietly while the call screen opens by itself, so there is no heads-up on top of it."
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })
        }
        if (nm.getNotificationChannel(CH_ONGOING) == null) {
            nm.createNotificationChannel(NotificationChannel(CH_ONGOING, "Ongoing calls", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })
        }
    }

    /** Cancels every call notification this app owns. */
    fun clearAll(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.cancel(ID_INCOMING); nm.cancel(ID_ONGOING)
        posted.clear(); quiet.clear(); fgOn = false
    }

    /** Switches a quiet incoming notification to the loud channel. Returns true if it changed (caller re-syncs). */
    fun escalate(ctx: Context, callId: Int): Boolean {
        if (quiet[callId] != true) return false
        quiet[callId] = false
        posted.remove(callId)
        ctx.getSystemService(NotificationManager::class.java)?.cancel(ID_INCOMING) // cancel first, or the update stays silent
        Diag.log("notif_escalated", callId, "call screen did not appear")
        return true
    }

    fun sync(ctx: Context, all: List<CallInfo>, svc: android.app.Service?) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        ensureChannels(nm)
        val top = all.filter { it.parentId == null && it.isLive }
        val ringing = top.firstOrNull { it.state == Call.STATE_RINGING }
        if (ringing != null) postIncoming(ctx, nm, ringing) else nm.cancel(ID_INCOMING)
        val ongoing = CallManager.pickPrimary(top.filter { it.state != Call.STATE_RINGING })?.takeIf { !it.isEnded }
        if (ongoing != null) postOngoing(ctx, nm, ongoing, svc)
        else {
            if (fgOn) { svc?.let { ServiceCompat.stopForeground(it, ServiceCompat.STOP_FOREGROUND_REMOVE) }; fgOn = false }
            nm.cancel(ID_ONGOING)
        }
    }

    private fun person(c: CallInfo) = Person.Builder().setName(c.title()).setImportant(true).build()

    private fun subtitle(c: CallInfo) = if (c.name != null) pretty(c.number) else null

    @SuppressLint("FullScreenIntentPolicy")
    private fun postIncoming(ctx: Context, nm: NotificationManager, c: CallInfo) {
        val open = PendingIntent.getActivity(ctx, 10, uiIntent(ctx), FLAGS)
        val answer = PendingIntent.getActivity(ctx, 11, uiIntent(ctx).putExtra(EXTRA_ACTION, "answer"), FLAGS)
        val decline = PendingIntent.getBroadcast(ctx, 12,
            Intent(ctx, CallActionReceiver::class.java).setAction(ACTION_DECLINE), FLAGS)
        val isQuiet = quiet.getOrPut(c.id) {
            val usable = ctx.getSystemService(PowerManager::class.java).isInteractive && !ctx.getSystemService(KeyguardManager::class.java).isKeyguardLocked
            Prefs.directLaunch.value && usable
        }
        val n = NotificationCompat.Builder(ctx, if (isQuiet) CH_INCOMING_QUIET else CH_INCOMING)
            .setSmallIcon(R.drawable.ic_call_notification)
            .setContentTitle(c.title())
            .setContentText(subtitle(c) ?: "Incoming call")
            .setSubText(c.sim)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(person(c), decline, answer))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .setFullScreenIntent(open, true) // call style requires one; on the quiet channel Android never launches it
            .setOngoing(true)
            .setTimeoutAfter(60_000) // safety net: an incoming-call notification can never outlive a ring cycle
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
        try {
            nm.notify(ID_INCOMING, n)
            Diag.log(if (posted.add(c.id)) "notif_posted" else "notif_updated", c.id, "kind=incoming")
        } catch (e: SecurityException) { Diag.log("notif_failed", c.id, "SecurityException") }
    }

    /** Android 12+ throws if a CallStyle notification isn't tied to a foreground service, user-initiated job or full-screen intent. We have none for ongoing calls, so on the first rejection we fall back to a plain ongoing notification. */
    private var ongoingCallStyle = true

    private fun postOngoing(ctx: Context, nm: NotificationManager, c: CallInfo, svc: android.app.Service?) {
        val open = PendingIntent.getActivity(ctx, 20, uiIntent(ctx), FLAGS)
        val hangup = PendingIntent.getBroadcast(ctx, 21,
            Intent(ctx, CallActionReceiver::class.java).setAction(ACTION_HANGUP), FLAGS)
        fun build(callStyle: Boolean) = NotificationCompat.Builder(ctx, CH_ONGOING)
            .setSmallIcon(R.drawable.ic_call_notification)
            .setContentTitle(c.title())
            .setContentText(if (c.state == Call.STATE_HOLDING) "On hold" else "Ongoing call")
            .setSubText(c.sim)
            .apply {
                if (callStyle) setStyle(NotificationCompat.CallStyle.forOngoingCall(person(c), hangup))
                else addAction(R.drawable.ic_call_notification, "Hang up", hangup)
            }
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setUsesChronometer(c.connectedAt > 0)
            .setWhen(if (c.connectedAt > 0) c.connectedAt else System.currentTimeMillis())
            .build()
        if (useFg && svc != null) {
            try {
                ServiceCompat.startForeground(svc, ID_ONGOING, build(true), ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
                fgOn = true
                return
            } catch (e: Exception) {
                useFg = false
                Diag.log("fg_failed", c.id, e.javaClass.simpleName)
            }
        }
        try {
            nm.notify(ID_ONGOING, build(ongoingCallStyle))
        } catch (e: IllegalArgumentException) {
            ongoingCallStyle = false
            Diag.log("notif_fallback", c.id, "kind=ongoing plain")
            try { nm.notify(ID_ONGOING, build(false)) } catch (_: Exception) { }
        } catch (e: SecurityException) { }
    }
}

class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Core.init(ctx)
        when (intent.action) {
            CallNotifications.ACTION_DECLINE -> CallManager.rejectRinging("notification")
            CallNotifications.ACTION_HANGUP -> CallManager.endPrimary()
        }
    }
}

fun endText(code: Int): String = when (code) {
    DisconnectCause.BUSY -> "Busy"
    DisconnectCause.REJECTED -> "Declined"
    DisconnectCause.MISSED -> "Missed"
    DisconnectCause.ERROR -> "Call failed"
    DisconnectCause.CANCELED -> "Canceled"
    DisconnectCause.RESTRICTED -> "Call not allowed"
    DisconnectCause.ANSWERED_ELSEWHERE -> "Answered on another device"
    DisconnectCause.CALL_PULLED -> "Call moved to another device"
    else -> "Call ended"
}

/**
 * Silences (no ring, no heads-up; still logged as missed) incoming calls from numbers that aren't saved contacts.
 * Telecom waits for [onScreenCall] before it rings, so this must stay one quick local query. The component is only
 * enabled while the Settings toggle is on. Does nothing without READ_CONTACTS, so a missing permission never silences everyone.
 */
class UnknownCallerScreeningService : CallScreeningService() {
    override fun onScreenCall(details: Call.Details) {
        Core.init(this)
        val incoming = Build.VERSION.SDK_INT < 29 || details.callDirection == Call.Details.DIRECTION_INCOMING
        var silence = false
        if (incoming && Prefs.silenceUnknown.value && granted(android.Manifest.permission.READ_CONTACTS)) {
            val number = details.handle?.schemeSpecificPart
            silence = number.isNullOrBlank() || ContactsRepo.lookup(this, number) == null
        }
        respondToCall(details, CallResponse.Builder().setSilenceCall(silence).build())
    }
}
