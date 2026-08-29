package dev.heezq.trapcraft;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The named-blend table, held to the promise the mixing station makes.
 *
 * Read out of the source rather than off the class, the way {@link PoliceTest}
 * does: {@link Blend} touches {@code StatusEffects}, and loading that outside a
 * running game means bootstrapping the whole registry to check a lookup table.
 *
 * The promise is one sentence -- <em>every mix of strains that are all
 * different has a name</em> -- and it is the reason anybody walks to the
 * station with buds they haven't planned around. Six names out of 203 possible
 * mixes is what made mixing a thing people tried once and went back to selling
 * plain bud; a table that quietly loses a combination puts one corner of the
 * game back the way it was, and nothing in game says which corner.
 *
 * The other half is the book. The catalogue page is generated from this same
 * table, and a written book does not wrap or scroll -- it draws what fits and
 * silently discards the rest. So the two numbers that decide whether a name is
 * readable in game (how wide it is, how many go on a page) are asserted here
 * rather than eyeballed, because the failure mode is a page that looks fine in
 * the source and stops mid-list on paper.
 */
class BlendTest {

    private static final Path SRC = Path.of("src/main/java/dev/heezq/trapcraft");

    /** Written book geometry: what one page can actually draw. */
    private static final int BOOK_LINES = 14;
    private static final int BOOK_COLS = 19;

    private record Recipe(List<String> parts, String name, float potency) {
    }

    private static String source(String file) throws Exception {
        return Files.readString(SRC.resolve(file));
    }

    /** The table as written, not as the compiler sees it. */
    private static List<Recipe> table() throws Exception {
        Matcher match = Pattern.compile(
                "new Recipe\\(List\\.of\\(([^)]*)\\),\\s*\\n\\s*\"(\\w+)\", 0x[0-9A-Fa-f]{6}, "
                        + "([\\d.]+)F").matcher(source("Blend.java"));
        List<Recipe> found = new ArrayList<>();
        while (match.find()) {
            List<String> parts = new ArrayList<>();
            for (String raw : match.group(1).split(",")) {
                parts.add(raw.trim().replace("Strain.", ""));
            }
            found.add(new Recipe(parts, match.group(2), Float.parseFloat(match.group(3))));
        }
        assertTrue(found.size() > 40, "the recipe table did not parse -- its shape moved, and "
                + "gen_wiki.py reads it with the same regex, so the website is wrong too");
        return found;
    }

    /** Strain display names, in declaration order. */
    private static List<String> strains() throws Exception {
        Matcher match = Pattern.compile("^\\s{4}(\\w+)\\(\"\\w+\", \"(\\w+)\",", Pattern.MULTILINE)
                .matcher(source("Strain.java"));
        List<String> names = new ArrayList<>();
        while (match.find()) {
            names.add(match.group(1));
        }
        assertEquals(6, names.size(), "six phenotypes is what every count in this test assumes");
        return names;
    }

    /** Every way to pick {@code size} different strains, as sorted name lists. */
    private static Set<List<String>> everyDistinctMix(List<String> strains, int size) {
        Set<List<String>> out = new HashSet<>();
        int total = 1 << strains.size();
        for (int mask = 0; mask < total; mask++) {
            if (Integer.bitCount(mask) != size) {
                continue;
            }
            List<String> pick = new ArrayList<>();
            for (int bit = 0; bit < strains.size(); bit++) {
                if ((mask & (1 << bit)) != 0) {
                    pick.add(strains.get(bit));
                }
            }
            out.add(List.copyOf(new TreeSet<>(pick)));
        }
        return out;
    }

    private static List<String> sorted(List<String> parts) {
        List<String> copy = new ArrayList<>(parts);
        copy.sort(null);
        return List.copyOf(copy);
    }

    /**
     * The whole feature in one assertion.
     *
     * Fifteen pairs, twenty triples, fifteen quads. Miss one and a player who
     * grew the two strains it wants gets a nameless jar for their trouble --
     * which is the exact experience this table exists to remove.
     */
    @Test
    void everyMixOfDifferentStrainsHasAName() throws Exception {
        List<String> strains = strains();
        Set<List<String>> named = new HashSet<>();
        for (Recipe recipe : table()) {
            named.add(sorted(recipe.parts()));
        }

        for (int size = 2; size <= 4; size++) {
            Set<List<String>> want = everyDistinctMix(strains, size);
            Set<List<String>> missing = new HashSet<>(want);
            missing.removeAll(named);
            assertTrue(missing.isEmpty(),
                    "these " + size + "-strain mixes have no name: " + missing);
        }
        assertEquals(50, everyDistinctMix(strains, 2).size() + everyDistinctMix(strains, 3).size()
                + everyDistinctMix(strains, 4).size(), "15 + 20 + 15 is the shape of the rule");
    }

    /**
     * A second recipe for the same buds is a recipe that never fires.
     *
     * Blend.BY_PARTS is built with toUnmodifiableMap, which throws on a
     * duplicate key -- but that throw lands at class-load on the server, i.e.
     * at boot, in front of everyone. Catching it here costs a test run.
     */
    @Test
    void noTwoRecipesWantTheSameBuds() throws Exception {
        Map<List<String>, String> seen = new HashMap<>();
        Set<String> names = new HashSet<>();
        for (Recipe recipe : table()) {
            String clash = seen.put(sorted(recipe.parts()), recipe.name());
            assertTrue(clash == null,
                    recipe.name() + " and " + clash + " are the same mix, so one of them can "
                            + "never be made and the server would refuse to boot");
            assertTrue(names.add(recipe.name()),
                    recipe.name() + " is used twice, and a customer asks for a mix BY NAME");

            int size = recipe.parts().size();
            assertTrue(size >= Blend.MIN_PARTS && size <= Blend.MAX_PARTS,
                    recipe.name() + " needs " + size + " buds, which the station cannot hold");
        }
    }

    /**
     * Wider mixes pay better, or there is no reason to spend the extra buds.
     *
     * Each part is a whole bud off the drying rack, so a four-way that potencies
     * below some two-way is a recipe asking the player to burn two buds for a
     * worse high. The bands overlap deliberately inside a tier -- that is
     * flavour -- but they must not overlap across one.
     */
    @Test
    void moreBudsIsAlwaysAStrongerMix() throws Exception {
        float widestPair = 0;
        float narrowestQuad = Float.MAX_VALUE;
        float widestTriple = 0;
        float narrowestTriple = Float.MAX_VALUE;
        for (Recipe recipe : table()) {
            // Only over the mixes that follow the rule. The three doubled-up
            // secrets are priced as secrets and sit deliberately off the ladder.
            if (sorted(recipe.parts()).size() != recipe.parts().size()) {
                continue;
            }
            switch (recipe.parts().size()) {
                case 2 -> widestPair = Math.max(widestPair, recipe.potency());
                case 3 -> {
                    widestTriple = Math.max(widestTriple, recipe.potency());
                    narrowestTriple = Math.min(narrowestTriple, recipe.potency());
                }
                default -> narrowestQuad = Math.min(narrowestQuad, recipe.potency());
            }
        }
        assertTrue(widestPair < narrowestTriple,
                "the best pair (" + widestPair + ") beats the worst triple (" + narrowestTriple
                        + "), so the third bud is wasted");
        assertTrue(widestTriple < narrowestQuad,
                "the best triple (" + widestTriple + ") beats the worst quad (" + narrowestQuad
                        + "), so the fourth bud is wasted");
    }

    /**
     * The catalogue has to fit on the paper it is printed on.
     *
     * check_pages.py measures pages built from string literals and cannot see
     * inside the loop that draws this one, so it reports the catalogue as
     * passing whatever length it really is. This is the check that isn't
     * fooled: it reads the same two numbers the loop uses and holds them to the
     * page.
     */
    @Test
    void theCatalogueFitsOnThePage() throws Exception {
        List<String> strains = strains();
        Set<Character> initials = new HashSet<>();
        for (String strain : strains) {
            // The book prints one letter per strain and nothing disambiguates
            // a collision -- two strains starting with the same letter would
            // make half the catalogue unreadable rather than merely wrong.
            assertTrue(initials.add(Character.toUpperCase(strain.charAt(0))),
                    "two strains share an initial, so the catalogue is ambiguous");
        }

        String guide = source("TrapGuide.java");
        Matcher perPage = Pattern.compile("final int perPage = (\\d+);").matcher(guide);
        assertTrue(perPage.find(), "namedBlendPages must keep its page budget in one named place");
        int rows = Integer.parseInt(perPage.group(1));
        // Two lines go to "6gN. SPIS" and the blank under it.
        assertTrue(rows + 2 <= BOOK_LINES,
                rows + " rows plus the heading is " + (rows + 2) + " lines, and " + BOOK_LINES
                        + " fit -- the tail of every catalogue page would vanish");

        for (Recipe recipe : table()) {
            int width = recipe.name().length() + 1 + recipe.parts().size();
            assertTrue(width <= BOOK_COLS,
                    "\"" + recipe.name() + "\" plus its " + recipe.parts().size()
                            + " initials is " + width + " characters, and a book line holds "
                            + BOOK_COLS + " -- it would wrap and push a name off the page");
        }
    }
}
