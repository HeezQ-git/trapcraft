package dev.heezq.trapcraft;

import net.minecraft.util.Formatting;

/**
 * The things a body can get hooked on, and how hard each one pulls.
 *
 * One row per thing you can actually be addicted TO, which is why the six weed
 * strains are six entries rather than one: somebody who smokes nothing but Purp
 * should crave Purp, not "weed", and a shelf of Kush should be no comfort at
 * all. Cocaine and heroin have no phenotypes -- their variation is purity, and
 * purity scales the size of the hit rather than making it a different drug.
 *
 * Every number the addiction system uses lives here, so retuning how hooked
 * something gets you is one table rather than a hunt through {@link
 * TrapAddiction}. The guide book and the wiki read these fields directly.
 *
 * <pre>
 *              hit    decay    period    hits to max   clean-up
 *   weed       2.0    1.20/m   14 min        50          83 min
 *   coke       6.0    0.60/m    7 min        17         167 min
 *   dope      16.0    0.30/m    3 min         6         333 min
 * </pre>
 *
 * The right two columns are derived rather than set -- {@link #hitsToMax} and
 * {@link #minutesToClean} compute them off the two rates to their left -- so
 * this table is a summary of four numbers and not a fifth and sixth that could
 * drift from them. Halve the clean-up if you ride the withdrawal out; see
 * {@link TrapAddiction}.
 */
public enum Drug {
    KUSH(Strain.KUSH),
    HAZE(Strain.HAZE),
    PURP(Strain.PURP),
    DIESEL(Strain.DIESEL),
    MIDNIGHT(Strain.MIDNIGHT),
    SUNSET(Strain.SUNSET),

    /** Refined coca. Purity is the variation, so there is only ever one meter. */
    COKE("coke", "Kokaina", 0xE8E4F0, Formatting.WHITE, 6.0F, 0.60F, 7, 1.0F),

    /**
     * The long line. Four times weed's hook per hit and a quarter of its decay,
     * which is the whole reason the chain behind it is allowed to be worth so
     * much: the money is real and so is the bill.
     */
    DOPE("dope", "Heroina", 0xA86A3A, Formatting.GOLD, 16.0F, 0.30F, 3, 3.0F);

    /** Nothing is ever hooked harder than this, whatever you do to it. */
    public static final float MAX = 100.0F;

    /**
     * Weed's numbers, shared by all six phenotypes.
     *
     * Same figures on purpose: the strains differ in what the high DOES, not in
     * how much of a habit it is, and giving Midnight a stiffer hook than Haze
     * would be a balance lever nobody could see or reason about.
     */
    private static final float WEED_HOOK = 2.0F;
    private static final float WEED_DECAY = 1.2F;
    private static final int WEED_PERIOD = 14;

    static {
        // The six weed rows are positional -- Drug.of(strain) indexes straight
        // into values(). Add a seventh strain and this fails at class load
        // with a clear message instead of silently leaving it un-trackable.
        if (Strain.values().length != COKE.ordinal()) {
            throw new IllegalStateException(
                    "Drug has " + COKE.ordinal() + " weed rows for "
                            + Strain.values().length + " strains -- add the missing one");
        }
        // The one thing about this table that is a DESIGN rather than a dial:
        // each line has to be worse than the one before it, on both axes. The
        // numbers are meant to be retuned; the ordering is not, and a retune
        // that quietly inverts it would be invisible in play until somebody
        // noticed dope was easier to shake than a joint.
        //
        // Checked at class load rather than in a test, because Drug pulls in
        // Strain and Strain pulls in Minecraft -- so the plain JUnit suite
        // cannot load this class at all. See the note in build.gradle.
        if (!(DOPE.hook > COKE.hook && COKE.hook > KUSH.hook)) {
            throw new IllegalStateException("hooks must climb weed < coke < dope");
        }
        if (!(DOPE.decay < COKE.decay && COKE.decay < KUSH.decay)) {
            throw new IllegalStateException("decay must fall weed > coke > dope");
        }
        if (!(DOPE.period < COKE.period && COKE.period < KUSH.period)) {
            throw new IllegalStateException("craving must ripen fastest on dope");
        }
        if (!(DOPE.priceScale > COKE.priceScale)) {
            throw new IllegalStateException("dope has to be worth more than powder");
        }
    }

    private final String id;
    private final String display;
    private final int colour;
    private final Formatting text;
    private final float hook;
    private final float decay;
    private final int period;
    private final float priceScale;
    private final Strain strain;

    Drug(Strain strain) {
        this(strain.id(), strain.display(), strain.colour(), Formatting.GREEN,
                WEED_HOOK, WEED_DECAY, WEED_PERIOD, 1.0F, strain);
    }

    Drug(String id, String display, int colour, Formatting text,
         float hook, float decay, int period, float priceScale) {
        this(id, display, colour, text, hook, decay, period, priceScale, null);
    }

    Drug(String id, String display, int colour, Formatting text,
         float hook, float decay, int period, float priceScale, Strain strain) {
        this.id = id;
        this.display = display;
        this.colour = colour;
        this.text = text;
        this.hook = hook;
        this.decay = decay;
        this.period = period;
        this.priceScale = priceScale;
        this.strain = strain;
    }

    public String id() {
        return id;
    }

    public String display() {
        return display;
    }

    /** Item-brightness colour, for the bar in {@code /addiction}. */
    public int colour() {
        return colour;
    }

    public Formatting text() {
        return text;
    }

    /** Added to the meter per hit, before the hit's own potency scales it. */
    public float hookPerHit() {
        return hook;
    }

    /** Points shed per real minute clean. Doubles while properly sick. */
    public float decayPerMinute() {
        return decay;
    }

    /** Minutes off it before the craving is at its worst. */
    public int cravePeriodMinutes() {
        return period;
    }

    /** Minutes from a full meter to nothing, coasting. */
    public int minutesToClean() {
        return Math.round(MAX / decay);
    }

    /** Hits at 1.0x potency to go from nothing to hooked to the eyes. */
    public int hitsToMax() {
        return Math.round(MAX / hook);
    }

    /** What one unit fetches, against a coca powder of the same purity. */
    public float priceScale() {
        return priceScale;
    }

    /** The phenotype this tracks, or null for the two refined lines. */
    public Strain strain() {
        return strain;
    }

    public boolean isWeed() {
        return strain != null;
    }

    public static Drug of(Strain strain) {
        return values()[strain.ordinal()];
    }

    public static Drug byId(String id) {
        for (Drug drug : values()) {
            if (drug.id.equals(id)) {
                return drug;
            }
        }
        return null;
    }
}
