# Photo Dream

A screensaver for older Android TVs that shows your own photos: from local storage, from a NAS on the local network
(SMB, WebDAV) and from cloud storage (OneDrive, Dropbox, and others if they allow it).

First target: Sony BRAVIA KD-55X9500D, Android 8.0 (API 26), about 1.5 GB RAM, no keyboard or touch.
Status: **local slideshow and gallery implemented.** It browses local/USB folders, shows supported photos with a
configurable interval, transition, and order, displays available EXIF details, and provides an on-demand thumbnail
grid and full-screen detail preview for large folders. It builds, but has not been installed or verified on the TV.
Decoded slideshow and preview images adapt to the screen size within a heap-based bitmap budget. 4K/8K images may
be sampled down on devices without enough memory and can look softer; this has not been visually tested on 4K/8K
hardware.
Settings can launch an immediate in-app slideshow or open Android's screensaver settings to select Photo Dream.

## Why

The TV's own screensaver (Chromecast's Backdrop) uses Google Photos through the Google Home app, and that setting
does not show for this TV. This project started from a [cleanyourtv](https://github.com/Gathin23/cleanyourtv) session
on that TV.

## Handoff: state and next steps (read this first in a new session)

**Done**
- Local photo slideshow and D-pad settings/folder picker are implemented. The app has not yet been installed on the TV.
- TV tooling and cleanyourtv rules are captured in [docs/CONTEXT.md](docs/CONTEXT.md).
- The TV (Sony KD-55X9500D, Android 8.0) already runs the cleanyourtv launcher; its ADB is **closed** (developer options
  off). To test, the owner must re-enable Developer options > USB debugging and Network debugging, and we close ADB
  again at the end ([docs/DEPLOY.md](docs/DEPLOY.md)). The cleanyourtv clone is expected next to this repo, with its
  Python venv in `tools\venv`.

**Decisions**
- Language: Kotlin, standard library only until a dependency is justified. `minSdk 26`.
- Repository will be **public**: no secrets, IPs, Wi-Fi names, tokens or personal paths. Choose a licence before the
  first push (done: MIT).
- No NAS. The owner has a Windows PC running Plex Media Server, usable as a LAN source (Plex photo library, or an
  SMB share of a photo folder).
- First cloud source: **OneDrive** (device-code flow, `Files.Read`, thumbnails). Other clouds come one at a time.
- Order: 1 local slideshow engine, 2 D-pad settings screen with its own folder browser (the TV has no DocumentsUI),
  3 OneDrive, 4 Plex, 5 SMB/WebDAV and other clouds, 6 polish.

**Needed from the owner before OneDrive**
1. A free Microsoft Entra app registration: public client, device-code flow enabled, delegated `Files.Read` and
   `offline_access`. Its client id goes in a git-ignored `secrets.properties`, never in the repo.
2. Personal or work/school account, and the photo folder (for example `Pictures`).
3. Whether the photos are mostly HEIC (Android 8 cannot decode it).

**Verify before coding:** every cloud and Plex API detail in `docs/DESIGN.md` is from memory.
**Unverified on the TV:** how to start a dream on demand, whether the Settings app shows a gear for the dream, and
USB access while debugging is on.

## Where to start

| Read | For |
|---|---|
| [docs/CONTEXT.md](docs/CONTEXT.md) | The TV, how screensavers work on it, rules carried over from cleanyourtv, the tools and build environment |
| [docs/DESIGN.md](docs/DESIGN.md) | Architecture, the three source types, cloud sign-in, memory budget, milestones, open questions |
| [docs/DEPLOY.md](docs/DEPLOY.md) | Build, install on the TV, select the screensaver, undo, close ADB |
| [AGENTS.md](AGENTS.md) | Instructions for AI coding agents working here |

## Build

```powershell
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat --no-daemon testDebugUnitTest assembleRelease
```

Requires JDK 17 and Android SDK platform 34 with build-tools 34.0.0. Gradle 8.9 and the Android Gradle Plugin 8.5.2 are
the same as cleanyourtv, so a cached build is fast.

## Layout

```
app/src/main/java/com/photodream/tv/PhotoDream.kt     the DreamService and sampled photo slideshow
app/src/main/java/com/photodream/tv/SettingsActivity.kt     D-pad settings screen
app/src/main/java/com/photodream/tv/FolderPickerActivity.kt     local and USB folder browser
app/src/main/java/com/photodream/tv/GalleryActivity.kt     on-demand thumbnail gallery for large folders
app/src/main/java/com/photodream/tv/PhotoPreviewActivity.kt     full-screen photo preview and metadata panel
app/src/main/java/com/photodream/tv/SlideshowActivity.kt     manually launched immersive slideshow
app/src/main/AndroidManifest.xml                      dream service, photo permissions, leanback features
docs/                                                 context, design, deployment
```

## Licence

MIT, see [LICENSE](LICENSE). Code copied from cleanyourtv is also MIT; keep its notice.
