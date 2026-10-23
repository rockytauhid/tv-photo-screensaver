# Context carried over from cleanyourtv

Facts and rules taken from the cleanyourtv repo (https://github.com/Gathin23/cleanyourtv, MIT) and from the
session that set up this TV. Read this before designing anything.

## The target TV

| | |
|---|---|
| Model | Sony BRAVIA KD-55X9500D, reported as `BRAVIA 4K 2015` (device `SVP-DTV15`, MediaTek) |
| Android | 8.0.0, API 26 (`ro.build.version.sdk = 26`) |
| Firmware | `SVP4KDTV15_PA-user 8.0.0 OPR2.170623.027.S16 6.827` |
| RAM | about 1.5 GB in total on this board; Sony's own services use most of it |
| Network | Wi-Fi, ADB over TCP on port 5555 (switch it off when finished) |

So: `minSdk 26`, Java 17 source level, no Android 9+ APIs without a version check, and no assumption of a fast CPU
or a lot of free memory. `compileSdk` and `targetSdk` are 34 only so the build tools are current.

## How the TV's screensaver works

- A screensaver is an Android `DreamService`. The manifest entry is in `app/src/main/AndroidManifest.xml`.
- This TV lists three: `com.android.dreams.basic/.Colors`, `com.google.android.backdrop/.Backdrop` (the active
  one, Chromecast's ambient mode) and the cleanyourtv launcher's `com.gathin.tvhome/.ClockDream`.
- The Settings screen (Device Preferences > Screen saver) lists every installed `DreamService`.
- Choose one over ADB: `settings put secure screensaver_components com.photodream.tv/.PhotoDream`.
  Undo: `settings put secure screensaver_components com.google.android.backdrop/.Backdrop`.
- Related settings: `screensaver_enabled`, `screensaver_activate_on_sleep`, `screensaver_activate_on_dock`.
- Chromecast's ambient mode (Google Photos) is set up from the Google Home app. That option does not show for this
  TV, which is why this project exists.
- A reference implementation is `launcher/app/src/main/java/com/gathin/tvhome/ClockDream.java` in cleanyourtv:
  `setInteractive(false)` (any key wakes the TV), `setFullscreen(true)`, `setScreenBright(false)`.
- Some firmwares (TCL) block a sideloaded service from binding until `appops set <pkg> AUTO_START allow` (the op's
  name is whatever `appops get <pkg>` prints) followed by `appops write-settings`. See cleanyourtv
  `docs/TROUBLESHOOTING.md`. Not seen on this Sony yet, but test the screensaver by waiting for it to start, not
  only by selecting it.

## Rules worth keeping from cleanyourtv

- **Keep it small and cheap.** The launcher's APK has a hard size gate (480 KB) and says "no blur or video" because
  of 1 GB TVs. This app will need more (network, image decoding), but treat memory as the main budget.
- **Decode images at screen size.** A 12 MP photo decoded in full is about 48 MB of bitmap. Use `inSampleSize` or
  `ImageDecoder` and `RGB_565` where banding does not matter. The launcher's `wallpaper_true_color` switch exists
  for exactly this trade-off (RGB_565 is 4 MB at 1080p, ARGB_8888 is 8 MB).
- **Nothing runs unless it is needed.** No background service, no polling, no wake locks outside the dream.
  Fetching happens only while the screensaver is showing.
- **No telemetry, no analytics, no ads.** Network only to the servers the person configures.
- **No root, no uninstalling, nothing flashed.** Deploy only with `adb install` or `pm install` and undo with
  `pm uninstall` of this app (this app's own package is fine to uninstall; the rule is about the TV's system apps).
- **Never commit secrets or identifiers:** no IP addresses, Wi-Fi names, account emails, serial numbers, Android IDs,
  MAC addresses, ADB keys, OAuth client secrets, tokens, NAS passwords. Use `192.168.1.50` or `<tv ip>` in docs.
  `.gitignore` already ignores `secrets.properties`, keystores, `backup/`, `shots/` and `work/`.
- **Screenshots can leak.** The cleanyourtv launcher shows the Wi-Fi name in its top bar. Blur Wi-Fi names, people's
  names and photos of people before a screenshot goes anywhere public.
- **Do not open factory or test menus** on a TV to see what they do.

## Tools from cleanyourtv that help

They live in `<cleanyourtv clone>\tools` and work with its Python virtualenv
(`tools\venv`, already created, packages `adb-shell`, `rsa`, `cryptography`). Set `$env:TV_HOST` first.

| Task | Command |
|---|---|
| Run a shell command on the TV | `tools\venv\Scripts\python.exe tools\tv.py "<command>"` |
| Print model, Android and firmware | `tools\venv\Scripts\python.exe tools\tv.py --probe` |
| Take a screenshot | `tools\venv\Scripts\python.exe tools\tv.py --screenshot shots\x.png` |
| Push a file to the TV | `tools\venv\Scripts\python.exe tools\tv.py --push <local> <remote>` or `tools\b64push.py` |
| Install an APK | push it to `/data/local/tmp/`, then `pm install -r /data/local/tmp/<name>.apk` |
| Check ADB is closed afterwards | `tools\venv\Scripts\python.exe tools\adb-closed.py <tv ip>` |

`launcher\build-and-install.ps1` is a good example of building, pushing in chunks with an md5 check, and installing
over `tv.py`. DEPLOY.md in this project describes the same flow for this app.

## Build environment already set up on this PC

- JDK 17: `C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot` (set `JAVA_HOME`)
- Android SDK: `%LOCALAPPDATA%\Android\Sdk` with platform 34, build-tools 34.0.0 and platform-tools
- Gradle 8.9 through the wrapper, Android Gradle Plugin 8.5.2 (same as cleanyourtv; the first build downloads
  dependencies and can take several minutes)
- Python 3.12 (user install) for the cleanyourtv tools

## What is already on the TV

- The cleanyourtv launcher `com.gathin.tvhome` is Home; the stock `com.google.android.tvlauncher` is disabled.
- Disabled (not uninstalled) telemetry, promo and help packages. A record is in `~/.cleanyourtv/`.
- Installed from Google Play: Plex, 10 play, 7plus, 9Now, SBS On Demand, Cx File Explorer, YouTube enabled.
- Plex runs there, so a Plex photo library is a possible source too (see DESIGN.md).
