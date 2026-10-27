package com.prototype.lightlauncher.data

import android.content.Context
import android.content.pm.PackageManager
import android.media.tv.TvContract
import android.net.Uri
import com.prototype.lightlauncher.core.ShelfSource

/**
 * One thing a dock app published to the TV provider: a title (for accessibility and the can't-open message), its
 * image address, whether it is part of the app's showcase (preview programs) or of what to continue (Watch Next),
 * and the intent the app wrote to open it, untrusted until [com.prototype.lightlauncher.core.PublishedIntent] has seen it.
 * A data class so a refresh can tell that the app moved a card to the next episode under the same poster.
 */
data class ShelfItem(val title: String, val imageUrl: String, val source: ShelfSource, val intentUri: String? = null)

/**
 * What each dock app publishes to the TV provider (preview programs and Watch Next, kept apart by
 * [ShelfItem.source]), read for the top shelf. A snapshot per refresh; [itemsFor] never touches the provider.
 *
 * The queries have no selection on purpose: without ACCESS_ALL_EPG_DATA (signature|privileged) the provider throws a
 * SecurityException for any selection, so the package is read from the column and filtered here. And without
 * READ_TV_LISTINGS (a runtime permission, granted over adb) it returns zero rows without saying why, so the
 * permission is checked first and an empty snapshot then means "not allowed", not "nothing published".
 *
 * ponytail: an app that gives no rank at all (every `weight` the same) keeps the provider's own order; that is the
 * app's own choice of what to put first, and it is all that can be known without judging the pictures.
 */
class TopShelf(private val context: Context, private val logger: Logger) {

    @Volatile
    private var snapshot: Map<String, List<ShelfItem>> = emptyMap()

    fun itemsFor(packageName: String): List<ShelfItem> = snapshot[packageName].orEmpty()

    /**
     * Blocking: call on an IO dispatcher. Never throws for provider problems; those leave an empty snapshot. True when
     * what the apps publish (pictures, titles or intents) is not what the previous snapshot held.
     */
    fun refresh(dockPackages: Set<String>): Boolean {
        val before: Map<String, List<ShelfItem>> = snapshot
        if (context.checkSelfPermission(READ_TV_LISTINGS) != PackageManager.PERMISSION_GRANTED) {
            if (snapshot.isNotEmpty() || !permissionReported) logger.warn("READ_TV_LISTINGS not granted: the top shelf has nothing to show")
            permissionReported = true
            snapshot = emptyMap()
            return before.isNotEmpty()
        }
        val found: MutableMap<String, MutableList<ShelfItem>> = HashMap()
        try {
            read(TvContract.WatchNextPrograms.CONTENT_URI, ShelfSource.WATCH_NEXT, dockPackages, found)
            read(TvContract.PreviewPrograms.CONTENT_URI, ShelfSource.PREVIEW, dockPackages, found)
        } catch (e: SecurityException) {
            logger.warn("TV provider refused the read (${e.javaClass.simpleName})")
            found.clear()
        } catch (e: IllegalArgumentException) {
            logger.warn("TV provider does not know the columns (${e.javaClass.simpleName})")
            found.clear()
        }
        snapshot = found
        return found != before
    }

    private var permissionReported: Boolean = false

    /**
     * Reads one table and keeps, per dock app, the [MAX_PER_SOURCE] best rows: the ones the app itself ranked highest
     * (a preview program's `weight`; for Watch Next, the most recently watched), ties in the provider's order.
     */
    private fun read(table: Uri, source: ShelfSource, dockPackages: Set<String>, into: MutableMap<String, MutableList<ShelfItem>>) {
        val rankColumn: String = if (source == ShelfSource.PREVIEW) {
            TvContract.PreviewPrograms.COLUMN_WEIGHT
        } else {
            TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS
        }
        val projection = arrayOf(
            TvContract.BaseTvColumns.COLUMN_PACKAGE_NAME,
            TvContract.PreviewPrograms.COLUMN_TITLE,
            TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI,
            TvContract.PreviewPrograms.COLUMN_THUMBNAIL_URI,
            rankColumn,
            TvContract.PreviewPrograms.COLUMN_INTENT_URI,
        )
        val ranked: MutableMap<String, MutableList<Pair<Long, ShelfItem>>> = HashMap()
        val cursor = context.contentResolver.query(table, projection, null, null, null) ?: return
        cursor.use {
            while (it.moveToNext()) {
                val packageName: String = it.getString(0) ?: continue
                if (packageName !in dockPackages) continue
                val image: String = it.getString(2)?.takeIf(String::isNotBlank) ?: it.getString(3)?.takeIf(String::isNotBlank) ?: continue
                val intentUri: String? = it.getString(5)?.takeIf(String::isNotBlank)
                ranked.getOrPut(packageName) { ArrayList() }.add(it.getLong(4) to ShelfItem(it.getString(1).orEmpty(), image, source, intentUri))
            }
        }
        for ((packageName, rows) in ranked) {
            val target: MutableList<ShelfItem> = into.getOrPut(packageName) { ArrayList() }
            val seen: MutableSet<String> = HashSet()
            // sortedByDescending is stable, so equal ranks (an app that sets none) keep the provider's order.
            for ((_, item) in rows.sortedByDescending { pair -> pair.first }) {
                if (target.count { existing -> existing.source == source } >= MAX_PER_SOURCE) break
                if (seen.add(item.imageUrl)) target.add(item)
            }
        }
    }

    private companion object {
        const val READ_TV_LISTINGS: String = "android.permission.READ_TV_LISTINGS"
        /** Enough for a carousel and for the collages of the small pictures (Spotify publishes 15). */
        const val MAX_PER_SOURCE: Int = 16
    }
}
