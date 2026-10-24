# CodeBuddy Mobile — Implementation Report

Package: `com.flux.codebuddy` (debug: `com.flux.codebuddy.debug`)
Version: 1.0.4 (versionCode 5)
Repository: https://github.com/HakaI963/CodeBuddyAndroid
Releases: https://github.com/HakaI963/CodeBuddyAndroid/releases

## 0. Fixes in 1.0.4

At smaller sizes the right edge of the web UI was cut off (send button, top-right
toolbar icons, the last card), which got worse as the slider moved down.

- **Cause:** the zoom was derived from `max(screen.width, screen.height)`, i.e. the
  display. The WebView is not the display: the activity pads the content for
  display-cutout insets, and freeform/split-screen windows are narrower too. With a
  cutout of ~80 CSS px on a 844 px-wide phone the window is 764 px, so the page was
  laid out 844/760 of the window width and the right ~10% sat outside the screen.
- **Fix:** the fit now uses the real window width,
  `visualViewport.width * visualViewport.scale`. The product is the window width in
  CSS px at any page scale, so it is stable before and after the viewport meta is
  applied. `screen` is only a fallback for WebViews without `visualViewport`.
- Verified by simulating a 764x390 window while `screen` still reports 844x390:
  before, the visual viewport was 1147 of a 1267 CSS px layout (10% off-screen);
  after, `visualViewport.scale` is 0.6032 and the visual viewport is exactly 1267,
  with no element crossing the right edge at any preset (1.00x through 0.17x).

## 0.1 Changes in 1.0.3

The 1.0.2 layout was still too large for the reporter, and one fixed size cannot
fit both a 5" phone and a tablet. The UI size is now user-selectable.

- **Settings -> UI size** slider with seven presets: 1.00x (the 1.0.2 layout),
  0.80, 0.60 (new default), 0.45, 0.30, 0.22 and 0.17x. 0.17x is 5.9x smaller than
  1.00x, satisfying the 5-6x minimum. The value is stored in `SharedPreferences`
  (`ui_scale`), so it survives restarts.
- **Reflow, not stretch.** The document-start script rewrites the viewport to
  `width = round(760 / scale)` with `initial-scale = (screenWidth / 760) * scale`.
  The web app therefore lays out at the wider size and is zoomed out; CSS `zoom`
  was measured to shrink the height without reflowing and was rejected.
  `minimum-scale=0.05` is required: with `width` alone Chrome clamped the page
  scale to 0.25 and `innerWidth` stopped at 3376 instead of 4750.
- **Applied live and across reloads.** Changing the slider calls
  `__cbmApplyScale()` on the loaded page and writes `localStorage['cbm-ui-scale']`;
  the page is then reloaded in place (same conversation URL, not the server root).
  The document-start script is registered once per origin, so localStorage is what
  carries the value into later loads.
- **Readability fixes.** `text-size-adjust:100%` stops Blink text autosizing, and
  `--conversation-content-max-width:100%` removes the 880px cap that otherwise
  leaves the chat/composer in a narrow column on a wide scaled viewport.
- The phone-only viewport guard was removed: the scale now applies on tablets too,
  where 1.00x used to zoom 760px into e.g. 1280px (1.68x, i.e. very large).

Measured in Chromium at 844x390 CSS px with the generated script (deviceScaleFactor
2, isMobile):

| preset | layout viewport | page scale | root font | header | composer | apparent text |
| --- | --- | --- | --- | --- | --- | --- |
| 1.00x | 760x352 | 1.111 | 12px | 44px | 86px | 13.3px |
| 0.60x | 1267x586 | 0.666 | 12px | 44px | 86px | 8.0px |
| 0.17x | 4471x2066 | 0.189 | 12px | 44px | 86px | 2.3px |

"Apparent text" is `12 CSS px * page scale`, so 0.60x is 1.67x smaller than 1.00x
and 0.17x is 5.9x smaller. The 0.60x and 0.17x rows use the SPA's desktop layout
(viewport >= 768px), which is the only way to fill the screen at those scales; the
compact rules are attached to `html.cbm-compact` (device short side <= 560px)
instead of a viewport media query, so they keep matching when the layout viewport
grows. Switching 0.60 -> 0.17 -> 0.60 produced no JavaScript errors, and the value
survived a reload each time.

## 0.2 Fixes in 1.0.2

The web UI was rendering its **desktop** layout inside the phone. At 844x390 CSS px
the SPA keeps a 360px session panel and a 56px rail open, leaving roughly half the
width for chat, and its default type scale filled most of the 390px height.

- The WebView now honours the page viewport (`useWideViewPort` /
  `loadWithOverviewMode`) and the document-start script narrows it to `width=760`
  on phone-sized screens only (`max(screen.width, screen.height) <= 1000`). That
  switches the SPA to its own mobile layout: full-width chat, drawer sidebar, no
  permanent session panel. Tablets keep the desktop layout.
- The compact layer now sets a 12px root font (rem-based spacing/typography shrinks
  ~25%), a 44px header, an ~86px composer (22px context chips, 12px input, 24px
  toolbar) and a login page that fits a 351px-tall viewport without clipping.

Measured at 844x390 with the new layer: layout viewport 760x352 at 1.11x zoom,
header 44px, composer 86px, chat column 760px wide (was 428px).

## 0.3 Fixes in 1.0.1

Two problems caused the app to sit on "Connecting to CodeBuddy…" forever:

1. **WebView clients were installed before they existed.** `WebShell` called
   `configure(initialWebView)` from an `init` block declared above the
   `shellClient` / `shellChromeClient` properties. Kotlin runs property
   initializers and `init` blocks in declaration order, so both were still `null`
   when assigned to the WebView. With no `WebViewClient`/`WebChromeClient` the app
   received no `onPageStarted`, `onProgressChanged`, `onPageFinished` or
   `onReceivedError` callbacks, so the loading overlay could never be dismissed and
   the toolbar stayed on "Connecting…". The `init` block now sits below the client
   declarations.
2. **No timeout on a stalled request.** A TCP connect to an unreachable address can
   stay pending for minutes without an error callback. The app now probes the
   server after 8 s (and again at 20 s) and replaces the spinner with a specific
   diagnosis: refused, timed out, unknown host, TLS, wrong server, or offline, with
   the exact command to start the server or the `adb reverse` alternative.

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

The viewport is rewritten to `width = 760 / scale` at the matching zoom, so the
web app reflows at every preset. At 1.00x that is the phone layout (760px:
full-width chat, drawer sidebar); below 1.00x the layout viewport passes 767px and
the SPA switches to its desktop layout, which is what fills the screen when zoomed
out. The compact CSS is attached to `html.cbm-compact`, set when the device's short
screen side is <= 560px, so it keeps matching after the layout viewport grows and
tablets are untouched:

- 12px root font, so rem-based spacing and type shrink by ~25%;
- header 44px, composer ~86px (22px chips, 12px input, 24px toolbar), 24px send;
- login page fits a 351px-tall viewport; only the first hint line is shown;
- `text-size-adjust:100%` disables Blink text autosizing;
- `--conversation-content-max-width:100%` removes the 880px chat cap.

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

Workflow runs [`40114071790`](https://github.com/HakaI963/CodeBuddyAndroid/actions/runs/40114071790)
(`main`, commit `6207975`) and [`40114464851`](https://github.com/HakaI963/CodeBuddyAndroid/actions/runs/40114464851)
(tag `v1.0.3`) completed green:

```
./gradlew --no-daemon --stacktrace testDebugUnitTest lintDebug assembleDebug assembleRelease
```

- `testDebugUnitTest` — **20 JVM unit tests**, all passing. `ServerUrlTest.kt`
  covers bare-address normalization, query/fragment preservation, HTTPS public
  hosts, public HTTP rejection (incl. 172.32.x, 100.128.x, IPv6 GUA), private IPv4
  ranges, local names, credentials, password query (incl. percent-encoded), invalid
  ports, malformed input, IPv6 loopback/ULA/link-local, non-default port, encoded
  paths and scheme normalization. `MobileInjectionTest.kt` checks that the generated
  viewport carries the selected scale (and clamps it to 0.17-1.00), that the apply
  script uses the same localStorage key, that the CSS disables text autosizing and
  removes the 880px cap, and that the preset list spans 1.00x down to 0.17x with
  0.17 at index 6.
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
package: name='com.flux.codebuddy' versionCode='4' versionName='1.0.3' compileSdkVersion='35'
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
| `CodeBuddyMobile-release.apk` | 170,716 | 167 KiB |
| `CodeBuddyMobile-debug.apk` | 2,651,350 | 2.5 MiB |

The release APK contains a 224,088-byte `classes.dex`, a 23,560-byte
`resources.arsc`, and obfuscated resources; `MainActivity` and
`ui.MaxWidthLinearLayout` are retained by name because the layout inflater needs
them. The 1.0.3 additions were confirmed inside the shipped `classes.dex`
(`cbm-ui-scale`, `cbm-compact`, `__cbmApplyScale`, `initial-scale`,
`text-size-adjust`, `--conversation-content-max-width`, `760 / S`) and inside
`resources.arsc` (`UI size`, `smaller than 1.00`, `scaleSeek`, `scaleValue`).

SHA-256 checksums are published next to the APKs as `SHA256SUMS.txt` and can be
verified with `sha256sum -c SHA256SUMS.txt`. They are generated by the same CI job
that builds the APKs, so they always match the published files. (The build is not
byte-reproducible: `META-INF/version-control-info.textproto` embeds commit data, so
each run produces different bytes.)

Each release publishes its own `SHA256SUMS.txt`; the values were verified with
`sha256sum -c` after downloading the assets of that release.

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
2. Install `CodeBuddyMobile-release.apk`.
3. Enter the server URL, e.g. `http://192.168.1.20:7890`, optionally a display name,
   and tap **Connect**. Use **Test** to probe reachability first. The **UI size**
   slider on the same screen sets the scale; **Save & connect** applies it and
   reloads the open conversation at the new size.
4. For USB-only setups: `adb reverse tcp:7890 tcp:7890`, then connect to
   `http://127.0.0.1:7890`.
