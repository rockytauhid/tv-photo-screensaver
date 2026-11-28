# Target TV Context & Rules

Hardware constraints, `DreamService` notes, and safety rules for this project. Read this before designing anything.

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
- Stock Android TV includes `com.android.dreams.basic/.Colors` and `com.google.android.backdrop/.Backdrop` (Chromecast's ambient mode).
- The Settings screen (Device Preferences > Screen saver) lists every installed `DreamService`.
- Choose one over ADB: `settings put secure screensaver_components com.photodream.tv/.PhotoDream`.
  Undo: `settings put secure screensaver_components com.google.android.backdrop/.Backdrop`.
- Related settings: `screensaver_enabled`, `screensaver_activate_on_sleep`, `screensaver_activate_on_dock`.
- Chromecast's ambient mode (Google Photos) is set up from the Google Home app. That option does not show for this
  TV, which is why this project exists.
- Typical `DreamService` flags: `setInteractive(false)` (any key wakes the TV), `setFullscreen(true)`, `setScreenBright(false)`.
- Some firmwares (e.g. TCL) block a sideloaded service from binding until `appops set <pkg> AUTO_START allow` (the op's
  name is whatever `appops get <pkg>` prints) followed by `appops write-settings`. Not seen on this Sony yet, but test
  the screensaver by waiting for it to start, not only by selecting it.

## Engineering & safety rules

- **Keep it small and cheap.** Avoid heavy animations, blur, or video decoding on memory-constrained TVs; treat memory
  as the main budget.
- **Decode images at screen size.** A 12 MP photo decoded in full is about 48 MB of bitmap. Use `inSampleSize` and
  `RGB_565` where banding does not matter (RGB_565 is 4 MB at 1080p, ARGB_8888 is 8 MB).
- **Nothing runs unless it is needed.** No background service, no polling, no wake locks outside the dream.
  Fetching happens only while the screensaver or app is showing.
- **No telemetry, no analytics, no ads.** Network only to the servers the person configures.
- **No root, no uninstalling system apps, nothing flashed.** Deploy only with `adb install` or `pm install` and undo with
  `pm uninstall` of this app (this app's own package is fine to uninstall; never touch the TV's system apps).
- **Never commit secrets or identifiers:** no IP addresses, Wi-Fi names, account emails, serial numbers, Android IDs,
  MAC addresses, ADB keys, OAuth client secrets, tokens, NAS passwords. Use `192.168.1.50` or `<tv ip>` in docs.
  `.gitignore` already ignores `secrets.properties`, keystores, `backup/`, `shots/` and `work/`.
- **Screenshots can leak.** Blur Wi-Fi names, people's names and photos of people before a screenshot goes anywhere public.
- **Do not open factory or test menus** on a TV to see what they do.

## Build environment

- JDK 17: `C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot` (set `JAVA_HOME`)
- Android SDK: `%LOCALAPPDATA%\Android\Sdk` with platform 34, build-tools 34.0.0 and platform-tools
- Gradle 8.9 through the wrapper, Android Gradle Plugin 8.5.2 (the first build downloads dependencies and can take several minutes)
