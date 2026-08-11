# Kiosks and the working town — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Residents earn a taxed wage before they spend, the town's purse becomes a real constraint that feeds back into demand, players can buy from each other's kiosks, and the console can name its shop and show its shelves.

**Architecture:** Three sites that mint emeralds from nothing (rent, shelf sales, casino stakes) collapse into one mint at payday in a new `TrapPayroll`. Everything downstream spends a purse that already exists, and the purse's size modulates how much custom walks in. The kiosk gains a rename and a shelves page on the existing `ShopScreen`, and a player-facing buy screen on the shelf modelled on `StallScreenHandler`.

**Tech Stack:** Fabric 1.21.8, Java 21 toolchain, Polymer (server-side only), JUnit 5, Python asset generators.

**Design:** `docs/plans/2026-08-11-villager-kiosks-labour-design.md` (commit `d44f4df`).

---

## Before you start

Read `docs/TRAPS.md`. Most of this mod's expensive bugs were the client and
the server disagreeing, not the code being wrong.

**Deviation from the design doc:** the design says `TrapMath.wage()`. Put it in
`HomeSurvey` instead — that is where the `RENT` table it is anchored to lives,
`HomeSurvey` imports nothing from Minecraft for exactly the same reason
`TrapMath` doesn't, and `SurveyTest` already covers `rentDue`. The
purse-to-demand curve does go in `TrapMath` next to `floorPull`.

**Commands** (the Gradle launcher JVM must be 21+):

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 24) ./gradlew test
```

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 24) ./gradlew build
```

```bash
python3 tools/gen_textures.py && python3 tools/gen_assets.py && python3 tools/check_models.py
```

**Never hand-edit anything under `src/main/resources/assets/`** — the next
generator run overwrites it.

---

## Task 1: The wage table

**Files:**
- Modify: `src/main/java/dev/heezq/trapcraft/HomeSurvey.java` (next to `RENT`, ~:491)
- Test: `src/test/java/dev/heezq/trapcraft/SurveyTest.java`

**Step 1: Write the failing tests**

Add to `SurveyTest.java`:

```java
@Test
void everyGradeClearsItsOwnRent() {
    for (int tier = 1; tier < HomeSurvey.RENT.length; tier++) {
        int wage = HomeSurvey.wageDue(tier, 1);
        int rent = HomeSurvey.rentDue(tier, HomeSurvey.MOOD_MAX, 1);
        assertTrue(wage > rent,
                "grade " + tier + " earns " + wage + " and owes " + rent);
    }
}

@Test
void aBetterHouseIsBetterPaid() {
    assertTrue(HomeSurvey.wageDue(8, 1) > HomeSurvey.wageDue(1, 1));
}

@Test
void aHouseholdEarnsPerHead() {
    assertEquals(HomeSurvey.wageDue(4, 1) * 4, HomeSurvey.wageDue(4, 4));
}

@Test
void nobodyIsPaidForACondemnedRoom() {
    assertEquals(0, HomeSurvey.wageDue(0, 1));
    assertEquals(0, HomeSurvey.wageDue(4, 0));
}
```

**Step 2: Run to verify it fails**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 24) ./gradlew test --tests '*SurveyTest*'
```

Expected: FAIL, `cannot find symbol: method wageDue`.

**Step 3: Implement**

In `HomeSurvey.java`, immediately after the `RENT` array:

```java
/**
 * What a resident of this grade earns a day, before tax.
 *
 * Anchored to {@link #RENT} rather than given a table of its own, so the two
 * can never drift into a grade that costs more to live in than it pays to
 * live in. The multiple is the single calibration knob for the whole town
 * economy -- see docs/plans/2026-08-11-villager-kiosks-labour-design.md.
 *
 * Mood is deliberately not a term. Rent bends with how a tenant feels about
 * the place; a wage is paid by an employer who has never seen it.
 */
public static final int WAGE_MULTIPLE = 3;

public static int wageDue(int tier, int heads) {
    if (tier <= 0 || tier >= RENT.length || heads <= 0) {
        return 0;
    }
    return RENT[tier] * WAGE_MULTIPLE * heads;
}
```

**Step 4: Run to verify it passes**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 24) ./gradlew test --tests '*SurveyTest*'
```

Expected: PASS.

**Step 5: Commit**

```bash
git add src/main/java/dev/heezq/trapcraft/HomeSurvey.java src/test/java/dev/heezq/trapcraft/SurveyTest.java && git commit -m "A grade that costs more to live in than it pays"
```

---

## Task 2: The demand curve

**Files:**
- Modify: `src/main/java/dev/heezq/trapcraft/TrapMath.java` (next to `floorPull`)
- Test: `src/test/java/dev/heezq/trapcraft/FormulaTest.java`

**Step 1: Write the failing tests**

```java
// --- the town's spending money -------------------------------------------

@Test
void aBrokeTownStaysIn() {
    assertEquals(0.0f, TrapMath.townDemand(0, 20), 0.001f);
}

@Test
void anEmptyTownWantsNothing() {
    assertEquals(0.0f, TrapMath.townDemand(10_000, 0), 0.001f);
}

@Test
void aComfortableTownShopsAsItAlwaysDid() {
    assertEquals(1.0f, TrapMath.townDemand(TrapMath.COMFORTABLE * 20L, 20), 0.01f);
}

@Test
void aRichTownIsCapped() {
    assertEquals(TrapMath.townDemand(TrapMath.COMFORTABLE * 2_000L, 20),
            TrapMath.townDemand(TrapMath.COMFORTABLE * 200_000L, 20), 0.001f);
}

@Test
void moreMoneyIsNeverLessCustom() {
    float last = -1f;
    for (long purse = 0; purse <= 100_000; purse += 500) {
        float now = TrapMath.townDemand(purse, 20);
        assertTrue(now >= last, "demand dipped at " + purse);
        last = now;
    }
}
```

**Step 2: Run to verify it fails**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 24) ./gradlew test --tests '*FormulaTest*'
```

Expected: FAIL, `cannot find symbol: method townDemand`.

**Step 3: Implement**

In `TrapMath.java`:

```java
/** Emeralds a head in the purse that means a town is comfortably off. */
public static final int COMFORTABLE = 200;
/** The most a flush town can multiply its own custom by. */
public static final float DEMAND_CAP = 2.0f;

/**
 * How hard the town is shopping, against how hard it shops when comfortable.
 *
 * The purse alone would make a big poor town look rich, so this is per head:
 * twenty people with 4000e between them are comfortable, and two hundred
 * people with the same 4000e are not.
 *
 * Capped because the alternative is a town that got lucky once and then
 * bought out every shop on the server forever. The cap is also what makes
 * this stable -- spending rises with the purse, which lowers the purse, so a
 * wrong WAGE_MULTIPLE is a slow town or a busy one, never a runaway one.
 */
public static float townDemand(long purse, int people) {
    if (people <= 0 || purse <= 0) {
        return 0f;
    }
    return Math.min(DEMAND_CAP, (purse / (float) people) / COMFORTABLE);
}
```

**Step 4: Run to verify it passes**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 24) ./gradlew test --tests '*FormulaTest*'
```

Expected: PASS.

**Step 5: Commit**

```bash
git add src/main/java/dev/heezq/trapcraft/TrapMath.java src/test/java/dev/heezq/trapcraft/FormulaTest.java && git commit -m "A purse that is a constraint and not a scoreboard"
```

---

## Task 3: `TrapPayroll` — the purse

**Files:**
- Create: `src/main/java/dev/heezq/trapcraft/TrapPayroll.java`
- Modify: `src/main/java/dev/heezq/trapcraft/TrapCraft.java:30` (register after `TrapCity`, before `TrapHomes`)

No test this task — it is persistence and glue, and the formulas it uses are
already covered. The behaviour gets its check in Task 4.

**Step 1: Write the file**

```java
package dev.heezq.trapcraft;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * What the town has to spend, and the one place it comes from.
 *
 * Before this, three separate systems minted emeralds out of nothing: a
 * tenant's rent, a shelf sale, and every stake laid on a casino floor. The
 * town was a fountain -- it had no income, so it had no budget, so nothing a
 * player built could make it richer or poorer.
 *
 * Now there is exactly one mint, at payday, and everything else moves money
 * that already exists. That is not bookkeeping pedantry: it is the difference
 * between a town whose custom you can grow and a town whose custom is a
 * constant.
 *
 * <h2>Why the purse is one number and not a wallet each</h2>
 *
 * Because a wallet each is a schedule each, a job each, and a save file that
 * grows with the population. The town is modelled the way a council models it
 * -- in aggregate -- and the villagers you watch walk to a shelf are a SAMPLE
 * of that, not the thing itself. Nothing about the economy depends on one of
 * them reaching its destination.
 */
public final class TrapPayroll {

    private static long purse;
    /** Running totals, for the city log. Reset with the world, not the day. */
    private static long wagesPaid;
    private static long incomeTax;
    private static Path saveFile;

    private TrapPayroll() {
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(TrapPayroll::load);
    }

    public static long purse() {
        return purse;
    }

    public static long wagesPaid() {
        return wagesPaid;
    }

    public static long incomeTax() {
        return incomeTax;
    }

    /**
     * A household is paid: the only money this mod makes on the town's behalf.
     *
     * Income tax comes off the top, straight into the vault, because a wage is
     * taxed before it is in anybody's hand and because the alternative -- the
     * town paying tax later out of the purse -- is a second place the purse
     * can be emptied by something that is not a purchase.
     */
    public static void earned(int gross) {
        if (gross <= 0) {
            return;
        }
        TrapMarket.minted(gross);
        int duty = TrapCity.dutyOn(gross, TrapCity.Duty.INCOME);
        TrapCity.receive(duty, TrapCity.Duty.INCOME);
        purse += gross - duty;
        wagesPaid += gross;
        incomeTax += duty;
        save();
    }

    /** Could the town cover this? A pure read -- see {@link #spend}. */
    public static boolean afford(int amount) {
        return amount <= 0 || purse >= amount;
    }

    /**
     * Take it out of the purse, or refuse.
     *
     * A boolean rather than a clamp on purpose. Every caller must fail CLOSED
     * -- no sale, no stake, no rent -- because a half-paid transaction is a
     * duplication bug wearing a hat. Check {@link #afford} before you take
     * goods off a shelf, not after.
     */
    public static boolean spend(int amount) {
        if (amount <= 0) {
            return true;
        }
        if (purse < amount) {
            return false;
        }
        purse -= amount;
        save();
        return true;
    }

    /**
     * Money coming back to the town: a casino payout, mostly.
     *
     * Not a mint. A punter who wins is handed money the house already had,
     * which the house got from the purse in the first place. Without this the
     * town leaks its whole wage bill into casino balances and stops shopping.
     */
    public static void credit(int amount) {
        if (amount <= 0) {
            return;
        }
        purse += amount;
        save();
    }

    // --- persistence ----------------------------------------------------------

    private static void load(MinecraftServer server) {
        saveFile = server.getSavePath(WorldSavePath.ROOT).resolve("trapcraft-town.txt");
        purse = 0;
        wagesPaid = 0;
        incomeTax = 0;
        try {
            if (!Files.exists(saveFile)) {
                return;
            }
            for (String row : Files.readAllLines(saveFile)) {
                String[] parts = row.trim().split("\\s+");
                if (parts.length < 2) {
                    continue;
                }
                switch (parts[0]) {
                    case "purse" -> purse = Long.parseLong(parts[1]);
                    case "wages" -> wagesPaid = Long.parseLong(parts[1]);
                    case "tax" -> incomeTax = Long.parseLong(parts[1]);
                    default -> {
                    }
                }
            }
        } catch (Exception failure) {
            TrapCraft.LOGGER.warn("couldn't read the town purse: {}", failure.toString());
        }
    }

    private static void save() {
        if (saveFile == null) {
            return;
        }
        try {
            Files.writeString(saveFile, "purse " + purse + "\nwages " + wagesPaid
                    + "\ntax " + incomeTax + '\n');
        } catch (Exception failure) {
            TrapCraft.LOGGER.warn("couldn't save the town purse: {}", failure.toString());
        }
    }
}
```

**Step 2: Register it**

In `TrapCraft.java`, between `TrapCity.register();` and `TrapHomes.register();`:

```java
        TrapPayroll.register();
```

Order matters: `earned` calls `TrapCity.dutyOn`, and `TrapHomes` is what calls
`earned`.

**Step 3: Verify it compiles**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 24) ./gradlew build
```

Expected: BUILD SUCCESSFUL.

**Step 4: Commit**

```bash
git add src/main/java/dev/heezq/trapcraft/TrapPayroll.java src/main/java/dev/heezq/trapcraft/TrapCraft.java && git commit -m "The town gets a purse before it gets a wage"
```

---

## Task 4: Payday, and rent out of the purse

**Files:**
- Modify: `src/main/java/dev/heezq/trapcraft/TrapHomes.java:653-671` (the rent block inside `live`)

**Step 1: Replace the rent block**

Find this in `live`:

```java
        int rent = HomeSurvey.rentDue(home.tier, home.mood, home.heads);
        if (rent > 0) {
            TrapCity.Duty duty = TrapCity.Duty.RENT;
            int owed = TrapCity.dutyOn(rent, duty);
            // Minted, like a shopper's money: a tenant is not a player and
            // their emeralds were never in the world before. Split at once
            // between the mailbox and the purse, both of which the market
            // resample knows to count.
            TrapMarket.minted(rent + owed);
            home.till += rent;
            TrapCity.receive(owed, duty);
```

Replace with:

```java
        // Paid first, then they pay their landlord out of it. This is the ONLY
        // mint left on the town's behalf -- rent, shelf sales and casino
        // stakes all move money that this line already made.
        TrapPayroll.earned(HomeSurvey.wageDue(home.tier, home.heads));

        int rent = HomeSurvey.rentDue(home.tier, home.mood, home.heads);
        if (rent > 0) {
            TrapCity.Duty duty = TrapCity.Duty.RENT;
            int owed = TrapCity.dutyOn(rent, duty);
            // Out of the purse now, not minted. A tenant who cannot make rent
            // pays none of it -- the mood drift above is what evicts them, and
            // a town this broke has bigger problems than one mailbox.
            if (!TrapPayroll.spend(rent + owed)) {
                save();
                return;
            }
            home.till += rent;
            TrapCity.receive(owed, duty);
```

Leave the owner message and the closing braces below it exactly as they are.

**Step 2: Update the stale javadoc on `TrapCity.receive`**

In `TrapCity.java`, the javadoc for `receive` says the money "has just been
made". It now also receives transfers out of the town purse. Add one line to
that paragraph:

```java
     * Also takes transfers out of the town purse -- rent and shelf duty are
     * money {@link TrapPayroll} minted at payday, arriving late.
```

**Step 3: Verify it compiles and the suite still passes**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 24) ./gradlew build
```

Expected: BUILD SUCCESSFUL, all tests pass.

**Step 4: Commit**

```bash
git add src/main/java/dev/heezq/trapcraft/TrapHomes.java src/main/java/dev/heezq/trapcraft/TrapCity.java && git commit -m "Paid on Friday, rent on Friday"
```

---

## Task 5: A shelf sale spends the purse

**Files:**
- Modify: `src/main/java/dev/heezq/trapcraft/TrapShops.java:617-655` (`buy`)

**The ordering matters.** `take` removes goods from the chest. If the town
turns out to be broke *after* that, the goods are gone and nobody paid. So:
check affordability, then take, then spend.

**Step 1: Rewrite the head of `buy`**

Find:

```java
        Line line = wanted(server, world, shop, world.getRandom());
        if (line == null || !take(world, shop, line)) {
            leave(server, shopper);
            return;
        }

        int duty = TrapCity.dutyOn(line.price(), line.duty());
        TrapMarket.minted(line.price() + duty);
        shop.till += line.price();
```

Replace with:

```java
        Line line = wanted(server, world, shop, world.getRandom());
        if (line == null) {
            leave(server, shopper);
            return;
        }
        int duty = TrapCity.dutyOn(line.price(), line.duty());
        int total = line.price() + duty;
        // Afford BEFORE take. take() empties the chest, and a town that turns
        // out to be broke one line later has walked off with the goods.
        if (!TrapPayroll.afford(total) || !take(world, shop, line)) {
            leave(server, shopper);
            return;
        }
        TrapPayroll.spend(total);
        shop.till += line.price();
```

The rest of the method — `shop.sold++`, the sound, the particles, the owner
message, `leave` — is unchanged.

**Step 2: Verify**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 24) ./gradlew build
```

Then confirm nothing mints behind your back:

```bash
grep -rn "TrapMarket.minted" src/main/java/dev/heezq/trapcraft/
```

Expected: two hits, and only these two. `TrapPayroll.earned` is the town's
only mint. `LaundryBlock.collect` is the other and is unrelated — dirty money
is an item the market has never counted, so washing it is the moment those
emeralds start existing. Any *third* hit is a fountain that survived.

**Step 3: Commit**

```bash
git add src/main/java/dev/heezq/trapcraft/TrapShops.java && git commit -m "A loaf is bought with money somebody earned"
```

---

## Task 6: A stake spends the purse, a win returns to it

**Files:**
- Modify: `src/main/java/dev/heezq/trapcraft/TrapFloor.java` (~:589 stake sizing, ~:727 `punterStaked`, ~:774 payout)

**Step 1: Shrink the stake to what the town can afford**

At ~:594 there is already a loop that halves a stake the house cannot cover.
Extend its condition so the town is the second thing that can turn a punter
away:

```java
        while (stake > TrapMath.PUNTER_MIN_STAKE
                && (!TrapHouse.covers(house, stake, TrapHouse.TOP_SLOT)
                        || !TrapPayroll.afford(stake))) {
            stake /= 2;
        }
        if (!TrapHouse.covers(house, stake, TrapHouse.TOP_SLOT)
                || !TrapPayroll.afford(stake)) {
            // Turned away at the smallest bet there is, by the house or by
            // their own pocket. Word gets round either way.
            TrapHouse.turnedAway(house);
```

Keep the existing body of that `if` as it is.

**Step 2: Take the stake at the point it is laid**

At ~:727, `TrapHouse.punterStaked(house, punter.stake);` becomes:

```java
        if (!TrapPayroll.spend(punter.stake)) {
            return;   // the town went broke between arriving and playing
        }
        TrapHouse.punterStaked(house, punter.stake);
```

Check what the enclosing method returns — if it is not `void`, return whatever
its existing "nothing happened" path returns.

**Step 3: Give winnings back to the town**

At ~:774, where `paid` is compared to the stake, add before that comparison:

```java
        // Back to the purse it came out of. Without this the town leaks its
        // whole wage bill into casino balances and quietly stops shopping,
        // which reads as "the shops broke" and is very hard to find.
        TrapPayroll.credit(paid);
```

**Step 4: Verify**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 24) ./gradlew build
```

**Step 5: Commit**

```bash
git add src/main/java/dev/heezq/trapcraft/TrapFloor.java && git commit -m "The floor takes the town's money and gives some of it back"
```

---

## Task 7: The purse modulates demand

**Files:**
- Modify: `src/main/java/dev/heezq/trapcraft/TrapShops.java:493-495` (`maybeVisit`)
- Modify: `src/main/java/dev/heezq/trapcraft/TrapFloor.java` (`bestPull` and `pullOf`)

**Step 1: Shops**

Find:

```java
        float pull = people * PULL
                * (TrapCity.built(TrapCity.Work.LAMPS) ? TrapCity.LAMPS_TRADE : 1f);
```

Replace with:

```java
        // A town with nothing in the purse does not go shopping. This is the
        // line that makes wages matter -- without it the purse only ever
        // grows, spend() never once refuses, and payday is a tax line and
        // nothing else.
        float pull = people * PULL
                * (TrapCity.built(TrapCity.Work.LAMPS) ? TrapCity.LAMPS_TRADE : 1f)
                * TrapMath.townDemand(TrapPayroll.purse(), people);
```

**Step 2: The casino floor**

In `pullOf` and `bestPull`, multiply the returned pull by
`TrapMath.townDemand(TrapPayroll.purse(), TrapHomes.population())` the same
way. Both already read `TrapHomes.population()`, so the term is to hand.

**Step 3: Verify**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 24) ./gradlew build
```

**Step 4: Commit**

```bash
git add src/main/java/dev/heezq/trapcraft/TrapShops.java src/main/java/dev/heezq/trapcraft/TrapFloor.java && git commit -m "A town shops as well as it is paid"
```

---

## Task 8: Name the shop

**Files:**
- Modify: `src/main/java/dev/heezq/trapcraft/ShopTillBlock.java:62-67` (`onPlaced`)
- Modify: `src/main/java/dev/heezq/trapcraft/TrapShops.java` (add `rename`)
- Modify: `src/main/java/dev/heezq/trapcraft/ShopScreen.java` (name slot click)

Follows `MailboxItem`'s rule: *"an anvil is the only text entry this mod has"*.

**Step 1: `TrapShops.rename`**

Next to `repricePrices`:

```java
/** Whatever the anvil called it. Blank names are ignored, not stored. */
public static void rename(Shop shop, String name) {
    String trimmed = name == null ? "" : name.replace('\n', ' ').trim();
    if (trimmed.isBlank() || trimmed.equals(shop.name)) {
        return;
    }
    shop.name = trimmed;
    save();
}
```

`Shop.name` is package-private and `ShopScreen` is in the same package, but
route it through here anyway — `save()` is the reason.

**Step 2: A named till is a named shop**

In `ShopTillBlock.onPlaced`, after `TrapShops.open(ground, pos, owner);`:

```java
            net.minecraft.text.Text named =
                    stack.get(net.minecraft.component.DataComponentTypes.CUSTOM_NAME);
            if (named != null) {
                TrapShops.Shop shop = TrapShops.shopAt(ground, pos);
                if (shop != null) {
                    TrapShops.rename(shop, named.getString());
                }
            }
```

**Step 3: Rename from the console**

In `ShopScreen.onSlotClick`, alongside the existing `PRICE_SLOT` branch:

```java
        if (index == TILL_SLOT) {
            ItemStack held = who.getMainHandStack();
            Text named = held.get(DataComponentTypes.CUSTOM_NAME);
            if (named == null || named.getString().isBlank()) {
                who.sendMessage(Text.literal("Hold something you've named in an "
                        + "anvil and click this to name the shop.")
                        .formatted(Formatting.GRAY), true);
            } else {
                TrapShops.rename(shop, named.getString());
                who.getWorld().playSound(null, who.getBlockPos(),
                        SoundEvents.BLOCK_ANVIL_USE, SoundCategory.PLAYERS, 0.6F, 1.4F);
            }
            paint();
        }
```

Add the lore line to `till()` so it is discoverable:

```java
                Text.empty(),
                line("Click holding an anvil-named item", Formatting.YELLOW),
                line("to rename the shop.", Formatting.YELLOW),
```

The screen title is set once at open, so a rename shows on the next open.
Say so in the lore rather than trying to re-title a live screen — see
`docs/TRAPS.md` on the client recomputing things.

**Step 4: Verify, then check in game**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 24) ./gradlew build
```

**Step 5: Commit**

```bash
git add src/main/java/dev/heezq/trapcraft/ShopTillBlock.java src/main/java/dev/heezq/trapcraft/TrapShops.java src/main/java/dev/heezq/trapcraft/ShopScreen.java && git commit -m "A shop can be called something"
```

---

## Task 9: The shelves page

**Files:**
- Modify: `src/main/java/dev/heezq/trapcraft/ShopScreen.java`

**Step 1: Add a page flag and a shelves view**

Add a field `private boolean showingShelves;` and in `paint()`, when it is set,
fill the line rows with one entry per shelf instead of the price list:

```java
    /** One shelf: where it is, how far, and what is under it. */
    private ItemStack shelfRow(TrapShops.Shelf shelf) {
        ServerWorld world = (ServerWorld) who.getWorld();
        int under = 0;
        if (world.getBlockEntity(shelf.pos().down()) instanceof Inventory box) {
            for (int slot = 0; slot < box.size(); slot++) {
                under += box.getStack(slot).getCount();
            }
        }
        int away = (int) Math.round(Math.sqrt(shop.pos().getSquaredDistance(shelf.pos())));
        ItemStack tag = new ItemStack(TrapContent.marketShelfItem);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain(shelf.pos().getX() + " " + shelf.pos().getY() + " "
                        + shelf.pos().getZ()).formatted(Formatting.WHITE, Formatting.BOLD));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line(away + " blocks from the till", Formatting.GRAY),
                line(under == 0 ? "Nothing stocked under it" : under + " items under it",
                        under == 0 ? Formatting.RED : Formatting.GREEN),
                Text.empty(),
                line("Click to make it sparkle.", Formatting.YELLOW))));
        return tag;
    }
```

**Step 2: Ping a shelf on click**

In `onSlotClick`, when `showingShelves` and the index lands on a shelf row:

```java
            TrapShops.Shelf shelf = shelves.get(index - LINES_FROM);
            ServerWorld world = (ServerWorld) who.getWorld();
            world.spawnParticles(ParticleTypes.END_ROD, shelf.pos().getX() + 0.5,
                    shelf.pos().getY() + 1.2, shelf.pos().getZ() + 0.5, 30, 0.3, 0.6, 0.3, 0.02);
            world.playSound(null, shelf.pos(), SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(),
                    SoundCategory.BLOCKS, 1.0F, 1.6F);
```

Keep a `private final List<TrapShops.Shelf> shelves = new ArrayList<>();`
repopulated in `paint()`, the way `StallScreenHandler` keeps `shown` — that is
the established way this codebase maps a slot index back to a thing.

**Step 3: Make the shelves slot toggle the page**

The `SHELVES_SLOT` branch in `onSlotClick` flips `showingShelves` and repaints.
Add `line("Click to list them.", Formatting.YELLOW)` to `shelves()`'s lore.

**Step 4: Verify and commit**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 24) ./gradlew build
```

```bash
git add src/main/java/dev/heezq/trapcraft/ShopScreen.java && git commit -m "You can see which counters are yours"
```

---

## Task 10: Players buy at the shelf

**Files:**
- Create: `src/main/java/dev/heezq/trapcraft/ShelfScreenHandler.java`
- Modify: `src/main/java/dev/heezq/trapcraft/TrapShops.java` (add `buy` for players)
- Modify: `src/main/java/dev/heezq/trapcraft/MarketShelfBlock.java:73-110` (`onUse`)

**Copy `StallScreenHandler.java` as the starting point.** It is the same
screen: a grid of lines, a sign, a purse, click to buy, a null-or-reason
String back from the model. Do not invent a new shape.

**Step 1: `TrapShops.buy` for a player**

```java
/**
 * A player over the same counter a townsperson uses.
 *
 * The same price and the same duty, deliberately -- a kiosk selling joints is
 * a licensed dispensary for players too, which is the whole point of the
 * legal rate: clean, declared, no heat, and worth less than the street.
 *
 * @return why it didn't happen, or null if it did
 */
public static String buy(ServerPlayerEntity buyer, Shop shop, Line line) {
    ServerWorld world = (ServerWorld) buyer.getWorld();
    int duty = TrapCity.dutyOn(line.price(), line.duty());
    int total = line.price() + duty;
    if (TrapMarket.wealthOf(buyer) < total) {
        return "That's " + total + "e and you have " + TrapMarket.wealthOf(buyer) + "e.";
    }
    if (buyer.getUuid().equals(shop.owner)) {
        return "It's your own shop.";
    }
    if (!take(world, shop, line)) {
        return "Sold out.";
    }
    TrapMarket.collect(buyer, total);
    shop.till += line.price();
    shop.sold++;
    shop.turnover += line.price();
    TrapCity.receive(duty, line.duty());
    TrapLedger.record(buyer, TrapLedger.Source.MARKET, -total);
    ItemStack bought = line.sample().copy();
    bought.setCount(line.count());
    buyer.getInventory().offerOrDrop(bought);
    save();
    return null;
}
```

Note this does **not** touch `TrapPayroll` — a player's emeralds already
exist, so the purse is not involved. That asymmetry is the point of having a
purse at all.

**Step 2: The screen**

`ShelfScreenHandler` takes `(int syncId, PlayerInventory, TrapShops.Shop)`,
lists `TrapShops.lineFor` over `TrapShops.stockOf`, one entry per distinct
label, and calls `TrapShops.buy` on click. Lift `tag`, `empty`, `sign`,
`purse`, `plain`, `line` and `ReadOnlySlot` from `StallScreenHandler` and
change what they read.

**Step 3: Wire it to the block**

In `MarketShelfBlock.onUse`, after the `shop == null` guard, replace the
informational message with:

```java
        if (shop.owner().equals(who.getUuid())) {
            // The owner gets the back office, same as the till gives them.
            TrapShops.Shop mine = shop;
            who.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                    (syncId, inventory, ignored) -> new ShopScreen(syncId, inventory, mine),
                    Text.literal(shop.name()).formatted(Formatting.GOLD)));
            return ActionResult.SUCCESS;
        }
        TrapShops.Shop theirs = shop;
        who.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, inventory, ignored) -> new ShelfScreenHandler(syncId, inventory, theirs),
                Text.literal(shop.name()).formatted(Formatting.GOLD)));
        return ActionResult.SUCCESS;
```

Add the imports `net.minecraft.screen.SimpleNamedScreenHandlerFactory`, drop
the now-unused `Inventory` import if nothing else uses it.

**Step 4: Verify and commit**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 24) ./gradlew build
```

```bash
git add src/main/java/dev/heezq/trapcraft/ShelfScreenHandler.java src/main/java/dev/heezq/trapcraft/TrapShops.java src/main/java/dev/heezq/trapcraft/MarketShelfBlock.java && git commit -m "A shelf is a shop window, and the till is the back office"
```

---

## Task 11: Stop calling three things "market"

**Files:**
- Modify: `tools/gen_assets.py:486-490` (the lang block)

**Do not change any block or item ID.** `market_shelf` is in every placed
block and every save file on the live world; renaming the ID orphans them.
Display names only.

**Step 1: Edit the lang entries**

```python
        "block.trapcraft.market_shelf": "Shop Shelf",
        "item.trapcraft.market_shelf": "Shop Shelf",
```

Leave `market_stall` alone — it is genuinely a market stall.

**Step 2: Regenerate and verify**

```bash
python3 tools/gen_textures.py && python3 tools/gen_assets.py && python3 tools/check_models.py
```

**Step 3: Commit**

```bash
git add tools/gen_assets.py src/main/resources/assets && git commit -m "Three things called market is two too many"
```

---

## Task 12: Models worth looking at

**Files:**
- Modify: `tools/gen_assets.py` — `till_assets()` (~:2137) and `shelf_assets()` (~:2224)
- Modify: `tools/gen_textures.py` — new material sprites

Both blocks are single 16×16×16 cuboids today. Use the `box()` helper and
follow `stall_model()` (~:1964) for the house style: each cuboid a closed
shell, no UVs unless a face needs the whole drawing, `ambientocclusion` off.

**Step 1: `shop_till` — a register standing on a counter**

```python
    put(f"assets/{NS}/models/block/shop_till.json", {
        "parent": "minecraft:block/block",
        "ambientocclusion": False,
        "textures": {
            "counter": f"{NS}:block/shop_till_side",
            "top": f"{NS}:block/shop_till_top",
            "body": f"{NS}:block/shop_till_front",
            "keys": f"{NS}:block/shop_till_keys",
            "screen": f"{NS}:block/shop_till_screen",
            "particle": f"{NS}:block/shop_till_side",
        },
        "elements": [
            box([0, 0, 0], [16, 11, 16], "counter", up="top"),      # the counter
            box([2, 11, 3], [14, 14, 13], "body", up="body"),       # register body
            box([3, 14, 4], [13, 15, 9], "keys", up="keys"),        # the key deck
            box([4, 14, 9], [12, 17, 12], "screen"),                # the display
        ],
    })
```

**Step 2: `market_shelf` — actual shelving**

```python
    put(f"assets/{NS}/models/block/market_shelf.json", {
        "parent": "minecraft:block/block",
        "ambientocclusion": False,
        "textures": {
            "frame": f"{NS}:block/market_shelf_side",
            "board": f"{NS}:block/market_shelf_top",
            "goods": f"{NS}:block/market_shelf_front",
            "particle": f"{NS}:block/market_shelf_side",
        },
        "elements": [
            box([0, 0, 0], [16, 1, 16], "frame", up="board"),   # the base
            box([0, 1, 14], [16, 16, 16], "frame"),             # the back panel
            box([0, 1, 0], [2, 16, 14], "frame"),               # left upright
            box([14, 1, 0], [16, 16, 14], "frame"),             # right upright
            box([2, 7, 1], [14, 8, 14], "board", up="board"),   # middle board
            box([2, 15, 1], [14, 16, 14], "board", up="board"), # top board
            box([2, 1, 2], [14, 7, 13], "goods"),               # goods, lower
            box([2, 8, 2], [14, 15, 13], "goods"),              # goods, upper
        ],
    })
```

**Step 3: The textures**

`shop_till_keys`, `shop_till_screen`, `market_shelf_front/side/top` — some
exist, some are new. Find how `stall_counter`, `stall_awning` and
`stall_goods` are drawn in `tools/gen_textures.py` and match their palette so
a street of stall, kiosk and shelf reads as one set. A missing sprite is a
purple-and-black block, not a crash, so check the render.

**Step 4: Regenerate and check**

```bash
python3 tools/gen_textures.py && python3 tools/gen_assets.py && python3 tools/check_models.py
```

Expected: no typo'd texture refs, no out-of-range elements.

**Step 5: Commit**

```bash
git add tools/ src/main/resources/assets && git commit -m "A till that looks like a till"
```

---

## Task 13: Work trips

**Files:**
- Modify: `src/main/java/dev/heezq/trapcraft/TrapShops.java` (the `Shopper` record and `arrive` / `shepherd`)

Purely cosmetic. Payroll is aggregate and already ran; this is what makes the
town look like it has jobs.

**Step 1: Give the trip a kind**

```java
    private enum Trip { SHOP, WORK }

    private record Shopper(BlockPos target, String dimension, int bornAt, Trip trip) {
    }
```

**Step 2: Pick a job site**

```java
    /**
     * Somewhere a person could plausibly be working: whatever players built.
     *
     * No new block and no POI registration -- a town's jobs ARE the tills,
     * tables, stalls and the vault somebody put down, and a village with none
     * of those simply has nobody commuting, which is honest.
     */
    private static BlockPos jobSite(MinecraftServer server, Random random) {
        List<BlockPos> sites = new ArrayList<>();
        for (Shop shop : SHOPS) {
            sites.add(shop.pos);
        }
        for (TrapStalls.Stall stall : TrapStalls.all()) {
            sites.add(stall.pos());
        }
        if (TrapCity.founded()) {
            sites.add(TrapCity.vaultAt());
        }
        return sites.isEmpty() ? null : sites.get(random.nextInt(sites.size()));
    }
```

Check `TrapStalls`' accessor names before relying on `all()` / `pos()`.

**Step 3: Roll a work trip in `maybeVisit`**

Roughly a third of arrivals head to a job site instead of a shelf. On reaching
it, instead of `buy`, spawn a few `ParticleTypes.HAPPY_VILLAGER`, wait out a
short shift, and `leave`. Nothing else happens — no money moves, because
payday already moved it.

**Step 4: Verify and commit**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 24) ./gradlew build
```

```bash
git add src/main/java/dev/heezq/trapcraft/TrapShops.java && git commit -m "Somebody has to be at work for the wages to make sense"
```

---

## Task 14: Put it in the books

**Files:**
- Modify: `src/main/java/dev/heezq/trapcraft/TrapCity.java:559-662` (`logDay`)

**Step 1: Append three columns**

At the **end** of both the header row and the data row (existing CSVs already
have a header; appending at the end keeps old rows readable as short rows
rather than mis-aligned ones):

```python
purse_town,wages,income_tax
```

```java
                    .append(TrapPayroll.purse()).append(',')
                    .append(TrapPayroll.wagesPaid()).append(',')
                    .append(TrapPayroll.incomeTax()).append('\n');
```

Move the existing `'\n'` off `TrapLaw.owedTotal()` onto the last new column.

**Step 2: Add the purse to `/city`**

In `books`, next to the treasury line:

```java
                .append(Text.literal("   town purse ").formatted(Formatting.DARK_GRAY))
                .append(Text.literal(TrapPayroll.purse() + "e")
                        .formatted(Formatting.AQUA))
```

**Step 3: Verify and commit**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 24) ./gradlew build
```

```bash
git add src/main/java/dev/heezq/trapcraft/TrapCity.java && git commit -m "A row a day that says whether the multiple is wrong"
```

---

## Task 15: Full verification

**Step 1: Everything**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 24) ./gradlew build && python3 tools/check_models.py && python3 tools/check_stock.py && python3 tools/check_pages.py && python3 tools/check_shaders.py
```

Expected: BUILD SUCCESSFUL and four clean checks.

**Step 2: The one invariant worth grepping for**

```bash
grep -rn "TrapMarket.minted" src/main/java/dev/heezq/trapcraft/
```

Expected: exactly one hit, `TrapPayroll.earned`. Any other is a fountain that
survived.

**Step 3: In game**

Never against the live world — see the memory note on live server testing.
Sync the jar into Prism and check, in order:

1. Place an anvil-named shop till → the shop has that name in `/shops`.
2. Put a shelf down within 24 blocks, a chest under it, food in the chest.
3. Open the till → shelves page lists it; clicking the row sparkles the shelf.
4. Second player right-clicks the shelf → buy screen, correct price and duty.
5. `/city` → the town purse is non-zero and moves across a couple of days.
6. Demolish the housing → the purse drains, the shop goes quiet, and comes
   back when houses do.

**Step 4: Deploy**

One restart, not several — see the deploy discipline note. Batch this with
anything else pending.

---

## Notes for the implementer

- **Fail closed everywhere.** Every converted site must refuse the whole
  transaction rather than proceed unpaid. Task 5's ordering is the pattern.
- **`WAGE_MULTIPLE` will be wrong first time.** That is expected and safe —
  Task 2's cap means a wrong value is a slow town or a busy one, never a
  runaway one. Task 14's CSV says which way to move it.
- **Blockstate pools are nearly dry** on this pack. Nothing here registers a
  new block, and it must stay that way.
- **Assets are generated.** Run the generators; never hand-edit
  `src/main/resources/assets/`.
