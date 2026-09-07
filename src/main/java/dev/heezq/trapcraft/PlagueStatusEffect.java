package dev.heezq.trapcraft;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectCategory;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;

/**
 * Zaraza -- the plague. What the rat king's bite and puddles leave on you.
 *
 * Every stack (the amplifier, 0 to {@link ArenaMath#PLAGUE_MAX}) eats at
 * your food bar; from the second the screen swims, from the third it costs
 * a heart every couple of seconds. It washes off: a stack a second while
 * you stand in water, and the chamber has a cross of it in the floor, so
 * the counter is in the room and it is where the rats are not.
 *
 * Only the exhaustion and the wisps live here. The nausea, the bleed and
 * the wash all run from {@code RatKingBoss.debuffTick}, because putting an
 * effect on an entity from inside its own effect update mutates the map
 * being iterated.
 *
 * Icon at textures/mob_effect/zaraza.png.
 */
public class PlagueStatusEffect extends StatusEffect {
    private static final int PERIOD = 20;
    private static final DustParticleEffect WISP = new DustParticleEffect(0x6bb51a, 1.1F);

    public PlagueStatusEffect() {
        super(StatusEffectCategory.HARMFUL, 0x6bb51a);
    }

    @Override
    public boolean canApplyUpdateEffect(int duration, int amplifier) {
        return duration % PERIOD == 0;
    }

    @Override
    public boolean applyUpdateEffect(ServerWorld world, LivingEntity entity, int amplifier) {
        if (entity instanceof PlayerEntity player) {
            player.getHungerManager().addExhaustion(0.6F * (amplifier + 1));
        }
        Vec3d head = entity.getEyePos();
        world.spawnParticles(WISP, head.x, head.y + 0.2, head.z, 2 + amplifier * 2, 0.4, 0.3, 0.4, 0.0);
        return true;
    }
}
