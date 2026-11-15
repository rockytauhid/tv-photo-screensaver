# Design notes

Status: the local slideshow and settings screen are implemented, but not yet tested on the TV. Nothing here has
been tested on the TV except where it says "measured". Statements marked **verify** come from memory of vendor
documentation and must be checked against the current docs before code depends on them: cloud APIs change often.

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
SettingsActivity         D-pad friendly screen to add sources and set interval, order, transition
LauncherActivity         optional: a leanback tile so the settings open from Home
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
- **Index first, then play.** List a source once, save the index as JSON in app-private storage, refresh when the
  dream starts and the index is older than a set time (6 to 24 hours). Start showing from the saved index at once;
  never wait on a network listing before the first picture.
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
- Watch out: some Sony boards cut power to a USB port while USB debugging is on (cleanyourtv `PROMPT.md`). Test the
  USB path with debugging off, via `logcat` over a different route or by checking the slideshow itself.

### 2. Local network (NAS)

- **SMB.** Most NASes (Synology, QNAP, Windows, Samba) offer it. Modern ones disable SMB1, so the library must speak
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
| Immich, Nextcloud, WebDAV clouds | Token or app password | Immich already has a screensaver in the cleanyourtv showcase (Homelab companion). |

**Fallback that removes most of the cloud work:** the person syncs the cloud folder to a NAS or a USB drive with a
tool such as rclone (on a PC, NAS or Raspberry Pi), and the app only needs local and SMB/WebDAV. Decide early
whether v1 ships with cloud at all.

Rules for cloud code:
- Request the least scope, read-only.
- Prefer server thumbnails sized for 1920x1080 to save bandwidth, memory and time.
- Store refresh tokens in app-private storage, encrypted with an Android Keystore key (API 23+). Never log tokens,
  never put a client secret in the repo (public clients do not need one; `secrets.properties` is git-ignored).
- No analytics SDKs, no Firebase, no Google Play Services dependency (this TV may not have the right version, and the
  cleanyourtv rule is "no telemetry").
- Refresh on a schedule only while the dream is running or from the settings screen.

## Performance and memory

- Decode with `BitmapFactory.Options.inSampleSize` computed from the image bounds (`inJustDecodeBounds` first), then
  scale to fit 1920x1080. Use `RGB_565` only if banding is acceptable (photos are fine, dark gradients band).
- Bitmap budget: one 1920x1080 ARGB_8888 is about 8 MB; three alive is 24 MB, well under the 192 MB heap limit.
- Reuse bitmaps with `inBitmap` where possible to reduce garbage collection pauses.
- One download/decode thread, one UI thread, no thread pools that grow.
- Transitions: alpha crossfade on two `ImageView`s. The Ken Burns zoom is a matrix animation; test it on the TV
  because the GPU is weak. cleanyourtv rules out blur and video on 1 GB sets.
- Burn-in protection is not a worry on an LCD, but dim the clock overlay if one is added.
- Avoid wake locks outside the dream. The dream itself keeps the screen on.

## Settings and distribution

- Settings screen: add/remove sources, folder browser, interval (5 s to 5 min), order (random, by date, by name),
  transition (cut, fade), clock overlay (off, small), test button ("show now").
- A `DreamService` can point to a settings activity through `android:settingsActivity` and a
  `<meta-data android:name="android.service.dream">` XML resource. Whether the Sony's Settings app shows a gear is
  **unverified**, so also add a leanback launcher activity (`android.intent.category.LEANBACK_LAUNCHER`) so the
  settings open from the cleanyourtv Home.
- Distribution: a sideloaded APK over ADB, debug-signed, like the launcher. Publishing on Google Play is a separate
  decision (it needs a developer account, a privacy policy and cloud API verification).
- Licence: undecided. If cleanyourtv code is copied (for example `ClockDream.java`), keep its MIT notice.

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
| 2 | Settings and gallery | D-pad folder browser, 5-second-step interval from 5 to 300 seconds, metadata toggle, transition/order selection, virtualized thumbnail gallery and full-screen detail preview. Implemented; TV navigation and 10,000-photo responsiveness are not yet verified on hardware. |
| 3 | WebDAV, then SMB | A NAS share plays; offline NAS falls back cleanly. |
| 4 | Cloud (one service) | OneDrive device-flow sign-in, thumbnails, token stored encrypted. Then Dropbox. |
| 5 | Polish | Clock overlay, Ken Burns if the GPU copes, caching limits, translations. |

## Decisions (from the owner)

- **Language: Kotlin** (1.9.24, JVM target 17). The skeleton is converted and builds (19 KB release APK).
  Keep the standard library only; avoid coroutines and other libraries until a need is written down here.
- **No NAS.** The owner has a Windows desktop PC that runs Plex Media Server. So the local-network source order is:
  1. **Plex photo library** over HTTP with a Plex token (the TV already has the Plex app, and the server already
     indexes and resizes photos; its photo transcoder endpoint can return 1920x1080 JPEGs). **verify** endpoints.
  2. **SMB/WebDAV** by sharing a photo folder from the Windows PC (SMB2/3 is on by default in Windows). Later.
- **First cloud: OneDrive.** Others (Dropbox, ...) are added one at a time afterwards.
- Revised milestone order: 0 skeleton, 1 local slideshow, 2 settings screen, 3 OneDrive, 4 Plex, 5 SMB/WebDAV and
  other clouds, 6 polish. OneDrive moves ahead of the LAN sources.

## Local implementation notes

- The settings app requests photo-read permission and includes a D-pad folder browser rooted at `/storage`, so
  mounted USB volumes can be selected without relying on DocumentsUI. Direct file browsing is intended for the
  Android 8 target; scoped-storage behaviour on newer Android versions is not verified.
- The slideshow accepts JPEG, PNG, WebP and GIF files (GIF is displayed as a still image). It decodes to a maximum
  of 1920x1080, reads EXIF date, GPS coordinates, camera make/model, exposure, aperture, ISO and focal length when
  present, and gracefully skips unreadable files.
- Interval settings range from 5 to 300 seconds in 5-second steps. Transitions are cut, crossfade and horizontal
  slide. Metadata visibility can be toggled as a group.
- Settings also open a D-pad photo gallery. It virtualizes the grid, decodes 256x144 thumbnails off the UI thread,
  bounds concurrent decode work and caches only a small LRU of thumbnails; browsing does not change the slideshow's
  folder-wide photo set.
- Slideshow order is configurable by name, capture date/time, or random per run. Date order supports newest-first
  and oldest-first; photos without EXIF capture time use filesystem modification time.
- Selecting a gallery thumbnail opens a sampled full-screen preview with a right-side details panel. The panel is
  hidden by default, configurable in settings, and toggled with its D-pad button or the remote's Info key.
- Gallery sorting cycles through filename A–Z, filename Z–A, capture time oldest-first and newest-first; the
  preview uses that same order and wraps left/right at both ends.
- Thumbnails show a loading label while decoding and a "No preview" placeholder for unreadable images. The
  full-screen preview shows the failed filename and metadata, and left/right navigation remains available.
- Pixel Motion Photos and other photos with embedded motion clips currently display only their still image;
  embedded video playback is not implemented.
- Settings can launch an immediate app-owned full-screen slideshow using the configured interval, transition and
  metadata visibility; left/right navigate photos and Back exits. Android does not expose a public API for an
  ordinary app to start or select its `DreamService`; the "Set as screensaver" button therefore opens the system's
  screensaver settings while Photo Dream is not selected, and is disabled once selected.

## Display resolution behavior

- Slideshow and preview decoding target the display's pixel dimensions, subject to a per-bitmap budget of
  one-eighth of the app heap (8 MB minimum, 256 MB maximum). The target TV's 192 MB heap and 1080p output allow
  native 1080p images; lower-resolution screens avoid unnecessary decoding. 4K/8K images can be decoded at higher
  resolutions only when the device heap allows it; otherwise they are sampled down and upscaled, which can look
  softer. Image sampling uses powers of two, so it may choose a smaller bitmap than the budget would otherwise allow.
- Gallery thumbnails scale with display density up to 512x288 and remain in the existing bounded LRU cache.
- The target hardware has only been measured at 1920x1080 output. 720p, native 4K and 8K rendering have not been
  visually tested on hardware.

## Open questions for the owner

1. Public repository or private? Affects licence, secrets handling and whether Google Play is a goal.
2. Is HEIC from phones common in the collection? If so, plan server-side conversion or a decoder library.
3. OneDrive needs an app registration (a free Microsoft Entra app, public client, device-code flow enabled). The owner
   creates it with their own Microsoft account; its client id is not a secret but is kept in `secrets.properties`.
4. Which OneDrive folder holds the photos (for example `Pictures`)? Personal or work/school account?
