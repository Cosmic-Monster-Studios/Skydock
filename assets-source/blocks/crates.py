#!/usr/bin/env python3
"""Regenerates <rank>_crate_side.png and <rank>_crate_top.png (16x16 block textures) for the five cargo crates; standard library only.
Run from the repository root: python3 assets-source/blocks/crates.py"""
import struct, zlib

OUT = "common/src/main/resources/assets/skydock/textures/block/{}_crate_{}.png"
# Per rank: H highlight, m plate, S shade, p panel, d panel seam, r rivet. Aether's seams glow.
PALETTES = {
    "tin":    {"H": (196, 202, 208), "m": (150, 156, 162), "S": (96, 102, 110), "p": (122, 128, 136), "d": (84, 90, 98), "r": (226, 230, 234)},
    "copper": {"H": (232, 150, 96), "m": (192, 108, 62), "S": (120, 62, 34), "p": (164, 88, 52), "d": (102, 52, 30), "r": (240, 190, 150)},
    "brass":  {"H": (222, 176, 84), "m": (176, 128, 52), "S": (112, 78, 30), "p": (150, 106, 44), "d": (90, 62, 24), "r": (240, 214, 150)},
    "gold":   {"H": (255, 236, 120), "m": (238, 196, 48), "S": (168, 124, 20), "p": (214, 170, 40), "d": (150, 108, 16), "r": (255, 250, 200)},
    "aether": {"H": (176, 124, 240), "m": (128, 72, 196), "S": (72, 36, 120), "p": (100, 54, 160), "d": (224, 186, 255), "r": (236, 210, 255)},
}
EDGE, BAND = "HrmmmmmmmmmmmmrS", "HHHHHHHHHHHHHHHS"
def row(inner): return "Hm" + inner + "mS"
SIDE = [BAND, EDGE, row("p" * 12), row("p" * 12), row("d" * 12), row("p" * 12), row("p" * 12), BAND,
        EDGE, row("p" * 12), row("p" * 12), row("d" * 12), row("p" * 12), row("p" * 12), EDGE, "S" * 16]
PLANKS = "pppdpppdpppp"
LATCH = "pppdHHHHpppp", "pppdHrrHpppp"
TOP = [BAND, EDGE, row(PLANKS), row(PLANKS), row(PLANKS), row(PLANKS), row(LATCH[0]), row(LATCH[1]),
       row(LATCH[1]), row(LATCH[0]), row(PLANKS), row(PLANKS), row(PLANKS), row(PLANKS), EDGE, "S" * 16]

def chunk(tag, data):
    body = tag + data
    return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

def png(rows, colours):
    raw = b"".join(b"\x00" + b"".join(bytes(colours[c] + (255,)) for c in row) for row in rows)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 16, 16, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))

if __name__ == "__main__":
    import os
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    for rank, colours in PALETTES.items():
        for face, art in (("side", SIDE), ("top", TOP)):
            assert len(art) == 16 and all(len(r) == 16 for r in art), face
            open(OUT.format(rank, face), "wb").write(png(art, colours))
