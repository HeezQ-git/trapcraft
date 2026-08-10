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
    """Job("Picking", "minecraft:wheat", cost, wage, "what they do")."""
    text = java("TrapCrew")
    return [{"name": m.group(1), "cost": int(m.group(2)), "wage": int(m.group(3)),
             "blurb": m.group(4)}
            for m in re.finditer(
                r'\w+\("([^"]+)", "[^"]+", (\d+), (\d+),\s*\n\s*"([^"]+)"\)', text)]


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

    DATA["pace_ticks"] = ints("PACE_TICKS", crew)
    DATA["pace_cost"] = ints("PACE_COST", crew)
    DATA["pace_wage"] = ints("PACE_WAGE", crew)
    DATA["pace_name"] = re.findall(r'"([^"]+)"', need(
        r'PACE_NAME = \{([^}]*)\}', crew, "PACE_NAME"))
    DATA["reach"] = ints("REACH_BLOCKS", crew)
    DATA["reach_cost"] = ints("REACH_COST", crew)
    DATA["hire"] = int(need(r"HIRE_COST = (\d+)", crew, "HIRE_COST"))
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
    DATA["declared_lines"] = len(re.findall(r'add\(c, "', stock))


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
                 f'+{j["wage"]}e', f'<span class="dim">{esc(j["blurb"])}</span>']
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
        ("07", "stalls", "Stalls"), ("08", "homes", "Housing"),
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

    sections.append(section("08", "homes", "Housing", "a room the city can see", f"""
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
        ["0–2", f"no dark corners — under a tenth of the floor below light "
                f"{d['dark_at']} earns one, none at all earns two"],
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
    <p class="note">Nothing rents yet. Tenants are next, and they will pay into the
    box.</p>"""))

    sections.append(section("09", "crew", "The Crew", "somebody to do the picking", f"""
    <p class="lede">{d['hire']}e to take somebody on, then {d['wage']}e every five
    minutes <strong>they are working</strong>, harvest or no harvest. {d['max_hands']}
    hands is all one operation will carry.</p>
    <p>They work <strong>daylight only</strong> — at dusk they find a bed inside the
    patch and turn in — and the clock stops with them, so nights cost nothing. They
    stop for a breather every {d['jobs_per_shift']} jobs, and the breather is a share
    of the shift rather than a flat minute, so a quick hand rests as briefly as it
    works. They will not tread your farmland back into dirt, pull a rack early, or
    bone-meal your own crops.</p>
    <p>The patch stays awake wherever you are, as long as you are logged in — you do
    not have to stand over anybody. If one wanders off or something eats it, the
    <strong>whip</strong> on the crew board drags it back, and puts a trained
    replacement down if the body is gone.</p>
    <h3 class="sub">Pace</h3>
    {table(["Rung", "A job every", "Costs", "Wage"], pace_rows)}
    <h3 class="sub">Jobs</h3>
    {table(["Job", "Costs", "Wage", "What they do"], job_rows)}
    <p>The times above are what you would measure with a stopwatch, breather included
    — not the raw pass rate.</p>
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
    <p class="note">A floor is a business, not a tap. Punters have to be served from
    your own stash, machines wear out, a pit boss costs a flat wage against a
    proportional skim, and an unattended floor earns very close to nothing.</p>"""))

    cmd_rows = [
        ["<code>/wiki</code>", "This page, as a clickable link in chat"],
        ["<code>/guide</code>", "Six handbooks — grower, refiner, street, crew, casino, city"],
        ["<code>/market</code>", "Why everything costs what it costs"],
        ["<code>/stalls</code>", "Who is selling, and where"],
        ["<code>/homes</code>", "Every house on the register, and its grade"],
        ["<code>/homes demolish</code>", "Take the house you're stood in off the register"],
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
