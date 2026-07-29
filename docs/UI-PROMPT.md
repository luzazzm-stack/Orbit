# UI prompt for Claude Design

Orbit already ships a working, self-contained start page at
`app/src/main/assets/home.html`. This document is for **iterating** on it — paste
the prompt below into Claude Design, then drop the result back over that file.

Nothing else needs to change: the page is plain HTML/CSS/JS with no build step,
no framework and no network dependencies.

---

## The prompt

> Design a **start page for a television web browser**. It is displayed on a
> 1920×1080 TV, viewed from about 2.5 metres away, and driven **entirely by a
> D-pad remote control** — there is no mouse, no touchscreen and no keyboard.
>
> **Deliver a single self-contained HTML file.** All CSS and JS inline. No
> external fonts, stylesheets, scripts, images or icon libraries of any kind —
> the page is loaded from local app assets with no network access. Use inline
> SVG for any iconography and system fonts only (`"Roboto", "Noto Sans",
> system-ui, sans-serif`).
>
> **Content:**
> - A brand lockup: the wordmark "ORBIT" beside a mark of a small filled circle
>   riding an inclined elliptical ring (a planet on an orbital path), inline SVG.
> - A live clock, 24-hour, top right.
> - One large search field, `id="q"`, placeholder "Search or enter address".
> - A grid of up to 8 shortcut tiles. Each tile is an `<a href>` containing a
>   rounded square badge showing the site's first letter over a colour derived
>   deterministically from its hostname, then the site name and the hostname
>   beneath it in a muted tone.
> - A footer strip of remote-control hints rendered as `<kbd>` chips:
>   `OK` open · `Hold OK` pointer mode · `MENU` toolbar · `BACK` back ·
>   `▲` at top of page opens the toolbar.
>
> **Hard constraints — these are what make it work on a TV:**
> 1. **Overscan.** Many TVs crop the outer ~5% of the panel. Nothing meaningful
>    may sit inside a `5vh 5vw` inset.
> 2. **Focus must be unmissable.** Every focusable element must have a dramatic
>    focused state — the app injects a class named `__orbit-focus` onto whatever
>    the D-pad has selected, so style **both** `.tile.__orbit-focus` and
>    `.tile:focus` identically. Combine a colour shift, a border colour change
>    and a `transform: scale(~1.045)`. A subtle state is a bug: at this viewing
>    distance the user genuinely cannot tell what is selected.
> 3. **Animate transform and opacity only.** The target device has no discrete
>    GPU (a 1.7 GB Android TV box). Anything that triggers layout or paint per
>    frame visibly stutters. Keep transitions ≤ 200 ms.
> 4. **Grid geometry must suit a D-pad.** A clean 4-column grid; every tile must
>    be reachable by pure up/down/left/right movement. No masonry, no offsets,
>    no overlapping cards.
> 5. **Type scale for distance.** Body text ≥ 17 px, tile titles ≥ 18 px, the
>    search field ≥ 22 px and at least 80 px tall. Nothing below 13 px.
>
> **Visual direction:** calm, dark, editorial. Near-black canvas (not pure
> black — pure black next to bright web content haloes on an LCD panel), a
> single cool blue accent, generous whitespace, restrained. Think a considered
> piece of system software, not a gaming launcher. **No red.**
>
> **Design tokens to keep** so it drops straight into the app:
> ```
> --canvas:#0A0C10   --surface:#151A21   --surface-2:#1C222B
> --line:#2A313C     --text:#E8ECF2      --muted:#9AA4B2
> --accent:#6AA6FF   --radius:18px
> ```
>
> **Required JS contract** — the app calls this after the page loads:
> ```js
> window.orbitInit({ searchUrl: "https://…/search?q=", sites: [{title, url}, …] })
> ```
> Render the tiles from `sites`, use `searchUrl` when the search field is
> submitted with Enter, and ship sensible hard-coded defaults so the page still
> looks complete when opened standalone.

---

## Dropping the result back in

1. Save the generated file over `app/src/main/assets/home.html`.
2. Confirm it still defines `window.orbitInit` and an input with `id="q"`.
3. Commit and push — CI rebuilds the APK.
4. `adb install -r` the new build.

To preview a candidate on the TV without rebuilding, push it to the device and
open it directly:

```bash
adb push home.html /sdcard/Download/home.html
# then in Orbit's address bar: file:///sdcard/Download/home.html
```

(That requires temporarily setting `allowFileAccess = true` in
`core/WebViewFactory.kt`; it is off in shipping builds by design.)

---

## If you also want to redesign the native chrome

The toolbar, side panel and list rows are **Android XML layouts**, not HTML, so
Claude Design output cannot be pasted in directly — but a mockup is still useful
to work from. Ask for a static mockup of:

- a **top toolbar**: back, forward, reload, home, a pill-shaped address field,
  voice, pointer toggle, bookmark, a tab counter in a rounded square, and an
  overflow button — all on a dark gradient scrim over page content;
- a **right-hand side panel**, 440 dp wide, with four section chips
  (Tabs / Bookmarks / History / Settings) above a list of 64 dp rows, each row
  being an icon, a title, a muted subtitle and an optional trailing action.

The focus convention to specify: **a focused control inverts** — solid white
plate, near-black glyph and label. That inversion is the single reason the UI
reads correctly from across a room, and it is already implemented in
`res/color/tint_on_focusable.xml` and `res/drawable/bg_focusable.xml`.
