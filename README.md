# CodeBuddy Mobile

A lightweight, landscape-only Android shell for the existing **CodeBuddy Code**
web UI. It embeds the system WebView, keeps CodeBuddy's own chat/session/streaming
backend untouched, and adds only the Android integration that a browser cannot
provide: a compact native toolbar, immersive full screen, keyboard-safe layout,
persistent server configuration, and a reachability check.

Package: `com.flux.codebuddy`  
App name: **CodeBuddy Mobile**  
Version: 1.0.3 (versionCode 4)

## What this is

- Native Android app (Kotlin, platform Views, AndroidX, system WebView).
- No React Native, Flutter, Electron, bundled browser, or second JS runtime.
- No replacement chat engine and no copy of the CodeBuddy backend.
- The existing web UI remains responsible for sessions, messages, streaming,
  Markdown, code highlighting, terminal/tool panes, file operations, and login.
- The app adds a 40 dp toolbar, dark theme, immersive mode, a compact server
  screen, retry/offline handling, and a small mobile CSS layer.

See [docs/INSPECTION.md](docs/INSPECTION.md) for the web UI inspection that drove
the design and [REPORT.md](REPORT.md) for build/test results and limitations.

## Requirements

- Android 8.0+ (API 26), ARM or x86 device with an up-to-date Android System WebView.
- CodeBuddy Code running on the Linux machine.
- Phone/tablet and Linux machine on the same Wi-Fi (or a VPN / HTTPS reverse proxy).

## Server preparation (important)

The current server is listening on `127.0.0.1:7890`, which is **not reachable from
the phone**. Restart CodeBuddy so it listens on the LAN interface:

```bash
codebuddy --serve --host 0.0.0.0 --port 7890
```

Find the Linux machine's LAN IP:

```bash
ip -4 addr show scope global
```

Allow the port through the firewall if needed (example for ufw):

```bash
sudo ufw allow 7890/tcp
```

Do **not** forward port 7890 on your router. For remote access use WireGuard/Tailscale
or an HTTPS reverse proxy with a trusted certificate. The server password is printed
at startup and is entered in the web UI; the Android app never stores it.

## Using the app

1. Install the APK.
2. Enter `http://<linux-lan-ip>:7890` (for example `http://192.168.1.100:7890`).
   A bare `192.168.1.100:7890` is accepted and treated as HTTP.
3. Optionally set a display name and keep **Remember this server** checked.
4. Tap **Test** to check reachability, then **Connect**.
5. Log in with the CodeBuddy server password in the web UI. The login is stored by
   the WebView and survives app restarts (backup is disabled).
6. Use the toolbar panel button to open/close the existing session sidebar, the
   reload button to reconnect, and the sliders button to edit the server.

You can paste a full CodeBuddy URL including `hostId`, `hostEpoch` and `folder`
query parameters. The web UI also adds those automatically when omitted.

For USB debugging, `adb reverse tcp:7890 tcp:7890` lets the phone use
`http://127.0.0.1:7890` through the cable.

## Build

Requirements: JDK 17+ and Android SDK 35 (`platforms;android-35`,
`build-tools;35.0.0`). Gradle 8.9 is provided by the wrapper.

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

Signed release build:

```bash
export CBM_KEYSTORE_PATH=/absolute/path/release.jks
export CBM_KEYSTORE_PASSWORD='...'
export CBM_KEY_ALIAS=codebuddy
export CBM_KEY_PASSWORD='...'
./gradlew assembleRelease
```

APK: `app/build/outputs/apk/release/app-release.apk`

If the signing variables are absent, the release variant is produced as
`app-release-unsigned.apk`. Never commit the keystore or passwords.

### GitHub Actions

`.github/workflows/android.yml` runs unit tests, lint, debug and release builds on
every push/PR. Because GitHub Actions artifact storage is quota-limited, the APKs
are published as **GitHub Release assets** instead of workflow artifacts:

- every push to `main` updates the rolling `ci-latest` pre-release;
- a `v*` tag creates the corresponding release (e.g. `v1.0.0`).

The workflow also verifies the release APK with `apksigner verify` and prints the
package/version/SDK data with `aapt2 dump badging`.

Set these repository secrets to produce a signed release in CI:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | `base64 -w0 release.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | keystore password |
| `ANDROID_KEY_ALIAS` | `codebuddy` |
| `ANDROID_KEY_PASSWORD` | key password |

Without the secrets the workflow still builds and publishes an unsigned release APK.

## Security model

- **No SSL bypass.** `onReceivedSslError` cancels; only system trust anchors are
  trusted. Self-signed certificates are rejected.
- **No JavaScript bridge.** `addJavascriptInterface` is not used. The only injected
  script is a document-start script that sets the existing theme/sidebar
  preferences and adds scoped CSS.
- **HTTP only for private hosts.** Android's network security config cannot express
  dynamic RFC1918 ranges, so cleartext is permitted at the platform level and
  `ServerUrl` enforces the real policy: HTTP is accepted only for loopback,
  RFC1918, link-local, CGNAT, IPv6 ULA/link-local, `.local`/`.lan`/`.home`/
  `.internal` or single-label hosts. Public hosts must use HTTPS.
- **Credentials in URLs are rejected**, including `?password=` query parameters.
  Log in inside the web UI instead.
- **No file:// access.** `allowFileAccess`, `allowContentAccess`, and universal
  file URL access are disabled. Mixed content is blocked.
- **External links** open in the system browser; `intent:`, `javascript:` and
  unknown schemes are blocked. `mailto:`/`tel:` are handed to the system.
- **No secrets in logs.** Release builds log nothing; debug builds log only
  WebView console source/line metadata, never URLs or page content.
- **Backup disabled** (`allowBackup=false`) so the WebView auth token is not copied
  to cloud backups.
- **WebView debugging** is enabled only in debug builds.

## Mobile adaptations to the existing web UI

The web UI is already responsive (breakpoints at 767/768 px, mobile sidebar and
overlay classes, 13 px chat text below 767 px). The app therefore does **not**
replace it. It makes five reversible client-side changes:

1. Forces the existing dark theme (`codebuddy-theme` = `{"mode":"dark"}`).
2. Seeds the existing sidebar as collapsed with a 240 px width on first run
   (`codebuddy-sidebar`), only if no preference exists. The toolbar panel button
   toggles the web UI's own sidebar.
3. Scales the UI through the viewport instead of stretching it: the layout
   viewport becomes `width = 760 / scale` with the matching `initial-scale`, so the
   SPA reflows. `1.00x` reproduces the 1.0.2 phone layout (`width=760`, full-width
   chat, drawer sidebar); smaller values widen the layout until the SPA switches to
   its own desktop layout, which is what makes 5-6x smaller sizes usable.
   `minimum-scale=0.05` is required because Chrome otherwise clamps page scale to
   0.25 and the viewport never widens.
4. Exposes the scale as **Settings -> UI size**, 1.00x down to 0.17x (~5.9x smaller
   than 1.00x), stored in `SharedPreferences`, applied to the live page and carried
   across reloads through `localStorage`. The default is 0.60x.
5. Adds CSS that disables Android text autosizing (`text-size-adjust:100%`), removes
   the 880 px chat/composer cap on wide scaled viewports
   (`--conversation-content-max-width:100%`), and - on phone-sized screens (short
   screen side <= 560 px, applied as an `html.cbm-compact` class so it keeps
   matching after the page is zoomed out) - sets a 12 px root font, a 44 px header
   and an ~86 px composer so the login page and composer fit.

No server files are modified. Removing `MobileInjection` restores the stock UI.

## Known limitations

- **On-device testing was not possible in the build environment** (no KVM/emulator
  and no attached device). CI compilation, unit tests, lint, APK inspection and
  signature verification were performed; see `REPORT.md`. Install and smoke-test
  on a real phone/tablet before relying on it.
- Blob/data downloads are not supported by Android WebView; HTTP(S) downloads use
  `DownloadManager`. Workspace file downloads that use `URL.createObjectURL` need
  the desktop UI.
- `window.open`/`target=_blank` popups are not supported. Ordinary links work;
  links to other hosts open in the browser.
- The native connection dot reflects page loads and network availability, not the
  web UI's live WebSocket state. The web UI shows its own streaming status.
- Microphone, camera and geolocation requests from the page are denied.
- Public HTTP is intentionally blocked; self-signed certificates are intentionally
  rejected.
- The compact CSS depends on current CodeBuddy class names. If they change, the
  app still works but loses the compaction.
- Landscape lock is ignored in Android multi-window/freeform and some ChromeOS
  window modes.
- The server must be started with `--host 0.0.0.0`; the app cannot change that.

## Troubleshooting

| Symptom | Fix |
| --- | --- |
| Stuck on "Connecting to CodeBuddy…" | The app now gives up after ~8 s and probes the server. If it still spins, the WebView request is hanging at the TCP level: check that the IP is the computer's, that both devices are on the same network, and that no firewall drops port 7890. |
| Connection refused | Server is bound to 127.0.0.1. Restart with `--host 0.0.0.0`; allow port 7890. |
| "Nothing is listening on the phone itself" | The URL uses `127.0.0.1`. Either run `adb reverse tcp:7890 tcp:7890` on the computer, or use the computer's LAN IP with `--host 0.0.0.0`. |
| Timed out | Wrong IP, different Wi-Fi/VLAN, or AP isolation. Ping the Linux IP from another device. |
| Login required | Expected. Enter the password printed by the CodeBuddy server. |
| Certificate not trusted | Use a valid certificate or a VPN; the app never bypasses TLS. |
| "Use HTTPS for servers outside your local network" | Expected policy. Use HTTPS, a VPN, or the private LAN IP. |
| IP changed after DHCP lease | Toolbar status -> Settings -> update URL -> Save & connect. |
| Keyboard covers composer | The activity uses `adjustResize` plus IME insets. If an OEM ignores insets, report the device. |
| Blob download fails | Use the desktop web UI for that file. |

## Project layout

```
app/src/main/java/com/flux/codebuddy/
  MainActivity.kt          activity, insets, immersive mode, back, status
  WebShell.kt              WebView config, clients, downloads, file chooser
  MobileInjection.kt       scoped CSS + theme/sidebar seeding + sidebar toggle JS
  ServerUrl.kt             pure-JVM URL policy and normalization
  ServerProbe.kt           reachability test
  ServerStore.kt           SharedPreferences persistence
  ui/ServerConfigView.kt   compact two-column server form
  ui/MaxWidthLinearLayout.kt
app/src/main/res/          dark theme, layouts, icons, network security config
app/src/test/              ServerUrl unit tests
.github/workflows/         CI build + release
```
