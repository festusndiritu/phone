package dev.mizzenmast.dialer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ContactDetailScreen(vm: DialerViewModel, actions: Actions, ct: Contact) {
    val c = LocalColors.current
    val all by vm.log.collectAsState()
    val starred = ct.id in vm.starredIds.collectAsState().value
    val related = remember(all, ct) { contactEntries(all, ct) }
    val first = ct.phones.firstOrNull()?.number
    Overlay(onBack = { vm.closeContactDetail() }) {
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
            if (related.isNotEmpty()) RecentActivity(related) { vm.openHistory(related.first()) }
            Spacer(Modifier.height(32.dp))
        }
    }
}
