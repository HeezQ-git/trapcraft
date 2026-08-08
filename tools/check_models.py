#!/usr/bin/env python3
"""Validate every generated block/item model.

A model with a typo'd texture key doesn't fail to load -- it renders as the
black-and-magenta checkerboard, and a model with an element outside the legal
range is silently dropped. Both look like "the texture didn't work" from in
game, hours after the change that caused them.

It also checks a Polymer trap: a block whose CARRIER is FULL_BLOCK tells the
client "I am a solid cube", so the client culls the faces of its neighbours.
If the model doesn't actually fill the cube you get an X-ray hole -- stand on
a floor above a cave with one of these in it and you see straight down into
the cave. The roulette table shipped like that.

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


def face_coverage(data: dict) -> dict[str, float]:
    """How much of each cube face the model's elements actually cover."""
    faces = {}
    for name, axis, at in (("down", 1, 0), ("up", 1, 16), ("north", 2, 0),
                           ("south", 2, 16), ("west", 0, 0), ("east", 0, 16)):
        covered = set()
        for element in data.get("elements", []):
            frm, to = element["from"], element["to"]
            if not frm[axis] <= at <= to[axis]:
                continue
            a, b = [i for i in range(3) if i != axis]
            for u in range(int(frm[a]), min(16, int(to[a]))):
                for v in range(int(frm[b]), min(16, int(to[b]))):
                    covered.add((u, v))
        faces[name] = len(covered) / 256.0
    return faces


def xray_holes() -> list[str]:
    """Blocks claiming to be solid cubes whose models leave the sky showing.

    Fails only on the up and down faces. Those are the ones you notice, because
    a floor with a hole in it shows the caves underneath. Open SIDES are
    reported as a note and tolerated on purpose: the three machines that have
    them would need thirty-seven carrier states between them to fix, which is
    the entire remaining TRANSPARENT_BLOCK pool on this pack.
    """
    declared = re.compile(
        r"BlockModelType\.(\w+),\s*\n\s*PolymerBlockModel\.of\(Identifier\.of\("
        r"\"trapcraft:block/([a-z0-9_]+)\"\)\)")
    problems = []
    notes = []
    for java in sorted(SRC.glob("*.java")):
        for carrier, model in declared.findall(java.read_text()):
            if carrier != "FULL_BLOCK":
                continue
            path = MODELS / "block" / f"{model}.json"
            if not path.is_file():
                continue
            faces = face_coverage(json.loads(path.read_text()))
            for side in ("down", "up"):
                if faces[side] < 0.99:
                    problems.append(
                        f"{model}: FULL_BLOCK carrier but the {side} face is only "
                        f"{faces[side] * 100:.0f}% covered -- players will see through it")
            open_sides = [f"{k} {v * 100:.0f}%"
                          for k, v in faces.items() if k not in ("down", "up") and v < 0.99]
            if open_sides:
                notes.append(f"  note: {model} has open sides ({', '.join(open_sides)})")
    for line in notes:
        print(line)
    return problems


def texture_exists(ref: str) -> bool:
    """trapcraft:block/foo -> textures/block/foo.png. Vanilla refs are assumed."""
    if not ref.startswith("trapcraft:"):
        return True
    return (TEXTURES / (ref.split(":", 1)[1] + ".png")).is_file()


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

        # A model with elements needs a particle texture or breaking it crashes
        # the particle renderer looking for one.
        if model.get("elements") and "particle" not in textures:
            problems.append(f"{rel}: has elements but no particle texture")

    problems.extend(xray_holes())

    for problem in problems:
        print(f"  {problem}")
    print(f"{checked} models, {elements_seen} elements, {len(problems)} problems")
    sys.exit(1 if problems else 0)


if __name__ == "__main__":
    main()
