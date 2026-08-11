package dev.heezq.trapcraft;

import java.util.List;

/**
 * The grade field in the flat text saves, and WHICH grade it is.
 *
 * Dealers and casino bars write a stack as {@code item|count|grade}, and grade
 * used to be {@code TrapComponents.get(stack).index()} -- the quality index,
 * for every stack. {@code get()} answers MIDS when there is no quality
 * component, so a Pure powder (grade in {@code purity}) and a Fire blend
 * (grade inside the {@link Blend}) were both written out as quality 1 and came
 * back off disk as literal Mids on the next restart, the blend's mix gone with
 * it. Leaves and paste carry no grade at all and were being renamed too.
 *
 * Tagging the number says which enum it belongs to. Strings only, no Minecraft
 * imports, so the encoding is testable without a game.
 */
record GradeTag(char kind, int index, List<String> parts) {
    static final char QUALITY = 'q';
    static final char PURITY = 'p';
    static final char BLEND = 'b';
    static final char NOTHING = '-';

    /** Coca leaves, paste -- contraband with no grade to lose. */
    static final GradeTag NONE = new GradeTag(NOTHING, 0, List.of());

    @Override
    public String toString() {
        return switch (kind) {
            case BLEND -> BLEND + Integer.toString(index) + ':' + String.join(",", parts);
            case NOTHING -> String.valueOf(NOTHING);
            default -> kind + Integer.toString(index);
        };
    }

    /**
     * A bare number is a save written before the tag existed, and back then
     * every number was a quality index -- which for buds and joints it really
     * was, so those load unchanged.
     */
    static GradeTag parse(String tag) {
        if (tag.isEmpty() || tag.charAt(0) == NOTHING) {
            return NONE;
        }
        if (Character.isDigit(tag.charAt(0))) {
            return new GradeTag(QUALITY, number(tag), List.of());
        }
        char kind = tag.charAt(0);
        String rest = tag.substring(1);
        if (kind == BLEND) {
            int colon = rest.indexOf(':');
            return colon < 0 ? NONE
                    : new GradeTag(BLEND, number(rest.substring(0, colon)),
                            List.of(rest.substring(colon + 1).split(",")));
        }
        return new GradeTag(kind == PURITY ? PURITY : QUALITY, number(rest), List.of());
    }

    /**
     * Never throws. Both save files are read inside one try block, so a single
     * malformed field would otherwise take every dealer on the server with it.
     */
    private static int number(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException malformed) {
            return 0;
        }
    }
}
