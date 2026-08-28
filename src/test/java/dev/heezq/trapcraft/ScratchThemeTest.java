package dev.heezq.trapcraft;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every costume has to fit all six ranks.
 *
 * A theme one item short does not fail when it is picked -- it fails the first
 * time a card happens to carry the rank nobody listed, which for the top face
 * is roughly the rarest moment on the floor and the worst one to throw in.
 * Counting the literals is enough to catch that, and it costs no ServerWorld:
 * same trade as {@link BarIsNotAMachineTest}.
 */
class ScratchThemeTest {

    @Test
    void everyThemeDressesEveryRank() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/dev/heezq/trapcraft/ScratchScreenHandler.java"));
        int table = source.indexOf("THEMES = {");
        assertTrue(table > 0, "the theme table is gone -- this test is stale");
        int end = source.indexOf("\n    };", table);
        assertTrue(end > table, "the theme table has no end");

        String[] themes = source.substring(table, end).split("new Theme\\(");
        assertTrue(themes.length > 3, "expected several themes, found " + (themes.length - 1));
        for (int index = 1; index < themes.length; index++) {
            // One foil plus six faces, and one name plus six words for them.
            assertEquals(7, count(themes[index], "Items."), "theme " + index + " items");
            assertEquals(14, count(themes[index], "\""), "theme " + index + " quotes");
        }
    }

    private static int count(String text, String needle) {
        int found = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
            found++;
        }
        return found;
    }
}
