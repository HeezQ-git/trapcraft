package dev.heezq.trapcraft;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bookmaker, and the one claim the whole feature rests on.
 *
 * The board is priced off reputation and home advantage; the result is decided
 * off those plus form, absences, rest, conditions and head to head. That gap
 * is supposed to be worth more than the book's cut to somebody who reads the
 * television, and worth nothing to somebody who does not.
 *
 * Neither half of that is obvious from the constants and both are easy to
 * break by retuning one of them. So both are simulated here: a punter who
 * always backs the favourite must lose at roughly the margin, and a punter who
 * only backs a real edge must win. Get {@link TrapMath#BOOK_MARGIN} or
 * {@link TrapMath#BOOK_SCALE} wrong and exactly one of these two tests fails,
 * which tells you which way you went.
 */
class BookmakerTest {

    // --- the prices themselves --------------------------------------------------

    @Test
    void everyMarketRunsAtTheStatedOverround() {
        // A two-way book: the two prices' implied chances must sum to a bit
        // over one, and that bit IS the margin. This is the number that makes
        // betting blind a losing proposition and nothing else does.
        for (int gap = 0; gap <= 30; gap += 5) {
            float mine = TrapMath.duelChance(80 + gap, 80);
            float book = 100.0f / TrapMath.price(mine) + 100.0f / TrapMath.price(1 - mine);
            assertTrue(book > 1.0f, "a book that does not overround is a gift, gap " + gap);
            assertTrue(book < 1.0f + 3 * TrapMath.BOOK_MARGIN,
                    "overround ran away at gap " + gap + ": " + book);
        }
    }

    @Test
    void shorterPricesMeanBetterChances() {
        int previous = Integer.MAX_VALUE;
        for (int gap = 30; gap >= -30; gap -= 5) {
            int price = TrapMath.price(TrapMath.duelChance(80 + gap, 80));
            assertTrue(price >= previous || previous == Integer.MAX_VALUE,
                    "price went the wrong way at gap " + gap);
            previous = price;
        }
    }

    @Test
    void aThreeWayBookAddsUpAndTheDrawBehaves() {
        float[] level = TrapMath.matchChances(80, 80);
        assertEquals(1.0f, level[0] + level[1] + level[2], 0.0005f);
        assertEquals(level[0], level[1], 0.0005f, "a level match is not a level market");
        // The draw is likeliest when nobody is favoured, which is the one
        // property that separates modelling it from bolting on a third team.
        assertTrue(level[2] > TrapMath.matchChances(80, 50)[2]);
    }

    @Test
    void theFieldAndThePlacesAgreeWithEachOther() {
        float[] ratings = {92, 88, 86, 84, 80, 78, 74, 70};
        float[] win = TrapMath.fieldChances(ratings);
        float total = 0;
        for (float chance : win) {
            total += chance;
        }
        assertEquals(1.0f, total, 0.0005f);

        float[] placed = TrapMath.placeChances(win, 3);
        float places = 0;
        for (int i = 0; i < placed.length; i++) {
            places += placed[i];
            assertTrue(placed[i] >= win[i], "placing must be easier than winning, runner " + i);
            assertTrue(placed[i] <= 1.0f);
        }
        // Three places are handed out, so the chances of taking one sum to three.
        assertEquals(3.0f, places, 0.005f);
    }

    @Test
    void twoOfThemPriceTheSameWhicheverWayTheyAreDrawn() {
        // A race between two is a match between two. If these disagree the
        // board contradicts itself across sports and somebody arbitrages it.
        float[] field = TrapMath.fieldChances(new float[]{88, 76});
        assertEquals(TrapMath.duelChance(88, 76), field[0], 0.0005f);
    }

    @Test
    void aCouponMultipliesAndTheCeilingHolds() {
        assertEquals(400, TrapMath.slipOdds(new int[]{200, 200}));
        assertEquals(100, TrapMath.slipOdds(new int[]{}));
        int monster = TrapMath.slipOdds(new int[]{
                TrapMath.BOOK_MAX_ODDS, TrapMath.BOOK_MAX_ODDS,
                TrapMath.BOOK_MAX_ODDS, TrapMath.BOOK_MAX_ODDS});
        assertTrue(monster > 0, "four long ones overflowed");
        assertEquals(TrapMath.BOOK_MAX_PAYOUT,
                TrapMath.slipReturn(TrapMath.BOOK_STAKES[TrapMath.BOOK_STAKES.length - 1],
                        monster),
                "the payout ceiling is the only thing between a four-fold and the economy");
    }

    @Test
    void formIsWorthWhatItSaysItIs() {
        assertEquals(5 * TrapMath.BOOK_FORM, TrapMath.formPoints("WWWWW"));
        assertEquals(-5 * TrapMath.BOOK_FORM, TrapMath.formPoints("PPPPP"));
        assertEquals(0, TrapMath.formPoints("RRRRR"));
        assertEquals(0, TrapMath.formPoints(""));
        // Out-of-range rest must clamp rather than throw: it arrives off a save
        // file, and a save file is whatever the last version wrote.
        assertEquals(TrapMath.BOOK_REST[0], TrapMath.restPoints(-3));
        assertEquals(TrapMath.BOOK_REST[TrapMath.BOOK_REST.length - 1],
                TrapMath.restPoints(99));
        assertEquals(TrapMath.BOOK_H2H_CAP, TrapMath.headToHeadPoints(9, 0));
    }

    // --- the claim ---------------------------------------------------------------

    /**
     * One synthetic fixture: two reputations and everything the price cannot
     * see, drawn the way the real board draws them.
     */
    private record Fixture(int repA, int repB, String formA, String formB, int outA, int outB,
                           int restA, int restB, int suitsA, int suitsB, boolean homeA) {

        float pricedA() {
            return TrapMath.pricedRating(repA, homeA);
        }

        float pricedB() {
            return TrapMath.pricedRating(repB, false);
        }

        float trueA() {
            return TrapMath.trueRating(repA, formA, outA, restA, suitsA, 0, homeA);
        }

        float trueB() {
            return TrapMath.trueRating(repB, formB, outB, restB, suitsB, 0, false);
        }
    }

    private static final String[] FORMS = {"", "WWWWW", "WWRWP", "RPRPR", "PPRPP", "PPPPP",
            "WWPWR", "WPWPW"};

    private static Fixture roll(Random rng) {
        // Reputations from inside one competition, which is the only place the
        // board ever draws a pair from. See TrapMath.BOOK_SCALE.
        int repA = 74 + rng.nextInt(19);
        int repB = Math.max(74, Math.min(92, repA - 12 + rng.nextInt(25)));
        return new Fixture(repA, repB,
                FORMS[rng.nextInt(FORMS.length)], FORMS[rng.nextInt(FORMS.length)],
                rng.nextInt(4), rng.nextInt(4),
                rng.nextInt(5), rng.nextInt(5),
                -8 + rng.nextInt(17), -8 + rng.nextInt(17),
                rng.nextBoolean());
    }

    /** Stake one on a selection at this price and return the profit. */
    private static float settle(boolean landed, int price) {
        return landed ? price / 100.0f - 1.0f : -1.0f;
    }

    @Test
    void backingTheFavouriteBlindLosesAtRoughlyTheMargin() {
        Random rng = new Random(20260829L);
        float profit = 0;
        int bets = 200_000;
        for (int i = 0; i < bets; i++) {
            Fixture fixture = roll(rng);
            int priceA = TrapMath.price(TrapMath.duelChance(fixture.pricedA(), fixture.pricedB()));
            int priceB = TrapMath.price(TrapMath.duelChance(fixture.pricedB(), fixture.pricedA()));
            boolean backA = priceA <= priceB;
            boolean wonA = rng.nextFloat() < TrapMath.duelChance(fixture.trueA(), fixture.trueB());
            profit += settle(backA == wonA, backA ? priceA : priceB);
        }
        float edge = profit / bets;
        // Everything the punter ignored is symmetric, so it cancels out over
        // this many bets and what is left is the cut. Bounded on BOTH sides:
        // a blind punter who loses far more than the margin means the board is
        // mispricing on top of charging for it.
        assertTrue(edge < -0.02f, "betting blind should lose; it returned " + edge);
        assertTrue(edge > -2.5f * TrapMath.BOOK_MARGIN,
                "betting blind lost far more than the margin (" + edge
                        + ") -- the board is mispriced, not just charging");
    }

    @Test
    void readingTheScreenBeatsTheMargin() {
        Random rng = new Random(20260829L);
        float profit = 0;
        int bets = 0;
        int looked = 0;
        while (looked < 400_000) {
            looked++;
            Fixture fixture = roll(rng);
            float pricedGap = fixture.pricedA() - fixture.pricedB();
            float trueGap = fixture.trueA() - fixture.trueB();
            // What a player standing at the screen actually does: everything on
            // the two panels points one way, and the price does not know. No
            // number here is shown in game -- the panels are words -- but this
            // is the arithmetic somebody doing it by eye is approximating.
            float hidden = trueGap - pricedGap;
            if (Math.abs(hidden) < EDGE) {
                continue;
            }
            bets++;
            boolean backA = hidden > 0;
            int price = TrapMath.price(backA
                    ? TrapMath.duelChance(fixture.pricedA(), fixture.pricedB())
                    : TrapMath.duelChance(fixture.pricedB(), fixture.pricedA()));
            boolean wonA = rng.nextFloat() < TrapMath.duelChance(fixture.trueA(), fixture.trueB());
            profit += settle(backA == wonA, price);
        }
        assertTrue(bets > 20_000, "the filter left almost nothing to bet on: " + bets);
        float edge = profit / bets;
        assertTrue(edge > 0.02f,
                "reading every factor on the screen still lost (" + edge + ") -- the "
                        + "margin has swallowed the information and the feature is a slot "
                        + "machine with team names on it");
        // The other side of it, and the one that actually got hit: at twice
        // these hidden factors a perfect reader returned thirty percent on
        // turnover, which is not an edge, it is a printing press wired into
        // TrapMarket. See the note on BOOK_FORM.
        assertTrue(edge < 0.30f,
                "reading the screen returned " + edge + " on turnover -- the hidden "
                        + "factors have outgrown reputation and the board is free money");
    }

    /**
     * How far the hidden factors must point one way before the disciplined
     * punter is interested. Roughly "three of the five agree", which is what
     * the studio page tells them to look for.
     */
    private static final float EDGE = 6.0f;
}
