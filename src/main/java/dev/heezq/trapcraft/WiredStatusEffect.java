package dev.heezq.trapcraft;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectCategory;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.server.world.ServerWorld;

/**
 * The up, and then the crash.
 *
 * Deliberately shaped as the opposite of {@link BakedStatusEffect}: Baked slows
 * you down and burns food while it lasts, this speeds you up and costs nothing
 * until it ends -- and then bills you all at once. Two lines that feel
 * different to use rather than one effect with different numbers.
 */
public class WiredStatusEffect extends StatusEffect {
    private static final int PERIOD = 20;
    /** Fires the crash as the effect runs out. */
    private static final int CRASH_AT = 20;
    private static final int CRASH_SECONDS = 35;

    public WiredStatusEffect() {
        super(StatusEffectCategory.BENEFICIAL, 0xE8E4F0);
    }

    @Override
    public boolean canApplyUpdateEffect(int duration, int amplifier) {
        return duration % PERIOD == 0;
    }

    @Override
    public boolean applyUpdateEffect(ServerWorld world, LivingEntity entity, int amplifier) {
        // Detected by remaining duration rather than a removal hook, so it also
        // fires correctly if the effect is cut short or the player logs out and
        // back in -- the crash should not be dodgeable by relogging.
        if (entity.getStatusEffect(TrapContent.wiredEffect) != null
                && entity.getStatusEffect(TrapContent.wiredEffect).getDuration() <= CRASH_AT) {
            crash(entity, amplifier);
        }
        return true;
    }

    /** Harder comedown the harder you were flying. */
    private static void crash(LivingEntity entity, int amplifier) {
        int ticks = (CRASH_SECONDS + amplifier * 15) * 20;
        entity.addStatusEffect(new StatusEffectInstance(
                StatusEffects.SLOWNESS, ticks, Math.min(2, amplifier), false, true));
        entity.addStatusEffect(new StatusEffectInstance(
                StatusEffects.WEAKNESS, ticks, 0, false, true));
        entity.addStatusEffect(new StatusEffectInstance(
                StatusEffects.MINING_FATIGUE, ticks, 0, false, true));
        entity.addStatusEffect(new StatusEffectInstance(
                StatusEffects.HUNGER, ticks / 2, 0, false, true));
    }
}
