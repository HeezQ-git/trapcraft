# Somewhere for the money to go

## The problem

Players have 10–50k spare and nothing to spend it on but the casino. The
economy has faucets — weed, contracts, rent, the counter — and no drain.

The evidence, read off the live server on 2026-08-13:

- The city has **five unbuilt public works worth 28,000e** (Lamps 3k, Watch 4k,
  Roads 6k, Tram 7k, Exchange 8k) and a purse of **3,097e**.
- **Every `TrapCity.receive` call is a duty skimmed off somebody else's
  transaction.** There is no path for a player to put money into the treasury
  at all. The largest sink in the mod is unreachable by design.
- The income duty has taken **38,750e** — about ten times every other duty
  combined. The money is real; it has nowhere to go.

## Shape

Three pieces, in order, each shippable on its own.

### A. The treasury takes donations, and the works have tiers

The cheap, high-value one: 28,000e of finished content sits behind a missing
button.

- A **donate slot** on the city vault screen. Click holding emeralds to pay
  them in; the money moves through `TrapMarket.take` so it leaves circulation
  the way every other payment does.
- Public works gain **levels** rather than being built once. `built(work)`
  becomes `level(work) >= 1`, so all eleven existing call sites keep working
  untouched. Each tier costs **2.5×** the last, capped at **III**.
- Runway: 7 works × 3 tiers at 2.5× ≈ **200k+**. At 10–50k spare that is
  months rather than an afternoon.
- Save format grows a level on the `built` line, read length-guarded so an
  older register still loads.

### B. Wealth costs something to hold

What stops A being a one-off. Property rates, per day, from the owner's pocket
into the treasury: per casino cabinet, per shop, per house let. Money drains
unless it is working, and the drain refills the purse, so the loop closes:

> fund the works → the works cost upkeep → upkeep drains you → fund again

Right now the treasury is a scoreboard nobody can write to.

### C. Something new to run

The content piece, deliberately last. A business with a heavy capital cost,
staff, stock and upkeep.

It comes last because **a profitable business is a faucet with an entry fee,
not a sink.** Built first it would make the surplus worse. Built after B, the
rates and tier upkeep exist to absorb what it earns.

## Not doing

- Cosmetic-only sinks (statues, titles). Ruled out by the user in favour of
  things that do something.
- Scaling every work's effect per tier. Only the ones that are already a
  simple multiplier — the rest take the level as a flat repeat of the same
  benefit until somebody wants otherwise.

## Checks

Source-read tests in the style of `ResidentTest`, pinning the two things that
fail silently: that `built()` stays true for a levelled work (or every effect
in the mod switches off at once), and that the save round-trips a level while
still reading an old line.
