package com.prototype.lightlauncher.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.provider.Settings
import android.util.DisplayMetrics
import com.prototype.lightlauncher.R
import com.prototype.lightlauncher.core.DockLayout
import com.prototype.lightlauncher.core.GridOrder
import com.prototype.lightlauncher.core.LabelText

/** What a tile stands for. EMPTY is a vacancy in the dock: a favourite that is not installed or is hidden. */
enum class TileKind { APP, HOME_SETTINGS, EMPTY }

/** One tile: the app it opens and what it shows. The colour fields are filled lazily by the UI. */
class TileEntry(
    val kind: TileKind,
    val label: String,
    val component: ComponentName,
    val banner: Drawable?,
    val icon: Drawable?,
    /** The app icon at xxxhdpi, loaded for the dock apps only; the top shelf shows it large. */
    val largeIcon: Drawable? = null,
    /** False for a phone app that only has the plain launcher entry: it opens with that category, not the leanback one. */
    val leanback: Boolean = true,
) {
    val pkg: String = component.packageName

    /** Stable key for the tile (its component), so focus can follow an app across reloads. */
    val key: String get() = component.flattenToShortString()
}

/**
 * The tile list, read from the package manager. The first [dockSlots] tiles are the pinned dock
 * (dock_apps, fixed order, a vacancy where a favourite is missing; see DockLayout), then the rest alphabetically. It runs off the main thread because it touches the package manager and every app's resources.
 */
class AppCatalog(
    private val context: Context,
    /** A context in the language the owner chose, made fresh for each load: the one string the catalog owns (Default home). */
    private val localizedContext: () -> Context,
    /** The dock: what the owner arranged on the TV, else config.xml dock_apps (or a theme's config.json). Read on every load. */
    private val favouritesProvider: () -> List<String>,
    /** The grid order the owner arranged on the TV (tile keys), empty for the default one. Read on every load. */
    private val gridOrderProvider: () -> List<String>,
    /** One dock slot per grid column. */
    private val dockSlots: Int,
    /** Never shown as tiles (config.xml hidden_apps, or a theme). */
    private val hidden: Set<String>,
    private val showHomeSettings: Boolean,
    /** The launcher's own "Default home" screen, for the optional tile; passed in so data never names UI classes. */
    private val homeSettingsComponent: ComponentName,
) {
    /** Builds the tiles: the dock, then the grid in the owner's order. */
    fun load(): List<TileEntry> {
        val pm: PackageManager = context.packageManager
        val favourites: List<String> = favouritesProvider()
        val leanback: List<ResolveInfo> = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)
        val leanbackPackages: Set<String> = leanback.mapNotNull { it.activityInfo?.packageName }.toSet()
        // Local change: a phone app installed by hand has no leanback entry, only the plain launcher one. Those show
        // too, but only user-installed ones: the system's own phone-style apps (Xiaomi's) would clutter the grid.
        val sideloaded: List<ResolveInfo> = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .filter { ri ->
                val activity = ri.activityInfo
                activity != null && activity.packageName !in leanbackPackages && (activity.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0
            }
            .distinctBy { it.activityInfo.packageName }
        val found: List<ResolveInfo> = leanback + sideloaded
        val fresh: MutableList<TileEntry> = ArrayList()
        for (ri in found) {
            val activity = ri.activityInfo ?: continue
            val pkg: String = activity.packageName
            if (pkg == context.packageName || pkg in hidden) continue
            val component = ComponentName(pkg, activity.name)
            val banner: Drawable? = loadBanner(pm, ri)
            val icon: Drawable? = ri.loadIcon(pm)
            val largeIcon: Drawable? = if (pkg in favourites) loadLargeIcon(pm, ri) else null
            // An app label with a bidi override or line separator must not redraw the tile name as something else (#117).
            fresh.add(TileEntry(TileKind.APP, LabelText.clean(ri.loadLabel(pm).toString(), pkg), component, banner, icon, largeIcon, leanback = pkg in leanbackPackages))
        }
        fresh.sortWith { a, b -> a.label.compareTo(b.label, ignoreCase = true) }
        val arranged = DockLayout.arrange(favourites, dockSlots, fresh) { it.pkg }
        val tiles: MutableList<TileEntry> = ArrayList()
        arranged.dock.forEachIndexed { slot, entry -> tiles.add(entry ?: vacancy(slot)) }
        val byKey: Map<String, TileEntry> = arranged.rest.associateBy { it.key }
        // The order from the TV first, anything it does not know (a new app) after, in the default order.
        GridOrder.reconcile(gridOrderProvider(), arranged.rest.map { it.key }).mapNotNullTo(tiles) { byKey[it] }
        // Optional "Default home" tile (config.xml), only when this TV actually has a home-screen picker.
        if (showHomeSettings && pm.resolveActivity(Intent(Settings.ACTION_HOME_SETTINGS), 0) != null) {
            tiles.add(
                TileEntry(TileKind.HOME_SETTINGS, localizedContext().getString(R.string.home_settings), homeSettingsComponent, null,
                    context.getDrawable(R.drawable.ic_home_settings)),
            )
        }
        return tiles
    }

    /** An empty dock slot. The class name only has to be unique per slot: it is the tile's stable key. */
    private fun vacancy(slot: Int): TileEntry =
        TileEntry(TileKind.EMPTY, "", ComponentName(context.packageName, "vacant.$slot"), null, null)

    /** The launcher icon at xxxhdpi: shown near 190 dp, the 96 px icon of a 320 dpi TV would look soft there. */
    private fun loadLargeIcon(pm: PackageManager, ri: ResolveInfo): Drawable? {
        val activity = ri.activityInfo
        val id: Int = if (activity.icon != 0) activity.icon else activity.applicationInfo.icon
        if (id != 0) {
            try {
                val resources: Resources = pm.getResourcesForApplication(activity.applicationInfo)
                return resources.getDrawableForDensity(id, DisplayMetrics.DENSITY_XXXHIGH, null)
            } catch (e: PackageManager.NameNotFoundException) {
                // The app went away mid-scan: the ordinary icon below is what is left.
            } catch (e: Resources.NotFoundException) {
                // An icon id that does not resolve at this density: same fallback.
            }
        }
        return ri.loadIcon(pm)
    }

    private fun loadBanner(pm: PackageManager, ri: ResolveInfo): Drawable? {
        val activity = ri.activityInfo
        val id: Int = if (activity.banner != 0) activity.banner else activity.applicationInfo.banner
        if (id != 0) {
            try {
                val resources: Resources = pm.getResourcesForApplication(activity.applicationInfo)
                // Keep at least xhdpi detail rather than downsampling artwork before the focus zoom.
                val density: Int = Math.max(DisplayMetrics.DENSITY_XHIGH, context.resources.displayMetrics.densityDpi)
                return resources.getDrawableForDensity(id, density, null)
            } catch (e: PackageManager.NameNotFoundException) {
                // The app went away mid-scan: its tile falls back to the plain banner lookup below.
            } catch (e: Resources.NotFoundException) {
                // A banner id that does not resolve at this density: same fallback.
            }
        }
        return activity.loadBanner(pm)
    }
}
