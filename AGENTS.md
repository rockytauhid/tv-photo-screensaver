# AGENTS.md

Instructions for AI coding agents (Copilot, Claude Code, Codex, Gemini CLI, Cursor) working in this repository.
Start with [README.md](README.md), then [docs/CONTEXT.md](docs/CONTEXT.md) and [docs/DESIGN.md](docs/DESIGN.md).

## What this is

An Android TV screensaver (`DreamService`) that shows photos from local storage, a NAS (SMB, WebDAV) and cloud
storage. Kotlin (standard library only until a dependency is justified), `minSdk 26`, built with Gradle 8.9 and AGP 8.5.2, JDK 17.

## Rules

- **Target the oldest TV:** API 26, 1.5 GB RAM, 192 MB heap limit, no touchscreen, no DocumentsUI, no keyboard.
  Everything must work with the D-pad. Check `Build.VERSION.SDK_INT` before any newer API.
- **Memory first:** decode images at 1920x1080 or smaller, at most three bitmaps alive, no full-size decodes.
- **Nothing runs unless the dream is showing** (or the settings screen is open): no background service, no polling,
  no wake locks.
- **No telemetry, analytics, ads or Firebase.** Network only to servers the person configures.
- **Read-only cloud scopes**, refresh tokens encrypted with the Android Keystore, never logged.
- **No secrets or identifiers in the repo:** no IP addresses, Wi-Fi names, emails, serial numbers, passwords, OAuth
  client secrets, tokens, ADB keys. Use `<tv ip>` or `192.168.1.50`. `secrets.properties`, keystores, `backup/`,
  `shots/` and `work/` are git-ignored; keep them that way.
- **Do not add dependencies** without writing down in `docs/DESIGN.md` what they cost (APK size, heap, licence) and
  why a hand-written client would not do. Avoid Google Play Services.
- **Do not edit the cleanyourtv repository** from here. It is a reference at `<cleanyourtv clone>`.
- **Be honest about what is verified.** Mark unverified API behaviour as such in the docs, and check cloud API terms
  against their current documentation before coding against them.

## Working on the TV

Follow the safety rules in `docs/CONTEXT.md` and `docs/DEPLOY.md`. In short: back up before changing settings,
change one thing at a time, keep the undo command (`settings put secure screensaver_components
com.google.android.backdrop/.Backdrop`), never touch factory or test menus, never disable Android's core packages,
and close ADB (USB and network debugging off, reboot, `adb-closed.py`) when the session ends.

## Before finishing a change

- `.\gradlew.bat --no-daemon testDebugUnitTest assembleRelease` passes (set `JAVA_HOME` and `ANDROID_HOME` as in
  README.md).
- New behaviour has a JVM unit test where it can be tested without a TV.
- A visual change has a screenshot from the TV (`tv.py --screenshot`), with Wi-Fi names and people blurred.
- Docs stay in step: update `docs/DESIGN.md` (milestones, decisions) and `docs/DEPLOY.md` (anything learned about
  starting or selecting the dream).
