# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). The version is set once, in
`launcher/app/build.gradle.kts` (`appVersion`); the Android version code is derived from it.

## [Unreleased]

## [1.0.1] - 2026-10-30

### Fixed

- **Empty dock slots can be filled.** On a TV that lacks some of the `dock_apps` (for example no SmartTube or Apple TV),
  those slots stay empty and move mode could not put an app in them. Now Up from the grid tile under an empty slot fills
  it, and Left and Right in the dock move an app into an empty slot instead of stepping over it.

### Changed

- Move mode: tiles slide to their new place, the picked up tile is a little smaller (1.26) and stays above the tiles it
  passes, and the dock panel no longer stretches while a tile crosses it.

## [1.0.0] - 2026-10-26

First release of LightLauncher, a tvOS style home screen for Android TV and Google TV, written in Kotlin with plain
Views.

### Added

- **Home like tvOS.** Six columns, a translucent glass dock panel with one slot per column (a favourite that is not
  installed leaves its slot empty), tiles that zoom and lift with a shadow when focused, and a clock pill in the corner
  that opens the settings. Light and dark appearance, with colours as tokens (`values/colors.xml` is the dark palette,
  `values-notnight/colors.xml` the light one); the theme's background colour and wallpaper apply in the dark appearance only.
- **Arrange the apps from the TV.** A long press on a dock or grid app starts move mode: Left and Right trade places in
  the dock, Down trades with the grid app below, Up opens the app's details; in the grid the tile is inserted one place
  or one row over and the others move along. Every step is saved; OK or Back ends it. Once anything is saved the saved
  dock is the dock, and `dock_apps` only counts until then. Clearing the launcher's data goes back to the configured order.
- **Top shelf** (opt-in, off by default, "Top shelf content"). The focused dock app shows what it publishes to the TV
  provider (`READ_TV_LISTINGS`). An app with preview art 1600 px wide or more (Apple TV, Prime Video) gets full screen
  pictures behind the dock, one after another every 6 seconds with a cross-fade and a slow push-in. Any other app
  (Netflix, Max, Spotify, SmartTube) gets a row of up to 16 "Play Next" cards, from its Watch Next rows first, else its
  previews; an app with nothing usable gets its icon. Pictures are never stretched. Max and YouTube pictures are asked
  for in a bigger size when the address allows it.
- **Netflix style cards.** The focused card grows and pushes the ones after it aside while the row slides to keep it
  where the first one starts. Everything runs on one clock and one curve (380 ms whatever the system's animation
  speed), a key pressed mid move carries on from where the cards are, the dock dims behind a hardware layer, and the
  cards come in one after another. OK opens what the app published for the card (only a plain open of the app's own
  content is accepted, see `PublishedIntent`), Down returns to the dock and Up goes on to the clock. Each app
  remembers the card you were on and comes back with the row slid to it.
- **Blurred backdrop.** When the focus goes down from a dock app to the grid, the picture of its shelf (the selected
  card, or the full screen picture) stays behind the home, blurred and tinted for the theme (washed out in light,
  capped in brightness in dark). The shelf fades out as the focus leaves, and coming back to Home from another app
  repaints nothing that did not change.
- **Settings screen**, laid out like the tvOS Settings list: Inputs (HDMI, AV, Live TV), Launcher settings, Wi-Fi (the
  network name beside its icon) and TV settings. Launcher settings holds the screensaver (preview now, photo album),
  Language (Automatic, English, Spanish), Appearance, Top shelf content, Block ads (Private DNS through AdGuard,
  needs `WRITE_SECURE_SETTINGS`), Check for updates and About (the version).
- **Photo screensaver** from a shared Google Photos album: 16:9 photos alone, narrower ones paired by aspect ratio,
  10 seconds per slide with a fade-in, no repeats until every photo was shown, a glow from the photo colours and the
  time and date in a corner. The album link can be sent from a phone: the TV shows a QR code and serves a small page on
  the local network for the length of the pairing (random token, one link, five minutes at most). Photos sync at
  most once a day, or on demand.
- **Check for updates.** Looks for a newer release of `update_repo` (this repository by default) on GitHub, downloads
  its APK, checks it against the SHA-256 GitHub publishes for it and hands it to Android's installer, which asks for
  confirmation and only installs it over the app if it is signed with the same key. The accessibility services that
  were on are turned on again by the new version. `update_repo` (`config.xml`) names the repository; empty hides the
  row.
- **Themes** pushed over ADB (`config.json` and an optional wallpaper) with `tools/theme.sh`, no rebuild needed.
- English and Spanish text, chosen in the settings or following the TV. Ten more languages (Arabic, Catalan, German,
  French, Polish, Russian, Serbian, Thai, Turkish, Chinese) are partly translated and fall back to English for the rest.
- `HomeViewModel`: the app and card scan runs off the main thread, and coming back to Home rebinds the tiles only when
  the scan changed.
- JVM unit tests for the pure logic, the album sync, the update download and the card store, plus a test that enforces
  the `core` / `data` / `ui` layer rules. StrictMode in debug builds.
- `build-and-install.sh` (and the Windows script) grants `READ_TV_LISTINGS` and `WRITE_SECURE_SETTINGS`, allows
  installs from the launcher, and can turn on the Home and Input key accessibility services.
- Home restores `animator_duration_scale` to 0.5 on resume (needs `WRITE_SECURE_SETTINGS`), for TVs whose firmware
  resets it at boot.
- Kotlin with plain Views (no Compose) and manual dependency injection. Build: Kotlin DSL with a version catalog,
  AGP 9.4, Gradle 9.7, Kotlin 2.4, compileSdk and targetSdk 37. MIT license; the Outfit typeface keeps the SIL Open
  Font License 1.1 (`THIRD_PARTY_NOTICES.md`).

### Security

- Three network uses, all opt-in or on demand, each with fixed rules (docs/DEVELOPMENT.md, "Network and privacy"):
  the album (https, Google Photos hosts only), the top shelf pictures (https, public looking hosts, size and time
  limits) and the update check (https, GitHub hosts only, digest required, 20 MB at most). Every redirect is checked
  by hand, and logs carry a category, never an address or a server message.
- Cleartext traffic and backups are disabled in the manifest. The update result receiver is not exported.

[Unreleased]: https://github.com/icarloscornejo/LightLauncher/compare/v1.0.1...HEAD
[1.0.1]: https://github.com/icarloscornejo/LightLauncher/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/icarloscornejo/LightLauncher/releases/tag/v1.0.0
