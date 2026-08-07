# Paranoia, The Ledger, Contracts — design

Date: 2026-08-07
Status: approved

Three features for TrapCraft (Fabric 1.21.8, server-side via Polymer). They
ship as one change because they share a foundation and, by decision, feed each
other: Contracts raise Heat, Heat drives Paranoia, and the Ledger stays neutral
utility.

## Acceptance bar

Every item below is craftable from materials obtainable on this map, present in
the Polymer creative tab, built on a real 3D model rather than a flat sprite,
and gives audible and visible feedback at the moment it does something. No new
blocks: the Polymer blockstate pools are nearly dry (`PLANT_BLOCK` had 3 free),
so all three features are items. Guides are updated in the same change.

## Verified primitives

Checked with `javap` against
`minecraft-merged-1.21.8-net.fabricmc.yarn.1_21_8.1.21.8+build.1-v2.jar`, not
assumed:

| Need | Signature |
|---|---|
| Per-player particles | `ServerWorld.spawnParticles(ServerPlayerEntity, T, boolean, boolean, double, double, double, int, double, double, double, double)` |
| Fake block for one player | `BlockUpdateS2CPacket(BlockPos, BlockState)` |
| Positioned sound for one player | `PlaySoundS2CPacket(RegistryEntry<SoundEvent>, SoundCategory, double, double, double, float, float, long)` |
| Phantom entity, no server entity | `EntitySpawnS2CPacket(int, UUID, double, double, double, float, float, EntityType<?>, int, Vec3d, double)` |
| Remove phantom | `EntitiesDestroyS2CPacket(int...)` |
| Village lookup | `ServerWorld.locateStructure(TagKey<Structure>, BlockPos, int, boolean)` with `StructureTags.VILLAGE` |
| Ledger GUI | `ScreenHandlerType.GENERIC_9X6` |

## 0. Shared foundation — `TrapPhantom`

"Show one player something that isn't there." Two real consumers, so it is
reuse rather than speculation.

```
fakeBlock(player, pos, state, ticks)
sound(player, pos, event, volume, pitch)
particles(player, effect, pos, count, spread, speed)
figure(player, type, pos) -> int id
clear(player, id) / clearAll(player)
```

Invariants, which are the whole reason this is one class:

- The world is never modified. Fake blocks are per-player render lies.
- Every fake block is held in a per-player revert map and restored from the
  real `BlockState` on expiry, disconnect, death, dimension change, and server
  stop.
- Phantom entity ids come from a private descending counter starting well below
  any real entity id, so they cannot collide with a real entity.
- All phantom ids are tracked per player and destroyed on the same lifecycle
  events as fake blocks.

## 1. Paranoia

### Meter

A 0–100 smoothed accumulator per player, held in memory. It decays on its own,
so losing it across a restart reads as sleeping it off and needs no
persistence.

Pressure inputs per tick:

- **Heat tier** from `TrapHeat.THRESHOLDS` — dominant term
- **Baked / Wired amplifier** — multiplier, not additive
- Light level below 7; underground; night
- **Solitude** — no other player within 48 blocks

Decay is fast in daylight, sober, at low heat, and **faster near another
player**. Safety in numbers is the counterplay and makes this a social mechanic
on a friends' server rather than a solo debuff.

### Tiers

Illusion only. Nothing spawns, nothing damages, nothing persists.

| Tier | Effect |
|---|---|
| 1 Twitchy | A stick snaps, a door creaks, a chest opens — positioned behind the player |
| 2 Watched | Footsteps echoing the player's own a half-second late; a torch flame particle where no torch exists |
| 3 Seeing things | A nearby block flickers to chest / cobweb / mob head for ~15 ticks, then reverts |
| 4 They're here | A motionless figure at render distance, removed the moment the player looks near it or closes within 20 blocks; whispers; rarely, the player's own name in chat |

Tier transitions announce on the actionbar, matching `TrapHeat`'s existing
warning style.

### Nerve Tonic

The counterplay item. Honey bottle + sugar + any small flower. Drinking crashes
paranoia to zero and grants ~90s immunity. Glassware model (there is precedent
in the `gen_textures.py` glassware section), drink particles, a descending calm
chime, and a vignette release on modded clients via the existing client module.

### Safety

`/paranoia` toggles per player. Self-disables for 60s after respawn.

## 2. The Ledger

**Craft:** book + compass + 2 amethyst shards. Real 3D model — closed book with
a pencil across it, built from `box()` elements.

**Scan:** iterates the `BlockEntity` maps of chunks within 32 horizontal / 16
vertical rather than ~139k raw block positions, filters to `Inventory`, and
recurses one level into shulker boxes stored inside containers. Modded
containers implementing `Inventory` (Crate Delight and similar) are picked up
for free.

**GUI:** `GENERIC_9X6`, one slot per distinct item type, sorted by count
descending, lore reading `x384 across 3 containers - nearest 12 blocks NE`.

**Ping:** clicking a result closes the GUI and draws a breadcrumb of `END_ROD`
particles through the air from the player to each holding container, one arc
per container, with a soft chime at each destination. Per-player, so a search
is private. Walking the trail is the interaction.

## 3. Contracts

**Burner Phone:** iron + redstone + copper + glass pane. 3D bar-phone model
with an antenna. Two-layer click on open; rings when the board refreshes.

**Board:** `GENERIC_9X6`, 3–5 jobs, refreshing daily. Example: *deliver 8x
Purple Haze, B+ or better, to the village at X,Z, within 12 minutes.*
Destination from `locateStructure(StructureTags.VILLAGE, ...)`. Quantity and
demanded grade scale with reputation.

**Accept** grants a compass carrying `DataComponentTypes.LODESTONE_TRACKER`
aimed at the village — vanilla, free, and it reads correctly with no custom
rendering. Accepting also raises Heat immediately. That is the coupling: higher
heat means better payouts, a chance of a pillager stickup en route, and
elevated Paranoia for the whole run.

**Deliver** by right-clicking any villager in the target village while holding
the goods. Validates strain, grade, and count; consumes; pays emeralds and rep
with a register chime and emerald particles. Missing the deadline costs rep
with a distinct failure sting. Countdown runs in the actionbar.

**Reputation lives on the phone** as a data component. No persistence
infrastructure, survives restarts for free, and losing the phone is a real
stake — the phone is cheap to recraft, the standing is not.

## Guides

These three features are cross-cutting rather than product-line specific, so
they get a third book: **the Street Handbook**, covering Heat, Paranoia,
Contracts, and the Ledger.

Three books should not mean three commands. `/trapguide` and `/cocaguide`
collapse into one:

```
/guide grower     the weed line
/guide refiner    the coca line
/guide street     heat, paranoia, contracts, the ledger
```

Plain Brigadier literals, so tab-completion and the error message on a bad type
come for free — no `SuggestionProvider` needed. Bare `/guide` prints the three
titles as clickable lines rather than erroring, using the 1.21.8 sealed
`ClickEvent` shape (`new ClickEvent.RunCommand("/guide street")`; the
pre-1.21.5 `new ClickEvent(Action, String)` constructor no longer exists).

The old `/trapguide` and `/cocaguide` names are dropped rather than aliased —
this is a three-player server and the new command tab-completes.

Built with the existing `page()` / `title()` / `body()` / `hint()` / `book()`
helpers. Every figure — paranoia tier thresholds, tonic duration, ledger
radius, contract timers and payout bands — is read from the governing constant,
never retyped. `README.md` is updated in the same change.

## Files

New: `TrapPhantom`, `TrapParanoia`, `NerveTonicItem`, `LedgerItem`,
`LedgerScreenHandler`, `TrapContracts`, `BurnerPhoneItem`.

Modified: `TrapContent` (registration, creative tab), `TrapGuide` (third book),
`tools/gen_textures.py` (sprites), `tools/gen_assets.py` (models, lang,
recipes), `README.md`.

Generated assets are produced by the generators and never hand-edited.

## Deliberately skipped

- No paranoia persistence across restarts — it decays anyway.
- No separate reputation store — it rides the phone item.
- No custom screen handler types — vanilla generic containers only.
- No new blocks — the Polymer pools cannot afford them.

## Open calls, flagged and accepted

- Rep on the phone means dying in lava costs your standing.
- Contract delivery accepts any villager in the target village rather than a
  specific NPC.
