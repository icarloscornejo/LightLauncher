# Development

## Stack

Kotlin 2.4 on AGP 9.4 (built-in Kotlin), Gradle 9.7, compileSdk/targetSdk 37, minSdk 26, JDK 17
toolchain. Plain Views and RecyclerView, no Compose. Dependencies: kotlinx-coroutines, lifecycle
(runtime, viewmodel), activity-ktx, recyclerview; JUnit 4 and coroutines-test for tests only. Versions
live in `launcher/gradle/libs.versions.toml`. Release builds are minified with R8.

## Layers

Everything is under `com.prototype.lightlauncher`, which is both the `namespace` and the `applicationId`, so the
folders under `src/main/java/com/prototype/lightlauncher` match the package:

| Package | What | May use |
|---|---|---|
| `core` | Pure logic: slide planning, album URL rules, colour maths, top shelf and update rules, reload requests | `kotlin.*`, `java.*`, `kotlinx.coroutines.*` only |
| `data` | Disk, network, package manager: `AlbumSync`, `Config`, `AppCatalog`, `TopShelf`, `Updater` | Android, never `ui` |
| `ui` | Views and controllers: `AppAdapter`, `HomeViewModel`, `DockDecoration`, `TopShelfView`, `AmbientShelf`, `SettingsScreen` and `SettingsMenu` | everything |

`DependencyRulesTest` fails the build when a file breaks these rules. The Android components (`MainActivity`,
`ClockDream`, the key services, `HomeSettingsActivity`, `UpdateResultReceiver`) stay in the root package because
the TV and the scripts refer to them by name.

Dependencies are wired by hand in `AppContainer` (created by `LightLauncherApp`); there is no DI framework.
Background work runs in coroutines: the app scan on the IO dispatcher through `HomeViewModel`, the album
sync in a process scope with a `SupervisorJob`, so one failure never takes Home down. Debug builds enable
StrictMode (log only) to catch disk or network work on the main thread.

## Network and privacy

There are three network uses: the screensaver album, the images of the top shelf, and the update check. There
are no accounts and no analytics.

**Top shelf images.** This is opt-in: the "Top shelf content" row of the Launcher settings is off by default, and
while it is off nothing is read from the TV provider and nothing is downloaded (`TopShelfSetting`). With it on, above the dock, the focused dock app shows full screen pictures, a row of cards, a collage or its icon, made from what it publishes
to the TV provider (Watch Next and preview programs, read with `READ_TV_LISTINGS`). Those programs carry `https` image addresses, so the
launcher downloads from hosts chosen by other apps and no host list is possible. `ShelfImageLoader` is the one place
it happens, and these are its limits (`core/ShelfImageUrl`, `core/ImageLimits`):

- Only https, port 443, no credentials in the address, and a public looking host: never an IP literal, a single
  label such as `router`, or a name ending in `.local`, `.internal`, `.lan`, `.home` or `.corp`, or a name whose last label starts with a digit. Every redirect (at
  most 3, followed by hand) is checked the same way, so an app cannot send the launcher to a device on the owner's
  network. Ceiling: a public looking name that resolves to a private address is not caught.
- For two hosts whose address says the size (Max's `w=` width, YouTube's `maxresdefault` for cards), `ShelfImageUpgrade`
  adds a bigger address to try first. It goes through the same checks and limits, and the published address is the
  fallback, so a host without the bigger picture costs one failed request.
- At most 3 MB and 8 seconds per image (plus up to one 5 second read timeout), and a stop request ends the download.
  A header that claims more than 8192 px a side or 40 million pixels is refused before decoding, and the decode is
  shrunk to the size the caller needs (about 800 px for a card, the screen for a full screen picture).
- Only the dock apps' images are ever requested. Downloads are cached under `cacheDir/shelf` (at most 400 files and
  128 MB, the least recently used go first) with a 40 MB memory cache of decoded images in front (room for one full
  screen picture, 8 MB, and a row of 16 cards). The
  carousel loads one picture at a time, only when its turn comes, and stops when Home is paused. The exception is an
  app with no full screen picture: up to 16 of its rows are loaded for the row of cards, at about 800 x 450 each and
  six downloads at a time.
- OK on a "Play Next" card opens an intent that the dock app wrote into the TV provider, which is the one thing here
  that acts on text from another app, so it is the part to read when reviewing. `SystemIntents.openPublished` parses
  it with `Intent.parseUri` and `core/PublishedIntent` decides: the action must be VIEW, MAIN or none (SEND and the like
  make Android add a read grant on the data after the launcher cleared it), `file:` data is refused, and an address
  that is missing or blank cannot be opened. Then the package is forced to the app's own, a component of another
  package, the selector and the clip data are dropped, the URI grant flags are removed, and it is started with
  `startActivity` only. Every failure, including an unexpected `RuntimeException` from a malformed address, becomes
  the can't-open message with only the exception class in the log, so it cannot reach `ShelfFeature` and switch the
  shelf off for every app.
- Any unexpected failure switches the shelf off for the process (`ShelfFeature`): the dock apps then show their icon.
- Logs carry a category (`address not allowed`, `too large`, ...) and, for a decoded image, its published size and
  whether it came from the cache; never an address, a host, a title or a server message.

**Screensaver album.** The screensaver's use is the album only. `AlbumSync` follows redirects by hand (at most 5), checks
https and the allowed hosts before every connection, caps response sizes, and only deletes old photos
after a complete sync. Logs carry a failure category and the origin (`home` or `dream`), never the link,
a URL or a server message. The link can also be sent from a phone: the settings show a QR code and serve one small
page on the local network (`LinkServer`) while that screen is open, behind a random token, for five minutes at most.
Cleartext traffic and backups are off in the manifest.

**Update check.** On demand only ("Check for updates" in the Launcher settings, never in the background).
`UpdateChecker` asks the GitHub API for the latest release of `update_repo` (`config.xml`; empty hides the row), and
`UpdateRules` decides, failing closed: https and GitHub hosts only (every redirect checked by hand), exactly one
asset named `LightLauncher-<version>.apk`, at most 20 MB, and the SHA-256 `digest` GitHub publishes for the asset
must be there and must match the download. `Updater` then hands the file to Android's `PackageInstaller`, which asks
the owner to confirm and refuses any APK not signed with the key of the installed app. Logs carry a category only.

## Testing

- `cd launcher && ./gradlew test`: JVM tests for `core` and the Android-free parts of `data`, in debug and
  release.
- On a TV: `adb shell screencap` captures come out grey while video, an HDMI input or a DRM app is on
  screen; press Home first. Keys injected by ADB skip accessibility services.
- `KEYCODE_DPAD_CENTER` on a tile opens that app.
- To inspect `files/` or `shared_prefs/` with `run-as`, install the debug APK temporarily and always go back
  to the release build afterwards.
- Screensaver: `adb shell am start -n com.android.systemui/.Somnambulator` starts it.

## Themes

`tools/theme.sh <folder>` pushes `config.json` (and `wallpaper.jpg`) and restarts Home. It replaces the
whole file and removes the wallpaper when the folder has none. `tools/theme.sh --reset` goes back to
`config.xml`. Keys: [themes/README.md](../themes/README.md).

## Versioning

Semantic versioning: MAJOR for changes that break a theme, a config key or an installed setup, MINOR for
new features, PATCH for fixes. Bump `appVersion` in `launcher/app/build.gradle.kts`, move the
`[Unreleased]` notes in `CHANGELOG.md` under the new version, and tag the commit `vX.Y.Z`.

### Releasing

1. `cd launcher && ./gradlew clean test assembleRelease`, then check the APK with `aapt2 dump badging` (version) and
   `apksigner verify`.
2. Name the APK `LightLauncher-X.Y.Z.apk` and attach it to the GitHub release `vX.Y.Z`. The updater accepts exactly one
   asset with that name, and needs the `digest` (SHA-256) that GitHub computes for it; put the same hash in the notes.
   Also attach `LightLauncher-X.Y.Z.apk.sha256` (the output of `shasum -a 256`) for tools that read the hash from a
   file, and never a second `.apk`. The tag must match the APK's version code (`v1.0.0` is 10000).
3. Sign every release with the same key. `launcher/keystore.properties` (never committed; `storeFile`,
   `storePassword`, `keyAlias`, `keyPassword`) points at the release keystore, and `assembleRelease` signs with it.
   Create one with `keytool -genkeypair -keystore <file>.jks -alias <alias> -keyalg RSA -keysize 4096 -validity 36500`
   and back it up: Android refuses to update an app over one signed with a different key, so a lost key means every
   installed copy has to be uninstalled first. Without the file the build falls back to the machine's debug key, fine
   for your own TV but not for a release other people update from.

## Conventions

- Comments in English, explaining why rather than what. No em-dash characters anywhere.
- Explicit types on public APIs and properties; descriptive names.
- The layer rules above are enforced by `DependencyRulesTest`; new logic goes in `core` with a JVM test.
- Errors are handled with specific exceptions and a log line; nothing is swallowed silently.
