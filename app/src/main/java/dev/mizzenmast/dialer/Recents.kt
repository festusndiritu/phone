package dev.mizzenmast.dialer

import android.content.Context
import android.provider.CallLog
import androidx.compose.runtime.Immutable

/**
 * What the Recents list actually draws. Built off the main thread (see [DialerViewModel.recents]) so that scrolling and
 * recomposition never run number formatting, date formatting or contact lookups: those are slow (PhoneNumberUtils loads
 * libphonenumber metadata on first use, DateUtils builds a formatter per call).
 */
@Immutable
sealed interface RItem {
    @Immutable data class Header(val label: String, val key: String) : RItem

    /** Consecutive calls to one number on one day. [title] and [time] describe the newest one. */
    @Immutable data class Group(val entries: List<LogEntry>, val contact: Contact?, val title: String, val time: String) : RItem {
        val first: LogEntry get() = entries.first()
    }
}

@Immutable
data class RecentsModel(val items: List<RItem>, val anySim: Boolean, val logEmpty: Boolean)

/** Maps the payload of a [Load.Ready]; every other state passes through untouched. */
fun <T, R> Load<T>.mapReady(f: (T) -> R): Load<R> = when (this) {
    is Load.Ready -> Load.Ready(f(data))
    Load.Loading -> Load.Loading
    Load.Denied -> Load.Denied
    is Load.Failed -> this
}

/** Day headers, with consecutive calls to the same number on the same day folded into one group. Pure: safe on any thread. */
fun buildRecents(ctx: Context, list: List<LogEntry>, missedOnly: Boolean, index: ContactIndex): RecentsModel {
    val src = if (missedOnly) list.filter { it.type == CallLog.Calls.MISSED_TYPE } else list
    val out = ArrayList<RItem>(src.size + 8)
    var curDay = Long.MIN_VALUE
    var curKey = ""
    var cur = ArrayList<LogEntry>()
    fun flush() {
        if (cur.isEmpty()) return
        val f = cur.first()
        val contact = index.find(f.number)
        out.add(RItem.Group(cur, contact, f.title(contact), timeText(ctx, f.date)))
        cur = ArrayList()
    }
    for (e in src) {
        val d = dayKey(e.date)
        val k = ContactIndex.key(e.number)
        if (!(cur.isNotEmpty() && d == curDay && k.isNotEmpty() && k == curKey)) {
            flush()
            if (d != curDay) { out.add(RItem.Header(dayLabel(ctx, e.date), "h$d")); curDay = d }
            curKey = k
        }
        cur.add(e)
    }
    flush()
    return RecentsModel(out, anySim = list.any { it.simSlot != null }, logEmpty = list.isEmpty())
}
