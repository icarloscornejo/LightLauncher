# Security

## Reporting a problem

Please report a vulnerability privately, through GitHub's "Report a vulnerability" button on the Security tab of
this repository, not in a public issue. Say what you found, how to reproduce it and which version it affects. I
will answer as soon as I can and credit you in the fix if you want.

## What counts

LightLauncher runs as the Home app of a TV, so the parts worth attacking are the ones that read something from
outside:

- the update check and the install it starts (`UpdateChecker`, `Updater`, `ApkInstaller`),
- the small page the TV serves on the local network while the QR code is on screen (`LinkServer`),
- the pictures downloaded for the top shelf (`ShelfImageLoader`) and the intents that other apps publish with them
  (`PublishedIntent`),
- the album download (`AlbumSync`).

The rules each one follows are written down in [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md), "Network and privacy".
A way around any of them is a vulnerability.

## What does not

- Anything that needs ADB access to the TV: ADB already is full control.
- The signing key of a build you made yourself: updates only install over an app signed with the same key, which
  is the point.
- The plain http page of the QR pairing being readable by someone on the same Wi-Fi during its five minutes: the
  token only lets them replace the screensaver album link with another Google Photos link.
