package dev.heezq.trapcraft;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * What is in the cases, how often, and what it is worth.
 *
 * Nothing here imports Minecraft, for the same reason as {@link TrapMath}:
 * this is the half of the feature that can be wrong in a way nobody notices
 * for a month, so it has to be reachable from a plain JUnit run. The Minecraft
 * half -- resolving these ids to items, the reel, the loot tables -- is in
 * {@link TrapCases}.
 *
 * <h2>The invariant this file exists to hold</h2>
 *
 * A key is bought with emeralds and the contents can be sold back for
 * emeralds, so a case is one arithmetic slip away from being a mint. The
 * guard is {@link #RESALE_CEILING}: whatever a case averages at SHELF price,
 * dumping the lot at the counter has to come back under what the key cost.
 * That is what makes the cases generous and still a sink -- a good open beats
 * the key several times over in GOODS, and nobody can farm emeralds with one.
 * {@code CaseOddsTest} is the check; it fails the build, not the server.
 *
 * <h2>Why these odds and not nicer ones</h2>
 *
 * They are Counter-Strike's, to the basis point, because the feeling being
 * copied is theirs and it is made almost entirely of 0.26%. Rounder numbers
 * (80/16/3/0.7/0.3) would be indistinguishable in play and would quietly stop
 * this being the thing it is imitating.
 */
public final class CaseOdds {

    private CaseOdds() {
    }

    /** Draws in one roll. Weights are in ten-thousandths, so 26 is 0.26%. */
    public static final int DRAWS = 10_000;

    /**
     * The most the counter could ever pay back, as a fraction of shelf price.
     *
     * The real number today is {@code TrapMath.SELL_RATE} (0.45) times the
     * Exchange's bonus (1.12), which is about 0.50. This is deliberately well
     * above that rather than equal to it: the margin is what stops a future
     * buff to the Exchange turning every case into a printer without anybody
     * touching this file. Depending on the exact product of two constants in
     * two other classes is how that bug gets written.
     */
    public static final float RESALE_CEILING = 0.60f;

    /** Rarity ladder, worst to best. Weights sum to {@link #DRAWS}. */
    public enum Grade {
        MIL_SPEC("Klasa wojskowa", 7992),
        RESTRICTED("Ograniczony", 1598),
        CLASSIFIED("Tajny", 320),
        COVERT("Utajniony", 64),
        /** The knife slot. One open in 385. */
        EXOTIC("Wyjątkowo rzadki", 26);

        private final String title;
        private final int weight;

        Grade(String title, int weight) {
            this.title = title;
            this.weight = weight;
        }

        public String title() {
            return title;
        }

        public int weight() {
            return weight;
        }

        /** Odds as the game states them, e.g. "0,26%". */
        public String chance() {
            return String.format("%.2f%%", weight * 100.0 / DRAWS).replace('.', ',');
        }
    }

    /**
     * The four cases, and what their key costs on the shelf.
     *
     * Prices are set against what this server earns: 450e is a good evening,
     * 22,000e is most of a wand. That spread is the point -- the street case
     * is something you open the day you find it, and the phantom is a decision.
     *
     * No display names here on purpose. They live in the language file that
     * gen_assets.py writes, and everything player-facing goes through
     * {@link #caseKey()} -- one Polish name per item, in the one place
     * Minecraft already looks for it. A copy in this file would be a second
     * name for the same box, and the two would disagree within a month.
     */
    public enum Tier {
        STREET("street", 450),
        DOCKS("docks", 1_600),
        CARTEL("cartel", 6_000),
        PHANTOM("phantom", 22_000);

        private final String id;
        private final int keyPrice;

        Tier(String id, int keyPrice) {
            this.id = id;
            this.keyPrice = keyPrice;
        }

        public String id() {
            return id;
        }

        public String caseId() {
            return id + "_case";
        }

        public String keyId() {
            return id + "_key";
        }

        /** Translation key for the case, for {@code Text.translatable}. */
        public String caseKey() {
            return "item.trapcraft." + caseId();
        }

        /** Translation key for the key. */
        public String keyKey() {
            return "item.trapcraft." + keyId();
        }

        public int keyPrice() {
            return keyPrice;
        }

        /** The tier above, or null at the top. Used by the trade-up. */
        public Tier above() {
            return ordinal() + 1 < values().length ? values()[ordinal() + 1] : null;
        }
    }

    /**
     * How many of one tier's keys buy one of the next.
     *
     * Counter-Strike's trade-up contract, and the only recipe in the feature.
     * FOUR because {@code 4 * price(n) >= price(n+1)} at every step -- see
     * CaseOddsTest -- so the craft can never be cheaper than just buying the
     * key. It exists for the keys you FOUND, which the shop will not take.
     */
    public static final int TRADE_UP = 4;

    /** One item and how many of it. */
    public record Drop(String id, int count) {
    }

    /**
     * One line of a case.
     *
     * {@code worth} is the SHELF price of the drops in emeralds, carried on the
     * line rather than looked up, because the two uses need it in places that
     * cannot reach the shop: the odds test runs with no game, and the reel
     * prints it on the item so a player can see what a grade is worth without
     * walking to a market. It being visible in-game is also what stops it
     * rotting -- a wrong number here is wrong on somebody's screen.
     */
    public record Reward(String label, int worth, List<Drop> drops) {
    }

    private static Drop d(String id, int count) {
        return new Drop(id, count);
    }

    private static Reward r(String label, int worth, Drop... drops) {
        return new Reward(label, worth, List.of(drops));
    }

    private static final Map<Tier, Map<Grade, List<Reward>>> POOLS = new EnumMap<>(Tier.class);

    private static void pool(Tier tier, Grade grade, Reward... rewards) {
        POOLS.computeIfAbsent(tier, ignored -> new EnumMap<>(Grade.class))
                .put(grade, List.of(rewards));
    }

    static {
        street();
        docks();
        cartel();
        phantom();
    }

    /**
     * 450e key. The one you open the evening you find it.
     *
     * Everything in the blue band is a stack of something you were going to
     * spend a weekend gathering, which is the right feeling for the common
     * outcome: not a prize, but not nothing either.
     */
    private static void street() {
        Tier t = Tier.STREET;
        pool(t, Grade.MIL_SPEC,
                r("9 diamentów", 378, d("minecraft:diamond", 9)),
                r("8 odłamków echa", 360, d("minecraft:echo_shard", 8)),
                r("3 muszle shulkera", 390, d("minecraft:shulker_shell", 3)),
                r("48 butelek doświadczenia", 360, d("minecraft:experience_bottle", 48)),
                r("6 prętów bryzy", 360, d("minecraft:breeze_rod", 6)));
        pool(t, Grade.RESTRICTED,
                r("3 pradawne szczątki", 900, d("minecraft:ancient_debris", 3)),
                r("Złote jabłko z zaklęciem", 850,
                        d("minecraft:enchanted_golden_apple", 1)),
                r("Totem i 5 diamentów", 910,
                        d("minecraft:totem_of_undying", 1), d("minecraft:diamond", 5)),
                r("Konduit", 900, d("minecraft:conduit", 1)),
                r("Szablon netherytu", 900,
                        d("minecraft:netherite_upgrade_smithing_template", 1)));
        pool(t, Grade.CLASSIFIED,
                r("Elytra", 1_600, d("minecraft:elytra", 1)),
                r("Latarnia morska", 1_500, d("minecraft:beacon", 1)),
                r("Sztabka netherytu i blok diamentów", 1_530,
                        d("minecraft:netherite_ingot", 1), d("minecraft:diamond_block", 1)),
                r("Gwiazda netheru i 5 diamentów", 1_610,
                        d("minecraft:nether_star", 1), d("minecraft:diamond", 5)));
        pool(t, Grade.COVERT,
                r("Jajo smoka", 4_000, d("minecraft:dragon_egg", 1)),
                r("2 gwiazdy netheru i latarnia", 4_300,
                        d("minecraft:nether_star", 2), d("minecraft:beacon", 1)),
                r("Napierśnik, hełm i elytra", 4_400,
                        d("minecraft:netherite_chestplate", 1),
                        d("minecraft:netherite_helmet", 1),
                        d("minecraft:elytra", 1)));
        pool(t, Grade.EXOTIC,
                r("Różdżka Pędu", 25_000, d("trapcraft:boost_wand", 1)));
    }

    /** 1,600e key. Where the blue band starts being something you'd have bought. */
    private static void docks() {
        Tier t = Tier.DOCKS;
        pool(t, Grade.MIL_SPEC,
                r("Elytra", 1_600, d("minecraft:elytra", 1)),
                r("Latarnia morska", 1_500, d("minecraft:beacon", 1)),
                r("Gwiazda netheru", 1_400, d("minecraft:nether_star", 1)),
                r("Sztabka netherytu i blok diamentów", 1_530,
                        d("minecraft:netherite_ingot", 1), d("minecraft:diamond_block", 1)),
                r("32 diamenty", 1_344, d("minecraft:diamond", 32)),
                r("4 pradawne szczątki", 1_200, d("minecraft:ancient_debris", 4)));
        pool(t, Grade.RESTRICTED,
                r("Jajo smoka", 4_000, d("minecraft:dragon_egg", 1)),
                r("3 sztabki netherytu", 3_450, d("minecraft:netherite_ingot", 3)),
                r("Napierśnik i nagolenniki z netherytu", 2_950,
                        d("minecraft:netherite_chestplate", 1),
                        d("minecraft:netherite_leggings", 1)),
                r("2 gwiazdy netheru i latarnia", 4_300,
                        d("minecraft:nether_star", 2), d("minecraft:beacon", 1)));
        pool(t, Grade.CLASSIFIED,
                r("5 sztabek netherytu i elytra", 7_350,
                        d("minecraft:netherite_ingot", 5), d("minecraft:elytra", 1)),
                r("Pełna zbroja z netherytu, totem i elytra", 7_850,
                        d("minecraft:netherite_helmet", 1),
                        d("minecraft:netherite_chestplate", 1),
                        d("minecraft:netherite_leggings", 1),
                        d("minecraft:netherite_boots", 1),
                        d("minecraft:totem_of_undying", 1),
                        d("minecraft:elytra", 1)),
                r("Jajo smoka i 2 latarnie", 7_000,
                        d("minecraft:dragon_egg", 1), d("minecraft:beacon", 2)));
        pool(t, Grade.COVERT,
                r("Blok netherytu i jajo smoka", 14_000,
                        d("minecraft:netherite_block", 1), d("minecraft:dragon_egg", 1)),
                r("12 sztabek netherytu", 13_800, d("minecraft:netherite_ingot", 12)),
                r("Blok netherytu, 2 latarnie i elytra", 14_600,
                        d("minecraft:netherite_block", 1),
                        d("minecraft:beacon", 2),
                        d("minecraft:elytra", 1)));
        pool(t, Grade.EXOTIC,
                r("Różdżka Żniw", 40_000, d("trapcraft:harvest_wand", 1)));
    }

    /**
     * 6,000e key, and the tier where the wand rack starts turning up.
     *
     * A wand is the right prize here for the same reason a knife is: it is the
     * thing on the far end of the shop that the earning game exists to buy,
     * and pulling one out of a box is the only other way to get it.
     */
    private static void cartel() {
        Tier t = Tier.CARTEL;
        pool(t, Grade.MIL_SPEC,
                r("Pełna zbroja z netherytu", 5_550,
                        d("minecraft:netherite_helmet", 1),
                        d("minecraft:netherite_chestplate", 1),
                        d("minecraft:netherite_leggings", 1),
                        d("minecraft:netherite_boots", 1)),
                r("5 sztabek netherytu", 5_750, d("minecraft:netherite_ingot", 5)),
                r("Jajo smoka i latarnia", 5_500,
                        d("minecraft:dragon_egg", 1), d("minecraft:beacon", 1)),
                r("3 gwiazdy netheru i elytra", 5_800,
                        d("minecraft:nether_star", 3), d("minecraft:elytra", 1)),
                r("4 sztabki netherytu i totem", 5_300,
                        d("minecraft:netherite_ingot", 4),
                        d("minecraft:totem_of_undying", 1)));
        pool(t, Grade.RESTRICTED,
                r("Blok netherytu i jajo smoka", 14_000,
                        d("minecraft:netherite_block", 1), d("minecraft:dragon_egg", 1)),
                r("11 sztabek netherytu", 12_650, d("minecraft:netherite_ingot", 11)),
                r("Blok netherytu i 2 latarnie", 13_000,
                        d("minecraft:netherite_block", 1), d("minecraft:beacon", 2)));
        pool(t, Grade.CLASSIFIED,
                r("Różdżka Pędu", 25_000, d("trapcraft:boost_wand", 1)),
                r("2 bloki netherytu, jajo smoka i latarnia", 25_500,
                        d("minecraft:netherite_block", 2),
                        d("minecraft:dragon_egg", 1),
                        d("minecraft:beacon", 1)),
                r("22 sztabki netherytu", 25_300, d("minecraft:netherite_ingot", 22)));
        pool(t, Grade.COVERT,
                r("Różdżka Żył", 55_000, d("trapcraft:prospect_wand", 1)),
                r("5 bloków netherytu i jajo smoka", 54_000,
                        d("minecraft:netherite_block", 5), d("minecraft:dragon_egg", 1)));
        pool(t, Grade.EXOTIC,
                r("Różdżka Burz", 120_000, d("trapcraft:storm_wand", 1)));
    }

    /**
     * 22,000e key. Nearly a boost wand, for one box.
     *
     * The blue band alone is two netherite blocks, which is the only honest
     * way to price a key this size: at these stakes the common outcome has to
     * be something you would have been pleased to buy, or the case is a slot
     * machine and the 79.92% is the losing arm.
     */
    private static void phantom() {
        Tier t = Tier.PHANTOM;
        pool(t, Grade.MIL_SPEC,
                r("2 bloki netherytu", 20_000, d("minecraft:netherite_block", 2)),
                r("Blok netherytu, 2 jaja smoka i latarnia", 19_500,
                        d("minecraft:netherite_block", 1),
                        d("minecraft:dragon_egg", 2),
                        d("minecraft:beacon", 1)),
                r("16 sztabek netherytu, totem i elytra", 20_700,
                        d("minecraft:netherite_ingot", 16),
                        d("minecraft:totem_of_undying", 1),
                        d("minecraft:elytra", 1)),
                r("4 jaja smoka i 2 latarnie", 19_000,
                        d("minecraft:dragon_egg", 4), d("minecraft:beacon", 2)));
        pool(t, Grade.RESTRICTED,
                r("Różdżka Żniw", 40_000, d("trapcraft:harvest_wand", 1)),
                r("4 bloki netherytu i 2 jaja smoka", 48_000,
                        d("minecraft:netherite_block", 4), d("minecraft:dragon_egg", 2)),
                r("5 bloków netherytu", 50_000, d("minecraft:netherite_block", 5)));
        pool(t, Grade.CLASSIFIED,
                r("Różdżka Murarzy", 80_000, d("trapcraft:builder_wand", 1)),
                r("9 bloków netherytu", 90_000, d("minecraft:netherite_block", 9)),
                r("8 bloków netherytu, jajo smoka i latarnia", 85_500,
                        d("minecraft:netherite_block", 8),
                        d("minecraft:dragon_egg", 1),
                        d("minecraft:beacon", 1)));
        pool(t, Grade.COVERT,
                r("Różdżka Burz i 8 bloków netherytu", 200_000,
                        d("trapcraft:storm_wand", 1), d("minecraft:netherite_block", 8)),
                r("20 bloków netherytu", 200_000, d("minecraft:netherite_block", 20)));
        pool(t, Grade.EXOTIC,
                r("Cały regał różdżek", 320_000,
                        d("trapcraft:boost_wand", 1),
                        d("trapcraft:harvest_wand", 1),
                        d("trapcraft:prospect_wand", 1),
                        d("trapcraft:builder_wand", 1),
                        d("trapcraft:storm_wand", 1)));
    }

    // --- reading it -----------------------------------------------------------

    public static List<Reward> pool(Tier tier, Grade grade) {
        return POOLS.get(tier).get(grade);
    }

    /**
     * Which grade a draw lands on.
     *
     * @param draw anything in [0, {@link #DRAWS})
     */
    public static Grade gradeFor(int draw) {
        int seen = 0;
        for (Grade grade : Grade.values()) {
            seen += grade.weight();
            if (draw < seen) {
                return grade;
            }
        }
        // Unreachable while the weights sum to DRAWS, which the test enforces.
        // Falling back to the common band rather than throwing: a bad draw
        // should cost somebody a good skin, not the server.
        return Grade.MIL_SPEC;
    }

    /** Average shelf value of one open, in emeralds. */
    public static double expectedWorth(Tier tier) {
        double total = 0;
        for (Grade grade : Grade.values()) {
            double average = pool(tier, grade).stream()
                    .mapToInt(Reward::worth).average().orElseThrow();
            total += average * grade.weight() / DRAWS;
        }
        return total;
    }
}
