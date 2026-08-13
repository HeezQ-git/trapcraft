package dev.heezq.trapcraft;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The four ways a hospital could quietly break something else.
 *
 * None of these is arithmetic, so none of them is a formula test: they are the
 * joins between the ward, the housing register and the treasury, and every one
 * of them presents from inside the game as an unrelated system misbehaving --
 * a town that mints money it never earned, a resident standing in two places,
 * rent that does not move when somebody is ill, a purse that goes negative.
 *
 * So this reads the source, the way {@link ResidentTest} does, and for the same
 * reason.
 */
class WardTest {

    private static String source(String file) throws Exception {
        return Files.readString(Path.of("src/main/java/dev/heezq/trapcraft/" + file));
    }

    @Test
    void theCityPaysTheDoctorsAndNobodyMintsIt() throws Exception {
        String wards = source("TrapHospitals.java");
        assertTrue(wards.contains("TrapCity.spend(fee)"),
                "treatment has to come OUT of the city purse -- a hospital that costs "
                        + "nothing is a hospital nobody has to fund, which is the whole "
                        + "of what this feature asks of a city");
        assertTrue(wards.contains("TrapPayroll.credit(fee)"),
                "and INTO the town's, because a doctor is a townsperson: credit moves "
                        + "money that already exists");
        assertFalse(wards.contains("TrapPayroll.earned("),
                "earned() is the one mint left in this mod. A ward that called it would "
                        + "pay its doctors with emeralds nobody was taxed for, and the "
                        + "market index would feel a shock that never happened");
    }

    @Test
    void aPatientIsNotAlsoStandingAtHome() throws Exception {
        String wards = source("TrapHospitals.java");
        String homes = source("TrapHomes.java");
        assertFalse(wards.contains("TENANT_TAG"),
                "a patient's body must not carry its house's tag: the housing census "
                        + "counts by that tag across the whole world, and a ward in an "
                        + "unloaded chunk would read as a missing resident and get a "
                        + "second copy spawned on the doorstep");
        assertTrue(homes.contains("home.heads - TrapHospitals.awayFrom(home.id)"),
                "so the house has to spawn to its household MINUS whoever is in a bed, "
                        + "or the same thing happens from the other end");
    }

    @Test
    void beingIllCostsTheHouseholdItsWage() throws Exception {
        String homes = source("TrapHomes.java");
        assertTrue(homes.contains("HomeSurvey.wageDue(home.tier, working, home.floor)"),
                "wages are charged per person WORKING, not per person living here -- "
                        + "an ill resident earning their wage from a hospital bed is the "
                        + "feature not existing");
        assertTrue(homes.contains("HomeSurvey.rentDue(home.tier, home.mood, working,"),
                "and rent comes out of those wages, so it has to move with them");
    }

    @Test
    void anUnpaidBillFailsClosed() throws Exception {
        String city = source("TrapCity.java");
        int spend = city.indexOf("public static boolean spend(int amount)");
        assertTrue(spend > 0, "TrapCity.spend must exist -- the ward bills through it");
        String body = city.substring(spend, spend + 400);
        assertTrue(body.contains("treasury < amount"),
                "spend() must refuse when the purse is short rather than clamping: a "
                        + "treasury that can go negative funds a public work it never "
                        + "had the money for");
        assertTrue(body.indexOf("return false") < body.indexOf("treasury -= amount"),
                "and it must refuse BEFORE it takes anything");
    }
}
