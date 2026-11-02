package dev.mizzenmast.dialer

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
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

/** The app is only usable once everything essential is granted; revoking one later brings setup back. */
@Composable
fun App(vm: DialerViewModel, actions: Actions) {
    // Setup stays up until the walkthrough is finished (so the optional pages after the last permission still show),
    // and comes back on its own if an essential is revoked later.
    val ready by vm.ready.collectAsState()
    Box(Modifier.fillMaxSize()) {
        if (ready) MainShell(vm, actions) else OnboardingScreen(vm, actions)
        NoticeHost(
            if (ready) Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 88.dp)
            else Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 12.dp)
        )
    }
}

private const val TAB_COUNT = 3

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
    val foreground by vm.foreground.collectAsState()

    val pager = rememberPagerState(initialPage = tab.coerceIn(0, TAB_COUNT - 1)) { TAB_COUNT }
    val motion = rememberMotionEnabled()
    // vm.tab -> pager (bottom-bar taps, intents). settledPage -> vm.tab (swipes). Both sides are no-ops once they agree.
    LaunchedEffect(tab) {
        if (pager.settledPage != tab) { if (motion) pager.animateScrollToPage(tab) else pager.scrollToPage(tab) }
    }
    LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.collect { vm.selectTab(it) } }
    // Missed-call notifications come down when the call list is actually in front of the person (and again when it reloads
    // while they are looking at it), not whenever the app happens to resume.
    val callListInFront = foreground && pager.settledPage == 0
    LaunchedEffect(callListInFront) {
        if (callListInFront) vm.log.collect { if (it is Load.Ready) vm.markMissedSeen() }
    }
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

            if (pager.targetPage != 2 && !overlayOpen) {
                DialerFab(Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 20.dp)) { vm.openDialer() }
            }
            detailLog?.let { CallDetailScreen(vm, actions, it, index.find(it.number)) }
            historyLog?.let { CallHistoryScreen(vm, actions, it, index.find(it.number)) }
            detailContact?.let { ContactDetailScreen(vm, actions, it) }
        }

        BottomBar(pager.targetPage) { vm.selectTab(it) }
    }
    if (dialerOpen) DialerSheet(vm, actions) { vm.closeDialer() }
    SimPickerSheet(vm, actions)
}
