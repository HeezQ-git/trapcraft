#!/usr/bin/env python3
"""Build the public wiki at site/index.html from the mod's own source.

Every figure on the page is READ OUT OF THE JAVA, for the same reason the
guide books are: a wiki with hand-typed numbers is a wiki that starts lying
the first time somebody retunes anything, and this mod retunes things weekly.
Coca's price moved by a factor of two last week and the pace ladder by a third;
a hand-written page would already be wrong about both.

Prose is hand-written, because prose does not rot. Numbers, tables, strain
profiles, crew costs, heat tiers and the advancement list are all parsed.

    python3 tools/gen_wiki.py

Output is one self-contained file apart from the webfonts. Open it directly or
push it and let the Pages workflow in .github/workflows/ serve it.
"""

import base64
import glob
import html
import io
import json
import pathlib
import re
import sys

from PIL import Image

import check_stock

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "src/main/java/dev/heezq/trapcraft"
ADVANCEMENTS = ROOT / "src/main/resources/data/trapcraft/advancement"
OUT = ROOT / "site/index.html"


def java(name: str) -> str:
    return (SRC / f"{name}.java").read_text()


def need(pattern: str, text: str, what: str, group: int = 1) -> str:
    """Pull one value out, or fail loudly. A silently missing number is worse
    than a broken build -- it renders as an empty cell nobody notices."""
    found = re.search(pattern, text)
    if not found:
        sys.exit(f"gen_wiki: couldn't find {what} -- the source moved, fix this script")
    return found.group(group)


def ints(name: str, text: str) -> list[int]:
    raw = need(name + r"\s*=\s*\{([^}]*)\}", text, name)
    return [int(n) for n in re.findall(r"-?\d+", raw)]


def floats(name: str, text: str) -> list[float]:
    raw = need(name + r"\s*=\s*\{([^}]*)\}", text, name)
    return [float(n) for n in re.findall(r"-?[\d.]+", raw)]


# --- icons ------------------------------------------------------------------
#
# Vanilla textures come out of the mapped client jar in the Loom cache; ours
# come out of our own resources. Both are inlined as data URIs so the page
# stays one file, which at 16x16 costs about twenty kilobytes for the lot.
#
# Vanilla assets are Mojang's. This is the same thing every Minecraft wiki in
# existence does and it is fan content, not a redistribution of the game -- but
# it is worth knowing that is what the base64 is, rather than discovering it.

TAG_STAND_IN = {
    "#minecraft:logs": "minecraft:oak_log",
    "#minecraft:planks": "minecraft:oak_planks",
    "#minecraft:wool": "minecraft:white_wool",
}

_ICONS: dict[str, str] = {}
_JAR = None


def jar():
    global _JAR
    if _JAR is None:
        import zipfile
        found = [j for j in glob.glob(str(
            pathlib.Path.home() / ".gradle/caches/fabric-loom/minecraftMaven"
                                  "/net/minecraft/minecraft-merged/*/*.jar"))
            if "sources" not in j and "backup" not in j]
        if not found:
            sys.exit("gen_wiki: no Minecraft jar in the Loom cache -- "
                     "run ./gradlew build first so the icons can be read")
        _JAR = zipfile.ZipFile(found[0])
    return _JAR


def from_model(name: str):
    """Our blocks have no single icon PNG -- they are built from face textures.

    Follow the model chain the way the game does (item model -> parent block
    model) and take the PARTICLE texture, which is exactly the representative
    face Minecraft itself picks when it needs one image for a block. That is
    how the ledger, the drying rack and the mixing station get an icon without
    anybody hand-listing which face to use.
    """
    models = ROOT / "src/main/resources/assets/trapcraft/models"
    seen = set()
    ref = f"trapcraft:item/{name}"
    for _ in range(5):
        kind, _, leaf = ref.partition(":")[2].partition("/")
        path = models / kind / f"{leaf}.json"
        if not path.is_file() or str(path) in seen:
            return None
        seen.add(str(path))
        body = json.loads(path.read_text())
        textures = body.get("textures", {})
        pick = textures.get("particle") or textures.get("layer0")
        if pick and pick.startswith("trapcraft:"):
            art = ROOT / ("src/main/resources/assets/trapcraft/textures/"
                          + pick.split(":", 1)[1] + ".png")
            if art.is_file():
                return art.read_bytes()
        if "parent" not in body:
            return None
        ref = body["parent"]
    return None


def icon(ident: str) -> str:
    """A 16x16 data URI for an item id, or '' if nothing has a texture for it."""
    ident = TAG_STAND_IN.get(ident, ident)
    if ident in _ICONS:
        return _ICONS[ident]
    namespace, _, name = ident.partition(":")
    raw = None
    if namespace == "trapcraft":
        for kind in ("item", "block"):
            path = (ROOT / f"src/main/resources/assets/trapcraft/textures/{kind}/{name}.png")
            if path.is_file():
                raw = path.read_bytes()
                break
        if raw is None:
            raw = from_model(name)
    else:
        # The compass and the clock have no plain texture -- they ship as
        # thirty-two directional frames, so take the first one.
        for candidate in (f"item/{name}", f"block/{name}",
                          f"item/{name}_00", f"block/{name}_00"):
            try:
                raw = jar().read(f"assets/minecraft/textures/{candidate}.png")
                break
            except KeyError:
                continue
    if raw is None:
        _ICONS[ident] = ""
        return ""
    # Animated textures ship as a vertical strip. Take the first frame, or the
    # icon renders as a squashed column of every frame at once.
    image = Image.open(io.BytesIO(raw)).convert("RGBA")
    if image.height > image.width:
        image = image.crop((0, 0, image.width, image.width))
    buffer = io.BytesIO()
    image.save(buffer, format="PNG", optimize=True)
    _ICONS[ident] = "data:image/png;base64," + base64.b64encode(buffer.getvalue()).decode()
    return _ICONS[ident]


def pretty(ident: str) -> str:
    """minecraft:oak_log -> Oak Log. #minecraft:logs -> Any Log."""
    if ident.startswith("#"):
        return "Any " + ident.split(":")[-1].rstrip("s").replace("_", " ").title()
    return ident.split(":")[-1].replace("_", " ").title()


# --- what the source says ---------------------------------------------------

def strains() -> list[dict]:
    """Each entry's effects come from ITS OWN chunk of the enum body.

    A fixed-size window after the match ran past the end of one declaration and
    into the next, so Kush was rendered with Haze's speed and jump boost. The
    chunk boundaries are the declarations themselves.
    """
    text = java("Strain")
    found = list(re.finditer(
        r'(\w+)\("(\w+)", "(\w+)", 0x([0-9A-Fa-f]{6}), (\d+), (\d+),\s*\n\s*"([^"]*)"',
        text))
    out = []
    for i, m in enumerate(found):
        stop = found[i + 1].start() if i + 1 < len(found) else len(text)
        effects = re.findall(r"StatusEffects\.([A-Z_]+), (\d+) \* 20, (\d+)",
                             text[m.end():stop])
        out.append({
            "id": m.group(2), "name": m.group(3), "colour": "#" + m.group(4),
            "seconds": int(m.group(5)), "intensity": int(m.group(6)),
            "blurb": m.group(7),
            "hybrid": m.group(1) in ("DIESEL", "MIDNIGHT", "SUNSET"),
            "effects": [(e[0].replace("_", " ").title(), int(e[1]),
                         int(e[2]) + 1) for e in effects],
        })
    return out


def graded(name: str) -> list[dict]:
    text = java(name)
    return [{"name": m.group(2), "potency": float(m.group(3)), "emeralds": int(m.group(4))}
            for m in re.finditer(r'(\w+)\("(\w+)", Formatting\.\w+, ([\d.]+)F, (\d+)\)', text)]


def crew_jobs() -> list[dict]:
    """Job("Picking", "minecraft:wheat", cost, wage, "what they do", "what it wants").

    Counted as well as parsed. Adding a sixth field to the enum silently took
    this to zero matches and the page shipped with an empty jobs table -- the
    duties and the works already had a guard for exactly that and this did not.
    """
    text = java("TrapCrew")
    body = text[text.index("public enum Job {"):]
    body = body[:body.index(";")]
    jobs = [{"name": m.group(1), "cost": int(m.group(2)), "wage": int(m.group(3)),
             "blurb": m.group(4), "needs": m.group(5)}
            for m in re.finditer(
                r'\w+\("([^"]+)", "[^"]+", (\d+), (\d+),\s*"([^"]+)",\s*"([^"]+)"\)',
                body, re.S)]
    declared = len(re.findall(r"^\s{8}[A-Z_]+\(", body, re.M))
    if declared != len(jobs):
        raise SystemExit(f"  parsed {len(jobs)} crew jobs but {declared} are declared "
                         f"-- the jobs table would be wrong")
    return jobs


def named_blends() -> list[dict]:
    """The combinations that do something the arithmetic wouldn't give you."""
    text = java("Blend")
    out = []
    for m in re.finditer(
            r'new Recipe\(List\.of\(([^)]+)\),\s*\n\s*"(\w+)", 0x([0-9A-Fa-f]{6}), ([\d.]+)F',
            text):
        parts = [p.split(".")[-1].strip().title() for p in m.group(1).split(",")]
        # Same fixed-window flaw as the strains had: bound it to this recipe.
        stop = text.find("new Recipe(", m.end())
        bonus = re.findall(r"StatusEffects\.([A-Z_]+)",
                           text[m.end():stop if stop > 0 else len(text)])
        out.append({"parts": parts, "name": m.group(2), "colour": "#" + m.group(3),
                    "potency": float(m.group(4)),
                    "bonus": [b.replace("_", " ").title() for b in bonus[:2]]})
    return out


def recipes() -> dict[str, dict]:
    """Shaped recipes as a grid the page can draw, keyed by result path."""
    out = {}
    folder = ROOT / "src/main/resources/data/trapcraft/recipe"
    for path in sorted(folder.glob("*.json")):
        body = json.loads(path.read_text())
        if body.get("type") != "minecraft:crafting_shaped":
            continue
        key = body.get("key", {})
        grid = []
        for row in body["pattern"]:
            cells = []
            for ch in row.ljust(3):
                cells.append(key.get(ch))
            grid.append(cells)
        while len(grid) < 3:
            grid.append([None, None, None])
        out[body["result"]["id"].split(":")[-1]] = {
            "grid": grid, "count": body["result"].get("count", 1),
            "result": body["result"]["id"]}
    return out


def advancements() -> list[dict]:
    out = []
    for path in sorted(ADVANCEMENTS.glob("*.json")):
        body = json.loads(path.read_text())
        display = body.get("display", {})
        out.append({
            "title": display.get("title", path.stem),
            "description": display.get("description", ""),
            "frame": display.get("frame", "task"),
        })
    return out


DATA = {}


def gather() -> None:
    math = java("TrapMath")
    crew = java("TrapCrew")
    heat = java("TrapHeat")
    rack = java("DryingRackBlock")
    homes = java("HomeSurvey")
    city = java("TrapCity")
    press = java("LeafPressBlock")

    DATA["strains"] = strains()
    DATA["quality"] = graded("Quality")
    DATA["purity"] = graded("Purity")
    DATA["jobs"] = crew_jobs()
    DATA["awards"] = advancements()
    DATA["blends"] = named_blends()
    DATA["recipes"] = recipes()

    DATA["sell_rate"] = float(need(r"SELL_RATE = ([\d.]+)f", math, "SELL_RATE"))
    DATA["stall_rate"] = float(need(r"STALL_RATE = ([\d.]+)f", math, "STALL_RATE"))
    DATA["stall_fee"] = float(need(r"STALL_FEE = ([\d.]+)f", math, "STALL_FEE"))
    DATA["payout_ceiling"] = int(need(r"PAYOUT_CEILING = (\d+)", math, "PAYOUT_CEILING"))
    DATA["rep_max"] = int(need(r"REP_MAX = (\d+)", math, "REP_MAX"))
    DATA["index_min"] = float(need(r"INDEX_MIN = ([\d.]+)f", math, "INDEX_MIN"))
    DATA["index_max"] = float(need(r"INDEX_MAX = ([\d.]+)f", math, "INDEX_MAX"))
    DATA["drift"] = float(need(r"DRIFT = ([\d.]+)f", math, "DRIFT"))
    DATA["coca_rolls"] = int(need(r"COCA_GROWTH_ROLLS = (\d+)", math, "COCA_GROWTH_ROLLS"))
    DATA["weed_wet"] = int(need(r"WEED_GROWTH_ROLLS_WET = (\d+)", math, "weed wet"))
    DATA["weed_dry"] = int(need(r"WEED_GROWTH_ROLLS_DRY = (\d+)", math, "weed dry"))
    DATA["mixed_trade"] = float(need(r"MIXED_TRADE = ([\d.]+)f", heat, "MIXED_TRADE"))

    DATA["slot_rtp"] = floats("SLOT_MEASURED_RTP", math)
    DATA["slot_sizes"] = ints("SLOT_SIZES", math)
    DATA["climb_return"] = float(need(r"CLIMB_RETURN = ([\d.]+)f", math, "CLIMB_RETURN"))
    DATA["roulette_pockets"] = int(need(r"ROULETTE_POCKETS = (\d+)", math, "roulette"))
    DATA["pull_at"] = int(need(r"PULL_AT = (\d+)", math, "PULL_AT"))
    DATA["pull_floor"] = float(need(r"PULL_FLOOR = ([\d.]+)f", math, "PULL_FLOOR"))
    DATA["jam_from"] = int(need(r"JAM_FROM = (\d+)", math, "JAM_FROM"))
    DATA["wear_broken"] = int(need(r"WEAR_BROKEN = (\d+)", math, "WEAR_BROKEN"))
    DATA["wear_per_rounds"] = int(need(r"WEAR_PER_ROUNDS = (\d+)", math, "WEAR_PER_ROUNDS"))

    DATA["pace_ticks"] = ints("PACE_TICKS", crew)
    DATA["pace_cost"] = ints("PACE_COST", crew)
    DATA["pace_wage"] = ints("PACE_WAGE", crew)
    DATA["pace_name"] = re.findall(r'"([^"]+)"', need(
        r'PACE_NAME = \{([^}]*)\}', crew, "PACE_NAME"))
    DATA["reach"] = ints("REACH_BLOCKS", crew)
    DATA["reach_cost"] = ints("REACH_COST", crew)
    DATA["hire"] = int(need(r"HIRE_COST = (\d+)", crew, "HIRE_COST"))
    DATA["night_rate"] = float(need(r"NIGHT_RATE = ([\d.]+)f", crew, "NIGHT_RATE"))
    DATA["jobs_per_shift"] = int(need(r"JOBS_PER_SHIFT = (\d+)", crew, "JOBS_PER_SHIFT"))
    DATA["grace"] = int(need(r"GRACE_PACKETS = (\d+)", crew, "GRACE_PACKETS"))
    DATA["break_share"] = float(need(r"CREW_BREAK_SHARE = ([\d.]+)f", math, "CREW_BREAK_SHARE"))

    DATA["min_floor"] = int(need(r"MIN_FLOOR = (\d+)", homes, "MIN_FLOOR"))
    DATA["floor_steps"] = ints("FLOOR_STEPS", homes)
    DATA["decor_steps"] = ints("DECOR_STEPS", homes)
    DATA["shell_steps"] = floats("SHELL_STEPS", homes)
    DATA["dark_at"] = int(need(r"DARK_AT = (\d+)", homes, "DARK_AT"))
    DATA["fittings"] = int(need(r"FITTINGS = (\d+)", homes, "FITTINGS"))
    DATA["top_tier"] = int(need(r"TOP_TIER = (\d+)", homes, "TOP_TIER"))
    DATA["span"] = int(need(r"SPAN = (\d+)", homes, "SPAN"))
    # DOTALL, because a blurb long enough to wrap is a blurb this would
    # otherwise drop in silence -- INCOME did exactly that on the first run,
    # and a tax table missing a tax is the worst kind of generated page.
    body = city[city.index("public enum Duty {"):]
    body = body[:body.index(";")]
    DATA["duties"] = [
        {"name": m[1], "blurb": m[2], "start": int(m[3]),
         "floor": int(m[4]), "ceiling": int(m[5])}
        for m in re.findall(
            r'(\w+)\(\s*"([^"]+)",\s*"([^"]+)",\s*(\d+),\s*(\d+),\s*(\d+)\)',
            body, re.S)]
    declared = len(re.findall(r"^\s{8}[A-Z_]+\(", body, re.M))
    if declared != len(DATA["duties"]):
        raise SystemExit(f"  parsed {len(DATA['duties'])} duties but "
                         f"{declared} are declared -- the tax table would be wrong")
    DATA["broke"] = int(need(r"BROKE = (\d+)", city, "BROKE"))
    DATA["flush"] = int(need(r"FLUSH = (\d+)", city, "FLUSH"))
    DATA["budget_days"] = int(need(r"BUDGET_DAYS = (\d+)", city, "BUDGET_DAYS"))
    law = java("TrapLaw")
    DATA["looks_away"] = int(need(r"LOOKS_AWAY = (\d+)", law, "LOOKS_AWAY"))
    DATA["assessment"] = float(need(r"ASSESSMENT = ([\d.]+)f", law, "ASSESSMENT"))
    DATA["wash_cut"] = float(need(r"WASH_CUT = ([\d.]+)f", law, "WASH_CUT"))
    drum = java("LaundryBlock")
    DATA["wash_min"] = int(need(r"MIN_LOAD = (\d+)", drum, "MIN_LOAD"))
    DATA["wash_max"] = int(need(r"MAX_LOAD = (\d+)", drum, "MAX_LOAD"))
    DATA["wash_each"] = int(need(r"WASH_TICKS_EACH = (\d+)", drum, "WASH_TICKS_EACH"))
    acts = city[city.index("public enum Act {"):]
    acts = acts[:acts.index(";")]
    DATA["acts"] = [{"name": m[1], "blurb": m[2]} for m in re.findall(
        r'(\w+)\(\s*"([^"]+)",\s*"([^"]+)",\s*(\d+)\)', acts, re.S)]
    if len(re.findall(r"^\s{8}[A-Z_]+\(", acts, re.M)) != len(DATA["acts"]):
        raise SystemExit("  the acts table would be wrong")
    works = city[city.index("public enum Work {"):]
    works = works[:works.index(";")]
    DATA["works"] = [{"name": m[1], "blurb": m[2], "cost": int(m[3])} for m in re.findall(
        r'(\w+)\(\s*"([^"]+)",\s*"([^"]+)",\s*(\d+)\)', works, re.S)]
    declared_works = len(re.findall(r"^\s{8}[A-Z_]+\(", works, re.M))
    if declared_works != len(DATA["works"]):
        raise SystemExit(f"  parsed {len(DATA['works'])} works but "
                         f"{declared_works} are declared")
    shops = java("TrapShops")
    DATA["retail"] = float(need(r"RETAIL = ([\d.]+)f", shops, "RETAIL"))
    DATA["legal_rate"] = float(need(r"LEGAL_RATE = ([\d.]+)f", shops, "LEGAL_RATE"))
    DATA["shop_reach"] = int(need(r"REACH = (\d+)", shops, "REACH"))
    DATA["markups"] = ints("MARKUP", shops)
    DATA["rent"] = ints("RENT", homes)
    DATA["mood_leaving"] = int(need(r"MOOD_LEAVING = (\d+)", homes, "MOOD_LEAVING"))
    DATA["wage_multiple"] = int(need(r"WAGE_MULTIPLE = (\d+)", homes, "WAGE_MULTIPLE"))
    DATA["size_lift"] = float(need(r"SIZE_LIFT = ([\d.]+)f", homes, "SIZE_LIFT"))
    DATA["floor_per_head"] = int(need(r"FLOOR_PER_HEAD = (\d+)", homes, "FLOOR_PER_HEAD"))
    DATA["comfortable"] = int(need(r"COMFORTABLE = (\d+)", math, "COMFORTABLE"))
    DATA["income_rate"] = int(need(
        r'INCOME\("Income", "[^"]*",\s*(\d+)', city, "the income duty's opening rate"))
    DATA["wage"] = int(need(r"int WAGE = (\d+)", crew, "WAGE"))
    DATA["max_hands"] = int(need(r"MAX_HANDS = (\d+)", crew, "MAX_HANDS"))

    DATA["heat_thresholds"] = ints("THRESHOLDS", heat)
    DATA["pillagers"] = ints("PILLAGERS", heat)
    DATA["vindicators"] = ints("VINDICATORS", heat)
    DATA["ravagers"] = ints("RAVAGERS", heat)
    DATA["heat_radius"] = int(need(r"RADIUS = (\d+)", heat, "heat RADIUS"))

    DATA["dry_ticks"] = int(need(r"DRY_TICKS = (\d+)", rack, "DRY_TICKS"))
    DATA["ready"] = int(need(r"READY_DRYNESS = (\d+)", rack, "READY_DRYNESS"))
    DATA["max_dryness"] = int(need(r"MAX_DRYNESS = (\d+)", rack, "MAX_DRYNESS"))
    DATA["leaves"] = int(need(r"LEAVES_PER_BATCH = (\d+)", press, "LEAVES_PER_BATCH"))
    tol = java("ToleranceStatusEffect")
    DATA["tol_max"] = int(need(r"MAX_LEVEL = (\d+)", tol, "tolerance MAX_LEVEL"))
    DATA["tol_mins"] = int(need(r"DURATION_TICKS = 20 \* 60 \* (\d+)", tol, "tolerance mins"))

    stock = java("ShopStock")
    DATA["categories"] = len(re.findall(r"new Category\(", stock))
    # Counting `add(c, "` misses every line the catalogue builds in a loop,
    # which is now most of them -- nine woods, sixteen dyes, eight coppers --
    # so the page would have advertised a market a third the size of the real
    # one. check_stock.py already expands those loops; borrow it.
    DATA["declared_lines"] = len(check_stock.catalogue(stock))


def band_top(tier: int) -> int:
    steps = DATA["floor_steps"]
    last = len(steps) - 1
    return steps[tier] if tier <= last else steps[last] + (steps[last] - steps[last - 1])


def roominess(tier: int, floor: int) -> float:
    steps = DATA["floor_steps"]
    frm = steps[min(tier, len(steps)) - 1]
    to = band_top(tier)
    return max(0.0, min(1.0, (floor - frm) / (to - frm)))


def rate_at(tier: int, floor: int) -> float:
    """Mirror of HomeSurvey.rateOf — the one number both sides read."""
    rent, top, lift = DATA["rent"], DATA["top_tier"], DATA["size_lift"]
    reaches = rent[tier + 1] if tier < top else rent[top] + (rent[top] - rent[top - 1])
    return rent[tier] + (reaches - rent[tier]) * roominess(tier, floor) * lift


def rent_at(tier: int, floor: int) -> int:
    """A contented tenant's rent, so mood drops out of it."""
    return max(1, round(rate_at(tier, floor)))


def wage_at(tier: int, floor: int) -> int:
    """Mirror of HomeSurvey.wageDue for one head, so the table cannot drift.

    Takes a FLOOR rather than a roominess on purpose: a band is exclusive at
    the top, so quoting the rate at roominess 1.0 advertises a wage nobody in
    that grade can actually be paid. The top grade reaches half a band past
    itself rather than nowhere — see HomeSurvey.bandTop for why.
    """
    return max(1, round(rate_at(tier, floor) * DATA["wage_multiple"]))


def minutes(rolls: int) -> float:
    """Random ticks to minutes at the default randomTickSpeed of 3."""
    return rolls * (4096 / 3) / 20 / 60


# --- the page ---------------------------------------------------------------

def esc(text: str) -> str:
    return html.escape(str(text))


# Hand-picked, and hand-picked on purpose: these are editorial, not data. The
# best writing in this project is in its comments, so the wiki quotes them.
QUOTES = [
    ("A win that hands over less than you put in is a loss wearing a party hat, "
     "and a machine full of those is one nobody can tell they are losing at.",
     "TrapMath.java"),
    ("A raid that only swings axes is a mob spawner with a story attached.",
     "TrapRaid.java"),
    ("The wage is the point. A crew is the first thing you own that can lose you "
     "money by existing.", "TrapCrew.java"),
    ("Company is the counterplay, which makes it a social mechanic rather than a "
     "solo debuff.", "TrapParanoia.java"),
    ("A price editor is a menu, and what this wants to be is a reason to walk "
     "across town.", "TrapStalls.java"),
]



def recipe_grid(name: str, label: str) -> str:
    """One crafting recipe drawn as a 3x3, straight from the shipped JSON."""
    r = DATA["recipes"].get(name)
    if not r:
        return ""
    cells = ""
    for row in r["grid"]:
        for cell in row:
            if not cell:
                cells += '<i></i>'
                continue
            art = icon(cell)
            inner = (f'<img src="{art}" alt="" loading="lazy">' if art
                     else esc(pretty(cell)[:3].upper()))
            cells += f'<i class="on" data-name="{esc(pretty(cell))}">{inner}</i>'
    yields = f'<b>x{r["count"]}</b>' if r["count"] > 1 else ""
    made = icon(r["result"])
    stamp = (f'<span class="made" data-name="{esc(label)}">'
             f'<img src="{made}" alt="" loading="lazy">{yields}</span>' if made else yields)
    return (f'<figure class="craft reveal"><div class="grid3">{cells}</div>'
            f'<figcaption>{stamp}{esc(label)}</figcaption></figure>')


def craft_row(pairs) -> str:
    return '<div class="crafts">' + "".join(
        recipe_grid(name, label) for name, label in pairs) + "</div>"


def blend_rows() -> str:
    out = []
    for b in DATA["blends"]:
        bonus = ", ".join(b["bonus"]) or "—"
        out.append([f'<span class="dot" style="--tint:{b["colour"]}"></span>'
                    f'<strong>{esc(b["name"])}</strong>',
                    '<span class="dim">' + esc(" + ".join(b["parts"])) + "</span>",
                    f'{b["potency"]:.2f}x', f'<span class="acc">{esc(bonus)}</span>'])
    return table(["Blend", "Recipe", "Potency", "It also gives you"], out)


def section(num: str, slug: str, title: str, kicker: str, body: str) -> str:
    return f"""
<section id="{slug}" class="sec">
  <div class="sec-mark">§{num}</div>
  <header class="sec-head">
    <p class="kicker">{esc(kicker)}</p>
    <h2>{esc(title)}</h2>
  </header>
  {body}
</section>"""


def table(headers: list[str], rows: list[list[str]], cls: str = "") -> str:
    head = "".join(f"<th>{esc(h)}</th>" for h in headers)
    body = "".join("<tr>" + "".join(f"<td>{c}</td>" for c in row) + "</tr>" for row in rows)
    return f'<div class="scroller"><table class="{cls}"><thead><tr>{head}</tr></thead>' \
           f"<tbody>{body}</tbody></table></div>"


def strain_cards() -> str:
    cards = []
    for s in DATA["strains"]:
        effects = "".join(
            f'<li>{esc(n)} <span class="dim">{secs}s'
            + (f" · {'I' * amp}" if amp > 1 else "") + "</span></li>"
            for n, secs, amp in s["effects"])
        tag = "hybrid" if s["hybrid"] else "pure"
        cards.append(f"""
    <article class="strain reveal" style="--tint:{s['colour']}">
      <div class="strain-top">
        <span class="swatch"></span>
        <span class="tag">{tag}</span>
      </div>
      <h3>{esc(s['name'])}</h3>
      <p class="blurb">{esc(s['blurb'])}</p>
      <ul class="fx">{effects}</ul>
      <dl class="stat">
        <div><dt>High</dt><dd>{s['seconds']}s</dd></div>
        <div><dt>Level</dt><dd>{'I' * s['intensity']}</dd></div>
      </dl>
    </article>""")
    return '<div class="strain-grid">' + "".join(cards) + "</div>"


def build() -> str:
    d = DATA
    coca_min = round(minutes(d["coca_rolls"]) * 3)
    wet_min = round(minutes(d["weed_wet"]) * 3)
    dry_min = round(minutes(d["weed_dry"]) * 3)
    cure_min = round(d["dry_ticks"] * d["max_dryness"] / 20 / 60)

    quality_rows = [[esc(q["name"]), f'{q["potency"]:.2f}×', f'{q["emeralds"]}e']
                    for q in d["quality"]]
    purity_rows = [[esc(p["name"]), f'{p["potency"]:.2f}×', f'{p["emeralds"]}e']
                   for p in d["purity"]]
    # The breather is a SHARE of the shift, so the honest seconds-per-job is
    # the pass interval plus its slice of rest. Printing the raw interval is
    # what made a paid-up hand feel like a fraud; see TrapMath.CREW_BREAK_SHARE.
    def job_seconds(ticks: int) -> float:
        shift = ticks * d["jobs_per_shift"]
        return (shift + max(20, round(shift * d["break_share"]))) / (d["jobs_per_shift"] * 20)

    def job_label(ticks: int) -> str:
        # Formatted exactly as TrapCrew.paceLabel does, so the page, the board
        # and the handbook never disagree by a rounding.
        seconds = job_seconds(ticks)
        return f"{seconds:g}s" if seconds == int(seconds) else f"{seconds:.1f}s"

    pace_rows = [[esc(d["pace_name"][i]), job_label(d["pace_ticks"][i]),
                  f'{d["pace_cost"][i]}e' if i else "—",
                  f'+{d["pace_wage"][i]}e']
                 for i in range(len(d["pace_ticks"]))]
    job_rows = [[esc(j["name"]), f'{j["cost"]}e' if j["cost"] else "free",
                 f'+{j["wage"]}e',
                 f'<span class="dim">{esc(j["blurb"])} Wants {esc(j["needs"])}.</span>']
                for j in d["jobs"]]
    heat_rows = []
    for i, t in enumerate(d["heat_thresholds"]):
        squad = f'{d["pillagers"][i]} pillager'
        if d["vindicators"][i]:
            squad += f', {d["vindicators"][i]} vindicator'
        if d["ravagers"][i]:
            squad += f', {d["ravagers"][i]} ravager'
        heat_rows.append([f"Tier {i + 1}", f"{t}+", squad])
    slot_rows = [[f'{d["slot_sizes"][i]}×{d["slot_sizes"][i]}',
                  f'{d["slot_rtp"][i] * 100:.1f}%']
                 for i in range(len(d["slot_sizes"]))]
    award_rows = [[esc(a["title"]), f'<span class="dim">{esc(a["description"])}</span>',
                   f'<span class="frame {a["frame"]}">{a["frame"]}</span>']
                  for a in d["awards"]]

    quotes = "".join(
        f'<figure class="pull reveal"><blockquote>{esc(q)}</blockquote>'
        f'<figcaption>{esc(src)}</figcaption></figure>'
        for q, src in QUOTES)

    nav_items = [
        ("00", "start", "Getting Started"),
        ("01", "grow", "The Grow"), ("02", "cure", "Curing & Rolling"),
        ("03", "blends", "Blends"), ("04", "high", "The High"),
        ("05", "coca", "The Coca Line"), ("06", "market", "The Market"),
        ("07", "stalls", "Stalls"), ("08", "city", "The City"),
        ("08b", "homes", "Housing"),
        ("09", "crew", "The Crew"),
        ("10", "heat", "Heat & Raids"), ("11", "street", "The Street"),
        ("12", "casino", "The House"), ("13", "commands", "Commands"),
        ("14", "awards", "Advancements"),
    ]
    nav = "".join(
        f'<a href="#{slug}"><span class="n">{num}</span>{esc(title)}</a>'
        for num, slug, title in nav_items)

    sections = []

    sections.append(section("00", "start", "Getting Started", "first hour", f"""
    <p class="lede">Seeds come from wandering traders — five emeralds, one strain each
    — or from farmer villagers at journeyman. Traders also buy cured buds and joints,
    which is enough to fund everything else.</p>
    <ol class="chain">
      <li><span class="step">01</span><strong>Get seeds</strong><span class="dim">trader, or a farmer villager</span></li>
      <li><span class="step">02</span><strong>Plant on watered farmland</strong><span class="dim">light and open sky raise the grade</span></li>
      <li><span class="step">03</span><strong>Build a drying rack</strong><span class="dim">8 sticks, 2 string</span></li>
      <li><span class="step">04</span><strong>Roll it, or sell it cured</strong><span class="dim">+ paper</span></li>
      <li><span class="step">05</span><strong>Read the handbooks</strong><span class="dim">/guide</span></li>
    </ol>
    {craft_row([("drying_rack", "Drying Rack"), ("mixing_station", "Mixing Station"),
                ("market_stall", "Market Stall"), ("ledger", "The Ledger"),
                ("burner_phone", "Burner Phone"), ("leaf_press", "Leaf Press")])}
    <p class="note">Hover a square to see what goes in it. Everything here is craftable
    — nothing in this mod is creative-only.</p>"""))

    sections.append(section("01", "grow", "The Grow", "six phenotypes, four stages", f"""
    <p class="lede">Seeds go on farmland. Four stages, then right-click a full-grown
    plant with an empty hand — you get buds, sometimes a seed, and the plant stays
    and regrows.</p>
    <p>Three strains are pure and grow from seed. The other three only exist by
    <strong>cross-breeding</strong>: plant two different pure strains as neighbours
    and a mature pair occasionally throws a hybrid seed.</p>
    {strain_cards()}
    <h3 class="sub">How long</h3>
    {table(["Ground", "Seed to ripe", "Why"], [
        ["Watered farmland", f"~{wet_min} min", '<span class="dim">moisture 7 under the plant</span>'],
        ["Dry ground", f"~{dry_min} min", '<span class="dim">twice as slow, and it costs you grade</span>'],
    ])}
    <p class="note">Water is worth three quality points <em>and</em> half the wait.
    One definition of "watered" drives both, so they can never disagree.</p>
    <h3 class="sub">Quality</h3>
    <p>Grade is decided at harvest by the conditions the plant actually grew in:
    hydration, light, open sky, and whether you rushed it with bone meal.</p>
    {table(["Grade", "Potency", "Per bud"], quality_rows)}"""))

    sections.append(section("02", "cure", "Curing & Rolling", "patience, then paper", f"""
    <p class="lede">Fresh buds are worth little. A drying rack turns them into
    cured product over about {cure_min} minutes, and the rack is a decision rather
    than a timer.</p>
    {table(["Stage", "What it means"], [
        ["0", '<span class="dim">soaking wet — refuses to be collected</span>'],
        ["1–2", '<span class="dim">collectable, but a grade lost per stage short</span>'],
        [str(d["ready"]), '<span class="acc">peak — full grade, double yield</span>'],
        [str(d["max_dryness"]), '<span class="dim">overdried, one grade lost</span>'],
    ])}
    <p>Peak gets a long grace period before it spoils. You should have to forget
    about it, not lose it for stepping away.</p>
    <h3 class="sub">Blends</h3>
    <p>The mixing station takes two to four kinds of dried bud and makes something
    that is none of them. Six strains taken two to four at a time is
    <strong>203 distinct recipes</strong>, and a handful of them have names worth
    finding. Whole stacks go in; it runs the lot in one click.</p>
    <p class="note">Grade follows your <em>worst</em> slot. Averaging would let one
    Fire bud launder three Swill ones.</p>"""))


    sections.append(section("03", "blends", "Blends", "203 recipes, six with names", f"""
    <p class="lede">Two to four kinds of dried bud go into the mixing station and come
    out as something that is none of them. Effects are the union of the parts, each
    scaled by its share — a mix that is three-quarters Kush feels mostly like Kush.</p>
    <p>Repeats count, so two Kush and a Purp is not the same as one of each. Six
    strains taken two to four at a time, order irrelevant, is <strong>203 distinct
    recipes</strong>. These ones do something the arithmetic would not give you:</p>
    {blend_rows()}
    <p class="note">A blend's grade follows your <em>worst</em> slot, and the station
    says so before you click. Mixing Fire with Swill makes Swill.</p>"""))

    sections.append(section("04", "high", "The High", "three custom effects", f"""
    <p class="lede">Baked and Wired are effects of their own, with their own icons and their own
    rules — not a stack of borrowed ones wearing a new name.</p>
    <div class="cards">
      <div class="card reveal"><h4>Baked</h4><p>Burns saturation, then hunger — the
      munchies. Heals you slowly while you are well fed, and chips your health if you
      smoke on an empty stomach. Duration and level come from the strain and the grade.</p></div>
      <div class="card reveal"><h4>Wired</h4><p>The coca line's effect. Speed and
      resistance to sit down for; the comedown is the part you plan around.</p></div>
      <div class="card reveal"><h4>Tolerance</h4><p>Builds as you smoke and damps
      everything that follows, up to level {DATA['tol_max']}. One level wears off in
      about {DATA['tol_mins']} minutes. Chain-smoking your own stock is a losing move.</p></div>
    </div>
    <h3 class="sub">Ways to take it</h3>
    {table(["Method", "What it does"], [
        ["<strong>Joint</strong>", '<span class="dim">bud + paper. The portable one, and what customers buy.</span>'],
        ["<strong>Bong</strong>", '<span class="dim">water bucket once, then a cured bud a hit. Water stays; the bowl does not.</span>'],
        ["<strong>Gravity bong</strong>", '<span class="dim">water, bud, flint and steel, then pull. The heaviest hit in the mod.</span>'],
    ])}
    {craft_row([("bong", "Bong"), ("gravity_bong", "Gravity Bong"),
                ("joint_kush", "Joint"), ("blend_joint", "Blend Joint")])}
    <p class="note">Hold right-click to smoke one. It plays out properly — the joint
    goes to your mouth, and everybody nearby sees the smoke.</p>"""))

    sections.append(section("05", "coca", "The Coca Line", "longer chain, richer end", f"""
    <p class="lede">A bush ripens in about {coca_min} minutes on any ground with
    light. The value is not in the farming — it is in what happens next.</p>
    <ol class="chain">
      <li><span class="step">01</span><strong>Bush</strong><span class="dim">2–4 leaves a harvest</span></li>
      <li><span class="step">02</span><strong>Leaf press</strong><span class="dim">{d['leaves']} leaves → paste</span></li>
      <li><span class="step">03</span><strong>Refiner</strong><span class="dim">paste + blaze powder</span></li>
      <li><span class="step">04</span><strong>Powder</strong><span class="dim">purity from timing</span></li>
    </ol>
    <p>Purity is decided entirely by when you pull it. Too early and it is cut;
    too late and it burns.</p>
    {table(["Purity", "Potency", "Per unit"], purity_rows)}
    <p class="note">Running coca and weed in the same place is
    <strong>{round((d['mixed_trade'] - 1) * 100)}% more heat</strong> than the two
    apart. Presses and refiners count as well as the plants. Two sheds beat one shed.</p>"""))

    sections.append(section("06", "market", "The Market", "a price list that breathes", f"""
    <p class="lede">{d['categories']} shelves and over a thousand lines, priced by three
    forces multiplied together — and every one of them moves for a reason you caused.</p>
    <div class="cards">
      <div class="card reveal"><h4>Supply</h4><p>The money in circulation, sampled
      from what players are carrying <em>and</em> what is sat in their chests. Every
      emerald spent leaves it; every payout enters it. A jackpot really is
      inflationary.</p></div>
      <div class="card reveal"><h4>Drift</h4><p>Each line walks its own path between
      random targets, eased so it arrives gently. Come back in a minute and copper has
      moved — in the direction it was already going. Up to ±{round(d['drift'] * 100)}%.</p></div>
      <div class="card reveal"><h4>Order flow</h4><p>Buying pushes a price up, selling
      pushes it down, and it fades over the following minutes. Clear the shelf and the
      last one costs more than the first.</p></div>
    </div>
    <p>Prices never drop below <strong>{d['index_min']:.2f}×</strong> or climb past
    <strong>{d['index_max']:.2f}×</strong> of normal — and "normal" is whatever the
    last few hours looked like, not whatever the first day looked like. A good week
    becomes the new normal instead of leaving everything dear forever.</p>
    <p class="note">The counter buys back at {round(d['sell_rate'] * 100)}% — wide on
    purpose. The shop is a convenience, not an income.</p>"""))

    sections.append(section("07", "stalls", "Stalls", "the reason to walk across town", f"""
    <p class="lede">The stall you place is yours. Put a chest directly underneath it
    and everything inside goes on sale to everybody else at
    {round(d['stall_rate'] * 100)}% of the market price.</p>
    {table(["Route", "Grower gets", "Builder pays", "Lost"], [
        ["The counter", f"{round(100 * d['sell_rate'])}e", "100e",
         '<span class="warn">55e to nobody</span>'],
        ["A stall", f'<span class="acc">{round(100 * d["stall_rate"] * (1 - d["stall_fee"]))}e</span>',
         f'<span class="acc">{round(100 * d["stall_rate"])}e</span>',
         f'{round(100 * d["stall_rate"] * d["stall_fee"])}e pitch fee'],
    ])}
    <p>Both sides beat the counter and neither takes anything from the other — the
    spread that used to evaporate is split between you. There is
    <strong>no price editor</strong>: prices follow the market, so a stall is never
    stale and the only decision left is what to stock.</p>
    <p class="note">The market screen tells you when a neighbour has a line cheaper,
    with their name and coordinates. <code>/stalls</code> lists the lot.</p>"""))

    sections.append(section("08", "city", "The City", "the public purse", f"""
    <p class="lede">Nothing is taxed until somebody crafts a <strong>city vault</strong> and
    puts it down, and no house can be registered either — there is nobody to register it
    with. Put one down and both start, server-wide, with an announcement.</p>
    <p>There is one vault and one purse. Every duty anybody pays goes into it, and
    <strong>anybody may spend it</strong> — every withdrawal is announced to everyone on the
    server. That is a decision, not an oversight: three friends can agree what the money is
    for in ten seconds, and a voting interface would be a menu standing where a conversation
    should be.</p>
    <p class="note">Breaking the vault spends nothing. The money is in the city's books, not
    in the block — it just means nobody can reach it, or file a house, until one is stood up
    again.</p>
    <h3 class="sub">What is taxed</h3>
    {table(["Duty", "On", "Starts at", "Band"], [
        [esc(x["name"]), f'<span class="dim">{esc(x["blurb"])}</span>',
         f'{x["start"]}%', f'{x["floor"]}–{x["ceiling"]}%'] for x in d["duties"]])}
    <p>Buying pays a duty on top of the shelf price — and the shelf price you see already
    includes it, at the counter and at a neighbour's stall alike. Being paid has income duty
    taken out of it. Every stake laid on a casino floor pays gaming duty, win or lose, which
    is the only version that cannot be dodged by a lucky night.</p>
    <p class="note"><strong>Nothing sold to customers or dealers is taxed at all.</strong>
    That is not an oversight either — the black market pays better per hour precisely because
    it pays nothing to anybody, and that is a problem worth having.</p>
    <h3 class="sub">What the purse buys</h3>
    <p>A treasury with no sink is a scoreboard. Each of these is bought once, permanently, by
    anybody, from the vault — and announced, the same rule as a withdrawal and for the same
    reason.</p>
    {table(["Public work", "Does", "Costs"], [
        [esc(w["name"]), f'<span class="dim">{esc(w["blurb"])}</span>', f'{w["cost"]}e']
        for w in d["works"]])}
    <p class="note">All four only make sense for a <em>city</em>. The roads are the one thing
    in the game that rewards building near each other; the watch is the city answering the
    thing that makes farms dangerous; and the exchange pays everybody, including whoever never
    leaves their farm.</p>
    <h3 class="sub">Shops the town walks into</h3>
    <p>A <strong>market shelf</strong> over a chest or barrel sells what is in it — not to
    players, but to the city. Townspeople come out of the housing, walk to the building, take
    a lot off the shelf and pay <strong>{round(d['retail'] * 100)}%</strong> of the market
    price, which is about double what the counter gives for the same crate. The duty on the
    sale goes straight to the purse.</p>
    <p>A <strong>shop till</strong> is the shop. Put one down and every market shelf within
    {d['shop_reach']} blocks joins it — no wand, no attaching, a shelf simply belongs to the
    nearest till. One name, one price policy, one cash register for the whole building, and
    stock is any chest or barrel under the till <em>or</em> under any of its shelves, so a back
    room and a stocked counter both work.</p>
    <p>Prices are yours: from {min(d['markups'])}% to {max(d['markups'])}% of what the town
    expects to pay. Cheap brings more of them through the door; dear takes more off each one.
    Opening the till pays you the takings.</p>
    <h3 class="sub">Over the counter</h3>
    <p>Shelves sell <strong>joints, cured buds and powder</strong> as well as groceries — at
    {round(d['legal_rate'] * 100)}% of the street price, but <strong>clean</strong>: paid in
    real emeralds, declared, taxed, and nobody carries heat for it. Half again as much on the
    street, dirty, and it has to go through a drum. The safest money is the slowest.</p>
    <p><strong>How much custom you get is the population</strong> — the sum of the housing
    grades. So the loop closes: houses make people, people shop, shopping pays the farmer and
    the city, and the purse pays for more of the town. Nobody has to be told to build houses;
    the shop tells them. <code>/shops</code> lists the counters and the head count.</p>
    <p class="note">Townspeople buy food far more often than anything else, which is both true
    and the reason this is built for whoever is doing the farming.</p>
    <h3 class="sub">The revenue office</h3>
    <p>Everything legal pays duty and everything illegal pays nothing, which on its own just
    means drugs are better. The office is the other end of that: it reads what came in against
    what you declared, and over <strong>{d['looks_away']}e a day it cannot account for</strong>
    it assesses you for {round(d['assessment'] * 100)}% of the excess. Cannot pay? The debt
    stands and you carry heat until it is settled — <code>/law pay</code>.</p>
    <h3 class="sub">Dirty money</h3>
    <p>The street does not pay in emeralds. Customers and dealers pay in <strong>dirty
    emeralds</strong> — an item, not a balance. No shop takes them, no wage comes out of them,
    and the market does not know they exist. They are not money yet.</p>
    <p>They become money in a <strong>laundry drum</strong>: right-click it holding them,
    {d['wash_min']} at a minimum and <strong>{d['wash_max']}</strong> to a load, then wait —
    {d['wash_each'] / 20:g} seconds an emerald, so about
    {d['wash_max'] * d['wash_each'] // 1200} minutes for a full drum. Take it out and you get
    clean emeralds with <strong>up to {round(d['wash_cut'] * 100)}%</strong> gone down the
    drain: the cut is rolled, so you never know quite how much. That is the moment those
    emeralds enter the money supply at all.</p>
    <p class="note">Time is per emerald rather than per load, so a drum is a throughput and not
    a free multiplier — and adding more restarts the clock, so load it all and walk away. If one
    is not enough, build another.</p>
    <p>Washing also clears the day's exposure — but only up to <strong>what your businesses
    could plausibly have taken</strong>. A shop that sold nothing explains nothing, however
    much its owner would like it to. A real business is the licence to launder and its size is
    the limit, which is why a market shelf and a casino floor are worth owning for a reason
    other than what they earn.</p>
    <h3 class="sub">Acts</h3>
    <p>The council passes laws when the city needs them and repeals them when it does not.
    Reactive, never random — a rule that arrives for no reason is weather, and nobody plays
    around weather. <code>/law</code> hands you the constitution, written fresh the moment you
    ask.</p>
    {table(["Act", "Passes when"], [[esc(a["name"]), f'<span class="dim">{esc(a["blurb"])}</span>']
                                    for a in d["acts"]])}
    <h3 class="sub">The budget</h3>
    <p>Rates move on their own every {d['budget_days']} days and the change is announced with
    its reason. Under {d['broke']}e in the purse and everything goes up; over {d['flush']}e and
    everything comes down; otherwise each rate wanders a point either way inside its band.
    <code>/city</code> prints the current table and what each duty has raised.</p>"""))

    sections.append(section("08b", "homes", "Housing", "a room the city can see", f"""
    <p class="lede">Craft a mailbox, stand it <strong>inside</strong> a room once and
    right-click it. It walks the walls and tells you what you have built — and once it
    passes, that room is an address.</p>
    <p>Then the box belongs <strong>outside</strong>. <strong>Sneak + right-click
    empty-handed</strong> and it comes into your hand carrying the address, so you can
    nail it up by the door or out on the street. Mining it works too. The survey stays
    pinned to the spot it was first taken from; the box is only where the post goes.</p>
    <p class="note">A box that has lost its address is not a problem: put a blank one
    back inside the house and it takes the job again, or stand one outside and it serves
    your nearest house that has no post. <code>/homes demolish</code> takes the house
    you are standing in off the register.</p>
    <h3 class="sub">Sealed means sealed</h3>
    <p><strong>Doors count as walls</strong>, which is what makes a bedroom with the door
    shut still part of your house: every door on the edge gets probed on its own, and one
    that opens onto something small is another room, while one that opens onto the world
    is your front door. Stairs and ladders make upstairs work with no extra thought.</p>
    <p class="note">A sealed void with no way in is not a house, so walling off a cavern
    to inflate the floor area does nothing.</p>
    <h3 class="sub">The five musts</h3>
    <p>Miss any of these and it is not a house at all, whatever else is in it:
    sealed · {d['min_floor']} squares of floor · a bed · a door onto the street · a light.</p>
    <h3 class="sub">Size is a lid, not a bonus</h3>
    <p>This is the part worth knowing. Floor area does not earn points — it decides the
    <strong>highest grade the place is allowed</strong>, and nothing else can lift it.
    A cupboard with a bed, a table, a chest, a furnace and a torch is a grade one, however
    neatly it is fitted out.</p>
    {table(["Floor", "Highest grade allowed"], [
        [f"{d['floor_steps'][i]}+ squares", str(i + 1)] for i in range(len(d['floor_steps']))
    ])}
    <p class="note">Floor means squares you could stand on, so <strong>every storey
    counts</strong> and a cathedral ceiling counts once. Three storeys of a modest cottage
    gets there as surely as one big hall.</p>
    <h3 class="sub">Then it is points</h3>
    {table(["Worth", "For"], [
        ["0–2", f"built, not dug — {round(d['shell_steps'][0] * 100)}% then "
                f"{round(d['shell_steps'][1] * 100)}% of the shell made of worked material"],
        ["0–3", f"fittings — a crafting table, storage, a furnace, a market stall and a "
                f"window; two earns one, four earns two, all {d['fittings']} earns three"],
        ["0–2", f"character — {d['decor_steps'][0]} then {d['decor_steps'][1]} different "
                f"kinds of block in the place"],
        ["0–2", f"lighting — measured at head height, brighter than {d['dark_at']}; "
                f"a fifth of the floor dim earns one, a twentieth earns two"],
    ])}
    <p>Every two points is a grade, up to <strong>{d['top_tier']}</strong> — and then the
    floor caps it. The mailbox always tells you the single next thing to do, so you never
    have to read the table.</p>
    <p class="note">Dirt, sand, gravel, plain stone, cobble, logs and leaves are what the
    world hands you and count as dug. Everything you crafted, smelted, cut or dyed counts
    as built — including anything a mod ships as decoration.</p>
    <p class="note">Two houses cannot share ground — flats side by side are fine, and so is
    one above another. A house reaches at most {d['span']} blocks from its mailbox, and it
    re-measures itself every couple of minutes, so knocking a wall through or taking the
    bed out shows up on its own. <code>/homes</code> lists everybody's.</p>
    <h3 class="sub">How many live there</h3>
    <p>A house can hold more than one person. Three things decide how many, and
    <strong>whichever is smallest wins</strong>:</p>
    <p>one bed each · {d['floor_per_head']} squares of floor each · a good enough grade.</p>
    <p>So a fourth bed in a small grade-two room houses nobody extra — you need the space and
    the grade as well. One person always fits, however rough the place is.</p>
    <p class="note">This matters because everything else counts <em>people</em>, not houses.
    Every shopper at your till, every gambler on your casino floor and every emerald of income
    tax is per person. A four-bed house is four people coming through your door.</p>

    <h3 class="sub">Payday</h3>
    <p>Your residents go out to work and get paid once a day, based on the grade of the house
    they live in. <strong>This is the only way new money comes into the town.</strong></p>
    <p>The city taxes their wages first — {d['income_rate']}% to start — and that goes to the
    vault. Whatever is left goes into the <strong>town purse</strong>.</p>
    <p>Rent is then paid <em>out of that purse</em>, into your mailbox, the same day. So the
    wage is anchored to the rent table — <strong>{d['wage_multiple']}× the rent</strong> at the
    bottom of every grade — which guarantees a resident always clears their own landlord and
    leaves something over to spend.</p>
    <h3 class="sub">Size counts too</h3>
    <p>The grade decides the money. On top of that, <strong>a bigger house of the same grade is
    worth more than a smaller one</strong>.</p>
    <p>Without that, size would only count in jumps: {d['floor_steps'][3]} squares of floor makes
    a grade four, and so does {d['floor_steps'][4] - 1}, so the last sixty blocks you laid would
    have earned nobody anything.</p>
    <p>A house at the biggest end of its grade earns <strong>halfway towards the next grade</strong>
    — never the whole way, so moving up a grade is always better than just building wider. Adding
    floor can never make anyone earn less.</p>
    <p><strong>Rent goes up the same way.</strong> Rent and wages are the same figure seen twice:
    what a resident gets paid, and what they hand you for the room. So a big house both earns its
    resident more and pays you more rent. Per resident, per day:</p>
    {table(["Grade", "Floor", "Earns (small → big)", "Rent to you (small → big)"],
           [[str(i),
             f"{d['floor_steps'][i - 1]}–{band_top(i) - 1}" if i < d['top_tier']
             else f"{d['floor_steps'][i - 1]}+",
             f"{wage_at(i, d['floor_steps'][i - 1])}e → "
             f"{wage_at(i, band_top(i) if i >= d['top_tier'] else band_top(i) - 1)}e",
             f"{rent_at(i, d['floor_steps'][i - 1])}e → "
             f"{rent_at(i, band_top(i) if i >= d['top_tier'] else band_top(i) - 1)}e"]
            for i in range(1, len(d['rent']))])}
    <p class="note">The top grade has no band above it to reach towards, so it gets one more of
    its own width — a palace out-earns a mansion, and past {band_top(d['top_tier'])} squares you
    have hit the ceiling of the whole ladder.</p>
    <p class="note">And a bigger house holds more people, so size pays twice: the household total
    is this figure times the number of heads.</p>
    <p class="note">You collect rent by opening the mailbox — there is no second thing to
    click. A tenant who somehow cannot make rent pays <em>none</em> of it rather than part;
    the mood slide below is what eventually moves them out.</p>

    <h3 class="sub">The town purse — why this matters to you</h3>
    <p>That leftover money is what your shops and casinos get paid with. <strong>The town can
    only spend what it has earned.</strong> If the purse is empty, people stay home — fewer
    customers at your shelves and fewer gamblers at your machines.</p>
    <p>What counts is how much there is <em>per person</em>. Around {d['comfortable']}e each
    means a comfortable town that shops normally, and more than that means it shops harder, up
    to a limit. Twenty people sharing a purse are well off; two hundred sharing the same purse
    are not.</p>
    <p>So the whole thing is a loop:</p>
    <p class="note"><strong>better houses → better-paid residents → more customers in your shop
    → more tax in the vault → public works → a better city.</strong> Build well and you get paid
    three times: the rent, the tax that pays for the city, and the money those people spend with
    you. <code>/city</code> shows the purse.</p>
    <h3 class="sub">Mood, and the letters</h3>
    <p>Tenants hold a mood out of 100. Dark corners and a falling grade wear it down, and
    <strong>an unhappy tenant pays less before they pay nothing</strong> — so a slide shows up
    in the money before it shows up as an empty house. Under {d['mood_leaving']} they are
    packing.</p>
    <p>They write, and the letters are in the mailbox: <em>"The light on the landing has
    gone."</em> <em>"There's something growing next door. I can smell it."</em> That is the
    whole tutorial for this system, and it needed no page.</p>
    <h3 class="sub">They are your customers too</h3>
    <p>Right-click a resident <strong>empty-handed</strong> and they tell you what they fancy —
    a strain's joints, cured buds, or powder — and what they will pay for it. Hold it and click
    them again and they buy, in <strong>dirty emeralds</strong>, exactly like a customer at the
    door. The fancy and the price are rolled fresh most days, and somebody who likes where they
    live pays a little over.</p>
    <p>They gamble, too. A resident living near a wired machine walks in and plays it rather
    than a stranger appearing from nowhere — the same person who pays your rent, going home
    afterwards. Which is an argument for building the casino where people actually live. Their
    stake comes out of the same purse their wages went into, and what they win goes back to it.</p>
    <p>And they go to work. About one townsperson in three that you see is walking to a job
    rather than to a counter — a shop till, a stall, a casino floor, the vault. A town's jobs
    are whatever has actually been built, so a village of houses and nothing else has nobody
    commuting. It is scenery: the wage was already paid from the housing register, whether or
    not anyone was stood there watching.</p>
    <h3 class="sub">Not next door</h3>
    <p>A grow within scanning distance of somebody's front room empties it. A small one leaves
    them miserable and paying two fifths; anything bigger and they go. <strong>The plantation
    and the apartment block cannot be the same place</strong> — that tension is what the whole
    city design was built around, and it is the only thing in the mod that makes the two halves
    of it argue over the same ground.</p>"""))

    sections.append(section("09", "crew", "The Crew", "somebody to do the picking", f"""
    <p class="lede">{d['hire']}e to take somebody on, then {d['wage']}e every five
    minutes <strong>they are working</strong>, harvest or no harvest. {d['max_hands']}
    hands is all one operation will carry.</p>
    <p>They work <strong>daylight only</strong> by default — at dusk they find a bed inside
    the patch and turn in — and the clock stops with them, so nights cost nothing. Put one
    <strong>on nights</strong> from the board and they never stop: +{round((d['night_rate'] - 1) * 100)}%
    on the wage <em>and</em> a clock that runs all night, so about twice the packets an hour
    for about twice the work. They
    stop for a breather every {d['jobs_per_shift']} jobs, and the breather is a share
    of the shift rather than a flat minute, so a quick hand rests as briefly as it
    works. They will not tread your farmland back into dirt, pull a rack early, or
    bone-meal your own crops.</p>
    <p>The patch stays awake wherever you are, as long as you are logged in — you do
    not have to stand over anybody. If one wanders off or something eats it, the
    <strong>whip</strong> on the crew board drags it back, and puts a trained
    replacement down if the body is gone.</p>
    <p>Every hand has <strong>its own spot</strong> — wherever you were standing when you
    took them on — and it moves. Walk to the new field, open the board, and
    <strong>Work here instead</strong> moves the spot and the person with it, across
    worlds if you like. They forget the old bed and the old chest and find new ones.</p>
    <h3 class="sub">Pace</h3>
    {table(["Rung", "A job every", "Costs", "Wage"], pace_rows)}
    <h3 class="sub">Jobs</h3>
    {table(["Job", "Costs", "Wage", "What they do"], job_rows)}
    <p>The times above are what you would measure with a stopwatch, breather included
    — not the raw pass rate.</p>
    <h3 class="sub">One chest</h3>
    <p>This is the thing people get wrong. A hand uses <strong>the nearest container to their
    spot</strong> — that one and no other — for everything: what it harvests into, and what it
    draws from. Rolling wants <strong>cured buds and paper in that chest</strong>; fresh buds
    off the plant will not do and no crafting table is involved. A different container nearer
    their spot quietly becomes the one they use.</p>
    <p class="note">The crew board names the chest it is actually using and marks any taught
    job the chest cannot currently back, so you never have to guess which of the two it is.</p>
    <h3 class="sub">Crews on file</h3>
    <p><code>/crew save &lt;name&gt;</code> writes down who works where and everything
    they know; <code>/crew load &lt;name&gt;</code> buys the lot back onto the same
    patches for what it cost the first time. <code>/crew plans</code> lists them. If a
    crew ever walks over wages it files itself under <code>walkout</code> on the way
    out, so nothing is ever really lost — only paid for twice.</p>
    <p class="note">Everything you teach them puts the wage up. A hand you cannot keep
    busy is a hand losing you money. Miss a payday and you get a notice rather than a
    walkout: {d['grace']} paydays on nothing, about two days, and paying one packet
    writes the arrears off.</p>"""))

    sections.append(section("10", "heat", "Heat & Raids", "being seen costs something", f"""
    <p class="lede">A grow in the open gets noticed. Heat is measured over a
    {d['heat_radius']}-block radius: ripe plants count 3, hidden 2, growing 1, occupied
    racks 1, presses and refiners 2.</p>
    {table(["Tier", "Heat", "Who turns up"], heat_rows)}
    <p>Past the top tier the squad stops growing but the <strong>clock keeps
    shortening</strong> — twice the cap is twice as often, down to a floor. Building the
    biggest field that fits is no longer free.</p>
    <h3 class="sub">They search</h3>
    <p>A raid does not just swing axes. Raiders walk to your containers, open them, and
    take product — so hiding a stash underground is worth doing, splitting it across two
    buildings is worth doing, and standing between them and the chest is worth doing.</p>
    <p class="note">Sealing the grow in buys time, not safety. If they cannot find a way
    round they come through the wall. Obsidian stops them; dirt does not.</p>
    {quotes}"""))

    sections.append(section("11", "street", "The Street", "paranoia, phones and people", f"""
    <p class="lede">Everything that is not a product line.</p>
    <div class="cards">
      <div class="card reveal"><h4>Paranoia</h4><p>A meter that builds from heat, how
      high you are, darkness, night and being alone — and decays in daylight, sober, or
      <em>near another player</em>. Four tiers, from noises behind you to a motionless
      figure at render distance. None of it is real. Nothing is ever
      spawned, nothing touches your build, and nobody else can see it.</p></div>
      <div class="card reveal"><h4>Contracts</h4><p>A burner phone is a job board:
      the same five deliveries all day, so you can look at the board in the
      morning and plan around it. Jobs are advertised cold and the heat premium is settled at the drop —
      you are paid for risk you actually ran. Capped at {d['payout_ceiling']}e.</p></div>
      <div class="card reveal"><h4>Reputation</h4><p>It lives on the phone itself, so lending
      somebody your phone lends them your name — and losing it loses the standing
      with it. Capped at {d['rep_max']}, because it feeds four multipliers at
      once and three of them used to be unbounded.</p></div>
      <div class="card reveal"><h4>Dealers</h4><p>The first thing you own that works
      while you are not there. They take a cut, sell far better at night than at noon,
      get in each other's way if you crowd a patch, and the cheap ones get robbed.</p></div>
      <div class="card reveal"><h4>The Ledger</h4><p>Book, compass and two amethyst.
      Reads every container within 32 blocks — shulker boxes included — and draws a line
      of light to whichever chest holds what you clicked.</p></div>
      <div class="card reveal"><h4>Stickups</h4><p>The farm raid comes for the plants.
      This comes for <em>you</em>, at the moment you hand product over in person. It is
      the price of dealing yourself instead of paying somebody else to.</p></div>
    </div>"""))

    sections.append(section("12", "casino", "The House", "seven games and a vault", f"""
    <p class="lede">A casino is a bankroll and a set of machines wired to it. The
    bankroll lives in the ledger, not on the card — the card is the key, so a machine
    can pay a winner at four in the morning while the owner is offline.</p>
    {table(["Cabinet", "Return to player"], slot_rows)}
    <p>Roulette is a <strong>single-zero wheel</strong>: {d['roulette_pockets']} pockets,
    and every bet on the table carries the same edge, so the choice is purely how you
    want to lose it. The Climb pays {d['climb_return'] * 100:.1f}% at
    <em>every</em> rung — there is no correct place to stop, which makes it nerve rather
    than arithmetic.</p>
    <h3 class="sub">Where the punters come from</h3>
    <p><strong>The town.</strong> Punters are people and people live in houses, so trade
    scales with the housed population up to {d['pull_at']} grades — and a floor with no city
    behind it gets only the {round(d['pull_floor'] * 100)}% who were walking past. Reputation
    and addiction still do the rest, and they still have to be earned.</p>
    <h3 class="sub">Machines wear out, and it shows</h3>
    <p>Every cabinet gains a wear point about one round in {d['wear_per_rounds']}, and dies at
    {d['wear_broken']}. Past <strong>{d['jam_from']}</strong> it starts swallowing money and
    sending punters back out of the door, so the hammer pays for itself in trade rather than
    in tidiness. The card shows the worst cabinet on the floor and you get told the moment one
    crosses the line.</p>
    <p class="note">A floor is a business, not a tap. Punters have to be served from
    your own stash, a pit boss costs a flat wage against a proportional skim, and an
    unattended floor earns very close to nothing.</p>"""))

    cmd_rows = [
        ["<code>/wiki</code>", "This page, as a clickable link in chat"],
        ["<code>/guide</code>",
         "Seven handbooks — grower, refiner, street, crew, casino, city, housing"],
        ["<code>/guide housing</code>", "Houses, grades, and where a resident's money comes from"],
        ["<code>/market</code>", "Why everything costs what it costs"],
        ["<code>/stalls</code>", "Who is selling, and where"],
        ["<code>/city</code>", "The purse, the current duties, and what each has raised"],
        ["<code>/law</code>", "The constitution, written the moment you ask"],
        ["<code>/homes</code>", "Every house on the register, and its grade"],
        ["<code>/shops</code>", "Every market shelf, and how many townspeople there are"],
        ["<code>/homes demolish</code>", "Take the house you're stood in off the register"],
        ["<code>/homes evict</code>", "Clear a tenant standing about with no house behind them"],
        ["<code>/crew</code>", "The crew board — hire, train, pay"],
        ["<code>/heat</code>", "How hot this spot is, and what it would bring"],
        ["<code>/paranoia</code>", "Turn the whole thing off, per player"],
        ["<code>/earnings</code>", "Today's takings, everybody, by job"],
        ["<code>/sethome · /home · /spawn · /back</code>", "Getting about"],
    ]
    sections.append(section("13", "commands", "Commands", "everything answers only you", f"""
    <p class="lede">Every command answers only the person who typed it, so nothing you run gets
    announced to everybody else.</p>
    {table(["Command", "What it does"], cmd_rows)}"""))

    sections.append(section("14", "awards", "Advancements",
                            f"{len(d['awards'])} of them", f"""
    <p class="lede">These are proper advancements — they turn up in your advancements screen like
    any others, with a toast when you earn one.</p>
    {table(["Name", "How", "Kind"], award_rows)}"""))

    body = "".join(sections)

    return TEMPLATE.format(nav=nav, sections=body, lines=d["declared_lines"],
                           strains=len(d["strains"]), awards=len(d["awards"]))


TEMPLATE = """<!doctype html>
<html lang="en">
<script>document.documentElement.className='js'</script>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>TrapCraft — Field Manual</title>
<meta name="description" content="TrapCraft: growing, curing, refining, the market,
stalls, crew, heat and the casino. Everything in the game, in one place.">
<meta name="theme-color" content="#0c0b0a">
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Pixelify+Sans:wght@400..700&family=Figtree:ital,wght@0,300..900;1,300..900&family=JetBrains+Mono:wght@300;400;500&display=swap" rel="stylesheet">
<style>
:root {{
  --ink: #0c0b0a;
  --ink-2: #131110;
  --bone: #ece5d8;
  --bone-dim: #9a9285;
  --rule: #2a2622;
  --acc: #4ade80;
  --warn: #ff8c42;
  --violet: #7a4fa8;
  --measure: 62ch;
  --pad: clamp(1.25rem, 4vw, 4rem);
}}

* {{ box-sizing: border-box; }}
html {{ scroll-behavior: smooth; }}
@media (prefers-reduced-motion: reduce) {{
  html {{ scroll-behavior: auto; }}
  *, *::before, *::after {{ animation-duration: .01ms !important; transition-duration: .01ms !important; }}
}}

body {{
  margin: 0;
  background: var(--ink);
  color: var(--bone);
  font-family: 'Figtree', 'Segoe UI', system-ui, sans-serif;
  font-size: clamp(1rem, .45vw + .92rem, 1.14rem);
  line-height: 1.65;
  font-optical-sizing: auto;
  -webkit-font-smoothing: antialiased;
  overflow-x: hidden;
}}

/* Grain. One data-URI turbulence over everything, very low opacity. */
body::after {{
  content: '';
  position: fixed; inset: -50%;
  pointer-events: none; z-index: 100; opacity: .16;
  background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='200' height='200'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='.9' numOctaves='3'/%3E%3C/filter%3E%3Crect width='200' height='200' filter='url(%23n)' opacity='.5'/%3E%3C/svg%3E");
  animation: grain 6s steps(6) infinite;
}}
@keyframes grain {{
  0%,100% {{ transform: translate(0,0) }} 20% {{ transform: translate(-3%,2%) }}
  40% {{ transform: translate(2%,-3%) }} 60% {{ transform: translate(-2%,-2%) }}
  80% {{ transform: translate(3%,3%) }}
}}

::selection {{ background: var(--acc); color: var(--ink); }}
a {{ color: inherit; }}

/* --- shell --------------------------------------------------------------- */

.wrap {{ display: grid; grid-template-columns: minmax(0, 1fr); }}
.wrap > * {{ min-width: 0; }}
@media (min-width: 1080px) {{
  .wrap {{ grid-template-columns: 16rem minmax(0, 1fr); }}
}}

/* --- nav ----------------------------------------------------------------- */

.rail {{
  position: sticky; top: 0; z-index: 40;
  background: color-mix(in srgb, var(--ink) 88%, transparent);
  backdrop-filter: blur(14px);
  border-bottom: 1px solid var(--rule);
}}
.rail-in {{ display: flex; gap: .25rem; overflow-x: auto; padding: .6rem var(--pad);
  scrollbar-width: none; }}
.rail-in::-webkit-scrollbar {{ display: none; }}
.rail a {{
  flex: 0 0 auto; text-decoration: none; white-space: nowrap;
  font-family: 'JetBrains Mono', ui-monospace, monospace; font-size: .74rem; letter-spacing: .1em;
  text-transform: uppercase; color: var(--bone-dim);
  padding: .4rem .7rem; border-radius: 2px; transition: color .25s, background .25s;
}}
.rail a .n {{ opacity: .45; margin-right: .45rem; }}
.rail a:hover {{ color: var(--bone); background: #ffffff0a; }}
.rail a.on {{ color: var(--ink); background: var(--bone); }}

@media (min-width: 1080px) {{
  .rail {{
    position: sticky; top: 0; height: 100vh; border-bottom: 0;
    border-right: 1px solid var(--rule); backdrop-filter: none;
    background: linear-gradient(180deg, #100e0d, #0c0b0a);
  }}
  .rail-in {{ flex-direction: column; gap: 0; padding: 2.5rem 0; height: 100%;
    overflow-y: auto; }}
  .rail a {{ padding: .55rem 2rem; border-left: 2px solid transparent; border-radius: 0; }}
  .rail a.on {{ background: none; color: var(--bone); border-left-color: var(--acc); }}
  .rail a.on .n {{ color: var(--acc); opacity: 1; }}
  .brand {{ padding: 0 2rem 2rem; }}
}}
.brand {{ display: none; }}
@media (min-width: 1080px) {{ .brand {{ display: block; }} }}
.brand b {{ font-family: 'Pixelify Sans', 'Trebuchet MS', sans-serif; font-weight: 900; font-size: 1.5rem;
  letter-spacing: -.02em; display: block; line-height: 1; }}
.brand span {{ font-family: 'JetBrains Mono', ui-monospace, monospace; font-size: .64rem;
  letter-spacing: .22em; text-transform: uppercase; color: var(--bone-dim); }}

/* --- hero ---------------------------------------------------------------- */

.hero {{
  position: relative; min-height: 88vh; display: flex; flex-direction: column;
  justify-content: center; padding: 6rem var(--pad) 4rem; overflow: hidden;
}}
.hero::before {{
  content: ''; position: absolute; inset: -30% -10% auto -10%; height: 90%;
  background:
    radial-gradient(45% 55% at 25% 40%, #4ade8022, transparent 70%),
    radial-gradient(40% 50% at 75% 30%, #7a4fa826, transparent 70%),
    radial-gradient(35% 45% at 55% 75%, #c47f3a1c, transparent 70%);
  filter: blur(20px); animation: drift 26s ease-in-out infinite alternate;
}}
@keyframes drift {{
  from {{ transform: translate3d(-3%, 0, 0) scale(1); }}
  to   {{ transform: translate3d(4%, 4%, 0) scale(1.12); }}
}}
.hero > * {{ position: relative; }}
.eyebrow {{
  font-family: 'JetBrains Mono', ui-monospace, monospace; font-size: .72rem; letter-spacing: .3em;
  text-transform: uppercase; color: var(--acc); margin: 0 0 1.5rem;
}}
.hero h1 {{
  font-family: 'Pixelify Sans', 'Trebuchet MS', sans-serif; font-weight: 900;
  font-size: clamp(2.6rem, 10vw, 8.5rem); line-height: .95; letter-spacing: -.01em;
  margin: 0; text-wrap: balance;
}}
.hero h1 em {{
  font-style: normal; font-weight: 400; display: block; color: var(--bone-dim);
  /* A whole clause at display size would wrap into a wall. */
  font-size: .42em; line-height: 1.15; margin-top: .35em; text-wrap: balance;
}}
.hero p {{ max-width: 46ch; margin: 2rem 0 0; color: var(--bone-dim);
  font-size: 1.15rem; }}
.figs {{ display: flex; flex-wrap: wrap; gap: 2.5rem; margin-top: 3.5rem;
  font-family: 'JetBrains Mono', ui-monospace, monospace; }}
.figs div b {{ display: block; font-size: clamp(1.6rem, 4vw, 2.4rem); font-weight: 500;
  color: var(--bone); line-height: 1; }}
.figs div span {{ font-size: .7rem; letter-spacing: .18em; text-transform: uppercase;
  color: var(--bone-dim); }}

/* staggered entrance */
.hero .eyebrow, .hero h1, .hero p, .hero .figs {{ animation: rise .9s cubic-bezier(.2,.7,.2,1) backwards; }}
.hero h1 {{ animation-delay: .08s }} .hero p {{ animation-delay: .22s }}
.hero .figs {{ animation-delay: .34s }}
@keyframes rise {{ from {{ opacity: 0; transform: translateY(1.6rem) }} }}

/* --- sections ------------------------------------------------------------ */

main {{ padding: 0 var(--pad) 8rem; }}
.sec {{ position: relative; padding: 5.5rem 0 1rem; border-top: 1px solid var(--rule);
  /* The rail is sticky, so an anchor jump would land under it. */
  scroll-margin-top: 4.2rem; }}
@media (min-width: 1080px) {{ .sec {{ scroll-margin-top: 1rem; }} }}
.sec-mark {{
  font-family: 'JetBrains Mono', ui-monospace, monospace; font-size: .72rem; letter-spacing: .2em;
  color: var(--bone-dim); margin-bottom: 1.5rem;
}}
@media (min-width: 1400px) {{
  .sec-mark {{ position: absolute; left: -5.5rem; top: 5.9rem; margin: 0; }}
}}
.kicker {{ font-family: 'JetBrains Mono', ui-monospace, monospace; font-size: .7rem; letter-spacing: .22em;
  text-transform: uppercase; color: var(--acc); margin: 0 0 .5rem; }}
.sec h2 {{
  font-family: 'Pixelify Sans', 'Trebuchet MS', sans-serif; font-weight: 900;
  font-size: clamp(1.9rem, 5vw, 3.4rem); line-height: 1.05; letter-spacing: 0;
  margin: 0 0 2rem;
}}
.sub {{ font-family: 'Pixelify Sans', 'Trebuchet MS', sans-serif; font-weight: 700; font-size: 1.5rem;
  margin: 3rem 0 1rem; letter-spacing: -.01em; }}
.lede {{ font-size: 1.28rem; max-width: var(--measure); color: var(--bone); }}
.sec p {{ max-width: var(--measure); }}
.note {{
  max-width: var(--measure); border-left: 2px solid var(--acc);
  padding: .2rem 0 .2rem 1.1rem; color: var(--bone-dim); font-style: italic;
}}
.dim {{ color: var(--bone-dim); }}
.acc {{ color: var(--acc); }}
.warn {{ color: var(--warn); }}
code {{ font-family: 'JetBrains Mono', ui-monospace, monospace; font-size: .86em; color: var(--bone);
  background: #ffffff0d; padding: .12em .4em; border-radius: 2px; }}

/* --- tables -------------------------------------------------------------- */

.scroller {{ overflow-x: auto; margin: 1.75rem 0; }}
table {{ width: 100%; border-collapse: collapse; font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: .84rem; min-width: 30rem; }}
th {{
  text-align: left; font-weight: 400; font-size: .66rem; letter-spacing: .18em;
  text-transform: uppercase; color: var(--bone-dim); padding: 0 1.5rem .7rem 0;
  border-bottom: 1px solid var(--rule); white-space: nowrap;
}}
td {{ padding: .8rem 1.5rem .8rem 0; border-bottom: 1px solid #1c1917; vertical-align: top; }}
tbody tr {{ transition: background .2s; }}
tbody tr:hover {{ background: #ffffff06; }}
.frame {{ font-size: .68rem; letter-spacing: .1em; text-transform: uppercase;
  color: var(--bone-dim); }}
.frame.goal {{ color: var(--acc); }}
.frame.challenge {{ color: var(--violet); }}

/* --- strains ------------------------------------------------------------- */

.strain-grid {{
  display: grid; gap: 1px; margin: 2.5rem 0; background: var(--rule);
  border: 1px solid var(--rule);
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 17rem), 1fr));
}}
.strain {{ background: var(--ink-2); padding: 1.75rem; position: relative;
  transition: background .35s; overflow: hidden; }}
.strain::before {{
  content: ''; position: absolute; inset: auto auto 0 0; width: 100%; height: 2px;
  background: var(--tint); transform: scaleX(0); transform-origin: left;
  transition: transform .5s cubic-bezier(.2,.7,.2,1);
}}
.strain:hover {{ background: #17140f; }}
.strain:hover::before {{ transform: scaleX(1); }}
.strain-top {{ display: flex; align-items: center; justify-content: space-between;
  margin-bottom: 1rem; }}
.swatch {{ width: 2.2rem; height: 2.2rem; border-radius: 50%; background: var(--tint);
  box-shadow: 0 0 2.5rem -.3rem var(--tint); }}
.tag {{ font-family: 'JetBrains Mono', ui-monospace, monospace; font-size: .62rem; letter-spacing: .18em;
  text-transform: uppercase; color: var(--bone-dim); }}
.strain h3 {{ font-family: 'Pixelify Sans', 'Trebuchet MS', sans-serif; font-size: 1.75rem;
  margin: 0 0 .4rem; font-weight: 700; }}
.blurb {{ color: var(--bone-dim); font-size: .95rem; margin: 0 0 1.1rem; }}
.fx {{ list-style: none; padding: 0; margin: 0 0 1.2rem; font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: .74rem; }}
.fx li {{ padding: .28rem 0; border-bottom: 1px solid #ffffff0a; }}
.stat {{ display: flex; gap: 2rem; margin: 0; font-family: 'JetBrains Mono', ui-monospace, monospace; }}
.stat dt {{ font-size: .62rem; letter-spacing: .16em; text-transform: uppercase;
  color: var(--bone-dim); }}
.stat dd {{ margin: .2rem 0 0; font-size: 1.05rem; }}

/* --- cards / chain / quotes ---------------------------------------------- */

.cards {{ display: grid; gap: 1.25rem; margin: 2.5rem 0;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 18rem), 1fr)); }}
.card {{ border: 1px solid var(--rule); padding: 1.6rem; background: var(--ink-2);
  transition: transform .4s cubic-bezier(.2,.7,.2,1), border-color .4s; }}
.card:hover {{ transform: translateY(-4px); border-color: #3d3730; }}
.card h4 {{ font-family: 'Pixelify Sans', 'Trebuchet MS', sans-serif; font-size: 1.35rem; margin: 0 0 .6rem;
  font-weight: 700; }}
.card p {{ margin: 0; font-size: .95rem; color: var(--bone-dim); }}

.chain {{ list-style: none; padding: 0; margin: 2.5rem 0; display: grid; gap: 1px;
  background: var(--rule); border: 1px solid var(--rule); }}
.chain li {{ background: var(--ink-2); padding: 1.4rem 1.6rem; display: flex;
  align-items: baseline; gap: 1.2rem; flex-wrap: wrap; }}
.chain .step {{ font-family: 'JetBrains Mono', ui-monospace, monospace; font-size: .72rem;
  color: var(--acc); letter-spacing: .16em; }}
.chain strong {{ font-family: 'Pixelify Sans', 'Trebuchet MS', sans-serif; font-size: 1.3rem; font-weight: 700; }}
.chain .dim {{ font-family: 'JetBrains Mono', ui-monospace, monospace; font-size: .78rem;
  margin-left: auto; flex: 1 1 14rem; text-align: right; }}
@media (max-width: 620px) {{ .chain .dim {{ text-align: left; margin-left: 0; }} }}

.pull {{ margin: 3rem 0; padding: 0; max-width: 52ch; }}
.pull blockquote {{ margin: 0; font-family: 'Figtree', 'Segoe UI', system-ui, sans-serif; font-style: italic;
  font-size: clamp(1.2rem, 2.6vw, 1.7rem); line-height: 1.45; font-weight: 300; }}
.pull figcaption {{ font-family: 'JetBrains Mono', ui-monospace, monospace; font-size: .68rem;
  letter-spacing: .18em; text-transform: uppercase; color: var(--bone-dim);
  margin-top: .9rem; }}
.pull figcaption::before {{ content: '— '; }}

/* --- crafting grids ------------------------------------------------------ */

.crafts {{ display: grid; gap: 1.5rem; margin: 2.5rem 0;
  grid-template-columns: repeat(auto-fit, minmax(8.5rem, 1fr)); }}
.craft {{ margin: 0; text-align: center; }}
.grid3 {{
  display: grid; grid-template-columns: repeat(3, 1fr); gap: 4px;
  width: 8rem; margin: 0 auto; padding: 5px;
  background: #3b3730;
  box-shadow: inset 2px 2px 0 #56514a, inset -2px -2px 0 #1a1815;
}}
/* Slots are drawn the way Minecraft draws them: a sunken well with a dark
   top-left edge and a light bottom-right one. */
.grid3 i {{
  position: relative; aspect-ratio: 1; background: #1b1917; display: grid;
  place-items: center; font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: .58rem; font-style: normal; color: #4a443c;
  box-shadow: inset 2px 2px 0 #0d0c0b, inset -2px -2px 0 #33302b;
  transition: background .2s;
}}
.grid3 i img {{
  width: 76%; height: 76%; object-fit: contain;
  /* 16x16 art blown up. Without this the browser smears it. */
  image-rendering: pixelated; image-rendering: crisp-edges;
}}
.grid3 i.on {{ cursor: help; }}
.grid3 i.on:hover {{ background: #2a2620; z-index: 3; }}

/* A Minecraft item tooltip, near enough. */
.grid3 i.on::after, .made::after {{
  content: attr(data-name);
  position: absolute; left: 50%; bottom: calc(100% + 8px); transform: translateX(-50%);
  background: #100014f2; color: #fff; border: 2px solid #2d0a63;
  outline: 1px solid #150127;
  font-family: 'Pixelify Sans', 'Trebuchet MS', sans-serif; font-size: .82rem;
  padding: .3rem .55rem; white-space: nowrap; pointer-events: none;
  opacity: 0; transition: opacity .14s; z-index: 5;
}}
.grid3 i.on:hover::after, .made:hover::after {{ opacity: 1; }}
@media (hover: none) {{ .grid3 i.on::after, .made::after {{ display: none; }} }}

.made {{ position: relative; display: inline-flex; align-items: center; gap: .2rem;
  margin-right: .45rem; vertical-align: -.35rem; }}
.made img {{ width: 1.3rem; height: 1.3rem; image-rendering: pixelated; }}
.made b {{ font-size: .7rem; color: var(--acc); font-weight: 500; }}
.craft figcaption {{ font-family: 'JetBrains Mono', ui-monospace, monospace; font-size: .68rem;
  letter-spacing: .12em; text-transform: uppercase; color: var(--bone-dim);
  margin-top: .8rem; }}
.dot {{ display: inline-block; width: .6rem; height: .6rem; border-radius: 50%;
  background: var(--tint); margin-right: .55rem; vertical-align: middle;
  box-shadow: 0 0 .9rem -.1rem var(--tint); }}

/* --- reveal -------------------------------------------------------------- */

.js .reveal {{ opacity: 0; transform: translateY(1.4rem);
  transition: opacity .7s cubic-bezier(.2,.7,.2,1), transform .7s cubic-bezier(.2,.7,.2,1); }}
.js .reveal.in {{ opacity: 1; transform: none; }}

footer {{ border-top: 1px solid var(--rule); padding: 3rem var(--pad) 5rem;
  font-family: 'JetBrains Mono', ui-monospace, monospace; font-size: .74rem; color: var(--bone-dim); }}
footer a {{ color: var(--acc); text-decoration: none; }}
footer a:hover {{ text-decoration: underline; }}
</style>
</head>
<body>
<div class="wrap">
  <nav class="rail">
    <div class="rail-in">
      <div class="brand"><b>TrapCraft</b><span>field manual</span></div>
      {nav}
    </div>
  </nav>

  <div>
    <header class="hero">
      <p class="eyebrow">Everything in the game · in one place</p>
      <h1>Everything<em>is somebody&#39;s business.</em></h1>
      <p>A market that breathes, a floor that wears out, hands on wages, and a raid
      that searches your chests. The complete reference — generated from the mod&#39;s
      own source, so it cannot go stale.</p>
      <div class="figs">
        <div><b>{lines}</b><span>priced lines</span></div>
        <div><b>{strains}</b><span>strains</span></div>
        <div><b>203</b><span>blends</span></div>
        <div><b>{awards}</b><span>advancements</span></div>
      </div>
    </header>

    <main>{sections}</main>

    <footer>
      <p>Every price, timing and grade on this page comes straight out of the game,
      so the page changes when the game does. If a number here looks wrong, it is
      the game that moved.</p>
      <p>There are five handbooks inside the game too — type <code>/guide</code>.
      Same numbers, fewer words, and you can read them without leaving the farm.</p>
    </footer>
  </div>
</div>

<script>
// Reveal on scroll, and light the nav for whatever you're reading.
const io = new IntersectionObserver((entries) => {{
  for (const e of entries) if (e.isIntersecting) {{
    e.target.classList.add('in');
    io.unobserve(e.target);
  }}
}}, {{ rootMargin: '0px 0px -8% 0px' }});
document.querySelectorAll('.reveal').forEach((el, i) => {{
  el.style.transitionDelay = (i % 6) * 60 + 'ms';
  io.observe(el);
}});

const links = [...document.querySelectorAll('.rail a')];
const spy = new IntersectionObserver((entries) => {{
  for (const e of entries) {{
    if (!e.isIntersecting) continue;
    links.forEach(a => a.classList.toggle('on', a.hash === '#' + e.target.id));
  }}
}}, {{ rootMargin: '-45% 0px -50% 0px' }});
document.querySelectorAll('.sec').forEach(s => spy.observe(s));
</script>
</body>
</html>
"""


def main() -> None:
    gather()
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(build())
    print(f"wrote {OUT.relative_to(ROOT)} — {len(DATA['strains'])} strains, "
          f"{len(DATA['jobs'])} crew jobs, {len(DATA['awards'])} advancements, "
          f"{DATA['declared_lines']} priced lines")


if __name__ == "__main__":
    main()
