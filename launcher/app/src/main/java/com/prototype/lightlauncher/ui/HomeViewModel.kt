package com.prototype.lightlauncher.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.prototype.lightlauncher.core.ReloadDuty
import com.prototype.lightlauncher.core.ReloadRequest
import com.prototype.lightlauncher.core.ReloadRequests
import com.prototype.lightlauncher.data.TileEntry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One scan of the installed apps. */
class HomeContent(val tiles: List<TileEntry>) {
    /**
     * What the scan shows, as plain text to compare two scans by ([com.prototype.lightlauncher.core.ScanChange]): the tiles
     * are rebuilt on every scan (new drawables, new objects), so they cannot be compared directly.
     */
    val signature: List<String> = tiles.map { "${it.kind}|${it.key}|${it.label}" }
}

/**
 * What Home shows, plus the one-time work owed since the screen last confirmed a reload. The duty rides with the
 * tiles it belongs to, and the screen clears it with [HomeViewModel.acknowledge], so subscribing again (every
 * return to Home) never repeats a focus restore or an entry animation.
 */
class HomeUiState(val content: HomeContent?, val duty: ReloadDuty?)

/**
 * The app scan runs off the main thread. A newer request cancels the scan in flight; its
 * duties are kept by [ReloadRequests] until the screen confirms them.
 */
class HomeViewModel(
    /** Reads the package manager; runs on [ioDispatcher]. */
    private val loader: () -> HomeContent,
    private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val requests = ReloadRequests()
    private val mutableState = MutableStateFlow(HomeUiState(null, null))
    private var scan: Job? = null
    private var rescan: Boolean = false

    val state: StateFlow<HomeUiState> = mutableState.asStateFlow()

    /**
     * Never two scans at once: the loader cannot be interrupted and each pass holds every icon in memory, so a burst
     * of requests (package updates, quick returns to Home) marks one more pass for when the running one ends, and
     * the result of a pass that was overtaken is dropped.
     */
    fun reload(request: ReloadRequest) {
        requests.add(request)
        if (scan?.isActive == true) {
            rescan = true
            return
        }
        scan = viewModelScope.launch {
            do {
                rescan = false
                val content: HomeContent = withContext(ioDispatcher) { loader() }
                if (!rescan) mutableState.value = HomeUiState(content, requests.pending())
            } while (rescan)
        }
    }

    /** The screen applied [duty]; a request made after it stays owed. */
    fun acknowledge(duty: ReloadDuty) {
        requests.acknowledge(duty.id)
        mutableState.value = HomeUiState(mutableState.value.content, requests.pending())
    }
}
