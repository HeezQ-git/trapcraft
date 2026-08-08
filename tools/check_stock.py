#!/usr/bin/env python3
"""Check the market catalogue for lines that fail quietly.

Two failures in ShopStock look identical from the outside -- the shelf just
doesn't have what you expected -- and neither logs anything useful:

  * A book line naming an enchantment that doesn't exist, or a level above its
    cap. `build()` counts it as "mod not present" and drops it, so a typo in
    "minecraft:featherfalling" costs you a shelf line and says nothing.
  * A reel width that disagrees between TrapMath and SlotScreenHandler,
    which throws mid-spin on a live server.
  * A roulette wheel missing a pocket, which lands the ball on one number
    while the table pays out on another.
  * A line so cheap the shop refuses to buy it back. sellPrice() returns 0
    below 2e, and the daily index can push a 2e line under that on a bad day.
    That is the "it's not rentable" complaint: you farm a bundle, walk to the
    shop, and it won't take it.

    python3 tools/check_stock.py
"""

import glob
import json
import pathlib
import re
import sys
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
STOCK = ROOT / "src/main/java/dev/heezq/trapcraft/ShopStock.java"

# TrapMath: the shop won't buy under 2e, and INDEX_MIN * (1 - DRIFT) is the
# cheapest a line can ever get -- a glut day with the drift against it.
SELL_FLOOR = 2
WORST_INDEX = 0.65 * (1 - 0.18)


def vanilla_enchantments() -> dict[str, int]:
    """id -> max_level, read from the vanilla datapack inside the client jar."""
    jars = glob.glob(str(pathlib.Path.home() / ".gradle/caches/fabric-loom/minecraftMaven"
                                               "/net/minecraft/minecraft-merged/*/*.jar"))
    jars = [j for j in jars if "sources" not in j]
    if not jars:
        sys.exit("no mapped Minecraft jar in the Loom cache -- run ./gradlew build first")
    found = {}
    with zipfile.ZipFile(jars[0]) as jar:
        for name in jar.namelist():
            match = re.fullmatch(r"data/minecraft/enchantment/(.+)\.json", name)
            if match:
                found[match.group(1)] = json.loads(jar.read(name)).get("max_level", 1)
    if not found:
        sys.exit(f"no enchantment data in {jars[0]} -- the path moved, fix this script")
    return found


def slot_reel() -> list[str]:
    """The reel width must agree across three declarations in two files.

    TrapMath draws symbol indices with rng.nextInt(SLOT_FACES) and
    SlotScreenHandler looks them up in FACES and FACE_NAMES. If those ever
    disagree the machine throws an ArrayIndexOutOfBounds mid-spin, which on a
    live server means a player watching their stake vanish into a stack trace.
    """
    math = (ROOT / "src/main/java/dev/heezq/trapcraft/TrapMath.java").read_text()
    screen = (ROOT / "src/main/java/dev/heezq/trapcraft/SlotScreenHandler.java").read_text()

    declared = re.search(r"SLOT_FACES\s*=\s*(\d+)", math)
    if not declared:
        return ["SLOT_FACES not found in TrapMath -- this check has rotted"]
    want = int(declared.group(1))

    problems = []
    for name, pattern in (("FACES", r"Item\[\]\s+FACES\s*=\s*\{(.*?)\}"),
                          ("FACE_NAMES", r"String\[\]\s+FACE_NAMES\s*=\s*\{(.*?)\}")):
        found = re.search(pattern, screen, re.S)
        if not found:
            problems.append(f"{name} not found in SlotScreenHandler")
            continue
        size = len([part for part in found.group(1).split(",") if part.strip()])
        if size != want:
            problems.append(f"{name} has {size} entries but SLOT_FACES is {want}")
    return problems


def roulette_wheel() -> list[str]:
    """The ball's path must be a real wheel: every pocket, exactly once.

    RouletteScreenHandler animates the ball by walking WHEEL and lands it with
    indexOnWheel(result). A missing pocket makes indexOnWheel fall back to 0,
    so the ball would settle visibly on one number while the table paid out on
    another -- the exact "shows one thing, pays another" failure the slot
    machine was rebuilt to make impossible.
    """
    source = (ROOT / "src/main/java/dev/heezq/trapcraft/RouletteScreenHandler.java").read_text()
    found = re.search(r"int\[\] WHEEL = \{(.*?)\};", source, re.S)
    if not found:
        return ["WHEEL not found in RouletteScreenHandler -- this check has rotted"]
    pockets = [int(n) for n in re.findall(r"\d+", found.group(1))]

    problems = []
    if sorted(pockets) != list(range(37)):
        missing = sorted(set(range(37)) - set(pockets))
        extra = sorted(p for p in set(pockets) if pockets.count(p) > 1)
        if missing:
            problems.append(f"WHEEL is missing pockets {missing}")
        if extra:
            problems.append(f"WHEEL repeats pockets {extra}")
        if not missing and not extra:
            problems.append(f"WHEEL has {len(pockets)} entries, expected 37")
    return problems


def main() -> int:
    source = STOCK.read_text()
    spells = vanilla_enchantments()
    problems = []

    books = re.findall(r'book\("minecraft:([a-z_]+)", (\d+), (\d+)\);', source)
    for name, level, _ in books:
        if name not in spells:
            problems.append(f"book {name}: no such enchantment")
        elif int(level) > spells[name]:
            problems.append(f"book {name} {level}: max level is {spells[name]}")

    missing = sorted(name for name in spells
                     if not any(b[0] == name and int(b[1]) == spells[name] for b in books))
    if missing:
        print(f"note: no max-level book for {', '.join(missing)}")

    problems.extend(slot_reel())
    problems.extend(roulette_wheel())

    goods = re.findall(r'add\(c, "([^"]+)", (\d+), (\d+)\);', source)
    for ident, _, base in goods:
        if round(int(base) * WORST_INDEX) < SELL_FLOOR:
            problems.append(f"{ident} at {base}e: unsellable once the index dips")

    print(f"{len(goods)} goods, {len(books)} books, "
          f"{len(spells)} vanilla enchantments available")
    for problem in problems:
        print(f"  {problem}")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
