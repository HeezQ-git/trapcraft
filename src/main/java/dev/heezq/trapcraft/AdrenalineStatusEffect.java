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
 * Adrenalina -- the arena's reward buff.
 *
 * Twenty seconds of it every time the witness drops a phase, three minutes
 * of it when it falls, so the winners walk out of the arena faster and
 * harder-hitting than they walked in and the buff itself is part of the
 * loot. Speed, a flat two on the sword, and a slow heal; nothing that would
 * matter at a casino or a grow, everything that matters in a fight.
 *
 * Plain StatusEffect, icon at textures/mob_effect/adrenalina.png -- see
 * {@link BakedStatusEffect} for why not a Polymer one.
 */
public class AdrenalineStatusEffect extends StatusEffect {
    private static final int PERIOD = 40;

    public AdrenalineStatusEffect() {
        super(StatusEffectCategory.BENEFICIAL, 0xff4a3c);
        addAttributeModifier(EntityAttributes.MOVEMENT_SPEED, TrapCraft.id("adrenalina_speed"),
                0.15, EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
        addAttributeModifier(EntityAttributes.ATTACK_DAMAGE, TrapCraft.id("adrenalina_damage"),
                2.0, EntityAttributeModifier.Operation.ADD_VALUE);
    }

    @Override
    public boolean canApplyUpdateEffect(int duration, int amplifier) {
        return duration % PERIOD == 0;
    }

    @Override
    public boolean applyUpdateEffect(ServerWorld world, LivingEntity entity, int amplifier) {
        if (entity.getHealth() < entity.getMaxHealth()) {
            entity.heal(1.0F);
        }
        Vec3d chest = entity.getPos().add(0.0, entity.getHeight() * 0.6, 0.0);
        world.spawnParticles(ParticleTypes.CRIT, chest.x, chest.y, chest.z, 4, 0.3, 0.3, 0.3, 0.05);
        return true;
    }
}
