#!/usr/bin/env python3
"""
Generate PhotoStat app icons.

Produces:
  icon.png  — 1024x1024 master PNG (committed; used for macOS iconutil and JavaFX window icon)
  icon.ico  — multi-size Windows icon (16–256 px, committed; passed to jpackage on Windows)

Design: 6-blade camera aperture on a dark-navy rounded-square background, with a teal
lens-centre circle.  The blades are drawn as overlapping "swept" parallelograms — the
inner edge of each blade is rotated clockwise relative to its outer edge, which creates
the characteristic camera-iris silhouette.
"""

import math
from PIL import Image, ImageDraw

# ── Palette ──────────────────────────────────────────────────────────────────
NAVY = (27,  58,  92,  255)   # background
WHITE = (255, 255, 255, 255)  # blades / outer ring
TEAL  = (56,  195, 178, 255)  # lens-centre highlight


def polar(cx, cy, r, deg):
    """Return the (x, y) point at radius r and angle deg from (cx, cy)."""
    rad = math.radians(deg)
    return (cx + r * math.cos(rad), cy + r * math.sin(rad))


def create_icon(size: int) -> Image.Image:
    """Render the icon at *size* × *size* pixels using 8× supersampling."""
    scale = 8
    s     = size * scale
    cx = cy = s / 2

    img  = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    # ── Background: rounded square ────────────────────────────────────────────
    radius = s // 5
    draw.rounded_rectangle([0, 0, s - 1, s - 1], radius=radius, fill=NAVY)

    # ── Thin white outer ring ─────────────────────────────────────────────────
    r_ring_outer = s * 0.415
    r_ring_inner = s * 0.385
    draw.ellipse(
        [cx - r_ring_outer, cy - r_ring_outer, cx + r_ring_outer, cy + r_ring_outer],
        fill=WHITE,
    )
    draw.ellipse(
        [cx - r_ring_inner, cy - r_ring_inner, cx + r_ring_inner, cy + r_ring_inner],
        fill=NAVY,
    )

    # ── 6 aperture blades ─────────────────────────────────────────────────────
    # Each blade is a quadrilateral:
    #   outer edge  — arc from (base − outer_half) to (base + outer_half) at r_outer
    #   inner edge  — arc from (base + offset − inner_half) to (base + offset + inner_half) at r_inner
    # The clockwise inner_offset creates the "swept" iris-blade look.
    r_outer    = s * 0.380
    r_inner    = s * 0.175
    n_blades   = 6
    outer_half = 26   # degrees — blade is 52° wide at outer edge (gap ≈ 8° at outer)
    inner_half = 23   # degrees — blade is 46° wide at inner edge
    inner_off  = 24   # degrees — inner edge rotated clockwise → swept blade silhouette

    for i in range(n_blades):
        base = i * (360 // n_blades)   # 0, 60, 120, 180, 240, 300

        p1 = polar(cx, cy, r_outer, base - outer_half)
        p2 = polar(cx, cy, r_outer, base + outer_half)
        p3 = polar(cx, cy, r_inner, base + inner_off + inner_half)
        p4 = polar(cx, cy, r_inner, base + inner_off - inner_half)

        draw.polygon([p1, p2, p3, p4], fill=WHITE)

    # ── Teal lens-centre circle ───────────────────────────────────────────────
    r_centre = s * 0.110
    draw.ellipse(
        [cx - r_centre, cy - r_centre, cx + r_centre, cy + r_centre],
        fill=TEAL,
    )

    # ── Downscale with LANCZOS for clean anti-aliased edges ───────────────────
    return img.resize((size, size), Image.LANCZOS)


def main():
    import os, sys
    out_dir = os.path.dirname(os.path.abspath(__file__))

    # Master PNG
    master = create_icon(1024)
    png_path = os.path.join(out_dir, "icon.png")
    master.save(png_path)
    print(f"Saved  {png_path}")

    # Windows ICO (multi-size: 16 → 256)
    ico_sizes = [256, 128, 64, 48, 32, 16]
    icons = [create_icon(s) for s in ico_sizes]
    ico_path = os.path.join(out_dir, "icon.ico")
    icons[0].save(
        ico_path,
        format="ICO",
        append_images=icons[1:],
        sizes=[(s, s) for s in ico_sizes],
    )
    print(f"Saved  {ico_path}")


if __name__ == "__main__":
    main()
