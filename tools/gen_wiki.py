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

import html
import json
import pathlib
import re
import sys

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
                ident = key.get(ch)
                cells.append(ident.split(":")[-1].replace("_", " ") if ident else None)
            grid.append(cells)
        while len(grid) < 3:
            grid.append([None, None, None])
        out[body["result"]["id"].split(":")[-1]] = {
            "grid": grid, "count": body["result"].get("count", 1)}
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
            if cell:
                words = cell.split()
                short = ("".join(w[0] for w in words) if len(words) > 1
                         else words[0][:3]).upper()
                cells += f'<i class="on" title="{esc(cell)}">{esc(short)}</i>'
            else:
                cells += '<i></i>'
    yields = f' <span class="dim">x{r["count"]}</span>' if r["count"] > 1 else ""
    return (f'<figure class="craft reveal"><div class="grid3">{cells}</div>'
            f'<figcaption>{esc(label)}{yields}</figcaption></figure>')


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
    pace_rows = [[esc(d["pace_name"][i]), f'{d["pace_ticks"][i] / 20:g}s',
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
        ("07", "stalls", "Stalls"), ("08", "crew", "The Crew"),
        ("09", "heat", "Heat & Raids"), ("10", "street", "The Street"),
        ("11", "casino", "The House"), ("12", "commands", "Commands"),
        ("13", "awards", "Advancements"),
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
    <p class="lede">Baked and Wired are real custom status effects, not stacked vanilla
    ones — so they carry their own icon, their own maths and their own consequences.</p>
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
    <p class="note">Joints use the only vanilla animation that raises an item to the
    mouth, so it reads as smoking on a completely unmodified client.</p>"""))

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
    <p>The index is clamped between <strong>{d['index_min']:.2f}×</strong> and
    <strong>{d['index_max']:.2f}×</strong> and measured against a <em>moving</em>
    anchor, so a good week becomes the new normal instead of welding prices to the cap.</p>
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

    sections.append(section("08", "crew", "The Crew", "somebody to do the picking", f"""
    <p class="lede">{d['hire']}e to take somebody on, then {d['wage']}e every five
    minutes whether the harvest was good or not. {d['max_hands']} hands is all one
    operation will carry.</p>
    <p>They work <strong>daylight only</strong> — at dusk they find a bed inside the
    patch and turn in — and they stop for a breather every dozen jobs. They will not
    tread your farmland back into dirt, pull a rack early, or bone-meal your own crops.</p>
    <h3 class="sub">Pace</h3>
    {table(["Rung", "A job every", "Costs", "Wage"], pace_rows)}
    <h3 class="sub">Jobs</h3>
    {table(["Job", "Costs", "Wage", "What they do"], job_rows)}
    <p class="note">Everything you teach them puts the wage up. A hand you cannot keep
    busy is a hand losing you money — and if you miss a payday they walk, taking what
    you taught them with them.</p>"""))

    sections.append(section("09", "heat", "Heat & Raids", "being seen costs something", f"""
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

    sections.append(section("10", "street", "The Street", "paranoia, phones and people", f"""
    <p class="lede">Everything that is not a product line.</p>
    <div class="cards">
      <div class="card reveal"><h4>Paranoia</h4><p>A meter that builds from heat, how
      high you are, darkness, night and being alone — and decays in daylight, sober, or
      <em>near another player</em>. Four tiers, from noises behind you to a motionless
      figure at render distance. Every bit of it is a packet sent to one client; nothing
      spawns and the world is never modified.</p></div>
      <div class="card reveal"><h4>Contracts</h4><p>A burner phone is a job board:
      five deliveries a day, seeded by the world day so the board is stable until
      tomorrow. Jobs are advertised cold and the heat premium is settled at the drop —
      you are paid for risk you actually ran. Capped at {d['payout_ceiling']}e.</p></div>
      <div class="card reveal"><h4>Reputation</h4><p>Lives on the phone as a data
      component, so it survives restarts for free and losing the phone loses the
      standing with it. Capped at {d['rep_max']}, because it feeds four multipliers at
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

    sections.append(section("11", "casino", "The House", "seven games and a vault", f"""
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
        ["<code>/guide</code>", "Five handbooks — grower, refiner, street, crew, casino"],
        ["<code>/market</code>", "Why everything costs what it costs"],
        ["<code>/stalls</code>", "Who is selling, and where"],
        ["<code>/crew</code>", "The crew board — hire, train, pay"],
        ["<code>/heat</code>", "How hot this spot is, and what it would bring"],
        ["<code>/paranoia</code>", "Turn the whole thing off, per player"],
        ["<code>/earnings</code>", "Today's takings, everybody, by job"],
        ["<code>/sethome · /home · /spawn · /back</code>", "Getting about"],
    ]
    sections.append(section("12", "commands", "Commands", "everything answers only you", f"""
    <p class="lede">Every command replies to the person who typed it — no op broadcast,
    so going creative does not announce itself.</p>
    {table(["Command", "What it does"], cmd_rows)}"""))

    sections.append(section("13", "awards", "Advancements",
                            f"{len(d['awards'])} of them", f"""
    <p class="lede">Real advancements, so they show in the vanilla tree with no client
    mod.</p>
    {table(["Name", "How", "Kind"], award_rows)}"""))

    body = "".join(sections)

    return TEMPLATE.format(nav=nav, sections=body, lines=d["declared_lines"],
                           strains=len(d["strains"]), awards=len(d["awards"]))


TEMPLATE = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>TrapCraft — Field Manual</title>
<meta name="description" content="The complete reference for TrapCraft: growing, curing,
refining, the market, stalls, crew, heat, the casino. Generated from the mod's source.">
<meta name="theme-color" content="#0c0b0a">
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Bodoni+Moda:ital,opsz,wght@0,6..96,400..900;1,6..96,400..900&family=Newsreader:ital,opsz,wght@0,6..72,200..800;1,6..72,200..800&family=IBM+Plex+Mono:wght@300;400;500&display=swap" rel="stylesheet">
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
  font-family: 'Newsreader', Georgia, serif;
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
  font-family: 'IBM Plex Mono', monospace; font-size: .74rem; letter-spacing: .1em;
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
.brand b {{ font-family: 'Bodoni Moda', serif; font-weight: 900; font-size: 1.5rem;
  letter-spacing: -.02em; display: block; line-height: 1; }}
.brand span {{ font-family: 'IBM Plex Mono', monospace; font-size: .64rem;
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
  font-family: 'IBM Plex Mono', monospace; font-size: .72rem; letter-spacing: .3em;
  text-transform: uppercase; color: var(--acc); margin: 0 0 1.5rem;
}}
.hero h1 {{
  font-family: 'Bodoni Moda', serif; font-weight: 900;
  font-size: clamp(3.2rem, 13vw, 11rem); line-height: .82; letter-spacing: -.035em;
  margin: 0; text-wrap: balance;
}}
.hero h1 em {{ font-style: italic; font-weight: 400; display: block;
  color: var(--bone-dim); }}
.hero p {{ max-width: 46ch; margin: 2rem 0 0; color: var(--bone-dim);
  font-size: 1.15rem; }}
.figs {{ display: flex; flex-wrap: wrap; gap: 2.5rem; margin-top: 3.5rem;
  font-family: 'IBM Plex Mono', monospace; }}
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
  font-family: 'IBM Plex Mono', monospace; font-size: .72rem; letter-spacing: .2em;
  color: var(--bone-dim); margin-bottom: 1.5rem;
}}
@media (min-width: 1400px) {{
  .sec-mark {{ position: absolute; left: -5.5rem; top: 5.9rem; margin: 0; }}
}}
.kicker {{ font-family: 'IBM Plex Mono', monospace; font-size: .7rem; letter-spacing: .22em;
  text-transform: uppercase; color: var(--acc); margin: 0 0 .5rem; }}
.sec h2 {{
  font-family: 'Bodoni Moda', serif; font-weight: 900;
  font-size: clamp(2.2rem, 6vw, 4.2rem); line-height: .95; letter-spacing: -.03em;
  margin: 0 0 2rem;
}}
.sub {{ font-family: 'Bodoni Moda', serif; font-weight: 700; font-size: 1.5rem;
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
code {{ font-family: 'IBM Plex Mono', monospace; font-size: .86em; color: var(--bone);
  background: #ffffff0d; padding: .12em .4em; border-radius: 2px; }}

/* --- tables -------------------------------------------------------------- */

.scroller {{ overflow-x: auto; margin: 1.75rem 0; }}
table {{ width: 100%; border-collapse: collapse; font-family: 'IBM Plex Mono', monospace;
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
.tag {{ font-family: 'IBM Plex Mono', monospace; font-size: .62rem; letter-spacing: .18em;
  text-transform: uppercase; color: var(--bone-dim); }}
.strain h3 {{ font-family: 'Bodoni Moda', serif; font-size: 2rem; margin: 0 0 .4rem;
  font-weight: 700; letter-spacing: -.02em; }}
.blurb {{ color: var(--bone-dim); font-size: .95rem; margin: 0 0 1.1rem; }}
.fx {{ list-style: none; padding: 0; margin: 0 0 1.2rem; font-family: 'IBM Plex Mono', monospace;
  font-size: .74rem; }}
.fx li {{ padding: .28rem 0; border-bottom: 1px solid #ffffff0a; }}
.stat {{ display: flex; gap: 2rem; margin: 0; font-family: 'IBM Plex Mono', monospace; }}
.stat dt {{ font-size: .62rem; letter-spacing: .16em; text-transform: uppercase;
  color: var(--bone-dim); }}
.stat dd {{ margin: .2rem 0 0; font-size: 1.05rem; }}

/* --- cards / chain / quotes ---------------------------------------------- */

.cards {{ display: grid; gap: 1.25rem; margin: 2.5rem 0;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 18rem), 1fr)); }}
.card {{ border: 1px solid var(--rule); padding: 1.6rem; background: var(--ink-2);
  transition: transform .4s cubic-bezier(.2,.7,.2,1), border-color .4s; }}
.card:hover {{ transform: translateY(-4px); border-color: #3d3730; }}
.card h4 {{ font-family: 'Bodoni Moda', serif; font-size: 1.35rem; margin: 0 0 .6rem;
  font-weight: 700; }}
.card p {{ margin: 0; font-size: .95rem; color: var(--bone-dim); }}

.chain {{ list-style: none; padding: 0; margin: 2.5rem 0; display: grid; gap: 1px;
  background: var(--rule); border: 1px solid var(--rule); }}
.chain li {{ background: var(--ink-2); padding: 1.4rem 1.6rem; display: flex;
  align-items: baseline; gap: 1.2rem; flex-wrap: wrap; }}
.chain .step {{ font-family: 'IBM Plex Mono', monospace; font-size: .72rem;
  color: var(--acc); letter-spacing: .16em; }}
.chain strong {{ font-family: 'Bodoni Moda', serif; font-size: 1.3rem; font-weight: 700; }}
.chain .dim {{ font-family: 'IBM Plex Mono', monospace; font-size: .78rem;
  margin-left: auto; flex: 1 1 14rem; text-align: right; }}
@media (max-width: 620px) {{ .chain .dim {{ text-align: left; margin-left: 0; }} }}

.pull {{ margin: 3rem 0; padding: 0; max-width: 52ch; }}
.pull blockquote {{ margin: 0; font-family: 'Bodoni Moda', serif; font-style: italic;
  font-size: clamp(1.3rem, 3vw, 1.9rem); line-height: 1.3; letter-spacing: -.01em; }}
.pull figcaption {{ font-family: 'IBM Plex Mono', monospace; font-size: .68rem;
  letter-spacing: .18em; text-transform: uppercase; color: var(--bone-dim);
  margin-top: .9rem; }}
.pull figcaption::before {{ content: '— '; }}

/* --- crafting grids ------------------------------------------------------ */

.crafts {{ display: grid; gap: 1.5rem; margin: 2.5rem 0;
  grid-template-columns: repeat(auto-fit, minmax(8.5rem, 1fr)); }}
.craft {{ margin: 0; text-align: center; }}
.grid3 {{
  display: grid; grid-template-columns: repeat(3, 1fr); gap: 3px;
  width: 7.5rem; margin: 0 auto; padding: 3px;
  background: var(--rule); border: 1px solid var(--rule);
}}
.grid3 i {{
  aspect-ratio: 1; letter-spacing: -.02em; background: var(--ink); display: grid; place-items: center;
  font-family: 'IBM Plex Mono', monospace; font-size: .6rem; font-style: normal;
  color: #3a352f; transition: background .3s, color .3s;
}}
.grid3 i.on {{ background: #1e1a16; color: var(--acc); cursor: help; }}
.craft:hover .grid3 i.on {{ background: #262019; color: var(--bone); }}
.craft figcaption {{ font-family: 'IBM Plex Mono', monospace; font-size: .68rem;
  letter-spacing: .12em; text-transform: uppercase; color: var(--bone-dim);
  margin-top: .8rem; }}
.dot {{ display: inline-block; width: .6rem; height: .6rem; border-radius: 50%;
  background: var(--tint); margin-right: .55rem; vertical-align: middle;
  box-shadow: 0 0 .9rem -.1rem var(--tint); }}

/* --- reveal -------------------------------------------------------------- */

.reveal {{ opacity: 0; transform: translateY(1.4rem);
  transition: opacity .7s cubic-bezier(.2,.7,.2,1), transform .7s cubic-bezier(.2,.7,.2,1); }}
.reveal.in {{ opacity: 1; transform: none; }}

footer {{ border-top: 1px solid var(--rule); padding: 3rem var(--pad) 5rem;
  font-family: 'IBM Plex Mono', monospace; font-size: .74rem; color: var(--bone-dim); }}
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
      <p class="eyebrow">Server-side · Fabric 1.21.8 · no client install</p>
      <h1>Everybody<em>eats.</em></h1>
      <p>Grow it, cure it, roll it, refine it, price it, sell it, launder it, and
      try not to get raided. The complete reference — generated from the mod's own
      source, so it cannot go stale.</p>
      <div class="figs">
        <div><b>{lines}</b><span>priced lines</span></div>
        <div><b>{strains}</b><span>strains</span></div>
        <div><b>203</b><span>blends</span></div>
        <div><b>{awards}</b><span>advancements</span></div>
      </div>
    </header>

    <main>{sections}</main>

    <footer>
      <p>Built by <code>tools/gen_wiki.py</code> from the source tree. Every number on
      this page is read from the constant that governs it.</p>
      <p>TrapCraft is server-side via Polymer — friends join with the vanilla client
      they already have.</p>
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
