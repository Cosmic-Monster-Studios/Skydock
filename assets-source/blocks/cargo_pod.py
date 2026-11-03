#!/usr/bin/env python3
"""Regenerates cargo_pod.png (16x16 block texture); standard library only.
Run from the repository root: python3 assets-source/blocks/cargo_pod.py"""
import struct, zlib

OUT = "common/src/main/resources/assets/skydock/textures/block/cargo_pod.png"
C = {
    "o": (38, 48, 58, 255),      # dark seam
    "h": (96, 116, 134, 255),    # hull plate
    "H": (124, 144, 162, 255),   # plate highlight
    "s": (70, 88, 104, 255),     # plate shade
    "r": (190, 202, 214, 255),   # rivet
    "b": (176, 128, 52, 255),    # brass band
    "B": (222, 176, 84, 255),    # brass highlight
    "d": (112, 78, 30, 255),     # brass shade
    "m": (230, 226, 206, 255),   # stencil paint
}
ART = [
    "oooooooooooooooo",
    "orHhhhhhhhhhhhro",
    "oHhhhhhhhhhhhhso",
    "ohhhhmmmmmmhhhso",
    "ohhhhmhmmhmhhhso",
    "ohhhhmmhhmmhhhso",
    "ohhhhmmhhmmhhhso",
    "ohhhhmhmmhmhhhso",
    "obBBBBBBBBBBBBdo",
    "obbrbbbbbbbbrbdo",
    "oddddddddddddddo",
    "ohhhhhhhhhhhhhso",
    "ohhhhhhhhhhhhhso",
    "orhhhhhhhhhhhhro",
    "osssssssssssssso",
    "oooooooooooooooo",
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
