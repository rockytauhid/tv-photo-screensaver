---
name: deploy-photo-dream-to-tv
description: Build and install this Photo Dream app on the user's explicitly authorized Android TV over the local network using the project's documented tools. Use only when the user asks to deploy or update Photo Dream on their TV.
---

# Deploy Photo Dream to an authorized TV

Use this workflow only for the user's own TV, with their explicit authorization and a host/IP address they provide. It installs this project only; it is not a general Android TV control skill.

## Scope and safety

- Target exactly one TV identified by the user. Never scan, sweep, enumerate, or guess hosts on the local network. Do not use mDNS, port scans, `adb devices` discovery, or the upstream `android-tv-skills` automation.
- Do not inspect or control other devices, query viewing history or installed apps, launch media, capture screenshots, change power state, clear app data, or uninstall apps.
- Do not enable debugging remotely. The user must be physically present to enable USB and network/ADB debugging and approve the computer on the TV.
- Network ADB is unauthenticated to other hosts on the LAN once authorized. Keep it enabled only for the deployment session. Do not store the TV host, ADB keys, or credentials in the repository, shell profiles, scripts, or logs.
- Do not change the selected screensaver unless the user explicitly asks. Preserve the previous value so it can be restored.
- Do not alter unrelated TV settings or disable, remove, or modify system packages.
- Use only the commands below for deployment. If a command fails or its result is unclear, stop and report the failure; do not broaden troubleshooting into device discovery or arbitrary shell commands.

## Procedure

1. Read the repository's `AGENTS.md`, `README.md`, `docs/CONTEXT.md`, and `docs/DEPLOY.md`. Follow their TV-specific safeguards and use their supported Android/JDK versions.
2. Confirm the user wants this app installed on their own TV and obtain the exact TV host/IP from them. If a local ADB helper tools path is used and unavailable, ask for its location; do not search the network or guess a filesystem path.
3. Build the release APK from the repository root:

   ```powershell
   $env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot'
   $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
   .\gradlew.bat --no-daemon testDebugUnitTest assembleRelease
   ```

   If the build or tests fail, stop before deployment and report the result.

4. With the user physically present, have them enable USB and network/ADB debugging on the TV and approve this computer on the TV. Do not attempt to enable debugging or approve a prompt through ADB.
5. In the current PowerShell process only, configure the user-provided host and the local ADB helper tool path. Do not persist these values:

   ```powershell
   $tools = '<adb-tools-path>'
   $py = "$tools\venv\Scripts\python.exe"
   $env:TV_HOST = '<user-provided TV host>'
   ```

6. Push and install only this app's release APK:

   ```powershell
   & $py "$tools\tv.py" --push app\build\outputs\apk\release\app-release.apk /data/local/tmp/photodream.apk
   & $py "$tools\tv.py" 'pm install -r /data/local/tmp/photodream.apk'
   & $py "$tools\tv.py" 'rm /data/local/tmp/photodream.apk'
   ```

   Verify the install command reports success. Do not report deployment success if it did not.

7. If the user also asks to select Photo Dream as the screensaver, first read and preserve the current value:

   ```powershell
   & $py "$tools\tv.py" 'settings get secure screensaver_components'
   ```

   If the current value is empty or cannot be read, stop and ask the user before changing it. Otherwise, set Photo Dream only with the user's authorization:

   ```powershell
   & $py "$tools\tv.py" 'settings put secure screensaver_components com.photodream.tv/.PhotoDream'
   ```

   Tell the user the exact saved value and how to restore it:

   ```powershell
   & $py "$tools\tv.py" 'settings put secure screensaver_components <saved value>'
   ```

   Otherwise, leave the current screensaver selection unchanged and let the user select Photo Dream in TV settings.
8. When deployment is complete, have the user turn off USB and network/ADB debugging, revoke debugging authorizations, and reboot the TV. Then check only the supplied host with the project's `adb-closed.py` tool, as documented in `docs/DEPLOY.md`. If the port is still open, report that plainly and do not claim the session is secured.

## Reporting

Report the build/test outcome, whether installation succeeded, whether screensaver selection was changed, and whether ADB was verified closed. Never include the TV host in committed files or public output.
