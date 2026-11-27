# Photo Dream

A screensaver for older Android TVs that shows your own photos: from local storage, from a NAS on the local network
(SMB, WebDAV) and from cloud storage (OneDrive, Dropbox, and others if they allow it).

First target: Sony BRAVIA KD-55X9500D, Android 8.0 (API 26), about 1.5 GB RAM, no keyboard or touch.
Status: **local slideshow and gallery implemented.** It browses local/USB folders, shows supported photos with a
configurable speed, shuffle, and transition style, shows the photo date and location, and provides an on-demand thumbnail
grid and full-screen detail preview for large folders. It builds and has been tested on an Android TV emulator, but
has not been installed or verified on the physical TV.
Decoded slideshow and preview images are kept at least as large as they are drawn (never upscaled) within a
heap-based bitmap budget. 4K/8K images may be sampled down on devices without enough memory and can look softer;
this has not been visually tested on 4K/8K hardware.
`test-data/sample-photos` contains 30 generated fixtures. The latest six cover landscape and portrait variants of
3:2, 4:3, and 16:9 aspect ratios at mixed resolutions.
The app opens directly to the photo gallery with a Photo Dream launcher icon. Gold is reserved for state: a
favorite photo, an enabled option, and the year and month of the highlighted photo.

- **Gallery:** the header shows the photo count, a favorite (star) icon, a play icon and a settings icon. The star
  toggles between favorites only (gold) and all photos (white); the choice is saved and shared with the slideshow.
  Highlighted icons get a circle. The three-row square thumbnail grid is one continuous timeline, newest first,
  scrolls horizontally, decodes at twice the rendered size and keeps a bounded thumbnail cache. Only the photo date
  is shown (bottom left); the location is left out to keep the grid snappy. The year and month shortcuts follow the
  highlighted photo (both turn gold, and a focused shortcut gets a white pill border). Selecting a year jumps to its newest
  photo; selecting the highlighted year again collapses or expands its months, and only one year shows its months at
  a time. Selecting a month jumps to its newest photo (the last day of that month).
- **Preview:** full-screen photo with a favorite star (gold when the photo is a favorite), play and settings icons at
  the top right (press Up to reach them, Down to return to the photo) and the date and location along the bottom.
  Opening Settings from Preview shows only the Preview toggles (Show Photo Date and Show Photo Location). Left/Right
  move through the photos; `<` and `>` edge indicators appear briefly (only `>` on the first photo, only `<` on the last,
  and both in between) before sliding out to the left/right, and reappear briefly when pressing Down from the top-right
  icons.
- **Slideshow:** visually identical to the system screensaver, with no buttons so the photo is the hero. Left/Right go
  to the previous/next photo, Play/Pause (or OK) pauses and resumes, Back returns to the gallery with the last photo
  highlighted. Pausing shows a big circled pause icon in the middle; resuming turns it into a circled play icon that
  fades away after a few seconds. Both slideshow and screensaver show the clock in the upper-right corner. With Shuffle
  off, photos are newest first.
- **Date and location:** the Options key (Menu) opens a panel with Show Photo Date and Show Photo Location in the
  gallery and preview; the same two switches are in Settings. They apply to the gallery (date only), preview,
  slideshow and screensaver. The location is the suburb and abbreviated country, for example `Indooroopilly, AU`,
  looked up with Android's Geocoder and cached in the app so it still shows when the network is down. If no name
  can be found the location is hidden. The app opens no network connection itself; the Geocoder uses the TV's
  location backend, which some TVs do not have (then no location is shown).
- **Option panels** (Options key, and Settings) are semi-transparent over a dimmed screen. Options are text only; the
  highlighted one is circled and an enabled switch shows On in gold. Settings controls the screensaver's speed,
  transition style, shuffle and all-photos/favorites source. Slow, Medium and Fast use 15, 8 and 3 seconds per photo.
  With Shuffle off the screensaver continues after the last photo it showed, so a long run does not keep repeating
  the newest photos.

## Why

The TV's own screensaver (Chromecast's Backdrop) uses Google Photos through the Google Home app, and that setting
does not show for this TV. This project started from a [cleanyourtv](https://github.com/Gathin23/cleanyourtv) session
on that TV.

## Handoff: state and next steps (read this first in a new session)

**Done**
- Local photo slideshow, gallery-first launch, D-pad settings panel and folder picker are implemented. The app has
  not yet been installed on the physical TV.
- TV tooling and cleanyourtv rules are captured in [docs/CONTEXT.md](docs/CONTEXT.md).
- The TV (Sony KD-55X9500D, Android 8.0) already runs the cleanyourtv launcher; its ADB is **closed** (developer options
  off). To test, the owner must re-enable Developer options > USB debugging and Network debugging, and we close ADB
  again at the end ([docs/DEPLOY.md](docs/DEPLOY.md)). The cleanyourtv clone is expected next to this repo, with its
  Python venv in `tools\venv`.

**Decisions**
- Language: Kotlin, standard library only until a dependency is justified. `minSdk 26`.
- Repository will be **public**: no secrets, IPs, Wi-Fi names, tokens or personal paths. Choose a licence before the
  first push (done: GPL-3.0-or-later).
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
app/src/main/java/com/photodream/tv/SettingsActivity.kt     D-pad settings side panel
app/src/main/java/com/photodream/tv/FolderPickerActivity.kt     local and USB folder browser
app/src/main/java/com/photodream/tv/GalleryActivity.kt     launcher and on-demand thumbnail gallery for large folders
app/src/main/java/com/photodream/tv/PhotoPreviewActivity.kt     full-screen photo preview and metadata panel
app/src/main/java/com/photodream/tv/SlideshowActivity.kt     manually launched immersive slideshow
app/src/main/AndroidManifest.xml                      dream service, photo permissions, leanback features
docs/                                                 context, design, deployment
```

## Licence

Photo Dream is free software under the GNU General Public License, version 3 or (at your option) any later
version; see [LICENSE](LICENSE).

Copyright (C) 2026 Rocky Adam Tauhid <rockytauhid@gmail.com>

This program comes with ABSOLUTELY NO WARRANTY. Anyone who distributes it or a program based on it must do so
under the same licence and provide the source code. Code copied from cleanyourtv is MIT licensed, which is
compatible with the GPL; keep its copyright notice. The AndroidX and Leanback libraries are Apache-2.0, which is
also compatible with GPL-3.0.
