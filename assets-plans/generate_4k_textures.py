"""Generate the five literal 4096x4096 detail maps used by the app.

Run with Blender's bundled Python (it ships numpy):
  /Applications/Blender.app/Contents/MacOS/Blender -b --python assets-plans/generate_4k_textures.py

Each map is RGB while the shader reads:
  R = height / bump
  G = roughness
  B = ambient-occlusion / edge wear
The maps are material-neutral; the table's theme color comes from the GLB.
"""

import os
import struct
import zlib

import numpy as np

SIZE = 4096
OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "textures")
os.makedirs(OUT, exist_ok=True)

y, x = np.mgrid[0:SIZE, 0:SIZE].astype(np.float32) / SIZE


def hash21(px, py):
    p = np.sin(px * 127.1 + py * 311.7) * 43758.5453
    return p - np.floor(p)


def value_noise(px, py):
    ix = np.floor(px)
    iy = np.floor(py)
    fx = px - ix
    fy = py - iy
    fx = fx * fx * (3.0 - 2.0 * fx)
    fy = fy * fy * (3.0 - 2.0 * fy)
    a = hash21(ix, iy)
    b = hash21(ix + 1.0, iy)
    c = hash21(ix, iy + 1.0)
    d = hash21(ix + 1.0, iy + 1.0)
    return (a + (b - a) * fx) + ((c + (d - c) * fx) - (a + (b - a) * fx)) * fy


def fbm(px, py, octaves=5):
    total = np.zeros_like(px)
    amplitude = 0.5
    frequency = 1.0
    for _ in range(octaves):
        total += amplitude * value_noise(px * frequency, py * frequency)
        frequency *= 2.03
        amplitude *= 0.5
    return total


def png(path, rgb):
    height, width, _ = rgb.shape
    raw = b"".join(b"\x00" + rgb[row].tobytes() for row in range(height))

    def chunk(tag, data):
        body = tag + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    header = b"\x89PNG\r\n\x1a\n"
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    with open(path, "wb") as handle:
        handle.write(header)
        handle.write(chunk(b"IHDR", ihdr))
        handle.write(chunk(b"IDAT", zlib.compress(raw, 9)))
        handle.write(chunk(b"IEND", b""))
    print(path, os.path.getsize(path) // 1024, "KB")


def build(style):
    xx = x * 12.0
    yy = y * 12.0
    grain = 0.5 + 0.5 * np.sin(
        (x * 180.0 + fbm(xx, yy, 5) * 14.0) * np.pi * 2.0
    )
    figure = fbm(xx * 0.45, yy * 0.18, 5)
    weave = 0.5 + 0.5 * np.sin(x * np.pi * 2.0 * 420.0) * np.sin(y * np.pi * 2.0 * 420.0)
    brushed = fbm(x * 700.0, y * 7.0, 4)
    pores = fbm(x * 620.0, y * 620.0, 3)
    macro = fbm(x * 2.2, y * 2.2, 5)
    creases = 1.0 - np.abs(fbm(x * 30.0, y * 30.0, 5) * 2.0 - 1.0)
    scratches = np.zeros_like(x)
    rng = np.random.default_rng(7)
    for _ in range(90):
        cx, cy = rng.random(), rng.random()
        length = 0.02 + rng.random() * 0.22
        angle = rng.random() * np.pi
        dx, dy = np.cos(angle), np.sin(angle)
        distance = np.abs((x - cx) * dy - (y - cy) * dx)
        along = (x - cx) * dx + (y - cy) * dy
        line = (distance < 0.00055) & (np.abs(along) < length * 0.5)
        scratches = np.maximum(scratches, line.astype(np.float32))
    chalk = np.clip((fbm(x * 9.0, y * 9.0, 4) - 0.52) * 5.0, 0.0, 1.0)
    dust = np.clip((macro - 0.5) * 2.0, 0.0, 1.0)

    if style == "scan":
        height = grain * 0.42 + weave * 0.18 + pores * 0.22 + figure * 0.18
        rough = 0.48 + 0.30 * (1.0 - grain) + 0.12 * pores
        ao = 0.72 + 0.28 * (grain * 0.5 + 0.5)
    elif style == "wear":
        height = grain * 0.30 + weave * 0.16 + creases * 0.18 + scratches * 0.20 + figure * 0.16
        rough = 0.40 + 0.30 * scratches + 0.18 * chalk + 0.12 * creases
        ao = 0.62 + 0.30 * (1.0 - creases) + 0.08 * dust
    elif style == "clean":
        height = grain * 0.30 + weave * 0.26 + figure * 0.14
        rough = 0.34 + 0.10 * (1.0 - grain)
        ao = 0.86 + 0.14 * (weave * 0.5 + 0.5)
    elif style == "procedural":
        height = grain * 0.28 + weave * 0.16 + (macro - 0.5) * 0.28 + pores * 0.12 + figure * 0.16
        rough = 0.42 + 0.22 * (1.0 - macro) + 0.16 * pores
        ao = 0.66 + 0.34 * (macro * 0.5 + 0.5)
    else:  # atlas
        height = grain * 0.34 + weave * 0.24 + pores * 0.14 + figure * 0.14 + scratches * 0.08
        rough = 0.42 + 0.18 * pores + 0.12 * scratches
        ao = 0.74 + 0.26 * (grain * 0.5 + 0.5)

    height = np.clip(height, 0.0, 1.0)
    rough = np.clip(rough, 0.0, 1.0)
    ao = np.clip(ao, 0.0, 1.0)
    rgb = np.stack([height, rough, ao], axis=-1)
    rgb = np.clip(rgb * 255.0, 0, 255).astype(np.uint8)
    png(os.path.join(OUT, "detail_%s.png" % style), rgb)


for name in ("scan", "wear", "clean", "procedural", "atlas"):
    build(name)
print("done")
