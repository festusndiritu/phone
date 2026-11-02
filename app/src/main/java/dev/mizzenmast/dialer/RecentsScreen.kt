package dev.mizzenmast.dialer

import android.provider.CallLog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallMissed
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Up to five starred contacts. Tap to call their first number, long-press to open the contact. Can be hidden in Settings > Appearance & interaction. */
@Composable
private fun FavoritesRow(favs: List<Contact>, onCall: (Contact) -> Unit, onOpen: (Contact) -> Unit) {
    val c = LocalColors.current
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(
        horizontal = 20.dp,
        vertical = 4.dp
    ), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        favs.forEach { f ->
            Column(
                Modifier.width(60.dp).clip(RoundedCornerShape(12.dp))
                    .combinedClickable(
                        role = Role.Button,
                        onClickLabel = "Call ${f.name}",
                        onLongClickLabel = "Open contact",
                        onLongClick = { onOpen(f) },
                        onClick = { onCall(f) }
                    )
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Avatar(f.name, f.photo, 52.dp)
                Spacer(Modifier.height(4.dp))
                Text(
                    f.name.substringBefore(' '),
                    color = c.inkDim,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun RecentsScreen(vm: DialerViewModel, actions: Actions) {
    val c = LocalColors.current
    val ctx = LocalContext.current
    val recents by vm.recents.collectAsState()
    val filter by vm.logFilter.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val showFavorites by Prefs.showFavorites.collectAsState()

    // Selection is by group (the id of its newest call); deleting a group deletes every call folded into it.
    var selecting by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<Long>() }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf<Long?>(null) }
    fun exitSelection() { selecting = false; selected.clear() }
    // Un-selecting the last row ends selection, like every system list does.
    fun toggle(id: Long) { if (id in selected) selected.remove(id) else selected.add(id); if (selected.isEmpty()) exitSelection() }
    BackHandler(enabled = query.isNotEmpty() && !selecting) { query = "" }
    BackHandler(enabled = selecting) { exitSelection() }

    val model = (recents as? Load.Ready)?.data
    val shown = remember(model, query) { model?.items?.matching(query) ?: emptyList() }
    val groups = remember(shown) { shown.filterIsInstance<RItem.Group>() }
    fun selectAll() { selected.clear(); selected.addAll(groups.map { it.first.id }) }
    DisposableEffect(selecting) { vm.setSelectionActive(selecting); onDispose { vm.setSelectionActive(false) } }

    Column(Modifier.fillMaxSize()) {
        when {
            selecting -> {
                val one = groups.firstOrNull { it.first.id == selected.singleOrNull() }
                ContextBar(selected.size, onClose = { exitSelection() }) {
                    // One row: everything you can do with it. Several rows: just delete.
                    if (one != null) {
                        if (one.first.number.isNotBlank()) IconAction(Icons.Rounded.ContentCopy, "Copy number", { copyNumber(ctx, one.first.number); exitSelection() })
                        if (groups.size > 1) IconAction(Icons.Rounded.Checklist, "Select all", { selectAll() })
                    }
                    IconAction(Icons.Rounded.Delete, "Delete", { confirmDelete = true })
                }
            }
            else -> ScreenTitle("Recents", trailing = {
                if (model?.logEmpty == false) Box {
                    IconAction(Icons.Rounded.MoreVert, "More options", { menu = true })
                    PopMenu(menu, { menu = false }) {
                        PopItem("Select all", { menu = false; selecting = true; selectAll() }, Icons.Rounded.Checklist, enabled = groups.isNotEmpty())
                        PopItem("Clear call log", { menu = false; confirmClear = true }, Icons.Rounded.DeleteSweep, danger = true)
                    }
                }
            })
        }
        // Search is always there, like in Contacts; the call-type filter lives inside it.
        if (!selecting && (recents is Load.Loading || model?.logEmpty == false)) SearchField(
            value = query, onChange = { query = it }, placeholder = "Search recents",
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp),
            trailing = {
                FilterButton(filter != 0, "Filter calls") { dismiss ->
                    listOf("All calls", "Missed", "Incoming", "Outgoing").forEachIndexed { i, label ->
                        PopItem(label, { dismiss(); vm.setLogFilter(i) }, selected = filter == i)
                    }
                }
            }
        )
        if (showFavorites && favorites.isNotEmpty() && !selecting && query.isBlank()) FavoritesRow(
            favorites,
            onCall = { actions.call(it.phones.first().number) },
            onOpen = { vm.openContactDetail(it) }
        )
        when (val s = recents) {
            Load.Loading -> ListSkeleton()
            Load.Denied -> StateMessage(Icons.Rounded.History, "See your recent calls",
                null, "Allow access", actions.requestCallLog)
            is Load.Failed -> StateMessage(Icons.Rounded.History, "Couldn't load calls", s.msg, "Try again", actions.requestCallLog)
            is Load.Ready -> if (s.data.logEmpty) {
                StateMessage(Icons.Rounded.History, "No calls yet")
            } else {
                if (shown.isEmpty()) {
                    if (query.isNotBlank()) StateMessage(Icons.Rounded.Search, "No matches")
                    else StateMessage(
                        Icons.AutoMirrored.Rounded.CallMissed,
                        when (filter) {
                            1 -> "No missed calls"; 2 -> "No incoming calls"; 3 -> "No outgoing calls"; else -> "No calls"
                        }
                    )
                } else LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp)) {
                    itemsIndexed(
                        shown,
                        key = { _, x -> if (x is RItem.Header) x.key else "g${(x as RItem.Group).first.id}" },
                        contentType = { _, x -> if (x is RItem.Header) 0 else 1 }
                    ) { i, item ->
                        when (item) {
                            is RItem.Header -> Text(item.label, color = c.inkDim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 6.dp))
                            is RItem.Group -> LogRow(item,
                                selecting = selecting,
                                selected = item.first.id in selected,
                                shape = rowShape(shown.getOrNull(i - 1) !is RItem.Group, shown.getOrNull(i + 1) !is RItem.Group),
                                onOpen = { vm.openDetail(item.first) },
                                expanded = expanded == item.first.id && !selecting,
                                onToggle = { expanded = if (expanded == item.first.id) null else item.first.id },
                                onAddContact = { actions.addContact(item.first.number) },
                                onCall = { actions.call(item.first.number) },
                                onSelect = { selecting = true; toggle(item.first.id) })
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        val ids = groups.filter { it.first.id in selected }.flatMap { g -> g.entries.map { it.id } }
        ConfirmDialog(if (ids.size == 1) "Delete this call?" else "Delete ${ids.size} calls?", null, "Delete",
            onConfirm = {
                confirmDelete = false
                actions.deleteLogs(ids)
                exitSelection()
            }, onDismiss = { confirmDelete = false })
    }
    if (confirmClear) ConfirmDialog("Clear call log?", "This deletes every call in your history, not just the ones shown. It can't be undone.", "Clear",
        onConfirm = {
            confirmClear = false
            actions.clearLog()
            exitSelection()
            query = ""
        }, onDismiss = { confirmClear = false })
}

/**
 * Tap the row to call back (the SIM choice happens in the call flow). The trailing time / SIM / arrow opens the details;
 * for a number that isn't saved it expands Add contact / Block / Details in place instead. Long-press anywhere to select.
 */
@Composable
private fun LogRow(
    g: RItem.Group, selecting: Boolean, selected: Boolean, shape: Shape,
    onOpen: () -> Unit, expanded: Boolean, onToggle: () -> Unit, onAddContact: () -> Unit,
    onCall: () -> Unit, onSelect: () -> Unit
) {
    val c = LocalColors.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val e = g.first
    val contact = g.contact
    val count = g.entries.size
    val missed = e.type == CallLog.Calls.MISSED_TYPE
    val title = g.title
    val unknown = contact == null && e.number.isNotBlank() && e.presentation == CallLog.Calls.PRESENTATION_ALLOWED
    var blocked by remember(e.number) { mutableStateOf(false) }
    LaunchedEffect(expanded) { if (expanded && unknown) blocked = withContext(Dispatchers.IO) { BlockedRepo.isBlocked(ctx, e.number) } }
    Column(Modifier.fillMaxWidth().clip(shape).background(c.surface).background(if (selected) c.accent.copy(alpha = 0.12f) else Color.Transparent)) {
        Row(
            Modifier.fillMaxWidth().then(if (selecting) Modifier.selectable(selected, role = Role.Checkbox, onClick = onSelect) else Modifier),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                Modifier.weight(1f).heightIn(min = 68.dp)
                    .then(if (selecting) Modifier else Modifier.combinedClickable(
                        role = Role.Button, onClickLabel = "Call $title", onLongClickLabel = "Select", onLongClick = onSelect, onClick = onCall))
                    .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // The check sits on the avatar's corner, so selecting never moves anything in the row.
                Box {
                    Avatar(contact?.name ?: e.name, contact?.photo, 46.dp)
                    androidx.compose.animation.AnimatedVisibility(
                        visible = selected,
                        modifier = Modifier.align(Alignment.BottomEnd).offset(x = 4.dp, y = 4.dp),
                        enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(initialScale = 0.6f),
                        exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.scaleOut(targetScale = 0.6f)
                    ) { SelectBadge() }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = rowDescription(ctx, e) }) {
                    Text(title + if (count > 1) "  ($count)" else "", color = if (missed) c.missed else c.ink, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(typeIcon(e.type), null, tint = typeColor(c, e.type), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(metaText(e), color = if (missed) c.missed else c.inkDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (!selecting && e.number.isNotBlank()) Column(
                Modifier.heightIn(min = 68.dp)
                    .combinedClickable(
                        role = Role.Button, onClickLabel = if (unknown) (if (expanded) "Collapse" else "Expand") else "Call details",
                        onLongClickLabel = "Select", onLongClick = onSelect, onClick = if (unknown) onToggle else onOpen)
                    .padding(start = 8.dp, end = 12.dp),
                horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.Center
            ) {
                Text(g.time, color = c.inkDim, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    e.simSlot?.takeIf { it in 1..2 }?.let { SimTag(it); Spacer(Modifier.width(2.dp)) }
                    Icon(Icons.Rounded.ChevronRight, null, tint = c.inkDim, modifier = Modifier.size(18.dp))
                }
            } else Spacer(Modifier.width(16.dp))
        }
        if (unknown) androidx.compose.animation.AnimatedVisibility(visible = expanded) {
            Row(Modifier.fillMaxWidth().padding(start = 76.dp, end = 16.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextPill("Add contact", onAddContact, filled = true)
                TextPill(if (blocked) "Unblock" else "Block", { scope.launch { blocked = toggleBlocked(ctx, e.number, blocked) } }, danger = !blocked)
                TextPill("Details", onOpen)
            }
        }
    }
}

/** Entries for the same person: every number saved on the contact, or just this number when it isn't saved. */
internal fun relatedEntries(all: Load<List<LogEntry>>, e: LogEntry, contact: Contact?): List<LogEntry> {
    val data = (all as? Load.Ready)?.data ?: return listOf(e)
    val keys = HashSet<String>()
    ContactIndex.key(e.number).takeIf { it.isNotEmpty() }?.let(keys::add)
    contact?.phones?.forEach { p -> ContactIndex.key(p.number).takeIf { it.isNotEmpty() }?.let(keys::add) }
    return if (keys.isEmpty()) data.filter { it.id == e.id } else data.filter { ContactIndex.key(it.number) in keys }
}
