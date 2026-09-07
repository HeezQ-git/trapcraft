package dev.heezq.trapcraft;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectCategory;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;

/**
 * Stawka -- the stake. The bandit's currency, and the one debuff in the
 * arena that is also a buff.
 *
 * Every stack is a bet you did not choose to place: your sword hits harder
 * by {@link #DAMAGE_PER_STACK}, and the bandit hits you harder by
 * {@link ArenaMath#STAKE_TAKEN_PER_STACK} of itself per stack (see {@code BanditEntity.damageMultiplier}).
 * Push your luck and you win the damage race; get greedy at four stacks and
 * one lever puts you in the stands. It wears off on its own, because a
 * casino never lets a hot streak last.
 *
 * Plain StatusEffect, icon at textures/mob_effect/stawka.png.
 */
public class StakeStatusEffect extends StatusEffect {
    public static final double DAMAGE_PER_STACK = 1.5;
    private static final int PERIOD = 20;

    public StakeStatusEffect() {
        super(StatusEffectCategory.NEUTRAL, 0xffc24a);
        addAttributeModifier(EntityAttributes.ATTACK_DAMAGE, TrapCraft.id("stawka"),
                DAMAGE_PER_STACK, EntityAttributeModifier.Operation.ADD_VALUE);
    }

    @Override
    public boolean canApplyUpdateEffect(int duration, int amplifier) {
        return duration % PERIOD == 0;
    }

    @Override
    public boolean applyUpdateEffect(ServerWorld world, LivingEntity entity, int amplifier) {
        Vec3d head = entity.getEyePos();
        world.spawnParticles(ParticleTypes.WAX_ON, head.x, head.y + 0.4, head.z, 2 + amplifier * 2,
                0.3, 0.2, 0.3, 0.0);
        return true;
    }
}
