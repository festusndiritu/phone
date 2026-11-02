package dev.mizzenmast.dialer

import android.content.Context
import android.provider.Settings
import android.telecom.Call
import android.telecom.PhoneAccountHandle
import androidx.core.os.BundleCompat

/**
 * The few platform values this app needs that are not in the public SDK, kept in one place. They are read defensively:
 * if a platform release changes one, a single feature degrades (no video-upgrade button, SIM list from our own query)
 * instead of the call screen failing.
 */
object HiddenApi {
    /** Call.Details.CAPABILITY_CAN_UPGRADE_TO_VIDEO; the same bit as Connection's. */
    private const val CAPABILITY_CAN_UPGRADE_TO_VIDEO = 0x00080000

    /** Call.AVAILABLE_PHONE_ACCOUNTS: the accounts Telecom offers while it waits for a SIM to be chosen. */
    private const val EXTRA_AVAILABLE_PHONE_ACCOUNTS = "selectPhoneAccountAccounts"

    /** Settings.Secure.INCALL_POWER_BUTTON_BEHAVIOR: 1 turns the screen off, 2 hangs up. */
    private const val SETTING_POWER_BUTTON_BEHAVIOR = "incall_power_button_behavior"
    private const val POWER_BUTTON_HANGS_UP = 2

    /** "This call can be switched to video": the hidden capability bit, or the public "both sides can send and receive" bits. */
    fun canUpgradeToVideo(call: Call): Boolean {
        val caps = call.details.callCapabilities
        val local = Call.Details.CAPABILITY_SUPPORTS_VT_LOCAL_BIDIRECTIONAL
        val remote = Call.Details.CAPABILITY_SUPPORTS_VT_REMOTE_BIDIRECTIONAL
        return (caps and CAPABILITY_CAN_UPGRADE_TO_VIDEO) != 0 || ((caps and local) == local && (caps and remote) == remote)
    }

    /** The accounts Telecom is asking the person to pick from, or null when it isn't (or the extra is missing). */
    fun offeredPhoneAccounts(call: Call): List<PhoneAccountHandle>? = try {
        call.details.extras
            ?.let { BundleCompat.getParcelableArrayList(it, EXTRA_AVAILABLE_PHONE_ACCOUNTS, PhoneAccountHandle::class.java) }
            ?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) { Diag.fail("offered_accounts", e); null }

    /** True when pressing power during a call hangs up. Assumes it does if the setting can't be read, which keeps the screen on. */
    fun powerButtonEndsCall(ctx: Context): Boolean = try {
        Settings.Secure.getInt(ctx.contentResolver, SETTING_POWER_BUTTON_BEHAVIOR, 1) == POWER_BUTTON_HANGS_UP
    } catch (e: Exception) { Diag.fail("power_button_ends_call", e); true }
}
