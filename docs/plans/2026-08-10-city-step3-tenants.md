# City step 3: somebody lives there

> Step 3 of `2026-08-10-city-taxes-housing-design.md`. Steps 1, 2 and most of
> 5 have shipped; 4 is a third done (population drives the shops).

**Goal:** a graded house attracts somebody who lives in it, pays rent into the
mailbox, minds whether the place is dark or broken, writes when it is, and
leaves if your grow gets close. After this, housing pays and the two halves of
the mod are fighting over the same ground.

---

## The tenant is a record; the body is decoration

Same split as the crew. A `Tenant` lives on the `Home` — name, mood, the day
they moved in, and the uuid of a villager. The villager is spawned when the
house's chunk is awake and re-found or replaced when it is not, and **rent
accrues either way**. A landlord who has to stand in the street to get paid is
not a landlord.

No whip needed: a tenant with no body simply gets a new one next time anybody
is near.

## Rent

Once per in-game day, into a till on the house. Right-click your own mailbox to
empty it, exactly like a stall or a shelf.

| Grade | Rent a day |
|---|---|
| 1 | 24e |
| 2 | 60e |
| 3 | 120e |
| 4 | 190e |
| 5 | 280e |

Anchored so a grade-5 house pays for about one flat-out crew hand. Passive
income should be a supplement somebody is pleased with, not a reason to stop
farming. **Mood scales it**: an unhappy tenant pays less before they leave.

The money is minted, like a shopper's, and split between the till and the
city's purse — which means a sixth duty, **Rent**, and one more balance in
`heldElsewhere()`.

## Mood

0 to 100. Once a day it moves up to 15 towards a target set by the house:

    target = 100
           - 4 per dark square
           - 60 if the grow next door is at heat tier 1 or worse
           - all of it if the grade has fallen to 0

At 0 they go. Mood also scales the rent, so a slide shows up in the money
before it shows up as an empty house.

## Letters

A short queue on the house, written when the target drops for a reason worth
naming, read in the mailbox screen. This is the tutorial and it needs no wiki:

- "The light on the landing has gone."
- "There is something growing next door. I can smell it."
- "The roof is open to the sky."

## Heat evicts

`TrapHeat.tierAt(world, anchor)`. A grow within scanning distance of somebody's
front room makes them nervous and then makes them leave. This is the piece the
whole design was built around: **the plantation and the apartment block cannot
be the same place.**

## Population becomes real

`TrapHomes.population()` currently sums the grades of every house. It becomes
the sum of the grades of **housed** houses, so an empty house brings no custom
to anybody's shop. The shops need no change.

## Files

| File | What |
|---|---|
| `TrapHomes.java` | Tenant record, rent, mood, letters, the daily tick, till |
| `MailboxScreenHandler.java` | Who lives there, how they feel, the last letters, the till |
| `TrapCity.java` | a sixth duty, `Duty.RENT` |
| `TrapMarket.java` | home tills into `heldElsewhere()` |
| `CityScreenHandler.java` | a sixth icon (the static guard already checks it fits) |
| `HomeSurvey.java` | rent table and mood constants, so they are testable |
| `SurveyTest.java` | rent by grade, mood drift, the eviction threshold |
| `TrapGuide.java`, `gen_wiki.py` | pages that read every number off the source |

## Order

1. `HomeSurvey`: rent table, mood arithmetic, tests. Red, green, commit.
2. `TrapHomes`: tenant, the daily tick, till, letters, persistence.
3. `Duty.RENT`, the mint split, `heldElsewhere()`.
4. Mailbox screen.
5. Guide, wiki, deploy.

Playtest before step 4: build a house, wait for somebody to move in, take the
rent, then put a weed plant outside their window and watch them go.
