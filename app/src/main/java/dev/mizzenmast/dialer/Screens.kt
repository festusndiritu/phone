package dev.mizzenmast.dialer

import android.app.role.RoleManager
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.provider.CallLog
import android.telecom.PhoneAccountHandle
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.automirrored.rounded.CallMade
import androidx.compose.material.icons.automirrored.rounded.CallMissed
import androidx.compose.material.icons.automirrored.rounded.CallReceived
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SettingsTarget { AppDetails, Notifications, FullScreen, Overlay, Battery, Sound, CallingAccounts, Blocked }

class Actions(
    val requestContacts: () -> Unit,
    val requestCallLog: () -> Unit,
    val requestNotifications: () -> Unit,
    val requestPhone: () -> Unit,
    /** Contacts + call log + phone in one request (the system shows one dialog per permission group). */
    val requestPeople: () -> Unit,
    val requestRole: () -> Unit,
    val open: (SettingsTarget) -> Unit,
    val call: (String) -> Unit,
    val callWith: (String, PhoneAccountHandle) -> Unit,
    val openContact: (Contact) -> Unit,
    val message: (String) -> Unit,
    val whatsapp: (String) -> Unit,
    val share: (String) -> Unit,
    val addContact: (String) -> Unit,
    /** Contact + the new starred state. */
    val toggleStar: (Contact, Boolean) -> Unit,
    /** Puts the number in the dialer sheet without calling. */
    val editBeforeCall: (String) -> Unit
)

fun fmtDuration(sec: Long): String {
    if (sec <= 0) return "0s"
    val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
    return when { h > 0 -> "${h}h ${m}m ${s}s"; m > 0 -> "${m}m ${"%02d".format(s)}s"; else -> "${s}s" }
}

private fun whenText(ctx: Context, t: Long): String =
    if (DateUtils.isToday(t)) "Today, " + timeText(ctx, t)
    else DateUtils.formatDateTime(ctx, t, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_ALL or DateUtils.FORMAT_SHOW_TIME)

private fun typeLabel(t: Int) = when (t) {
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
private fun rowDescription(ctx: Context, e: LogEntry): String =
    listOfNotNull(typeLabel(e.type), whenText(ctx, e.date), e.durationSec.takeIf { it > 0 }?.let(::fmtDuration), e.simSlot?.let { "SIM $it" }).joinToString(", ")

/** Every call type gets an arrow, so the first glyph in every row has the same shape and weight. */
private fun typeIcon(t: Int): ImageVector = when (t) {
    CallLog.Calls.OUTGOING_TYPE -> Icons.AutoMirrored.Rounded.CallMade
    CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE, CallLog.Calls.BLOCKED_TYPE -> Icons.AutoMirrored.Rounded.CallMissed
    else -> Icons.AutoMirrored.Rounded.CallReceived // incoming, answered elsewhere, voicemail
}

/** Green in, blue out, red missed/declined/blocked. Anything else stays neutral. */
private fun typeColor(c: DialerColors, t: Int): Color = when (t) {
    CallLog.Calls.INCOMING_TYPE, CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> c.answer
    CallLog.Calls.OUTGOING_TYPE -> c.outgoing
    CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE, CallLog.Calls.BLOCKED_TYPE -> c.missed
    else -> c.inkDim
}

/** Second line of a recents row: what kind of call it was and how long it lasted (or why there was no talk time). */
private fun metaText(e: LogEntry): String = when {
    e.type == CallLog.Calls.MISSED_TYPE -> "Missed"
    e.durationSec > 0 -> typeLabel(e.type) + " · " + fmtDuration(e.durationSec)
    e.type == CallLog.Calls.OUTGOING_TYPE -> "Outgoing · No answer"
    else -> typeLabel(e.type)
}

/** The calls of one day share a card: only its first row rounds the top, only its last rounds the bottom. */
private fun rowShape(first: Boolean, last: Boolean): Shape {
    val top = if (first) 22.dp else 0.dp
    val bottom = if (last) 22.dp else 0.dp
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

fun roleHeld(ctx: Context): Boolean =
    ctx.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_DIALER) == true

@Composable
private fun Overlay(onBack: () -> Unit, content: @Composable () -> Unit) {
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
    DisposableEffect(selecting) { vm.selectionActive.value = selecting; onDispose { vm.selectionActive.value = false } }

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
                        PopItem(label, { dismiss(); vm.logFilter.value = i }, selected = filter == i)
                    }
                }
            }
        )
        if (showFavorites && favorites.isNotEmpty() && !selecting && query.isBlank()) FavoritesRow(
            favorites,
            onCall = { actions.call(it.phones.first().number) },
            onOpen = { vm.detailContact.value = it }
        )
        when (val s = recents) {
            Load.Loading -> ListSkeleton()
            Load.Denied -> StateMessage(Icons.Rounded.History, "See your recent calls",
                null, "Allow access", actions.requestCallLog)
            is Load.Failed -> StateMessage(Icons.Rounded.History, "Couldn't load calls", s.msg, "Try again", actions.requestCallLog)
            is Load.Ready -> if (s.data.logEmpty) {
                StateMessage(Icons.Rounded.History, "No calls yet")
            } else {
                val m = s.data
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
                                onOpen = { vm.detailLog.value = item.first },
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
                vm.deleteLogs(ids) { n -> if (n == 0) Notices.show("Couldn't delete calls") }
                exitSelection()
            }, onDismiss = { confirmDelete = false })
    }
    if (confirmClear) ConfirmDialog("Clear call log?", "This deletes every call in your history, not just the ones shown. It can't be undone.", "Clear",
        onConfirm = {
            confirmClear = false
            vm.clearLog { ok -> if (!ok) Notices.show("Couldn't clear call log") }
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
private fun relatedEntries(all: Load<List<LogEntry>>, e: LogEntry, contact: Contact?): List<LogEntry> {
    val data = (all as? Load.Ready)?.data ?: return listOf(e)
    val keys = HashSet<String>()
    ContactIndex.key(e.number).takeIf { it.isNotEmpty() }?.let(keys::add)
    contact?.phones?.forEach { p -> ContactIndex.key(p.number).takeIf { it.isNotEmpty() }?.let(keys::add) }
    return if (keys.isEmpty()) data.filter { it.id == e.id } else data.filter { ContactIndex.key(it.number) in keys }
}

fun timeText(ctx: Context, t: Long): String = DateUtils.formatDateTime(ctx, t, DateUtils.FORMAT_SHOW_TIME)

/** One call: direction in a round badge (a check while selected), what it was, when and for how long, and the SIM it used. */
@Composable
private fun HistoryEntryRow(e: LogEntry, showDate: Boolean, modifier: Modifier = Modifier, selected: Boolean = false) {
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
private fun Section(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalColors.current
    if (title != null) Text(title, color = c.inkDim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 20.dp, bottom = 6.dp))
    else Spacer(Modifier.height(12.dp))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.surface), content = content)
}

/** One tappable line of a [Section]: optional icon, the title, an optional value on the right, an optional chevron. */
@Composable
private fun CardRow(title: String, icon: ImageVector? = null, value: String? = null, chevron: Boolean = false, onClick: () -> Unit) {
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
private fun QuickActions(number: String, actions: Actions) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        ActionButton(rememberVectorPainter(Icons.Rounded.Call), "Call", filled = true) { actions.call(number) }
        ActionButton(rememberVectorPainter(Icons.AutoMirrored.Filled.Message), "Message") { actions.message(number) }
        ActionButton(painterResource(R.drawable.whatsapp), "WhatsApp", iconTint = Color.Unspecified) { actions.whatsapp(number) }
        ActionButton(rememberVectorPainter(Icons.Rounded.Share), "Share") { actions.share(number) }
    }
}

/** A saved number: tap to call, long-press for the number menu, a message shortcut at the end. [highlight] marks the number this call was with. */
@Composable
private fun PhoneRow(p: ContactPhone, ct: Contact, highlight: Boolean, actions: Actions) {
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

private class SimRow(val key: String?, val slot: Int?, val title: String, val sub: String?)

/** Slot number and carrier name for each call account. Reads telephony state, so call it off the main thread. */
private fun simRows(ctx: Context, handles: List<PhoneAccountHandle>) = handles.mapIndexed { i, h ->
    val slot = SimLabels.slot(ctx, h.componentName.flattenToString(), h.id) ?: (i + 1)
    SimRow(SimLabels.key(h), slot, "SIM $slot", SimLabels.label(ctx, h)?.takeUnless { it.equals("SIM $slot", ignoreCase = true) })
}

/** "Call from" line for the numbers on a screen. Opens the SIM sheet to pick Default or one SIM for them. Only on dual-SIM phones. */
@Composable
private fun SimPrefRow(numbers: List<String>, subject: String) {
    val ctx = LocalContext.current
    val sims = remember { SimLabels.accounts(ctx) }
    if (sims.size < 2 || numbers.isEmpty()) return
    val rows by produceState(emptyList(), sims) { value = withContext(Dispatchers.IO) { simRows(ctx, sims) } }
    var key by remember(numbers) { mutableStateOf(Prefs.simFor(numbers.first())) }
    var open by remember { mutableStateOf(false) }
    CardRow("Call from", Icons.Rounded.SimCard, value = rows.firstOrNull { it.key == key }?.title ?: "Default", chevron = true) { open = true }
    if (open) SimSheet(subject, rows, key, withDefault = true, onPick = { k ->
        numbers.forEach { Prefs.setSimFor(it, k) }
        key = k
        open = false
    }, onDismiss = { open = false })
}

/** Radio list of SIMs in a bottom sheet. With [withDefault] the first option is "use the phone's default"; a tap on any option picks it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimSheet(subject: String, rows: List<SimRow>, selected: String?, withDefault: Boolean, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    val c = LocalColors.current
    @Composable
    fun Option(key: String?, slot: Int?, title: String, sub: String?, on: Boolean) {
        Row(
            Modifier.fillMaxWidth().selectable(on, role = Role.RadioButton) { onPick(key) }.heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(22.dp).border(2.dp, if (on) c.accent else c.line, CircleShape), contentAlignment = Alignment.Center) {
                if (on) Box(Modifier.size(10.dp).background(c.accent, CircleShape))
            }
            Spacer(Modifier.width(16.dp))
            if (slot != null) {
                Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(c.accent), contentAlignment = Alignment.Center) {
                    Text("$slot", color = c.onAccent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                if (sub != null) Text(sub, color = c.inkDim, fontSize = 13.sp)
            }
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { Box(Modifier.padding(top = 12.dp, bottom = 8.dp).size(width = 40.dp, height = 4.dp).clip(CircleShape).background(c.line)) },
        containerColor = c.bg, contentColor = c.ink
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)) {
            Text("CALL WITH", style = T.label, color = c.accent, modifier = Modifier.padding(start = 12.dp))
            Text(subject, color = c.inkDim, fontSize = 14.sp, modifier = Modifier.padding(start = 12.dp, top = 2.dp, bottom = 12.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.surface)) {
                if (withDefault) Option(null, null, "Default", "Use your device's default SIM", selected == null)
                rows.forEach { Option(it.key, it.slot, it.title, it.sub, it.key == selected) }
            }
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(28.dp)).background(c.surface).clickable(role = Role.Button, onClick = onDismiss),
                contentAlignment = Alignment.Center
            ) { Text("Cancel", color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium) }
        }
    }
}

/** Everything the call log has for the numbers saved on [ct], newest first. */
private fun contactEntries(all: Load<List<LogEntry>>, ct: Contact): List<LogEntry> {
    val data = (all as? Load.Ready)?.data ?: return emptyList()
    val keys = ct.phones.map { ContactIndex.key(it.number) }.filter { it.isNotEmpty() }.toSet()
    return data.filter { ContactIndex.key(it.number) in keys }
}

/** The last few calls as tappable rows (each opens the full history), then "View all". */
@Composable
private fun RecentActivity(related: List<LogEntry>, openHistory: () -> Unit) {
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

@Composable
fun CallDetailScreen(vm: DialerViewModel, actions: Actions, e: LogEntry, contact: Contact?) {
    val c = LocalColors.current
    val all by vm.log.collectAsState()
    val starredIds by vm.starredIds.collectAsState()
    val related = remember(all, e, contact) { relatedEntries(all, e, contact) }
    // Everything for this person was deleted (from the history screen): nothing left to show.
    LaunchedEffect(all, related) { if (all is Load.Ready && related.isEmpty()) vm.detailLog.value = null }
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
    Overlay(onBack = { vm.detailLog.value = null }) {
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
            RecentActivity(related) { vm.historyLog.value = e }
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
private fun SelectBadge() {
    val c = LocalColors.current
    // Ring matches the selected row's tint (accent at 12% over the screen), so the badge looks cut out of the avatar.
    val ring = androidx.compose.ui.graphics.lerp(c.surface, c.accent, 0.12f)
    Box(Modifier.size(22.dp).background(ring, CircleShape).padding(2.dp).background(c.accent, CircleShape), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Check, null, tint = c.onAccent, modifier = Modifier.size(14.dp))
    }
}

/** Every call with this person, grouped by day. Filters on top; long-press or "Select" to delete several. */
@Composable
fun CallHistoryScreen(vm: DialerViewModel, e: LogEntry, contact: Contact?) {
    val c = LocalColors.current
    val ctx = LocalContext.current
    val all by vm.log.collectAsState()
    val related = remember(all, e, contact) { relatedEntries(all, e, contact) }
    LaunchedEffect(all, related) { if (all is Load.Ready && related.isEmpty()) vm.historyLog.value = null }

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
    DisposableEffect(selecting) { vm.selectionActive.value = selecting; onDispose { vm.selectionActive.value = false } }
    val talkSec = remember(related) { related.sumOf { it.durationSec } }
    BackHandler { if (selecting) exitSelection() else vm.historyLog.value = null }

    Column(Modifier.fillMaxSize().background(c.bg).pointerInput(Unit) { detectTapGestures { } }) {
        if (selecting) ContextBar(selected.size, onClose = { exitSelection() }) {
            if (selected.size == 1 && shown.size > 1) IconAction(Icons.Rounded.Checklist, "Select all", { selected.clear(); selected.addAll(shown.map { it.id }) })
            IconAction(Icons.Rounded.Delete, "Delete", { confirm = true })
        } else Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconAction(Icons.AutoMirrored.Rounded.ArrowBack, "Back", { vm.historyLog.value = null })
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
            vm.deleteLogs(selected.toList()) { n -> if (n == 0) Notices.show("Couldn't delete calls") }
            exitSelection()
        }, onDismiss = { confirm = false })
}

@Composable
fun ContactDetailScreen(vm: DialerViewModel, actions: Actions, ct: Contact) {
    val c = LocalColors.current
    val all by vm.log.collectAsState()
    val starred = ct.id in vm.starredIds.collectAsState().value
    val related = remember(all, ct) { contactEntries(all, ct) }
    val first = ct.phones.firstOrNull()?.number
    Overlay(onBack = { vm.detailContact.value = null }) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(8.dp))
            Avatar(ct.name, ct.photo, 96.dp)
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ct.name, color = c.ink, fontSize = 26.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, modifier = Modifier.weight(1f, fill = false))
                Box(
                    Modifier.size(40.dp).clip(CircleShape)
                        .clickable(role = Role.Button, onClickLabel = if (starred) "Remove favorite" else "Add favorite") { actions.toggleStar(ct, !starred) },
                    contentAlignment = Alignment.Center
                ) { Icon(
                    if (starred) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                    if (starred) "Favorite" else "Not a favorite",
                    tint = if (starred) c.accent else c.inkDim
                ) }
            }
            Text("Saved contact", color = c.inkDim, fontSize = 15.sp)
            if (first != null) {
                Spacer(Modifier.height(20.dp))
                QuickActions(first, actions)
            }
            if (ct.phones.isNotEmpty()) Section(if (ct.phones.size > 1) "Numbers" else "Number") {
                ct.phones.forEach { p -> PhoneRow(p, ct, false, actions) }
                SimPrefRow(ct.phones.map { it.number }, ct.name)
            }
            Section { CardRow("Edit contact", Icons.Rounded.Edit, chevron = true) { actions.openContact(ct) } }
            if (related.isNotEmpty()) RecentActivity(related) { vm.historyLog.value = related.first() }
            Spacer(Modifier.height(32.dp))
        }
    }
}

private fun toneFor(ch: Char): Int = when (ch) {
    '*' -> ToneGenerator.TONE_DTMF_S
    '#' -> ToneGenerator.TONE_DTMF_P
    else -> ToneGenerator.TONE_DTMF_0 + (ch - '0')
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialerSheet(vm: DialerViewModel, actions: Actions, onDismiss: () -> Unit) {
    val c = LocalColors.current
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        shape = RectangleShape,
        dragHandle = null,
        containerColor = c.surface, // a different shade from the screen behind it, so the sheet reads as its own layer
        contentColor = c.ink
    ) {
        DialpadContent(vm, actions, onCall = { n ->
            actions.call(n)
            vm.dial.value = ""
            onDismiss()
        })
    }
}

@Composable
fun SimPickerSheet(vm: DialerViewModel, actions: Actions) {
    val pending by vm.simPicker.collectAsState()
    val p = pending ?: return
    val ctx = LocalContext.current
    val rows by produceState(emptyList(), p) { value = withContext(Dispatchers.IO) { simRows(ctx, p.second.map { it.handle }) } }
    if (rows.isEmpty()) return
    SimSheet(pretty(p.first) ?: p.first, rows, selected = null, withDefault = false, onPick = { k ->
        val sim = p.second.firstOrNull { SimLabels.key(it.handle) == k } ?: return@SimSheet
        vm.simPicker.value = null
        actions.callWith(p.first, sim.handle)
    }, onDismiss = { vm.simPicker.value = null })
}

private fun keepDialChars(t: String) = t.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }

@Composable
private fun DialpadContent(vm: DialerViewModel, actions: Actions, onCall: (String) -> Unit) {
    val c = LocalColors.current
    val suggestions by vm.suggestions.collectAsState()
    val tones by Prefs.keypadTones.collectAsState()
    val dialState by vm.dial.collectAsState()
    val lastDialed by vm.lastDialed.collectAsState()
    val index by vm.index.collectAsState()
    val tick = rememberTick()
    val gen = remember { try { ToneGenerator(AudioManager.STREAM_DTMF, 70) } catch (e: Exception) { Diag.fail("tone_generator", e); null } }
    DisposableEffect(Unit) { onDispose { gen?.release() } }

    // Local value so the field keeps a real cursor/selection (native select, copy, long-press paste).
    var tfv by remember { mutableStateOf(TextFieldValue(dialState, TextRange(dialState.length))) }
    LaunchedEffect(dialState) { if (dialState != tfv.text) tfv = TextFieldValue(dialState, TextRange(dialState.length)) }
    val number = tfv.text

    fun apply(v: TextFieldValue) {
        // Pasted text is reduced to dial characters; the cursor is remapped to the cleaned text.
        val clean = keepDialChars(v.text)
        val s = keepDialChars(v.text.take(v.selection.start)).length
        val e = keepDialChars(v.text.take(v.selection.end)).length
        tfv = TextFieldValue(clean, TextRange(s, e))
        vm.dial.value = clean
        tick()
    }
    fun insert(ch: Char) {
        val sel = tfv.selection
        val t = tfv.text.replaceRange(sel.min, sel.max, ch.toString())
        apply(TextFieldValue(t, TextRange(sel.min + 1)))
    }
    fun backspace() {
        val sel = tfv.selection
        if (!sel.collapsed) apply(TextFieldValue(tfv.text.removeRange(sel.min, sel.max), TextRange(sel.min)))
        else if (sel.start > 0) apply(TextFieldValue(tfv.text.removeRange(sel.start - 1, sel.start), TextRange(sel.start - 1)))
    }

    // Sized from the screen, not from the (unbounded) sheet content constraints.
    val screenH = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    val key = ((screenH - 360.dp) / 5f).coerceIn(52.dp, 68.dp)

    Column(Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        // Only exists once there are matches (two or more digits typed); the sheet is anchored at the bottom, so the keypad doesn't move.
        androidx.compose.animation.AnimatedVisibility(visible = suggestions.isNotEmpty()) {
            Row(Modifier.heightIn(min = 44.dp).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                suggestions.forEach { s ->
                    Column(Modifier.clip(RoundedCornerShape(14.dp)).background(c.surfaceHigh).clickable { onCall(s.phones.first().number) }.padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Text(s.name, color = c.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 100.dp))
                        Text(s.phones.first().number, color = c.inkDim, fontSize = 11.sp, maxLines = 1)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        // The system keyboard is suppressed; the field exists for the cursor, selection handles and the Copy/Paste popup.
        InterceptPlatformTextInput(interceptor = { _, _ -> awaitCancellation() }) {
            val size = when { number.length <= 10 -> 40.sp; number.length <= 14 -> 32.sp; else -> 24.sp }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(48.dp))
                BasicTextField(
                    value = tfv,
                    onValueChange = ::apply,
                    singleLine = true,
                    textStyle = TextStyle(color = c.ink, fontSize = size, fontWeight = FontWeight.Light, textAlign = TextAlign.Center),
                    cursorBrush = SolidColor(c.accent),
                    modifier = Modifier.weight(1f).height(52.dp),
                    decorationBox = { inner ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            if (number.isEmpty()) Text("", color = c.inkDim, fontSize = 20.sp)
                            inner()
                        }
                    }
                )
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    if (number.isNotEmpty()) Box(
                        Modifier.size(48.dp).clip(CircleShape).combinedClickable(
                            role = Role.Button, onClickLabel = "Delete digit", onLongClickLabel = "Clear number",
                            onClick = { backspace() }, onLongClick = { apply(TextFieldValue("")) }
                        ), contentAlignment = Alignment.Center
                    ) { Icon(Icons.AutoMirrored.Rounded.Backspace, "Backspace (long press to clear)", tint = c.inkDim) }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Keypad(key, onKey = { ch ->
            insert(ch)
            if (tones) gen?.startTone(toneFor(ch), 120)
        }, onLongZero = { insert('+') }, spread = true)
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(30.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(key), contentAlignment = Alignment.Center) {
                // A typed number that isn't saved yet can be saved from here.
                if (number.count { it.isDigit() } >= 3 && index.find(number) == null) Column(
                    Modifier.wrapContentWidth(unbounded = true).clip(RoundedCornerShape(14.dp))
                        .clickable(role = Role.Button, onClickLabel = "Save number") { actions.addContact(number) }.padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Rounded.PersonAdd, null, tint = c.inkDim, modifier = Modifier.size(24.dp))
                    Text("Add contact", color = c.inkDim, fontSize = 11.sp, maxLines = 1, softWrap = false)
                }
            }
            // With nothing typed, the call button pastes the last number you dialed (press again to call it).
            RoundButton(Icons.Rounded.Call, if (number.isBlank()) "Fill last dialed number" else "Call", c.accent, c.onAccent, key,
                enabled = number.isNotBlank() || lastDialed != null) {
                if (number.isNotBlank()) onCall(number)
                else lastDialed?.let { apply(TextFieldValue(it, TextRange(it.length))) }
            }
            Spacer(Modifier.size(key)) // keeps the call button under the middle column
        }
    }
}