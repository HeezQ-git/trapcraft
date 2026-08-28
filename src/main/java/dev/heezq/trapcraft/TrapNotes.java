package dev.heezq.trapcraft;

import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * One span of a chat line, with bold and italic switched off first.
 *
 * <h2>The bug this exists to stop</h2>
 *
 * A Text's children INHERIT its style, and {@code formatted(Formatting.GRAY)}
 * sets a colour without touching bold. So the ordinary way of writing a notice
 * in this mod --
 *
 * <pre>Text.literal("HEADLINE").formatted(RED, BOLD)
 *     .append(Text.literal(" the rest of it").formatted(GRAY))</pre>
 *
 * -- produces a line that is bold ALL THE WAY DOWN, including the quiet grey
 * explanation nobody meant to shout. Reported from the live server as "wszystko
 * jest szare i pogrubione, źle się to łączy", which is exactly what it is: the
 * headline's weight bleeding into every sibling after it, so nothing stands out
 * because everything does.
 *
 * There is no way to spot this from the server side. It compiles, it sends, and
 * the only place it is visible is in somebody's chat window.
 *
 * <h2>The rule</h2>
 *
 * Start a notice from {@link Text#empty()} -- a root with no style of its own --
 * and build every span through here. Bold and italic are cleared BEFORE the
 * requested formatting is applied, so a span is bold only when it asks to be,
 * and asking is what makes it the one thing the eye lands on.
 */
final class TrapNotes {

    private TrapNotes() {
    }

    /** A span that inherits nothing it did not ask for. */
    static MutableText say(String text, Formatting... style) {
        return Text.literal(text)
                .styled(s -> s.withBold(false).withItalic(false))
                .formatted(style);
    }

    /**
     * A headline: one loud word, and then the line goes quiet.
     *
     * Bold on the label ONLY. The sentence that follows it is somebody
     * explaining what happened, and an explanation in bold is a raised voice
     * for no reason.
     */
    static MutableText headline(String label, Formatting colour) {
        return Text.empty().append(say(label, colour, Formatting.BOLD));
    }

    /** An indented second line, for the half nobody has to read twice. */
    static MutableText under(String text) {
        return say("\n  " + text, Formatting.DARK_GRAY);
    }

    /**
     * A labelled figure: quiet word, loud number.
     *
     * The shape every readout in this mod wants and none of them had -- the
     * label is scenery and the number is the answer, so only one of them is
     * allowed to be bright.
     */
    static MutableText figure(String label, String value, Formatting colour) {
        return Text.empty()
                .append(say(label, Formatting.DARK_GRAY))
                .append(say(value, colour));
    }
}
