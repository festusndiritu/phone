package dev.mizzenmast.dialer

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.telecom.Call
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

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
        startActivity(DialerIntents.openDialer(this))
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
        val need = live.isNotEmpty() && (special || HiddenApi.powerButtonEndsCall(this))
        if (need == keepOn) return
        keepOn = need
        Diag.log("keep_screen_on", CallManager.currentCallId(), "on=$need powerButtonEndsCall=${HiddenApi.powerButtonEndsCall(this)}")
        if (need) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

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
