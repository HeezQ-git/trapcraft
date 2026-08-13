package dev.heezq.trapcraft;

import net.minecraft.util.Formatting;

/**
 * Grade for refined powder, the coca line's equivalent of {@link Quality}.
 *
 * Separate enum on purpose: weed grade is decided by how you GREW it, purity is
 * decided by how you REFINED it. Same idea, different input, and the two lines
 * should feel like different skills rather than one mechanic reskinned.
 */
public enum Purity {
    CUT("Cięte", Formatting.DARK_GRAY, 0.55F, 3),
    STREET("Uliczne", Formatting.WHITE, 1.00F, 7),
    CLEAN("Dobre", Formatting.AQUA, 1.50F, 13),
    PURE("Idealne", Formatting.LIGHT_PURPLE, 2.10F, 22);

    private final String display;
    private final Formatting colour;
    private final float potency;
    private final int emeralds;

    Purity(String display, Formatting colour, float potency, int emeralds) {
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
            case CUT -> Formatting.DARK_GRAY;
            case STREET -> Formatting.BLACK;
            case CLEAN -> Formatting.DARK_AQUA;
            case PURE -> Formatting.DARK_PURPLE;
        };
    }

    public float potency() {
        return potency;
    }

    /** Well above weed: Pure is 22 emeralds against Fire weed's 7. */
    public int emeralds() {
        return emeralds;
    }

    public int index() {
        return ordinal();
    }

    public static Purity byIndex(int index) {
        Purity[] values = values();
        return values[Math.max(0, Math.min(index, values.length - 1))];
    }
}
