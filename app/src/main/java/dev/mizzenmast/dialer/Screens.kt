@file:OptIn(ExperimentalFoundationApi::class)
@file:Suppress("DEPRECATION")

package dev.mizzenmast.dialer

import android.Manifest
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.PowerManager
import android.provider.CallLog
import android.provider.Settings
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Backspace
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallMade
import androidx.compose.material.icons.rounded.CallMissed
import androidx.compose.material.icons.rounded.CallReceived
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material.icons.rounded.Sms
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

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
    val callWith: (String, android.telecom.PhoneAccountHandle) -> Unit,
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

// ---------- helpers ----------

fun fmtDuration(sec: Long): String {
    if (sec <= 0) return "0s"
    val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
    return when { h > 0 -> "${h}h ${m}m ${s}s"; m > 0 -> "${m}m ${"%02d".format(s)}s"; else -> "${s}s" }
}

private fun whenText(ctx: Context, t: Long): String {
    val flags = if (DateUtils.isToday(t)) DateUtils.FORMAT_SHOW_TIME
    else DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_ALL or DateUtils.FORMAT_SHOW_TIME
    return DateUtils.formatDateTime(ctx, t, flags)
}

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

/** The direction icon already says incoming/outgoing, so list rows only spell out the exceptions. */
private fun briefLabel(t: Int): String? =
    if (t == CallLog.Calls.INCOMING_TYPE || t == CallLog.Calls.OUTGOING_TYPE) null else typeLabel(t)

/** Full wording for screen readers, since the list shows the shortened summary. */
private fun rowDescription(ctx: Context, e: LogEntry): String =
    listOfNotNull(typeLabel(e.type), whenText(ctx, e.date), e.durationSec.takeIf { it > 0 }?.let(::fmtDuration), e.simSlot?.let { "SIM $it" }).joinToString(", ")

/** Every call type gets an arrow, so the first glyph in every row has the same shape and weight. */
private fun typeIcon(t: Int): ImageVector = when (t) {
    CallLog.Calls.OUTGOING_TYPE -> Icons.Rounded.CallMade
    CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE, CallLog.Calls.BLOCKED_TYPE -> Icons.Rounded.CallMissed
    else -> Icons.Rounded.CallReceived // incoming, answered elsewhere, voicemail
}

/** Green in, blue out, red missed/declined/blocked. Anything else stays neutral. */
private fun typeColor(c: DialerColors, t: Int): Color = when (t) {
    CallLog.Calls.INCOMING_TYPE, CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> c.answer
    CallLog.Calls.OUTGOING_TYPE -> c.outgoing
    CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE, CallLog.Calls.BLOCKED_TYPE -> c.missed
    else -> c.inkDim
}

/** What follows the time: the duration, or the reason there isn't one. Null when there is nothing to say. */
private fun tailText(e: LogEntry): String? = when {
    e.type == CallLog.Calls.MISSED_TYPE -> "Missed"
    e.durationSec > 0 -> fmtDuration(e.durationSec)
    e.type == CallLog.Calls.OUTGOING_TYPE -> "No answer"
    else -> briefLabel(e.type)
}

/** Fixed-size slots, so the arrow, the SIM glyph and the text start at the same x on every row. */
private val MetaSlot = 16.dp

/**
 * Second line of a recents row: arrow, SIM glyph, time, then duration / "Missed". Same size and colours every time.
 * [reserveSim] keeps the SIM slot (empty) for calls without a known slot whenever other rows in the list have one.
 */
@Composable
private fun CallMetaLine(e: LogEntry, time: String, reserveSim: Boolean, modifier: Modifier = Modifier) {
    val c = LocalColors.current
    val ctx = LocalContext.current
    val tone = typeColor(c, e.type)
    val flagged = e.type == CallLog.Calls.MISSED_TYPE
    val text = buildAnnotatedString {
        append(time)
        tailText(e)?.let { t ->
            append(" · ")
            if (flagged) withStyle(SpanStyle(color = c.missed, fontWeight = FontWeight.Medium)) { append(t) } else append(t)
        }
    }
    Row(modifier.semantics(mergeDescendants = true) { contentDescription = rowDescription(ctx, e) }, verticalAlignment = Alignment.CenterVertically) {
        Icon(typeIcon(e.type), null, tint = tone, modifier = Modifier.size(MetaSlot))
        if (reserveSim) {
            Spacer(Modifier.width(6.dp))
            Box(Modifier.size(MetaSlot)) { e.simSlot?.takeIf { it in 1..2 }?.let { SimIcon(it, size = MetaSlot) } }
        }
        Spacer(Modifier.width(8.dp))
        Text(text, color = c.inkDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
    }
}

fun roleHeld(ctx: Context): Boolean =
    if (Build.VERSION.SDK_INT >= 29) ctx.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_DIALER) == true
    else ctx.getSystemService(android.telecom.TelecomManager::class.java)?.defaultDialerPackage == ctx.packageName

@Composable
private fun Overlay(onBack: () -> Unit, content: @Composable () -> Unit) {
    val c = LocalColors.current
    BackHandler(onBack = onBack)
    // The empty pointerInput makes the overlay swallow touches, so taps never fall through to the screen underneath.
    Column(Modifier.fillMaxSize().background(c.bg).pointerInput(Unit) { detectTapGestures { } }) {
        Box(Modifier.padding(start = 8.dp, top = 8.dp).size(48.dp).clip(CircleShape).clickable(onClickLabel = "Back", onClick = onBack), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.ArrowBack, "Back", tint = c.ink)
        }
        content()
    }
}

// ---------- recents ----------

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

/** Up to five starred contacts. Tap to call their first number, long-press to open the contact. Hidden in Settings > Favorites row. */
@Composable
private fun FavoritesRow(favs: List<Contact>, onCall: (Contact) -> Unit, onOpen: (Contact) -> Unit) {
    val c = LocalColors.current
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        favs.forEach { f ->
            Column(
                Modifier.width(60.dp).clip(RoundedCornerShape(12.dp))
                    .combinedClickable(role = Role.Button, onClickLabel = "Call ${f.name}", onLongClickLabel = "Open contact", onLongClick = { onOpen(f) }, onClick = { onCall(f) })
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Avatar(f.name, f.photo, 52.dp)
                Spacer(Modifier.height(4.dp))
                Text(f.name.substringBefore(' '), color = c.inkDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
fun RecentsScreen(vm: DialerViewModel, actions: Actions) {
    val c = LocalColors.current
    val recents by vm.recents.collectAsState()
    val filter by vm.logFilter.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val showFavorites by Prefs.showFavorites.collectAsState()
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Recents")
        if (showFavorites && favorites.isNotEmpty()) FavoritesRow(favorites, onCall = { actions.call(it.phones.first().number) }, onOpen = { vm.detailContact.value = it })
        when (val s = recents) {
            Load.Loading -> ListSkeleton()
            Load.Denied -> StateMessage(Icons.Rounded.History, "See your recent calls",
                null, "Allow access", actions.requestCallLog)
            is Load.Failed -> StateMessage(Icons.Rounded.History, "Couldn't load calls", s.msg, "Try again", actions.requestCallLog)
            is Load.Ready -> if (s.data.logEmpty) {
                StateMessage(Icons.Rounded.History, "No calls yet")
            } else {
                Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip("All", filter == 0, { vm.logFilter.value = 0 })
                    FilterChip("Missed", filter == 1, { vm.logFilter.value = 1 })
                }
                val m = s.data
                if (m.items.isEmpty()) StateMessage(Icons.Rounded.CallMissed, "No missed calls")
                else LazyColumn(Modifier.fillMaxSize()) {
                    items(
                        m.items,
                        key = { if (it is RItem.Header) it.key else "g${(it as RItem.Group).first.id}" },
                        contentType = { if (it is RItem.Header) 0 else 1 }
                    ) { item ->
                        when (item) {
                            is RItem.Header -> Text(item.label, color = c.inkDim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp))
                            is RItem.Group -> LogRow(item, m.anySim,
                                onOpen = { vm.detailLog.value = item.first },
                                onContact = item.contact?.let { ct -> { vm.detailContact.value = ct } },
                                onCall = { actions.call(item.first.number) })
                        }
                    }
                }
            }
        }
    }
}

/** Tap the row for details, the avatar for the contact, the phone icon to call back (SIM choice happens in the call flow). */
@Composable
private fun LogRow(g: RItem.Group, reserveSim: Boolean, onOpen: () -> Unit, onContact: (() -> Unit)?, onCall: () -> Unit) {
    val c = LocalColors.current
    val e = g.first
    val contact = g.contact
    val count = g.entries.size
    val missed = e.type == CallLog.Calls.MISSED_TYPE
    val title = g.title
    Row(
        Modifier.fillMaxWidth().clickable(onClickLabel = "Call details", onClick = onOpen).padding(start = 20.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(contact?.name ?: e.name, contact?.photo, 48.dp,
            if (onContact != null) Modifier.clip(CircleShape).clickable(role = Role.Button, onClickLabel = "Open contact", onClick = onContact) else Modifier)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title + if (count > 1) "  ($count)" else "", color = if (missed) c.missed else c.ink, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            CallMetaLine(e, g.time, reserveSim)
        }
        if (e.number.isNotBlank()) {
            Box(Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onCall), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Call, "Call $title", tint = c.accent)
            }
        } else Spacer(Modifier.width(16.dp))
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

/** One call: direction arrow, when, SIM slot, duration. [leading] is the checkbox while selecting. */
@Composable
private fun HistoryEntryRow(e: LogEntry, showDate: Boolean, reserveSim: Boolean, modifier: Modifier = Modifier, leading: (@Composable () -> Unit)? = null) {
    val c = LocalColors.current
    val ctx = LocalContext.current
    val missed = e.type == CallLog.Calls.MISSED_TYPE
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        leading?.invoke()
        Icon(typeIcon(e.type), null, tint = typeColor(c, e.type), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        if (reserveSim) {
            Box(Modifier.size(MetaSlot)) {
                e.simSlot?.takeIf { it in 1..2 }?.let { SimIcon(it, size = MetaSlot) }
            }
            Spacer(Modifier.width(8.dp))
        }
        Text(listOfNotNull(briefLabel(e.type), if (showDate) whenText(ctx, e.date) else timeText(ctx, e.date)).joinToString(" · "),
            color = if (missed) c.missed else c.ink, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { contentDescription = typeLabel(e.type) + ", " + whenText(ctx, e.date) })
        Text(if (e.durationSec > 0) fmtDuration(e.durationSec) else "", color = c.inkDim, fontSize = 13.sp, textAlign = TextAlign.End, modifier = Modifier.width(64.dp))
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
            .widthIn(min = 80.dp)
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

/** A saved number: tap to call, with a message shortcut. [highlight] marks the number this call was with. */
@Composable
private fun PhoneRow(p: ContactPhone, highlight: Boolean, actions: Actions) {
    val c = LocalColors.current
    var menu by remember { mutableStateOf(false) }
    if (menu) NumberMenu(p.number, onEdit = { actions.editBeforeCall(p.number) }, onDismiss = { menu = false })
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClickLabel = "Call", onLongClickLabel = "Copy or edit number", onLongClick = { menu = true }) { actions.call(p.number) }
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(pretty(p.number) ?: p.number, color = if (highlight) c.accent else c.ink, fontSize = 18.sp)
            Text(p.label, color = c.inkDim, fontSize = 13.sp)
        }
        Box(Modifier.size(44.dp).clip(CircleShape).background(c.surfaceHigh).clickable(onClickLabel = "Message") { actions.message(p.number) }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Sms, "Message ${p.label}", tint = c.ink, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(10.dp))
        Box(Modifier.size(44.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Call, "Call ${p.label}", tint = c.onAccent, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
fun CallDetailScreen(vm: DialerViewModel, actions: Actions, e: LogEntry, contact: Contact?) {
    val c = LocalColors.current
    val all by vm.log.collectAsState()
    val related = remember(all, e, contact) { relatedEntries(all, e, contact) }
    // Everything for this person was deleted (from the history screen): nothing left to show.
    LaunchedEffect(all, related) { if (all is Load.Ready && related.isEmpty()) vm.detailLog.value = null }
    val number = e.number
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var blocked by remember(number) { mutableStateOf(false) }
    var numberMenu by remember { mutableStateOf(false) }
    if (numberMenu) NumberMenu(number, onEdit = { actions.editBeforeCall(number) }, onDismiss = { numberMenu = false })
    val numberHold = if (number.isNotBlank()) Modifier.clip(RoundedCornerShape(12.dp))
        .combinedClickable(onLongClickLabel = "Copy or edit number", onLongClick = { numberMenu = true }, onClick = {}) else Modifier
    LaunchedEffect(number) { if (number.isNotBlank()) blocked = withContext(Dispatchers.IO) { BlockedRepo.isBlocked(ctx, number) } }
    Overlay(onBack = { vm.detailLog.value = null }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(8.dp))
            Avatar(contact?.name ?: e.name, contact?.photo, 96.dp,
                if (contact != null) Modifier.clip(CircleShape).clickable(role = Role.Button, onClickLabel = "Open contact") { vm.detailContact.value = contact } else Modifier)
            Spacer(Modifier.height(16.dp))
            Text(e.title(contact), color = c.ink, fontSize = 26.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, modifier = numberHold)
            if ((contact != null || e.name != null) && number.isNotBlank()) Text(pretty(number) ?: number, color = c.inkDim, fontSize = 16.sp, modifier = numberHold)
            if (number.isNotBlank()) {
                Spacer(Modifier.height(20.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    ActionButton(
                        rememberVectorPainter(Icons.Rounded.Call),
                        "Call",
                        filled = true
                    ) {
                        actions.call(number)
                    }

                    ActionButton(
                        painterResource(R.drawable.whatsapp),
                        "WhatsApp",
                        iconTint = Color.Unspecified
                    ) {
                        actions.whatsapp(number)
                    }

                    ActionButton(
                        rememberVectorPainter(Icons.AutoMirrored.Filled.Message),
                        "Message"
                    ) {
                        actions.message(number)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    if (contact == null) ActionButton(rememberVectorPainter(Icons.Rounded.PersonAdd), "Add contact") { actions.addContact(number) }
                    ActionButton(
                        rememberVectorPainter(Icons.Rounded.Block),
                        if (blocked) "Unblock" else "Block",
                        iconTint = if (blocked) null else c.decline
                    ) {
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                if (!BlockedRepo.canBlock(ctx)) null
                                else if (blocked) BlockedRepo.unblock(ctx, number) else BlockedRepo.block(ctx, number)
                            }
                            when (ok) {
                                null -> Toast.makeText(ctx, "Set as default phone app to block numbers", Toast.LENGTH_SHORT).show()
                                true -> blocked = !blocked
                                false -> Toast.makeText(ctx, "Couldn't update block list", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
            val phones = contact?.phones.orEmpty()
            if (phones.size > 1) {
                Spacer(Modifier.height(20.dp))
                Text("NUMBERS", style = T.label, color = c.inkDim, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                phones.forEach { p -> PhoneRow(p, ContactIndex.key(p.number) == ContactIndex.key(number), actions) }
            }
            Spacer(Modifier.height(20.dp))
            Text("HISTORY", style = T.label, color = c.inkDim, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
            val anySim = related.any { it.simSlot != null }
            related.take(4).forEach { r -> HistoryEntryRow(r, showDate = true, reserveSim = anySim, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { vm.historyLog.value = e }.padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("View all", color = c.accent, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                if (related.size > 4) Text("${related.size}", color = c.inkDim, fontSize = 13.sp)
                Icon(Icons.Rounded.ChevronRight, null, tint = c.inkDim)
            }
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
private fun SelectMark(on: Boolean) {
    val c = LocalColors.current
    Icon(if (on) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null, tint = if (on) c.accent else c.inkDim, modifier = Modifier.size(24.dp))
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

    val anySim = remember(related) { related.any { it.simSlot != null } }
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
    fun toggle(id: Long) { if (id in selected) selected.remove(id) else selected.add(id) }
    BackHandler { if (selecting) exitSelection() else vm.historyLog.value = null }

    Column(Modifier.fillMaxSize().background(c.bg).pointerInput(Unit) { detectTapGestures { } }) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button) { if (selecting) exitSelection() else vm.historyLog.value = null },
                contentAlignment = Alignment.Center
            ) { Icon(if (selecting) Icons.Rounded.Close else Icons.Rounded.ArrowBack, if (selecting) "Cancel selection" else "Back", tint = c.ink) }
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(if (selecting) "${selected.size} selected" else "History", color = c.ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                if (!selecting) Text(e.title(contact), color = c.inkDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (shown.isNotEmpty()) {
                val allOn = selected.size == shown.size
                Box(
                    Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).clickable(role = Role.Button) {
                        when {
                            !selecting -> selecting = true
                            allOn -> selected.clear()
                            else -> { selected.clear(); selected.addAll(shown.map { it.id }) }
                        }
                    }.padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) { Text(if (!selecting) "Select" else if (allOn) "None" else "All", color = c.accent, fontSize = 15.sp, fontWeight = FontWeight.Medium) }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("All", "Outgoing", "Incoming", "Missed").forEachIndexed { i, l -> FilterChip(l, filter == i, { filter = i; selected.clear() }) }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (shown.isEmpty()) StateMessage(Icons.Rounded.History, "No calls")
            else LazyColumn(Modifier.fillMaxSize()) {
                items(rows, key = { if (it is HItem.Day) "d${it.key}" else "e${(it as HItem.Entry).e.id}" }) { item ->
                    when (item) {
                        is HItem.Day -> Text(item.label, color = c.inkDim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp))
                        is HItem.Entry -> {
                            val r = item.e
                            val on = r.id in selected
                            val lead: (@Composable () -> Unit)? = if (selecting) ({ SelectMark(on); Spacer(Modifier.width(14.dp)) }) else null
                            HistoryEntryRow(r, showDate = false, reserveSim = anySim, leading = lead,
                                modifier = Modifier.fillMaxWidth()
                                    .then(
                                        if (selecting) Modifier.selectable(on, role = Role.Checkbox) { toggle(r.id) }
                                        else Modifier.combinedClickable(onLongClickLabel = "Select", onLongClick = { selecting = true; selected.add(r.id) }, onClick = {})
                                    )
                                    .background(if (on) c.accent.copy(alpha = 0.12f) else Color.Transparent)
                                    .padding(horizontal = 20.dp, vertical = 12.dp))
                        }
                    }
                }
            }
        }
        if (selecting) Box(Modifier.fillMaxWidth().background(c.surface).padding(16.dp), contentAlignment = Alignment.Center) {
            if (selected.isNotEmpty()) TextPill("Delete ${selected.size}", { confirm = true }, Modifier.fillMaxWidth(), danger = true)
            else Spacer(Modifier.height(44.dp))
        }
    }
    if (confirm) ConfirmDialog(if (selected.size == 1) "Delete this call?" else "Delete ${selected.size} calls?", null, "Delete",
        onConfirm = {
            confirm = false
            vm.deleteLogs(selected.toList()) { n -> if (n == 0) Toast.makeText(ctx, "Couldn't delete calls", Toast.LENGTH_SHORT).show() }
            exitSelection()
        }, onDismiss = { confirm = false })
}

// ---------- contacts ----------

private fun letterOf(name: String): Char {
    val ch = name.trim().firstOrNull() ?: return '#'
    val base = java.text.Normalizer.normalize(ch.toString(), java.text.Normalizer.Form.NFD).firstOrNull() ?: return '#'
    return if (base.isLetter()) base.uppercaseChar() else '#'
}

@Composable
private fun AlphaRail(letters: List<Char>, modifier: Modifier = Modifier, onPick: (Char) -> Unit) {
    val c = LocalColors.current
    var h by remember { mutableIntStateOf(1) }
    fun pick(y: Float) { if (letters.isNotEmpty()) onPick(letters[((y / h) * letters.size).toInt().coerceIn(0, letters.size - 1)]) }
    Column(
        modifier.fillMaxHeight().padding(vertical = 12.dp).width(28.dp).onSizeChanged { h = it.height.coerceAtLeast(1) }
            .pointerInput(letters) { detectTapGestures { o -> pick(o.y) } }
            .pointerInput(letters) { detectVerticalDragGestures { change, _ -> pick(change.position.y) } },
        verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally
    ) {
        letters.forEach { Text(it.toString(), color = c.accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContactsScreen(vm: DialerViewModel, actions: Actions) {
    val c = LocalColors.current
    val list by vm.filtered.collectAsState()
    val q by vm.query.collectAsState()
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Contacts")
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).clip(RoundedCornerShape(14.dp)).background(c.surface).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.Search, null, tint = c.inkDim, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            BasicTextField(
                value = q, onValueChange = { vm.query.value = it }, singleLine = true,
                textStyle = TextStyle(color = c.ink, fontSize = 16.sp), cursorBrush = SolidColor(c.accent),
                modifier = Modifier.weight(1f),
                decorationBox = { inner -> Box { if (q.isEmpty()) Text("Search contacts", color = c.inkDim, fontSize = 16.sp); inner() } }
            )
        }
        Spacer(Modifier.height(8.dp))
        when (val s = list) {
            Load.Loading -> ListSkeleton()
            Load.Denied -> StateMessage(Icons.Rounded.Contacts, "See your contacts", null, "Allow access", actions.requestContacts)
            is Load.Failed -> StateMessage(Icons.Rounded.Contacts, "Couldn't load contacts", s.msg, "Try again", actions.requestContacts)
            is Load.Ready -> if (s.data.isEmpty()) {
                StateMessage(Icons.Rounded.Contacts, if (q.isBlank()) "No contacts" else "No matches")
            } else {
                val sections = remember(s.data) {
                    val m = s.data.groupBy { letterOf(it.name) }
                    val keys = m.keys.filter { it != '#' } + (if (m.containsKey('#')) listOf('#') else emptyList())
                    keys.map { it to m.getValue(it) }
                }
                val positions = remember(sections) {
                    val map = HashMap<Char, Int>(); var run = 0
                    sections.forEach { (l, people) -> map[l] = run; run += 1 + people.size }
                    map
                }
                val state = rememberLazyListState()
                val scope = rememberCoroutineScope()
                Box(Modifier.fillMaxSize()) {
                    LazyColumn(Modifier.fillMaxSize(), state = state) {
                        sections.forEach { (letter, people) ->
                            stickyHeader(key = "h$letter") {
                                Text(letter.toString(), color = c.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.fillMaxWidth().background(c.bg).padding(horizontal = 20.dp, vertical = 6.dp))
                            }
                            items(people, key = { it.id }) { ct ->
                                Row(Modifier.fillMaxWidth().clickable { vm.detailContact.value = ct }.padding(start = 20.dp, end = 38.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Avatar(ct.name, ct.photo, 44.dp)
                                    Spacer(Modifier.width(14.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(ct.name, color = c.ink, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text((pretty(ct.phones.first().number) ?: ct.phones.first().number) + (if (ct.phones.size > 1) "  +${ct.phones.size - 1}" else ""), color = c.inkDim, fontSize = 13.sp, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                    if (q.isBlank() && sections.size > 1) {
                        AlphaRail(sections.map { it.first }, Modifier.align(Alignment.CenterEnd)) { l ->
                            positions[l]?.let { p -> scope.launch { state.scrollToItem(p) } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ContactDetailScreen(vm: DialerViewModel, actions: Actions, ct: Contact) {
    val c = LocalColors.current
    val starred = ct.id in vm.starredIds.collectAsState().value
    Overlay(onBack = { vm.detailContact.value = null }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Avatar(ct.name, ct.photo, 96.dp)
            Spacer(Modifier.height(16.dp))
            Text(ct.name, color = c.ink, fontSize = 26.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(12.dp))
            ActionButton(
                rememberVectorPainter(if (starred) Icons.Rounded.Star else Icons.Rounded.StarBorder),
                if (starred) "Favorite" else "Add favorite",
                iconTint = if (starred) c.accent else null
            ) { actions.toggleStar(ct, !starred) }
            Spacer(Modifier.height(8.dp))
            ct.phones.forEach { p -> PhoneRow(p, false, actions) }
            Spacer(Modifier.height(20.dp))
            TextPill("Edit contact", { actions.openContact(ct) })
        }
    }
}

// ---------- keypad ----------

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
        containerColor = c.bg,
        contentColor = c.ink
    ) {
        DialpadContent(vm, actions, onCall = { n ->
            actions.call(n)
            vm.dial.value = ""
            onDismiss()
        })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimPickerSheet(vm: DialerViewModel, actions: Actions) {
    val pending by vm.simPicker.collectAsState()
    val p = pending ?: return
    val c = LocalColors.current
    ModalBottomSheet(
        onDismissRequest = { vm.simPicker.value = null },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RectangleShape, dragHandle = null, containerColor = c.bg, contentColor = c.ink
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("CALL WITH", style = T.label, color = c.accent, modifier = Modifier.padding(start = 4.dp))
            Text(p.first, color = c.inkDim, fontSize = 14.sp, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
            p.second.forEach { sim ->
                SimChoice(sim.label) { vm.simPicker.value = null; actions.callWith(p.first, sim.handle) }
            }
        }
    }
}

private fun keepDialChars(t: String) = t.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun DialpadContent(vm: DialerViewModel, actions: Actions, onCall: (String) -> Unit) {
    val c = LocalColors.current
    val suggestions by vm.suggestions.collectAsState()
    val tones by Prefs.keypadTones.collectAsState()
    val dialState by vm.dial.collectAsState()
    val tick = rememberTick()
    val gen = remember { try { ToneGenerator(AudioManager.STREAM_DTMF, 70) } catch (e: Exception) { null } }
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
        Row(Modifier.heightIn(min = 44.dp).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            suggestions.forEach { s ->
                Column(Modifier.clip(RoundedCornerShape(14.dp)).background(c.surface).clickable { onCall(s.phones.first().number) }.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text(s.name, color = c.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 100.dp))
                    Text(s.phones.first().number, color = c.inkDim, fontSize = 11.sp, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        // The system keyboard is suppressed; the field exists for the cursor, selection handles and the Copy/Paste popup.
        InterceptPlatformTextInput(interceptor = { _, _ -> awaitCancellation() }) {
            val size = when { number.length <= 10 -> 40.sp; number.length <= 14 -> 32.sp; else -> 24.sp }
            BasicTextField(
                value = tfv,
                onValueChange = ::apply,
                singleLine = true,
                textStyle = TextStyle(color = c.ink, fontSize = size, fontWeight = FontWeight.Light, textAlign = TextAlign.Center),
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 24.dp),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (number.isEmpty()) Text("", color = c.inkDim, fontSize = 20.sp)
                        inner()
                    }
                }
            )
        }
        Spacer(Modifier.height(12.dp))
        Keypad(key, onKey = { ch ->
            insert(ch)
            if (tones) gen?.startTone(toneFor(ch), 120)
        }, onLongZero = { insert('+') })
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.size(key))
            RoundButton(Icons.Rounded.Call, "Call", c.answer, Color.White, key, enabled = number.isNotBlank()) { onCall(number) }
            Box(Modifier.size(key), contentAlignment = Alignment.Center) {
                if (number.isNotEmpty()) Box(
                    Modifier.size(56.dp).clip(CircleShape).combinedClickable(
                        onClickLabel = "Delete digit",
                        onClick = { backspace() },
                        onLongClick = { apply(TextFieldValue("")) }
                    ), contentAlignment = Alignment.Center
                ) { Icon(Icons.Rounded.Backspace, "Backspace (long press to clear)", tint = c.inkDim) }
            }
        }
    }
}

// ---------- settings ----------

@Composable
fun StatusRow(title: String, sub: String?, ok: Boolean, action: String?, onAction: () -> Unit) {
    val c = LocalColors.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (ok) c.answer else c.hold))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            if (sub != null) Text(sub, color = c.inkDim, fontSize = 13.sp)
        }
        if (!ok && action != null) TextPill(action, onAction)
    }
}

@Composable
private fun ToggleRow(title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = LocalColors.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            if (sub != null) Text(sub, color = c.inkDim, fontSize = 13.sp)
        }
        Spacer(Modifier.width(12.dp))
        Toggle(checked, onChange)
    }
}

@Composable
private fun LinkRow(title: String, sub: String?, onClick: () -> Unit) {
    val c = LocalColors.current
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(title, color = c.accent, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        if (sub != null) Text(sub, color = c.inkDim, fontSize = 13.sp)
    }
}

@Composable
fun SettingsScreen(vm: DialerViewModel, actions: Actions) {
    val c = LocalColors.current
    val ctx = LocalContext.current
    val tick by vm.tick.collectAsState()
    val theme by Prefs.theme.collectAsState()
    val haptics by Prefs.haptics.collectAsState()
    val tones by Prefs.keypadTones.collectAsState()
    val hints by Prefs.gestureHints.collectAsState()
    val direct by Prefs.directLaunch.collectAsState()
    val diag by Prefs.diagnostics.collectAsState()
    val favs by Prefs.showFavorites.collectAsState()
    val silence by Prefs.silenceUnknown.collectAsState()

    var setupOpen by rememberSaveable { mutableStateOf(false) }
    var troubleOpen by rememberSaveable { mutableStateOf(false) }
    val isDefault = remember(tick) { roleHeld(ctx) }
    val contacts = remember(tick) { ctx.granted(Manifest.permission.READ_CONTACTS) }
    val callLog = remember(tick) { ctx.granted(Manifest.permission.READ_CALL_LOG) && ctx.granted(Manifest.permission.WRITE_CALL_LOG) }
    val phone = remember(tick) { ctx.granted(Manifest.permission.CALL_PHONE) }
    val notif = remember(tick) { Build.VERSION.SDK_INT < 33 || ctx.granted(Manifest.permission.POST_NOTIFICATIONS) }
    val fsi = remember(tick) { Build.VERSION.SDK_INT < 34 || ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent() }
    val overlay = remember(tick) { Settings.canDrawOverlays(ctx) }
    val battery = remember(tick) { ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenTitle("Settings")

        val setupOk = isDefault && contacts && callLog && phone && notif && fsi
        if (setupOk) {
            ExpandRow(Icons.Rounded.CheckCircle, c.answer, "Setup complete", null, setupOpen) { setupOpen = !setupOpen }
        }
        if (!setupOk || setupOpen) {
            // A row only explains itself while it still needs action.
            SectionLabel("SETUP")
            StatusRow("Default phone app", if (isDefault) null else "Required for calls", isDefault, "Set", actions.requestRole)
            StatusRow("Contacts", if (contacts) null else "Names and photos", contacts, "Allow", actions.requestContacts)
            StatusRow("Call history", if (callLog) null else "Recent calls", callLog, "Allow", actions.requestCallLog)
            StatusRow("Phone", if (phone) null else "Place calls", phone, "Allow", actions.requestPhone)
            StatusRow("Notifications", if (notif) null else "Call alerts", notif, "Allow", actions.requestNotifications)
            if (Build.VERSION.SDK_INT >= 34) StatusRow("Full-screen alerts", if (fsi) null else "Show calls over the lock screen", fsi, "Open") { actions.open(SettingsTarget.FullScreen) }
            StatusRow("Display over other apps", if (overlay) null else "Optional", overlay, "Open") { actions.open(SettingsTarget.Overlay) }
            StatusRow("Unrestricted battery", if (battery) null else "Optional", battery, "Open") { actions.open(SettingsTarget.Battery) }
        }

        SectionLabel("CALLS")
        NavRow(Icons.Rounded.MusicNote, "Ringtone & vibration", null) { actions.open(SettingsTarget.Sound) }
        NavRow(Icons.Rounded.SimCard, "Calling accounts", "Default SIM") { actions.open(SettingsTarget.CallingAccounts) }
        NavRow(Icons.Rounded.Block, "Blocked numbers", null) { actions.open(SettingsTarget.Blocked) }
        ToggleRow("Silence unknown callers", "Only saved contacts ring; others appear as missed calls", silence) { on ->
            Prefs.set("silence_unknown", Prefs.silenceUnknown, on)
            // The screening service stays disabled while this is off, so Telecom never binds it.
            ctx.packageManager.setComponentEnabledSetting(
                android.content.ComponentName(ctx, UnknownCallerScreeningService::class.java),
                if (on) android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED else android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                android.content.pm.PackageManager.DONT_KILL_APP
            )
        }
        NavRow(Icons.Rounded.Notifications, "Call notifications", null) { actions.open(SettingsTarget.Notifications) }

        SectionLabel("PREFERENCES")
        Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("System", "Light", "Dark").forEachIndexed { i, l -> TextPill(l, { Prefs.setTheme(i) }, filled = theme == i) }
        }
        ToggleRow("Favorites row", "Starred contacts at the top of Recents", favs) { Prefs.set("show_favorites", Prefs.showFavorites, it) }
        ToggleRow("Haptic feedback", null, haptics) { Prefs.set("haptics", Prefs.haptics, it) }
        ToggleRow("Keypad tones", null, tones) { Prefs.set("keypad_tones", Prefs.keypadTones, it) }
        ToggleRow("Swipe hint on incoming calls", null, hints) { Prefs.set("gesture_hints", Prefs.gestureHints, it) }
        ToggleRow("Open call screen on ring", "Android may still block this", direct) { Prefs.set("direct_launch", Prefs.directLaunch, it) }

        Spacer(Modifier.height(8.dp))
        ExpandRow(Icons.Rounded.Build, c.inkDim, "Troubleshooting", null, troubleOpen) { troubleOpen = !troubleOpen }
        if (troubleOpen) {
            LinkRow("Setup guide", "Walk through the permissions again") { Prefs.set("onboarded", Prefs.onboarded, false) }
            ToggleRow("Diagnostic logging", "No numbers or names recorded", diag) { Prefs.set("diagnostics", Prefs.diagnostics, it) }
            LinkRow("Call timing report", null) { vm.showDiag.value = true }
            LinkRow("Background activity", "Allow auto-launch and background activity in App info") { actions.open(SettingsTarget.AppDetails) }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun ExpandRow(icon: ImageVector, tint: Color, title: String, sub: String?, open: Boolean, onClick: () -> Unit) {
    val c = LocalColors.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            if (sub != null) Text(sub, color = c.inkDim, fontSize = 13.sp)
        }
        Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (open) "Collapse" else "Expand", tint = c.inkDim)
    }
}

@Composable
private fun NavRow(icon: ImageVector, title: String, sub: String?, onClick: () -> Unit) {
    val c = LocalColors.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(c.surface), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = c.accent, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            if (sub != null) Text(sub, color = c.inkDim, fontSize = 13.sp)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = c.inkDim)
    }
}

//private fun Modifier = this

// ---------- diagnostics ----------

@Composable
fun DiagnosticsScreen(vm: DialerViewModel, actions: Actions) {
    val c = LocalColors.current
    val events by Diag.events.collectAsState()
    val ids = events.map { it.callId }.filter { it != 0 }.distinct().takeLast(5).reversed()
    Overlay(onBack = { vm.showDiag.value = false }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Text("Call timing", style = T.title, color = c.ink)
            Spacer(Modifier.height(8.dp))
            Text("From Telecom handing over the call to the first frame. Excludes modem, ringtone and display time.", color = c.inkDim, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextPill("Share report", { actions.share(Diag.report(events)) }, filled = true)
                TextPill("Clear", { Diag.clear() }, danger = true)
            }
            if (ids.isEmpty()) Text("\nNo calls recorded. Turn on logging, then take a call.", color = c.inkDim)
            ids.forEach { id ->
                Spacer(Modifier.height(20.dp))
                Text("Call #$id", color = c.ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Diag.intervals(id, events).forEach { iv ->
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Row {
                            Text(iv.label, color = c.ink, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Text(iv.ms?.let { "$it ms" } ?: "n/a", color = if (iv.ms == null) c.inkDim else c.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                        if (iv.note.isNotEmpty()) Text(iv.note, color = c.inkDim, fontSize = 11.sp)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Text("RAW EVENTS (latest 60)", style = T.label, color = c.inkDim)
            Spacer(Modifier.height(6.dp))
            val t0 = events.firstOrNull()?.t ?: 0L
            events.takeLast(60).forEach {
                Text("+${it.t - t0} ${it.name} #${it.callId} ${it.detail}", color = c.inkDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}