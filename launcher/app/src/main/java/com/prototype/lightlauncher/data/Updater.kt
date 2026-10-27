package com.prototype.lightlauncher.data

import android.content.pm.PackageInstaller
import com.prototype.lightlauncher.core.UpdateOffer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/** Which step of an update failed. */
enum class UpdateFailure { CHECK, DOWNLOAD, INSTALL }

/** Where the update is, as the settings row shows it. */
sealed class UpdateState {
    object Idle : UpdateState()

    object Checking : UpdateState()

    object UpToDate : UpdateState()

    data class Available(val offer: UpdateOffer) : UpdateState()

    object Downloading : UpdateState()

    /** The system's install screen is up and waiting for the owner. */
    object AwaitingConfirmation : UpdateState()

    data class Failed(val failure: UpdateFailure) : UpdateState()
}

/**
 * The one updater of the process, so a theme or language change that recreates the activity neither loses
 * it nor starts a second one. Looking, downloading and installing each begin only from the state that allows it, so a
 * second press of OK while one is running is ignored. The state is a flow the settings row follows.
 */
class Updater(
    private val checker: UpdateChecker,
    private val installer: ApkInstaller,
    private val repo: () -> String,
    private val installedVersion: () -> String,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val mutableState: MutableStateFlow<UpdateState> = MutableStateFlow(UpdateState.Idle)
    val state: StateFlow<UpdateState> get() = mutableState

    /** The offer being installed, so a cancelled install can go back to offering it. */
    @Volatile
    private var installing: UpdateOffer? = null

    /** Looks for a newer release. Ignored while something is already running. */
    fun check() {
        if (!begin(UpdateState.Checking) { !isBusy(it) }) return
        scope.launch(ioDispatcher) {
            mutableState.value = try {
                when (val result: CheckResult = checker.check(repo(), installedVersion())) {
                    is CheckResult.Available -> UpdateState.Available(result.offer)
                    CheckResult.UpToDate -> UpdateState.UpToDate
                    CheckResult.Failed -> UpdateState.Failed(UpdateFailure.CHECK)
                }
            } catch (unexpected: RuntimeException) {
                // Whatever it was, the row must not stay on "Checking" for good.
                UpdateState.Failed(UpdateFailure.CHECK)
            }
        }
    }

    /** Downloads the offered release and hands it to the system installer. Ignored unless an update is on offer. */
    fun install() {
        val offer: UpdateOffer = (mutableState.value as? UpdateState.Available)?.offer ?: return
        if (!begin(UpdateState.Downloading) { it is UpdateState.Available }) return
        installing = offer
        scope.launch(ioDispatcher) {
            try {
                val apk: File? = checker.download(offer)
                if (apk == null) {
                    mutableState.value = UpdateState.Failed(UpdateFailure.DOWNLOAD)
                } else if (!installer.install(apk, offer.version)) {
                    mutableState.value = UpdateState.Failed(UpdateFailure.INSTALL)
                }
            } catch (unexpected: RuntimeException) {
                mutableState.value = UpdateState.Failed(UpdateFailure.INSTALL)
            }
            // Committed: the answer comes to [onInstallStatus]; until then the row says it is working, and the state is
            // left alone because that answer may already have arrived.
        }
    }

    /**
     * The session's answer (a [PackageInstaller] STATUS_ code). The owner cancelling puts the offer back; any other
     * failure is an install failure. Success needs nothing: the system replaces this app and starts it again.
     * Returns true when the install did not happen (the caller undoes what it prepared for it).
     */
    fun onInstallStatus(status: Int): Boolean {
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> mutableState.value = UpdateState.AwaitingConfirmation
            PackageInstaller.STATUS_SUCCESS -> Unit
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                val offer: UpdateOffer? = installing
                mutableState.value = if (offer != null) UpdateState.Available(offer) else UpdateState.Idle
                return true
            }
            else -> {
                mutableState.value = UpdateState.Failed(UpdateFailure.INSTALL)
                return true
            }
        }
        return false
    }

    /** Moves to [next] only if the state now is one of those [allowed] accepts, atomically. */
    private fun begin(next: UpdateState, allowed: (UpdateState) -> Boolean): Boolean {
        while (true) {
            val current: UpdateState = mutableState.value
            if (!allowed(current)) return false
            if (mutableState.compareAndSet(current, next)) return true
        }
    }

    /**
     * Something is running, so nothing new may start. Waiting for the owner's confirmation is not busy: if the system
     * never shows or reports that screen, the row must still be usable (a new check replaces the state).
     */
    private fun isBusy(state: UpdateState): Boolean =
        state is UpdateState.Checking || state is UpdateState.Downloading
}
