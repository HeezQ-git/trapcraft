package dev.heezq.trapcraft;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

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
}
