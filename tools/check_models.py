#!/usr/bin/env python3
"""Validate every generated block/item model.

A model with a typo'd texture key doesn't fail to load -- it renders as the
black-and-magenta checkerboard, and a model with an element outside the legal
range is silently dropped. Both look like "the texture didn't work" from in
game, hours after the change that caused them.

    python3 tools/check_models.py
"""

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent / "src/main/resources/assets/trapcraft"
MODELS = ROOT / "models"
TEXTURES = ROOT / "textures"

# Minecraft clamps elements to this range and drops the model otherwise.
MIN_COORD, MAX_COORD = -16.0, 32.0


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

    for problem in problems:
        print(f"  {problem}")
    print(f"{checked} models, {elements_seen} elements, {len(problems)} problems")
    sys.exit(1 if problems else 0)


if __name__ == "__main__":
    main()
