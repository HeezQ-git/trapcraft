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
| `python3 tools/check_stock.py` | book lines that drop silently, goods too cheap to sell |

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

## The guide books

`/guide` — three books under one command, with tab-completion:

| Command | Covers |
|---------|--------|
| `/guide grower` | growing, curing, rolling, heat |
| `/guide refiner` | the coca line |
| `/guide street` | paranoia, the ledger, contracts |

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
