package dev.mizzenmast.dialer

import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.CallEndpoint
import android.telecom.InCallService
import androidx.annotation.RequiresApi

class DialerInCallService : InCallService() {
    /** Android 14+ delivers audio through call endpoints; before that, through CallAudioState. */
    private val endpoints: EndpointAudio? = if (Build.VERSION.SDK_INT >= 34) EndpointAudio(this) else null

    override fun onCreate() {
        super.onCreate()
        Core.init(this)
        Diag.log("service_created")
        CallNotifications.clearAll(this) // anything left from a previous process is stale by definition
        CallManager.attach(this)
    }

    override fun onBind(intent: Intent?): IBinder? {
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

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION") // see AudioUi
    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        if (endpoints?.hasState == true) return // the endpoint callbacks are the source of truth once they have reported
        CallManager.setAudio(AudioUi.of(this, audioState))
    }

    @RequiresApi(34)
    override fun onCallEndpointChanged(callEndpoint: CallEndpoint) { endpoints?.onChanged(callEndpoint) }

    @RequiresApi(34)
    override fun onAvailableCallEndpointsChanged(availableEndpoints: List<CallEndpoint>) { endpoints?.onAvailable(availableEndpoints) }

    @RequiresApi(34)
    override fun onMuteStateChanged(isMuted: Boolean) { endpoints?.onMuteChanged(isMuted) }

    /** Moves call audio to [route] (an [AudioUi] route). */
    @Suppress("DEPRECATION") // setAudioRoute is the only way before Android 14
    fun routeAudio(route: Int) {
        if (endpoints?.route(route) != true) setAudioRoute(route)
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
