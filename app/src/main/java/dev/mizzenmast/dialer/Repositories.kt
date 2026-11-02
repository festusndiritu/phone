package dev.mizzenmast.dialer

import android.Manifest
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
import android.telephony.SubscriptionManager
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

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
    fun sourceLabel(ctx: Context?, type: String?, name: String?): String = when {
        type == "com.google" -> name?.takeIf { it.isNotBlank() } ?: "Google"
        type?.contains("whatsapp", ignoreCase = true) == true -> "WhatsApp"
        type?.contains("sim", ignoreCase = true) == true -> {
            if (
                ctx != null &&
                ContextCompat.checkSelfPermission(
                    ctx,
                    Manifest.permission.READ_PHONE_STATE
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                simSource(ctx, name)
            } else {
                // Still identify SIM 1 / SIM 2 from the account name if possible.
                name?.let {
                    Regex("(?i)sim\\D{0,3}([12])").find(it)
                        ?.let { match -> "SIM ${match.groupValues[1]}" }
                } ?: "SIM"
            }
        }
        type.isNullOrBlank() || listOf("local", "device", "phone").any { type.contains(it, ignoreCase = true) } -> "Phone"
        else -> name?.takeIf { it.isNotBlank() } ?: type
    }

    /** "SIM 1" / "SIM 2" from the account name ("SIM1", "USIM 2") or from the carrier it's named after; plain "SIM" when it can't tell. */
    @RequiresPermission(Manifest.permission.READ_PHONE_STATE)
    private fun simSource(ctx: Context?, name: String?): String {
        name?.let { n -> Regex("(?i)sim\\D{0,3}([12])").find(n)?.let { return "SIM ${it.groupValues[1]}" } }
        if (
            ctx != null &&
            !name.isNullOrBlank() &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                val subs = ctx.getSystemService(
                    SubscriptionManager::class.java
                )?.activeSubscriptionInfoList.orEmpty()

                subs.firstOrNull { s ->
                    listOf(s.displayName, s.carrierName).any { v ->
                        !v.isNullOrBlank() &&
                                name.contains(v.toString(), ignoreCase = true)
                    }
                }?.let { return "SIM ${it.simSlotIndex + 1}" }
            } catch (e: SecurityException) {
                Diag.fail("sim_source", e)
            } catch (e: Exception) {
                Diag.fail("sim_source", e)
            }
        }
        return "SIM"
    }

    /**
     * Asks every syncing account (Google, etc.) to refresh its contacts now, ignoring the auto-sync setting.
     * Returns how many accounts were asked. The provider then notifies us, so the list reloads by itself as data arrives.
     */
    fun requestSync(ctx: Context): Int {
        var n = 0
        try {
            val cols = arrayOf(ContactsContract.RawContacts.ACCOUNT_TYPE, ContactsContract.RawContacts.ACCOUNT_NAME)
            val seen = HashSet<Pair<String, String>>()
            ctx.contentResolver.query(ContactsContract.RawContacts.CONTENT_URI, cols, "${ContactsContract.RawContacts.DELETED} = 0", null, null)?.use { c ->
                while (c.moveToNext()) {
                    val type = c.getString(0)?.takeIf { it.isNotBlank() } ?: continue
                    val name = c.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                    if (type.contains("sim", ignoreCase = true)) continue // on-device, nothing to sync
                    seen.add(type to name)
                }
            }
            val extras = Bundle().apply {
                putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
            }
            for ((type, name) in seen) try {
                ContentResolver.requestSync(android.accounts.Account(name, type), ContactsContract.AUTHORITY, extras)
                n++
            } catch (e: Exception) { Diag.fail("request_sync", e) }
        } catch (e: Exception) { Diag.fail("request_sync_accounts", e) }
        return n
    }

    /** Contact id to the places it is stored. A joined contact can live in several accounts. Failure only means no source filters. */
    private fun savedIn(ctx: Context): Map<Long, Set<String>> {
        val out = HashMap<Long, MutableSet<String>>()
        try {
            val cols = arrayOf(ContactsContract.RawContacts.CONTACT_ID, ContactsContract.RawContacts.ACCOUNT_TYPE, ContactsContract.RawContacts.ACCOUNT_NAME)
            val kinds = HashSet<String>()
            ctx.contentResolver.query(ContactsContract.RawContacts.CONTENT_URI, cols, "${ContactsContract.RawContacts.DELETED} = 0", null, null)?.use { c ->
                while (c.moveToNext()) {
                    val label = sourceLabel(ctx, c.getString(1), c.getString(2))
                    out.getOrPut(c.getLong(0)) { HashSet() }.add(label)
                    // Account types only (never names): shows how this phone names its SIM / WhatsApp accounts.
                    val type = c.getString(1)
                    if (type != "com.google") kinds.add("${type?.takeIf { it.isNotBlank() } ?: "local"}->$label")
                }
            }
            Diag.log("contact_sources", 0, kinds.sorted().joinToString(" | ").take(300))
        } catch (e: Exception) { Diag.fail("contact_sources", e) }
        return out
    }

    /** Blocking; call from IO. Needs WRITE_CONTACTS. Returns false if nothing was updated. */
    fun setStarred(ctx: Context, contactId: Long, starred: Boolean): Boolean = try {
        val v = android.content.ContentValues().apply { put(ContactsContract.Contacts.STARRED, if (starred) 1 else 0) }
        ctx.contentResolver.update(Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_URI, contactId.toString()), v, null, null) > 0
    } catch (e: Exception) { Diag.fail("set_starred", e); false }

    /** Fast single-number lookup for incoming calls. Blocking; call from IO. Returns null if unavailable. */
    fun lookup(ctx: Context, number: String): Lookup {
        if (!ctx.granted(Manifest.permission.READ_CONTACTS)) return Lookup.Unavailable
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            ctx.contentResolver.query(
                uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME, ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI),
                null, null, null
            )?.use { c -> if (c.moveToFirst()) Lookup.Match(Found(c.getString(0), c.getString(1))) else Lookup.NoMatch }
                ?: Lookup.Unavailable
        } catch (e: Exception) { Diag.fail("contact_lookup", e); Lookup.Unavailable }
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
        SimLabels.refresh(ctx)
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
