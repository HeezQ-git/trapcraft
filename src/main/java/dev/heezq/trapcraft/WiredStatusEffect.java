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

    /**
     * Harder comedown the harder you were flying -- but NOT on the axis the
     * powder was taken for.
     *
     * This used to add MINING_FATIGUE, and that one line is why nobody on the
     * server had ever taken anything: the crash was an exact mirror of the
     * high. Haste is x1.2 to x1.6 for 38-147s; Mining Fatigue I is x0.3 for
     * 35-65s, and 70% off for a minute beats 20% on for two. Measured in
     * seconds-of-digging against staying sober:
     *
     * <pre>
     *   Ciete    38s  +7.7   crash -24.5  = -16.8
     *   Uliczne  70s +28.0   crash -35.0  =  -7.0
     *   Dobre   105s +63.0   crash -45.5  = +17.5
     *   Idealne 147s +88.2   crash -45.5  = +42.7
     * </pre>
     *
     * The two grades a player actually makes most of were NEGATIVE -- taking
     * the drug dug less rock than not taking it, before the powder's own price
     * and before six points on the habit meter. A choice that is dominated at
     * every price is not a choice, and the empty addiction file was the proof.
     *
     * Slowness and Weakness stay. They are a real bill and they are thematic:
     * you walk home slowly and you cannot punch. What they are not is a refund
     * of the exact thing you paid for.
     */
    private static void crash(LivingEntity entity, int amplifier) {
        int ticks = (CRASH_SECONDS + amplifier * 15) * 20;
        entity.addStatusEffect(new StatusEffectInstance(
                StatusEffects.SLOWNESS, ticks, Math.min(2, amplifier), false, true));
        entity.addStatusEffect(new StatusEffectInstance(
                StatusEffects.WEAKNESS, ticks, 0, false, true));
        entity.addStatusEffect(new StatusEffectInstance(
                StatusEffects.HUNGER, ticks / 2, 0, false, true));
    }
}
