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
  * Two catalogue lines for the same item id. DECLARED is a map, so the
    second silently overwrites the first and one of the two prices is dead
    code nobody will ever see quoted.
  * A catalogue line naming a vanilla item that doesn't exist. build() drops
    it exactly the way it drops a mod nobody has installed, and says the same
    nothing -- which is how `minecraft:scute` sat on the shelf list for two
    versions after the game renamed it, and `minecraft:boat` for longer.
  * A shaped recipe whose pattern uses a symbol the key doesn't define. The
    game logs one parse error at startup and the item is simply uncraftable
    forever after. The coin toss shipped that way, using _ for a blank where
    Minecraft wants a space.
  * A registered block or item with no name in the language file, which
    shows up in game as "item.trapcraft.whatever".
  * A gambling machine the casino system has never heard of. TrapHouse.at()
    returns null for anything not in isMachine(), so a new table would take
    bets, pay out of thin air, and never once mention that it isn't wired to
    anybody -- it would simply behave like the old unowned ones forever.
  * A casino screen handler still calling TrapMarket.take/pay directly, which
    is money moving past the vault: the player pays, the house never sees it.
  * A line so cheap the shop refuses to buy it back. sellPrice() returns 0
    below 2e, and the daily index can push a 2e line under that on a bad day.
    That is the "it's not rentable" complaint: you farm a bundle, walk to the
    shop, and it won't take it.
  * A crafting recipe that mints money: the shop sells the ingredients for
    less than it buys the result back for, so a crafting table is a printer.
    Nine wheat into a hay bale and back out again is the shape of it, and the
    reverse recipe means BOTH directions have to be priced or the loop just
    runs the other way round.

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
MC_JARS = (pathlib.Path.home() / ".gradle/caches/fabric-loom/minecraftMaven"
                                 "/net/minecraft/minecraft-merged/*/*.jar")

# The catalogue is written half in literals and half in loops over wood types,
# dyes and stone families, and every check below needs the SAME list: the ids
# the mod will actually declare. These three read the Java, so they are coupled
# to how it is written -- a loop shaped differently to the ones in ShopStock
# reads as no loop at all, and its lines quietly stop being checked.
LOOP = re.compile(r"for \(String (\w+) : new String\[\]\{(.*?)\}\) \{(.*?)\n        \}", re.S)
ADD = re.compile(r"add\([A-Za-z]+, (.+?), (\d+), (\d+)\);")
SHAPES = re.compile(r"shapes\([A-Za-z]+, (.+?), (\d+), (\d+), (true|false)\);")

# TrapMath: the shop won't buy under 2e, and INDEX_MIN * (1 - DRIFT) is the
# cheapest a line can ever get -- a glut day with the drift against it.
SELL_FLOOR = 2
WORST_INDEX = 0.65 * (1 - 0.18)
# TrapMath.SELL_RATE.
SELL_RATE = 0.45


def expand(expression: str, var: str, values: list[str]) -> list[str]:
    """Every id one Java expression can produce, inside the loop it sits in.

    Literals and the loop variable, in any arrangement -- "minecraft:" + wood +
    "_log", state + "copper_bulb", or a plain string. Anything else returns
    nothing rather than a guess, so a line this can't read is a line reported
    as missing by the checks that follow rather than one silently approved.
    """
    parts = [part.strip() for part in expression.split(" + ")]
    if any(not part.startswith('"') and part != var for part in parts):
        return []
    if var not in parts:
        return ["".join(part.strip('"') for part in parts)]
    return ["".join(value if part == var else part.strip('"') for part in parts)
            for value in values]


def catalogue(source: str) -> list[tuple[str, int, int]]:
    """(id, bundle size, price) for every line the market declares.

    Loops expanded, because ShopStock declares whole families that way -- the
    nine woods, the sixteen dyes, the copper matrix -- and a check that reads
    only string literals is blind to most of the catalogue. That blindness was
    load-bearing: the wooden trapdoor sold for two and a half times the planks
    it is made of for as long as nothing here could see it.
    """
    found = []

    def read(text: str, var: str = "", values: list[str] = ()) -> None:
        for expression, count, base in ADD.findall(text):
            for ident in expand(expression, var, values):
                found.append((ident, int(count), int(base)))
        for expression, count, base, walls in SHAPES.findall(text):
            for family in expand(expression, var, values):
                for form in ("stairs", "slab") + (("wall",) if walls == "true" else ()):
                    found.append((f"minecraft:{family}_{form}", int(count), int(base)))

    body = source
    for loop in LOOP.finditer(source):
        # [^"]* rather than +: the copper loop's first state is "", the
        # unweathered one, and a + here reads the gap between the two empty
        # quotes as a value and turns every copper id into nonsense.
        read(loop.group(3), loop.group(1), re.findall(r'"([^"]*)"', loop.group(2)))
        body = body.replace(loop.group(0), "")
    read(body)
    return found


def vanilla_items() -> set[str]:
    """Every item id the game has, one file each in the jar's item models."""
    jars = [j for j in glob.glob(str(MC_JARS)) if "sources" not in j]
    if not jars:
        sys.exit("no mapped Minecraft jar in the Loom cache -- run ./gradlew build first")
    with zipfile.ZipFile(jars[0]) as jar:
        found = {"minecraft:" + m.group(1) for m in
                 (re.fullmatch(r"assets/minecraft/items/(.+)\.json", name)
                  for name in jar.namelist()) if m}
    if not found:
        sys.exit(f"no item models in {jars[0]} -- the path moved, fix this script")
    return found


def vanilla_enchantments() -> dict[str, int]:
    """id -> max_level, read from the vanilla datapack inside the client jar."""
    jars = [j for j in glob.glob(str(MC_JARS)) if "sources" not in j]
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


def vanilla_recipes() -> list[tuple[str, int, list[str]]]:
    """(result id, result count, ingredient ids) for everything vanilla crafts.

    Tags resolve to their members and the caller takes the cheapest, because
    the cheapest ingredient is the one an exploit would actually buy.
    """
    jars = [j for j in glob.glob(str(MC_JARS)) if "sources" not in j]
    if not jars:
        sys.exit("no mapped Minecraft jar in the Loom cache -- run ./gradlew build first")
    out = []
    with zipfile.ZipFile(jars[0]) as jar:
        tags = {}
        for name in jar.namelist():
            match = re.fullmatch(r"data/minecraft/tags/item/(.+)\.json", name)
            if match:
                tags["#minecraft:" + match.group(1)] = [
                    v for v in json.loads(jar.read(name)).get("values", [])
                    if isinstance(v, str)]

        def members(ident: str) -> list[str]:
            # One level deep. A tag of tags resolves to nothing rather than
            # recursing, which costs coverage and never a false alarm.
            return [m for m in tags.get(ident, [ident]) if not m.startswith("#")]

        for name in jar.namelist():
            if not re.fullmatch(r"data/minecraft/recipe/.+\.json", name):
                continue
            body = json.loads(jar.read(name))
            kind = body.get("type", "")
            if kind in ("minecraft:crafting_shaped", "minecraft:crafting_shapeless"):
                if kind.endswith("shaped"):
                    key = body.get("key", {})
                    used = [c for row in body["pattern"] for c in row if c != " "]
                    parts = [key[c] for c in used if c in key]
                else:
                    parts = list(body.get("ingredients", []))
            elif kind in ("minecraft:smelting", "minecraft:smoking",
                          "minecraft:blasting", "minecraft:campfire_cooking"):
                parts = [body["ingredient"]]
            else:
                continue
            # An ingredient is an id, a tag, or a list of either -- "any one
            # of these" -- so every slot ends up as the list of items that
            # could legally fill it.
            slots = []
            for part in parts:
                options = part if isinstance(part, list) else [part]
                slots.append([m for option in options if isinstance(option, str)
                              for m in members(option)])
            if not slots or any(not slot for slot in slots):
                continue
            result = body["result"]
            out.append((result["id"], result.get("count", 1), slots))
    return out


def craft_loops(goods: list[tuple[str, int, int]]) -> list[str]:
    """No recipe may be worth more sold than its ingredients cost to buy.

    The shop is the only place emeralds enter and leave on their own, so any
    recipe where sell(result) beats buy(ingredients) is an infinite money
    printer that needs no farm, no risk and no travel -- somebody stands at a
    crafting table until the index caps out.

    Checked at FLAT prices, deliberately. Drift and order flow can open a
    window either way for a few minutes, and steering that window is the part
    of the market players are meant to play; a permanent hole in the flat
    price list is not.

    Vanilla recipes only -- modded ones live in mod jars this can't see. It
    still catches the whole vanilla food tree, which is where the raw crops
    everybody farms turn into things worth selling.

    Stonecutting is not checked and does not need to be, as long as no shape
    costs more per piece than the block it is cut from -- one block gives at
    most two slabs, and two slabs sold back at 45% can never beat one block
    bought at full price. That is what {@link shapes} is for.
    """
    unit = {}
    for ident, count, base in goods:
        unit[ident] = base / count

    problems = []
    found = []
    for result, count, ingredients in vanilla_recipes():
        if result not in unit:
            continue
        # The cheapest legal way to buy each ingredient slot. A slot nobody
        # sells is a slot the exploit can't be built from, so the whole recipe
        # drops out.
        cost = 0.0
        for slot in ingredients:
            priced = [unit[m] for m in slot if m in unit]
            if not priced:
                cost = None
                break
            cost += min(priced)
        if cost is None:
            continue
        earns = unit[result] * count * SELL_RATE
        # A tolerance, not a fudge. Every price is an integer and most of these
        # recipes are pennies either way, so without a floor the report is
        # forty lines of "0.1e becomes 0.1e" and nobody reads the four that
        # matter. A loop worth under an emerald a craft is slower than mining.
        if earns > cost + 1.0 and earns > cost * 1.15:
            found.append((earns - cost, result, cost, earns))
    for profit, result, cost, earns in sorted(found, reverse=True):
        problems.append(f"crafting {result} mints {profit:.1f}e a go: "
                        f"{cost:.1f}e of ingredients sells back for {earns:.1f}e")
    return problems


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


def duplicate_lines(goods: list[tuple[str, int, int]]) -> list[str]:
    """One id, one price. The map keeps whichever was declared last.

    Counted after the loops are expanded, which is the only way to see the
    collisions that actually happened: thirteen wool lines and five terracotta
    lines sat in the file for months being silently overwritten by the dye
    loop that runs after them, and every one of them looked like a price
    somebody had chosen.
    """
    seen = {}
    problems = []
    for ident, _, _ in goods:
        seen[ident] = seen.get(ident, 0) + 1
    for ident, count in sorted(seen.items()):
        if count > 1:
            problems.append(f"{ident} is listed {count} times -- only the last price counts")
    return problems


def unknown_ids(goods: list[tuple[str, int, int]]) -> list[str]:
    """A minecraft: id the game doesn't have is a line nobody will ever see.

    build() drops it exactly the way it drops a mod that isn't installed, and
    says the same nothing about it -- so `minecraft:scute` kept its price in
    the file for two versions after the game renamed it to turtle_scute.

    Only vanilla ids are checkable. A modded one that's missing is the
    mechanism working, not a typo, and there is no registry here to ask.
    """
    items = vanilla_items()
    return [f"{ident} at {base}e: no such item in vanilla"
            for ident, _, base in sorted(set(goods))
            if ident.startswith("minecraft:") and ident not in items]


def recipes() -> list[str]:
    """Every shaped pattern's symbols must match its key, both ways."""
    folder = ROOT / "src/main/resources/data/trapcraft/recipe"
    problems = []
    for path in sorted(folder.glob("*.json")):
        body = json.loads(path.read_text())
        if body.get("type") != "minecraft:crafting_shaped":
            continue
        used = {c for row in body["pattern"] for c in row if c != " "}
        keys = set(body.get("key", {}))
        for missing in sorted(used - keys):
            problems.append(f"{path.name}: pattern uses '{missing}' with no key for it")
        for spare in sorted(keys - used):
            problems.append(f"{path.name}: key defines '{spare}' the pattern never uses")
    return problems


def names() -> list[str]:
    """Everything registered must have a name in en_us.json.

    A missing entry is not an error anywhere -- the game just renders the
    translation key, so the item is called "item.trapcraft.roulette" in the
    creative tab and nobody notices until a screenshot. Blocks need BOTH keys:
    the block form for the placed block and the item form for the thing in
    your hand.
    """
    content = (ROOT / "src/main/java/dev/heezq/trapcraft/TrapContent.java").read_text()
    lang = json.loads((ROOT / "src/main/resources/assets/trapcraft/lang/en_us.json").read_text())

    # Only whole-literal names. The per-strain registrations build their id by
    # concatenation -- registerItem("seeds_" + strain.id(), ...) -- and their
    # language keys are generated per strain, so matching the prefix would
    # report six phantom gaps and teach everyone to ignore this check.
    items = set(re.findall(r'registerItem\("([a-z0-9_]+)"\s*,', content))
    blocks = set(re.findall(r'registerBlock\("([a-z0-9_]+)"\s*,', content))

    problems = []
    for name in sorted(items):
        if f"item.trapcraft.{name}" not in lang:
            problems.append(f"no name for item.trapcraft.{name}")
    for name in sorted(blocks):
        # Crops are registered as blocks with no item form of their own, so
        # only the block key is required unless an item was registered too.
        if f"block.trapcraft.{name}" not in lang:
            problems.append(f"no name for block.trapcraft.{name}")
    return problems


def casino_floor() -> list[str]:
    """Every gambling machine must be ownable, and pay through the house.

    Two silent failures, both of which look like "it works fine":

      * A machine block missing from TrapHouse.isMachine() can never be wired
        to a casino. Right-clicking it with the card does nothing at all, and
        nothing is logged.
      * A handler still calling TrapMarket.take/pay takes the player's stake
        straight out of the world instead of into the vault. The bet happens,
        the money vanishes, and the owner's books say the machine was never
        played.

    A machine is identified by what it does rather than by a hand-kept list:
    it is a block whose onUse hands a House to a screen handler. That is the
    same fact isMachine() is asserting, so the two cannot drift apart without
    this noticing.
    """
    src = ROOT / "src/main/java/dev/heezq/trapcraft"
    house = (src / "TrapHouse.java").read_text()
    # The METHOD body, not the first mention of the name. isFitting() talks
    # about isMachine in its own javadoc, and reading a fixed window from the
    # first match silently started measuring the wrong text the day that was
    # added -- which is how a checker quietly stops checking.
    at = house.index("boolean isMachine(Block block) {")
    body = house[at:house.index("\n    }", at)]
    known = set(re.findall(r"TrapContent\.([a-zA-Z]+)", body))
    content = (src / "TrapContent.java").read_text()

    problems = []
    for path in sorted(src.glob("*Block.java")):
        text = path.read_text()
        if "ScreenHandler(syncId, inventory, house)" not in text:
            continue
        # A machine is a thing you BET at. The bar takes a house the same way
        # and opens the same shape of screen, but nobody stakes anything on it
        # -- so the discriminator is whether its handler takes money, not
        # whether it knows which casino it belongs to.
        handler = src / (path.stem.replace("Block", "") + "ScreenHandler.java")
        if not handler.exists():
            handler = src / (path.stem.replace("Block", "").replace("Machine", "")
                             + "ScreenHandler.java")
        if handler.exists() and "TrapHouse.stake(" not in handler.read_text():
            continue
        # The block class knows its own registry name from the loot table it
        # drops, which is the one string that is always the registered path.
        found = re.search(r'registerBlock\("([a-z0-9_]+)", ' + path.stem, content)
        if not found:
            problems.append(f"{path.name}: no registerBlock call found for it")
            continue
        field = re.search(r"(\w+) = registerBlock\(\"" + found.group(1) + r"\"", content)
        if not field:
            problems.append(f"{path.name}: can't find its TrapContent field")
        elif field.group(1) not in known:
            problems.append(f"{path.name}: not listed in TrapHouse.isMachine()")

    for path in sorted(src.glob("*ScreenHandler.java")):
        text = path.read_text()
        if "TrapHouse.House house" not in text:
            continue
        for call in ("TrapMarket.take(", "TrapMarket.pay("):
            if call in text:
                problems.append(f"{path.name}: still calls {call} -- "
                                f"that money never reaches the vault")
    return problems


def city_board() -> list[str]:
    """The vault screen's layout, checked before a player checks it for us.

    CityScreenHandler asserts its own layout in a static initialiser, which is
    the right instinct and fires at the worst possible moment: class loading
    happens the first time somebody RIGHT-CLICKS THE VAULT, so the assertion
    takes the server down mid-session rather than failing a build.

    That is exactly what adding three public works did on 2026-08-11 -- four
    icons for seven works, `IllegalStateException: city board: 7 works won't
    fit`, and a crash loop every time anybody touched the vault. Nothing in
    the build or the checkers loads that class, so nothing caught it.

    Adding a Duty or a Work is a one-line change in a different file, which is
    what makes this worth checking from the outside.
    """
    src = ROOT / "src/main/java/dev/heezq/trapcraft"
    board = (src / "CityScreenHandler.java").read_text()
    city = (src / "TrapCity.java").read_text()

    def const(name):
        found = re.search(rf"int {name} = (\d+);", board)
        return int(found.group(1)) if found else None

    def icons(name):
        found = re.search(rf"Item\[\] {name} = \{{(.*?)\}};", board, re.S)
        return len([x for x in found.group(1).split(",") if x.strip()]) if found else None

    def entries(name):
        block = city[city.index(f"public enum {name} {{"):]
        return len(re.findall(r"^\s{8}[A-Z_]+\(", block[:block.index(";")], re.M))

    size, rates, ledger = const("SIZE"), const("RATES_FROM"), const("LEDGER_SLOT")
    works_at, about, takes = const("WORKS_FROM"), const("ABOUT_SLOT"), const("TAKE_FROM")
    if None in (size, rates, ledger, works_at, about, takes):
        return ["city board: the slot constants moved, fix check_stock"]

    duties, works = entries("Duty"), entries("Work")
    problems = []
    if icons("ICONS") != duties:
        problems.append(f"city board: {duties} duties but {icons('ICONS')} icons")
    if rates + duties > ledger:
        problems.append(f"city board: {duties} duties run over the ledger slot")
    if icons("WORK_ICONS") != works:
        problems.append(f"city board: {works} works but {icons('WORK_ICONS')} icons")
    if works_at + works > size:
        problems.append(f"city board: {works} works run off the end of the screen")
    if works_at <= about < works_at + works:
        problems.append(f"city board: {works} works draw over the blurb at {about}")
    if works_at <= takes + 3 and takes < works_at + works:
        problems.append(f"city board: {works} works draw over the withdraw buttons")
    return problems


def half_a_chest() -> list[str]:
    """Position lookups that would see one half of a double chest.

    `world.getBlockEntity(pos) instanceof Inventory` returns a 27-slot
    ChestBlockEntity for a double chest, never the 54-slot pair. Every reader
    in the mod was written that way once, and the symptom was a crew hand
    filling half a chest, calling it full and throwing the harvest on the
    floor -- reported, reasonably, as "the workers are broken" rather than as
    anything to do with chests.

    TrapBoxes.at is the fix and the hopper's own resolver underneath it.

    Two shapes are deliberately NOT flagged, because neither can be wrong:

    Chunk-wide sweeps. Iterating a chunk's block entities visits both halves as
    separate entries, so counting money or finding a stash that way is already
    right.

    Presence tests -- `instanceof Inventory` with nothing bound. "Is there a
    container on this square" has the same answer for half a chest and a whole
    one. Only a BINDING (`instanceof Inventory box`) says the code is about to
    read slots out of it, and that is what has to see all fifty-four. The first
    version of this rule flagged the presence tests too and cried wolf three
    times on its first run.
    """
    binds = re.compile(r"getBlockEntity\(.*instanceof\s+(?:[\w.]+\.)*Inventory\s+\w")
    allowed = {"TrapBoxes.java"}
    problems = []
    src = ROOT / "src/main/java/dev/heezq/trapcraft"
    for java in sorted(src.glob("*.java")):
        if java.name in allowed:
            continue
        for number, line in enumerate(java.read_text().splitlines(), 1):
            if binds.search(line):
                problems.append(
                    f"{java.name}:{number}: binds one half of a double chest -- "
                    f"use TrapBoxes.at(world, pos)")
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

    goods = catalogue(source)
    problems.extend(slot_reel())
    problems.extend(roulette_wheel())
    problems.extend(names())
    problems.extend(duplicate_lines(goods))
    problems.extend(unknown_ids(goods))
    problems.extend(recipes())
    problems.extend(casino_floor())
    problems.extend(craft_loops(goods))
    problems.extend(half_a_chest())
    problems.extend(city_board())

    for ident, _, base in goods:
        if round(base * WORST_INDEX) < SELL_FLOOR:
            problems.append(f"{ident} at {base}e: unsellable once the index dips")

    print(f"{len(goods)} goods, {len(books)} books, "
          f"{len(spells)} vanilla enchantments available")
    for problem in problems:
        print(f"  {problem}")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
