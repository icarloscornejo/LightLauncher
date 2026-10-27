package com.prototype.lightlauncher.ui

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.Gravity
import com.prototype.lightlauncher.core.BackdropBlur
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * The blurred picture each dock app leaves behind the home. A thumbnail of the picture its shelf showed
 * (the full screen picture, or its first card) is kept per app, and [AmbientShelf] shows it softened (blurred, then
 * darkened toward the dark backdrop or washed toward white, per theme) when the focus goes down to the grid, and under
 * the full screen pictures of the apps that have one, where it is what you see while the sharp picture is still loading.
 *
 * What is kept is the raw thumbnail, never the softened one: the theme can change between runs. Thumbnails of full screen
 * apps are also kept on disk (`backdrop/<package>.png`, a few KB each) so the first focus after a restart has one at
 * once; cards' thumbnails live in memory only, since the dock shows them on the neutral backdrop anyway.
 *
 * The map is read and written on the main thread only. Thumbnails are made on [ioDispatcher] and handed back to it.
 */
class ShelfBackdrops(
    private val ambient: AmbientShelf,
    private val directory: File,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val resources: Resources,
    /** Washes the picture out toward white (light theme) instead of darkening it. */
    light: Boolean,
    /** The theme brings its own wallpaper or background colour: nothing is drawn over it. */
    private val themeOwnsBackground: Boolean,
    darkTint: Int,
) {
    /** The softened picture is made the first time it is shown, not for every slide of a carousel nobody leaves. */
    private inner class Entry(val thumbnail: Bitmap, val fullScreen: Boolean, val version: Int) {
        val softened: Drawable by lazy { BitmapDrawable(resources, soften(thumbnail)).apply { gravity = Gravity.FILL } }
    }

    private val entries: MutableMap<String, Entry> = HashMap()
    private val tint: Int = if (light) WHITE else darkTint
    private val tintShare: Float = if (light) LIGHT_TINT_SHARE else DARK_TINT_SHARE

    /** The dark theme never gets a bright backdrop, whatever the picture; the light one has no limit. */
    private val maxBrightness: Float = if (light) 1f else DARK_MAX_BRIGHTNESS
    private var version: Int = 0

    /** Follows the "Top shelf content" setting: off means no thumbnails are made and the backdrop stays neutral. */
    var enabled: Boolean = true

    /** The thumbnails kept on disk, into memory. Call when the shelf is enabled (at start, and when it is switched on). */
    fun loadStored() {
        if (themeOwnsBackground || !enabled) return
        scope.launch {
            val stored: List<Pair<String, Bitmap>> = withContext(ioDispatcher) {
                directory.listFiles { file -> file.isFile && file.name.endsWith(SUFFIX) }.orEmpty().mapNotNull { file ->
                    BitmapFactory.decodeFile(file.path)?.let { file.name.removeSuffix(SUFFIX) to it }
                }
            }
            for ((packageName, thumbnail) in stored) {
                if (packageName !in entries) entries[packageName] = entryOf(thumbnail, fullScreen = true)
            }
        }
    }

    /** The dock focused [packageName]: the picture under its full screen shelf, or the neutral backdrop. */
    fun showForDock(packageName: String) {
        val entry: Entry? = entries[packageName]
        if (entry != null && entry.fullScreen && enabled && !themeOwnsBackground) ambient.show(keyOf(packageName, entry), entry.softened) else ambient.hide()
    }

    /** The shelf of [packageName] is going away (the focus went to the grid): its picture stays, blurred, behind the home. */
    fun showForLeaving(packageName: String) {
        val entry: Entry? = entries[packageName]
        if (entry != null && enabled && !themeOwnsBackground) ambient.show(keyOf(packageName, entry), entry.softened) else ambient.hide()
    }

    /** Back to the neutral backdrop. */
    fun clear() = ambient.hide()

    /**
     * Makes the thumbnail of [source] (the picture the shelf of [packageName] just showed) and keeps it. [applyNow] is asked
     * on the main thread when it is ready: true puts it behind the home at once (the shelf is still the one that asked,
     * or it is leaving). [firstOfVisit] writes it to disk (a full screen picture) or removes the old file (cards), once
     * per visit and not for every slide of the carousel.
     */
    fun remember(packageName: String, source: Bitmap, fullScreen: Boolean, firstOfVisit: Boolean, applyNow: () -> Boolean) {
        if (themeOwnsBackground || !enabled) return
        scope.launch {
            val thumbnail: Bitmap = withContext(ioDispatcher) { thumbnailOf(source) }
            if (!enabled) return@launch
            val entry: Entry = entryOf(thumbnail, fullScreen)
            entries[packageName] = entry
            if (applyNow()) ambient.show(keyOf(packageName, entry), entry.softened)
            if (firstOfVisit) withContext(ioDispatcher) { if (fullScreen) save(packageName, thumbnail) else forget(packageName) }
        }
    }

    private fun entryOf(thumbnail: Bitmap, fullScreen: Boolean): Entry = Entry(thumbnail, fullScreen, ++version)

    private fun keyOf(packageName: String, entry: Entry): String = "$packageName#${entry.version}"

    private fun soften(thumbnail: Bitmap): Bitmap {
        val width: Int = thumbnail.width
        val height: Int = thumbnail.height
        val pixels = IntArray(width * height)
        thumbnail.getPixels(pixels, 0, width, 0, 0, width, height)
        val softened: IntArray = BackdropBlur.soften(pixels, width, height, BLUR_RADIUS, tint, tintShare, maxBrightness)
        return Bitmap.createBitmap(softened, width, height, Bitmap.Config.ARGB_8888)
    }

    /** Halves the picture until it is close to the thumbnail's size, then scales to it: one big step would skip pixels. */
    private fun thumbnailOf(source: Bitmap): Bitmap {
        var current: Bitmap = source
        while (current.width >= THUMBNAIL_WIDTH * 4) {
            current = Bitmap.createScaledBitmap(current, current.width / 2, current.height / 2, true)
        }
        return Bitmap.createScaledBitmap(current, THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT, true)
    }

    /** Written to a temporary file and renamed, so a cut in the middle never leaves half a picture behind. */
    private fun save(packageName: String, thumbnail: Bitmap) {
        try {
            directory.mkdirs()
            val temporary = File(directory, "$packageName$SUFFIX.tmp")
            FileOutputStream(temporary).use { thumbnail.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!temporary.renameTo(File(directory, packageName + SUFFIX))) temporary.delete()
        } catch (e: IOException) {
            Log.w(TAG, "backdrop thumbnail not saved")
        }
    }

    private fun forget(packageName: String) {
        File(directory, packageName + SUFFIX).delete()
    }

    private companion object {
        const val TAG: String = "ShelfBackdrops"
        const val SUFFIX: String = ".png"
        const val WHITE: Int = 0xFFFFFFFF.toInt()

        /** Small enough that the blur is a few thousand pixel reads; the screen stretches it with filtering, which blurs more. */
        const val THUMBNAIL_WIDTH: Int = 96
        const val THUMBNAIL_HEIGHT: Int = 54
        const val BLUR_RADIUS: Int = 5

        /** How far toward the tint the picture is mixed: dark mode darkens it, light mode washes it out. To be tuned by eye on the TV. */
        const val DARK_TINT_SHARE: Float = 0.55f
        const val LIGHT_TINT_SHARE: Float = 0.65f

        /** The brightest the dark theme's backdrop may be on average (0 to 1); the neutral dark background is about 0.07. */
        const val DARK_MAX_BRIGHTNESS: Float = 0.15f
    }
}
