# Photo Dream

A screensaver for older Android TVs that shows your own photos: from local storage, from a NAS on the local network
(SMB, WebDAV) and from cloud storage (OneDrive, Dropbox, and others if they allow it).

First target: Sony BRAVIA KD-55X9500D, Android 8.0 (API 26), about 1.5 GB RAM, no keyboard or touch.
Status: **skeleton only.** It builds and defines a `DreamService` that shows a placeholder line; it has not been
installed on the TV yet and no photo source exists.

## Why

The TV's own screensaver (Chromecast's Backdrop) uses Google Photos through the Google Home app, and that setting
does not show for this TV. This project started from a [cleanyourtv](https://github.com/Gathin23/cleanyourtv) session
on that TV.

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
.\gradlew.bat --no-daemon assembleRelease
```

Requires JDK 17 and Android SDK platform 34 with build-tools 34.0.0. Gradle 8.9 and the Android Gradle Plugin 8.5.2 are
the same as cleanyourtv, so a cached build is fast.

## Layout

```
app/src/main/java/com/photodream/tv/PhotoDream.kt     the DreamService (placeholder)
app/src/main/AndroidManifest.xml                      dream service, INTERNET permission, leanback features
docs/                                                 context, design, deployment
```

## Licence

Not chosen yet. Code copied from cleanyourtv is MIT; keep its notice.
