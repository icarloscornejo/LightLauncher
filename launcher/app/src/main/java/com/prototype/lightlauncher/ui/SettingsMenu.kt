package com.prototype.lightlauncher.ui

import android.content.Context
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.prototype.lightlauncher.R
import com.prototype.lightlauncher.core.Appearance
import com.prototype.lightlauncher.core.DnsBlockState
import com.prototype.lightlauncher.core.Language
import com.prototype.lightlauncher.core.QrCode
import com.prototype.lightlauncher.core.QrMatrix
import com.prototype.lightlauncher.data.AlbumStatus
import com.prototype.lightlauncher.data.PrivateDns
import com.prototype.lightlauncher.data.UpdateFailure
import com.prototype.lightlauncher.data.UpdateState

/**
 * The pages and rows of the settings screen, and what each one does. The things that belong to the
 * activity (opening Wi-Fi and the TV settings, the theme, the language, the top shelf content switch, the update) are asked of the [Host]; the rest
 * (the Private DNS switch) lives here. Rows are built once; [refresh] writes the current values into them.
 */
class SettingsMenu(
    private val context: Context,
    private val screen: SettingsScreen,
    private val inputs: TvInputs,
    private val host: Host,
) {
    interface Host {
        fun openWifi()
        fun openWifiSettings(): Boolean
        fun openTvSettings()
        fun appearance(): Appearance
        fun chooseAppearance(value: Appearance)
        fun language(): Language
        fun chooseLanguage(value: Language)
        fun homeContentOn(): Boolean
        fun toggleHomeContent()
        fun previewScreensaver()

        /** The photos on the TV and when the last sync was tried. */
        fun albumStatus(): AlbumStatus

        /** True when an album link is set (from a phone, or from config.json). */
        fun albumHasLink(): Boolean

        /**
         * Opens the door for a phone: returns the address to show as a QR code, or null when the TV cannot be reached
         * (no Wi-Fi, no free port). [onReceived] runs on the main thread when a link arrives and was kept.
         */
        fun startLinkReceiver(onReceived: () -> Unit): String?
        fun stopLinkReceiver()

        /** Syncs the album now; [done] runs on the main thread when it ended. */
        fun syncAlbumNow(done: () -> Unit)

        /** Takes the album away, link and photos; [done] runs on the main thread when it ended. */
        fun removeAlbum(done: () -> Unit)

        /** Where the update is. The row follows it: the activity calls [refreshUpdate] whenever it changes. */
        fun updateState(): UpdateState

        /** The repository the releases are read from ("owner/name"); empty hides the update row. */
        fun updateRepo(): String
        fun versionName(): String
        fun checkForUpdate()
        fun installUpdate()
    }

    private val dns = PrivateDns(context)

    private val screensaverRow = SettingsRow(SCREENSAVER_ROW, context.getString(R.string.screensaver)).apply {
        chevron = true
        onClick = { screen.push(SCREENSAVER_PAGE) }
    }
    private val homeRow = SettingsRow(HOME_ROW, context.getString(R.string.settings_home)).apply {
        chevron = true
        onClick = { screen.push(HOME_PAGE) }
    }
    /** An accordion: OK lists the inputs right under the row, asked then so their states are current. */
    private val inputsRow = SettingsRow(INPUTS_ROW, context.getString(R.string.inputs)).apply {
        hint = context.getString(R.string.shortcut_inputs_hint)
        children = {
            // The list was read ahead of time (see [setInputs]); asking the system now, on the main thread, made OK wait.
            (knownInputs ?: inputs.entries()).mapIndexed { index, entry ->
                SettingsRow("input_$index", entry.title).apply {
                    iconRes = entry.iconRes
                    value = entry.status?.let(context::getString)
                    onClick = entry.open
                }
            }
        }
        onEmpty = { Toast.makeText(context, R.string.no_inputs, Toast.LENGTH_SHORT).show() }
    }
    private val wifiRow = SettingsRow(WIFI_ROW, context.getString(R.string.wifi)).apply {
        showsValue = true
        iconRes = R.drawable.ic_wifi
        iconAtEnd = true
        hint = context.getString(R.string.shortcut_wifi_hint)
        onClick = { host.openWifi() }
        onLongClick = { host.openWifiSettings() }
    }
    private val tvRow = SettingsRow(TV_ROW, context.getString(R.string.tv_settings)).apply {
        chevron = true
        hint = context.getString(R.string.shortcut_settings_hint)
        onClick = { host.openTvSettings() }
    }

    private val previewRow = SettingsRow(PREVIEW_ROW, context.getString(R.string.screensaver_preview)).apply {
        hint = context.getString(R.string.screensaver_preview_hint)
        onClick = { host.previewScreensaver() }
    }
    private val albumRow = SettingsRow(ALBUM_ROW, context.getString(R.string.album_title)).apply {
        chevron = true
        hint = context.getString(R.string.album_hint)
        onClick = { screen.push(ALBUM_PAGE) }
    }

    // The album page: what is on the TV, the link from a phone, a sync on demand, and taking it away.
    private val albumStatusRow = SettingsRow(ALBUM_STATUS_ROW, context.getString(R.string.album_status_title))
    private val albumLinkRow = SettingsRow(ALBUM_LINK_ROW, context.getString(R.string.album_link)).apply {
        chevron = true
        hint = context.getString(R.string.album_link_hint)
        onClick = { screen.push(QR_PAGE) }
    }
    private val albumSyncRow = SettingsRow(ALBUM_SYNC_ROW, context.getString(R.string.album_sync_now)).apply {
        onClick = {
            if (!host.albumHasLink()) {
                Toast.makeText(context, R.string.album_needs_link, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, R.string.album_syncing, Toast.LENGTH_SHORT).show()
                host.syncAlbumNow { refresh() }
            }
        }
    }
    private val albumRemoveRow = SettingsRow(ALBUM_REMOVE_ROW, context.getString(R.string.album_remove)).apply {
        chevron = true
        onClick = {
            if (host.albumHasLink()) screen.push(REMOVE_PAGE) else Toast.makeText(context, R.string.album_needs_link, Toast.LENGTH_SHORT).show()
        }
    }
    private val removeConfirmRow = SettingsRow(REMOVE_CONFIRM_ROW, context.getString(R.string.album_remove_confirm)).apply {
        onClick = {
            host.removeAlbum { refresh() }
            screen.back()
        }
    }
    private val removeCancelRow = SettingsRow(REMOVE_CANCEL_ROW, context.getString(R.string.cancel)).apply { onClick = { screen.back() } }

    // The page with the QR code: the code, what to do with it, and a way out.
    private val qrView = QrView(context)
    private val qrText = TextView(context).apply {
        setTextColor(context.getColor(R.color.text_secondary))
        textSize = 17f
        typeface = context.resources.getFont(R.font.outfit)
        setLineSpacing(0f, 1.15f)
    }
    private val qrStatus = TextView(context).apply {
        setTextColor(context.getColor(R.color.text_primary))
        textSize = 19f
        typeface = context.resources.getFont(R.font.outfit_semibold)
    }
    private val qrContent: View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
        val density: Float = context.resources.displayMetrics.density
        val side: Int = Math.round(220 * density)
        addView(qrView, LinearLayout.LayoutParams(side, side))
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(qrStatus, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(qrText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Math.round(10 * density) })
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = Math.round(28 * density) },
        )
        setPadding(Math.round(18 * density), 0, 0, Math.round(14 * density))
    }
    private val qrCancelRow = SettingsRow(QR_CANCEL_ROW, context.getString(R.string.cancel)).apply { onClick = { screen.back() } }

    private val languageRow = SettingsRow(LANGUAGE_ROW, context.getString(R.string.language)).apply {
        chevron = true
        hint = context.getString(R.string.language_hint)
        onClick = { screen.push(LANGUAGE_PAGE) }
    }
    private val appearanceRow = SettingsRow(APPEARANCE_ROW, context.getString(R.string.appearance)).apply {
        chevron = true
        hint = context.getString(R.string.shortcut_appearance_hint)
        onClick = { screen.push(APPEARANCE_PAGE) }
    }
    private val contentRow = SettingsRow(CONTENT_ROW, context.getString(R.string.home_content)).apply {
        hint = context.getString(R.string.shortcut_home_content_hint)
        onClick = { host.toggleHomeContent() }
    }
    private val dnsRow = SettingsRow(DNS_ROW, context.getString(R.string.block_ads)).apply {
        onClick = ::toggleDns
    }
    private val updateRow = SettingsRow(UPDATE_ROW, context.getString(R.string.update_check)).apply {
        hint = context.getString(R.string.update_hint)
        onClick = {
            when (host.updateState()) {
                is UpdateState.Available -> host.installUpdate()
                // Something is already running: a second press must not start a second one. While the system asks
                // for confirmation, a press checks again, so a screen that never came cannot lock the row.
                UpdateState.Checking, UpdateState.Downloading -> Unit
                else -> host.checkForUpdate()
            }
        }
    }
    /** Information only: the version, and where the code and the releases live (the hint, while it has the focus). */
    private val aboutRow = SettingsRow(ABOUT_ROW, context.getString(R.string.about)).apply {
        val repo: String = host.updateRepo()
        hint = if (repo.isEmpty()) context.getString(R.string.about_hint_plain) else context.getString(R.string.about_hint, repo)
    }

    private val languageChoices: Map<Language, SettingsRow> = mapOf(
        Language.AUTO to choice("language_auto", R.string.language_auto, null) { host.chooseLanguage(Language.AUTO) },
        Language.EN to choice("language_en", R.string.language_english, null) { host.chooseLanguage(Language.EN) },
        Language.ES to choice("language_es", R.string.language_spanish, null) { host.chooseLanguage(Language.ES) },
    )
    private val appearanceChoices: Map<Appearance, SettingsRow> = mapOf(
        Appearance.LIGHT to choice("appearance_light", R.string.appearance_light, R.string.appearance_light_sub) { host.chooseAppearance(Appearance.LIGHT) },
        Appearance.DARK to choice("appearance_dark", R.string.appearance_dark, R.string.appearance_dark_sub) { host.chooseAppearance(Appearance.DARK) },
    )

    init {
        // Inputs first: it is the row used most, and its accordion opens downwards over the rest.
        screen.add(SettingsPage(MAIN_PAGE, context.getString(R.string.settings_title), listOf(inputsRow, homeRow, wifiRow, tvRow)))
        screen.add(
            SettingsPage(SCREENSAVER_PAGE, context.getString(R.string.screensaver), listOf(previewRow, albumRow), parent = HOME_PAGE),
        )
        screen.add(
            SettingsPage(
                ALBUM_PAGE, context.getString(R.string.album_title), listOf(albumStatusRow, albumLinkRow, albumSyncRow, albumRemoveRow),
                parent = SCREENSAVER_PAGE,
            ),
        )
        screen.add(
            SettingsPage(
                QR_PAGE, context.getString(R.string.qr_title), listOf(qrCancelRow), parent = ALBUM_PAGE,
                content = qrContent, onShow = ::startReceiving, onHide = { host.stopLinkReceiver() },
            ),
        )
        screen.add(
            SettingsPage(
                REMOVE_PAGE, context.getString(R.string.album_remove_title), listOf(removeConfirmRow, removeCancelRow), parent = ALBUM_PAGE,
                hint = context.getString(R.string.album_remove_hint),
            ),
        )
        screen.add(
            SettingsPage(
                HOME_PAGE, context.getString(R.string.settings_home),
                // No repository, no update row (a build that does not publish releases).
                listOfNotNull(screensaverRow, languageRow, appearanceRow, contentRow, dnsRow, updateRow.takeIf { host.updateRepo().isNotEmpty() }, aboutRow),
                parent = MAIN_PAGE,
            ),
        )
        screen.add(SettingsPage(LANGUAGE_PAGE, context.getString(R.string.language), languageChoices.values.toList(), parent = HOME_PAGE))
        screen.add(SettingsPage(APPEARANCE_PAGE, context.getString(R.string.appearance), appearanceChoices.values.toList(), parent = HOME_PAGE))
        screen.rowView(INPUTS_ROW).visibility = View.GONE // until the first read says there is something to list
        refresh()
    }

    /** The QR page came on screen: ask for an address, draw it, and wait. A link that arrives takes the owner back to the album page. */
    private fun startReceiving() {
        qrView.show(null)
        qrText.text = ""
        qrStatus.text = ""
        val address: String? = host.startLinkReceiver {
            Toast.makeText(context, R.string.album_received, Toast.LENGTH_LONG).show()
            screen.back()
            refresh()
        }
        val code: QrMatrix? = address?.let(QrCode::encode)
        if (address == null || code == null) {
            qrStatus.setText(R.string.qr_unavailable)
            return
        }
        qrView.show(code)
        qrStatus.setText(R.string.qr_waiting)
        qrText.text = context.getString(R.string.qr_steps) + "\n\n" + address
    }

    private fun refreshAlbum() {
        val status: AlbumStatus = host.albumStatus()
        val linked: Boolean = host.albumHasLink()
        val last: Long? = status.lastAttemptMillis
        albumStatusRow.value = when {
            !linked -> context.getString(R.string.album_status_none)
            last == null -> context.getResources().getQuantityString(R.plurals.album_status_never, status.photos, status.photos)
            else -> context.getResources().getQuantityString(
                R.plurals.album_status, status.photos, status.photos,
                DateUtils.getRelativeTimeSpanString(last, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
            )
        }
        albumRow.value = if (linked) context.resources.getQuantityString(R.plurals.photos, status.photos, status.photos) else context.getString(R.string.album_status_none)
        albumLinkRow.value = context.getString(if (linked) R.string.album_link_set else R.string.album_link_unset)
        albumSyncRow.enabled = linked
        albumRemoveRow.enabled = linked
        for (row in listOf(albumStatusRow, albumRow, albumLinkRow, albumSyncRow, albumRemoveRow)) screen.refresh(row)
    }

    /** One option of a list of choices: its name, an optional line under it, and what OK does. */
    private fun choice(id: String, title: Int, subtitle: Int?, pick: () -> Unit): SettingsRow =
        SettingsRow(id, context.getString(title)).apply {
            this.subtitle = subtitle?.let(context::getString)
            onClick = pick
        }

    private var knownInputs: List<TvInputs.Entry>? = null

    /** What to read off the main thread and hand to [setInputs]. */
    fun readInputs(): Pair<List<TvInputs.Entry>, Boolean> = inputs.entries() to inputs.available()

    /**
     * Keeps what [readInputs] found. The Inputs row is hidden when it would have nothing to list (a streaming box has
     * no HDMI-in, AV or tuner), and until the first answer is in.
     */
    fun setInputs(entries: List<TvInputs.Entry>, available: Boolean) {
        knownInputs = entries
        screen.rowView(INPUTS_ROW).visibility = if (available) View.VISIBLE else View.GONE
    }

    /** Writes the current values into the rows. Called when the screen opens and after anything changes. */
    fun refresh() {
        refreshAlbum()
        languageChoices.forEach { (language, row) -> row.checked = language == host.language() }
        appearanceChoices.forEach { (appearance, row) -> row.checked = appearance == host.appearance() }
        languageRow.value = context.getString(languageName(host.language()))
        appearanceRow.value = context.getString(appearanceName(host.appearance()))
        contentRow.value = context.getString(if (host.homeContentOn()) R.string.option_on else R.string.option_off)
        refreshDns()
        aboutRow.value = host.versionName()
        refreshUpdate()
        for (row in listOf(languageRow, appearanceRow, contentRow, dnsRow, aboutRow) + languageChoices.values + appearanceChoices.values) screen.refresh(row)
    }

    /** Writes where the update is into its row: called when it changes, from wherever it was started. */
    fun refreshUpdate() {
        updateRow.value = when (val state: UpdateState = host.updateState()) {
            UpdateState.Idle -> null
            UpdateState.Checking -> context.getString(R.string.update_checking)
            UpdateState.UpToDate -> context.getString(R.string.update_up_to_date)
            is UpdateState.Available -> context.getString(R.string.update_available, state.offer.version)
            UpdateState.Downloading -> context.getString(R.string.update_downloading)
            UpdateState.AwaitingConfirmation -> context.getString(R.string.update_confirm)
            is UpdateState.Failed -> context.getString(
                when (state.failure) {
                    UpdateFailure.CHECK -> R.string.update_failed_check
                    UpdateFailure.DOWNLOAD -> R.string.update_failed_download
                    UpdateFailure.INSTALL -> R.string.update_failed_install
                },
            )
        }
        screen.refresh(updateRow)
    }

    private fun refreshDns() {
        val state: DnsBlockState = dns.state()
        val custom: String? = dns.customServer()
        dnsRow.enabled = state != DnsBlockState.UNAVAILABLE && dns.canWrite()
        dnsRow.value = context.getString(
            when {
                !dnsRow.enabled -> R.string.option_unavailable
                state == DnsBlockState.ON -> R.string.option_on
                else -> R.string.option_off
            },
        )
        // Before the switch replaces the owner's own server, the row says which one it is.
        dnsRow.hint = if (custom != null) {
            context.getString(R.string.block_ads_now, custom) + " " + context.getString(R.string.block_ads_hint)
        } else {
            context.getString(R.string.block_ads_hint)
        }
    }

    private fun toggleDns() {
        val state: DnsBlockState = dns.state()
        if (state == DnsBlockState.UNAVAILABLE || !dns.canWrite()) {
            Toast.makeText(context, if (state == DnsBlockState.UNAVAILABLE) R.string.dns_needs_android9 else R.string.dns_needs_permission, Toast.LENGTH_LONG).show()
            return
        }
        if (!dns.set(state != DnsBlockState.ON)) Toast.makeText(context, R.string.dns_not_changed, Toast.LENGTH_SHORT).show()
        refresh()
    }

    private fun languageName(language: Language): Int = when (language) {
        Language.AUTO -> R.string.language_auto
        Language.EN -> R.string.language_english
        Language.ES -> R.string.language_spanish
    }

    private fun appearanceName(appearance: Appearance): Int = when (appearance) {
        Appearance.LIGHT -> R.string.appearance_light
        Appearance.DARK -> R.string.appearance_dark
    }

    companion object {
        const val MAIN_PAGE: String = "main"
        const val SCREENSAVER_PAGE: String = "screensaver"
        const val ALBUM_PAGE: String = "album"
        const val QR_PAGE: String = "qr"
        const val REMOVE_PAGE: String = "album_remove"
        const val HOME_PAGE: String = "home"
        const val LANGUAGE_PAGE: String = "language"
        const val APPEARANCE_PAGE: String = "appearance"

        const val SCREENSAVER_ROW: String = "screensaver_row"
        const val HOME_ROW: String = "home_row"
        const val INPUTS_ROW: String = "inputs"
        const val WIFI_ROW: String = "wifi"
        const val TV_ROW: String = "tv"
        const val PREVIEW_ROW: String = "preview"
        const val ALBUM_ROW: String = "album_row"
        const val ALBUM_STATUS_ROW: String = "album_status"
        const val ALBUM_LINK_ROW: String = "album_link"
        const val ALBUM_SYNC_ROW: String = "album_sync"
        const val ALBUM_REMOVE_ROW: String = "album_remove_row"
        const val REMOVE_CONFIRM_ROW: String = "remove_confirm"
        const val REMOVE_CANCEL_ROW: String = "remove_cancel"
        const val QR_CANCEL_ROW: String = "qr_cancel"
        const val LANGUAGE_ROW: String = "language_row"
        const val APPEARANCE_ROW: String = "appearance_row"
        const val CONTENT_ROW: String = "content"
        const val DNS_ROW: String = "dns"
        const val UPDATE_ROW: String = "update"
        const val ABOUT_ROW: String = "about"
    }
}
