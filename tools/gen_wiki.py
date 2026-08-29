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
import math
import pathlib
import re
import sys

from PIL import Image

import check_stock
import gen_assets

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
    # Comments first, then the terminator. A semicolon inside a note above one
    # of the entries used to end the block early, which dropped every job below
    # it AND the declared count in the same stroke -- so the guard below agreed
    # with itself about a list that was missing its last member.
    body = re.sub(r"//[^\n]*", "", body)
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


def crime_kinds() -> list[dict]:
    """The offence table, off the enum.

    Typed out in prose once and it went stale the first time a weight moved,
    which on this page is worse than useless: the whole point of printing the
    weights is that a council can see what it is buying protection FROM.
    """
    text = java("TrapCrime")
    found = re.findall(r'^\s{8}(\w+)\("([^"]+)", (\d+), (\d+), (\d+),', text, re.M)
    if not found:
        sys.exit("gen_wiki: couldn't read the crime kinds -- the enum moved")
    return [{"name": n, "display": d, "weight": int(w), "fine": int(f), "days": int(days)}
            for n, d, w, f, days in found]


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


def leagues() -> list[dict]:
    """Every competition, read out of TrapSports.

    The rosters are the point of this section -- "real teams whose real
    standing decides the price" is a claim the page has to be able to show,
    not assert -- and a hand-typed table of a hundred and twenty names would
    be wrong within a week of the first retune. So the names, the reputations,
    the styles and the whole suits matrix come out of the Java.
    """
    src = java("TrapSports")
    out = []
    for block in re.finditer(
            r'new League\(\s*"([^"]+)",\s*"([^"]+)",\s*(\w+),\s*(\w+|null),\s*'
            r'(\w+),\s*(\w+),\s*(\d+),\s*(true|false),\s*(true|false),\s*'
            r'(\d+),\s*(\d+),\s*new Runner\[\]\{(.*?)\n    \}\);',
            src, re.S):
        name, sport, conditions, _venues, styles, suits = block.group(1, 2, 3, 4, 5, 6)
        field, draws, home, places, slots, roster = block.group(7, 8, 9, 10, 11, 12)
        runners = [
            {"name": m.group(1), "reputation": int(m.group(2)),
             "style": int(m.group(3)), "note": m.group(4)}
            for m in re.finditer(r'r\("([^"]+)",\s*(\d+),\s*(\d+),\s*"([^"]+)"\)', roster)
        ]
        out.append({
            "name": name, "sport": sport,
            "conditions": strings(conditions, src),
            "styles": strings(styles, src),
            "suits": matrix(suits, src),
            "field": int(field), "draws": draws == "true", "home": home == "true",
            "places": int(places), "slots": int(slots),
            "runners": sorted(runners, key=lambda r: -r["reputation"]),
        })
    if not out:
        sys.exit("gen_wiki: no leagues parsed out of TrapSports -- the source moved")
    check_leagues(out)
    return out


def check_leagues(competitions: list[dict]) -> None:
    """The same invariants TrapSports asserts at class-init, checked on the desk.

    TrapSports throws in a static initialiser if any of these are wrong, which
    is correct -- a suits table whose rows do not line up with its conditions
    silently gives a runner its neighbour's bonus, and there is no symptom.
    But a static initialiser only runs when the server boots, and "the server
    will not start" is a thing to find out here rather than on a live world.

    The roster rule is not theoretical: the gonitwa shipped with fourteen
    horses and room for two races of eight, which is sixteen, and it would
    have taken the server down at boot.
    """
    for c in competitions:
        where = f"gen_wiki: {c['name']}"
        if len(c["suits"]) != len(c["conditions"]):
            sys.exit(f"{where}: {len(c['suits'])} suits rows for "
                     f"{len(c['conditions'])} conditions")
        for row in c["suits"]:
            if len(row) != len(c["styles"]):
                sys.exit(f"{where}: a suits row has {len(row)} columns for "
                         f"{len(c['styles'])} styles")
        for runner in c["runners"]:
            if not 0 <= runner["style"] < len(c["styles"]):
                sys.exit(f"{where}: {runner['name']} has style {runner['style']}")
        need = c["field"] * c["slots"]
        if len(c["runners"]) < need:
            sys.exit(f"{where}: {c['slots']} fixtures of {c['field']} need {need} "
                     f"runners and the roster has {len(c['runners'])} -- TrapSports "
                     f"throws on this at boot")


def strings(name: str, src: str) -> list[str]:
    raw = need(name + r'\s*=\s*\{([^}]*)\}', src, name)
    return re.findall(r'"([^"]*)"', raw)


def matrix(name: str, src: str) -> list[list[int]]:
    # re.S here rather than need(): the tables span lines, and need() searches
    # without it. A single-line search silently finds nothing and the whole
    # section renders empty, which is the failure this script exists to avoid.
    found = re.search(name + r'\s*=\s*\{(.*?)\n    \};', src, re.S)
    if not found:
        sys.exit(f"gen_wiki: couldn't find the {name} table -- the source moved")
    return [[int(n) for n in re.findall(r'-?\d+', row)]
            for row in re.findall(r'\{([^}]*)\}', found.group(1))]


def gather() -> None:
    math = java("TrapMath")
    crew = java("TrapCrew")
    heat = java("TrapHeat")
    rack = java("DryingRackBlock")
    homes = java("HomeSurvey")
    city = java("TrapCity")
    press = java("LeafPressBlock")

    clubs = java("TrapClubs")
    DATA["top_tier"] = int(need(r"TOP_TIER = (\d+)", city, "TOP_TIER"))
    DATA["tier_step"] = float(need(r"TIER_STEP = ([\d.]+)", city, "TIER_STEP"))
    DATA["shop_rate"] = int(need(r"SHOP_RATE = (\d+)", city, "SHOP_RATE"))
    DATA["house_rate"] = int(need(r"HOUSE_RATE = (\d+)", city, "HOUSE_RATE"))
    DATA["club_door"] = ints("DOOR", clubs)

    sports = java("TrapSports")
    DATA["leagues"] = leagues()
    DATA["book_margin"] = float(need(r"BOOK_MARGIN = ([\d.]+)f", math, "BOOK_MARGIN"))
    DATA["book_scale"] = float(need(r"BOOK_SCALE = ([\d.]+)f", math, "BOOK_SCALE"))
    DATA["book_form"] = int(need(r"BOOK_FORM = (\d+)", math, "BOOK_FORM"))
    DATA["book_absence"] = int(need(r"BOOK_ABSENCE = (\d+)", math, "BOOK_ABSENCE"))
    DATA["book_rest"] = ints("BOOK_REST", math)
    DATA["book_home"] = int(need(r"BOOK_HOME = (\d+)", math, "BOOK_HOME"))
    DATA["book_h2h_cap"] = int(need(r"BOOK_H2H_CAP = (\d+)", math, "BOOK_H2H_CAP"))
    DATA["book_legs"] = int(need(r"BOOK_MAX_LEGS = (\d+)", math, "BOOK_MAX_LEGS"))
    DATA["book_payout"] = int(need(r"BOOK_MAX_PAYOUT = ([\d_]+)", math,
                                   "BOOK_MAX_PAYOUT").replace("_", ""))
    DATA["book_stakes"] = ints("BOOK_STAKES", math)
    DATA["book_draw_base"] = float(need(r"BOOK_DRAW_BASE = ([\d.]+)f", math, "draw base"))
    DATA["book_min_ticks"] = int(need(r"MIN_TICKS = 20 \* 60 \* (\d+)", sports, "MIN_TICKS"))
    DATA["book_max_ticks"] = int(need(r"MAX_TICKS = 20 \* 60 \* (\d+)", sports, "MAX_TICKS"))
    DATA["book_slips"] = int(need(r"MAX_SLIPS = (\d+)", sports, "MAX_SLIPS"))

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
    DATA["served_edge_product"] = float(need(
        r"SERVED_EDGE_PRODUCT = ([\d.]+)f", math, "SERVED_EDGE_PRODUCT"))
    DATA["served_edge_food"] = float(need(
        r"SERVED_EDGE_FOOD = ([\d.]+)f", math, "SERVED_EDGE_FOOD"))

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
    DATA["keeper_wage"] = int(need(r"KEEPER_WAGE = (\d+)", shops, "KEEPER_WAGE"))
    DATA["markups"] = ints("MARKUP", shops)
    DATA["rent"] = ints("RENT", homes)
    DATA["mood_leaving"] = int(need(r"MOOD_LEAVING = (\d+)", homes, "MOOD_LEAVING"))
    DATA["wage_multiple"] = int(need(r"WAGE_MULTIPLE = (\d+)", homes, "WAGE_MULTIPLE"))
    DATA["size_lift"] = float(need(r"SIZE_LIFT = ([\d.]+)f", homes, "SIZE_LIFT"))
    DATA["floor_per_head"] = int(need(r"FLOOR_PER_HEAD = (\d+)", homes, "FLOOR_PER_HEAD"))

    wards = java("TrapHospitals")
    DATA["ward_beds"] = int(need(r"MIN_BEDS = (\d+)", wards, "ward MIN_BEDS"))
    DATA["ward_floor"] = int(need(r"MIN_FLOOR = (\d+)", wards, "ward MIN_FLOOR"))
    DATA["ward_fee"] = int(need(r"FEE = (\d+)", wards, "ward FEE"))
    DATA["ward_clinic_off"] = float(need(r"CLINIC_OFF = ([\d.]+)f", wards, "CLINIC_OFF"))
    DATA["ward_stay"] = int(need(r"STAY_DAYS = (\d+)", wards, "STAY_DAYS"))
    DATA["ward_lost"] = int(need(r"LOST_DAYS = (\d+)", wards, "LOST_DAYS"))
    DATA["comfortable"] = int(need(r"COMFORTABLE = (\d+)", math, "COMFORTABLE"))

    # The second office. Read, never retyped -- the budget page quotes numbers
    # a council is expected to plan around, and a stale one costs somebody a
    # night's worth of burglaries they thought they had paid to prevent.
    police = java("TrapPolice")
    DATA["nick_cells"] = int(need(r"MIN_CELLS = (\d+)", police, "police MIN_CELLS"))
    DATA["nick_floor"] = int(need(r"MIN_FLOOR = (\d+)", police, "police MIN_FLOOR"))
    DATA["cop_wage"] = int(need(r"int WAGE = (\d+)", police, "police WAGE"))
    DATA["beat_step"] = int(need(r"BUDGET_STEP = (\d+)", police, "BUDGET_STEP"))
    DATA["beat_max"] = int(need(r"MAX_BUDGET = (\d+)", police, "MAX_BUDGET"))
    DATA["gear_at"] = int(need(r"GEAR_AT = (\d+)", police, "GEAR_AT"))
    DATA["top_gear"] = int(need(r"TOP_GEAR = (\d+)", police, "TOP_GEAR"))
    DATA["pocketful"] = int(need(r"LOOKS_AWAY = (\d+)", police, "police LOOKS_AWAY"))
    DATA["crime_base"] = float(need(r"CRIME_BASE = ([\d.]+)f", math, "CRIME_BASE"))
    DATA["crime_ceiling"] = float(need(r"CRIME_CEILING = ([\d.]+)f", math, "CRIME_CEILING"))
    DATA["crime_hardship_lift"] = float(need(
        r"CRIME_HARDSHIP_LIFT = ([\d.]+)f", math, "CRIME_HARDSHIP_LIFT"))
    DATA["crime_heat_lift"] = float(need(
        r"CRIME_HEAT_LIFT = ([\d.]+)f", math, "CRIME_HEAT_LIFT"))
    DATA["night_crime"] = float(need(r"NIGHT_CRIME = ([\d.]+)f", math, "NIGHT_CRIME"))
    DATA["top_deterrence"] = float(need(r"TOP_DETERRENCE = ([\d.]+)f", math, "TOP_DETERRENCE"))
    DATA["murder_fatal"] = float(need(r"MURDER_FATAL = ([\d.]+)f", math, "MURDER_FATAL"))
    DATA["hospitalised"] = float(need(r"HOSPITALISED = ([\d.]+)f", math, "HOSPITALISED"))
    DATA["suspect_pace"] = float(need(r"SUSPECT_PACE = ([\d.]+)", math, "SUSPECT_PACE"))
    DATA["officer_pace"] = float(need(r"OFFICER_PACE = ([\d.]+)", math, "OFFICER_PACE"))
    DATA["officer_gear_pace"] = float(need(
        r"OFFICER_PACE_PER_GEAR = ([\d.]+)", math, "OFFICER_PACE_PER_GEAR"))
    DATA["crimes"] = crime_kinds()
    DATA["income_rate"] = int(need(
        # Matched on the enum constant, not its display name: the display name
        # is player-facing prose and gets translated, and pinning the scrape to
        # it broke this script the day the mod went Polish.
        r'INCOME\("[^"]*", "[^"]*",\s*(\d+)', city, "the income duty's opening rate"))
    DATA["wage"] = int(need(r"int WAGE = (\d+)", crew, "WAGE"))
    DATA["max_hands"] = int(need(r"MAX_HANDS = (\d+)", crew, "MAX_HANDS"))

    # --- the poppy line and the habit ---------------------------------------
    # Same rule as everything above: read it, never retype it. The habit page
    # in particular quotes numbers a player is expected to plan around, and a
    # wiki quoting a retuned decay rate is worse than no wiki.
    poppy = java("PoppyCropBlock")
    scoring = java("ScoringTableBlock")
    pot = java("WashPotBlock")
    acet = java("AcetylatorBlock")
    drug = java("Drug")

    DATA["poppy_rolls"] = int(need(r"POPPY_GROWTH_ROLLS = (\d+)", math, "POPPY_GROWTH_ROLLS"))
    DATA["poppy_light"] = int(need(r"NEEDS_LIGHT = (\d+)", poppy, "NEEDS_LIGHT"))
    DATA["poppy_min"] = int(need(r"MIN_PODS = (\d+)", poppy, "MIN_PODS"))
    DATA["poppy_max"] = int(need(r"MAX_PODS = (\d+)", poppy, "MAX_PODS"))
    DATA["pods"] = int(need(r"PODS_PER_BATCH = (\d+)", scoring, "PODS_PER_BATCH"))
    DATA["opium"] = int(need(r"OPIUM_PER_BATCH = (\d+)", pot, "OPIUM_PER_BATCH"))
    DATA["lime"] = int(need(r"LIME_PER_BATCH = (\d+)", pot, "LIME_PER_BATCH"))
    DATA["ac_peak"] = int(need(r"int PEAK = (\d+)", acet, "acetylator PEAK"))
    DATA["ac_ruined"] = int(need(r"int RUINED = (\d+)", acet, "acetylator RUINED"))
    DATA["ac_grace"] = int(need(r"PEAK_GRACE = (\d+)", acet, "PEAK_GRACE"))

    DATA["drug_max"] = float(need(r"MAX = ([\d.]+)F", drug, "Drug MAX"))
    DATA["weed_hook"] = float(need(r"WEED_HOOK = ([\d.]+)F", drug, "WEED_HOOK"))
    DATA["weed_decay"] = float(need(r"WEED_DECAY = ([\d.]+)F", drug, "WEED_DECAY"))
    DATA["weed_period"] = int(need(r"WEED_PERIOD = (\d+)", drug, "WEED_PERIOD"))
    # The two refined lines carry their numbers on the enum row itself. Matched
    # as a whole row so a reordered argument can't silently swap decay for hook
    # -- which would read as a plausible table and be completely wrong.
    row = r'{}\("[^"]+", "[^"]+", 0x[0-9A-Fa-f]+, Formatting\.\w+,\s*' \
          r'([\d.]+)F,\s*([\d.]+)F,\s*(\d+),\s*([\d.]+)F\)'
    for key, const in (("coke", "COKE"), ("dope", "DOPE")):
        found = re.search(row.format(const), drug, re.S)
        if not found:
            raise SystemExit(f"  couldn't read the {const} row out of Drug.java")
        DATA[f"{key}_hook"] = float(found.group(1))
        DATA[f"{key}_decay"] = float(found.group(2))
        DATA[f"{key}_period"] = int(found.group(3))
        DATA[f"{key}_price"] = float(found.group(4))

    DATA["itch_at"] = float(need(r"HABIT_ITCH = ([\d.]+)f", math, "HABIT_ITCH"))
    DATA["crave_at"] = float(need(r"HABIT_CRAVE = ([\d.]+)f", math, "HABIT_CRAVE"))
    DATA["sick_at"] = float(need(r"HABIT_SICK = ([\d.]+)f", math, "HABIT_SICK"))

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
    # The wand shelf, read off the catalogue rather than typed: it is the one
    # shelf whose PRICE is the feature, so a page quoting a stale one is worse
    # than a page that never mentioned it.
    rack = re.findall(r'add\(c, "trapcraft:(\w+_wand)", 1, (\d+)\)', stock)
    wands = [int(p) for _, p in rack]
    # Grouped with a space, not a comma: these are the only five-figure prices
    # on the page and 120,000 reads as a decimal to a Polish eye.
    DATA["wand_low"] = f"{min(wands):,}".replace(",", " ")
    DATA["wand_high"] = f"{max(wands):,}".replace(",", " ")

    # The ladder on top of that shelf. Both multipliers and the tier count come
    # out of TrapMath and the two upgrade prices are worked out here the same
    # way WandItem works them out -- half the shelf, then all of it -- so the
    # page cannot quote a ladder the wands do not have. Names come from
    # gen_assets, which is where the items are named in the first place.
    speed = floats("WAND_SPEED", math)
    power = floats("WAND_POWER", math)
    DATA["wand_tiers"] = int(need(r"WAND_TIERS = (\d+)", math, "WAND_TIERS"))
    DATA["wand_faster"] = round((1 - speed[1]) * 100)
    DATA["wand_stronger"] = round((power[1] - 1) * 100)
    DATA["wand_faster_top"] = round((1 - speed[-1]) * 100)
    DATA["wand_stronger_top"] = round((power[-1] - 1) * 100)
    DATA["wand_rack"] = [
        (gen_assets.WANDS[name][0], int(price), int(price) // 2, int(price))
        for name, price in rack
    ]


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
    ("Wygrana, która oddaje mniej, niż włożyłeś, to przegrana w czapeczce imprezowej, "
     "a automat pełen takich to automat, na którym nikt nie zauważa, że przegrywa.",
     "TrapMath.java"),
    ("Nalot, który tylko macha toporami, to spawner mobów z dopisaną fabułą.",
     "TrapRaid.java"),
    ("Chodzi właśnie o pensję. Ekipa to pierwsza rzecz, jaką posiadasz, a która może "
     "kosztować cię pieniądze samym istnieniem.", "TrapCrew.java"),
    ("Towarzystwo jest kontrą, co czyni z tego mechanikę społeczną, a nie debuff "
     "dla samotnika.", "TrapParanoia.java"),
    ("Edytor cen to menu, a to ma być powód, żeby przejść przez całe miasto.",
     "TrapStalls.java"),
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


def mixes_possible() -> int:
    """Every mix the station accepts: n strains taken 2..4, repeats allowed."""
    n = len(DATA["strains"])
    return sum(math.comb(n + k - 1, k) for k in (2, 3, 4))


def blend_rows() -> str:
    out = []
    # Widest last: the table reads as the ladder the potencies actually are.
    for b in sorted(DATA["blends"], key=lambda b: (len(b["parts"]), b["name"])):
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
        tag = "krzyżówka" if s["hybrid"] else "naturalna"
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
        <div><dt>Czas efektu</dt><dd>{s['seconds']}s</dd></div>
        <div><dt>Siła</dt><dd>{'I' * s['intensity']}</dd></div>
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
    job_rows = [[esc(j["name"]), f'{j["cost"]}e' if j["cost"] else "za darmo",
                 f'+{j["wage"]}e',
                 f'<span class="dim">{esc(j["blurb"])} Potrzebuje: {esc(j["needs"])}.</span>']
                for j in d["jobs"]]
    heat_rows = []
    for i, t in enumerate(d["heat_thresholds"]):
        squad = f'{d["pillagers"][i]} grabieżców'
        if d["vindicators"][i]:
            squad += f', {d["vindicators"][i]} mścicieli'
        if d["ravagers"][i]:
            squad += f', {d["ravagers"][i]} niszczyciel'
        heat_rows.append([f"Próg {i + 1}", f"{t}+", squad])
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
        ("00", "start", "Na start"),
        ("01", "grow", "Uprawa"), ("02", "cure", "Suszenie i skręty"),
        ("03", "blends", "Mieszanki"), ("04", "high", "Efekty"),
        ("05", "coca", "Kokaina"),
        ("05b", "poppy", "Heroina"), ("05c", "habit", "Nałóg"),
        ("06", "market", "Rynek"),
        ("07", "stalls", "Stragany"), ("08", "city", "Miasto"),
        ("08b", "homes", "Mieszkania"),
        ("08c", "police", "Policja"),
        ("09", "crew", "Ekipa"),
        # "Naloty", not "Policja i naloty". That section is the pillager squad
        # that comes for a farm; §08c is the office the city pays. Two things
        # called police on one page is a page that answers neither question.
        ("10", "heat", "Naloty"), ("11", "street", "Ulica"),
        ("12", "casino", "Kasyno"), ("12b", "bets", "Zakłady"),
        ("13", "commands", "Komendy"),
        ("14", "awards", "Osiągnięcia"),
    ]
    nav = "".join(
        f'<a href="#{slug}"><span class="n">{num}</span>{esc(title)}</a>'
        for num, slug, title in nav_items)

    sections = []

    sections.append(section("00", "start", "Na start", "pierwsza godzina", f"""
    <p class="lede">Nasiona kupisz od wędrownych handlarzy — pięć szmaragdów za jedną
    odmianę — albo od rolników na poziomie czeladnika. Handlarze skupują też suszone
    szyszki i skręty, a to wystarczy, żeby sfinansować całą resztę.</p>
    <ol class="chain">
      <li><span class="step">01</span><strong>Zdobądź nasiona</strong><span class="dim">handlarz albo rolnik</span></li>
      <li><span class="step">02</span><strong>Posadź na podlanej roli</strong><span class="dim">światło i odkryte niebo podnoszą klasę</span></li>
      <li><span class="step">03</span><strong>Zbuduj suszarkę</strong><span class="dim">8 patyków, 2 nici</span></li>
      <li><span class="step">04</span><strong>Zwiń skręty albo sprzedaj susz</strong><span class="dim">+ papier</span></li>
      <li><span class="step">05</span><strong>Przeczytaj poradniki</strong><span class="dim">/guide</span></li>
    </ol>
    {craft_row([("drying_rack", "Suszarka"), ("mixing_station", "Mieszalnik"),
                ("market_stall", "Stragan"), ("ledger", "Spis skrzyń"),
                ("burner_phone", "Telefon na kartę"), ("leaf_press", "Prasa do liści")])}
    <p class="note">Najedź na kratkę, żeby zobaczyć, co się w niej znajduje. Wszystko
    tutaj da się wykraftować — nic w tym modzie nie jest tylko z trybu kreatywnego.</p>"""))

    sections.append(section("01", "grow", "Uprawa", "sześć odmian, cztery fazy", f"""
    <p class="lede">Nasiona sadzi się na zaoranej ziemi. Cztery fazy wzrostu, potem
    kliknij dojrzałą roślinę PPM z pustą ręką — dostaniesz szyszki, czasem nasiono,
    a roślina zostaje i odrasta.</p>
    <p>Trzy odmiany są naturalne i rosną z nasion. Pozostałe trzy powstają wyłącznie
    przez <strong>krzyżowanie</strong>: posadź dwie różne odmiany naturalne obok siebie,
    a dojrzała para czasem wypuści nasiono krzyżówki.</p>
    {strain_cards()}
    <h3 class="sub">Ile to trwa</h3>
    {table(["Podłoże", "Od nasiona do zbioru", "Dlaczego"], [
        ["Podlana zaorana ziemia", f"~{wet_min} min", '<span class="dim">wilgotność 7 pod rośliną</span>'],
        ["Sucha ziemia", f"~{dry_min} min", '<span class="dim">dwa razy wolniej i niższa klasa</span>'],
    ])}
    <p class="note">Woda to trzy punkty jakości <em>oraz</em> połowa czasu oczekiwania.
    Zawsze podlewaj.</p>
    <h3 class="sub">Jakość</h3>
    <p>Klasa ustala się w chwili zbioru, na podstawie warunków, w jakich roślina
    naprawdę rosła: nawodnienie, światło, odkryte niebo i to, czy przyspieszałeś
    ją mączką kostną.</p>
    {table(["Klasa", "Moc", "Za sztukę"], quality_rows)}"""))

    sections.append(section("02", "cure", "Suszenie i skręty", "cierpliwość, potem bibułka", f"""
    <p class="lede">Świeże szyszki są mało warte. Suszarka zamienia je w gotowy towar
    w mniej więcej {cure_min} minut, a moment zbioru to decyzja, nie zwykły timer.</p>
    {table(["Faza", "Co to znaczy"], [
        ["0", '<span class="dim">zupełnie mokre — nie da się zebrać</span>'],
        ["1–2", '<span class="dim">da się zebrać, ale tracisz klasę za każdą brakującą fazę</span>'],
        [str(d["ready"]), '<span class="acc">szczyt — pełna klasa i podwójny zbiór</span>'],
        [str(d["max_dryness"]), '<span class="dim">przesuszone, jedna klasa w dół</span>'],
    ])}
    <p>Szczyt ma długi zapas czasu, zanim towar się zepsuje. Masz o nim zapomnieć,
    a nie tracić go za odejście od komputera na chwilę.</p>
    <h3 class="sub">Mieszanki</h3>
    <p>Mieszalnik bierze od dwóch do czterech rodzajów suszu i robi z nich coś, co nie
    jest żadnym z nich. Sześć odmian brane po dwie do czterech daje
    <strong>{mixes_possible()} różnych przepisów</strong>, a {len(DATA["blends"])} z nich ma
    własną nazwę.
    Wrzucasz całe stacki; maszyna przerabia wszystko jednym kliknięciem.</p>
    <p class="note">Klasa idzie z <em>najgorszego</em> slotu. Uśrednianie pozwoliłoby
    jedną szyszką klasy Topowe przemycić trzy Słabe.</p>"""))


    sections.append(section("03", "blends", "Mieszanki",
                            f"{mixes_possible()} przepisów, {len(DATA['blends'])} z nazwą", f"""
    <p class="lede">Od dwóch do czterech rodzajów suszu wrzucasz do mieszalnika i wychodzi
    z niego coś, co nie jest żadnym z nich. Efekty to suma składników, każdy przeskalowany
    swoim udziałem — mieszanka w trzech czwartych z Kusha działa głównie jak Kush.</p>
    <p>Powtórki się liczą, więc dwa Kush i jeden Purp to nie to samo, co po jednym
    z każdego. Sześć odmian brane po dwie do czterech, bez znaczenia kolejności, daje
    <strong>{mixes_possible()} różnych przepisów</strong>. KAŻDY skład z samych różnych
    odmian ma nazwę, kolor i efekt, którego nie daje żaden ze składników — to te poniżej.
    Powtórzona odmiana zwykle daje mieszankę bezimienną:</p>
    {blend_rows()}
    <p class="note">Klasa mieszanki idzie z <em>najgorszego</em> slotu, a mieszalnik mówi
    ci to przed zatwierdzeniem. Zmieszanie klasy Topowe ze Słabymi da Słabe.</p>"""))

    sections.append(section("04", "high", "Efekty", "trzy własne efekty", f"""
    <p class="lede">Naćpany i Nakręcony to osobne efekty, z własnymi ikonami i własnymi
    zasadami — nie zlepek pożyczonych efektów pod nową nazwą.</p>
    <div class="cards">
      <div class="card reveal"><h4>Naćpany</h4><p>Zjada nasycenie, potem głód — czyli
      klasyczna gastrofaza. Powoli cię leczy, dopóki jesteś najedzony, i odbiera zdrowie,
      jeśli palisz na pusty żołądek. Czas trwania i poziom zależą od odmiany i klasy.</p></div>
      <div class="card reveal"><h4>Nakręcony</h4><p>Efekt linii kokainowej. Szybkość
      i odporność na dobry początek; zjazd na końcu to część, którą trzeba zaplanować.</p></div>
      <div class="card reveal"><h4>Tolerancja</h4><p>Rośnie w miarę palenia i tłumi
      wszystko, co przyjdzie potem, aż do poziomu {DATA['tol_max']}. Jeden poziom schodzi
      w około {DATA['tol_mins']} minut. Palenie własnego towaru bez przerwy się nie opłaca.</p></div>
    </div>
    <h3 class="sub">Sposoby zażycia</h3>
    {table(["Sposób", "Co robi"], [
        ["<strong>Skręt</strong>", '<span class="dim">szyszka + papier. Przenośny, i to jego kupują klienci.</span>'],
        ["<strong>Bong</strong>", '<span class="dim">raz wiadro wody, potem po jednej suszonej szyszce na wejście. Woda zostaje, szyszka nie.</span>'],
        ["<strong>Tłok</strong>", '<span class="dim">woda, szyszka, krzesiwo, potem pociągnij. Najmocniejsze wejście w modzie.</span>'],
    ])}
    {craft_row([("bong", "Bong"), ("gravity_bong", "Tłok"),
                ("joint_kush", "Skręt"), ("blend_joint", "Skręt mieszany")])}
    <p class="note">Przytrzymaj PPM, żeby zapalić. Animacja jest pełna — skręt idzie do ust,
    a wszyscy w pobliżu widzą dym.</p>"""))

    sections.append(section("05", "coca", "Kokaina", "dłuższa produkcja, lepszy zarobek", f"""
    <p class="lede">Krzak dojrzewa w około {coca_min} minut na dowolnej ziemi, byle było
    światło. Wartość nie leży w samej uprawie — tylko w tym, co dzieje się dalej.</p>
    <ol class="chain">
      <li><span class="step">01</span><strong>Krzak</strong><span class="dim">2–4 liście na zbiór</span></li>
      <li><span class="step">02</span><strong>Prasa do liści</strong><span class="dim">{d['leaves']} liści → pasta</span></li>
      <li><span class="step">03</span><strong>Rafineria</strong><span class="dim">pasta + płonący proszek</span></li>
      <li><span class="step">04</span><strong>Proszek</strong><span class="dim">czystość zależy od czasu</span></li>
    </ol>
    <p>Czystość zależy wyłącznie od tego, kiedy wyjmiesz towar. Za wcześnie — jest cięty,
    za późno — spala się.</p>
    {table(["Czystość", "Moc", "Za sztukę"], purity_rows)}
    <p class="note">Koka i konopie w jednym miejscu to
    <strong>{round((d['mixed_trade'] - 1) * 100)}% więcej uwagi policji</strong> niż obie
    osobno. Prasy i rafinerie liczą się tak samo jak rośliny. Dwie osobne szopy biją jedną.</p>"""))

    poppy_min = round(minutes(d["poppy_rolls"]) * 3)
    dope_rows = [
        [f'<span class="acc">{esc(g["name"])}</span>', f'{g["potency"]:.2f}x',
         f'{round(g["emeralds"] * d["dope_price"])}e']
        for g in DATA["purity"]]

    sections.append(section("05b", "poppy", "Heroina",
                            "trzy maszyny, a i tak można wszystko stracić", f"""
    <p class="lede">Najdłuższa produkcja. Mak dojrzewa około {poppy_min} minut — wolniej
    niż cokolwiek innego, co da się posadzić — i nie urośnie poniżej
    <strong>światła {d['poppy_light']}</strong>, więc w odróżnieniu od konopi nie ma tu
    uprawy w piwnicy. Najcenniejsza roślina to ta, której nie da się ukryć.</p>
    <ol class="chain">
      <li><span class="step">01</span><strong>Mak</strong><span class="dim">{d['poppy_min']}–{d['poppy_max']} makówek na zbiór, pełne światło</span></li>
      <li><span class="step">02</span><strong>Stół do nacinania</strong><span class="dim">{d['pods']} makówek → surowe opium</span></li>
      <li><span class="step">03</span><strong>Garnek</strong><span class="dim">{d['opium']} opium + {d['lime']} mączki kostnej, nad ogniem</span></li>
      <li><span class="step">04</span><strong>Acetylator</strong><span class="dim">baza + sfermentowane oko pająka</span></li>
      <li><span class="step">05</span><strong>Heroina</strong><span class="dim">czystość zależy od czasu, a partię łatwo stracić</span></li>
    </ol>
    <div class="cards">
      <div class="card reveal"><h4>Garnek nie ma własnego ognia</h4><p>Postępuje tylko wtedy,
      gdy coś pod nim płonie — ognisko, rozpalony piec, lawa. Jak zgaśnie, nic się nie psuje,
      ale też nic się nie dzieje. Ten krok to budowa laboratorium, nie kliknięcie.</p></div>
      <div class="card reveal"><h4>Acetylator potrafi cię pokonać</h4><p>Najgorsze, co może
      zrobić rafineria, to niska klasa. Tutaj najgorsze to pusta maszyna: jeden krok za szczytem
      i cała partia przepada — baza, kwas, makówki, wszystko. Masz
      <strong>{d['ac_grace']} kroków</strong> zapasu tam, gdzie rafineria daje pięć.</p></div>
    </div>
    {table(["Czystość", "Moc", "Za sztukę"], dope_rows)}
    <p>Mniej więcej <strong>{d['dope_price']:.0f}x</strong> tego, co daje proszek o tej samej
    czystości, przy dwa razy większym polu i trzy razy dłuższym czekaniu. Cena liczona
    z krzywej proszku, a nie wymyślona, żeby obie linie nie rozjechały się w arbitraż.</p>
    {craft_row([("scoring_table", "Stół do nacinania"), ("wash_pot", "Garnek"),
                ("acetylator", "Acetylator")])}
    <p class="note">Wąskim gardłem są nasiona: jedno na wizytę wędrownego handlarza za 26
    szmaragdów albo rzadki łup z posterunku, rezydencji lub bastionu. Roślina wysiewa się
    sama na tyle często, że wystarczy zdobyć jedno.</p>
    <h3 class="sub">Odlot</h3>
    <p>Trzecia forma haju w tym modzie i jedyna, której rachunek przychodzi gdzie indziej.
    Nic cię nie boli, regenerujesz zdrowie, nie chce ci się jeść — i przez cały czas nie
    możesz biegać, walczyć ani kopać. Weź drugą działkę, zanim zejdzie pierwsza, i masz
    przedawkowanie: nie zabije cię, ale zabierze kilka minut.</p>
    <p class="note">Trawa bierze zapłatę <em>od razu</em>, głodem. Koka <em>po fakcie</em>,
    zjazdem. Heroina <em>później</em> — patrz niżej.</p>"""))

    def clean_min(decay):
        return round(d["drug_max"] / decay)

    def to_max(hook):
        return round(d["drug_max"] / hook)

    habit_rows = [
        ["<strong>Trawa</strong>", f'{d["weed_hook"]:.1f}', f'{to_max(d["weed_hook"])}',
         f'{d["weed_period"]} min', f'{clean_min(d["weed_decay"])} min'],
        ["<strong>Kokaina</strong>", f'{d["coke_hook"]:.1f}', f'{to_max(d["coke_hook"])}',
         f'{d["coke_period"]} min', f'{clean_min(d["coke_decay"])} min'],
        ['<strong class="acc">Heroina</strong>', f'{d["dope_hook"]:.1f}',
         f'{to_max(d["dope_hook"])}', f'{d["dope_period"]} min',
         f'{clean_min(d["dope_decay"])} min'],
    ]

    sections.append(section("05c", "habit", "Nałóg",
                            "osobny licznik na odmianę, po obu stronach lady", f"""
    <p class="lede">Tolerancja odpowiada na pytanie "ile da mi następna działka" i schodzi
    w kilka minut. To jest odpowiedź na inne pytanie, takie, które przeżywa sesję:
    jak bardzo tego <em>potrzebujesz</em>. Dwa osobne liczniki, celowo.</p>
    <p>Każda odmiana ma własny licznik. Nałóg na Purp domaga się Purpa — szopa pełna Kusha
    nic nie da, przez co monokultura staje się problemem, a sześć odmian ma sens większy
    niż tylko lista efektów. Kokaina i heroina mają po jednym liczniku.</p>
    <h3 class="sub">Presja, nie odliczanie</h3>
    <p class="formula"><code>presja = (licznik ÷ {round(d['drug_max'])})
    × min(1, czas od ostatniej działki ÷ okres tej używki)</code></p>
    <p>Oba czynniki muszą być wysokie, żeby wynik był wysoki. Lekki nałóg nie tylko wolno
    zaczyna szkodzić — on <em>nie jest w stanie</em> tego zrobić, bo pierwszy czynnik
    ogranicza wynik poniżej progu, który musiałby osiągnąć. Na chorobę trzeba sobie
    zapracować.</p>
    {table(["Poziom", "Wymaga licznika", "Co robi"], [
        ['<span class="dim">Swędzi</span>', f'{round(d["itch_at"] * d["drug_max"])}+',
         '<span class="dim">tylko komunikaty, nic więcej</span>'],
        ['<span class="acc">Głód</span>', f'{round(d["crave_at"] * d["drug_max"])}+',
         '<span class="dim">przestają działać ci ręce</span>'],
        ['<strong>Odstawienie</strong>', f'{round(d["sick_at"] * d["drug_max"])}+',
         '<span class="dim">nic nie działa — a heroina dodatkowo odbiera zdrowie</span>'],
    ])}
    {table(["Używka", "Za działkę", "Działek do maksa", "Głód wraca po", "Czysty po"],
           habit_rows)}
    <p class="note">Mocniejsze klasy liczą się za więcej niż jedną działkę. Odstawienie nigdy
    nie zabija — przestaje szkodzić, zanim zdąży.</p>
    <h3 class="sub">Jak z tego wyjść</h3>
    <p>Tylko czas. Licznik spada sam, a spada
    <strong>dwa razy szybciej, kiedy naprawdę chorujesz</strong>, więc przetrzymanie
    najgorszego jest lekarstwem, a ciche podtrzymywanie małego nałogu to droga na skróty
    donikąd. Lek na nerwy wycisza objawy, nie ruszając licznika: sposób na przepracowanie
    popołudnia, a nie na wyjście z nałogu.</p>
    <p class="note">Wzięcie tego, czego ci brakuje, kasuje objawy natychmiast i daje premię —
    tym większą, im gorzej było. Im gorzej, tym lepiej działa działka. Na tym polega
    pułapka i tak ma być.</p>
    <h3 class="sub">Druga strona lady</h3>
    <p>NPC też się uzależniają — i to od <em>ciebie</em>. Każda transakcja buduje listę
    klientów, ważoną rodzajem towaru: heroina przesuwa ją jakieś osiem razy szybciej na
    sztukę niż skręt. Zyskujesz klientów, którzy podchodzą częściej, proszą konkretnie
    o mocny towar i biorą więcej za jednym razem; oraz lokatorów, którzy zaczynają pytać
    o działki zamiast o skręty.</p>
    <p class="note">Oba liczniki widać w <code>/addiction</code>. Przestaniesz sprzedawać
    i lista klientów zanika.</p>"""))

    sections.append(section("06", "market", "Rynek", "cennik, który żyje", f"""
    <p class="lede">{d['categories']} półek i ponad tysiąc pozycji, wycenianych przez trzy
    czynniki mnożone przez siebie — a każdy z nich rusza się z powodu, który sam wywołałeś.</p>
    <div class="cards">
      <div class="card reveal"><h4>Podaż pieniądza</h4><p>Ilość kasy w obiegu, liczona
      z tego, co gracze noszą przy sobie <em>oraz</em> co leży w ich skrzyniach. Każdy wydany
      szmaragd ją zmniejsza, każda wypłata zwiększa. Duża wygrana naprawdę napędza
      inflację.</p></div>
      <div class="card reveal"><h4>Dryf</h4><p>Każda pozycja idzie własną drogą między losowymi
      celami, wygładzoną tak, żeby docierała łagodnie. Wróć za minutę i miedź się przesunęła —
      w kierunku, w którym już szła. Do ±{round(d['drift'] * 100)}%.</p></div>
      <div class="card reveal"><h4>Twoje transakcje</h4><p>Kupowanie podbija cenę, sprzedawanie
      ją zbija, a efekt zanika przez kolejne minuty. Wykup całą półkę, a ostatnia sztuka będzie
      droższa od pierwszej.</p></div>
    </div>
    <p>Ceny nigdy nie spadną poniżej <strong>{d['index_min']:.2f}×</strong> ani nie przekroczą
    <strong>{d['index_max']:.2f}×</strong> normy — a "norma" to to, jak wyglądało kilka
    ostatnich godzin, a nie pierwszy dzień. Dobry tydzień staje się nową normą, zamiast
    zostawiać wszystko drogie na zawsze.</p>
    <p class="note">Lada odkupuje po {round(d['sell_rate'] * 100)}% — celowo z dużym spreadem.
    Sklep NPC to wygoda, nie źródło dochodu.</p>
    <p>Na samym końcu półek stoją <strong>różdżki</strong>: pięć sztuk od
    {d["wand_low"]}e do {d["wand_high"]}e — pęd i przeskok, żniwa całego pola,
    podświetlone rudy, dokładanie bloków i piorun na zawołanie. Wszystko inne na rynku
    da się zdobyć samemu; to jest ta półka, na którą się zbiera.</p>
    <p class="note">Różdżek lada nie odkupuje za żadne pieniądze. Da się je też
    wykuć — z jednej do trzech rzeczy, po które trzeba się bić.</p>
    <h3>Poziomy różdżek</h3>
    <p>Ta z półki to dopiero pierwszy z <strong>{d['wand_tiers']}</strong> poziomów.
    Kucnij i kliknij prawym w <strong>stół zaklęć</strong> trzymając różdżkę, a podniesie
    się o jeden — za emki, prosto z kieszeni albo z portfela. Każdy poziom to
    <strong>−{d['wand_faster']}%</strong> czasu odnowienia i <strong>+{d['wand_stronger']}%</strong>
    zasięgu, promienia albo obrażeń, więc III odnawia się o {d['wand_faster_top']}% szybciej
    i sięga o {d['wand_stronger_top']}% dalej niż ta ze sklepu.</p>
    {table(["Różdżka", "Półka", "II", "III"],
           [[esc(name), f"{shelf:,}e".replace(",", " "),
             f'<span class="acc">{two:,}e</span>'.replace(",", " "),
             f'<span class="acc">{three:,}e</span>'.replace(",", " ")]
            for name, shelf, two, three in d["wand_rack"]])}
    <p class="note">Pełna drabinka kosztuje półtora raza tyle, co sama różdżka — i to
    jedyne miejsce na rynku, gdzie takie pieniądze naprawdę znikają z obiegu.
    Różdżka Burz jako jedyna nie zyskuje zasięgu, tylko siłę: piorun z czterdziestu
    bloków przez ścianę i tak jest na granicy.</p>
    <p class="note">Ulepszenie nie zadziała, dopóki różdżka stygnie po użyciu — gra nie
    pyta wtedy przedmiotu o nic. Kliknij ponownie, gdy zegar zejdzie.</p>"""))

    sections.append(section("07", "stalls", "Stragany", "powód, żeby przejść przez miasto", f"""
    <p class="lede">Stragan, który postawisz, należy do ciebie. Postaw pod nim skrzynię,
    a wszystko w jej środku trafia na sprzedaż dla innych graczy po
    {round(d['stall_rate'] * 100)}% ceny rynkowej.</p>
    {table(["Sposób", "Sprzedający dostaje", "Kupujący płaci", "Przepada"], [
        ["Lada NPC", f"{round(100 * d['sell_rate'])}e", "100e",
         '<span class="warn">55e w powietrze</span>'],
        ["Stragan", f'<span class="acc">{round(100 * d["stall_rate"] * (1 - d["stall_fee"]))}e</span>',
         f'<span class="acc">{round(100 * d["stall_rate"])}e</span>',
         f'{round(100 * d["stall_rate"] * d["stall_fee"])}e opłaty za miejsce'],
    ])}
    <p>Obie strony wychodzą lepiej niż na ladzie i żadna nie traci na rzecz drugiej —
    marża, która wcześniej znikała, dzieli się między was. <strong>Nie ma edytora cen</strong>:
    ceny idą za rynkiem, więc stragan nigdy nie jest nieaktualny, a jedyna decyzja to,
    co w nim trzymać.</p>
    <p class="note">Ekran rynku mówi ci, kiedy sąsiad ma coś taniej — z nazwą i koordynatami.
    <code>/stalls</code> pokazuje wszystkie.</p>"""))

    sections.append(section("08", "city", "Miasto", "kasa publiczna", f"""
    <p class="lede">Nic nie jest opodatkowane, dopóki ktoś nie zrobi i nie postawi
    <strong>skarbca miasta</strong>. Bez niego nie da się też zarejestrować domu — nie ma
    komu. Postaw go i obie rzeczy ruszają, na całym serwerze, z ogłoszeniem na czacie.</p>
    <p>Jest jeden skarbiec i jedna wspólna kasa. Trafia do niej każdy zapłacony podatek i
    <strong>wypłacić z niej może każdy</strong> — każda wypłata jest ogłaszana wszystkim na
    serwerze. To decyzja projektowa, nie przeoczenie: trzech znajomych ustali w dziesięć
    sekund, na co idzie kasa, a interfejs do głosowania byłby menu postawionym tam, gdzie
    powinna być rozmowa.</p>
    <p class="note">Rozbicie skarbca niczego nie wydaje. Pieniądze są w księgach miasta, nie
    w bloku — po prostu nikt nie ma do nich dostępu i nie da się zarejestrować domu, dopóki
    skarbiec nie stanie z powrotem.</p>
    <h3 class="sub">Co jest opodatkowane</h3>
    {table(["Podatek", "Od czego", "Start", "Widełki"], [
        [esc(x["name"]), f'<span class="dim">{esc(x["blurb"])}</span>',
         f'{x["start"]}%', f'{x["floor"]}–{x["ceiling"]}%'] for x in d["duties"]])}
    <p>Kupując, płacisz podatek doliczony do ceny półkowej — a cena, którą widzisz, już go
    zawiera, zarówno przy ladzie, jak i na cudzym straganie. Z każdej wypłaty potrącany jest
    podatek dochodowy. Każdy zakład postawiony w kasynie płaci podatek od gier, niezależnie
    od wyniku, i tylko tego nie da się ominąć szczęśliwym wieczorem.</p>
    <p class="note"><strong>Sprzedaż klientom z ulicy i dilerom nie jest opodatkowana wcale.</strong>
    To też nie jest przeoczenie — czarny rynek płaci lepiej na godzinę właśnie dlatego, że
    nie płaci nikomu nic, i to jest problem wart posiadania.</p>
    <h3 class="sub">Wpłacanie do kasy</h3>
    <p>Skarbiec był przez długi czas kranem działającym w jedną stronę: dało się z niego
    <em>brać</em>, a jedyne, co go zasilało, to podatki ściągane z transakcji zrobionych przez
    kogoś innego. Inwestycje miejskie stały więc za kasą, która napełniała się kilkoma
    procentami cudzych wypłat.</p>
    <p>Przycisk <strong>Wpłać</strong> jest obok kasy. Kliknięcie wrzuca porcję, PPM wrzuca
    wszystko, co masz przy sobie. To najkrótsza droga między skrzynią pełną szmaragdów
    a miastem, w którym warto mieszkać.</p>
    <h3 class="sub">Opłaty stałe</h3>
    <p>Posiadanie kosztuje codziennie, z twojej kieszeni do wspólnej kasy:
    <strong>{d['shop_rate']}e za sklep</strong> i <strong>{d['house_rate']}e za każdą klasę</strong>
    każdego wynajmowanego domu. Sklep i wynajęty dom były kiedyś jedynymi biznesami w grze
    zupełnie bez kosztów — kasyno płaci za utrzymanie, ekipa chce pensji, a właściciel
    mieszkań po prostu bogacił się każdego ranka bez końca.</p>
    <p class="note">Tylko kiedy jesteś online i zawsze najwyżej za jeden dzień. Tydzień
    nieobecności to jeden dzień do zapłaty, a nie dług.</p>
    <h3 class="sub">Na co idzie kasa miasta</h3>
    <p>Skarbiec bez odpływu to tylko tablica wyników. Każda inwestycja ma do
    <strong>{d['top_tier']} poziomów</strong>, każdy kosztuje {d['tier_step']}× więcej od
    poprzedniego, a inwestycja działa dalej, kiedy zbierasz na kolejny poziom. Kupić może
    każdy, przy skarbcu, i wszyscy dostają info — ta sama zasada co przy wypłacie i z tego
    samego powodu.</p>
    {table(["Inwestycja", "Co daje", "Koszt"], [
        [esc(w["name"]), f'<span class="dim">{esc(w["blurb"])}</span>',
         f'{w["cost"]}e, potem ×{d["tier_step"]}']
        for w in d["works"]])}
    <p class="note">Każda z nich ma sens tylko dla <em>miasta</em>. Drogi to jedyna rzecz
    w grze, która nagradza budowanie blisko siebie; straż miejska to odpowiedź miasta na to,
    co czyni plantacje niebezpiecznymi; giełda płaci wszystkim, także temu, kto nigdy nie
    wychodzi ze swojej farmy; a szkoła, przychodnia i tramwaje sprawiają, że ludzie mieszkający
    w twoich domach są warci więcej — lepiej zarabiają, dłużej znoszą złe warunki i więcej
    z nich naraz chodzi na zakupy.</p>
    <h3 class="sub">Sklepy, do których przychodzi miasto</h3>
    <p><strong>Półka sklepowa</strong> to lada, którą zapełniasz jak skrzynię — kliknij PPM
    własną półkę i włóż towar. Sprzedaje to, co na niej leży, i nie tylko graczom:
    mieszkańcy wychodzą z domów, idą do budynku, biorą partię z półki i płacą
    <strong>{round(d['retail'] * 100)}%</strong> ceny rynkowej, czyli mniej więcej dwa razy
    tyle, co daje lada NPC za tę samą skrzynkę. Podatek od sprzedaży idzie prosto do kasy
    miasta.</p>
    <p><strong>Kasa sklepowa</strong> to jest sklep. Postaw ją, a każda półka w promieniu
    {d['shop_reach']} bloków podłącza się sama — bez różdżki, bez łączenia; półka po prostu
    należy do najbliższej kasy. Jedna nazwa, jedna polityka cenowa, jedna kasa na cały
    budynek, a towar to wszystko, co leży na półkach — plus dowolna skrzynia lub beczka pod
    kasą <em>albo</em> pod którąkolwiek z półek, więc zaplecze i zapełniona lada działają
    tak samo.</p>
    <p>Ceny ustalasz sam: od {min(d['markups'])}% do {max(d['markups'])}% tego, ile miasto
    spodziewa się zapłacić. Tanio przyciąga więcej ludzi, drogo zabiera więcej od każdego.
    Otwarcie kasy wypłaca ci utarg.</p>
    <h3 class="sub">Ktoś za ladą</h3>
    <p>Przy kasie możesz nająć <strong>sprzedawcę</strong> za {d['keeper_wage']}e dziennie,
    płatne z utargu. Stoi za ladą, sklep przyciąga dużo więcej klientów, a — i to jest
    prawdziwy powód — <strong>handluje dalej, kiedy jesteś gdziekolwiek na serwerze</strong>,
    a nie tylko wtedy, gdy akurat stoisz obok.</p>
    <p class="note">Ale nie po twoim wylogowaniu. Sklep trzymający swój chunk wczytany na
    stałe byłby chunk loaderem za {d['keeper_wage']}e dziennie, a serwer pełen takich to
    czyjś budżet tickowy. Ekipa działa tak samo i z tego samego powodu.</p>
    <p class="note">Jeśli kasa nie ma na pensję, sprzedawca odchodzi i dostajesz o tym info.
    Sprzedawcę opłaca sklep, nie twoja kieszeń, więc sklep, który nic nie sprzedaje, nie
    będzie cię po cichu drenował.</p>
    <h3 class="sub">Towar spod lady, legalnie</h3>
    <p>Półki sprzedają też <strong>skręty, susz i proszek</strong> obok zwykłych zakupów — po
    {round(d['legal_rate'] * 100)}% ceny ulicznej, ale <strong>na czysto</strong>: płatne
    prawdziwymi szmaragdami, zgłoszone, opodatkowane i nikt nie zbiera za to uwagi policji.
    Na ulicy dostaniesz o połowę więcej, ale brudną kasą, którą trzeba przepuścić przez
    pralnię. Najbezpieczniejsze pieniądze to te najwolniejsze.</p>
    <p><strong>Liczba klientów to liczba mieszkańców</strong> — suma klas twoich domów.
    Pętla się domyka: domy dają ludzi, ludzie robią zakupy, zakupy płacą rolnikowi i miastu,
    a kasa miasta finansuje rozbudowę. Nikomu nie trzeba mówić, żeby budował domy — mówi mu
    to sklep. <code>/shops</code> pokazuje wszystkie sklepy i liczbę mieszkańców.</p>
    <p class="note">Mieszkańcy kupują jedzenie znacznie częściej niż cokolwiek innego, co jest
    zarówno prawdą, jak i powodem, dla którego to wszystko jest zbudowane pod kogoś, kto
    zajmuje się uprawą.</p>
    <h3 class="sub">Urząd skarbowy</h3>
    <p>Wszystko legalne płaci podatek, a wszystko nielegalne nie płaci nic, co samo w sobie
    znaczy tylko tyle, że narkotyki są lepsze. Urząd to druga strona tego równania:
    porównuje wpływy z tym, co zgłosiłeś, i powyżej <strong>{d['looks_away']}e dziennie bez
    pokrycia</strong> nalicza ci {round(d['assessment'] * 100)}% nadwyżki. Nie masz czym
    zapłacić? Dług zostaje, a policja ma cię na oku, dopóki go nie uregulujesz —
    <code>/law pay</code>.</p>
    <h3 class="sub">Brudne pieniądze</h3>
    <p>Ulica nie płaci szmaragdami. Klienci i dilerzy płacą <strong>brudnymi
    szmaragdami</strong> — to przedmiot, nie saldo. Żaden sklep ich nie przyjmie, nie zapłacisz
    nimi pensji, a rynek nie wie, że istnieją. To jeszcze nie są pieniądze.</p>
    <p>Stackują się i pakują jak prawdziwe: <strong>dziewięć na blok i z powrotem</strong>,
    a duża wypłata przychodzi od razu w blokach. Uciążliwość brudnej kasy ma polegać na tym,
    że trzeba ją wyprać, a nie na tym, że tydzień pracy to szesnaście stacków do niesienia.
    Bęben przyjmuje też bloki, każdy liczony za dziewięć.</p>
    <p>Pieniędzmi stają się w <strong>bębnie pralniczym</strong>: kliknij go PPM, trzymając je
    w ręce, minimum {d['wash_min']}, maksimum <strong>{d['wash_max']}</strong> na wsad, potem
    czekaj — {d['wash_each'] / 20:g} sekundy na szmaragd, czyli około
    {d['wash_max'] * d['wash_each'] // 1200} minut na pełny bęben. Wyjmujesz czyste szmaragdy,
    z których <strong>do {round(d['wash_cut'] * 100)}%</strong> przepadło: prowizja jest
    losowana, więc nigdy nie wiesz dokładnie ile. Dopiero w tym momencie te szmaragdy w ogóle
    trafiają do obiegu pieniądza.</p>
    <p class="note">Czas liczy się na szmaragd, a nie na wsad, więc bęben to przepustowość,
    a nie darmowy mnożnik — a dorzucanie w trakcie zeruje licznik, więc wrzuć wszystko naraz
    i odejdź. Jak jeden nie wystarcza, postaw drugi.</p>
    <p>Pranie zmniejsza też dzienną ekspozycję wobec urzędu — ale tylko do wysokości
    <strong>tego, co twoje biznesy realistycznie mogły utargować</strong>. Sklep, który nic
    nie sprzedał, niczego nie wyjaśnia, choćby właściciel bardzo chciał. Prawdziwy biznes
    jest licencją na pranie, a jego wielkość jest limitem — dlatego półka sklepowa i sala
    kasyna są warte posiadania z innego powodu niż to, ile same zarabiają.</p>
    <h3 class="sub">Ustawy</h3>
    <p>Rada uchwala prawo, kiedy miasto tego potrzebuje, i uchyla, kiedy potrzeba mija.
    Zawsze w reakcji, nigdy losowo — przepis, który pojawia się bez powodu, to pogoda,
    a wokół pogody nikt nie planuje. <code>/law</code> daje ci konstytucję, spisaną na
    świeżo w chwili, gdy o nią poprosisz.</p>
    {table(["Ustawa", "Wchodzi w życie, gdy"], [[esc(a["name"]), f'<span class="dim">{esc(a["blurb"])}</span>']
                                    for a in d["acts"]])}
    <h3 class="sub">Budżet</h3>
    <p>Stawki zmieniają się same co {d['budget_days']} dni, a zmiana jest ogłaszana wraz
    z powodem. Poniżej {d['broke']}e w kasie wszystko idzie w górę; powyżej {d['flush']}e
    wszystko schodzi w dół; w innym razie każda stawka błądzi o punkt w obie strony wewnątrz
    swoich widełek. <code>/city</code> wypisuje aktualną tabelę i to, ile zebrał każdy
    podatek.</p>"""))

    sections.append(section("08b", "homes", "Mieszkania", "pokój, który miasto widzi", f"""
    <p class="lede">Zrób skrzynkę pocztową, postaw ją raz <strong>w środku</strong> pokoju
    i kliknij PPM. Obejdzie ściany i powie ci, co zbudowałeś — a jeśli przejdzie kontrolę,
    ten pokój staje się adresem.</p>
    <p>Potem skrzynka należy <strong>na zewnątrz</strong>. <strong>Kucnij i kliknij PPM
    pustą ręką</strong>, a trafi ci do ręki razem z adresem, więc możesz przybić ją przy
    drzwiach albo na ulicy. Rozbicie jej też działa. Pomiar zostaje przypięty do miejsca,
    w którym został zrobiony po raz pierwszy; skrzynka to tylko miejsce, gdzie trafia poczta.</p>
    <p class="note">Skrzynka, która zgubiła adres, to żaden problem: postaw pustą z powrotem
    w domu, a przejmie zadanie, albo postaw jedną na zewnątrz, a obsłuży twój najbliższy dom
    bez poczty. <code>/homes demolish</code> zdejmuje z rejestru dom, w którym stoisz.</p>
    <h3 class="sub">Szczelny znaczy szczelny</h3>
    <p><strong>Drzwi liczą się jak ściany</strong> — i właśnie dlatego sypialnia z zamkniętymi
    drzwiami nadal należy do twojego domu: każde drzwi na granicy są sprawdzane osobno,
    a te prowadzące do czegoś małego to kolejny pokój, podczas gdy te prowadzące na świat
    to drzwi wejściowe. Schody i drabiny sprawiają, że piętro działa bez żadnych dodatkowych
    zabiegów.</p>
    <p class="note">Zamknięta pustka bez wejścia nie jest domem, więc zamurowanie jaskini,
    żeby sztucznie zawyżyć metraż, nic nie daje.</p>
    <h3 class="sub">Pięć wymogów</h3>
    <p>Brak choćby jednego i to w ogóle nie jest dom, cokolwiek innego w nim stoi:
    szczelny · {d['min_floor']} kratek podłogi · łóżko · drzwi na ulicę · światło.</p>
    <h3 class="sub">Metraż to sufit, nie bonus</h3>
    <p>To najważniejsza rzecz do zapamiętania. Powierzchnia nie daje punktów — ustala
    <strong>najwyższą klasę, na jaką dom może się załapać</strong>, i nic innego tego nie
    podniesie. Komórka z łóżkiem, stołem, skrzynią, piecem i pochodnią to klasa 1, choćby
    była wykończona idealnie.</p>
    {table(["Podłoga", "Najwyższa możliwa klasa"], [
        [f"{d['floor_steps'][i]}+ kratek", str(i + 1)] for i in range(len(d['floor_steps']))
    ])}
    <p>Podłoga to kratki, na których dałoby się stanąć, więc <strong>liczy się każde
    piętro</strong>, a wysoki sufit liczy się raz. Trzy piętra skromnego domku dojdą tam
    równie pewnie, co jedna wielka hala.</p>
    <h3 class="sub">Potem liczą się punkty</h3>
    {table(["Punkty", "Za co"], [
        ["0–2", f"zbudowane, nie wykopane — {round(d['shell_steps'][0] * 100)}%, potem "
                f"{round(d['shell_steps'][1] * 100)}% ścian z obrobionych materiałów"],
        ["0–3", f"wyposażenie — stół rzemieślniczy, skrzynia, piec, stragan i "
                f"okno; dwa dają punkt, cztery dwa, wszystkie {d['fittings']} dają trzy"],
        ["0–2", f"wystrój — {d['decor_steps'][0]}, potem {d['decor_steps'][1]} różnych "
                f"rodzajów bloków w środku"],
        ["0–2", f"oświetlenie — mierzone na wysokości głowy, jaśniej niż {d['dark_at']}; "
                f"jedna piąta ciemnej podłogi daje punkt, jedna dwudziesta dwa"],
    ])}
    <p>Każde dwa punkty to jedna klasa, do <strong>{d['top_tier']}</strong> — a potem
    metraż i tak ustala sufit. Skrzynka zawsze mówi ci jedną konkretną rzecz do zrobienia,
    więc nigdy nie musisz czytać tej tabeli.</p>
    <p class="note">Ziemia, piasek, żwir, goły kamień, bruk, kłody i liście to rzeczy, które
    daje świat, i liczą się jako wykopane. Wszystko, co wykraftowałeś, wytopiłeś, przyciąłeś
    albo zabarwiłeś, liczy się jako zbudowane — łącznie z tym, co inne mody dają jako ozdoby.</p>
    <p class="note">Dwa domy nie mogą dzielić tego samego miejsca — mieszkania obok siebie są
    OK, jedno nad drugim też. Dom sięga najwyżej {d['span']} bloków od swojej skrzynki
    i mierzy się od nowa co kilka minut, więc wybicie ściany albo zabranie łóżka wychodzi na
    jaw samo. <code>/homes</code> pokazuje domy wszystkich graczy.</p>
    <h3 class="sub">Ilu tam mieszka</h3>
    <p>W domu może mieszkać więcej niż jedna osoba. Decydują trzy rzeczy, a
    <strong>wygrywa najmniejsza z nich</strong>:</p>
    <p>jedno łóżko na osobę · {d['floor_per_head']} kratek podłogi na osobę · odpowiednio
    wysoka klasa.</p>
    <p>Czwarte łóżko w małym pokoju klasy 2 nie da ci więc nikogo więcej — potrzeba też
    miejsca i klasy. Jedna osoba zmieści się zawsze, jakkolwiek marne byłoby to lokum.</p>
    <p class="note">To ważne, bo wszystko inne liczy <em>ludzi</em>, a nie domy. Każdy klient
    przy twojej kasie, każdy gracz na sali twojego kasyna i każdy szmaragd podatku dochodowego
    liczy się na osobę. Dom na cztery łóżka to czterech ludzi wchodzących do twojego sklepu.</p>

    <h3 class="sub">Wypłata</h3>
    <p>Twoi lokatorzy chodzą do pracy i raz dziennie dostają wypłatę, zależną od klasy domu,
    w którym mieszkają. <strong>To jedyny sposób, w jaki do miasta trafiają nowe pieniądze.</strong></p>
    <p>Miasto najpierw pobiera podatek od ich pensji — na start {d['income_rate']}% — i ten
    trafia do skarbca. Reszta idzie do <strong>portfela mieszkańców</strong>.</p>
    <p>Czynsz jest potem płacony <em>z tego portfela</em> do twojej skrzynki, tego samego dnia.
    Pensja jest więc zakotwiczona w tabeli czynszów — <strong>{d['wage_multiple']}× czynsz</strong>
    na dolnym progu każdej klasy — co gwarantuje, że lokator zawsze spłaci właściciela i zostanie
    mu jeszcze coś do wydania.</p>
    <h3 class="sub">Metraż też się liczy</h3>
    <p>Klasa ustala podstawę. Do tego <strong>większy dom tej samej klasy jest wart więcej niż
    mniejszy</strong>.</p>
    <p>Bez tego metraż liczyłby się tylko skokowo: {d['floor_steps'][3]} kratek podłogi daje
    klasę 4 i {d['floor_steps'][4] - 1} też, więc ostatnie sześćdziesiąt bloków, które położyłeś,
    nikomu by nic nie dało.</p>
    <p>Dom na górnej granicy swojej klasy zarabia <strong>w połowie drogi do następnej
    klasy</strong> — nigdy całej, więc awans klasy zawsze bije samo poszerzanie. Dokładanie
    podłogi nigdy nie sprawi, że ktoś zarobi mniej.</p>
    <p><strong>Czynsz rośnie tak samo.</strong> Czynsz i pensja to ta sama liczba widziana dwa
    razy: ile lokator dostaje i ile oddaje ci za pokój. Duży dom daje więc zarówno wyższą pensję
    lokatorowi, jak i wyższy czynsz tobie. Na lokatora, na dzień:</p>
    {table(["Klasa", "Podłoga", "Zarabia (mały → duży)", "Czynsz dla ciebie (mały → duży)"],
           [[str(i),
             f"{d['floor_steps'][i - 1]}–{band_top(i) - 1}" if i < d['top_tier']
             else f"{d['floor_steps'][i - 1]}+",
             f"{wage_at(i, d['floor_steps'][i - 1])}e → "
             f"{wage_at(i, band_top(i) if i >= d['top_tier'] else band_top(i) - 1)}e",
             f"{rent_at(i, d['floor_steps'][i - 1])}e → "
             f"{rent_at(i, band_top(i) if i >= d['top_tier'] else band_top(i) - 1)}e"]
            for i in range(1, len(d['rent']))])}
    <p class="note">Najwyższa klasa nie ma nad sobą kolejnej, do której mogłaby dążyć, więc
    dostaje jeszcze jeden zakres własnej szerokości — pałac zarabia więcej niż rezydencja,
    a powyżej {band_top(d['top_tier'])} kratek trafiasz na sufit całej drabinki.</p>
    <p class="note">Większy dom mieści też więcej ludzi, więc metraż płaci dwa razy: suma dla
    gospodarstwa to ta liczba razy liczba lokatorów.</p>
    <p class="note">Czynsz odbierasz, otwierając skrzynkę pocztową — nie ma drugiej rzeczy do
    klikania. Lokator, który z jakiegoś powodu nie ma na czynsz, nie płaci <em>nic</em>, a nie
    część; to spadek nastroju opisany niżej ostatecznie go wyprowadza.</p>

    <h3 class="sub">Portfel mieszkańców — dlaczego to twoja sprawa</h3>
    <p>Ta reszta pieniędzy to właśnie to, czym płacą twoje sklepy i kasyna. <strong>Miasto może
    wydać tylko tyle, ile zarobiło.</strong> Jeśli portfel jest pusty, ludzie siedzą w domach —
    mniej klientów przy twoich półkach i mniej graczy przy twoich automatach.</p>
    <p>Liczy się to, ile przypada <em>na osobę</em>. Około {d['comfortable']}e na głowę oznacza
    miasto, które robi zakupy normalnie, a więcej niż tyle — że kupuje ostrzej, do pewnego
    limitu. Dwadzieścia osób dzielących ten portfel ma się dobrze; dwieście osób dzielących ten
    sam portfel już nie.</p>
    <p>Całość jest więc pętlą:</p>
    <p class="note"><strong>lepsze domy → lepiej opłacani lokatorzy → więcej klientów w twoim
    sklepie → więcej podatku w skarbcu → inwestycje miejskie → lepsze miasto.</strong> Buduj
    dobrze, a zarobisz trzy razy: na czynszu, na podatku, który finansuje miasto, i na
    pieniądzach, które ci ludzie zostawiają u ciebie. <code>/city</code> pokazuje stan kasy.</p>
    <h3 class="sub">Nastrój i listy</h3>
    <p>Lokatorzy mają nastrój w skali do 100. Ciemne kąty i spadająca klasa go obniżają, a
    <strong>niezadowolony lokator najpierw płaci mniej, a dopiero potem przestaje płacić</strong>
    — spadek widać więc w pieniądzach, zanim zobaczysz go jako pusty dom. Poniżej
    {d['mood_leaving']} zaczyna się pakować.</p>
    <p>Piszą do ciebie, a listy leżą w skrzynce: <em>"Zgasło światło na korytarzu."</em>
    <em>"Obok coś rośnie. Czuć to w powietrzu."</em> To jest cały samouczek tego systemu
    i nie potrzebował ani jednej strony instrukcji.</p>
    <h3 class="sub">Są też twoimi klientami</h3>
    <p>Kliknij lokatora PPM <strong>z pustą ręką</strong>, a powie ci, na co ma ochotę — skręty
    konkretnej odmiany, susz albo proszek — i ile za to zapłaci. Weź to do ręki, kliknij go
    ponownie, a kupi, płacąc <strong>brudnymi szmaragdami</strong>, dokładnie jak klient z ulicy.
    Ochota i cena losują się na nowo prawie każdego dnia, a ktoś, komu się dobrze mieszka, płaci
    trochę więcej.</p>
    <p>Grają też w kasynie. Lokator mieszkający blisko podłączonego automatu wchodzi i gra na
    nim zamiast obcego pojawiającego się znikąd — ta sama osoba, która płaci ci czynsz i wraca
    potem do domu. To argument, żeby budować kasyno tam, gdzie ludzie faktycznie mieszkają. Ich
    stawka pochodzi z tego samego portfela, do którego trafiła ich pensja, a wygrana do niego
    wraca.</p>
    <p>I chodzą do pracy. Mniej więcej co trzeci mieszkaniec, którego widzisz, idzie do pracy,
    a nie do sklepu — do kasy sklepowej, na stragan, na salę kasyna, do skarbca. Miejsca pracy
    w mieście to to, co faktycznie zostało zbudowane, więc wioska z samych domów nie ma nikogo
    dojeżdżającego. To scenografia: pensja i tak została wypłacona z rejestru mieszkań,
    niezależnie od tego, czy ktoś stał i patrzył.</p>
    <h3 class="sub">Nie po sąsiedzku</h3>
    <p>Uprawa w zasięgu skanowania czyjegoś salonu wyludnia go. Mała zostawia lokatorów
    nieszczęśliwymi i płacącymi dwie piąte czynszu; cokolwiek większego i się wyprowadzają.
    <strong>Plantacja i blok mieszkalny nie mogą być w tym samym miejscu</strong> — na tym
    napięciu zbudowany jest cały projekt miasta i to jedyna rzecz w modzie, która sprawia,
    że obie jego połowy kłócą się o ten sam teren.</p>

    <h3 class="sub">Kiedy któregoś ugryzą</h3>
    <p>Zombie, które wejdzie przez okno, zamienia lokatora w zombie. Ta osoba jest wtedy
    <strong>chora</strong>: nic nie zarabia, nie płaci czynszu i nie robi zakupów — gospodarstwu
    brakuje jednej pensji, dopóki nie wyzdrowieje. Twoja skrzynka mówi kto i na jak długo.</p>
    <p>Zrób <strong>szpital</strong> i postaw go w budynku, dokładnie tak jak stawiasz skrzynkę
    w pokoju. Kliknij, a obejdzie ściany i oceni miejsce. Oddział wymaga więcej niż dom:</p>
    <p>{d['ward_beds']} łóżka · {d['ward_floor']} kratek podłogi · szczelny, z drzwiami ·
    <strong>ani jednego ciemnego kąta</strong> · {round(d['shell_steps'][1] * 100)}% zbudowane,
    a nie wykopane · szafka na zaopatrzenie.</p>
    <p><strong>Łóżka to przepustowość.</strong> Oddział z trzema łóżkami leczy trzy osoby
    naraz; czwarta czeka, chora, aż zwolni się łóżko. Ugryziony w dowolnym miejscu miasta
    trafia do najbliższego otwartego oddziału z wolnym łóżkiem i wraca do domu po
    {d['ward_stay']} dniu.</p>
    <h3 class="sub">Płaci za to miasto</h3>
    <p>To pierwsza rzecz, która zabiera pieniądze ze skarbca <em>bez czyjejkolwiek decyzji</em>.
    Lekarze dostają <strong>{d['ward_fee']}e dziennie za pacjenta</strong>, z kasy miasta do
    portfela mieszkańców — więc wraca to do ciebie przez drzwi twojego sklepu.
    <strong>Przychodnia</strong> zbija każdy rachunek o
    {round((1 - d['ward_clinic_off']) * 100)}%.</p>
    <p class="note">Pusta kasa miasta oznacza, że nikt nie jest leczony. Chorzy zostają chorzy,
    gospodarstwo dalej nic nie zarabia, a po {d['ward_lost']} dniach umierają — a jeśli to był
    lokator, wraz z nim znika najem. Miasto bez szpitala to ta sama historia, tylko bez łóżka
    do czekania. To jest argument za utrzymywaniem skarbca przy pieniądzach i jedyny w tym
    modzie, którego stawką jest pogrzeb.</p>"""))

    sections.append(section("08b", "clubs", "Kluby nocne", "jeden pokój, jedna decyzja", f"""
    <p class="lede"><strong>Budka klubowa</strong> postawiona w pokoju robi z tego pokoju klub.
    Nic nie ocenia budynku i nigdy nie będzie — klub to jedyne miejsce, gdzie liczy się
    wyłącznie gust, a lista wymagań mówiąca, że brakuje dwóch lamp, byłaby modem projektującym
    klub za ciebie. Zostaw im miejsce do stania, a sami znajdą drogę do środka.</p>
    <h3 class="sub">Wstęp</h3>
    <p>Cena biletu to jedyne pokrętło i jest to prawdziwa decyzja:
    <strong>od {d['club_door'][0]}e do {d['club_door'][-1]}e</strong>, cztery progi. Tanio
    zapełnia salę i daje grosze od głowy; drogo to pusta sala za cztery razy większą kasę.
    Które jest lepsze, zależy od tego, ilu ludzi tu naprawdę mieszka — a miasto rośnie, więc
    odpowiedź się zmienia.</p>
    {table(["Wstęp", "Cena", "Przyciąga"], [
        ["Dla każdego", f"{d['club_door'][0]}e", "wszystkich, którzy nie śpią"],
        ["Tania noc", f"{d['club_door'][1]}e", "większość z nich"],
        ["Normalny bilet", f"{d['club_door'][2]}e", "tych z pieniędzmi"],
        ["Tylko dla członków", f"{d['club_door'][3]}e", "nielicznych, płacących sporo"]])}
    <h3 class="sub">Kto przychodzi</h3>
    <p>Twoi właśni lokatorzy, z twoich własnych domów — ci sami ludzie, którzy płacą ci czynsz,
    z tej samej puli co gracze w kasynie i klienci sklepów. <strong>Wieczór w klubie to wieczór,
    kiedy ktoś nie siedzi w domu</strong>, nie stoi przy automacie i nie robi zakupów. Jedna
    osoba jest zawsze w jednym miejscu, a każdy lokal w mieście konkuruje o tych samych
    sąsiadów.</p>
    <p>Przychodzą wyłącznie po zmroku. Idą pieszo, jeśli mieszkają na tyle blisko, żeby NPC
    zaplanował trasę, a jeśli nie — po prostu pojawiają się przy drzwiach. Płacą raz przy
    wejściu, tańczą przez chwilę, potem wracają do domu i siedzą tam jakiś czas, zanim znów
    najdzie ich ochota.</p>
    <p class="note"><strong>Pełna sala robi hałas w okolicy.</strong> Cztery osoby w środku
    i policja zaczyna się interesować — klub to najgłośniejsza rzecz, jaką możesz mieć, i to
    jest koszt, którego nie mierzy się w szmaragdach.</p>
    <p>Utarg leży w kasie, dopóki go nie odbierzesz. Rozbicie budki wysypie go na ziemię,
    zamiast go skasować.</p>
    """))

    sections.append(section("08c", "police", "Policja i przestępczość",
                            "urząd z pokrętłem", f"""
    <p class="lede">Miasto okrada samo siebie. Nie przychodzi to z zewnątrz jak nalot i nie
    jest karą za nic — <strong>im więcej ludzi mieszka w mieście, tym więcej się w nim
    dzieje</strong>, a jedyne, co można z tym zrobić, to zbudować komisariat i zapłacić
    komuś, żeby chodził po ulicach.</p>
    <h3 class="sub">Komisariat</h3>
    <p>Blok komisariatu postawiony w gotowym budynku ocenia go tak samo jak skrzynka
    pocztowa i szpital: {d['nick_cells']} łóżka (to są cele) · {d['nick_floor']} kratek
    podłogi · szczelny, z drzwiami · ani jednego ciemnego kąta ·
    {round(d['shell_steps'][1] * 100)}% zbudowane, a nie wykopane · skrzynia na zbrojownię.</p>
    <p><strong>Każda cela to jeden etat.</strong> Nie chodzi tylko o to, gdzie trzymać
    zatrzymanych — miasto nie obsadzi więcej funkcjonariuszy, niż ma cel, więc pieniądze bez
    budynku nie kupują nikogo. To jedyna rzecz, która trzyma blok w feature'rze, którego
    pokrętło stoi gdzie indziej.</p>
    <h3 class="sub">Pokrętło jest przy skarbcu</h3>
    <p>Budżet komendy ustawia się w kasie miasta, nie na komisariacie — bo to rada uchwala
    budżet, a nie dyżurny. Krok {d['beat_step']}e, do {d['beat_max']}e dziennie. Z tej jednej
    liczby wynika wszystko:</p>
    <p><strong>{d['cop_wage']}e dziennie</strong> to jeden funkcjonariusz. Każde
    <strong>{d['gear_at']}e</strong> budżetu to jeden stopień wyposażenia, do
    {d['top_gear']} — a wyposażenie to tempo, zasięg wzroku, siła ciosu i zdrowie.
    <strong>Straż miejska</strong> z inwestycji miejskich daje jeden stopień gratis.</p>
    <p class="note">Pusta kasa nie wyłącza policji, tylko ją przerzedza: miasto płaci tyle,
    ile ma, w krokach po {d['beat_step']}e, i ogłasza, ilu zostało. Nieopłacona komenda to
    jutrzejsze włamania — i to jest cały mechanizm.</p>
    <h3 class="sub">Co robią</h3>
    <p>Chodzą. Od domu do sklepu, od sklepu do skarbca, wokół własnego komisariatu —
    i <strong>nie kładą się spać</strong>, bo w nocy dzieje się {d['night_crime']}x więcej.
    Co zobaczą wrogiego w zasięgu, to biją pałką. Zombie potrafi ich zabić i wtedy komenda
    wystawia kogoś na jego miejsce.</p>
    <h3 class="sub">Co się dzieje w mieście</h3>
    {table(["Przestępstwo", "Udział", "Grzywna", "Cela"],
           [[c["display"], f"{c['weight']}%", f"{c['fine']}e", f"{c['days']} dni"]
            for c in d["crimes"]])}
    <p>Bazowo <strong>{d['crime_base']} przestępstwa dziennie na stu mieszkańców</strong> — czyli
    w mieście dwudziestu osób coś dzieje się mniej więcej <strong>raz na pół godziny gry</strong>.
    Bieda w domach dokłada do tego {round(d['crime_hardship_lift'] * 100)}%, rozgrzany handel
    kolejne {round(d['crime_heat_lift'] * 100)}%, a noc mnoży całość przez
    {d['night_crime']}. Te dwa pierwsze się <em>dodają</em>, nie mnożą — inaczej pechowe
    miasto potrafiłoby biec siedem razy szybciej od swojej własnej stawki.</p>
    <p class="note">Nad tym wszystkim jest twardy sufit: <strong>{d['crime_ceiling']}
    przestępstwa dziennie</strong>, cokolwiek by się działo. Najgorsze możliwe miasto to jedno
    zdarzenie na dziesięć minut gry, a nie oblężenie — a policja zbija nawet to o
    <strong>do {round(d['top_deterrence'] * 100)}%</strong>. <code>/crime</code> pokazuje
    dokładnie te liczby, więc nic tu nie jest pogodą.</p>
    <p><strong>Kradzież i włamanie zabierają prawdziwe pieniądze</strong> — ze skrzynki
    pocztowej i z kasy sklepu, jako udział tego, co tam leży. Pełna skrzynka to cel, więc
    zbieranie czynszu jest samo w sobie obroną. Pieniądze nie znikają: idą do portfela
    mieszkańców, bo złodziej też tu mieszka i wyda je u kogoś w sklepie.</p>
    <p><strong>Rozbój</strong> wysyła lokatora do szpitala — tym samym wejściem co ugryzienie,
    z tym samym rachunkiem i tym samym pogrzebem, jeśli miasto nie ma oddziału.
    <strong>Zabójstwa</strong> to {[c for c in d["crimes"] if c["name"] == "MURDER"][0]["weight"]}%
    spraw i zdarzają się <strong>wyłącznie po zmroku</strong> — za dnia ta sama sprawa wychodzi
    jako rozbój. {round(d['murder_fatal'] * 100)}% z nich kończy się od razu śmiercią lokatora,
    reszta trafia na oddział, więc miasto ze szpitalem grzebie mniej ludzi przy tej samej
    liczbie noży.</p>
    <h3 class="sub">Pościg</h3>
    <p>Sprawca zostaje na miejscu z czerwoną nazwą i ucieka. Biegnie tempem
    {d['suspect_pace']} — <strong>szybciej niż funkcjonariusz bez wyposażenia
    ({d['officer_pace']}) i wolniej niż w pełni wyposażony
    ({round(d['officer_pace'] + d['officer_gear_pace'] * d['top_gear'], 3)})</strong>.
    To jest jedyne miejsce, gdzie widać, za co się płaci, i jedyna rzecz w tym module,
    którą rozstrzyga fizyka, a nie tabela.</p>
    <p>Złapany oddaje, co zabrał, grzywna idzie do kasy miasta, a on sam do celi na kilka dni.
    Cele pełne — wychodzi za kaucją, grzywna zostaje. Nikt go nie dopadnie w kilka minut —
    sprawa umorzona i pieniądze przepadają.</p>
    <h3 class="sub">Kontrola osobista</h3>
    <p>Patrol podchodzi też do <em>ciebie</em>. Zatrzymuje, jeśli masz przy sobie ponad
    <strong>{d['pocketful']} sztuk towaru</strong>, świeży heat albo zaległość w urzędzie —
    i wtedy wypisuje mandat do kasy miasta. Skręt w kieszeni nikogo nie obchodzi.</p>
    <p class="note">Nie zabierają towaru i nikogo nie aresztują. Jeden mandat na kilka minut,
    najwyżej, i nigdy taki, którego nie masz z czego zapłacić — wtedy zamiast pieniędzy
    dostajesz uwagę.</p>
    """))

    sections.append(section("09", "crew", "Ekipa", "ktoś, kto zbierze za ciebie", f"""
    <p class="lede">{d['hire']}e za zatrudnienie, potem {d['wage']}e za każde pięć minut,
    <strong>kiedy pracują</strong>, niezależnie od tego, czy coś zebrali. {d['max_hands']}
    osób to maksimum dla jednej ekipy.</p>
    <p>Domyślnie pracują <strong>tylko za dnia</strong> — o zmroku szukają łóżka na działce
    i kładą się spać — a licznik pensji staje razem z nimi, więc noce nic nie kosztują.
    Przestaw kogoś <strong>na nocną zmianę</strong> z tablicy, a nie przestanie pracować
    w ogóle: +{round((d['night_rate'] - 1) * 100)}% do pensji <em>oraz</em> licznik chodzący
    całą noc, czyli mniej więcej dwa razy więcej wypłat na godzinę za dwa razy więcej pracy.
    Robią sobie przerwę co {d['jobs_per_shift']} czynności, a przerwa jest ułamkiem zmiany,
    a nie stałą minutą, więc szybki robotnik odpoczywa tak krótko, jak pracuje. Nie zdepczą
    twojej zaoranej ziemi, nie ściągną suszu z suszarki za wcześnie ani nie użyją mączki
    na twoich uprawach.</p>
    <p>Działka działa niezależnie od tego, gdzie jesteś, byle byś był zalogowany — nie musisz
    nad nikim stać. Jeśli ktoś się zapodzieje albo coś go zje, <strong>bat</strong> na tablicy
    ekipy ściąga go z powrotem, a jeśli ciała nie ma — stawia wyszkolone zastępstwo.</p>
    <p>Każdy ma <strong>własne miejsce pracy</strong> — tam, gdzie stałeś, gdy go zatrudniałeś —
    i da się je przenieść. Idź na nowe pole, otwórz tablicę i <strong>Pracuj tutaj</strong>
    przenosi miejsce razem z człowiekiem, także między wymiarami. Zapominają stare łóżko
    i starą skrzynię, i znajdują nowe.</p>
    <h3 class="sub">Tempo</h3>
    {table(["Poziom", "Czynność co", "Koszt", "Pensja"], pace_rows)}
    <h3 class="sub">Zawody</h3>
    {table(["Zawód", "Koszt", "Pensja", "Co robi"], job_rows)}
    <p>Powyższe czasy to to, co zmierzyłbyś stoperem, razem z przerwą — a nie surowy odstęp
    między przebiegami.</p>
    <h3 class="sub">Wszystko da się cofnąć</h3>
    <p>Pensja idzie za tym, co jest <strong>włączone teraz</strong>, a nie za tym, co
    kiedykolwiek kupiłeś. <strong>Shift+LPM</strong> na Tempie albo Zasięgu obniża poziom
    o jeden i od razu obcina pensję; zwykły klik podnosi z powrotem — i skoro ten poziom
    już raz kupiłeś, <strong>powrót w górę jest za darmo</strong>. Robotnik napędzony
    na maksa na czas budowy może przespać zimę na najwolniejszym poziomie za 0e dodatku.</p>
    <p>Zawody działają tak samo. <strong>Shift+LPM</strong> na wyuczonym zawodzie
    <strong>wyłącza</strong> go — zwalnia jedno z dwóch miejsc i zdejmuje jego dodatek
    z pensji — ale nauka zostaje. Możesz mieć wyuczonych pięć zawodów i włączone dwa,
    przełączać je zależnie od pory roku, i nigdy nie płacić drugi raz. Limit dwóch dotyczy
    tego, co robi <em>naraz</em>, nie tego, co umie.</p>
    <h3 class="sub">Jedna skrzynia</h3>
    <p>To jest rzecz, którą wszyscy mylą. Robotnik korzysta z <strong>pojemnika najbliższego
    swojemu miejscu pracy</strong> — tego jednego i żadnego innego — do wszystkiego: tam wkłada
    plony i stamtąd bierze materiały. Skręcanie wymaga <strong>suszonych szyszek i papieru
    w tej skrzyni</strong>; świeże szyszki prosto z rośliny nie wystarczą, a stół rzemieślniczy
    nie jest w to zamieszany. Inny pojemnik postawiony bliżej po cichu staje się tym używanym.</p>
    <p class="note">Tablica ekipy pokazuje, z której skrzyni faktycznie korzysta, i oznacza
    każdy wyuczony zawód, którego skrzynia aktualnie nie jest w stanie obsłużyć, więc nigdy
    nie musisz zgadywać, o którą z dwóch chodzi.</p>
    <h3 class="sub">Zapisane ekipy</h3>
    <p><code>/crew save &lt;nazwa&gt;</code> zapisuje, kto gdzie pracuje i co umie;
    <code>/crew load &lt;nazwa&gt;</code> odkupuje całość na te same działki za tyle, ile
    kosztowało za pierwszym razem. <code>/crew plans</code> pokazuje listę. Jeśli ekipa
    kiedykolwiek odejdzie przez brak wypłat, zapisuje się sama pod nazwą <code>walkout</code>,
    więc nic naprawdę nie przepada — po prostu płacisz drugi raz.</p>
    <p class="note">Wszystko, co masz <em>włączone</em>, podnosi pensję. Robotnik, którego nie
    masz czym zająć, to strata pieniędzy — wyłącz mu, czego akurat nie potrzebujesz. Za spóźnioną wypłatę dostajesz ostrzeżenie, a nie odejście:
    {d['grace']} wypłat na zero, czyli około dwóch dni, a zapłacenie jednej umarza całe
    zaległości.</p>"""))

    sections.append(section("10", "heat", "Naloty", "bycie widzianym kosztuje", f"""
    <p class="lede">Uprawa na widoku zostaje zauważona. Uwaga policji liczona jest w promieniu
    {d['heat_radius']} bloków: dojrzałe rośliny liczą się po 3, ukryte po 2, rosnące po 1,
    zajęte suszarki po 1, prasy i rafinerie po 2.</p>
    {table(["Próg", "Punkty", "Kto przychodzi"], heat_rows)}
    <p>Powyżej najwyższego progu oddział przestaje rosnąć, ale <strong>przerwy między nalotami
    dalej się skracają</strong> — dwa razy więcej punktów niż limit to dwa razy częściej, aż do
    dolnej granicy. Budowanie największego pola, jakie się mieści, przestało być darmowe.</p>
    <h3 class="sub">Przeszukują</h3>
    <p>Nalot to nie tylko machanie toporami. Napastnicy podchodzą do twoich pojemników,
    otwierają je i zabierają towar — więc chowanie zapasu pod ziemią ma sens, rozdzielenie go
    na dwa budynki ma sens, i stanięcie im na drodze też ma sens.</p>
    <p class="note">Zamurowanie uprawy kupuje czas, nie bezpieczeństwo. Jak nie znajdą drogi
    dookoła, wejdą przez ścianę. Obsydian ich zatrzymuje, ziemia nie.</p>
    {quotes}"""))

    sections.append(section("11", "street", "Ulica", "paranoja, telefony i ludzie", f"""
    <p class="lede">Wszystko, co nie jest linią produkcyjną.</p>
    <div class="cards">
      <div class="card reveal"><h4>Paranoja</h4><p>Licznik, który rośnie od uwagi policji,
      poziomu odurzenia, ciemności, nocy i samotności — a spada w świetle dnia, na trzeźwo
      albo <em>w pobliżu innego gracza</em>. Cztery poziomy, od dźwięków za plecami po
      nieruchomą postać na granicy zasięgu widzenia. Nic z tego nie jest prawdziwe. Nic nie
      jest spawnowane, nic nie dotyka twojej budowli i nikt inny tego nie widzi.</p></div>
      <div class="card reveal"><h4>Zlecenia</h4><p>Telefon na kartę to tablica ogłoszeń:
      te same pięć dostaw przez cały dzień, więc możesz rano spojrzeć na listę i zaplanować
      wokół niej. Zlecenia są wyceniane na zimno, a premia za uwagę policji rozliczana przy
      odbiorze — płacą ci za ryzyko, które naprawdę podjąłeś. Limit: {d['payout_ceiling']}e.</p></div>
      <div class="card reveal"><h4>Reputacja</h4><p>Siedzi w samym telefonie, więc pożyczenie
      komuś telefonu to pożyczenie mu swojego nazwiska — a zgubienie go to utrata pozycji
      razem z nim. Limit: {d['rep_max']}, bo zasila naraz cztery mnożniki, a trzy z nich były
      kiedyś nieograniczone.</p></div>
      <div class="card reveal"><h4>Dilerzy</h4><p>Pierwsza rzecz, jaką posiadasz, a która działa,
      kiedy ciebie nie ma. Biorą prowizję, sprzedają dużo lepiej nocą niż w południe, wchodzą
      sobie w drogę, jeśli stłoczysz ich w jednym miejscu, a tani dają się okradać.</p></div>
      <div class="card reveal"><h4>Spis skrzyń</h4><p>Książka, kompas i dwa ametysty.
      Czyta każdy pojemnik w promieniu 32 bloków — łącznie z shulkerami — i rysuje świetlną
      linię do skrzyni, w której leży to, co kliknąłeś.</p></div>
      <div class="card reveal"><h4>Napady</h4><p>Nalot na farmę przychodzi po rośliny. To
      przychodzi po <em>ciebie</em>, w chwili gdy osobiście przekazujesz towar. To cena za
      handlowanie samemu, zamiast opłacenia kogoś innego.</p></div>
    </div>"""))

    sections.append(section("12", "casino", "Kasyno", "siedem gier i skarbiec", f"""
    <p class="lede">Kasyno to skarbiec i zestaw podłączonych do niego automatów. Pieniądze
    leżą w księgach, a nie na karcie — karta jest tylko kluczem, więc automat może wypłacić
    wygraną o czwartej nad ranem, kiedy właściciel jest offline.</p>
    {table(["Plansza", "Zwrot dla gracza"], slot_rows)}
    <p>Ruletka ma <strong>jedno zero</strong>: {d['roulette_pockets']} pól, a każdy zakład
    na stole ma tę samą przewagę kasyna, więc wybór to wyłącznie kwestia tego, jak chcesz
    przegrać. Wspinaczka zwraca {d['climb_return'] * 100:.1f}% na <em>każdym</em> szczeblu —
    nie ma dobrego momentu na przerwanie, przez co to kwestia nerwów, a nie matematyki.</p>
    <h3 class="sub">Skąd biorą się gracze</h3>
    <p><strong>Z miasta.</strong> Gracze to ludzie, a ludzie mieszkają w domach, więc obrót
    rośnie razem z liczbą mieszkańców aż do {d['pull_at']} klas — a kasyno bez miasta za
    plecami dostaje tylko {round(d['pull_floor'] * 100)}% z tych, którzy akurat przechodzili
    obok. Reputacja i uzależnienie robią resztę i wciąż trzeba na nie zapracować.</p>
    <h3 class="sub">Automaty się zużywają i widać to</h3>
    <p>Każdy automat zbiera punkt zużycia mniej więcej raz na {d['wear_per_rounds']} rund
    i pada przy {d['wear_broken']}. Powyżej <strong>{d['jam_from']}</strong> zaczyna połykać
    pieniądze i odsyłać graczy z powrotem do drzwi, więc młot zwraca się w obrocie, a nie
    w porządku na sali. Karta pokazuje najgorszy automat na sali i dostajesz info w chwili,
    gdy któryś przekroczy granicę.</p>
    <h3 class="sub">Bar to twoja przewaga</h3>
    <p>Sam automat zostawia jakieś trzy procent, a haracz, części i okazjonalny oszust
    zjadają to w całości — sala jadąca wyłącznie na tabeli wypłat zarabia mniej więcej nic.
    Płaci <strong>to, co jest za ladą baru</strong>. Każdy wchodzący dostaje kolejkę z twojego
    zapasu, a ktoś po czterech rundach na twoim własnym towarze gra gorzej, niż grał na
    wejściu: <strong>{round(d['served_edge_product'] * 100)} punktów</strong> mniej odzyskuje,
    wobec {round(d['served_edge_food'] * 100)} u gracza karmionego chlebem. Pusty bar oznacza
    trzeźwych graczy grających zgodnie z tabelą, po jednej grze każdy, i pustoszejącą salę.</p>
    <p class="note">Sala to biznes, nie kran. Graczy trzeba obsługiwać własnym towarem, szef
    sali kosztuje stałą pensję przeciwko proporcjonalnym kradzieżom, a sala bez opieki zarabia
    prawie zero.</p>"""))

    # --- the bookmaker -------------------------------------------------------
    #
    # Two tables carry this section and both are generated: the competition
    # list, so nobody has to count rosters by hand, and one full suits matrix,
    # so the page shows the SHAPE of the readable edge -- which is the thing
    # somebody has to understand before any of the prose means anything.
    league_rows = []
    for competition in d["leagues"]:
        if competition["field"] > 2:
            market = f'zwycięzca + miejsce (pierwsza {competition["places"]})'
        elif competition["draws"]:
            market = "1 X 2"
        else:
            market = "zwycięzca"
        league_rows.append([
            f'<strong>{esc(competition["name"])}</strong>',
            esc(competition["sport"]),
            str(len(competition["runners"])),
            f'{competition["field"]} w stawce' if competition["field"] > 2 else "para",
            market,
            f'<span class="dim">{esc(", ".join(competition["conditions"]))}</span>',
        ])

    tennis = next(c for c in d["leagues"] if c["name"] == "ATP")
    suits_rows = []
    for row, condition in enumerate(tennis["conditions"]):
        cells = [esc(condition)]
        for column in range(len(tennis["styles"])):
            points = tennis["suits"][row][column]
            tone = "up" if points > 0 else "down" if points < 0 else "dim"
            cells.append(f'<span class="{tone}">{points:+d}</span>')
        suits_rows.append(cells)

    top = sorted((r for c in d["leagues"] for r in c["runners"]),
                 key=lambda r: -r["reputation"])[:6]
    roster_cards = "".join(
        f'<div class="card reveal"><h4>{esc(r["name"])}</h4>'
        f'<p class="dim">{esc(r["note"])}</p></div>' for r in top)

    rest_low, rest_high = d["book_rest"][0], d["book_rest"][-1]
    sections.append(section("12b", "bets", "Zakłady sportowe",
                            "kurs wie mniej niż ty", f"""
    <p class="lede">Postaw <strong>telewizor</strong> i masz osiem rozgrywek chodzących
    non stop — Liga Mistrzów, Ekstraklasa, ATP, WTA, Formuła 1, gonitwy i dwie ligi
    koszykówki. Spotkania startują same co
    {d['book_min_ticks']}–{d['book_max_ticks']} minut, niezależnie od tego, czy ktoś
    patrzy. Wszyscy zawodnicy są prawdziwi, a ich renoma odpowiada temu, jak jest
    naprawdę: kto zna te ligi, ten wie, kto jest kim, jeszcze zanim spojrzy na kurs.</p>

    <h3 class="sub">Dlaczego da się tu wygrać</h3>
    <p>Bukmacher wycenia spotkanie z <strong>dwóch</strong> rzeczy: renomy zawodnika
    i tego, kto gra u siebie (+{d['book_home']} do siły). Z niczego więcej. Wynik
    rozstrzyga się z <strong>sześciu</strong>, i te cztery brakujące są wypisane
    słowami na ekranie każdego spotkania:</p>
    {table(["Czynnik", "Ile jest wart", "Gdzie to zobaczysz"], [
        ["Forma", f"±{d['book_form'] * 5} pkt (pięć ostatnich wyników, po "
                  f"{d['book_form']} pkt każdy)",
         "Karta zawodnika: W wygrana, R remis, P porażka"],
        ["Absencje", f"−{d['book_absence']} pkt za każdego brakującego, do trzech",
         "Karta zawodnika: „brakuje 2 zawodników”"],
        ["Odpoczynek", f"od {rest_low} do +{rest_high} pkt",
         "Karta zawodnika: ile rund przerwy"],
        ["Warunki", "zależnie od stylu, patrz tabela niżej",
         "Nagłówek spotkania + „Styl” na karcie"],
        ["Bezpośrednie starcia", f"do ±{d['book_h2h_cap']} pkt",
         "Środkowa karta, liczone z tego serwera"],
    ])}
    <p class="note">Nigdzie nie zobaczysz wyliczonej szansy ani podpowiedzi, kto jest
    lepszy. Ekran podaje <em>składniki</em>, nie odpowiedź — inaczej to nie byłby zakład,
    tylko przycisk „odbierz”.</p>

    <h3 class="sub">Warunki kontra styl</h3>
    <p>Każdy zawodnik ma styl, każde spotkanie ma warunki, a to, czy do siebie pasują,
    jest warte kilka punktów siły — i nie ma tego w kursie. Tenis, jako przykład
    (pozostałe rozgrywki mają własne tabele o tym samym kształcie):</p>
    {table(["Nawierzchnia"] + [esc(s) for s in tennis["styles"]], suits_rows)}
    <p class="note">Mączkarz na trawie to inny zawodnik. To jest cała gra.</p>

    <h3 class="sub">Marża, czyli dlaczego na ślepo się nie da</h3>
    <p>Od każdego kursu odchodzi <strong>{round(d['book_margin'] * 100)}%</strong>.
    Obstawianie faworyta bez patrzenia na nic innego przynosi mniej więcej dokładnie
    tyle na minusie — to nie pech, tylko cennik. Żeby wyjść na plus, przewaga, którą
    wyczytasz z ekranu, musi być większa niż ta marża. Trzy z czterech czynników mówiące
    to samo zwykle wystarczą; czynniki, które się kłócą, to spotkanie do odpuszczenia.</p>

    <h3 class="sub">Kupon</h3>
    <p>Do <strong>{d['book_legs']} pozycji</strong> na kuponie, kursy się mnożą — ale
    marża też, więc czwórka to cztery razy zapłacona prowizja. Z jednego spotkania
    wchodzi tylko jeden typ. Stawki od {d['book_stakes'][0]}e do
    {d['book_stakes'][-1]}e, maksymalna wypłata {d['book_payout']}e, do
    {d['book_slips']} kuponów w grze naraz. Kupon rozlicza się sam przy ostatniej
    pozycji: jesteś w grze — pieniądze od razu, nie ma cię — czekają w telewizorze.</p>
    <p class="note">Miasto bierze daninę hazardową od każdej postawionej stawki, od
    obrotu, a nie od wygranej. Tak samo jak w kasynie.</p>

    <h3 class="sub">Co jest na antenie</h3>
    {table(["Rozgrywki", "Sport", "Zawodników", "Format", "Rynki", "Warunki"],
           league_rows)}
    <div class="cards">{roster_cards}</div>
    <p class="note">Forma na ekranie to prawdziwe wyniki z tego serwera, nie ozdoba.
    Bezpośrednie starcia też. Kto ogląda wyniki, ten zna formę, zanim wejdzie
    w spotkanie.</p>"""))

    cmd_rows = [
        ["<code>/wiki</code>", "Ta strona, jako klikalny link na czacie"],
        ["<code>/guide</code>",
         "Poradniki — uprawa, koka, mak, nałóg, ulica, ekipa, kasyno, miasto, mieszkania, zakłady"],
        ["<code>/guide zaklady</code>", "Zakłady sportowe: co czytać z telewizora"],
        ["<code>/guide housing</code>", "Domy, klasy i skąd lokatorzy biorą pieniądze"],
        ["<code>/market</code>", "Dlaczego wszystko kosztuje tyle, ile kosztuje"],
        ["<code>/stalls</code>", "Kto sprzedaje i gdzie"],
        ["<code>/city</code>", "Kasa miasta, aktualne podatki i ile każdy zebrał"],
        ["<code>/law</code>", "Prawo miasta, spisane w chwili, gdy o nie poprosisz"],
        ["<code>/homes</code>", "Każdy dom w rejestrze i jego klasa"],
        ["<code>/shops</code>", "Wszystkie sklepy i liczba mieszkańców"],
        ["<code>/homes demolish</code>", "Zdejmij z rejestru dom, w którym stoisz"],
        ["<code>/homes evict</code>", "Usuń lokatora kręcącego się bez domu"],
        ["<code>/crew</code>", "Tablica ekipy — zatrudnianie, szkolenie, pensje"],
        ["<code>/heat</code>", "Jak gorąco jest w tym miejscu i co z tego wyniknie"],
        ["<code>/paranoia</code>", "Wyłącz całość, osobno dla każdego gracza"],
        ["<code>/earnings</code>", "Dzisiejsze zarobki wszystkich, według źródła"],
        ["<code>/sethome · /home · /spawn · /back</code>", "Przemieszczanie się"],
    ]
    sections.append(section("13", "commands", "Komendy", "każda odpowiada tylko tobie", f"""
    <p class="lede">Każda komenda odpowiada wyłącznie osobie, która ją wpisała, więc nic, co
    uruchomisz, nie jest ogłaszane reszcie serwera.</p>
    {table(["Komenda", "Co robi"], cmd_rows)}"""))

    sections.append(section("14", "awards", "Osiągnięcia",
                            f"jest ich {len(d['awards'])}", f"""
    <p class="lede">To są prawdziwe osiągnięcia — pojawiają się w ekranie osiągnięć jak
    wszystkie inne, razem z powiadomieniem, gdy je zdobędziesz.</p>
    {table(["Nazwa", "Jak zdobyć", "Rodzaj"], award_rows)}"""))

    body = "".join(sections)

    return TEMPLATE.format(nav=nav, sections=body, lines=d["declared_lines"],
                           strains=len(d["strains"]), awards=len(d["awards"]),
                           blends=len(d["blends"]))


TEMPLATE = """<!doctype html>
<html lang="pl">
<script>document.documentElement.className='js'</script>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>TrapCraft — Poradnik terenowy</title>
<meta name="description" content="TrapCraft: uprawa, suszenie, rafinacja, rynek,
stragany, ekipa, policja i kasyno. Cała gra w jednym miejscu.">
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
.up {{ color: var(--acc); }}
.down {{ color: var(--warn); }}
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
      <div class="brand"><b>TrapCraft</b><span>poradnik terenowy</span></div>
      {nav}
    </div>
  </nav>

  <div>
    <header class="hero">
      <p class="eyebrow">Cała gra · w jednym miejscu</p>
      <h1>Wszystko<em>jest czyimś interesem.</em></h1>
      <p>Rynek, który żyje, sala kasyna, która się zużywa, ludzie na pensji i nalot,
      który przeszukuje twoje skrzynie. Pełna dokumentacja — generowana wprost ze
      źródeł moda, więc nie może się zdezaktualizować.</p>
      <div class="figs">
        <div><b>{lines}</b><span>wycenionych pozycji</span></div>
        <div><b>{strains}</b><span>odmian</span></div>
        <div><b>{blends}</b><span>nazwanych mieszanek</span></div>
        <div><b>{awards}</b><span>osiągnięć</span></div>
      </div>
    </header>

    <main>{sections}</main>

    <footer>
      <p>Każda cena, każdy czas i każda klasa na tej stronie pochodzą wprost z gry,
      więc strona zmienia się razem z nią. Jeśli któraś liczba wygląda źle, to
      znaczy, że zmieniła się gra.</p>
      <p>W samej grze są też poradniki — wpisz <code>/guide</code>. Te same liczby,
      mniej słów, i można je czytać bez wychodzenia z plantacji.</p>
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
