package dev.mizzenmast.dialer

import android.Manifest
import android.app.Application
import android.provider.CallLog
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

/** Filters for Contacts: by where the contact is saved. The choice persists. */
sealed interface ContactFilter {
    data object All : ContactFilter
    data object Favorites : ContactFilter
    /** Contacts stored in one place: "SIM 1", "SIM 2", "Phone", a Google account, WhatsApp... */
    data class Saved(val label: String) : ContactFilter

    /** The form kept in [Prefs.contactFilter]. */
    val key: String get() = when (this) { All -> "all"; Favorites -> "fav"; is Saved -> "src:$label" }

    companion object {
        fun parse(k: String): ContactFilter = when {
            k == "fav" -> Favorites
            k.startsWith("src:") -> Saved(k.removePrefix("src:"))
            else -> All
        }
    }
}

/** One ViewModel for the main app UI. In-call state lives in CallManager (process-wide, owned by the InCallService). */
class DialerViewModel(app: Application, saved: SavedStateHandle) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()
    private companion object { const val FIRST_PAGE = 60 }

    private val _tab = MutableStateFlow<Int>(0)
    val tab: StateFlow<Int> = _tab.asStateFlow()
    private val _dial = MutableStateFlow<String>("")
    val dial: StateFlow<String> = _dial.asStateFlow()
    private val _dialerOpen = MutableStateFlow<Boolean>(false)
    val dialerOpen: StateFlow<Boolean> = _dialerOpen.asStateFlow()
    private val _logFilter = MutableStateFlow<Int>(0) // 0 all, 1 missed, 2 incoming, 3 outgoing
    val logFilter: StateFlow<Int> = _logFilter.asStateFlow()
    /** Number waiting for the user to choose a SIM (set only when Android has no default calling SIM). */
    private val _simPicker = MutableStateFlow<Pair<String, List<SimOption>>?>(null)
    val simPicker: StateFlow<Pair<String, List<SimOption>>?> = _simPicker.asStateFlow()
    private val _query = MutableStateFlow<String>("")
    val query: StateFlow<String> = _query.asStateFlow()
    private val _detailLog = MutableStateFlow<LogEntry?>(null)
    val detailLog: StateFlow<LogEntry?> = _detailLog.asStateFlow()
    /** Entry whose full call history is open (same person/number as [detailLog]). */
    private val _historyLog = MutableStateFlow<LogEntry?>(null)
    val historyLog: StateFlow<LogEntry?> = _historyLog.asStateFlow()
    /** True while a list is showing its contextual selection bar; MainShell tints the status bar to match so the bar reads edge to edge. */
    private val _selectionActive = MutableStateFlow<Boolean>(false)
    val selectionActive: StateFlow<Boolean> = _selectionActive.asStateFlow()
    private val _detailContact = MutableStateFlow<Contact?>(null)
    val detailContact: StateFlow<Contact?> = _detailContact.asStateFlow()

    private val _setup = MutableStateFlow(readSetup(ctx))
    /** What is granted and what is still missing. Re-read by [onPermissionsChanged]; screens just observe it. */
    val setup: StateFlow<Setup> = _setup
    /** The main screen is usable once the essentials are granted AND the walkthrough has been finished; losing an essential later brings setup back. */
    val ready: StateFlow<Boolean> = combine(_setup, Prefs.onboarded) { s, done -> s.essentials && done }
        .stateIn(viewModelScope, SharingStarted.Eagerly, _setup.value.essentials && Prefs.onboarded.value)
    /** Permission requests waiting for the system dialog, saved across rotation and process death. */
    val permissionQueue = PermissionQueue(saved)
    /** True while the main screen is resumed (in front of the person). */
    private val _foreground = MutableStateFlow<Boolean>(false)
    val foreground: StateFlow<Boolean> = _foreground.asStateFlow()

    fun finishSetup() = Prefs.setOnboarded(true)

    fun setDial(number: String) { _dial.value = number }
    /** Opens the dialpad, optionally with [number] already typed in. */
    fun openDialer(number: String? = null) {
        if (number != null) _dial.value = number
        _dialerOpen.value = true
    }
    fun closeDialer() { _dialerOpen.value = false }
    fun setLogFilter(index: Int) { _logFilter.value = index }
    fun setQuery(q: String) { _query.value = q }
    fun showSimPicker(number: String, options: List<SimOption>) { _simPicker.value = number to options }
    fun dismissSimPicker() { _simPicker.value = null }
    fun openDetail(e: LogEntry) { _detailLog.value = e }
    fun closeDetail() { _detailLog.value = null }
    fun openHistory(e: LogEntry) { _historyLog.value = e }
    fun closeHistory() { _historyLog.value = null }
    fun openContactDetail(c: Contact) { _detailContact.value = c }
    fun closeContactDetail() { _detailContact.value = null }
    fun setSelectionActive(on: Boolean) { _selectionActive.value = on }
    fun setForeground(on: Boolean) { _foreground.value = on }

    /** The call list is in front of the person, so the missed calls it shows are seen: take down their notifications. */
    fun markMissedSeen() = CallNotifications.clearMissed(ctx)

    private val _contactFilter = MutableStateFlow<ContactFilter>(ContactFilter.parse(Prefs.contactFilter.value))
    val contactFilter: StateFlow<ContactFilter> = _contactFilter.asStateFlow()
    /** True for a few seconds after "Sync contacts", just to show it's working. */
    private val _syncing = MutableStateFlow<Boolean>(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    fun setContactFilter(f: ContactFilter) { _contactFilter.value = f; Prefs.setContactFilter(f.key) }

    /** Asks the accounts to sync now and reloads what's on the phone. Later changes arrive through the contacts observer. */
    fun syncContacts() {
        if (syncing.value) return
        _syncing.value = true
        viewModelScope.launch {
            val n = withContext(Dispatchers.IO) { ContactsRepo.requestSync(ctx) }
            reloadContacts()
            Notices.show(if (n > 0) "Syncing contacts…" else "Contacts refreshed")
            delay(3000.milliseconds)
            _syncing.value = false
        }
    }

    /** Bottom-tab navigation. Switching tabs always closes the detail screens that sit on top of them. */
    fun selectTab(i: Int) {
        _detailLog.value = null
        _historyLog.value = null
        _detailContact.value = null
        _tab.value = i
    }

    private val _contacts = MutableStateFlow<Load<List<Contact>>>(Load.Loading)
    private val _log = MutableStateFlow<Load<List<LogEntry>>>(Load.Loading)
    val log: StateFlow<Load<List<LogEntry>>> = _log

    // flowOn(Default): viewModelScope runs on Main, and these walk every contact / call log row.
    val index: StateFlow<ContactIndex> = _contacts
        .map { ContactIndex((it as? Load.Ready)?.data ?: emptyList()) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, ContactIndex(emptyList()))

    /** Recents, ready to draw: grouped, titled and time-formatted off the main thread. Rebuilt when the log, the contacts or the filter change. */
    val recents: StateFlow<Load<RecentsModel>> = combine(_log, index, logFilter) { l, idx, f ->
        l.mapReady { buildRecents(ctx, it, f, idx) }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, Load.Loading)

    /** Number of the most recent call *you placed* (not the newest log entry, which may be incoming or missed). Null when none. */
    val lastDialed: StateFlow<String?> = _log
        .map { l -> (l as? Load.Ready)?.data?.firstOrNull { it.type == CallLog.Calls.OUTGOING_TYPE && it.number.isNotBlank() }?.number }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val filtered: StateFlow<Load<List<Contact>>> = combine(_contacts, query, contactFilter) { l, q, f -> filterContacts(l, q, f) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, Load.Loading)

    /** Every place contacts are stored in, for the filter menu: SIMs, Phone, accounts, then WhatsApp and anything else. */
    val savedIn: StateFlow<List<String>> = _contacts
        .map { l ->
            fun rank(s: String) = when { s.startsWith("SIM") -> 0; s == "Phone" -> 1; s == "WhatsApp" -> 3; '@' in s -> 2; else -> 4 }
            (l as? Load.Ready)?.data.orEmpty().flatMapTo(HashSet()) { it.savedIn }.sortedWith(compareBy({ rank(it) }, { it }))
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Starred contacts for the row at the top of Recents (max 5, alphabetical like the contact list). */
    val favorites: StateFlow<List<Contact>> = _contacts
        .map { l -> (l as? Load.Ready)?.data?.filter { it.starred }?.take(5) ?: emptyList() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Ids of every starred contact (not capped like [favorites]); drives the star toggle on contact detail. */
    val starredIds: StateFlow<Set<Long>> = _contacts
        .map { l -> (l as? Load.Ready)?.data?.filter { it.starred }?.map { it.id }?.toSet() ?: emptySet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val suggestions: StateFlow<List<Contact>> = combine(_contacts, dial) { l, d ->
        val digits = d.filter { it.isDigit() }
        if (l is Load.Ready && digits.length >= 2)
            l.data.filter { c -> c.phones.any { it.number.filter(Char::isDigit).contains(digits) } }.take(3)
        else emptyList()
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private fun filterContacts(l: Load<List<Contact>>, q: String, filter: ContactFilter): Load<List<Contact>> {
        if (l !is Load.Ready) return l
        // A saved filter for a place that no longer exists (account removed) behaves like "All" instead of an empty list.
        val f = if (filter is ContactFilter.Saved && l.data.none { filter.label in it.savedIn }) ContactFilter.All else filter
        val s = q.trim()
        if (s.isEmpty() && f == ContactFilter.All) return l
        val digits = s.filter { it.isDigit() }
        return Load.Ready(l.data.filter { c ->
            (f !is ContactFilter.Favorites || c.starred) &&
                    (f !is ContactFilter.Saved || f.label in c.savedIn) &&
                    (s.isEmpty() || c.name.contains(s, ignoreCase = true) ||
                            (digits.length >= 2 && c.phones.any { it.number.filter(Char::isDigit).contains(digits) }))
        })
    }

    private var logJob: Job? = null
    private var contactJob: Job? = null
    private var active = false

    init { onPermissionsChanged() }

    /** The contacts and call-log observers run only while the app is on screen; restarting them reloads once, so lists are fresh on return. */
    fun setActive(on: Boolean) {
        if (active == on) return
        active = on
        if (on) onPermissionsChanged()
        else { contactJob?.cancel(); contactJob = null; logJob?.cancel(); logJob = null }
    }

    /** Call after any permission/role change and on resume. Refreshes [setup] and starts or stops provider observers accordingly. */
    fun onPermissionsChanged() {
        PhoneKey.init(ctx)
        _setup.value = readSetup(ctx)
        if (ctx.granted(Manifest.permission.READ_CONTACTS)) {
            if (active && contactJob == null) contactJob = viewModelScope.launch {
                ContactsRepo.changes(ctx).conflate().collect { reloadContacts(); delay(500.milliseconds) }
            }
        } else {
            contactJob?.cancel(); contactJob = null
            _contacts.value = Load.Denied
        }
        if (ctx.granted(Manifest.permission.READ_CALL_LOG)) {
            if (active && logJob == null) logJob = viewModelScope.launch {
                CallLogRepo.changes(ctx).conflate().collect { reloadLog(); delay(250.milliseconds) }
            }
        } else {
            logJob?.cancel(); logJob = null
            _log.value = Load.Denied
        }
    }

    private suspend fun reloadContacts() {
        try {
            val all = withContext(Dispatchers.IO) { ContactsRepo.loadAll(ctx) }
            val starred = all.count { it.starred }
            Diag.log("contacts_loaded", 0, "n=" + all.size + " starred=" + starred) // counts only; shows up in Share log
            _contacts.value = Load.Ready(all)
        } catch (e: CancellationException) {
            throw e // the observer was stopped mid-load: not an error to show
        } catch (e: Exception) { Diag.fail("contacts_load", e); _contacts.value = Load.Failed(e.message ?: "Could not read contacts") }
    }

    /**
     * First load is two-phase: a short page so Recents paints almost immediately, then the full history.
     * Later reloads (the provider notifies on every write) go straight to the full load.
     */
    private suspend fun reloadLog() {
        try {
            if (_log.value !is Load.Ready) {
                val head = withContext(Dispatchers.IO) { CallLogRepo.load(ctx, FIRST_PAGE) }
                _log.value = Load.Ready(head)
                if (head.size < FIRST_PAGE) return // that was everything
            }
            _log.value = Load.Ready(withContext(Dispatchers.IO) { CallLogRepo.load(ctx) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Diag.fail("log_load", e)
            _log.value = Load.Failed(e.message ?: "Could not read call history")
        }
    }

    /** The contacts observer reloads the list afterward, so the UI follows on its own. */
    fun setStarred(id: Long, starred: Boolean, onFail: () -> Unit) {
        viewModelScope.launch {
            if (!withContext(Dispatchers.IO) { ContactsRepo.setStarred(ctx, id, starred) }) onFail()
        }
    }

    /** Deletes the entire call log. [onDone] gets false if the system refused. */
    fun clearLog(onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { CallLogRepo.clear(ctx) }
            if (ok) reloadLog()
            onDone(ok)
        }
    }

    /** Screens close themselves when nothing is left to show, so this only reports how many were removed. */
    fun deleteLogs(ids: Collection<Long>, onDone: (Int) -> Unit) {
        viewModelScope.launch {
            val n = withContext(Dispatchers.IO) { CallLogRepo.delete(ctx, ids) }
            if (n > 0) reloadLog()
            onDone(n)
        }
    }
}
