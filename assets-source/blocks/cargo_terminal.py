#!/usr/bin/env python3
"""Regenerates cargo_terminal_front.png and cargo_terminal_side.png (16x16 block textures); standard library only.
Run from the repository root: python3 assets-source/blocks/cargo_terminal.py"""
import struct, zlib

OUT = "common/src/main/resources/assets/skydock/textures/block/cargo_terminal_{}.png"
C = {
    "o": (38, 44, 50, 255),      # dark seam
    "i": (70, 78, 86, 255),      # iron casing
    "I": (98, 108, 116, 255),    # casing highlight
    "s": (52, 58, 66, 255),      # casing shade
    "b": (176, 128, 52, 255),    # brass trim
    "B": (222, 176, 84, 255),    # brass highlight
    "d": (112, 78, 30, 255),     # brass shade
    "g": (30, 58, 46, 255),      # screen glass
    "G": (96, 196, 132, 255),    # screen glow
    "r": (190, 202, 214, 255),   # rivet
    "k": (180, 60, 44, 255),     # red key
    "y": (226, 196, 70, 255),    # yellow key
}
FRONT = [
    "bBBBBBBBBBBBBBBd",
    "Bddddddddddddddd",
    "Bdoooooooooooood",
    "BdogGggGGgggGgod",
    "BdogGGgGGgGGggod",
    "Bdogggggggggggod",
    "BdogGgGGGgGgggod",
    "Bdogggggggggggod",
    "Bdoooooooooooood",
    "biiiiiiiiiiiiiid",
    "bIriIIIIIIIIrisd",
    "bIiIkIyIkIyIiisd",
    "bIiiiiiiiiiiiisd",
    "bIiiiiiiiiiiiisd",
    "bssssssssssssssd",
    "dddddddddddddddd",
]
SIDE = [
    "bBBBBBBBBBBBBBBd",
    "bIIIIIIIIIIIIIsd",
    "bIriiiiiiiiiirsd",
    "bIiiiiiiiiiiiisd",
    "bIiioooooooiiisd",
    "bIiiiiiiiiiiiisd",
    "bIiioooooooiiisd",
    "bIiiiiiiiiiiiisd",
    "bddddddddddddddd",
    "bBBBBBBBBBBBBBBd",
    "bIiiiiiiiiiiiisd",
    "bIiiiiiiiiiiiisd",
    "bIriiiiiiiiiirsd",
    "bIiiiiiiiiiiiisd",
    "bssssssssssssssd",
    "dddddddddddddddd",
]

def chunk(tag, data):
    body = tag + data
    return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

def png(rows):
    raw = b"".join(b"\x00" + b"".join(bytes(C[c]) for c in row) for row in rows)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 16, 16, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))

if __name__ == "__main__":
    import os
    for name, art in (("front", FRONT), ("side", SIDE)):
        assert len(art) == 16 and all(len(r) == 16 for r in art), name
        os.makedirs(os.path.dirname(OUT), exist_ok=True)
        open(OUT.format(name), "wb").write(png(art))
