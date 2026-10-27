package com.prototype.lightlauncher.ui

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.prototype.lightlauncher.R
import com.prototype.lightlauncher.core.PublishedIntent
import java.net.URISyntaxException

/** Opening system screens and apps from the home screen, with the can't-open toast instead of a crash. */
object SystemIntents {

    /**
     * China-market MIUI TV firmware answers no standard Settings intents at all; open Xiaomi's own settings app
     * (com.xiaomi.mitv.settings) there, so the Settings and Wi-Fi rows keep working. On firmware with a
     * standard settings handler the behaviour is unchanged.
     */
    fun forAction(context: Context, standardAction: String): Intent {
        val standard = Intent(standardAction)
        if (standard.resolveActivity(context.packageManager) != null) return standard
        val xiaomi = Intent().setComponent(ComponentName("com.xiaomi.mitv.settings", "com.xiaomi.mitv.settings.entry.MainActivity"))
        if (xiaomi.resolveActivity(context.packageManager) != null) return xiaomi
        return Intent(standardAction) // nothing found: open() shows the can't-open toast
    }

    /**
     * Opens what a dock app published for a "Play Next" card: its own intent, narrowed to a plain open inside that app
     * ([PublishedIntent] says what is accepted; the package is forced, a component of another package, the selector,
     * the clip data and every URI grant flag are dropped). Whatever goes wrong shows the can't-open message and stays
     * here: the intent was written by another app, so no failure of it may reach the shelf guard and switch the shelf off
     * for every app. That is why the last catch is broad, and it logs only the class of the failure.
     */
    fun openPublished(context: Context, address: String?, packageName: String, label: String) {
        try {
            require(address != null && PublishedIntent.isUsable(address)) { "no address" }
            val intent: Intent = Intent.parseUri(address, Intent.URI_INTENT_SCHEME)
            require(PublishedIntent.allows(intent.action, intent.data?.scheme, OPEN_ACTIONS)) { "action or data not accepted" }
            if (!PublishedIntent.keepsComponent(intent.component?.packageName, packageName)) intent.component = null
            intent.selector = null
            intent.clipData = null
            intent.setPackage(packageName)
            intent.removeFlags(GRANT_FLAGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: URISyntaxException) {
            cantOpen(context, label, e)
        } catch (e: RuntimeException) {
            cantOpen(context, label, e)
        }
    }

    private fun cantOpen(context: Context, label: String, failure: Exception) {
        Log.w("LightLauncher", "card not opened (${failure.javaClass.simpleName})")
        Toast.makeText(context, context.getString(R.string.cant_open, label), Toast.LENGTH_SHORT).show()
    }

    /** The only actions a published card may use: a plain open. SEND and the like make Android add a grant on the data. */
    private val OPEN_ACTIONS: Set<String> = setOf(Intent.ACTION_VIEW, Intent.ACTION_MAIN)

    private const val GRANT_FLAGS: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION

    fun open(context: Context, intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, R.string.not_available, Toast.LENGTH_SHORT).show()
        }
    }
}
