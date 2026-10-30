package dev.mizzenmast.dialer

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Settings
import android.telecom.TelecomManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri

class MainActivity : ComponentActivity() {
    companion object { const val EXTRA_TAB = "tab"; const val EXTRA_OPEN_DIALER = "open_dialer"; const val EXTRA_CALL = "call_number" }

    private val vm: DialerViewModel by viewModels()
    private var pendingCall: String? = null
    private var pendingStar: Pair<Contact, Boolean>? = null

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        vm.onPermissionsChanged()
        val p = pendingCall
        pendingCall = null
        if (p != null) {
            if (r[Manifest.permission.CALL_PHONE] == true || checkSelfPermission(Manifest.permission.CALL_PHONE) == android.content.pm.PackageManager.PERMISSION_GRANTED) place(p)
            else Notices.show("Phone permission needed")
        }
        pendingStar?.let { (ct, on) ->
            pendingStar = null
            if (checkSelfPermission(Manifest.permission.WRITE_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED) toggleStar(ct, on)
            else Notices.show("Contacts permission needed")
        }
    }
    private val roleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.onPermissionsChanged() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Core.init(this)
        enableEdgeToEdge()
        handleIntent(intent)
        val actions = Actions(
            requestContacts = { askOrSettings(arrayOf(Manifest.permission.READ_CONTACTS)) },
            requestCallLog = { askOrSettings(arrayOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.WRITE_CALL_LOG)) },
            requestNotifications = {
                if (Build.VERSION.SDK_INT >= 33) askOrSettings(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) else open(SettingsTarget.Notifications)
            },
            requestPhone = { askOrSettings(PHONE_PERMS) },
            requestPeople = {
                askOrSettings(arrayOf(
                    Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALL_LOG, Manifest.permission.WRITE_CALL_LOG, *PHONE_PERMS
                ))
            },
            requestRole = ::requestRole,
            open = ::open,
            call = ::place,
            callWith = ::placeCall,
            message = { n -> try { startActivity(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", n, null))) } catch (e: Exception) { Diag.fail("open_sms", e); Notices.show("No messaging app found") } },
            whatsapp = ::whatsapp,
            openContact = ::openContact,
            share = ::share,
            addContact = ::addContact,
            toggleStar = ::toggleStar,
            editBeforeCall = { vm.dial.value = it; vm.dialerOpen.value = true }
        )
        setContent {
            val mode by Prefs.theme.collectAsState()
            DialerTheme(mode) { App(vm, actions) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        vm.setActive(true)
    }

    override fun onStop() {
        vm.setActive(false)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        CallNotifications.clearMissed(this)
        vm.onPermissionsChanged() // also refreshes permission/role status rows
    }

    private fun handleIntent(i: Intent?) {
        i ?: return
        if (i.hasExtra(EXTRA_TAB)) vm.selectTab(i.getIntExtra(EXTRA_TAB, 0).coerceIn(0, 2))
        if (i.getBooleanExtra(EXTRA_OPEN_DIALER, false)) vm.dialerOpen.value = true
        i.getStringExtra(EXTRA_CALL)?.let { i.removeExtra(EXTRA_CALL); place(it); CallNotifications.clearMissed(this) }
        val data = i.data
        if ((i.action == Intent.ACTION_DIAL || i.action == Intent.ACTION_VIEW) && data?.scheme == "tel") {
            vm.dial.value = Uri.decode(data.schemeSpecificPart.orEmpty())
            vm.dialerOpen.value = true
        }
    }

    /** Request runtime permissions. If Android no longer shows the dialog (denied repeatedly), the Settings screen offers "App info". */
    private fun askOrSettings(perms: Array<String>) {
        val missing = perms.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) { vm.onPermissionsChanged(); return }
        permLauncher.launch(missing.toTypedArray())
    }

    /** Placing calls: real TelecomManager.placeCall, only after CALL_PHONE is granted. */
    private fun place(number: String) {
        val n = number.trim()
        if (n.isEmpty()) { Notices.show("Enter a number first"); return }
        if (!granted(Manifest.permission.CALL_PHONE)) {
            pendingCall = n
            permLauncher.launch(PHONE_PERMS.filter { !granted(it) }.toTypedArray())
            return
        }
        if (!granted(Manifest.permission.READ_PHONE_STATE)) {
            Notices.show("Allow phone access in Settings to choose a SIM")
        } else {
            val sims = SimLabels.accounts(this)
            val preferred = Prefs.simFor(n)?.let { k -> sims.firstOrNull { SimLabels.key(it) == k } }
            if (preferred != null) { placeCall(n, preferred); return }
            if (sims.size > 1 && !SimLabels.hasDefaultAccount(this)) {
                vm.simPicker.value = n to sims.map { h -> SimOption(h, SimLabels.label(this, h) ?: "SIM") }
                return
            }
        }
        placeCall(n, null)
    }

    private fun placeCall(n: String, handle: android.telecom.PhoneAccountHandle?) {
        try {
            val extras = Bundle().apply { if (handle != null) putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle) }
            getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", n, null), extras)
            Diag.log("place_call_requested")
        } catch (e: Exception) {
            Diag.fail("place_call", e)
            Notices.show("Couldn't place call")
        }
    }

    private fun requestRole() {
        if (roleHeld(this)) { Notices.show("Already the default"); return }
        val rm = getSystemService(RoleManager::class.java)
        if (!rm.isRoleAvailable(RoleManager.ROLE_DIALER)) { Notices.show("Not available on this device"); return }
        roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER))
    }

    // Fine for a sideloaded app; Play restricts it (then use ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS and drop the permission).
    @android.annotation.SuppressLint("BatteryLife")
    private fun open(t: SettingsTarget) {
        val pkg = "package:$packageName".toUri()
        val i = when (t) {
            SettingsTarget.AppDetails -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
            SettingsTarget.Notifications -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            SettingsTarget.FullScreen ->
                if (Build.VERSION.SDK_INT >= 34) Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg)
                else Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            SettingsTarget.Overlay -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg)
            SettingsTarget.Battery -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)
            SettingsTarget.Sound -> Intent(Settings.ACTION_SOUND_SETTINGS)
            SettingsTarget.CallingAccounts -> Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS)
            SettingsTarget.Blocked -> getSystemService(TelecomManager::class.java).createManageBlockedNumbersIntent()
        }
        try { startActivity(i) } catch (e: Exception) {
            Diag.fail("open_settings", e)
            try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)) } catch (e2: Exception) { Diag.fail("open_app_settings", e2); Notices.show("Couldn't open settings") }
        }
    }

    private fun whatsapp(number: String) = openWhatsApp(this, number)

    private fun openContact(c: Contact) {
        val uri = ContactsContract.Contacts.getLookupUri(c.id, c.lookupKey)
        try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (e: Exception) { Diag.fail("open_contact", e); Notices.show("No contacts app found") }
    }

    private fun addContact(number: String) {
        val i = Intent(Intent.ACTION_INSERT_OR_EDIT).setType(ContactsContract.Contacts.CONTENT_ITEM_TYPE)
            .putExtra(ContactsContract.Intents.Insert.PHONE, number)
        try { startActivity(i) } catch (e: Exception) { Diag.fail("add_contact", e); Notices.show("No contacts app found") }
    }

    private fun toggleStar(c: Contact, starred: Boolean) {
        if (checkSelfPermission(Manifest.permission.WRITE_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingStar = c to starred
            permLauncher.launch(arrayOf(Manifest.permission.WRITE_CONTACTS))
            return
        }
        vm.setStarred(c.id, starred) { Notices.show("Couldn't update favorite") }
    }

    private fun share(text: String) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share diagnostics"))
    }
}

/** The app is only usable once everything essential is granted; revoking one later brings setup back. */
@Composable
fun App(vm: DialerViewModel, actions: Actions) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val tick by vm.tick.collectAsState()
    val onboarded by Prefs.onboarded.collectAsState()
    val essentials = remember(tick) { readSetup(ctx).essentials }
    // Setup stays up until the walkthrough is finished (so the optional pages after the last permission still show),
    // and comes back on its own if an essential is revoked later.
    val ready = essentials && onboarded
    Box(Modifier.fillMaxSize()) {
        if (ready) MainShell(vm, actions) else OnboardingScreen(vm, actions)
        NoticeHost(
            if (ready) Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 88.dp)
            else Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 12.dp)
        )
    }
}

private const val TAB_COUNT = 3
private const val TAB_SETTINGS = 2

/**
 * The three bottom tabs live in a horizontal pager (swipe or tap). Detail screens (call info, history, contact) are overlays
 * above the pager; any tab change closes them, so the bottom bar always works.
 */
@Composable
private fun MainShell(vm: DialerViewModel, actions: Actions) {
    val c = LocalColors.current
    val tab by vm.tab.collectAsState()
    val detailLog by vm.detailLog.collectAsState()
    val historyLog by vm.historyLog.collectAsState()
    val detailContact by vm.detailContact.collectAsState()
    val index by vm.index.collectAsState()
    val dialerOpen by vm.dialerOpen.collectAsState()
    val overlayOpen = detailLog != null || historyLog != null || detailContact != null
    val selectionActive by vm.selectionActive.collectAsState()

    val pager = rememberPagerState(initialPage = tab.coerceIn(0, TAB_COUNT - 1)) { TAB_COUNT }
    val motion = rememberMotionEnabled()
    // vm.tab -> pager (bottom-bar taps, intents). settledPage -> vm.tab (swipes). Both sides are no-ops once they agree.
    LaunchedEffect(tab) {
        if (pager.settledPage != tab) { if (motion) pager.animateScrollToPage(tab) else pager.scrollToPage(tab) }
    }
    LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.collect { vm.tab.value = it } }
    // Back from Contacts / Settings returns to Recents first. Overlays register their own handlers later, so they win.
    BackHandler(enabled = !overlayOpen && pager.currentPage != 0) { vm.selectTab(0) }

    Column(Modifier.fillMaxSize().background(c.bg)) {
        // Replaces statusBarsPadding(): the strip takes the contextual bar's color while selecting (Recents tab or call history),
        // so status bar and bar read as one surface instead of three bands.
        val tinted = selectionActive && (historyLog != null || pager.targetPage == 0)
        val statusTint by animateColorAsState(if (tinted) c.surface else c.bg, label = "statusTint")
        Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(statusTint))
        Box(Modifier.weight(1f)) {
            HorizontalPager(
                state = pager, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = TAB_COUNT - 1,
                userScrollEnabled = !overlayOpen, key = { it }
            ) { page ->
                when (page) {
                    0 -> RecentsScreen(vm, actions)
                    1 -> ContactsScreen(vm, actions)
                    else -> SettingsScreen(vm, actions)
                }
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = pager.targetPage != TAB_SETTINGS && !overlayOpen,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 20.dp),
                enter = fadeIn() + scaleIn(initialScale = 0.8f), exit = fadeOut() + scaleOut(targetScale = 0.8f)
            ) { DialerFab { vm.dialerOpen.value = true } }

            detailLog?.let { CallDetailScreen(vm, actions, it, index.find(it.number)) }
            historyLog?.let { CallHistoryScreen(vm, it, index.find(it.number)) }
            detailContact?.let { ContactDetailScreen(vm, actions, it) }
        }
        BottomBar(pager.targetPage) { vm.selectTab(it) }
    }
    if (dialerOpen) DialerSheet(vm, actions) { vm.dialerOpen.value = false }
    SimPickerSheet(vm, actions)
}
