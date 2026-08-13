#!/usr/bin/env python3
"""Validate every generated block/item model.

A model with a typo'd texture key doesn't fail to load -- it renders as the
black-and-magenta checkerboard, and a model with an element outside the legal
range is silently dropped. Both look like "the texture didn't work" from in
game, hours after the change that caused them.

It also checks a Polymer trap: a block whose CARRIER is FULL_BLOCK tells the
client "I am a solid cube", so the client culls the faces of its neighbours.
If you can see straight THROUGH the model you get an X-ray hole -- stand on a
floor above a cave with one of these in it and you see straight down into the
cave. The roulette table shipped like that; so did the shop till, which showed
the landscape through the gap between its counter and its canopy.

    python3 tools/check_models.py
"""

import json
import re
import sys
from pathlib import Path

PROJECT = Path(__file__).resolve().parent.parent
ROOT = PROJECT / "src/main/resources/assets/trapcraft"
SRC = PROJECT / "src/main/java/dev/heezq/trapcraft"
MODELS = ROOT / "models"
TEXTURES = ROOT / "textures"

# Minecraft clamps elements to this range and drops the model otherwise.
MIN_COORD, MAX_COORD = -16.0, 32.0


def sightlines(data: dict) -> dict[str, float]:
    """How much of the block you can see straight through, per axis.

    Not "is this face covered". A recessed cabinet fails that test and is
    perfectly solid -- the recess has a back. What matters for a FULL_BLOCK
    carrier is whether there is a straight LINE through the block, because the
    client has been told this is a solid cube and has already culled the faces
    of the floor and the wall behind it. A line through the block is therefore
    a line into a world with nothing drawn in it.

    Project every element onto each axis-perpendicular plane and union them. A
    pixel column no element covers is a hole.
    """
    leaks = {}
    for axis, name in ((0, "east-west"), (1, "up-down"), (2, "north-south")):
        a, b = [i for i in range(3) if i != axis]
        covered = set()
        for element in data.get("elements", []):
            frm, to = element["from"], element["to"]
            for u in range(max(0, int(frm[a])), min(16, int(to[a] + 0.999))):
                for v in range(max(0, int(frm[b])), min(16, int(to[b] + 0.999))):
                    covered.add((u, v))
        leaks[name] = 1 - len(covered) / 256.0
    return leaks


def solid_models() -> tuple[set[str], list[str]]:
    """Every model served on a FULL_BLOCK carrier, read off the Java.

    A bare name is one model. A name that is concatenated with something is a
    PREFIX, and covers every stage model built from it in a loop -- which is
    the case that used to escape this check entirely, so refiner_idle was
    tested and refiner_0..4 were not.
    """
    wanted = set()
    unresolved = []
    for java in sorted(SRC.glob("*.java")):
        text = java.read_text()
        for match in re.finditer(r"BlockModelType\.(\w+)", text):
            if match.group(1) != "FULL_BLOCK":
                continue
            call = text[match.end():text.find(";", match.end())]
            name = re.search(r'"(?:trapcraft:block/)?([a-z0-9_]+)"(\s*\+)?', call)
            if not name:
                unresolved.append(
                    f"{java.name}: a FULL_BLOCK carrier whose model name this check "
                    f"cannot read -- it is not a string literal, so nothing verifies "
                    f"that the model is a closed cube")
                continue
            if name.group(2):        # "foo_" + step: a family of stage models
                wanted.update(p.stem for p in (MODELS / "block").glob(f"{name.group(1)}*.json"))
            else:
                wanted.add(name.group(1))
    return wanted, unresolved


def xray_holes() -> list[str]:
    """Blocks claiming to be solid cubes that you can see straight through."""
    wanted, problems = solid_models()
    for model in sorted(wanted):
        path = MODELS / "block" / f"{model}.json"
        if not path.is_file():
            continue
        for axis, leak in sightlines(json.loads(path.read_text())).items():
            if leak > 0.001:
                problems.append(
                    f"{model}: FULL_BLOCK carrier, but {leak * 100:.0f}% of the "
                    f"{axis} sightlines go straight through it -- against a wall or "
                    f"a floor, players see the culled world through the gap. Close "
                    f"the shell, or move the block to TrapPolymer.NON_SOLID")
    return problems


def effective_uv(element: dict, side: str, face: dict) -> list[float]:
    """The uv this face will actually be drawn with.

    A face without a uv gets one derived from where the element sits, and that
    derivation is only inside the sprite if the element is inside the cube. A
    bottle standing at y=15.5..23 derives v from -7, and negative v does not
    clamp -- it reads whatever was stitched above that sprite in the atlas.
    """
    if "uv" in face:
        return face["uv"]
    x0, y0, z0 = element["from"]
    x1, y1, z1 = element["to"]
    return {
        "down": [x0, 16 - z1, x1, 16 - z0],
        "up": [x0, z0, x1, z1],
        "north": [16 - x1, 16 - y1, 16 - x0, 16 - y0],
        "south": [x0, 16 - y1, x1, 16 - y0],
        "west": [z0, 16 - y1, z1, 16 - y0],
        "east": [16 - z1, 16 - y1, 16 - z0, 16 - y0],
    }[side]


def texture_exists(ref: str) -> bool:
    """trapcraft:block/foo -> textures/block/foo.png. Vanilla refs are assumed."""
    if not ref.startswith("trapcraft:"):
        return True
    return (TEXTURES / (ref.split(":", 1)[1] + ".png")).is_file()


def effect_icons() -> list[str]:
    """Every registered status effect needs an icon in textures/mob_effect.

    Vanilla draws a missing one as the black-and-purple chequer in the effect
    list and the inventory, and says nothing anywhere -- which from the outside
    is indistinguishable from the mod being broken. Wired shipped without one
    and stayed that way until somebody thought to mention the purple squares.

    Read off the registration call rather than a list, so a fourth effect is
    covered the day it is added instead of the day somebody remembers.
    """
    content = (SRC / "TrapContent.java").read_text()
    problems = []
    for name in re.findall(r'RegistryKeys\.STATUS_EFFECT, TrapCraft\.id\("([a-z_]+)"\)', content):
        if not (TEXTURES / "mob_effect" / f"{name}.png").is_file():
            problems.append(f"status effect '{name}' has no textures/mob_effect/{name}.png "
                            f"-- it will render as missing-texture purple")
    return problems


def main() -> None:
    problems = []
    checked = 0
    elements_seen = 0

    for path in sorted(MODELS.rglob("*.json")):
        checked += 1
        rel = path.relative_to(MODELS.parent.parent.parent.parent.parent)
        model = json.loads(path.read_text())
        textures = model.get("textures", {})

        for key, ref in textures.items():
            if isinstance(ref, str) and not ref.startswith("#") and not texture_exists(ref):
                problems.append(f"{rel}: texture {key!r} -> {ref} has no png")

        for i, element in enumerate(model.get("elements", [])):
            elements_seen += 1
            frm, to = element["from"], element["to"]
            flat = 0
            for axis in range(3):
                if not MIN_COORD <= frm[axis] <= MAX_COORD or not MIN_COORD <= to[axis] <= MAX_COORD:
                    problems.append(f"{rel}: element {i} axis {axis} out of range")
                if frm[axis] > to[axis]:
                    problems.append(
                        f"{rel}: element {i} axis {axis} is inside out "
                        f"({frm[axis]} > {to[axis]})")
                elif frm[axis] == to[axis]:
                    flat += 1
            # One flat axis is a plane, which is legal and is how every foliage
            # model in the game is built. Two is a line and renders nothing.
            if flat > 1:
                problems.append(f"{rel}: element {i} is degenerate on {flat} axes")
            for side, face in element.get("faces", {}).items():
                ref = face.get("texture", "")
                if ref.startswith("#") and ref[1:] not in textures:
                    problems.append(
                        f"{rel}: element {i} face {side} uses {ref}, "
                        f"which isn't in textures {sorted(textures)}")
                uv = effective_uv(element, side, face)
                if min(uv) < 0 or max(uv) > 16:
                    problems.append(
                        f"{rel}: element {i} face {side} reads uv {uv}, which is "
                        f"outside the sprite -- it will sample whatever is next "
                        f"to this texture in the atlas. Name a uv, or move it "
                        f"inside the cube")

        # A model with elements needs a particle texture or breaking it crashes
        # the particle renderer looking for one.
        if model.get("elements") and "particle" not in textures:
            problems.append(f"{rel}: has elements but no particle texture")

    problems.extend(xray_holes())
    problems.extend(effect_icons())

    for problem in problems:
        print(f"  {problem}")
    print(f"{checked} models, {elements_seen} elements, {len(problems)} problems")
    sys.exit(1 if problems else 0)


if __name__ == "__main__":
    main()
