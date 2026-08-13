package dev.heezq.trapcraft;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * The outline has to turn the same way the model does.
 *
 * Every directional block gets its model spun by Polymer and its hitbox left
 * alone, which is fine while the model is symmetric and wrong the moment it
 * is not -- the bong's downstem pokes out one side. A rotation with the sign
 * backwards throws nothing and logs nothing; the only symptom is a block you
 * can see on your right and have to click on your left.
 *
 * The fixed point is vanilla's own y-rotation: clockwise from above, so a
 * north face ends up east.
 */
class TurnBoxTest {

    /** The bong's downstem: sticks out east on the north-facing model. */
    private static final double[] STEM = {11, 5, 6.5, 15, 9.5, 9.5};

    private static double[] turn(int spin) {
        return TrapMath.turn(spin, STEM[0], STEM[1], STEM[2], STEM[3], STEM[4], STEM[5]);
    }

    @Test
    void northIsTheModelAsBuilt() {
        assertArrayEquals(STEM, turn(0));
    }

    @Test
    void aQuarterTurnSendsTheEastStemSouth() {
        // Clockwise from above: what pointed east now points south, so the box
        // that was hard against x=15 is hard against z=15.
        assertArrayEquals(new double[] {6.5, 5, 11, 9.5, 9.5, 15}, turn(90));
    }

    @Test
    void aHalfTurnSendsItWest() {
        assertArrayEquals(new double[] {1, 5, 6.5, 5, 9.5, 9.5}, turn(180));
    }

    @Test
    void threeQuartersSendsItNorth() {
        assertArrayEquals(new double[] {6.5, 5, 1, 9.5, 9.5, 5}, turn(270));
    }

    @Test
    void fourQuarterTurnsComeBackToWhereItStarted() {
        double[] box = STEM.clone();
        for (int quarter = 0; quarter < 4; quarter++) {
            box = TrapMath.turn(90, box[0], box[1], box[2], box[3], box[4], box[5]);
        }
        assertArrayEquals(STEM, box);
    }

    @Test
    void heightIsNeverTouched() {
        for (int spin : new int[] {0, 90, 180, 270}) {
            double[] box = turn(spin);
            assertArrayEquals(new double[] {5, 9.5}, new double[] {box[1], box[4]},
                    "a y-rotation must leave y alone, whatever it does to x and z");
        }
    }
}
