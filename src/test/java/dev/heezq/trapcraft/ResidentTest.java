package dev.heezq.trapcraft;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One person is in one place.
 *
 * The rule the town runs on, and the one nothing else can check. Every way it
 * has been broken so far was a line that looked local and correct: a census
 * that counted a box instead of the world, a sweep that recognised the head of
 * a household and nobody else, a casino that switched a villager's AI off and
 * never switched it back on. Each of those, on its own, presents identically
 * from inside the game -- residents flickering in and out at their front doors
 * and two of the same person in two places -- and none of them is visible in
 * any formula.
 *
 * So this reads the source, the way {@link DealerBodyTest} does, and for the
 * same reason: ugly, and cheaper than the evening it costs to find out which
 * of the three it was this time.
 */
class ResidentTest {

    private static String source(String file) throws Exception {
        return Files.readString(Path.of("src/main/java/dev/heezq/trapcraft/" + file));
    }

    @Test
    void aPunterIsSomebodyWhoAlreadyLivesHere() throws Exception {
        String floor = source("TrapFloor.java");
        assertFalse(floor.contains("EntityType.VILLAGER.create"),
                "the floor must not conjure punters: a punter is a resident who walked "
                        + "over, which is what makes the casino's crowd and the town's "
                        + "population the same people counted once");
        assertTrue(floor.contains("Math.min(town, Math.round(town * NIGHT_SHARE * busy))"),
                "the town is the CEILING on a floor -- twenty-four residents can never "
                        + "be twenty-five punters -- but it must not be the number, or "
                        + "everybody is at a slot machine at all hours and the town has "
                        + "no more people in it than one where nobody is");
    }

    @Test
    void somebodyWhoHasHadTheirGoGoesHomeAndStaysThere() throws Exception {
        String homes = source("TrapHomes.java");
        assertTrue(homes.contains("STAYING_IN.get(villager.getUuid())"),
                "freeResident() must read the cooldown. It picks the NEAREST free "
                        + "tenant, and somebody who has just finished an errand is "
                        + "standing at the shop or the casino -- so without it they are "
                        + "picked again seconds later, forever, and the same half-dozen "
                        + "people live at the counters while the rest of town never goes");
        for (String caller : new String[]{"TrapFloor.java", "TrapShops.java"}) {
            assertTrue(source(caller).contains("TrapHomes.stayIn("),
                    caller + " must put somebody in after their errand, or the two "
                            + "systems hand the same person straight to each other");
        }
    }

    @Test
    void somebodyOnShiftIsNotAlsoStandingAtHome() throws Exception {
        String shops = source("TrapShops.java");
        String homes = source("TrapHomes.java");
        int on = shops.indexOf("private static void clockOn(");
        assertTrue(on >= 0, "clockOn() has gone");
        String body = shops.substring(on, shops.indexOf("\n    }", on));
        assertTrue(body.contains("TrapHomes.goToWork(shopper,"),
                "clocking on has to hand the body to the register. Left standing at the "
                        + "counter, a shift is eight seconds of a villager Brain in "
                        + "somebody's shop and then a walk home that starts indoors -- "
                        + "which is how the town ends up lost in your building");
        assertTrue(homes.contains("- atWork(world, home.id)"),
                "and the census has to count them out, or the house spawns a replacement "
                        + "while they are at work and the shift ends with two of them");
        assertTrue(homes.contains("AT_WORK.removeIf(shift -> shift.until() <= world.getTime())"),
                "shifts have to expire, or the first day's work is the last anybody sees "
                        + "of that person");
    }

    @Test
    void anErrandEndsOnTheirOwnDoorstepRatherThanIndoors() throws Exception {
        for (String caller : new String[]{"TrapShops.java", "TrapFloor.java",
                "TrapClubs.java"}) {
            assertTrue(source(caller).contains("TrapHomes.putHome("),
                    caller + " must PUT them home at the end of an errand. Every one of "
                            + "these finishes indoors -- a till, a machine, a bar -- and "
                            + "sendHome walks anybody within forty blocks, which is a "
                            + "villager asked to path out of a building it was teleported "
                            + "into. The target is reissued every pass, never reached, and "
                            + "the town spends the week lost in somebody's shop");
        }
    }

    @Test
    void hiringSomebodyPutsSomebodyBehindTheCounter() throws Exception {
        String shops = source("TrapShops.java");
        int hire = shops.indexOf("public static String staff(");
        assertTrue(hire >= 0, "staff() has gone");
        String body = shops.substring(hire, shops.indexOf("\n    }", hire));
        assertTrue(body.contains("shop.lastPaid = TrapMarket.today("),
                "hiring must start their first day NOW. Left stale, the wage round ten "
                        + "seconds later reads 'a day is owed', takes the wage off a till "
                        + "that has not earned any yet, and walks them out through the "
                        + "continue that sits above stand() -- so on a fresh shop, hiring "
                        + "somebody spawned nobody at all, ever");
        assertTrue(body.contains("stand(world, shop)"),
                "and they are stood up on hire rather than on the next wage round, so "
                        + "somebody is at the counter by the time the screen closes");
    }

    @Test
    void aBittenShopkeeperGoesOnSickLeaveRatherThanBeingReplaced() throws Exception {
        String shops = source("TrapShops.java");
        int stand = shops.indexOf("private static void stand(ServerWorld world, Shop shop)");
        assertTrue(stand >= 0, "stand() has gone");
        String body = shops.substring(stand, shops.indexOf("\n    }", stand));
        assertTrue(body.contains("ZombieVillagerEntity.class"),
                "a villager that is bitten becomes a ZombieVillagerEntity -- a NEW "
                        + "entity with a new id wearing the same tags -- so the id in "
                        + "KEEPERS goes stale, stand() finds nobody and hires another, "
                        + "who has the same night ahead of them. That is how a counter "
                        + "grows a crowd of zombies");
        assertTrue(body.contains("shop.sick = true"),
                "and the answer is sick leave, not a replacement");
        assertTrue(shops.contains("if (shop.sick) {\n                continue;\n            }"),
                "the wage round must stop at a sick keeper, or the boss pays somebody "
                        + "who is a zombie");
    }

    @Test
    void aBittenShopkeeperIsTakenToHospitalAndComesBackOnAClock() throws Exception {
        String shops = source("TrapShops.java");
        assertTrue(shops.contains("TrapHospitals.takeIn(world, turned)"),
                "a bitten keeper goes to a ward. One who can only be saved by a player "
                        + "standing over them with a splash potion is one who stays a "
                        + "zombie -- the city has a hospital for exactly this");
        assertTrue(shops.contains(".append(shop.backOn).append(' ')"),
                "and the due-back day must be WRITTEN DOWN. Once they have been carried "
                        + "off there is nothing at the counter to look at, so this number "
                        + "is the only thing that knows they are still off -- a transient "
                        + "one has every restart hire over somebody lying in a hospital");
        assertTrue(shops.contains("money.length > 5 ? Long.parseLong(money[5]) : -1"),
                "read back length-guarded, so an older register still loads");
    }

    @Test
    void aShopWhoseTillIsGoneStopsHiring() throws Exception {
        String shops = source("TrapShops.java");
        assertTrue(shops.contains("instanceof ShopTillBlock)"),
                "the register must ask the world what is at the spot. onStateReplaced "
                        + "only fires when a PLAYER breaks the till -- a piston (skipped "
                        + "as `moved`), an explosion or another mod left the shop "
                        + "re-hiring a keeper at an address with no counter, forever");
        int home = shops.indexOf("private static void sendHome(");
        String body = shops.substring(home, shops.indexOf("\n    }", home));
        assertTrue(body.contains("KEEPER_TAG"),
                "and closing a shop must clear its keepers by TAG. The remembered id is "
                        + "wrong for a keeper who was bitten and for one left behind by a "
                        + "till that moved -- both leave a body nothing will ever look for");
    }

    @Test
    void thePurseCanBePaidIntoAndTheWorksHaveTiers() throws Exception {
        String city = source("TrapCity.java");
        assertTrue(city.contains("public static String payIn("),
                "the vault was a one-way tap: withdraw hands the treasury out, donate "
                        + "grants a casino its float, and the only two inflows are duties "
                        + "skimmed off transactions other people made -- so 28,000e of "
                        + "public works sat behind a purse nobody could fill");
        int built = city.indexOf("public static boolean built(");
        String body = city.substring(built, city.indexOf("\n    }", built));
        assertTrue(body.contains("level(work) >= 1"),
                "built() must stay 'is there one'. Eleven places ask it -- the clinic's "
                        + "discount, the school's wages, the watch's patrols -- and every "
                        + "one means 'is there one', not 'how many'. Change what it "
                        + "returns and every public work switches off at once, silently");
        assertTrue(city.contains("parts.length > 2 ? Integer.parseInt(parts[2]) : 1"),
                "and a 'built' line written before levels existed is one built once");
    }

    @Test
    void owningThingsCostsSomethingEveryDay() throws Exception {
        String city = source("TrapCity.java");
        assertTrue(city.contains("private static void rates("),
                "a shop and a let house were the only businesses with no outgoings at "
                        + "all -- a casino pays upkeep and a crew wants wages, but a "
                        + "landlord simply got richer every morning forever");
        int rates = city.indexOf("private static void rates(");
        String body = city.substring(rates, city.indexOf("\n    }", city.indexOf("save();", rates)));
        assertTrue(body.contains("treasury += owed"),
                "the rates must land in the purse the works come out of, or the loop "
                        + "does not close and this is just a fine");
    }

    @Test
    void thereAreEnoughNamesToGoRound() {
        java.util.Set<String> pool = new java.util.HashSet<>();
        for (int seed = 0; seed < 500; seed++) {
            String name = TrapHomes.nameFor(seed);
            pool.add(name);
            assertTrue(!name.isBlank() && name.length() <= 8,
                    "\"" + name + "\" is the wrong shape for a nameplate -- these sit "
                            + "over a villager's head next to a stake or a receipt, and a "
                            + "long one pushes the plate wider than the villager");
        }
        assertTrue(pool.size() >= 80,
                "only " + pool.size() + " names. The pool used to be exactly the size of "
                        + "the town, which let the pigeonhole principle do the naming: "
                        + "every household had a duplicate in it and the street read as "
                        + "four copies of six people");
    }

    @Test
    void thereIsOneTownAndBothErrandsDrawOnIt() throws Exception {
        String homes = source("TrapHomes.java");
        int out = homes.indexOf("public static boolean out(");
        assertTrue(out >= 0, "TrapHomes no longer answers who is out");
        String body = homes.substring(out, homes.indexOf("\n    }", out));
        assertTrue(body.contains("TrapFloor.PUNTER_TAG") && body.contains("TrapShops.TAG"),
                "'is this person out' has to mean BOTH errands. Two systems each "
                        + "keeping their own idea of who is free is two systems that "
                        + "will each send the same person somewhere at the same time");

        assertFalse(source("TrapShops.java").contains("EntityType.WANDERING_TRADER.create"),
                "a shopper must be a resident who walked over. The conjured traders "
                        + "wore a name picked at random out of the housing register, so "
                        + "Lom bought bread at a till while the real Lom sat at home");
        assertFalse(homes.contains("someoneFromTown"),
                "and the helper that painted a resident's name onto a stranger should "
                        + "be gone with them, or it invites the next one");
    }

    @Test
    void theCensusAsksTheWorldRatherThanABoxRoundTheHouse() throws Exception {
        String homes = source("TrapHomes.java");
        int census = homes.indexOf("List<? extends net.minecraft.entity.passive.VillagerEntity> living");
        assertTrue(census >= 0, "keepBodies no longer counts the household");
        assertTrue(homes.indexOf("getEntitiesByType", census) >= 0
                        && homes.indexOf("getEntitiesByType", census)
                        < homes.indexOf("for (var turned", census),
                "the household must be counted wherever its people are standing -- "
                        + "counting a box round the house declares anybody out at a "
                        + "machine missing and spawns a second copy of them at home");
    }

    @Test
    void theStraySweepKnowsTheWholeHouseholdAndNotJustItsHead() throws Exception {
        String homes = source("TrapHomes.java");
        int sweep = homes.indexOf("private static void sweep(ServerWorld world, BlockPos near)");
        assertTrue(sweep >= 0, "the stray sweep has gone");
        String body = homes.substring(sweep, homes.indexOf("public static void touch()", sweep));
        assertTrue(body.contains("TENANT_TAG + \"_\" + home.id"),
                "the sweep must recognise a body by the HOUSE named on it. Matching "
                        + "home.body only knows the head of the household, so everybody "
                        + "else in every house was binned on sight and respawned at the "
                        + "anchor twelve seconds later");
        assertFalse(body.contains("living.contains"),
                "membership by body id is the bug this test exists for");
    }

    @Test
    void aNightOutIsUndoneWhenItEnds() throws Exception {
        String floor = source("TrapFloor.java");
        int leave = floor.indexOf("private static void leave(ServerWorld world");
        assertTrue(leave >= 0, "leave() has gone");
        String body = floor.substring(leave, floor.indexOf("\n    }", leave));
        assertTrue(body.contains("removeCommandTag(PUNTER_TAG)")
                        && body.contains("setAiDisabled(false)"),
                "a punter is rooted at the machine by switching their AI off, so leave() "
                        + "must switch it back on and drop the tag -- otherwise every "
                        + "resident who ever had a night out stands frozen at that cabinet "
                        + "for good while their house makes another one of them");
    }
}
