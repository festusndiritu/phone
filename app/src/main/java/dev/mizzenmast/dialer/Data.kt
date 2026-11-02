package dev.mizzenmast.dialer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import java.util.Locale

fun Context.granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

/** Needed to place calls and to know which SIM to offer. */
val PHONE_PERMS = arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE)

data class ContactPhone(val number: String, val label: String)
/** [savedIn] is where the contact is stored: a Google account's email, "Phone", or a SIM (see [ContactsRepo.sourceLabel]). */
data class Contact(
    val id: Long, val lookupKey: String?, val name: String, val photo: String?, val phones: List<ContactPhone>,
    val starred: Boolean = false, val savedIn: Set<String> = emptySet()
)
data class Found(val name: String?, val photo: String?)

/** A contacts lookup that didn't match anyone is not the same as one that couldn't run (no permission, provider error). */
sealed interface Lookup {
    data class Match(val found: Found) : Lookup
    data object NoMatch : Lookup
    data object Unavailable : Lookup
}
data class LogEntry(
    val id: Long, val number: String, val name: String?, val type: Int,
    val date: Long, val durationSec: Long, val presentation: Int,
    val sim: String? = null, val simSlot: Int? = null
) {
    /** What the SIM chip shows: the physical slot (1/2) when known, else the account label. */
    val simText: String? get() = simSlot?.toString() ?: sim
}

sealed interface Load<out T> {
    object Loading : Load<Nothing>
    object Denied : Load<Nothing>
    data class Failed(val msg: String) : Load<Nothing>
    data class Ready<T>(val data: T) : Load<T>
}

/** Opens a WhatsApp chat via wa.me, optionally with a prefilled message. Numbers are normalized to international format using the SIM's country. */
fun openWhatsApp(ctx: Context, number: String, text: String? = null) {
    val iso = (ctx.getSystemService(TelephonyManager::class.java)?.simCountryIso?.takeIf { it.isNotBlank() }
        ?: Locale.getDefault().country).uppercase()
    val digits = (PhoneNumberUtils.formatNumberToE164(number, iso) ?: number).filter(Char::isDigit)
    if (digits.isEmpty()) { Notices.show("Invalid number"); return }
    val uri = ("https://wa.me/$digits" + (text?.let { "?text=" + Uri.encode(it) } ?: "")).toUri()
    for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
        try {
            ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri).setPackage(pkg).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: android.content.ActivityNotFoundException) { }
    }
    Notices.show("WhatsApp not installed")
}

fun pretty(number: String?): String? {
    if (number.isNullOrBlank()) return null
    return try { PhoneNumberUtils.formatNumber(number, Locale.getDefault().country) ?: number } catch (e: Exception) { Diag.fail("format_number", e); number }
}

fun CallInfo.title(): String = when {
    isConference -> "Conference call"
    name != null -> name
    presentation == TelecomManager.PRESENTATION_RESTRICTED -> "Private number"
    presentation == TelecomManager.PRESENTATION_PAYPHONE -> "Payphone"
    number != null -> pretty(number) ?: number
    else -> "Unknown caller"
}

fun LogEntry.title(contact: Contact?): String = when {
    contact != null -> contact.name
    !name.isNullOrBlank() -> name
    presentation == CallLog.Calls.PRESENTATION_RESTRICTED -> "Private number"
    presentation == CallLog.Calls.PRESENTATION_PAYPHONE -> "Payphone"
    presentation == CallLog.Calls.PRESENTATION_UNKNOWN || number.isBlank() -> "Unknown"
    else -> pretty(number) ?: number
}

class ContactIndex(list: List<Contact>) {
    private val map = HashMap<String, Contact>().apply {
        list.forEach { c -> c.phones.forEach { p -> val k = key(p.number); if (k.isNotEmpty()) putIfAbsent(k, c) } }
    }
    fun find(number: String?): Contact? = number?.takeIf { it.isNotBlank() }?.let { map[key(it)] }
    companion object {
        fun key(n: String): String = PhoneKey.of(n)
    }
}
