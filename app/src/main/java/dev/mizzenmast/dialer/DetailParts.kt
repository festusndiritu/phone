package dev.mizzenmast.dialer

import android.provider.CallLog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun Overlay(onBack: () -> Unit, content: @Composable () -> Unit) {
    val c = LocalColors.current
    BackHandler(onBack = onBack)
    // The empty pointerInput makes the overlay swallow touches, so taps never fall through to the screen underneath.
    Column(Modifier.fillMaxSize().background(c.bg).pointerInput(Unit) { detectTapGestures { } }) {
        Box(Modifier.padding(start = 8.dp, top = 8.dp).size(48.dp).clip(CircleShape).clickable(onClickLabel = "Back", onClick = onBack), contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = c.ink)
        }
        content()
    }
}

/** One call: direction in a round badge (a check while selected), what it was, when and for how long, and the SIM it used. */
@Composable
internal fun HistoryEntryRow(e: LogEntry, showDate: Boolean, modifier: Modifier = Modifier, selected: Boolean = false) {
    val c = LocalColors.current
    val ctx = LocalContext.current
    val missed = e.type == CallLog.Calls.MISSED_TYPE
    Row(modifier.semantics(mergeDescendants = true) { contentDescription = rowDescription(ctx, e) }, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(if (selected) c.accent else c.surfaceHigh), contentAlignment = Alignment.Center) {
            if (selected) Icon(Icons.Rounded.Check, "Selected", tint = c.onAccent, modifier = Modifier.size(20.dp))
            else Icon(typeIcon(e.type), null, tint = typeColor(c, e.type), modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(typeLabel(e.type), color = if (missed) c.missed else c.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(listOfNotNull(if (showDate) whenText(ctx, e.date) else timeText(ctx, e.date), e.durationSec.takeIf { it > 0 }?.let(::fmtDuration)).joinToString(" · "),
                color = c.inkDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        e.simSlot?.takeIf { it in 1..2 }?.let { Spacer(Modifier.width(8.dp)); SimTag(it) }
    }
}

@Composable
private fun ActionButton(
    icon: Painter,
    label: String,
    filled: Boolean = false,
    iconTint: Color? = null,
    onClick: () -> Unit
) {
    val c = LocalColors.current

    Column(
        Modifier
            .widthIn(min = 72.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(if (filled) c.accent else c.surfaceHigh),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = icon,
                contentDescription = null,
                tint = iconTint ?: if (filled) c.onAccent else c.ink,
                modifier = Modifier.size(24.dp)
            )
        }

        Spacer(Modifier.height(6.dp))
        Text(label, color = c.ink, fontSize = 13.sp)
    }
}

/** One card of a detail screen: rows on a rounded surface, under an optional small heading. */
@Composable
internal fun Section(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalColors.current
    if (title != null) Text(title, color = c.inkDim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 20.dp, bottom = 6.dp))
    else Spacer(Modifier.height(12.dp))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.surface), content = content)
}

/** One tappable line of a [Section]: optional icon, the title, an optional value on the right, an optional chevron. */
@Composable
internal fun CardRow(title: String, icon: ImageVector? = null, value: String? = null, chevron: Boolean = false, onClick: () -> Unit) {
    val c = LocalColors.current
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) { Icon(icon, null, tint = c.accent, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(16.dp)) }
        Text(title, color = c.ink, fontSize = 16.sp, modifier = Modifier.weight(1f))
        if (value != null) Text(value, color = c.inkDim, fontSize = 14.sp)
        if (chevron) Icon(Icons.Rounded.ChevronRight, null, tint = c.inkDim)
    }
}

/** Call, message, WhatsApp and share for one number: the round buttons under a name. */
@Composable
internal fun QuickActions(number: String, actions: Actions) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        ActionButton(rememberVectorPainter(Icons.Rounded.Call), "Call", filled = true) { actions.call(number) }
        ActionButton(rememberVectorPainter(Icons.AutoMirrored.Filled.Message), "Message") { actions.message(number) }
        ActionButton(painterResource(R.drawable.whatsapp), "WhatsApp", iconTint = Color.Unspecified) { actions.whatsapp(number) }
        ActionButton(rememberVectorPainter(Icons.Rounded.Share), "Share") { actions.share(number) }
    }
}

/** A saved number: tap to call, long-press for the number menu, a message shortcut at the end. [highlight] marks the number this call was with. */
@Composable
internal fun PhoneRow(p: ContactPhone, ct: Contact, highlight: Boolean, actions: Actions) {
    val c = LocalColors.current
    var menu by remember { mutableStateOf(false) }
    if (menu) NumberMenu(p.number, ct.name, ct.photo, onEdit = { actions.editBeforeCall(p.number) }, onAddContact = null, onDismiss = { menu = false })
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(role = Role.Button, onClickLabel = "Call", onLongClickLabel = "Number options", onLongClick = { menu = true }) { actions.call(p.number) }
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.Call, null, tint = c.accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(pretty(p.number) ?: p.number, color = if (highlight) c.accent else c.ink, fontSize = 17.sp)
            Text(p.label, color = c.inkDim, fontSize = 13.sp)
        }
        IconAction(Icons.Rounded.Sms, "Message ${p.label}", { actions.message(p.number) })
    }
}

/** The last few calls as tappable rows (each opens the full history), then "View all". */
@Composable
internal fun RecentActivity(related: List<LogEntry>, openHistory: () -> Unit) {
    val c = LocalColors.current
    Section("Recent activity") {
        related.take(3).forEach { r ->
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Open history", onClick = openHistory).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HistoryEntryRow(r, showDate = true, modifier = Modifier.weight(1f))
                Icon(Icons.Rounded.ChevronRight, null, tint = c.inkDim)
            }
        }
        CardRow("View all", value = if (related.size > 3) "${related.size}" else null, chevron = true, onClick = openHistory)
    }
}

/** Everything the call log has for the numbers saved on [ct], newest first. */
internal fun contactEntries(all: Load<List<LogEntry>>, ct: Contact): List<LogEntry> {
    val data = (all as? Load.Ready)?.data ?: return emptyList()
    val keys = ct.phones.map { ContactIndex.key(it.number) }.filter { it.isNotEmpty() }.toSet()
    return data.filter { ContactIndex.key(it.number) in keys }
}
