package dev.heezq.trapcraft;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A visitor is not a resident, and the difference is load-bearing.
 *
 * {@code TrapHomes.population()} feeds rent, payroll, house reputation and the
 * casino's own cap on how many people can be out at once. Somebody passing
 * through who gets counted as a tenant corrupts all four at once, and none of
 * it is visible in any formula: it presents as the town quietly getting richer
 * and busier than anybody built it to be.
 *
 * The other half is the departure. {@link TrapFloor} already learned once that
 * a villager whose AI is switched off to root them at a machine, and which
 * nothing switches back on, stands at that cabinet for good. A visitor has no
 * bed and no entry in the register, so the two resident departure calls --
 * {@code sendHome} and {@code stayIn} -- are exactly how one becomes furniture.
 *
 * Source-read, like {@link ResidentTest}, for the same reason: ugly, and
 * cheaper than the evening it costs to find out which of the four it was.
 */
class TouristTest {

    private static String source(String file) throws Exception {
        return Files.readString(Path.of("src/main/java/dev/heezq/trapcraft/" + file));
    }

    @Test
    void aVisitorIsNeverATenant() throws Exception {
        String visitors = source("TrapVisitors.java");
        assertFalse(visitors.contains("addCommandTag(TrapHomes.TENANT_TAG)"),
                "a visitor must never be tagged a tenant: population feeds rent, "
                        + "payroll, reputation and the casino's room() cap, and a "
                        + "fake resident corrupts all four at once");
    }

    @Test
    void aVisitorLeavesTownRatherThanGoingHome() throws Exception {
        String visitors = source("TrapVisitors.java");
        assertTrue(visitors.contains("body.discard()"),
                "a visitor has no home, so the trip ends by leaving the world");
        assertFalse(visitors.contains("TrapHomes.sendHome")
                        || visitors.contains("TrapHomes.stayIn"),
                "sendHome and stayIn are the resident paths -- a visitor down "
                        + "either of them is the frozen-villager-at-a-cabinet bug "
                        + "the floor already learned once");
    }

    @Test
    void theFloorSendsVisitorsBackToWhoeverOwnsThem() throws Exception {
        String floor = source("TrapFloor.java");
        assertTrue(floor.contains("TrapVisitors.errandDone(punter.id)"),
                "when the floor is done with somebody from out of town it must "
                        + "hand them back rather than walk them to a house they "
                        + "have not got");
        assertTrue(floor.contains("if (punter.visitor) {"),
                "the resident departure path must be behind a branch, or every "
                        + "visitor ends the night as a statue");
    }

    @Test
    void theFloorStillDoesNotConjureItsOwnPunters() throws Exception {
        String floor = source("TrapFloor.java");
        assertFalse(floor.contains("EntityType.VILLAGER.create"),
                "conjuring lives in TrapVisitors and nowhere else -- a punter is "
                        + "still a resident who walked over, and the floor filling "
                        + "with people the register never heard of is the exact lie "
                        + "that was deleted once already");
    }

    @Test
    void aVisitorIsMarkedByItsClothesAndNotByAnAsset() throws Exception {
        String visitors = source("TrapVisitors.java");
        assertTrue(visitors.contains("VillagerType"),
                "the marker is an out-of-town villager type: it costs no texture "
                        + "and, more to the point, no Polymer carrier -- the pack "
                        + "booted with sixteen left in BIOME_TRANSPARENT_BLOCK");
        assertFalse(visitors.contains("VillagerType.PLAINS"),
                "plains is what everybody local wears, so a visitor wearing it is "
                        + "a visitor you cannot pick out of the crowd");
    }

    @Test
    void aVisitorsMoneyIsMoneyTheIndexHasSeen() throws Exception {
        String visitors = source("TrapVisitors.java");
        assertTrue(visitors.contains("TrapMarket.minted(amount)")
                        && visitors.contains("TrapMarket.minted(-amount)"),
                "outside money lands in a vault rather than on a player, so it "
                        + "cannot ride in on pay() -- it has to be minted in and "
                        + "out, or the index drifts and everybody's prices move "
                        + "for a reason nobody can name");
        assertFalse(visitors.contains("TrapPayroll.spend"),
                "a visitor's night out must not come off the town's wage bill: "
                        + "that is the ceiling this whole feature exists to lift");
    }

    @Test
    void theCityTakesItsCutOfSomebodyPassingThrough() throws Exception {
        String floor = source("TrapFloor.java");
        assertTrue(floor.contains("TrapCity.receive(duty, TrapCity.Duty.GAMING)"),
                "gaming duty on a visitor's stake is the drain welded to the "
                        + "faucet -- without it outside money is just a tap with "
                        + "better manners");
    }
}
