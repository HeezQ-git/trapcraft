package dev.heezq.trapcraft;

import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectCategory;

/**
 * Builds up as you smoke and blunts the next high.
 *
 * Purely a marker -- it has no per-tick behaviour of its own. JointItem reads
 * the amplifier and shortens what it hands out. Riding on the status effect
 * system rather than a side map means it saves with the player, shows in the
 * HUD so the falloff is legible, and expires on its own.
 */
public class ToleranceStatusEffect extends StatusEffect {
    /** Each level cuts 18% off a new high, to a floor of 40%. */
    public static final float PER_LEVEL = 0.18F;
    public static final float FLOOR = 0.40F;
    public static final int MAX_LEVEL = 4;
    /** How long one level takes to wear off. */
    public static final int DURATION_TICKS = 20 * 60 * 6;

    public ToleranceStatusEffect() {
        super(StatusEffectCategory.HARMFUL, 0x6b6b6b);
    }

    @Override
    public boolean canApplyUpdateEffect(int duration, int amplifier) {
        return false; // marker only
    }

    /** Multiplier applied to a fresh high, given current tolerance level. */
    public static float multiplier(int level) {
        return Math.max(FLOOR, 1.0F - level * PER_LEVEL);
    }
}
