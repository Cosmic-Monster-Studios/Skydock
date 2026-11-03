#!/usr/bin/env python3
"""Regenerates warp_anchor.png (16x16) in the v2 runtime palette; standard library only.
Run from the repository root: python3 assets-source/items/warp_anchor.py"""
import struct, zlib

OUT = "common/src/main/resources/assets/skydock/textures/item/warp_anchor.png"
C = {
    "o": (44, 52, 56, 255),      # dark outline
    "c": (150, 82, 52, 255),     # copper
    "C": (204, 124, 82, 255),    # copper highlight
    "p": (96, 44, 140, 255),     # ender core rim
    "P": (160, 92, 214, 255),    # ender core glow
    "W": (226, 196, 255, 255),   # core spark
}
ART = [
    "................",
    "......oooo......",
    ".....oCCcco.....",
    "....oCpPPpco....",
    "....oCPWWPco....",
    "....ocpPPpco....",
    ".....occcco.....",
    "..oooooCcooooo..",
    "..oCCCCCccccco..",
    "..oooooCcooooo..",
    "......oCco......",
    ".oo...oCco...oo.",
    ".oCo..oCco..oco.",
    ".ocCCooCcooccco.",
    "..occcccccccco..",
    "...oooooooooo...",
]

def chunk(tag, data):
    body = tag + data
    return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

def png(rows):
    raw = b"".join(b"\x00" + b"".join(bytes(C[c]) if c in C else b"\x00\x00\x00\x00" for c in row) for row in rows)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 16, 16, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))

if __name__ == "__main__":
    assert len(ART) == 16 and all(len(r) == 16 for r in ART)
    import os
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    open(OUT, "wb").write(png(ART))
