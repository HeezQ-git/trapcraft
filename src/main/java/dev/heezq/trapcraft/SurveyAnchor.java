package dev.heezq.trapcraft;

/**
 * A block a survey is taken FROM, and therefore one the flood fill walks
 * straight through.
 *
 * <h2>Why this is an interface and not three names in an if</h2>
 *
 * {@link HomeSurvey} starts its fill at the anchor's own cell, so an anchor
 * that reads as solid is a fill that never leaves the block it began in. That
 * path returns {@code buried}, which every checklist in the mod then prints as
 * "not sealed" -- so the symptom is a perfectly good building being told it has
 * a hole in it, with no hole anywhere and nothing to go and look at.
 *
 * It was a literal list in {@code Ground.open}: air, the mailbox, the ward.
 * The nick was added to the register a version later, nobody thought about a
 * list two files away, and the first station anybody built reported "Jest
 * dziura. Z takiego aresztu się wychodzi" at a sealed room with glass in the
 * windows. A list you have to remember to add to is a bug with a delay on it.
 *
 * Implementing this is the declaration. Anything that hands its own position
 * to {@link TrapHomes#look} belongs here, and the compiler carries the note.
 */
public interface SurveyAnchor {
}
