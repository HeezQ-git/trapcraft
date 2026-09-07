package dev.heezq.trapcraft;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectCategory;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;

/**
 * Groza -- dread. The witness's currency.
 *
 * Every hit it lands adds a stack (the amplifier, 0 to {@link ArenaMath#DREAD_MAX}),
 * and the stacks are what make the fight worse: each one slows you, the
 * third pulses the screen dark, the fourth bleeds. None of it is lethal on
 * its own; all of it makes the next Fala harder to jump and the next orb
 * harder to punch, which is how a boss with eight damage on a swing gets to
 * be dangerous without a bigger number.
 *
 * The counter is company. {@link TrapArena} fades a stack off anyone with
 * another player within {@link ArenaMath#COMPANY_RANGE} -- the same rule
 * Paranoia already taught everyone on this server -- so grouping up is the
 * answer and the mechanic explains itself.
 *
 * Only the wisps live here. The darkness pulse, the bleed and the fade all
 * run from {@link TrapArena#dreadTick}: adding or replacing an effect from
 * inside an effect's own update mutates the map the entity is iterating,
 * which vanilla survives by swallowing the exception -- and skipping every
 * other effect's tick while it does.
 *
 * A plain StatusEffect on purpose; see {@link BakedStatusEffect} for why not
 * a Polymer one. The icon is textures/mob_effect/groza.png.
 */
public class DreadStatusEffect extends StatusEffect {
    private static final int PERIOD = 20;
    private static final DustParticleEffect WISP = new DustParticleEffect(0x4a2a8c, 1.2F);

    public DreadStatusEffect() {
        super(StatusEffectCategory.HARMFUL, 0x5a2d9c);
        // Scales with the amplifier by itself: vanilla multiplies the amount
        // by (amplifier + 1), so four stacks is a third of your speed gone.
        addAttributeModifier(EntityAttributes.MOVEMENT_SPEED, TrapCraft.id("groza"),
                -0.08, EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
    }

    @Override
    public boolean canApplyUpdateEffect(int duration, int amplifier) {
        return duration % PERIOD == 0;
    }

    @Override
    public boolean applyUpdateEffect(ServerWorld world, LivingEntity entity, int amplifier) {
        Vec3d head = entity.getEyePos();
        world.spawnParticles(WISP, head.x, head.y + 0.3, head.z, 3 + amplifier * 2,
                0.4, 0.3, 0.4, 0.0);
        return true;
    }
}
