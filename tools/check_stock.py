#!/usr/bin/env python3
"""Check the market catalogue for lines that fail quietly.

Two failures in ShopStock look identical from the outside -- the shelf just
doesn't have what you expected -- and neither logs anything useful:

  * A book line naming an enchantment that doesn't exist, or a level above its
    cap. `build()` counts it as "mod not present" and drops it, so a typo in
    "minecraft:featherfalling" costs you a shelf line and says nothing.
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
