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
    CUT("Cięte", Formatting.DARK_GRAY, 0.55F, 9),
    STREET("Uliczne", Formatting.WHITE, 1.00F, 21),
    CLEAN("Dobre", Formatting.AQUA, 1.50F, 39),
    PURE("Idealne", Formatting.LIGHT_PURPLE, 2.10F, 66);

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

    /**
     * Well above weed: Pure is 66 emeralds against Fire weed's 21.
     *
     * Scaled 3x on 2026-08-15, together with {@link Quality#emeralds()}, so the
     * whole ratio table between the two lines is untouched -- what moved is
     * where both sit against {@link ShopStock}. A Cut line used to fetch 6e on
     * the street and a loaf of bread costs 6e, which said a refined drug is
     * worth dinner. Pure now goes for 125e against a 42e diamond, and the long
     * dope chain behind it for 376e, which is what the machines and the risk
     * are actually worth. The market catalogue is the anchor: retune against
     * bread, diamond and netherite there, not against this table's own past.
     */
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
