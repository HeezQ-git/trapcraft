#!/usr/bin/env python3
"""Generate every models/items/lang/loot/recipe JSON for TrapCraft.

Re-runnable: `python3 tools/gen_assets.py`. These files are pure boilerplate
that varies only by strain name, so they're generated rather than hand-written
-- adding a strain means editing STRAINS here and in gen_textures.py, nothing
else. Don't hand-edit the output; the next run overwrites it.
"""

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent / "src/main/resources"
NS = "trapcraft"
STRAINS = {"kush": "Kush", "haze": "Haze", "purp": "Purp",
           "diesel": "Diesel", "midnight": "Midnight", "sunset": "Sunset"}
MAX_AGE = 3

written = 0


def put(relative: str, payload: dict) -> None:
    global written
    path = ROOT / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2) + "\n")
    written += 1


def leaf(y0, y1, spread, angle, span_x, tex="leaf"):
    """One foliage quad, angled about the vertical.

    Zero thickness on one axis, which is legal and is how every plant in the
    game is built -- minecraft:block/cross is exactly two of these. Element
    rotation only accepts 0, +/-22.5 and +/-45, so eight distinct directions is
    the most a plant can have: four angles on an X-spanning quad and the same
    on a Z-spanning one.

    shade off, like vanilla foliage: quads facing different ways would
    otherwise get different brightness and the plant would look striped.
    """
    if span_x:
        frm, to = [8 - spread, y0, 8], [8 + spread, y1, 8]
        faces = {"north": {"texture": f"#{tex}"}, "south": {"texture": f"#{tex}"}}
    else:
        frm, to = [8, y0, 8 - spread], [8, y1, 8 + spread]
        faces = {"east": {"texture": f"#{tex}"}, "west": {"texture": f"#{tex}"}}
    return {
        "from": frm, "to": to,
        "rotation": {"origin": [8, (y0 + y1) / 2.0, 8], "axis": "y",
                     "angle": angle, "rescale": True},
        "shade": False,
        "faces": faces,
    }


def plant_model(age: int, leaf_tex: str, bud_tex: str | None) -> dict:
    """A crop with an actual stalk instead of two crossed pictures of one.

    A billboard has no plant in it -- turn your head and it turns with you,
    which is precisely why a field of these read as flat. Here the stalk is a
    real box you can walk round, and the foliage is a fan of quads at as many
    angles as element rotation allows, so the silhouette changes as you move.

    Growth is in the geometry, not just the texture: the stalk climbs, the fan
    widens and gains a tier per stage, and the mature plant grows bud boxes
    that stand proud of the leaves.
    """
    height = [5, 9, 13, 15][age]
    els = [{
        "from": [7, 0, 7], "to": [9, height, 9],
        "faces": {side: {"texture": "#stem"} for side in
                  ("north", "south", "east", "west", "up")},
    }]

    # (bottom, top, spread, angle, spans-x) per tier. Lower tiers droop wider,
    # upper ones sit tighter -- that taper is most of what makes it read as a
    # plant rather than a bush.
    tiers = [
        [(1, 8, 5.5, 45, True), (1, 8, 5.5, -45, False)],
        [(1, 9, 6.0, 45, True), (1, 9, 6.0, -45, False),
         (4, 12, 5.0, 22.5, True), (4, 12, 5.0, -22.5, False)],
        [(1, 10, 6.5, 45, True), (1, 10, 6.5, -45, False),
         (4, 13, 5.5, 22.5, True), (4, 13, 5.5, -22.5, False),
         (7, 15, 4.5, 0, True), (7, 15, 4.5, 0, False)],
        [(1, 11, 7.0, 45, True), (1, 11, 7.0, -45, False),
         (3, 14, 6.0, 22.5, True), (3, 14, 6.0, -22.5, False),
         (6, 16, 5.0, 0, True), (6, 16, 5.0, 0, False),
         (8, 16, 4.0, -45, True), (8, 16, 4.0, 45, False)],
    ][age]
    for y0, y1, spread, angle, span_x in tiers:
        els.append(leaf(y0, y1, spread, angle, span_x))

    if bud_tex is not None:
        # Main cola on top, two smaller ones down the stalk.
        for frm, to in (([6.5, 11, 6.5], [9.5, 15.5, 9.5]),
                        ([4.5, 7, 7], [6.5, 10.5, 9]),
                        ([9.5, 6, 7], [11.5, 9.5, 9])):
            els.append(box(frm, to, "bud"))

    textures = {"stem": f"{NS}:block/crop_stem", "leaf": leaf_tex,
                "particle": leaf_tex}
    if bud_tex is not None:
        textures["bud"] = bud_tex
    return {
        "parent": "minecraft:block/block",
        "ambientocclusion": False,
        "textures": textures,
        "elements": els,
    }


def block_models() -> None:
    # Ages 0-2 are one neutral model across strains: you shouldn't be able to
    # identify a seedling by looking at it. Only the mature plant shows its
    # phenotype, and it shows it in the leaves AND the buds.
    for age in range(MAX_AGE):
        put(f"assets/{NS}/models/block/cannabis_crop_age{age}.json",
            plant_model(age, f"{NS}:block/crop_leaf", None))
    for strain in STRAINS:
        put(f"assets/{NS}/models/block/cannabis_crop_age{MAX_AGE}_{strain}.json",
            plant_model(MAX_AGE, f"{NS}:block/crop_leaf_{strain}",
                        f"{NS}:block/crop_bud_{strain}"))

    put(f"assets/{NS}/models/block/drying_rack_empty.json", rack_model(None))
    # One model per (strain, drying stage) so the cure is visible on the block.
    for strain in STRAINS:
        for stage in range(5):
            put(f"assets/{NS}/models/block/drying_rack_{strain}_{stage}.json",
                rack_model(f"{NS}:block/drying_rack_bud_{strain}_{stage}"))


def rack_model(bud: str | None) -> dict:
    """The drying rack as a recessed cabinet.

    It used to be a cube with a picture of a rack painted on each face, which
    is why it read flat next to everything else. This is real geometry: a
    frame of corner posts between a plinth and a lid, with a four-pixel recess
    on each of the four sides and the bud hanging inside it.

    RECESSED, not an open frame, and that's forced rather than chosen. Polymer
    serves this on a FULL_BLOCK carrier, so the client treats the space as
    solid -- an open skeleton would let you see through to a world lit as if
    the block were still there, which looks broken. The other pools that would
    allow a see-through model are either nearly empty (TRANSPARENT_BLOCK has
    fewer free states than this block needs models) or would desync collision.
    A closed cabinet with recessed faces gets the depth without either problem,
    and a cabinet is a perfectly good drying rack.

    No facing property, so all four sides are identical -- you can put it
    against a wall or in the middle of a room and it reads the same.
    """
    inner = f"{NS}:block/drying_rack_inner"
    frame = f"{NS}:block/drying_rack_side"
    lid = f"{NS}:block/drying_rack_top"

    els = [
        # Plinth and lid, full width, so the silhouette is still a solid block
        # from above and below.
        box([0, 0, 0], [16, 3, 16], "frame", up="lid", down="lid"),
        box([0, 13, 0], [16, 16, 16], "frame", up="lid", down="lid"),
        # Four corner posts.
        box([0, 3, 0], [4, 13, 4], "frame"),
        box([12, 3, 0], [16, 13, 4], "frame"),
        box([0, 3, 12], [4, 13, 16], "frame"),
        box([12, 3, 12], [16, 13, 16], "frame"),
        # The core the recesses are cut into. This is what stops you seeing
        # through the block.
        box([4, 3, 4], [12, 13, 12], "inner"),
    ]

    # A rail across each recess, and the bud hanging off it. The rail sits
    # proud of the core so it catches light from the side.
    rails = [([4, 10, 1], [12, 11, 2.5]), ([4, 10, 13.5], [12, 11, 15]),
             ([1, 10, 4], [2.5, 11, 12]), ([13.5, 10, 4], [15, 11, 12])]
    for frm, to in rails:
        els.append(box(frm, to, "frame"))

    if bud is not None:
        # Hanging from each rail, in the 4px gap between post line and core.
        hangs = [([4.5, 4, 1.2], [11.5, 10, 2.3]), ([4.5, 4, 13.7], [11.5, 10, 14.8]),
                 ([1.2, 4, 4.5], [2.3, 10, 11.5]), ([13.7, 4, 4.5], [14.8, 10, 11.5])]
        for frm, to in hangs:
            els.append(box(frm, to, "bud"))

    textures = {
        "frame": frame,
        "lid": lid,
        "inner": inner,
        "particle": frame,
    }
    if bud is not None:
        textures["bud"] = bud

    return {
        "parent": "minecraft:block/block",
        # Off: the recesses are shallow and vanilla AO bands every join in them
        # with a dark seam, which reads as dirt rather than depth.
        "ambientocclusion": False,
        "textures": textures,
        "elements": els,
    }


def item_assets() -> None:
    """Flat sprites for everything except the rack, which shows its block model."""
    flat = [f"{kind}_{strain}"
            for strain in STRAINS
            for kind in ("seeds", "raw_bud", "dried_bud", "joint")]

    flat = flat + ["miners_hammer"]

    for name in flat:
        put(f"assets/{NS}/models/item/{name}.json", {
            "parent": "minecraft:item/generated",
            "textures": {"layer0": f"{NS}:item/{name}"},
        })

    put(f"assets/{NS}/models/item/drying_rack.json",
        {"parent": f"{NS}:block/drying_rack_empty"})

    # 1.21.4+ item model definitions -- what getPolymerItemModel() resolves to.
    for name in flat + ["drying_rack"]:
        put(f"assets/{NS}/items/{name}.json", {
            "model": {"type": "minecraft:model", "model": f"{NS}:item/{name}"},
        })


def post_effects() -> None:
    """One blur per strain -- radius is baked into the JSON, not settable live.

    Vanilla's own blur.json ships six passes at Radius 0 (the pause menu injects
    the value at runtime), so pointing at minecraft:blur does nothing at all.
    These reuse vanilla's shader programs with a real radius.
    """
    # How much of the previous frame survives each frame. Higher = longer
    # trails. Kush is the heavy one, Haze barely ghosts.
    blends = {"haze": 0.56, "kush": 0.74, "purp": 0.62,
              "diesel": 0.60, "midnight": 0.78, "sunset": 0.58,
              # Mixes get their own three, by how many distinct strains went
              # in. These are the only pipelines that switch on the mirror,
              # echo and posterise layers.
              "blend2": 0.68, "blend3": 0.74, "blend4": 0.80,
              # The coca line. Short trails on purpose -- Baked smears and
              # sinks, Wired is supposed to feel sharp and over-clocked, and
              # long trails would read as the same drug twice.
              "wired": 0.34,
              # The comedown. Trails go long as everything sags.
              "crash": 0.82}

    def blit(source, output):
        return {
            "vertex_shader": "minecraft:post/blit",
            "fragment_shader": "minecraft:post/blit",
            "inputs": [{"sampler_name": "In", "target": source, "bilinear": False}],
            "output": output,
            "uniforms": {
                "BlitConfig": [
                    {"name": "ColorModulate", "type": "vec4", "value": [1.0, 1.0, 1.0, 1.0]},
                ]
            },
        }

    # Three trail lengths per strain. The blend value can't be changed at
    # runtime -- it's baked into the pipeline JSON -- so a harder hit means
    # swapping to a different file, and the client latches the choice at the
    # moment you take the hit so the shader reload can't flash mid-high.
    bands = [0.0, 0.09, 0.16]

    # How hard the warp shader bends the world, per band. Band 0 is one
    # ordinary joint and is deliberately almost nothing -- a faint lens, not a
    # funhouse -- because that's the baseline the mod has always had. Band 2 is
    # a fire tlok and is meant to be a lot.
    #
    #                 warp    swirl   split   spin   pulse
    warps = [(0.0016, 0.030, 0.0025,  4.0,  0.55),
             (0.0060, 0.110, 0.0090, 14.0,  0.85),
             (0.0135, 0.230, 0.0180, 30.0,  1.20)]

    # Per-strain character, multiplying the band values. Same idea as the
    # sway/FOV tables on the client: a strain should still feel like itself at
    # every strength, so Purp twists and Kush lumbers whatever band it's in.
    #             warp swirl split spin pulse
    character = {"haze":     (1.25, 0.55, 1.15, 0.70, 1.70),
                 "kush":     (0.80, 0.75, 0.70, 0.55, 0.45),
                 "purp":     (1.00, 1.60, 1.35, 1.80, 0.95),
                 "diesel":   (1.15, 0.95, 1.00, 0.90, 1.35),
                 "midnight": (0.90, 1.45, 0.85, 1.30, 0.40),
                 "sunset":   (1.05, 1.20, 1.20, 1.50, 1.10),
                 "blend2":   (1.30, 1.30, 1.40, 1.60, 1.15),
                 "blend3":   (1.55, 1.70, 1.65, 2.10, 1.30),
                 "blend4":   (1.85, 2.10, 1.90, 2.60, 1.45),
                 # Wired: almost no warp or twist, but hard channel split
                 # and a fast pulse. Crisp and jittery, not liquid.
                 "wired":    (0.35, 0.10, 2.40, 0.40, 2.60),
                 # Crash: everything slows to a crawl and the world bends
                 # under its own weight.
                 "crash":    (1.40, 0.90, 0.50, 0.30, 0.18)}

    # mirror segments, ghost strength, echo, posterise steps. Single strains
    # get none of it -- these four are what a blend buys you, and they escalate
    # with how many distinct strains are in the mix.
    #
    # Ghost stays well under half even at the top: a full kaleidoscope is
    # unplayable, you genuinely cannot see where you're walking.
    # Saturation multiplier per stem. Single strains sit at 1.0 (untouched);
    # blends push past what the game normally shows; the coca line runs cold
    # and over-saturated, then the crash drains it to almost nothing.
    sat = {"blend2": 1.15, "blend3": 1.30, "blend4": 1.45,
           "wired": 1.35, "crash": 0.22}

    wilds = {"blend2": [(0, 0.0, 0.00, 0), (4, 0.12, 0.10, 0), (4, 0.20, 0.18, 12)],
             "blend3": [(0, 0.0, 0.06, 0), (6, 0.18, 0.16, 14), (6, 0.28, 0.26, 9)],
             "blend4": [(5, 0.10, 0.12, 0), (8, 0.26, 0.24, 10), (8, 0.38, 0.34, 6)],
             # Wired posterises hard at the top -- flat banded colour reads
             # as over-exposed rather than as the liquid blend look.
             "wired":  [(0, 0.0, 0.00, 0), (0, 0.0, 0.00, 16), (0, 0.0, 0.06, 10)],
             "crash":  [(0, 0.0, 0.10, 0), (0, 0.0, 0.16, 0), (0, 0.0, 0.22, 0)]}

    for strain, base in blends.items():
      for band, longer in enumerate(bands):
        blend = round(min(base + longer, 0.90), 3)
        warp, swirl, split, spin, pulse = warps[band]
        put(f"assets/{NS}/post_effect/motion_blur_{strain}_{band}.json", {
            "targets": {
                # persistent: survives between frames. Without it there is no
                # previous frame to accumulate and the effect does nothing.
                "prev": {"persistent": True},
                "swap": {},
            },
            "passes": [
                {
                    "vertex_shader": "minecraft:post/blit",
                    "fragment_shader": f"{NS}:post/motion_blur",
                    "inputs": [
                        {"sampler_name": "In", "target": "minecraft:main", "bilinear": False},
                        {"sampler_name": "Prev", "target": "prev", "bilinear": False},
                    ],
                    "output": "swap",
                    "uniforms": {
                        "MotionBlurConfig": [
                            {"name": "Blend", "type": "float", "value": blend},
                        ]
                    },
                },
                # Feed the result back for the next frame BEFORE warping. The
                # accumulation buffer has to hold the straight image: warp it
                # on the way in and every frame re-warps the last one, which
                # compounds into mush within a second.
                blit("swap", "prev"),
                {
                    "vertex_shader": "minecraft:post/blit",
                    "fragment_shader": f"{NS}:post/trip_warp",
                    "inputs": [{"sampler_name": "In", "target": "swap", "bilinear": True}],
                    "output": "minecraft:main",
                    "uniforms": {
                        "TripConfig": [
                            {"name": "Warp", "type": "float",
                             "value": round(warp * character[strain][0], 5)},
                            {"name": "Swirl", "type": "float",
                             "value": round(swirl * character[strain][1], 4)},
                            {"name": "Split", "type": "float",
                             "value": round(split * character[strain][2], 5)},
                            {"name": "Spin", "type": "float",
                             "value": round(spin * character[strain][3], 2)},
                            {"name": "Pulse", "type": "float",
                             "value": round(pulse * character[strain][4], 3)},
                            {"name": "Mirror", "type": "float",
                             "value": float(wilds.get(strain, [(0, 0, 0, 0)] * 3)[band][0])},
                            {"name": "Ghost", "type": "float",
                             "value": float(wilds.get(strain, [(0, 0, 0, 0)] * 3)[band][1])},
                            {"name": "Echo", "type": "float",
                             "value": float(wilds.get(strain, [(0, 0, 0, 0)] * 3)[band][2])},
                            {"name": "Poster", "type": "float",
                             "value": float(wilds.get(strain, [(0, 0, 0, 0)] * 3)[band][3])},
                            {"name": "Sat", "type": "float",
                             "value": sat.get(strain, 1.0)},
                        ]
                    },
                },
            ],
        })

    return


def _unused_gaussian_blur() -> None:
    """Superseded by the accumulation blur above -- kept only as a worked
    example of driving vanilla's box_blur with a real radius, since vanilla's
    own blur.json ships at Radius 0 and is useless as a reference."""
    radii = {"haze": 3.0, "kush": 6.0, "purp": 4.5}

    # One axis per file, not both. A blur along ONE axis is a directional smear
    # -- that's what motion blur looks like when you whip your head sideways.
    # Blurring both axes is just soft-focus and reads as bad eyesight instead.
    axes = {"h": [1.0, 0.0], "v": [0.0, 1.0]}

    for strain, radius in radii.items():
        for axis, direction in axes.items():
            put(f"assets/{NS}/post_effect/high_blur_{strain}_{axis}.json", {
                "targets": {"swap": {}},
                "passes": [
                    {
                        "vertex_shader": "minecraft:post/blur",
                        "fragment_shader": "minecraft:post/box_blur",
                        "inputs": [{
                            "sampler_name": "In",
                            "target": "minecraft:main",
                            "bilinear": True,
                        }],
                        "output": "swap",
                        "uniforms": {
                            "BlurConfig": [
                                {"name": "BlurDir", "type": "vec2", "value": direction},
                                {"name": "Radius", "type": "float", "value": radius},
                            ]
                        },
                    },
                    {
                        "vertex_shader": "minecraft:post/blit",
                        "fragment_shader": "minecraft:post/blit",
                        "inputs": [{
                            "sampler_name": "In",
                            "target": "swap",
                            "bilinear": False,
                        }],
                        "output": "minecraft:main",
                    },
                ],
            })


def lang() -> None:
    entries = {
        "block.trapcraft.drying_rack": "Drying Rack",
        "block.trapcraft.wild_cannabis": "Wild Cannabis",
        "item.trapcraft.miners_hammer": "Miner's Hammer",
        "block.trapcraft.coca_crop": "Coca Bush",
        "item.trapcraft.coca_seeds": "Coca Seeds",
        "item.trapcraft.coca_leaves": "Coca Leaves",
        "item.trapcraft.coca_paste": "Coca Paste",
        "item.trapcraft.coca_powder": "Powder",
        "block.trapcraft.leaf_press": "Leaf Press",
        "item.trapcraft.leaf_press": "Leaf Press",
        "block.trapcraft.refiner": "Refiner",
        "item.trapcraft.refiner": "Refiner",
        "effect.trapcraft.wired": "Wired",
        "block.trapcraft.bong": "Bong",
        "item.trapcraft.bong": "Bong",
        "block.trapcraft.gravity_bong": "T\u0142ok",
        "item.trapcraft.gravity_bong": "T\u0142ok",
        "item.trapcraft.drying_rack": "Drying Rack",
        "block.trapcraft.mixing_station": "Mixing Station",
        "item.trapcraft.mixing_station": "Mixing Station",
        # Both are normally renamed per-blend by the component, so these only
        # show on a /give with no mix attached.
        "item.trapcraft.blend_bud": "Blend Bud",
        "item.trapcraft.blend_joint": "Blend Joint",
        "item.trapcraft.nerve_tonic": "Nerve Tonic",
        "item.trapcraft.ledger": "The Ledger",
        "item.trapcraft.wallet": "Wallet",
        # Renamed per-casino by the component; this only shows on a
        # freshly crafted, unsigned one.
        "item.trapcraft.casino_card": "Casino Licence",
        "block.trapcraft.roulette": "Roulette Table",
        "block.trapcraft.plinko": "The Drop",
        "block.trapcraft.climb": "The Climb",
        "block.trapcraft.toss": "Coin Toss",
        "item.trapcraft.toss": "Coin Toss",
        "block.trapcraft.blackjack": "Blackjack",
        "block.trapcraft.scratch": "Scratchers",
        "block.trapcraft.casino_bar": "The Bar",
        "item.trapcraft.casino_bar": "The Bar",
        "item.trapcraft.scratch": "Scratchers",
        "item.trapcraft.blackjack": "Blackjack",
        "item.trapcraft.climb": "The Climb",
        "item.trapcraft.plinko": "The Drop",
        "item.trapcraft.roulette": "Roulette Table",
        "item.trapcraft.burner_phone": "Burner Phone",
        "block.trapcraft.market_stall": "Market Stall",
        "item.trapcraft.market_stall": "Market Stall",
        "block.trapcraft.mailbox": "Mailbox",
        "item.trapcraft.mailbox": "Mailbox",
        "block.trapcraft.city_vault": "City Vault",
        "item.trapcraft.city_vault": "City Vault",
        "block.trapcraft.slot_machine": "Lucky Streak",
        "item.trapcraft.slot_machine": "Lucky Streak",
        "effect.trapcraft.baked": "Baked",
        "effect.trapcraft.tolerance": "Tolerance",
        "entity.minecraft.villager.trapcraft.dealer": "Dealer",
    }
    for strain, nice in STRAINS.items():
        entries[f"block.trapcraft.cannabis_crop_{strain}"] = f"{nice} Plant"
        entries[f"item.trapcraft.seeds_{strain}"] = f"{nice} Seeds"
        entries[f"item.trapcraft.raw_bud_{strain}"] = f"Fresh {nice} Bud"
        entries[f"item.trapcraft.dried_bud_{strain}"] = f"Cured {nice} Bud"
        entries[f"item.trapcraft.joint_{strain}"] = f"{nice} Joint"
    put(f"assets/{NS}/lang/en_us.json", dict(sorted(entries.items())))


def loot_tables() -> None:
    for strain in STRAINS:
        mature = {
            "condition": "minecraft:block_state_property",
            "block": f"{NS}:cannabis_crop_{strain}",
            "properties": {"age": str(MAX_AGE)},
        }
        put(f"data/{NS}/loot_table/blocks/cannabis_crop_{strain}.json", {
            "type": "minecraft:block",
            "pools": [
                # Always returns the seed, mature or not -- losing your only
                # seed to a misclick is a bad time on a friends server.
                {
                    "rolls": 1,
                    "entries": [{"type": "minecraft:item", "name": f"{NS}:seeds_{strain}"}],
                },
                {
                    "rolls": 1,
                    "conditions": [mature],
                    "entries": [{
                        "type": "minecraft:item",
                        "name": f"{NS}:raw_bud_{strain}",
                        "functions": [{
                            "function": "minecraft:set_count",
                            "count": {"type": "minecraft:uniform", "min": 1, "max": 3},
                        }],
                    }],
                },
                {
                    "rolls": 1,
                    "conditions": [mature],
                    "entries": [{
                        "type": "minecraft:item",
                        "name": f"{NS}:seeds_{strain}",
                        "functions": [{
                            "function": "minecraft:set_count",
                            "count": {"type": "minecraft:uniform", "min": 0, "max": 2},
                        }],
                    }],
                },
            ],
        })

    # Wild plants give a random strain -- picking your phenotype is what the
    # traders are for. One pool, three evenly weighted entries.
    put(f"data/{NS}/loot_table/blocks/wild_cannabis.json", {
        "type": "minecraft:block",
        "pools": [{
            "rolls": 1,
            "entries": [
                {
                    "type": "minecraft:item",
                    "name": f"{NS}:seeds_{strain}",
                    "weight": 1,
                    "functions": [{
                        "function": "minecraft:set_count",
                        "count": {"type": "minecraft:uniform", "min": 1, "max": 2},
                    }],
                }
                for strain in STRAINS
            ],
        }],
    })

    put(f"data/{NS}/loot_table/blocks/drying_rack.json", {
        "type": "minecraft:block",
        "pools": [{
            "rolls": 1,
            "entries": [{"type": "minecraft:item", "name": f"{NS}:drying_rack"}],
        }],
    })


def worldgen() -> None:
    """Wild patches. Registered to biomes from Java (BiomeModifications)."""
    wild = {"Name": f"{NS}:wild_cannabis"}

    put(f"data/{NS}/worldgen/configured_feature/wild_cannabis.json", {
        "type": "minecraft:random_patch",
        "config": {
            "tries": 12,
            "xz_spread": 7,
            "y_spread": 3,
            "feature": {
                "feature": {
                    "type": "minecraft:simple_block",
                    "config": {
                        "to_place": {
                            "type": "minecraft:simple_state_provider",
                            "state": wild,
                        }
                    },
                },
                # Without would_survive the patch happily places plants on
                # stone and water, and they pop off on the next block update.
                "placement": [{
                    "type": "minecraft:block_predicate_filter",
                    "predicate": {
                        "type": "minecraft:would_survive",
                        "state": wild,
                        "offset": [0, 0, 0],
                    },
                }],
            },
        },
    })

    put(f"data/{NS}/worldgen/placed_feature/wild_cannabis.json", {
        "feature": f"{NS}:wild_cannabis",
        "placement": [
            # 1 in 14 chunks in eligible biomes -- a find, not scenery.
            {"type": "minecraft:rarity_filter", "chance": 14},
            {"type": "minecraft:in_square"},
            {"type": "minecraft:heightmap", "heightmap": "WORLD_SURFACE_WG"},
            {"type": "minecraft:biome"},
        ],
    })


COCA_ITEMS = ["coca_seeds", "coca_leaves", "coca_paste", "coca_powder"]


def press_model(progress: int | None) -> dict:
    """The leaf press as a screw press that visibly presses.

    Both machines were cube_bottom_top with a picture of themselves painted on
    each face -- the same flat problem the bong and tlok were rebuilt to fix,
    and the last two places in the pack still doing it.

    CLOSED, not an open frame, for the same reason as the drying rack: Polymer
    serves this on a FULL_BLOCK carrier, so the client treats the volume as
    solid and any gap shows you a world lit as though the block were still
    there. Depth comes from a recess between proud corner posts, between a
    solid plinth and a solid lid.

    The stage is in the geometry rather than the texture: the platen descends
    and the pulp under it gets thinner, so a press mid-batch reads as a machine
    doing work instead of a box with a different picture on it.
    """
    loaded = progress is not None
    # Pulp thins as the platen comes down; empty parks the platen at the top.
    pulp_top = 3.0 + (3.0 - progress * 0.8) if loaded else 3.0
    platen_y = pulp_top if loaded else 10.5

    els = [
        box([0, 0, 0], [16, 3, 16], "stone", up="stone", down="stone"),   # plinth
        box([0, 13, 0], [16, 16, 16], "wood", up="wood", down="wood"),    # lid
        # Corner posts, proud of the core so the recess between them reads.
        box([0, 3, 0], [3.5, 13, 3.5], "wood"),
        box([12.5, 3, 0], [16, 13, 3.5], "wood"),
        box([0, 3, 12.5], [3.5, 13, 16], "wood"),
        box([12.5, 3, 12.5], [16, 13, 16], "wood"),
        # Dark interior above the platen -- this is the piece that keeps the
        # block solid, and reading as shadow is exactly what it should do.
        box([3.5, platen_y + 1.5, 3.5], [12.5, 13, 12.5], "void"),
        # The platen itself, standing proud so it catches the light.
        box([3.2, platen_y, 3.2], [12.8, platen_y + 1.5, 12.8], "iron"),
    ]
    if loaded:
        els.append(box([3.4, 3, 3.4], [12.6, pulp_top, 12.6], "pulp"))
    else:
        els.append(box([3.5, 3, 3.5], [12.5, platen_y, 12.5], "void"))

    # Screw boss and handle on the lid, so the top isn't a blank slab.
    els.append(box([6, 16, 6], [10, 17, 10], "iron"))
    els.append(box([2.5, 16.2, 7.2], [13.5, 16.8, 8.8], "iron"))

    return {
        "parent": "minecraft:block/block",
        "ambientocclusion": False,
        "textures": {
            "stone": f"{NS}:block/press_stone",
            "wood": f"{NS}:block/press_wood",
            "iron": f"{NS}:block/press_iron",
            "pulp": f"{NS}:block/press_pulp",
            "void": f"{NS}:block/press_void",
            "particle": f"{NS}:block/press_wood",
        },
        "elements": els,
    }


def refiner_model(progress: int | None) -> dict:
    """The refiner as a copper retort over a firebox.

    Same closed-shell rule as the press. The heat lives in an ember band around
    the firebox that brightens with progress and goes dull at BURNT, so you can
    read the state from across the room -- which is the whole point of a
    machine that can be ruined by leaving it too long.
    """
    ember = "refiner_ember_idle" if progress is None else f"refiner_ember_{progress}"

    els = [
        box([0, 0, 0], [16, 5, 16], "brick", up="brick", down="brick"),    # firebox
        box([0, 14, 0], [16, 16, 16], "copper", up="copper", down="copper"),  # lid
        # Ember band, proud of the firebox so it glows round the whole base.
        box([-0.05, 1.5, -0.05], [16.05, 3.2, 16.05], "ember"),
        # Corner posts and the solid vessel core between them.
        box([0, 5, 0], [3, 14, 3], "copper"),
        box([13, 5, 0], [16, 14, 3], "copper"),
        box([0, 5, 13], [3, 14, 16], "copper"),
        box([13, 5, 13], [16, 14, 16], "copper"),
        box([3, 5, 3], [13, 14, 13], "copper"),
        # Sight glass banding the vessel, proud of the core.
        box([2.8, 7, 2.8], [13.2, 10, 13.2], "glass"),
        # Condenser arm over the top and a spout back down into the recess.
        box([6.5, 16, 6.5], [9.5, 18, 9.5], "copper"),
        box([9.5, 16.4, 7.2], [14.5, 17.6, 8.8], "copper"),
        box([13.2, 12, 7.2], [14.8, 16.4, 8.8], "copper"),
    ]

    return {
        "parent": "minecraft:block/block",
        "ambientocclusion": False,
        "textures": {
            "brick": f"{NS}:block/refiner_brick",
            "copper": f"{NS}:block/refiner_copper",
            "glass": f"{NS}:block/refiner_glass",
            "ember": f"{NS}:block/{ember}",
            "particle": f"{NS}:block/refiner_brick",
        },
        "elements": els,
    }


def coca_assets() -> None:
    """Crop stages, the two machines, lang and recipes for the coca line."""
    # Same construction as the cannabis plants, but coca never grows buds --
    # the harvest is the leaves, so the mature stage just gets more of them.
    for age in range(4):
        put(f"assets/{NS}/models/block/coca_crop_age{age}.json",
            plant_model(age, f"{NS}:block/coca_plant_leaf", None))

    put(f"assets/{NS}/models/block/leaf_press_empty.json", press_model(None))
    for step in range(4):
        put(f"assets/{NS}/models/block/leaf_press_{step}.json", press_model(step))
    put(f"assets/{NS}/models/block/refiner_idle.json", refiner_model(None))
    for step in range(5):
        put(f"assets/{NS}/models/block/refiner_{step}.json", refiner_model(step))

    for name in COCA_ITEMS:
        put(f"assets/{NS}/models/item/{name}.json", {
            "parent": "minecraft:item/generated",
            "textures": {"layer0": f"{NS}:item/{name}"},
        })
    put(f"assets/{NS}/models/item/leaf_press.json", {"parent": f"{NS}:block/leaf_press_empty"})
    put(f"assets/{NS}/models/item/refiner.json", {"parent": f"{NS}:block/refiner_idle"})
    for name in COCA_ITEMS + ["leaf_press", "refiner"]:
        put(f"assets/{NS}/items/{name}.json", {
            "model": {"type": "minecraft:model", "model": f"{NS}:item/{name}"},
        })

    put(f"data/{NS}/loot_table/blocks/coca_crop.json", {
        "type": "minecraft:block",
        "pools": [{"rolls": 1, "entries": [
            {"type": "minecraft:item", "name": f"{NS}:coca_seeds"}]}],
    })
    for block in ("leaf_press", "refiner"):
        put(f"data/{NS}/loot_table/blocks/{block}.json", {
            "type": "minecraft:block",
            "pools": [{"rolls": 1, "entries": [
                {"type": "minecraft:item", "name": f"{NS}:{block}"}]}],
        })

    put(f"data/{NS}/recipe/leaf_press.json", {
        "type": "minecraft:crafting_shaped",
        "category": "misc",
        "pattern": ["LLL", "SIS", "SSS"],
        # #minecraft:logs, not oak specifically. A recipe that silently refuses
        # spruce is indistinguishable from a recipe that doesn't exist, and the
        # guide only ever said "logs".
        "key": {"L": "minecraft:smooth_stone", "S": "#minecraft:logs",
                "I": "minecraft:iron_ingot"},
        "result": {"id": f"{NS}:leaf_press", "count": 1},
    })
    put(f"data/{NS}/recipe/refiner.json", {
        "type": "minecraft:crafting_shaped",
        "category": "misc",
        "pattern": ["III", "IBI", "CCC"],
        "key": {"I": "minecraft:iron_ingot", "B": "minecraft:blaze_rod",
                "C": "minecraft:copper_block"},
        "result": {"id": f"{NS}:refiner", "count": 1},
    })


def mixing_station_assets() -> None:
    """The station block plus the two blend items it feeds."""
    put(f"assets/{NS}/models/block/mixing_station.json", {
        "parent": "minecraft:block/cube_bottom_top",
        "textures": {
            "top": f"{NS}:block/mixing_station_top",
            "bottom": f"{NS}:block/mixing_station_side",
            "side": f"{NS}:block/mixing_station_side",
            "particle": f"{NS}:block/mixing_station_side",
        },
    })
    put(f"assets/{NS}/models/item/mixing_station.json", {
        "parent": f"{NS}:block/mixing_station",
    })
    put(f"assets/{NS}/items/mixing_station.json", {
        "model": {"type": "minecraft:model", "model": f"{NS}:item/mixing_station"},
    })
    put(f"data/{NS}/loot_table/blocks/mixing_station.json", {
        "type": "minecraft:block",
        "pools": [{"rolls": 1, "entries": [
            {"type": "minecraft:item", "name": f"{NS}:mixing_station"}]}],
    })

    for item in ("blend_bud", "blend_joint"):
        put(f"assets/{NS}/models/item/{item}.json", {
            "parent": "minecraft:item/generated",
            "textures": {"layer0": f"{NS}:item/{item}"},
        })
        put(f"assets/{NS}/items/{item}.json", {
            "model": {"type": "minecraft:model", "model": f"{NS}:item/{item}"},
        })

    put(f"data/{NS}/recipe/mixing_station.json", {
        "type": "minecraft:crafting_shaped",
        "category": "misc",
        "pattern": ["BGB", "CIC", "LLL"],
        "key": {"B": "minecraft:glass_bottle", "G": "minecraft:bowl",
                "C": "minecraft:copper_ingot", "I": "minecraft:iron_ingot",
                # Any log -- see the note on the leaf press recipe.
                "L": "#minecraft:logs"},
        "result": {"id": f"{NS}:mixing_station", "count": 1},
    })

    # crafting_transmute, same reason as the per-strain joints: a shapeless
    # recipe builds a fresh stack and would drop the blend component, turning
    # every rolled mix into a blank joint with no mix on it at all.
    put(f"data/{NS}/recipe/blend_joint.json", {
        "type": "minecraft:crafting_transmute",
        "category": "misc",
        "input": f"{NS}:blend_bud",
        "material": "minecraft:paper",
        "result": {"id": f"{NS}:blend_joint"},
    })


DEVICES = ["bong_dry", "bong_wet", "bong_loaded"] + [f"gravity_bong_{i}" for i in range(5)]


def box(frm, to, tex, up=None, down=None):
    """One cuboid, same material all round unless a cap is overridden.

    UVs are left off so Minecraft derives them from the element's own size.
    That keeps the textures tiling at a consistent scale whatever the box is,
    which is the whole reason the glassware materials are patterns rather than
    pictures -- a hand-placed UV would stretch differently on every part.
    """
    faces = {}
    for side in ("north", "south", "east", "west"):
        faces[side] = {"texture": f"#{tex}"}
    faces["up"] = {"texture": f"#{up or tex}"}
    faces["down"] = {"texture": f"#{down or tex}"}
    return {"from": frm, "to": to, "faces": faces}


def tlok_model(stage: int) -> dict:
    """The tlok as an actual bottle standing in a bucket.

    Five stages that read as one object doing something, rather than five
    pictures. The bottle RISES out of the water as it fills, because that is
    what a gravity bong physically does -- pulling it up is what draws the
    smoke in. Stages 3 and 4 sit a notch higher than 0-2 for exactly that
    reason, and the interior swaps water for smoke at the same moment.

    Minecraft elements are axis-aligned boxes only, so the taper from body to
    neck is four stacked boxes rather than a curve. At block scale that reads
    as a bottle silhouette; a real cone would need an entity model and a whole
    renderer to go with it.
    """
    water = stage >= 1                  # bucket filled
    lifted = stage in (3, 4)            # pulled up, smoke inside
    loaded = stage >= 2                 # bud in the bowl
    lift = 1.0 if lifted else 0.0

    def y(v):
        return v + lift

    # The fill level is GEOMETRY -- the lower part of the body is a separate box
    # wearing the contents as its own skin -- rather than an inner box seen
    # through a translucent wall. Polymer serves these on a carrier block whose
    # render layer we don't control, and on a cutout layer partial alpha snaps
    # to fully opaque, which would have hidden an inner box completely. This
    # way the water line reads the same however the client decides to draw it.
    if lifted:
        fill, level = ("smoke_stale" if stage == 4 else "smoke"), 8.5
    elif water:
        fill, level = "water", 7.0
    else:
        fill, level = None, 2.0

    els = [
        # The bucket. Its top face is water once you've filled it, which is the
        # cheapest honest way to show a filled tub: you only ever see the top.
        box([2, 0, 2], [14, 4, 14], "basin", up="water" if water else "basin"),
    ]
    if fill:
        els.append(box([4, y(2), 4], [12, y(level), 12], fill))
    els += [
        # Bottle: body above the fill line, shoulder, neck, cap.
        box([4, y(level), 4], [12, y(9), 12], "plastic"),
        box([5, y(9), 5], [11, y(11), 11], "plastic"),
        box([6.5, y(11), 6.5], [9.5, y(12.5), 9.5], "plastic"),
        box([6, y(12.5), 6], [10, y(14), 10], "cap"),
    ]

    if loaded:
        # The packed bowl, sat on the cap.
        els.append(box([7, y(14), 7], [9, y(15), 9], "bowl"))

    textures = {
        "basin": f"{NS}:block/tlok_basin",
        "plastic": f"{NS}:block/tlok_plastic",
        "water": f"{NS}:block/tlok_water",
        "smoke": f"{NS}:block/tlok_smoke",
        "smoke_stale": f"{NS}:block/tlok_smoke_stale",
        "cap": f"{NS}:block/tlok_cap",
        "bowl": f"{NS}:block/tlok_bowl",
        "particle": f"{NS}:block/tlok_plastic",
    }
    return {
        "parent": "minecraft:block/block",
        # Off because the model is a thin stack of small boxes -- vanilla AO
        # bands them with dark seams at every join.
        "ambientocclusion": False,
        "textures": textures,
        "elements": els,
    }


def bong_model(stage: str) -> dict:
    """Beaker base, straight tube, mouthpiece, and a downstem out the side."""
    # Same trick as the tlok: the water is the skin of the lower tube section,
    # not something seen through the glass.
    level = 7 if stage in ("wet", "loaded") else 3
    els = [
        box([4, 0, 4], [12, 3, 12], "base"),          # weighted beaker foot
        box([5.5, 12, 5.5], [10.5, 13.5, 10.5], "glass"),   # mouthpiece flare
        box([11, 5, 7], [13.5, 7, 9], "glass"),       # downstem, out and down
        box([12.5, 7, 6.5], [15, 9.5, 9.5], "bowl" if stage == "loaded" else "glass"),
        box([6, level, 6], [10, 12, 10], "glass"),    # tube above the water
    ]
    if level > 3:
        els.append(box([6, 3, 6], [10, level, 10], "water"))
    return {
        "parent": "minecraft:block/block",
        "ambientocclusion": False,
        "textures": {
            "base": f"{NS}:block/bong_base",
            "glass": f"{NS}:block/bong_glass",
            "water": f"{NS}:block/tlok_water",
            "bowl": f"{NS}:block/tlok_bowl",
            "particle": f"{NS}:block/bong_glass",
        },
        "elements": els,
    }


def device_assets() -> None:
    """Bong and tlok as real geometry.

    These were cross models -- two flat quads in an X, the same trick vanilla
    uses for grass. That's why they looked papery next to everything else: a
    bottle drawn on a billboard has no bottle in it. Both are now built out of
    boxes with translucent walls you can see the contents through.
    """
    for stage in range(5):
        put(f"assets/{NS}/models/block/gravity_bong_{stage}.json", tlok_model(stage))
    for stage in ("dry", "wet", "loaded"):
        put(f"assets/{NS}/models/block/bong_{stage}.json", bong_model(stage))

    for item, tex in (("bong", "bong_wet"), ("gravity_bong", "gravity_bong_1")):
        # Inherit the block model rather than flattening it to a sprite, so the
        # thing in your hand is the same bottle you're about to place.
        put(f"assets/{NS}/models/item/{item}.json", {
            "parent": f"{NS}:block/{tex}",
        })
        put(f"assets/{NS}/items/{item}.json", {
            "model": {"type": "minecraft:model", "model": f"{NS}:item/{item}"},
        })
        put(f"data/{NS}/loot_table/blocks/{item}.json", {
            "type": "minecraft:block",
            "pools": [{"rolls": 1, "entries": [
                {"type": "minecraft:item", "name": f"{NS}:{item}"}]}],
        })

    mixing_station_assets()

    put(f"data/{NS}/recipe/bong.json", {
        "type": "minecraft:crafting_shaped",
        "category": "misc",
        "pattern": [" B ", "GBG", "GGG"],
        "key": {"G": "minecraft:glass", "B": "minecraft:bamboo"},
        "result": {"id": f"{NS}:bong", "count": 1},
    })
    # The tlok is the improvised one: a bottle and a bucket, nothing fancy.
    put(f"data/{NS}/recipe/gravity_bong.json", {
        "type": "minecraft:crafting_shapeless",
        "category": "misc",
        "ingredients": ["minecraft:glass_bottle", "minecraft:bucket",
                        "minecraft:bamboo", "minecraft:paper"],
        "result": {"id": f"{NS}:gravity_bong", "count": 1},
    })


# Vanilla minecraft:block/block display transforms: (rotation, translation,
# scale) per slot. Everything here is expressed RELATIVE to these, because a
# block item at gui scale 0.625 is what a Minecraft slot is drawn to expect.
# The first attempt used absolute scales around 1.7 -- nearly three times the
# vanilla value -- and the items burst out of their slots.
_BLOCK_DISPLAY = {
    "gui": ((30, 225, 0), (0, 0, 0), 0.625),
    "ground": ((0, 0, 0), (0, 3, 0), 0.25),
    "fixed": ((0, 0, 0), (0, 0, 0), 0.5),
    "thirdperson_righthand": ((75, 45, 0), (0, 2.5, 0), 0.375),
    "thirdperson_lefthand": ((75, 45, 0), (0, 2.5, 0), 0.375),
    "firstperson_righthand": ((0, 45, 0), (0, 0, 0), 0.4),
    "firstperson_lefthand": ((0, 225, 0), (0, 0, 0), 0.4),
    "head": ((0, 0, 0), (0, 14, 0), 1.0),
}


def held(boost: float = 1.0, gui_rotation=None) -> dict:
    """Display transforms for a 3D item, as a nudge on the vanilla block ones.

    `boost` is a multiplier on vanilla's scales, so 1.0 renders exactly like a
    normal block item and 1.15 is a slightly generous one. Keep it near 1 --
    anything much above overflows the slot frame, which is what "bigger" got
    wrong the first time. Reach for bigger GEOMETRY before a bigger boost.

    gui_rotation overrides only the inventory angle, for shapes whose
    silhouette doesn't read at vanilla's default 225 degrees.
    """
    display = {}
    for slot, (rotation, translation, scale) in _BLOCK_DISPLAY.items():
        if slot == "gui" and gui_rotation is not None:
            rotation = gui_rotation
        display[slot] = {
            "rotation": list(rotation),
            "translation": list(translation),
            "scale": [round(scale * boost, 4)] * 3,
        }
    return display


def nerve_tonic_model() -> dict:
    """A stubby apothecary bottle with a cork, not a sprite of one.

    Built like the bong and tlok: the liquid is the skin of the lower section
    rather than something seen through a wall, because a translucent inner box
    inside a translucent outer box z-fights on every client that renders the
    pack on the cutout layer.
    """
    return {
        "parent": "minecraft:block/block",
        "ambientocclusion": False,
        "textures": {
            "glass": f"{NS}:item/tonic_glass",
            "liquid": f"{NS}:item/tonic_liquid",
            "cork": f"{NS}:item/tonic_cork",
            "particle": f"{NS}:item/tonic_liquid",
        },
        "display": held(1.05, gui_rotation=(20, 215, 0)),
        "elements": [
            box([4, 0, 4], [12, 7, 12], "liquid"),          # the dose itself
            box([4, 7, 4], [12, 9.5, 12], "glass"),         # air above it
            box([6, 9.5, 6], [10, 13, 10], "glass"),        # neck
            box([5.5, 13, 5.5], [10.5, 15, 10.5], "cork"),  # stopper
        ],
    }


def nerve_tonic_assets() -> None:
    put(f"assets/{NS}/models/block/nerve_tonic.json", nerve_tonic_model())
    # Inherit the geometry rather than flattening to a sprite, same as the
    # glassware -- a bottle drawn on a billboard has no bottle in it.
    put(f"assets/{NS}/models/item/nerve_tonic.json", {
        "parent": f"{NS}:block/nerve_tonic",
    })
    put(f"assets/{NS}/items/nerve_tonic.json", {
        "model": {"type": "minecraft:model", "model": f"{NS}:item/nerve_tonic"},
    })

    # Honey for the base, sugar to take the edge off, and a flower because
    # every calming brew in every game ever has a flower in it. All three are
    # obtainable within a day of spawning, which is the point: the counterplay
    # to paranoia must not be gated behind the thing that causes it.
    put(f"data/{NS}/recipe/nerve_tonic.json", {
        "type": "minecraft:crafting_shapeless",
        "category": "misc",
        "ingredients": ["minecraft:honey_bottle", "minecraft:sugar",
                        "#minecraft:small_flowers"],
        "result": {"id": f"{NS}:nerve_tonic", "count": 1},
    })


def table_model(top: str, furniture=None) -> dict:
    """A gaming table: four legs, an apron, a felt top in a brass rim, and
    whatever that particular game keeps standing on it.

    It used to be a single textured cube -- a solid block of wood from the
    floor to the felt, which is not a table, it is a crate somebody drew a
    card game on. Legs cost a state from the thin TRANSPARENT_BLOCK pool
    apiece, and three states is what four sides of daylight under a table is
    worth.

    `furniture` is a list of extra elements, so the coin, the card shoe and
    the scratchcard rack are the thing you tell the three games apart by from
    across the room rather than a texture you have to walk up to and read.
    """
    elements = [
        # Four legs, inset from the corners so there is daylight between them.
        box([1.5, 0, 1.5], [4, 11, 4], "leg"),
        box([12, 0, 1.5], [14.5, 11, 4], "leg"),
        box([1.5, 0, 12], [4, 11, 14.5], "leg"),
        box([12, 0, 12], [14.5, 11, 14.5], "leg"),
        # The apron the top sits on, tying the legs together.
        box([1, 11, 1], [15, 13.5, 15], "side", up="side", down="side"),
        # The playing surface, proud of the apron, with the felt on the lid.
        box([0, 13.5, 0], [16, 15.5, 16], "rim", up="top", down="side"),
    ]
    elements.extend(furniture or [])
    return {
        "parent": "minecraft:block/block",
        "ambientocclusion": False,
        "textures": {
            "top": f"{NS}:block/{top}",
            "side": f"{NS}:block/table_side",
            "leg": f"{NS}:block/table_leg",
            "rim": f"{NS}:block/table_rim",
            "coin": f"{NS}:block/toss_coin",
            "shoe": f"{NS}:block/card_shoe",
            "chips": f"{NS}:block/chip_stack",
            "rack": f"{NS}:block/card_rack",
            "shelf": f"{NS}:block/bar_shelf",
            "particle": f"{NS}:block/table_side",
        },
        "elements": elements,
    }


def bar_furniture() -> list:
    """A back shelf of bottles standing up behind the counter.

    The one piece of casino furniture that has to read as somewhere you PUT
    things rather than somewhere you play, because it is the only block on the
    floor the owner has a job at.
    """
    return [
        box([1, 15.5, 12.5], [15, 23, 14.5], "shelf", up="shelf", down="shelf"),
        box([0.5, 22.5, 12], [15.5, 23.8, 15], "rim", up="rim"),
        box([2.5, 15.5, 3], [5, 17.2, 5.5], "chips"),
    ]


def toss_furniture() -> list:
    """A coin standing on its rim, mid-spin, and the stake it was tossed for.

    Upright rather than lying flat: a coin lying on a table is a yellow dot,
    and a coin on its edge is unmistakably a coin about to fall one way or the
    other, which is the entire game.
    """
    return [
        {**box([6.5, 15.5, 7.6], [9.5, 18.5, 8.4], "coin", up="coin", down="coin"),
         "rotation": {"origin": [8, 17, 8], "axis": "y", "angle": 22.5}},
        box([11, 15.5, 10.5], [13.5, 17, 13], "chips"),
    ]


def blackjack_furniture() -> list:
    """The shoe the cards come out of, and two stacks of chips."""
    return [
        box([9.5, 15.5, 2.5], [14, 18, 7], "shoe", up="shoe", down="shoe"),
        box([2.5, 15.5, 10], [5, 17.5, 12.5], "chips"),
        box([5.5, 15.5, 11.5], [8, 16.8, 14], "chips"),
    ]


def scratch_furniture() -> list:
    """A rack of unsold cards standing up at the back of the counter."""
    return [
        box([2.5, 15.5, 2], [13.5, 21, 3.5], "rack", up="rack", down="rack"),
        box([10.5, 15.5, 10], [13, 17, 12.5], "chips"),
    ]


def table_assets(name: str, top: str, pattern, key, furniture=None) -> None:
    put(f"assets/{NS}/models/block/{name}.json", table_model(top, furniture))
    put(f"assets/{NS}/models/item/{name}.json", {"parent": f"{NS}:block/{name}"})
    put(f"assets/{NS}/items/{name}.json", {
        "model": {"type": "minecraft:model", "model": f"{NS}:item/{name}"},
    })
    put(f"assets/{NS}/blockstates/{name}.json", {
        "variants": {"": {"model": f"{NS}:block/{name}"}},
    })
    put(f"data/{NS}/recipe/{name}.json", {
        "type": "minecraft:crafting_shaped",
        "category": "misc",
        "pattern": pattern,
        "key": key,
        "result": {"id": f"{NS}:{name}", "count": 1},
    })
    put(f"data/{NS}/loot_table/blocks/{name}.json", {
        "type": "minecraft:block",
        "pools": [{
            "rolls": 1,
            "entries": [{"type": "minecraft:item", "name": f"{NS}:{name}"}],
            "conditions": [{"condition": "minecraft:survives_explosion"}],
        }],
    })


def climb_model() -> dict:
    """A cabinet with the climb itself running up the front of it.

    The strongbox it used to be was a handsome box that told you nothing: the
    game is six rungs and a bad door on each, and a chest says "loot", not
    "how far dare you go". Four brass treads now step up and across the face
    with a lamp on the end of each, which is the game drawn on the outside of
    the machine.

    The BODY still fills the cube exactly, so the carrier can honestly say
    FULL_BLOCK -- every tread, lamp and post below is bolted to the OUTSIDE of
    it. Proud geometry is free; hollow geometry costs a blockstate.
    """
    # Four rungs stepping up and to the right, each with its lamp on the end.
    treads = []
    for rung in range(4):
        low = 2.6 + rung * 3.0
        left = 1.4 + rung * 2.4
        treads.append(box([left, low, -1.3], [left + 5.2, low + 1.6, 0.4],
                          "step", up="step", down="step"))
        treads.append(box([left + 5.2, low + 0.1, -1.1], [left + 6.6, low + 1.5, 0.2],
                          "lamp", up="lamp", down="lamp"))
    return {
        "parent": "minecraft:block/block",
        "ambientocclusion": False,
        "textures": {
            "face": f"{NS}:block/climb_face",
            "plate": f"{NS}:block/climb_plate",
            "lid": f"{NS}:block/climb_lid",
            "step": f"{NS}:block/climb_step",
            "lamp": f"{NS}:block/climb_lamp",
            "rim": f"{NS}:block/table_rim",
            "particle": f"{NS}:block/climb_plate",
        },
        "elements": [
            {
                "from": [0, 0, 0],
                "to": [16, 16, 16],
                "faces": {
                    "north": {"texture": "#face", "uv": [0, 0, 16, 16]},
                    "south": {"texture": "#plate", "uv": [0, 0, 16, 16]},
                    "east": {"texture": "#plate", "uv": [0, 0, 16, 16]},
                    "west": {"texture": "#plate", "uv": [0, 0, 16, 16]},
                    "up": {"texture": "#lid", "uv": [0, 0, 16, 16]},
                    "down": {"texture": "#plate", "uv": [0, 0, 16, 16]},
                },
            },
            box([-0.6, 0, -0.6], [16.6, 2, 16.6], "rim", down="rim"),      # plinth
            box([-0.6, 14.6, -0.6], [16.6, 16.6, 16.6], "rim", up="lid"),  # crown
            box([-0.4, 2, -0.4], [1.2, 14.6, 1.2], "rim"),                 # corner posts
            box([14.8, 2, -0.4], [16.4, 14.6, 1.2], "rim"),
            box([-0.4, 2, 14.8], [1.2, 14.6, 16.4], "rim"),
            box([14.8, 2, 14.8], [16.4, 14.6, 16.4], "rim"),
            # The top lamp, bigger than the rest: the sixth rung is the one
            # everybody is looking at from the moment they sit down.
            box([6, 15.9, 5], [10, 18.4, 9], "lamp", up="lamp", down="lamp"),
        ] + treads,
    }


def award(name, title, description, icon, parent, *, hidden=False,
          frame="task", trigger=None) -> None:
    """One advancement.

    `trigger` None means the moment can't be seen by any vanilla criterion, so
    it gets `impossible` and TrapAwards.grant() fires it from code. Anything
    that is really "you own one of these" uses inventory_changed instead and
    needs no Java at all.
    """
    criteria = {"granted": {"trigger": "minecraft:impossible"}} if trigger is None else trigger
    body = {
        "display": {
            "icon": {"id": icon},
            "title": title,
            "description": description,
            "frame": frame,
            "show_toast": True,
            "announce_to_chat": True,
            "hidden": hidden,
        },
        "criteria": criteria,
        "requirements": [list(criteria.keys())],
    }
    if parent is None:
        body["display"]["background"] = f"{NS}:textures/block/stall_counter.png"
    else:
        body["parent"] = f"{NS}:{parent}"
    put(f"data/{NS}/advancement/{name}.json", body)


def has(*items) -> dict:
    """An inventory_changed criterion for owning any of these."""
    return {"got": {"trigger": "minecraft:inventory_changed",
                    "conditions": {"items": [{"items": list(items)}]}}}


def advancements() -> None:
    award("root", "Everybody Eats", "Get hold of some seeds and start something.",
          f"{NS}:seeds_kush", None, trigger=has(f"{NS}:seeds_kush", f"{NS}:seeds_haze",
                                                f"{NS}:seeds_purp", f"{NS}:seeds_diesel",
                                                f"{NS}:seeds_sunset", f"{NS}:seeds_midnight"))

    award("cured", "Patience", "Dry a bud properly instead of smoking it wet.",
          f"{NS}:dried_bud_kush", "root",
          trigger=has(*[f"{NS}:dried_bud_{s}" for s in
                        ("kush", "haze", "purp", "diesel", "sunset", "midnight")]))
    award("rolled", "Rolled", "Turn a bud into something you can actually sell.",
          f"{NS}:joint_kush", "cured",
          trigger=has(*[f"{NS}:joint_{s}" for s in
                        ("kush", "haze", "purp", "diesel", "sunset", "midnight")]))
    award("blended", "House Blend", "Put two strains through the mixing station.",
          f"{NS}:blend_bud", "rolled", trigger=has(f"{NS}:blend_bud", f"{NS}:blend_joint"))
    award("named_blend", "By Name", "Make a blend somebody has already named.",
          f"{NS}:blend_joint", "blended", frame="goal")

    award("refined", "Refined", "Take the coca line all the way to powder.",
          f"{NS}:coca_powder", "root", trigger=has(f"{NS}:coca_powder"))

    award("open", "Open For Business", "Set up a market stall.",
          f"{NS}:market_stall", "root", trigger=has(f"{NS}:market_stall"))
    award("address", "An Address", "Put a mailbox on a room somebody could live in.",
          f"{NS}:mailbox", "root", trigger=has(f"{NS}:mailbox"))
    award("founded", "Founded", "Put the city vault down and start taxing everybody.",
          f"{NS}:city_vault", "root", trigger=has(f"{NS}:city_vault"), frame="goal")
    award("banked", "Banked", "Carry a wallet instead of twenty stacks.",
          f"{NS}:wallet", "open", trigger=has(f"{NS}:wallet"))
    award("liquidation", "Liquidation", "Clear 500 emeralds at the counter in one go.",
          "minecraft:hopper", "open", frame="goal")
    award("mover", "Moved The Market", "Push one item's price by a fifth on your own.",
          "minecraft:emerald_block", "liquidation", frame="challenge")

    award("floor", "The Floor", "Get a machine of your own on the floor.",
          f"{NS}:slot_machine", "root",
          trigger=has(f"{NS}:slot_machine", f"{NS}:roulette", f"{NS}:plinko", f"{NS}:climb"))
    award("jackpot", "Jackpot", "Take ten times your stake off a machine.",
          "minecraft:nether_star", "floor", frame="goal")
    award("nerve", "Nerve", "Walk away from the sixth rung of The Climb.",
          "minecraft:gold_ingot", "floor", frame="goal")
    award("edge", "Edge Case", "Land the ball in an outside slot on The Drop.",
          "minecraft:snowball", "floor", frame="goal")
    award("whole_floor", "House Money", "Win something on all four machines.",
          "minecraft:emerald", "floor", frame="challenge")

    award("followed", "Followed Home",
          "Deal in person once too often and find out who was watching.",
          "minecraft:crossbow", "root", frame="goal")
    award("licence", "Licensed", "Get your hands on a casino licence.",
          f"{NS}:casino_card", "floor", trigger=has(f"{NS}:casino_card"))
    award("broke_the_bank", "Broke The Bank",
          "Win more off one machine than its casino had in the vault.",
          "minecraft:gold_block", "licence", frame="challenge")

    award("rim", "On The Rim", "Call the edge on the coin toss and hit it.",
          "minecraft:nether_star", "floor", frame="challenge")
    award("natural", "Natural", "Get dealt twenty-one on two cards.",
          "minecraft:paper", "floor", frame="goal")

    award("network", "The Network", "Put somebody on the street selling for you.",
          "minecraft:player_head", "root", frame="goal")
    award("kingpin", "Kingpin", "Bring a dealer up to level five.",
          "minecraft:golden_helmet", "network", frame="challenge")

    award("crew", "Payroll", "Take somebody on. Wages start immediately.",
          "minecraft:villager_spawn_egg", "root", frame="goal")
    award("foreman", "Foreman", "Teach one hand everything there is to teach.",
          "minecraft:golden_hoe", "crew", frame="challenge")
    award("raided", "They Found It", "Have a raid walk out with your product.",
          "minecraft:crossbow", "root", frame="goal")
    award("clean", "Nothing To See", "Sit through a raid without losing a gram.",
          "minecraft:barrier", "raided", frame="challenge")


def climb_assets() -> None:
    put(f"assets/{NS}/models/block/climb.json", climb_model())
    put(f"assets/{NS}/models/item/climb.json", {"parent": f"{NS}:block/climb"})
    put(f"assets/{NS}/items/climb.json", {
        "model": {"type": "minecraft:model", "model": f"{NS}:item/climb"},
    })
    put(f"assets/{NS}/blockstates/climb.json", {
        "variants": {"": {"model": f"{NS}:block/climb"}},
    })

    # Iron for the box, gold for the locks, and a tripwire hook because the
    # whole game is about which door is wired.
    put(f"data/{NS}/recipe/climb.json", {
        "type": "minecraft:crafting_shaped",
        "category": "misc",
        "pattern": ["III", "GTG", "III"],
        "key": {
            "I": "minecraft:iron_ingot",
            "G": "minecraft:gold_ingot",
            "T": "minecraft:tripwire_hook",
        },
        "result": {"id": f"{NS}:climb", "count": 1},
    })

    put(f"data/{NS}/loot_table/blocks/climb.json", {
        "type": "minecraft:block",
        "pools": [{
            "rolls": 1,
            "entries": [{"type": "minecraft:item", "name": f"{NS}:climb"}],
            "conditions": [{"condition": "minecraft:survives_explosion"}],
        }],
    })


def plinko_model(upper: bool) -> dict:
    """A tall peg board in a frame, standing against the wall.

    Deliberately shallow front to back -- it is a board, not a cabinet, and
    the ball falls down the FACE of it. Made of a back panel, a raised frame
    around the edge, and side rails, so the pegs sit in a recess you can see
    into rather than being painted on a slab.
    """
    face = "board" if upper else "slots"
    return {
        "parent": "minecraft:block/block",
        "ambientocclusion": False,
        "textures": {
            "board": f"{NS}:block/plinko_board",
            "slots": f"{NS}:block/plinko_slots",
            "frame": f"{NS}:block/plinko_frame",
            "particle": f"{NS}:block/plinko_frame",
        },
        "elements": [
            box([1, 0, 10], [15, 16, 12], face, up="frame", down="frame"),   # the field
            box([0, 0, 9], [1, 16, 13], "frame", up="frame", down="frame"),  # left rail
            box([15, 0, 9], [16, 16, 13], "frame", up="frame", down="frame"),  # right rail
            box([1, 15, 9], [15, 16, 13], "frame", up="frame")               # head rail
            if upper else
            box([1, 0, 9], [15, 1, 13], "frame", down="frame"),              # tray at the foot
            box([0.5, 0, 7.4], [15.5, 3, 9], "frame", up="frame", down="frame"),  # catch lip
            box([0.5, 3, 7.4], [15.5, 3.6, 8.2], "frame", up="frame"),       # lip edge
        ],
    }


def plinko_assets() -> None:
    for half, upper in (("upper", True), ("lower", False)):
        put(f"assets/{NS}/models/block/plinko_{half}.json", plinko_model(upper))
    put(f"assets/{NS}/models/item/plinko.json", {"parent": f"{NS}:block/plinko_lower"})
    put(f"assets/{NS}/items/plinko.json", {
        "model": {"type": "minecraft:model", "model": f"{NS}:item/plinko"},
    })
    put(f"assets/{NS}/blockstates/plinko.json", {
        "variants": {
            "half=lower": {"model": f"{NS}:block/plinko_lower"},
            "half=upper": {"model": f"{NS}:block/plinko_upper"},
        },
    })

    # Iron for the pegs, glass to watch through, planks for the frame and a
    # diamond for the tray. Priced like the other two tables.
    put(f"data/{NS}/recipe/plinko.json", {
        "type": "minecraft:crafting_shaped",
        "category": "misc",
        "pattern": ["PIP", "GDG", "PIP"],
        "key": {
            "P": "#minecraft:planks",
            "I": "minecraft:iron_ingot",
            "G": "minecraft:glass",
            "D": "minecraft:diamond",
        },
        "result": {"id": f"{NS}:plinko", "count": 1},
    })

    # Only the lower half drops, or breaking one gives you two boards.
    put(f"data/{NS}/loot_table/blocks/plinko.json", {
        "type": "minecraft:block",
        "pools": [{
            "rolls": 1,
            "entries": [{"type": "minecraft:item", "name": f"{NS}:plinko"}],
            "conditions": [
                {"condition": "minecraft:survives_explosion"},
                {"condition": "minecraft:block_state_property",
                 "block": f"{NS}:plinko", "properties": {"half": "lower"}},
            ],
        }],
    })


def roulette_model() -> dict:
    """A waist-high table: legs, a felt top, a mahogany rim, and the wheel.

    Built low and wide on purpose. The slot machine next to it is two blocks
    tall, so a table you look DOWN at is what makes a room of both read as a
    casino floor rather than a row of cabinets. The wheel head sits proud of
    the felt with a brass hub, because a flat green square with a picture of a
    wheel on it is a rug.
    """
    return {
        "parent": "minecraft:block/block",
        "ambientocclusion": False,
        "textures": {
            "felt": f"{NS}:block/roulette_felt",
            "rim": f"{NS}:block/roulette_rim",
            "wheel": f"{NS}:block/roulette_wheel",
            "particle": f"{NS}:block/roulette_rim",
        },
        "elements": [
            # Four legs, inset so the table reads as standing rather than as a
            # solid cube painted to look like one.
            box([2, 0, 2], [4, 9, 4], "rim"),
            box([12, 0, 2], [14, 9, 4], "rim"),
            box([2, 0, 12], [4, 9, 14], "rim"),
            box([12, 0, 12], [14, 9, 14], "rim"),
            # The top: felt inside a raised rim.
            box([1, 9, 1], [15, 11, 15], "rim", up="rim", down="rim"),
            box([2, 11, 2], [14, 11.5, 14], "felt", up="felt"),
            # The wheel head, sunk into the felt and standing proud of it.
            box([4, 11.5, 4], [12, 13, 12], "wheel", up="wheel", down="wheel"),
            box([6.5, 13, 6.5], [9.5, 13.8, 9.5], "rim", up="rim"),
            # The dealer's chip rack along one edge -- the detail that says
            # somebody works this table rather than it being scenery.
            box([2.5, 11.5, 1.6], [13.5, 13.2, 3.2], "rim", up="rim", down="rim"),
            box([3.5, 13.2, 1.9], [5.5, 14.2, 2.9], "wheel", up="wheel"),
            box([7, 13.2, 1.9], [9, 14.2, 2.9], "wheel", up="wheel"),
            box([10.5, 13.2, 1.9], [12.5, 14.2, 2.9], "wheel", up="wheel"),
        ],
    }


def roulette_assets() -> None:
    put(f"assets/{NS}/models/block/roulette.json", roulette_model())
    put(f"assets/{NS}/models/item/roulette.json", {"parent": f"{NS}:block/roulette"})
    put(f"assets/{NS}/items/roulette.json", {
        "model": {"type": "minecraft:model", "model": f"{NS}:item/roulette"},
    })
    put(f"assets/{NS}/blockstates/roulette.json", {
        "variants": {"": {"model": f"{NS}:block/roulette"}},
    })

    # Wool for the felt, planks for the table, iron for the wheel bearing and
    # gold for the trim. Costs about what the slot machine does -- a casino
    # should be a project, not a starter build.
    put(f"data/{NS}/recipe/roulette.json", {
        "type": "minecraft:crafting_shaped",
        "category": "misc",
        "pattern": ["GIG", "WWW", "PPP"],
        "key": {
            "G": "minecraft:gold_ingot",
            "I": "minecraft:iron_ingot",
            "W": "minecraft:green_wool",
            "P": "#minecraft:planks",
        },
        "result": {"id": f"{NS}:roulette", "count": 1},
    })

    put(f"data/{NS}/loot_table/blocks/roulette.json", {
        "type": "minecraft:block",
        "pools": [{
            "rolls": 1,
            "entries": [{"type": "minecraft:item", "name": f"{NS}:roulette"}],
            "conditions": [{"condition": "minecraft:survives_explosion"}],
        }],
    })


def wallet_model() -> dict:
    """A fat leather pouch with a brass clasp and an emerald poking out.

    Built as a box rather than a sprite for the same reason as the ledger: a
    flat pouch is a brown rectangle in a hotbar next to every other brown
    rectangle. The depth and the clasp are what make it read as a wallet, and
    the emerald on top is what makes it read as money.
    """
    return {
        "parent": "minecraft:block/block",
        "ambientocclusion": False,
        "textures": {
            "body": f"{NS}:item/wallet_body",
            "flap": f"{NS}:item/wallet_flap",
            "coin": f"{NS}:item/wallet_coin",
            "particle": f"{NS}:item/wallet_body",
        },
        "display": held(1.1, gui_rotation=(30, 225, 0)),
        "elements": [
            box([3, 2, 4], [13, 11, 12], "body", up="body", down="body"),   # the pouch
            box([2.6, 8.5, 3.6], [13.4, 12, 12.4], "flap", up="flap"),      # flap over the top
            box([6.5, 11.5, 7], [9.5, 14.5, 9], "coin", up="coin"),         # emerald poking out
        ],
    }


def wallet_assets() -> None:
    put(f"assets/{NS}/models/block/wallet.json", wallet_model())
    put(f"assets/{NS}/models/item/wallet.json", {"parent": f"{NS}:block/wallet"})
    put(f"assets/{NS}/items/wallet.json", {
        "model": {"type": "minecraft:model", "model": f"{NS}:item/wallet"},
    })

    # Leather for the pouch, string for the seam, gold for the clasp, and one
    # emerald so it costs money to have somewhere to put money. Cheap on
    # purpose: this is quality-of-life, not a reward.
    put(f"data/{NS}/recipe/wallet.json", {
        "type": "minecraft:crafting_shaped",
        "category": "misc",
        "pattern": ["SGS", "LEL", "LLL"],
        "key": {
            "S": "minecraft:string",
            "G": "minecraft:gold_nugget",
            "L": "minecraft:leather",
            "E": "minecraft:emerald",
        },
        "result": {"id": f"{NS}:wallet", "count": 1},
    })


def casino_card_model() -> dict:
    """A thick plastic card lying flat with a raised chip on the corner.

    A card seen face-on in a hotbar is a rectangle, and a rectangle at 16
    pixels is indistinguishable from paper, a map or anybody else's quest
    item. The thickness and the proud chip are what give it a silhouette, and
    the GUI angle is picked so both read at once.
    """
    return {
        "parent": "minecraft:block/block",
        "ambientocclusion": False,
        "textures": {
            "face": f"{NS}:item/card_face",
            "edge": f"{NS}:item/card_edge",
            "chip": f"{NS}:item/card_chip",
            "particle": f"{NS}:item/card_face",
        },
        "display": held(1.2, gui_rotation=(32, 210, 0)),
        "elements": [
            box([2, 6.5, 4], [14, 8, 12], "edge", up="face", down="face"),   # the card
            box([9.5, 8, 5.5], [13, 8.9, 9], "chip", up="chip", down="chip"),  # the chip
        ],
    }


def casino_card_assets() -> None:
    put(f"assets/{NS}/models/block/casino_card.json", casino_card_model())
    put(f"assets/{NS}/models/item/casino_card.json",
        {"parent": f"{NS}:block/casino_card"})
    put(f"assets/{NS}/items/casino_card.json", {
        "model": {"type": "minecraft:model", "model": f"{NS}:item/casino_card"},
    })

    # Gold for the trim, paper for the licence, a diamond for the chip and a
    # block of emeralds for the float you are expected to have before this is
    # any use at all. Deliberately steep: a casino is a business, and the
    # recipe is the only place the cost of starting one can live.
    put(f"data/{NS}/recipe/casino_card.json", {
        "type": "minecraft:crafting_shaped",
        "category": "misc",
        "pattern": ["GPG", "PEP", "GDG"],
        "key": {
            "G": "minecraft:gold_ingot",
            "P": "minecraft:paper",
            "E": "minecraft:emerald_block",
            "D": "minecraft:diamond",
        },
        "result": {"id": f"{NS}:casino_card", "count": 1},
    })


def ledger_model() -> dict:
    """A closed book lying flat with a pencil across it.

    Flat-sprite books read as "generic quest item" in a bar full of other
    books; the pencil is what makes it legible as an index at a glance in a
    hotbar.
    """
    return {
        "parent": "minecraft:block/block",
        "ambientocclusion": False,
        "textures": {
            "cover": f"{NS}:item/ledger_cover",
            "pages": f"{NS}:item/ledger_pages",
            "pencil": f"{NS}:item/ledger_pencil",
            "particle": f"{NS}:item/ledger_cover",
        },
        # Tilted hard in the GUI: a book lying flat is a rectangle from above
        # and unrecognisable at 16 pixels, so it is shown at an angle where the
        # page block and the pencil both read.
        "display": held(1.15, gui_rotation=(35, 215, 0)),
        "elements": [
            box([2, 3.5, 1.5], [14, 6, 14.5], "pages", up="pages"),      # page block
            box([1.5, 6, 1], [14.5, 7.6, 15], "cover", up="cover"),      # front board
            box([1.5, 1.6, 1], [14.5, 3.5, 15], "cover", down="cover"),  # back board
            box([3, 7.6, 7], [13, 9, 8.6], "pencil"),                    # pencil on top
        ],
    }


def ledger_assets() -> None:
    put(f"assets/{NS}/models/block/ledger.json", ledger_model())
    put(f"assets/{NS}/models/item/ledger.json", {"parent": f"{NS}:block/ledger"})
    put(f"assets/{NS}/items/ledger.json", {
        "model": {"type": "minecraft:model", "model": f"{NS}:item/ledger"},
    })

    # Compass for "where", amethyst for "look", book for "index". Mid-game on
    # purpose: this is the payoff for having accumulated enough stuff to lose
    # track of it, not a starter tool.
    put(f"data/{NS}/recipe/ledger.json", {
        "type": "minecraft:crafting_shaped",
        "category": "misc",
        "pattern": [" A ", "ABA", " C "],
        "key": {
            "A": "minecraft:amethyst_shard",
            "B": "minecraft:book",
            "C": "minecraft:compass",
        },
        "result": {"id": f"{NS}:ledger", "count": 1},
    })


def phone_model() -> dict:
    """A cheap candybar handset: body, screen, keypad, stub antenna.

    Held upright rather than lying flat -- a phone seen edge-on in a hotbar is
    a grey rectangle, and the antenna is what makes the silhouette read as a
    burner from across the screen.
    """
    return {
        "parent": "minecraft:block/block",
        "ambientocclusion": False,
        "textures": {
            "shell": f"{NS}:item/phone_shell",
            "screen": f"{NS}:item/phone_screen",
            "keys": f"{NS}:item/phone_keys",
            "particle": f"{NS}:item/phone_shell",
        },
        # Barely rotated in the GUI: the screen and keypad are the whole
        # silhouette, and turning it edge-on hides both behind a 2px shell.
        "display": held(1.1, gui_rotation=(15, 200, 0)),
        "elements": [
            box([4.5, 0.5, 6.5], [11.5, 14, 9.5], "shell"),      # body
            box([5.1, 8, 6.1], [10.9, 12.8, 6.7], "screen"),     # screen, proud of shell
            box([5.1, 1.8, 6.1], [10.9, 7.2, 6.7], "keys"),      # keypad
            box([9.8, 14, 7.4], [10.6, 16, 8.4], "shell"),       # antenna stub
        ],
    }


def phone_assets() -> None:
    put(f"assets/{NS}/models/block/burner_phone.json", phone_model())
    put(f"assets/{NS}/models/item/burner_phone.json", {"parent": f"{NS}:block/burner_phone"})
    put(f"assets/{NS}/items/burner_phone.json", {
        "model": {"type": "minecraft:model", "model": f"{NS}:item/burner_phone"},
    })

    # Copper for the aerial, redstone for the guts, amethyst for the screen.
    # Cheap on purpose: the phone is replaceable, the reputation on it isn't.
    put(f"data/{NS}/recipe/burner_phone.json", {
        "type": "minecraft:crafting_shaped",
        "category": "misc",
        "pattern": [" C ", "IAI", "IRI"],
        "key": {
            "C": "minecraft:copper_ingot",
            "I": "minecraft:iron_nugget",
            "A": "minecraft:amethyst_shard",
            "R": "minecraft:redstone",
        },
        "result": {"id": f"{NS}:burner_phone", "count": 1},
    })


def stall_model() -> dict:
    """A market stall: counter, striped awning, goods on the top.

    Closed shell like every other block here -- Polymer serves it on a
    FULL_BLOCK carrier, so gaps would show a world lit as if the block were
    solid. The awning overhangs the counter to give it a silhouette.
    """
    return {
        "parent": "minecraft:block/block",
        "ambientocclusion": False,
        "textures": {
            "counter": f"{NS}:block/stall_counter",
            "awning": f"{NS}:block/stall_awning",
            "goods": f"{NS}:block/stall_goods",
            "particle": f"{NS}:block/stall_counter",
        },
        "elements": [
            box([0, 0, 0], [16, 10, 16], "counter", up="goods"),      # counter
            box([0, 10, 0], [16, 13, 16], "goods", up="goods"),       # produce on top
            box([-1, 13, -1], [17, 16, 17], "awning", up="awning"),   # overhanging awning
        ],
    }


def slot_model(upper: bool) -> dict:
    """Half a cabinet: sloped console below, lit display head above.

    Two blocks so it stands like furniture rather than sitting on the floor
    like a crate. The lower half is the console you reach -- a sloped deck with
    the lever out of the right side -- and the upper half is the glass, framed
    in brass with a marquee across the top.

    Closed shells both, same FULL_BLOCK rule as everything else here: any gap
    shows a world lit as though the block were solid.
    """
    if upper:
        return {
            "parent": "minecraft:block/block",
            "ambientocclusion": False,
            "textures": {
                "glass": f"{NS}:block/slot_screen",
                "body": f"{NS}:block/slot_body",
                "trim": f"{NS}:block/slot_trim",
                "particle": f"{NS}:block/slot_body",
            },
            "elements": [
                box([1, 0, 1], [15, 13, 15], "body"),                 # cabinet head
                box([0.8, 1.5, 0.6], [15.2, 11.5, 1.1], "glass"),     # front glass
                box([0.8, 1.5, 14.9], [15.2, 11.5, 15.4], "glass"),   # back glass
                box([0, 13, 0], [16, 16, 16], "trim", up="trim"),     # marquee
                box([0.6, 0.8, 0.4], [15.4, 1.6, 15.6], "trim"),      # lower brass lip
                # A bezel round the glass, so the reels sit in a window
                # instead of being painted on the front of a box.
                box([0.4, 11.3, -0.3], [15.6, 12.6, 0.9], "trim", up="trim"),
                box([0.4, 0.6, -0.3], [15.6, 1.9, 0.9], "trim", down="trim"),
                box([0.2, 0.6, -0.3], [1.8, 12.6, 0.9], "trim"),
                box([14.2, 0.6, -0.3], [15.8, 12.6, 0.9], "trim"),
                # Lamp columns up the corners and a lit sign on the marquee.
                box([-0.5, 1.2, -0.5], [0.9, 12.4, 0.9], "glass"),
                box([15.1, 1.2, -0.5], [16.5, 12.4, 0.9], "glass"),
                box([2.5, 13.4, -0.9], [13.5, 15.6, 0.4], "glass",
                    up="trim", down="trim"),
                box([-0.6, 15.6, -1.1], [16.6, 16.8, 16.6], "trim", up="trim"),
            ],
        }
    return {
        "parent": "minecraft:block/block",
        "ambientocclusion": False,
        "textures": {
            "body": f"{NS}:block/slot_body",
            "deck": f"{NS}:block/slot_deck",
            "trim": f"{NS}:block/slot_trim",
            "particle": f"{NS}:block/slot_body",
        },
        "elements": [
            box([1, 0, 1], [15, 12, 15], "body"),                 # cabinet
            box([0.5, 12, 0.5], [15.5, 14, 15.5], "deck", up="deck"),   # sloped deck
            box([0, 14, 2], [16, 16, 14], "trim", up="trim"),     # console lip
            box([15.4, 6, 6.5], [17, 11, 9.5], "trim"),           # lever arm
            box([15.6, 10.5, 6], [17.6, 12.5, 10], "deck"),       # lever knob
            box([0, 0, 0], [16, 1.2, 16], "trim", down="trim"),   # plinth
            # The coin tray. Proud of the front, so the cabinet has something
            # sticking out at hand height and stops reading as a fridge.
            box([3, 2.5, -1.6], [13, 5, 0.6], "trim", up="deck", down="trim"),
            box([3, 5, -1.6], [13, 5.6, -1.0], "trim", up="trim"),
            # The button row along the front of the deck -- the bit you press
            # when you cannot be bothered to reach for the arm.
            box([3.2, 14, 1.4], [5.6, 15.2, 3.8], "trim", up="deck"),
            box([6.8, 14, 1.4], [9.2, 15.2, 3.8], "trim", up="deck"),
            box([10.4, 14, 1.4], [12.8, 15.2, 3.8], "trim", up="deck"),
            # Fluted side columns, so the cabinet has a profile from the side
            # as well as the front.
            box([-0.6, 1.2, 2], [1, 13.5, 5], "trim"),
            box([15, 1.2, 2], [16.6, 13.5, 5], "trim"),
            box([-0.6, 1.2, 11], [1, 13.5, 14], "trim"),
            box([15, 1.2, 11], [16.6, 13.5, 14], "trim"),
        ],
    }


def slot_assets() -> None:
    put(f"assets/{NS}/models/block/slot_machine_lower.json", slot_model(False))
    put(f"assets/{NS}/models/block/slot_machine_upper.json", slot_model(True))
    # The item shows the console half -- the recognisable end with the lever.
    put(f"assets/{NS}/models/item/slot_machine.json",
        {"parent": f"{NS}:block/slot_machine_lower"})
    put(f"assets/{NS}/items/slot_machine.json", {
        "model": {"type": "minecraft:model", "model": f"{NS}:item/slot_machine"},
    })
    put(f"data/{NS}/loot_table/blocks/slot_machine.json", {
        "type": "minecraft:block",
        "pools": [{"rolls": 1, "entries": [
            {"type": "minecraft:item", "name": f"{NS}:slot_machine"}]}],
    })
    # Iron shell, redstone guts, gold on the front, and a diamond because the
    # thing has to look like it might pay out.
    put(f"data/{NS}/recipe/slot_machine.json", {
        "type": "minecraft:crafting_shaped",
        "category": "misc",
        "pattern": ["IGI", "RDR", "III"],
        "key": {"I": "minecraft:iron_ingot", "G": "minecraft:gold_ingot",
                "R": "minecraft:redstone", "D": "minecraft:diamond"},
        "result": {"id": f"{NS}:slot_machine", "count": 1},
    })


def stall_assets() -> None:
    put(f"assets/{NS}/models/block/market_stall.json", stall_model())
    put(f"assets/{NS}/models/item/market_stall.json", {"parent": f"{NS}:block/market_stall"})
    put(f"assets/{NS}/items/market_stall.json", {
        "model": {"type": "minecraft:model", "model": f"{NS}:item/market_stall"},
    })
    put(f"data/{NS}/loot_table/blocks/market_stall.json", {
        "type": "minecraft:block",
        "pools": [{"rolls": 1, "entries": [
            {"type": "minecraft:item", "name": f"{NS}:market_stall"}]}],
    })
    # Wool for the awning, logs for the frame, an emerald because it's a shop.
    put(f"data/{NS}/recipe/market_stall.json", {
        "type": "minecraft:crafting_shaped",
        "category": "misc",
        "pattern": ["WWW", "LEL", "LLL"],
        "key": {"W": "#minecraft:wool", "L": "#minecraft:logs",
                "E": "minecraft:emerald_block"},
        "result": {"id": f"{NS}:market_stall", "count": 1},
    })


def mailbox_model() -> dict:
    """A post box on a post, with the flag up.

    Deliberately narrow. It has to read as street furniture from across a
    square rather than as another machine, and it is the one block in this mod
    that is SUPPOSED to be daylight round the edges -- which is why it draws a
    TRANSPARENT_BLOCK state instead of the free FULL_BLOCK pool.
    """
    return {
        "parent": "minecraft:block/block",
        "ambientocclusion": False,
        "textures": {
            "post": f"{NS}:block/mailbox_post",
            "box": f"{NS}:block/mailbox_box",
            "flag": f"{NS}:block/mailbox_flag",
            "particle": f"{NS}:block/mailbox_post",
        },
        "elements": [
            box([7, 0, 7], [9, 9, 9], "post"),                          # the post
            box([4, 9, 5], [12, 15, 11], "box", up="box", down="post"), # the box
            box([11.5, 10, 7.5], [12.5, 15.5, 8.5], "flag"),            # the flag, up
        ],
    }


def vault_assets() -> None:
    """A solid cube, so it may honestly claim the FULL_BLOCK carrier.

    Everything civic in this mod is going to end up next to this block, and a
    hollow model on a solid carrier is the X-ray hole check_models.py exists to
    catch. A vault is supposed to be a solid lump anyway.
    """
    # Written out as an explicit element rather than inheriting block/cube,
    # because check_models.py measures face coverage off a model's OWN
    # elements -- and a model that keeps its geometry in a vanilla parent
    # reads as an empty shell it cannot vouch for. Spelling the cube out is
    # cheaper than teaching the checker to trust a parent it never loads.
    put(f"assets/{NS}/models/block/city_vault.json", {
        "parent": "minecraft:block/block",
        "textures": {
            "face": f"{NS}:block/city_vault_face",
            "side": f"{NS}:block/city_vault_side",
            "top": f"{NS}:block/city_vault_top",
            "particle": f"{NS}:block/city_vault_side",
        },
        "elements": [{
            "from": [0, 0, 0],
            "to": [16, 16, 16],
            "faces": {
                "north": {"uv": [0, 0, 16, 16], "texture": "#face"},
                "south": {"uv": [0, 0, 16, 16], "texture": "#side"},
                "east": {"uv": [0, 0, 16, 16], "texture": "#side"},
                "west": {"uv": [0, 0, 16, 16], "texture": "#side"},
                "up": {"uv": [0, 0, 16, 16], "texture": "#top"},
                "down": {"uv": [0, 0, 16, 16], "texture": "#top"},
            },
        }],
    })
    put(f"assets/{NS}/models/item/city_vault.json", {"parent": f"{NS}:block/city_vault"})
    put(f"assets/{NS}/items/city_vault.json", {
        "model": {"type": "minecraft:model", "model": f"{NS}:item/city_vault"},
    })
    put(f"data/{NS}/loot_table/blocks/city_vault.json", {
        "type": "minecraft:block",
        "pools": [{"rolls": 1, "entries": [
            {"type": "minecraft:item", "name": f"{NS}:city_vault"}]}],
    })
    # Gold and iron round an emerald block. Dear enough that founding the city
    # is a decision somebody saved up for, cheap enough that it happens.
    put(f"data/{NS}/recipe/city_vault.json", {
        "type": "minecraft:crafting_shaped",
        "category": "misc",
        "pattern": ["GIG", "IEI", "GIG"],
        "key": {"G": "minecraft:gold_ingot", "I": "minecraft:iron_ingot",
                "E": "minecraft:emerald_block"},
        "result": {"id": f"{NS}:city_vault", "count": 1},
    })


def mailbox_assets() -> None:
    put(f"assets/{NS}/models/block/mailbox.json", mailbox_model())
    put(f"assets/{NS}/models/item/mailbox.json", {"parent": f"{NS}:block/mailbox"})
    put(f"assets/{NS}/items/mailbox.json", {
        "model": {"type": "minecraft:model", "model": f"{NS}:item/mailbox"},
    })
    put(f"data/{NS}/loot_table/blocks/mailbox.json", {
        "type": "minecraft:block",
        "pools": [{"rolls": 1, "entries": [
            {"type": "minecraft:item", "name": f"{NS}:mailbox"}]}],
    })
    # Iron for the box, a plank for the post, and paper because the whole
    # point of it is what somebody puts through the slot.
    put(f"data/{NS}/recipe/mailbox.json", {
        "type": "minecraft:crafting_shaped",
        "category": "misc",
        "pattern": ["IPI", " S ", " S "],
        "key": {"I": "minecraft:iron_nugget", "P": "minecraft:paper",
                "S": "#minecraft:planks"},
        "result": {"id": f"{NS}:mailbox", "count": 1},
    })


def tags() -> None:
    """Make the hammer enchantable.

    Enchanting eligibility in 1.21 is tag-driven: Item.Settings.enchantable()
    sets how GOOD the enchants are, but the table only offers them if the item
    is in these tags. Setting one without the other silently does nothing.
    Tags merge across mods, so adding to minecraft: namespace is correct here.
    """
    for tag in ("mining", "mining_loot", "durability", "vanishing"):
        put(f"data/minecraft/tags/item/enchantable/{tag}.json", {
            "values": [f"{NS}:miners_hammer"],
        })


def recipes() -> None:
    # 5 diamonds is deliberate: nine blocks a swing should cost real money.
    put(f"data/{NS}/recipe/miners_hammer.json", {
        "type": "minecraft:crafting_shaped",
        "category": "equipment",
        "pattern": ["DDD", "DSD", " S "],
        "key": {"D": "minecraft:diamond", "S": "minecraft:stick"},
        "result": {"id": f"{NS}:miners_hammer", "count": 1},
    })

    put(f"data/{NS}/recipe/drying_rack.json", {
        "type": "minecraft:crafting_shaped",
        "category": "misc",
        "pattern": ["SSS", "T T", "SSS"],
        "key": {"S": "minecraft:stick", "T": "minecraft:string"},
        "result": {"id": f"{NS}:drying_rack", "count": 1},
    })
    for strain in STRAINS:
        # crafting_transmute, NOT crafting_shapeless. A shapeless recipe builds
        # a fresh result stack and silently drops the quality component, so
        # every joint would come out the default grade no matter what went in.
        # Transmute carries the input's components onto the result.
        put(f"data/{NS}/recipe/joint_{strain}.json", {
            "type": "minecraft:crafting_transmute",
            "category": "misc",
            "input": f"{NS}:dried_bud_{strain}",
            "material": "minecraft:paper",
            "result": {"id": f"{NS}:joint_{strain}"},
        })


def main() -> None:
    block_models()
    item_assets()
    post_effects()
    lang()
    loot_tables()
    coca_assets()
    device_assets()
    nerve_tonic_assets()
    ledger_assets()
    wallet_assets()
    casino_card_assets()
    roulette_assets()
    plinko_assets()
    climb_assets()
    # A gold ingot for the coin, green wool for the felt, planks for the box.
    table_assets("toss", "toss_top", [" G ", "WWW", "PPP"],
                 {"G": "minecraft:gold_ingot", "W": "minecraft:green_wool",
                  "P": "#minecraft:planks"}, toss_furniture())
    # Paper for the cards, and a bit more of everything: it's a proper table.
    table_assets("blackjack", "blackjack_top", ["APA", "WWW", "PPP"],
                 {"A": "minecraft:paper", "P": "#minecraft:planks",
                  "W": "minecraft:green_wool"}, blackjack_furniture())
    # Paper and gold leaf on red: a newsagent's counter, not a card table.
    # Bottles, a barrel and planks. Cheap on purpose -- the bar is a chore you
    # are being asked to take on, not a reward for having got somewhere.
    table_assets("casino_bar", "bar_top", ["BHB", "PPP", "PPP"],
                 {"B": "minecraft:glass_bottle", "H": "minecraft:barrel",
                  "P": "#minecraft:planks"}, bar_furniture())
    table_assets("scratch", "scratch_top", ["AGA", "WWW", "PPP"],
                 {"A": "minecraft:paper", "G": "minecraft:gold_ingot",
                  "P": "#minecraft:planks", "W": "minecraft:red_wool"},
                 scratch_furniture())
    advancements()
    phone_assets()
    stall_assets()
    mailbox_assets()
    vault_assets()
    slot_assets()
    tags()
    worldgen()
    recipes()
    print(f"wrote {written} json files under {ROOT}")


if __name__ == "__main__":
    main()
