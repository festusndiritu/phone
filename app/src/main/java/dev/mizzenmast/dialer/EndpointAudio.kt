package dev.mizzenmast.dialer

import android.os.OutcomeReceiver
import android.telecom.CallEndpoint
import android.telecom.CallEndpointException
import android.telecom.InCallService
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat

/**
 * Call audio on Android 14+, where CallEndpoint replaces CallAudioState. Keeps the three things the callbacks deliver
 * (current endpoint, available endpoints, mute) and publishes them in the [AudioUi] shape the UI already uses.
 */
@RequiresApi(34)
internal class EndpointAudio(private val service: InCallService) {
    private var current: CallEndpoint? = null
    private var available: List<CallEndpoint> = emptyList()
    private var muted = CallManager.audio.value?.muted ?: false

    val hasState get() = current != null

    fun onChanged(endpoint: CallEndpoint) { current = endpoint; publish() }
    fun onAvailable(endpoints: List<CallEndpoint>) { available = endpoints; publish() }
    fun onMuteChanged(isMuted: Boolean) { muted = isMuted; publish() }

    /** Asks Telecom to move audio to [route]. False if no such endpoint is known (yet), so the caller can fall back. */
    fun route(route: Int): Boolean {
        val target = available.firstOrNull { routeOf(it.endpointType) == route } ?: return false
        service.requestCallEndpointChange(target, ContextCompat.getMainExecutor(service), object : OutcomeReceiver<Void, CallEndpointException> {
            override fun onResult(result: Void?) {}
            override fun onError(error: CallEndpointException) { Diag.log("audio_route_failed", 0, "code=${error.code}") }
        })
        return true
    }

    private fun publish() {
        val now = current ?: return
        val route = routeOf(now.endpointType)
        val mask = available.fold(route) { m, e -> m or routeOf(e.endpointType) }
        CallManager.setAudio(AudioUi(route, mask, muted, if (route == AudioUi.BLUETOOTH) now.endpointName.toString() else null))
    }

    private fun routeOf(type: Int) = when (type) {
        CallEndpoint.TYPE_EARPIECE -> AudioUi.EARPIECE
        CallEndpoint.TYPE_BLUETOOTH -> AudioUi.BLUETOOTH
        CallEndpoint.TYPE_WIRED_HEADSET -> AudioUi.WIRED
        CallEndpoint.TYPE_SPEAKER -> AudioUi.SPEAKER
        else -> 0
    }
}
