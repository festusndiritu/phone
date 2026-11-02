package dev.mizzenmast.dialer

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.telecom.Call
import android.telecom.Connection
import android.telecom.DisconnectCause
import android.telecom.InCallService
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.view.Surface
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
import kotlin.time.Duration.Companion.seconds

@Suppress("DEPRECATION") // Call.state is deprecated from API 31; Call.details.state needs it
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
    private val dialAt = HashMap<Int, Long>()
    private val buzzed = HashSet<Int>()
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
    private val _audio = MutableStateFlow<AudioUi?>(null)
    val audio: StateFlow<AudioUi?> = _audio

    data class VideoUi(val camOn: Boolean = true, val front: Boolean = true, val peerW: Int = 0, val peerH: Int = 0, val previewAspect: Float = 0.75f,
        /** Camera is being swapped front/rear (released, then re-opened). */ val switching: Boolean = false,
        /** We asked to go to video and haven't heard back yet. */ val pendingUp: Boolean = false)

    private val _video = MutableStateFlow(VideoUi())
    val video: StateFlow<VideoUi> = _video
    private val upgradeAsk = HashMap<Int, Int>()
    private val videoCbs = HashMap<Int, InCallService.VideoCall.Callback>()
    /** The provider instance our callback is registered on; a new instance means the provider was replaced. */
    private val hooked = HashMap<Int, InCallService.VideoCall>()
    private val camBound = HashMap<Int, String>()
    private val _uiVisible = MutableStateFlow(false)
    /** True while the call screen is on screen (picture-in-picture counts). Camera binding and the UI clocks follow it. */
    val uiVisible: StateFlow<Boolean> = _uiVisible
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val videoReqAt = HashMap<Int, Long>()
    private var flipTimer: Runnable? = null
    private var pendingTimer: Runnable? = null

    @Suppress("DEPRECATION") // getCallAudioState: see AudioUi
    fun attach(svc: InCallService) {
        service = WeakReference(svc)
        _audio.value = AudioUi.of(svc, svc.callAudioState)
    }

    fun detach() {
        val ctx = appCtx // capture before the service reference is dropped
        refs.forEach { (id, call) ->
            unhookVideo(id)
            callbacks[id]?.let { try { call.unregisterCallback(it) } catch (e: Exception) { Diag.fail("call_unregister", e, id) } }
        }
        cancelVideoTimers()
        refs.clear(); idOf.clear(); callbacks.clear(); incoming.clear(); resolved.clear(); resolvedFor.clear(); lingering.clear(); simLabels.clear(); simHandleFor.clear()
        videoCbs.clear(); hooked.clear(); upgradeAsk.clear(); camBound.clear(); _video.value = VideoUi()
        service = null
        _audio.value = null
        publish(ctx)
        ctx?.let { CallNotifications.clearAll(it) }
    }

    fun setAudio(s: AudioUi?) { _audio.value = s }

    fun onAdded(svc: DialerInCallService, call: Call) {
        val id = Prefs.nextCallId()
        idOf[call] = id
        refs[id] = call
        val st = stateOf(call)
        incoming[id] = st == Call.STATE_RINGING
        if (st != Call.STATE_RINGING) dialAt[id] = SystemClock.elapsedRealtime()
        Diag.log("call_added", id, "state=${stateName(st)}")
        val cb = Cb(id)
        callbacks[id] = cb
        call.registerCallback(cb)
        syncVideo(id, call)
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
        unhookVideo(id)
        upgradeAsk.remove(id)
        callbacks.remove(id)?.let { call.unregisterCallback(it) }
        refs.remove(id)
        idOf.remove(call)
        if (refs.values.none { VideoProfile.isVideo(it.details.videoState) }) { cancelVideoTimers(); _video.value = VideoUi() }
        Diag.log("call_removed", id, "cause=${snap.endCode}")
        if (snap.parentId == null && snap.endCode == DisconnectCause.MISSED && incoming[id] == true) {
            appCtx?.let { ctx ->
                CallNotifications.missed(ctx, snap)
                scope.launch { // Telecom posts its copy around call end; remove it once it has
                    delay(600.milliseconds); CallNotifications.cancelSystemMissed(ctx)
                    delay(2.seconds); CallNotifications.cancelSystemMissed(ctx)
                }
            }
        }
        if (snap.parentId == null && !snap.incoming && snap.endLabel in setOf("Busy", "No answer", "Declined")) {
            appCtx?.let { CallNotifications.unanswered(it, snap) }
        }
        if (snap.parentId == null) {
            lingering[id] = snap.copy(state = Call.STATE_DISCONNECTED, childIds = emptyList(), sims = emptyList())
            publish()
            scope.launch {
                delay(1200.milliseconds) // brief "Call ended / Busy / Declined" feedback, then the screen goes away
                lingering.remove(id)
                resolved.remove(id); resolvedFor.remove(id); incoming.remove(id); dialAt.remove(id); buzzed.remove(id); simLabels.remove(id); simHandleFor.remove(id)
                publish()
            }
        } else publish()
    }

    private class Cb(val id: Int) : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            Diag.log("state_${stateName(state)}", id)
            if (state == Call.STATE_DISCONNECTED) {
                val dc = call.details.disconnectCause
                Diag.log("end_cause", id, "code=${dc?.code} reason=${dc?.reason?.take(48)} sinceVideoReq=${videoReqAt[id]?.let { SystemClock.elapsedRealtime() - it } ?: -1}ms")
            }
            if (state == Call.STATE_ACTIVE && incoming[id] == false && Prefs.vibrateOnAnswer.value && buzzed.add(id)) buzz()
            if (state == Call.STATE_ACTIVE) Diag.log("video_caps", id, "canUpgrade=${HiddenApi.canUpgradeToVideo(call)} caps=${call.details.callCapabilities} videoState=${call.details.videoState} hasVideoCall=${call.videoCall != null}")
            syncVideo(id, call)
            publish()
        }
        override fun onDetailsChanged(call: Call, details: Call.Details) { lookup(id, call); loadSim(id, call); syncVideo(id, call); publish() }
        override fun onVideoCallChanged(call: Call, videoCall: InCallService.VideoCall?) { syncVideo(id, call); publish() }
        override fun onParentChanged(call: Call, parent: Call?) = publish()
        override fun onChildrenChanged(call: Call, children: List<Call>) = publish()
        override fun onConferenceableCallsChanged(call: Call, calls: List<Call>) = publish()
    }

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
            sim = simLabels[id],
            videoState = d.videoState,
            canVideo = HiddenApi.canUpgradeToVideo(call),
            videoRequest = upgradeAsk[id] ?: 0,
            endLabel = if (st == Call.STATE_DISCONNECTED) outgoingEndLabel(id, d) else null
        )
    }

    /** Two short pulses when the other side picks up, so you can take the phone off your ear and wait. */
    private fun buzz() {
        val ctx = appCtx ?: return
        try {
            ctx.getSystemService(android.os.Vibrator::class.java)
                ?.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 130, 90, 130), -1))
        } catch (e: Exception) { Diag.fail("vibrate_on_answer", e) }
    }

    /**
     * Telecom reports most "nobody picked up" endings as a generic error. For outgoing calls that never connected, tell
     * busy / declined / no answer / unreachable apart using the cause code, the reason string and how long it rang.
     * What the network signals varies by carrier, so unknown endings fall back to [endText].
     */
    private fun outgoingEndLabel(id: Int, d: Call.Details): String? {
        if (incoming[id] != false || d.connectTimeMillis > 0) return null
        val dc = d.disconnectCause ?: return null
        if (dc.code == DisconnectCause.LOCAL || dc.code == DisconnectCause.CANCELED) return null
        val r = dc.reason.orEmpty().uppercase()
        val waited = dialAt[id]?.let { SystemClock.elapsedRealtime() - it } ?: 0L
        fun has(vararg k: String) = k.any { it in r }
        return when {
            dc.code == DisconnectCause.BUSY || has("BUSY") && !has("CONGESTION") -> "Busy"
            dc.code == DisconnectCause.REJECTED || has("REJECT", "DECLINE") -> "Declined"
            has("NUMBER_UNREACHABLE", "POWER_OFF") -> "Not reachable"
            has("INVALID_NUMBER", "UNOBTAINABLE") -> "Invalid number"
            has("OUT_OF_SERVICE", "OUT_OF_NETWORK", "LOST_SIGNAL", "SERVER_UNREACHABLE") -> "No signal"
            has("CONGESTION", "LIMIT_EXCEEDED") -> "Network busy"
            has("NO_ANSWER", "NO_USER", "TIMED_OUT", "ALERTING") -> "No answer"
            // Seen on this phone: ringing out ends TIMED_OUT (~30s); the other side ending it first is a normal release.
            dc.code == DisconnectCause.REMOTE && has("NORMAL") && waited >= 4_000 -> "Declined"
            (dc.code == DisconnectCause.ERROR || dc.code == DisconnectCause.REMOTE || dc.code == DisconnectCause.UNKNOWN || dc.code == DisconnectCause.OTHER) && waited >= 10_000 -> "No answer"
            else -> null
        }
    }

    private fun lookup(id: Int, call: Call) {
        val n = numberOf(call) ?: return
        if (resolvedFor[id] == n) return
        val ctx = appCtx ?: return
        resolvedFor[id] = n
        if (resolved.remove(id) != null) publish() // the old name belonged to the old number
        scope.launch(Dispatchers.IO) {
            val result = ContactsRepo.lookup(ctx, n)
            withContext(Dispatchers.Main) {
                if (!refs.containsKey(id) || resolvedFor[id] != n) return@withContext // call gone, or its number changed meanwhile
                if (result is Lookup.Match) {
                    resolved[id] = Resolved(result.found.name, result.found.photo)
                    publish()
                } else if (result is Lookup.Unavailable) resolvedFor.remove(id) // couldn't ask; try again on the next update
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
            SimLabels.refresh(ctx)
            val label = if (SimLabels.multiSim(ctx)) SimLabels.label(ctx, h) else null
            withContext(Dispatchers.Main) {
                if (label != null && refs.containsKey(id) && simLabels[id] != label) { simLabels[id] = label; publish() }
            }
        }
    }

    private fun simOptions(call: Call): List<SimOption> {
        val ctx = appCtx ?: return emptyList()
        SimLabels.refresh(ctx)
        val handles = HiddenApi.offeredPhoneAccounts(call) ?: SimLabels.accounts(ctx)
        return handles.map { h -> SimOption(h, SimLabels.label(ctx, h) ?: "SIM") }
    }

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

    private fun withVideo(id: Int, f: (InCallService.VideoCall) -> Unit) {
        try { refs[id]?.videoCall?.let { f(it) } } catch (e: Exception) { Diag.log("video_error", id, e.javaClass.simpleName) }
    }

    private fun cameraId(ctx: Context, front: Boolean): String? = try {
        val cm = ctx.getSystemService(CameraManager::class.java)
        val want = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == want }
    } catch (e: Exception) { Diag.fail("camera_id", e); null }

    private fun hookVideo(id: Int, call: Call) {
        val vc = call.videoCall ?: return
        if (hooked[id] === vc) return
        if (hooked.containsKey(id)) unhookVideo(id) // the provider was replaced
        val cb = object : InCallService.VideoCall.Callback() {
            override fun onSessionModifyRequestReceived(videoProfile: VideoProfile?) {
                Diag.log("video_request_in", id, "state=${videoProfile?.videoState}")
                upgradeAsk[id] = videoProfile?.videoState ?: return
                publish()
            }
            override fun onSessionModifyResponseReceived(status: Int, requestedProfile: VideoProfile?, responseProfile: VideoProfile?) {
                Diag.log("video_response", id, "status=$status after=${videoReqAt.remove(id)?.let { SystemClock.elapsedRealtime() - it } ?: -1}ms")
                pendingTimer?.let(main::removeCallbacks); pendingTimer = null
                _video.value = _video.value.copy(pendingUp = false)
                when (status) {
                    Connection.VideoProvider.SESSION_MODIFY_REQUEST_REJECTED_BY_REMOTE -> Notices.show("Video request declined")
                    Connection.VideoProvider.SESSION_MODIFY_REQUEST_TIMED_OUT -> Notices.show("No answer to the video request")
                    Connection.VideoProvider.SESSION_MODIFY_REQUEST_FAIL,
                    Connection.VideoProvider.SESSION_MODIFY_REQUEST_INVALID -> Notices.show("Couldn't switch to video")
                }
            }
            override fun onCallSessionEvent(event: Int) {}
            override fun onPeerDimensionsChanged(width: Int, height: Int) { _video.value = _video.value.copy(peerW = width, peerH = height) }
            override fun onVideoQualityChanged(videoQuality: Int) {}
            override fun onCallDataUsageChanged(dataUsage: Long) {}
            override fun onCameraCapabilitiesChanged(cameraCapabilities: VideoProfile.CameraCapabilities?) {
                val w = cameraCapabilities?.width ?: 0
                val h = cameraCapabilities?.height ?: 0
                if (w > 0 && h > 0) _video.value = _video.value.copy(previewAspect = minOf(w, h).toFloat() / maxOf(w, h))
            }
        }
        hooked[id] = vc
        videoCbs[id] = cb
        try { vc.registerCallback(cb) } catch (e: Exception) { Diag.fail("video_register", e); hooked.remove(id); videoCbs.remove(id) }
    }

    /** Detaches our callback and camera from [id]'s video provider: the call ended, or the provider was replaced or went away. */
    private fun unhookVideo(id: Int) {
        val vc = hooked.remove(id)
        val cb = videoCbs.remove(id)
        if (vc != null) {
            if (cb != null) try { vc.unregisterCallback(cb) } catch (e: Exception) { Diag.fail("video_unregister", e) }
            if (camBound.containsKey(id)) try { vc.setCamera(null) } catch (e: Exception) { Diag.fail("camera_release", e) }
        }
        camBound.remove(id)
    }

    /** Keeps the camera attached only while we are sending video, the call is active and the call screen is on screen. */
    private fun syncVideo(id: Int, call: Call) {
        if (call.videoCall == null) { unhookVideo(id); return }
        hookVideo(id, call)
        val ctx = appCtx ?: return
        val want = _uiVisible.value && ctx.granted(Manifest.permission.CAMERA) && _video.value.camOn && !_video.value.switching && stateOf(call) == Call.STATE_ACTIVE &&
            VideoProfile.isTransmissionEnabled(call.details.videoState)
        if (want) {
            val cam = cameraId(ctx, _video.value.front) ?: return
            if (camBound[id] != cam) {
                camBound[id] = cam
                Diag.log("video_camera", id, "front=${_video.value.front}")
                withVideo(id) { it.setCamera(cam); it.requestCameraCapabilities() }
            }
        } else if (camBound.remove(id) != null) withVideo(id) { it.setCamera(null) }
    }

    private fun syncAllVideo() = refs.forEach { (id, c) -> syncVideo(id, c) }

    fun setUiVisible(visible: Boolean) { _uiVisible.value = visible; syncAllVideo() }
    fun toggleCamera() { _video.value = _video.value.copy(camOn = !_video.value.camOn); syncAllVideo() }
    /**
     * Flip = the same sequence that already works by hand (camera off, then on): release the camera, let the provider
     * and the self-view surface settle, then bind the other camera. Taps during the swap are ignored.
     */
    fun flipCamera() {
        val v = _video.value
        if (v.switching || !v.camOn) return
        val toFront = !v.front
        val ctx = appCtx
        if (ctx != null && cameraId(ctx, toFront) == null) {
            Notices.show(if (toFront) "No front camera found" else "No rear camera found")
            return
        }
        _video.value = v.copy(front = toFront, switching = true)
        syncAllVideo() // releases the current camera and hides the self-view
        flipTimer = postOnce(flipTimer, 450) {
            _video.value = _video.value.copy(switching = false)
            syncAllVideo() // binds the new camera; the self-view comes back with a fresh surface
        }
    }

    private fun postOnce(previous: Runnable?, delayMs: Long, block: () -> Unit): Runnable {
        previous?.let(main::removeCallbacks)
        return Runnable { block() }.also { main.postDelayed(it, delayMs) }
    }

    private fun cancelVideoTimers() {
        flipTimer?.let(main::removeCallbacks); flipTimer = null
        pendingTimer?.let(main::removeCallbacks); pendingTimer = null
    }
    fun setPreviewSurface(id: Int, s: Surface?) = withVideo(id) { it.setPreviewSurface(s) }
    fun setDisplaySurface(id: Int, s: Surface?) = withVideo(id) { it.setDisplaySurface(s) }
    fun setOrientation(id: Int, degrees: Int) = withVideo(id) { it.setDeviceOrientation(degrees) }

    /** Ask the other side to switch to video (on) or back to voice (off). */
    fun requestVideo(id: Int, on: Boolean) {
        if (on && _video.value.pendingUp) { Diag.log("video_request_ignored", id, "already_pending"); return }
        Diag.log(if (on) "video_request_out" else "video_to_voice", id)
        if (on) {
            videoReqAt[id] = SystemClock.elapsedRealtime()
            _video.value = _video.value.copy(pendingUp = true)
            pendingTimer = postOnce(pendingTimer, 45_000) { _video.value = _video.value.copy(pendingUp = false) } // if no response ever arrives
        } else {
            pendingTimer?.let(main::removeCallbacks); pendingTimer = null
            _video.value = _video.value.copy(pendingUp = false)
        }
        withVideo(id) { it.sendSessionModifyRequest(VideoProfile(if (on) VideoProfile.STATE_BIDIRECTIONAL else VideoProfile.STATE_AUDIO_ONLY, VideoProfile.QUALITY_HIGH)) }
    }

    /** Answer the other side's request to switch to video. [receiveOnly] accepts without sending our camera. */
    fun answerVideoRequest(id: Int, accept: Boolean, receiveOnly: Boolean = false) {
        val asked = upgradeAsk.remove(id) ?: return
        val state = when {
            !accept -> refs[id]?.details?.videoState ?: VideoProfile.STATE_AUDIO_ONLY
            receiveOnly -> asked and VideoProfile.STATE_TX_ENABLED.inv()
            else -> asked
        }
        Diag.log("video_request_answer", id, "accept=$accept state=$state")
        withVideo(id) { it.sendSessionModifyResponse(VideoProfile(state, VideoProfile.QUALITY_HIGH)) }
        publish()
    }

    /** Pick up a carrier video call as video. Without camera permission we still receive their video. */
    fun answerVideo(id: Int, withCamera: Boolean) {
        val call = refs[id] ?: return
        val vs = call.details.videoState
        Diag.log("answer_video", id, "state=$vs camera=$withCamera")
        call.answer(if (withCamera) vs else vs and VideoProfile.STATE_TX_ENABLED.inv())
    }

    fun answer(id: Int) { Diag.log("answer_tapped", id); refs[id]?.answer(VideoProfile.STATE_AUDIO_ONLY) }
    fun reject(id: Int, text: String? = null) {
        Diag.log("decline_tapped", id, if (text != null) "with_message" else "")
        refs[id]?.reject(text != null, text) // Telecom sends the SMS itself, so no SMS permission is needed here
    }
    fun rejectRinging(source: String) {
        ringing()?.let { Diag.log("decline_source", it.id, "source=$source"); reject(it.id) }
    }
    fun disconnect(id: Int) { Diag.log("disconnect_tapped", id); refs[id]?.disconnect() }
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

    fun setMuted(m: Boolean) { service?.get()?.setMuted(m) }

    fun setRoute(route: Int) { (service?.get() as? DialerInCallService)?.routeAudio(route) }

    // Lint wants MODIFY_PHONE_STATE, a system-only permission; the default dialer role is what Telecom accepts instead.
    @SuppressLint("MissingPermission")
    fun silence(ctx: Context) {
        if (!roleHeld(ctx)) { Diag.log("silence_denied", detail = "not the default dialer"); return }
        ctx.getSystemService(TelecomManager::class.java)?.silenceRinger()
    }
}
