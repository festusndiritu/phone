package dev.mizzenmast.dialer

import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils

/** What to do about a "call this number" request, worked out before anything is shown or dialed. */
sealed interface CallPlan {
    /** Place it now. [handle] is the SIM to use, or null for the system default. [note] explains a fallback worth telling the person about. */
    data class Dial(val handle: PhoneAccountHandle?, val note: String? = null) : CallPlan
    /** Two SIMs and no default for calls: ask which one. */
    data class ChooseSim(val number: String, val options: List<SimOption>) : CallPlan
    /** CALL_PHONE isn't granted yet. */
    data object NeedsPermission : CallPlan
    data class Invalid(val message: String) : CallPlan
}

sealed interface CallSubmit {
    data object Sent : CallSubmit
    data class Failed(val message: String, val permissionLost: Boolean = false) : CallSubmit
}

/**
 * Placing calls through Telecom, kept apart from the Activity. [plan] decides (permissions, which SIM), [submit] hands the call over.
 * "Sent" only means Telecom accepted the request: if the call then fails (no signal, no SIM), the call screen says so.
 */
object CallPlacer {
    fun plan(ctx: Context, raw: String): CallPlan {
        val n = raw.trim()
        if (n.isEmpty()) return CallPlan.Invalid("Enter a number first")
        if (!ctx.granted(Manifest.permission.CALL_PHONE)) return CallPlan.NeedsPermission
        // An emergency call must never wait behind a SIM picker; Telecom chooses the best SIM itself.
        if (isEmergency(n)) return CallPlan.Dial(null)
        if (!ctx.granted(Manifest.permission.READ_PHONE_STATE)) {
            return CallPlan.Dial(null, "Calling with your default SIM. Allow phone access to choose one.")
        }
        SimLabels.refresh(ctx)
        val sims = SimLabels.accounts(ctx)
        val saved = Prefs.simFor(n)
        val preferred = saved?.let { k -> sims.firstOrNull { SimLabels.key(it) == k } }
        if (preferred != null) return CallPlan.Dial(preferred)
        if (sims.size > 1 && !SimLabels.hasDefaultAccount(ctx)) {
            return CallPlan.ChooseSim(n, sims.map { h -> SimOption(h, SimLabels.label(ctx, h) ?: "SIM") })
        }
        return CallPlan.Dial(null, if (saved != null) "The SIM saved for this number isn't available. Using your default SIM." else null)
    }

    fun submit(ctx: Context, number: String, handle: PhoneAccountHandle?): CallSubmit {
        val telecom = ctx.getSystemService(TelecomManager::class.java)
            ?: return CallSubmit.Failed("Calling isn't available on this device")
        return try {
            val extras = Bundle().apply { if (handle != null) putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle) }
            telecom.placeCall(Uri.fromParts("tel", number, null), extras)
            Diag.log("place_call_requested", 0, if (handle != null) "sim=chosen" else "sim=default")
            CallSubmit.Sent
        } catch (e: SecurityException) {
            Diag.fail("place_call", e)
            CallSubmit.Failed("Phone permission was turned off. Allow it to place calls.", permissionLost = true)
        } catch (e: IllegalArgumentException) {
            Diag.fail("place_call", e)
            CallSubmit.Failed("That number can't be dialed")
        } catch (e: Exception) {
            Diag.fail("place_call", e)
            CallSubmit.Failed("Couldn't place the call. Please try again.")
        }
    }

    @Suppress("DEPRECATION") // TelephonyManager.isEmergencyNumber needs more permission than we hold
    private fun isEmergency(n: String) = try { PhoneNumberUtils.isEmergencyNumber(n) } catch (_: Exception) { false }
}
