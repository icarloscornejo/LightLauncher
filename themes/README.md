# Themes

A theme is a folder with a `config.json` and, optionally, a `wallpaper.jpg`. Pushing one changes the
launcher's dock, layout and look on the TV without rebuilding the APK: the launcher reads
`config.json` as an overlay over its built-in `config.xml` the next time it starts.

```bash
TV_HOST=192.168.1.50 tools/theme.sh themes/aurora          # push a theme and restart Home
TV_HOST=192.168.1.50 tools/theme.sh --reset                # back to the built-in config.xml
powershell -ExecutionPolicy Bypass -File tools\theme.ps1 -TvHost 192.168.1.50 themes\aurora
```

`theme.sh` replaces the whole `config.json` on the TV (and removes the wallpaper when the folder has none), so
keep every key you use in one folder.

| Theme | What it does |
|---|---|
| `aurora/` | A dark aurora wallpaper (teal and violet on navy, darker at the bottom) with a matching background colour; copy it as a starting point for your own |

## Keys

Every key is optional; a missing key keeps the value from `config.xml`, an unknown or wrongly typed key
is ignored, and a file that doesn't parse is ignored as a whole, so a theme can never break Home.

| Key | Type | Meaning |
|---|---|---|
| `dock_apps` | list of package names | The dock row, in order; a missing or hidden app leaves its slot empty. Once the owner arranges apps on the TV (long press), the dock they saved is the dock and this list is ignored. Clearing the launcher's data (`pm clear`) goes back to this list |
| `hidden_apps` | list of package names | Never shown as tiles |
| `live_tv_package`, `live_tv_action` | string | The Live TV app and, if it has no leanback entry, the action to start it with |
| `source_panel_service`, `source_panel_action` | string | The TV's own source panel, for sets with no TvInputManager |
| `input_action` | string | Action sent with an HDMI/AV row's channel URI when nothing handles the standard `VIEW` (XGIMI: `com.xgimi.action.hdmiPlayer`) |
| `columns` | 1 to 8 | Tiles per row; tiles shrink to fit from 5 up |
| `rows_below_dock` | 0 to 3 | Grid rows left visible under the dock |
| `reduced_motion` | bool | Calmer motion for weak GPUs: no tile overshoot, short fades, no entrance replay |
| `vpn_app` | string | App the Wi-Fi row of the settings opens when a VPN is configured; the row also shows a red "VPN off" badge while no VPN is up |
| `show_home_settings_shortcut` | bool | Keep the optional Default-home tile visible when the TV has a home picker; false by default |
| `background_color` | `#RRGGBB` | Colour behind everything in the Dark appearance; black by default |
| `wallpaper` | bool | Show `wallpaper.jpg` from the theme folder (`true`), or ignore it (`false`); Dark appearance only |
| `dream_album_url` | https link | Shared Google Photos album (`photos.app.goo.gl` or `photos.google.com`) for the photo screensaver; empty keeps the clock screensaver. Anyone with the link can see the album, so keep it out of public themes. The link can also be sent from a phone, see the README |

## Wallpapers

`wallpaper.jpg` is drawn full screen behind the launcher, centre-cropped. Keep it dark, 1920×1080 and
under about 300 KB: the clock, the top shelf and the dock panel are designed for a dark picture, and a 1 GB TV
decodes the picture into memory on every Home start. Pictures larger than the screen are downsampled.
The Light appearance ignores both the wallpaper and `background_color`.

## Reduced motion

`"reduced_motion": true` keeps the look but calms the movement: tiles zoom without overshoot, fades are shorter, and Home does not replay the tile
entrance when you come back to it. Use it on TVs whose GPU cannot keep up; a MediaTek MT5895 on
Android 10 is the known case. The default, `false`, keeps the normal motion.

```json
{ "reduced_motion": true }
```

## Adding a theme

Open a PR with a new folder here, a one-line row in the table above and a screenshot from a real TV
in the PR description. Keep the dock list generic (the big streaming apps) unless the theme is for a
specific TV, and say which TV you tried it on.
