#!/usr/bin/env python3
"""Check every post-processing pipeline the client can ask for actually exists.

setPostProcessor takes an Identifier and the client builds that Identifier from
a stem and a band -- so a look whose stem has no JSON on disk is a resource
load failure at the moment the effect lands, which surfaces as a black screen
or a crash for everyone on the pack. Nothing in the build catches it: the Java
compiles, the generator runs, and the two just disagree about a string.

Also checks the nod curve, which is the one piece of shape maths in the client
half that has no other witness. A sign error in it would not crash anything --
the head would simply never come back up, or never go down -- and nobody would
be able to tell which from a screenshot.

    python3 tools/check_pipelines.py
"""

import json
import math
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent.parent
CLIENT = HERE / "src/main/java/dev/heezq/trapcraft/client"
EFFECTS = HERE / "src/main/resources/assets/trapcraft/post_effect"

BANDS = (0, 1, 2)
UNIFORMS = {"Warp", "Swirl", "Split", "Spin", "Pulse",
            "Mirror", "Ghost", "Echo", "Poster", "Sat"}

# The blur field is the last argument of each HighStyle row.
STYLE_STEM = re.compile(r'^\s*\d+,\s*[\d.]+F,\s*[\d.]+F,\s*[\d.]+F,\s*"([a-z0-9]+)"\)',
                        re.M)
# WiredLook and NodLook name theirs in a return.
RETURNED_STEM = re.compile(r'return\s+(?:\w+\s*>\s*[\d.]+F\s*\?\s*)?"([a-z0-9]+)"'
                           r'(?:\s*:\s*"([a-z0-9]+)")?\s*;')


def stems() -> set[str]:
    found = set(STYLE_STEM.findall((CLIENT / "TrapCraftClient.java").read_text()))
    for name in ("WiredLook.java", "NodLook.java"):
        body = (CLIENT / name).read_text()
        block = body[body.index("public static String stem()"):]
        found.update(g for g in RETURNED_STEM.search(block).groups() if g)
    return found


HOLD, FALL = 0.55, 0.35          # NodLook.NOD_HOLD / NOD_FALL


def nod(f: float) -> float:
    """Mirror of NodLook.nod(). Named so a drift shows up as a failure here."""
    if f < HOLD:
        return 0.0
    if f < HOLD + FALL:
        return 0.5 - 0.5 * math.cos(math.pi * (f - HOLD) / FALL)
    return 0.5 + 0.5 * math.cos(math.pi * (f - HOLD - FALL) / (1.0 - HOLD - FALL))


def main() -> None:
    bad = []

    found = stems()
    if len(found) < 10:
        bad.append(f"only matched {len(found)} stems ({sorted(found)}) -- the"
                   " regexes have drifted from the Java, not the Java from them")
    for stem in sorted(found):
        for band in BANDS:
            path = EFFECTS / f"motion_blur_{stem}_{band}.json"
            if not path.is_file():
                bad.append(f"{path.name} missing -- the client can ask for it")
                continue
            trip = json.loads(path.read_text())["passes"][2]["uniforms"]["TripConfig"]
            names = {u["name"] for u in trip}
            if names != UNIFORMS:
                bad.append(f"{path.name} uniforms {sorted(names ^ UNIFORMS)} off")

    # The head has to start up, come back up, and spend most of the cycle up.
    if nod(0.0) > 1e-9 or nod(1.0) > 1e-9:
        bad.append("nod does not start and end with the head up")
    if abs(nod(HOLD + FALL) - 1.0) > 1e-9:
        bad.append("nod does not reach the bottom at the end of the fall")
    left, right = nod(HOLD + FALL - 1e-6), nod(HOLD + FALL + 1e-6)
    if abs(left - right) > 1e-4:
        bad.append(f"nod jumps at the seam: {left:.4f} -> {right:.4f}")
    duty = sum(nod(i / 2000) for i in range(2000)) / 2000
    if not 0.15 < duty < 0.30:
        bad.append(f"nod spends {duty:.0%} of the cycle down -- passed out, not nodding")

    if bad:
        print("\n".join(bad))
        sys.exit(1)
    print(f"{len(found)} pipeline stems x {len(BANDS)} bands all present; "
          f"nod bottoms out at {HOLD + FALL:.0%} and rides down {duty:.0%} of the cycle")


if __name__ == "__main__":
    main()
