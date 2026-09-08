#!/usr/bin/env python3
"""Generate the arena blueprint TrapArena builds the pit from.

Writes data/trapcraft/arena/witness.blocks.gz -- one line per block,
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
OUT_DIR = ROOT / "data/trapcraft/arena"

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


def stairs_to_stands(put, x: int, z: int, top: int, fill="minecraft:deepslate_bricks",
                     stair="minecraft:deepslate_brick_stairs") -> None:
    """A flight up from the landing to the first terrace, both sides."""
    if not (LANDING_HALF < abs(x) <= LANDING_HALF + 3 and WALL_R < z <= WALL_R + 3):
        return
    step = abs(x) - LANDING_HALF          # 1..3
    facing = "east" if x > 0 else "west"
    for y in range(-1, step):
        put(x, y, z, fill)
    put(x, step, z, f"{stair}[facing={facing}]")
    # Headroom over the flight: the terrace fill above each step is cleared
    # (air lines are dropped at write time, so this only overrides the dict).
    for y in range(step + 1, top + 1):
        put(x, y, z, "minecraft:air")



# =============================================================================
# The bandit's roof: a casino rooftop under neon.
#
# Red carpet, gold trim, a roulette ring in the floor that the fight spins,
# quartz walls with froglight tubes, and JACKPOT written across the back in
# lamp. Loud on purpose: the opposite of the pit the watcher fights in.
# =============================================================================

BANDIT_RING = (6.5, 15.5)     # the roulette ring, inner and outer radius
BANDIT_SECTORS = 12
RING_COLOURS = ["minecraft:red_concrete", "minecraft:black_concrete", "minecraft:lime_concrete"]


def ring_sector(x: int, z: int) -> int:
    angle = math.degrees(math.atan2(z, x)) % 360.0
    return int(angle // (360.0 / BANDIT_SECTORS))


def build_bandit() -> dict:
    blocks = {}

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

            if r <= WALL_R:
                put(x, -1, z, "minecraft:quartz_bricks")
                put(x, -2, z, "minecraft:smooth_quartz")
                put(x, -3, z, "minecraft:gold_block" if r > WALL_R - 1.5 else "minecraft:quartz_block")

            if r <= PIT_R:
                put(x, 0, z, bandit_floor(x, z, r))
                continue

            if r <= WALL_R:
                put(x, 0, z, "minecraft:gold_block" if r <= PIT_R + 1 else "minecraft:quartz_bricks")
                if in_gate:
                    put(x, WALL_TOP + 1, z, "minecraft:pearlescent_froglight", "neon")
                    continue
                for y in range(1, WALL_TOP + 1):
                    put(x, y, z, "minecraft:quartz_bricks")
                if r <= PIT_R + 1.0:
                    angle = int(round(math.degrees(math.atan2(z, x))))
                    if angle % 10 == 0:
                        put(x, 2, z, "minecraft:ochre_froglight", "neon")
                    put(x, WALL_TOP + 1, z, "minecraft:gold_block")
                    put(x, WALL_TOP + 2, z, "minecraft:iron_bars")
                else:
                    put(x, WALL_TOP + 1, z, "minecraft:smooth_quartz")
                bandit_pillar(put, x, z)
                continue

            if in_landing:
                put(x, -1, z, "minecraft:quartz_bricks")
                put(x, 0, z, "minecraft:red_concrete")
                if abs(x) == LANDING_HALF:
                    put(x, 1, z, "minecraft:gold_block")
                    if int(z) % 3 == 0:
                        put(x, 2, z, "minecraft:verdant_froglight", "neon")
                continue

            inner = WALL_R
            for outer, top in TIERS:
                if r <= outer:
                    for y in range(-1, top):
                        put(x, y, z, "minecraft:quartz_bricks")
                    if r <= inner + 1.0:
                        put(x, top, z, f"minecraft:quartz_stairs[facing={toward_centre(x, z)}]")
                    else:
                        put(x, top, z, "minecraft:red_concrete" if (x + z) % 2 == 0 else "minecraft:quartz_bricks")
                    stairs_to_stands(put, x, z, top, "minecraft:quartz_bricks", "minecraft:quartz_stairs")
                    break
                inner = outer
            else:
                for y in range(-1, TIERS[-1][1] + 1):
                    put(x, y, z, "minecraft:quartz_bricks")
                put(x, TIERS[-1][1] + 1, z, "minecraft:gold_block")
                angle = int(round(math.degrees(math.atan2(z, x))))
                if angle % 12 == 0:
                    put(x, TIERS[-1][1] + 2, z, "minecraft:pearlescent_froglight", "neon")

    jackpot_sign(put)
    return blocks


def bandit_floor(x: int, z: int, r: float) -> str:
    inner, outer = BANDIT_RING
    if r <= 2.5:
        return "minecraft:gold_block|sigil" if abs(x) + abs(z) <= 1 else "minecraft:black_concrete|sigil"
    if r <= inner:
        return "minecraft:red_concrete" if (x + z) % 2 else "minecraft:red_terracotta"
    if r <= outer:
        return RING_COLOURS[ring_sector(x, z) % 3] + "|ring"
    if r <= outer + 1:
        return "minecraft:gold_block"
    if spoke_of(x, z) and int(max(abs(x), abs(z))) % 3 == 0:
        return "minecraft:sea_lantern|vein"
    return "minecraft:red_concrete" if (x + z) % 2 else "minecraft:red_terracotta"


def bandit_pillar(put, x: int, z: int) -> None:
    for k in range(SPOKES):
        angle = k * 2 * math.pi / SPOKES
        px, pz = PILLAR_R * math.cos(angle), PILLAR_R * math.sin(angle)
        if abs(x + 0.5 - px) <= 1.0 and abs(z + 0.5 - pz) <= 1.0:
            for y in range(WALL_TOP + 1, PILLAR_TOP + 1):
                put(x, y, z, "minecraft:gold_block" if y % 3 == 0 else "minecraft:quartz_pillar")
            put(x, PILLAR_TOP + 1, z, "minecraft:pearlescent_froglight", "neon")
            put(x, PILLAR_TOP + 2, z, "minecraft:ochre_froglight", "neon")
            return


# JACKPOT in a 3x5 lamp font on the north wall, above the rampart. Seven
# letters of three columns and a gap: 27 wide, centred.
LETTERS = {
    "J": ["111", "..1", "..1", "1.1", "111"],
    "A": ["111", "1.1", "111", "1.1", "1.1"],
    "C": ["111", "1..", "1..", "1..", "111"],
    "K": ["1.1", "1.1", "11.", "1.1", "1.1"],
    "P": ["111", "1.1", "111", "1..", "1.."],
    "O": ["111", "1.1", "1.1", "1.1", "111"],
    "T": ["111", ".1.", ".1.", ".1.", ".1."],
}


def jackpot_sign(put) -> None:
    word = "JACKPOT"
    width = len(word) * 4 - 1
    x0 = -(width // 2)
    z = -int(PILLAR_R) - 4
    base = TIERS[-1][1] + 3
    for i, letter in enumerate(word):
        rows = LETTERS[letter]
        for row, line in enumerate(rows):
            for col, ch in enumerate(line):
                x = x0 + i * 4 + col
                y = base + (4 - row)
                put(x, y, z, "minecraft:black_concrete")
                if ch == "1":
                    put(x, y, z + 1, "minecraft:pearlescent_froglight", "neon")
                else:
                    put(x, y, z + 1, "minecraft:black_concrete")
    # A backboard behind the lamps so the word reads against the night.
    for x in range(x0 - 2, x0 + width + 2):
        for y in range(base - 1, base + 6):
            if (x, y, z) not in ():
                put(x, y, z - 1, "minecraft:black_concrete")


# =============================================================================
# The rat king's chamber: a sewer cistern.
#
# Enclosed, wet, dim. Stone bricks going mossy, water in the channels,
# grates, six tunnel mouths in the wall the king comes and goes by, and a
# balcony under the ceiling for whoever is sitting out.
# =============================================================================

RAT_R = 18.5
RAT_WALL = RAT_R + 3
RAT_CEILING = 8
TUNNELS = 6
TUNNEL_DEPTH = 6


def build_ratking() -> dict:
    blocks = {}

    def put(x, y, z, state, tag=None):
        blocks[(x, y, z)] = (state, tag)

    limit = int(RAT_WALL) + TUNNEL_DEPTH + 2
    for x in range(-limit, limit + 1):
        for z in range(-limit, limit + 1):
            r = r_of(x, z)
            if r > RAT_WALL + 0.5:
                continue
            # floor and its bed
            put(x, -1, z, "minecraft:stone_bricks")
            put(x, -2, z, "minecraft:deepslate")
            if r <= RAT_R:
                put(x, 0, z, rat_floor(x, z, r))
                # the channels: a cross of water, one deep
                if (abs(x) <= 1 or abs(z) <= 1) and 3 < r <= RAT_R - 1:
                    put(x, 0, z, "minecraft:water")
                    put(x, -1, z, "minecraft:mossy_stone_bricks")
                # ceiling
                put(x, RAT_CEILING, z, "minecraft:stone_bricks" if RNG.random() > 0.15 else "minecraft:mossy_stone_bricks")
                if r > 2.5 and int(r) % 6 == 0 and int(round(math.degrees(math.atan2(z, x)))) % 45 == 0:
                    put(x, RAT_CEILING - 1, z, "minecraft:soul_lantern[hanging=true]")
                if RNG.random() < 0.06:
                    put(x, RAT_CEILING - 1, z, "minecraft:pointed_dripstone[vertical_direction=down,thickness=tip]")
                # balcony under the ceiling, round the wall
                if r > RAT_R - 2.5:
                    put(x, 5, z, "minecraft:stone_brick_slab[type=top]")
                    if r > RAT_R - 1.5:
                        put(x, 6, z, "minecraft:iron_bars")
                continue
            # the wall, floor to ceiling
            for y in range(0, RAT_CEILING + 1):
                state = "minecraft:stone_bricks"
                roll = RNG.random()
                if roll < 0.18:
                    state = "minecraft:mossy_stone_bricks"
                elif roll < 0.3:
                    state = "minecraft:cracked_stone_bricks"
                put(x, y, z, state)
            if r <= RAT_R + 1.0 and 1 <= 2 and RNG.random() < 0.25:
                put(x, RNG.randint(1, RAT_CEILING - 1), z, "minecraft:glow_lichen[%s=true]" % ("south" if z < 0 else "north"))

    # the tunnels: six mouths, two wide and three tall, going into the dark
    for k in range(TUNNELS):
        angle = k * 2 * math.pi / TUNNELS + math.pi / TUNNELS
        for depth in range(0, TUNNEL_DEPTH + 1):
            rr = RAT_R - 0.5 + depth
            cx = rr * math.cos(angle)
            cz = rr * math.sin(angle)
            for dx in (-1, 0, 1):
                for dz in (-1, 0, 1):
                    x, z = int(round(cx + dx)), int(round(cz + dz))
                    for y in range(1, 4):
                        blocks[(x, y, z)] = ("minecraft:air", None)
                    blocks[(x, 0, z)] = ("minecraft:mossy_stone_bricks", "tunnel" if depth == 0 and dx == 0 and dz == 0 else None)
                    blocks[(x, 4, z)] = ("minecraft:stone_bricks", None)
        # a grate of iron bars at the far end so nobody walks off into nothing
        far = RAT_R - 0.5 + TUNNEL_DEPTH + 1
        for dx in (-1, 0, 1):
            for dz in (-1, 0, 1):
                x, z = int(round(far * math.cos(angle) + dx)), int(round(far * math.sin(angle) + dz))
                for y in range(1, 4):
                    blocks[(x, y, z)] = ("minecraft:iron_bars", None)

    # the gate: a pipe from the south, arrival inside it
    for z in range(int(RAT_R), int(RAT_WALL) + 9):
        for dx in range(-2, 3):
            for y in range(0, 5):
                blocks[(dx, y, z)] = ("minecraft:stone_bricks" if abs(dx) == 2 or y in (0, 4) else "minecraft:air", None)
            blocks[(dx, 0, z)] = ("minecraft:mossy_stone_bricks", None)
        if z % 4 == 0:
            blocks[(0, 3, z)] = ("minecraft:soul_lantern[hanging=true]", None)
    return blocks


def rat_floor(x: int, z: int, r: float) -> str:
    if r <= 2.5:
        return "minecraft:polished_deepslate|sigil"
    roll = RNG.random()
    if spoke_of(x, z) and int(max(abs(x), abs(z))) % 4 == 0 and abs(x) > 1 and abs(z) > 1:
        return "minecraft:sea_lantern|vein"
    if roll < 0.2:
        return "minecraft:mossy_stone_bricks"
    if roll < 0.3:
        return "minecraft:cracked_stone_bricks"
    if roll < 0.34:
        return "minecraft:mud"
    return "minecraft:stone_bricks"


# =============================================================================
# The storm's platform: a disc in the clouds.
#
# No walls. A ring of end rods marks the edge, six lightning rods on copper
# stand where the bolts want to go, four braziers where the cold does not,
# and a bridge south to the spectator cloud. Everything else is sky.
# =============================================================================

STORM_R = 17.5
STORM_RODS = 6
STORM_FIRES = 4


def build_storm() -> dict:
    blocks = {}

    def put(x, y, z, state, tag=None):
        blocks[(x, y, z)] = (state, tag)

    limit = int(STORM_R) + 6
    for x in range(-limit, limit + 1):
        for z in range(-limit, limit + 1):
            r = r_of(x, z)
            if r <= STORM_R:
                put(x, 0, z, storm_floor(x, z, r))
                put(x, -1, z, "minecraft:white_concrete" if r < STORM_R - 2 else "minecraft:light_gray_concrete")
                if r > STORM_R - 1.0 and int(round(math.degrees(math.atan2(z, x)))) % 15 == 0:
                    put(x, 1, z, "minecraft:end_rod", "edge")
            elif r <= STORM_R + 3.5 and RNG.random() < 0.55:
                # the cloud fringe: wool and snow, a block down, uneven
                put(x, -1 - RNG.randint(0, 1), z, "minecraft:white_wool" if RNG.random() < 0.6 else "minecraft:snow_block")
    for k in range(STORM_RODS):
        angle = k * 2 * math.pi / STORM_RODS
        x, z = int(round(10 * math.cos(angle))), int(round(10 * math.sin(angle)))
        put(x, 1, z, "minecraft:copper_block")
        put(x, 2, z, "minecraft:copper_block")
        put(x, 3, z, "minecraft:lightning_rod", "rod")
    for k in range(STORM_FIRES):
        angle = k * 2 * math.pi / STORM_FIRES + math.pi / STORM_FIRES
        x, z = int(round(5 * math.cos(angle))), int(round(5 * math.sin(angle)))
        put(x, 0, z, "minecraft:hay_block")
        put(x, 1, z, "minecraft:campfire[lit=true]", "fire")
    # the bridge south, and the spectator cloud at its end
    for z in range(int(STORM_R), 27):
        for dx in (-2, -1, 0, 1, 2):
            put(dx, 0, z, "minecraft:white_concrete" if abs(dx) < 2 else "minecraft:light_gray_concrete")
            if abs(dx) == 2:
                put(dx, 1, z, "minecraft:end_rod" if z % 3 == 0 else "minecraft:glass_pane")
    for x in range(-6, 7):
        for z in range(26, 39):
            if r_of(x, z - 32) <= 6.5:
                put(x, 0, z, "minecraft:white_concrete")
                put(x, -1, z, "minecraft:light_gray_concrete")
                if r_of(x, z - 32) > 5.5:
                    put(x, 1, z, "minecraft:glass_pane")
    return blocks


def storm_floor(x: int, z: int, r: float) -> str:
    if r <= 2.5:
        return "minecraft:sea_lantern|sigil"
    if r > STORM_R - 1.5:
        return "minecraft:light_gray_concrete"
    if spoke_of(x, z) and int(max(abs(x), abs(z))) % 3 == 0 and r > 3.5:
        return "minecraft:sea_lantern|vein"
    return "minecraft:white_concrete" if RNG.random() > 0.12 else "minecraft:quartz_block"


def write(blocks, name: str) -> None:
    OUT = OUT_DIR / f"{name}.blocks.gz"
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
    "quartz_bricks": (236, 230, 222), "smooth_quartz": (240, 236, 228), "quartz_block": (236, 232, 224),
    "quartz_pillar": (230, 226, 218), "quartz_stairs": (228, 224, 216),
    "red_concrete": (150, 30, 30), "red_terracotta": (140, 60, 45), "black_concrete": (12, 12, 16),
    "lime_concrete": (90, 170, 30), "pearlescent_froglight": (250, 210, 240),
    "ochre_froglight": (250, 230, 130), "verdant_froglight": (170, 250, 170),
    "stone_bricks": (120, 120, 120), "mossy_stone_bricks": (100, 120, 90), "cracked_stone_bricks": (105, 105, 105),
    "water": (40, 80, 200), "mud": (60, 50, 45), "stone_brick_slab": (118, 118, 118),
    "glow_lichen": (110, 170, 130), "pointed_dripstone": (130, 110, 90),
    "white_concrete": (235, 240, 242), "light_gray_concrete": (150, 150, 155), "white_wool": (240, 240, 240),
    "snow_block": (245, 250, 255), "end_rod": (250, 250, 230), "copper_block": (200, 110, 70),
    "lightning_rod": (200, 120, 80), "hay_block": (200, 170, 60), "campfire": (255, 140, 40),
    "glass_pane": (200, 220, 240), "air": (0, 0, 0),
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


ARENAS = {
    "witness": lambda: build(),
    "bandit": lambda: build_bandit(),
    "ratking": lambda: build_ratking(),
    "storm": lambda: build_storm(),
}


def main() -> None:
    wanted = [a for a in sys.argv[1:] if a in ARENAS] or list(ARENAS)
    for name in wanted:
        RNG.seed(0x5EED)
        blocks = ARENAS[name]()
        write(blocks, name)
        if "--preview" in sys.argv:
            where = Path(sys.argv[sys.argv.index("--preview") + 1]) / name
            preview(blocks, where)


if __name__ == "__main__":
    main()
