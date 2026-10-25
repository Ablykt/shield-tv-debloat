# SHIELD TV debloat + Zen Home

Debloat for an NVIDIA SHIELD TV Pro (2019, `mdarcy`, Android 11 / SHIELD Experience 9.x),
plus **Zen Home**, a fast Apple TV-style launcher. Everything is reversible: packages are
*disabled*, never uninstalled, and `scripts/restore.sh` puts the SHIELD back exactly as it was.

## What it does

1. **Backup first** (`scripts/backup.sh`): every package list (incl. which are disabled), all
   `settings` namespaces, default home, preferred activities, app-ops, memory, a screenshot.
2. **Debloat**: 24 packages disabled with `pm disable-user`. Full list with reasons:
   [`scripts/debloat-list.txt`](scripts/debloat-list.txt).
   - NVIDIA telemetry/junk: stats, feedback, registration, welcome, beta console/app,
     developer widget, profiler, input viewer, factory helpers, promo auto-installer, Share/Gamecast.
   - Google: feedback, print spooler, Live Channels, Play Music, Play Movies, YouTube TV,
     YouTube Music, Photos & Videos.
   - The stock launcher and its recommendation/ad feed (only once Zen Home is the default).
3. **Kept on purpose**: AI upscaling & display/audio (NvCPLSvc, NvCPLUpdater, nvaudiosvc, AV sync,
   Dolby), remote pairing, IR TV control, remote locator, Customize button, controller mapper,
   SHIELD remote server, GeForce NOW / NVIDIA Games, SMB sharing, Play Games, Google Assistant
   (the remote's mic sends `ASSIST`), Chromecast, Ambient-mode screensaver, OTA updates.

Result on the test SHIELD: swap use fell from 516 MB to 138 MB and I/O wait from 66% to 0%.
Zen Home holds ~65 MB while another app is open, versus ~440 MB for the stock launcher and its
recommendation service.

## Zen Home

- 12 public-domain/CC0 nature photos drawn at **native 4K** (the SHIELD renders UI at 1080p; the
  wallpaper gets its own 4K surface), crossfading every 5 minutes with a location caption.
- Greeting, large clock and date; Search and Settings as Liquid Glass capsules.
- Dock in an iOS 26-style **Liquid Glass** panel: clear glass whose rim refracts the wallpaper
  with slight chromatic fringing, plus a specular highlight that follows the focused app.
  Android 11 has no runtime shaders, so the lens is baked per pixel off the UI thread.
- Apple TV-style focus: tiles spring larger, lift onto a shadow, tilt toward the direction you
  moved and catch a sheen. No borders.
- Navigation sounds (tick / tock / bump), synthesized by `launcher/tools/make_sounds.py`. They
  follow the SHIELD's *System sounds* switch. The HDMI audio link is kept awake while Home is on
  screen; otherwise TVs and soundbars swallow 30 ms clicks.
- App tiles are rendered in a short-lived `:tiles` process, so other apps' resource tables
  never sit in the launcher's memory.

| Remote | Does |
|---|---|
| OK | open app |
| **Hold OK** (or ☰) on an app | Move · Add to / Remove from Dock · Hide · App info |
| ◀ ▶ (▲ ▼ in the grid) while moving | reorder, OK to finish |
| ▼ from the dock | all apps |
| Home | back to the dock |
| Mic | Google Assistant |

Hidden apps collect in a "Hidden (n)" tile at the end of the grid.

## Scripts

```bash
scripts/backup.sh                   # snapshot the SHIELD (do this before anything else)
scripts/debloat.sh phase1           # disable telemetry/junk (keeps the stock launcher)
scripts/apply.sh                    # whole setup: phase 1, install Zen Home, make it home, phase 2
scripts/restore.sh --dry-run        # preview the undo
scripts/restore.sh                  # undo everything, stock launcher back (Zen Home stays installed)
scripts/restore.sh --uninstall-zen  # same, and remove Zen Home
scripts/restore.sh --package com.nvidia.nvgamecast   # re-enable one thing
launcher/build.sh                   # build the APK (no Gradle needed)
```

The scripts default to `192.168.0.222:5555`; set `ANDROID_SERIAL=<ip>:5555` for another
address. Turn on ADB under Settings → Device Preferences → Developer options → Network debugging.

**Requirements:** Android SDK with `platform-tools`, `build-tools/35.0.1` and an
`android-30` or `android-33` platform, plus a JDK (`javac`, `keytool`).

## Not in this repository

- `backup/`: device snapshots hold the SHIELD's serial, Android ID, Wi-Fi name and screenshots.
- `launcher/keystore/`: the APK signing key. `build.sh` creates one if it's missing. Android only
  installs an update over Zen Home if it's signed with the same key, so keep yours. With a new
  key, uninstall the old Zen Home first.

## Wallpaper credits

See `launcher/app/assets/wallpapers/captions.txt`. All images are CC0 or public domain, from
Wikimedia Commons.
