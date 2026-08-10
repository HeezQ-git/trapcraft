# The City, Step 1: Earnings Ledger — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Record every meaningful emerald a player earns or spends, tagged with which role it came from, so balance between the farmer, the refiner, the casino owner and the landlord can be read off a file instead of argued about.

**Architecture:** A single `TrapLedger` class holding an in-memory day-bucketed tally plus an append-only CSV. Call sites `record(...)` explicitly at the ~15 places that represent real earning — deliberately NOT a wrapper around `TrapMarket.take/pay`, which are called from twenty files and do not know *why* money is moving. Each `Source` carries a `declared` flag, which is what the audit in step 6 will read.

**Tech Stack:** Java 21, Fabric 1.21.8, JUnit 5 (`./gradlew test`), plain text persistence under the world folder, following the `TrapStalls` / `TrapCrew` ledger pattern.

---

## Scope note

This plan covers **step 1 only**. Steps 2–6 are outlined at the bottom without
bite-sized tasks on purpose: the design says each step ships and gets played
before the next starts, so detailed tasks for step 6 would be written against
a design that step 3's playtest will change. Re-plan each step when it starts.

---

### Task 1: The Source enum and the pure tally maths

**Files:**
- Create: `src/main/java/dev/heezq/trapcraft/TrapLedger.java`
- Test: `src/test/java/dev/heezq/trapcraft/FormulaTest.java`

**Step 1: Write the failing test**

Append to `FormulaTest.java`:

```java
// --- the earnings ledger ---------------------------------------------------

@Test
void everySourceKnowsWhetherItIsDeclared() {
    assertFalse(TrapLedger.Source.WEED.declared(), "drug money is not declared");
    assertFalse(TrapLedger.Source.COCA.declared());
    assertTrue(TrapLedger.Source.STALL.declared(), "a stall is a legitimate shop");
    assertTrue(TrapLedger.Source.CASINO.declared());
    assertTrue(TrapLedger.Source.RENT.declared());
}

/** The audit in step 6 reads exactly this split, so it is worth pinning now. */
@Test
void declaredAndUndeclaredSplitCleanly() {
    long declared = java.util.Arrays.stream(TrapLedger.Source.values())
            .filter(TrapLedger.Source::declared).count();
    assertTrue(declared >= 6, "most sources should be above board");
    assertTrue(declared < TrapLedger.Source.values().length,
            "at least one source has to be black market or the audit is pointless");
}
```

**Step 2: Run test to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test --tests '*FormulaTest*'`
Expected: FAIL, `cannot find symbol: class TrapLedger`

**Step 3: Write minimal implementation**

Create `TrapLedger.java` with the enum only:

```java
public final class TrapLedger {
    public enum Source {
        WEED(false), COCA(false),
        FOOD(true), MARKET(true), STALL(true), CASINO(true),
        CONTRACT(true), CREW(true), RENT(true), TAX(true),
        INVEST(true), SCRAP(true);

        private final boolean declared;

        Source(boolean declared) {
            this.declared = declared;
        }

        /** Whether the revenue office gets told about it. See step 6. */
        public boolean declared() {
            return declared;
        }
    }

    private TrapLedger() {
    }
}
```

**Step 4: Run test to verify it passes**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test --tests '*FormulaTest*'`
Expected: PASS

**Step 5: Commit**

```bash
git add src/main/java/dev/heezq/trapcraft/TrapLedger.java src/test/java/dev/heezq/trapcraft/FormulaTest.java
git commit -m "The ledger knows which money is above board"
```

---

### Task 2: Recording and the daily rollup

**Files:**
- Modify: `src/main/java/dev/heezq/trapcraft/TrapLedger.java`
- Test: `src/test/java/dev/heezq/trapcraft/FormulaTest.java`

**Step 1: Write the failing test**

```java
@Test
void rollupTotalsBySourceAndKeepsNamesApart() {
    Map<String, Map<TrapLedger.Source, Integer>> book = new LinkedHashMap<>();
    TrapLedger.tally(book, "HeezQ", TrapLedger.Source.WEED, 120);
    TrapLedger.tally(book, "HeezQ", TrapLedger.Source.WEED, 80);
    TrapLedger.tally(book, "HeezQ", TrapLedger.Source.CREW, -40);
    TrapLedger.tally(book, "KARTGERL", TrapLedger.Source.CASINO, 500);

    assertEquals(200, book.get("HeezQ").get(TrapLedger.Source.WEED));
    assertEquals(-40, book.get("HeezQ").get(TrapLedger.Source.CREW));
    assertEquals(500, book.get("KARTGERL").get(TrapLedger.Source.CASINO));
    assertEquals(160, TrapLedger.net(book.get("HeezQ")));
}

@Test
void undeclaredIsSummedSeparatelyForTheAudit() {
    Map<TrapLedger.Source, Integer> mine = new EnumMap<>(TrapLedger.Source.class);
    mine.put(TrapLedger.Source.WEED, 900);
    mine.put(TrapLedger.Source.STALL, 100);
    assertEquals(100, TrapLedger.declaredOf(mine));
    assertEquals(900, TrapLedger.undeclaredOf(mine));
}
```

**Step 2: Run test to verify it fails**

Expected: FAIL, `cannot find symbol: method tally`

**Step 3: Write minimal implementation**

Add the four static helpers. Keep them free of Minecraft imports so they stay
testable in the plain JUnit suite — this is the same rule `TrapMath` follows.

**Step 4: Run test to verify it passes**

Expected: PASS

**Step 5: Commit**

```bash
git commit -am "Roll the ledger up per player per source"
```

---

### Task 3: Persistence and the daily flush

**Files:**
- Modify: `src/main/java/dev/heezq/trapcraft/TrapLedger.java`
- Modify: `src/main/java/dev/heezq/trapcraft/TrapCraft.java:26` (register after `TrapMarket.register()`)

**Step 1:** `register()` hooks `SERVER_STARTED` to resolve
`world/trapcraft-ledger.csv`, and `END_SERVER_TICK` to flush a rollup when
`TrapMarket.today(server)` changes.

**Step 2:** `record(ServerPlayerEntity, Source, int)` appends
`day,player,source,delta` and updates the in-memory tally. Guard: no-op when
`delta == 0` or the save file is null (before world load).

**Step 3:** On day change, append a human-readable block to
`world/trapcraft-earnings.txt` — the file the user actually reads — and log
one INFO line so it is visible in the container log too.

**Step 4:** Run `./gradlew build`. Expected: BUILD SUCCESSFUL.

**Step 5:** Commit.

---

### Task 4: Wire the call sites

**Files (exact lines from the current tree):**

| File | Line | Source |
|---|---|---|
| `TrapDealing.java` | 504 | `WEED` or `COCA` — branch on the craving |
| `DealerScreenHandler.java` | 223 | `WEED` (dealer takings) |
| `SellScreenHandler.java` | 236 | `MARKET` |
| `ShopScreenHandler.java` | 555, 588 | `MARKET` (buy negative, sell positive) |
| `TrapStalls.java` | 262–263, 319 | `STALL` |
| `TrapHouse.java` | 608, 653, 683, 724, 737 | `CASINO` |
| `TrapContracts.java` | 516 | `CONTRACT` |
| `TrapCrew.java` | 427, 475, 1100 | `CREW` (all negative) |
| `TrapInvest.java` | 134 | `INVEST` |
| `TrapCoins.java` | 157, 199 | `INVEST` |
| `TrapScrap` (via `SellScreenHandler`) | — | `SCRAP` |

**Deliberately NOT wired:** `WalletItem.java:126` and `TrapHouse` deposit /
withdraw. Moving your own money between your pocket and your own wallet or
vault is not earning, and logging it would double-count every emerald that
passes through a wallet.

**Step 1:** Add one `TrapLedger.record(...)` per row above, immediately after
the existing `TrapMarket` call.

**Step 2:** `./gradlew build && for c in check_models check_pages check_shaders check_stock trip_check; do python3 tools/$c.py; done`
Expected: build success, all five checkers exit 0.

**Step 3:** Commit.

---

### Task 5: The readout command and the guide

**Files:**
- Modify: `TrapLedger.java` — add `/earnings` (ops only), printing today's
  rollup in chat so it can be checked without leaving the game.
- Modify: `src/main/java/dev/heezq/trapcraft/TrapGuide.java` — one page under
  the street handbook noting the file and the command.

**Step 1–3:** Implement, `./gradlew build`, run `check_pages.py` (the new page
must fit 14 lines), commit.

---

## Steps 2–6, outlined only

Re-plan each when it starts. Files are the best current guess, not a contract.

**Step 2 — Mailbox, flood fill, tier.** New `TrapHouses.java` (ledger, anchor,
bounds, tier), `MailboxBlock.java`, `MailboxItem.java` carrying the house id
component, `HouseScreenHandler` for the checklist readout. Tier scoring and
box-overlap go in `TrapMath` so they are unit-testable. Register the item and
block in `TrapContent`, textures in `gen_textures.py`.

**Step 3 — Tenants.** Extend `TrapHouses` with tenant UUID, mood and the rent
timer. Re-find the villager the way `TrapCrew.find` does. Letters are lore
lines on the mailbox screen. Heat eviction reads `TrapHeat.measureHeat`.

**Step 4 — Population.** A `TrapHouses.population()` read by
`TrapDealing` (visit rate), `TrapHouse` (punter volume) and `TrapContracts`
(board size).

**Step 5 — Tax and treasury.** New `TrapCity.java`. Tax hooks the same call
sites as the ledger, which is why the ledger comes first.

**Step 6 — Audit, laundering, constitution.** Reads `TrapLedger` declared vs
undeclared against `TrapMarket.wealthOf` growth. `LawBookItem` rewriting its
own `WRITTEN_BOOK_CONTENT` on use, plus an inventory sweep on law change.

---

## Definition of done for step 1

- `./gradlew test` passes, including the four new tests.
- All five checkers exit 0.
- After a session of play, `world/trapcraft-earnings.txt` shows a per-player
  per-source table whose numbers are recognisable as what actually happened.
- No emerald is counted twice; wallet and vault movements are absent.
