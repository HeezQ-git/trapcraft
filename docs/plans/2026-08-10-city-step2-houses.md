# City step 2: the mailbox, the survey, the grade

> Step 2 of `2026-08-10-city-taxes-housing-design.md`. Step 1 (the earnings
> ledger) shipped in pack 1.0.147.

**Goal:** put a mailbox in a room, right-click it, and be told what you have
built and what it is missing. Nothing rents yet -- that is step 3. This step
has to be fun on its own, or the rest is not worth building.

**Architecture:** a Minecraft-free `HomeSurvey` holding the flood fill and the
grading, so the hard part is a JUnit test rather than an evening in game. A
`TrapHomes` register keyed by id, saved as text under the world, following
`TrapStalls`. A `MailboxBlock` that surveys on first use and opens a readout
after, and a `MailboxItem` that carries the house id so a mailbox can be
broken and re-placed on the street without losing the house.

---

## Why the survey is its own class

Two-phase flood fill with door probes is the highest-risk logic in the whole
city design, and it is also the only part with a worked example already
written down:

    [bedroom]--door--[hall]--door--[porch]--door--[street]

That example is a unit test. To make it one, the fill has to be able to run
against a string grid instead of a world, so `HomeSurvey` knows nothing about
Minecraft and takes a three-question `Space`:

```java
public interface Space {
    boolean open(int x, int y, int z);   // can a survey expand into it
    boolean door(int x, int y, int z);   // door, gate or trapdoor
    boolean taken(int x, int y, int z);  // somebody else's claim
}
```

Positions are packed into longs the way Minecraft packs `BlockPos`, so the
caller can convert without a dependency going the other way.

## The fill

**Phase one.** Breadth-first from the anchor against a visited set. Doors are
walls, but their positions are remembered. Stops at `ROOM_CAP` (4096) or
`SPAN` (48 on any axis); hitting either means the space is not sealed and the
survey fails.

**Phase two.** Probe each remembered door with its own fill of `PROBE_CAP`,
starting AT the door so that one door is passable and every other is not:

- closes inside the budget -> a room. Merge it, queue the doors it found.
- runs out of budget -> the outdoors. Do not merge. That door is a front door.

`PROBE_CAP` is 1024, not the 512 the design sketched. 512 is a ten by ten room
three high -- an ordinary garage -- and a probe that runs out of budget is
called the outdoors, so the cheap number would have refused to count anybody's
big rooms and called their internal doors front doors. Outdoors blows any
budget instantly, so a larger cap costs only wasted work on a genuine exterior
door. `TOTAL_CAP` moves to 16384 to match.

Iterative against `TOTAL_CAP` across all probes. A door is probed once.
If the total budget runs out, every unprobed door is called a front door --
failing towards "that is outside" rather than towards a house that swallowed a
cave.

## The grade

Uninhabitable (tier 0) unless all of: sealed, 9 floor columns, a bed, a front
door, and at least one light. Then points:

| Points | For |
|---|---|
| 0-3 | floor area past 9, 20, 40, 80 |
| 0-4 | one each for crafting, storage, cooking, a market stall |
| 0-2 | six and twelve distinct decorative blocks |
| 0-1 | lit: one light per 16 floor columns |

`tier = 1 + min(4, points / 2)`. Ten points available, eight for tier 5, so the
top grade wants most of the list and not all of it.

## Files

| File | What |
|---|---|
| `HomeSurvey.java` | new. `Space`, the fill, the grading, box overlap. No Minecraft. |
| `SurveyTest.java` | new. The worked example, the caps, the grading table. |
| `TrapHomes.java` | new. The register, the readout data, persistence, the re-survey tick, `/homes`. |
| `MailboxBlock.java` | new. Survey on first use, readout after. |
| `MailboxScreenHandler.java` | new. The checklist as a screen. |
| `TrapComponents.java` | `home` component: which house a mailbox item is the key to. |
| `TrapContent.java` | register the block, the item, the creative tab entry. |
| `TrapCraft.java` | `TrapHomes.register()`. |
| `gen_textures.py` | the mailbox texture. |
| `gen_assets.py` | model, item model, loot table, recipe, lang, advancement. |
| `TrapGuide.java` | `/guide city`, every number read off `HomeSurvey`. |
| `gen_wiki.py` | a housing section. |

`trapcraft-homes.txt`, not `-houses.txt`: `TrapHouse` is the casino and already
owns that filename.

## Failure modes

- **Fill escapes** -> caps, and the survey reports "not sealed" rather than
  claiming a cave.
- **Overlap** -> box intersection on registration, and the fill treats another
  claim as a wall.
- **Anchor buried** -> the anchor is only open if it is air or the mailbox, so
  a wall built over it reads as collapsed.
- **Mailbox moved** -> the anchor is stored at first survey and never moves;
  the mailbox position is separate and may be anywhere.
- **Survey cost** -> loaded chunks only, every two minutes, one house per pass.

## Order

1. `HomeSurvey` + `SurveyTest`: fill, then grading. Red, green, commit.
2. `TrapHomes` register + persistence, no block yet.
3. `MailboxBlock`, item, component, textures, recipe.
4. `MailboxScreenHandler` readout.
5. Re-survey tick and `/homes`.
6. Guide page, wiki section, deploy.

Playtest before step 3: build a room, survey it, break a wall, see the grade
drop.
