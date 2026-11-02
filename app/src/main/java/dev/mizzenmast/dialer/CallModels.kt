package dev.mizzenmast.dialer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.DisconnectCause
import android.telecom.PhoneAccountHandle
import android.telecom.VideoProfile
import androidx.core.content.ContextCompat

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
    val sim: String? = null,
    val videoState: Int = 0,
    /** Carrier says this call can be switched to video (Google Phone's "video call" button). */
    val canVideo: Boolean = false,
    /** Video state the other side asked to switch to; 0 = no pending request. */
    val videoRequest: Int = 0,
    /** Why an outgoing call that never connected ended (Busy, Declined, No answer...); null = use [endText]. */
    val endLabel: String? = null
) {
    val isVideo get() = VideoProfile.isVideo(videoState)
    val sendingVideo get() = VideoProfile.isTransmissionEnabled(videoState)
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

/**
 * Audio route and mute state. CallAudioState is deprecated from API 34 (CallEndpoint replaces it) but is the only API
 * on API 30-33, so it is read here and nowhere else.
 */
@Suppress("DEPRECATION")
data class AudioUi(val route: Int, val mask: Int, val muted: Boolean, val btName: String?) {
    companion object {
        const val EARPIECE = CallAudioState.ROUTE_EARPIECE
        const val SPEAKER = CallAudioState.ROUTE_SPEAKER
        const val BLUETOOTH = CallAudioState.ROUTE_BLUETOOTH
        const val WIRED = CallAudioState.ROUTE_WIRED_HEADSET

        fun of(ctx: Context, s: CallAudioState?) = s?.let { AudioUi(it.route, it.supportedRouteMask, it.isMuted, bluetoothName(ctx, it)) }

        /** The device name needs BLUETOOTH_CONNECT from API 31; without it the UI just says "Bluetooth". */
        private fun bluetoothName(ctx: Context, s: CallAudioState): String? {
            val device = s.activeBluetoothDevice ?: return null
            if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return null
            return device.name
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
