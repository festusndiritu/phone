package dev.mizzenmast.dialer

import android.content.Context
import android.telecom.PhoneAccountHandle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class SimRow(val key: String?, val slot: Int?, val title: String, val sub: String?)

/** Slot number and carrier name for each call account. Reads telephony state, so call it off the main thread. */
private fun simRows(ctx: Context, handles: List<PhoneAccountHandle>): List<SimRow> {
    SimLabels.refresh(ctx)
    return handles.mapIndexed { i, h ->
        val slot = SimLabels.slot(ctx, h.componentName.flattenToString(), h.id) ?: (i + 1)
        SimRow(SimLabels.key(h), slot, "SIM $slot", SimLabels.label(ctx, h)?.takeUnless { it.equals("SIM $slot", ignoreCase = true) })
    }
}

/** "Call from" line for the numbers on a screen. Opens the SIM sheet to pick Default or one SIM for them. Only on dual-SIM phones. */
@Composable
internal fun SimPrefRow(numbers: List<String>, subject: String) {
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

@Composable
fun SimPickerSheet(vm: DialerViewModel, actions: Actions) {
    val pending by vm.simPicker.collectAsState()
    val p = pending ?: return
    val ctx = LocalContext.current
    val rows by produceState(emptyList(), p) { value = withContext(Dispatchers.IO) { simRows(ctx, p.second.map { it.handle }) } }
    if (rows.isEmpty()) return
    SimSheet(pretty(p.first) ?: p.first, rows, selected = null, withDefault = false, onPick = { k ->
        val sim = p.second.firstOrNull { SimLabels.key(it.handle) == k } ?: return@SimSheet
        vm.dismissSimPicker()
        actions.callWith(p.first, sim.handle)
    }, onDismiss = { vm.dismissSimPicker() })
}
