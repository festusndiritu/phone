@file:OptIn(ExperimentalFoundationApi::class)

package dev.mizzenmast.dialer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private fun letterOf(name: String): Char {
    val ch = name.trim().firstOrNull() ?: return '#'
    val base = java.text.Normalizer.normalize(ch.toString(), java.text.Normalizer.Form.NFD).firstOrNull() ?: return '#'
    return if (base.isLetter()) base.uppercaseChar() else '#'
}

@Composable
fun ContactsScreen(vm: DialerViewModel, actions: Actions) {
    val c = LocalColors.current
    val list by vm.filtered.collectAsState()
    val q by vm.query.collectAsState()
    val filter by vm.contactFilter.collectAsState()
    val savedIn by vm.savedIn.collectAsState()
    val syncing by vm.syncing.collectAsState()
    // Places only get a menu entry when contacts live in 2+ of them
    val sourceItems = if (savedIn.size > 1) savedIn else emptyList()
    val picked = filter
    val active = if (picked is ContactFilter.Saved && picked.label !in sourceItems) ContactFilter.All else picked
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Contacts", trailing = {
            IconAction(Icons.Rounded.Sync, if (syncing) "Syncing contacts" else "Sync contacts", { vm.syncContacts() }, tint = if (syncing) c.inkDim else c.ink)
        })
        SearchField(
            value = q,
            onChange = { vm.query.value = it },
            trailing = {
                FilterButton(active != ContactFilter.All, "Filter contacts") { dismiss ->
                    val entries = listOf("All" to ContactFilter.All, "Favorites" to ContactFilter.Favorites) +
                            sourceItems.map { it to ContactFilter.Saved(it) }
                    entries.forEach { (label, f) -> PopItem(label, { dismiss(); vm.setContactFilter(f) }, selected = active == f) }
                }
            }
        )
        Spacer(Modifier.height(4.dp))
        when (val s = list) {
            Load.Loading -> ListSkeleton()
            Load.Denied -> StateMessage(Icons.Rounded.Contacts, "See your contacts", null, "Allow access", actions.requestContacts)
            is Load.Failed -> StateMessage(Icons.Rounded.Contacts, "Couldn't load contacts", s.msg, "Try again", actions.requestContacts)
            is Load.Ready -> if (s.data.isEmpty()) {
                when {
                    q.isNotBlank() -> StateMessage(Icons.Rounded.Search, "No matches")
                    active == ContactFilter.Favorites -> StateMessage(Icons.Rounded.Star, "No favorites yet", "Star a contact and it shows up here")
                    active is ContactFilter.Saved -> StateMessage(Icons.Rounded.Contacts, "Nothing saved in ${active.label}")
                    else -> StateMessage(Icons.Rounded.Contacts, "No contacts")
                }
            } else {
                ContactList(s.data, scrollKey = q to active, showRail = q.isBlank()) { vm.detailContact.value = it }
            }
        }
    }
}

/**
 * Quiet search field. The clear button appears as soon as there's text; with [onEmptyClear] it stays visible when empty
 * and closes the search instead. [trailing] adds something after it (a filter button). Search on the keyboard dismisses it.
 */
@Composable
fun SearchField(
    modifier: Modifier = Modifier.padding(horizontal = 20.dp),
    value: String, onChange: (String) -> Unit,
    placeholder: String = "Search contacts", autoFocus: Boolean = false,
    onEmptyClear: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = LocalColors.current
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val motion = rememberMotionEnabled()
    LaunchedEffect(Unit) { if (autoFocus) focus.requestFocus() }
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(c.surfaceHigh).padding(start = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.Search, null, tint = c.inkDim, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = TextStyle(color = c.ink, fontSize = 16.sp), cursorBrush = SolidColor(c.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
            modifier = Modifier.weight(1f).padding(vertical = 14.dp).focusRequester(focus),
            decorationBox = { inner -> Box { if (value.isEmpty()) Text(placeholder, color = c.inkDim, fontSize = 16.sp); inner() } }
        )

        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            androidx.compose.animation.AnimatedVisibility(
                visible = value.isNotEmpty() || onEmptyClear != null,
                enter = if (motion) fadeIn() + scaleIn(initialScale = 0.7f) else EnterTransition.None,
                exit = if (motion) fadeOut() + scaleOut(targetScale = 0.7f) else ExitTransition.None
            ) {
                val label = if (value.isEmpty()) "Close search" else "Clear search"
                Box(
                    Modifier.size(48.dp).clip(CircleShape)
                        .clickable(role = Role.Button, onClickLabel = label) {
                            if (value.isNotEmpty()) { onChange(""); focus.requestFocus() } else onEmptyClear?.invoke()
                        },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Rounded.Close, label, tint = c.inkDim, modifier = Modifier.size(20.dp)) }
            }
        }
        trailing?.invoke()
    }
}

/** The A-Z list with sticky headers and the alphabet rail. Scrolls back to the top whenever [scrollKey] (query + filter) changes. */
@Composable
private fun ContactList(data: List<Contact>, scrollKey: Any, showRail: Boolean, onOpen: (Contact) -> Unit) {
    val c = LocalColors.current
    val motion = rememberMotionEnabled()
    val sections = remember(data) {
        val m = data.groupBy { letterOf(it.name) }
        val keys = m.keys.filter { it != '#' } + (if (m.containsKey('#')) listOf('#') else emptyList())
        keys.map { it to m.getValue(it) }
    }
    val positions = remember(sections) {
        val map = HashMap<Char, Int>(); var run = 0
        sections.forEach { (l, people) -> map[l] = run; run += 1 + people.size }
        map
    }
    val letters = remember(sections) { sections.map { it.first } }
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(scrollKey) { state.scrollToItem(0) }
    // The section the list is currently showing: the last one whose header has reached the top.
    val currentLetter by remember(sections, positions) {
        derivedStateOf {
            val first = state.firstVisibleItemIndex
            var cur = sections.first().first
            for ((l, _) in sections) { if ((positions[l] ?: 0) <= first) cur = l else break }
            cur
        }
    }

    val railOn = showRail && sections.size > 1
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(bottom = 96.dp)) {
            sections.forEach { (letter, people) ->
                stickyHeader(key = "h$letter") {
                    Text(letter.toString(), color = c.accent, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth().background(c.bg).padding(start = 26.dp, end = 20.dp, top = 10.dp, bottom = 4.dp))
                }
                items(people, key = { it.id }) { ct ->
                    // One rounded card per contact: avatar, name over a phone line, a star for favorites, a chevron.
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = if (railOn) 38.dp else 16.dp, top = 4.dp, bottom = 4.dp)
                            .clip(RoundedCornerShape(22.dp)).background(c.surface)
                            .clickable { onOpen(ct) }.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Avatar(ct.name, ct.photo, 52.dp, green = true)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(ct.name, color = c.ink, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(3.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Call, null, tint = c.inkDim, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(8.dp))
                                Text((pretty(ct.phones.first().number) ?: ct.phones.first().number) + (if (ct.phones.size > 1) "  +${ct.phones.size - 1}" else ""),
                                    color = c.inkDim, fontSize = 14.sp, maxLines = 1)
                            }
                        }
                        if (ct.starred) {
                            Icon(Icons.Rounded.Star, "Favorite", tint = c.accent, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(12.dp))
                        }
                        Icon(Icons.Rounded.ChevronRight, null, tint = c.inkDim)
                    }
                }
            }
        }
        if (railOn) {
            AlphabetRail(letters, currentLetter, Modifier.align(Alignment.CenterEnd)) { l, animate ->
                positions[l]?.let { p -> scope.launch { if (animate && motion) state.animateScrollToItem(p) else state.scrollToItem(p) } }
            }
        }
    }
}

private val BubbleSize = 56.dp

/** Fast-scroll rail. [onJump] gets the letter and whether to animate: the first jump of a touch glides, drag jumps are instant. */
@Composable
private fun AlphabetRail(letters: List<Char>, current: Char, modifier: Modifier = Modifier, onJump: (Char, Boolean) -> Unit) {
    val c = LocalColors.current
    val tick = rememberTick()
    val motion = rememberMotionEnabled()
    val density = LocalDensity.current
    var h by remember { mutableIntStateOf(1) }
    var touched by remember { mutableStateOf<Char?>(null) }
    var bubbleLetter by remember { mutableStateOf(current) }
    val shown = touched ?: current

    val bubblePx = with(density) { BubbleSize.toPx() }
    val cell = h.toFloat() / letters.size.coerceAtLeast(1)
    val idx = letters.indexOf(bubbleLetter).coerceAtLeast(0)
    val bubbleY by animateFloatAsState(
        ((idx + 0.5f) * cell - bubblePx / 2f).coerceIn(0f, (h - bubblePx).coerceAtLeast(0f)),
        animationSpec = if (motion) tween(90) else snap(), label = "bubbleY"
    )
    val bubbleX = with(density) { (BubbleSize + 12.dp).roundToPx() }

    Box(
        modifier.fillMaxHeight().width(32.dp).padding(vertical = 12.dp).onSizeChanged { h = it.height.coerceAtLeast(1) }
            .clearAndSetSemantics { contentDescription = "Alphabet index" }
            .pointerInput(letters) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var first = true
                    fun pickAt(y: Float) {
                        val l = letters[((y / h) * letters.size).toInt().coerceIn(0, letters.size - 1)]
                        if (l != touched) {
                            touched = l; bubbleLetter = l
                            tick()
                            onJump(l, first)
                            first = false
                        }
                    }
                    down.consume()
                    pickAt(down.position.y)
                    do {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                        if (change != null && change.pressed) { pickAt(change.position.y); change.consume() }
                    } while (change != null && change.pressed)
                    touched = null
                }
            }
    ) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            letters.forEach { l -> RailLetter(l, active = l == shown, motion = motion, modifier = Modifier.weight(1f).fillMaxWidth()) }
        }
        AnimatedVisibility(
            visible = touched != null,
            modifier = Modifier.align(Alignment.TopStart).offset { IntOffset(-bubbleX, bubbleY.roundToInt()) },
            enter = if (motion) fadeIn(tween(80)) + scaleIn(initialScale = 0.6f, transformOrigin = TransformOrigin(1f, 0.5f)) else EnterTransition.None,
            exit = if (motion) fadeOut(tween(160)) + scaleOut(targetScale = 0.6f, transformOrigin = TransformOrigin(1f, 0.5f)) else ExitTransition.None
        ) {
            Box(
                Modifier.size(BubbleSize).clip(RoundedCornerShape(topStart = 28.dp, topEnd = 6.dp, bottomEnd = 6.dp, bottomStart = 28.dp)).background(c.accent),
                contentAlignment = Alignment.Center
            ) { Text(bubbleLetter.toString(), color = c.onAccent, fontSize = 28.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

/** One letter. The active one grows an accent disc behind it; everything animates between states (instantly with system animations off). */
@Composable
private fun RailLetter(letter: Char, active: Boolean, motion: Boolean, modifier: Modifier = Modifier) {
    val c = LocalColors.current
    val t by animateFloatAsState(if (active) 1f else 0f, animationSpec = if (motion) tween(120) else snap(), label = "railLetter")
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(
            Modifier.heightIn(max = 20.dp).fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true)
                .graphicsLayer { scaleX = 0.6f + 0.4f * t; scaleY = 0.6f + 0.4f * t; alpha = t }
                .clip(CircleShape).background(c.accent)
        )
        Text(
            letter.toString(), color = lerp(c.inkDim, c.onAccent, t), fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.graphicsLayer { scaleX = 1f + 0.1f * t; scaleY = 1f + 0.1f * t }
        )
    }
}
