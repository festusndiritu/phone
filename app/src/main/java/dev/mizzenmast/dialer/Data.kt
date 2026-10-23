package dev.mizzenmast.dialer

import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.Locale

fun Context.granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

/** Needed to place calls and to know which SIM to offer. */
val PHONE_PERMS = arrayOf(android.Manifest.permission.CALL_PHONE, android.Manifest.permission.READ_PHONE_STATE)

data class ContactPhone(val number: String, val label: String)
/** [savedIn] is where the contact is stored: a Google account's email, "Phone", or a SIM (see [ContactsRepo.sourceLabel]). */
data class Contact(
    val id: Long, val lookupKey: String?, val name: String, val photo: String?, val phones: List<ContactPhone>,
    val starred: Boolean = false, val savedIn: Set<String> = emptySet()
)
data class Found(val name: String?, val photo: String?)
data class LogEntry(
    val id: Long, val number: String, val name: String?, val type: Int,
    val date: Long, val durationSec: Long, val presentation: Int,
    val sim: String? = null, val simSlot: Int? = null
) {
    /** What the SIM chip shows: the physical slot (1/2) when known, else the account label. */
    val simText: String? get() = simSlot?.toString() ?: sim
}

/** SIM names for the call screen and history. Always called off the main thread; every failure just means "no chip". */
object SimLabels {
    private val cache = HashMap<String, String?>()

    /** Call-capable accounts; empty (not an error) when READ_PHONE_STATE is not granted. */
    fun accounts(ctx: Context): List<android.telecom.PhoneAccountHandle> {
        if (ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return emptyList()
        return ctx.getSystemService(TelecomManager::class.java)?.callCapablePhoneAccounts.orEmpty()
    }

    fun multiSim(ctx: Context): Boolean = accounts(ctx).size > 1

    /** False when there is no default SIM for calls, or when READ_PHONE_STATE is not granted (can't tell). */
    fun hasDefaultAccount(ctx: Context): Boolean {
        return ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED && ctx.getSystemService(TelecomManager::class.java)?.getDefaultOutgoingPhoneAccount("tel") != null
    }

    @Synchronized
    fun label(ctx: Context, h: android.telecom.PhoneAccountHandle?): String? {
        h ?: return null
        val key = h.componentName.flattenToString() + "/" + h.id
        if (cache.containsKey(key)) return cache[key]
        val l = try { ctx.getSystemService(TelecomManager::class.java)?.getPhoneAccount(h)?.label?.toString()?.takeIf { it.isNotBlank() } }
        catch (e: Exception) { Diag.fail("sim_label", e); null }
        cache[key] = l
        return l
    }

    private val slotCache = HashMap<String, Int?>()

    /** 1-based physical SIM slot for a call account, or null when it can't be determined (no permission, SIM removed). */
    @Synchronized
    fun slot(ctx: Context, component: String?, id: String?): Int? {
        if (component.isNullOrBlank() || id == null) return null
        if (ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return null
        val key = "$component/$id"
        if (slotCache.containsKey(key)) return slotCache[key]
        val slot = try {
            val cn = android.content.ComponentName.unflattenFromString(component)
            val subId = cn?.let { ctx.getSystemService(android.telephony.TelephonyManager::class.java)?.getSubscriptionId(android.telecom.PhoneAccountHandle(it, id)) }
            if (subId == null || subId < 0) null
            else ctx.getSystemService(android.telephony.SubscriptionManager::class.java)?.getActiveSubscriptionInfo(subId)?.simSlotIndex?.takeIf { it >= 0 }?.plus(1)
        } catch (e: Exception) { Diag.fail("sim_slot", e); null }
        // A null caused by a missing permission shouldn't stick once it's granted, so only cache hits.
        if (slot != null) slotCache[key] = slot
        return slot
    }

    fun label(ctx: Context, component: String?, id: String?): String? {
        if (component.isNullOrBlank() || id == null) return null
        val cn = android.content.ComponentName.unflattenFromString(component) ?: return null
        return label(ctx, android.telecom.PhoneAccountHandle(cn, id))
    }
}

sealed interface Load<out T> {
    object Loading : Load<Nothing>
    object Denied : Load<Nothing>
    data class Failed(val msg: String) : Load<Nothing>
    data class Ready<T>(val data: T) : Load<T>
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
        fun key(n: String): String {
            val d = n.filter(Char::isDigit)
            return if (d.length >= 7) d.takeLast(9) else d
        }
    }
}

private fun changes(ctx: Context, uri: Uri): Flow<Unit> = callbackFlow {
    val o = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { trySend(Unit) }
    }
    try { ctx.contentResolver.registerContentObserver(uri, true, o) } catch (e: SecurityException) { Diag.fail("observer", e) }
    trySend(Unit)
    awaitClose { ctx.contentResolver.unregisterContentObserver(o) }
}

object ContactsRepo {
    fun changes(ctx: Context) = changes(ctx, ContactsContract.Contacts.CONTENT_URI)

    /** Blocking; call from IO. */
    fun loadAll(ctx: Context): List<Contact> {
        val cols = arrayOf(Phone.CONTACT_ID, Phone.LOOKUP_KEY, Phone.DISPLAY_NAME_PRIMARY, Phone.NUMBER, Phone.TYPE, Phone.LABEL, Phone.PHOTO_THUMBNAIL_URI, Phone.STARRED)
        val out = LinkedHashMap<Long, Contact>()
        ctx.contentResolver.query(Phone.CONTENT_URI, cols, null, null, "${Phone.DISPLAY_NAME_PRIMARY} COLLATE LOCALIZED ASC")?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val num = c.getString(3)?.trim().orEmpty()
                if (num.isEmpty()) continue
                val label = Phone.getTypeLabel(ctx.resources, c.getInt(4), c.getString(5)).toString()
                val ex = out[id]
                if (ex == null) {
                    out[id] = Contact(id, c.getString(1), c.getString(2) ?: num, c.getString(6), listOf(ContactPhone(num, label)), starred = c.getInt(7) == 1)
                } else if (ex.phones.none { ContactIndex.key(it.number) == ContactIndex.key(num) }) {
                    out[id] = ex.copy(phones = ex.phones + ContactPhone(num, label))
                }
            }
        }
        val saved = savedIn(ctx)
        return out.values.map { it.copy(savedIn = saved[it.id].orEmpty()) }
    }

    /** Account names differ per phone maker, so this goes by what the account type says and falls back to the account's own name. */
    fun sourceLabel(type: String?, name: String?): String = when {
        type == "com.google" -> name?.takeIf { it.isNotBlank() } ?: "Google"
        type?.contains("sim", ignoreCase = true) == true -> name?.takeIf { it.contains("sim", ignoreCase = true) } ?: "SIM"
        type.isNullOrBlank() || listOf("local", "device", "phone").any { type.contains(it, ignoreCase = true) } -> "Phone"
        else -> name?.takeIf { it.isNotBlank() } ?: type
    }

    /** Contact id to the places it is stored. A joined contact can live in several accounts. Failure only means no source filters. */
    private fun savedIn(ctx: Context): Map<Long, Set<String>> {
        val out = HashMap<Long, MutableSet<String>>()
        try {
            val cols = arrayOf(ContactsContract.RawContacts.CONTACT_ID, ContactsContract.RawContacts.ACCOUNT_TYPE, ContactsContract.RawContacts.ACCOUNT_NAME)
            ctx.contentResolver.query(ContactsContract.RawContacts.CONTENT_URI, cols, "${ContactsContract.RawContacts.DELETED} = 0", null, null)?.use { c ->
                while (c.moveToNext()) out.getOrPut(c.getLong(0)) { HashSet() }.add(sourceLabel(c.getString(1), c.getString(2)))
            }
        } catch (e: Exception) { Diag.fail("contact_sources", e) }
        return out
    }

    /** Blocking; call from IO. Needs WRITE_CONTACTS. Returns false if nothing was updated. */
    fun setStarred(ctx: Context, contactId: Long, starred: Boolean): Boolean = try {
        val v = android.content.ContentValues().apply { put(ContactsContract.Contacts.STARRED, if (starred) 1 else 0) }
        ctx.contentResolver.update(Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_URI, contactId.toString()), v, null, null) > 0
    } catch (e: Exception) { Diag.fail("set_starred", e); false }

    /** Fast single-number lookup for incoming calls. Blocking; call from IO. Returns null if unavailable. */
    fun lookup(ctx: Context, number: String): Found? {
        if (!ctx.granted(android.Manifest.permission.READ_CONTACTS)) return null
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            ctx.contentResolver.query(
                uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME, ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI),
                null, null, null
            )?.use { c -> if (c.moveToFirst()) Found(c.getString(0), c.getString(1)) else null }
        } catch (e: Exception) { Diag.fail("contact_lookup", e); null }
    }
}

object CallLogRepo {
    fun changes(ctx: Context) = changes(ctx, CallLog.Calls.CONTENT_URI)

    fun load(ctx: Context, limit: Int = 400): List<LogEntry> {
        val cols = arrayOf(
            CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE,
            CallLog.Calls.DURATION, CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER_PRESENTATION,
            CallLog.Calls.PHONE_ACCOUNT_COMPONENT_NAME, CallLog.Calls.PHONE_ACCOUNT_ID
        )
        val multi = SimLabels.multiSim(ctx)
        // Per-load memo, misses included. A call from a SIM that's gone (or without permission) never caches inside
        // SimLabels, so without this every such row would ask Telephony again: two binder calls per row, per reload.
        val slots = HashMap<String, Int?>()
        val args = Bundle().apply {
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(CallLog.Calls.DATE))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
        }
        val out = ArrayList<LogEntry>()
        ctx.contentResolver.query(CallLog.Calls.CONTENT_URI, cols, args, null)?.use { c ->
            while (c.moveToNext()) {
                val comp = c.getString(7)
                val acct = c.getString(8)
                val slot = if (!multi) null else "$comp/$acct".let { k ->
                    if (slots.containsKey(k)) slots[k] else SimLabels.slot(ctx, comp, acct).also { slots[k] = it }
                }
                out += LogEntry(c.getLong(0), c.getString(1).orEmpty(), c.getString(5), c.getInt(2), c.getLong(3), c.getLong(4), c.getInt(6),
                    sim = if (multi) SimLabels.label(ctx, comp, acct) else null,
                    simSlot = slot)
            }
        }
        return out
    }

    /** Wipes the whole call log (every call, not just what Recents currently shows). True unless the system refused. */
    fun clear(ctx: Context): Boolean = try {
        ctx.contentResolver.delete(CallLog.Calls.CONTENT_URI, null, null)
        true
    } catch (e: SecurityException) { Diag.fail("clear_call_log", e); false }

    /** Returns how many rows were removed. */
    fun delete(ctx: Context, ids: Collection<Long>): Int = try {
        ids.chunked(200).sumOf { chunk ->
            ctx.contentResolver.delete(CallLog.Calls.CONTENT_URI,
                "${CallLog.Calls._ID} IN (${chunk.joinToString(",") { "?" }})", chunk.map { it.toString() }.toTypedArray())
        }
    } catch (e: SecurityException) { Diag.fail("delete_call_log", e); 0 }
}

/** System block list. Writing needs the default-dialer role; Telecom itself rejects calls from these numbers before they ring. Blocking; call from IO. */
object BlockedRepo {
    fun canBlock(ctx: Context): Boolean = try { android.provider.BlockedNumberContract.canCurrentUserBlockNumbers(ctx) } catch (e: Exception) { Diag.fail("can_block", e); false }

    fun isBlocked(ctx: Context, number: String): Boolean = try { android.provider.BlockedNumberContract.isBlocked(ctx, number) } catch (e: Exception) { Diag.fail("is_blocked", e); false }

    fun block(ctx: Context, number: String): Boolean = try {
        val v = android.content.ContentValues().apply { put(android.provider.BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, number) }
        ctx.contentResolver.insert(android.provider.BlockedNumberContract.BlockedNumbers.CONTENT_URI, v) != null
    } catch (e: Exception) { Diag.fail("block_number", e); false }

    fun unblock(ctx: Context, number: String): Boolean = try {
        android.provider.BlockedNumberContract.unblock(ctx, number) > 0
    } catch (e: Exception) { Diag.fail("unblock", e); false }
}
