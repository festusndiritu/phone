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
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
    companion object { const val EXTRA_TAB = "tab"; const val EXTRA_OPEN_DIALER = "open_dialer" }

    private val vm: DialerViewModel by viewModels()
    private var pendingCall: String? = null
    private var pendingStar: Pair<Contact, Boolean>? = null

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        vm.onPermissionsChanged()
        val p = pendingCall
        pendingCall = null
        if (p != null) {
            if (r[Manifest.permission.CALL_PHONE] == true || checkSelfPermission(Manifest.permission.CALL_PHONE) == android.content.pm.PackageManager.PERMISSION_GRANTED) place(p)
            else toast("Phone permission needed")
        }
        pendingStar?.let { (ct, on) ->
            pendingStar = null
            if (checkSelfPermission(Manifest.permission.WRITE_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED) toggleStar(ct, on)
            else toast("Contacts permission needed")
        }
    }
    private val roleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.onPermissionsChanged() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Core.init(this)
        enableEdgeToEdge()
        // Someone who already set everything up by hand (an install from before onboarding existed) never sees the guide.
        if (!Prefs.onboarded.value && readSetup(this).essentials) Prefs.set("onboarded", Prefs.onboarded, true)
        handleIntent(intent)
        val actions = Actions(
            requestContacts = { askOrSettings(arrayOf(Manifest.permission.READ_CONTACTS)) },
            requestCallLog = { askOrSettings(arrayOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.WRITE_CALL_LOG)) },
            requestNotifications = {
                if (Build.VERSION.SDK_INT >= 33) askOrSettings(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) else open(SettingsTarget.Notifications)
            },
            requestPhone = { askOrSettings(arrayOf(Manifest.permission.CALL_PHONE)) },
            requestPeople = {
                askOrSettings(arrayOf(
                    Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALL_LOG, Manifest.permission.WRITE_CALL_LOG,
                    Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE
                ))
            },
            requestRole = ::requestRole,
            open = ::open,
            call = ::place,
            callWith = ::placeCall,
            message = { n -> try { startActivity(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", n, null))) } catch (e: Exception) { toast("No messaging app found") } },
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

    override fun onResume() {
        super.onResume()
        vm.onPermissionsChanged() // also refreshes permission/role status rows
    }

    private fun handleIntent(i: Intent?) {
        i ?: return
        if (i.hasExtra(EXTRA_TAB)) vm.selectTab(i.getIntExtra(EXTRA_TAB, 0).coerceIn(0, 2))
        if (i.getBooleanExtra(EXTRA_OPEN_DIALER, false)) vm.dialerOpen.value = true
        val data = i.data
        if ((i.action == Intent.ACTION_DIAL || i.action == Intent.ACTION_VIEW) && data?.scheme == "tel") {
            vm.dial.value = Uri.decode(data.schemeSpecificPart.orEmpty())
            vm.dialerOpen.value = true
        }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    /** Request runtime permissions. If Android no longer shows the dialog (denied repeatedly), the Settings screen offers "App info". */
    private fun askOrSettings(perms: Array<String>) {
        val missing = perms.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) { vm.onPermissionsChanged(); return }
        permLauncher.launch(missing.toTypedArray())
    }

    /** Placing calls: real TelecomManager.placeCall, only after CALL_PHONE is granted. */
    private fun place(number: String) {
        val n = number.trim()
        if (n.isEmpty()) { toast("Enter a number first"); return }
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingCall = n
            permLauncher.launch(arrayOf(Manifest.permission.CALL_PHONE))
            return
        }
        val tm = getSystemService(TelecomManager::class.java)
        val sims = simsFor(tm)
        // "Ask every time" (no default calling SIM): choose here, so the system's own "Call with" dialog never appears.
        if (sims.size > 1 && !hasDefaultTelAccount(tm)) { vm.simPicker.value = n to sims; return }
        placeCall(n, null)
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun simsFor(tm: TelecomManager): List<SimOption> = try {
        tm.callCapablePhoneAccounts.map { h -> SimOption(h, try { tm.getPhoneAccount(h)?.label?.toString() } catch (e: Exception) { null } ?: "SIM") }
    } catch (e: Exception) { emptyList() }

    @android.annotation.SuppressLint("MissingPermission")
    private fun hasDefaultTelAccount(tm: TelecomManager): Boolean = try {
        tm.getDefaultOutgoingPhoneAccount("tel") != null
    } catch (e: Exception) { true } // can't tell: fall back to Telecom's own handling

    private fun placeCall(n: String, handle: android.telecom.PhoneAccountHandle?) {
        try {
            val extras = Bundle().apply { if (handle != null) putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle) }
            getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", n, null), extras)
            Diag.log("place_call_requested")
        } catch (e: SecurityException) {
            toast("Couldn't place call")
        } catch (e: Exception) {
            toast("Couldn't place call")
        }
    }

    private fun requestRole() {
        if (roleHeld(this)) { toast("Already the default"); return }
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = getSystemService(RoleManager::class.java)
            if (!rm.isRoleAvailable(RoleManager.ROLE_DIALER)) { toast("Not available on this device"); return }
            roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER))
        } else {
            roleLauncher.launch(Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName))
        }
    }

    // The one-tap battery prompt is fine for a sideloaded app, but Google Play restricts it. If this is ever published on Play,
    // use Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS instead and drop the REQUEST_IGNORE_BATTERY_OPTIMIZATIONS permission.
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
            try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)) } catch (_: Exception) { toast("Couldn't open settings") }
        }
    }

    /** Opens a WhatsApp chat via wa.me. Numbers are normalised to international format using the SIM's country. */
    private fun whatsapp(number: String) {
        val iso = (getSystemService(android.telephony.TelephonyManager::class.java)?.simCountryIso?.takeIf { it.isNotBlank() }
            ?: java.util.Locale.getDefault().country).uppercase()
        val digits = (android.telephony.PhoneNumberUtils.formatNumberToE164(number, iso) ?: number).filter(Char::isDigit)
        if (digits.isEmpty()) { toast("Invalid number"); return }
        val uri = "https://wa.me/$digits".toUri()
        for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
            try { startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(pkg)); return } catch (_: android.content.ActivityNotFoundException) { }
        }
        toast("WhatsApp not installed")
    }

    private fun openContact(c: Contact) {
        val uri = ContactsContract.Contacts.getLookupUri(c.id, c.lookupKey)
        try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (e: Exception) { toast("No contacts app found") }
    }

    private fun addContact(number: String) {
        val i = Intent(Intent.ACTION_INSERT_OR_EDIT).setType(ContactsContract.Contacts.CONTENT_ITEM_TYPE)
            .putExtra(ContactsContract.Intents.Insert.PHONE, number)
        try { startActivity(i) } catch (e: Exception) { toast("No contacts app found") }
    }

    private fun toggleStar(c: Contact, starred: Boolean) {
        if (checkSelfPermission(Manifest.permission.WRITE_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingStar = c to starred
            permLauncher.launch(arrayOf(Manifest.permission.WRITE_CONTACTS))
            return
        }
        vm.setStarred(c.id, starred) { toast("Couldn't update favorite") }
    }

    private fun share(text: String) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share diagnostics"))
    }
}

@Composable
fun App(vm: DialerViewModel, actions: Actions) {
    val onboarded by Prefs.onboarded.collectAsState()
    if (onboarded) MainShell(vm, actions)
    else OnboardingScreen(vm, actions) { Prefs.set("onboarded", Prefs.onboarded, true) }
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
        Box(Modifier.weight(1f).statusBarsPadding()) {
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
            AnimatedVisibility(
                visible = pager.targetPage != TAB_SETTINGS && !overlayOpen,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 20.dp),
                enter = fadeIn() + scaleIn(initialScale = 0.8f), exit = fadeOut() + scaleOut(targetScale = 0.8f)
            ) { DialerFab { vm.dialerOpen.value = true } }
            // Overlays (each handles its own back press)
            detailLog?.let { CallDetailScreen(vm, actions, it, index.find(it.number)) }
            historyLog?.let { CallHistoryScreen(vm, it, index.find(it.number)) }
            detailContact?.let { ContactDetailScreen(vm, actions, it) }
        }
        BottomBar(pager.targetPage) { vm.selectTab(it) }
    }
    if (dialerOpen) DialerSheet(vm, actions) { vm.dialerOpen.value = false }
    SimPickerSheet(vm, actions)
}
