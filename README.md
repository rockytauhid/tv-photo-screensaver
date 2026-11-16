# Photo Dream

A screensaver for older Android TVs that shows your own photos: from local storage, from a NAS on the local network
(SMB, WebDAV) and from cloud storage (OneDrive, Dropbox, and others if they allow it).

First target: Sony BRAVIA KD-55X9500D, Android 8.0 (API 26), about 1.5 GB RAM, no keyboard or touch.
Status: **local slideshow and gallery implemented.** It browses local/USB folders, shows supported photos with a
configurable speed, shuffle, and transition style, displays available EXIF details, and provides an on-demand thumbnail
grid and full-screen detail preview for large folders. It builds and has been tested on an Android TV emulator, but
has not been installed or verified on the physical TV.
Decoded slideshow and preview images adapt to the screen size within a heap-based bitmap budget. 4K/8K images may
be sampled down on devices without enough memory and can look softer; this has not been visually tested on 4K/8K
hardware.
`test-data/sample-photos` contains 30 generated fixtures. The latest six cover landscape and portrait variants of
3:2, 4:3, and 16:9 aspect ratios at mixed resolutions.
The app opens directly to the photo gallery with a Photo Dream launcher icon. Its header shows the photo count at the
same scale as the actions, a Favorites/All filter icon, play-slideshow and Settings icons on the right. The filter
choice is saved and shared with the slideshow. The gallery provides compact shortcuts for years and months that
contain photos, lazy thumbnail loading for large folders, square thumbnails in a three-row viewport, and the focused
photo's date and location at the bottom of the screen. Its three-row square thumbnail grid scrolls horizontally,
decodes at twice the rendered pixel size, and keeps only a bounded thumbnail cache. Favorites becomes the default
filter when favorites exist unless the user has selected All.
The remote Options key opens a screen-specific, semi-transparent panel that slides in from the right and covers the
rightmost quarter of the screen while dimming the background. Its actions are smaller, text-only, and use title case;
Gallery offers screensaver selection, Preview controls details and favorite status, and Slideshow controls details,
the all/favorite photo set, speed, shuffle, and transition style. Focused actions and icons are yellow; the active date
shortcuts are larger, bold, and white. The slideshow's Favorites/All, play/pause, and Settings controls move up and
fade away after three seconds, and return when a remote button is pressed. Selecting the play icon or pressing Play on
the remote starts at the
highlighted gallery photo. Settings slides in as a solid half-width panel over the dimmed screen and controls the
Android screensaver's speed, transition style, shuffle, and all-photos/favorites source. Slow, Medium, and Fast
use 3, 8, and 15 seconds respectively. The slideshow can be paused with the remote and resumed with Play; Back
returns to the gallery with the last displayed photo highlighted. With Shuffle off, photos are always newest first
(random order remains configurable). The slideshow shows the selected photo's date and location in the same bottom
positions and format as the gallery, with its clock at the upper left. The system screensaver clock remains upper
right. A Settings icon is available in the top-right corner of the gallery, preview, and slideshow screens.
The Preview screen's play icon starts the slideshow at the previewed photo; Back returns to Gallery with the last
displayed photo highlighted.

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
app/src/main/java/com/photodream/tv/SettingsActivity.kt     D-pad settings side panel
app/src/main/java/com/photodream/tv/FolderPickerActivity.kt     local and USB folder browser
app/src/main/java/com/photodream/tv/GalleryActivity.kt     launcher and on-demand thumbnail gallery for large folders
app/src/main/java/com/photodream/tv/PhotoPreviewActivity.kt     full-screen photo preview and metadata panel
app/src/main/java/com/photodream/tv/SlideshowActivity.kt     manually launched immersive slideshow
app/src/main/AndroidManifest.xml                      dream service, photo permissions, leanback features
docs/                                                 context, design, deployment
```

## Licence

MIT, see [LICENSE](LICENSE). Code copied from cleanyourtv is also MIT; keep its notice.
