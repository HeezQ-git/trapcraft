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

import glob
import json
import re
import subprocess
import sys
import zipfile
from io import BytesIO
from pathlib import Path

from check_stock import MC_JARS, SELL_FLOOR, SELL_RATE, STOCK, catalogue

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


def cut_goods() -> dict[str, tuple[int, int]]:
    """suffix -> (bundle, price) for the exceptions to flat pricing.

    A pane comes out of a recipe in greater number than the block went in, so
    it cannot carry its mod's flat price. Read rather than assumed, because a
    check that costs panes at the block price is LENIENT about every recipe
    that eats one -- and lenient is the direction that lets a hole through.
    """
    source = SWEEPS.read_text()
    match = re.search(r"PANES = new Sweep\([A-Z]+, (\d+), (\d+)\);", source)
    if not match:
        sys.exit("no PANES rule in ShopStock -- this check has rotted")
    return {"_pane": (int(match.group(1)), int(match.group(2)))}


def timber() -> list[tuple[str, int, int]]:
    """(item tag, bundle, price) for the wood sweep, in first-match order."""
    found = [(f"#minecraft:{tag.lower()}", int(count), int(price)) for tag, count, price
             in re.findall(r"TIMBER\.put\(ItemTags\.([A-Z_]+), new int\[\]\{(\d+), (\d+)\}\);",
                           SWEEPS.read_text())]
    if not found:
        sys.exit("no TIMBER table in ShopStock -- this check has rotted")
    return found


def item_tags(blobs: dict[str, bytes]) -> dict[str, set[str]]:
    """Every item tag in the pack, nesting resolved, vanilla and modded merged.

    Both halves are needed and neither is enough: a wood mod usually declares
    its own #mod:aspen_logs and hangs it off vanilla's #minecraft:logs, so the
    tag that decides the price only means anything once the mod's own tag files
    are read alongside the game's.
    """
    raw: dict[str, list[str]] = {}
    sources = [p for p in glob.glob(str(MC_JARS)) if "sources" not in p]
    if not sources:
        sys.exit("no mapped Minecraft jar in the Loom cache -- run ./gradlew build first")
    readers = [zipfile.ZipFile(sources[0])] + \
        [zipfile.ZipFile(BytesIO(blob)) for blob in blobs.values()]
    for jar in readers:
        for name in jar.namelist():
            match = re.fullmatch(r"data/([a-z0-9_.-]+)/tags/item/(.+)\.json", name)
            if not match:
                continue
            try:
                body = json.loads(jar.read(name))
            except ValueError:
                continue
            values = [v if isinstance(v, str) else v.get("id", "")
                      for v in body.get("values", [])]
            raw.setdefault(f"#{match.group(1)}:{match.group(2)}", []).extend(
                v for v in values if v)

    def members(tag: str, seen: set[str]) -> set[str]:
        if tag in seen:
            return set()
        seen.add(tag)
        out = set()
        for value in raw.get(tag, []):
            out |= members(value, seen) if value.startswith("#") else {value}
        return out

    return {tag: members(tag, set()) for tag in raw}


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
    cut = cut_goods()
    blobs = jars()
    wood = timber()
    tags = item_tags(blobs)
    unit = {ident: base / count for ident, count, base in catalogue(STOCK.read_text())}
    # The flat prices themselves, so a recipe of one modded item into another
    # is costed rather than skipped.
    for namespace, (count, price) in table.items():
        unit[f"@{namespace}"] = price / count

    def line(ident: str) -> tuple[str, int, int] | None:
        """(shelf, bundle, bundle price) for a swept item, or None if unswept.

        Same order the mod stocks in: a mod with its own shelf keeps it, and
        only what is left falls through to the wood tags.
        """
        namespace = ident.split(":")[0]
        if namespace in table:
            for suffix, (count, price) in cut.items():
                if ident.endswith(suffix):
                    return namespace, count, price
            return (namespace, *table[namespace])
        if ident in unit:
            return None
        for tag, count, price in wood:
            if ident in tags.get(tag, ()):
                return "timber", count, price
        return None

    def shelf_price(ident: str) -> float | None:
        """What the shop sells one of these for, or None if it doesn't."""
        found = line(ident)
        return None if found is None else found[2] / found[1]

    def sell_back(ident: str) -> float:
        """What the counter pays for one. Nothing, under the two-emerald floor.

        TrapMarket.sellPrice refuses to buy back a line whose flat price is
        under SELL_FLOOR, which is the whole reason the furniture can be sold
        by the piece at 1e -- a check that costs those crafts at 45% would fail
        the deploy over a loop the game will not let anybody run.
        """
        found = line(ident)
        if found is None or found[2] < SELL_FLOOR:
            return 0.0
        return shelf_price(ident) * SELL_RATE

    worst: dict[str, tuple[float, str, float, float]] = {}
    counted = 0
    for name, blob in blobs.items():
        with zipfile.ZipFile(BytesIO(blob)) as jar:
            for entry in jar.namelist():
                if not re.fullmatch(r"data/[a-z0-9_.-]+/recipe/.+\.json", entry):
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
                shelf = line(made)
                if shelf is None:
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
                earns = sell_back(made) * result.get("count", 1)
                margin = earns - cost
                if margin > worst.get(shelf[0], (float("-inf"),))[0]:
                    worst[shelf[0]] = (margin, entry.rsplit("/", 1)[-1], cost, earns)

    shelves = dict(sorted(table.items()))
    shelves["timber"] = (wood[0][1], wood[0][2])
    problems = []
    for shelf, (count, price) in shelves.items():
        found = worst.get(shelf)
        if found is None:
            print(f"  note: no costable recipe for {shelf}, price unverified")
            continue
        margin, culprit, cost, earns = found
        if margin > 0:
            problems.append(
                f"{shelf}: crafting {culprit} nets {margin:+.3f}e a go "
                f"({cost:.3f}e of ingredients sells back for {earns:.3f}e)")
        elif price < SELL_FLOOR:
            print(f"  ok   {shelf:16} {price / count:.3f}e each, never bought back")
        else:
            print(f"  ok   {shelf:16} {price / count:.3f}e each, worst craft "
                  f"{culprit} loses {-margin:.3f}e ({cost:.3f}e -> {earns:.3f}e)")

    print(f"{counted} modded recipes costed across {len(shelves)} swept shelves")
    for problem in problems:
        print(f"  {problem}")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
