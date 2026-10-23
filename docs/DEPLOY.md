# Deploying to the TV

Set up once: the PC needs JDK 17, the Android SDK (platform 34, build-tools 34.0.0) and the cleanyourtv tools. The
details are in [CONTEXT.md](CONTEXT.md). The TV needs USB debugging and Network/ADB debugging on, and the PC needs
to be allowed once through the prompt on the TV. Close ADB again when you finish (last section).

All commands run from `<repo>` in PowerShell. Keep `<tv ip>` out of anything
you commit.

## Build

```powershell
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat --no-daemon assembleRelease
```

The APK is `app\build\outputs\apk\release\app-release.apk`. `local.properties` (git-ignored) holds `sdk.dir`;
Gradle needs it, so create it with `sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk` if it is missing.

## Install

Uses the cleanyourtv ADB client, which needs no `adb.exe`. In PowerShell:

```powershell
$tools = '<cleanyourtv clone>\tools'
$py = "$tools\venv\Scripts\python.exe"
$env:TV_HOST = '<tv ip>'
& $py "$tools\tv.py" --push app\build\outputs\apk\release\app-release.apk /data/local/tmp/photodream.apk
& $py "$tools\tv.py" 'pm install -r /data/local/tmp/photodream.apk'
& $py "$tools\tv.py" 'rm /data/local/tmp/photodream.apk'
```

`tv.py --push` takes `<local> <remote>`; check the first lines of `tv.py` and `b64push.py` if it complains, and
compare with `launcher\build-and-install.ps1`, which pushes in chunks and checks the md5.

## Select the screensaver

On the TV: Settings > Device Preferences > Screen saver > Photo Dream. Or over ADB:

```powershell
& $py "$tools\tv.py" 'settings put secure screensaver_components com.photodream.tv/.PhotoDream'
```

Undo (back to Chromecast's Backdrop, the setting before this project):

```powershell
& $py "$tools\tv.py" 'settings put secure screensaver_components com.google.android.backdrop/.Backdrop'
```

The TV starts the screensaver after its idle timeout (Settings > Device Preferences > Screen saver > Start now or
When to start). **Record here** whether `Start now` works and whether another command starts it on demand.

## Remove

```powershell
& $py "$tools\tv.py" 'pm uninstall com.photodream.tv'
```

This only removes this app. The settings value above must be restored first, or the TV falls back to its default.

## Closing ADB afterwards

From the cleanyourtv rules (`AGENTS.md`, "Close ADB afterwards"): switch USB debugging and Network/ADB debugging off
in the TV's Developer options, revoke USB debugging authorisations, reboot the TV, then check the port is closed:

```powershell
& $py "$tools\adb-closed.py" <tv ip>
```

Port 5555 is plaintext and unauthenticated for anyone on the same network once an authorisation exists, so do not
leave it open.
