#!/usr/bin/env python3
"""Regenerates the cargo outpost structure template (outpost.nbt); standard library only.
Run from the repository root: python3 assets-source/structures/cargo_outpost.py

Template frame (terminal faces north, toward the pad):
  x 1..9, z 1..9  the 9 x 9 landing pad at y=0, painted border, clear air above
  x 3..7, z 10..14 the 5 x 5 terminal building; its north wall, on the pad edge, holds the door
                   and the terminal beside it, facing the pad
  x 0, z 0        lamp post with a brass lantern, off the pad's north-west corner
Cells not listed are structure void, so the terrain there is kept."""
import gzip, os, struct

OUT = "common/src/main/resources/data/skydock/structure/cargo_outpost/outpost.nbt"
SIZE = (11, 6, 15)
PAD = range(1, 10)
BUILDING_X, BUILDING_Z = range(3, 8), range(10, 15)
TERMINAL = (4, 1, 10)
PAD_TOP_CENTRE = (5, 1, 5)


class IntArray(list):
    pass


def payload(value):
    """Returns (tag id, encoded payload) for a Python value: dict, str, int, IntArray or list."""
    if isinstance(value, dict):
        body = b"".join(named(k, v) for k, v in value.items())
        return 10, body + b"\x00"
    if isinstance(value, str):
        data = value.encode("utf-8")
        return 8, struct.pack(">H", len(data)) + data
    if isinstance(value, bool) or isinstance(value, int):
        return 3, struct.pack(">i", int(value))
    if isinstance(value, IntArray):
        return 11, struct.pack(">i", len(value)) + b"".join(struct.pack(">i", v) for v in value)
    if isinstance(value, list):
        if not value:
            return 9, struct.pack(">bi", 0, 0)
        items = [payload(v) for v in value]
        tag = items[0][0]
        assert all(t == tag for t, _ in items)
        return 9, struct.pack(">bi", tag, len(items)) + b"".join(p for _, p in items)
    raise TypeError(value)


def named(name, value):
    tag, body = payload(value)
    data = name.encode("utf-8")
    return struct.pack(">bH", tag, len(data)) + data + body


def layout():
    """Maps (x, y, z) to (block name, properties dict)."""
    cells = {}
    def put(x, y, z, name, **props):
        cells[(x, y, z)] = (name, props)
    for x in PAD:
        for z in PAD:
            border = x in (PAD[0], PAD[-1]) or z in (PAD[0], PAD[-1])
            put(x, 0, z, "minecraft:yellow_concrete" if border else "minecraft:smooth_stone")
            for y in range(1, SIZE[1]):
                put(x, y, z, "minecraft:air")
    for x in BUILDING_X:
        for z in BUILDING_Z:
            corner = x in (BUILDING_X[0], BUILDING_X[-1]) and z in (BUILDING_Z[0], BUILDING_Z[-1])
            wall = x in (BUILDING_X[0], BUILDING_X[-1]) or z in (BUILDING_Z[0], BUILDING_Z[-1])
            put(x, 0, z, "minecraft:spruce_planks")
            for y in range(1, 4):
                if corner:
                    put(x, y, z, "minecraft:stripped_spruce_log", axis="y")
                elif wall:
                    put(x, y, z, "minecraft:spruce_planks")
                else:
                    put(x, y, z, "minecraft:air")
            put(x, 4, z, "minecraft:stripped_spruce_log" if corner else "minecraft:spruce_planks", **({"axis": "y"} if corner else {}))
            put(x, 5, z, "minecraft:spruce_slab" if not wall else "minecraft:air", **({"type": "bottom", "waterlogged": "false"} if not wall else {}))
    # Door opening facing the pad, and windows in the side walls.
    put(5, 1, 10, "minecraft:air")
    put(5, 2, 10, "minecraft:air")
    pane = {"east": "false", "north": "true", "south": "true", "waterlogged": "false", "west": "false"}
    put(3, 2, 12, "minecraft:glass_pane", **pane)
    put(7, 2, 12, "minecraft:glass_pane", **pane)
    put(*TERMINAL, "skydock:cargo_terminal", facing="north")
    put(6, 3, 13, "skydock:brass_lantern")
    # Lamp post off the pad corner, visible from the air.
    put(0, 0, 0, "minecraft:stone_bricks")
    for y in range(1, 4):
        put(0, y, 0, "minecraft:spruce_fence")
    put(0, 4, 0, "skydock:brass_lantern")
    return cells


def template():
    cells = layout()
    palette, index, blocks = [], {}, []
    for pos in sorted(cells, key=lambda p: (p[1], p[2], p[0])):
        name, props = cells[pos]
        key = (name, tuple(sorted(props.items())))
        if key not in index:
            index[key] = len(palette)
            entry = {"Name": name}
            if props:
                entry["Properties"] = dict(sorted(props.items()))
            palette.append(entry)
        block = {"pos": list(pos), "state": index[key]}
        if pos == TERMINAL:
            offset = [PAD_TOP_CENTRE[i] - TERMINAL[i] for i in range(3)]
            block["nbt"] = {"id": "skydock:cargo_terminal", "PadOffset": IntArray(offset)}
        blocks.append(block)
    return {"size": list(SIZE), "entities": [], "blocks": blocks, "palette": palette, "DataVersion": 3955}


if __name__ == "__main__":
    data = b"\x0a" + struct.pack(">H", 0) + payload(template())[1]
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "wb") as f:
        f.write(gzip.compress(data, mtime=0))
