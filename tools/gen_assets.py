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
        "block.trapcraft.drying_rack": "Suszarka",
        "block.trapcraft.wild_cannabis": "Dzikie konopie",
        "item.trapcraft.miners_hammer": "Młot górniczy",
        "block.trapcraft.coca_crop": "Krzak koki",
        "item.trapcraft.coca_seeds": "Nasiona koki",
        "item.trapcraft.coca_leaves": "Liście koki",
        "item.trapcraft.coca_paste": "Pasta z koki",
        "item.trapcraft.coca_powder": "Proszek",
        "block.trapcraft.leaf_press": "Prasa do liści",
        "item.trapcraft.leaf_press": "Prasa do liści",
        "block.trapcraft.refiner": "Rafineria",
        "item.trapcraft.refiner": "Rafineria",
        "effect.trapcraft.wired": "Nakręcony",
        "block.trapcraft.bong": "Bong",
        "item.trapcraft.bong": "Bong",
        "block.trapcraft.gravity_bong": "T\u0142ok",
        "item.trapcraft.gravity_bong": "T\u0142ok",
        "item.trapcraft.drying_rack": "Suszarka",
        "block.trapcraft.mixing_station": "Mieszalnik",
        "item.trapcraft.mixing_station": "Mieszalnik",
        # Both are normally renamed per-blend by the component, so these only
        # show on a /give with no mix attached.
        "item.trapcraft.blend_bud": "Susz mieszany",
        "item.trapcraft.blend_joint": "Skręt mieszany",
        "item.trapcraft.nerve_tonic": "Lek na nerwy",
        "item.trapcraft.ledger": "Spis skrzyń",
        "item.trapcraft.wallet": "Portfel",
        # Renamed per-casino by the component; this only shows on a
        # freshly crafted, unsigned one.
        "item.trapcraft.casino_card": "Licencja kasyna",
        "block.trapcraft.roulette": "Stół do ruletki",
        "block.trapcraft.plinko": "Plinko",
        "block.trapcraft.climb": "Wspinaczka",
        "block.trapcraft.toss": "Rzut monetą",
        "item.trapcraft.toss": "Rzut monetą",
        "block.trapcraft.blackjack": "Blackjack",
        "block.trapcraft.scratch": "Zdrapki",
        "block.trapcraft.casino_bar": "Bar",
        "item.trapcraft.casino_bar": "Bar",
        "item.trapcraft.scratch": "Zdrapki",
        "item.trapcraft.blackjack": "Blackjack",
        "item.trapcraft.climb": "Wspinaczka",
        "item.trapcraft.plinko": "Plinko",
        "item.trapcraft.roulette": "Stół do ruletki",
        "item.trapcraft.burner_phone": "Telefon na kartę",
        "block.trapcraft.nightclub": "Budka klubowa",
        "item.trapcraft.nightclub": "Budka klubowa",
        "block.trapcraft.market_stall": "Stragan",
        "item.trapcraft.market_stall": "Stragan",
        "block.trapcraft.mailbox": "Skrzynka pocztowa",
        "item.trapcraft.mailbox": "Skrzynka pocztowa",
        "block.trapcraft.city_vault": "Skarbiec miasta",
        "item.trapcraft.city_vault": "Skarbiec miasta",
        "block.trapcraft.hospital": "Szpital",
        "item.trapcraft.hospital": "Szpital",
        # "Shop Shelf", not "Market Shelf". Three things called market -- the
        # counter, the stall and this -- meant the word had stopped narrowing
        # anything down. The ID stays market_shelf: every placed block on the
        # live world is that ID, and renaming it orphans the lot.
        "block.trapcraft.market_shelf": "Półka sklepowa",
        "item.trapcraft.market_shelf": "Półka sklepowa",
        "item.trapcraft.dirty_emerald": "Brudny szmaragd",
        "block.trapcraft.dirty_emerald_block": "Blok brudnych szmaragdów",
        "item.trapcraft.dirty_emerald_block": "Blok brudnych szmaragdów",
        "block.trapcraft.shop_till": "Kasa sklepowa",
        "item.trapcraft.shop_till": "Kasa sklepowa",
        "block.trapcraft.laundry": "Bęben pralniczy",
        "item.trapcraft.laundry": "Bęben pralniczy",
        "block.trapcraft.slot_machine": "Jednoręki bandyta",
        "item.trapcraft.slot_machine": "Jednoręki bandyta",
        "effect.trapcraft.baked": "Naćpany",
        "effect.trapcraft.tolerance": "Tolerancja",
        "block.trapcraft.poppy_crop": "Mak lekarski",
        "item.trapcraft.poppy_seeds": "Nasiona maku",
        "item.trapcraft.poppy_pod": "Makówka",
        "item.trapcraft.raw_opium": "Surowe opium",
        "item.trapcraft.morphine_base": "Baza morfinowa",
        # Renamed per-purity by the component, like powder; this is the bare
        # name a /give with no grade on it shows.
        "item.trapcraft.heroin": "Heroina",
        "block.trapcraft.scoring_table": "Stół do nacinania",
        "item.trapcraft.scoring_table": "Stół do nacinania",
        "block.trapcraft.wash_pot": "Garnek do gotowania",
        "item.trapcraft.wash_pot": "Garnek do gotowania",
        "block.trapcraft.acetylator": "Acetylator",
        "item.trapcraft.acetylator": "Acetylator",
        "effect.trapcraft.nod": "Odlot",
        "effect.trapcraft.withdrawal": "Odstawienie",
        "entity.minecraft.villager.trapcraft.dealer": "Diler",
    }
    for strain, nice in STRAINS.items():
        entries[f"block.trapcraft.cannabis_crop_{strain}"] = f"Krzak {nice}"
        entries[f"item.trapcraft.seeds_{strain}"] = f"Nasiona {nice}"
        entries[f"item.trapcraft.raw_bud_{strain}"] = f"Świeża szyszka {nice}"
        entries[f"item.trapcraft.dried_bud_{strain}"] = f"Susz {nice}"
        entries[f"item.trapcraft.joint_{strain}"] = f"Skręt {nice}"
    for name, (nice, _, _) in WANDS.items():
        entries[f"item.trapcraft.{name}"] = nice
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


POPPY_ITEMS = ["poppy_seeds", "poppy_pod", "raw_opium", "morphine_base", "heroin"]


def scoring_model(progress: int | None) -> dict:
    """The scoring bench: pods on a slab, blades over them, sap in the channel.

    Closed shell with recessed sides, same rule as every other machine in the
    pack -- Polymer serves these on a FULL_BLOCK carrier and the client lights
    the volume as solid, so a see-through frame shows a hole in the world.

    The stage is geometry, not a different picture: the pods sit up proud at
    stage 0 and sink as they are worked, while the sap channel round the base
    fills. So a bench half way through reads as a bench half way through, from
    across the room, without a progress bar.
    """
    loaded = progress is not None
    # Pods lose a pixel and a half a step; the sap gains what they lose.
    pod_top = 11.0 - (progress * 1.2) if loaded else 11.0
    sap_top = 3.2 + (progress * 0.45) if loaded else 3.2

    els = [
        box([0, 0, 0], [16, 3, 16], "wood", up="wood", down="wood"),      # plinth
        box([0, 13, 0], [16, 16, 16], "wood", up="wood", down="wood"),    # lid
        # Corner posts, proud of the core so the recess between them reads.
        box([0, 3, 0], [3.5, 13, 3.5], "wood"),
        box([12.5, 3, 0], [16, 13, 3.5], "wood"),
        box([0, 3, 12.5], [3.5, 13, 16], "wood"),
        box([12.5, 3, 12.5], [16, 13, 16], "wood"),
        # The sap channel, running right round the base and standing proud so
        # it catches light on all four sides.
        box([-0.05, 3, -0.05], [16.05, sap_top, 16.05], "latex"),
    ]
    if loaded:
        els.append(box([3.5, sap_top, 3.5], [12.5, pod_top, 12.5], "pods"))
    # Dark interior, from the top of the work all the way up to the lid.
    #
    # This is the piece that keeps the block solid, and it has to reach the
    # lid: the bench used to stop this at y=12 and leave the band above the
    # pods as open air, which on a FULL_BLOCK carrier is not a gap but a hole.
    # The client has been told this is a solid cube and has already culled the
    # faces of the floor and wall behind it, so a straight line through the
    # band is a straight line into a world with nothing drawn in it. That got
    # worse as the batch worked down -- an empty bench leaked half a pixel and
    # a nearly-done one leaked four.
    els.append(box([3.5, pod_top if loaded else sap_top, 3.5],
                   [12.5, 13, 12.5], "void"))
    # Blade rails, crossing the recesses on the OUTSIDE of that core rather
    # than floating in the middle of it. Same detail, visible from all four
    # sides, and it cannot open the block up again wherever it is put.
    for offset in (4.5, 7.5, 10.5):
        els.append(box([-0.05, 11.4, offset - 0.4], [16.05, 12.4, offset + 0.4], "iron"))

    # A handle across the lid, so the top is not a blank slab.
    els.append(box([5, 16, 7.2], [11, 17, 8.8], "iron"))

    return {
        "parent": "minecraft:block/block",
        "ambientocclusion": False,
        "textures": {
            "wood": f"{NS}:block/press_wood",
            "iron": f"{NS}:block/press_iron",
            "void": f"{NS}:block/press_void",
            "latex": f"{NS}:block/scoring_latex",
            "pods": f"{NS}:block/poppy_pod_block",
            "particle": f"{NS}:block/press_wood",
        },
        "elements": els,
    }


def wash_pot_model(progress: int | None) -> dict:
    """A lidded copper pot on a brick collar, wearing its level on the outside.

    First shape of this was an open pot you looked down into, which is the
    honest picture and the wrong model: a FULL_BLOCK carrier lights the volume
    as solid, and tools/check_models.py rightly failed it for a top face that
    was 2% covered. So the pot is sealed and the wash is read from the SIDE
    instead, as a band standing proud of the vessel that climbs and darkens a
    step at a time -- the refiner's sight glass, doing a second job.

    Which is also the better block. You look at these from across a room, and a
    band that rises all the way round beats a puddle you have to stand over.

    The pot deliberately carries no fire of its own -- see WashPotBlock -- so
    there is no firebox and no ember here. The fire is a block you built
    underneath, and its absence is the other half of the read.
    """
    loaded = progress is not None
    liquid = "wash_liquid_idle" if not loaded else f"wash_liquid_{progress}"
    # Climbs as it cooks down and thickens: 5.5 at load, 11.1 at done.
    level = 5.5 + (progress * 1.4) if loaded else 4.5

    els = [
        box([0, 0, 0], [16, 2.5, 16], "brick", up="brick", down="brick"),   # collar
        box([0, 14, 0], [16, 16, 16], "copper", up="copper", down="copper"),  # lid
        # Corner posts and the solid belly between them, same construction as
        # the refiner -- closed shell, depth from the recess.
        box([0, 2.5, 0], [3, 14, 3], "copper"),
        box([13, 2.5, 0], [16, 14, 3], "copper"),
        box([0, 2.5, 13], [3, 14, 16], "copper"),
        box([13, 2.5, 13], [16, 14, 16], "copper"),
        box([3, 2.5, 3], [13, 14, 13], "copper"),
        # The wash, standing half a pixel proud of the belly so it catches
        # light on all four faces. Prouder than the refiner's sight glass on
        # purpose: this band IS the progress bar, and at 0.3 it read as a
        # discolouration rather than a level.
        box([2.5, 3.5, 2.5], [13.5, level, 13.5], "wash"),
        # Handles either side, so it reads as something you lift off a fire.
        box([-0.4, 9, 5], [0.6, 12, 11], "copper"),
        box([15.4, 9, 5], [16.4, 12, 11], "copper"),
        # Vent stack on the lid: the steam has to be going somewhere.
        box([6.5, 16, 6.5], [9.5, 18, 9.5], "copper"),
        box([6, 18, 6], [10, 18.8, 10], "copper"),
    ]

    return {
        "parent": "minecraft:block/block",
        "ambientocclusion": False,
        "textures": {
            "brick": f"{NS}:block/refiner_brick",
            "copper": f"{NS}:block/refiner_copper",
            "wash": f"{NS}:block/{liquid}",
            "particle": f"{NS}:block/refiner_copper",
        },
        "elements": els,
    }


def acetylator_model(progress: int | None) -> dict:
    """A sealed glass vessel clamped in an iron frame over a stone bench.

    The whole block exists to make one moment legible: the stage the batch is
    at. So the vessel is the tallest thing on it, the fluid inside it is a
    proper column rather than a puddle, and its texture pulses at peak and goes
    to tar one step later. Everything else -- frame, bench, condenser -- is
    deliberately dull so the glass is where your eye lands.
    """
    running = progress is not None
    fluid = "acetylator_fluid_idle" if not running else f"acetylator_fluid_{progress}"
    # Fills toward peak, then collapses when it goes over: a ruined batch looks
    # ruined without having to read the colour.
    if not running:
        level = 6.0
    elif progress >= AC_RUINED:
        level = 6.5
    else:
        level = 6.0 + progress * 1.4

    els = [
        box([0, 0, 0], [16, 4, 16], "stone", up="stone", down="stone"),      # bench
        box([0, 14.5, 0], [16, 16, 16], "iron", up="iron", down="iron"),     # yoke
        # Frame posts at the corners, proud of the vessel between them.
        box([0, 4, 0], [3, 14.5, 3], "iron"),
        box([13, 4, 0], [16, 14.5, 3], "iron"),
        box([0, 4, 13], [3, 14.5, 16], "iron"),
        box([13, 4, 13], [16, 14.5, 16], "iron"),
        # The vessel: a solid core wearing glass, with the fluid a separate box
        # standing proud of it so it is lit rather than seen through.
        box([3, 4, 3], [13, 14.5, 13], "glass"),
        box([2.8, 4.2, 2.8], [13.2, level, 13.2], "fluid"),
        # Clamp bands across the glass, top and bottom.
        box([2.6, 5.4, 2.6], [13.4, 6.2, 13.4], "iron"),
        box([2.6, 12.6, 2.6], [13.4, 13.4, 13.4], "iron"),
        # Condenser: a stack off the yoke and an arm returning to the bench.
        box([6.5, 16, 6.5], [9.5, 18.5, 9.5], "copper"),
        box([9.5, 16.6, 7.2], [14.6, 17.9, 8.8], "copper"),
        box([13.2, 10, 7.2], [14.8, 16.6, 8.8], "copper"),
    ]

    return {
        "parent": "minecraft:block/block",
        "ambientocclusion": False,
        "textures": {
            "stone": f"{NS}:block/press_stone",
            "iron": f"{NS}:block/press_iron",
            "copper": f"{NS}:block/refiner_copper",
            "glass": f"{NS}:block/refiner_glass",
            "fluid": f"{NS}:block/{fluid}",
            "particle": f"{NS}:block/press_iron",
        },
        "elements": els,
    }


# Mirrors of the constants in AcetylatorBlock and its friends. Kept as names
# rather than bare numbers so a retune in Java is one grep away from here --
# tools/check_models.py has no way to notice a model built for four stages
# serving a block that now has five.
AC_PEAK = 4
AC_RUINED = 5
SCORING_DONE = 4
WASH_DONE = 4


def poppy_assets() -> None:
    """Crop stages, three machines, items, loot and recipes for the long line."""
    # Leaves at every stage, a red flower at stage two, and pods at three. The
    # flower is the tell that it is nearly ready; the pods are the harvest.
    for age in range(2):
        put(f"assets/{NS}/models/block/poppy_crop_age{age}.json",
            plant_model(age, f"{NS}:block/poppy_leaf", None))
    put(f"assets/{NS}/models/block/poppy_crop_age2.json",
        plant_model(2, f"{NS}:block/poppy_leaf", f"{NS}:block/poppy_flower"))
    put(f"assets/{NS}/models/block/poppy_crop_age3.json",
        plant_model(3, f"{NS}:block/poppy_leaf", f"{NS}:block/poppy_pod_block"))

    put(f"assets/{NS}/models/block/scoring_table_empty.json", scoring_model(None))
    for step in range(SCORING_DONE + 1):
        put(f"assets/{NS}/models/block/scoring_table_{step}.json", scoring_model(step))

    put(f"assets/{NS}/models/block/wash_pot_empty.json", wash_pot_model(None))
    for step in range(WASH_DONE + 1):
        put(f"assets/{NS}/models/block/wash_pot_{step}.json", wash_pot_model(step))

    put(f"assets/{NS}/models/block/acetylator_idle.json", acetylator_model(None))
    for step in range(AC_RUINED + 1):
        put(f"assets/{NS}/models/block/acetylator_{step}.json", acetylator_model(step))

    for name in POPPY_ITEMS:
        put(f"assets/{NS}/models/item/{name}.json", {
            "parent": "minecraft:item/generated",
            "textures": {"layer0": f"{NS}:item/{name}"},
        })
    for block in ("scoring_table", "wash_pot", "acetylator"):
        parent = {"scoring_table": "scoring_table_empty", "wash_pot": "wash_pot_empty",
                  "acetylator": "acetylator_idle"}[block]
        put(f"assets/{NS}/models/item/{block}.json", {"parent": f"{NS}:block/{parent}"})
    for name in POPPY_ITEMS + ["scoring_table", "wash_pot", "acetylator"]:
        put(f"assets/{NS}/items/{name}.json", {
            "model": {"type": "minecraft:model", "model": f"{NS}:item/{name}"},
        })

    put(f"data/{NS}/loot_table/blocks/poppy_crop.json", {
        "type": "minecraft:block",
        "pools": [{"rolls": 1, "entries": [
            {"type": "minecraft:item", "name": f"{NS}:poppy_seeds"}]}],
    })
    for block in ("scoring_table", "wash_pot", "acetylator"):
        put(f"data/{NS}/loot_table/blocks/{block}.json", {
            "type": "minecraft:block",
            "pools": [{"rolls": 1, "entries": [
                {"type": "minecraft:item", "name": f"{NS}:{block}"}]}],
        })

    # Three recipes, each dearer than the last, and the last one dearer than
    # anything in the coca line -- which is the whole point of the long line.
    put(f"data/{NS}/recipe/scoring_table.json", {
        "type": "minecraft:crafting_shaped",
        "category": "misc",
        "pattern": ["III", "LLL", "L L"],
        # #minecraft:logs, not oak -- see the note on the leaf press recipe.
        "key": {"I": "minecraft:iron_ingot", "L": "#minecraft:logs"},
        "result": {"id": f"{NS}:scoring_table", "count": 1},
    })
    put(f"data/{NS}/recipe/wash_pot.json", {
        "type": "minecraft:crafting_shaped",
        "category": "misc",
        "pattern": ["C C", "CKC", "BBB"],
        "key": {"C": "minecraft:copper_ingot", "K": "minecraft:cauldron",
                "B": "minecraft:bricks"},
        "result": {"id": f"{NS}:wash_pot", "count": 1},
    })
    put(f"data/{NS}/recipe/acetylator.json", {
        "type": "minecraft:crafting_shaped",
        "category": "misc",
        "pattern": ["GSG", "CRC", "III"],
        "key": {"G": "minecraft:glass", "S": "minecraft:brewing_stand",
                "C": "minecraft:copper_block", "R": "minecraft:blaze_rod",
                "I": "minecraft:iron_block"},
        "result": {"id": f"{NS}:acetylator", "count": 1},
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


def derived_uv(frm, to, side) -> list[float]:
    """The uv Minecraft works out for itself when a face doesn't name one.

    Straight out of JsonUnbakedModel: it is the element's own footprint on
    that face, in block coordinates.
    """
    x0, y0, z0 = frm
    x1, y1, z1 = to
    return {
        "down": [x0, 16 - z1, x1, 16 - z0],
        "up": [x0, z0, x1, z1],
        "north": [16 - x1, 16 - y1, 16 - x0, 16 - y0],
        "south": [x0, 16 - y1, x1, 16 - y0],
        "west": [z0, 16 - y1, z1, 16 - y0],
        "east": [16 - z1, 16 - y1, 16 - z0, 16 - y0],
    }[side]


def box(frm, to, tex, up=None, down=None, north=None, south=None,
        east=None, west=None, uv=None):
    """One cuboid, same material all round unless a face is overridden.

    UVs are left off so Minecraft derives them from the element's own size.
    That keeps the textures tiling at a consistent scale whatever the box is,
    which is the whole reason the glassware materials are patterns rather than
    pictures -- a hand-placed UV would stretch differently on every part.

    The derivation only works INSIDE the cube, though. A bottle standing at
    y=15.5..23 derives v from -7 to 0.5, and a negative v does not clamp: it
    reads whatever happens to sit above that sprite in the stitched atlas. Every
    prop standing on a table was quietly doing that. So the derived window gets
    clipped back into the sprite, and anything that clips away to nothing --
    a box entirely above y=16, which is most of a back bar -- falls back to the
    whole texture.

    Pass `uv` to say "the whole drawing, on every face", which is what a 2px
    box wearing a picture of a bottle actually wants.
    """
    picked = {"north": north, "south": south, "east": east, "west": west,
              "up": up, "down": down}
    faces = {}
    for side, override in picked.items():
        face = {"texture": f"#{override or tex}"}
        window = list(uv) if uv else clip(derived_uv(frm, to, side))
        if window != derived_uv(frm, to, side):
            face["uv"] = window
        faces[side] = face
    return {"from": frm, "to": to, "faces": faces}


def clip(uv) -> list[float]:
    """Pull a uv window back inside the sprite, or give up and use all of it."""
    window = [min(16, max(0, round(edge, 4))) for edge in uv]
    if window[0] == window[2] or window[1] == window[3]:
        return [0, 0, 16, 16]
    return window


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
    """A pit table: felt in a brass rim over a panelled skirt to the carpet.

    It had four legs once, and the legs are what kept the whole casino on
    leaf carriers -- an open cube needs a see-through carrier, and every
    see-through carrier Polymer owns is a leaf state that shader packs wave
    as foliage. A real casino table is skirted to the floor anyway (that is
    where the drop box lives), so the closed shell is more honest, not less:
    kick plinth, panelled skirt, corner posts proud of it, and the playing
    surface overhanging the lot. check_models.py verifies the shell stays
    closed now that the carrier claims to be solid.

    `furniture` is a list of extra elements, so the coin, the card shoe and
    the scratchcard rack are the thing you tell the three games apart by from
    across the room rather than a texture you have to walk up to and read.
    """
    elements = [
        # Kick plinth, full footprint: the floor's culled top face must
        # never be visible, and this is the element that guarantees it.
        box([0, 0, 0], [16, 1.5, 16], "leg", up="leg", down="leg"),
        # The skirt: fielded panels to the carpet. Recessed a shade so the
        # kick and the top both read as separate mouldings.
        box([0.25, 1.5, 0.25], [15.75, 13.5, 15.75], "skirt",
            up="skirt", down="skirt"),
        # Corner posts, proud of the skirt: the silhouette the legs used to
        # give it, without the daylight that cost a leaf state.
        box([-0.5, 0, -0.5], [1.5, 14.3, 1.5], "leg"),
        box([14.5, 0, -0.5], [16.5, 14.3, 1.5], "leg"),
        box([-0.5, 0, 14.5], [1.5, 14.3, 16.5], "leg"),
        box([14.5, 0, 14.5], [16.5, 14.3, 16.5], "leg"),
        # The playing surface, proud of the skirt, with the felt on the lid.
        box([0, 13.5, 0], [16, 15.5, 16], "rim", up="top", down="side"),
    ]
    elements.extend(furniture or [])
    return {
        "parent": "minecraft:block/block",
        "ambientocclusion": False,
        "textures": {
            "top": f"{NS}:block/{top}",
            "side": f"{NS}:block/table_side",
            "skirt": f"{NS}:block/table_skirt",
            "leg": f"{NS}:block/table_leg",
            "rim": f"{NS}:block/table_rim",
            "coin": f"{NS}:block/toss_coin",
            "shoe": f"{NS}:block/card_shoe",
            "chips": f"{NS}:block/chip_stack",
            "rack": f"{NS}:block/card_rack",
            "particle": f"{NS}:block/table_skirt",
        },
        "elements": elements,
    }


def bar_model() -> dict:
    """A bar, rather than a gaming table with a shelf bolted to the back.

    It was the fourth table: same four legs, same felt-less top, a plank
    standing up behind it and a stack of chips on the front. From the customer
    side that is a table, and the one block on the floor the owner has an
    actual job at should not look like the three they only stand near.

    So it is built the way a bar is: a panelled front you walk up to, a brass
    foot rail to put your boot on, a counter that overhangs it, and a back bar
    of bottles on two shelves behind. All of which only works if it can be
    turned round -- everything here is drawn facing NORTH, and BarBlock spins
    the carrier to match its FACING. Nothing sticks out sideways past x=0 or
    x=16, so a row of them reads as one long counter.

    Bottles and glasses name their uv, because they are pictures rather than
    materials: the whole drawing belongs on the 2px box, not the 2px slice of
    it that the box's own footprint would pick out.
    """
    whole = [0, 0, 16, 16]
    elements = [
        # --- the counter you stand at ------------------------------------
        # Full-footprint kick and a front flush with the block edge: the
        # carrier is a solid cube now (leaf carriers wave under shaders), so
        # the shell has to close -- the panelling and the foot rail stand
        # PROUD of the front instead of the front being recessed behind them.
        box([0, 0, 0], [16, 1.5, 16], "wood", up="wood", down="wood"),  # kick
        box([0, 1.5, 0], [16, 13, 11], "front",                   # the carcass
            up="wood", down="wood"),
        box([0.3, 1.5, -0.5], [15.7, 2.3, 0], "brass"),           # beading, low
        box([0.3, 12.2, -0.5], [15.7, 13, 0], "brass"),           # beading, high
        box([3.4, 2.3, -0.5], [4.4, 12.2, 0], "brass"),           # stiles
        box([11.6, 2.3, -0.5], [12.6, 12.2, 0], "brass"),
        box([2.5, 1.4, -1.6], [3.5, 3.2, 0], "brass"),            # rail brackets
        box([12.5, 1.4, -1.6], [13.5, 3.2, 0], "brass"),
        box([0, 3.2, -1.8], [16, 4.4, -0.6], "brass"),            # the foot rail
        # The top overhangs the front, which is the bit you lean on.
        # table_side's brass band lands on the front edge of it, so the lip
        # is brass without another element.
        box([0, 13, -1.5], [16, 15.5, 11.5], "wood", up="top", uv=whole),

        # --- the back bar ------------------------------------------------
        # It stands on the floor rather than starting at counter height: a
        # shelf of bottles hanging in the air over the keeper's head is what
        # the old one did, and it looked like it. Uprights and boards run to
        # the seams so the closed shell holds behind the counter too.
        box([0.5, 0, 11], [2.2, 25, 16], "wood"),                 # uprights
        box([13.8, 0, 11], [15.5, 25, 16], "wood"),
        box([2.2, 0, 14.2], [13.8, 24.5, 16], "shelf"),           # boards behind
        # The service shelf bridges counter and back bar at working height.
        box([0.5, 15.5, 11], [15.5, 16.3, 15.6], "wood"),         # bottom shelf
        box([0.5, 20.3, 12], [15.5, 21.1, 15.6], "wood"),         # upper shelf
        box([0.2, 25, 12.2], [15.8, 26, 15.8], "brass"),          # cornice
    ]
    # Two rows of stock, none of it the same height, because a shelf of
    # identical bottles reads as a texture and a shelf of mismatched ones
    # reads as somebody's actual back bar.
    lower = [("green", 20.3), ("amber", 19.9), ("clear", 20.3),
             ("green", 19.7), ("amber", 20.3)]
    for index, (glass, height) in enumerate(lower):
        left = 2.4 + index * 2.2
        elements.append(box([left, 16.3, 12.8], [left + 1.8, height, 14.6],
                            glass, uv=whole))
    upper = [("amber", 24.6), ("clear", 24.3), ("green", 24.6), ("amber", 24.2)]
    for index, (glass, height) in enumerate(upper):
        left = 3.0 + index * 2.2
        elements.append(box([left, 21.1, 12.8], [left + 1.8, height, 14.6],
                            glass, uv=whole))
    elements.extend([
        # The taps, which is what tells you it's a bar and not a shop counter.
        box([9.4, 15.5, 7.6], [12.4, 16.4, 9.8], "brass"),
        box([10.4, 16.4, 8.2], [11.4, 20.6, 9.2], "brass"),
        box([9.6, 19, 6.6], [10.3, 20.2, 8.4], "brass"),
        box([11.5, 19, 6.6], [12.2, 20.2, 8.4], "brass"),
        # Two poured and waiting on the customer's side of the taps.
        box([3, 15.5, 4.2], [4.6, 18, 5.8], "glass", uv=whole),
        box([5.2, 15.5, 6], [6.6, 17.7, 7.4], "glass", uv=whole),
    ])
    return {
        "parent": "minecraft:block/block",
        "ambientocclusion": False,
        "textures": {
            "top": f"{NS}:block/bar_top",
            "front": f"{NS}:block/bar_front",
            "wood": f"{NS}:block/table_side",
            "shelf": f"{NS}:block/bar_shelf",
            "brass": f"{NS}:block/bar_brass",
            "glass": f"{NS}:block/bar_glass",
            "green": f"{NS}:block/bar_bottle_green",
            "amber": f"{NS}:block/bar_bottle_amber",
            "clear": f"{NS}:block/bar_bottle_clear",
            "particle": f"{NS}:block/bar_front",
        },
        "elements": elements,
    }


def bar_assets() -> None:
    """The bar: one model, four facings, and the recipe it always had.

    Not table_assets() any more -- that writes a single "" variant, and a
    block with a front needs one per direction. The four are the same model
    turned, exactly the way vanilla writes a furnace.

    These angles have to match BarBlock.spin(), which is what the server
    actually serves; this file is only read by a client holding the mod.
    """
    put(f"assets/{NS}/models/block/casino_bar.json", bar_model())
    # A block and a half tall, so it has to sit smaller and lower than a cube
    # or the back bar hangs out of the top of the inventory slot.
    display = held(0.66)
    for slot in ("gui", "fixed", "ground"):
        display[slot]["translation"][1] -= 2
    put(f"assets/{NS}/models/item/casino_bar.json",
        {"parent": f"{NS}:block/casino_bar", "display": display})
    put(f"assets/{NS}/items/casino_bar.json", {
        "model": {"type": "minecraft:model", "model": f"{NS}:item/casino_bar"},
    })
    put(f"assets/{NS}/blockstates/casino_bar.json", {
        "variants": {
            "facing=north": {"model": f"{NS}:block/casino_bar"},
            "facing=east": {"model": f"{NS}:block/casino_bar", "y": 90},
            "facing=south": {"model": f"{NS}:block/casino_bar", "y": 180},
            "facing=west": {"model": f"{NS}:block/casino_bar", "y": 270},
        },
    })
    # Bottles, a barrel and planks. Cheap on purpose -- the bar is a chore you
    # are being asked to take on, not a reward for having got somewhere.
    put(f"data/{NS}/recipe/casino_bar.json", {
        "type": "minecraft:crafting_shaped",
        "category": "misc",
        "pattern": ["BHB", "PPP", "PPP"],
        "key": {"B": "minecraft:glass_bottle", "H": "minecraft:barrel",
                "P": "#minecraft:planks"},
        "result": {"id": f"{NS}:casino_bar", "count": 1},
    })
    put(f"data/{NS}/loot_table/blocks/casino_bar.json", {
        "type": "minecraft:block",
        "pools": [{
            "rolls": 1,
            "entries": [{"type": "minecraft:item", "name": f"{NS}:casino_bar"}],
            "conditions": [{"condition": "minecraft:survives_explosion"}],
        }],
    })


# Everything standing ON a table sits above y=16, where the uv Minecraft
# derives for itself goes negative -- see box(). These are drawings of a coin,
# a shoe, a rack and a stack of chips, so each one wants the whole drawing.
WHOLE = [0, 0, 16, 16]


def toss_furniture() -> list:
    """A coin standing on its rim, mid-spin, and the stake it was tossed for.

    Upright rather than lying flat: a coin lying on a table is a yellow dot,
    and a coin on its edge is unmistakably a coin about to fall one way or the
    other, which is the entire game.
    """
    return [
        {**box([6.5, 15.5, 7.6], [9.5, 18.5, 8.4], "coin", uv=WHOLE),
         "rotation": {"origin": [8, 17, 8], "axis": "y", "angle": 22.5}},
        box([11, 15.5, 10.5], [13.5, 17, 13], "chips", uv=WHOLE),
    ]


def blackjack_furniture() -> list:
    """The shoe the cards come out of, and two stacks of chips."""
    return [
        box([9.5, 15.5, 2.5], [14, 18, 7], "shoe", uv=WHOLE),
        box([2.5, 15.5, 10], [5, 17.5, 12.5], "chips", uv=WHOLE),
        box([5.5, 15.5, 11.5], [8, 16.8, 14], "chips", uv=WHOLE),
    ]


def scratch_furniture() -> list:
    """A rack of unsold cards standing up at the back of the counter."""
    return [
        box([2.5, 15.5, 2], [13.5, 21, 3.5], "rack", uv=WHOLE),
        box([10.5, 15.5, 10], [13, 17, 12.5], "chips", uv=WHOLE),
    ]


def facing_variants(model: str, half: str | None = None) -> dict:
    """Blockstate variants for a TurnableBlock, spun like a furnace.

    Vanilla clients never read these -- Polymer bakes the spin into each
    carrier's model mapping -- but a client running the mod (the dev loop,
    and anyone who installs the jar) resolves the real blockstate here, and
    with a bare "" variant every table on the server faces north.
    """
    out = {}
    for facing, angle in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
        key = f"facing={facing}" + (f",half={half}" if half else "")
        variant = {"model": model}
        if angle:
            variant["y"] = angle
        out[key] = variant
    return out


def table_assets(name: str, top: str, pattern, key, furniture=None) -> None:
    put(f"assets/{NS}/models/block/{name}.json", table_model(top, furniture))
    put(f"assets/{NS}/models/item/{name}.json", {"parent": f"{NS}:block/{name}"})
    put(f"assets/{NS}/items/{name}.json", {
        "model": {"type": "minecraft:model", "model": f"{NS}:item/{name}"},
    })
    put(f"assets/{NS}/blockstates/{name}.json", {
        "variants": facing_variants(f"{NS}:block/{name}"),
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
    award("root", "Każdy musi jeść", "Zdobądź nasiona i zacznij działać.",
          f"{NS}:seeds_kush", None, trigger=has(f"{NS}:seeds_kush", f"{NS}:seeds_haze",
                                                f"{NS}:seeds_purp", f"{NS}:seeds_diesel",
                                                f"{NS}:seeds_sunset", f"{NS}:seeds_midnight"))

    award("cured", "Cierpliwość", "Wysusz szyszkę porządnie, zamiast palić ją na mokro.",
          f"{NS}:dried_bud_kush", "root",
          trigger=has(*[f"{NS}:dried_bud_{s}" for s in
                        ("kush", "haze", "purp", "diesel", "sunset", "midnight")]))
    award("rolled", "Zwinięte", "Zamień szyszkę w coś, co da się realnie sprzedać.",
          f"{NS}:joint_kush", "cured",
          trigger=has(*[f"{NS}:joint_{s}" for s in
                        ("kush", "haze", "purp", "diesel", "sunset", "midnight")]))
    award("blended", "Mieszanka firmowa", "Zmieszaj dwie odmiany w mieszalniku.",
          f"{NS}:blend_bud", "rolled", trigger=has(f"{NS}:blend_bud", f"{NS}:blend_joint"))
    award("named_blend", "Z nazwy", "Zrób mieszankę, która ma już swoją nazwę.",
          f"{NS}:blend_joint", "blended", frame="goal")

    award("refined", "Oczyszczone", "Przejdź całą linię koki aż do proszku.",
          f"{NS}:coca_powder", "root", trigger=has(f"{NS}:coca_powder"))

    # The long line. Hung off `refined` rather than off root, because the poppy
    # is the thing you go looking for once powder has stopped being exciting --
    # and the tree should say so.
    award("poppy", "Pełne słońce", "Zdobądź nasiona maku. W piwnicy nie urosną.",
          f"{NS}:poppy_seeds", "refined", trigger=has(f"{NS}:poppy_seeds"))
    award("opium", "Nacinanie", "Natnij partię makówek i zbierz to, co z nich wypłynie.",
          f"{NS}:raw_opium", "poppy", trigger=has(f"{NS}:raw_opium"))
    award("base", "Nad ogniem", "Wygotuj opium do bazy morfinowej. Garnek sam ognia nie da.",
          f"{NS}:morphine_base", "opium", trigger=has(f"{NS}:morphine_base"))
    award("dope", "Długa droga", "Trzy maszyny, a i tak można wszystko stracić.",
          f"{NS}:heroin", "base", frame="goal", trigger=has(f"{NS}:heroin"))
    # No vanilla criterion can see "the acetylator hit peak and you were there",
    # so this one is granted from code -- see TrapAwards.
    award("pure_dope", "W punkt", "Wyjmij towar z acetylatora dokładnie na szczycie czystości.",
          f"{NS}:heroin", "dope", frame="challenge")
    # Under root, not under dope. The habit is what all three lines feed and
    # you can earn this on weed alone -- filing it under the poppy branch would
    # hang it off an advancement the player might never take.
    award("hooked", "Uzależniony", "Doprowadź licznik nałogu aż do odstawienia.",
          f"{NS}:nerve_tonic", "root", frame="goal", hidden=True)
    award("clean_sheet", "Na czysto", "Zbij pełny licznik nałogu z powrotem do zera.",
          "minecraft:milk_bucket", "hooked", frame="challenge")

    award("open", "Otwarte", "Postaw własny stragan.",
          f"{NS}:market_stall", "root", trigger=has(f"{NS}:market_stall"))
    award("address", "Adres", "Postaw skrzynkę pocztową w pokoju, w którym da się mieszkać.",
          f"{NS}:mailbox", "root", trigger=has(f"{NS}:mailbox"))
    award("founded", "Miasto założone", "Postaw skarbiec miasta i zacznij ściągać podatki.",
          f"{NS}:city_vault", "root", trigger=has(f"{NS}:city_vault"), frame="goal")
    award("ward", "Miejsce do leczenia",
          "Otwórz szpital, żeby ugryzienie przestało być wyrokiem.",
          f"{NS}:hospital", "founded", trigger=has(f"{NS}:hospital"), frame="goal")
    award("shopkeeper", "Sklepikarz", "Otwórz sklep, w którym miasto może robić zakupy.",
          f"{NS}:shop_till", "open", trigger=has(f"{NS}:shop_till"))
    award("dirty", "Brudna kasa", "Przyjmij zapłatę, której żaden bank nie przyjmie.",
          f"{NS}:dirty_emerald", "root", trigger=has(f"{NS}:dirty_emerald"))
    award("clean", "Nic tu nie ma", "Przetrwaj nalot, nie tracąc ani grama towaru.",
          f"{NS}:laundry", "dirty", frame="goal", trigger=has(f"{NS}:laundry"))
    award("banked", "W portfelu", "Noś portfel zamiast dwudziestu stacków szmaragdów.",
          f"{NS}:wallet", "open", trigger=has(f"{NS}:wallet"))
    award("liquidation", "Wyprzedaż", "Sprzedaj na ladzie towar za 500 szmaragdów w jednej transakcji.",
          "minecraft:hopper", "open", frame="goal")
    award("mover", "Ruszyłeś rynek", "Podbij cenę jednego przedmiotu o jedną piątą, w pojedynkę.",
          "minecraft:emerald_block", "liquidation", frame="challenge")

    award("floor", "Własna sala", "Postaw pierwszy automat na swojej sali.",
          f"{NS}:slot_machine", "root",
          trigger=has(f"{NS}:slot_machine", f"{NS}:roulette", f"{NS}:plinko", f"{NS}:climb"))
    award("jackpot", "Kumulacja", "Wygraj z automatu dziesięciokrotność stawki.",
          "minecraft:nether_star", "floor", frame="goal")
    award("nerve", "Zimna krew", "Odejdź z wygraną z szóstego szczebla Wspinaczki.",
          "minecraft:gold_ingot", "floor", frame="goal")
    award("edge", "Skrajny przypadek", "Trafi kulką w skrajną przegródkę w Plinko.",
          "minecraft:snowball", "floor", frame="goal")
    award("whole_floor", "Kasa kasyna", "Wygraj coś na wszystkich czterech automatach.",
          "minecraft:emerald", "floor", frame="challenge")

    award("followed", "Ktoś cię śledził",
          "Sprzedaj z ręki raz za dużo i przekonaj się, kto patrzył.",
          "minecraft:crossbow", "root", frame="goal")
    award("licence", "Z licencją", "Zdobądź licencję kasyna.",
          f"{NS}:casino_card", "floor", trigger=has(f"{NS}:casino_card"))
    award("broke_the_bank", "Rozbity bank",
          "Wygraj z automatu więcej, niż kasyno miało w skarbcu.",
          "minecraft:gold_block", "licence", frame="challenge")

    award("rim", "Na kancie", "Obstaw kant przy rzucie monetą i trafi.",
          "minecraft:nether_star", "floor", frame="challenge")
    award("natural", "Naturalne 21", "Dostań dwadzieścia jeden na dwóch pierwszych kartach.",
          "minecraft:paper", "floor", frame="goal")

    award("network", "Siatka", "Wyślij kogoś na ulicę, żeby sprzedawał za ciebie.",
          "minecraft:player_head", "root", frame="goal")
    award("kingpin", "Szef szefów", "Wyszkol dilera do piątego poziomu.",
          "minecraft:golden_helmet", "network", frame="challenge")

    award("crew", "Lista płac", "Zatrudnij pierwszą osobę. Pensja leci od razu.",
          "minecraft:villager_spawn_egg", "root", frame="goal")
    award("foreman", "Brygadzista", "Naucz jednego robotnika wszystkiego, czego się da.",
          "minecraft:golden_hoe", "crew", frame="challenge")
    award("raided", "Znaleźli", "Pozwól, żeby nalot wyniósł twój towar.",
          "minecraft:crossbow", "root", frame="goal")
    award("clean", "Nic tu nie ma", "Przetrwaj nalot, nie tracąc ani grama towaru.",
          "minecraft:barrier", "raided", frame="challenge")


def climb_assets() -> None:
    put(f"assets/{NS}/models/block/climb.json", climb_model())
    put(f"assets/{NS}/models/item/climb.json", {"parent": f"{NS}:block/climb"})
    put(f"assets/{NS}/items/climb.json", {
        "model": {"type": "minecraft:model", "model": f"{NS}:item/climb"},
    })
    put(f"assets/{NS}/blockstates/climb.json", {
        "variants": facing_variants(f"{NS}:block/climb"),
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
    """An arcade cabinet with the drop running down the front of it.

    It used to be a shallow board floating in the back half of the cube,
    which needed a see-through carrier -- and every see-through carrier is a
    leaf state that shader packs wave. Same cure as the climb: the body
    fills the cube exactly, and everything that makes it a game -- the pegs,
    the slot fins, the catch tray, the marquee -- is bolted to the OUTSIDE
    of the front face. Proud geometry is free; hollow geometry costs a
    blockstate and a wave.

    The pegs are real boxes, staggered 3-4-3-4 the way a plinko field is,
    so the ball's path is something you could point at rather than a print.
    """
    pegs = []
    # Upper half: four rows of pegs. Lower: two rows, then the slot fins
    # and the tray take over. Rows stagger, and every peg stands proud.
    rows = ((2.5, (4, 8, 12)), (5.5, (2.6, 6, 10, 13.4)),
            (8.5, (4, 8, 12)), (11.5, (2.6, 6, 10, 13.4))) if upper else \
           ((10.5, (2.6, 6, 10, 13.4)), (13.5, (4, 8, 12)))
    for low, centres in rows:
        for centre in centres:
            pegs.append(box([centre - 0.6, low, -0.8], [centre + 0.6, low + 1.2, 0.2],
                            "peg", uv=[6, 6, 10, 10]))

    elements = [
        # The body fills the cube: game face on the front, panelled sides.
        {
            "from": [0, 0, 0],
            "to": [16, 16, 16],
            "faces": {
                "north": {"texture": "#board" if upper else "#slots",
                          "uv": [0, 0, 16, 16]},
                "south": {"texture": "#side", "uv": [0, 0, 16, 16]},
                "east": {"texture": "#side", "uv": [0, 0, 16, 16]},
                "west": {"texture": "#side", "uv": [0, 0, 16, 16]},
                "up": {"texture": "#frame", "uv": [0, 0, 16, 16]},
                "down": {"texture": "#frame", "uv": [0, 0, 16, 16]},
            },
        },
        # Corner rails up the front edges, continuous across both halves.
        box([-0.4, 0, -0.6], [1.2, 16, 0.8], "frame"),
        box([14.8, 0, -0.6], [16.4, 16, 0.8], "frame"),
    ] + pegs

    if upper:
        # The marquee crown: the lit sign that says a game lives here. Its
        # face carries the chasing-arrow animation.
        elements.append(box([-0.6, 15.2, -1.2], [16.6, 17.0, 1.6], "marquee",
                            up="frame", down="frame"))
    else:
        # The catch tray, and the fins that divide the payout slots. The
        # slot colours are painted on the face between the fins.
        elements += [
            box([0.6, 1.2, -2.4], [15.4, 2.4, 0.6], "frame", up="frame"),
            box([0.6, 2.4, -2.4], [15.4, 3.6, -1.6], "frame", up="frame"),
            box([1.4, 3.2, -0.7], [2.0, 8.5, 0.3], "frame"),
            box([4.6, 3.2, -0.7], [5.2, 8.5, 0.3], "frame"),
            box([7.8, 3.2, -0.7], [8.4, 8.5, 0.3], "frame"),
            box([11.0, 3.2, -0.7], [11.6, 8.5, 0.3], "frame"),
            box([14.2, 3.2, -0.7], [14.8, 8.5, 0.3], "frame"),
        ]

    return {
        "parent": "minecraft:block/block",
        "ambientocclusion": False,
        "textures": {
            "board": f"{NS}:block/plinko_board",
            "slots": f"{NS}:block/plinko_slots",
            "frame": f"{NS}:block/plinko_frame",
            "side": f"{NS}:block/plinko_side",
            "marquee": f"{NS}:block/plinko_marquee",
            "peg": f"{NS}:block/plinko_peg",
            "particle": f"{NS}:block/plinko_frame",
        },
        "elements": elements,
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
            **facing_variants(f"{NS}:block/plinko_lower", half="lower"),
            **facing_variants(f"{NS}:block/plinko_upper", half="upper"),
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
    """A waist-high table: skirted base, a laid-out felt, and the wheel.

    Built low and wide on purpose. The slot machine next to it is two blocks
    tall, so a table you look DOWN at is what makes a room of both read as a
    casino floor rather than a row of cabinets. The wheel head sits proud of
    the felt with a brass hub, because a flat green square with a picture of a
    wheel on it is a rug.

    Same closed shell as table_model and for the same reason: the legs it
    used to stand on are what kept it on a leaf carrier, and leaf carriers
    wave under shader packs. The skirt goes to the carpet, the way a real
    wheel table hides its cash drawer.
    """
    return {
        "parent": "minecraft:block/block",
        "ambientocclusion": False,
        "textures": {
            "felt": f"{NS}:block/roulette_felt",
            "rim": f"{NS}:block/roulette_rim",
            "wheel": f"{NS}:block/roulette_wheel",
            "skirt": f"{NS}:block/table_skirt",
            "leg": f"{NS}:block/table_leg",
            "chips": f"{NS}:block/chip_stack",
            "particle": f"{NS}:block/roulette_rim",
        },
        "elements": [
            # The same skirted base as the card tables, so the pit reads as
            # one suite of furniture: kick, panelled skirt, corner posts.
            box([0, 0, 0], [16, 1.5, 16], "leg", up="leg", down="leg"),
            box([0.25, 1.5, 0.25], [15.75, 13.5, 15.75], "skirt",
                up="skirt", down="skirt"),
            box([-0.5, 0, -0.5], [1.5, 14.3, 1.5], "leg"),
            box([14.5, 0, -0.5], [16.5, 14.3, 1.5], "leg"),
            box([-0.5, 0, 14.5], [1.5, 14.3, 16.5], "leg"),
            box([14.5, 0, 14.5], [16.5, 14.3, 16.5], "leg"),
            # The top: the betting layout under a mahogany rim.
            box([0, 13.5, 0], [16, 15.5, 16], "rim", up="felt", down="skirt"),
            # The wheel head, standing proud of the felt: a spinning disc
            # with a brass hub. The disc face is animated -- the one part of
            # the table that should move is the wheel.
            box([3.5, 15.5, 3.5], [12.5, 16.7, 12.5], "wheel",
                up="wheel", down="rim"),
            box([6.5, 16.7, 6.5], [9.5, 17.4, 9.5], "rim", up="rim"),
            # The dealer's chip rack along the front edge -- the detail that
            # says somebody works this table rather than it being scenery.
            box([2.5, 15.5, 0.9], [13.5, 16.5, 2.7], "rim", up="rim", down="rim"),
            box([3.3, 16.5, 1.2], [5.3, 17.3, 2.4], "chips", up="chips", uv=[0, 0, 16, 16]),
            box([7, 16.5, 1.2], [9, 17.3, 2.4], "chips", up="chips", uv=[0, 0, 16, 16]),
            box([10.7, 16.5, 1.2], [12.7, 17.3, 2.4], "chips", up="chips", uv=[0, 0, 16, 16]),
        ],
    }


def roulette_assets() -> None:
    put(f"assets/{NS}/models/block/roulette.json", roulette_model())
    put(f"assets/{NS}/models/item/roulette.json", {"parent": f"{NS}:block/roulette"})
    put(f"assets/{NS}/items/roulette.json", {
        "model": {"type": "minecraft:model", "model": f"{NS}:item/roulette"},
    })
    put(f"assets/{NS}/blockstates/roulette.json", {
        "variants": facing_variants(f"{NS}:block/roulette"),
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


# The wand rack: (name, the ingredient that makes it that wand, how many).
#
# The rods and the amethyst are the same on all five -- what you are buying is
# the thing in the head, which is exactly what the recipe should read like. The
# COUNT is what keeps the two routes honest: the shop wants five figures for
# these, so a wand you could craft from one nether star would make the shelf
# decoration. Three withers is a comparable evening.
WANDS = {
    "boost_wand": ("Różdżka Pędu", "minecraft:breeze_rod", 1),
    "harvest_wand": ("Różdżka Żniw", "minecraft:sniffer_egg", 2),
    "prospect_wand": ("Różdżka Żył", "minecraft:echo_shard", 2),
    "builder_wand": ("Różdżka Murarzy", "minecraft:recovery_compass", 3),
    "storm_wand": ("Różdżka Burz", "minecraft:nether_star", 3),
}

# Same five cells filled whichever count it is, so the guide book can draw the
# shape once and only say how many of them are cores.
WAND_PATTERNS = {
    1: [" AC", " RA", "R  "],
    2: [" CC", " RA", "R  "],
    3: [" CC", " RC", "RA "],
}


def wand_assets() -> None:
    """Flat, like a stick.

    These started as 3D models -- shaft, grip and a stone standing proud of it
    -- and they were worse: a wand is a stick with something on the end, and
    held on the vanilla item diagonal it reads as one at a glance. The only
    thing that separates the five is the colour of the stone, which is the same
    thing that separated them before, now drawn rather than modelled.
    """
    for name, (_, core, cores) in WANDS.items():
        put(f"assets/{NS}/models/item/{name}.json", {
            "parent": "minecraft:item/generated",
            "textures": {"layer0": f"{NS}:item/{name}"},
        })
        put(f"assets/{NS}/items/{name}.json", {
            "model": {"type": "minecraft:model", "model": f"{NS}:item/{name}"},
        })

        # Blaze rods for the shaft, amethyst to focus it, and one to three of
        # the thing that only comes off something that fought back. This is the
        # other route to a wand and it is paid in effort rather than emeralds
        # -- but not in pennies, or the five-figure shelf price is a joke.
        put(f"data/{NS}/recipe/{name}.json", {
            "type": "minecraft:crafting_shaped",
            "category": "equipment",
            "pattern": WAND_PATTERNS[cores],
            "key": {
                "A": "minecraft:amethyst_shard",
                "C": core,
                "R": "minecraft:blaze_rod",
            },
            "result": {"id": f"{NS}:{name}", "count": 1},
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
                "marquee": f"{NS}:block/slot_marquee",
                "particle": f"{NS}:block/slot_body",
            },
            "elements": [
                # The head fills the cube to half a pixel: the carrier says
                # solid, so the shell may not leave a sightline through.
                box([0.5, 0, 0.5], [15.5, 13, 15.5], "body"),         # cabinet head
                box([0.3, 1.5, 0.1], [15.7, 11.5, 0.6], "glass"),     # front glass
                box([0.3, 1.5, 15.4], [15.7, 11.5, 15.9], "glass"),   # back glass
                # The marquee band wears the chasing lamps; its lid stays
                # brass so the machine reads metal from above.
                box([0, 13, 0], [16, 16, 16], "marquee", up="trim"),  # marquee
                box([0.4, 0.8, -0.1], [15.6, 1.6, 16.1], "trim"),     # lower brass lip
                # A bezel round the glass, so the reels sit in a window
                # instead of being painted on the front of a box.
                box([0.2, 11.3, -0.6], [15.8, 12.6, 0.7], "trim", up="trim"),
                box([0.2, 0.6, -0.6], [15.8, 1.9, 0.7], "trim", down="trim"),
                box([0, 0.6, -0.6], [1.6, 12.6, 0.7], "trim"),
                box([14.4, 0.6, -0.6], [16, 12.6, 0.7], "trim"),
                # Lamp columns up the corners and a lit sign on the marquee.
                box([-0.7, 1.2, -0.7], [0.7, 12.4, 0.7], "glass"),
                box([15.3, 1.2, -0.7], [16.7, 12.4, 0.7], "glass"),
                box([2.5, 13.4, -0.9], [13.5, 15.6, 0.4], "marquee",
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
            # Half-pixel insets, not whole ones: the shell has to read as a
            # cabinet with mouldings, but the carrier says solid cube, so
            # every sightline through the block must die on geometry.
            box([0.5, 0, 0.5], [15.5, 12, 15.5], "body"),         # cabinet
            box([0.3, 12, 0.3], [15.7, 14, 15.7], "deck", up="deck"),   # sloped deck
            box([0, 14, 2], [16, 16, 14], "trim", up="trim"),     # console lip
            # Brass edge bands seal the top corners the lip leaves open,
            # front and back -- the coin-drop rail on a real console.
            box([0, 14, 0.2], [16, 16, 1.2], "trim", up="trim"),
            box([0, 14, 14.8], [16, 16, 15.8], "trim", up="trim"),
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
    put(f"assets/{NS}/blockstates/slot_machine.json", {
        "variants": {
            **facing_variants(f"{NS}:block/slot_machine_lower", half="lower"),
            **facing_variants(f"{NS}:block/slot_machine_upper", half="upper"),
        },
    })
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


def club_assets() -> None:
    # Explicit geometry rather than parenting to cube_all. A vanilla parent
    # carries no elements of its own, so check_models reads the shell as empty
    # and -- correctly -- warns that every sightline goes straight through a
    # FULL_BLOCK carrier. Six faces, spelled out.
    put(f"assets/{NS}/models/block/nightclub.json", {
        "parent": "minecraft:block/block",
        "textures": {"all": f"{NS}:block/nightclub",
                     "particle": f"{NS}:block/nightclub"},
        "elements": [{
            "from": [0, 0, 0], "to": [16, 16, 16],
            "faces": {face: {"uv": [0, 0, 16, 16], "texture": "#all",
                             "cullface": face}
                      for face in ("north", "south", "east", "west", "up", "down")},
        }],
    })
    put(f"assets/{NS}/models/item/nightclub.json", {"parent": f"{NS}:block/nightclub"})
    put(f"assets/{NS}/items/nightclub.json", {
        "model": {"type": "minecraft:model", "model": f"{NS}:item/nightclub"},
    })
    put(f"data/{NS}/loot_table/blocks/nightclub.json", {
        "type": "minecraft:block",
        "pools": [{"rolls": 1, "entries": [
            {"type": "minecraft:item", "name": f"{NS}:nightclub"}]}],
    })
    # A note block for the thump, wool to deaden the room, redstone lamps for
    # the lights, and a diamond because a club is not a thing you open cheaply.
    put(f"data/{NS}/recipe/nightclub.json", {
        "type": "minecraft:crafting_shaped",
        "category": "misc",
        "pattern": ["WLW", "NDN", "WLW"],
        "key": {"W": "#minecraft:wool", "L": "minecraft:redstone_lamp",
                "N": "minecraft:note_block", "D": "minecraft:diamond_block"},
        "result": {"id": f"{NS}:nightclub", "count": 1},
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


def till_assets() -> None:
    """A register standing on a counter, not a cube with a picture of one.

    The counter you lean on, the brass body, the key deck and the display
    standing up behind it -- with an overhead shelf across the top.

    The shelf is not decoration. A FULL_BLOCK carrier whose model leaves the up
    face open shows the sky through it (check_models fails on exactly this),
    so the top has to be covered by something. A canopy over the counter is
    what a shop has anyway, and it gives the till the same silhouette the
    stall's awning does, which is what makes a street of them read as one
    trade rather than three mods.
    """
    put(f"assets/{NS}/models/block/shop_till.json", {
        "parent": "minecraft:block/block",
        "ambientocclusion": False,
        "textures": {
            "counter": f"{NS}:block/shop_till_side",
            "top": f"{NS}:block/shop_till_top",
            "body": f"{NS}:block/shop_till_front",
            "keys": f"{NS}:block/shop_till_keys",
            "screen": f"{NS}:block/shop_till_screen",
            "particle": f"{NS}:block/shop_till_side",
        },
        "elements": [
            box([0, 0, 0], [16, 10, 16], "counter", up="top"),    # the counter
            # The register FILLS the bay between counter and canopy rather
            # than standing in the middle of it with air all round.
            #
            # That air was the bug in the screenshot: a FULL_BLOCK carrier
            # tells the client this is a solid cube, so it culls the faces of
            # the floor and the wall behind -- and then the open band between
            # counter and shelf is a window onto a world with nothing drawn in
            # it. A till against a wall showed the landscape through itself.
            box([0, 10, 0], [16, 15, 16], "body", up="body"),     # the register
            # Key deck and display stand proud of the FRONT, which is the whole
            # point of the till having a front to be placed facing with.
            box([2.5, 12.4, -0.9], [13.5, 13.8, 0.6], "keys", up="keys"),
            box([4, 13.8, -0.7], [12, 15, 0.5], "screen"),
            box([0, 15, 0], [16, 16, 16], "counter", up="top"),   # overhead shelf
        ],
    })
    put(f"assets/{NS}/models/item/shop_till.json", {"parent": f"{NS}:block/shop_till"})
    put(f"assets/{NS}/items/shop_till.json", {
        "model": {"type": "minecraft:model", "model": f"{NS}:item/shop_till"},
    })
    put(f"data/{NS}/loot_table/blocks/shop_till.json", {
        "type": "minecraft:block",
        "pools": [{"rolls": 1, "entries": [
            {"type": "minecraft:item", "name": f"{NS}:shop_till"}]}],
    })
    # Planks round gold and an emerald: a counter with a register on it.
    put(f"data/{NS}/recipe/shop_till.json", {
        "type": "minecraft:crafting_shaped",
        "category": "misc",
        "pattern": ["PGP", "PEP", "PPP"],
        "key": {"P": "#minecraft:planks", "G": "minecraft:gold_ingot",
                "E": "minecraft:emerald"},
        "result": {"id": f"{NS}:shop_till", "count": 1},
    })


def laundry_assets() -> None:
    """The drum, its three faces, and the money that goes in it."""
    put(f"assets/{NS}/models/item/dirty_emerald.json", {
        "parent": "minecraft:item/generated",
        "textures": {"layer0": f"{NS}:item/dirty_emerald"},
    })
    put(f"assets/{NS}/items/dirty_emerald.json", {
        "model": {"type": "minecraft:model", "model": f"{NS}:item/dirty_emerald"},
    })

    # Nine to a block and back, exactly like emeralds. The whole point is that
    # it behaves like money you can carry; a one-way recipe would be a trap.
    # Spelled out rather than parented to cube_all: check_models reads the
    # elements to prove a FULL_BLOCK carrier is actually solid, and an
    # inherited cube has no elements to read.
    put(f"assets/{NS}/models/block/dirty_emerald_block.json", {
        "parent": "minecraft:block/block",
        "textures": {
            "all": f"{NS}:block/dirty_emerald_block",
            "particle": f"{NS}:block/dirty_emerald_block",
        },
        "elements": [box([0, 0, 0], [16, 16, 16], "all")],
    })
    put(f"assets/{NS}/models/item/dirty_emerald_block.json",
        {"parent": f"{NS}:block/dirty_emerald_block"})
    put(f"assets/{NS}/items/dirty_emerald_block.json", {
        "model": {"type": "minecraft:model", "model": f"{NS}:item/dirty_emerald_block"},
    })
    put(f"data/{NS}/loot_table/blocks/dirty_emerald_block.json", {
        "type": "minecraft:block",
        "pools": [{"rolls": 1, "entries": [
            {"type": "minecraft:item", "name": f"{NS}:dirty_emerald_block"}]}],
    })
    put(f"data/{NS}/recipe/dirty_emerald_block.json", {
        "type": "minecraft:crafting_shaped",
        "category": "misc",
        "pattern": ["DDD", "DDD", "DDD"],
        "key": {"D": f"{NS}:dirty_emerald"},
        "result": {"id": f"{NS}:dirty_emerald_block", "count": 1},
    })
    put(f"data/{NS}/recipe/dirty_emerald_from_block.json", {
        "type": "minecraft:crafting_shapeless",
        "category": "misc",
        "ingredients": [f"{NS}:dirty_emerald_block"],
        "result": {"id": f"{NS}:dirty_emerald", "count": 9},
    })
    for name in ("laundry_empty", "laundry_running", "laundry_done"):
        put(f"assets/{NS}/models/block/{name}.json", {
            "parent": "minecraft:block/block",
            "textures": {
                "all": f"{NS}:block/{name}",
                "particle": f"{NS}:block/{name}",
            },
            "elements": [{
                "from": [0, 0, 0],
                "to": [16, 16, 16],
                "faces": {side: {"uv": [0, 0, 16, 16], "texture": "#all"}
                          for side in ("north", "south", "east", "west", "up", "down")},
            }],
        })
    put(f"assets/{NS}/models/item/laundry.json", {"parent": f"{NS}:block/laundry_empty"})
    put(f"assets/{NS}/items/laundry.json", {
        "model": {"type": "minecraft:model", "model": f"{NS}:item/laundry"},
    })
    put(f"data/{NS}/loot_table/blocks/laundry.json", {
        "type": "minecraft:block",
        "pools": [{"rolls": 1, "entries": [
            {"type": "minecraft:item", "name": f"{NS}:laundry"}]}],
    })
    # A cauldron in an iron frame with a bucket of water. It is a washing
    # machine; it should look like somebody built a washing machine.
    put(f"data/{NS}/recipe/laundry.json", {
        "type": "minecraft:crafting_shaped",
        "category": "misc",
        "pattern": ["III", "ICI", "IWI"],
        "key": {"I": "minecraft:iron_ingot", "C": "minecraft:cauldron",
                "W": "minecraft:water_bucket"},
        "result": {"id": f"{NS}:laundry", "count": 1},
    })


def shelf_assets() -> None:
    """Actual shelving: a frame, two boards, and goods sitting on them.

    The old one was a cube wearing a drawing of shelves, which is fine in a
    screenshot and wrong the moment you stand next to a row of them. Uprights
    and a back panel give it depth from the side, which is how you see most of
    a shop -- you walk along the aisle, not at it.

    Timber matches the till and the stall on purpose. A street of the three
    should read as one trade.
    """
    put(f"assets/{NS}/models/block/market_shelf.json", {
        "parent": "minecraft:block/block",
        "ambientocclusion": False,
        "textures": {
            "frame": f"{NS}:block/market_shelf_side",
            "back": f"{NS}:block/market_shelf_front",
            "board": f"{NS}:block/market_shelf_board",
            "goods": f"{NS}:block/market_shelf_stock",
            "particle": f"{NS}:block/market_shelf_side",
        },
        "elements": [
            box([0, 0, 0], [16, 1, 16], "frame", up="board"),     # the base
            box([0, 1, 14], [16, 15, 16], "back", up="frame"),    # the back panel
            box([0, 1, 0], [2, 15, 14], "frame", up="frame"),     # left upright
            box([14, 1, 0], [16, 15, 14], "frame", up="frame"),   # right upright
            box([2, 7, 1], [14, 8, 14], "board", up="board"),     # middle board
            box([2, 1, 2], [14, 7, 13], "goods", up="goods"),     # goods, lower
            box([2, 8, 2], [14, 15, 13], "goods", up="goods"),    # goods, upper
            box([0, 15, 0], [16, 16, 16], "board", up="board"),   # the top board
        ],
    })
    put(f"assets/{NS}/models/item/market_shelf.json", {"parent": f"{NS}:block/market_shelf"})
    put(f"assets/{NS}/items/market_shelf.json", {
        "model": {"type": "minecraft:model", "model": f"{NS}:item/market_shelf"},
    })
    put(f"data/{NS}/loot_table/blocks/market_shelf.json", {
        "type": "minecraft:block",
        "pools": [{"rolls": 1, "entries": [
            {"type": "minecraft:item", "name": f"{NS}:market_shelf"}]}],
    })
    # Planks and a barrel: it is shelving, and cheap on purpose. A supermarket
    # is meant to be a row of them, which nobody builds at four emeralds each.
    put(f"data/{NS}/recipe/market_shelf.json", {
        "type": "minecraft:crafting_shaped",
        "category": "misc",
        "pattern": ["PPP", "PBP", "PPP"],
        "key": {"P": "#minecraft:planks", "B": "minecraft:barrel"},
        "result": {"id": f"{NS}:market_shelf", "count": 2},
    })


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


def hospital_assets() -> None:
    """A tiled panel with a red cross on it, spelled out as a solid cube.

    Same reasoning as the vault: it claims a FULL_BLOCK carrier, so it has to
    BE a full block or check_models.py finds the X-ray hole -- and the elements
    are written out here rather than inherited from block/cube because the
    checker measures coverage off a model's own geometry.
    """
    put(f"assets/{NS}/models/block/hospital.json", {
        "parent": "minecraft:block/block",
        "textures": {
            "face": f"{NS}:block/hospital_face",
            "side": f"{NS}:block/hospital_side",
            "top": f"{NS}:block/hospital_top",
            "particle": f"{NS}:block/hospital_side",
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
    put(f"assets/{NS}/models/item/hospital.json", {"parent": f"{NS}:block/hospital"})
    put(f"assets/{NS}/items/hospital.json", {
        "model": {"type": "minecraft:model", "model": f"{NS}:item/hospital"},
    })
    put(f"data/{NS}/loot_table/blocks/hospital.json", {
        "type": "minecraft:block",
        "pools": [{"rolls": 1, "entries": [
            {"type": "minecraft:item", "name": f"{NS}:hospital"}]}],
    })
    # Quartz for the tiling, a lamp because a ward is lit, and a golden apple
    # in the middle -- the one thing in vanilla that already means "cures a
    # villager of this exact illness", and dear enough that a hospital is a
    # thing a city saves up for rather than the first block anybody places.
    put(f"data/{NS}/recipe/hospital.json", {
        "type": "minecraft:crafting_shaped",
        "category": "misc",
        "pattern": ["QLQ", "QAQ", "QQQ"],
        "key": {"Q": "minecraft:quartz_block", "L": "minecraft:lantern",
                "A": "minecraft:golden_apple"},
        "result": {"id": f"{NS}:hospital", "count": 1},
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
    """Make the hammer enchantable, and the tool-required blocks minable.

    Enchanting eligibility in 1.21 is tag-driven: Item.Settings.enchantable()
    sets how GOOD the enchants are, but the table only offers them if the item
    is in these tags. Setting one without the other silently does nothing.
    Tags merge across mods, so adding to minecraft: namespace is correct here.

    requiresTool() is the same shape of trap: it says "a correct tool drops
    this", and correctness is decided by the pickaxe's tool component, which
    only matches blocks in mineable/pickaxe. A block that requires a tool and
    is in no mineable tag has no correct tool in the game -- it mines at bare
    hand speed and drops NOTHING, whatever you hit it with.
    """
    for tag in ("mining", "mining_loot", "durability", "vanishing"):
        put(f"data/minecraft/tags/item/enchantable/{tag}.json", {
            "values": [f"{NS}:miners_hammer"],
        })

    # Every block registered with requiresTool(). Any pickaxe drops them --
    # no needs_iron_tool, these are shop fittings you should be able to move.
    #
    # The rest of these lists are SPEED, not drops: a block in no mineable tag
    # ignores every tool and breaks at bare-hand pace however sharp your axe
    # is. Polymer already mines these server-side against the real block, so
    # the tag is the whole difference between "pickaxe works on the metal
    # machine" and "nothing works on anything".
    put("data/minecraft/tags/block/mineable/pickaxe.json", {
        "values": [f"{NS}:{b}" for b in (
            "wash_pot", "acetylator", "city_vault", "police",
            "dirty_emerald_block", "laundry",
            # Metal machines without requiresTool: any hand drops them, a
            # pickaxe just stops pretending it's no better than a fist.
            "slot_machine", "climb", "refiner",
        )],
    })

    # The wooden furniture: tables, counters, cabinets, racks. An axe takes
    # them down at axe speed, exactly like a crafting table.
    put("data/minecraft/tags/block/mineable/axe.json", {
        "values": [f"{NS}:{b}" for b in (
            "toss", "blackjack", "scratch", "roulette", "plinko", "casino_bar",
            "market_stall", "market_shelf", "shop_till", "nightclub",
            "mailbox", "mixing_station", "drying_rack", "scoring_table",
            "leaf_press",
        )],
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
    poppy_assets()
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
    bar_assets()
    # Paper and gold leaf on red: a newsagent's counter, not a card table.
    table_assets("scratch", "scratch_top", ["AGA", "WWW", "PPP"],
                 {"A": "minecraft:paper", "G": "minecraft:gold_ingot",
                  "P": "#minecraft:planks", "W": "minecraft:red_wool"},
                 scratch_furniture())
    advancements()
    wand_assets()
    phone_assets()
    stall_assets()
    club_assets()
    mailbox_assets()
    hospital_assets()
    vault_assets()
    shelf_assets()
    laundry_assets()
    till_assets()
    slot_assets()
    tags()
    worldgen()
    recipes()
    print(f"wrote {written} json files under {ROOT}")


if __name__ == "__main__":
    main()
