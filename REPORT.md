# CodeBuddy Mobile 2.0.0 - what changed

Version: 2.0.0 (versionCode 6)
Previous: 1.0.4 (versionCode 5)

## The reported bug

> "if I reopen the app from recents it reloads as a website"

Confirmed. 1.0.x had no way to keep the page alive, and its `onCreate` always
navigated:

```kotlin
// 1.0.4 MainActivity.onCreate - always ran
connectCurrent(saved, result.target, startUrl)   // -> webShell.load(...)
```

Root causes, all fixed:

1. **No WebView retention.** The WebView was inflated from the layout, so any
   activity recreation (rotation, theme change, split-screen) destroyed it and
   rebuilt the SPA. Fixed by creating the WebView in code and handing it to the
   next activity through `onRetainCustomNonConfigurationInstance`.
2. **No state across process death.** Only the URL string was saved. Now
   `WebView.saveState()`/`restoreState()` is used, so history, scroll and the
   form state come back and the document paints from cache instead of the
   network.
3. **No task retention.** Added `android:alwaysRetainTaskState="true"`.
4. **`WebView.pauseTimers()` in `onPause`.** That is a process-wide call: it froze
   the SPA's timers and left the chat socket stale, which made the resume look
   like a reload. Removed.
5. **A network callback could call `reconnect()`** on its own. It now only
   updates status.
6. **`onRenderProcessGone` destroyed the WebView permanently** and left an error
   card. It now builds a fresh WebView and reports the failure once.
7. **Every launch re-fetched the site root.** The app now opens on a native home
   screen and only loads the chat when you ask for it.

## The UI work

1.0.x had a native toolbar but everything else was the website, and the toolbar
   buttons were JavaScript lookups into the page DOM (`aria-label` scans), which
   broke whenever CodeBuddy renamed a class. 2.0.0:

- Native home screen: server card, status dot, Continue / New chat / Test, copy
  URL, open in browser, and a recents list (cap 12, rename on long-press,
  pruned when the server origin changes).
- Native app bar with back-to-home, status, reload and an overflow sheet.
- Native settings sheet: URL, display name, remember, UI size, keep-screen-on,
  test connection, clear login/cache.
- Native dark dialogs for JS alert/confirm/prompt and beforeunload, instead of
  the platform light dialog.
- Recents ordering/format rules are pure JVM (`RecentsCodec`) and unit tested.

## Verified before pushing

Built and tested locally in a throwaway toolchain that has since been deleted
(the repository itself builds only in CI):

| Check | Result |
| --- | --- |
| `assembleDebug` | success |
| `testDebugUnitTest` | 31 tests, 0 failures |
| `lintDebug` | success (`abortOnError = true`) |
| `assembleRelease` | covered by CI (R8 + resource shrinking) |

## Behaviour notes

- Pressing Back in the chat closes the web sidebar first, then walks WebView
  history, then returns to the native home screen. Back on home exits with the
  usual double-press toast.
- Changing the server URL clears recents that point at the old origin.
- Changing only the UI size reloads the current conversation, not the root.
- "Clear login and cached data" wipes cookies, cache, history and WebStorage,
  then reconnects so you can log in again.

## Still not possible

- Blob/data downloads cannot be passed to `DownloadManager`.
- `window.open` popups are unsupported.
- A fully native chat would require CodeBuddy's private streaming protocol; the
  chat surface therefore remains the official web UI.
