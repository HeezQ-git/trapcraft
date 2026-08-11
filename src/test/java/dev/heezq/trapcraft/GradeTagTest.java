package dev.heezq.trapcraft;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A grade written to disk has to come back as the same grade.
 *
 * The bug this exists to keep dead: every stack was saved as its QUALITY
 * index, and quality defaults to MIDS when the component is absent. Powder
 * keeps its grade in purity and a blend keeps its inside the Blend, so a Pure
 * powder and a Fire blend were both saved as "1" and reloaded as Mids -- every
 * restart, silently, on stock the player had already paid for.
 */
class GradeTagTest {

    private static void roundTrips(GradeTag grade) {
        assertEquals(grade, GradeTag.parse(grade.toString()),
                "grade did not survive the trip to disk and back: " + grade);
    }

    @Test
    void everyGradeLineSurvivesTheRoundTrip() {
        roundTrips(new GradeTag(GradeTag.QUALITY, 3, List.of()));   // Fire joint
        roundTrips(new GradeTag(GradeTag.PURITY, 3, List.of()));    // Pure powder
        roundTrips(new GradeTag(GradeTag.BLEND, 3, List.of("kush", "haze")));
        roundTrips(GradeTag.NONE);                                  // coca leaves
    }

    @Test
    void purityIsNotReadBackAsQuality() {
        // The whole bug in one line: both are index 3, and before the tag the
        // file had no way to say that one of them meant Pure.
        assertEquals(GradeTag.PURITY, GradeTag.parse("p3").kind());
        assertEquals(GradeTag.QUALITY, GradeTag.parse("q3").kind());
    }

    @Test
    void savesWrittenBeforeTheTagStillLoad() {
        // Untagged files only ever held buds and joints correctly, and those
        // really were quality indices.
        assertEquals(new GradeTag(GradeTag.QUALITY, 3, List.of()), GradeTag.parse("3"));
    }

    @Test
    void aMangledFieldCostsOneStackAndNotTheWholeServer() {
        // Both save files are read in a single try block, so a throw here
        // would drop every dealer in the world.
        assertEquals(0, GradeTag.parse("qwhat").index());
        assertEquals(GradeTag.NONE, GradeTag.parse("b3"));
        assertEquals(GradeTag.NONE, GradeTag.parse(""));
    }
}
