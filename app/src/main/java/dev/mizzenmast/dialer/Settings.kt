package dev.mizzenmast.dialer

import android.Manifest
import android.app.NotificationManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

private val ThemeNames = listOf("System", "Light", "Dark")

@Composable
fun SettingsScreen(vm: DialerViewModel, actions: Actions) {
    val ctx = LocalContext.current
    val tick by vm.tick.collectAsState()
    val theme by Prefs.theme.collectAsState()
    val haptics by Prefs.haptics.collectAsState()
    val tones by Prefs.keypadTones.collectAsState()
    val hints by Prefs.gestureHints.collectAsState()
    val direct by Prefs.directLaunch.collectAsState()
    val diag by Prefs.diagnostics.collectAsState()
    val silence by Prefs.silenceUnknown.collectAsState()
    val showFavorites by Prefs.showFavorites.collectAsState()

    var troubleOpen by rememberSaveable { mutableStateOf(false) }
    var themeDialog by remember { mutableStateOf(false) }
    val isDefault = remember(tick) { roleHeld(ctx) }
    val contacts = remember(tick) { ctx.granted(Manifest.permission.READ_CONTACTS) }
    val callLog = remember(tick) { ctx.granted(Manifest.permission.READ_CALL_LOG) && ctx.granted(Manifest.permission.WRITE_CALL_LOG) }
    val phone = remember(tick) { ctx.granted(Manifest.permission.CALL_PHONE) }
    val notif = remember(tick) { Build.VERSION.SDK_INT < 33 || ctx.granted(Manifest.permission.POST_NOTIFICATIONS) }
    val fsi = remember(tick) { Build.VERSION.SDK_INT < 34 || ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent() }
    val overlay = remember(tick) { Settings.canDrawOverlays(ctx) }
    val battery = remember(tick) { ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenTitle("Settings", "Personalize your calling experience")
        Spacer(Modifier.height(8.dp))
        PhoneSetupCard(isDefault, contacts, callLog, phone, notif, fsi, overlay, actions)

        SettingsSection("Calling", "SIMs, sound and call management") {
            NavRow(Icons.Rounded.SimCard, "SIM & calling accounts", "Choose how outgoing calls are placed") { actions.open(SettingsTarget.CallingAccounts) }
            NavRow(Icons.AutoMirrored.Rounded.VolumeUp, "Sound & vibration", "Ringtone, alerts and call sounds") { actions.open(SettingsTarget.Sound) }
            NavRow(Icons.Rounded.Block, "Blocked numbers", "Manage blocked callers") { actions.open(SettingsTarget.Blocked) }
        }

        SettingsSection("Incoming calls", "Control how calls appear and behave") {
            ToggleRow(Icons.Rounded.OpenInFull, "Call screen", "Opens automatically while the phone is awake and unlocked", direct) {
                Prefs.set("direct_launch", Prefs.directLaunch, it)
            }
            ToggleRow(Icons.Rounded.Person, "Unknown callers", "Silence callers who aren't saved. They show as missed calls", silence) { on ->
                Prefs.set("silence_unknown", Prefs.silenceUnknown, on)
                // The screening service stays disabled while this is off, so Telecom never binds it.
                ctx.packageManager.setComponentEnabledSetting(
                    android.content.ComponentName(ctx, UnknownCallerScreeningService::class.java),
                    if (on) android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED else android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    android.content.pm.PackageManager.DONT_KILL_APP
                )
            }
            NavRow(Icons.Rounded.Notifications, "Call notifications", "Alerts and notification behavior") { actions.open(SettingsTarget.Notifications) }
        }

        SettingsSection("Appearance & interaction", "Make it feel like home") {
            NavRow(Icons.Rounded.Palette, "Theme", "System, light or dark", value = ThemeNames[theme.coerceIn(0, 2)]) { themeDialog = true }
            ToggleRow(Icons.Rounded.Star, "Favorites in Recents", "Show starred contacts at the top of Recents", showFavorites) {
                Prefs.set("show_favorites_row", Prefs.showFavorites, it)
            }
            ToggleRow(Icons.Rounded.Vibration, "Haptic feedback", null, haptics) { Prefs.set("haptics", Prefs.haptics, it) }
            ToggleRow(Icons.Rounded.MusicNote, "Keypad tones", null, tones) { Prefs.set("keypad_tones", Prefs.keypadTones, it) }
            ToggleRow(Icons.Rounded.TouchApp, "Incoming-call swipe hints", null, hints) { Prefs.set("gesture_hints", Prefs.gestureHints, it) }
        }

        SettingsSection("Reliability", "Keep your dialer running smoothly") {
            if (battery) {
                StaticRow(Icons.Rounded.BatteryChargingFull, "Battery optimization", "Unrestricted, so calls can show on screen") { StatusPill("Ready", ready = true) }
            } else {
                ActionRow(Icons.Rounded.BatteryChargingFull, "Battery optimization", "Allow unrestricted use so calls show when power saving is on",
                    onClick = { actions.open(SettingsTarget.Battery) }) { StatusPill("Allow", ready = false) }
            }
            NavRow(Icons.Rounded.PowerSettingsNew, "Auto-start & background", "Allow auto-start if calls ring but the screen never opens") { actions.open(SettingsTarget.AppDetails) }
        }

        SettingsSection("About & diagnostics", "Support and app information") {
            ExpandRow(Icons.Rounded.Build, "Troubleshooting", "Setup guide, diagnostic logs and support", troubleOpen) { troubleOpen = !troubleOpen }
            Reveal(troubleOpen) {
                Column {
                    NavRow(null, "Setup guide", "Walk through the permissions again", chevron = false) { Prefs.set("onboarded", Prefs.onboarded, false) }
                    ToggleRow(null, "Keep a log", "Crashes and call events. No numbers or names recorded", diag) { Prefs.set("diagnostics", Prefs.diagnostics, it) }
                    NavRow(null, "Share log", "Send the log to whoever is debugging", chevron = false) { actions.share(Diag.report(Diag.events.value)) }
                    NavRow(null, "Clear log", null, chevron = false) { Diag.clear() }
                }
            }
            StaticRow(Icons.Rounded.Info, "About", "Version ${Diag.version}") { }
        }
        Spacer(Modifier.height(32.dp))
    }
    if (themeDialog) ThemeDialog(theme) { themeDialog = false }
}

// ---------- structure ----------

/** Title + one-line description, then the rows. No dividers: the spacing between sections does the separating. */
@Composable
private fun SettingsSection(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalColors.current
    Column(Modifier.fillMaxWidth().padding(top = 28.dp)) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 6.dp).semantics(mergeDescendants = true) { heading() }) {
            Text(title, color = c.ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = c.inkDim, fontSize = 13.sp)
        }
        content()
    }
}

/** Height-animated reveal; plain show/hide when the user turned system animations off. */
@Composable
private fun Reveal(visible: Boolean, content: @Composable () -> Unit) {
    val motion = rememberMotionEnabled()
    AnimatedVisibility(
        visible,
        enter = if (motion) fadeIn() + expandVertically() else EnterTransition.None,
        exit = if (motion) fadeOut() + shrinkVertically() else ExitTransition.None
    ) { content() }
}

// ---------- rows ----------

/** Icon tile, title, optional description, then whatever [trailing] draws. [icon] null indents a child row under its parent's text. */
@Composable
private fun RowShell(icon: ImageVector?, title: String, subtitle: String?, modifier: Modifier, trailing: @Composable () -> Unit) {
    val c = LocalColors.current
    Row(
        modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(c.surface), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = c.ink, modifier = Modifier.size(22.dp))
            }
        } else Spacer(Modifier.width(44.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            if (subtitle != null) Text(subtitle, color = c.inkDim, fontSize = 13.sp, modifier = Modifier.padding(top = 1.dp))
        }
        trailing()
    }
}

/** Opens something: chevron on the right, with an optional current value in front of it. */
@Composable
private fun NavRow(icon: ImageVector?, title: String, subtitle: String?, value: String? = null, chevron: Boolean = true, onClick: () -> Unit) {
    val c = LocalColors.current
    RowShell(icon, title, subtitle, Modifier.clickable(role = Role.Button, onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(12.dp))
            if (value != null) {
                Text(value, color = c.inkDim, fontSize = 14.sp)
                Spacer(Modifier.width(4.dp))
            }
            if (chevron) Icon(Icons.Rounded.ChevronRight, null, tint = c.inkDim)
        }
    }
}

/** The whole row is the switch: one touch target, one accessibility node. */
@Composable
private fun ToggleRow(icon: ImageVector?, title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    RowShell(icon, title, subtitle, Modifier.toggleable(checked, role = Role.Switch, onValueChange = onChange)) {
        Row {
            Spacer(Modifier.width(12.dp))
            Toggle(checked, null)
        }
    }
}

/** Expands inline; the chevron turns to match. */
@Composable
private fun ExpandRow(icon: ImageVector, title: String, subtitle: String?, expanded: Boolean, onClick: () -> Unit) {
    val c = LocalColors.current
    val turn by animateFloatAsState(if (expanded) 180f else 0f, label = "expand")
    RowShell(
        icon, title, subtitle,
        Modifier.clickable(role = Role.Button, onClick = onClick).semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
    ) {
        Row {
            Spacer(Modifier.width(12.dp))
            Icon(Icons.Rounded.ExpandMore, null, tint = c.inkDim, modifier = Modifier.graphicsLayer { rotationZ = turn })
        }
    }
}

/** Not interactive. */
@Composable
private fun StaticRow(icon: ImageVector, title: String, subtitle: String?, trailing: @Composable () -> Unit) {
    RowShell(icon, title, subtitle, Modifier.semantics(mergeDescendants = true) { }) {
        Row {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

/** The whole row does the action that its trailing pill advertises. */
@Composable
private fun ActionRow(icon: ImageVector, title: String, subtitle: String?, onClick: () -> Unit, trailing: @Composable () -> Unit) {
    RowShell(icon, title, subtitle, Modifier.clickable(role = Role.Button, onClick = onClick)) {
        Row {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

/** "Ready" is a quiet tint; anything that still needs doing is a filled, tappable pill. */
@Composable
private fun StatusPill(text: String, ready: Boolean, onClick: (() -> Unit)? = null) {
    val c = LocalColors.current
    @Composable
    fun Pill() {
        Box(
            Modifier.clip(RoundedCornerShape(14.dp)).background(if (ready) c.accent.copy(alpha = 0.16f) else c.accent).padding(horizontal = 12.dp, vertical = 5.dp)
        ) {
            Text(text, color = if (ready) c.accent else c.onAccent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
    if (onClick == null) Pill()
    else Box(Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 4.dp), contentAlignment = Alignment.Center) { Pill() }
}

// ---------- phone setup ----------

/**
 * One card for everything the dialer needs. Collapsed it says whether setup is done; the two summary rows are
 * always visible, and tapping the header (or "Review") opens the individual permissions.
 */
@Composable
private fun PhoneSetupCard(
    isDefault: Boolean, contacts: Boolean, callLog: Boolean, phone: Boolean, notif: Boolean, fsi: Boolean, overlay: Boolean,
    actions: Actions
) {
    val c = LocalColors.current
    val permsOk = contacts && callLog && phone && notif && fsi
    val ready = isDefault && permsOk
    val issues = listOf(isDefault, contacts, callLog, phone, notif, fsi).count { !it }
    var expanded by rememberSaveable { mutableStateOf(!ready) }
    val turn by animateFloatAsState(if (expanded) 180f else 0f, label = "setupChevron")
    val tone = if (ready) c.accent else c.hold
    val shape = RoundedCornerShape(24.dp)
    val toggleLabel = if (expanded) "Hide details" else "Show details"

    Column(
        Modifier.padding(horizontal = 20.dp, vertical = 8.dp).fillMaxWidth().clip(shape)
            .background(tone.copy(alpha = 0.10f)).border(1.dp, tone.copy(alpha = 0.28f), shape)
    ) {
        Row(
            Modifier.fillMaxWidth()
                .clickable(role = Role.Button, onClickLabel = toggleLabel) { expanded = !expanded }
                .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(tone), contentAlignment = Alignment.Center) {
                Icon(if (ready) Icons.Rounded.Check else Icons.Rounded.Warning, null, tint = if (ready) c.onAccent else c.bg, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Phone setup", color = c.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (ready) "Your dialer is ready to make and receive calls." else if (issues == 1) "1 thing needs your attention" else "$issues things need your attention",
                    color = c.inkDim, fontSize = 13.sp
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Rounded.ExpandMore, null, tint = c.inkDim, modifier = Modifier.graphicsLayer { rotationZ = turn })
        }
        Column(
            Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp).fillMaxWidth()
                .clip(RoundedCornerShape(18.dp)).background(c.bg.copy(alpha = 0.55f)).padding(vertical = 4.dp)
        ) {
            SetupSummaryRow(
                Icons.Rounded.PhoneAndroid, "Default phone app",
                if (isDefault) "Set as your primary dialer" else "Required to make and receive calls",
                isDefault, "Set", actions.requestRole
            )
            SetupSummaryRow(Icons.Rounded.Security, "Required permissions", "Contacts, calls and notifications", permsOk, "Review") { expanded = true }
            Reveal(expanded) {
                Column(Modifier.padding(bottom = 8.dp)) {
                    SetupDetailRow("Contacts", "Names and photos", contacts, "Allow", actions.requestContacts)
                    SetupDetailRow("Call history", "Recent calls", callLog, "Allow", actions.requestCallLog)
                    SetupDetailRow("Phone", "Place calls", phone, "Allow", actions.requestPhone)
                    SetupDetailRow("Notifications", "Call alerts", notif, "Allow", actions.requestNotifications)
                    if (Build.VERSION.SDK_INT >= 34) SetupDetailRow("Full-screen alerts", "Show calls over the lock screen", fsi, "Open") { actions.open(SettingsTarget.FullScreen) }
                    SetupDetailRow("Display over other apps", "Optional", overlay, "Open") { actions.open(SettingsTarget.Overlay) }
                }
            }
        }
    }
}

@Composable
private fun SetupSummaryRow(icon: ImageVector, title: String, subtitle: String, ok: Boolean, action: String, onAction: () -> Unit) {
    val c = LocalColors.current
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(c.surface), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = c.ink, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = c.inkDim, fontSize = 12.sp)
        }
        Spacer(Modifier.width(8.dp))
        if (ok) StatusPill("Ready", ready = true) else StatusPill(action, ready = false, onClick = onAction)
    }
}

/** A single permission under the summary. The hint only shows while it still needs action. */
@Composable
private fun SetupDetailRow(title: String, hint: String, ok: Boolean, action: String, onAction: () -> Unit) {
    val c = LocalColors.current
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 66.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = c.ink, fontSize = 14.sp)
            if (!ok) Text(hint, color = c.inkDim, fontSize = 12.sp)
        }
        Spacer(Modifier.width(8.dp))
        if (ok) StatusPill("Ready", ready = true) else StatusPill(action, ready = false, onClick = onAction)
    }
}

// ---------- theme ----------

@Composable
private fun ThemeDialog(current: Int, onDismiss: () -> Unit) {
    val c = LocalColors.current
    val options = listOf("Match your phone's setting", "Always light", "Always dark")
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.clip(RoundedCornerShape(24.dp)).background(c.surface).padding(vertical = 12.dp)) {
            Text("Theme", color = c.ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 8.dp))
            ThemeNames.forEachIndexed { i, name ->
                val on = i == current
                Row(
                    Modifier.fillMaxWidth().selectable(on, role = Role.RadioButton) { Prefs.setTheme(i); onDismiss() }.padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(if (on) Icons.Rounded.RadioButtonChecked else Icons.Rounded.RadioButtonUnchecked, null, tint = if (on) c.accent else c.inkDim)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(name, color = c.ink, fontSize = 16.sp)
                        Text(options[i], color = c.inkDim, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
