package dev.mizzenmast.dialer

import android.Manifest
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Smart filters at the top of Contacts. The SIM filters use the call log: people you've called or been called by on that SIM. */
enum class ContactFilter { All, Sim1, Sim2, Favorites }

/** One ViewModel for the main app UI. In-call state lives in CallManager (process-wide, owned by the InCallService). */
class DialerViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()
    private companion object { const val FIRST_PAGE = 60 }

    val tab = MutableStateFlow(0)
    val dial = MutableStateFlow("")
    val dialerOpen = MutableStateFlow(false)
    val logFilter = MutableStateFlow(0) // 0 all, 1 missed
    /** Number waiting for the user to choose a SIM (set only when Android has no default calling SIM). */
    val simPicker = MutableStateFlow<Pair<String, List<SimOption>>?>(null)
    val query = MutableStateFlow("")
    val detailLog = MutableStateFlow<LogEntry?>(null)
    /** Entry whose full call history is open (same person/number as [detailLog]). */
    val historyLog = MutableStateFlow<LogEntry?>(null)
    val detailContact = MutableStateFlow<Contact?>(null)
    val tick = MutableStateFlow(0)
    val contactFilter = MutableStateFlow(ContactFilter.All)

    /** Bottom-tab navigation. Switching tabs always closes the detail screens that sit on top of them. */
    fun selectTab(i: Int) {
        detailLog.value = null
        historyLog.value = null
        detailContact.value = null
        tab.value = i
    }

    private val _contacts = MutableStateFlow<Load<List<Contact>>>(Load.Loading)
//    val contacts: StateFlow<Load<List<Contact>>> = _contacts
    private val _log = MutableStateFlow<Load<List<LogEntry>>>(Load.Loading)
    val log: StateFlow<Load<List<LogEntry>>> = _log

    // flowOn(Default): viewModelScope runs on Main, and these walk every contact / call log row.
    val index: StateFlow<ContactIndex> = _contacts
        .map { ContactIndex((it as? Load.Ready)?.data ?: emptyList()) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, ContactIndex(emptyList()))

    /** Recents, ready to draw: grouped, titled and time-formatted off the main thread. Rebuilt when the log, the contacts or the filter change. */
    val recents: StateFlow<Load<RecentsModel>> = combine(_log, index, logFilter) { l, idx, f ->
        l.mapReady { buildRecents(ctx, it, f == 1, idx) }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, Load.Loading)

    /** Per SIM slot (1, 2): normalised numbers that appear in the call log on that SIM. Empty when slots are unknown (single SIM, API < 30). */
    val simNumbers: StateFlow<Map<Int, Set<String>>> = _log
        .map { l -> simNumbersOf((l as? Load.Ready)?.data.orEmpty()) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val filtered: StateFlow<Load<List<Contact>>> = combine(_contacts, query, contactFilter, simNumbers) { l, q, f, sims -> filterContacts(l, q, f, sims) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, Load.Loading)

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

    private fun simNumbersOf(log: List<LogEntry>): Map<Int, Set<String>> {
        val out = HashMap<Int, MutableSet<String>>()
        for (e in log) {
            val slot = e.simSlot ?: continue
            val k = ContactIndex.key(e.number)
            if (k.isNotEmpty()) out.getOrPut(slot) { HashSet() }.add(k)
        }
        return out
    }

    private fun filterContacts(l: Load<List<Contact>>, q: String, filter: ContactFilter, sims: Map<Int, Set<String>>): Load<List<Contact>> {
        if (l !is Load.Ready) return l
        // SIM chips are hidden when no slot is known, so a stale SIM selection behaves like "All".
        val f = if ((filter == ContactFilter.Sim1 || filter == ContactFilter.Sim2) && sims.isEmpty()) ContactFilter.All else filter
        val s = q.trim()
        if (s.isEmpty() && f == ContactFilter.All) return l
        val digits = s.filter { it.isDigit() }
        val simKeys: Set<String>? = when (f) {
            ContactFilter.Sim1 -> sims[1].orEmpty()
            ContactFilter.Sim2 -> sims[2].orEmpty()
            else -> null
        }
        return Load.Ready(l.data.filter { c ->
            (f != ContactFilter.Favorites || c.starred) &&
                    (simKeys == null || c.phones.any { ContactIndex.key(it.number) in simKeys }) &&
                    (s.isEmpty() || c.name.contains(s, ignoreCase = true) ||
                            (digits.length >= 2 && c.phones.any { it.number.filter(Char::isDigit).contains(digits) }))
        })
    }

    private var logJob: Job? = null
    private var contactJob: Job? = null

    init { onPermissionsChanged() }

    /** Call after any permission/role change and on resume. Starts or stops provider observers accordingly. */
    fun onPermissionsChanged() {
        tick.value += 1
        if (ctx.granted(Manifest.permission.READ_CONTACTS)) {
            if (contactJob == null) contactJob = viewModelScope.launch {
                ContactsRepo.changes(ctx).conflate().collect { reloadContacts(); delay(500) }
            }
        } else {
            contactJob?.cancel(); contactJob = null
            _contacts.value = Load.Denied
        }
        if (ctx.granted(Manifest.permission.READ_CALL_LOG)) {
            if (logJob == null) logJob = viewModelScope.launch {
                CallLogRepo.changes(ctx).conflate().collect { reloadLog(); delay(250) }
            }
        } else {
            logJob?.cancel(); logJob = null
            _log.value = Load.Denied
        }
    }

    private suspend fun reloadContacts() {
        _contacts.value = try {
            Load.Ready(withContext(Dispatchers.IO) { ContactsRepo.loadAll(ctx) })
        } catch (e: Exception) { Load.Failed(e.message ?: "Could not read contacts") }
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
            _log.value = Load.Failed(e.message ?: "Could not read call history")
        }
    }

    /** The contacts observer reloads the list afterwards, so the UI follows on its own. */
    fun setStarred(id: Long, starred: Boolean, onFail: () -> Unit) {
        viewModelScope.launch {
            if (!withContext(Dispatchers.IO) { ContactsRepo.setStarred(ctx, id, starred) }) onFail()
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