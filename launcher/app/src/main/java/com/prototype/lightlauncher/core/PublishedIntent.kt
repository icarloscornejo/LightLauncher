package com.prototype.lightlauncher.core

/**
 * What the launcher agrees to open when the owner presses OK on a "Play Next" card. The address is an
 * intent another app wrote into the TV provider, so it is treated as untrusted: only a plain open of the app's own
 * content is accepted. `SEND` and its relatives are refused because Android adds a read grant for the intent's data
 * when it starts them, even after the launcher cleared the grant flags; a `file:` address would make the system
 * refuse to start the activity at all. The caller still points the intent at the app's own package.
 */
object PublishedIntent {

    /** True when there is an address to try. A card without one is shown but cannot be focused. */
    fun isUsable(address: String?): Boolean = !address.isNullOrBlank()

    /**
     * [action] and the scheme of the data of the parsed intent. No action at all is a plain open of the app; any other
     * must be one of [openActions] (the caller passes the system's VIEW and MAIN, which this layer cannot name).
     */
    fun allows(action: String?, dataScheme: String?, openActions: Set<String>): Boolean {
        if (action != null && action !in openActions) return false
        return !"file".equals(dataScheme, ignoreCase = true)
    }

    /** An explicit component is kept only when it belongs to [appPackage]; any other is dropped, not followed. */
    fun keepsComponent(componentPackage: String?, appPackage: String): Boolean =
        componentPackage == null || componentPackage == appPackage
}
