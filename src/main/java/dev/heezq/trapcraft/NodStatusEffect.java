package dev.heezq.trapcraft;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectCategory;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;

/**
 * Nodding off. The third shape a high can take in this mod, and on purpose the
 * only one whose bill arrives somewhere other than the effect itself.
 *
 * <pre>
 *   Baked  -- pays while it lasts:  hunger burns the whole time.
 *   Wired  -- pays when it ends:    the crash lands all at once.
 *   Nod    -- pays days later:      the meter in TrapAddiction.
 * </pre>
 *
 * So this one is allowed to be almost purely good in the moment -- nothing
 * hurts, the bleeding stops, you are not hungry -- because the price is not in
 * here at all. What it costs you now is your usefulness: the item pairs it with
 * Slowness and Mining Fatigue, so a nodding player is a comfortable passenger
 * who cannot fight, mine or run. That combination is what stops the strongest
 * drug in the game from also being the best combat buff in the game.
 *
 * Deliberately not a PolymerStatusEffect, for the reason spelled out at length
 * on {@link BakedStatusEffect}: the default replacement is null and null erases
 * the effect from the sync packet.
 */
public class NodStatusEffect extends StatusEffect {
    /** Every two seconds. Slow, like the effect. */
    private static final int PERIOD_TICKS = 40;
    /** Health returned per tick of the effect, per level. */
    private static final float HEAL_PER_TICK = 0.5F;
    /**
     * Hunger held still rather than fed.
     *
     * Saturation, not food: it stops the bar draining while you are under
     * without ever filling it, so a nod is not a meal and coming down on an
     * empty stomach is still coming down on an empty stomach.
     */
    private static final float SATURATION_HELD = 2.0F;

    public NodStatusEffect() {
        super(StatusEffectCategory.BENEFICIAL, 0xA86A3A);
    }

    @Override
    public boolean canApplyUpdateEffect(int duration, int amplifier) {
        return duration % PERIOD_TICKS == 0;
    }

    @Override
    public boolean applyUpdateEffect(ServerWorld world, LivingEntity entity, int amplifier) {
        warmth(world, entity, amplifier);

        if (entity.getHealth() < entity.getMaxHealth()) {
            entity.heal(HEAL_PER_TICK * (1 + amplifier));
        }
        if (entity instanceof PlayerEntity player) {
            var hunger = player.getHungerManager();
            if (hunger.getSaturationLevel() < SATURATION_HELD) {
                hunger.setSaturationLevel(SATURATION_HELD);
            }
        }
        return true;
    }

    /**
     * The look of it: a slow warm drift upward rather than smoke.
     *
     * FALLING_HONEY reads as heavy and unhurried and is the one vanilla
     * particle that looks like something sinking rather than rising, which is
     * the whole feeling being sold. Spawned server-side because a Polymer
     * client never gets randomDisplayTick -- see the mod's notes on that.
     */
    private static void warmth(ServerWorld world, LivingEntity entity, int amplifier) {
        Vec3d head = entity.getEyePos();
        world.spawnParticles(ParticleTypes.FALLING_HONEY,
                head.x, head.y + 0.15, head.z, 1 + amplifier, 0.25, 0.2, 0.25, 0.0);
        world.spawnParticles(ParticleTypes.WARPED_SPORE,
                head.x, head.y, head.z, 2 + amplifier, 0.35, 0.3, 0.35, 0.0);
    }
}
