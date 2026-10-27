# CodeBuddy Mobile

A native Android app for **CodeBuddy Code**. It is not a website wrapper: the
home screen, app bar, settings, recent-conversation list and every button are
Android views. Only the chat surface itself is the CodeBuddy web UI, hosted in a
WebView that is **retained across app switches and restored across process
death**, so reopening the app from recents returns to the conversation you were
in instead of booting the site again.

Package: `com.flux.codebuddy`  
App name: **CodeBuddy Mobile**  
Version: 2.0.0 (versionCode 6)

## Why 2.0.0

1.0.x opened the server URL in a WebView on every launch. That is why reopening
the app from the recents list looked like loading a website: the SPA was fetched
and booted again. 2.0.0 fixes the cause instead of the symptom:

| Cause in 1.0.x | Fix in 2.0.0 |
| --- | --- |
| `onCreate` always called `webShell.load()` | The WebView is only loaded when the chat is actually opened; the home screen is native and instant |
| No WebView state retention | The live WebView is handed to the new activity via `onRetainCustomNonConfigurationInstance` |
| No state across process death | `WebView.saveState()` / `restoreState()` + `android:alwaysRetainTaskState="true"` |
| `WebView.pauseTimers()` on pause froze the SPA's timers | Only the page is paused; timers and the socket stay live |
| WebView destroyed on renderer death | A fresh WebView is created and the failure is reported once |
| Network callback could silently reload | The callback only updates status; it never navigates |
| Toolbar buttons were JS lookups into the page DOM | All navigation, reload, settings, new chat and home are native |

## What is native now

- **Home screen** - server card with live status, `Continue`, `New chat`, `Test`,
  copy URL, open in browser, and a recents list of the conversations you opened.
- **App bar** - back-to-home, connection dot, title, reload, and an overflow
  sheet (continue, new chat, sidebar, browser, home, settings).
- **Settings** - server URL, display name, remember toggle, UI size, keep-screen-on,
  test connection, and clear login/cache.
- **Recents** - long-press an entry to rename it; the list is capped at 12 and is
  pruned when you point the app at a different server.

Chat, streaming, sessions, tools, the file tree and login are still handled by
CodeBuddy's own web UI - the app does not reimplement the backend protocol.

## Requirements

- Android 8.0+ (API 26) with an up-to-date Android System WebView.
- CodeBuddy Code running on your machine.

## Server preparation

The server must listen on the LAN interface:

```bash
codebuddy --serve --host 0.0.0.0 --port 7890
```

Then allow the port (ufw example) and enter `http://<lan-ip>:7890` in the app.
Do **not** forward port 7890 on your router; use WireGuard/Tailscale or an HTTPS
reverse proxy for remote access.

## Using the app

1. Install the APK and enter your server address (a bare `192.168.1.100:7890` is
   accepted and treated as HTTP).
2. Tap **Test** to check reachability, then **Connect**.
3. Log in inside the chat. The login is stored by the WebView and survives
   restarts (cloud backup is disabled).
4. Press Back from the chat to reach the native home screen. Reopening the app
   from recents returns to the same conversation.

For USB debugging, `adb reverse tcp:7890 tcp:7890` lets the phone use
`http://127.0.0.1:7890`.

## Build

Everything is built by GitHub Actions; no local Gradle, SDK or NDK is required.

`.github/workflows/android.yml` runs unit tests, lint, and the debug and release
builds on every push to `main`, then publishes the APKs as **GitHub Release**
assets (workflow artifacts are quota-limited):

- every push to `main` updates the rolling `ci-latest` pre-release;
- a `v*` tag creates the matching release.

The workflow verifies the APK with `apksigner verify` and prints
`aapt2 dump badging`.

Set these repository secrets to produce a **signed** release APK in CI:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | `base64 -w0 release.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | keystore password |
| `ANDROID_KEY_ALIAS` | key alias |
| `ANDROID_KEY_PASSWORD` | key password |

Without the secrets the release APK is built unsigned (`app-release-unsigned.apk`)
and the debug APK is still signed with the debug key.

## Security model

- **No SSL bypass.** `onReceivedSslError` cancels; only system trust anchors.
- **No JavaScript bridge.** `addJavascriptInterface` is not used. The only
  injected script sets the existing theme/sidebar preferences and scoped CSS.
- **HTTP only for private hosts.** Cleartext is allowed at the platform level
  (Android cannot express RFC1918 ranges in a network security config) and
  `ServerUrl` enforces the policy: loopback, RFC1918, link-local, CGNAT, IPv6
  ULA/link-local, `.local`/`.lan`/`.home`/`.internal` or single-label hosts.
  Public hosts must use HTTPS.
- **Credentials in URLs are rejected**, including `?password=`.
- **No file:// access**, mixed content blocked.
- **External links** open in the system browser; unknown schemes are blocked.
- **No secrets in logs**; release builds log nothing.
- **Backup disabled** so the WebView token is not uploaded to cloud backups.
- **Recents** store only URLs and titles, never tokens.

## Project layout

```
app/src/main/java/com/flux/codebuddy/
  MainActivity.kt          home/chat/settings screens, insets, back, status
  WebShell.kt              retained WebView, clients, downloads, file chooser
  RecentsCodec.kt          pure-JVM recents format and list rules (unit tested)
  RecentsStore.kt          SharedPreferences wrapper for the recents list
  MobileInjection.kt       scoped CSS + theme/sidebar seeding + sidebar toggle JS
  ServerUrl.kt             pure-JVM URL policy and normalization
  ServerProbe.kt           reachability test
  ServerStore.kt           SharedPreferences persistence
  UiScale.kt               UI size presets
  ui/ServerConfigView.kt   native settings form
  ui/MaxWidthLinearLayout.kt
app/src/main/res/          layouts, drawables, dark theme, network security config
app/src/test/              ServerUrl, MobileInjection and RecentsCodec tests
.github/workflows/         CI build + release
```

## Known limitations

- Blob/data downloads (`URL.createObjectURL`) cannot be handed to
  `DownloadManager`; HTTP(S) downloads work.
- `window.open` popups are not supported.
- The status dot reflects page loads and network availability, not the web UI's
  internal WebSocket state.
- Microphone, camera and geolocation requests from the page are denied.
- Public HTTP is intentionally blocked; self-signed certificates are rejected.
- The compact CSS depends on current CodeBuddy class names; the native UI is
  unaffected if they change.
- The server must be started with `--host 0.0.0.0`; the app cannot change that.
