package dev.heezq.trapcraft;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The seams between systems, and the four ways they fail silently.
 *
 * Everything pinned here is a line that looks local and correct and whose
 * failure shows up in a different file, days later, as a number nobody can
 * account for. Source-read for {@link ResidentTest}'s reason: none of it is a
 * formula, all of it needs a world, and reading the text is cheaper than the
 * evening it costs to find out which seam it was.
 */
class WiringTest {

    private static String source(String file) throws Exception {
        return Files.readString(Path.of("src/main/java/dev/heezq/trapcraft/" + file));
    }

    /**
     * A visitor handed to a resident path becomes furniture.
     *
     * Written down three times already -- {@link TrapFloor}, {@link TrapShops}
     * and {@link TrapVisitors} all carry a note about it -- because
     * {@code stayIn} and {@code putHome} both assume a bed that somebody
     * passing through has not got. The symptom is a villager standing at a
     * cabinet or a bar forever, and the cause is one missing branch.
     */
    @Test
    void aVisitorIsNeverSentHome() throws Exception {
        String clubs = source("TrapClubs.java");
        int leave = clubs.indexOf("private static void leave(MinecraftServer");
        assertTrue(leave >= 0, "TrapClubs.leave() has gone");
        String body = clubs.substring(leave, clubs.indexOf("\n    }", leave));
        int handed = body.indexOf("TrapVisitors.errandDone(");
        int home = body.indexOf("TrapHomes.putHome(");
        assertTrue(handed >= 0,
                "a club must hand a visitor back to TrapVisitors when the night ends, "
                        + "or they stand on the dance floor until the trip's backstop "
                        + "expires and then vanish mid-song");
        assertTrue(handed < home,
                "the visitor branch must RETURN before putHome/stayIn are reached: "
                        + "somebody passing through has no house to be put in, and that "
                        + "is exactly how TrapFloor turned punters into statues");
    }

    /**
     * A wage paid to a townsperson is a transfer, not a hole.
     *
     * {@link TrapHospitals} settled this for the doctors and {@link TrapPayroll}
     * states it as the rule: one mint, at payday, and everything downstream
     * moves money that already exists. A crew is the largest recurring outgoing
     * a player has, so a {@code take} here quietly deletes the biggest wage
     * bill in the city and the shops go poor for reasons three files away.
     */
    @Test
    void crewWagesReachTheTownRatherThanTheVoid() throws Exception {
        String crew = source("TrapCrew.java");
        assertFalse(crew.contains("TrapMarket.take(boss"),
                "every emerald a crew is paid must go through payTheTown(): "
                        + "TrapMarket.take destroys money, and a hand is a townsperson "
                        + "whose wage should come back through a shop door");
        assertTrue(crew.contains("TrapPayroll.credit(amount)"),
                "payTheTown() must credit the town purse, or the money is collected "
                        + "off the player and lands nowhere at all -- which is worse "
                        + "than the bug it replaced");
    }

    /**
     * A hand is somebody who already lives here.
     *
     * The last phantom in the mod: a crew used to create villagers out of
     * nothing, which meant the town's population never decided whether you
     * could do anything. Conjuring one again would restore that silently --
     * everything would work, and housing would simply stop mattering.
     */
    @Test
    void aHandIsDrawnFromTheTown() throws Exception {
        String crew = source("TrapCrew.java");
        assertFalse(crew.contains("EntityType.VILLAGER.create"),
                "the crew must not conjure hands: they come through "
                        + "TrapHomes.freeResident, which is what makes a town's "
                        + "population the supply of labour");
        assertTrue(crew.contains("TrapHomes.freeResident(world, patch, HIRE_REACH)"),
                "put() must ask the housing register for somebody free");
        assertTrue(source("TrapHomes.java").contains("TrapCrew.HAND_TAG"),
                "out() must know about a hand, or a shop can call somebody away "
                        + "mid-harvest and the house sweep walks them home from the "
                        + "field they are employed to work");
        int release = crew.indexOf("private static void release(");
        assertTrue(release >= 0, "release() has gone");
        String body = crew.substring(release, crew.indexOf("\n    }", release));
        assertTrue(body.contains("removeCommandTag(HAND_TAG)") && body.contains("setBaseValue"),
                "letting somebody go must take the tag AND the attributes back off: "
                        + "a released hand that keeps the tag is a townsperson deleted "
                        + "from the labour force for good, and one that keeps the scale "
                        + "leaves the town slowly filling with small fast people");
    }

    /**
     * A fire never eats a build.
     *
     * The hard rule the whole of {@link TrapFires} is written to, and the one
     * thing in it that could not be walked back: a block broken by an accident
     * nobody caused is somebody's evening gone with no counterplay and no
     * author. Pinned as an absence, because the failure mode is a line somebody
     * adds later to make it feel more real.
     */
    @Test
    void nothingActuallyBurnsDown() throws Exception {
        String fires = source("TrapFires.java");
        for (String forbidden : new String[]{
                "breakBlock", "setBlockState", "Blocks.FIRE", "Blocks.SOUL_FIRE"}) {
            assertFalse(fires.contains(forbidden),
                    "TrapFires must never touch the world (" + forbidden + "): a fire is "
                            + "a state on a register entry drawn as particles, and the "
                            + "damage it does is to things this mod invented and can "
                            + "honestly take back");
        }
        assertTrue(fires.contains("TrapHospitals.hurt("),
                "a scorched house must send its tenant through the door TrapHospitals "
                        + "already opens for a bite and a beating, rather than growing a "
                        + "second casualty ward");
    }

    /**
     * The council can never pay more for goods than the counter charges.
     *
     * A city order priced at or above {@link TrapMarket#buyPrice} is a machine
     * that turns emeralds into emeralds: buy the lot off the market, carry it
     * to the vault, sell it back, repeat. It would be the fastest money in the
     * mod and would read as a feature for about a day.
     */
    @Test
    void aCityOrderCannotBeArbitraged() throws Exception {
        assertTrue(source("TrapCity.java").contains("TrapMath.stallPrice(TrapMarket.buyPrice("),
                "an order must be priced through stallPrice(), which is under 100% of "
                        + "what the counter asks -- anything at or over it is an "
                        + "emerald printer with a queue");
        // Never OVER, which is the property that matters. Rounding makes the
        // two equal on the cheapest few lines -- stallPrice(2) is 2 -- and
        // that is still not a loop: the buyer pays duty on top of the counter's
        // price and the seller pays duty on the council's, so a round trip at
        // parity loses money twice. Over it, the arithmetic reverses.
        for (int market = 1; market < 4000; market++) {
            assertTrue(TrapMath.stallPrice(market) <= market,
                    "stallPrice(" + market + ") = " + TrapMath.stallPrice(market)
                            + " must never exceed the counter's own asking price, or the "
                            + "vault becomes the best shop in town to sell the market's "
                            + "own stock back to");
        }
    }

    /**
     * Speculation is not the one legal way to earn that pays nobody.
     *
     * The untaxed black market is the deliberate shape of this mod. A coin
     * market outside the duty system quietly gave that property to a legal,
     * risk-free, indoor activity as well -- which is the one thing that makes
     * the whole revenue office pointless.
     */
    @Test
    void coinGainsPayDuty() throws Exception {
        String coins = source("TrapCoins.java");
        assertTrue(coins.contains("TrapCity.charge(player, Math.max(0, paid - holding.spent())"),
                "a coin sale must pay duty on the GAIN: on the gross it would mean a "
                        + "trade that closed exactly where it opened lost a tenth of "
                        + "its money, which is a reason never to touch the feature");
    }

    /**
     * A bounty is the town's money, not new money.
     *
     * {@link TrapPayroll}'s own javadoc sets the rule -- check afford BEFORE
     * you hand anything over -- and a citizen's arrest is the one reward in
     * the mod paid straight out of the purse to a player. Pay first and ask
     * later and every collar mints emeralds against a purse that is allowed
     * to go negative nowhere else, which presents months later as the town
     * being unaccountably rich. The town already has two million it cannot
     * spend; a leak here would never be noticed.
     */
    @Test
    void aBountyIsSpentBeforeItIsPaid() throws Exception {
        String crime = source("TrapCrime.java");
        int collar = crime.indexOf("private static void collar(");
        assertTrue(collar >= 0, "TrapCrime.collar() has gone -- the player's only verb");
        String body = crime.substring(collar, crime.indexOf("\n    }", collar));
        int spend = body.indexOf("TrapPayroll.spend(");
        int pay = body.indexOf("TrapMarket.pay(");
        assertTrue(spend >= 0,
                "the bounty must come out of TrapPayroll's purse: a reward conjured "
                        + "for the catcher is the one mint this mod does not have");
        assertTrue(pay >= 0, "the catcher must actually be paid through TrapMarket.pay");
        assertTrue(spend < pay,
                "spend() must be checked BEFORE pay() hands the emeralds over, or a "
                        + "town too poor for the reward pays it anyway");
        assertFalse(body.contains("TrapPayroll.credit("),
                "nothing about an arrest credits the purse: the fine already moves "
                        + "money to the council inside caught()");
    }

    /**
     * The comedown may not refund the thing the drug was taken for.
     *
     * Powder grants SPEED, HASTE and STRENGTH; the crash bills SLOWNESS,
     * WEAKNESS and HUNGER. It also billed MINING_FATIGUE, and that single line
     * made the whole coca line mathematically pointless -- Haste is x1.2..x1.6
     * for 38-147s, Mining Fatigue I is x0.3 for 35-65s, and losing 70% for a
     * minute beats gaining 20% for two. In seconds-of-digging against sober:
     * Ciete -16.8, Uliczne -7.0, Dobre +17.5, Idealne +42.7. The two grades a
     * player actually produces most of were NEGATIVE, so taking the drug was
     * dominated by not taking it at every price a player would ever see.
     *
     * The live server proved it: trapcraft-addiction.txt was zero bytes after
     * 393 days, on a world where 291,574e of weed had been sold. People made
     * the stuff and nobody ever used any of it.
     *
     * Movement and combat are allowed to be billed -- Slowness against Speed
     * and Weakness against Strength are both net-positive over the hit, and a
     * crash that costs nothing is not a crash. Mining is the one axis where
     * the counter-effect is stronger than the buff, so it is the one axis the
     * crash must stay off.
     */
    @Test
    void theCrashDoesNotCancelTheHigh() throws Exception {
        String wired = source("WiredStatusEffect.java");
        int crash = wired.indexOf("private static void crash(");
        assertTrue(crash >= 0, "WiredStatusEffect.crash() has gone");
        String body = wired.substring(crash, wired.indexOf("\n    }", crash));
        assertFalse(body.contains("MINING_FATIGUE"),
                "the crash must not apply MINING_FATIGUE: it is x0.3 against a Haste of "
                        + "x1.2-x1.6, so it more than refunds the only thing the powder "
                        + "is taken for and makes the coca line negative at the two "
                        + "commonest purities");
        assertTrue(body.contains("SLOWNESS") && body.contains("HUNGER"),
                "the crash still has to cost something, or the powder is a free buff");
    }

    /**
     * A crop no hired hand will touch is a crop nobody grows.
     *
     * Poppy shipped missing from both of {@link TrapCrew}'s lists -- the one
     * that decides there is a job here and the one that does it -- so it was
     * the only plant in the mod that could not be farmed with money. On a
     * server where the players hold a couple of hundred thousand emeralds
     * each, that is the whole game: weed and coca scale by hiring another
     * hand, poppy scaled only by standing in the field yourself. It sold
     * exactly zero units in 393 days while weed did 291,574e, and the
     * difference was these two instanceof chains, not the price.
     *
     * Discovered rather than designed, so it is pinned by enumeration: any
     * future crop is caught by the same test without anybody remembering to
     * extend it.
     */
    @Test
    void everyCropCanBePickedByACrew() throws Exception {
        String crew = source("TrapCrew.java");
        List<String> crops = new ArrayList<>();
        try (var files = Files.list(Path.of("src/main/java/dev/heezq/trapcraft"))) {
            for (Path each : files.toList()) {
                String name = each.getFileName().toString();
                if (name.endsWith(".java") && Files.readString(each).contains("extends CropBlock")) {
                    crops.add(name.substring(0, name.length() - ".java".length()));
                }
            }
        }
        assertTrue(crops.size() >= 3, "expected at least the three drug crops, found " + crops);
        for (String crop : crops) {
            assertTrue(crew.contains("instanceof " + crop),
                    crop + " is never named in TrapCrew: a hand walks past it, so the "
                            + "only way to farm it is by hand and the line dies the way "
                            + "the poppy line did");
            assertTrue(crew.contains(crop + ") block).harvest(")
                            || crew.contains("instanceof " + crop + " "),
                    crop + " is recognised by TrapCrew but never harvested through its "
                            + "own harvest(): getDroppedStacks runs the loot table and "
                            + "returns a seed, so the hand dismantles the farm");
        }
    }
}
