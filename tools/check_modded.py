#!/usr/bin/env python3
"""Check the modded sweeps can't be crafted into money.

ShopStock.stockTheMods puts two and a half thousand modded lines on the
shelves at ONE PRICE PER MOD. That price is only safe because of an arithmetic
accident: the counter buys back at 45%, so anything priced under 2.2x its own
ingredients is a loss to craft and sell, whatever its recipe happens to be.

check_stock.py cannot see any of this. It reads vanilla recipes out of the
Minecraft jar, and these recipes live in mod jars -- which is exactly the
condition under which a hole stays open for months. So this reads the mod jars
too, costs every recipe against the same catalogue, and fails if any mod's
flat price has drifted above the cheapest thing that mod can make.

It will drift. A Macaw's update that adds one cheap recipe moves the floor
under a price nobody touched, and nothing in the game would ever say so.

    python3 tools/check_modded.py

Jars are read from the running server, because that is the only honest source:
the volume runs mods that server.mrpack does not list. No container, no check
-- and that is an error, not a pass, for the same reason a checker that cannot
find the Minecraft jar refuses to shrug and continue.
"""

import json
import re
import subprocess
import sys
import zipfile
from io import BytesIO
from pathlib import Path

from check_stock import SELL_RATE, STOCK, catalogue

CONTAINER = "mcserver-mc-1"
SWEEPS = Path(__file__).resolve().parent.parent / \
    "src/main/java/dev/heezq/trapcraft/ShopStock.java"


def swept() -> dict[str, tuple[int, int]]:
    """namespace -> (bundle, price), read out of the SWEEPS table in Java."""
    source = SWEEPS.read_text()
    table = re.search(r"SWEEPS = Map\.ofEntries\((.*?)\);", source, re.S)
    if not table:
        sys.exit("no SWEEPS table in ShopStock -- this check has rotted")
    found = {ident: (int(count), int(price)) for ident, count, price in re.findall(
        r'Map\.entry\("([a-z_]+)", new Sweep\([A-Z]+, (\d+), (\d+)\)\)', table.group(1))}
    if not found:
        sys.exit("SWEEPS table parsed to nothing -- this check has rotted")
    return found


def pane_rate() -> float:
    """What a pane costs, per item. Panes are the one exception to flat pricing.

    Read rather than assumed, because a check that costs panes at the block
    price is LENIENT about every recipe that eats one -- and lenient is the
    direction that lets a hole through.
    """
    source = SWEEPS.read_text()
    found = re.search(r"PANES = new Sweep\([A-Z]+, (\d+), (\d+)\);", source)
    if not found:
        sys.exit("no PANES rule in ShopStock -- this check has rotted")
    return int(found.group(2)) / int(found.group(1))


def jars() -> dict[str, bytes]:
    """Every mod jar on the server, by filename."""
    listing = subprocess.run(["docker", "exec", CONTAINER, "sh", "-c", "ls /data/mods"],
                             capture_output=True, text=True)
    if listing.returncode != 0:
        sys.exit(f"can't read /data/mods in {CONTAINER} -- is the server up? "
                 f"({listing.stderr.strip()})")
    out = {}
    for name in listing.stdout.split():
        if not name.endswith(".jar"):
            continue
        blob = subprocess.run(["docker", "exec", CONTAINER, "cat", f"/data/mods/{name}"],
                              capture_output=True)
        if blob.returncode == 0:
            out[name] = blob.stdout
    if not out:
        sys.exit(f"no jars found in {CONTAINER}:/data/mods")
    return out


def slots(body: dict) -> list[list[str]] | None:
    """The item ids that could fill each ingredient slot, or None if not a craft."""
    kind = body.get("type", "")
    if kind == "minecraft:crafting_shaped":
        key = body.get("key", {})
        parts = [key[c] for row in body["pattern"] for c in row if c != " " and c in key]
    elif kind == "minecraft:crafting_shapeless":
        parts = list(body.get("ingredients", []))
    elif kind in ("minecraft:stonecutting", "minecraft:smelting", "minecraft:blasting"):
        parts = [body.get("ingredient")]
    else:
        return None
    return [[o for o in (p if isinstance(p, list) else [p]) if isinstance(o, str)]
            for p in parts]


def main() -> int:
    table = swept()
    panes = pane_rate()
    unit = {ident: base / count for ident, count, base in catalogue(STOCK.read_text())}
    # The flat prices themselves, so a recipe of one modded item into another
    # is costed rather than skipped.
    for namespace, (count, price) in table.items():
        unit[f"@{namespace}"] = price / count

    def shelf_price(ident: str) -> float | None:
        """What the shop sells one of these for, or None if it doesn't."""
        namespace = ident.split(":")[0]
        if namespace not in table:
            return None
        if ident.endswith("_pane"):
            return panes
        count, price = table[namespace]
        return price / count

    worst: dict[str, tuple[float, str, float, float]] = {}
    counted = 0
    for name, blob in jars().items():
        with zipfile.ZipFile(BytesIO(blob)) as jar:
            for entry in jar.namelist():
                match = re.fullmatch(r"data/([a-z_]+)/recipe/.+\.json", entry)
                if not match or match.group(1) not in table:
                    continue
                try:
                    body = json.loads(jar.read(entry))
                except (ValueError, KeyError):
                    continue
                ingredients = slots(body)
                if not ingredients or any(not slot for slot in ingredients):
                    continue
                result = body.get("result", {})
                made = str(result.get("id", ""))
                sells_for = shelf_price(made)
                if sells_for is None:
                    continue
                cost = 0.0
                for slot in ingredients:
                    priced = [shelf_price(m) if shelf_price(m) is not None else unit.get(m)
                              for m in slot]
                    priced = [value for value in priced if value is not None]
                    if not priced:
                        cost = None
                        break
                    # The cheapest legal filling, because that is the one an
                    # exploit would actually buy.
                    cost += min(priced)
                if cost is None:
                    continue
                counted += 1
                # What the counter hands back for a craft, against what the
                # craft cost at the same counter.
                earns = sells_for * result.get("count", 1) * SELL_RATE
                namespace = made.split(":")[0]
                margin = earns - cost
                if margin > worst.get(namespace, (float("-inf"),))[0]:
                    worst[namespace] = (margin, entry.rsplit("/", 1)[-1], cost, earns)

    problems = []
    for namespace, (count, price) in sorted(table.items()):
        found = worst.get(namespace)
        if found is None:
            print(f"  note: no costable recipe for {namespace}, price unverified")
            continue
        margin, culprit, cost, earns = found
        if margin > 0:
            problems.append(
                f"{namespace}: crafting {culprit} nets {margin:+.3f}e a go "
                f"({cost:.3f}e of ingredients sells back for {earns:.3f}e)")
        else:
            print(f"  ok   {namespace:16} {price / count:.3f}e each, worst craft "
                  f"{culprit} loses {-margin:.3f}e ({cost:.3f}e -> {earns:.3f}e)")

    print(f"{counted} modded recipes costed across {len(table)} swept mods")
    for problem in problems:
        print(f"  {problem}")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
