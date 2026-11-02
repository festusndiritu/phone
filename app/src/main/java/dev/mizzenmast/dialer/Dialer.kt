package dev.mizzenmast.dialer

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.awaitCancellation

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
            vm.setDial("")
            onDismiss()
        })
    }
}

private fun keepDialChars(t: String) = t.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }

/** The number being typed. Edits are cleaned to dial characters and mirrored into the ViewModel; a complete secret code is sent instead of kept. */
@Stable
private class DialField(
    private val vm: DialerViewModel,
    private val tick: () -> Unit,
    private val onSecretCode: (String) -> Unit
) {
    // A real TextFieldValue (not a String) so the field keeps its cursor and selection for native select, copy and paste.
    var value by mutableStateOf(TextFieldValue(vm.dial.value, TextRange(vm.dial.value.length)))
        private set
    val text get() = value.text

    fun sync(external: String) {
        if (external != value.text) value = TextFieldValue(external, TextRange(external.length))
    }

    fun set(v: TextFieldValue) {
        val clean = keepDialChars(v.text)
        if (SpecialCodes.secretCode(clean) != null) {
            onSecretCode(clean)
            clear()
            return
        }
        val start = keepDialChars(v.text.take(v.selection.start)).length
        val end = keepDialChars(v.text.take(v.selection.end)).length
        value = TextFieldValue(clean, TextRange(start, end))
        vm.setDial(clean)
        tick()
    }

    fun fill(number: String) = set(TextFieldValue(number, TextRange(number.length)))

    fun clear() {
        value = TextFieldValue("")
        vm.setDial("")
    }

    fun insert(ch: Char) {
        val sel = value.selection
        set(TextFieldValue(text.replaceRange(sel.min, sel.max, ch.toString()), TextRange(sel.min + 1)))
    }

    fun backspace() {
        val sel = value.selection
        if (!sel.collapsed) set(TextFieldValue(text.removeRange(sel.min, sel.max), TextRange(sel.min)))
        else if (sel.start > 0) set(TextFieldValue(text.removeRange(sel.start - 1, sel.start), TextRange(sel.start - 1)))
    }
}

@Composable
private fun DialpadContent(vm: DialerViewModel, actions: Actions, onCall: (String) -> Unit) {
    val suggestions by vm.suggestions.collectAsState()
    val tones by Prefs.keypadTones.collectAsState()
    val dialState by vm.dial.collectAsState()
    val lastDialed by vm.lastDialed.collectAsState()
    val index by vm.index.collectAsState()
    val tick = rememberTick()
    val toneGenerator = rememberToneGenerator()
    val field = remember { DialField(vm, tick, actions.call) }
    LaunchedEffect(dialState) { field.sync(dialState) }
    val number = field.text
    val key = rememberKeySize()

    Column(Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        SuggestionRow(suggestions, onCall)
        Spacer(Modifier.height(8.dp))
        NumberField(field)
        Spacer(Modifier.height(6.dp))
        Keypad(key, onKey = { ch ->
            field.insert(ch)
            if (tones) toneGenerator?.startTone(toneFor(ch), 120)
        }, onLongZero = { field.insert('+') }, spread = true)
        Spacer(Modifier.height(24.dp))
        DialActions(
            key = key,
            number = number,
            canSave = number.count { it.isDigit() } >= 3 && index.find(number) == null,
            canFillLast = lastDialed != null,
            onSave = { actions.addContact(number) },
            onCall = { if (number.isNotBlank()) onCall(number) else lastDialed?.let(field::fill) }
        )
    }
}

@Composable
private fun rememberToneGenerator(): ToneGenerator? {
    val gen = remember { try { ToneGenerator(AudioManager.STREAM_DTMF, 70) } catch (e: Exception) { Diag.fail("tone_generator", e); null } }
    DisposableEffect(gen) { onDispose { gen?.release() } }
    return gen
}

/** Sized from the screen, not from the (unbounded) sheet content constraints. */
@Composable
private fun rememberKeySize(): Dp {
    val screenH = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    return ((screenH - 360.dp) / 5f).coerceIn(52.dp, 68.dp)
}

/** Saved contacts matching the digits typed so far; it only exists once there are matches, and the sheet is anchored at the bottom so the keypad doesn't move. */
@Composable
private fun SuggestionRow(suggestions: List<Contact>, onCall: (String) -> Unit) {
    val c = LocalColors.current
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
}

/** The system keyboard is suppressed; the field exists for the cursor, selection handles and the Copy/Paste popup. */
@Composable
private fun NumberField(field: DialField) {
    val c = LocalColors.current
    val number = field.text
    InterceptPlatformTextInput(interceptor = { _, _ -> awaitCancellation() }) {
        val size = when { number.length <= 10 -> 40.sp; number.length <= 14 -> 32.sp; else -> 24.sp }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(48.dp))
            BasicTextField(
                value = field.value,
                onValueChange = field::set,
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
                        onClick = field::backspace, onLongClick = field::clear
                    ), contentAlignment = Alignment.Center
                ) { Icon(Icons.AutoMirrored.Rounded.Backspace, "Backspace (long press to clear)", tint = c.inkDim) }
            }
        }
    }
}

/** Save the typed number (when it isn't a contact yet) and the call button. With nothing typed, the call button pastes the last number dialed. */
@Composable
private fun DialActions(key: Dp, number: String, canSave: Boolean, canFillLast: Boolean, onSave: () -> Unit, onCall: () -> Unit) {
    val c = LocalColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(30.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(key), contentAlignment = Alignment.Center) {
            if (canSave) Column(
                Modifier.wrapContentWidth(unbounded = true).clip(RoundedCornerShape(14.dp))
                    .clickable(role = Role.Button, onClickLabel = "Save number", onClick = onSave).padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Rounded.PersonAdd, null, tint = c.inkDim, modifier = Modifier.size(24.dp))
                Text("Add contact", color = c.inkDim, fontSize = 11.sp, maxLines = 1, softWrap = false)
            }
        }
        RoundButton(
            Icons.Rounded.Call, if (number.isBlank()) "Fill last dialed number" else "Call", c.accent, c.onAccent, key,
            enabled = number.isNotBlank() || canFillLast, onClick = onCall
        )
        Spacer(Modifier.size(key)) // keeps the call button under the middle column
    }
}
