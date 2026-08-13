package dev.heezq.trapcraft;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Nobody gets seated at the bar.
 *
 * The bar is wired exactly like a machine, so every path that picks a wire to
 * put a body on has to ask isMachine -- and maybeArrive did not. Punters bet
 * against a shelf at returnOf's 0.97 fallback, the counters wore out like
 * cabinets, and then nothing could put them right: mend is gated on isMachine
 * as well, so the floor carried fittings that were permanently OUT OF ORDER at
 * coordinates their owner was told to take a hammer to. Measured on the live
 * server 2026-08-13: all four bars between 86 and 100 wear, the worst cabinets
 * on a floor of forty-seven, with eleven arrivals logged straight to them.
 *
 * Reading the source, because the alternative is a ServerWorld -- same trade
 * as {@link FloorNoticeTest}.
 */
class BarIsNotAMachineTest {

    private static String floor() throws Exception {
        return Files.readString(
                Path.of("src/main/java/dev/heezq/trapcraft/TrapFloor.java"));
    }

    /** A method's body, signature to its own closing brace. */
    private static String bodyOf(String source, String signature) {
        int from = source.indexOf(signature);
        assertTrue(from >= 0, signature + " is gone -- this test is stale");
        int to = source.indexOf("\n    }", from);
        assertTrue(to > from, signature + " has no end");
        return source.substring(from, to);
    }

    @Test
    void everyPathThatOffersSomewhereToPlayChecksIsMachine() throws Exception {
        String source = floor();
        for (String picker : new String[] {
                "private static void maybeArrive(MinecraftServer server, boolean forced)",
                "public static String freeWire(MinecraftServer server, int purse)"}) {
            assertTrue(bodyOf(source, picker).contains("TrapHouse.isMachine("),
                    picker + " must not offer a bar as somewhere to play");
        }
    }

    @Test
    void theBeatClearsWearOffWhateverIsNotAMachine() throws Exception {
        assertTrue(bodyOf(floor(), "private static void beat(MinecraftServer server)")
                        .contains("TrapHouse.unwear("),
                "a world already carrying wear on a bar has to heal itself from the "
                        + "beat -- no hammer can reach one, so without this the "
                        + "counters read as broken machines forever");
    }
}
