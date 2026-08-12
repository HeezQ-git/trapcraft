package dev.heezq.trapcraft;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The habit's one real formula.
 *
 * Only {@link TrapMath} is reachable from here -- it imports nothing from
 * Minecraft, which is the whole reason the pressure calculation lives there
 * rather than in TrapAddiction. The per-drug table is data and guards itself at
 * class load (see the static block in {@link Drug}); what is worth a test is
 * the arithmetic that decides whether somebody can walk.
 *
 * The claim under test is the one the design rests on: pressure is a PRODUCT,
 * so a small habit is not merely slow to hurt you, it is incapable of it.
 */
class AddictionTest {
    private static final float MAX = 100f;
    private static final int PERIOD = 14;              // weed's, in minutes
    private static final long FULL = PERIOD * 60L * 20L;   // ticks to ripen

    @Test
    void cleanMeterIsNeverAnyPressure() {
        assertEquals(0f, TrapMath.habitPressure(0f, MAX, FULL * 10, PERIOD), 0.0001f);
    }

    @Test
    void justUsedIsNoPressureHoweverHookedYouAre() {
        assertEquals(0f, TrapMath.habitPressure(MAX, MAX, 0, PERIOD), 0.0001f);
    }

    @Test
    void pressureRipensOverThePeriodAndThenStops() {
        float half = TrapMath.habitPressure(MAX, MAX, FULL / 2, PERIOD);
        float full = TrapMath.habitPressure(MAX, MAX, FULL, PERIOD);
        float later = TrapMath.habitPressure(MAX, MAX, FULL * 6, PERIOD);
        assertEquals(0.5f, half, 0.01f);
        assertEquals(1.0f, full, 0.001f);
        assertEquals(full, later, 0.0001f, "past the period it must plateau, not keep climbing");
    }

    /**
     * The load-bearing one.
     *
     * If this ever fails, somebody has turned the meter into a timer and a
     * player who smoked four joints last week can be made properly ill by
     * going on holiday.
     */
    @Test
    void aLightHabitCanNeverReachTheBadBands() {
        float light = TrapMath.HABIT_CRAVE * MAX - 1f;
        for (long waited = 0; waited <= FULL * 20; waited += FULL / 4) {
            int band = TrapMath.habitBand(
                    TrapMath.habitPressure(light, MAX, waited, PERIOD));
            assertTrue(band <= TrapMath.BAND_ITCH,
                    "meter " + light + " reached band " + band + " after " + waited + " ticks");
        }
    }

    @Test
    void aFullMeterLeftAloneGetsProperlySick() {
        assertEquals(TrapMath.BAND_SICK,
                TrapMath.habitBand(TrapMath.habitPressure(MAX, MAX, FULL, PERIOD)));
    }

    @Test
    void bandsAreOrderedAndBounded() {
        assertTrue(TrapMath.HABIT_ITCH < TrapMath.HABIT_CRAVE);
        assertTrue(TrapMath.HABIT_CRAVE < TrapMath.HABIT_SICK);
        assertTrue(TrapMath.HABIT_SICK <= 1.0f, "an unreachable band is a dead band");
        assertEquals(TrapMath.BAND_CLEAN, TrapMath.habitBand(0f));
        assertEquals(TrapMath.BAND_SICK, TrapMath.habitBand(1f));
    }

    /**
     * A shorter craving period means the same habit bites sooner, which is the
     * only lever making dope feel different from weed at the same meter.
     */
    @Test
    void aShorterPeriodBitesSooner() {
        long tenMinutes = 10 * 60 * 20;
        float weed = TrapMath.habitPressure(MAX, MAX, tenMinutes, 14);
        float dope = TrapMath.habitPressure(MAX, MAX, tenMinutes, 3);
        assertTrue(dope > weed, "dope must ripen first, got " + dope + " vs " + weed);
        assertEquals(1.0f, dope, 0.001f, "three minutes in, dope is already all the way up");
    }

    @Test
    void rubbishInputsDoNotThrow() {
        assertEquals(0f, TrapMath.habitPressure(-5f, MAX, FULL, PERIOD), 0.0001f);
        assertEquals(0f, TrapMath.habitPressure(MAX, MAX, -FULL, PERIOD), 0.0001f);
        assertEquals(0f, TrapMath.habitPressure(MAX, 0f, FULL, PERIOD), 0.0001f);
        assertEquals(0f, TrapMath.habitPressure(MAX, MAX, FULL, 0), 0.0001f);
    }

    /**
     * The long line has to actually be long. Poppies are the slowest thing you
     * can plant, and if that ever stops being true the whole "harder than
     * cocaine" argument goes with it.
     */
    @Test
    void poppyIsTheSlowestCrop() {
        assertTrue(TrapMath.POPPY_GROWTH_ROLLS > TrapMath.COCA_GROWTH_ROLLS);
        assertTrue(TrapMath.POPPY_GROWTH_ROLLS > TrapMath.WEED_GROWTH_ROLLS_WET);
    }
}
