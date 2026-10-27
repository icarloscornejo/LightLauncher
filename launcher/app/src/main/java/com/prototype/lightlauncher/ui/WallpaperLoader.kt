package com.prototype.lightlauncher.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import java.io.File

/**
 * A theme's full-screen picture behind everything. Decoded off the main thread and downsampled to the screen,
 * so a 4K JPEG costs a 1080p bitmap at most (about 4 MB in RGB_565), the price of a wallpaper on a small TV.
 */
object WallpaperLoader {
    private const val TAG: String = "LightLauncher"

    /** Decodes [file] for a screen of this size; null (and a log line) when it cannot be read. Call off the main thread. */
    fun decode(file: File, screenWidth: Int, screenHeight: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= screenWidth && bounds.outHeight / (sample * 2) >= screenHeight) sample *= 2
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565 // no alpha needed, half the memory
            }
            BitmapFactory.decodeFile(file.path, options).also { if (it == null) Log.w(TAG, "wallpaper could not be decoded") }
        } catch (e: OutOfMemoryError) {
            // A huge picture must never take the home screen down; the plain colour stays.
            Log.w(TAG, "wallpaper too large to decode")
            null
        }
    }

    /** Puts the decoded picture at the very back of [root]. */
    fun show(root: FrameLayout, bitmap: Bitmap) {
        val wall = ImageView(root.context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageBitmap(bitmap)
        }
        root.addView(wall, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
}
