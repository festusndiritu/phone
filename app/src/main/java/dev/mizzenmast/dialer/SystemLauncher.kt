package dev.mizzenmast.dialer

import android.app.role.RoleManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.Settings
import android.telecom.TelecomManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri

/**
 * The hand-offs to other parts of Android: system settings, the default-phone-app role, messaging, contacts and sharing.
 * Each one reports a failure to the person instead of throwing. Create it as an Activity property (the role launcher must be registered early).
 */
class SystemLauncher(private val activity: ComponentActivity, onRoleResult: () -> Unit) {
    private val roleLauncher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { onRoleResult() }

    fun requestRole() {
        if (roleHeld(activity)) { Notices.show("Already the default"); return }
        val rm = activity.getSystemService(RoleManager::class.java)
        if (!rm.isRoleAvailable(RoleManager.ROLE_DIALER)) { Notices.show("Not available on this device"); return }
        roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER))
    }

    // Fine for a sideloaded app; Play restricts it (then use ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS and drop the permission).
    @android.annotation.SuppressLint("BatteryLife")
    fun open(t: SettingsTarget) {
        val pkg = "package:${activity.packageName}".toUri()
        val i = when (t) {
            SettingsTarget.AppDetails -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
            SettingsTarget.Notifications -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
            SettingsTarget.FullScreen ->
                if (Build.VERSION.SDK_INT >= 34) Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg)
                else Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
            SettingsTarget.Battery -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)
            SettingsTarget.Sound -> Intent(Settings.ACTION_SOUND_SETTINGS)
            SettingsTarget.CallingAccounts -> Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS)
            SettingsTarget.Blocked -> activity.getSystemService(TelecomManager::class.java).createManageBlockedNumbersIntent()
        }
        try { activity.startActivity(i) } catch (e: Exception) {
            Diag.fail("open_settings", e)
            try { activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)) } catch (e2: Exception) { Diag.fail("open_app_settings", e2); Notices.show("Couldn't open settings") }
        }
    }

    fun message(number: String) {
        try { activity.startActivity(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null))) }
        catch (e: Exception) { Diag.fail("open_sms", e); Notices.show("No messaging app found") }
    }

    fun whatsapp(number: String) = openWhatsApp(activity, number)

    fun openContact(c: Contact) {
        val uri = ContactsContract.Contacts.getLookupUri(c.id, c.lookupKey)
        // ACTION_EDIT lands straight in the contacts app's editor; ACTION_VIEW only showed the contact and left the edit button to be found.
        val i = Intent(Intent.ACTION_EDIT, uri).putExtra("finishActivityOnSaveCompleted", true)
        try { activity.startActivity(i) } catch (e: Exception) { Diag.fail("edit_contact", e); Notices.show("No contacts app found") }
    }

    fun addContact(number: String) {
        val i = Intent(Intent.ACTION_INSERT_OR_EDIT).setType(ContactsContract.Contacts.CONTENT_ITEM_TYPE)
            .putExtra(ContactsContract.Intents.Insert.PHONE, number)
        try { activity.startActivity(i) } catch (e: Exception) { Diag.fail("add_contact", e); Notices.show("No contacts app found") }
    }

    fun share(text: String) {
        try {
            activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share diagnostics"))
        } catch (e: Exception) { Diag.fail("share", e); Notices.show("Couldn't share") }
    }
}
