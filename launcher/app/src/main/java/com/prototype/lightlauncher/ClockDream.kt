package com.prototype.lightlauncher

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.service.dreams.DreamService
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.prototype.lightlauncher.core.SlideLayout
import com.prototype.lightlauncher.core.SlidePhoto
import com.prototype.lightlauncher.core.SlidePlanner
import com.prototype.lightlauncher.data.LanguageStore
import com.prototype.lightlauncher.data.LocalizedContext
import com.prototype.lightlauncher.data.ShownSnapshot
import com.prototype.lightlauncher.ui.ColorSampler
import com.prototype.lightlauncher.ui.FeatheredPlate
import com.prototype.lightlauncher.ui.GlowBitmap
import com.prototype.lightlauncher.ui.GlowSpot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar
import java.util.Random

/**
 * Screensaver: the launcher's thin clock and date on black, over a slow ambient glow.
 * Every minute the clock drifts to a new spot and the glow shifts colour, so nothing burns in.
 *
 * When AlbumSync has downloaded photos, a slideshow of them replaces the drifting clock. A slide is
 * one wide photo, or two narrower ones side by side, faded in over the previous one, over a glow in the photos'
 * own colours like the home screen's; the time and date sit in the bottom-left corner, scaled
 * down, on a dark plate with faded edges.
 *
 * The session owns a coroutine scope, cancelled when the dream detaches, so nothing from a previous
 * session can land on this one. Photo sizes and decoding run off the main thread; the photo list itself is read
 * on attach because it decides between the photo and clock modes (and the screen brightness) right there.
 */
class ClockDream : DreamService() {

    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val random: Random = Random()
    private lateinit var container: AppContainer

    // ---- clock mode ----
    private lateinit var content: View
    private lateinit var glow: GradientDrawable
    private var colorIndex: Int = 0
    private var glowColor: Int = 0
    private var tickJob: Job? = null

    // ---- photo mode ----
    private val slides: Array<View?> = arrayOfNulls(2)
    private val leftPhotos: Array<ImageView?> = arrayOfNulls(2)
    private val rightPhotos: Array<ImageView?> = arrayOfNulls(2)
    private var photoFiles: List<File> = emptyList()
    /** The photos by file name, the identity SlidePlanner works with. */
    private var photosByName: Map<String, SlidePhotoFile> = emptyMap()
    private var planner: SlidePlanner? = null
    /** The ShownStore version the planner's set was read at; a rename since then makes the write fail. */
    private var shownVersion: Long = 0
    private var frontSlide: Int = 0
    private lateinit var header: View
    private var photoMode: Boolean = false
    private var slideJob: Job? = null

    private class SlidePhotoFile(val file: File, val photo: SlidePhoto)

    /** The screensaver's date follows the language setting too. */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocalizedContext.wrap(newBase, LanguageStore(newBase).read()))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false // any key wakes the TV back to where it was
        isFullscreen = true
        container = (application as LightLauncherApp).container
        photoFiles = container.albumSync.photos()
        photoMode = photoFiles.isNotEmpty()
        isScreenBright = photoMode // photos are meant to be seen, the clock is dimmed
        setContentView(R.layout.dream_clock)
        // The dream can start in a cold process, before Home ever loaded the config: read it off the main thread.
        scope.launch(Dispatchers.IO) { container.albumSync.syncIfStale(container.albumLink, "dream") }

        content = findViewById(R.id.dreamContent)
        val ambient: View = findViewById(R.id.dreamAmbient)
        glow = GradientDrawable().apply { gradientType = GradientDrawable.RADIAL_GRADIENT }
        ambient.background = glow
        ambient.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ -> glow.gradientRadius = (right - left) * 0.55f }
        colorIndex = random.nextInt(PALETTE.size)
        setGlow(PALETTE[colorIndex])

        if (photoMode) setUpPhotoMode(ambient)
    }

    private fun setUpPhotoMode(ambient: View) {
        ambient.visibility = View.GONE
        slides[0] = findViewById(R.id.dreamSlideA)
        slides[1] = findViewById(R.id.dreamSlideB)
        leftPhotos[0] = findViewById(R.id.dreamSlideALeft)
        rightPhotos[0] = findViewById(R.id.dreamSlideARight)
        leftPhotos[1] = findViewById(R.id.dreamSlideBLeft)
        rightPhotos[1] = findViewById(R.id.dreamSlideBRight)
        for (slide in slides) slide?.visibility = View.VISIBLE
        content.visibility = View.GONE // the drifting clock is only for the photo-less mode
        header = findViewById(R.id.dreamHeader)
        val density: Float = resources.displayMetrics.density
        // The plate touches the corner, so only its top and right edges fade into the photo.
        header.background = FeatheredPlate(HEADER_PLATE_ALPHA, HEADER_FEATHER_DP * density, false, true, true, false)
        // The scaling below shrinks these paddings too, so ask for more to land on exactly 16dp.
        val edgeGap: Int = Math.round(HEADER_EDGE_GAP_DP * density / HEADER_SCALE)
        val bottomGap: Int = Math.max(0, edgeGap - Math.round(HEADER_BASELINE_INSET_DP * density))
        // The top and right edges fade out over HEADER_FEATHER_DP, so the fade sits outside this margin and the text
        // keeps solid dark all around it (left and bottom touch the screen edge and do not fade).
        val fadeInset: Int = Math.round(HEADER_FEATHER_DP * density)
        val fadeSideGap: Int = Math.round(HEADER_FADE_SIDE_GAP_DP * density / HEADER_SCALE) + fadeInset
        header.setPadding(edgeGap, fadeSideGap, fadeSideGap, bottomGap)
        // Scale towards the bottom-left corner, so the plate stays flush with it.
        header.pivotX = 0f
        header.addOnLayoutChangeListener { view, _, top, _, bottom, _, _, _, _ -> view.pivotY = (bottom - top).toFloat() }
        header.scaleX = HEADER_SCALE
        header.scaleY = HEADER_SCALE
        header.visibility = View.VISIBLE
        header.bringToFront() // stays on top of the photos
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        if (photoMode) {
            startSlideshow()
            return
        }
        content.alpha = 0f
        content.post {
            placeClock()
            content.animate().alpha(1f).setDuration(1200).start()
        }
        tickJob = scope.launch {
            while (isActive) {
                delay(MOVE_MS)
                moveClock()
                shiftGlow()
            }
        }
    }

    override fun onDreamingStopped() {
        slideJob?.cancel()
        tickJob?.cancel()
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        scope.cancel()
        super.onDetachedFromWindow()
    }

    // ---- photo slideshow ----

    private fun startSlideshow() {
        slideJob = scope.launch {
            if (!reloadCatalog()) return@launch
            while (isActive) {
                val currentPlanner: SlidePlanner = planner ?: return@launch
                val slide: List<SlidePhotoFile> = currentPlanner.nextSlide().mapNotNull { photosByName[it] }
                if (!container.shownStore.write(currentPlanner.shownNames(), shownVersion)) {
                    // AlbumSync renamed the photos since the set was read: start over from the translated set.
                    if (!reloadCatalog()) return@launch
                    continue
                }
                Log.i(TAG, "Dream round: ${currentPlanner.shownCount} of ${currentPlanner.totalCount} photos shown")
                val prepared: PreparedSlide? = if (slide.isEmpty()) null else withContext(Dispatchers.Default) { prepare(slide) }
                if (prepared == null) {
                    if (slide.any { !it.file.exists() }) {
                        // An album clean-up or a rename moved a photo between the pick and the decode: not an error.
                        Log.i(TAG, "Dream photos changed on disk, reloading the list")
                    } else {
                        Log.w(TAG, "Dream photo could not be decoded: ${slide.firstOrNull()?.file?.name}")
                        delay(1_000L)
                    }
                    if (!reloadCatalog()) return@launch
                    continue
                }
                crossfadeTo(prepared)
                delay(PHOTO_MS)
            }
        }
    }

    /**
     * Lists the folder, reads every photo's size (header only, cheap) and the round so far, all off the main
     * thread, and builds a fresh planner. False when there is nothing left to show.
     */
    private suspend fun reloadCatalog(): Boolean {
        val loaded: Pair<Map<String, SlidePhotoFile>, ShownSnapshot> = withContext(Dispatchers.IO) {
            val shown: ShownSnapshot = container.shownStore.read()
            val files: List<File> = container.albumSync.photos()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val measured: MutableMap<String, SlidePhotoFile> = LinkedHashMap()
            for (file in files) {
                bounds.outWidth = 0
                bounds.outHeight = 0
                BitmapFactory.decodeFile(file.path, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    Log.w(TAG, "Dream photo could not be read: ${file.name}")
                    continue
                }
                measured[file.name] = SlidePhotoFile(file, SlidePhoto(file.name, bounds.outWidth.toFloat() / bounds.outHeight))
            }
            measured to shown
        }
        photosByName = loaded.first
        if (photosByName.isEmpty()) return false
        shownVersion = loaded.second.version
        planner = SlidePlanner(photosByName.values.map { it.photo }, loaded.second.names, random)
        return true
    }

    private class PreparedSlide(val left: Bitmap, val right: Bitmap?, val layout: SlideLayout, val background: BitmapDrawable)

    /** Decodes the slide's photos at the size they will be shown, plans the layout and paints the glow. */
    private fun prepare(slide: List<SlidePhotoFile>): PreparedSlide? {
        val screenWidth: Float = resources.displayMetrics.widthPixels.toFloat()
        val screenHeight: Float = resources.displayMetrics.heightPixels.toFloat()
        val leftPhoto: SlidePhotoFile = slide[0]
        val rightPhoto: SlidePhotoFile? = slide.getOrNull(1)
        val layout: SlideLayout = if (rightPhoto == null) SlideLayout.SINGLE
        else SlideLayout.forPair(leftPhoto.photo.aspect, rightPhoto.photo.aspect, screenWidth, screenHeight)
        val left: Bitmap = decode(leftPhoto.file, if (rightPhoto == null) screenWidth.toInt() else layout.leftWidth) ?: return null
        val right: Bitmap? = rightPhoto?.let { decode(it.file, layout.rightWidth) ?: return null }
        val background: android.graphics.Bitmap = if (right == null) dreamGlowBitmap(vividColorOf(left), 0, false, layout)
        else dreamGlowBitmap(vividColorOf(left), vividColorOf(right), true, layout)
        val drawable = BitmapDrawable(resources, background).apply { gravity = Gravity.FILL }
        return PreparedSlide(left, right, layout, drawable)
    }

    /** Decodes at the largest power-of-two step that still covers [targetWidth]; null when the file is gone or bad. */
    private fun decode(file: File, targetWidth: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (targetWidth > 0 && bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** A width of 0 gives the view the remaining space (weight 1); anything else is an exact size. */
    private fun applyWidth(view: ImageView, widthPx: Int) {
        val params = view.layoutParams as LinearLayout.LayoutParams
        params.width = if (widthPx > 0) widthPx else 0
        params.weight = if (widthPx > 0) 0f else 1f
        view.layoutParams = params
    }

    /** The new slide fades in over the old one (opaque black behind its photos), then the old one is cleared. */
    private fun crossfadeTo(prepared: PreparedSlide) {
        val back: Int = 1 - frontSlide
        val front: Int = frontSlide
        val incoming: View = slides[back] ?: return
        val outgoing: View = slides[front] ?: return
        val incomingLeft: ImageView = leftPhotos[back] ?: return
        val incomingRight: ImageView = rightPhotos[back] ?: return
        incomingLeft.setImageBitmap(prepared.left)
        incomingRight.setImageBitmap(prepared.right)
        incomingRight.visibility = if (prepared.right != null) View.VISIBLE else View.GONE // gone: the left photo takes the whole width
        applyWidth(incomingLeft, prepared.layout.leftWidth)
        applyWidth(incomingRight, prepared.layout.rightWidth)
        incoming.background = prepared.background // opaque, so it fully covers the slide under it while it fades in
        incoming.alpha = 0f
        incoming.bringToFront()
        header.bringToFront()
        incoming.animate().alpha(1f).setDuration(FADE_MS).withEndAction {
            outgoing.alpha = 0f
            leftPhotos[front]?.setImageDrawable(null) // let the previous photos' memory go
            rightPhotos[front]?.setImageDrawable(null)
        }.start()
        frontSlide = back
    }

    private fun vividColorOf(photo: Bitmap): Int = ColorSampler.vividColor(BitmapDrawable(resources, photo))

    // ---- clock mode ----

    private fun moveClock() {
        content.animate().alpha(0f).setDuration(900).withEndAction {
            placeClock()
            content.animate().alpha(1f).setDuration(1200).start()
        }.start()
    }

    /** A random spot that keeps the whole clock on screen, with the glow centred behind it. */
    private fun placeClock() {
        val parent: View = content.parent as View
        val maxX: Int = Math.max(0, parent.width - content.width)
        val maxY: Int = Math.max(0, parent.height - content.height)
        val x: Float = maxX * (0.1f + 0.8f * random.nextFloat())
        val y: Float = maxY * (0.1f + 0.8f * random.nextFloat())
        content.x = x
        content.y = y
        if (parent.width > 0 && parent.height > 0) {
            glow.setGradientCenter((x + content.width / 2f) / parent.width, (y + content.height / 2f) / parent.height)
        }
    }

    private fun shiftGlow() {
        val from: Int = glowColor
        colorIndex = (colorIndex + 1) % PALETTE.size
        ValueAnimator.ofObject(ArgbEvaluator(), from, PALETTE[colorIndex]).apply {
            duration = 8000
            addUpdateListener { setGlow(it.animatedValue as Int) }
            start()
        }
    }

    private fun setGlow(color: Int) {
        glowColor = color
        val rgb: Int = color and 0x00FFFFFF
        glow.colors = intArrayOf(0x40000000 or rgb, 0x14000000 or rgb, rgb)
    }

    companion object {
        private const val TAG: String = "LightLauncher"
        private const val MOVE_MS: Long = 60_000L

        /** Deep, low-brightness glow colours; the glow is drawn at partial alpha on black. */
        private val PALETTE: IntArray = intArrayOf(0xFF2B5CFF.toInt(), 0xFF00A39A.toInt(), 0xFF8A3FFC.toInt(), 0xFFE0661B.toInt(), 0xFFD6336C.toInt())

        private const val PHOTO_MS: Long = 10_000L
        private const val FADE_MS: Long = 1_200L

        /** The time and date header at this fraction of its size, anchored at its bottom-left corner. */
        private const val HEADER_SCALE: Float = 0.75f
        private const val HEADER_PLATE_ALPHA: Int = 0x99
        private const val HEADER_FEATHER_DP: Int = 100

        /** Solid dark margin between the header's text and the plate's left and bottom edges (the corner), as it appears on screen. */
        private const val HEADER_EDGE_GAP_DP: Int = 16

        /** Same, for the top and right edges, which fade out beyond it. */
        private const val HEADER_FADE_SIDE_GAP_DP: Int = 10

        /** Empty space the font leaves under the time's baseline, before scaling: measured, not guessed. */
        private const val HEADER_BASELINE_INSET_DP: Int = 14

        /** The shared glow recipe (GlowBitmap.DREAM), one glow per photo, behind it. */
        fun dreamGlowBitmap(leftColor: Int, rightColor: Int, pair: Boolean, layout: SlideLayout): Bitmap =
            if (!pair) GlowBitmap.DREAM.draw(listOf(GlowSpot(0.5f, 0.5f, 0.6f, leftColor)))
            else GlowBitmap.DREAM.draw(listOf(GlowSpot(layout.leftCenter, 0.5f, 0.4f, leftColor), GlowSpot(layout.rightCenter, 0.5f, 0.4f, rightColor)))
    }
}
