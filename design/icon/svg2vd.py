"""Convert the delivered flat SVGs to Android VectorDrawable XML.

Only handles what these files actually contain: <path fill="#RRGGBB" d="..."/>.
That is deliberate — a general SVG converter would be far more code and would
silently accept constructs (filters, gradients, masks) that the brief ruled out
precisely because VectorDrawable cannot express them.
"""
import re
import sys
from pathlib import Path

PATH_RE = re.compile(r'<path\s+fill="(#[0-9A-Fa-f]{3,8})"\s+d="([^"]+)"', re.S)
SVG_RE = re.compile(r'<svg[^>]*viewBox="([\d.\s-]+)"', re.S)

BANNED = ("<filter", "<mask", "<clipPath", "<text", "linearGradient",
          "radialGradient", "<image", "<pattern", "filter=", "mask=")


def convert(svg_path: Path, out_path: Path, width_dp: int, height_dp: int) -> int:
    src = svg_path.read_text(encoding="utf-8")

    for token in BANNED:
        if token in src:
            raise SystemExit(f"{svg_path.name}: contains {token!r}, "
                             "which VectorDrawable cannot represent")

    vb = SVG_RE.search(src)
    if not vb:
        raise SystemExit(f"{svg_path.name}: no viewBox")
    _, _, vw, vh = vb.group(1).split()

    paths = PATH_RE.findall(src)
    if not paths:
        raise SystemExit(f"{svg_path.name}: no <path fill= d=> found")

    body = "\n".join(
        f'    <path\n'
        f'        android:fillColor="{fill}"\n'
        f'        android:pathData="{d.strip()}" />'
        for fill, d in paths
    )

    xml = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        f'<!-- Generated from {svg_path.name}. Do not hand-edit; regenerate with\n'
        '     scratchpad/icons/svg2vd.py if the source artwork changes. -->\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        f'    android:width="{width_dp}dp"\n'
        f'    android:height="{height_dp}dp"\n'
        f'    android:viewportWidth="{vw}"\n'
        f'    android:viewportHeight="{vh}">\n'
        f'{body}\n'
        '</vector>\n'
    )
    out_path.write_text(xml, encoding="utf-8")
    return len(paths)


if __name__ == "__main__":
    src_dir = Path(sys.argv[1])
    res = Path(sys.argv[2])

    jobs = [
        ("orbit-foreground.svg", res / "drawable" / "ic_launcher_foreground.xml", 108, 108),
        ("orbit-banner.svg", res / "drawable" / "tv_banner.xml", 320, 180),
        ("orbit-icon.svg", res / "mipmap" / "ic_launcher.xml", 48, 48),
    ]
    for name, out, w, h in jobs:
        n = convert(src_dir / name, out, w, h)
        print(f"{name:28s} -> {out.name:28s} ({n} paths)")
