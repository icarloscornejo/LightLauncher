package com.prototype.lightlauncher.core

/** Why the app list is being reloaded. */
enum class ReloadOrigin { RESUME, NEW_INTENT, PACKAGE, DECISION }

/** One request to reload the app list, with what should happen once the new list is on screen. */
data class ReloadRequest(val origin: ReloadOrigin, val animate: Boolean, val restoreFocus: Boolean)

/**
 * The one-time work owed for every request made since the screen last confirmed one: the union of their
 * origins and flags. [id] is what the screen confirms with [ReloadRequests.acknowledge].
 */
data class ReloadDuty(val id: Long, val origins: Set<ReloadOrigin>, val animate: Boolean, val restoreFocus: Boolean)

/** What the screen should do with the focus when it applies a reload. */
enum class FocusAction { RESTORE, LEAVE }

/**
 * Keeps every reload request until the screen confirms it applied its duties, so a newer
 * request (for example a package event arriving while the resume reload runs, or after it finished but
 * before it was applied) never drops the older one's focus restore or animation. Each duty is applied
 * once: a confirmed request is forgotten, an unconfirmed one is merged into the next duty.
 *
 * Not thread safe: use it from one thread (the main thread, or the view model's main scope).
 */
class ReloadRequests {
    private val unconfirmed: MutableList<Pair<Long, ReloadRequest>> = ArrayList()
    private var nextId: Long = 1

    /** Records a request; returns its id. */
    fun add(request: ReloadRequest): Long {
        val id: Long = nextId++
        unconfirmed.add(id to request)
        return id
    }

    /** The merged duty of every unconfirmed request, or null when there is none. */
    fun pending(): ReloadDuty? {
        if (unconfirmed.isEmpty()) return null
        val requests: List<ReloadRequest> = unconfirmed.map { it.second }
        return ReloadDuty(
            id = unconfirmed.last().first,
            origins = requests.mapTo(LinkedHashSet()) { it.origin },
            animate = requests.any { it.animate },
            restoreFocus = requests.any { it.restoreFocus },
        )
    }

    /** The screen applied the duty with this id: every request up to it is done. Later ones stay. */
    fun acknowledge(dutyId: Long) {
        unconfirmed.removeAll { it.first <= dutyId }
    }

    companion object {
        /**
         * The focus rule, unchanged from the Java launcher: a resume or Home press always brings the focus back
         * to the remembered tile (unless a popup or dialog is open); a decision (a saved reorder) does too;
         * package events alone never take the focus from a header button or popup.
         */
        fun focusAction(duty: ReloadDuty, focusOnHeaderOrPopup: Boolean, popupOrDialogOpen: Boolean): FocusAction {
            if (popupOrDialogOpen) return FocusAction.LEAVE
            if (ReloadOrigin.RESUME in duty.origins || ReloadOrigin.NEW_INTENT in duty.origins) return FocusAction.RESTORE
            if (!duty.restoreFocus) return FocusAction.LEAVE
            val onlyPackageEvents: Boolean = duty.origins.all { it == ReloadOrigin.PACKAGE }
            if (onlyPackageEvents && focusOnHeaderOrPopup) return FocusAction.LEAVE
            return FocusAction.RESTORE
        }
    }
}
