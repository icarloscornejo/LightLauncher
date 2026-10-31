package com.prototype.lightlauncher

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.media.tv.TvInputManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.animation.AnimationUtils
import android.view.animation.LayoutAnimationController
import android.widget.FrameLayout
import android.widget.TextClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.prototype.lightlauncher.core.Appearance
import com.prototype.lightlauncher.core.DockOrder
import com.prototype.lightlauncher.core.GridOrder
import com.prototype.lightlauncher.core.Language
import com.prototype.lightlauncher.core.Orders
import com.prototype.lightlauncher.core.FocusAction
import com.prototype.lightlauncher.core.ReloadOrigin
import com.prototype.lightlauncher.core.ReloadRequest
import com.prototype.lightlauncher.core.ReloadRequests
import com.prototype.lightlauncher.core.ScanChange
import com.prototype.lightlauncher.data.AppCatalog
import com.prototype.lightlauncher.data.AppearanceStore
import com.prototype.lightlauncher.data.TopShelfSetting
import com.prototype.lightlauncher.data.Config
import com.prototype.lightlauncher.data.DockOrderStore
import com.prototype.lightlauncher.core.UpdateOffer
import com.prototype.lightlauncher.data.AlbumStatus
import com.prototype.lightlauncher.data.UpdateState
import com.prototype.lightlauncher.data.AndroidLogger
import com.prototype.lightlauncher.data.LanguageStore
import com.prototype.lightlauncher.data.LinkServer
import com.prototype.lightlauncher.data.LocalNetwork
import com.prototype.lightlauncher.data.LocalizedContext
import com.prototype.lightlauncher.data.ShelfItem
import com.prototype.lightlauncher.ui.AmbientShelf
import com.prototype.lightlauncher.ui.NeutralBackdrop
import com.prototype.lightlauncher.ui.ShelfBackdrops
import com.prototype.lightlauncher.ui.AppAdapter
import com.prototype.lightlauncher.ui.DockDecoration
import com.prototype.lightlauncher.ui.FocusMemory
import com.prototype.lightlauncher.ui.HomeContent
import com.prototype.lightlauncher.ui.HomeUiState
import com.prototype.lightlauncher.ui.HomeViewModel
import com.prototype.lightlauncher.ui.Motion
import com.prototype.lightlauncher.ui.NetworkPill
import com.prototype.lightlauncher.ui.SettingsMenu
import com.prototype.lightlauncher.ui.SettingsScreen
import com.prototype.lightlauncher.ui.SystemIntents
import com.prototype.lightlauncher.ui.TopShelfView
import com.prototype.lightlauncher.ui.TvInputs
import com.prototype.lightlauncher.ui.WallpaperLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * tvOS style home: black, a pinned dock row on a glass panel, an app grid, and the focused tile zoomed with a shadow.
 * The header and shelf live in their own controllers (com.prototype.lightlauncher.ui).
 */
class MainActivity : ComponentActivity(), AppAdapter.TileListener {

    private val container: AppContainer get() = (application as LightLauncherApp).container

    /** The app scan, off the main thread. Built from the application context only. */
    private val home: HomeViewModel by viewModels {
        viewModelFactory {
            initializer {
                val appContext: Context = applicationContext
                val config: Config = container.config
                val dockOrderStore = DockOrderStore(appContext)
                val languageStore = LanguageStore(appContext)
                // The view model outlives the activity, so a language change must not be baked into what it was built from:
                // every scan reads the setting again and reads its strings through a context in that language.
                val localized: () -> Context = { LocalizedContext.wrap(appContext, languageStore.read()) }
                val catalog = AppCatalog(appContext, localized, { DockOrder.reconcile(dockOrderStore.readDock(), config.dockApps) }, { dockOrderStore.readGrid() },
                    config.columns, config.hiddenApps.toSet(), config.showHomeSettingsShortcut,
                    ComponentName(appContext, HomeSettingsActivity::class.java))
                HomeViewModel({ HomeContent(catalog.load()) }, container.ioDispatcher)
            }
        }
    }

    /** Tiles per row and dock slots (res/values/config.xml, columns). */
    private var columns: Int = 4
    private var density: Float = 1f
    /** Calmer motion for weak GPUs (config.xml, reduced_motion): short fades, no replay. */
    private var reducedMotion: Boolean = false
    /** The owner left through the settings screen (an app opened from it): the next restore starts Home over at the clock. */
    private var returnedFromSettings: Boolean = false
    /** The scan on screen, so a state that only cleared its duty does not rebind the grid. */
    private var shownContent: HomeContent? = null
    /** D-pad presses so far: a deferred focus restore that finds this changed leaves the owner's move alone. */
    private var keyPresses: Long = 0
    private var fullyDrawnReported: Boolean = false

    /** The appearance setting and the theme it resolved to when this activity was created. */
    private var appearance: Appearance = Appearance.DARK
    private var lightTheme: Boolean = false

    /** The owner's "Top shelf content" switch. Off: plain background, nothing read or downloaded. */
    private var topShelfEnabled: Boolean = false

    /** Where the dock panel's top edge was when a dock tile last took the focus (screen coordinates): the shelf sits above it. */
    private var dockPanelTop: Float = 0f

    /**
     * Set just before recreate() for a theme or language change, so the new activity reopens the settings on the page
     * and the row they were on. onPause closes the screen before onSaveInstanceState runs, so they are taken first.
     */
    private var reopenPage: String? = null
    private var reopenRow: String? = null

    /** The language setting, and the one this activity was created with (the screen recreates it when they differ). */
    private var language: Language = Language.AUTO
    private var appliedLanguage: Language = Language.AUTO

    /**
     * Move mode (long press on a dock app or a grid app): the key of the tile being moved, or null when
     * the mode is off; whether the press that opened it has been released (only the next full OK press ends the mode);
     * and the dock being edited, the same list [DockOrder.reconcile] gives the catalog, so a package missing from the TV
     * keeps its slot. The grid order is read from the adapter at every step, since its tiles are all there.
     */
    private var movingKey: String? = null
    private var moveArmed: Boolean = false
    private var moveDock: List<String> = emptyList()
    private lateinit var dockOrderStore: DockOrderStore
    private lateinit var moveBack: OnBackPressedCallback

    private lateinit var adapter: AppAdapter
    private lateinit var grid: RecyclerView
    private lateinit var root: FrameLayout
    private lateinit var content: FrameLayout
    private lateinit var clockPill: TextClock
    private lateinit var settings: SettingsScreen
    private lateinit var settingsMenu: SettingsMenu
    private lateinit var settingsBack: OnBackPressedCallback
    private lateinit var backdrops: ShelfBackdrops
    private lateinit var topShelf: TopShelfView
    private lateinit var networkPill: NetworkPill

    private val handler = Handler(Looper.getMainLooper())

    /** Refreshes the network pill when the connection changes: at boot Wi-Fi often connects after Home is shown. */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handler.post { networkPill.update() }
        }

        override fun onLost(network: Network) {
            handler.post { networkPill.update() }
        }
    }

    /** Listens for any VPN coming up or going down, even one this app's traffic is excluded from. */
    private val vpnCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handler.post { networkPill.update() }
        }

        override fun onLost(network: Network) {
            handler.post { networkPill.update() }
        }
    }

    /** Shows the Inputs row once the TV registers an input: at boot HDMI inputs can appear after Home is shown. */
    private val inputCallback = object : TvInputManager.TvInputCallback() {
        override fun onInputAdded(inputId: String) {
            loadInputs()
        }

        override fun onInputRemoved(inputId: String) {
            loadInputs()
        }
    }

    private val packageFilter = IntentFilter().apply {
        addAction(Intent.ACTION_PACKAGE_ADDED)
        addAction(Intent.ACTION_PACKAGE_REMOVED)
        addAction(Intent.ACTION_PACKAGE_CHANGED)
        addDataScheme("package")
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Updates send REMOVED followed by ADDED; keep the tile until the new APK is ready.
            if (intent.action == Intent.ACTION_PACKAGE_REMOVED && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
            requestReload(ReloadOrigin.PACKAGE, animate = false, restoreFocus = grid.hasFocus())
        }
    }

    private val minuteTick: Runnable = object : Runnable {
        override fun run() {
            networkPill.update()
            handler.postDelayed(this, MINUTE_MS)
        }
    }

    /**
     * Forces the night bit of the configuration from the Appearance setting, so the colour tokens
     * (values/ is dark, values-notnight/ is light) follow the owner's choice and not what the TV reports. It runs
     * before the Activity has an application, so it reads the setting from [newBase] alone; the container does not
     * exist yet. The language setting is applied here too ([LocalizedContext]). Everything else in the configuration,
     * TV type included, is kept.
     */
    override fun attachBaseContext(newBase: Context) {
        appearance = AppearanceStore(newBase).read()
        lightTheme = appearance.isLight
        language = LanguageStore(newBase).read()
        appliedLanguage = language
        val configuration = Configuration(newBase.resources.configuration)
        val night: Int = if (lightTheme) Configuration.UI_MODE_NIGHT_NO else Configuration.UI_MODE_NIGHT_YES
        configuration.uiMode = (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        LocalizedContext.configure(configuration, language)
        super.attachBaseContext(newBase.createConfigurationContext(configuration))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        density = resources.displayMetrics.density
        // config.xml, overlaid by config.json from a pushed theme (see Config and tools/theme.sh).
        val config: Config = container.config // loaded once per process
        columns = config.columns
        reducedMotion = config.reducedMotion

        root = findViewById(R.id.root)
        // The theme's background colour and wallpaper are made for the dark look, so they wait for it. A theme that brings
        // its own keeps it: the blurred picture of the shelf would cover it for good, so it is not drawn then.
        val themeHasBackground: Boolean = config.wallpaper != null || config.backgroundColor != DEFAULT_BACKGROUND
        val themeOwnsBackground: Boolean = !lightTheme && themeHasBackground
        if (lightTheme && themeHasBackground) Log.i(TAG, "background_color and wallpaper only apply in the dark appearance")
        if (themeOwnsBackground) {
            root.setBackgroundColor(config.backgroundColor)
            config.wallpaper?.let { showWallpaper(it) }
        } else {
            // A neutral backdrop per theme; it does not take the colour of the focused app.
            root.background = BitmapDrawable(resources, NeutralBackdrop.bitmap(getColor(R.color.home_bg_top), getColor(R.color.home_bg_bottom)))
                .apply { gravity = Gravity.FILL }
        }
        backdrops = ShelfBackdrops(
            AmbientShelf(findViewById(R.id.ambient), findViewById(R.id.ambientNext), reducedMotion),
            java.io.File(cacheDir, "backdrop"), lifecycleScope, container.ioDispatcher, resources, lightTheme, themeOwnsBackground,
            getColor(R.color.home_bg_bottom),
        )
        topShelf = TopShelfView(
            findViewById(R.id.topShelf), findViewById(R.id.shelfHero), findViewById(R.id.shelfHeroNext), findViewById(R.id.shelfScrim),
            container.topShelf, container.shelfImages, backdrops, lifecycleScope, container.ioDispatcher, density, reducedMotion,
            onCardOpen = ::openShelfCard,
            onCardKey = { keyCode ->
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) clockPill.requestFocus() else focusTile(FocusMemory.slot)
            },
            onFocusLost = { focusTile(FocusMemory.slot) },
            onStage = ::stageCards,
        )
        topShelfEnabled = TopShelfSetting(this).isEnabled()
        backdrops.enabled = topShelfEnabled
        backdrops.loadStored()
        content = findViewById(R.id.content)
        clockPill = findViewById(R.id.clockPill)
        settings = SettingsScreen(findViewById(R.id.settingsScreen), content, density, reducedMotion)

        grid = findViewById(R.id.grid)
        grid.layoutManager = GridLayoutManager(this, columns)
        grid.setHasFixedSize(true)
        // Only moves are animated (move mode slides the tiles); reloads use notifyDataSetChanged, which no animator
        // touches. No change animation: crossing dock and grid rebinds a tile with a payload, and a cross-fade would
        // swap its view for another and take the focus away. Stable ids keep the moved tile's view, so its focus too.
        grid.itemAnimator = if (reducedMotion) null else DefaultItemAnimator().apply {
            supportsChangeAnimations = false
            moveDuration = Motion.scaled(this@MainActivity, MOVE_MODE_SLIDE_MS)
            addDuration = 0L
            removeDuration = 0L
            changeDuration = 0L
        }
        grid.addItemDecoration(DockDecoration(columns, density, getColor(R.color.dock_fill), getColor(R.color.dock_stroke)))
        val enterAnimation: Int = if (reducedMotion) R.anim.tile_enter_reduced else R.anim.tile_enter
        val enterDelay: Float = if (reducedMotion) 0f else 0.06f
        grid.layoutAnimation = LayoutAnimationController(AnimationUtils.loadAnimation(this, enterAnimation), enterDelay)
        adapter = AppAdapter(this, this, columns, reducedMotion)
        grid.adapter = adapter

        // tvOS layout: push the dock row towards the bottom of the screen, leaving room for rows_below_dock grid
        // rows under it; the rest of the grid stays below until you press Down.
        val rowsBelowDock: Int = config.rowsBelowDock
        grid.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            // Item height follows the tile size, which follows the column count (AppAdapter.tileWidthPx).
            val inner: Int = (right - left) - grid.paddingLeft - grid.paddingRight
            val itemHeight: Int = AppAdapter.itemHeightPx(AppAdapter.tileHeightPx(AppAdapter.tileWidthPx(inner, columns, density)), density)
            val dockRow: Int = itemHeight + ((DockDecoration.TOP_DP + DockDecoration.BOTTOM_DP) * density).toInt() // item height plus DockDecoration offsets
            val wanted: Int = Math.max(0, (bottom - top) - dockRow - itemHeight * rowsBelowDock - (30 * density).toInt())
            if (grid.paddingTop != wanted) {
                grid.setPadding(grid.paddingLeft, wanted, grid.paddingRight, grid.paddingBottom)
                // A dock tile that took the focus before this padding existed placed the shelf (and the scroll) for a grid
                // that was not where it ended up (the first layout after a recreate): place them again now.
                if (grid.hasFocus()) grid.post { focusTile(FocusMemory.slot) }
            }
        }

        // Wi-Fi and Inputs are rows of the settings screen: these classes hold the view of their row
        // (the Wi-Fi name, the Inputs row that hides itself).
        settingsMenu = SettingsMenu(this, settings, TvInputs(this, config), settingsHost)
        networkPill = NetworkPill(this, settings.valueView(SettingsMenu.WIFI_ROW), settings.iconView(SettingsMenu.WIFI_ROW), config.vpnApp, density)
        clockPill.setOnClickListener { openSettings() }
        // The update is the process's, not this activity's: the row follows it, also after a theme or language change.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.updater.state.collect { state ->
                    settingsMenu.refreshUpdate()
                    if (state is UpdateState.Failed) AccessibilityRestore.clear(this@MainActivity)
                }
            }
        }
        // The first start after an update: the accessibility services Android switched off are turned on again.
        if (!AccessibilityRestore.restoreIfUpdated(this, container.installedVersion())) {
            Toast.makeText(this, R.string.update_services_manual, Toast.LENGTH_LONG).show()
        }
        val reopenAt: String? = savedInstanceState?.getString(STATE_REOPEN_PAGE)
        if (reopenAt != null) {
            // A theme or language change just recreated the activity: back to the page and row the owner was on.
            val reopenOn: String? = savedInstanceState.getString(STATE_REOPEN_ROW)
            grid.post { openSettings(reopenAt, reopenOn) }
        }

        // Keep focus on the clock pill at its edges: Android's focus finder would otherwise jump to a tile far
        // below when there is nothing in that direction.
        clockPill.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> true
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    // The "Play Next" cards when the shelf has openable ones; else back to the dock tile Up came from,
                    // not wherever the focus finder lands.
                    if (!topShelf.focusFirstCard()) focusTile(FocusMemory.slot % columns)
                    true
                }
                else -> false
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { home.state.collect(::apply) }
        }

        // Home screen: Back goes to the first dock app and stays there.
        onBackPressedDispatcher.addCallback(this) { goToFirstDockTile() }
        // Added after the no-op above, so it runs first while the settings screen is open: up one page, then closed.
        settingsBack = onBackPressedDispatcher.addCallback(this, enabled = false) {
            if (!settings.back()) {
                closeSettings(restoreFocus = false)
                resetHomeAtClock()
            }
        }
        // Registered last, so it runs first while move mode is on. Android 16 and later send Back here and not through dispatchKeyEvent.
        dockOrderStore = DockOrderStore(this)
        moveBack = onBackPressedDispatcher.addCallback(this, enabled = false) { endMove(reload = true) }

        (getSystemService(TV_INPUT_SERVICE) as TvInputManager?)?.registerCallback(inputCallback, handler)
        getSystemService(ConnectivityManager::class.java)?.let { connectivity ->
            connectivity.registerDefaultNetworkCallback(networkCallback)
            connectivity.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build(),
                vpnCallback,
            )
        }
    }

    /** A theme's wallpaper is decoded off the main thread; the plain colour shows until then. */
    private fun showWallpaper(file: java.io.File) {
        val metrics = resources.displayMetrics
        lifecycleScope.launch {
            val bitmap: Bitmap? = withContext(container.ioDispatcher) { WallpaperLoader.decode(file, metrics.widthPixels, metrics.heightPixels) }
            if (bitmap != null) WallpaperLoader.show(root, bitmap)
        }
    }

    /**
     * Home pressed while Home is already in front: singleTask delivers it here, then onResume runs. Back to the
     * top with the first dock tile focused, like the stock launcher. Back from an app never comes this way, so
     * returning to the app you left from is unchanged (#118).
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!intent.hasCategory(Intent.CATEGORY_HOME)) return
        // Home pressed on Home does not pause the activity, so onResume will not move the focus: do it here.
        endMove(reload = true)
        closeSettings(restoreFocus = false)
        goToFirstDockTile()
    }

    /**
     * Back and Home on the home screen go to the first dock app, from a grid app or the clock pill, with
     * the grid scrolled back to the top so the dock row is where it started and its top shelf shows. Already on that
     * tile, it does nothing.
     */
    private fun goToFirstDockTile() {
        endMove(reload = true)
        val first: Int = if (adapter.itemCount > 0) focusablePosition(0) else -1
        val alreadyThere: Boolean = grid.hasFocus() && FocusMemory.slot == first
        if (alreadyThere) return
        // What a later restore (onResume after an app) goes back to, in case the focus cannot move right now.
        FocusMemory.slot = 0
        FocusMemory.key = if (adapter.itemCount > 0) adapter.keyAt(0) else null
        grid.stopScroll()
        grid.scrollToPosition(0)
        grid.post { focusTile(0) }
    }

    /**
     * The settings screen (opened from the clock pill) is the last place the owner pressed OK, so leaving it
     * puts Home back as it starts, with the focus on that pill: the first dock app is the one the home shows (its shelf up,
     * the grid scrolled to the dock) and the pill, not a tile, holds the focus. Without it, coming back from a theme or
     * language change would leave the grid at the top, with no shelf, until a key was pressed.
     */
    private fun resetHomeAtClock() {
        endMove(reload = true)
        val first: Int = if (adapter.itemCount > 0) focusablePosition(0) else -1
        FocusMemory.slot = Math.max(first, 0)
        FocusMemory.key = if (first >= 0) adapter.keyAt(first) else null
        grid.stopScroll()
        grid.scrollToPosition(0)
        grid.post {
            if (first >= 0) focusTile(first)
            // The tile takes the focus at once when it is laid out; if it has to wait for a scroll it keeps it when it comes.
            if (first < 0 || grid.hasFocus()) clockPill.requestFocus()
        }
    }

    /** Restores [WANTED_ANIMATOR_SCALE] when something else changed it. */
    private fun enforceAnimatorScale() {
        val current: Float = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, -1f)
        if (Math.abs(current - WANTED_ANIMATOR_SCALE) < 0.001f) return
        try {
            Settings.Global.putFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, WANTED_ANIMATOR_SCALE)
        } catch (e: SecurityException) {
            Log.w(TAG, "animator scale left at $current: WRITE_SECURE_SETTINGS not granted", e)
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh the screensaver photos at most once a day.
        container.albumSync.requestSync(container.albumLink, "home")
        refreshTopShelf()
        enforceAnimatorScale()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packageReceiver, packageFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(packageReceiver, packageFilter)
        }
        requestReload(ReloadOrigin.RESUME, animate = true, restoreFocus = true)
        networkPill.update()
        loadInputs()
        handler.postDelayed(minuteTick, MINUTE_MS)
    }

    /**
     * Reads what the dock apps publish to the TV provider, off the main thread, on every resume. When it
     * differs from the last read and a dock app has the focus, that app's shelf is redrawn with the new data.
     */
    private fun refreshTopShelf() {
        if (!topShelfEnabled) return
        val dockPackages: Set<String> = currentDock().toSet()
        container.processScope.launch(container.ioDispatcher) {
            val changed: Boolean = ShelfFeature.run(false) { container.topShelf.refresh(dockPackages) }
            if (changed) withContext(Dispatchers.Main) { redrawTopShelf() }
            // Then, quietly, the pictures each app will be asked for first, so moving along the dock does not wait for them.
            topShelf.prefetch(dockPackages)
        }
    }

    /** Shows the shelf for the focused dock tile again, if a dock tile has the focus. */
    private fun redrawTopShelf() {
        val position: Int = FocusMemory.slot
        if (topShelfEnabled && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && grid.hasFocus() && position < columns && !adapter.isVacant(position)) {
            // What the app publishes changed: redraw even if it is the shelf already on screen.
            topShelf.show(adapter.packageAt(position), adapter.largeIconAt(position), dockPanelTop, force = true)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) keyPresses++
        if (movingKey != null && handleMoveKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    /**
     * OK on a "Play Next" card: opens what the focused dock app published for it. The shelf guard wraps it like every
     * call into the shelf code; [SystemIntents.openPublished] keeps its own failures, so one bad intent cannot switch it off.
     */
    private fun openShelfCard(item: ShelfItem) {
        val position: Int = FocusMemory.slot
        val label: String = item.title.ifBlank { adapter.labelAt(position) }
        ShelfFeature.run(Unit) { SystemIntents.openPublished(this, item.intentUri, adapter.packageAt(position), label) }
    }

    /**
     * The row of "Play Next" cards has the focus (or gives it back): the dock behind it dims and drops a little, so the
     * row is what the eye goes to, like a main row of content. The grid wrapper holds the dock and the grid; the clock
     * pill is not part of it.
     */
    private fun stageCards(on: Boolean) {
        val wrapper: View = findViewById(R.id.gridWrapper)
        val shift: Float = if (on) 52 * density else 0f
        val alpha: Float = if (on) 0.4f else 1f
        wrapper.animate().cancel()
        // A hardware layer for as long as the dock is dimmed: its alpha is then a texture drawn once and composed, not a
        // full screen off screen copy made again every frame the row of cards moves.
        if (reducedMotion) {
            wrapper.translationY = shift
            wrapper.alpha = alpha
            wrapper.setLayerType(if (on) View.LAYER_TYPE_HARDWARE else View.LAYER_TYPE_NONE, null)
            return
        }
        wrapper.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        wrapper.animate().translationY(shift).alpha(alpha)
            .setDuration(Motion.scaled(this, Motion.MOVE_MS))
            .setInterpolator(Motion.EASE)
            // Only when it really got back to full (a cut short animation is followed by another that wants the layer).
            .withEndAction { if (!on && wrapper.alpha >= 1f) wrapper.setLayerType(View.LAYER_TYPE_NONE, null) }
            .start()
    }

    // ---- move mode -------------------------------------------------------------------------------

    /** The dock as it is now: what the owner arranged on the TV, else `dock_apps`. */
    private fun currentDock(): List<String> = DockOrder.reconcile(dockOrderStore.readDock(), container.config.dockApps)

    /**
     * A long press on a dock app or a grid app starts move mode. The optional Default home tile is not an app to
     * arrange: it goes on to the details screen, like an empty slot does not.
     */
    override fun onTileLongPress(position: Int): Boolean {
        if (adapter.isVacant(position)) return false
        val dock: List<String> = currentDock()
        if (position < columns) {
            // The tile and the list must agree on who sits where, or a swap would save the wrong order: do nothing then.
            if (adapter.packageAt(position) != dock.getOrNull(position)) return true
        } else if (adapter.keyAt(position) !in adapter.gridKeys()) {
            return false
        }
        val tile: View = grid.findViewHolderForAdapterPosition(position)?.itemView?.findViewById(R.id.card) ?: return true
        movingKey = adapter.keyAt(position)
        moveArmed = false
        moveDock = dock
        moveBack.isEnabled = true
        adapter.startMoving(tile)
        return true
    }

    /**
     * While move mode is on only the four directions and the confirm keys are taken here (volume and media keys go on
     * as usual). An arrow moves the tile once per press (see [moveTile]); Up on a dock app opens its details instead,
     * since nothing is above the dock; OK or Back ends it. The release of the press that opened the mode goes through
     * to the tile, which clears its pressed look.
     */
    private fun handleMoveKey(event: KeyEvent): Boolean {
        val keyCode: Int = event.keyCode
        if (keyCode in CONFIRM_KEYS) {
            if (event.action == KeyEvent.ACTION_UP) {
                if (!moveArmed) {
                    moveArmed = true
                    return false
                }
                endMove(reload = true)
            }
            return true
        }
        val single: Boolean = event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (single) moveTile(keyCode)
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (single) {
                    val position: Int = adapter.positionOf(movingKey)
                    if (position in 0 until columns) {
                        val packageName: String = adapter.packageAt(position)
                        endMove(reload = false) // the details screen pauses Home, and the resume reloads
                        adapter.openDetails(packageName)
                    } else {
                        moveTile(keyCode)
                    }
                }
                return true
            }
            else -> return false
        }
    }

    /**
     * Where a move ends up: the lists to save, the position the tile goes to, and whether it trades places, is inserted,
     * or fills an empty dock slot (the slot is dropped and the grid is one tile shorter).
     */
    private class MovePlan(val dock: List<String>, val grid: List<String>, val target: Int, val trade: Boolean, val fill: Boolean = false)

    /** The package of a tile key, which is its component flattened ("package/.Class"). */
    private fun packageOfKey(key: String): String = key.substringBefore('/')

    /**
     * One arrow press in move mode. In the dock, Left and Right trade places with the next slot (an empty one
     * included) and Down trades with the grid app below, which takes the dock slot. In the grid, the tile is
     * inserted one place (Left, Right) or one row (Up, Down) over and the others move along; Up from the first row
     * trades with the dock app above, or fills the empty slot above. The lists are saved at every step, before the tile moves, so nothing is lost if
     * a reload or Home comes halfway through and a failed save moves nothing.
     */
    private fun moveTile(keyCode: Int) {
        val position: Int = adapter.positionOf(movingKey)
        if (position < 0) {
            endMove(reload = true)
            return
        }
        val gridKeys: List<String> = adapter.gridKeys()
        val plan: MovePlan = (if (position < columns) planInDock(position, keyCode, gridKeys) else planInGrid(position, keyCode, gridKeys)) ?: return
        if (!dockOrderStore.write(plan.dock, plan.grid)) {
            Toast.makeText(this, R.string.order_not_saved, Toast.LENGTH_SHORT).show()
            return
        }
        moveDock = plan.dock
        if (plan.fill) adapter.fill(plan.target, position) else if (plan.trade) adapter.swap(position, plan.target) else adapter.move(position, plan.target)
        FocusMemory.slot = plan.target
        if (position / columns != plan.target / columns) {
            // A new row, or the other side of the dock: the screen has to follow the tile, and the shelf the zone it is in.
            grid.post {
                val tile: View? = grid.findViewHolderForAdapterPosition(plan.target)?.itemView?.findViewById(R.id.card)
                if (tile != null && movingKey != null) onTileFocused(plan.target, tile)
            }
        }
    }

    private fun planInDock(position: Int, keyCode: Int, gridKeys: List<String>): MovePlan? {
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            // The grid app of the same column, in the first row under the dock.
            val key: String = adapter.keyAt(position) ?: return null
            val crossed: Orders = DockOrder.swapAcross(moveDock, gridKeys, position, position, key, ::packageOfKey) ?: return null
            return MovePlan(crossed.dock, crossed.grid, columns + position, trade = true)
        }
        val direction: Int = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -1
            KeyEvent.KEYCODE_DPAD_RIGHT -> 1
            else -> return null
        }
        // Local change: an empty slot is a place like any other, so a dock app can be moved into it (before 1.0.1 the
        // move stepped over vacancies and a TV with fewer apps than slots could never fill them).
        val target: Int = position + direction
        if (target !in 0 until Math.min(columns, adapter.itemCount) || target !in moveDock.indices) return null
        return MovePlan(DockOrder.swap(moveDock, position, target), gridKeys, target, trade = true)
    }

    private fun planInGrid(position: Int, keyCode: Int, gridKeys: List<String>): MovePlan? {
        val index: Int = position - columns
        if (index !in gridKeys.indices) return null
        val last: Int = gridKeys.size - 1
        val destination: Int = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> index - 1
            KeyEvent.KEYCODE_DPAD_RIGHT -> index + 1
            KeyEvent.KEYCODE_DPAD_DOWN -> Math.min(index + columns, last)
            KeyEvent.KEYCODE_DPAD_UP -> index - columns
            else -> return null
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP && index < columns) {
            // First row of the grid: the dock slot above it, in the same column. An empty slot is filled by the tile.
            if (adapter.isVacant(index)) {
                val filled: Orders = DockOrder.fillVacancy(moveDock, gridKeys, index, index, ::packageOfKey) ?: return null
                return MovePlan(filled.dock, filled.grid, index, trade = false, fill = true)
            }
            val key: String = adapter.keyAt(index) ?: return null
            val crossed: Orders = DockOrder.swapAcross(moveDock, gridKeys, index, index, key, ::packageOfKey) ?: return null
            return MovePlan(crossed.dock, crossed.grid, index, trade = true)
        }
        if (destination !in 0..last || destination == index) return null
        return MovePlan(moveDock, GridOrder.move(gridKeys, index, destination), columns + destination, trade = false)
    }

    /**
     * Leaves move mode. [reload] asks for a fresh scan, so a scan that started before the last move, and would put the
     * old order back, is replaced by one that reads the saved order.
     */
    private fun endMove(reload: Boolean) {
        if (movingKey == null) return
        movingKey = null
        moveArmed = false
        moveBack.isEnabled = false
        adapter.stopMoving()
        if (reload) {
            requestReload(ReloadOrigin.DECISION, animate = false, restoreFocus = false)
            refreshTopShelf() // an app moved into the dock needs its shelf read
        }
    }

    /** Rebuilds the tiles off the main thread; [apply] puts focus back where it was. */
    private fun requestReload(origin: ReloadOrigin, animate: Boolean, restoreFocus: Boolean) {
        home.reload(ReloadRequest(origin, animate, restoreFocus))
    }

    /** Shows a delivered scan, then does what its requests asked for, once, and confirms it. */
    private fun apply(state: HomeUiState) {
        val content: HomeContent = state.content ?: return
        val duty = state.duty
        val action: FocusAction? = duty?.let {
            ReloadRequests.focusAction(it, focusOnHeaderOrPopup = !grid.hasFocus(), popupOrDialogOpen = settings.isOpen)
        }
        // Coming back to Home scans again, but when the scan shows the same apps the tiles are left as they
        // are, and so is the shelf (it stays where it was, see TopShelfView.pause), so nothing reloads in front of the owner.
        var rebound = false
        if (content !== shownContent) {
            val rebind: Boolean = ScanChange.needsRebind(duty?.origins.orEmpty(), shownContent?.signature, content.signature)
            shownContent = content
            if (rebind) {
                // A new scan replaces the tiles under the moving one: the mode ends, and its own reload brings the saved order.
                endMove(reload = true)
                // Read these before submit(): rebinding can move focus to the first tile and overwrite them.
                val returnKey: String? = FocusMemory.key
                val returnSlot: Int = FocusMemory.slot
                adapter.submit(content.tiles)
                FocusMemory.key = returnKey
                FocusMemory.slot = returnSlot
                rebound = true
            }
        }
        if (duty == null) return
        if (duty.animate && !reducedMotion && rebound) grid.scheduleLayoutAnimation()
        if (action == FocusAction.RESTORE) {
            // Follow the same app, or the nearest remaining slot when it was removed.
            restoreFocus()
        } else {
            // A package change must not steal focus from a top button or the Inputs popup.
            val position: Int = adapter.positionOf(FocusMemory.key)
            FocusMemory.slot = Math.max(0, Math.min(if (position >= 0) position else FocusMemory.slot, adapter.itemCount - 1))
            FocusMemory.key = adapter.keyAt(FocusMemory.slot)
            if (position < 0) {
                backdrops.clear()
                topShelf.hide(toGrid = false)
            }
        }
        home.acknowledge(duty)
    }

    /**
     * Return to the app we left from, or the same slot if that app is gone. Always refocus: after a reload the
     * view that holds focus may no longer show the app it had.
     */
    private fun restoreFocus() {
        val returnKey: String? = FocusMemory.key
        val returnSlot: Int = FocusMemory.slot
        val presses: Long = keyPresses
        grid.post {
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@post
            if (keyPresses == presses) {
                if (returnedFromSettings) {
                    returnedFromSettings = false
                    resetHomeAtClock()
                } else {
                    val position: Int = adapter.positionOf(returnKey)
                    focusTile(if (position >= 0) position else returnSlot)
                }
            }
            if (!fullyDrawnReported) {
                fullyDrawnReported = true
                reportFullyDrawn()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(packageReceiver)
        } catch (e: IllegalArgumentException) {
            // Paired with onResume, so this only happens if registering failed there; nothing to undo.
            Log.w(TAG, "package receiver was not registered")
        }
        handler.removeCallbacks(minuteTick)
        endMove(reload = false) // the resume reloads
        // The settings screen stays up while the next app is still coming (it takes a third of a second to draw): closing
        // it here showed the home in between. onStop closes it. The QR page holds a port open for a phone, so it goes now.
        if (settings.currentPageId == SettingsMenu.QR_PAGE) closeSettings(restoreFocus = false)
        topShelf.pause() // the carousel must not keep downloading and animating behind another app; the resume redraws it
    }

    /** The next app is on screen: the settings screen, kept up until now so the home never showed through, goes. */
    override fun onStop() {
        super.onStop()
        // Another app took over from the settings (TV settings, Wi-Fi): coming back, Home starts over at the clock.
        if (settings.isOpen) returnedFromSettings = true
        closeSettings(restoreFocus = false)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLinkReceiver()
        (getSystemService(TV_INPUT_SERVICE) as TvInputManager?)?.unregisterCallback(inputCallback)
        getSystemService(ConnectivityManager::class.java)?.let { connectivity ->
            connectivity.unregisterNetworkCallback(networkCallback)
            connectivity.unregisterNetworkCallback(vpnCallback)
        }
    }

    // ---- focus -----------------------------------------------------------------------------------

    override fun onTileFocused(position: Int, card: View) {
        FocusMemory.slot = position
        FocusMemory.key = adapter.keyAt(position)
        val item = card.parent as View
        if (position < columns) {
            // Dock: park it at the bottom of the screen and show the top shelf for this app.
            val tileTop = Rect(0, 0, card.width, card.height)
            root.offsetDescendantRectToMyCoords(card, tileTop)
            val decoratedTop: Int = item.top - (DockDecoration.TOP_DP * density).toInt()
            val dy: Int = decoratedTop - grid.paddingTop
            // A 1px mismatch would start a scroll on every dock move. Only scroll when the row is actually off;
            // reduced motion does it in one jump so it does not fight the highlight.
            val scroll: Boolean = Math.abs(dy) > (8 * density).toInt()
            if (scroll) {
                if (reducedMotion) grid.scrollBy(0, dy) else grid.smoothScrollBy(0, dy)
            }
            dockPanelTop = (if (scroll) tileTop.top - dy else tileTop.top) - DockDecoration.PANEL_PAD_DP * density
            if (topShelfEnabled) {
                topShelf.show(adapter.packageAt(position), adapter.largeIconAt(position), dockPanelTop)
            } else {
                topShelf.hide(toGrid = false)
                backdrops.clear()
            }
        } else {
            // Grid tiles have no top shelf. The one that was up fades into the blurred backdrop, which stays.
            topShelf.hide()
            // Scroll just enough so the whole zoomed tile, its shadow and its name are visible.
            val pad: Int = (FOCUS_SCROLL_PAD_DP * density).toInt()
            grid.requestChildRectangleOnScreen(item, Rect(0, -pad * 2, item.width, item.height + pad), reducedMotion)
        }
    }

    // ---- navigation ------------------------------------------------------------------------------

    override fun onTileVerticalKey(position: Int, keyCode: Int): Boolean {
        val count: Int = adapter.itemCount
        val rowStart: Int = position - position % columns
        // Horizontal: swallow the key at a row's ends so focus never wraps to the next row or escapes up into
        // the top bar. Moves are explicit so they step over the empty dock slots (a favourite
        // that is not installed), which the default focus search would not know to keep on this row.
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            val target: Int = (position - 1 downTo rowStart).firstOrNull { !adapter.isVacant(it) } ?: return true
            focusTile(target)
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            val rowEnd: Int = Math.min(rowStart + columns, count) - 1
            val target: Int = (position + 1..rowEnd).firstOrNull { !adapter.isVacant(it) } ?: return true
            focusTile(target)
            return true
        }
        val target: Int
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            // Dock row: the "Play Next" cards above it when there are openable ones, else let focus move to the top bar.
            if (position < columns) return topShelf.focusFirstCard()
            target = position - columns
        } else {
            val lastRowStart: Int = ((count - 1) / columns) * columns
            if (position >= lastRowStart) return true // already on the last row: stay put
            target = Math.min(position + columns, count - 1)
        }
        focusTile(target)
        return true
    }

    /**
     * [position], or the nearest tile on its row that can take focus when it is an empty dock slot. When the whole
     * dock row is empty (none of the favourites is installed) the first tile after it; -1 if there is none.
     */
    private fun focusablePosition(position: Int): Int {
        if (!adapter.isVacant(position)) return position
        val rowStart: Int = position - position % columns
        val rowEnd: Int = Math.min(rowStart + columns, adapter.itemCount)
        var best: Int = -1
        for (candidate in rowStart until rowEnd) {
            if (adapter.isVacant(candidate)) continue
            if (best == -1 || Math.abs(candidate - position) < Math.abs(best - position)) best = candidate
        }
        if (best == -1) {
            for (candidate in rowEnd until adapter.itemCount) {
                if (!adapter.isVacant(candidate)) return candidate
            }
        }
        return best
    }

    private fun focusTile(position: Int) {
        if (adapter.itemCount == 0) {
            clockPill.requestFocus()
            return
        }
        val clamped: Int = focusablePosition(Math.max(0, Math.min(position, adapter.itemCount - 1)))
        if (clamped < 0) {
            clockPill.requestFocus() // the whole dock row is empty
            return
        }
        val holder: RecyclerView.ViewHolder? = grid.findViewHolderForAdapterPosition(clamped)
        if (holder != null) {
            val card: View = holder.itemView.findViewById(R.id.card)
            // Already focused (e.g. back from an app): no focus change fires, so redo the shelf by hand.
            if (card.isFocused) onTileFocused(clamped, card) else card.requestFocus()
            return
        }
        // Not laid out yet: scroll there, then focus once the scroll settles, unless the owner moved meanwhile.
        val presses: Long = keyPresses
        grid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return
                recyclerView.removeOnScrollListener(this)
                if (keyPresses != presses || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
                val settled: RecyclerView.ViewHolder? = recyclerView.findViewHolderForAdapterPosition(clamped)
                val target: View? = settled?.itemView?.findViewById(R.id.card) ?: recyclerView.getChildAt(0)
                target?.requestFocus()
            }
        })
        grid.smoothScrollToPosition(clamped)
    }

    // ---- settings screen -------------------------------------------------------------------------

    /** Reads the TV's inputs off the main thread (it asks the system several times) so the Inputs row opens at once. */
    private fun loadInputs() {
        lifecycleScope.launch {
            val (entries, available) = withContext(container.ioDispatcher) { settingsMenu.readInputs() }
            settingsMenu.setInputs(entries, available)
        }
    }

    /** Opens the settings over the home screen, on [pageId] (and [rowId]) when a recreate puts them back where they were. */
    private fun openSettings(pageId: String = SettingsMenu.MAIN_PAGE, rowId: String? = null) {
        loadInputs() // the states of the ports are read again; the list already on screen is the last one known
        settingsMenu.refresh()
        settings.open(clockPill, pageId, rowId)
        settingsBack.isEnabled = true
    }

    /** [restoreFocus] is false when onPause or a Home press closes it: the resume brings the focus back. */
    private fun closeSettings(restoreFocus: Boolean) {
        settingsBack.isEnabled = false
        settings.close(restoreFocus)
    }

    /** What the rows of the settings do; the rows themselves are in [SettingsMenu]. */
    private val settingsHost: SettingsMenu.Host = object : SettingsMenu.Host {
        override fun openWifi() = networkPill.open()

        override fun openWifiSettings(): Boolean {
            SystemIntents.open(this@MainActivity, SystemIntents.forAction(this@MainActivity, Settings.ACTION_WIFI_SETTINGS))
            return true
        }

        override fun openTvSettings() =
            SystemIntents.open(this@MainActivity, SystemIntents.forAction(this@MainActivity, Settings.ACTION_SETTINGS))

        override fun appearance(): Appearance = this@MainActivity.appearance

        override fun chooseAppearance(value: Appearance) = this@MainActivity.chooseAppearance(value)

        override fun language(): Language = this@MainActivity.language

        override fun chooseLanguage(value: Language) = this@MainActivity.chooseLanguage(value)

        override fun homeContentOn(): Boolean = topShelfEnabled

        override fun toggleHomeContent() = toggleTopShelf()

        override fun previewScreensaver() = this@MainActivity.previewScreensaver()

        override fun albumStatus(): AlbumStatus = container.albumSync.status()

        override fun albumHasLink(): Boolean = container.albumLink().isNotBlank()

        override fun startLinkReceiver(onReceived: () -> Unit): String? = this@MainActivity.startLinkReceiver(onReceived)

        override fun stopLinkReceiver() = this@MainActivity.stopLinkReceiver()

        override fun syncAlbumNow(done: () -> Unit) {
            container.processScope.launch(container.ioDispatcher) {
                container.albumSync.syncNow(container.albumLink, "settings")
                withContext(Dispatchers.Main) { done() }
            }
        }

        override fun removeAlbum(done: () -> Unit) {
            container.processScope.launch(container.ioDispatcher) {
                // The mark first, so a sync that was already waiting for the lock sees the album as removed.
                val marked: Boolean = container.albumLinkStore.remove()
                if (marked) container.albumSync.clear()
                withContext(Dispatchers.Main) {
                    if (!marked) Toast.makeText(this@MainActivity, R.string.order_not_saved, Toast.LENGTH_SHORT).show()
                    done()
                }
            }
        }

        override fun updateState(): UpdateState = container.updater.state.value

        override fun updateRepo(): String = getString(R.string.update_repo)

        override fun versionName(): String = container.installedVersion()

        override fun checkForUpdate() = container.updater.check()

        override fun installUpdate() = this@MainActivity.installUpdate()
    }

    // ---- updates ---------------------------------------------------------------------------------

    /**
     * OK on an update that is on offer. Android asks that this app be allowed to install apps, once; until then the owner
     * is taken to that switch. The accessibility services that are on are noted first, to be turned on again by the new
     * version (see [AccessibilityRestore]).
     */
    private fun installUpdate() {
        val offer: UpdateOffer = (container.updater.state.value as? UpdateState.Available)?.offer ?: return
        if (!packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, R.string.update_unknown_sources, Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            } catch (unavailable: ActivityNotFoundException) {
                Log.w(TAG, "This TV has no screen for allowing installs from an app")
            }
            return
        }
        AccessibilityRestore.save(this, offer.version)
        container.updater.install()
    }

    // ---- album link from a phone -----------------------------------------------------------------

    /** The server that is open while the QR page is on screen, or null. */
    private var linkServer: LinkServer? = null

    /**
     * Opens the door for a phone and returns the address to put in the QR code, or null when the TV has no Wi-Fi or
     * Ethernet address or no free port. The first album link that arrives is kept, a sync starts at once, and
     * [onReceived] runs on the main thread. The server stops by itself after that, after five minutes, or when the
     * QR page closes.
     */
    private fun startLinkReceiver(onReceived: () -> Unit): String? {
        stopLinkReceiver()
        val address: String = LocalNetwork.ipv4(this) ?: return null
        val server = LinkServer(AndroidLogger("LinkServer"), { link ->
            val kept: Boolean = container.albumLinkStore.save(link)
            if (kept) {
                container.processScope.launch(container.ioDispatcher) { container.albumSync.syncNow(container.albumLink, "settings") }
                runOnUiThread(onReceived)
            }
            kept
        })
        if (!server.start()) return null
        linkServer = server
        return "http://$address:${server.port}/${server.token}"
    }

    private fun stopLinkReceiver() {
        linkServer?.stop()
        linkServer = null
    }

    // ---- top shelf content -----------------------------------------------------------------------

    /** OK on the Top shelf content row. Turning it on reads the TV provider; turning it off hides the shelf at once. */
    private fun toggleTopShelf() {
        val enabled: Boolean = !topShelfEnabled
        if (!TopShelfSetting(this).write(enabled)) {
            Toast.makeText(this, R.string.order_not_saved, Toast.LENGTH_SHORT).show()
            return
        }
        topShelfEnabled = enabled
        settingsMenu.refresh()
        backdrops.enabled = enabled
        if (enabled) {
            backdrops.loadStored()
            refreshTopShelf()
        } else {
            topShelf.hide(toGrid = false)
            backdrops.clear()
        }
    }

    // ---- appearance and language -----------------------------------------------------------------

    /** The page and row the settings are on, kept for the activity that will replace this one. */
    private fun rememberSettingsPlace() {
        reopenPage = settings.currentPageId
        reopenRow = settings.focusedRowId
    }

    /** OK on a choice of the Appearance page. A change of theme recreates the activity. */
    private fun chooseAppearance(value: Appearance) {
        if (!AppearanceStore(this).write(value)) {
            Toast.makeText(this, R.string.order_not_saved, Toast.LENGTH_SHORT).show()
            return
        }
        appearance = value
        settingsMenu.refresh()
        if (value.isLight != lightTheme) {
            rememberSettingsPlace()
            recreate()
        }
    }

    /** OK on a choice of the Language page. The text of the whole screen is read again, so the activity is recreated. */
    private fun chooseLanguage(value: Language) {
        if (!LanguageStore(this).write(value)) {
            Toast.makeText(this, R.string.order_not_saved, Toast.LENGTH_SHORT).show()
            return
        }
        language = value
        settingsMenu.refresh()
        if (value != appliedLanguage) {
            rememberSettingsPlace()
            recreate()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        reopenPage?.let { outState.putString(STATE_REOPEN_PAGE, it) }
        reopenRow?.let { outState.putString(STATE_REOPEN_ROW, it) }
    }

    // ---- screensaver -----------------------------------------------------------------------------

    /**
     * "Preview now". The system starts the screensaver that is selected in its settings, which is not necessarily ours,
     * so when it is another one the owner is sent to choose it. A TV with no Somnambulator (the entry the system's own
     * "start now" uses) says so.
     */
    private fun previewScreensaver() {
        val ours = ComponentName(this, ClockDream::class.java)
        val selected: String? = try {
            Settings.Secure.getString(contentResolver, "screensaver_components")
        } catch (e: SecurityException) {
            null
        }
        val isOurs: Boolean = selected.orEmpty().split(',').mapNotNull(ComponentName::unflattenFromString).any { it == ours }
        if (!isOurs) {
            Toast.makeText(this, R.string.screensaver_pick, Toast.LENGTH_LONG).show()
            SystemIntents.open(this, Intent(Settings.ACTION_DREAM_SETTINGS))
            return
        }
        try {
            startActivity(Intent().setClassName("com.android.systemui", "com.android.systemui.Somnambulator").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.screensaver_preview_unavailable, Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Toast.makeText(this, R.string.screensaver_preview_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val TAG: String = "LightLauncher"
        private const val MINUTE_MS: Long = 60_000L
        private const val STATE_REOPEN_PAGE: String = "reopenPage"
        private const val STATE_REOPEN_ROW: String = "reopenRow"

        /** The keys a View treats as OK (KeyEvent.isConfirmKey is not public API), which move mode must not let click a tile. */
        private val CONFIRM_KEYS: Set<Int> = setOf(
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_BUTTON_A,
        )

        /** What Config uses when the theme sets no background colour. */
        private val DEFAULT_BACKGROUND: Int = 0xFF000000.toInt()

        /** Room kept around a focused grid tile when the grid scrolls to show it (dp). */
        private const val FOCUS_SCROLL_PAD_DP: Float = 10f

        /**
         * com.mediatek.tv.agent resets animator_duration_scale to 0.6 on every boot, so Home puts it
         * back each time it resumes. Needs `pm grant com.prototype.lightlauncher android.permission.WRITE_SECURE_SETTINGS`.
         */
        private const val WANTED_ANIMATOR_SCALE: Float = 0.5f

        /** How long a tile takes to slide to its new place in move mode: quick, since every arrow press moves one. */
        private const val MOVE_MODE_SLIDE_MS: Long = 200L
    }
}
