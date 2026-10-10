package dev.mizzenmast.dialer

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** What the app has been allowed to do. Read fresh whenever [DialerViewModel.tick] changes (resume, permission results, role result). */
@Immutable
class Setup(
    val isDefault: Boolean, val contacts: Boolean, val callLog: Boolean, val phone: Boolean,
    val notif: Boolean, val fsi: Boolean, val overlay: Boolean, val battery: Boolean
) {
    /** The parts the app can't do its job without. Overlay and battery are optional. */
    val essentials: Boolean get() = isDefault && contacts && callLog && phone && notif && fsi
}

fun readSetup(ctx: Context) = Setup(
    isDefault = roleHeld(ctx),
    contacts = ctx.granted(Manifest.permission.READ_CONTACTS),
    callLog = ctx.granted(Manifest.permission.READ_CALL_LOG) && ctx.granted(Manifest.permission.WRITE_CALL_LOG),
    phone = ctx.granted(Manifest.permission.CALL_PHONE),
    notif = Build.VERSION.SDK_INT < 33 || ctx.granted(Manifest.permission.POST_NOTIFICATIONS),
    fsi = Build.VERSION.SDK_INT < 34 || ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent(),
    overlay = Settings.canDrawOverlays(ctx),
    battery = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
)

private class Req(val title: String, val sub: String?, val ok: Boolean, val action: String? = null, val onClick: () -> Unit = {})

/**
 * One onboarding page. When [needed], the primary button runs [action] instead of moving on; [skip] always remains available.
 * [fallback] replaces the action after one attempt that didn't help (Android stops showing a permission dialog once it was denied twice).
 */
private class Page(
    val icon: ImageVector, val title: String, val body: String, val reqs: List<Req> = emptyList(),
    val needed: Boolean = false, val action: String? = null, val onAction: () -> Unit = {},
    val fallback: String? = null, val onFallback: () -> Unit = {}
)

@Composable
fun OnboardingScreen(vm: DialerViewModel, actions: Actions, onDone: () -> Unit) {
    val c = LocalColors.current
    val ctx = LocalContext.current
    val tick by vm.tick.collectAsState()
    val s = remember(tick) { readSetup(ctx) }
    var step by rememberSaveable { mutableIntStateOf(0) }
    var tried by rememberSaveable(step) { mutableStateOf(false) }

    val pages = listOf(
        Page(Icons.Rounded.Phone, "Welcome", "Let's get your phone app ready. It takes about a minute, and you can change all of it later in Settings. Everything stays on your phone."),
        Page(
            Icons.Rounded.Call, "Make it your phone app",
            "Android only sends incoming calls to the default phone app. Without this, calls won't show up here.",
            reqs = listOf(Req("Default phone app", if (s.isDefault) null else "Required for calls", s.isDefault)),
            needed = !s.isDefault, action = "Set as default", onAction = actions.requestRole
        ),
        Page(
            Icons.Rounded.Contacts, "See your people",
            "Contacts give you names and photos, call history fills Recents, and phone access lets you place calls. Making this the default app usually grants all three.",
            reqs = listOf(
                Req("Contacts", if (s.contacts) null else "Names and photos", s.contacts),
                Req("Call history", if (s.callLog) null else "Recent calls", s.callLog),
                Req("Phone", if (s.phone) null else "Place calls", s.phone)
            ),
            needed = !(s.contacts && s.callLog && s.phone), action = "Allow access", onAction = actions.requestPeople,
            fallback = "Open app settings", onFallback = { actions.open(SettingsTarget.AppDetails) }
        ),
        Page(
            Icons.Rounded.Notifications, "Never miss a call",
            "Notifications and full-screen alerts let a call take over the lock screen. The last two are optional, but help on phones that close background apps aggressively.",
            reqs = buildList {
                add(Req("Notifications", if (s.notif) null else "Call alerts", s.notif, "Allow", actions.requestNotifications))
                if (Build.VERSION.SDK_INT >= 34) add(Req("Full-screen alerts", if (s.fsi) null else "Show calls over the lock screen", s.fsi, "Open") { actions.open(SettingsTarget.FullScreen) })
                add(Req("Display over other apps", if (s.overlay) null else "Optional", s.overlay, "Open") { actions.open(SettingsTarget.Overlay) })
                add(Req("Unrestricted battery", if (s.battery) null else "Optional", s.battery, "Open") { actions.open(SettingsTarget.Battery) })
            }
        )
    )
    val page = pages[step.coerceIn(0, pages.lastIndex)]
    val last = step >= pages.lastIndex
    val blocked = page.needed && page.action != null
    fun next() { if (last) onDone() else step++ }

    BackHandler(enabled = step > 0) { step-- }

    Column(Modifier.fillMaxSize().background(c.bg).systemBarsPadding()) {
        AnimatedContent(
            targetState = step,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            transitionSpec = {
                val dir = if (targetState > initialState) 1 else -1
                (slideInHorizontally { it / 4 * dir } + fadeIn()) togetherWith (slideOutHorizontally { -it / 4 * dir } + fadeOut())
            },
            label = "onboarding"
        ) { i ->
            val p = pages[i.coerceIn(0, pages.lastIndex)]
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp)) {
                Spacer(Modifier.height(56.dp))
                Box(Modifier.size(96.dp).clip(CircleShape).background(c.accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                    Icon(p.icon, null, tint = c.accent, modifier = Modifier.size(44.dp))
                }
                Spacer(Modifier.height(28.dp))
                Text(p.title, style = T.title, color = c.ink)
                Spacer(Modifier.height(10.dp))
                Text(p.body, color = c.inkDim, fontSize = 16.sp, lineHeight = 24.sp)
                if (p.reqs.isNotEmpty()) Spacer(Modifier.height(20.dp))
                p.reqs.forEach { r -> StatusRow(r.title, r.sub, r.ok, r.action, r.onClick) }
                Spacer(Modifier.height(24.dp))
            }
        }

        Dots(count = pages.size, selected = step)
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                if (blocked) Text(
                    "Skip", color = c.inkDim, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).clickable(role = Role.Button) { next() }
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }
            val label = when {
                blocked && tried && page.fallback != null -> page.fallback
                blocked -> page.action
                last -> "Finish"
                step == 0 -> "Get started"
                else -> "Continue"
            }
            TextPill(label, {
                when {
                    blocked && tried && page.fallback != null -> page.onFallback()
                    blocked -> { tried = true; page.onAction() }
                    else -> next()
                }
            }, filled = true)
        }
    }
}

@Composable
private fun Dots(count: Int, selected: Int) {
    val c = LocalColors.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { i ->
            val w by animateDpAsState(if (i == selected) 22.dp else 8.dp, label = "dot")
            Box(Modifier.padding(horizontal = 3.dp).height(8.dp).width(w).clip(CircleShape).background(if (i == selected) c.accent else c.surfaceHigh))
        }
    }
}
