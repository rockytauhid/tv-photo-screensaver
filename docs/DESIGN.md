# Design notes

Status: the local slideshow and gallery/settings UI are implemented and tested on an Android TV emulator, but not on
the physical TV. Physical-TV measurements are marked "measured"; statements marked **verify** come from memory of
vendor documentation and must be checked against the current docs before code depends on them.

## Goal

A screensaver (`DreamService`) for old Android TVs that shows a slideshow of the person's own photos from:

1. local storage (internal or a USB drive),
2. the local network (NAS: SMB shares first, WebDAV second),
3. cloud storage (OneDrive, Dropbox, Google Drive, others if cheap).

Target: Sony KD-55X9500D, Android 8.0 (API 26). Facts and constraints are in [CONTEXT.md](CONTEXT.md).

## Measured on the target TV

| | |
|---|---|
| Screen | `wm size` 1920x1080, density 320 (the 4K panel is driven at 1080p) |
| RAM | `MemTotal` 1,540,608 kB (about 1.5 GB) |
| Java heap | `dalvik.vm.heapgrowthlimit` 192m, `heapsize` 512m (no `largeHeap` needed to stay under 192 MB) |
| Free storage | `/data` 8.2 GB, 4.9 GB free (a disk cache of 100 to 200 MB is affordable, 1 GB is not) |
| Features | leanback, `leanback_only`, ethernet, live_tv. No touchscreen |
| Folder picker | no `DocumentsUI` package, so `ACTION_OPEN_DOCUMENT_TREE` is probably unusable. Build our own browser |

Implications: decode every image at 1920x1080 or smaller, keep at most three bitmaps alive (current, next,
transition), and never hold a full-size decode.

## Architecture

```
PhotoDream (DreamService)
  -> Slideshow           timer, order (shuffle / by date), pause on error, skip bad files
       -> PhotoSource    one interface, many implementations
       -> ImageLoader    sampled decode, EXIF rotation, small LRU of bitmaps, disk cache
       -> Renderer       two ImageViews + crossfade (alpha animation only), optional slow zoom
GalleryActivity          leanback launcher, item count, thumbnail grid, slideshow and settings controls
SettingsActivity         D-pad settings panel that slides in over the dimmed gallery
```

`PhotoSource` (sketch):

```java
interface PhotoSource {
    String id();                              // stable key for the index and the cache
    List<PhotoRef> list(Cancel cancel);       // recursive, filtered to jpg/jpeg/png/webp/heic?, may be slow
    InputStream open(PhotoRef ref, int maxWidth, int maxHeight); // a server thumbnail when the service has one
}
```

- The slideshow works on `PhotoRef` (source id, path or id, size, modified time) and never knows the protocol.
- **Index first, then play.** List a source once, save the index in app-private storage (`photo_index.tsv`), refresh
  quietly in the background when the dream or slideshow starts. Start showing from the saved index at once; never wait
  on a network listing before the first picture.
- **Prefetch one image ahead** on a single background thread. A slow NAS must not freeze the transition.
- **Failure is normal.** A source that is offline is skipped with a quiet log line; if all fail, fall back to a
  plain clock or the last cached photos, never a crash or an error dialog (nobody is holding a mouse).
- HEIC: Android 8.0 cannot decode it (`ImageDecoder` and HEIC arrive in API 28). Skip those files in v1 and say so
  in the settings screen; phones often export HEIC.
- Formats in v1: JPEG, PNG, WebP, GIF as a still frame. Not video.

## Sources

### 1. Local storage

- Internal storage and a USB stick appear under `/storage/<uuid>` or `/mnt/media_rw/<uuid>`.
  Needs `READ_EXTERNAL_STORAGE` (a runtime permission from API 23). A `DreamService` cannot show the prompt, so
  ask in `SettingsActivity`. There is no scoped storage before Android 10, so plain `java.io.File` works.
- Own folder browser in `SettingsActivity`, D-pad only (no SAF picker, see the table above).
- Watch out: some Sony boards cut power to a USB port while USB debugging is on. Test the
  USB path with debugging off, via `logcat` over a different route or by checking the slideshow itself.

### 2. Local network (Immich, SMB, WebDAV, Plex)

- **Immich (first LAN source).** Self-hosted photo server over LAN HTTP/HTTPS with a user API key (`x-api-key` header,
  stored encrypted via Android Keystore). Uses built-in `HttpURLConnection` + `org.json` — **zero new dependencies**.
  - **Server thumbnails** (**verify** current Immich API paths before coding, e.g. `/api/assets/{id}/thumbnail?size=preview`
    and `/api/albums` / `/api/search/metadata`): returns server-resized JPEGs/WebPs, which keeps bandwidth and TV heap
    low and sidesteps Android 8's lack of HEIC decoding.
  - Plugs into `PhotoSource` (`ImmichPhotoSource`) with offline-first index caching + bounded disk image cache (100–200 MB),
    so the screensaver keeps playing cached photos even when the server is offline.
- **SMB (second LAN source).** Most NASes and Windows PCs offer it. Modern ones disable SMB1, so the library must speak
  SMB2/3. Candidates (**verify** licence, size, Android 8 behaviour):
  - `smbj` (Apache 2.0): SMB2/3 only, pure Java, uses BouncyCastle or JCE for crypto; check it loads on API 26.
  - `jcifs-ng` (LGPL 2.1): SMB1/2/3, a bigger API; the LGPL has implications for a closed app and for R8 shrinking.
  - Both are large for a TV with 1.5 GB RAM: measure APK size and heap before committing.
  Settings: host (name or IP), share, path, user, password, domain. Store the password encrypted.
- **WebDAV.** `PROPFIND` with `HttpURLConnection` or a tiny hand-written client: no dependency. Covers Nextcloud,
  ownCloud, Synology WebDAV, many other NASes and a few cloud services. Cheap to add, so build it before SMB if the
  person's NAS supports it.
- **DLNA/UPnP.** The Sony already browses DLNA servers itself, so it is a possible later source, not a first one.
- **Discovery:** mDNS (`NsdManager`) lists SMB and WebDAV hosts on the LAN. Nice to have, manual entry first.
- Cleartext HTTP to a LAN address is blocked from API 28 unless allowed; on API 26 it works, but write the code and
  `networkSecurityConfig` as if the target were 28+ so a newer TV does not break it.

### 3. Cloud

Hard parts: sign-in on a TV with no keyboard, and what each service still allows. Use the **OAuth 2.0 device flow**
(the TV shows a short code and a URL, the person types the code on a phone). A `DreamService` cannot show a sign-in
screen, so sign in from `SettingsActivity` and keep the refresh token.

| Service | Sign-in | Notes (**verify all**) |
|---|---|---|
| OneDrive | Microsoft identity platform, device code flow, public client (no client secret), scope `Files.Read` and `offline_access` | Graph `GET /me/drive/root:/Pictures:/children` and a `thumbnails` option gives server-sized images. Needs an app registration (a client id) made by the developer. |
| Dropbox | PKCE or the "no redirect" code flow; no standard device flow | `files/list_folder`, `files/get_thumbnail_v2` for resized images, scope `files.content.read`. Needs an app key. |
| Google Drive | Device flow only allows narrow scopes (believed: `drive.file`, `drive.appdata`, not `drive.readonly`) | May mean the person must pick or share the folder through the app, or use a different route. Needs research before promising anything. |
| Google Photos | Library API stopped returning the whole library to apps in 2025 (believed); a Picker API replaced it | Probably not a good source. The Google Home ambient route is also missing on this TV. Treat as out of scope unless research says otherwise. |
| Plex | Already installed on the TV; a Plex Media Server serves photo libraries over HTTP with a token | A good "cloud-like" source for people who run Plex. |
| Immich, Nextcloud, WebDAV clouds | Token or app password | Self-hosted LAN photo servers; Immich is first in the LAN roadmap. |

**Fallback that removes most of the cloud work:** the person syncs the cloud folder to a NAS or a USB drive with a
tool such as rclone (on a PC, NAS or Raspberry Pi), and the app only needs local and SMB/WebDAV. Decide early
whether v1 ships with cloud at all.

Rules for cloud code:
- Request the least scope, read-only.
- Prefer server thumbnails sized for 1920x1080 to save bandwidth, memory and time.
- Store refresh tokens in app-private storage, encrypted with an Android Keystore key (API 23+). Never log tokens,
  never put a client secret in the repo (public clients do not need one; `secrets.properties` is git-ignored).
- No analytics SDKs, no Firebase, no Google Play Services dependency (this TV may not have the right version, and the
  app has strict zero-telemetry rules).
- Refresh on a schedule only while the dream is running or from the settings screen.

## Performance and memory

- Decode with `BitmapFactory.Options.inSampleSize` computed from the image bounds (`inJustDecodeBounds` first), then
  scale to fit 1920x1080. Use `RGB_565` only if banding is acceptable (photos are fine, dark gradients band).
- Bitmap budget: one 1920x1080 ARGB_8888 is about 8 MB; three alive is 24 MB, well under the 192 MB heap limit.
- Reuse bitmaps with `inBitmap` where possible to reduce garbage collection pauses.
- One download/decode thread, one UI thread, no thread pools that grow.
- Transitions: alpha crossfade on two `ImageView`s. The Ken Burns zoom is a matrix animation; test it on the TV
  because the GPU is weak. Avoid blur and video on 1–1.5 GB TVs.
- Burn-in protection is not a worry on an LCD, but dim the clock overlay if one is added.
- Avoid wake locks outside the dream. The dream itself keeps the screen on.

## Settings and distribution

- Settings screen: add/remove sources, folder browser, speed presets (Slow 15 s, Medium 8 s, Fast 3 s),
  shuffle on/off, transition style (cut, fade, slide), clock overlay (off, small), test button ("show now").
- A `DreamService` can point to a settings activity through `android:settingsActivity` and a
  `<meta-data android:name="android.service.dream">` XML resource. Whether the Sony's Settings app shows a gear is
  **unverified**, so expose `GalleryActivity` through `android.intent.category.LEANBACK_LAUNCHER`; the gallery and
  its settings panel are then available from Home.
- Distribution: a sideloaded APK over ADB, debug-signed, like the launcher. Publishing on Google Play is a separate
  decision (it needs a developer account, a privacy policy and cloud API verification).
- Licence: GPL-3.0-or-later, copyright Rocky Adam Tauhid.

## Testing

- JVM unit tests (`app/src/test`): index building, filename and extension filtering, shuffle without repeats,
  sample-size maths, OAuth response parsing, WebDAV `PROPFIND` XML parsing. Run with `gradlew testDebugUnitTest`.
- A fake `PhotoSource` that serves bundled images makes slideshow tests possible without a TV.
- On the TV: select the dream (`settings put secure screensaver_components ...`), wait for it to start by itself, take
  screenshots with `tv.py --screenshot`. A way to start a dream on demand is **unverified** on this firmware
  (for example `am start -n com.android.systemui/.Somnambulator`); try it and record what works in DEPLOY.md.
- Check memory with `dumpsys meminfo com.photodream.tv` after several hundred slides to catch leaks.

## Milestones

| # | Milestone | Done when |
|---|---|---|
| 0 | Skeleton | `PhotoDream` builds, is listed under Screen saver and starts on the TV. **The code scaffold builds; not yet installed.** |
| 1 | Local slideshow | Reads supported photos recursively from a selected internal/USB folder; images are sampled to at most 1920x1080 and transitions support cut, fade and slide. Implemented; TV playback and long-run memory are not yet verified. |
| 2 | Settings and gallery | D-pad folder browser, 3/8/15-second speed presets, shuffle and transition-style selection, metadata toggle, virtualized thumbnail gallery and full-screen detail preview. Implemented; TV navigation and 10,000-photo responsiveness are not yet verified on hardware. |
| 3 | Local network (Immich, then SMB) | An Immich server or SMB share plays via `PhotoSource`; offline server falls back cleanly to cached photos. |
| 4 | Online clouds | OneDrive device-flow sign-in, thumbnails, token stored encrypted. Then Google Drive / Dropbox / others as allowed. |
| 5 | Polish | Ken Burns if the GPU copes, disk thumbnail cache, clock toggle, translations. |

## Decisions (from the owner)

- **Language: Kotlin** (1.9.24, JVM target 17). Keep the standard library only; avoid coroutines and other libraries
  until a need is written down here.
- **Local network before online cloud.** Priority order:
  1. **Immich** over LAN HTTP/HTTPS with API key (zero new dependencies, server-sized thumbnails avoid HEIC issues).
  2. **SMB** share on LAN (Windows PC / NAS; evaluate `smbj` APK size & heap before adding).
  3. **Plex / WebDAV** (optional LAN sources).
  4. **Online clouds** (OneDrive, Google Drive, iCloud, Dropbox — later, one at a time).
- Revised milestone order: 0 skeleton, 1 local slideshow, 2 settings & gallery, 3 Local network (Immich & SMB),
  4 Online clouds, 5 polish.

## Intentional behaviours & performance decisions

Keep these intentional behaviours intact when refactoring or optimizing:

- **Two-phase fresh-folder loading + deferred EXIF scan (`GalleryActivity`, `LocalPhotoSource.refreshFromFiles`):**
  When opening an uncached folder for the first time, files are listed immediately using `lastModified` so the gallery
  appears right away. The full EXIF capture-date pass runs on a `Thread.MIN_PRIORITY` worker after a `1.5s` delay and
  reuses the already-scanned file list (avoiding a second directory walk), so the first visible screen of thumbnails
  gets full USB/HDD I/O bandwidth without contention.
- **Embedded EXIF thumbnails first (`PhotoImageDecoder.decodeThumbnail`):**
  Gallery tiles prefer the embedded EXIF JPEG thumbnail (~20 KB in the file header) when its shortest side is at least
  `120px`, applying EXIF orientation and falling back to sampled full JPEG decoding only when absent or too small. This
  dramatically speeds up initial gallery loading on USB drives and external HDDs while keeping heap usage tiny.
- **USB / external HDD sleep & remount recovery (`LocalPhotoSource.isFolderAvailable`, `GalleryActivity`, `PhotoPreviewActivity`):**
  External USB hard drives often lose power during TV sleep and take 2–10 seconds to spin up and remount on wake.
  `LocalPhotoSource.refresh` never wipes `photo_index.tsv` when the folder is temporarily unmounted; `GalleryActivity`
  registers a foreground-only `ACTION_MEDIA_MOUNTED` receiver while open, shows `"Waiting for storage drive…"` and
  retries every 2s (up to 30s) while spinning up, and `PhotoPreviewActivity` retries up to 5×1.5s while keeping the
  `"Loading..."` wave animation active if a photo isn't readable yet on wake.
- **Press Back again to exit (`GalleryActivity`):**
  Pressing Back on the main gallery shows a `"Press Back again to exit"` toast and requires a second Back press within
  2 seconds to exit, preventing accidental exits when backing out of Preview or Settings.
- **Dark icon-only cold-start splash (`LaunchTheme`, `splash_background.xml`):**
  `GalleryActivity` uses `LaunchTheme` (`#101010` + centered `72dp` app icon, `windowNoTitle = true`) and switches to
  `AppTheme` in `onCreate`, eliminating Android's default white window / black title bar flash on cold start.
- **Live Settings updates (`GalleryActivity`, `SettingsActivity`):**
  `GalleryActivity` observes `SharedPreferences` changes while open so choosing **Use this folder** populates the
  gallery immediately behind the semi-transparent Settings panel, and toggling date/location updates live. Storage
  permission is prompted on startup and when selecting **Choose photo folder** (no separate "Photo access allowed" row).
- **Soft edge scrims for bright photos (`TvScreenUi.createEdgeScrim`):**
  Preview, Slideshow, and `PhotoDream` place subtle top/bottom black-to-transparent gradient scrims behind overlays
  plus an `8px` drop shadow so white clock, date, location, `Paused`, and action icons stay legible over bright/white
  photos while remaining virtually invisible over dark photos.
- **No edge-peek slivers in the 3-row grid:**
  The 3-row `HorizontalGridView` naturally peeks the next column on the right edge (`TARGET_VISIBLE_COLUMNS = 7.75`);
  do not reintroduce separate edge-peek `ImageView` overlays.
- **Preview Photo Details (`PhotoMetadata.readDetails`, `SettingsActivity`):**
  Opening Settings from Preview reads EXIF + header-only dimensions (`inJustDecodeBounds`) and optional `Geocoder`
  location off the main thread and renders a D-pad-scrollable **Photo details** card (omitting empty fields).

## Local implementation notes

- The settings app requests photo-read permission and includes a D-pad folder browser rooted at `/storage`, with
  `Internal Storage`, `Internal Pictures`, and mounted USB volumes (skipping unreadable `/storage/emulated` and
  `/storage/self` entries), high-contrast focus borders, direct subfolder/photo counts, a gold `Current` badge on the
  active folder, and focus restoration when navigating up. Direct file browsing is intended for the Android 8 target;
  scoped-storage behaviour on newer Android versions is not verified.
- The local photo index is persisted in app-private storage (`photo_index.tsv`, zero external dependencies) via
  `PhotoIndex`, `LocalPhotoSource`, and `PhotoRepository`. On repeat launches, Gallery, Preview, Slideshow, and
  `PhotoDream` open immediately from the cached index; Gallery reconciles quietly in the background and reads EXIF
  only for new or modified files.
- `DiskPhotoCache` provides a bounded, zero-dependency LRU disk cache in `cacheDir` (32 MB for pre-sized gallery
  thumbnails so repeat opens skip full-resolution EXIF/JPEG decodes; 128 MB cap ready for remote LAN/cloud photos).
- `PhotoImageDecoder.decodeFromSource(...)` decodes from any `PhotoSource` stream through `DiskPhotoCache` without
  buffering full files in RAM, and `NetworkMonitor` provides an API-guarded connectivity check for future LAN/cloud
  sources.
- `PhotoPreferences` wraps shared preferences in one typed class.
- Both `PhotoDream` and `SlideshowActivity` start from the cached index, run a single background reconciliation on
  their worker thread (swapping in changes without interrupting the current photo), fall back to all photos for the
  run if Favorites-only is enabled with no matching favorites in the folder, prefetch one frame ahead while releasing
  outgoing transition bitmaps on animation end (staying within the 3-frame bitmap budget), and cap consecutive decode
  failures so an unplugged drive or unreadable collection falls back cleanly to the status message and clock.
- The slideshow accepts JPEG, PNG, WebP and GIF files (GIF is displayed as a still image). It decodes within the
  display and heap budget, applies EXIF orientation (including mirrored orientations), reads EXIF capture date and GPS
  coordinates when present, and gracefully skips unreadable or out-of-memory files.
- Speed presets map Slow, Medium and Fast to 15, 8 and 3 seconds. Shuffle toggles random ordering; when off,
  photos are shown newest first (and the screensaver resumes after the last shown photo). Styles are cut, crossfade
  and horizontal slide. Photo date and photo location visibility can be toggled independently.
- The app opens to a D-pad photo gallery with a uniform 20dp safe-area margin aligning the header title, top-right
  action icons (Favorites/All, play-slideshow, Settings), and bottom-left date label (and mirrored by Preview's
  top-left photo counter `1 of N`, top-right icons, and bottom date/location footer). When photo access has not been
  granted or no photo folder is selected yet, the gallery automatically opens the Settings panel once per launch so
  first-time setup starts immediately. While scanning, a centered loading spinner replaces the empty grid.
  Favorites/All is a persisted filter shared with the slideshow and screensaver. Settings slides in from the right as a
  semi-transparent half-width panel with uppercase section headers, muted helper text, and rounded dark option cards
  that highlight with a white border on focus. The three-row thumbnail grid is centered between the shortcuts and
  footer, sized to show 7 full columns plus ~3/4 of the 8th column on 1080p, starts aligned with the 20dp left margin,
  scrolls across the full screen width with no right padding, and enlarges (1.14x) the focused thumbnail. Thumbnails
  decode off the UI thread at up to twice their rendered pixel size, with bounded decode work and a small LRU cache.
  Only the focused thumbnail's capture date is shown at the bottom left of the gallery screen to keep scrolling snappy;
  filenames and metadata overlays are not shown on thumbnails.
- The horizontal grid uses `androidx.leanback:leanback:1.0.0` (Apache-2.0) and its RecyclerView support rather than
  constructing a view for every photo. The Leanback AAR is 1.36 MB; the debug APK grew by 1.91 MB (from 936,925 to
  2,843,704 bytes). Its photo-view memory remains bounded to the visible/recycled cells; the existing thumbnail LRU
  remains capped at 6 MB.
- Shuffle toggles random ordering per run; with Shuffle off, photos are always newest-first by capture date/time.
  Photos without EXIF capture time use filesystem modification time.
- Favorites are stored by photo path. The gallery starts in Favorites when the selected folder contains any unless
  the user has persisted All; its header filter and the Settings "Show: Favorite" option are disabled when the
  selected folder contains none. Available years and months are based on the active set.
- The Settings activity controls the system DreamService's speed preset, transition style, shuffle, date/location
  visibility, and all-photos or favorites-only source through shared preferences. Android system settings still select
  Photo Dream and control when the dream starts. The DreamService is non-interactive, so its Options key is not
  available while it runs.
- Selecting a gallery thumbnail opens a full-screen preview with favorite, play, and settings icons at the top right
  (press Up to reach them, Down to return to the photo) and the date and location along the bottom. Opening Settings
  from Preview shows only Show Photo Date and Show Photo Location. Edge `<` / `>` direction indicators appear briefly
  when opening Preview, moving between photos, or pressing Down from the top-right icons (only `>` on the first photo,
  only `<` on the last), then slide off to the left/right. The remote Options/Menu key opens a right-quarter
  semi-transparent panel in the gallery and preview with Show Photo Date and Show Photo Location (plus Set As
  Screensaver in the gallery). Gold is reserved for state (active favorite, enabled toggle, current year/month);
  focused actions and icons are circled in white.
- The manual slideshow and system `DreamService` share `SlideshowStage` so they are visually identical: no on-screen
  buttons, top-right clock, matching `18sp` shadowed date/location footer, and shared fade/slide/cut transitions.
  In manual slideshow mode, Left/Right move between photos, Play/Pause or OK pauses and resumes (showing a centered
  pause/play icon), and Back returns to the gallery with the last photo highlighted.
- Location names are formatted as `Suburb, CC` using Android's `Geocoder` and cached in memory and app preferences so
  previously resolved names still display offline. The app opens no network socket itself; if the TV has no Geocoder
  backend, the location is hidden.
- Starting the manual slideshow from the gallery's play icon or remote Play key begins with the highlighted photo
  when it belongs to the configured slideshow photo set.
- The Preview screen's play icon starts the manual slideshow from that photo. Returning from Preview or its
  slideshow forwards the selected or last-displayed path to Gallery.
- When a selected photo's previous or next thumbnail is outside the visible grid, a narrow cropped preview at that
  screen edge cues the adjacent image. These two previews use the same bounded background thumbnail loader/cache.
- Thumbnails show a clean dark tile while decoding and fade in smoothly once ready; unreadable images show a
  "No preview" placeholder. The full-screen preview shows the failed filename, and left/right navigation remains
  available.
- Both the manual slideshow and system `DreamService` place the clock in the upper-right corner.
- Pixel Motion Photos and other photos with embedded motion clips currently display only their still image;
  embedded video playback is not implemented.
- Android does not expose a public API for an ordinary app to start or select its `DreamService`; the "Set as
  screensaver" option therefore opens the system's screensaver settings while Photo Dream is not selected, and is
  disabled once selected.

## Display resolution behavior

- Slideshow and preview decoding target the display's pixel dimensions, subject to a per-bitmap budget of
  one-eighth of the app heap (8 MB minimum, 256 MB maximum). The target TV's 192 MB heap and 1080p output allow
  native 1080p images; lower-resolution screens avoid unnecessary decoding. 4K/8K images can be decoded at higher
  resolutions only when the device heap allows it; otherwise they are sampled down and upscaled, which can look
  softer. Image sampling uses powers of two, so it may choose a smaller bitmap than the budget would otherwise allow.
- Gallery thumbnails are square, scale with display density up to 512x512 and remain in the existing bounded LRU
  cache.
- The target hardware has only been measured at 1920x1080 output. 720p, native 4K and 8K rendering have not been
  visually tested on hardware.

## Open questions for the owner

1. Is HEIC from phones common in the collection? If so, plan server-side conversion or a decoder library (OneDrive's
   server-side JPEG thumbnails sidestep this when used).
2. OneDrive needs an app registration (a free Microsoft Entra app, public client, device-code flow enabled). The owner
   creates it with their own Microsoft account; its client id is not a secret but is kept in `secrets.properties`.
3. Which OneDrive folder holds the photos (for example `Pictures`)? Personal or work/school account?
