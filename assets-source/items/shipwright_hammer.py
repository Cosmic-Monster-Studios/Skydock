#!/usr/bin/env python3
"""Regenerates shipwright_hammer.png (16x16) in the v2 runtime palette; standard library only.
Run from the repository root: python3 assets-source/items/shipwright_hammer.py"""
import struct, zlib

OUT = "common/src/main/resources/assets/skydock/textures/item/shipwright_hammer.png"
C = {
    "o": (44, 52, 56, 255),      # dark outline
    "h": (124, 89, 59, 255),     # timber handle
    "H": (93, 61, 52, 255),      # handle shade
    "b": (185, 143, 78, 255),    # brass band
    "B": (215, 177, 106, 255),   # brass highlight
    "i": (97, 109, 111, 255),    # iron head
    "I": (130, 142, 144, 255),   # iron highlight
}
ART = [
    "................",
    "....oooooooo....",
    "...oIIIiiiiio...",
    "...oIiiiiiiio...",
    "...oiiiiiiiio...",
    "...oBbbbbbbbo...",
    "....oooHhoooo...",
    ".......oho......",
    ".......ohHo.....",
    "......ohHo......",
    "......ohHo......",
    ".....ohHo.......",
    ".....ohHo.......",
    "....ohHo........",
    "....oHo.........",
    ".....o..........",
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
