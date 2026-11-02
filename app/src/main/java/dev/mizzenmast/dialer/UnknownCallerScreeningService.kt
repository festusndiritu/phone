package dev.mizzenmast.dialer

import android.Manifest
import android.telecom.Call
import android.telecom.CallScreeningService

/**
 * Silences (no ring, no heads-up; still logged as missed) incoming calls from numbers that aren't saved contacts.
 * Telecom waits for [onScreenCall] before it rings, so this must stay one quick local query. The component is only
 * enabled while the Settings toggle is on. Only a lookup that ran and found nobody silences a call; a missing permission or a
 * provider error never silences everyone.
 */
class UnknownCallerScreeningService : CallScreeningService() {
    override fun onScreenCall(details: Call.Details) {
        Core.init(this)
        val incoming = details.callDirection == Call.Details.DIRECTION_INCOMING
        var silence = false
        if (incoming && Prefs.silenceUnknown.value && granted(Manifest.permission.READ_CONTACTS)) {
            val number = details.handle?.schemeSpecificPart
            silence = number.isNullOrBlank() || ContactsRepo.lookup(this, number) is Lookup.NoMatch
        }
        respondToCall(details, CallResponse.Builder().setSilenceCall(silence).build())
    }
}
