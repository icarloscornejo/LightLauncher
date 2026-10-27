package com.prototype.lightlauncher

import android.content.Context
import com.prototype.lightlauncher.core.AlbumLink
import com.prototype.lightlauncher.data.AlbumLinkStore
import com.prototype.lightlauncher.data.AlbumSync
import com.prototype.lightlauncher.data.ApkInstaller
import com.prototype.lightlauncher.data.AndroidLogger
import com.prototype.lightlauncher.data.Config
import com.prototype.lightlauncher.data.Logger
import com.prototype.lightlauncher.data.ShelfImageLoader
import com.prototype.lightlauncher.data.PrefsShownStore
import com.prototype.lightlauncher.data.ShownStore
import com.prototype.lightlauncher.data.TopShelf
import com.prototype.lightlauncher.data.UpdateChecker
import com.prototype.lightlauncher.data.Updater
import com.prototype.lightlauncher.data.UrlConnectionFetcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * The only place that knows the concrete classes (manual dependency injection, no framework).
 * Everything in it is created lazily, so building the container costs nothing at process start.
 */
class AppContainer(private val appContext: Context) {

    val logger: Logger = AndroidLogger()

    val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    /**
     * Work that must outlive any screen (album sync, order saves). One failure never takes the others or the
     * process down; only the exception class is logged, never its message.
     */
    val processScope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, throwable ->
            logger.warn("background work failed: ${throwable.javaClass.simpleName}")
        },
    )

    /**
     * Loaded once per process, on first use. A few KB on external storage: synchronous on Home's onCreate
     * (measured 2 ms); the screensaver reads it on a background thread.
     */
    val config: Config by lazy {
        val started: Long = System.nanoTime()
        Config.load(appContext, logger).also {
            logger.info("Config loaded in ${(System.nanoTime() - started) / 1_000_000} ms")
        }
    }

    val albumSync: AlbumSync by lazy {
        AlbumSync(
            folder = File(appContext.filesDir, "dream"),
            fetcher = UrlConnectionFetcher(),
            logger = logger,
            clock = System::currentTimeMillis,
            scope = processScope,
            ioDispatcher = ioDispatcher,
            shownStore = shownStore,
        )
    }

    /** The album link the owner set from the settings (or took away), kept on the TV. */
    val albumLinkStore: AlbumLinkStore by lazy { AlbumLinkStore(appContext) }

    /**
     * The link the screensaver syncs now: the one set from the settings, else config.json or config.xml. Asked by
     * AlbumSync inside its lock, so it reads the config (a file) on whatever thread the sync runs on.
     */
    val albumLink: () -> String = { AlbumLink.resolve(albumLinkStore.link(), albumLinkStore.removed(), config.dreamAlbumUrl) }

    /** The screensaver's "shown this round" set, shared by the running screensaver and AlbumSync's renames. */
    val shownStore: ShownStore by lazy { PrefsShownStore(appContext) }

    /** What the dock apps publish to the TV provider, for the top shelf. */
    val topShelf: TopShelf by lazy { TopShelf(appContext, AndroidLogger("TopShelf")) }

    /**
     * The top shelf's images. Short timeouts on top of the loader's own limits; a plain agent, since the
     * screensaver's "curl" one exists only for Google Photos' short link.
     */
    val shelfImages: ShelfImageLoader by lazy {
        ShelfImageLoader(
            cacheRoot = appContext.cacheDir,
            fetcher = UrlConnectionFetcher(userAgent = "LightLauncher", connectTimeoutMs = 5_000, readTimeoutMs = 5_000),
            logger = AndroidLogger("TopShelf"),
        )
    }

    /**
     * Looking for a newer release on GitHub and installing it (the settings' "Check for updates"). One per
     * process, so the activity being recreated neither loses an update that is running nor starts a second one. The
     * repository comes from update_repo in config.xml; left empty, it never updates from anywhere.
     */
    val updater: Updater by lazy {
        val updateLogger: Logger = AndroidLogger("Update")
        Updater(
            checker = UpdateChecker(UrlConnectionFetcher(userAgent = "LightLauncher"), File(appContext.cacheDir, "update"), updateLogger),
            installer = ApkInstaller(appContext, UpdateResultReceiver::class.java, updateLogger),
            repo = { appContext.getString(R.string.update_repo) },
            installedVersion = { installedVersion() },
            scope = processScope,
            ioDispatcher = ioDispatcher,
        )
    }

    /** The version name of this install (build.gradle.kts, appVersion). */
    fun installedVersion(): String = appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: ""
}
