package dev.mizzenmast.dialer

import android.content.Context
import android.provider.CallLog
import android.text.format.DateUtils
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallMade
import androidx.compose.material.icons.automirrored.rounded.CallMissed
import androidx.compose.material.icons.automirrored.rounded.CallReceived
import androidx.compose.material.icons.rounded.Call
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

fun fmtDuration(sec: Long): String {
    if (sec <= 0) return "0s"
    val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
    return when { h > 0 -> "${h}h ${m}m ${s}s"; m > 0 -> "${m}m ${"%02d".format(s)}s"; else -> "${s}s" }
}

internal fun whenText(ctx: Context, t: Long): String =
    if (DateUtils.isToday(t)) "Today, " + timeText(ctx, t)
    else DateUtils.formatDateTime(ctx, t, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_ALL or DateUtils.FORMAT_SHOW_TIME)

internal fun typeLabel(t: Int) = when (t) {
    CallLog.Calls.INCOMING_TYPE -> "Incoming"
    CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
    CallLog.Calls.MISSED_TYPE -> "Missed"
    CallLog.Calls.VOICEMAIL_TYPE -> "Voicemail"
    CallLog.Calls.REJECTED_TYPE -> "Declined"
    CallLog.Calls.BLOCKED_TYPE -> "Blocked"
    CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> "Answered elsewhere"
    else -> "Call"
}

/** Full wording for screen readers, since the list shows the shortened summary. */
internal fun rowDescription(ctx: Context, e: LogEntry): String =
    listOfNotNull(typeLabel(e.type), whenText(ctx, e.date), e.durationSec.takeIf { it > 0 }?.let(::fmtDuration), e.simSlot?.let { "SIM $it" }).joinToString(", ")

/** Every call type gets an arrow, so the first glyph in every row has the same shape and weight. */
internal fun typeIcon(t: Int): ImageVector = when (t) {
    CallLog.Calls.OUTGOING_TYPE -> Icons.AutoMirrored.Rounded.CallMade
    CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE, CallLog.Calls.BLOCKED_TYPE -> Icons.AutoMirrored.Rounded.CallMissed
    else -> Icons.AutoMirrored.Rounded.CallReceived // incoming, answered elsewhere, voicemail
}

/** Green in, blue out, red missed/declined/blocked. Anything else stays neutral. */
internal fun typeColor(c: DialerColors, t: Int): Color = when (t) {
    CallLog.Calls.INCOMING_TYPE, CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> c.answer
    CallLog.Calls.OUTGOING_TYPE -> c.outgoing
    CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE, CallLog.Calls.BLOCKED_TYPE -> c.missed
    else -> c.inkDim
}

/** Second line of a recents row: what kind of call it was and how long it lasted (or why there was no talk time). */
internal fun metaText(e: LogEntry): String = when {
    e.type == CallLog.Calls.MISSED_TYPE -> "Missed"
    e.durationSec > 0 -> typeLabel(e.type) + " · " + fmtDuration(e.durationSec)
    e.type == CallLog.Calls.OUTGOING_TYPE -> "Outgoing · No answer"
    else -> typeLabel(e.type)
}

/** The calls of one day share a card: only its first row rounds the top, only its last rounds the bottom. */
internal fun rowShape(first: Boolean, last: Boolean): Shape {
    val top = if (first) 22.dp else 0.dp
    val bottom = if (last) 22.dp else 0.dp
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

fun dayKey(t: Long) = (t + java.util.TimeZone.getDefault().getOffset(t)) / 86_400_000L

fun dayLabel(ctx: Context, t: Long): String {
    val ago = dayKey(System.currentTimeMillis()) - dayKey(t)
    return when {
        ago <= 0L -> "Today"
        ago == 1L -> "Yesterday"
        ago in 2L..6L -> DateUtils.formatDateTime(ctx, t, DateUtils.FORMAT_SHOW_WEEKDAY)
        else -> DateUtils.formatDateTime(ctx, t, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)
    }
}

fun timeText(ctx: Context, t: Long): String = DateUtils.formatDateTime(ctx, t, DateUtils.FORMAT_SHOW_TIME)
