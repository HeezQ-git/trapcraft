package dev.heezq.trapcraft;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which book the revenue office reads, and whether the old one survives to be
 * read at all.
 *
 * Both halves guard the same defect from opposite sides. {@link TrapLedger}
 * and {@link TrapLaw} each notice the day turning on their own timer -- one a
 * second, the other ten -- and the ledger's handler is registered first, so
 * for as long as the office read {@code today()} it read a book that had
 * already been emptied a moment earlier. Nothing threw and nothing logged; the
 * office simply never billed anybody, and the only evidence was a live server
 * where a dealer banked 71640e undeclared in a day and owed 4335e in total.
 *
 * So: the arithmetic half checks the handover really hands the rows over, and
 * the source half checks the office asks for them -- the second being the sort
 * of cross-file JOIN {@link PoliceTest} tests the same way and for the same
 * reason, because no amount of arithmetic catches reading the wrong map.
 */
class LedgerDayTest {

    private static Map<String, Map<TrapLedger.Source, Integer>> book(String who, int weed) {
        Map<String, Map<TrapLedger.Source, Integer>> out = new LinkedHashMap<>();
        Map<TrapLedger.Source, Integer> row = new EnumMap<>(TrapLedger.Source.class);
        row.put(TrapLedger.Source.WEED, weed);
        out.put(who, row);
        return out;
    }

    @Test
    void theClosedDayIsWhatTheKeeperEndsUpHolding() {
        var today = book("KARTGERL", 71640);
        var yesterday = new LinkedHashMap<String, Map<TrapLedger.Source, Integer>>();

        TrapLedger.rollOver(today, yesterday);

        assertTrue(today.isEmpty(), "the new day starts empty");
        assertEquals(71640, TrapLedger.undeclaredOf(yesterday.get("KARTGERL")),
                "and the office can still see what the old one held");
    }

    @Test
    void theNewDaysEarningsDoNotLeakBackIntoTheOldOne() {
        var today = book("KARTGERL", 71640);
        var yesterday = new LinkedHashMap<String, Map<TrapLedger.Source, Integer>>();
        TrapLedger.rollOver(today, yesterday);

        // The handover is a shallow one on purpose. If tally reused the row
        // rather than building a fresh one, the morning's first sale would
        // land on the bill for the night before.
        TrapLedger.tally(today, "KARTGERL", TrapLedger.Source.WEED, 12);

        assertEquals(71640, TrapLedger.undeclaredOf(yesterday.get("KARTGERL")));
        assertEquals(12, TrapLedger.undeclaredOf(today.get("KARTGERL")));
    }

    @Test
    void aKeeperIsNotAllowedToAccumulateDays() {
        var yesterday = new LinkedHashMap<String, Map<TrapLedger.Source, Integer>>();
        TrapLedger.rollOver(book("KARTGERL", 400), yesterday);
        TrapLedger.rollOver(book("iwasleon", 900), yesterday);

        assertFalse(yesterday.containsKey("KARTGERL"),
                "a bill is for one day, not for every day anybody ever dealt");
        assertEquals(1, yesterday.size());
    }

    @Test
    void theOfficeAssessesTheDayTheLedgerHasFinishedWith() throws Exception {
        String law = Files.readString(
                Path.of("src/main/java/dev/heezq/trapcraft/TrapLaw.java"));
        int office = law.indexOf("private static void office(");
        assertTrue(office > 0, "office() moved");
        String body = law.substring(office, law.indexOf("private static void nag(", office));

        assertTrue(body.contains("TrapLedger.yesterday("),
                "the office must read the closed book");
        assertFalse(body.contains("TrapLedger.today("),
                "reading the open one is the bug this test exists for");
    }
}
