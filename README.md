# TrapCraft

Cannabis cultivation for TrapPack (Fabric 1.21.8). **Server-side only** — built
on Polymer, which is already in the pack, so nobody installs anything. Friends
join with the vanilla client they already have and accept the server resource
pack when prompted.

## Building

The Gradle launcher JVM must be **21 or newer** — Loom refuses to load on
anything older, and macOS often defaults to an older JDK:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 24) ./gradlew build
```

The `java{}` toolchain asks for 21 specifically. There is no need to install
one: `settings.gradle` pulls in the foojay resolver, so Gradle downloads a 21
for the compile itself on first run.

Assets are **generated** — never hand-edit anything under
`src/main/resources/assets/`, the next run overwrites it:

```bash
python3 tools/gen_textures.py && python3 tools/gen_assets.py
```

Read [docs/TRAPS.md](docs/TRAPS.md) before debugging anything that looks
impossible -- most of this mod's expensive bugs were the client and the server
disagreeing, not the code being wrong.

Four checks, all offline and all fast:

| Command | Catches |
|---------|---------|
| `./gradlew test` | the pressure, payout and ledger formulas |
| `python3 tools/check_models.py` | typo'd texture refs, out-of-range elements |
| `python3 tools/check_pages.py` | guide pages that would silently truncate |
| `python3 tools/check_shaders.py` | post-effect JSON |
| `python3 tools/check_stock.py` | book lines that drop silently, goods too cheap to sell, and any vanilla recipe whose result sells for more than its ingredients cost |

## The loop

```
seeds ──plant on farmland──> plant (4 stages) ──break──> fresh buds + seeds
fresh buds ──drying rack, 4 min──> cured buds ──+ paper──> joint ──smoke──> Baked
```

Three strains, each with its own effect profile:

| Strain | Baked level | Duration | On top of that |
|--------|-------------|----------|----------------|
| Kush   | II          | 90s      | Slowness, Regeneration |
| Haze   | I           | 60s      | Speed, Jump Boost |
| Purp   | III         | 120s     | Night Vision, Nausea |

**Baked** is a real custom status effect, not stacked vanilla ones. It burns
saturation then hunger (the munchies), heals you slowly while you're well fed,
and chips your health if you smoke on an empty stomach.

## The three lines

Weed is the short one. There are two longer ones behind it, and each is meant
to feel like a different job rather than the same job with bigger numbers.

```
weed    seeds ──> plant ──> drying rack ──> joint                       Baked
coca    seeds ──> bush  ──> leaf press ──> refiner ──> powder           Wired
poppy   seeds ──> poppy ──> scoring table ──> wash pot ──> acetylator   Nod
```

The poppy line is deliberately the hard one:

* **Slowest crop in the mod**, and the only one that needs light 12 — no
  cellar farms, so the crop worth the most is the one you cannot hide.
* **The wash pot carries no fire.** It only advances while something under it
  is burning, which makes the middle step a build rather than a click.
* **The acetylator can lose the batch.** The refiner's worst case is a bad
  grade; this one's is an empty machine one step past peak, with two steps of
  grace where the refiner gives five.
* Roughly 3× powder's price per unit, off about twice the field.

The three highs are shaped as three different bills. **Baked** charges you
while it lasts (hunger). **Wired** charges you when it ends (the crash).
**Nod** charges you days later, on the meter below.

## The habit

`/addiction` (or `/habit`). One meter per thing you take, 0–100 — and every
weed strain has its own, so a Purp habit wants Purp and a shed full of Kush is
no help at all.

```
pressure = (meter / 100) × min(1, time since your last hit / that drug's period)
```

A product, not a timer. Both factors have to be large, so a light habit is not
merely slow to hurt you — it is *incapable* of it, because the first factor
caps the result below the band it would need. Bands at 20 / 45 / 72: the nag,
then your hands stop working, then everything does.

|  | Per hit | Hits to max | Craving ripens | Clean in |
|--|---------|-------------|----------------|----------|
| weed | 2.0 | 50 | 14 min | 83 min |
| cocaine | 6.0 | 17 | 7 min | 167 min |
| heroin | 16.0 | 6 | 3 min | 333 min |

The meter bleeds off **twice as fast while you are properly sick**, so riding
out the worst of it is the cure. Nerve tonic holds the symptoms off without
touching the meter — an afternoon's work, not a way out. Taking the thing you
crave clears it instantly and pays a bonus scaled by how bad it had got, which
is the trap and is meant to be.

**The street gets hooked too, and on you specifically.** Every sale builds a
per-dealer client list, weighted by the drug — dope moves it about eight times
faster per unit than a joint. What it buys: customers who turn up sooner, ask
for the strong stuff, and take more per visit, plus tenants who start asking
for bags instead of joints. Stop selling and it fades.

Numbers live in `Drug.java` (one row per thing you can be hooked on) and the
formula in `TrapMath.habitPressure`, which is the half a plain JUnit run can
reach — see `AddictionTest`.

## Paranoia

A big operation makes the world feel like it's watching you. A meter builds
from your **Heat** tier, how high you are, darkness, night, and being alone,
and it decays fast in daylight, sober, or **near another player** — company is
the counterplay, which makes it a social mechanic rather than a solo debuff.

Four tiers, escalating from noises behind you, to footsteps echoing your own
and phantom light, to blocks that briefly aren't what they are, to a
motionless figure at render distance that vanishes if you look straight at it.

**Every bit of it is a packet sent to one client.** Nothing spawns, nothing
deals damage, and the world is never modified — `TrapPhantom` tracks every
fake block and reverts it on expiry, respawn, disconnect, and shutdown. A
**Nerve Tonic** (honey bottle + sugar + any small flower) clears the meter and
holds it down. `/paranoia` turns the whole thing off per player.

## The Ledger

Book + compass + 2 amethyst shards. Right-click to read every container within
32 blocks across and 16 up or down — including inside shulker boxes stored in
chests — and get a sorted list of what you own and where.

Click a row and it draws a line of light through the air to each chest holding
it. It walks the chunk block-entity maps rather than the ~139k block positions
in that volume, and skips unloaded chunks instead of dragging them into memory.

## Contracts

A **Burner Phone** (copper + iron nuggets + amethyst + redstone) is a job
board: five deliveries a day, seeded by the world day so the list is stable
until tomorrow. Take one and you get a compass pointed at the village.

Accepting a job **puts heat on you**, that heat feeds Paranoia for the whole
run, and it is also what makes the job pay — hot work pays better. Deliver by
right-clicking any villager near the drop; miss the clock and it costs rep.

Reputation lives on the phone as a data component. No persistence code, it
survives restarts for free, and losing the phone loses the standing with it.

## Stalls

The stall you place is **yours**. Put a chest or barrel directly underneath it
and everything in it goes on sale to everybody else at 85% of the market price;
right-click your own stall to take the till. `/stalls` lists who is selling
what and where.

The same block still opens the open market when it's yours or unclaimed, and
that is deliberate — the counter is the backstop that means nobody is ever
stuck, and every stall in town is a cheaper way to get the same thing off
somebody who had it spare.

The numbers are the whole point:

| Route | Grower gets | Builder pays | Lost |
|-------|-------------|--------------|------|
| The counter | 45e | 100e | **55e to nobody** |
| A stall | **81e** | **85e** | 4e pitch fee |

Both sides do better than they would at the market, neither takes anything
from the other, and the spread that used to evaporate is split between them.
Prices follow the market automatically, so a stall is never mispriced and
there is no price editor to fill in — the only decision is what to stock.

## Wands

The far end of the market, and the only shelf on it that answers "what am I
saving *for*". Five items, 25,000e to 120,000e, when a fat contract pays a
couple of hundred. All five are one class (`WandItem`) and one sprite recoloured
five ways; a right-click is the whole interface, with sneak as the second verb
where there is one.

| Wand | Right-click | Cooldown | Shelf |
|------|-------------|----------|-------|
| Rush | Throws you where you're looking, then 6s of slow falling. Sneak blinks 12 blocks | 2s / 5s | 25,000e |
| Reaping | Harvests and replants everything ripe in 9×9 around you, into your bag | 3s | 40,000e |
| Seams | Lights up every ore within 10 blocks, through stone, for your eyes only | 10s | 55,000e |
| Masons | Extends the face you clicked by up to 8 blocks, out of your inventory | 1.5s | 80,000e |
| Storms | Lightning where you're looking, 12 damage, no fire, only hostiles | 15s | 120,000e |

They first shipped at a fifth of those prices, against players holding 30–50k:
the whole rack cost one stash. A sink priced under what its buyers already have
is not a sink.

Each one carries its own description as default lore (`Kind.blurb(tier)`) —
what it does, how you use it, how often, and what the next tier would cost — so
it explains itself in the hand, in a chest and on the shelf. The shelf tag
rewrites lore wholesale, so `priceTag` puts any self-description back on top.
Every figure in it is read from the same constants the guide book quotes, the
cooldown included: the item draws a sweep that says you are waiting but never
how long for.

**Three tiers each.** The one on the shelf is the floor; **sneak + right-click
an enchanting table** spends cores for the next step, twice. A tier is −20% off
the cooldown and +25% on the reach, the radius or the damage
(`TrapMath.wandCooldown`/`wandReach`/`wandDamage`), so III comes round twice as
often as I and reaches half again as far. Two multipliers for all five rather
than a ladder per wand: one promise a player can hold in their head, and one
set of numbers to keep honest against five tooltips and a book.

A step costs **half the wand's shelf price, then all of it** — 12,500e then
25,000e for Rush, 60,000e then 120,000e for Storms — read off the catalogue
line (`ShopStock.matching`) rather than typed in, and paid through
`TrapMarket.take` and the ledger like every other counter. It first shipped
charging *cores* — more breeze rods, more nether stars — which reads well and
prices nothing: those are market lines like everything else, and a shelf that
sells a sniffer egg for pocket change turns "two more eggs" into an upgrade you
buy with an afternoon's rent. Emeralds are the one unit the market cannot
undercut, because they are the market. A finished wand has therefore cost half
again what it cost to buy, which is the only sink at this end of the shop.

The bench is code rather than a recipe because a vanilla ingredient matches on
the *item* and cannot see components: a shapeless "wand plus payment" recipe
would take a finished wand and hand back a fresh one. The lore quotes the
*rule* rather than the figure — lore is baked at registration and rewritten on
upgrade, and at neither moment is there a catalogue to read; clicking without
the money says the number. Storms is the one exception to the ladder — the bolt
lands harder and sooner, but never from further than 40 blocks, because reach
on a wall-piercing bolt is the turret the cooldown was raised to prevent.

Three things they get right that are easy to get wrong:

- **The shop will not buy them back at any price** (`TrapScrap.refusal`, and
  `TrapMarket.sellPrice` returns 0). Each is craftable, and a counter paying
  half of six figures for a wand made from a few thousand emeralds of nether
  stars is a crafting table with a mint attached.
- **Reaping goes through each crop's own `harvest()`**, never
  `getDroppedStacks`. Breaking one of this mod's plants runs the loot table and
  returns a *seed*; the buds only come off a right-click. Same trap the crew
  fell into.
- **Blink walks the player's hitbox out in half blocks** rather than raycasting.
  A ray finds the wall's face, which is precisely where you must not land.

Crafting is the other route and is paid in effort rather than emeralds: blaze
rods, amethyst, and **one to three** of the ingredient that decides which wand
it is — 1 breeze rod, 2 sniffer eggs, 2 echo shards, 3 recovery compasses,
3 nether stars. The count scales with the shelf price on purpose; at one star
apiece the shelf would be decoration.

## The crew

`/crew hire` takes somebody on where you are standing; `/crew` opens the board.
A hand works a box around that spot, puts everything in the nearest chest, and
takes a wage every five minutes whether the harvest was good or not.

Everything past picking is bought: **pace** (a job every 10s down to every 1.5s),
a **bigger patch** (12 to 26 blocks), and five jobs — farmhand, curing, sowing,
tilling, fertilising. Every purchase also puts the wage up, so a hand you can't
keep busy loses you money, and missing a payday means they walk with everything
you taught them.

Two things worth knowing about how it works:

- They are scaled to **0.85**. `FarmlandBlock.onLandedUpon` only tramples
  entities whose `width² × height` clears 0.512; a villager is 0.70 and a hand
  is 0.43, so they physically cannot un-till your field. No mixin, no gamerule.
- They are steered with the brain's `WALK_TARGET` memory, not the navigator.
  Going round the brain is why the first one wandered off — the stroll tasks
  cancelled it every tick. Setting the memory both moves them *and* starves the
  strolls, which require it to be absent.

## The guide books

`/guide` — ten books under one command, with tab-completion:

| Command | Covers |
|---------|--------|
| `/guide grower` | growing, curing, rolling, heat |
| `/guide refiner` | the coca line |
| `/guide chemist` | the poppy line, end to end |
| `/guide habit` | addiction, cravings, withdrawal, the street |
| `/guide street` | paranoia, the ledger, contracts |
| `/guide crew` | hiring hands, the ladders, what they cost |
| `/guide casino` | running a floor |
| `/guide city` | the vault, the rates, the public works |
| `/guide housing` | mailboxes, grades, rent, the ward |
| `/guide police` | the station, the budget dial, crime, fines |

Every number on every page is read from the constant that governs it, so
retuning a mechanic retunes the book and it can never quietly start lying.
`tools/check_pages.py` catches pages that would overflow the ~14×19 book page
and silently truncate.

## The client module (optional)

The same jar is both the server mod and an optional client mod. Drop it in a
player's `mods/` folder and they additionally get the custom high: a pulsing
colour wash with a vignette, a slow camera drift, and a gentle FOV breathe —
tinted by strain, scaling with the Baked amplifier.

**It is optional on purpose.** Without it everything still works, so adding a
strain never forces anyone to reinstall the pack. Nothing about the server
requires it, and players with and without it can share a world.

Requires `fabric-api` and `polymer-bundled` on the client — both already in
TrapPack, so it's just the one jar.

The camera drift offsets the *camera*, not the player's rotation, so your
crosshair still points where you aimed it. Tuning constants are at the top of
`client/TrapCraftClient.java`; `MAX_SWAY_DEGREES` is the one to lower first if
anyone finds it queasy.

## Smoking

Joints use `UseAction.TOOT_HORN` — the only vanilla use animation that raises
the item to the mouth, so it reads as smoking on an unmodified client. Campfire
crackle while you drag, fire-extinguish on the exhale, and a puff of
`CAMPFIRE_COSY_SMOKE` in your look direction. While Baked you trail a haze that
thickens with the strain's amplifier; it's server-spawned, so everyone nearby
sees it too.

## Getting started in-game

Seeds come from wandering traders (5 emeralds, one strain per trader) or
farmer villagers at journeyman. Traders also buy cured buds and joints.

Craft a drying rack with 8 sticks and 2 string (string in the middle row).

## Commands (Essentials-lite)

`TrapEssentials.java`. Everything answers **only the person who typed it** —
`sendFeedback(.., false)` skips the op broadcast, so `/gm c` doesn't announce
that you went creative.

| Command | Who | What |
|---------|-----|------|
| `/gm <c\|s\|a\|sp\|0-3> [players]` | op | gamemode, quietly |
| `/day` `/noon` `/night` `/midnight` | op | rolls forward to the next one, keeps the day counter |
| `/sun` `/rain` `/storm` | op | weather, 10 min |
| `/heal` `/feed` `/fly [players]` | op | `/fly` toggles |
| `/sethome [name]` `/home [name]` `/delhome <name>` `/homes` | everyone | 10 per player |
| `/addiction` `/habit` | everyone | your meters, and the street's appetite for you |
| `/spawn` | everyone | world spawn |
| `/back` | everyone | where you died, or where you were before the last teleport |

`/tpa` is **not** here — `tpa-utilities` is already in the pack and does it
better. Homes live in `world/trapessentials.json`; `/back` is memory-only and
resets with the server.

## Editing it

Textures and JSON are **generated**, not hand-authored. Don't edit the PNGs or
the model/loot/recipe JSON directly — the next run overwrites them.

```bash
python3 tools/gen_textures.py && python3 tools/gen_assets.py
```

Colours live in `STRAINS` at the top of `tools/gen_textures.py`. Sprites are
ASCII maps in the same file. Adding a fourth strain means one enum constant in
`Strain.java` plus matching entries in both scripts.

## Build and deploy

Needs JDK 21 (`brew install openjdk@21`); Gradle comes from the wrapper.

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew build
```

The jar goes into `overrides/mods/` inside `../modpacks/server.mrpack`, next to
`cultural-delights-polymer-port`. `server.mrpack.pre-trapcraft` is the backup
from before it was first added — restore that file to remove the mod entirely.

`tools/deploy.py` bumps the pack's `versionId` on every run (1.0.16 → 1.0.17 →
…). That number is what Prism shows your friends, so it's how anyone answers
"which build are you on?" when something misbehaves. Re-exporting from the
Modrinth App resets it to whatever that app writes; `bump()` tolerates any
format and just increments the trailing number. `python3 tools/deploy.py
--selftest` checks that parser.

Note the pack is only ever written by `deploy.py`, on your machine. The server
mounts `./modpacks` **read-only** and only reads the file at container start —
which is why every change needs a restart, and why the server can never corrupt
the pack.

## Known limits

- **No custom villager profession.** `VillagerProfession` is a `final` record in
  1.21.8 and can't implement Polymer's `PolymerVillagerProfession`, so an
  unknown profession id risks a registry desync on join for vanilla clients.
  Dealing runs through wandering traders and farmers instead. See the note in
  `TrapTrades.java`.
- ~~Drying progress is invisible.~~ Fixed: the rack now has a texture per
  (strain, dryness), so buds visibly darken as they cure and stage 3 shows
  gold trichome tips — "ready to collect" is readable from across the room.
- The rack holds one strain marker, not a stack. That's why it needs no
  BlockEntity — see the note in `DryingRackBlock.java`.
- **Blockstate pools are nearly dry on this pack.** Polymer hands out unused
  vanilla blockstates from per-shape pools, and `cultural-delights` plus
  `farmers-delight-polymer-patch` have taken most of them. At last boot
  `PLANT_BLOCK` had **3** free and we need 12, so the crops ride on
  `VINES_BLOCK` (~98 free, also collisionless) instead. Every boot logs a
  `Polymer pool ... = N` table — **read it before adding blocks**. If a pool
  runs out, `TrapPolymer.requestOrFallback` degrades to a vanilla lookalike and
  warns, rather than returning null (a null crashes the server at registration,
  because Polymer's collision mixin dereferences it while building the shape
  cache).

## Verified / not verified

Checked live against the real pack over RCON: blocks register and place, crops
survive on farmland and hold their `age` state, the crop loot table drops,
the drying rack keeps its `occupied`/`dryness` state, items resolve with their
display names, and the custom `Baked` effect applies.

**Not** verified, because it needs a real client connected: right-clicking the
rack to insert and collect buds, smoking a joint end-to-end, the trader offers
actually appearing, and resource-pack delivery to a vanilla client. Worth one
pass in-game before you tell everyone it's live.
