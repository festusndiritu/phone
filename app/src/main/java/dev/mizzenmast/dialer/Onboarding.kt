package dev.mizzenmast.dialer

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/** What the app has been allowed to do. Read fresh whenever [DialerViewModel.tick] changes (resume, permission results, role result). */
@Immutable
class Setup(
    val isDefault: Boolean, val contacts: Boolean, val callLog: Boolean, val phone: Boolean,
    val notif: Boolean, val fsi: Boolean, val overlay: Boolean, val battery: Boolean
) {
    /** The parts the app can't do its job without (being the phone app, placing calls, call alerts); until they are all granted only setup is shown.
     *  Contacts, call history and full-screen alerts are asked for here but can be skipped: Contacts and Recents offer "Allow access" when opened. Overlay and battery live in Settings. */
    val essentials: Boolean get() = isDefault && phone && notif
}

fun readSetup(ctx: Context) = Setup(
    isDefault = roleHeld(ctx),
    contacts = ctx.granted(Manifest.permission.READ_CONTACTS),
    callLog = ctx.granted(Manifest.permission.READ_CALL_LOG) && ctx.granted(Manifest.permission.WRITE_CALL_LOG),
    phone = PHONE_PERMS.all { ctx.granted(it) },
    notif = Build.VERSION.SDK_INT < 33 || ctx.granted(Manifest.permission.POST_NOTIFICATIONS),
    fsi = Build.VERSION.SDK_INT < 34 || ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent(),
    overlay = Settings.canDrawOverlays(ctx),
    battery = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
)
private enum class Step { Welcome, Overview, Default, Contacts, History, Notifications, FullScreen, Battery, Appearance, Done }

/** Pages the app can't be useful without: no "Not now" on these. Everything else can be skipped and granted later, when it's needed. */
private val Step.essential get() = this == Step.Default || this == Step.Notifications

/** True while this step still needs something from the user. Only the essentials count; the rest is optional. */
private fun Step.pending(s: Setup) = when (this) {
    Step.Default -> !(s.isDefault && s.phone)
    Step.Notifications -> !s.notif
    else -> false
}

/**
 * One permission page. When the thing is already [granted] the primary button just continues; otherwise it runs [action],
 * and [fallback] replaces it after one attempt that didn't help (Android stops showing a permission dialog once it was denied twice).
 */
private class Spec(
    val art: @Composable () -> Unit, val title: String, val body: String,
    val whyIcon: ImageVector, val why: String, val granted: Boolean,
    val action: String, val onAction: () -> Unit,
    val fallback: String? = null, val onFallback: () -> Unit = {}
)

private fun specFor(st: Step, s: Setup, a: Actions): Spec = when (st) {
    Step.Default -> Spec(
        { PhoneArt() }, "Make this your default phone app.",
        "Android only sends incoming calls to the default phone app. Without this, calls won't show up here.",
        Icons.Rounded.Info, "This lets the app handle incoming and outgoing calls, so you get the full experience.",
        s.isDefault && s.phone, "Set as default", { if (!s.isDefault) a.requestRole() else a.requestPhone() },
        "Open app settings", { a.open(SettingsTarget.AppDetails) }
    )
    Step.Contacts -> Spec(
        { ContactsArt() }, "See your contacts.",
        "Get quick access to your contacts, see names and photos, and make calls with ease.",
        Icons.Rounded.Person, "It helps you identify who's calling and makes it easier to call your friends and family.",
        s.contacts, "Allow access", a.requestContacts,
        "Open app settings", { a.open(SettingsTarget.AppDetails) }
    )
    Step.History -> Spec(
        { HistoryArt() }, "See your call history.",
        "Access your recent calls so you can quickly call back, see who contacted you and keep track of important calls.",
        Icons.Rounded.History, "It helps you stay organized and never miss an important call.",
        s.callLog, "Allow access", a.requestCallLog,
        "Open app settings", { a.open(SettingsTarget.AppDetails) }
    )
    Step.Notifications -> Spec(
        { NotificationArt() }, "Get call notifications.",
        "Receive alerts for incoming calls, even when the app is in the background.",
        Icons.Rounded.Notifications, "You'll never miss an important call, even if your phone is locked or you're using another app.",
        s.notif, "Allow notifications", a.requestNotifications,
        "Open notification settings", { a.open(SettingsTarget.Notifications) }
    )
    Step.FullScreen -> Spec(
        { LockScreenArt() }, "Show calls over the lock screen.",
        "Let incoming calls appear over your lock screen so you can answer quickly, even when your phone is locked.",
        Icons.Rounded.Shield, "It helps you respond faster and avoid missing calls.",
        s.fsi, "Allow full-screen access", { a.open(SettingsTarget.FullScreen) }
    )
    else -> Spec(
        { ShieldArt() }, "Keep calls running in the background.",
        "Allow the app to ignore battery optimizations so calls and notifications aren't interrupted.",
        Icons.Rounded.BatteryChargingFull, "It ensures the app works reliably, even when you're not actively using it.",
        s.battery, "Allow optimisation exemption", { a.open(SettingsTarget.Battery) }
    )
}

/**
 * Two screens of intro and then one hub: the welcome splash, and the setup list. Every item on the list opens its own page and
 * comes back to the list, in any order; nothing is sequential. The list's Continue goes on once the essentials are in.
 */
@Composable
fun OnboardingScreen(vm: DialerViewModel, actions: Actions) {
    val c = LocalColors.current
    val ctx = LocalContext.current
    val tick by vm.tick.collectAsState()
    val s = remember(tick) { readSetup(ctx) }
    // Someone who finished setup earlier and later lost an essential lands on the list, with no splash to go back to.
    val returning = remember { Prefs.onboarded.value }
    var current by rememberSaveable { mutableIntStateOf(if (returning) Step.Overview.ordinal else Step.Welcome.ordinal) }
    var tried by rememberSaveable(current) { mutableStateOf(false) }
    val step = Step.entries[current]
    val firstPending = listOf(Step.Default, Step.Notifications).firstOrNull { it.pending(s) }

    fun go(t: Step) { current = t.ordinal }
    fun back() {
        when (step) {
            Step.Welcome -> {}
            Step.Overview -> if (!returning) go(Step.Welcome)
            else -> go(Step.Overview)
        }
    }
    /** Continue / Skip on the list: whatever essential is still missing comes first, otherwise the finish page. */
    fun proceed() { go(firstPending ?: Step.Done) }

    BackHandler(enabled = step != Step.Welcome && !(returning && step == Step.Overview)) { back() }

    Box(Modifier.fillMaxSize().background(c.bg)) {
        AnimatedContent(
            targetState = current,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                val dir = if (targetState > initialState) 1 else -1
                (slideInHorizontally { it / 4 * dir } + fadeIn()) togetherWith (slideOutHorizontally { -it / 4 * dir } + fadeOut())
            },
            label = "onboarding"
        ) { i ->
            when (val st = Step.entries[i]) {
                Step.Welcome -> WelcomeScreen(onStart = { go(Step.Overview) })

                Step.Overview -> {
                    val theme by Prefs.theme.collectAsState()
                    val rows = buildList {
                        add(CheckRow(Step.Default, Icons.Rounded.Call, "Default phone app", "Make this your calling app", s.isDefault && s.phone))
                        add(CheckRow(Step.Contacts, Icons.Rounded.Contacts, "Contacts", "See names and photos", s.contacts))
                        add(CheckRow(Step.History, Icons.Rounded.History, "Call history", "Show recent calls", s.callLog))
                        add(CheckRow(Step.Notifications, Icons.Rounded.Notifications, "Notifications", "Receive incoming call alerts", s.notif))
                        if (Build.VERSION.SDK_INT >= 34) add(CheckRow(Step.FullScreen, Icons.Rounded.Shield, "Full-screen alerts", "Show calls over lock screen", s.fsi))
                        add(CheckRow(Step.Battery, Icons.Rounded.BatteryChargingFull, "Background running", "Keep calls from being interrupted", s.battery))
                        add(CheckRow(Step.Appearance, Icons.Rounded.Palette, "Appearance", "System, light or dark", null, value = listOf("System", "Light", "Dark")[theme.coerceIn(0, 2)]))
                    }
                    SetupPage(
                        top = { OverviewTop(done = rows.mapNotNull { it.ok }, onBack = if (returning) null else ({ back() }), onSkip = { proceed() }) },
                        primary = "Continue", primaryIcon = Icons.Rounded.ChevronRight, onPrimary = { proceed() }
                    ) {
                        Spacer(Modifier.height(24.dp))
                        Text("Let's set up your phone app.", style = T.title, color = c.ink)
                        Spacer(Modifier.height(10.dp))
                        Text("A few quick steps to make sure calls, contacts and notifications work as they should.", color = c.inkDim, fontSize = 16.sp, lineHeight = 24.sp)
                        Spacer(Modifier.height(20.dp))
                        ChecklistCard(rows, readyLabel = "Ready", pendingLabel = "Pending", onOpen = { go(it.step) })
                        Spacer(Modifier.height(16.dp))
                    }
                }

                Step.Appearance -> {
                    val theme by Prefs.theme.collectAsState()
                    SetupPage(
                        top = { BackTop(::back) },
                        primary = "Continue", primaryIcon = Icons.Rounded.ChevronRight, onPrimary = { go(Step.Overview) },
                        background = { Hills(Modifier.align(Alignment.BottomCenter)) }
                    ) {
                        Spacer(Modifier.height(24.dp))
                        Text("Appearance", style = T.title, color = c.ink)
                        Spacer(Modifier.height(10.dp))
                        Text("Choose how the app looks on your device.", color = c.inkDim, fontSize = 16.sp, lineHeight = 24.sp)
                        Spacer(Modifier.height(24.dp))
                        val options = listOf(
                            Triple("System", "Follow your device settings", 0),
                            Triple("Light", "Always use light theme", 1),
                            Triple("Dark", "Always use dark theme", 2)
                        )
                        val shape = RoundedCornerShape(16.dp)
                        Column(Modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.line, shape)) {
                            options.forEachIndexed { idx, (name, sub, value) ->
                                if (idx > 0) HorizontalDivider(color = c.line)
                                Row(
                                    Modifier.fillMaxWidth().selectable(theme == value, role = Role.RadioButton) { Prefs.setTheme(value) }
                                        .padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = theme == value, onClick = null,
                                        colors = RadioButtonDefaults.colors(selectedColor = c.accent, unselectedColor = c.inkDim)
                                    )
                                    Spacer(Modifier.width(14.dp))
                                    Column {
                                        Text(name, color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                                        Text(sub, color = c.inkDim, fontSize = 13.sp)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                }

                Step.Done -> {
                    val ready = s.essentials
                    SetupPage(
                        top = { BackTop(::back) },
                        primary = if (ready) "Continue to dialer" else "Finish setup",
                        onPrimary = { if (ready) Prefs.setOnboarded(true) else proceed() },
                        secondary = "Open settings", onSecondary = { actions.open(SettingsTarget.AppDetails) }
                    ) {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Spacer(Modifier.height(4.dp))
                            ArtBox(150.dp) { DoneArt() }
                            Spacer(Modifier.height(8.dp))
                            Text(if (ready) "You're all set!" else "Almost there.", style = T.title, color = c.ink, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(10.dp))
                            Text(
                                if (ready) "Your phone app is ready. You can now make calls, see your contacts and stay connected \u2014 all in one place."
                                else "A few things are still needed before calls can work. Finish setup takes you to the first one.",
                                color = c.inkDim, fontSize = 16.sp, lineHeight = 24.sp, textAlign = TextAlign.Center
                            )
                        }
                        Spacer(Modifier.height(20.dp))
                        Text("What you've set up", color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
                        SummaryCard(buildList {
                            add("Default phone app" to (s.isDefault && s.phone))
                            add("Contacts" to s.contacts)
                            add("Call history" to s.callLog)
                            add("Notifications" to s.notif)
                            if (Build.VERSION.SDK_INT >= 34) add("Full-screen alerts" to s.fsi)
                        })
                        Spacer(Modifier.height(16.dp))
                    }
                }

                else -> {
                    val spec = specFor(st, s, actions)
                    val showFallback = tried && !spec.granted && spec.fallback != null
                    SetupPage(
                        top = { BackTop(::back) },
                        primary = when { spec.granted -> "Continue"; showFallback -> spec.fallback else -> spec.action },
                        primaryIcon = if (spec.granted) Icons.Rounded.ChevronRight else null,
                        onPrimary = {
                            when {
                                spec.granted -> go(Step.Overview)
                                showFallback -> spec.onFallback()
                                else -> { tried = true; spec.onAction() }
                            }
                        },
                        secondary = if (spec.granted || st.essential) null else "Not now", onSecondary = { go(Step.Overview) }
                    ) {
                        Spacer(Modifier.height(8.dp))
                        ArtBox { spec.art() }
                        Spacer(Modifier.height(20.dp))
                        Text(spec.title, style = T.title, color = c.ink)
                        Spacer(Modifier.height(10.dp))
                        Text(spec.body, color = c.inkDim, fontSize = 16.sp, lineHeight = 24.sp)
                        Spacer(Modifier.height(20.dp))
                        if (spec.granted) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 14.dp)) {
                                Icon(Icons.Rounded.CheckCircle, null, tint = c.answer, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Allowed", color = c.answer, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        WhyCard(spec.whyIcon, spec.why)
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}

// ---- Layout pieces -------------------------------------------------------------------------------------------------

/** Top bar, scrolling content, and the pinned actions. Every page except the welcome screen is built on this. */
@Composable
private fun SetupPage(
    top: @Composable () -> Unit,
    primary: String, onPrimary: () -> Unit,
    secondary: String? = null, onSecondary: () -> Unit = {},
    primaryIcon: ImageVector? = null,
    background: (@Composable BoxScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        background?.invoke(this)
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            top()
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp), content = content)
            Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 12.dp)) {
                PrimaryButton(primary, onPrimary, primaryIcon)
                if (secondary != null) {
                    Spacer(Modifier.height(4.dp))
                    QuietButton(secondary, onSecondary)
                }
            }
        }
    }
}

@Composable
private fun BackTop(onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconAction(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack)
    }
}

/** Back and Skip, with one progress segment per listed item that fills in as it is set up. */
@Composable
private fun OverviewTop(done: List<Boolean>, onBack: (() -> Unit)?, onSkip: () -> Unit) {
    val c = LocalColors.current
    Column {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 12.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconAction(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack)
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onSkip).padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center
            ) { Text("Skip", color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            done.forEach { ok -> Box(Modifier.weight(1f).height(4.dp).clip(CircleShape).background(if (ok) c.answer else c.surfaceHigh)) }
        }
    }
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit, icon: ImageVector? = null) {
    val c = LocalColors.current
    Row(
        Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(20.dp)).background(c.ink).clickable(role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, color = c.bg, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        if (icon != null) {
            Spacer(Modifier.width(8.dp))
            Icon(icon, null, tint = c.bg, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun QuietButton(text: String, onClick: () -> Unit) {
    val c = LocalColors.current
    Box(
        Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(text, color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium) }
}

@Composable
private fun WhyCard(icon: ImageVector, text: String) {
    val c = LocalColors.current
    val shape = RoundedCornerShape(16.dp)
    Row(Modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.line, shape).padding(16.dp)) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(c.accent.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = c.accent, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Why it matters", color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(text, color = c.inkDim, fontSize = 14.sp, lineHeight = 20.sp)
        }
    }
}

/** [ok] null means the row has no ready/pending state; it shows [value] instead. */
private class CheckRow(val step: Step, val icon: ImageVector, val title: String, val sub: String, val ok: Boolean?, val value: String? = null)

@Composable
private fun ChecklistCard(rows: List<CheckRow>, readyLabel: String, pendingLabel: String, onOpen: (CheckRow) -> Unit) {
    val c = LocalColors.current
    val shape = RoundedCornerShape(16.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.line, shape)) {
        rows.forEachIndexed { idx, r ->
            if (idx > 0) HorizontalDivider(color = c.line)
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Open ${r.title}") { onOpen(r) }.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(if (r.ok == true) c.accent else c.ink), contentAlignment = Alignment.Center) {
                    Icon(r.icon, null, tint = if (r.ok == true) c.onAccent else c.bg, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.title, color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(r.sub, color = c.inkDim, fontSize = 13.sp)
                }
                Spacer(Modifier.width(8.dp))
                if (r.ok != null) {
                    Icon(
                        if (r.ok) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null,
                        tint = if (r.ok) c.answer else c.inkDim, modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(if (r.ok) readyLabel else pendingLabel, color = if (r.ok) c.answer else c.inkDim, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                } else if (r.value != null) {
                    Text(r.value, color = c.inkDim, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
                Icon(Icons.Rounded.ChevronRight, null, tint = c.inkDim, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun SummaryCard(rows: List<Pair<String, Boolean>>) {
    val c = LocalColors.current
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(c.answer.copy(alpha = 0.08f)).border(1.dp, c.answer.copy(alpha = 0.25f), shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        rows.forEach { (name, ok) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null,
                    tint = if (ok) c.answer else c.inkDim, modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(12.dp))
                Text(name, color = if (ok) c.ink else c.inkDim, fontSize = 14.sp)
            }
        }
    }
}

// ---- Welcome -------------------------------------------------------------------------------------------------------

/** Always dark, whatever the app theme is, so the system bar icons are forced light while it is on screen. */
@Composable
private fun WelcomeScreen(onStart: () -> Unit) {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        if (window == null) onDispose { } else {
            val ctl = WindowCompat.getInsetsController(window, view)
            val prevStatus = ctl.isAppearanceLightStatusBars
            val prevNav = ctl.isAppearanceLightNavigationBars
            ctl.isAppearanceLightStatusBars = false
            ctl.isAppearanceLightNavigationBars = false
            onDispose {
                ctl.isAppearanceLightStatusBars = prevStatus
                ctl.isAppearanceLightNavigationBars = prevNav
            }
        }
    }
    val teal = Color(0xFF4FD1B0)
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0C211D), Color(0xFF071512), Color(0xFF040B0A))))) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            val swoosh = Path().apply {
                moveTo(-w * 0.1f, h * 0.92f)
                cubicTo(w * 0.3f, h * 0.62f, w * 0.7f, h * 0.9f, w * 1.1f, h * 0.5f)
                lineTo(w * 1.1f, h); lineTo(-w * 0.1f, h); close()
            }
            drawPath(swoosh, Brush.linearGradient(listOf(Color(0xFF1B6B5A).copy(alpha = 0.6f), Color.Transparent), start = Offset(w, h * 0.5f), end = Offset(w * 0.2f, h)))
            val line = Path().apply {
                moveTo(-w * 0.1f, h * 0.84f)
                cubicTo(w * 0.35f, h * 0.55f, w * 0.65f, h * 0.84f, w * 1.1f, h * 0.42f)
            }
            drawPath(line, teal.copy(alpha = 0.22f), style = Stroke(width = 1.5.dp.toPx()))
        }
        Icon(
            Icons.Rounded.Call, null, tint = Color.White.copy(alpha = 0.92f),
            modifier = Modifier.align(BiasAlignment(0.45f, 0.42f)).size(64.dp).rotate(-12f)
        )
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 28.dp)) {
            Spacer(Modifier.height(32.dp))
            Text("Dialer", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(48.dp))
            Text("Calls,\nwithout\nthe clutter.", color = Color.White, fontSize = 46.sp, lineHeight = 50.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp)
            Spacer(Modifier.height(18.dp))
            Text("A cleaner, faster and more reliable way to stay connected.", color = Color.White.copy(alpha = 0.78f), fontSize = 16.sp, lineHeight = 22.sp, modifier = Modifier.width(280.dp))
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth().padding(bottom = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                Row(
                    Modifier.height(56.dp).clip(RoundedCornerShape(28.dp)).background(Color(0xFFE4EFEA)).clickable(role = Role.Button, onClick = onStart).padding(horizontal = 28.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Get started", color = Color(0xFF0C211D), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.width(10.dp))
                    Icon(Icons.Rounded.ChevronRight, null, tint = Color(0xFF0C211D), modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

// ---- Illustrations (drawn from theme colors so they follow light and dark) ---------------------------------------

@Composable
private fun ArtBox(height: Dp = 176.dp, content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center, content = content)
}

@Composable
private fun Blob(modifier: Modifier = Modifier, width: Dp = 200.dp, height: Dp = 150.dp) {
    val c = LocalColors.current
    Box(modifier.size(width, height).clip(RoundedCornerShape(50)).background(c.accent.copy(alpha = 0.10f)))
}

@Composable
private fun Badge(modifier: Modifier, color: Color, icon: ImageVector, size: Dp = 56.dp) {
    Box(modifier.size(size).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(size / 2))
    }
}

/** Soft layered hills for the Appearance page. Built from theme colors, so they follow whichever theme is picked. */
@Composable
private fun Hills(modifier: Modifier = Modifier) {
    val c = LocalColors.current
    val back = c.accent.copy(alpha = 0.09f)
    val mid = c.accent.copy(alpha = 0.15f)
    val front = c.inkDim.copy(alpha = 0.16f)
    Canvas(modifier.fillMaxWidth().height(240.dp)) {
        val w = size.width; val h = size.height
        fun hill(startY: Float, c1: Offset, c2: Offset, endY: Float) = Path().apply {
            moveTo(0f, h * startY)
            cubicTo(c1.x * w, h * c1.y, c2.x * w, h * c2.y, w, h * endY)
            lineTo(w, h); lineTo(0f, h); close()
        }
        drawPath(hill(0.55f, Offset(0.25f, 0.2f), Offset(0.6f, 0.75f), 0.35f), back)
        drawPath(hill(0.8f, Offset(0.3f, 0.45f), Offset(0.65f, 0.95f), 0.6f), mid)
        drawPath(hill(0.9f, Offset(0.35f, 0.7f), Offset(0.7f, 1.0f), 0.8f), front)
    }
}

@Composable
private fun PhoneArt(height: Dp = 156.dp) {
    val c = LocalColors.current
    val shape = RoundedCornerShape(height / 8.7f)
    Box(contentAlignment = Alignment.Center) {
        Blob(width = height * 1.3f, height = height * 0.96f)
        Box(Modifier.size(height * 0.67f, height).clip(shape).background(c.surface).border(height / 31f, c.ink, shape), contentAlignment = Alignment.Center) {
            Badge(Modifier, c.answer, Icons.Rounded.Call, size = height / 3f)
        }
    }
}

@Composable
private fun SkeletonCard(modifier: Modifier, dots: List<Color>) {
    val c = LocalColors.current
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier.size(160.dp, 112.dp).clip(shape).background(c.surface).border(1.dp, c.line, shape).padding(14.dp),
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        dots.forEach { d ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.dp).clip(CircleShape).background(d))
                Spacer(Modifier.width(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.width(70.dp).height(5.dp).clip(CircleShape).background(c.surfaceHigh))
                    Box(Modifier.width(44.dp).height(5.dp).clip(CircleShape).background(c.line))
                }
            }
        }
    }
}

@Composable
private fun ContactsArt() {
    val c = LocalColors.current
    Box(Modifier.size(220.dp, 176.dp)) {
        Blob(Modifier.align(Alignment.Center))
        SkeletonCard(Modifier.align(Alignment.Center).offset(x = (-10).dp), listOf(c.surfaceHigh, c.surfaceHigh, c.surfaceHigh))
        Badge(Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = 14.dp), c.outgoing, Icons.Rounded.Person)
    }
}

@Composable
private fun HistoryArt() {
    val c = LocalColors.current
    Box(Modifier.size(220.dp, 176.dp)) {
        Blob(Modifier.align(Alignment.Center))
        SkeletonCard(Modifier.align(Alignment.Center).offset(x = 12.dp), listOf(c.answer, c.missed, c.line))
        Badge(Modifier.align(Alignment.TopStart).padding(start = 6.dp, top = 14.dp), c.outgoing, Icons.Rounded.History)
    }
}

@Composable
private fun NotificationArt() {
    val c = LocalColors.current
    Box(Modifier.size(176.dp)) {
        Box(Modifier.fillMaxSize().clip(CircleShape).background(c.accent.copy(alpha = 0.10f)))
        Badge(Modifier.align(Alignment.Center), Color(0xFF5B3FC4), Icons.Rounded.Notifications, size = 88.dp)
        Box(Modifier.align(Alignment.Center).offset(x = 32.dp, y = (-32).dp).size(14.dp).clip(CircleShape).background(c.missed))
    }
}

@Composable
private fun LockScreenArt() {
    val c = LocalColors.current
    Box(Modifier.size(200.dp, 176.dp)) {
        Blob(Modifier.align(Alignment.Center))
        Column(
            Modifier.align(Alignment.Center).size(116.dp, 150.dp).clip(RoundedCornerShape(18.dp)).background(c.ink).padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(14.dp))
            Text("9:30", color = c.bg, fontSize = 26.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(18.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.surface).padding(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Badge(Modifier, c.answer, Icons.Rounded.Call, size = 18.dp)
                Spacer(Modifier.width(6.dp))
                Box(Modifier.weight(1f).height(5.dp).clip(CircleShape).background(c.line))
            }
        }
    }
}

@Composable
private fun ShieldArt() {
    val c = LocalColors.current
    Box(Modifier.size(200.dp, 176.dp)) {
        Blob(Modifier.align(Alignment.Center))
        Icon(Icons.Rounded.Shield, null, tint = c.answer, modifier = Modifier.align(Alignment.Center).size(120.dp))
        Icon(Icons.Rounded.Bolt, null, tint = Color.White, modifier = Modifier.align(Alignment.Center).size(48.dp))
    }
}

@Composable
private fun DoneArt() {
    val c = LocalColors.current
    Box(Modifier.size(200.dp, 150.dp)) {
        Badge(Modifier.align(Alignment.Center), c.answer, Icons.Rounded.Check, size = 96.dp)
        listOf(Triple(-66, -46, 8), Triple(62, -40, 6), Triple(-74, 10, 5), Triple(70, 20, 7), Triple(-30, -66, 5), Triple(34, 62, 5)).forEach { (x, y, d) ->
            Box(Modifier.align(Alignment.Center).offset(x = x.dp, y = y.dp).size(d.dp).clip(CircleShape).background(c.answer.copy(alpha = 0.45f)))
        }
    }
}