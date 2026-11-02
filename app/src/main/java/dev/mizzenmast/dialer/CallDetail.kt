package dev.mizzenmast.dialer

import android.provider.CallLog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CallDetailScreen(vm: DialerViewModel, actions: Actions, e: LogEntry, contact: Contact?) {
    val c = LocalColors.current
    val all by vm.log.collectAsState()
    val starredIds by vm.starredIds.collectAsState()
    val related = remember(all, e, contact) { relatedEntries(all, e, contact) }
    // Everything for this person was deleted (from the history screen): nothing left to show.
    LaunchedEffect(all, related) { if (all is Load.Ready && related.isEmpty()) vm.closeDetail() }
    val number = e.number
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var blocked by remember(number) { mutableStateOf(false) }
    var numberMenu by remember { mutableStateOf(false) }
    if (numberMenu) NumberMenu(number, contact?.name ?: e.name, contact?.photo, onEdit = { actions.editBeforeCall(number) },
        onAddContact = if (contact == null) { { actions.addContact(number) } } else null, onDismiss = { numberMenu = false })
    val numberHold = if (number.isNotBlank()) Modifier.clip(RoundedCornerShape(12.dp))
        .combinedClickable(onLongClickLabel = "Number options", onLongClick = { numberMenu = true }, onClick = {}) else Modifier
    LaunchedEffect(number) { if (number.isNotBlank()) blocked = withContext(Dispatchers.IO) { BlockedRepo.isBlocked(ctx, number) } }
    val starred = contact != null && contact.id in starredIds
    val label = contact?.phones?.firstOrNull { ContactIndex.key(it.number) == ContactIndex.key(number) }?.label
    Overlay(onBack = { vm.closeDetail() }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(8.dp))
            Avatar(contact?.name ?: e.name, contact?.photo, 96.dp)
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.title(contact), color = c.ink, fontSize = 26.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, modifier = Modifier.weight(1f, fill = false).then(numberHold))
                if (starred) { Spacer(Modifier.width(8.dp)); Icon(Icons.Rounded.Star, "Favorite", tint = c.accent, modifier = Modifier.size(22.dp)) }
            }
            if ((contact != null || e.name != null) && number.isNotBlank()) Text(pretty(number) ?: number, color = c.inkDim, fontSize = 16.sp, modifier = numberHold)
            if (label != null) Text(label, color = c.inkDim, fontSize = 14.sp)
            if (number.isNotBlank()) {
                Spacer(Modifier.height(20.dp))
                QuickActions(number, actions)
            }
            if (number.isNotBlank()) Section {
                SimPrefRow(listOf(number), pretty(number) ?: number)
                CardRow(if (blocked) "Unblock number" else "Block number", Icons.Rounded.Block) { scope.launch { blocked = toggleBlocked(ctx, number, blocked) } }
                if (contact == null) CardRow("Add contact", Icons.Rounded.PersonAdd) { actions.addContact(number) }
            }
            contact?.takeIf { it.phones.size > 1 }?.let { ct ->
                Section("Numbers") { ct.phones.forEach { p -> PhoneRow(p, ct, ContactIndex.key(p.number) == ContactIndex.key(number), actions) } }
            }
            RecentActivity(related) { vm.openHistory(e) }
            Spacer(Modifier.height(32.dp))
        }
    }
}

private sealed interface HItem {
    data class Day(val label: String, val key: Long) : HItem
    data class Entry(val e: LogEntry) : HItem
}

private fun matchesFilter(f: Int, t: Int): Boolean = when (f) {
    1 -> t == CallLog.Calls.OUTGOING_TYPE
    2 -> t == CallLog.Calls.INCOMING_TYPE || t == CallLog.Calls.ANSWERED_EXTERNALLY_TYPE
    3 -> t == CallLog.Calls.MISSED_TYPE
    else -> true
}

@Composable
internal fun SelectBadge() {
    val c = LocalColors.current
    // Ring matches the selected row's tint (accent at 12% over the screen), so the badge looks cut out of the avatar.
    val ring = androidx.compose.ui.graphics.lerp(c.surface, c.accent, 0.12f)
    Box(Modifier.size(22.dp).background(ring, CircleShape).padding(2.dp).background(c.accent, CircleShape), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Check, null, tint = c.onAccent, modifier = Modifier.size(14.dp))
    }
}

/** Every call with this person, grouped by day. Filters on top; long-press or "Select" to delete several. */
@Composable
fun CallHistoryScreen(vm: DialerViewModel, actions: Actions, e: LogEntry, contact: Contact?) {
    val c = LocalColors.current
    val ctx = LocalContext.current
    val all by vm.log.collectAsState()
    val related = remember(all, e, contact) { relatedEntries(all, e, contact) }
    LaunchedEffect(all, related) { if (all is Load.Ready && related.isEmpty()) vm.closeHistory() }

    var filter by rememberSaveable { mutableIntStateOf(0) }
    var selecting by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<Long>() }
    var confirm by remember { mutableStateOf(false) }

    val shown = remember(related, filter) { related.filter { matchesFilter(filter, it.type) } }
    val rows = remember(shown) {
        val out = ArrayList<HItem>(); var cur = Long.MIN_VALUE
        for (r in shown) {
            val d = dayKey(r.date)
            if (d != cur) { out += HItem.Day(dayLabel(ctx, r.date), d); cur = d }
            out += HItem.Entry(r)
        }
        out
    }
    fun exitSelection() { selecting = false; selected.clear() }
    fun toggle(id: Long) { if (id in selected) selected.remove(id) else selected.add(id); if (selected.isEmpty()) exitSelection() }
    DisposableEffect(selecting) { vm.setSelectionActive(selecting); onDispose { vm.setSelectionActive(false) } }
    val talkSec = remember(related) { related.sumOf { it.durationSec } }
    BackHandler { if (selecting) exitSelection() else vm.closeHistory() }

    Column(Modifier.fillMaxSize().background(c.bg).pointerInput(Unit) { detectTapGestures { } }) {
        if (selecting) ContextBar(selected.size, onClose = { exitSelection() }) {
            if (selected.size == 1 && shown.size > 1) IconAction(Icons.Rounded.Checklist, "Select all", { selected.clear(); selected.addAll(shown.map { it.id }) })
            IconAction(Icons.Rounded.Delete, "Delete", { confirm = true })
        } else Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconAction(Icons.AutoMirrored.Rounded.ArrowBack, "Back", { vm.closeHistory() })
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text("History", color = c.ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(e.title(contact), color = c.inkDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        // Total over everything with this person, whatever the filter below shows.
        if (talkSec > 0) Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp).clip(RoundedCornerShape(22.dp)).background(c.surface).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(c.accent.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Call, null, tint = c.accent, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text("Total talk time", color = c.inkDim, fontSize = 13.sp)
                Text(fmtDuration(talkSec), color = c.ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("All", "Outgoing", "Incoming", "Missed").forEachIndexed { i, l -> FilterChip(l, filter == i, { filter = i; exitSelection() }) }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (shown.isEmpty()) StateMessage(Icons.Rounded.History, "No calls")
            else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                itemsIndexed(rows, key = { _, x -> if (x is HItem.Day) "d${x.key}" else "e${(x as HItem.Entry).e.id}" }) { i, item ->
                    when (item) {
                        is HItem.Day -> Text(item.label, color = c.inkDim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 6.dp))
                        is HItem.Entry -> {
                            val r = item.e
                            val on = r.id in selected
                            val shape = rowShape(rows.getOrNull(i - 1) !is HItem.Entry, rows.getOrNull(i + 1) !is HItem.Entry)
                            HistoryEntryRow(r, showDate = false, selected = on,
                                modifier = Modifier.fillMaxWidth().clip(shape).background(c.surface)
                                    .then(
                                        if (selecting) Modifier.selectable(on, role = Role.Checkbox) { toggle(r.id) }
                                        else Modifier.combinedClickable(onLongClickLabel = "Select", onLongClick = { selecting = true; selected.add(r.id) }, onClick = {})
                                    )
                                    .background(if (on) c.accent.copy(alpha = 0.12f) else Color.Transparent)
                                    .padding(horizontal = 16.dp, vertical = 10.dp))
                        }
                    }
                }
            }
        }
    }
    if (confirm) ConfirmDialog(if (selected.size == 1) "Delete this call?" else "Delete ${selected.size} calls?", null, "Delete",
        onConfirm = {
            confirm = false
            actions.deleteLogs(selected.toList())
            exitSelection()
        }, onDismiss = { confirm = false })
}
