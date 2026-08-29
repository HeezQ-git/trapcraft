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
    "L": "#fff8d8",   # marquee lamp, hot
    "g": "#54d37a",   # win lamp
    "e": "#c8ccd8",   # chrome
    # The reels behind the glass.
    "f": "#efe8d4",   # reel paper
    "F": "#cfc4a4",   # reel paper, shaded / separator
    "c": "#c22730",   # cherry
    "s": "#3f8a2f",   # cherry stem
    "z": "#2b56c4",   # lucky seven
}

# A fielded lacquer panel, not red noise: border, gold pinstripe corners,
# sheen high on the panel and shadow low, the way sprayed lacquer actually
# catches a room's light.
SLOT_BODY = """
dRRRRRRRRRRRRRRd
RrrrrrrrrrrrrrrR
Rrddddddddddddrd
Rrdyrrrrrrrrydrd
RrdrRRrrrrRrrdrd
RrdrRrrrrrrrrdrd
Rrdrrrrrrrrrrdrd
Rrdrrrrrrrrrrdrd
Rrdrrrrrrrrrrdrd
Rrdrrdrrrrrrrdrd
Rrdrrrrrrrdrrdrd
Rrdyrrrrrrrrydrd
Rrddddddddddddrd
RrrrrrrrrrrrrrrR
drrdrrrrrrrrdrrd
dddddddddddddddd
"""

# The reel: one 16-row strip, four symbols with a shaded gap row between.
# All three reels are this strip started at a different row, which is why
# they never look synchronised -- see slot_screen_frames().
SLOT_REEL = """
ffsf
fccf
fccf
FFFF
fYyf
yYyy
fyyf
FFFF
zzzf
ffzf
fzff
FFFF
kkkk
kffk
kkkk
FFFF
"""


def slot_screen_frames() -> list[str]:
    """The reel window: three reels spinning behind glass.

    Four frames, each reel rolled four rows further on -- a quarter of the
    strip per frame, so the loop closes exactly. The phases (0, 5, 10) keep
    the reels out of step, which is what makes three copies of one strip
    read as three independent reels. The glass itself stays still: chrome
    payline ticks and a translucent glare that never moves, painted over
    whatever the reels are doing.
    """
    strip = SLOT_REEL.strip("\n").split("\n")
    frames = []
    for f in range(4):
        rows = ["k" * 16]
        for y in range(14):
            cells = []
            for phase in (0, 5, 10):
                cells.append(strip[(y + 4 * f + phase) % 16])
            rows.append("k" + "k".join(cells) + "k")
        rows.append("k" * 16)
        # The still glass: chrome payline ticks either side of mid-height.
        # No translucent glare -- the carrier renders on the solid layer,
        # where partial alpha comes out as a solid white smear.
        for tick_row in (7, 8):
            rows[tick_row] = "e" + rows[tick_row][1:15] + "e"
        frames.append("\n".join(rows))
    return frames


# The marquee: brass rails round a lamp chase over a lacquer field. Only the
# lamp row moves; everything else is the same map in every frame.
SLOT_MARQUEE_BASE = """
oyyyyyyyyyyyyyyo
LwwwLwwwLwwwLwww
drrrrrrrrrrrrrrd
oyyyyyyyyyyyyyyo
rrrrrrrrrrrrrrrr
rrrrrrYYrrrrrrrr
rrrrrYYYYrrrrrrr
rrrryYYYYyrrrrrr
rrrrrYYYYrrrrrrr
rrrrrrYYrrrrrrrr
rrrrrrrrrrrrrrrr
drrrrrrrrrrrrrrd
oyyyyyyyyyyyyyyo
LwwwLwwwLwwwLwww
drrrrrrrrrrrrrrd
oooooooooooooooo
"""


def slot_marquee_frames() -> list[str]:
    """Chase the marquee lamps: the lit bulb walks one step per frame."""
    frames = []
    rows = SLOT_MARQUEE_BASE.strip("\n").split("\n")
    for f in range(4):
        chased = list(rows)
        for lamp_row in (1, 13):
            lamps = rows[lamp_row]
            chased[lamp_row] = lamps[-f:] + lamps[:-f] if f else lamps
        frames.append("\n".join(chased))
    return frames

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


# A speaker stack with the neon on. Deliberately dark: it is the only block in
# the mod meant to be looked at in an unlit room, and a bright one would read
# as a jukebox sitting in a field.
CLUB_PAL = {
    "x": "#0d0a12",     # cabinet, outline
    "c": "#1c1726",     # cabinet
    "C": "#2b2438",     # cabinet, lit
    "g": "#3a3050",     # grille
    "n": "#c026a8",     # neon
    "N": "#ff5ad8",     # neon, hot
    "b": "#2b8ce0",     # neon, cold
    "B": "#6fc4ff",     # neon, cold lit
    "k": "#070510",     # cone, dark
}

CLUB_FRONT = """
xxxxxxxxxxxxxxxx
xNnnnnnnnnnnnnNx
xnCCCCCCCCCCCCnx
xnCggggggggggCnx
xnCgkkkkkkkkgCnx
xnCgkxxxxxxkgCnx
xnCgkxNNNNxkgCnx
xnCgkxNNNNxkgCnx
xnCgkxxxxxxkgCnx
xnCgkkkkkkkkgCnx
xnCggggggggggCnx
xnCCCCCCCCCCCCnx
xbBBBBBBBBBBBBbx
xcccccccccccccCx
xcggggggggggggcx
xxxxxxxxxxxxxxxx
"""

STALL_PAL = {
    "w": "#7a5a34",   # counter timber
    "W": "#96703f",   # timber, lit
    "d": "#5c4326",   # timber, shadow
    "c": "#b23b3b",   # awning stripe, red
    "C": "#d45252",   # awning, lit
    "n": "#e8e2d2",   # awning stripe, cream
    "N": "#f6f2e6",   # awning, lit cream
    "g": "#3f7a33",   # produce
    "G": "#5f9e42",   # produce, lit
    "o": "#c07a2a",   # citrus
    "O": "#e8a441",   # citrus, lit
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

# Goods on the counter, seen edge-on: the awning covers the top of the
# stall now, so this is only ever the band between countertop and eave.
# Two rows tiled the height of the sprite, so any slice of it lands on a
# whole band whatever uv the element derives.
STALL_GOODS = """
kGGkkOOkkCCkkEEk
gGGgoOOocCCceEEe
kGGkkOOkkCCkkEEk
gGGgoOOocCCceEEe
kGGkkOOkkCCkkEEk
gGGgoOOocCCceEEe
kGGkkOOkkCCkkEEk
gGGgoOOocCCceEEe
kGGkkOOkkCCkkEEk
gGGgoOOocCCceEEe
kGGkkOOkkCCkkEEk
gGGgoOOocCCceEEe
kGGkkOOkkCCkkEEk
gGGgoOOocCCceEEe
kGGkkOOkkCCkkEEk
gGGgoOOocCCceEEe
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

# The skirt the tables wear now that they are closed to the carpet: two
# fielded mahogany panels under a brass pin line. The model's skirt band
# shows rows 2-14 of this, so the panels live there and the top rows are
# rail for anything else that slices it.
TABLE_SKIRT = """
mMMMMMMMMMMMMMMm
wwwwwwwwwwwwwwww
wdddddddwddddddw
wdWWWWWdwdWWWWWd
wdWwwwWdwdWwwwWd
wdWwwwWdwdWwwwWd
wdWwWwWdwdWwWwWd
wdWwwwWdwdWwwwWd
wdWwwwWdwdWwwwWd
wdWwwwWdwdWwwwWd
wdWwWwWdwdWwWwWd
wdWwwwWdwdWwwwWd
wdWWWWWdwdWWWWWd
wdddddddwddddddw
wwwwwwwwwwwwwwww
dddddddddddddddd
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

# --- the bar --------------------------------------------------------------
#
# Its own palette rather than more keys bolted onto TABLE_PAL, which is
# already shared by six maps and warns you about exactly this. The bar stopped
# being a gaming table with props on it when it got a front, and the front is
# the whole reason it now has to be placed facing somewhere.

BAR_PAL = {
    "x": "#140d08",     # outline / seam
    "k": "#0b0704",     # deep shadow
    "w": "#3a2415",     # counter wood
    "W": "#5a3a22",     # wood, lit
    "d": "#26160c",     # wood, shadow
    "v": "#6b4526",     # panel field
    "m": "#c9a227",     # brass
    "M": "#f0cf5a",     # brass, lit
    "n": "#8a6a1c",     # brass, shadow
    "l": "#e8e2d4",     # bottle label
    "L": "#f7f3e8",     # label, lit
    "c": "#b9c6cc",     # tumbler glass
    "C": "#e6f0f4",     # tumbler glass, lit
    "s": "#7a4a18",     # what's in the tumbler
    # 1 and 2 are the glass the BOTTLE map is filled with, so one drawing
    # gives you the whole back bar. See BAR_GLASSWARE.
}

# What the bottles are made of. Dark first, then the highlight.
BAR_GLASSWARE = {
    "green": ("#1e6b3a", "#37a05c"),
    "amber": ("#8a4a12", "#c9761f"),
    "clear": ("#5f7d86", "#9fc0cb"),
}

# The counter top, seen from above: polished wood, two rings somebody didn't
# wipe up, and the brass strip along the customer's edge.
BAR_TOP = """
mMMMMMMMMMMMMMMm
wwwwwwwwwwwwwwww
wWWWWWWWWWWWWWWw
wWWdWWWWWWWdWWWw
wWWWWWWWWWWWWWWw
wWWWWWxxWWWWWWWw
wWWWWxWWxWWWWWWw
wWWWWxWWxWWWdWWw
wWWWWWxxWWWWWWWw
wWdWWWWWWWWWWWWw
wWWWWWWWWWxxWWWw
wWWWWWWWWxWWxWWw
wWWWWdWWWxWWxWWw
wWWWWWWWWWxxWWWw
wWWWWWWWWWWWWWWw
wwwwwwwwwwwwwwww
"""

# The panelled front the customer stands at: two fielded panels, brass beading
# top and bottom. Every pixel is painted, because this one gets stretched over
# boxes of every shape and a transparent row would read as a hole in the bar.
BAR_FRONT = """
mMMMMMMMMMMMMMMm
wwwwwwwwwwwwwwww
wdxxxxxdwdxxxxxd
wxvvvvvxwxvvvvvx
wxvWWWvxwxvWWWvx
wxvWWWvxwxvWWWvx
wxvWWWvxwxvWWWvx
wxvWWWvxwxvWWWvx
wxvWWWvxwxvWWWvx
wxvWWWvxwxvWWWvx
wxvWWWvxwxvWWWvx
wxvvvvvxwxvvvvvx
wdxxxxxdwdxxxxxd
wwwwwwwwwwwwwwww
mMMMMMMMMMMMMMMm
dddddddddddddddd
"""

# The back board the bottles stand against: tongue-and-groove, dark.
BAR_SHELF = """
dddddddddddddddd
wWWWwdwWWWwdwWWw
wWWWwdwWWWwdwWWw
wWWWwdwWWWwdwWWw
wWWWwdwWWWwdwWWw
wWWWwdwWWWwdwWWw
wWWWwdwWWWwdwWWw
wWWWwdwWWWwdwWWw
wWWWwdwWWWwdwWWw
wWWWwdwWWWwdwWWw
wWWWwdwWWWwdwWWw
wWWWwdwWWWwdwWWw
wWWWwdwWWWwdwWWw
wWWWwdwWWWwdwWWw
wWWWwdwWWWwdwWWw
dddddddddddddddd
"""

# Brass, mottled rather than banded, because it is worn by the foot rail
# (horizontal) and the tap tower (vertical) and a stripe can only be right for
# one of them.
BAR_BRASS = """
nmmMMMMmmMMMMmmn
mMMMMMMMMMMMMMMm
mMMmMMMMmMMMMMMm
mMMMMMMMMMMMmMMm
mMMMMmMMMMMMMMMm
mMMMMMMMMMmMMMMm
mMMmMMMMMMMMMMMm
mMMMMMMMMmMMMMMm
mMMMMMmMMMMMMMMm
mMMMMMMMMMMMmMMm
mMMmMMMMMMMMMMMm
mMMMMMMMmMMMMMMm
mMMMMMMMMMMMMMMm
mMMMMmMMMMMMMMMm
mMMMMMMMMMMMMMMm
nmmMMMMmmMMMMmmn
"""

# One bottle, drawn once and filled three times. Every element that uses it
# names uv [0,0,16,16], so the whole drawing lands on a 2px-wide box and you
# get a bottle rather than a slice of one.
BAR_BOTTLE = """
......xxxx......
......x11x......
......x11x......
......x21x......
.....xx11xx.....
....xx1111xx....
...xx111111xx...
...x1llllll1x...
...x1LLLLLL1x...
...x1llllll1x...
...x11111111x...
...x12111111x...
...x11111112x...
...x11111111x...
...xxxxxxxxxx...
................
"""

# A tumbler with something brown in it, stood on the counter.
BAR_GLASS = """
................
...xxxxxxxxxx...
...xCccccccCx...
...xcCccccccx...
...xcssssssCx...
...xcsssssssx...
...xcssssssCx...
...xcsssssssx...
...xcssssssCx...
...xcsssssssx...
...xcssssssCx...
...xcsssssssx...
....xsssssCx....
....xxxxxxxx....
................
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
    "G": "#5ee394",     # winning slot, lit
    "r": "#c03a3a",     # losing slot
    "R": "#e05a5a",     # losing slot, lit
    "y": "#e8c33a",     # edge slot
    "k": "#07090f",     # the void behind the tray
}

# The backboard behind the 3D pegs: midnight blue with faint fall-trails
# under the peg columns, and one silver ball caught mid-drop. The pegs
# themselves are geometry now (see plinko_model), so the board stopped
# painting them -- a printed peg behind a real one reads as a double.
PLINKO_BOARD = """
dbbbbbbbbbbbbbbd
bbbbBbbbbbbbBbbb
bbBbbbbbBbbbbbbb
bbbbBbbbbbbbBbbb
bbBbbbbbBbbbbbbb
bbbbbbbbbbbbBbbb
bbBbbbbpPbbbbbbb
bbbbBbbpPbbbBbbb
bbBbbbbbbbbbbbbb
bbbbBbbbBbbbBbbb
bbBbbbbbbbbbbbbb
bbbbBbbbBbbbBbbb
bbBbbbbbbbbbbbbb
bbbbBbbbBbbbBbbb
bbbbbbbbbbbbbbbb
dbbbbbbbbbbbbbbd
"""

# The lower face: trails continue, then the painted slot backs the fins
# divide -- green pays, red does not -- and the dark void behind the tray.
PLINKO_SLOTS = """
dbbbbbbbbbbbbbbd
bbbbBbbbbbbbBbbb
bbBbbbbbBbbbbbbb
bbbbBbbbbbbbBbbb
bbBbbbbbBbbbbbbb
bbbbBbbbbbbbBbbb
bbBbbbbbBbbbbbbb
wwwwwwwwwwwwwwww
wwGGGwRRwwRRwGGw
wwgggwrrwwrrwggw
wwgggwrrwwrrwggw
wwgggwrrwwrrwggw
wwwwwwwwwwwwwwww
kkkkkkkkkkkkkkkk
kkkkkkkkkkkkkkkk
kkkkkkkkkkkkkkkk
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

# The cabinet flanks: a fielded violet panel in a lit frame, so a row of
# cabinets reads as furniture from the side rather than as a purple wall.
PLINKO_SIDE = """
mMwwwwwwwwwwwwMm
MWWWWWWWWWWWWWWM
wWddddddddddddWw
wWdwwwwwwwwwwdWw
wWdwWwwwwwwWwdWw
wWdwwwwwdwwwwdWw
wWdwwWwwwwwwwdWw
wWdwwwwwwwWwwdWw
wWdwWwwdwwwwwdWw
wWdwwwwwwwwWwdWw
wWdwwWwwwwwwwdWw
wWdwwwwwWwwwwdWw
wWdwwwwwwwwwwdWw
wWddddddddddddWw
MWWWWWWWWWWWWWWM
mMwwwwwwwwwwwwMm
"""

# One peg, drawn as a full tile: the model samples the centre window
# (uv 6..10), so the middle four pixels are the peg's polished face and the
# rest is the shadowed steel it would read as if anything else sliced it.
PLINKO_PEG = """
bbbbbbbbbbbbbbbb
bbbbbppppppbbbbb
bbbppppppppppbbb
bbpppPPPPpppppbb
bbppPPPPPPppppbb
bpppPPPPPPpppppb
bppPPPPPPPPppppb
bppPPPPPPPPppppb
bpppPPPPPPpppppb
bbppppPPppppppbb
bbpppppppppppbbb
bbbppppppppppbbb
bbbbbppppppbbbbb
bbbbbbbbbbbbbbbb
bbbbbbbbbbbbbbbb
bbbbbbbbbbbbbbbb
"""


def plinko_marquee_frames() -> list[str]:
    """The marquee stripe: a gold barber chase sliding across the crown.

    The crown face samples row 0 of this, so the animation only has to be
    honest along one row -- the rest of the map is the same diagonal drawn
    down the sheet for anything else that slices it. Four frames, period
    four, so the loop closes.
    """
    base = "MmwwMmwwMmwwMmww"
    frames = []
    for f in range(4):
        rows = []
        for y in range(16):
            shift = (y + f) % 4
            rows.append(base[-shift:] + base[:-shift] if shift else base)
        frames.append("\n".join(rows))
    return frames

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

# The felt: green baize with the LAYOUT painted on it -- a white border
# line running under the wheel, and the red/black betting cells along the
# player's edge. A roulette top without the print is a rug.
ROULETTE_FELT = """
kkkkkkkkkkkkkkkk
kggggggggggggggk
kgSSSSSSSSSSSSgk
kgSggggggggggSgk
kgSggGggggggkSgk
kgSggggggkgggSgk
kgSgGggggggggSgk
kgSgggggggGggSgk
kgSggkgggggggSgk
kgSggggGgggggSgk
kgSgggggggkggSgk
kgSgGggggggggSgk
kgSSSSSSSSSSSSgk
kgrrbbrrbbrrbbgk
kgrrbbrrbbrrbbgk
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


def roulette_wheel_map() -> str:
    """The wheel head, drawn by radius so the rings come out true.

    Wood square, gold rim, sixteen alternating pockets, silver bowl, gold
    hub -- and the ball sitting in one pocket. Drawn as geometry rather than
    typed, because concentric circles at 16px are exactly the thing fingers
    get wrong and arithmetic gets right. The corners are PAINTED wood, not
    transparent: the carrier renders on the solid layer now, where an alpha
    hole comes out as a black pixel, not a hole.

    The spin is four frames of this image rotated 90 degrees at a time --
    see the write in main(). Rotation is why the map must read correctly
    at every quarter turn: rings are rotation-proof by construction, and
    the ball orbits, which is the whole point.
    """
    import math
    rows = []
    for y in range(16):
        row = ""
        for x in range(16):
            dx, dy = x - 7.5, y - 7.5
            distance = math.hypot(dx, dy)
            if distance > 7.9:
                row += "w"                      # wood corners of the head
            elif distance > 6.7:
                row += "m"                      # gold rim
            elif distance > 4.4:
                # Sixteen pockets, alternating. atan2 sweeps -pi..pi, so
                # scale to sixteenths and let parity paint the wedge.
                sector = int((math.atan2(dy, dx) + math.pi) / (2 * math.pi) * 16)
                row += "r" if sector % 2 == 0 else "b"
            elif distance > 1.8:
                # The bowl: brushed silver, lit toward the top-left the way
                # every other sprite in the mod carries its light.
                row += "S" if (dx + dy) < -2.5 else "s"
            else:
                row += "M"                      # gold hub
        rows.append(row)
    # The ball, resting in a pocket at the wheel's north-east. NOT at the
    # rim: the model's disc face crops this texture to 3.5..12.5, so a ball
    # any further out exists only on the side slices where nobody can name
    # it. Two pixels, bright over shadow, inside the crop.
    rows[4] = rows[4][:11] + "Ss" + rows[4][13:]
    rows[5] = rows[5][:11] + "s" + rows[5][12:]
    return "\n".join(rows)

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


# --- wands ------------------------------------------------------------------
#
# One sprite, recoloured five ways. They are a family and should read as one
# from across a hotbar: what tells a boost wand from a storm wand is the colour
# of the stone in the head, not a redraw.
#
# Flat, on the same diagonal as a stick or a blaze rod, rather than the 3D
# model these shipped with first. A wand is a stick with something on the end
# and it reads better as one -- and it puts the gem where the eye already looks
# for a tool's business end.

WAND_PAL = {
    "w": "#3b2a1c",     # wand wood, shadow
    "W": "#5a3f2a",     # wand wood
    "H": "#7a5738",     # wand wood, lit edge
    "n": "#241a11",     # grain
    "l": "#2f2318",     # leather wrap, shadow
    "L": "#4a382a",     # leather wrap
    "T": "#6b5340",     # leather wrap, lit
    "s": "#c8b98a",     # binding thread
}

# Gem at the tip, wrapped grip at the butt, on the stick diagonal. Rendered
# once per wand with the three stone colours swapped, which is why the map
# itself is colourless.
WAND = """
................
..........GGG...
.........GBBBG..
........GBdddBG.
........GBdddBG.
.........GBBBG..
.........HW.....
........HW......
.......HW.......
......HW........
.....HW.........
....TL..........
...TL...........
..TL............
.wW.............
................
"""

# lit face, body, core -- in that order, per wand.
WAND_GEMS = {
    "boost": ("#d8f4ff", "#6fd0f0", "#2b7fa8"),      # wind and cold light
    "harvest": ("#e6f9a8", "#8fd44a", "#3f7a22"),    # a field in July
    "prospect": ("#ffcf8a", "#d9822b", "#7a3d0a"),   # lamplight on ore
    "builder": ("#dccfff", "#9a7ce0", "#4b3287"),    # amethyst, near enough
    # White-hot in the middle rather than dark: at 16px a gold stone with a
    # brown heart is the prospecting one, and two wands that look alike in a
    # hotbar are two wands somebody casts by mistake.
    "storm": ("#fff08a", "#ffd21f", "#fffbe0"),      # the flash, held still
}


# --- cases and keys ---------------------------------------------------------
#
# Same trick as the wands above: two sprites, recoloured four ways each. A tier
# is a colour and nothing else, which is the right call for a family somebody
# will have all four of in one chest -- a redraw per tier would make them four
# unrelated items instead of one ladder.
#
# The pairing is the other half of it. A case and its key share a palette
# exactly, so "which key opens this" is answerable across a hotbar without
# reading a tooltip.

CASE_KEY_TIERS = {
    # frame, lit face, body -- darkest to lightest, per tier.
    "street": ("#2b2b2f", "#9aa0a8", "#6e747c", "#4a4f56"),    # tin and grease
    "docks": ("#12333a", "#5fd3d8", "#2e8f97", "#1d5f66"),     # wet steel
    "cartel": ("#3a2a06", "#ffd75e", "#d9a41f", "#9a7212"),    # money
    "phantom": ("#17111f", "#c08bff", "#6c3fa8", "#3a2159"),   # the black one
}

# A crate: lid, seam, latch, body. The latch sits across the seam so the thing
# reads as SHUT, which is the entire point of a case you haven't got a key for.
CASE = """
................
................
.dddddddddddddd.
.dHHHHHHHHHHHHd.
.dLLLLLLLLLLLLd.
.dLLLLLmmLLLLLd.
.ddddddmmdddddd.
.dBBBBBmmBBBBBd.
.dBBBBBMMBBBBBd.
.dBBBBBBBBBBBBd.
.dBBBBBBBBBBBBd.
.dBBBBBBBBBBBBd.
.dddddddddddddd.
................
................
................
"""

# Bow at the top, two teeth at the bottom right. Upright rather than on the
# stick diagonal the wands use: a key lying diagonally in a hotbar reads as
# another wand, and these two families must not be confusable.
KEY = """
................
.....ooooo......
....oHHHHHo.....
...oHOo.oOHo....
...oHo...oOo....
...oOOo.oOOo....
....oOOOOOo.....
.....oSSSo......
......sSs.......
......sSs.......
......sSsO......
......sSs.......
......sSsOO.....
......sSs.......
.......s........
................
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

# The same face again, wide awake. Wired is the coca high, so it is the Baked
# character with its eyes forced open and a bolt through it -- pinned pupils,
# a hard flat stare, and none of the droop the other two have. Different enough
# to read at a glance in the effect list, related enough to belong to the set.
#
# It had no icon at all until now, which vanilla renders as the missing-texture
# chequer -- the black and purple somebody reported. check_models.py now fails
# if a registered effect has no file, because a missing icon looks exactly like
# a broken mod and says nothing about which one.
WIRED_ICON = """
................
................
....44....44....
...4114..4114...
...4141..4141...
...4114..4114...
....44....44....
................
.......EE.......
......EE........
.....EEEEE......
.......EE.......
......EE........
................
.....444444.....
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


# --- the hospital -----------------------------------------------------------
#
# White tile and a red cross, because that is what a hospital looks like
# everywhere and nothing else in this mod is either. It has to be the one
# building in a street of green awnings and brass tills you can find at a
# glance, at night, while something is chasing you.

HOSPITAL_PAL = {
    "w": "#e6e8ec",     # tile
    "W": "#fdfefe",     # tile, lit
    "g": "#b9bec6",     # grout
    "d": "#8f959e",     # shadow
    "r": "#a82222",     # cross
    "R": "#e04040",     # cross, lit
    "x": "#4a4f57",     # outline
}

HOSPITAL_FACE = """
xxxxxxxxxxxxxxxx
xWWWWWWWWWWWWWWx
xgwwwwwwwwwwwwgx
xgwwwwwrrwwwwwgx
xgwwwwwRRwwwwwgx
xgwwwwwRRwwwwwgx
xgwwrrRRRRrrwwgx
xgwwRRRRRRRRwwgx
xgwwrrRRRRrrwwgx
xgwwwwwRRwwwwwgx
xgwwwwwRRwwwwwgx
xgwwwwwrrwwwwwgx
xgwwwwwwwwwwwwgx
xgddddddddddddgx
xWWWWWWWWWWWWWWx
xxxxxxxxxxxxxxxx
"""

HOSPITAL_SIDE = """
xxxxxxxxxxxxxxxx
xWWWWWWWWWWWWWWx
xgwwwwwwgwwwwwgx
xgwwwwwwgwwwwwgx
xgwwwwwwgwwwwwgx
xggggggggggggggx
xgwwwgwwwwwwwwgx
xgwwwgwwwwwwwwgx
xgwwwgwwwwwwwwgx
xggggggggggggggx
xgwwwwwwgwwwwwgx
xgwwwwwwgwwwwwgx
xgwwwwwwgwwwwwgx
xgddddddddddddgx
xWWWWWWWWWWWWWWx
xxxxxxxxxxxxxxxx
"""

HOSPITAL_TOP = """
xxxxxxxxxxxxxxxx
xWWWWWWWWWWWWWWx
xgwwwwwwgwwwwwgx
xgwwwwwwgwwwwwgx
xgwwwwwwgwwwwwgx
xgwwwwwwgwwwwwgx
xgwwwwwwgwwwwwgx
xggggggggggggggx
xgwwwwwwgwwwwwgx
xgwwwwwwgwwwwwgx
xgwwwwwwgwwwwwgx
xgwwwwwwgwwwwwgx
xgwwwwwwgwwwwwgx
xgwwwwwwgwwwwwgx
xWWWWWWWWWWWWWWx
xxxxxxxxxxxxxxxx
"""


# --- the fire station -------------------------------------------------------
#
# Red brick, a wide pale garage door across the face, and a bell over it. The
# door is the load-bearing half for the same reason the police chequer is: a
# fire station is the one civic building whose shape is recognisable before any
# marking on it, because the whole front of it is a hole big enough to drive
# through. Everything else on this face exists to say that opening is a door
# and not a window -- the sill under it, the lintel over it, and the bell.
#
# Warmer and lighter than the nick on purpose. The two buildings will stand on
# the same street, both are red-and-something, and the pair has to be tellable
# apart at a glance in the dark: navy-on-grey against red-on-cream.

FIRE_PAL = {
    "b": "#9c3a2c",     # brick
    "B": "#b04a38",     # brick, lit
    "d": "#6f2820",     # brick, shadow
    "m": "#7d3229",     # mortar course
    "w": "#e6ddcc",     # cream door
    "W": "#f2ebdd",     # cream door, lit
    "g": "#c3b8a4",     # door groove
    "s": "#4a4a4f",     # slate sill
    "y": "#d8b23c",     # brass bell
    "Y": "#efd06a",     # brass, lit
    "x": "#2a1712",     # outline
}

FIRE_FACE = """
xxxxxxxxxxxxxxxx
xBBBBBBBBBBBBBBx
xbbbbbyYYybbbbbx
xbbbbyYyyYybbbbx
xbbbbyyyyyybbbbx
xbbbbbsssssbbbbx
xssssssssssssssx
xswWWWWWWWWWWwsx
xswWgWWggWWgWwsx
xswWgWWggWWgWwsx
xswWgWWggWWgWwsx
xswWgWWggWWgWwsx
xswwwwwwwwwwwwsx
xssssssssssssssx
xBBBBBBBBBBBBBBx
xxxxxxxxxxxxxxxx
"""

FIRE_SIDE = """
xxxxxxxxxxxxxxxx
xBBBBBBBBBBBBBBx
xbbbbmbbbbbbbbbx
xbbbbmbbbbbbbbbx
xmmmmmmmmmmmmmmx
xbbbbbbbbbmbbbbx
xbbbbbbbbbmbbbbx
xmmmmmmmmmmmmmmx
xbbbbmbbbbbbbbbx
xbbbbmbbbbbbbbbx
xmmmmmmmmmmmmmmx
xbbbbbbbbbmbbbbx
xbbbbbbbbbmbbbbx
xddddddddddddddx
xBBBBBBBBBBBBBBx
xxxxxxxxxxxxxxxx
"""

FIRE_TOP = """
xxxxxxxxxxxxxxxx
xBBBBBBBBBBBBBBx
xbbbbbbmbbbbbbbx
xbbbbbbmbbbbbbbx
xmmmmmmmmmmmmmmx
xbbbbbbmbbbbbbbx
xbbbbbbmbbbbbbbx
xmmmmmmmmmmmmmmx
xbbbbbbmbbbbbbbx
xbbbbbbmbbbbbbbx
xmmmmmmmmmmmmmmx
xbbbbbbmbbbbbbbx
xbbbbbbmbbbbbbbx
xbbbbbbmbbbbbbbx
xBBBBBBBBBBBBBBx
xxxxxxxxxxxxxxxx
"""


# --- the police station -----------------------------------------------------
#
# Grey civic concrete, a blue-and-white chequer band across the top, and a
# silver shield under it. The chequer is the load-bearing half: it is the one
# marking that says "police" in every country that has any, it survives being
# sixteen pixels wide because it is nothing but alternating squares, and it
# reads at a glance from across a street at night -- which is the same test the
# hospital's red cross was built to pass.
#
# Deliberately NOT navy overall. A solid dark block on a dark street is a hole;
# the concrete is the same value as vanilla stone brick so the building reads
# as municipal, and only the band and the badge carry the colour.

POLICE_PAL = {
    "c": "#6a6f78",     # concrete
    "C": "#7d838d",     # concrete, lit
    "g": "#565a62",     # course line
    "d": "#3f4349",     # shadow
    "n": "#23386b",     # navy
    "N": "#33528f",     # navy, lit
    "w": "#dfe4ec",     # white
    "s": "#aab2be",     # silver
    "S": "#848c99",     # silver, shaded
    "y": "#c9a227",     # brass
    "x": "#262a2f",     # outline
}

POLICE_FACE = """
xxxxxxxxxxxxxxxx
xCCCCCCCCCCCCCCx
xcNwNwNwNwNwNwcx
xcwNwNwNwNwNwNcx
xcggggggggggggcx
xcccsssssssscccx
xcccsNNyyNNscccx
xcccsyyyyyyscccx
xcccsNyyyyNscccx
xcccsNyNNyNscccx
xcccSsNNNNsScccx
xccccSsNNsSccccx
xcccccSssScccccx
xcddddddddddddcx
xCCCCCCCCCCCCCCx
xxxxxxxxxxxxxxxx
"""

POLICE_SIDE = """
xxxxxxxxxxxxxxxx
xCCCCCCCCCCCCCCx
xccccccgcccccccx
xccccccgcccccccx
xccccccgcccccccx
xggggggggggggggx
xcccgccccccccccx
xcccgccccccccccx
xcccgccccccccccx
xggggggggggggggx
xccccccgcccccccx
xccccccgcccccccx
xccccccgcccccccx
xcddddddddddddcx
xCCCCCCCCCCCCCCx
xxxxxxxxxxxxxxxx
"""

POLICE_TOP = """
xxxxxxxxxxxxxxxx
xCCCCCCCCCCCCCCx
xccccccgcccccccx
xccccccgcccccccx
xccccccgcccccccx
xccccccgcccccccx
xggggggggggggggx
xccccccgcccccccx
xccccccgcccccccx
xccccccgcccccccx
xccccccgcccccccx
xccccccgcccccccx
xccccccgcccccccx
xccccccgcccccccx
xCCCCCCCCCCCCCCx
xxxxxxxxxxxxxxxx
"""


# --- the mailbox ------------------------------------------------------------
#
# Post box green with a brass slot and a red flag, because those three colours
# together are "post" everywhere on earth and nothing else in this mod is any
# of them. It has to be readable as street furniture at thirty blocks.

MAILBOX_PAL = {
    "g": "#1d4a33",     # box, body
    "G": "#2a6a49",     # box, lit
    "k": "#122e20",     # box, shadow
    "m": "#b08a2a",     # brass
    "M": "#e0bb56",     # brass, lit
    "r": "#a12626",     # flag
    "R": "#cf3b3b",     # flag, lit
    "w": "#6b4a2a",     # post
    "W": "#8a6238",     # post, lit
    "d": "#3d2916",     # post, shadow
    "x": "#0d0d0d",     # outline
}

MAILBOX_BOX = """
xxxxxxxxxxxxxxxx
xGGGGGGGGGGGGGGx
xGggggggggggggGx
xGggggggggggggGx
xGgggmmmmmmggggx
xGgggmMMMMmggggx
xGgggmmmmmmggggx
xGggggggggggggGx
xGgggggggggggggx
xGggggkkkkgggggx
xGgggggggggggggx
xkgggggggggggggx
xkkkkkkkkkkkkkkx
xxxxxxxxxxxxxxxx
................
................
"""

MAILBOX_POST = """
xxxxxxxxxxxxxxxx
xWwwddwwWwwddwwx
xWwwddwwWwwddwwx
xWwwddwwWwwddwwx
xWwwddwwWwwddwwx
xWwwddwwWwwddwwx
xWwwddwwWwwddwwx
xWwwddwwWwwddwwx
xWwwddwwWwwddwwx
xWwwddwwWwwddwwx
xWwwddwwWwwddwwx
xWwwddwwWwwddwwx
xWwwddwwWwwddwwx
xWwwddwwWwwddwwx
xWwwddwwWwwddwwx
xxxxxxxxxxxxxxxx
"""

MAILBOX_FLAG = """
xxxxxxxxxxxxxxxx
xRRRRRRRRRRRRRRx
xRrrrrrrrrrrrrRx
xRrrrrrrrrrrrrRx
xRrrrrrrrrrrrrRx
xRrrrrrrrrrrrrRx
xRrrrrrrrrrrrrRx
xRrrrrrrrrrrrrRx
xRrrrrrrrrrrrrRx
xRrrrrrrrrrrrrRx
xRrrrrrrrrrrrrRx
xRrrrrrrrrrrrrRx
xRrrrrrrrrrrrrRx
xmmmmmmmmmmmmmmx
xxxxxxxxxxxxxxxx
................
"""


# --- the city vault ---------------------------------------------------------
#
# Stone and brass with a green seam down the middle: it has to read as civic
# rather than as another machine, and as somewhere money is KEPT rather than
# somewhere money is played with. Nothing else in this mod is grey.

VAULT_PAL = {
    "s": "#6a6a6e",     # stone
    "S": "#8b8b90",     # stone, lit
    "d": "#46464a",     # stone, shadow
    "m": "#b08a2a",     # brass
    "M": "#e0bb56",     # brass, lit
    "g": "#1f7a45",     # emerald seam
    "G": "#35b168",     # emerald, lit
    "x": "#26262a",     # outline
}

VAULT_FACE = """
xxxxxxxxxxxxxxxx
xSsssssssssssSsx
xsddddddddddddsx
xsdmmmmmmmmmmdsx
xsdmMMMMMMMMmdsx
xsdmMggggggMmdsx
xsdmMgGGGGgMmdsx
xsdmMgGxxGgMmdsx
xsdmMgGxxGgMmdsx
xsdmMgGGGGgMmdsx
xsdmMggggggMmdsx
xsdmMMMMMMMMmdsx
xsdmmmmmmmmmmdsx
xsddddddddddddsx
xSsssssssssssSsx
xxxxxxxxxxxxxxxx
"""

VAULT_SIDE = """
xxxxxxxxxxxxxxxx
xSsssssssssssSsx
xsddddddddddddsx
xsdsssssssssssdx
xsdsSsssssssSsdx
xsdsssssssssssdx
xsdmmmmmmmmmmdsx
xsdmMMMMMMMMmdsx
xsdmmmmmmmmmmdsx
xsdsssssssssssdx
xsdsSsssssssSsdx
xsdsssssssssssdx
xsddddddddddddsx
xSsssssssssssSsx
xsddddddddddddsx
xxxxxxxxxxxxxxxx
"""

VAULT_TOP = """
xxxxxxxxxxxxxxxx
xSssssssssssssSx
xsdddddddddddddx
xsdmmmmmmmmmmmdx
xsdmMMMMMMMMMmdx
xsdmMgggggggMmdx
xsdmMgGGGGGgMmdx
xsdmMgGGGGGgMmdx
xsdmMgGGGGGgMmdx
xsdmMgggggggMmdx
xsdmMMMMMMMMMmdx
xsdmmmmmmmmmmmdx
xsdddddddddddddx
xSssssssssssssSx
xsdddddddddddddx
xxxxxxxxxxxxxxxx
"""



# --- the market shelf -------------------------------------------------------
#
# A stocked shelf seen from the front: three tiers of crates and jars on a
# timber frame. It has to read as a SHOP from across a square -- somewhere
# things are laid out to be bought -- rather than as another storage box.

SHELF_PAL = {
    "w": "#7a5330",     # timber
    "W": "#9a6d41",     # timber, lit
    "d": "#4b3119",     # timber, shadow
    "c": "#a8642f",     # crate
    "C": "#c98game",    # placeholder, replaced below
    "j": "#4f8f57",     # jar, green
    "J": "#6fb877",     # jar, lit
    "r": "#b0432f",     # jar, red
    "p": "#d9cba8",     # paper / bread
    "x": "#2a1c10",     # outline
}
SHELF_PAL["C"] = "#c9834a"

SHELF_FRONT = """
xxxxxxxxxxxxxxxx
xWwwwwwwwwwwwwWx
xwddddddddddddwx
xwcCcjJjrrpppdwx
xwcccjjjrrpppdwx
xwddddddddddddwx
xwWWWWWWWWWWWWwx
xwddddddddddddwx
xwpppcCcjJjrrdwx
xwpppcccjjjrrdwx
xwddddddddddddwx
xwWWWWWWWWWWWWwx
xwddddddddddddwx
xwjJjrrpppcCcdwx
xwjjjrrpppcccdwx
xxxxxxxxxxxxxxxx
"""

SHELF_SIDE = """
xxxxxxxxxxxxxxxx
xWwwwwwwwwwwwwWx
xwddddddddddddwx
xwWwwwwwwwwwwWwx
xwWwwwwwwwwwwWwx
xwddddddddddddwx
xwWWWWWWWWWWWWwx
xwddddddddddddwx
xwWwwwwwwwwwwWwx
xwWwwwwwwwwwwWwx
xwddddddddddddwx
xwWWWWWWWWWWWWwx
xwddddddddddddwx
xwWwwwwwwwwwwWwx
xwWwwwwwwwwwwWwx
xxxxxxxxxxxxxxxx
"""

SHELF_TOP = """
xxxxxxxxxxxxxxxx
xWWWWWWWWWWWWWWx
xWwwwwwwwwwwwwWx
xWwddddddddddwWx
xWwdpppcCcjJjwWx
xWwdpppcccjjjwWx
xWwddddddddddwWx
xWwwwwwwwwwwwwWx
xWwwwwwwwwwwwwWx
xWwddddddddddwWx
xWwdrrjJjpppcwWx
xWwdrrjjjpppcwWx
xWwddddddddddwWx
xWwwwwwwwwwwwwWx
xWWWWWWWWWWWWWWx
xxxxxxxxxxxxxxxx
"""


# --- dirty money and the drum -----------------------------------------------
#
# The emerald, but grubby: the same silhouette so it reads as money at a
# glance, greyed and smudged so it reads as the WRONG money at a second one.

DIRTY_PAL = {
    "g": "#3f5a45",     # grubby green
    "G": "#557a5c",     # grubby green, lit
    "k": "#28362c",     # shadow
    "d": "#4a4034",     # grime
    "D": "#5f5240",     # grime, lit
    "x": "#161c18",     # outline
}

DIRTY_EMERALD = """
................
.......xx.......
......xGgx......
.....xGggdx.....
....xGggdDgx....
...xGggdDdggx...
..xGggkddkgggx..
..xGgkdDDdkggx..
..xGgkdDDdkggx..
..xGggkddkgggx..
...xgggdDdggx...
....xggdDgkx....
.....xggdkx.....
......xgkx......
.......xx.......
................
"""

DIRTY_EMERALD_BLOCK = """
dxxxxxdxxxxxxxdd
xGGggxxGgggGGgxd
xGggGgxgGggggGxd
xgGggGxdgGggGgdx
xggGgdxxdggGggxx
xdggdxdxxdgGgdxd
xxdxxxddxxdggxxd
dxxdGGgxdxxdxxdd
dxGGgggGxxdxxddx
xGggggGgGxxdggxx
xgGggGggGgxdGGgx
xggGggGgGgxgGggx
xdgGggGgdgxggGgx
xxdggGgdxdxdggdx
ddxxdgdxxxddxdxx
dxdxxxxxdxxxxxdd
"""

LAUNDRY_PAL = {
    "s": "#8a8d92",     # steel
    "S": "#aeb2b8",     # steel, lit
    "d": "#5c5f64",     # steel, shadow
    "b": "#2e5f8a",     # water
    "B": "#4a8cc4",     # water, lit
    "g": "#3f5a45",     # dirty money in the drum
    "e": "#2fa85a",     # clean emerald
    "E": "#5fd98a",     # clean, lit
    "x": "#1c1e21",     # outline
}

LAUNDRY_EMPTY = """
xxxxxxxxxxxxxxxx
xSssssssssssssSx
xsddddddddddddsx
xsdSSSSSSSSSSdsx
xsdSssssssssSdsx
xsdSsdddddddSdsx
xsdSsdssssssSdsx
xsdSsdssssssSdsx
xsdSsdssssssSdsx
xsdSsdssssssSdsx
xsdSsddddddsSdsx
xsdSSSSSSSSSSdsx
xsddddddddddddsx
xsdSsssssssssdsx
xSssssssssssssSx
xxxxxxxxxxxxxxxx
"""

LAUNDRY_RUNNING = """
xxxxxxxxxxxxxxxx
xSssssssssssssSx
xsddddddddddddsx
xsdSSSSSSSSSSdsx
xsdSbbbbbbbbSdsx
xsdSbBbgbbBbSdsx
xsdSbbbbgbbbSdsx
xsdSbgbbbbgbSdsx
xsdSbbBbbbbbSdsx
xsdSbbbbgbBbSdsx
xsdSbbbbbbbbSdsx
xsdSSSSSSSSSSdsx
xsddddddddddddsx
xsdSsssssssssdsx
xSssssssssssssSx
xxxxxxxxxxxxxxxx
"""

LAUNDRY_DONE = """
xxxxxxxxxxxxxxxx
xSssssssssssssSx
xsddddddddddddsx
xsdSSSSSSSSSSdsx
xsdSssssssssSdsx
xsdSsseeessssdsx
xsdSseEEEesssdsx
xsdSseEEEesssdsx
xsdSsseeesssSdsx
xsdSssesesssSdsx
xsdSssssssssSdsx
xsdSSSSSSSSSSdsx
xsddddddddddddsx
xsdSsssssssssdsx
xSssssssssssssSx
xxxxxxxxxxxxxxxx
"""


# --- the shop till ----------------------------------------------------------
#
# A counter with a brass register standing on it. It has to read as the place
# you PAY from across a room full of shelves, so brass and a green display
# rather than more timber.

TILL_PAL = {
    "w": "#7a5330",     # counter
    "W": "#9a6d41",     # counter, lit
    "d": "#4b3119",     # counter, shadow
    "m": "#b08a2a",     # brass
    "M": "#e0bb56",     # brass, lit
    "g": "#2fa85a",     # display
    "G": "#5fd98a",     # display, lit
    "k": "#1d2b22",     # display, dark
    "x": "#2a1c10",     # outline
}

TILL_FRONT = """
xxxxxxxxxxxxxxxx
xWwwwwwwwwwwwwWx
xwddddddddddddwx
xwdmmmmmmmmmmdwx
xwdmMMMMMMMMmdwx
xwdmMkgggggkMdwx
xwdmMkgGGGgkMdwx
xwdmMkgggggkMdwx
xwdmMMMMMMMMmdwx
xwdmmmmmmmmmmdwx
xwddddddddddddwx
xwWwwwwwwwwwwWwx
xwddddddddddddwx
xwWwwwwwwwwwwWwx
xWwwwwwwwwwwwwWx
xxxxxxxxxxxxxxxx
"""

TILL_SIDE = """
xxxxxxxxxxxxxxxx
xWwwwwwwwwwwwwWx
xwddddddddddddwx
xwdWwwwwwwwwWdwx
xwdWwwwwwwwwWdwx
xwdmmmmmmmmmmdwx
xwdmMMMMMMMMmdwx
xwdmmmmmmmmmmdwx
xwdWwwwwwwwwWdwx
xwdWwwwwwwwwWdwx
xwddddddddddddwx
xwWwwwwwwwwwwWwx
xwddddddddddddwx
xwWwwwwwwwwwwWwx
xWwwwwwwwwwwwwWx
xxxxxxxxxxxxxxxx
"""

TILL_TOP = """
xxxxxxxxxxxxxxxx
xWWWWWWWWWWWWWWx
xWwwwwwwwwwwwwWx
xWwmmmmmmmmmmwWx
xWwmMMMMMMMMmwWx
xWwmMkkkkkkMmwWx
xWwmMkgggggMmwWx
xWwmMkgGGGgMmwWx
xWwmMkkkkkkMmwWx
xWwmMMMMMMMMmwWx
xWwmmmmmmmmmmwWx
xWwwwwwwwwwwwwWx
xWwddddddddddwWx
xWWWWWWWWWWWWWWx
xWwwwwwwwwwwwwWx
xxxxxxxxxxxxxxxx
"""


TILL_KEYS = """
xxxxxxxxxxxxxxxx
xWwwwwwwwwwwwwWx
xwddddddddddddwx
xwdmMmMmMmMmMdwx
xwdmmmmmmmmmmdwx
xwddddddddddddwx
xwdmMmMmMmMmMdwx
xwdmmmmmmmmmmdwx
xwddddddddddddwx
xwdmMmMmMmMmMdwx
xwdmmmmmmmmmmdwx
xwddddddddddddwx
xwdMMmmmmmmMMdwx
xwdmmmmmmmmmmdwx
xWwwwwwwwwwwwwWx
xxxxxxxxxxxxxxxx
"""

TILL_SCREEN = """
xxxxxxxxxxxxxxxx
xmMMMMMMMMMMMMmx
xMkkkkkkkkkkkkMx
xMkggggggggggkMx
xMkgGGGgggGGGgkx
xMkgGgGgggGgGgkx
xMkgGGGgggGGGgkx
xMkgggggggggggkx
xMkgGGGgggGGGgkx
xMkgGgggggGgggkx
xMkgGGGgggGGGgkx
xMkkkkkkkkkkkkMx
xmMMMMMMMMMMMMmx
xwddddddddddddwx
xWwwwwwwwwwwwwWx
xxxxxxxxxxxxxxxx
"""

SHELF_BOARD = """
WWWWWWWWWWWWWWWW
wwwwwwwwwwwwwwww
wdwwwwwdwwwwwdww
wwwwwwwwwwwwwwww
wwwwwwwwwwwwwwww
wwdwwwwwwwdwwwww
wwwwwwwwwwwwwwww
wwwwwwwwwwwwwwww
wwwwwwwwwwwwwwww
wdwwwwwdwwwwwdww
wwwwwwwwwwwwwwww
wwwwwwwwwwwwwwww
wwwwwdwwwwwwwdww
wwwwwwwwwwwwwwww
wwwwwwwwwwwwwwww
dddddddddddddddd
"""

SHELF_STOCK = """
cCcjJjrrppprrjJj
cccjjjrrpppprjjj
cccjjjrrpppprjjj
dddddddddddddddd
pppcCcjJjrrpppcC
pppcccjjjrrpppcc
pppcccjjjrrpppcc
dddddddddddddddd
jJjrrpppcCcjJjrr
jjjrrpppcccjjjrr
jjjrrpppcccjjjrr
dddddddddddddddd
rrpppcCcjJjrrppp
rrpppcccjjjrrppp
rrpppcccjjjrrppp
dddddddddddddddd
"""


# --- the poppy line ---------------------------------------------------------
#
# One palette for the whole chain, because the chain is the point: the same
# milky white shows up as sap on the bench, as the wash in the pot and as the
# powder in the bindle, and the same tar brown shows up as oxidised latex, as
# pressed base and as a ruined batch. You should be able to look at any two
# items in this line and tell they belong to each other.

POPPY_PAL = {
    "s": "#4a5c3a",   # stem, greyer than cannabis
    "l": "#42583a",   # leaf, shadow
    "L": "#6d8a5c",   # leaf, body
    "V": "#9ab882",   # leaf, highlight
    "p": "#7d8f6a",   # pod, shadow
    "P": "#a9bd93",   # pod, body
    "Q": "#cfdcbb",   # pod, highlight
    "c": "#5a6b4a",   # pod crown
    "r": "#8a2f3a",   # petal, shadow
    "R": "#c4485a",   # petal, body
    "W": "#e8788a",   # petal, highlight
    "k": "#1a1410",   # outline
    "m": "#e9e0c4",   # latex, milky
    "M": "#f6f0dc",   # latex, lit
    "n": "#5c4227",   # latex, gone over
    "b": "#3a2818",   # base, dark
    "B": "#6b4a2c",   # base, lit
    "t": "#241a10",   # tar
    "h": "#c9ab84",   # product, shadow
    "H": "#e8d8bc",   # product, body
    "J": "#f7eeda",   # product, highlight
    "g": "#7d7d84",   # steel
    "G": "#b4b4bd",   # steel, lit
    "q": "#7fb0c8",   # sweat
}

POPPY_LEAF = """
.......V........
.......V........
....V..V..V.....
....VL.V.LV.....
...lVL.V.LVl....
...lVLLVLLVl....
..llVLLVLLVll...
...lVLLVLLVl....
....lVLVLVl.....
.....lVLVl......
......lVl.......
.......l........
.......s........
.......s........
.......s........
.......s........
"""

# Stage two of the plant flowers. Three ages of leaves and then a red field is
# most of why this crop is worth looking at, and the flower going over into a
# pod is the tell that it is ready without having to count stages.
POPPY_FLOWER = """
................
................
.....r...r......
....rRRWRRr.....
...rRWWWWWRr....
...rRWWkWWRr....
...rRWWWWWRr....
....rRRWRRr.....
.....rrrrr......
......lVl.......
.......l........
................
................
................
................
................
"""

POPPY_POD_BLOCK = """
................
......ccc.......
.....cQQQc......
....pQPPPQp.....
...pQPPPPPQp....
...pPPPPPPPp....
...pPPPPPPPp....
...pPPPPPPPp....
....pPPPPPp.....
....pPPPPPp.....
.....ppppp......
......ppp.......
................
................
................
................
"""

POPPY_POD = """
................
................
......ccc.......
.....cQQQc......
....pQPPPQp.....
...pQPPPPPQp....
...pPPPPPPPp....
...pPPPPPPPp....
....pPPPPPp.....
.....ppppp......
......ppp.......
.......s........
.......s........
................
................
................
"""

# A lump of latex that has been sat out: milky at the rim, gone brown in the
# middle. The whole item is the reason the next two steps exist.
RAW_OPIUM = """
................
................
.....mmmmm......
....mnnnnnm.....
...mnbbbbbnm....
...mnbBBBbnm....
...mnbBBBbnm....
...mnbbbbbnm....
....mnnnnnm.....
.....mmmmm......
................
................
................
................
................
................
"""

MORPHINE_BASE = """
................
................
...kkkkkkkkkk...
...kBBBBBBBBk...
...kBbbbbbbBk...
...kBbBBBBbBk...
...kBbBttBbBk...
...kBbBttBbBk...
...kBbBBBBbBk...
...kBbbbbbbBk...
...kBBBBBBBBk...
...kkkkkkkkkk...
................
................
................
................
"""

# A folded wax bindle rather than a pile of powder, so it can never be mistaken
# for coca at a glance in a chest -- which matters, because one of them is
# worth three times the other and a customer will only take the one they asked
# for.
HEROIN = """
................
................
.....JJJJJJ.....
....JHHHHHHJ....
...JHHhhhhHHJ...
...JHhhHHhhHJ...
..JHhhHHHHhhHJ..
..JHhhHHHHhhHJ..
...JHhhHHhhHJ...
...JHHhhhhHHJ...
....JHHHHHHJ....
.....gGGGGg.....
................
................
................
................
"""

# One liquid surface for the whole line, recoloured per use and per stage.
# These stretch over model faces of very different sizes, so like every other
# machine material in this file it is a repeating texture rather than a picture
# of a pot with something in it. A and B are placeholders; translate() maps
# them onto real palette keys at the call site.
FLUID = """
AAAABAAAAAAABAAA
AAABAAAAAABAAAAA
BAAAAAAABAAAAAAA
AAAAABAAAAAAAABA
AABAAAAAAAABAAAA
AAAAAAABAAAAAABA
AAAABAAAAABAAAAA
BAAAAAAAAAAABAAA
AAAAAABAAAAAAAAB
AAABAAAAABAAAAAA
AABAAAAAAAAAABAA
AAAAAAABAAAABAAA
BAAAABAAAAAAAAAA
AAAAAAAAABAAAABA
AABAAAAABAAAAAAA
AAAABAAAAAAABAAA
"""

# What the wash looks like as it cooks down: sap, then lime-cloudy, then
# thickening, then base. Read off the block from across the room, which is the
# only reason the pot needs five models instead of one.
WASH_STAGES = [("m", "M"), ("P", "Q"), ("B", "P"), ("b", "B"), ("b", "n")]

# The vessel through the sight glass: clear, milky, blooming, settling, and
# then the good stuff -- and finally tar, which is the batch gone.
ACETYLATOR_STAGES = [("Q", "J"), ("m", "M"), ("H", "J"), ("h", "H"),
                     ("J", "H"), ("t", "b")]

# Heavy lids, warm colour: this is the one high in the mod that is meant to
# read as comfortable rather than as fun.
NOD_ICON = """
................
................
................
..BBBB....BBBB..
..BttB....BttB..
...BB......BB...
................
................
.....B....B.....
......BBBB......
................
.......bb.......
......bbbb......
.......bb.......
................
................
"""

# Sunk eyes, a downturned mouth and a bead of sweat. Deliberately the same face
# as Baked and Tolerance wear, because it is the same person.
WITHDRAWAL_ICON = """
................
................
................
...kkk....kkk...
...ktk....ktk...
...kkk....kkk...
................
..k..........k..
................
.....kkkkkk.....
....k......k....
................
.q...........q..
................
................
................
"""


def filled(ascii_map: str, background: str) -> str:
    """Replace transparent padding with a painted background character.

    For textures that ended up on solid-layer carriers: the solid layer
    ignores alpha, so a transparent pixel renders as black. Props drawn with
    '.' padding for the old cutout carriers get their padding painted the
    colour of whatever they stand against instead.
    """
    return ascii_map.replace(".", background)


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
    write(render(TILL_FRONT, TILL_PAL), "block", "shop_till_front.png")
    write(render(TILL_SIDE, TILL_PAL), "block", "shop_till_side.png")
    write(render(TILL_TOP, TILL_PAL), "block", "shop_till_top.png")
    write(render(TILL_KEYS, TILL_PAL), "block", "shop_till_keys.png")
    write(render(TILL_SCREEN, TILL_PAL), "block", "shop_till_screen.png")
    write(render(DIRTY_EMERALD, DIRTY_PAL), "item", "dirty_emerald.png")
    write(render(DIRTY_EMERALD_BLOCK, DIRTY_PAL), "block", "dirty_emerald_block.png")
    write(render(LAUNDRY_EMPTY, LAUNDRY_PAL), "block", "laundry_empty.png")
    write(render(LAUNDRY_RUNNING, LAUNDRY_PAL), "block", "laundry_running.png")
    write(render(LAUNDRY_DONE, LAUNDRY_PAL), "block", "laundry_done.png")
    write(render(SHELF_FRONT, SHELF_PAL), "block", "market_shelf_front.png")
    write(render(SHELF_SIDE, SHELF_PAL), "block", "market_shelf_side.png")
    write(render(SHELF_TOP, SHELF_PAL), "block", "market_shelf_top.png")
    write(render(SHELF_BOARD, SHELF_PAL), "block", "market_shelf_board.png")
    write(render(SHELF_STOCK, SHELF_PAL), "block", "market_shelf_stock.png")
    write(render(VAULT_FACE, VAULT_PAL), "block", "city_vault_face.png")
    write(render(VAULT_SIDE, VAULT_PAL), "block", "city_vault_side.png")
    write(render(VAULT_TOP, VAULT_PAL), "block", "city_vault_top.png")
    write(render(HOSPITAL_FACE, HOSPITAL_PAL), "block", "hospital_face.png")
    write(render(HOSPITAL_SIDE, HOSPITAL_PAL), "block", "hospital_side.png")
    write(render(HOSPITAL_TOP, HOSPITAL_PAL), "block", "hospital_top.png")
    write(render(FIRE_FACE, FIRE_PAL), "block", "fire_house_face.png")
    write(render(FIRE_SIDE, FIRE_PAL), "block", "fire_house_side.png")
    write(render(FIRE_TOP, FIRE_PAL), "block", "fire_house_top.png")
    write(render(POLICE_FACE, POLICE_PAL), "block", "police_face.png")
    write(render(POLICE_SIDE, POLICE_PAL), "block", "police_side.png")
    write(render(POLICE_TOP, POLICE_PAL), "block", "police_top.png")
    write(render(MAILBOX_BOX, MAILBOX_PAL), "block", "mailbox_box.png")
    write(render(MAILBOX_POST, MAILBOX_PAL), "block", "mailbox_post.png")
    write(render(MAILBOX_FLAG, MAILBOX_PAL), "block", "mailbox_flag.png")
    write(render(TOSS_TOP, TABLE_PAL), "block", "toss_top.png")
    write(render(CARD_TOP, TABLE_PAL), "block", "blackjack_top.png")
    write(render(TABLE_SIDE, TABLE_PAL), "block", "table_side.png")
    write(render(TABLE_SKIRT, TABLE_PAL), "block", "table_skirt.png")
    write(render(TABLE_LEG, TABLE_PAL), "block", "table_leg.png")
    # Solid-carrier blocks slice this anywhere -- the old transparent rows
    # would render black, so they are painted outline-dark instead.
    write(render(filled(TABLE_RIM, "x"), TABLE_PAL), "block", "table_rim.png")

    write(render(BAR_TOP, BAR_PAL), "block", "bar_top.png")
    write(render(BAR_FRONT, BAR_PAL), "block", "bar_front.png")
    write(render(BAR_SHELF, BAR_PAL), "block", "bar_shelf.png")
    write(render(BAR_BRASS, BAR_PAL), "block", "bar_brass.png")
    # The whole casino sits on solid-layer carriers now, so every prop that
    # was drawn on '.' for the old cutout carriers gets its padding painted:
    # wood behind the glassware, outline-dark behind the table furniture.
    write(render(filled(BAR_GLASS, "w"), BAR_PAL), "block", "bar_glass.png")
    for colour, (dark, lit) in BAR_GLASSWARE.items():
        write(render(filled(BAR_BOTTLE, "w"), {**BAR_PAL, "1": dark, "2": lit}),
              "block", f"bar_bottle_{colour}.png")
    write(render(filled(TOSS_COIN, "g"), TABLE_PAL), "block", "toss_coin.png")
    write(render(filled(CARD_SHOE, "x"), TABLE_PAL), "block", "card_shoe.png")
    write(render(filled(CHIP_STACK, "x"), TABLE_PAL), "block", "chip_stack.png")
    write(render(filled(CARD_RACK, "x"), TABLE_PAL), "block", "card_rack.png")
    write(render(CLIMB_FACE, CLIMB_PAL), "block", "climb_face.png")
    write(render(CLIMB_STEP, CLIMB_PAL), "block", "climb_step.png")
    write(render(CLIMB_LAMP, CLIMB_PAL), "block", "climb_lamp.png")
    write(render(CLIMB_PLATE, CLIMB_PAL), "block", "climb_plate.png")
    write(render(CLIMB_LID, CLIMB_PAL), "block", "climb_lid.png")
    write(render(PLINKO_BOARD, PLINKO_PAL), "block", "plinko_board.png")
    write(render(PLINKO_SLOTS, PLINKO_PAL), "block", "plinko_slots.png")
    write(render(PLINKO_FRAME, PLINKO_PAL), "block", "plinko_frame.png")
    write(render(PLINKO_SIDE, PLINKO_PAL), "block", "plinko_side.png")
    write(render(PLINKO_PEG, PLINKO_PAL), "block", "plinko_peg.png")
    # The crown chase: gold sliding along the marquee, four frames, closed
    # loop. The one moving part a cabinet earns while nobody is playing it.
    write_animated([render(frame, PLINKO_PAL) for frame in plinko_marquee_frames()],
                   3, "block", "plinko_marquee.png")
    write(render(ROULETTE_FELT, ROULETTE_PAL), "block", "roulette_felt.png")
    write(render(ROULETTE_RIM, ROULETTE_PAL), "block", "roulette_rim.png")
    # The wheel spins: the same head rotated a quarter turn per frame, so
    # the pockets sweep and the ball orbits. Slow on purpose -- a wheel at
    # strobe speed reads as a broken texture, not a game.
    wheel = render(roulette_wheel_map(), ROULETTE_PAL)
    write_animated([wheel,
                    wheel.transpose(Image.Transpose.ROTATE_270),
                    wheel.transpose(Image.Transpose.ROTATE_180),
                    wheel.transpose(Image.Transpose.ROTATE_90)],
                   8, "block", "roulette_wheel.png")
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
    write(render(CLUB_FRONT, CLUB_PAL), "block", "nightclub.png")
    write(render(STALL_COUNTER, STALL_PAL), "block", "stall_counter.png")
    write(render(STALL_AWNING, STALL_PAL), "block", "stall_awning.png")
    write(render(STALL_GOODS, STALL_PAL), "block", "stall_goods.png")
    write(render(SLOT_BODY, SLOT_PAL), "block", "slot_body.png")
    # The reels spin and the marquee lamps chase. Interpolation smears the
    # four-row reel steps into something like motion blur, which is the
    # closest a 16px texture gets to a spinning reel.
    write_animated([render(frame, SLOT_PAL) for frame in slot_screen_frames()],
                   3, "block", "slot_screen.png")
    write_animated([render(frame, SLOT_PAL) for frame in slot_marquee_frames()],
                   3, "block", "slot_marquee.png")
    write(render(SLOT_TRIM, SLOT_PAL), "block", "slot_trim.png")
    write(render(SLOT_DECK, SLOT_PAL), "block", "slot_deck.png")
    for wand, (lit, body, core) in WAND_GEMS.items():
        write(render(WAND, dict(WAND_PAL, G=lit, B=body, d=core)),
              "item", f"{wand}_wand.png")

    print("cases and keys:")
    for tier, (frame, lit, body, dark) in CASE_KEY_TIERS.items():
        write(render(CASE, {"d": frame, "H": lit, "L": body, "B": dark,
                            # The latch is metal on every tier -- it is the
                            # part that is the same lock four times over.
                            "m": "#c9ccd1", "M": "#7c8189"}),
              "item", f"{tier}_case.png")
        write(render(KEY, {"o": frame, "H": lit, "O": body, "S": body, "s": dark}),
              "item", f"{tier}_key.png")

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

    print("poppy line:")
    write(render(POPPY_LEAF, POPPY_PAL), "block", "poppy_leaf.png")
    write(render(POPPY_FLOWER, POPPY_PAL), "block", "poppy_flower.png")
    write(render(POPPY_POD_BLOCK, POPPY_PAL), "block", "poppy_pod_block.png")
    write(render(POPPY_POD, POPPY_PAL), "item", "poppy_pod.png")
    write(render(RAW_OPIUM, POPPY_PAL), "item", "raw_opium.png")
    write(render(MORPHINE_BASE, POPPY_PAL), "item", "morphine_base.png")
    write(render(HEROIN, POPPY_PAL), "item", "heroin.png")
    # Grey seed off the shared seed sprite, the same way coca's is a green one.
    write(render(SEEDS.translate(str.maketrans({"3": "g"})), neutral2),
          "item", "poppy_seeds.png")

    # Sap on the scoring bench. The pool DEPTH is geometry (see press_model for
    # why), so one texture covers every stage of it.
    write(render(FLUID.translate(str.maketrans({"A": "m", "B": "M"})), POPPY_PAL),
          "block", "scoring_latex.png")

    for stage, (body, fleck) in enumerate(WASH_STAGES):
        write(render(FLUID.translate(str.maketrans({"A": body, "B": fleck})), POPPY_PAL),
              "block", f"wash_liquid_{stage}.png")
    # Nothing in it. Same map, the colour of an empty copper pot's shadow.
    write(render(FLUID.translate(str.maketrans({"A": "t", "B": "b"})), POPPY_PAL),
          "block", "wash_liquid_idle.png")

    for stage, (body, fleck) in enumerate(ACETYLATOR_STAGES):
        if stage == 4:
            # Peak pulses, exactly like the refiner's ember does, and for the
            # same reason: this is the moment worth crossing a room for, and
            # the next one loses the batch outright.
            write_animated([render(FLUID.translate(str.maketrans({"A": c, "B": "H"})),
                                   POPPY_PAL) for c in ("J", "H", "J", "M")],
                           3, "block", f"acetylator_fluid_{stage}.png")
        else:
            write(render(FLUID.translate(str.maketrans({"A": body, "B": fleck})), POPPY_PAL),
                  "block", f"acetylator_fluid_{stage}.png")
    write(render(FLUID.translate(str.maketrans({"A": "t", "B": "b"})), POPPY_PAL),
          "block", "acetylator_fluid_idle.png")

    write(render(NOD_ICON, POPPY_PAL), "mob_effect", "nod.png")
    write(render(WITHDRAWAL_ICON, POPPY_PAL), "mob_effect", "withdrawal.png")

    write(render(BAKED_ICON, palette_for("purp")), "item", "effect_baked.png")
    # Vanilla reads status effect icons from textures/mob_effect/<name>.png.
    write(render(BAKED_ICON, palette_for("purp")), "mob_effect", "baked.png")
    write(render(TOLERANCE_ICON, palette_for("purp")), "mob_effect", "tolerance.png")
    # Wired is the coca line's effect, so it takes the coca palette rather than
    # a strain's. Missing entirely until now -- see the note on WIRED_ICON.
    write(render(WIRED_ICON, palette_for("haze")), "mob_effect", "wired.png")


if __name__ == "__main__":
    main()
