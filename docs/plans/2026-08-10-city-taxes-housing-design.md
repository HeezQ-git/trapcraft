# The City: earnings ledger, housing, population, tax and law

Design agreed 2026-08-10. Supersedes nothing; extends the market, the stalls,
heat and the casino.

## The problem

Three people play this server and never need each other. Everything earns in
parallel against an infinite counter, and the city they are actually building
-- which is the part they enjoy -- is invisible to the game. Nothing measures
it, nothing rewards it, and money has nowhere to go once you have plenty.

This adds the missing half: a city that notices what you built, pays you for
it, taxes you on it, and occasionally changes the rules.

## Goals

1. Make building a city a **game loop** with a measurable score, not scenery.
2. Give the drug half and the civic half a **reason to argue** over the same
   ground, so choosing where to put things matters.
3. Give money a **sink and a purpose** past personal wealth.
4. Keep it **co-operative**. Nobody robs anybody; everybody's building helps
   everybody.

## Non-goals

- Player-versus-player anything.
- A voting or proposal UI. Three friends on a call can agree out loud.
- Land claims as protection. This is not a grief-prevention system.

---

## 1. The earnings ledger

Built first. It is the balance instrument AND the audit's data source, so it
has to exist before anything downstream can be tuned or enforced.

`TrapLedger.record(player, Source, delta)` called at the sites that represent
real earning -- customer sale, dealer payout, contract payout, stall sale,
casino stake and win, market buy and sell, scrap counter, crew wage, rent,
tax. Deliberately NOT a wrapper around `TrapMarket.take/pay`: those are called
from twenty files and do not know why they are moving money, and threading a
reason through all of them would be a large diff for a worse answer.

**Sources:** `WEED`, `COCA`, `FOOD`, `MARKET`, `STALL`, `CASINO`, `CONTRACT`,
`CREW`, `RENT`, `TAX`, `INVEST`, `SCRAP`.

**Output:** append-only `world/trapcraft-ledger.csv` (day, player, source,
delta), plus a daily human-readable rollup per player per source. The rollup
is what gets read to answer "is the casino owner out-earning the farmer".

**Declared vs undeclared:** each source is flagged. `WEED` and `COCA` sales to
dealers and customers are undeclared; everything else is declared. That single
flag is what the audit reads.

## 2. Houses

### The mailbox

Craft a mailbox, place it **inside** the room, right-click to survey. On a
pass the house is registered under an **anchor** -- the position surveyed
from. The mailbox block, when broken, drops an item **stamped with the house
id and name**, so it can be re-placed anywhere (outside the door, on the
street) and re-attach to the same house.

This is the casino card pattern: the mailbox is the key, the ledger is the
vault. It is already proven in this codebase and it is what makes "inspect
inside, then move outside" work at all.

### What counts as the house

A flood fill from the anchor, in two phases.

The first draft of this said the fill should pass through doors, so that a
bedroom with the door shut still counted. That is wrong and would have failed
on every house ever built: a front door is a door, so the fill would walk
straight out of it and into the world. "Passes through doors" and "sealed
room" are the same sentence contradicting itself.

**Phase one -- doors are walls.** The fill expands into air only, plus a short
allowlist of things you can stand in (ladders, torches, carpets, signs,
flowers). Doors, gates, trapdoors, glass, fences and slabs all stop it. So it
closes on any normal building. Capped at 4096 blocks and ~48 blocks from the
anchor on any axis.

**Phase two -- probe each door on the boundary** with its own bounded fill of
about 512 blocks:

- Closes within budget: it is a room. Merge it, and queue any doors it found.
- Escapes its budget: that is the outdoors. Do not merge. That door is a front
  door.

Iterative rather than recursive, against a global budget of ~8192, so nested
rooms and porches resolve without a depth limit.

Worked example, and the case that settled the design:

    [bedroom]--door--[hall]--door--[porch]--door--[street]

The bedroom probe closes and merges. The porch probe closes -- doors are walls
to a probe too, so the outer door bounds it -- and merges. The outer door's
probe escapes, so it does not merge, and it is the front door. Two doors side
by side as a wide entrance both escape and neither merges. A garage with an
inner door and a garage door resolves the same way, with no special case.

Consequences, all of them wanted:

- A bedroom with the door shut counts.
- Leaving the front door open does not break the house, because door state is
  irrelevant to a fill that treats doors as walls.
- Stairs and ladders make multi-storey work with no extra code.
- A sealed void with no way in does not count, closing the obvious exploit of
  walling off a cavern to inflate floor area.
- The house learns its own exterior doors for free -- the probes that escaped
  -- which step 3 needs for tenant pathing and for posting letters.
- "This room isn't sealed" now means a genuine hole in the wall rather than
  "you built a door".

**It cannot loop.** The fill is breadth-first against a visited set, so a block
is never revisited whether the fill escapes or not; the caps bound total work;
and it stops dead at an unloaded chunk border rather than pulling the world
into memory. Worst realistic case is a few thousand block reads, once every
two minutes.

Minimum 3x3 floor. Claims may not overlap; the fill stops at another house's
claim.

### Tier

Floor area, amenities present, light level and count of distinct decoration
blocks produce a tier 1-5. Higher tier attracts a better tenant paying more
rent. The mailbox displays the checklist as a **readout**, filled by the scan
-- there is no right-clicking eight blocks to tick them off, because the fill
has already visited every one of them.

### Re-survey

Every two minutes, houses in loaded chunks re-survey **from the anchor**. Tier
is recalculated; a broken wall, a removed bed or a dark room shows up here. If
the anchor block itself is filled in, the house reads as collapsed.

## 3. Tenants and population

A tier-1-or-better house attracts a villager within a few minutes. They:

- **Pay rent into the mailbox** every five minutes. Offline-safe, collected by
  right-clicking, exactly like the stall till.
- **Shop at the nearest stall**, which is how housing creates demand and how a
  market square gets customers.
- **Hold a mood** that decays from darkness, mobs spawning inside the claim,
  damage to the house, and heat.
- **Write letters** to the mailbox when unhappy. "The light in the stairwell
  is out." "Something is growing next door." This is the tutorial, and it
  needs no wiki.

**Heat evicts.** A house inside a grow's heat radius makes its tenant nervous
and eventually makes them leave. This is the load-bearing tension of the whole
design: the plantation and the apartment block cannot be the same place.

**Population** is the count of housed tenants. It feeds casino punter volume,
customer visit rate and contract board size -- all three already read a "how
busy is it" number that currently has nothing behind it.

## 4. Tax and the treasury

**Taxed:** stall sales, market counter sales, casino handle, contract payouts,
rent.
**Untaxed:** sales to dealers and customers -- the black market.

Revenue pools into a **city treasury**. Anyone may spend it; every purchase is
announced server-wide. No vote, by choice.

**Upgrades:** Night Watch (raid cooldown up), Market Depot (stall capacity),
Paved Roads (+1 tier to nearby houses), Housing Grant (population cap up).

## 5. Audit and laundering

The revenue office compares **wealth growth against declared income**. Too
large a gap triggers an assessment: pay a share of the unexplained, or take an
inspection.

The escape is **laundering** -- turnover through your own stall or your own
casino counts as declared. Drugs stay the best money per hour; the money is
dirty until it has been through something legitimate.

This is the piece that turns drugs, casino, stalls and tax into one system
rather than four.

## 6. The constitution

`/law` opens a book of current tax rates, active acts, and when each passed
and why.

**The book keeps itself current two ways**, because they cover different
moments:
- It **rewrites its pages when right-clicked**, so it can never be stale when
  read.
- A **law change sweeps online inventories** and rewrites any copy found, with
  a chat line -- a book already open on screen will not refresh itself.

**Laws are reactive, never random:**

| Trigger | Act |
|---|---|
| Server heat high for two days | Crackdown: raid cooldown down, searches harsher |
| Treasury empty | Levy: rates up until it recovers |
| Population booming | Housing Standards: minimum tier for rent rises |
| Large unexplained wealth server-wide | Revenue Drive: audits more frequent |

Each announces with its reason and repeals when the condition clears. The
constitution ends up reading as a history of what the server did.

## Data and persistence

Following the existing pattern -- a side ledger keyed by position or id, saved
as plain text under the world folder, written on change rather than shutdown.

| File | Holds |
|---|---|
| `trapcraft-ledger.csv` | append-only earnings rows |
| `trapcraft-houses.txt` | id, name, owner, dimension, anchor, bounds, tier, till, tenant, mood |
| `trapcraft-city.txt` | treasury balance, purchased upgrades, active acts and their start day |

## Failure modes to guard

- **Flood fill escaping** into a cave system: volume cap, fails closed.
- **Overlapping claims**: bounding-box intersection test on registration.
- **Anchor buried**: house reads as collapsed rather than throwing.
- **Survey cost**: loaded chunks only; every two minutes, not every tick.
- **Tenant duplication** across restarts: tenant UUID stored, entity re-found
  the way the crew re-finds a hand.
- **Money invented**: rent and tax move through the existing take/collect and
  pay/handOver split, so a transfer is never reported as creation.

## Testing

Unit tests for everything that does not need a world: tier scoring, tax
arithmetic, the audit gap, box overlap, flood-fill bounds. A new checker for
the new invariants. The five existing gates keep running.

In-game behaviour -- tenants pathing, letters arriving, the fill agreeing with
what a person would call a room -- needs a playtest and is called out as such.

## Build order

| Step | What | Stop and check |
|---|---|---|
| 1 | Earnings ledger | Numbers look sane for a session |
| 2 | Mailbox, flood fill, tier, checklist | Building a room and getting a grade is fun on its own |
| 3 | Tenants, rent, mood, letters, heat eviction | It is a loop |
| 4 | Population feeding punters, customers, contracts | It is a tycoon |
| 5 | Tax, treasury, upgrades | It is a city |
| 6 | Audit, laundering, constitution, reactive laws | It has politics |

Steps 5 and 6 are worthless if 2 to 4 do not land. Each step ships and gets
played before the next starts.

A guide book -- `/guide city` -- ships alongside, built the same way as the
other five: every number read from the constant that governs it.
