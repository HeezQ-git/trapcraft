#!/usr/bin/env python3
"""Generate every TrapCraft texture as 16x16 pixel art.

Re-runnable: `python3 tools/gen_textures.py`. Edit PALETTE or the ASCII maps
below and re-run -- the PNGs are build output, not hand-authored art, so never
edit them directly or the next run overwrites you.

Each sprite is an ASCII map; characters index into a palette. '.' is
transparent. Per-strain sprites recolor the SAME map, which is why the three
strains read as the same plant in different phenotypes.
"""

from pathlib import Path
from PIL import Image

OUT = Path(__file__).resolve().parent.parent / "src/main/resources/assets/trapcraft/textures"

# ---------------------------------------------------------------- palettes

# Per strain: (dark, mid, light, accent) -- accent is the bud/pistil colour.
STRAINS = {
    "kush":  ("#1d3b1a", "#2f6b28", "#4a9a3c", "#8a5fa8"),
    "haze":  ("#3d4d16", "#6b8f24", "#9ec43a", "#d8c65a"),
    "purp":  ("#2a1b3d", "#4b2f6b", "#7a4fa8", "#c47fd8"),
    # Hybrids -- only obtainable by cross-breeding. Deliberately pulled away
    # from the parent hues so a hybrid never gets mistaken for one of them.
    "diesel":   ("#2a3d2a", "#4d6b45", "#7fa86a", "#d8d8a0"),
    "midnight": ("#141428", "#2a2a52", "#4a4a8a", "#8a8ad8"),
    "sunset":   ("#4d2a14", "#8a4d1f", "#c47f3a", "#f0c060"),
}

COMMON = {
    "s": "#3b2a16",  # stem / dark wood
    "S": "#5c4423",  # light wood
    "d": "#4a3419",  # mid wood, plank faces
    "D": "#54401f",  # second plank tone -- breaks up flat slabs of colour
    "n": "#241a0e",  # shadowed interior of the rack
    "N": "#2f2213",  # slightly lifted interior, for depth
    "w": "#e8e4d8",  # rolling paper
    "W": "#f7f5ee",  # paper highlight
    "e": "#ff7a2a",  # ember
    "E": "#ffd24a",  # ember hot
    "k": "#1a1a1a",  # outline
    "g": "#6b6b6b",  # string / grey
    "m": "#5a6070",  # hammer steel, shadow
    "M": "#8d94a6",  # hammer steel, body
    "L": "#c8ccd8",  # hammer steel, highlight
    "c": "#2f5f2a",  # coca leaf, shadow
    "C": "#4f8f42",  # coca leaf, body
    "V": "#7fbf68",  # coca leaf, highlight
    "p": "#8a7a5a",  # paste, dull
    "P": "#b5a888",  # paste, lit
    "u": "#d8d8e4",  # powder, shadow
    "U": "#f4f4fa",  # powder, lit
    "t": "#4a4a52",  # machine metal, dark
    "T": "#71717d",  # machine metal, light
    "q": "#7fb0c8",  # glass, shadow
    "Q": "#b8dceb",  # glass, lit
    "R": "#e8f6ff",  # glass, highlight
    "z": "#2a6ea8",  # water
    "Z": "#4a9ad8",  # water, lit
    "b": "#8a6a3a",  # seed brown
    "B": "#c9a86a",  # seed light
}

# ---------------------------------------------------------------- sprites
# 1/2/3 = dark/mid/light strain colour, 4 = accent, plus COMMON keys.

CROP_AGE0 = """
................
................
................
................
................
................
................
................
................
.......3........
......323.......
.......s........
.......s........
................
................
................
"""

CROP_AGE1 = """
................
................
................
................
................
................
.......3........
......323.......
.....32.23......
....3..s..3.....
.....2.s.2......
.......s........
.......s........
.......s........
................
................
"""

CROP_AGE2 = """
................
................
................
.......3........
......323.......
.....32.23......
....3.....3.....
...32..s..23....
..3....s....3...
...2.3.s.3.2....
....32.s.23.....
.......s........
.......s........
.......s........
.......s........
................
"""

# Mature: same silhouette, '4' marks the bud sites that take the accent colour.
CROP_AGE3 = """
................
.......4........
......434.......
.....3.4.3......
....323.323.....
...3..414..3....
..32...4...23...
.3....s4s....3..
..3.4.sss.4.3...
...32.s4s.23....
....3.sss.3.....
.....2.s.2......
.......s........
.......s........
.......s........
.......s........
"""

# Three seeds, each outlined dark with a highlight down one side and a speck
# of the strain colour. The old version was flat brown ovals with no outline,
# which vanished against dirt-toned inventory backgrounds.
SEEDS = """
................
....ss..........
...sB3s.........
...sB33s........
...sbbbs........
....sss.........
.........ss.....
........sB3s....
........sB33s...
........sbbbs...
.........sss....
...ss...........
..sB3s..........
..sB33s.........
..sbbbs.........
...sss..........
"""

# Fresh bud: a rounded cluster lit from the upper left -- light (3) on the top
# left, mid (2) through the body, dark (1) falling away bottom right, with a
# few accent pixels (4) standing in for pistils. The old version alternated
# 1 and 4 in a checkerboard, which at 16x16 just read as noise.
RAW_BUD = """
................
.......33.......
......3342......
.....333422.....
.....332221.....
....33342221....
....33222211....
...3334222111...
...3322422111...
....33222211....
....33422211....
.....332211.....
......3221......
.......ss.......
.......ss.......
................
"""

# Cured bud: same construction, but tighter, a tone darker throughout, and
# speckled with gold (B) for trichomes. Reads as denser and older next to the
# fresh one, which is the whole point of having both.
DRIED_BUD = """
................
................
.......22.......
......2B21......
.....222211.....
.....221111.....
....22B21111....
....22211111....
....2221B111....
....22111111....
.....221111.....
.....B21111.....
......2211......
.......s........
................
................
"""

# The three joints used to be pixel-identical -- this map only touched the
# paper/ember/stem palette and never the strain colours, so all you could tell
# apart was the name. Now the band ('3') and the filter ('1') carry the strain.
JOINT = """
................
............eE..
...........eEe..
..........wWe...
.........wWw....
........wWw.....
.......3W3......
......3W3.......
.....wWw........
....wWw.........
...wWw..........
..1Ww...........
..11w...........
.11.............
................
................
"""

# All three rack faces are fully opaque. They used to be hollow frames with a
# transparent middle, which on a FULL_BLOCK model renders as a black void --
# the block read as a dark cube rather than a rack.

# Occupied: two string rails with three shaded bud clusters hanging off each.
# A/B/C are placeholders resolved per drying stage by DRY_STAGES below, so the
# rack visibly cures instead of looking identical until you right-click it.
DRYING_RACK_FRONT = """
ssssssssssssssss
sSSSSSSSSSSSSSSs
sSggggggggggggSs
sSNANNNANNNANNSs
sSABANABANABANSs
sSBCBNBCBNBCBNSs
sSNCNNNCNNNCNNSs
sSggggggggggggSs
sSNANNNANNNANNSs
sSABANABANABANSs
sSBCBNBCBNBCBNSs
sSNCNNNCNNNCNNSs
sSdDddDddDddDdSs
sSNNNNNNNNNNNNSs
sSSSSSSSSSSSSSSs
ssssssssssssssss
"""

# Fresh and plump -> shrinking and darkening -> cured with gold trichome tips.
# Stage 3 is the "come collect me" state, deliberately the odd one out.
DRY_STAGES = [
    {"A": "3", "B": "2", "C": "1"},   # 0 just hung, brightest
    {"A": "3", "B": "1", "C": "1"},   # 1
    {"A": "2", "B": "1", "C": "1"},   # 2 darkening
    {"A": "B", "B": "1", "C": "1"},   # 3 READY: gold tips on dark bud
    {"A": "b", "B": "s", "C": "s"},   # 4 overdried: all colour gone, brittle
]

# --- 3D plants ------------------------------------------------------------
#
# The crop sprites above are pictures of a whole plant, drawn to be shown on a
# two-quad billboard. These are the parts a real model is built from: one leaf,
# one length of stalk, one mass of bud. Recoloured per strain by the same
# palette machinery, so a Purp plant and a Haze plant are the same plant.

# A five-bladed fan converging on the stalk. Drawn as a silhouette rather than
# with blade-by-blade detail: at a metre away in game the MASS is what reads,
# and pixel filigree just turns to noise.
CROP_LEAF = """
.......3........
....3..3..3.....
....33.3.33.....
...33.333.33....
.2.33.333.33.2..
.22.3333333.22..
..223333333322..
...2333333332...
....23333332....
.....233332.....
......2332......
.......32.......
.......s........
.......s........
.......s........
.......s........
"""

# Stalk. Deliberately uniform across its width -- the stem element is two
# pixels wide, so it only ever samples a narrow vertical strip of this, and any
# strip has to look the same as any other.
CROP_STEM = """
s2s22s2s2s22s2s2
2s22s2s22s2s2s22
s22s2s2s2s22s2s2
22s2s22s2s2s22s2
s2s22s2s2s22s2s2
2s2s2s22s2s22s2s
s22s2s2s22s2s2s2
2s2s22s2s2s2s22s
s2s2s2s22s2s2s2s
22s2s2s2s2s22s2s
s2s22s2s2s2s2s22
2s2s2s2s22s2s2s2
s22s2s22s2s2s2s2
2s2s2s2s2s22s2s2
s2s22s2s2s2s2s22
2s2s2s22s2s2s2s2
"""

# Bud mass for the mature plant's colas. Accent pixels are the pistils.
CROP_BUD = """
2324232232423242
3232423232324232
2423232423232324
3232432323242323
2324232324232432
4232324232323223
2323243232432324
3242323242323232
2323232323243232
3232423232323424
2432323242323232
3232324232432323
2323432323232432
4232323242323223
2324232323243232
3232423232323242
"""

# Coca leaves are simple ovals, nothing like a cannabis fan -- the two crops
# should not be mistakable for each other at a glance across a field.
COCA_PLANT_LEAF = """
................
.......C........
......CVC.......
.....CVVVC......
....cCVVVCc.....
...cCVVVVVCc....
...cCVVVVVCc....
....cCVVVCc.....
.....cCVCc......
......ccc.......
.......c........
.......s........
.......s........
.......s........
.......s........
.......s........
"""

# Materials for the 3D rack. Unlike DRYING_RACK_FRONT -- which is a picture of
# a whole rack painted on a cube face -- these tile across small model elements,
# so they're surfaces: dark cabinet interior, and a dense mat of bud.

# Interior of the recess. Deliberately near-black with a little grain: it's the
# shadowed back of a cabinet, and it's what makes the recess read as depth
# rather than as a flat panel of a different colour.
RACK_INNER = """
nNnnNnnnNnnNnnnN
NnnnNnnNnnnnNnnn
nnNnnnnNnnNnnnNn
nNnnnNnnnnNnnnnn
nnnnNnnnNnnNnnNn
NnnNnnnnnnnnNnnn
nnnnnNnnNnnnnNnn
nNnnNnnnnNnnNnnn
nnNnnnnNnnnnnnnN
nnnNnnNnnnNnnNnn
NnnnnnnnNnnnnnnn
nnNnnNnnnnNnnnNn
nnnnNnnnnNnnnnnn
nNnnnnnNnnnnNnNn
nnnNnnnnnnNnnnnn
nnnnnNnnNnnnnnnN
"""

# A dense mat of hanging bud, filling the tile. A/B/C are the same drying-stage
# placeholders the flat rack faces use, so the cabinet and the old sprite can't
# disagree about what stage 3 looks like.
RACK_BUD = """
ABBACBBAABCBBAAB
BACBBACBBAACBBCA
ACBBAABCBBACBBAA
BBACBBAACBBAABCB
ABBAACBBAABCBBAC
CBBACBBAACBBAABB
BAACBBAABCBBACBB
ABCBBAACBBAABBAC
BBAABCBBACBBAACB
ACBBAABBACBBAABC
BBACBBAACBBAABBA
AABBACBBAABCBBAC
BCBBAACBBACBBAAB
ABBACBBAABBAACBB
BAACBBAACBBACBBA
CBBAABCBBAABBACB
"""

# Empty: the same frame with bare slats, so you can tell at a glance that
# there's nothing curing without counting pixels.
DRYING_RACK_SIDE = """
ssssssssssssssss
sSSSSSSSSSSSSSSs
sSNNNNNNNNNNNNSs
sSdDddDddDddDdSs
sSNNNNNNNNNNNNSs
sSNNNNNNNNNNNNSs
sSdDddDddDddDdSs
sSNNNNNNNNNNNNSs
sSNNNNNNNNNNNNSs
sSdDddDddDddDdSs
sSNNNNNNNNNNNNSs
sSNNNNNNNNNNNNSs
sSdDddDddDddDdSs
sSNNNNNNNNNNNNSs
sSSSSSSSSSSSSSSs
ssssssssssssssss
"""

# Top: plain planks with cross beams, so the rack reads as furniture from above.
DRYING_RACK_TOP = """
ssssssssssssssss
sSSSSSSSSSSSSSSs
sSddddddddddddSs
sSddddddddddddSs
sSSSSSSSSSSSSSSs
sSddddddddddddSs
sSddddddddddddSs
sSSSSSSSSSSSSSSs
sSddddddddddddSs
sSddddddddddddSs
sSSSSSSSSSSSSSSs
sSddddddddddddSs
sSddddddddddddSs
sSSSSSSSSSSSSSSs
sSddddddddddddSs
ssssssssssssssss
"""

# Miner's hammer. Vertical rather than vanilla's diagonal: the head needs the
# width to read as a sledge, and a diagonal one at 16x16 loses the flat face.
HAMMER = """
................
......mmmmmm....
.....mMMLLLMm...
.....mMMLLLMm...
.....mMMMLLMm...
.....mmmmmmmm...
....mmMm........
...sSm..........
...sSs..........
..sSs...........
..sSs...........
.sSs............
.sSs............
sSs.............
ss..............
................
"""

# --- coca line ----------------------------------------------------------
# Same construction as the cannabis crop so the two lines read as one mod:
# a stem with leaves, just a rounder leaf shape and its own green.
COCA_AGE0 = """
................
................
................
................
................
................
................
................
................
.......V........
......CVC.......
.......s........
.......s........
................
................
................
"""

COCA_AGE1 = """
................
................
................
................
................
................
.......V........
......CVC.......
.....cC.Cc......
......c.c.......
.......s........
.......s........
.......s........
................
................
................
"""

COCA_AGE2 = """
................
................
................
................
......VVV.......
.....CVCVC......
....cCC.CCc.....
....c..s..c.....
.....cCsCc......
......csc.......
.......s........
.......s........
.......s........
.......s........
................
................
"""

COCA_AGE3 = """
................
......VVV.......
.....VCCCV......
....VCC.CCV.....
...cCC...CCc....
...cC..V..Cc....
..cC..CVC..Cc...
..c..CC.CC..c...
...cC..s..Cc....
....cC.s.Cc.....
.....cCsCc......
......csc.......
.......s........
.......s........
.......s........
.......s........
"""

COCA_LEAVES = """
................
.......V........
......VVV.......
.....VCCCV......
....VCC.CCV.....
...VCC...CCV....
...VC..s..CV....
..VCC..s..CCV...
..VC...s...CV...
...cC..s..Cc....
....cC.s.Cc.....
.....cCsCc......
......ccc.......
.......s........
................
................
"""

COCA_PASTE = """
................
................
.....ppppp......
....pPPPPPp.....
...pPPppPPPp....
...pPppppPPp....
...pPppppPPp....
...pPPppPPPp....
....pPPPPPp.....
.....ppppp......
................
................
................
................
................
................
"""

COCA_POWDER = """
................
................
................
......uUu.......
.....uUUUu......
....uUUUUUu.....
...uUUUUUUUu....
...uUUUUUUUu....
....uUUUUUu.....
.....uUUUu......
......uuu.......
................
................
................
................
................
"""

# --- machine materials ----------------------------------------------------
#
# Tiling surfaces for the rebuilt press and refiner, replacing the old
# full-face pictures. Same rule as the glassware: these stretch across faces of
# very different sizes, so every one is a repeating material rather than a
# picture of a machine.

MACHINE_PAL = {
    "s": "#6f6f6f",   # smooth stone
    "S": "#8a8a8a",   # stone, lit
    "z": "#565656",   # stone, shadow
    "w": "#8a6a3c",   # oak beam
    "W": "#a5834e",   # oak, lit grain
    "v": "#63492a",   # oak, shadow
    "i": "#8d8f94",   # iron plate
    "I": "#b2b5ba",   # iron, lit
    "j": "#65686d",   # iron, shadow
    "r": "#3f3f46",   # rivet
    "g": "#4e8a3a",   # pulp, body
    "G": "#67ad4c",   # pulp, lit
    "h": "#356226",   # pulp, shadow
    "k": "#141414",   # interior void
    "K": "#1f1f1f",   # void, faint
    "b": "#8a4b32",   # firebrick
    "B": "#a35d3f",   # firebrick, lit
    "n": "#5f3222",   # firebrick, mortar
    "e": "#ff9a2b",   # ember
    "E": "#ffd166",   # ember, hot
    "d": "#c2410c",   # ember, dim
    "c": "#b46a3a",   # copper
    "C": "#d98b52",   # copper, lit
    "x": "#8a4e26",   # copper, shadow
    "p": "#7fb8c9a0",  # sight glass
    "P": "#a8dcecc0",  # sight glass, highlight
}

PRESS_STONE = """
ssSsssszsssSssss
sssssszsssssssSs
SsssssssssSsssss
sszsssssssssssss
ssssssSssssssszs
zsssssssssssssss
ssssSssszssssSss
ssssssssssssssss
sSsssszssssssssz
ssssssssSsssssss
sszsssssssssSsss
ssssSsssssszssss
ssssssssssssssss
zsssSssssszsssss
ssssssssSsssssss
sssszssssssssSss
"""

PRESS_WOOD = """
wWwwwvwwwwWwwwvw
wWwwwvwwwwWwwwvw
vvvvvvvvvvvvvvvv
wwWwwwwvwwwwWwww
wwWwwwwvwwwwWwww
wwWwwwwvwwwwWwww
vvvvvvvvvvvvvvvv
wWwwvwwwwWwwwwvw
wWwwvwwwwWwwwwvw
wWwwvwwwwWwwwwvw
vvvvvvvvvvvvvvvv
wwwWwwvwwwwWwwww
wwwWwwvwwwwWwwww
wwwWwwvwwwwWwwww
vvvvvvvvvvvvvvvv
wWwwwvwwwwWwwwvw
"""

PRESS_IRON = """
rIIIIIIIIIIIIIIr
IiiiiiiiiiiiiiiI
IijjiiiiiiiijjiI
IiiiiiiiiiiiiiiI
IiiiiIIIIIiiiiiI
IiiiIiiiiiIiiiiI
IiiiIiiiiiIiiiiI
IiiiIiiiiiIiiiiI
IiiiIiiiiiIiiiiI
IiiiiIIIIIiiiiiI
IiiiiiiiiiiiiiiI
IijjiiiiiiiijjiI
IiiiiiiiiiiiiiiI
IiiiiiiiiiiiiiiI
IiiiiiiiiiiiiiiI
rIIIIIIIIIIIIIIr
"""

PRESS_PULP = """
gGgghggGgghggGgg
GgghgggghggggghG
gghgggGggghgggGg
hggGgggghgggghgg
ggggghggggGggggh
gGggggghgggggGgg
gghgggggGgghgggg
hgggGgghggggggGg
gggggggggghggggg
gGgghgggGggggghg
ggggggghggGggggg
gghggGgggggghggg
Ggggggggghgggggg
ggghgggGgggggGgh
gggggghggggghggg
gGgghggGgghggGgg
"""

PRESS_VOID = """
kkkKkkkkkkkKkkkk
kkkkkkkKkkkkkkkk
KkkkkkkkkkkkkKkk
kkkkKkkkkkkkkkkk
kkkkkkkkKkkkkkkK
kKkkkkkkkkkkkkkk
kkkkkKkkkkkKkkkk
kkkkkkkkkkkkkkkk
kkKkkkkkkKkkkkkk
kkkkkkKkkkkkkkKk
Kkkkkkkkkkkkkkkk
kkkKkkkkkkkKkkkk
kkkkkkkkKkkkkkkk
kKkkkKkkkkkkkkkk
kkkkkkkkkkkKkkkk
kkkKkkkkkkkkkkKk
"""

REFINER_BRICK = """
nnnnnnnnnnnnnnnn
nbbbbbbBnbbbbbbn
nbbBbbbbnbbbbBbn
nbbbbbbbnbbbbbbn
nnnnnnnnnnnnnnnn
bbbnbbbbbbbnbbbb
bbBnbbbbbBbnbbbb
bbbnbbbbbbbnbbbb
nnnnnnnnnnnnnnnn
nbbbbBbbnbbbbbbn
nbbbbbbbnbbBbbbn
nbbbbbbbnbbbbbbn
nnnnnnnnnnnnnnnn
bbbnbbbbbbbnbbbb
bbbnbbBbbbbnbbBb
nnnnnnnnnnnnnnnn
"""

REFINER_COPPER = """
cCccccxcccccCccc
CccccccccxccccCc
ccccxccccccccccc
ccCccccccxcccccc
xxxxxxxxxxxxxxxx
cccCcccccccxcccc
ccccccxcccccccCc
cCcccccccccccccc
ccccccccxcccCccc
xxxxxxxxxxxxxxxx
ccxcccCccccccccc
cccccccccxcccccc
cCcccccccccCcccx
ccccxccccccccccc
xxxxxxxxxxxxxxxx
cCccccxcccccCccc
"""

REFINER_GLASS = """
pPppppppppppPppp
PppppppppppppppP
pppppppppppppppp
pppPppppppPppppp
pppppppppppppppp
PppppppppppppppP
pppppppppppppppp
ppppppPppppppppp
pppppppppppppppp
PppppppppppppppP
pppPppppppppPppp
pppppppppppppppp
pppppppppppppppp
PppppppppppppppP
pppppppppppppppp
pPppppppppppPppp
"""

# The ember plate. "A" is swapped per stage so the same map covers cold
# through peak without five hand-drawn copies.
REFINER_EMBER = """
AAdAAAAdAAAAdAAA
AdAAAAAAdAAAAAAd
dAAAAdAAAAAAdAAA
AAAAAAAAAAAAAAAA
AdAAAAdAAAAdAAAA
AAAAdAAAAAAAAAdA
AAdAAAAAAdAAAAAA
dAAAAAAdAAAAdAAA
AAAAdAAAAAAAAAAA
AAdAAAAAAAAdAAAd
AAAAAAdAAAAAAAAA
AdAAAAAAAdAAAAAA
AAAAdAAAAAAAdAAA
dAAAAAAAAdAAAAAA
AAAdAAAdAAAAAAdA
AAAAAAAAAAAAdAAA
"""


# Machines. Wooden press, metal refiner, so they're distinguishable at range.


# Leaves -> pressed pulp -> paste, so the press shows its stage like the rack.



# Idle -> warming -> nearly there -> PEAK (bright white) -> burnt (dark).

# --- smoking gear -------------------------------------------------------
# One bottle silhouette, filled differently per state, so the bong and the
# tlok read as the same family of glassware.
BOTTLE = """
.......qq.......
.......QQ.......
......qQQq......
......qAAq......
.....qAAAAq.....
.....qAAAAq.....
.....qAAAAq.....
....qAAAAAAq....
....qAAAAAAq....
....qAAAAAAq....
....qAAAAAAq....
....qAAAAAAq....
.....qAAAAq.....
.....qqqqqq.....
................
................
"""

BOTTLE_STAGES = {
    "dry":    {"A": "Q"},   # empty glass
    "wet":    {"A": "z"},   # water in
    "loaded": {"A": "Z"},   # water + a load, brighter
}

# --- nerve tonic ----------------------------------------------------------
#
# Tiling materials for the bottle model, not pictures of a bottle. Same rule
# as the glassware below: these stretch across faces of different sizes, so
# anything that reads as "an object" would distort on the neck.

TONIC_PAL = {
    "b": "#d6e8f0d0",   # apothecary glass, wall
    "B": "#eef8ffe0",   # glass, highlight down the rib
    "h": "#a8c6d4b8",   # glass, shadow
    "a": "#e8a83c",     # tonic, body -- honey amber
    "A": "#f7c766",     # tonic, lit
    "d": "#b87c1e",     # tonic, deep
    "c": "#a9764a",     # cork, body
    "C": "#c28f60",     # cork, lit grain
    "n": "#7d5433",     # cork, shadow
}

TONIC_GLASS = """
bbBbbbbhbbbbBbbb
bbBbbbbhbbbbBbbb
hhBhhhhhhhhhBhhh
bbBbbbbhbbbbBbbb
bbBbbbbhbbbbBbbb
bbBbbbbhbbbbBbbb
hhBhhhhhhhhhBhhh
bbBbbbbhbbbbBbbb
bbBbbbbhbbbbBbbb
bbBbbbbhbbbbBbbb
hhBhhhhhhhhhBhhh
bbBbbbbhbbbbBbbb
bbBbbbbhbbbbBbbb
bbBbbbbhbbbbBbbb
hhBhhhhhhhhhBhhh
bbBbbbbhbbbbBbbb
"""

TONIC_LIQUID = """
aaAaaaadaaaaAaaa
aaAaaaadaaaaAaaa
aaaaaaadaaaaaaaa
ddaaaaaaaaaaaadd
aaaaAaaaaaAaaaaa
aaaaAaaaaaAaaaaa
aaaaaaaaaaaaaaaa
aadaaaaaaaaadaaa
aaAaaaadaaaaAaaa
aaAaaaadaaaaAaaa
aaaaaaadaaaaaaaa
ddaaaaaaaaaaaadd
aaaaAaaaaaAaaaaa
aaaaAaaaaaAaaaaa
aaaaaaaaaaaaaaaa
aadaaaaaaaaadaaa
"""

TONIC_CORK = """
cCccccncccCccccn
cCccccncccCccccn
nnnnnnnnnnnnnnnn
ccCcccccnccCcccc
ccCcccccnccCcccc
cccccccccccccccc
nncnnncnnncnnncn
cCccccncccCccccn
cCccccncccCccccn
nnnnnnnnnnnnnnnn
ccCcccccnccCcccc
ccCcccccnccCcccc
cccccccccccccccc
nncnnncnnncnnncn
cCccccncccCccccn
cCccccncccCccccn
"""


# --- slot machine ---------------------------------------------------------

SLOT_PAL = {
    "r": "#8e2020",   # cabinet lacquer
    "R": "#b83030",   # lacquer, lit
    "d": "#5e1414",   # lacquer, shadow
    "y": "#c9992e",   # brass
    "Y": "#f2d271",   # brass, lit
    "o": "#8a6a1c",   # brass, shadow
    "k": "#120e12",   # glass surround
    "b": "#1b2a3a",   # screen glass
    "B": "#2f4a63",   # glass, lit
    "w": "#e8e4d8",   # marquee lamp
    "g": "#54d37a",   # win lamp
    "e": "#c8ccd8",   # chrome
}

SLOT_BODY = """
dRRRRRRRRRRRRRRd
RrrrrrrrrrrrrrrR
RrrdrrrrrrrdrrrR
RrrrrrrrrrrrrrrR
RrrrrrrrrrrrrrrR
RrdrrrrrrrrrrdrR
RrrrrrrrrrrrrrrR
RrrrrrrrrrrrrrrR
RrrrrdrrrrrrrrrR
RrrrrrrrrrrrrrrR
RrdrrrrrrrrdrrrR
RrrrrrrrrrrrrrrR
RrrrrrrrrrrrrrrR
RrrrdrrrrrrrrrrR
RrrrrrrrrrrrrrrR
dRRRRRRRRRRRRRRd
"""

SLOT_SCREEN = """
kkkkkkkkkkkkkkkk
kbbbbbbbbbbbbbbk
kbBBbbBBbbBBbbbk
kbBbbbBbbbBbbbbk
kbBBbbBBbbBBbbbk
kbbbbbbbbbbbbbbk
kbBBbbBBbbBBbbbk
kbBbbbBbbbBbbbbk
kbBBbbBBbbBBbbbk
kbbbbbbbbbbbbbbk
kbBBbbBBbbBBbbbk
kbBbbbBbbbBbbbbk
kbBBbbBBbbBBbbbk
kbbbbbbbbbbbbbbk
kbbbbbbbbbbbbbbk
kkkkkkkkkkkkkkkk
"""

SLOT_TRIM = """
oyyyyyyyyyyyyyyo
yYYYYYYYYYYYYYYy
yYwwYYgYYgYYwwYy
yYYYYYYYYYYYYYYy
oyyyyyyyyyyyyyyo
yYYYYYYYYYYYYYYy
yYwwYYgYYgYYwwYy
yYYYYYYYYYYYYYYy
oyyyyyyyyyyyyyyo
yYYYYYYYYYYYYYYy
yYwwYYgYYgYYwwYy
yYYYYYYYYYYYYYYy
oyyyyyyyyyyyyyyo
yYYYYYYYYYYYYYYy
yYYYYYYYYYYYYYYy
oyyyyyyyyyyyyyyo
"""

SLOT_DECK = """
eeeeeeeeeeeeeeee
eyyyyyyyyyyyyyye
eyrrrrrrrrrrrrye
eyrkkkkkkkkkkrye
eyrkbbbkbbbkbrye
eyrkbBbkbBbkbrye
eyrkbbbkbbbkbrye
eyrkkkkkkkkkkrye
eyrrrrrrrrrrrrye
eyyyyyyyyyyyyyye
eyggyyyyyyyyggye
eyyyyyyyyyyyyyye
eyrrrrrrrrrrrrye
eyyyyyyyyyyyyyye
eeeeeeeeeeeeeeee
eeeeeeeeeeeeeeee
"""


# --- market stall ---------------------------------------------------------

STALL_PAL = {
    "w": "#7a5a34",   # counter timber
    "W": "#96703f",   # timber, lit
    "d": "#5c4326",   # timber, shadow
    "c": "#b23b3b",   # awning stripe, red
    "C": "#d45252",   # awning, lit
    "n": "#e8e2d2",   # awning stripe, cream
    "N": "#f6f2e6",   # awning, lit cream
    "g": "#3f7a33",   # produce
    "e": "#3ba55c",   # emerald
    "E": "#6ee49a",   # emerald, lit
    "k": "#2b2018",   # shadow under the counter
}

STALL_COUNTER = """
WWWWWWWWWWWWWWWW
wwwwwwwwwwwwwwww
wdwwwwwdwwwwwdww
wwwwwwwwwwwwwwww
dddddddddddddddd
wwwwwwwwwwwwwwww
wwdwwwwwwwdwwwww
wwwwwwwwwwwwwwww
dddddddddddddddd
wwwwwwwwwwwwwwww
wdwwwwwdwwwwwdww
wwwwwwwwwwwwwwww
dddddddddddddddd
wwwwwwwwwwwwwwww
wwwwwdwwwwwwwdww
kkkkkkkkkkkkkkkk
"""

STALL_AWNING = """
CCCCnnnnCCCCnnnn
ccccNNNNccccNNNN
ccccnnnnccccnnnn
ccccnnnnccccnnnn
CCCCnnnnCCCCnnnn
ccccNNNNccccNNNN
ccccnnnnccccnnnn
ccccnnnnccccnnnn
CCCCnnnnCCCCnnnn
ccccNNNNccccNNNN
ccccnnnnccccnnnn
ccccnnnnccccnnnn
CCCCnnnnCCCCnnnn
ccccNNNNccccNNNN
ccccnnnnccccnnnn
ccccnnnnccccnnnn
"""

STALL_GOODS = """
kkkkkkkkkkkkkkkk
kkgkkkEkkkkgkkkk
kgggkkekkkgggkkk
kkgkkkkkkkkgkkkk
kkkkkeEkkkkkkkkk
kkkkkkekkkkkkgkk
kkgkkkkkkkkkgggk
kgggkkkkEkkkkgkk
kkgkkkkkekkkkkkk
kkkkkkkkkkkkkkkk
kkkkgkkkkkeEkkkk
kkkgggkkkkekkkkk
kkkkgkkkkkkkkkkk
kkkkkkkkkkkkkkkk
kkkkkkEkkkkkkkkk
kkkkkkekkkkkkkkk
"""


# --- the coin toss and the card table -------------------------------------

TABLE_PAL = {

    "w": "#4a2f1a",     # rail
    "W": "#69452a",     # rail, lit
    "d": "#2e1c0f",     # rail, shadow
    "g": "#1e6b3a",     # felt
    "G": "#2a8c4c",     # felt, lit
    "k": "#14472a",     # felt, shadow
    "m": "#c9a227",     # brass / coin
    "M": "#f0cf5a",     # brass, lit
    "p": "#e8e8ea",     # card face
    "r": "#b02020",     # red pip
    "b": "#1a1a1a",     # black pip
    # The furniture that stands on the tables. Keys chosen to miss everything
    # above: this palette is shared by six maps and a collision silently
    # recolours whichever one was written second.
    "x": "#171013",     # outline
    "o": "#8a6a1c",     # coin, shadow
    "O": "#f0cf5a",     # coin, face
    "n": "#3a2b1c",     # shoe, dark wood
    "N": "#5a4429",     # shoe, lit
    "c": "#e8e2d4",     # card stock
    "C": "#f7f3e8",     # card, lit
    "e": "#a3232f",     # red chip
    "E": "#c8323f",     # red chip, lit
    "s": "#a8a8a8",     # scratch foil
    "S": "#cfcfcf",     # foil, lit
}

# The toss box lid: a big coin embossed in the felt.
TOSS_TOP = """
wwwwwwwwwwwwwwww
wggggggggggggggw
wggggmmmmmmggggw
wgggmMMMMMMmgggw
wggmMMmmmmMMmggw
wggmMmmMMmmMmggw
wggmMmMMMMmMmggw
wggmMmMMMMmMmggw
wggmMmmMMmmMmggw
wggmMMmmmmMMmggw
wgggmMMMMMMmgggw
wggggmmmmmmggggw
wggggggggggggggw
wgGggggggggggGgw
wggggggggggggggw
wwwwwwwwwwwwwwww
"""

# The card table lid: two cards laid out on the felt.
CARD_TOP = """
wwwwwwwwwwwwwwww
wggggggggggggggw
wgpppppgggppppgw
wgpbppprgggpppgw
wgpppppgggprppgw
wgppprpgggppppgw
wgpppppgggpbppgw
wggggggggggggggw
wggkgggggggggggw
wgggggggmmgggggw
wggggggmMMmggggw
wggggggmMMmggggw
wgggggggmmgggggw
wggggggggggggggw
wgGgggggggggggGw
wwwwwwwwwwwwwwww
"""

# The sides of both: panelled wood with a brass rail.
TABLE_SIDE = """
mMwwwwwwwwwwwwMm
MWWWWWWWWWWWWWWM
wWdWWWWWWWWWdWWw
wWWWWWWWWWWWWWWw
wWWWWWdWWWWWWWWw
wWWWWWWWWWdWWWWw
wWdWWWWWWWWWWWWw
wWWWWWWWWdWWWWWw
wWWWdWWWWWWWWdWw
wWWWWWWWWWWWWWWw
wWWdWWWWdWWWWWWw
wWWWWWWWWWWWdWWw
wWWWWdWWWWWWWWWw
wWdWWWWWWWWWWWWw
MWWWWWWWWWWWWWWM
mMwwwwwwwwwwwwMm
"""

# The furniture that stands on the tables. Small, dark-outlined shapes that
# read at 16px from across a room -- the whole point of a table having things
# ON it is that you can tell the games apart without opening one.

TABLE_LEG = """
xxxxxxxxxxxxxxxx
xMMMMMMMMMMMMMMx
xMWWWWWWWWWWWWMx
xMWdWWWWWWWWdWMx
xMWWWWWWWWWWWWMx
xMWWWWdWWWWWWWMx
xMWWWWWWWWdWWWMx
xMWdWWWWWWWWWWMx
xMWWWWWWWdWWWWMx
xMWWWdWWWWWWWWMx
xMWWWWWWWWWWWWMx
xMWWdWWWWdWWWWMx
xMWWWWWWWWWWWWMx
xMMMMMMMMMMMMMMx
xxxxxxxxxxxxxxxx
xxxxxxxxxxxxxxxx
"""

# Brass edging round the top of every table, so the felt sits in a rim.
TABLE_RIM = """
xxxxxxxxxxxxxxxx
xmmmmmmmmmmmmmmx
xmMMMMMMMMMMMMmx
xmMmmmmmmmmmMMmx
xxxxxxxxxxxxxxxx
................
................
................
................
................
................
................
................
................
................
................
"""

# The coin standing on its rim in the middle of the toss table.
TOSS_COIN = """
....xxxxxxxx....
...xoooooooox...
..xoOOOOOOOOox..
.xoOOxxxxxxOOox.
.xoOxOOOOOOxOox.
.xoOxOxxxxOxOox.
.xoOxOxOOxOxOox.
.xoOxOxOOxOxOox.
.xoOxOxxxxOxOox.
.xoOxOOOOOOxOox.
.xoOOxxxxxxOOox.
..xoOOOOOOOOox..
...xoooooooox...
....xxxxxxxx....
................
................
"""

# The card shoe on the blackjack table.
CARD_SHOE = """
xxxxxxxxxxxxxxxx
xnnnnnnnnnnnnnnx
xnNNNNNNNNNNNNnx
xnNccccccccccNnx
xnNcCCCCCCCCcNnx
xnNcCeeeeeeCcNnx
xnNcCCCCCCCCcNnx
xnNccccccccccNnx
xnNNNNNNNNNNNNnx
xnnnnnnnnnnnnnnx
xxxxxxxxxxxxxxxx
................
................
................
................
................
"""

# A stack of chips, for the corner of a table.
CHIP_STACK = """
..xxxxxxxxxxxx..
.xeeeeeeeeeeeex.
.xEEEEEEEEEEEEx.
.xxxxxxxxxxxxxx.
.xCCCCCCCCCCCCx.
.xccccccccccccx.
.xxxxxxxxxxxxxx.
.xeeeeeeeeeeeex.
.xEEEEEEEEEEEEx.
.xxxxxxxxxxxxxx.
.xCCCCCCCCCCCCx.
.xccccccccccccx.
.xxxxxxxxxxxxxx.
................
................
................
"""

# The rack of unsold scratchcards standing on the newsagent's counter.
CARD_RACK = """
xxxxxxxxxxxxxxxx
xmMmMmMmMmMmMmMx
xMcccMcccMcccMMx
xMcCcMcCcMcCcMMx
xMcccMcccMcccMMx
xMsssMsssMsssMMx
xMsSsMsSsMsSsMMx
xMsssMsssMsssMMx
xMcccMcccMcccMMx
xmMmMmMmMmMmMmMx
xxxxxxxxxxxxxxxx
................
................
................
................
................
"""

# The bar top: bottles and glasses lined up along a dark wood counter.
BAR_TOP = """
xxxxxxxxxxxxxxxx
xWWWWWWWWWWWWWWx
xWdWWWWWWWWdWWWx
xWWWWWWWWWWWWWWx
xxxxxxxxxxxxxxxx
xCcCcCcCcCcCcCcx
xcCcCcCcCcCcCcCx
xxxxxxxxxxxxxxxx
xWWWWdWWWWWWWWWx
xWWWWWWWWWdWWWWx
xxxxxxxxxxxxxxxx
xOoOoOoOoOoOoOox
xoOoOoOoOoOoOoOx
xxxxxxxxxxxxxxxx
xWdWWWWWWWWWWdWx
xxxxxxxxxxxxxxxx
"""

# The bottle shelf that stands up behind it.
BAR_SHELF = """
xxxxxxxxxxxxxxxx
xmMmMmMmMmMmMmMx
xxxxxxxxxxxxxxxx
xOxCxOxCxOxCxOxx
xOxCxOxCxOxCxOxx
xOxCxOxCxOxCxOxx
xxxxxxxxxxxxxxxx
xmMmMmMmMmMmMmMx
xxxxxxxxxxxxxxxx
xCxOxCxOxCxOxCxx
xCxOxCxOxCxOxCxx
xCxOxCxOxCxOxCxx
xxxxxxxxxxxxxxxx
xmMmMmMmMmMmMmMx
xxxxxxxxxxxxxxxx
................
"""

# --- the climb (strongbox) ------------------------------------------------

CLIMB_PAL = {
    "i": "#5b6068",     # iron plate
    "I": "#7d838c",     # plate, lit
    "d": "#3a3e44",     # plate, shadow
    "r": "#2c2f34",     # rivet / seam
    "m": "#c9a227",     # brass
    "M": "#f0cf5a",     # brass, lit
    "k": "#1a1c20",     # keyhole
    "g": "#2fbf6b",     # green lamp
    "G": "#8df0b4",     # lamp, lit core
    "a": "#e0a02a",     # amber lamp
    "A": "#ffd77a",     # amber, lit core
    "t": "#8a6a1c",     # tread, shadow
    "T": "#d8b249",     # tread, lit
}

# The tread of one rung on the climb: brass plate with a grip pattern, so
# four of them stepping up the face read as a staircase and not as shelves.
CLIMB_STEP = """
tttttttttttttttt
tTTTTTTTTTTTTTTt
tTmTmTmTmTmTmTTt
tTTTTTTTTTTTTTTt
tTmTmTmTmTmTmTTt
tTTTTTTTTTTTTTTt
tttttttttttttttt
................
................
................
................
................
................
................
................
................
"""

# The lamp on the end of a rung. Lit when you have got that far.
CLIMB_LAMP = """
kkkkkkkkkkkkkkkk
kaaaaaaaaaaaaaak
kaAAAAAAAAAAAAak
kaAGGGGGGGGGGAak
kaAGgggggggGGAak
kaAGgggggggGGAak
kaAGGGGGGGGGGAak
kaAAAAAAAAAAAAak
kaaaaaaaaaaaaaak
kkkkkkkkkkkkkkkk
................
................
................
................
................
................
"""

# The face: three brass locks in a row on a riveted iron door.
CLIMB_FACE = """
rrrrrrrrrrrrrrrr
rIIIIIIIIIIIIIIr
rIiiiiiiiiiiiiIr
rIimMMmiimMMmiIr
rIimMkMiimMkMiIr
rIimMMmiimMMmiIr
rIiiiiiiiiiiiiIr
rIiiimMMmiiiiiIr
rIiiimMkMiiiiiIr
rIiiimMMmiiiiiIr
rIiiiiiiiiiiiiIr
rIiiiiiggiiiiiIr
rIiiiiiiiiiiiiIr
rIIIIIIIIIIIIIIr
rdddddddddddddrr
rrrrrrrrrrrrrrrr
"""

# The sides and back: plain riveted plate.
CLIMB_PLATE = """
rrrrrrrrrrrrrrrr
rIIIIIIIIIIIIIIr
rIiiiiiiiiiiiiIr
rIidiiiiiiiidiIr
rIiiiiiiiiiiiiIr
rIiiiiidiiiiiiIr
rIiiiiiiiiiiiiIr
rIidiiiiiiiidiIr
rIiiiiiiiiiiiiIr
rIiiiiidiiiiiiIr
rIiiiiiiiiiiiiIr
rIidiiiiiiiidiIr
rIiiiiiiiiiiiiIr
rIIIIIIIIIIIIIIr
rdddddddddddddrr
rrrrrrrrrrrrrrrr
"""

# The lid: brass banding across iron.
CLIMB_LID = """
rrrrrrrrrrrrrrrr
rIIIIIIIIIIIIIIr
rImmmmmmmmmmmmIr
rIMMMMMMMMMMMMIr
rImmmmmmmmmmmmIr
rIiiiiiiiiiiiiIr
rIiiiiiiiiiiiiIr
rImmmmmmmmmmmmIr
rIMMMMMMMMMMMMIr
rImmmmmmmmmmmmIr
rIiiiiiiiiiiiiIr
rIiiiiiiiiiiiiIr
rIiiiiiiiiiiiiIr
rIIIIIIIIIIIIIIr
rdddddddddddddrr
rrrrrrrrrrrrrrrr
"""

# --- the drop (plinko) ----------------------------------------------------

PLINKO_PAL = {
    "w": "#3a2a4a",     # cabinet frame, dark violet wood
    "W": "#55406e",     # frame, lit
    "d": "#241a2e",     # frame, shadow
    "m": "#c9a227",     # brass
    "M": "#f0cf5a",     # brass, lit
    "b": "#101828",     # backboard
    "B": "#1b2740",     # backboard, lit
    "p": "#cfd6e0",     # peg
    "P": "#ffffff",     # peg, lit
    "g": "#2fbf6b",     # winning slot
    "r": "#c03a3a",     # losing slot
    "y": "#e8c33a",     # edge slot
}

# The peg field: a dark board with a lattice of bright pegs.
PLINKO_BOARD = """
bbbbbbbbbbbbbbbb
bBbpbbbpbbbpbbBb
bbbbbbbbbbbbbbbb
bpbbbpbbbpbbbpbb
bbbbbbbbbbbbbbbb
bBbpbbbpbbbpbbBb
bbbbbbbbbbbbbbbb
bpbbbpbbbpbbbpbb
bbbbbbbbbbbbbbbb
bBbpbbbpbbbpbbBb
bbbbbbbbbbbbbbbb
bpbbbpbbbpbbbpbb
bbbbbbbbbbbbbbbb
bBbpbbbpbbbpbbBb
bbbbbbbbbbbbbbbb
bbbbbbbbbbbbbbbb
"""

# The bottom half: the same field, then the payout slots in a row.
PLINKO_SLOTS = """
bbbbbbbbbbbbbbbb
bpbbbpbbbpbbbpbb
bbbbbbbbbbbbbbbb
bBbpbbbpbbbpbbBb
bbbbbbbbbbbbbbbb
bpbbbpbbbpbbbpbb
bbbbbbbbbbbbbbbb
wwwwwwwwwwwwwwww
yyggrrrrrrrrggyy
yyggrrrrrrrrggyy
wwwwwwwwwwwwwwww
bbbbbbbbbbbbbbbb
bbbbbbbbbbbbbbbb
bbbbbbbbbbbbbbbb
bbbbbbbbbbbbbbbb
bbbbbbbbbbbbbbbb
"""

# The frame around the board, with brass corners.
PLINKO_FRAME = """
mMwwwwwwwwwwwwMm
MWWWWWWWWWWWWWWM
wWdWWWWWWWWWdWWw
wWWWWWdWWWWWWWWw
wWWWWWWWWWdWWWWw
wWdWWWWWWWWWWWWw
wWWWWWWWWdWWWWWw
wWWWdWWWWWWWWdWw
wWWWWWWWWWWWWWWw
wWWdWWWWdWWWWWWw
wWWWWWWWWWWWdWWw
wWWWWdWWWWWWWWWw
wWdWWWWWWWWWWWWw
wWWWWWWWdWWWWWWw
MWWWWWWWWWWWWWWM
mMwwwwwwwwwwwwMm
"""

# --- the roulette table ---------------------------------------------------

ROULETTE_PAL = {
    "w": "#5a3a1e",     # mahogany rim
    "W": "#7a5230",     # rim, lit
    "d": "#3a2412",     # rim, shadow
    "m": "#c9a227",     # brass
    "M": "#f0cf5a",     # brass, lit
    "g": "#1e6b3a",     # felt
    "G": "#2a8c4c",     # felt, lit
    "k": "#14472a",     # felt, shadow
    "r": "#b02020",     # red pocket
    "b": "#1a1a1a",     # black pocket
    "s": "#c8ccd0",     # silver / ball
    "S": "#f2f5f7",     # silver, lit
}

# The felt: green baize with a lighter worn patch where the chips go.
ROULETTE_FELT = """
kkkkkkkkkkkkkkkk
kggggggggggggggk
kgGGgggggggGGggk
kggggggggggggggk
kgggGGgggggggggk
kggggggggggGggGk
kgGggggggggggggk
kgggggggGGggggGk
kGggggggggggggGk
kggggGggggggGggk
kgggggggggggkggk
kGgggggkgggggggk
kggGgggggggggGgk
kgggggggggggkggk
kggggggggggggggk
kkkkkkkkkkkkkkkk
"""

# The rim: polished wood with brass studs at the corners.
ROULETTE_RIM = """
mMwwwwwwwwwwwwMm
MWWWWWWWWWWWWWWM
wWWdWWWWWWWdWWWw
wWWWWWWdWWWWWWWw
wWdWWWWWWWWWdWWw
wWWWWWdWWWWWWWWw
wWWWWWWWWWdWWWWw
wWdWWWWWWWWWWWWw
wWWWWWWWWdWWWWWw
wWWWdWWWWWWWWdWw
wWWWWWWWWWWWWWWw
wWWdWWWWdWWWWWWw
wWWWWWWWWWWWdWWw
wWWWWdWWWWWWWWWw
MWWWWWWWWWWWWWWM
mMwwwwwwwwwwwwMm
"""

# The wheel head: alternating red and black wedges around a silver hub, with
# the ball resting in one pocket.
ROULETTE_WHEEL = """
....wwwwwwww....
..wwrrbbrrbbww..
.wwrrbbrrbbrrww.
wwrrbbrrbbrrbbww
wrrbbss....ssbbw
wrbbsSSSSSSSSsbw
wbbsSSssssssSSsw
wbsSSssSSSSssSSw
wbsSSssSSSSssSSw
wbbsSSssssssSSsw
wrbbsSSSSSSSSsbw
wrrbbss....ssbbw
wwrrbbrrbbrrbbww
.wwrrbbrrbbrrww.
..wwrrbbrrbbww..
....wwwwwwww....
"""

# --- the wallet -----------------------------------------------------------

WALLET_PAL = {
    "l": "#6b4423",     # leather
    "L": "#89592f",     # leather, lit
    "d": "#4a2e17",     # leather, shadow
    "s": "#3a2411",     # stitching / seam
    "c": "#c9a227",     # brass clasp
    "C": "#f0cf5a",     # brass, lit
    "e": "#2fbf6b",     # emerald
    "E": "#7df0a8",     # emerald, lit facet
    "g": "#1d7a45",     # emerald, shadow
}

# A folded pouch: flap over the top, seam down the sides, clasp in the middle.
WALLET_BODY = """
................
....dddddddd....
...dLLLLLLLLd...
..dLLLLLLLLLLd..
..sllllllllllс..
..sllllllllllс..
..sllllllllllс..
..slllllllllls..
..slllllllllls..
..slllllllllls..
..sdllllllllds..
..sdddddddddds..
...dddddddddd...
....ssssssss....
................
................
"""
WALLET_BODY = WALLET_BODY.replace("с", "s")   # cyrillic slipped in; keep the map ASCII

# The flap and clasp, drawn as a separate layer so the model can sit it
# proud of the body -- a clasp painted flat reads as a smudge at 16px.
WALLET_FLAP = """
................
................
................
..LLLLLLLLLLLL..
..LLLLLLLLLLLL..
..dLLLLLLLLLLd..
.....cCCCCc.....
.....CccccC.....
.....cCCCCc.....
................
................
................
................
................
................
................
"""

# An emerald peeking out of the top, so a wallet reads as money and not as a
# generic leather pouch from some other mod.
WALLET_COIN = """
................
................
.....gEEEEg.....
....gEeeeeEg....
...gEeeeeeeEg...
...geeeeeeeeg...
...geeeeeeeeg...
....geeeeeeg....
.....gggggg.....
................
................
................
................
................
................
................
"""

# --- the scratchers counter -----------------------------------------------

SCRATCH_PAL = {
    "r": "#8c2230",     # counter felt, red
    "R": "#b03040",     # felt, lit
    "d": "#5e1420",     # felt, shadow
    "c": "#d9d3c2",     # card stock
    "C": "#f2eee2",     # card, lit
    "s": "#a8a8a8",     # silver foil, unscratched
    "S": "#cfcfcf",     # foil, lit
    "g": "#c9a227",     # gold trim
    "G": "#f0cf5a",     # gold, lit
    "k": "#2b1d16",     # outline
}

# The counter top seen from above: three tickets fanned out on red felt, one
# already scratched. Read at 16px what has to survive is the LAYOUT -- three
# pale rectangles on red -- so the panels are drawn big and blocky rather than
# detailed.
SCRATCH_TOP = """
gggggggggggggggg
gdrrrrrrrrrrrrdg
grkccckkccckkccg
grkcCckkcCckkcCg
grkssckkssckksck
grkSsckkSsckkSsg
grkssckkssckkscg
grkccckkccckkccg
grrrrrrrrrrrrrrg
grkcccccckkGGkrg
grkCcccccckgGkrg
grksssssssckkkrg
grkSssssssckrrrg
grkccccccccckrrg
gdrrrrrrrrrrrrdg
gggggggggggggggg
"""

# --- the owner's card -----------------------------------------------------

CARD_PAL = {
    "k": "#141216",     # black plastic
    "K": "#241f28",     # plastic, lit
    "d": "#0a090c",     # plastic, shadow
    "g": "#b8892b",     # gold foil
    "G": "#f2ce68",     # gold, lit
    "h": "#7a5716",     # gold, shadow
    "e": "#2fbf6b",     # emerald pip
    "E": "#7df0a8",     # emerald, lit
    "w": "#e8e2d4",     # embossed lettering
}

# The face: gold-bordered black card with an embossed band and a pip. Read at
# 16px this has to be a SILHOUETTE, not a picture -- the border and the band
# are what make it a card rather than a dark rectangle.
CARD_FACE = """
gggggggggggggggg
gGGGGGGGGGGGGGGg
gGkkkkkkkkkkkkGg
gGkKKKKKKKKKKkGg
gGkKwwwwwwwwKkGg
gGkKwddddddwKkGg
gGkKwwwwwwwwKkGg
gGkKKKKKKKKKKkGg
gGkKKKKKKKKKKkGg
gGkkkkkkkkkkkkGg
gGhhhhhhhhhhhhGg
gGGGGGGGGGGGGGGg
gggggggggggggggg
................
................
................
"""

# The edge, so the card has thickness rather than being a decal.
CARD_EDGE = """
gGGGGGGGGGGGGGGg
ghhhhhhhhhhhhhhg
gggggggggggggggg
................
................
................
................
................
................
................
................
................
................
................
................
................
"""

# A raised chip on the corner, which is the one detail that reads instantly as
# "this is a card that means money" from across a hotbar.
CARD_CHIP = """
................
....hhhhhhhh....
...hgGGGGGGgh...
...hGeeeeeeGh...
...hGeEEEEeGh...
...hGeEeeEeGh...
...hGeEEEEeGh...
...hGeeeeeeGh...
...hgGGGGGGgh...
....hhhhhhhh....
................
................
................
................
................
................
"""


# --- the ledger -----------------------------------------------------------

LEDGER_PAL = {
    "l": "#3c2a1e",     # cover leather
    "L": "#4e382772",   # cover, lit grain (alpha keeps the grain subtle)
    "d": "#2a1c13",     # cover, shadow
    "m": "#7b5e2b",     # brass corner
    "M": "#a98240",     # brass, lit
    "p": "#e6ddc4",     # paper
    "P": "#f5efdd",     # paper, lit edge
    "q": "#c9bf9f",     # paper, shadow line
    "w": "#8a7a52",     # ruled line
    "y": "#d8a83a",     # pencil body
    "Y": "#f0c765",     # pencil, lit
    "t": "#2d2d2d",     # pencil tip / lead
}

LEDGER_COVER = """
mMllllllllllllMm
MllLllllllLllllM
llllllllllllllll
lLllldllllllLlll
lllllllldlllllll
llLllllllllLllll
dllllllllllllldl
llllLdllllllllll
llllllllllLlllll
lLlllllllllllLll
llllldllllldllll
llLllllllllllLll
llllllllllllllll
MlllLlllllllllLM
mMllllllllllllMm
mMMllllllllllMMm
"""

LEDGER_PAGES = """
pppppppppppppppp
PPPPPPPPPPPPPPPP
pwwwwwwwwwwwwwqp
pppppppppppppppp
pwwwwwwwwwwwqppp
pppppppppppppppp
pwwwwwwwwwwwwwwp
qqqqqqqqqqqqqqqq
pppppppppppppppp
pwwwwwwwwwqppppp
pppppppppppppppp
pwwwwwwwwwwwwwqp
pppppppppppppppp
PPPPPPPPPPPPPPPP
qqqqqqqqqqqqqqqq
pppppppppppppppp
"""

LEDGER_PENCIL = """
tttyyyyyyyyyyYYy
ttyYYYYYYYYYYYYy
tyyyyyyyyyyyyyyy
yyyyyyyyyyyyyyyy
YYYYYYYYYYYYYYYY
yyyyyyyyyyyyyyyy
tttyyyyyyyyyyYYy
ttyYYYYYYYYYYYYy
tyyyyyyyyyyyyyyy
yyyyyyyyyyyyyyyy
YYYYYYYYYYYYYYYY
yyyyyyyyyyyyyyyy
tttyyyyyyyyyyYYy
ttyYYYYYYYYYYYYy
tyyyyyyyyyyyyyyy
yyyyyyyyyyyyyyyy
"""


# --- burner phone ---------------------------------------------------------

PHONE_PAL = {
    "s": "#2b2f36",     # shell plastic
    "S": "#3c424b",     # shell, lit edge
    "d": "#1a1d21",     # shell, shadow / seam
    "g": "#7d8894",     # scuffed grey, the wear on a cheap handset
    "e": "#1d3a22",     # screen glass, off
    "E": "#4f9c53",     # screen glow
    "L": "#8ede8a",     # screen, bright pixel row
    "k": "#22262b",     # key
    "K": "#485059",     # key, lit top
    "n": "#141619",     # gap between keys
}

PHONE_SHELL = """
SSSSSSSSSSSSSSSS
sssssssssssssssd
ssssgssssssssssd
ssssssssssssdsss
ssdsssssssssssss
ssssssssgsssssss
ssssssssssssssds
dddddddddddddddd
SSSSSSSSSSSSSSSS
ssssssssssssssss
sssgssssssssssss
ssssssssssdsssss
ssssssssssssssss
ssdsssssgsssssss
ssssssssssssssss
dddddddddddddddd
"""

PHONE_SCREEN = """
eeeeeeeeeeeeeeee
eEEEEEEEEEEEEEEe
eELLLLLLLLLLLLEe
eEeeeeeeeeeeeeEe
eEeLLLLLLLLLLeEe
eEeeeeeeeeeeeeEe
eEeLLLLLLLeeeeEe
eEeeeeeeeeeeeeEe
eEeLLLLLLLLLLeEe
eEeeeeeeeeeeeeEe
eEeLLLLLeeeeeeEe
eEeeeeeeeeeeeeEe
eELLLLLLLLLLLLEe
eEEEEEEEEEEEEEEe
eeeeeeeeeeeeeeee
eeeeeeeeeeeeeeee
"""

PHONE_KEYS = """
nnnnnnnnnnnnnnnn
nKKKKnKKKKnKKKKn
nkkkknkkkknkkkkn
nkkkknkkkknkkkkn
nnnnnnnnnnnnnnnn
nKKKKnKKKKnKKKKn
nkkkknkkkknkkkkn
nkkkknkkkknkkkkn
nnnnnnnnnnnnnnnn
nKKKKnKKKKnKKKKn
nkkkknkkkknkkkkn
nkkkknkkkknkkkkn
nnnnnnnnnnnnnnnn
nKKKKnKKKKnKKKKn
nkkkknkkkknkkkkn
nnnnnnnnnnnnnnnn
"""


# --- glassware ------------------------------------------------------------
#
# These are surfaces for a real 3D model, not billboards, so they tile across
# faces of very different sizes -- everything here is a repeating material
# rather than a picture of an object. The bottle and glass entries carry alpha:
# a tlok whose water level you can't see through the wall is just a box.

GLASS_PAL = {
    # Alpha here is a bonus, not the design: if the carrier block turns out to
    # render on a cutout layer these all snap opaque, and the models are built
    # so that still looks right. Kept high enough to read as frosted PET rather
    # than vapour when it does survive.
    "b": "#cbe4f1c0",   # PET wall
    "B": "#e8f6ffd8",   # PET rib highlight
    "h": "#9dc2d4a8",   # PET shadow between ribs
    "R": "#f2fbffe8",   # moulded ridge, the hard rings round a bottle
    "w": "#2478c8cc",   # water
    "W": "#4a9ee0cc",   # water, lit
    "v": "#17538fcc",   # water, deep
    "s": "#e4e9ecc4",   # smoke, milky
    "S": "#f6f9fbd0",   # smoke, bright curl
    "t": "#c3ccd2b4",   # smoke, thin
    "k": "#1d2124",     # basin, shadow
    "K": "#2b3237",     # basin, body
    "l": "#3d474e",     # basin, lit rim
    "r": "#a8262a",     # cap
    "c": "#c9383c",     # cap, lit
    "n": "#7c1b1f",     # cap, shadow
    "g": "#3f7a33",     # bud, shadow
    "G": "#5aa447",     # bud, body
    "V": "#7fc862",     # bud, highlight
    "q": "#d8ecf4b4",   # bong glass, clearer than PET
    "Q": "#f2fbffd0",   # bong glass highlight
    "j": "#a9ccdc98",   # bong glass shadow
    "p": "#4a4038",     # bong base, ceramic
    "P": "#645648",     # bong base, lit
}

# A PET bottle wall: vertical ribs, with the moulded rings that go round the
# middle. Reads as plastic rather than glass, which is what a tlok is.
TLOK_PLASTIC = """
hbBbhbBbhbBbhbBb
hbBbhbBbhbBbhbBb
hbBbhbBbhbBbhbBb
RRRRRRRRRRRRRRRR
hbBbhbBbhbBbhbBb
hbBbhbBbhbBbhbBb
hbBbhbBbhbBbhbBb
hbBbhbBbhbBbhbBb
RRRRRRRRRRRRRRRR
hbBbhbBbhbBbhbBb
hbBbhbBbhbBbhbBb
hbBbhbBbhbBbhbBb
hbBbhbBbhbBbhbBb
RRRRRRRRRRRRRRRR
hbBbhbBbhbBbhbBb
hbBbhbBbhbBbhbBb
"""

TLOK_WATER = """
WwwWwwvwwWwwwvww
wwvwwwwWwwvwwwWw
wWwwwvwwwwWwwvww
wwwWwwvwwwwwwwWw
vwwwwwWwwvwwWwww
wwWwwvwwwwwwwwvw
wvwwwwwwWwvwwwWw
wwwvwWwwwwwwvwww
Wwwwwwwvwwwwwwww
wwvwwWwwwvwwwWwv
wwwwwwwwwwWwwwww
wWwvwwwWwwwwvwww
wwwwwvwwwwWwwwww
vwwWwwwwvwwwwwWw
wwwwwwWwwwwvwwww
wwWwvwwwwWwwwwwv
"""

TLOK_SMOKE = """
sstssStssstsssSs
tsssSsssttsssssS
ssStsssssSssttss
sssstsSsssssStss
Ssssssstssttssss
sstsSssssSssssts
ssssstSssssttsSs
tsSssssstsssssss
sssstssSsssStsss
sSssttsssssssStt
ssssssssStssssss
stsSsssttsssSsss
sssstsssssSsstss
StssssSsssttssss
ssssStssssssssSs
tsssssstSsstssSs
"""

TLOK_BASIN = """
llllllllllllllll
lKKKKKKKKKKKKKKl
lKkKKkKKKkKKkKKl
lKKKKKKkKKKKKKKl
lKkKKKKKKKkKKkKl
lKKKkKKKKKKKKKKl
lKKKKKKKkKKkKKKl
lkKKkKKKKKKKKKKl
lKKKKKkKKKKkKKKl
lKKkKKKKKkKKKKKl
lKKKKKKkKKKKKkKl
lkKKKkKKKKKKKKKl
lKKKKKKKKkKKkKKl
lKKkKKKkKKKKKKKl
lKKKKKKKKKKKKKKl
llllllllllllllll
"""

TLOK_CAP = """
ncrcncrcncrcncrc
ncrcncrcncrcncrc
cccccccccccccccc
ncrcncrcncrcncrc
ncrcncrcncrcncrc
ncrcncrcncrcncrc
cccccccccccccccc
ncrcncrcncrcncrc
ncrcncrcncrcncrc
ncrcncrcncrcncrc
cccccccccccccccc
ncrcncrcncrcncrc
ncrcncrcncrcncrc
ncrcncrcncrcncrc
cccccccccccccccc
ncrcncrcncrcncrc
"""

TLOK_BOWL = """
gGgVGggGVgGgGVgg
GVGgGVgGgGgVGgGg
ggGgggVgGVgGggVG
GgVGgGgGggGgVGgg
gGggVGgggGVgGggG
VGgGggGVgGggGgVg
ggGVgGggGgVGgGgg
GgggGgVgGVggGgGV
gVGgGggGggGVgGgg
GggGVGgVgGggGgGg
ggVgggGgGgVGgVGg
GgGgVGgGVgggGggG
gGggGgVggGgGVgGg
VGgGggGgGVgGggGV
ggGVgGgVgGggGgGg
GgggGVgGggVGgGgg
"""

BONG_GLASS = """
jqQqjqqQjqQqjqqQ
jqQqjqqQjqQqjqqQ
jqQqjqqQjqQqjqqQ
jqQqjqqQjqQqjqqQ
jqQqjqqQjqQqjqqQ
jqQqjqqQjqQqjqqQ
jqQqjqqQjqQqjqqQ
jqQqjqqQjqQqjqqQ
jqQqjqqQjqQqjqqQ
jqQqjqqQjqQqjqqQ
jqQqjqqQjqQqjqqQ
jqQqjqqQjqQqjqqQ
jqQqjqqQjqQqjqqQ
jqQqjqqQjqQqjqqQ
jqQqjqqQjqQqjqqQ
jqQqjqqQjqQqjqqQ
"""

# --- mixing station -------------------------------------------------------
#
# The blend items are deliberately NOT strain-coloured: a mix can be any of a
# hundred-odd combinations and the item's NAME already carries the colour. One
# texture that reads as "several things at once" beats a hundred that don't
# exist.

MIX_PAL = {
    "d": "#3a2b1a",   # bench wood, shadow
    "D": "#5a4429",   # bench wood
    "L": "#755a37",   # bench wood, lit
    "k": "#20303a",   # iron banding
    "K": "#41525e",   # iron banding, lit
    "s": "#8e9aa2",   # stone mortar
    "S": "#b3bec6",   # stone mortar, lit
    "n": "#2b3238",   # bowl interior
    "1": "#4a9a3c",   # kush green
    "2": "#9ec43a",   # haze lime
    "3": "#7a4fa8",   # purp violet
    "4": "#c47f3a",   # sunset amber
    "5": "#4a4a8a",   # midnight blue
    "w": "#e8e4d8",   # rolling paper
    "W": "#f7f5ee",   # paper highlight
    "e": "#ff7a2a",   # ember
    "g": "#6b6b6b",   # ash
}

MIX_SIDE = """
LLLLLLLLLLLLLLLL
LDDDDDDDDDDDDDDL
LDkkkkkkkkkkkkDL
LDkKKKKKKKKKKkDL
LDkkkkkkkkkkkkDL
LDDDDDDDDDDDDDDL
LDdDDdDDDdDDdDDL
LDDDDDDDDDDDDDDL
LdDDDdDDdDDDdDDL
LDDDDDDDDDDDDDDL
LDkkkkkkkkkkkkDL
LDkKKKKKKKKKKkDL
LDkkkkkkkkkkkkDL
LDDdDDDdDDdDDDDL
LDDDDDDDDDDDDDDL
LLLLLLLLLLLLLLLL
"""

MIX_TOP = """
LLLLLLLLLLLLLLLL
LDDDDDDDDDDDDDDL
LDssssssssssssDL
LDsSSSSSSSSSSsDL
LDsSnnnnnnnnSsDL
LDsSn1122nnnSsDL
LDsSn11223nnSsDL
LDsSn1223344SsDL
LDsSn2233445SsDL
LDsSnn33455nSsDL
LDsSnnn455nnSsDL
LDsSnnnnnnnnSsDL
LDsSSSSSSSSSSsDL
LDssssssssssssDL
LDDDDDDDDDDDDDDL
LLLLLLLLLLLLLLLL
"""

BLEND_BUD = """
................
.......11.......
......1221......
.....122331.....
.....223344.....
....12334455....
....23344551....
...123445512....
...234455123....
....34551234....
....45512345....
.....5123451....
.....123451.....
......2345......
.......45.......
................
"""

BLEND_JOINT = """
................
...............e
..............ee
.............eW.
............Ww..
...........1w...
..........22....
.........33w....
........44w.....
.......55w......
......11w.......
.....22w........
....33w.........
...44w..........
..W5............
.g..............
"""

BONG_BASE = """
PPPPPPPPPPPPPPPP
PppPpppPppPpppPp
pppPppppPpppPppp
PpppPpppppPppPpp
ppPppppPppppPppp
pPppPpppPppPpppp
ppppPppppPpppPpp
PppPppPpppPppppP
ppPpppPppPppPppp
pppPppppPpppPppP
PppppPpppPppPppp
ppPpppPppppPpppP
pPppPpppPppPpppp
pppPppppPpppPppp
PppPpppPppPpppPp
PPPPPPPPPPPPPPPP
"""

# Status effect icon -- 18x18 is vanilla's size but we render it as an item.
BAKED_ICON = """
................
................
....33....33....
...3113..3113...
...3141..3141...
...3113..3113...
....33....33....
................
................
...4........4...
....4......4....
.....444444.....
................
................
................
................
"""

# Same face, deliberately: tolerance is the flipside of Baked, so it reads as
# the same character worn out rather than as an unrelated icon. Half-shut eyes,
# flat mouth, and a downward arrow for "this is reducing something".
TOLERANCE_ICON = """
................
................
................
...gggg..gggg...
...g11g..g11g...
....gg....gg....
................
................
.....gggggg.....
................
.......gg.......
.......gg.......
.....g.gg.g.....
......gggg......
.......gg.......
................
"""


def render(ascii_map: str, palette: dict[str, str]) -> Image.Image:
    rows = [r for r in ascii_map.strip("\n").split("\n")]
    assert len(rows) == 16, f"expected 16 rows, got {len(rows)}"
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    for y, row in enumerate(rows):
        assert len(row) == 16, f"row {y} is {len(row)} wide, expected 16"
        for x, ch in enumerate(row):
            if ch == ".":
                continue
            hexcode = palette.get(ch)
            if hexcode is None:
                raise KeyError(f"no palette entry for {ch!r} at ({x},{y})")
            r = int(hexcode[1:3], 16)
            g = int(hexcode[3:5], 16)
            b = int(hexcode[5:7], 16)
            # Optional 8th/9th hex digit is alpha. Everything predating the
            # glassware is 6-digit and stays fully opaque; a bottle you can't
            # see the water through isn't a bottle.
            a = int(hexcode[7:9], 16) if len(hexcode) == 9 else 255
            px[x, y] = (r, g, b, a)
    return img


def palette_for(strain: str) -> dict[str, str]:
    dark, mid, light, accent = STRAINS[strain]
    return {**COMMON, "1": dark, "2": mid, "3": light, "4": accent}


def write_animated(frames, frametime, *parts: str) -> None:
    """Vanilla animated texture: frames stacked vertically plus a .mcmeta.

    Works on every client through the served resource pack -- no client mod
    involved. Frame-by-frame only, so it suits glow and flicker, not motion.
    """
    strip = Image.new("RGBA", (16, 16 * len(frames)), (0, 0, 0, 0))
    for index, frame in enumerate(frames):
        strip.alpha_composite(frame, (0, index * 16))
    path = OUT.joinpath(*parts)
    path.parent.mkdir(parents=True, exist_ok=True)
    strip.save(path)
    meta = path.with_suffix(".png.mcmeta")
    meta.write_text(
        '{\n  "animation": {\n    "frametime": %d,\n    "interpolate": true\n  }\n}\n' % frametime)
    print(f"  {path.name} ({len(frames)} frames, animated)")


def write(img: Image.Image, *parts: str) -> None:
    path = OUT.joinpath(*parts)
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path)
    print(f"  {path.relative_to(OUT.parent.parent.parent.parent.parent)}")


def main() -> None:
    print("block textures:")
    # Ages 0-2 are strain-agnostic on purpose: a seedling shouldn't leak which
    # strain it is. Only the mature plant shows its phenotype.
    neutral = palette_for("kush")
    for age, sprite in enumerate([CROP_AGE0, CROP_AGE1, CROP_AGE2]):
        write(render(sprite, neutral), "block", f"cannabis_crop_age{age}.png")
    for strain in STRAINS:
        write(render(CROP_AGE3, palette_for(strain)),
              "block", f"cannabis_crop_age3_{strain}.png")

    # An occupied rack is tinted by what's hanging in it AND by how far along
    # the cure is, so "ready to collect" is visible from across the room.
    for strain in STRAINS:
        pal = palette_for(strain)
        for stage, subs in enumerate(DRY_STAGES):
            # translate(), not chained replace(): stage 3 maps A->B and B->1,
            # and sequential replaces would turn the fresh B's into 1's too.
            sprite = DRYING_RACK_FRONT.translate(str.maketrans(subs))
            write(render(sprite, pal),
                  "block", f"drying_rack_front_{strain}_{stage}.png")
    write(render(DRYING_RACK_SIDE, neutral), "block", "drying_rack_side.png")
    write(render(RACK_INNER, neutral), "block", "drying_rack_inner.png")
    write(render(CROP_STEM, neutral), "block", "crop_stem.png")
    # Ages 0-2 share one neutral leaf: a seedling must not give away its strain.
    write(render(CROP_LEAF, neutral), "block", "crop_leaf.png")
    for strain in STRAINS:
        pal = palette_for(strain)
        write(render(CROP_LEAF, pal), "block", f"crop_leaf_{strain}.png")
        write(render(CROP_BUD, pal), "block", f"crop_bud_{strain}.png")
    write(render(COCA_PLANT_LEAF, neutral), "block", "coca_plant_leaf.png")
    for strain in STRAINS:
        pal = palette_for(strain)
        for stage, subs in enumerate(DRY_STAGES):
            # translate(), not chained replace(): stage 3 maps A->B and B->1,
            # and replace() would run the fresh Bs straight through the
            # second rule and lose the gold tips entirely.
            write(render(RACK_BUD.translate(str.maketrans(subs)), pal),
                  "block", f"drying_rack_bud_{strain}_{stage}.png")
    write(render(DRYING_RACK_TOP, neutral), "block", "drying_rack_top.png")

    print("item textures:")
    for strain in STRAINS:
        pal = palette_for(strain)
        write(render(SEEDS, pal), "item", f"seeds_{strain}.png")
        write(render(RAW_BUD, pal), "item", f"raw_bud_{strain}.png")
        write(render(DRIED_BUD, pal), "item", f"dried_bud_{strain}.png")
        write(render(JOINT, pal), "item", f"joint_{strain}.png")
    write(render(HAMMER, palette_for("kush")), "item", "miners_hammer.png")
    write(render(TONIC_GLASS, TONIC_PAL), "item", "tonic_glass.png")
    write(render(TONIC_LIQUID, TONIC_PAL), "item", "tonic_liquid.png")
    write(render(TONIC_CORK, TONIC_PAL), "item", "tonic_cork.png")
    write(render(TOSS_TOP, TABLE_PAL), "block", "toss_top.png")
    write(render(CARD_TOP, TABLE_PAL), "block", "blackjack_top.png")
    write(render(TABLE_SIDE, TABLE_PAL), "block", "table_side.png")
    write(render(TABLE_LEG, TABLE_PAL), "block", "table_leg.png")
    write(render(BAR_TOP, TABLE_PAL), "block", "bar_top.png")
    write(render(BAR_SHELF, TABLE_PAL), "block", "bar_shelf.png")
    write(render(TABLE_RIM, TABLE_PAL), "block", "table_rim.png")
    write(render(TOSS_COIN, TABLE_PAL), "block", "toss_coin.png")
    write(render(CARD_SHOE, TABLE_PAL), "block", "card_shoe.png")
    write(render(CHIP_STACK, TABLE_PAL), "block", "chip_stack.png")
    write(render(CARD_RACK, TABLE_PAL), "block", "card_rack.png")
    write(render(CLIMB_FACE, CLIMB_PAL), "block", "climb_face.png")
    write(render(CLIMB_STEP, CLIMB_PAL), "block", "climb_step.png")
    write(render(CLIMB_LAMP, CLIMB_PAL), "block", "climb_lamp.png")
    write(render(CLIMB_PLATE, CLIMB_PAL), "block", "climb_plate.png")
    write(render(CLIMB_LID, CLIMB_PAL), "block", "climb_lid.png")
    write(render(PLINKO_BOARD, PLINKO_PAL), "block", "plinko_board.png")
    write(render(PLINKO_SLOTS, PLINKO_PAL), "block", "plinko_slots.png")
    write(render(PLINKO_FRAME, PLINKO_PAL), "block", "plinko_frame.png")
    write(render(ROULETTE_FELT, ROULETTE_PAL), "block", "roulette_felt.png")
    write(render(ROULETTE_RIM, ROULETTE_PAL), "block", "roulette_rim.png")
    write(render(ROULETTE_WHEEL, ROULETTE_PAL), "block", "roulette_wheel.png")
    write(render(WALLET_BODY, WALLET_PAL), "item", "wallet_body.png")
    write(render(WALLET_FLAP, WALLET_PAL), "item", "wallet_flap.png")
    write(render(WALLET_COIN, WALLET_PAL), "item", "wallet_coin.png")
    write(render(SCRATCH_TOP, SCRATCH_PAL), "block", "scratch_top.png")
    write(render(CARD_FACE, CARD_PAL), "item", "card_face.png")
    write(render(CARD_EDGE, CARD_PAL), "item", "card_edge.png")
    write(render(CARD_CHIP, CARD_PAL), "item", "card_chip.png")
    write(render(LEDGER_COVER, LEDGER_PAL), "item", "ledger_cover.png")
    write(render(LEDGER_PAGES, LEDGER_PAL), "item", "ledger_pages.png")
    write(render(LEDGER_PENCIL, LEDGER_PAL), "item", "ledger_pencil.png")
    write(render(PHONE_SHELL, PHONE_PAL), "item", "phone_shell.png")
    write(render(PHONE_SCREEN, PHONE_PAL), "item", "phone_screen.png")
    write(render(PHONE_KEYS, PHONE_PAL), "item", "phone_keys.png")
    write(render(STALL_COUNTER, STALL_PAL), "block", "stall_counter.png")
    write(render(STALL_AWNING, STALL_PAL), "block", "stall_awning.png")
    write(render(STALL_GOODS, STALL_PAL), "block", "stall_goods.png")
    write(render(SLOT_BODY, SLOT_PAL), "block", "slot_body.png")
    write(render(SLOT_SCREEN, SLOT_PAL), "block", "slot_screen.png")
    write(render(SLOT_TRIM, SLOT_PAL), "block", "slot_trim.png")
    write(render(SLOT_DECK, SLOT_PAL), "block", "slot_deck.png")

    print("smoking gear:")
    gear = palette_for("kush")
    for name, subs in BOTTLE_STAGES.items():
        if name == "loaded":
            write_animated([render(BOTTLE.translate(str.maketrans({"A": c})), gear)
                            for c in ("Z", "z", "Z", "Q")],
                           4, "block", "bong_loaded.png")
        else:
            write(render(BOTTLE.translate(str.maketrans(subs)), gear), "block", f"bong_{name}.png")
    # tlok stages: empty, water, loaded, burning (ember), stale (grey)
    for stage, subs in enumerate([{"A": "Q"}, {"A": "z"}, {"A": "Z"},
                                  {"A": "e"}, {"A": "g"}]):
        if stage == 3:
            # Burning flickers -- and the flicker is your cue that the window
            # is open. When it stops, you've missed it.
            write_animated([render(BOTTLE.translate(str.maketrans({"A": c})), gear)
                            for c in ("e", "E", "e", "z")],
                           2, "block", "gravity_bong_3.png")
        else:
            write(render(BOTTLE.translate(str.maketrans(subs)), gear),
                  "block", f"gravity_bong_{stage}.png")

    # Materials for the 3D glassware. Tiled across model faces rather than
    # drawn as a picture of the object, so they have to read at any size.
    for name, sprite in (("tlok_plastic", TLOK_PLASTIC), ("tlok_water", TLOK_WATER),
                         ("tlok_basin", TLOK_BASIN), ("tlok_cap", TLOK_CAP),
                         ("tlok_bowl", TLOK_BOWL), ("bong_glass", BONG_GLASS),
                         ("bong_base", BONG_BASE)):
        write(render(sprite, GLASS_PAL), "block", f"{name}.png")

    # The smoke inside the bottle drifts. Same map, shifted a row per frame --
    # cheap, and it turns a flat fill into something that looks alive while
    # you're deciding whether to pull.
    def rolled(sprite: str, by: int) -> str:
        rows = sprite.strip("\n").split("\n")
        return "\n".join(rows[by:] + rows[:by])

    write_animated([render(rolled(TLOK_SMOKE, i), GLASS_PAL) for i in (0, 4, 8, 12)],
                   3, "block", "tlok_smoke.png")
    # Stale smoke has settled: same drift, slower, and it reads grey not milky.
    stale = {**GLASS_PAL, "s": "#9aa2a79e", "S": "#b0b8bd9e", "t": "#868e93a0"}
    write_animated([render(rolled(TLOK_SMOKE, i), stale) for i in (0, 6)],
                   8, "block", "tlok_smoke_stale.png")

    print("mixing station:")
    write(render(MIX_SIDE, MIX_PAL), "block", "mixing_station_side.png")
    # The bowl cycles through the strain colours -- the block advertises what
    # it's for without a sign, and it's the only texture in the mod that shows
    # all six palettes at once.
    # translate(), not chained replace(): "1"->"2" then "2"->"3" would
    # cascade and every colour would collapse onto the last one.
    write_animated([render(MIX_TOP.translate(str.maketrans("12345", c)), MIX_PAL)
                    for c in ("12345", "23451", "34512", "45123", "51234")],
                   6, "block", "mixing_station_top.png")
    write(render(BLEND_BUD, MIX_PAL), "item", "blend_bud.png")
    write(render(BLEND_JOINT, MIX_PAL), "item", "blend_joint.png")


    print("coca line:")
    neutral2 = palette_for("kush")
    for age, sprite in enumerate([COCA_AGE0, COCA_AGE1, COCA_AGE2, COCA_AGE3]):
        write(render(sprite, neutral2), "block", f"coca_crop_age{age}.png")
    write(render(COCA_LEAVES, neutral2), "item", "coca_leaves.png")
    write(render(COCA_PASTE, neutral2), "item", "coca_paste.png")
    write(render(COCA_POWDER, neutral2), "item", "coca_powder.png")
    write(render(SEEDS.translate(str.maketrans({"3": "C"})), neutral2), "item", "coca_seeds.png")

    # Machine materials for the rebuilt press and refiner. The old per-stage
    # full-face sprites are gone: the stages live in the geometry now, so these
    # are surfaces rather than pictures of a machine at a moment.
    write(render(PRESS_STONE, MACHINE_PAL), "block", "press_stone.png")
    write(render(PRESS_WOOD, MACHINE_PAL), "block", "press_wood.png")
    write(render(PRESS_IRON, MACHINE_PAL), "block", "press_iron.png")
    write(render(PRESS_PULP, MACHINE_PAL), "block", "press_pulp.png")
    write(render(PRESS_VOID, MACHINE_PAL), "block", "press_void.png")

    write(render(REFINER_BRICK, MACHINE_PAL), "block", "refiner_brick.png")
    write(render(REFINER_COPPER, MACHINE_PAL), "block", "refiner_copper.png")
    write(render(REFINER_GLASS, MACHINE_PAL), "block", "refiner_glass.png")

    # Ember plate per stage: cold, warming, hot, then the peak, which pulses so
    # "come get it" is visible from across the room without looking straight at
    # it -- the one thing worth keeping from the old refiner_3 animation.
    for step, colour in enumerate(("d", "d", "e", "e", "d")):
        if step == 3:
            write_animated([render(REFINER_EMBER.translate(str.maketrans({"A": c})),
                                   MACHINE_PAL) for c in ("E", "e", "E", "d")],
                           3, "block", f"refiner_ember_{step}.png")
        else:
            write(render(REFINER_EMBER.translate(str.maketrans({"A": colour})),
                         MACHINE_PAL), "block", f"refiner_ember_{step}.png")
    # Idle firebox: banked coals, barely alive.
    write(render(REFINER_EMBER.translate(str.maketrans({"A": "n"})), MACHINE_PAL),
          "block", "refiner_ember_idle.png")

    write(render(BAKED_ICON, palette_for("purp")), "item", "effect_baked.png")
    # Vanilla reads status effect icons from textures/mob_effect/<name>.png.
    write(render(BAKED_ICON, palette_for("purp")), "mob_effect", "baked.png")
    write(render(TOLERANCE_ICON, palette_for("purp")), "mob_effect", "tolerance.png")


if __name__ == "__main__":
    main()
