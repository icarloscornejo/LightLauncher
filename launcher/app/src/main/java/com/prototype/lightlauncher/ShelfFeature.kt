package com.prototype.lightlauncher

import android.util.Log
import kotlin.coroutines.cancellation.CancellationException

/**
 * The guard around the top shelf. Every call from the home
 * screen into the shelf code goes through [run]: the first unexpected failure is logged once (its class, never its
 * message, which could carry an address) and switches the shelf off for the rest of the process, so the dock apps
 * simply show their icon and Home keeps working. A network or decode problem is not a failure of the feature: the
 * loader turns those into "no image" itself. A cancelled coroutine passes through untouched; out of memory and other
 * virtual machine errors are not caught.
 */
object ShelfFeature {

    @Volatile
    private var broken: Boolean = false

    /** Where the one failure line goes; tests replace it, since android.util.Log is not available there. */
    @Volatile
    var reporter: (Throwable) -> Unit = { throwable -> Log.w("TopShelf", "top shelf switched off for this session: ${throwable.javaClass.simpleName}") }

    fun working(): Boolean = !broken

    /** Runs [step] and returns its value, or [fallback] once the shelf is off or the step fails. */
    fun <T> run(fallback: T, step: () -> T): T {
        if (broken) return fallback
        return try {
            step()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            switchOff(e)
            fallback
        } catch (e: LinkageError) {
            switchOff(e)
            fallback
        }
    }

    private fun switchOff(throwable: Throwable) {
        broken = true
        try {
            reporter(throwable)
        } catch (e: Exception) {
            // The report is best effort; the shelf is already off, which is what matters.
        }
    }

    /** For tests only. */
    fun reset() {
        broken = false
    }
}
