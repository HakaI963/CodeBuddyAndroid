# CodeBuddy Mobile — Implementation Report

Package: `com.flux.codebuddy` (debug: `com.flux.codebuddy.debug`)
Version: 1.0.0 (versionCode 1)
Repository: https://github.com/HakaI963/CodeBuddyAndroid
Releases: https://github.com/HakaI963/CodeBuddyAndroid/releases

## 1. What was built

A native Android shell that hosts the **existing** CodeBuddy Code web UI in the
system WebView. The AI engine, chat, sessions, streaming and authentication remain
entirely server-side; nothing in the CodeBuddy installation was modified.

- Kotlin + AndroidX + Gradle Kotlin DSL, `minSdk 26`, `targetSdk/compileSdk 35`.
- Landscape-only (`sensorLandscape`), edge-to-edge with hidden system bars.
- 40dp toolbar, collapsible session sidebar (via the web UI's own control),
  compact dark styling, server configuration screen with URL validation.
- Release build is R8-minified with resource shrinking and signed.

Source layout (8 Kotlin files, ~1.8k lines):

| File | Responsibility |
| --- | --- |
| `MainActivity.kt` | window/insets/immersive, config flow, back handling, connectivity |
| `WebShell.kt` | WebView configuration, clients, downloads, lifecycle, sidebar control |
| `MobileInjection.kt` | document-start theme/sidebar seeding + scoped compact CSS |
| `ServerUrl.kt` | pure-Kotlin URL policy (scheme/host/port/credentials/password params) |
| `ServerStore.kt` | `SharedPreferences` persistence (opt-in "remember") |
| `ServerProbe.kt` | blocking reachability probe with typed failures |
| `ui/ServerConfigView.kt` | setup/settings form binding, validation, busy state |
| `ui/MaxWidthLinearLayout.kt` | `maxWidthDp` measurement for the config card |

## 2. Environment inspection (before implementation)

`docs/INSPECTION.md` records the measurements taken from the running web UI at
`http://127.0.0.1:7890` with Playwright at 844×390, 640×360, 568×320 and 1024×600.

Key findings that shaped the design:

- The web UI is already responsive (breakpoints at 767/768px and 640px), so only a
  small height-scoped CSS layer was needed — no fork of the frontend.
- Theme and sidebar state are read from `localStorage` (`codebuddy-theme` expects
  JSON, `codebuddy-sidebar` holds panel state), so the app seeds those keys before
  the SPA boots rather than fighting the UI after load.
- A bare origin redirects to `?hostId=…&hostEpoch=…&folder=…`, so both bare origins
  and full URLs (with query/fragment preserved) are accepted.

## 3. Mobile adaptations

Scoped to `@media (orientation:landscape) and (max-height:520px)`, so tablets and
desktop browsers are untouched:

- status/header 56px → 40px, composer 134px → 108px, composer toolbar 44px → 32px;
- compact chips/send button (32px), tighter textarea padding, 13px message text;
- login page made scrollable and compact so the form fits a 320px-tall viewport.

Android-side: cutout padding on the short edges, IME insets applied as bottom
padding, immersive mode restored after the keyboard closes, focus changes,
configuration changes and `onResume`. Back dismisses the IME first, then the
settings overlay, then closes the web sidebar, then WebView history, then a
double-back exit.

## 4. Security model

- No `addJavascriptInterface`; the only script is a document-start injection scoped
  to the configured origin (with a per-page fallback on older WebViews).
- No SSL bypass: `onReceivedSslError` cancels, client certificates are cancelled,
  only system trust anchors are used.
- `allowFileAccess`/`allowContentAccess` disabled, mixed content never allowed,
  `allowBackup=false`, WebView debugging only in debug builds.
- URL policy in `ServerUrl.kt`: http/https only, no userinfo, no `password`/`passwd`
  query keys (percent-decoded before checking), HTTP permitted only for
  loopback/RFC1918/link-local/CGNAT/IPv6 ULA/link-local and `.local`/`.lan`/`.home`/
  `.internal`/single-label hosts; public hosts require HTTPS.
- No secrets are logged. Release signing material comes from CI secrets.

## 5. Build and verification (actually executed)

The build host is aarch64 and has no `/dev/kvm`; Google Maven publishes no
`aapt2` for linux-arm64, so a local Gradle build is impossible. The project is
therefore compiled by GitHub Actions on `ubuntu-latest`, which also matches the
requested workflow.

Workflow run [`40103610669`](https://github.com/HakaI963/CodeBuddyAndroid/actions/runs/40103610669)
(commit `9184cfa`) completed green:

```
./gradlew --no-daemon --stacktrace testDebugUnitTest lintDebug assembleDebug assembleRelease
```

- `testDebugUnitTest` — **15 JVM unit tests**, all passing (`ServerUrlTest.kt`):
  bare-address normalization, query/fragment preservation, HTTPS public hosts,
  public HTTP rejection (incl. 172.32.x, 100.128.x, IPv6 GUA), private IPv4 ranges,
  local names, credentials, password query (incl. percent-encoded), invalid ports,
  malformed input, IPv6 loopback/ULA/link-local, non-default port, encoded paths,
  scheme normalization.
- `lintDebug` — completed with `abortOnError = true`; no blocking issues.
- `assembleDebug` / `assembleRelease` — both produced APKs.

`apksigner verify` on the release APK:

```
Verifies
Verified using v2 scheme (APK Signature Scheme v2): true
Number of signers: 1
Signer #1 certificate DN: CN=CodeBuddy Mobile, OU=Personal, O=Flux, L=Local, ST=Local, C=US
Signer #1 certificate SHA-256 digest: 25cce6cf73517b16b5fb25cb85ea81bb5271ca5fb82265f2085f23addd87976c
Signer #1 key algorithm: RSA
Signer #1 key size (bits): 4096
```

(`v1`/`v3` are reported `false`: AGP signs with v2 only for `minSdk 26`, which covers
API 26+. `v1` is unnecessary and `v3` requires `minSdk >= 28`.)

`aapt2 dump badging` on the release APK:

```
package: name='com.flux.codebuddy' versionCode='1' versionName='1.0.0' compileSdkVersion='35'
minSdkVersion:'26'
targetSdkVersion:'35'
uses-permission: name='android.permission.INTERNET'
uses-permission: name='android.permission.ACCESS_NETWORK_STATE'
uses-permission: name='android.permission.WRITE_EXTERNAL_STORAGE' maxSdkVersion='28'
application-label:'CodeBuddy Mobile'
launchable-activity: name='com.flux.codebuddy.MainActivity'
```

### Measured APK sizes

| Artifact | Bytes | Size |
| --- | ---: | ---: |
| `CodeBuddyMobile-1.0.0-release.apk` | 165,872 | 162 KiB |
| `CodeBuddyMobile-1.0.0-debug.apk` | 2,639,622 | 2.5 MiB |

The release APK contains a 216,600-byte `classes.dex`, a 22,312-byte
`resources.arsc`, and obfuscated resources; `MainActivity` and
`ui.MaxWidthLinearLayout` are retained by name because the layout inflater needs
them.

SHA-256 checksums are published next to the APKs as `SHA256SUMS.txt` and can be
verified with `sha256sum -c SHA256SUMS.txt`. They are generated by the same CI job
that builds the APKs, so they always match the published files. (The build is not
byte-reproducible: `META-INF/version-control-info.textproto` embeds commit data, so
each run produces different bytes.)

## 6. Tests that were NOT run

- **No emulator or physical-device run.** This host has no `/dev/kvm` and no
  attached device, so the app has not been launched on Android. Rendering,
  keyboard behavior, immersive mode and the sidebar toggle are implemented from the
  WebView/WindowInsets APIs and the measured web UI, but are unverified on-device.
- No instrumentation tests (`androidTest`) exist.
- No automated test of `ServerProbe` (it performs real network I/O); it is exercised
  only by the app's "Test" button.

## 7. Known limitations

- Compact CSS targets the web UI's current class names; a future frontend change
  could require updating `MobileInjection.kt` (the app still works, just less dense).
- `blob:`/`data:` downloads are not supported (WebView cannot stream them to
  `DownloadManager`); they show a toast.
- `window.open`/popups are disabled; links open in-app if same server, otherwise in
  the external browser.
- Media capture and geolocation are denied.
- Self-signed HTTPS certificates and public plain-HTTP servers are rejected by design.
- The toolbar connection dot reflects network/HTTP reachability, not the WebSocket
  state shown inside the web UI.
- Landscape lock can be ignored by apps in multi-window on some OEM builds.

## 8. Using the app

1. Start the server on the Linux host:
   `codebuddy --serve --host 0.0.0.0 --port 7890`
2. Install `CodeBuddyMobile-1.0.0-release.apk`.
3. Enter the server URL, e.g. `http://192.168.1.20:7890`, optionally a display name,
   and tap **Connect**. Use **Test** to probe reachability first.
4. For USB-only setups: `adb reverse tcp:7890 tcp:7890`, then connect to
   `http://127.0.0.1:7890`.
