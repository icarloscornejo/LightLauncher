package com.prototype.lightlauncher.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.widget.Toast
import com.prototype.lightlauncher.R
import com.prototype.lightlauncher.data.Config
import java.util.Locale

/**
 * The TV's inputs: HDMI ports (with their connection state), AV and Live TV, and what switching to each one does.
 * The settings screen lists the entries in an accordion under its Inputs row, so this class only finds them.
 */
class TvInputs(
    private val activity: Activity,
    private val config: Config,
) {
    /** One thing to switch to. [status] is a string resource (Connected, No device) or null when the TV does not say. */
    class Entry(val iconRes: Int, val title: String, val status: Int?, val open: () -> Unit)

    private val tvInputs: TvInputManager? = activity.getSystemService(TvInputManager::class.java)

    /** False when there would be nothing to list: streaming boxes have no HDMI-in, AV or tuner. */
    fun available(): Boolean {
        var any: Boolean = liveTvIntent() != null || config.sourcePanelService.isNotEmpty()
        if (!any && tvInputs != null) any = tvInputs.tvInputList.any { it.isPassthroughInput }
        return any
    }

    /** The Live TV app's launch intent: its leanback entry point, or the configured action if it has none. */
    private fun liveTvIntent(): Intent? {
        if (config.liveTvPackage.isEmpty()) return null
        val launch: Intent? = activity.packageManager.getLeanbackLaunchIntentForPackage(config.liveTvPackage)
        if (launch != null || config.liveTvAction.isEmpty()) return launch
        val byAction: Intent = Intent(config.liveTvAction).setPackage(config.liveTvPackage)
        return if (activity.packageManager.resolveActivity(byAction, 0) != null) byAction else null
    }

    /** The TV's own source-selection panel, for TVs that have no TvInputManager (TCL Android TV 9). */
    private fun openSourcePanel() {
        val intent = Intent(config.sourcePanelAction)
        ComponentName.unflattenFromString(config.sourcePanelService)?.let { intent.component = it }
        try {
            activity.startService(intent)
        } catch (e: SecurityException) {
            Toast.makeText(activity, R.string.not_available, Toast.LENGTH_SHORT).show()
        } catch (e: IllegalStateException) {
            // Background service starts are refused on newer Android: same outcome for the owner.
            Toast.makeText(activity, R.string.not_available, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Switches to a passthrough input (HDMI, AV). The standard way is ACTION_VIEW on its channel URI, which the
     * Live TV app handles; where nothing takes that, the configured `input_action` is sent with the same URI
     * instead (XGIMI's HDMI player only answers com.xgimi.action.hdmiPlayer).
     */
    private fun openInput(inputId: String) {
        val uri = TvContract.buildChannelUriForPassthroughInput(inputId)
        val view: Intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.cursor.item/channel")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            activity.startActivity(view)
            return
        } catch (e: ActivityNotFoundException) {
            if (config.inputAction.isEmpty()) {
                Toast.makeText(activity, R.string.not_available, Toast.LENGTH_SHORT).show()
                return
            }
        }
        SystemIntents.open(activity, Intent(config.inputAction).setDataAndType(uri, "vnd.android.cursor.item/channel"))
    }

    /**
     * What can be switched to now, HDMI ports first, then AV, then Live TV. It asks the system several times over, so
     * it belongs off the main thread (the settings read it ahead of time and keep the answer).
     */
    fun entries(): List<Entry> {
        val entries: MutableList<Entry> = ArrayList()
        var tunerId: String? = null
        var tunerIsHardware = false
        if (tvInputs != null) {
            // HDMI ports first in port order, then AV and the rest. A label is asked of the system once per input, not
            // once per comparison: each ask is a call into another process.
            val labels: Map<String, String> = tvInputs.tvInputList.associate { it.id to (it.loadLabel(activity)?.toString() ?: it.id) }
            val inputs: List<TvInputInfo> = tvInputs.tvInputList.sortedWith { a, b ->
                val hdmiA: Boolean = a.type == TvInputInfo.TYPE_HDMI
                val hdmiB: Boolean = b.type == TvInputInfo.TYPE_HDMI
                if (hdmiA != hdmiB) (if (hdmiA) -1 else 1)
                else labels.getValue(a.id).compareTo(labels.getValue(b.id), ignoreCase = true)
            }
            // Some TVs (Sony) report every HDMI port as connected whether or not anything is plugged in, so drop
            // the HDMI status then. A TV with every port really in use loses it too.
            val hdmiPorts: List<TvInputInfo> = inputs.filter { it.isPassthroughInput && it.type == TvInputInfo.TYPE_HDMI }
            val hdmiConnected: Int = hdmiPorts.count { tvInputs.getInputState(it.id) == TvInputManager.INPUT_STATE_CONNECTED }
            val hdmiStateBogus: Boolean = hdmiPorts.size > 1 && hdmiConnected == hdmiPorts.size
            for (info in inputs) {
                if (info.type == TvInputInfo.TYPE_TUNER && (info.serviceInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0) {
                    // Prefer a hardware tuner: the framework names those ".../HW<n>". System apps such as AirPlay
                    // also register software inputs of type TUNER and may sort first (seen on TCL G05).
                    val hardware: Boolean = info.id.matches(Regex(".*/HW\\d+$"))
                    val current: String? = tunerId
                    if (current == null || (hardware && !tunerIsHardware) || (hardware == tunerIsHardware && info.id < current)) {
                        tunerId = info.id
                        tunerIsHardware = hardware
                    }
                }
                if (!info.isPassthroughInput) continue
                var label: String = labels.getValue(info.id)
                val hdmi: Boolean = info.type == TvInputInfo.TYPE_HDMI
                if (hdmi && !label.uppercase(Locale.ROOT).contains("HDMI")) label = "HDMI $label"
                val id: String = info.id
                // This TV reports "standby" for every port whether or not something is plugged in, so only definite states show.
                val status: Int? = if (hdmi && hdmiStateBogus) null else when (tvInputs.getInputState(id)) {
                    TvInputManager.INPUT_STATE_CONNECTED -> R.string.connected
                    TvInputManager.INPUT_STATE_DISCONNECTED -> R.string.not_connected
                    else -> null
                }
                entries.add(Entry(if (hdmi) R.drawable.ic_input else R.drawable.ic_av, label, status) { openInput(id) })
            }
        }
        // Nothing to list where there is no TvInputManager: the TV's own source panel switches inputs.
        if (entries.isEmpty() && config.sourcePanelService.isNotEmpty()) {
            entries.add(Entry(R.drawable.ic_input, activity.getString(R.string.inputs), null) { openSourcePanel() })
        }
        var liveTv: Intent? = liveTvIntent()
        val tuner: String? = tunerId
        if (liveTv != null && tuner != null) {
            liveTv = Intent(Intent.ACTION_VIEW)
                .setDataAndType(TvContract.buildChannelUriForPassthroughInput(tuner), "vnd.android.cursor.item/channel")
                .setPackage(config.liveTvPackage)
        }
        if (liveTv != null) {
            val liveTvIntent: Intent = liveTv
            entries.add(Entry(R.drawable.ic_livetv, activity.getString(R.string.live_tv), null) { SystemIntents.open(activity, liveTvIntent) })
        }
        return entries
    }
}
