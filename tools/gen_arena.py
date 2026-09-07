#!/usr/bin/env python3
"""Generate the arena blueprint TrapArena builds the pit from.

Writes data/trapcraft/arena/arena.blocks.gz -- one line per block,
`x y z blockstate [tag]`, relative to the arena origin (the pit floor is y=0,
players stand at y=1) -- and, when asked, a preview PNG so the layout can be
looked at on the desk before a deploy that kicks everybody.

    python3 tools/gen_arena.py                 # rewrite the blueprint
    python3 tools/gen_arena.py --preview out/  # plus top-down and isometric PNGs

Why a blueprint rather than Java placing blocks straight out of a loop: the
preview and the server then read the SAME data, so what was checked on the
desk is what gets built, and retuning the pit never touches the Java.

Tags mark the blocks the fight switches on and off:

    sigil   the centre plate that lights up when the boss rises
    vein    the sea lanterns in the floor spokes -- phase three turns them off
    glow    crying obsidian set into the walls, off in phase three too
"""

import gzip
import math
import random
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent / "src/main/resources"
OUT = ROOT / "data/trapcraft/arena/arena.blocks.gz"

# --- shape ------------------------------------------------------------------
# All radii are measured to block centres. The pit is a circle of floor, a
# thick rampart with eight pillars, and three terraces of stands rising
# outward so anybody knocked out can watch over the wall.

PIT_R = 20.5          # floor
WALL_R = 24.5         # rampart, four blocks thick
WALL_TOP = 3          # rampart blocks at y=1..3; bars at 4
TIERS = ((27.0, 4), (29.5, 5), (32.0, 6))   # (outer radius, top block y)
RAIL_R = 32.5
PILLAR_R = 23.0
PILLAR_TOP = 10
GATE_HALF = 2         # the opening is five wide
LANDING_HALF = 3
LANDING_END = 31.0
SPOKES = 8

# Seeded so two runs agree byte for byte -- an unseeded pattern would turn
# every regeneration into a phantom diff of a thousand lines.
RNG = random.Random(0x5EED)


def r_of(x: int, z: int) -> float:
    return math.hypot(x, z)


def spoke_of(x: int, z: int) -> bool:
    """Whether this cell sits on one of the eight radial lines."""
    if x == 0 or z == 0 or abs(x) == abs(z):
        return True
    return False


def toward_centre(x: int, z: int) -> str:
    """Which way a stair on this cell should face to look at the middle."""
    if abs(x) >= abs(z):
        return "west" if x > 0 else "east"
    return "north" if z > 0 else "south"


def cracked(base: str, chance: float) -> str:
    return f"minecraft:cracked_{base}" if RNG.random() < chance else f"minecraft:{base}"


def build() -> dict[tuple[int, int, int], tuple[str, str | None]]:
    """(x, y, z) -> (blockstate, tag)."""
    blocks: dict[tuple[int, int, int], tuple[str, str | None]] = {}

    def put(x, y, z, state, tag=None):
        blocks[(x, y, z)] = (state, tag)

    limit = int(RAIL_R) + 1
    for x in range(-limit, limit + 1):
        for z in range(-limit, limit + 1):
            r = r_of(x, z)
            if r > RAIL_R:
                continue
            in_gate = abs(x) <= GATE_HALF and z > PIT_R
            in_landing = abs(x) <= LANDING_HALF and WALL_R < z <= LANDING_END

            # --- the pit floor and what holds it up ---------------------------
            if r <= WALL_R:
                put(x, -1, z, "minecraft:deepslate")
                put(x, -2, z, "minecraft:polished_blackstone")
                put(x, -3, z, "minecraft:obsidian")

            if r <= PIT_R:
                put(x, 0, z, floor_block(x, z, r))
                continue

            # --- the rampart -----------------------------------------------------
            if r <= WALL_R:
                put(x, 0, z, "minecraft:polished_blackstone_bricks")
                if in_gate:
                    # The doorway: open from floor to bar height, an arch of
                    # crying obsidian over the top so it reads as the way in.
                    put(x, WALL_TOP + 1, z, "minecraft:crying_obsidian", "glow")
                    continue
                for y in range(1, WALL_TOP + 1):
                    put(x, y, z, "minecraft:polished_blackstone_bricks")
                # The inner face is what the fight is seen against: purple
                # light set into it every few blocks, chiselled band on top.
                if r <= PIT_R + 1.0:
                    angle = int(round(math.degrees(math.atan2(z, x))))
                    if angle % 15 == 0:
                        put(x, 2, z, "minecraft:crying_obsidian", "glow")
                    put(x, WALL_TOP + 1, z, "minecraft:iron_bars")
                else:
                    put(x, WALL_TOP + 1, z, "minecraft:polished_deepslate")
                pillar(put, x, z)
                continue

            # --- the landing outside the gate ------------------------------------
            if in_landing:
                put(x, -1, z, "minecraft:deepslate")
                put(x, 0, z, "minecraft:polished_blackstone_bricks")
                if abs(x) == LANDING_HALF:
                    put(x, 1, z, "minecraft:polished_blackstone_brick_wall")
                    if int(z) % 3 == 0:
                        put(x, 2, z, "minecraft:soul_lantern")
                continue

            # --- the stands -------------------------------------------------------
            inner = WALL_R
            for outer, top in TIERS:
                if r <= outer:
                    for y in range(-1, top):
                        put(x, y, z, "minecraft:deepslate_bricks")
                    if r <= inner + 1.0:
                        put(x, top, z, f"minecraft:deepslate_brick_stairs"
                                       f"[facing={toward_centre(x, z)}]")
                    else:
                        put(x, top, z, cracked("deepslate_bricks", 0.08))
                    stairs_to_stands(put, x, z, top)
                    break
                inner = outer
            else:
                # The rail: a low wall round the back with a lantern on every
                # post, so the stands are lit from behind and the pit is not.
                for y in range(-1, TIERS[-1][1] + 1):
                    put(x, y, z, "minecraft:polished_blackstone_bricks")
                put(x, TIERS[-1][1] + 1, z, "minecraft:polished_blackstone_brick_wall")
                angle = int(round(math.degrees(math.atan2(z, x))))
                if angle % 12 == 0:
                    put(x, TIERS[-1][1] + 2, z, "minecraft:lantern")

    return blocks


def floor_block(x: int, z: int, r: float) -> str:
    """The pit floor: rings of stone, gold at the centre, lit spokes."""
    if x == 0 and z == 0:
        return "minecraft:crying_obsidian|sigil"
    if abs(x) + abs(z) == 1:
        return "minecraft:gold_block|sigil"
    if r <= 2.5:
        return "minecraft:polished_blackstone|sigil"
    if spoke_of(x, z) and 3.5 < r <= 19.5:
        step = int(max(abs(x), abs(z)))
        if step % 3 == 0:
            return "minecraft:sea_lantern|vein"
        return "minecraft:polished_blackstone"
    if r <= 8.5:
        return cracked("deepslate_tiles", 0.12)
    if r <= 9.5:
        return "minecraft:chiseled_polished_blackstone"
    if r <= 16.5:
        return cracked("polished_blackstone_bricks", 0.10)
    if r <= 19.5:
        return cracked("deepslate_bricks", 0.10)
    return "minecraft:polished_deepslate"


def pillar(put, x: int, z: int) -> None:
    """Eight two-by-two columns on the rampart, soul fire on top."""
    for k in range(SPOKES):
        angle = k * 2 * math.pi / SPOKES
        px, pz = PILLAR_R * math.cos(angle), PILLAR_R * math.sin(angle)
        if abs(x + 0.5 - px) <= 1.0 and abs(z + 0.5 - pz) <= 1.0:
            for y in range(WALL_TOP + 1, PILLAR_TOP + 1):
                put(x, y, z, "minecraft:chiseled_deepslate" if y == 7
                    else "minecraft:deepslate_tiles")
            put(x, PILLAR_TOP + 1, z, "minecraft:soul_soil")
            put(x, PILLAR_TOP + 2, z, "minecraft:soul_fire")
            return


def stairs_to_stands(put, x: int, z: int, top: int) -> None:
    """A flight up from the landing to the first terrace, both sides."""
    if not (LANDING_HALF < abs(x) <= LANDING_HALF + 3 and WALL_R < z <= WALL_R + 3):
        return
    step = abs(x) - LANDING_HALF          # 1..3
    facing = "east" if x > 0 else "west"
    for y in range(-1, step):
        put(x, y, z, "minecraft:deepslate_bricks")
    put(x, step, z, f"minecraft:deepslate_brick_stairs[facing={facing}]")
    # Headroom over the flight: the terrace fill above each step is cleared
    # (air lines are dropped at write time, so this only overrides the dict).
    for y in range(step + 1, top + 1):
        put(x, y, z, "minecraft:air")


def write(blocks) -> None:
    OUT.parent.mkdir(parents=True, exist_ok=True)
    lines = []
    for (x, y, z), (state, tag) in sorted(blocks.items(), key=lambda kv: (kv[0][1], kv[0][0], kv[0][2])):
        if "|" in state:
            state, tag = state.split("|", 1)
        if state == "minecraft:air":
            continue
        lines.append(f"{x} {y} {z} {state}" + (f" {tag}" if tag else ""))
    with gzip.open(OUT, "wt", encoding="utf-8") as handle:
        handle.write("\n".join(lines) + "\n")
    print(f"wrote {len(lines)} blocks -> {OUT.relative_to(ROOT.parent.parent)}")


# --- preview ------------------------------------------------------------------

COLOURS = {
    "deepslate_tiles": (60, 60, 64), "cracked_deepslate_tiles": (72, 72, 76),
    "deepslate_bricks": (70, 70, 74), "cracked_deepslate_bricks": (82, 82, 86),
    "deepslate_brick_stairs": (78, 78, 84), "deepslate": (80, 80, 84),
    "polished_deepslate": (74, 74, 78), "chiseled_deepslate": (90, 90, 96),
    "polished_blackstone": (38, 34, 42), "polished_blackstone_bricks": (44, 40, 48),
    "cracked_polished_blackstone_bricks": (54, 50, 58),
    "chiseled_polished_blackstone": (60, 54, 66), "polished_blackstone_brick_wall": (44, 40, 48),
    "blackstone": (34, 30, 36), "obsidian": (18, 12, 30),
    "crying_obsidian": (110, 40, 190), "sea_lantern": (190, 240, 230),
    "gold_block": (250, 210, 60), "iron_bars": (140, 140, 150),
    "soul_soil": (70, 50, 40), "soul_fire": (80, 220, 255),
    "lantern": (255, 190, 90), "soul_lantern": (120, 220, 240),
}


def colour_of(state: str) -> tuple[int, int, int]:
    name = state.split(":", 1)[1].split("[", 1)[0]
    return COLOURS.get(name, (255, 0, 255))


def preview(blocks, folder: Path) -> None:
    from PIL import Image, ImageDraw

    folder.mkdir(parents=True, exist_ok=True)
    limit = int(RAIL_R) + 1
    size = 2 * limit + 1
    scale = 8

    # Top-down: the highest block in each column, shaded by height.
    top = Image.new("RGB", (size * scale, size * scale), (8, 8, 12))
    draw = ImageDraw.Draw(top)
    columns: dict[tuple[int, int], tuple[int, str]] = {}
    for (x, y, z), (state, _) in blocks.items():
        state = state.split("|", 1)[0]
        if state == "minecraft:air":
            continue
        if (x, z) not in columns or columns[(x, z)][0] < y:
            columns[(x, z)] = (y, state)
    for (x, z), (y, state) in columns.items():
        r, g, b = colour_of(state)
        light = 0.75 + 0.05 * y
        px, pz = (x + limit) * scale, (z + limit) * scale
        draw.rectangle([px, pz, px + scale - 1, pz + scale - 1],
                       fill=(int(min(255, r * light)), int(min(255, g * light)),
                             int(min(255, b * light))))
    top.save(folder / "arena_top.png")

    # Isometric: painter's order by depth, three faces per block.
    s = 5
    width = size * 2 * s + 40
    height = size * s + 20 * s + 40
    iso = Image.new("RGB", (width, height), (8, 8, 12))
    draw = ImageDraw.Draw(iso)
    ox, oy = width // 2, 14 * s

    def screen(x, y, z):
        return ox + (x - z) * s, oy + (x + z) * s // 2 - y * s

    ordered = sorted(((x, y, z, st) for (x, y, z), (st, _) in blocks.items()
                      if not st.startswith("minecraft:air")),
                     key=lambda b: (b[0] + b[2], b[1]))
    for x, y, z, state in ordered:
        state = state.split("|", 1)[0]
        r, g, b = colour_of(state)
        tx, ty = screen(x, y, z)
        # Top face
        draw.polygon([(tx, ty), (tx + s, ty + s // 2), (tx, ty + s), (tx - s, ty + s // 2)],
                     fill=(r, g, b))
        # Right (+x facing) and left (+z facing) faces, darkened.
        draw.polygon([(tx, ty + s), (tx + s, ty + s // 2), (tx + s, ty + s // 2 + s),
                      (tx, ty + 2 * s)], fill=(r * 2 // 3, g * 2 // 3, b * 2 // 3))
        draw.polygon([(tx, ty + s), (tx - s, ty + s // 2), (tx - s, ty + s // 2 + s),
                      (tx, ty + 2 * s)], fill=(r // 2, g // 2, b // 2))
    iso.save(folder / "arena_iso.png")
    print(f"previews -> {folder}")


def main() -> None:
    blocks = build()
    write(blocks)
    if "--preview" in sys.argv:
        where = Path(sys.argv[sys.argv.index("--preview") + 1])
        preview(blocks, where)


if __name__ == "__main__":
    main()
