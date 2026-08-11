# Kiosks and the working town: wages, one mint, and a counter players can buy at

Design agreed 2026-08-11. Extends the city, the shops, the homes and the
casino. Supersedes nothing.

## The problem

The town has infinite money.

Everything else in the city half is built and working -- houses are graded and
tenanted, a till and its shelves are a business, townspeople walk in and buy
things, what they buy is taxed into the vault, and contraband sold over a
counter is clean, declared and worth 65% of the street. Read the request that
prompted this design and most of it is already shipped.

What is not is the half that would make any of it *cost* something. A
townsperson's emeralds are minted at the moment they reach the counter. So is
a tenant's rent. So is a punter's stake. The town is a fountain: it has no
income, so it has no budget, so nothing a player builds can make it richer or
poorer, and "more residents = more money" is true only because the spawn rate
reads `population()`.

Three separate places mint money out of nothing:

| Site | What it invents |
|---|---|
| `TrapHomes` daily pass (~:661) | the rent a tenant pays |
| `TrapShops.buy` (~:633) | the price and duty of every shelf sale |
| `TrapFloor` punter | every stake laid on a casino floor |

This design replaces all three with **one** mint, at payday, and makes
everything downstream spend money that already exists.

## Goals

1. Residents **earn** before they spend, and what they earn is **taxed** into
   the vault as income.
2. The town's money is a **real constraint** that feeds back into demand, so a
   rich town is visibly better custom than a poor one.
3. Players can **buy from each other's kiosks**, not only sell to the counter.
4. The console is a console: it can **name the shop** and **show its shelves**.
5. Every block involved has a **proper 3D model**, coherent as a set.

## Non-goals

- Per-villager wallets, job assignment, or schedules. Payroll is aggregate.
- Manual shelf linking. See §4 -- the existing auto-join is kept deliberately.
- New blocks. Polymer's blockstate pools are nearly dry on this pack.

---

## 1. `TrapPayroll`: one mint

New file. Holds the town's purse, runs payday, and is the only place in the
mod that mints money on the town's behalf.

Payday hooks into the daily per-home pass `TrapHomes` already runs for rent --
the same loop, one step earlier, so a resident is paid and *then* pays their
landlord out of it:

```
for each tenanted home:
    gross  = wage(tier) * heads
    mint gross once                       (TrapMarket.minted -- it is new money)
    duty   = dutyOn(gross, Duty.INCOME)   -> TrapCity.receive, straight to the vault
    purse += gross - duty
    rent    = rentDue(tier, mood, heads)  -> now TAKEN FROM THE PURSE, not minted
```

`Duty.INCOME` already exists (10% start, 4--20 band) and is currently charged
only to players. This is the other half of it: the town pays income tax too.

**Wage is anchored to rent**, so a resident always clears their own landlord
and the two tables can never drift apart:

```java
WAGE[tier] = HomeSurvey.RENT[tier] * WAGE_MULTIPLE      // multiple starts at 3
```

`RENT` is `{0, 6, 14, 26, 42, 62, 86, 112, 140}` across grades 1--8, so:

| Grade | Earns | Rent | Left to spend |
|---|---|---|---|
| 1 | 18e | 6e | 12e |
| 4 | 126e | 42e | 84e |
| 8 | 420e | 140e | 280e |

`WAGE_MULTIPLE` is the single calibration knob for the whole economy and lives
next to the formula. It is not a config file, and it is not per-house.

**API:**

- `long purse()`
- `boolean spend(int amount)` -- true if the town could afford it and it moved
- `void payday(MinecraftServer)` -- called from the existing daily pass

`spend` is a boolean rather than a clamp on purpose. A shop that cannot be
paid must not sell, because a half-paid sale is a duplication bug wearing a
hat.

## 2. The purse has to bind

The trap in §1 taken alone: shopping is gated today only by spawn rate --
`MAX_SHOPPERS` is 6, each shopper buys one line, `maybeVisit` runs every 20
seconds. A town would bank thousands it never touches, `spend` would never
once return false, and the purse would be a scoreboard that changed nothing
but the income-tax line.

So the purse **modulates demand** rather than merely permitting it.
`maybeVisit` currently computes:

```java
pull = people * PULL * (lamps ? LAMPS_TRADE : 1)
```

It gains a purse-per-head term: a flush town shops hard, a broke town goes
quiet, and the same term gates how often a punter turns up on a casino floor.

That is what closes the loop the request actually asks for:

```
better housing -> higher wages -> more custom through your door
              -> more duty in the vault -> more public works -> better city
```

It also self-equilibrates -- purse up, spending up, purse down -- so
`WAGE_MULTIPLE` sets *where* the equilibrium sits, not whether there is one.
That is why it can be a single number and still be safe to tune.

## 3. The console

`ShopScreen` today has three live slots: takings, prices, shelf count. It
gains two things.

**A name.** Following `MailboxItem`'s rule -- *"an anvil is the only text
entry this mod has"* -- and for its reason: a register full of "HeezQ's shop
2", "HeezQ's shop 3" is a register nobody can read.

- `ShopTillBlock.onPlaced` reads the placed stack's `CUSTOM_NAME`, so an
  anvil-named till is a named shop the moment it lands, exactly like a named
  shulker.
- Clicking the name slot in the console while holding an anvil-named item
  renames the shop to that item's name. Roughly five lines, no new item, no
  chat prompt, no text-entry screen.

**A shelves page.** Each shelf listed with its distance from the till and what
is stocked under it. Clicking a row pings that shelf with particles and a
sound. This is the real answer to "which shelves are connected" -- the
question was never *control*, it was *visibility*.

## 4. Why shelves still join by themselves

The request asked for the console to be "connected to selected shelves".
`TrapShops` argues explicitly against that:

> Because an attachment is a thing that can be wrong. A shelf belongs to the
> nearest till, full stop [...] The alternative was a wand, a click mode and a
> saved list of positions that could disagree with the world.

That reasoning still holds, and manual linking would add exactly the failure
it names: a shelf that looks connected and isn't, or points at a till that was
broken. Auto-join stays. §3's shelves page solves the problem the request was
actually pointing at without inventing state that can go stale.

If two shops ever genuinely sit close enough to steal each other's counters,
the smallest fix is an exclude toggle in the console -- only exclusions saved,
always visible. Not built now.

## 5. Players buying from a kiosk

`MarketShelfBlock.onUse`:

- **owner** -> the console, as the till gives them
- **anybody else** -> a buy screen: the shop's lines at the shop's price plus
  duty, paid with `TrapMarket.take`, landing in the shop's till and the city's
  vault

So a shelf is the shop *window* and the till is the *back office*, which is
also what they look like. A player pays the same price a townsperson does,
including the same duty, which means a kiosk selling joints is a legal
dispensary for players too -- clean money, declared, no heat, 65% of street.

**The Market Stall survives**, unchanged. A stall is a table: one block, stock
the chest under it, auto-priced off the market, no console. A kiosk is a
business. They are different scales and both are worth having.

The blocks get renamed so nothing is called "market" twice -- today
`TrapMarket` (the global counter and price index), `MarketStallBlock` (a
player's stall) and `MarketShelfBlock` (a kiosk counter) all share a word that
means three things.

## 6. Visible shifts

Aggregate payroll with nothing to watch would be a spreadsheet. The existing
townsperson spawner (`arrive` / `shepherd`, which already walks a villager to
a shelf and sends them home) gains a second trip type: walk to a job site,
stand a shift with particles, leave.

Job sites are **what players already built** -- shop tills, casino tables,
stalls, the vault. No new block, no POI registration, no per-villager state.

This is explicitly cosmetic. Payroll is computed from the housing register and
runs whether or not a single chunk is loaded; the shifts are a *sample* of it
that happens to be visible. Nothing about the economy depends on a villager
reaching its destination, which is the only reason this is affordable.

## 7. Models

`shop_till` and `market_shelf` are one-element cuboids today -- a textured box
each. Both get rebuilt in `tools/gen_assets.py` as multi-element models
sharing a timber-and-awning language with `market_stall` (already 3 elements),
so a street of them reads as one set.

Assets are generated. `tools/gen_textures.py` then `tools/gen_assets.py`,
never hand-edited, and `tools/check_models.py` catches typo'd texture refs and
out-of-range elements.

## 8. Persistence and checks

**Saved:** `trapcraft-town.txt` -- purse and last payday. The shop's name
already has a field in `trapcraft-shops.txt`.

**Tested:** `TrapMath.wage(tier)` and the purse-to-demand curve get cases in
`FormulaTest`, which is where this repo's formulas are checked offline. The
cases that matter: wage always exceeds rent at every grade, an empty purse
yields no demand, and `spend` never returns true for more than the purse
holds.

**Logged:** `trapcraft-city.csv` gains `purse`, `wages` and `income_tax`
columns, so the multiple is tuned off a day's data rather than off a feeling.

## Risks

- **Blockstate pools.** Nearly dry on this pack. Nothing here registers a new
  block; the models replace existing entries.
- **Double-spend at the seams.** Three mint sites become one purse with a
  boolean gate. Every converted site must fail closed -- no sale, no stake, no
  rent -- rather than proceed unpaid.
- **Calibration.** The first `WAGE_MULTIPLE` will be wrong. It is one number,
  the CSV says which way to move it, and §2's feedback means a wrong value is
  a slow town or a busy one, not a broken one.
