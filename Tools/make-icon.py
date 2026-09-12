#!/usr/bin/env python3
"""
Regenerates the app icon from the palette. Pure standard library — no Pillow.

The mark is the week-bar motif from the Today screen: four capsules of uneven
height, leaning forward. Run from anywhere:

    python3 ~/Stride/Tools/make-icon.py
"""

import math
import os
import struct
import sys
import zlib

SIZE = 1024
LIME = (0xDD, 0xF8, 0x5C)
LIME_TOP = (0xE6, 0xFB, 0x72)
LIME_BOTTOM = (0xD2, 0xF2, 0x46)
DEEP_GREEN = (0x14, 0x40, 0x1F)
NEAR_WHITE = (0xF2, 0xF2, 0xF2)

# Bar geometry, in a 1024 square before centring: (x, height)
BARS = [(330, 300), (446, 470), (562, 240), (678, 390)]
BAR_WIDTH = 76.0
BASELINE = 742.0
LEAN = 0.18
TARGET_SPAN = 560.0


def blank(background):
    """A fresh canvas. `background` of None leaves it transparent."""
    buffer = bytearray(SIZE * SIZE * 4)
    if background is None:
        return buffer
    top, bottom = background
    for y in range(SIZE):
        t = y / (SIZE - 1)
        r = int(top[0] + (bottom[0] - top[0]) * t)
        g = int(top[1] + (bottom[1] - top[1]) * t)
        b = int(top[2] + (bottom[2] - top[2]) * t)
        row = y * SIZE * 4
        for x in range(SIZE):
            i = row + x * 4
            buffer[i], buffer[i + 1], buffer[i + 2], buffer[i + 3] = r, g, b, 255
    return buffer


def blend(buffer, index, colour, alpha):
    if alpha <= 0:
        return
    existing = buffer[index + 3]
    if existing == 0:
        buffer[index], buffer[index + 1], buffer[index + 2] = colour
        buffer[index + 3] = 255 if alpha >= 1 else int(255 * alpha)
        return
    if alpha >= 1:
        buffer[index], buffer[index + 1], buffer[index + 2] = colour
        buffer[index + 3] = 255
        return
    for k in range(3):
        buffer[index + k] = int(buffer[index + k] * (1 - alpha) + colour[k] * alpha)
    buffer[index + 3] = max(existing, int(255 * alpha))


def capsule(buffer, start, end, width, colour):
    """An anti-aliased round-capped bar, drawn by distance to the centre line."""
    half = width / 2.0
    ax, ay = start
    bx, by = end
    min_x = max(0, int(min(ax, bx) - half - 2))
    max_x = min(SIZE - 1, int(max(ax, bx) + half + 2))
    min_y = max(0, int(min(ay, by) - half - 2))
    max_y = min(SIZE - 1, int(max(ay, by) + half + 2))
    dx, dy = bx - ax, by - ay
    length_squared = dx * dx + dy * dy

    for y in range(min_y, max_y + 1):
        for x in range(min_x, max_x + 1):
            if length_squared < 1e-9:
                distance = math.hypot(x - ax, y - ay)
            else:
                t = max(0.0, min(1.0, ((x - ax) * dx + (y - ay) * dy) / length_squared))
                distance = math.hypot(x - (ax + t * dx), y - (ay + t * dy))
            coverage = half + 0.5 - distance
            if coverage <= 0:
                continue
            blend(buffer, (y * SIZE + x) * 4, colour, min(1.0, coverage))


def bar_geometry():
    """Bars scaled and centred so every variant sits identically."""
    points = []
    for x, height in BARS:
        points.append(((x, BASELINE), (x + height * LEAN, BASELINE - height)))

    half = BAR_WIDTH / 2
    xs = [p[0] for pair in points for p in pair]
    ys = [p[1] for pair in points for p in pair]
    min_x, max_x = min(xs) - half, max(xs) + half
    min_y, max_y = min(ys) - half, max(ys) + half

    scale = TARGET_SPAN / max(max_x - min_x, max_y - min_y)
    centre_x, centre_y = (min_x + max_x) / 2, (min_y + max_y) / 2

    def place(point):
        return ((point[0] - centre_x) * scale + SIZE / 2,
                (point[1] - centre_y) * scale + SIZE / 2)

    return [(place(a), place(b)) for a, b in points], BAR_WIDTH * scale


def draw_mark(buffer, colour):
    bars, width = bar_geometry()
    for start, end in bars:
        capsule(buffer, start, end, width, colour)


def write_png(path, buffer):
    raw = bytearray()
    for y in range(SIZE):
        raw.append(0)
        raw += buffer[y * SIZE * 4:(y + 1) * SIZE * 4]

    def chunk(tag, payload):
        return (struct.pack(">I", len(payload)) + tag + payload
                + struct.pack(">I", zlib.crc32(tag + payload) & 0xffffffff))

    data = (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", SIZE, SIZE, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
            + chunk(b"IEND", b""))
    with open(path, "wb") as handle:
        handle.write(data)


def main():
    default = os.path.expanduser("~/Stride/Stride/Assets.xcassets/AppIcon.appiconset")
    out = sys.argv[1] if len(sys.argv) > 1 else default
    os.makedirs(out, exist_ok=True)

    # Light: the mark sits on lime, matching every primary button in the app.
    light = blank((LIME_TOP, LIME_BOTTOM))
    draw_mark(light, DEEP_GREEN)
    write_png(os.path.join(out, "icon-light.png"), light)

    # Dark: transparent, so iOS supplies its own dark ground behind lime bars.
    dark = blank(None)
    draw_mark(dark, LIME)
    write_png(os.path.join(out, "icon-dark.png"), dark)

    # Tinted: greyscale on transparent; iOS maps luminance onto the user's tint.
    tinted = blank(None)
    draw_mark(tinted, NEAR_WHITE)
    write_png(os.path.join(out, "icon-tinted.png"), tinted)

    print("Wrote icon-light.png, icon-dark.png and icon-tinted.png to " + out)


if __name__ == "__main__":
    main()
