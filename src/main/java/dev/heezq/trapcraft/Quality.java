package dev.heezq.trapcraft;

import net.minecraft.util.Formatting;

/**
 * How good the bud is. Set at harvest from how well the plant was grown, then
 * carried on the itemstack all the way to the joint.
 *
 * This is the thing that makes the loop have a skill floor: the actions were
 * always the same, and now doing them carefully is worth several times doing
 * them lazily -- in both potency and emeralds.
 */
public enum Quality {
    SWILL("Słabe", Formatting.DARK_GRAY, 0.60F, 1),
    MIDS("Zwykłe", Formatting.WHITE, 1.00F, 2),
    LOUD("Mocne", Formatting.AQUA, 1.45F, 4),
    FIRE("Topowe", Formatting.LIGHT_PURPLE, 2.00F, 7);

    /** How many condition points are needed to reach each grade. */
    public static final int[] THRESHOLDS = {0, 3, 5, 7};

    private final String display;
    private final Formatting colour;
    private final float potency;
    private final int emeralds;

    Quality(String display, Formatting colour, float potency, int emeralds) {
        this.display = display;
        this.colour = colour;
        this.potency = potency;
        this.emeralds = emeralds;
    }

    public String display() {
        return display;
    }

    public Formatting colour() {
        return colour;
    }

    /**
     * Paper-safe variant for the guide books.
     *
     * The item colours are tuned for dark inventory backgrounds, and on a
     * written book's cream page WHITE is literally invisible and AQUA is
     * barely legible. Same grade, two contexts, two colours.
     */
    public Formatting bookColour() {
        return switch (this) {
            case SWILL -> Formatting.DARK_GRAY;
            case MIDS -> Formatting.BLACK;
            case LOUD -> Formatting.DARK_AQUA;
            case FIRE -> Formatting.DARK_PURPLE;
        };
    }

    /** Multiplies both the Baked duration and the strain's own effects. */
    public float potency() {
        return potency;
    }

    /** What a trader pays for a stack of this grade. Fire is 7x Swill. */
    public int emeralds() {
        return emeralds;
    }

    public int index() {
        return ordinal();
    }

    public static Quality byIndex(int index) {
        Quality[] values = values();
        return values[Math.max(0, Math.min(index, values.length - 1))];
    }

    /**
     * Grade from grow conditions. Max 8 points:
     *
     * <ul>
     *   <li>+3 fully hydrated farmland (moisture 7)  -- keep water close
     *   <li>+2 bright light at the plant             -- don't grow in a cave
     *   <li>+2 open sky above                        -- or build a skylight
     *   <li>+1 not rushed with bone meal             -- patience pays
     * </ul>
     *
     * Bone meal still works and is still the fast route; it just costs you a
     * grade, so there's a real choice between volume and quality.
     */
    public static Quality fromConditions(boolean hydrated, int light, boolean skyVisible, boolean rushed) {
        int points = 0;
        if (hydrated) {
            points += 3;
        }
        if (light >= 12) {
            points += 2;
        } else if (light >= 9) {
            points += 1;
        }
        if (skyVisible) {
            points += 2;
        }
        if (!rushed) {
            points += 1;
        }

        Quality best = SWILL;
        for (Quality quality : values()) {
            if (points >= THRESHOLDS[quality.ordinal()]) {
                best = quality;
            }
        }
        return best;
    }
}
