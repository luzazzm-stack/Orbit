# Orbit

A web browser for Android TV that is actually driveable from the remote.

Built for a **Skyworth SWTV-20AE** (Android 10 / API 29, `armeabi-v7a` only,
1.7 GB RAM, 1920×1080 @ 320 dpi, `leanback_only`, full GMS).

---

## Why the system WebView, and not GeckoView or a Chromium build

Measured on the target device before any code was written:

```
ro.build.version.release   10          ro.product.cpu.abilist  armeabi-v7a,armeabi
ro.build.version.sdk       29          MemTotal                1.7 GB
ro.build.characteristics   tv          leanback_only           yes
WebView provider           com.google.android.webview 150.0.7871.124
```

| Option | Verdict |
|---|---|
| **System WebView** ✅ | The device already runs **Chromium 150**, kept current by the Play Store. ~3 MB APK, no native libraries, so the 32-bit-only ABI stops being a constraint at all. |
| GeckoView | Adds ~70–90 MB per ABI and needs materially more RAM. On a 1.7 GB box that already exhausts its GPU heap under load, this is the wrong trade. TV Bro — the reference Android TV browser — supports both engines and recommends WebView on weak devices for the same reason. |
| Chromium from source | ~100 GB of disk and hours of build time to end up with *the same renderer that is already installed*, on a laptop with no Android SDK and integrated graphics. No upside. |

The engine was never the interesting problem. **The input layer is.**

---

## The input layer

WebView's built-in D-pad support walks the DOM in tab order. On any real
two-dimensional page that produces nonsense — pressing RIGHT lands on a footer
link because it happens to be the next node. That is why the TV already had
`io.github.virresh.matvt`, an accessibility-service virtual mouse, installed as
a workaround. Orbit replaces both.

### Link mode (default)

`assets/spatialnav.js` is injected into every page and navigates **geometrically**:

- candidates are scored as `primaryDistance + perpendicularDistance × 6`, so
  RIGHT strongly prefers the next item *in the same row* over something closer
  but two rows down
- off-screen elements beyond ±1.5 viewports are discarded, keeping key response
  instant on long pages
- when nothing lies in the pressed direction, the nearest scrollable ancestor
  scrolls (not just the document — sidebars and feeds scroll correctly), then
  focus re-anchors onto whatever scrolled into view
- activation fires a full pointer sequence *and* a real `click()`, so both
  pointer-event listeners and plain anchors work
- a `MutationObserver` drops focus when an SPA detaches the focused node

### Pointer mode

Spatial navigation can only reach what the DOM exposes — cross-origin iframes,
canvas apps and map widgets are invisible to it. Pointer mode is the universal
fallback: a virtual cursor that synthesises **real `SOURCE_MOUSE` events**
(`ACTION_HOVER_MOVE`, `ACTION_DOWN/UP` with `BUTTON_PRIMARY`, `ACTION_SCROLL`
with `AXIS_VSCROLL`). Chromium therefore produces genuine hover states and wheel
scrolling, which is what the desktop layouts Orbit requests actually expect.

Speed ramps quadratically from 320 px/s to 2400 px/s over 750 ms, so a tap nudges
a few pixels and a held key crosses the screen. At the top and bottom edges the
pointer stops and the page scrolls instead.

The remote also exposes a native mouse (`TV BLE Remote Mouse`); those events pass
straight through untouched.

### Remote mapping

| Key | Action |
|---|---|
| D-pad | Move between links · move the pointer in pointer mode |
| **OK** | Activate the focused element · click in pointer mode |
| **Hold OK** (600 ms) | Toggle pointer mode |
| **BACK** | Exit pointer mode → close panel → hide toolbar → page back → close tab → exit |
| **MENU / INFO** | Toggle the toolbar |
| **▲ at top of page** | Opens the toolbar |
| **▼ from the toolbar** | Returns to the page |
| **SEARCH** | Focus the address bar |
| Play/Pause, FF, RW | Control the first `<video>` on the page (±15 s) |
| Ch+/Ch−, PgUp/PgDn | Page scroll |

---

## Memory model

Each live WebView is a Chromium renderer process. With 1.7 GB total, `TabManager`
keeps at most **3 live** and freezes the least recently used beyond that:
navigation state is saved into a `Bundle` and the WebView destroyed. Waking a tab
restores it. `onLowMemory` sheds every background tab before the system kills the
process. Tabs are capped at 8.

---

## Features

- Tabs, bookmarks, history (JSON-persisted, atomic writes, history capped at 400)
- Omnibox that takes URLs or searches; Google / DuckDuckGo / Bing / YouTube
- Voice search via `RecognizerIntent`
- Host-suffix ad and tracker blocking — on this hardware it is a *performance*
  feature, not just a privacy one
- Desktop user-agent by default (TV screens fit desktop layouts; the claimed
  Chrome version is derived from the real engine so sites never serve 2019
  fallback markup)
- Fullscreen `<video>` via `onShowCustomView`
- Adjustable text zoom, pointer speed, smooth scrolling
- Local start page with shortcut tiles (`assets/home.html`)
- Registered for `http`/`https` `VIEW` intents, so other TV apps can hand it links

---

## Building

There is no local Android SDK on the dev machine, so builds run in CI:

```
git push            # GitHub Actions builds a signed release APK
gh run watch        # …and publishes it to a GitHub Release
```

The release keystore is committed on purpose: a stable signature means
`adb install -r` upgrades in place and keeps app data. It is a self-signed key
for a personal sideload, not a distribution credential.

### Install on the TV

```bash
adb connect 192.168.1.67:5555      # confirm the IP with: adb mdns services
adb install -r Orbit-v0.1.0.apk
```

The TV's DHCP lease moves — `adb mdns services` reports the current address for
serial `0000A877E5EF6331`.

---

## Project layout

```
app/src/main/
  assets/
    spatialnav.js      geometric D-pad navigation injected into every page
    home.html          start page (self-contained, no network)
    blocklist.txt      ad/tracker host suffixes
  java/app/orbit/
    core/    WebViewFactory, TabManager, Tab, AdBlocker, UrlUtils
    input/   SpatialNav, CursorController, CursorOverlay
    ui/      BrowserActivity, PanelController, PanelAdapter
    data/    Prefs, Store
```

## Known gaps

- Cross-origin iframes cannot be spatially navigated (a browser-security limit,
  not a bug) — pointer mode covers them.
- No DRM playback: Netflix and friends need a certified app, not a WebView.
- Focus highlight relies on injecting CSS, so pages with an extremely strict
  Content-Security-Policy may show no ring. Pointer mode is unaffected.
