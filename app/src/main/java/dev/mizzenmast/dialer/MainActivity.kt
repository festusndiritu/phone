package dev.mizzenmast.dialer

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

/** Wires Android to the rest of the app and keeps no state: intents go through [DialerIntents], permissions through [PermissionRequester], calls through [CallPlacer]. */
class MainActivity : ComponentActivity() {
    private val vm: DialerViewModel by viewModels()

    // Registered while the Activity is constructed (before it starts), as the result launchers require.
    private val system = SystemLauncher(this) { vm.onPermissionsChanged() }
    private val permissions = PermissionRequester(
        this,
        queue = { vm.permissionQueue },
        onChanged = { vm.onPermissionsChanged() },
        perform = ::perform
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Core.init(this)
        enableEdgeToEdge()
        // A recreated Activity (rotation, process death) is handed the intent it first started with; that intent was already acted on.
        if (savedInstanceState == null) handleIntent(intent)
        val actions = Actions(
            requestContacts = { permissions.request(Feature.Contacts) },
            requestCallLog = { permissions.request(Feature.CallLog) },
            requestNotifications = {
                if (Build.VERSION.SDK_INT >= 33) permissions.request(Feature.Notifications) else system.open(SettingsTarget.Notifications)
            },
            requestPhone = { permissions.request(Feature.Phone) },
            requestRole = system::requestRole,
            open = system::open,
            call = ::place,
            callWith = ::placeVia,
            message = system::message,
            whatsapp = system::whatsapp,
            openContact = system::openContact,
            share = system::share,
            addContact = system::addContact,
            toggleStar = ::toggleStar,
            editBeforeCall = { vm.openDialer(it) },
            deleteLogs = { permissions.request(Feature.EditCallLog, PendingAction.DeleteLogs(it)) },
            clearLog = { permissions.request(Feature.EditCallLog, PendingAction.ClearLog) }
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

    override fun onResume() {
        super.onResume()
        vm.setForeground(true)
        CallNotifications.dismissIfIdle(this)
        vm.onPermissionsChanged()
    }

    override fun onPause() {
        vm.setForeground(false)
        super.onPause()
    }

    override fun onStop() {
        vm.setActive(false)
        super.onStop()
    }

    private fun handleIntent(i: Intent) {
        for (launch in DialerIntents.parse(i)) when (launch) {
            is Launch.ShowTab -> vm.selectTab(launch.index)
            Launch.OpenDialer -> vm.openDialer()
            is Launch.Prefill -> vm.openDialer(launch.number)
            is Launch.CallBack -> {
                CallNotifications.clearMissedFor(this, launch.number)
                place(launch.number)
            }
        }
        DialerIntents.consume(i)
    }

    /** What was waiting for a permission, now that it has been granted. */
    private fun perform(action: PendingAction) {
        when (action) {
            is PendingAction.Call -> place(action.number)
            is PendingAction.Star -> vm.setStarred(action.contactId, action.starred) { Notices.show("Couldn't update favorite") }
            is PendingAction.DeleteLogs -> vm.deleteLogs(action.ids) { if (it == 0) Notices.show("Couldn't delete calls") }
            PendingAction.ClearLog -> vm.clearLog { if (!it) Notices.show("Couldn't clear call log") }
        }
    }

    private fun place(number: String) {
        if (SpecialCodes.dispatch(this, number)) return
        when (val plan = CallPlacer.plan(this, number)) {
            is CallPlan.Invalid -> Notices.show(plan.message)
            CallPlan.NeedsPermission -> permissions.request(Feature.Phone, PendingAction.Call(number.trim()))
            is CallPlan.ChooseSim -> vm.showSimPicker(plan.number, plan.options)
            is CallPlan.Dial -> {
                plan.note?.let { Notices.show(it) }
                placeVia(number.trim(), plan.handle)
            }
        }
    }

    private fun placeVia(number: String, handle: android.telecom.PhoneAccountHandle?) {
        when (val result = CallPlacer.submit(this, number, handle)) {
            CallSubmit.Sent -> {}
            is CallSubmit.Failed -> {
                Notices.show(result.message)
                if (result.permissionLost) vm.onPermissionsChanged() // brings setup back if an essential really is gone
            }
        }
    }

    private fun toggleStar(c: Contact, starred: Boolean) =
        permissions.request(Feature.EditContacts, PendingAction.Star(c.id, starred))
}
