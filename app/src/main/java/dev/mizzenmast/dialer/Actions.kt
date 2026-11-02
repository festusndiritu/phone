package dev.mizzenmast.dialer

import android.telecom.PhoneAccountHandle

enum class SettingsTarget { AppDetails, Notifications, FullScreen, Battery, Sound, CallingAccounts, Blocked }

class Actions(
    val requestContacts: () -> Unit,
    val requestCallLog: () -> Unit,
    val requestNotifications: () -> Unit,
    val requestPhone: () -> Unit,
    val requestRole: () -> Unit,
    val open: (SettingsTarget) -> Unit,
    val call: (String) -> Unit,
    val callWith: (String, PhoneAccountHandle) -> Unit,
    val openContact: (Contact) -> Unit,
    val message: (String) -> Unit,
    val whatsapp: (String) -> Unit,
    val share: (String) -> Unit,
    val addContact: (String) -> Unit,
    /** Contact + the new starred state. */
    val toggleStar: (Contact, Boolean) -> Unit,
    /** Puts the number in the dialer sheet without calling. */
    val editBeforeCall: (String) -> Unit,
    /** Deleting call-log rows needs the write permission, which is only asked for when the person does this. */
    val deleteLogs: (List<Long>) -> Unit,
    val clearLog: () -> Unit
)
