package com.prototype.lightlauncher.data

import android.content.Context
import android.content.res.Resources
import com.prototype.lightlauncher.R
import com.prototype.lightlauncher.core.ConfigRules
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * The launcher's settings: the values in res/values/config.xml, with an optional overlay read from
 * `config.json` in the app's external files directory (/sdcard/Android/data/com.prototype.lightlauncher/files/).
 * Every key in the JSON is optional and an unknown or badly typed key is ignored (and named in the log),
 * so a theme can change the dock and the look without a rebuild: `tools/theme.sh themes/<name>` pushes a
 * theme folder there and restarts Home.
 *
 * Loaded once per process by [com.prototype.lightlauncher.AppContainer]; nothing here touches the network.
 */
class Config private constructor(
    val dockApps: List<String>,
    val hiddenApps: List<String>,
    val liveTvPackage: String,
    val liveTvAction: String,
    val sourcePanelService: String,
    val sourcePanelAction: String,
    val inputAction: String,
    val columns: Int,
    val rowsBelowDock: Int,
    /** Calmer motion for weak GPUs (config.xml, reduced_motion): short fades, no replay. */
    val reducedMotion: Boolean,
    val vpnApp: String,
    val showHomeSettingsShortcut: Boolean,
    /** Background colour behind everything; black unless a theme says otherwise. */
    val backgroundColor: Int,
    /** Full-screen picture behind the launcher, or null for the plain colour. */
    val wallpaper: File?,
    /** Whether the overlay file was present and parsed. */
    val overlayApplied: Boolean,
    /** Shared Google Photos album link for the screensaver; empty keeps the clock screensaver. */
    val dreamAlbumUrl: String,
) {
    companion object {
        const val FILE_NAME: String = "config.json"
        const val WALLPAPER_NAME: String = "wallpaper.jpg"
        private const val MAX_OVERLAY_BYTES: Long = 64 * 1024

        /** The folder the theme tools push into; null when external storage is unavailable. */
        fun dir(context: Context): File? = context.getExternalFilesDir(null)

        fun load(context: Context, logger: Logger): Config {
            val res: Resources = context.resources
            var dock: List<String> = res.getStringArray(R.array.dock_apps).toList()
            var hidden: List<String> = res.getStringArray(R.array.hidden_apps).toList()
            var liveTv: String = res.getString(R.string.live_tv_package).trim()
            var liveTvAction: String = res.getString(R.string.live_tv_action).trim()
            var panelService: String = res.getString(R.string.source_panel_service).trim()
            var panelAction: String = res.getString(R.string.source_panel_action).trim()
            var inputAction: String = res.getString(R.string.input_action).trim()
            var columns: Int = res.getInteger(R.integer.columns)
            var rowsBelow: Int = res.getInteger(R.integer.rows_below_dock)
            var reducedMotion: Boolean = res.getBoolean(R.bool.reduced_motion)
            var vpn: String = res.getString(R.string.vpn_app).trim()
            var homeSettings: Boolean = res.getBoolean(R.bool.show_home_settings_shortcut)
            var background: Int = 0xFF000000.toInt()
            var dreamAlbumUrl: String = res.getString(R.string.dream_album_url).trim()

            val dir: File? = dir(context)
            val json: JSONObject? = read(dir?.let { File(it, FILE_NAME) }, logger)
            if (json != null) {
                val overlay = Overlay(json, logger)
                dock = overlay.stringList("dock_apps", dock)
                hidden = overlay.stringList("hidden_apps", hidden)
                liveTv = overlay.string("live_tv_package", liveTv)
                liveTvAction = overlay.string("live_tv_action", liveTvAction)
                panelService = overlay.string("source_panel_service", panelService)
                panelAction = overlay.string("source_panel_action", panelAction)
                inputAction = overlay.string("input_action", inputAction)
                columns = overlay.integer("columns", columns, 1, 8)
                rowsBelow = overlay.integer("rows_below_dock", rowsBelow, 0, 3)
                reducedMotion = overlay.bool("reduced_motion", reducedMotion)
                vpn = overlay.string("vpn_app", vpn)
                homeSettings = overlay.bool("show_home_settings_shortcut", homeSettings)
                background = overlay.color("background_color", background)
                dreamAlbumUrl = overlay.string("dream_album_url", dreamAlbumUrl)
            }
            var wallpaper: File? = dir?.let { File(it, WALLPAPER_NAME) }
            if (wallpaper != null && !(wallpaper.isFile && wallpaper.length() > 0)) wallpaper = null
            // A theme can also switch the wallpaper off while the file is still there.
            if (json != null && json.has("wallpaper") && !Overlay(json, logger).bool("wallpaper", true)) wallpaper = null

            // The dock has one slot per column and an unfilled slot stays empty, so a mismatch is worth a line.
            if (dock.size != maxOf(1, columns)) logger.warn("dock_apps has ${dock.size} entries for ${maxOf(1, columns)} columns")

            return Config(
                dock, hidden, liveTv, liveTvAction, panelService, panelAction, inputAction,
                maxOf(1, columns), maxOf(0, rowsBelow), reducedMotion,
                vpn, homeSettings, background, wallpaper, json != null, dreamAlbumUrl,
            )
        }

        /** Reads and parses the overlay; any problem means "no overlay", never a crash on the home screen. */
        private fun read(file: File?, logger: Logger): JSONObject? {
            if (file == null || !file.isFile) return null
            if (file.length() > MAX_OVERLAY_BYTES) {
                logger.warn("ignoring $FILE_NAME: larger than 64 KB")
                return null
            }
            return try {
                var text: String = file.readText(Charsets.UTF_8)
                if (text.startsWith("\uFEFF")) text = text.substring(1) // Windows editors add a BOM
                JSONObject(text)
            } catch (e: IOException) {
                logger.warn("ignoring $FILE_NAME: ${e.javaClass.simpleName}")
                null
            } catch (e: org.json.JSONException) {
                // Never log the message: org.json puts the whole document in it, and the document can hold
                // the private album link.
                logger.warn("ignoring $FILE_NAME: ${e.javaClass.simpleName}")
                null
            }
        }
    }

    /**
     * Typed reads of the overlay. A key that is present but unusable keeps its default and is named in the
     * log; only the key is logged, never the value, because a value such as the album link is private.
     */
    private class Overlay(private val json: JSONObject, private val logger: Logger) {
        private fun ignored(key: String) {
            if (json.has(key)) logger.warn("ignoring $FILE_NAME key $key: invalid value")
        }

        fun string(key: String, fallback: String): String {
            val value: Any? = json.opt(key)
            if (value is String) return value.trim()
            ignored(key)
            return fallback
        }

        fun bool(key: String, fallback: Boolean): Boolean {
            val value: Any? = json.opt(key)
            if (value is Boolean) return value
            ignored(key)
            return fallback
        }

        fun integer(key: String, fallback: Int, min: Int, max: Int): Int {
            val value: Any? = json.opt(key)
            val inRange: Int? = if (value is Number) ConfigRules.intInRange(value.toInt(), min, max) else null
            if (inRange != null) return inRange
            ignored(key)
            return fallback
        }

        fun color(key: String, fallback: Int): Int {
            val value: Any? = json.opt(key)
            // Always opaque: the window behind is black anyway.
            val parsed: Int? = if (value is String) ConfigRules.parseOpaqueColor(value) else null
            if (parsed != null) return parsed
            ignored(key)
            return fallback
        }

        fun stringList(key: String, fallback: List<String>): List<String> {
            val value: Any? = json.opt(key)
            if (value !is JSONArray) {
                ignored(key)
                return fallback
            }
            val out: MutableList<String> = ArrayList()
            for (index in 0 until value.length()) {
                val item: Any? = value.opt(index)
                if (item is String && item.isNotBlank()) out.add(item.trim())
            }
            return out
        }
    }
}
