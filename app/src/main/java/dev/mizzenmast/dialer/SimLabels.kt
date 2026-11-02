package dev.mizzenmast.dialer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

/** SIM names for the call screen and history. Always called off the main thread; every failure just means "no chip". */
object SimLabels {
    private val labels = HashMap<String, String>()
    private val slots = HashMap<String, Int>()
    private var setup: String? = null

    /**
     * Forgets cached names and slots when the SIM setup changed since the last call (a SIM swapped, moved to another slot,
     * removed or renamed). One cheap lookup; call it before reading a batch of labels.
     */
    @Synchronized
    fun refresh(ctx: Context) {
        val now = describeSetup(ctx)
        if (now == setup) return
        setup = now
        labels.clear()
        slots.clear()
    }

    private fun describeSetup(ctx: Context): String {
        if (!ctx.granted(Manifest.permission.READ_PHONE_STATE)) return "no-permission"
        val subs = try {
            ctx.getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList.orEmpty()
                .joinToString(",") { "${it.subscriptionId}:${it.simSlotIndex}:${it.displayName}" }
        } catch (e: Exception) { Diag.fail("sim_setup", e); "" }
        return accounts(ctx).joinToString(",") { key(it) } + "|" + subs
    }

    /** Call-capable accounts; empty (not an error) when READ_PHONE_STATE is not granted. */
    fun accounts(ctx: Context): List<PhoneAccountHandle> {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return emptyList()
        return ctx.getSystemService(TelecomManager::class.java)?.callCapablePhoneAccounts.orEmpty()
    }

    fun multiSim(ctx: Context): Boolean = accounts(ctx).size > 1

    fun key(h: PhoneAccountHandle): String = h.componentName.flattenToString() + "/" + h.id

    /** False when there is no default SIM for calls, or when READ_PHONE_STATE is not granted (can't tell). */
    fun hasDefaultAccount(ctx: Context): Boolean {
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED && ctx.getSystemService(TelecomManager::class.java)?.getDefaultOutgoingPhoneAccount("tel") != null
    }

    @Synchronized
    fun label(ctx: Context, h: PhoneAccountHandle?): String? {
        h ?: return null
        val key = h.componentName.flattenToString() + "/" + h.id
        labels[key]?.let { return it }
        val l = try { ctx.getSystemService(TelecomManager::class.java)?.getPhoneAccount(h)?.label?.toString()?.takeIf { it.isNotBlank() } }
        catch (e: Exception) { Diag.fail("sim_label", e); null }
        if (l != null) labels[key] = l // a miss (SIM gone, no permission) mustn't stick
        return l
    }

    /** 1-based physical SIM slot for a call account, or null when it can't be determined (no permission, SIM removed). */
    @Synchronized
    fun slot(ctx: Context, component: String?, id: String?): Int? {
        if (component.isNullOrBlank() || id == null) return null
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return null
        val key = "$component/$id"
        slots[key]?.let { return it }
        val slot = try {
            val cn = android.content.ComponentName.unflattenFromString(component)
            val subId = cn?.let { ctx.getSystemService(TelephonyManager::class.java)?.getSubscriptionId(
                PhoneAccountHandle(it, id)
            ) }
            if (subId == null || subId < 0) null
            else ctx.getSystemService(SubscriptionManager::class.java)?.getActiveSubscriptionInfo(subId)?.simSlotIndex?.takeIf { it >= 0 }?.plus(1)
        } catch (e: Exception) { Diag.fail("sim_slot", e); null }
        if (slot != null) slots[key] = slot // only hits are cached: a null may just be a missing permission
        return slot
    }

    fun label(ctx: Context, component: String?, id: String?): String? {
        if (component.isNullOrBlank() || id == null) return null
        val cn = android.content.ComponentName.unflattenFromString(component) ?: return null
        return label(ctx, PhoneAccountHandle(cn, id))
    }
}
