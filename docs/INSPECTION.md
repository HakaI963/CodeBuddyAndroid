# CodeBuddy web UI inspection

Environment inspected on 2026-10-24:

- CodeBuddy Code v2.174.0, served by `codebuddy --serve --port 7890`
  (bound to `127.0.0.1`, password auth enabled).
- Development URL:
  `http://127.0.0.1:7890/?hostId=local&hostEpoch=0&folder=%2Fhome%2Fflux#/chat`
- Linux user `flux`, workspace `/home/flux`.
- Frontend assets under the installed npm package
  `@tencent-ai/codebuddy-code/dist/web-ui`.

The app was built without modifying the server or its frontend files.

## Responsiveness

The stock web UI is already responsive:

- `index.html` has `width=device-width`, `viewport-fit=cover`,
  `interactive-widget=resizes-content`, and PWA/standalone meta tags.
- CSS breakpoints at 767/768 px and 640 px; a mobile sidebar overlay
  (`sidebar-mobile`, `sessions-panel-mobile-close`) and 13 px chat text below
  767 px.
- The chat header and composer have dedicated classes (`app-status-bar`,
  `composer-card`, `composer-stack`, `composer-toolbar`, `composer-chip`).
- The login page has dedicated classes (`login-page`, `login-content`,
  `login-title`, `login-hint`, `login-footer`).

Measured at 844x390 landscape (Chrome, device scale 1):

| Element | Default | After scoped CSS |
| --- | --- | --- |
| Web header | 56 px | 40 px |
| Composer | 134 px | 108 px |
| Composer toolbar | 44 px (wrapped) | 32 px |
| Session sidebar | 396 px (expanded) | 0 (collapsed) / 240 (seeded width) |

At 640x360 and 568x320 the same compact CSS applies. At 1024x600 the media query
does not apply, so tablets keep the stock spacing.

The login page is taller than a 360 px-high viewport (`body` is
`overflow:hidden`; content measured 546 px), so it was compacted and made
scrollable for short landscape screens.

## URL parameters and authentication

- Loading the bare origin `http://127.0.0.1:7890/` after login redirects to
  `?hostId=local&hostEpoch=0&folder=%2Fhome%2Fflux#/chat`; the server/web UI
  supplies the defaults and restores the last session.
- `?folder=...` alone is also completed with `hostId`/`hostEpoch`.
- The app therefore accepts a bare server URL, and also accepts a full URL with
  query/fragment so `hostId`, `hostEpoch` and `folder` can be preserved when the
  user needs a specific host/folder.
- Auth is a password form. The token is kept in WebView localStorage
  (`codebuddy.auth.token`); the app never reads it and disables backup.
- The web UI stores sidebar state in `codebuddy-sidebar` and theme in
  `codebuddy-theme`. Theme values are JSON objects, e.g. `{"mode":"dark"}`.

## Existing controls used by the app

- Sidebar toggle is a button with `aria-label="Expand sidebar"` /
  `"Collapse sidebar"` (Chinese labels also handled).
- On narrow widths the open drawer exposes `.sessions-panel-mobile-close`.
- The login page is identified by `.login-btn`.
- Copy actions try `navigator.clipboard.writeText` and fall back to a hidden
  textarea plus `document.execCommand('copy')`, so copying works on plain HTTP
  LAN origins where the async Clipboard API is unavailable.

## Decisions

1. Keep the existing chat frontend; build the *shell* natively. The home screen,
   app bar, settings and recents list are Android views and do not read the DOM.
2. Keep the existing composer and session list; do not reimplement chat or
   streaming.
3. Apply only a small, height-scoped CSS layer for phones; leave tablets alone.
4. Seed the existing sidebar as collapsed instead of adding a native session
   list, so session state stays in one place.
5. Add a native home screen and app bar; the sidebar toggle is the only action
   that still reaches into the page, because the drawer belongs to the SPA.
6. Use a document-start script (`WebViewCompat.addDocumentStartJavaScript`) for
   theme/CSS injection, with a per-page fallback. No JavaScript bridge.
7. Do not modify the CodeBuddy installation. All changes are in the Android
   client and are reversible by removing `MobileInjection`.

## Features intentionally left to the web UI

Chat, streaming, Markdown, syntax highlighting, copy, scrolling, file tree,
terminal/tool panes, authentication, session history and workspace navigation
are all rendered and handled by the existing web app. The Android layer only
provides the window, input/insets, downloads/file picker, external-link routing,
and server configuration.

## Limitations discovered

- Blob/data downloads use `URL.createObjectURL` + anchor download, which Android
  WebView cannot hand to `DownloadManager`; HTTP(S) downloads work.
- Some actions use `window.open`; popups are not supported by the shell.
- The live WebSocket state is internal to the web UI; the native dot reflects
  page load and network state only.
