# Icon prompt for Claude Design

The current mark — a thin ring with a dot on it — is weak: at the size an
Android TV home row actually renders it, the ring reads as a smudge and the dot
disappears. Here is a brief to replace it.

**Paste the block below into Claude Design.** Everything after it explains how to
get the result back into the app.

---

## The prompt

> Design an **app icon and a TV banner** for **Orbit**, a web browser built for
> Android TV. It is driven entirely by a remote control, it is fast and
> privacy-focused, and its personality is calm precision — a considered piece of
> system software, not a consumer entertainment app.
>
> **Deliver two SVGs.**
>
> **1. App icon — 108 × 108 SVG.**
> This becomes an Android adaptive icon. That means:
> - The launcher masks it to a circle, squircle or rounded square, and *you do
>   not get to choose which*. Everything essential must sit inside the centre
>   **66 × 66** area. The outer ring of ~21px on every side **will be cropped**
>   on some launchers.
> - Deliver it as two layers, clearly separated in the file: a **background**
>   (a single flat colour filling the whole 108 × 108, edge to edge) and a
>   **foreground** (the mark itself, transparent elsewhere).
> - **No text, no letterforms, no wordmark.** A one-letter "O" is specifically
>   not wanted — it is what every browser without an idea does.
> - It must still read as a distinct silhouette at **48 px** and from three
>   metres away on a television. Squint at it: if the shapes merge, it fails.
>
> **2. TV banner — 320 × 180 SVG.**
> This is the whole tile on the Android TV home screen — it is not a logo on a
> card, it *is* the card. It **should** include the word "Orbit" as real
> lettering. Keep all content inside a 16px margin. Assume it sits on a dark
> home screen and is often shown next to loud, colourful app banners, so it
> should win on clarity rather than on volume.
>
> **Hard technical constraints — the output is converted to Android
> VectorDrawable, so anything below breaks the build:**
> - Flat filled `<path>` shapes only. No embedded raster images, no `<filter>`,
>   no blur, no drop shadows, no masks, no clip paths, no patterns, no blend
>   modes, no `<text>` elements (convert any lettering to outlined paths).
> - Strokes are acceptable but must be simple, uniform-width and un-dashed;
>   outlined paths are preferred.
> - At most one linear gradient, and only if it genuinely earns its place.
> - Keep it to roughly **8 paths or fewer**. Complexity is the enemy here.
>
> **Direction — explore several, do not settle on the first:**
> - A **planet and its orbital path** rendered as a bold, weighted mark: a solid
>   disc with a thick inclined ellipse crossing behind and in front of it. The
>   idea is right; the current execution is too thin and too timid. Make the
>   ring heavy enough to survive being scaled down.
> - An **eye / aperture** built from orbital arcs — browsing as looking.
> - A **compass or reticle**, leaning on the navigation meaning of "orbit".
> - Something geometric and abstract that simply reads as *precise* and
>   *engineered* at a glance.
>
> **Colour:** the app's palette is a near-black canvas `#0A0C10` and a single
> cool blue accent `#6AA6FF`, with off-white `#E8ECF2` for the mark itself.
> Staying inside that palette is preferred, but propose a better one if you have
> a genuinely stronger idea. **No red** — it is the colour of the "recording"
> and "error" states everywhere else on a TV.
>
> **Show me 3–4 distinct concepts**, each rendered at 108 px, at 48 px, and
> inside a circular mask, so I can judge how each survives cropping and scaling
> before picking one.

---

## Getting the result back into the app

Send me the **SVG source**, not a screenshot or a PNG — SVG converts cleanly to
a VectorDrawable, and a raster icon visibly pixelates on this hardware.

Then I will:

1. Convert the foreground to `app/src/main/res/drawable/ic_launcher_foreground.xml`
   and the background colour into `mipmap-anydpi-v26/ic_launcher.xml`.
2. Convert the banner to `app/src/main/res/drawable/tv_banner.xml` (this is what
   `android:banner` points at, and it is what you actually see on the TV home
   row).
3. Regenerate the pre-API-26 fallback at `mipmap/ic_launcher.xml`.
4. Push, let CI build, install, and screenshot it **on the TV home screen** so
   you can judge it at real size rather than in a design tool.

If Claude Design will only give you a PNG, send it anyway and say so — a flat,
high-contrast mark can be re-drawn as paths by hand, it just takes a pass.

### Checking it before you commit to it

The two failure modes for this specific device:

- **Too thin.** The Skyworth's home row draws banners fairly small. Hairlines
  and 1–2px details vanish. Weight everything up more than feels right on a
  monitor.
- **Too busy inside the mask.** Adaptive icons get cropped differently by every
  launcher. If a concept only works uncropped, it is not the concept.
