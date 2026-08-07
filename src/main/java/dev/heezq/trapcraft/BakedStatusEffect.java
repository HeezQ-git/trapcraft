package dev.heezq.trapcraft;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectCategory;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;

/**
 * The munchies, as a status effect. Real per-tick behaviour rather than a pile
 * of stacked vanilla effects -- burns hunger, and tops health back up while
 * you're fed, so a high on an empty stomach actually costs you something.
 */
/*
 * Deliberately NOT a PolymerStatusEffect.
 *
 * PolymerStatusEffect.getPolymerReplacement() defaults to returning null, and
 * in the status-effect sync path that null wins: Polymer erases the effect from
 * the packet before it leaves the server, for modded and vanilla clients alike.
 * Symptom is nasty because the effect still works perfectly server-side --
 * hunger drains, healing ticks, NBT shows it -- while every client sees nothing.
 *
 * As a plain StatusEffect it syncs natively, which is what any client running
 * this mod wants. Vanilla clients still don't get an icon, but they didn't
 * before either, so nothing regressed. Re-add Polymer only with an explicit
 * getPolymerReplacement returning a real vanilla effect, never the default.
 */
public class BakedStatusEffect extends StatusEffect {
    // --- tuning -------------------------------------------------------------
    // Vanilla: 4.0 exhaustion burns 1 saturation, and only once saturation is
    // gone does a drumstick drop. So 1.0 exhaustion per 10s is about one
    // drumstick every 40s at Baked I -- cheap enough that the healing wins.
    private static final int PERIOD_TICKS = 200;          // 10s
    private static final float EXHAUSTION_PER_TICK = 1.0F;
    private static final int WELL_FED = 10;               // heal at/above this
    private static final float HEAL_PER_TICK = 1.0F;

    public BakedStatusEffect() {
        super(StatusEffectCategory.NEUTRAL, 0x7a4fa8);
    }

    @Override
    public boolean canApplyUpdateEffect(int duration, int amplifier) {
        // Fixed cadence. Amplifier scales the bite per tick, not the rate --
        // scaling both is what made Purp eat a full food bar in 20 seconds.
        return duration % PERIOD_TICKS == 0;
    }

    @Override
    public boolean applyUpdateEffect(ServerWorld world, LivingEntity entity, int amplifier) {
        haze(world, entity, amplifier);

        if (!(entity instanceof PlayerEntity player)) {
            return true;
        }

        var hunger = player.getHungerManager();
        if (hunger.getFoodLevel() > 0) {
            // Exhaustion rather than setFoodLevel, so vanilla burns saturation
            // first and the bar only drops once that buffer is spent -- exactly
            // how sprinting behaves. Direct setFoodLevel skipped that buffer,
            // which is why it felt so punishing.
            // amplifier identifies the STRAIN; how hard it burns is looked up.
            // Reading the amplifier as a potency level here is what made Purp
            // eat a food bar in 20 seconds back when the two were conflated.
            int intensity = Strain.byIndex(amplifier).intensity();
            hunger.addExhaustion(EXHAUSTION_PER_TICK * (1.0F + intensity * 0.25F));
            // Well-fed and stoned = comfortable. Chip health back slowly.
            if (hunger.getFoodLevel() >= WELL_FED && player.getHealth() < player.getMaxHealth()) {
                player.heal(HEAL_PER_TICK);
            }
        } else if (player.getHealth() > 2.0F) {
            // Baked with an empty stomach is not comfortable.
            player.damage(world, world.getDamageSources().starve(), 1.0F);
        }
        return true;
    }

    /**
     * The visible half of being high: a slow haze around the head that thickens
     * with the strain. Spawned server-side, so everyone nearby sees it on a
     * vanilla client -- including you in third person.
     */
    private static void haze(ServerWorld world, LivingEntity entity, int amplifier) {
        Vec3d head = entity.getEyePos();
        for (int i = 0; i <= Strain.byIndex(amplifier).intensity(); i++) {
            world.spawnParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE,
                    head.x, head.y + 0.2, head.z, 1,
                    0.25, 0.15, 0.25, 0.005);
        }
    }

    // The icon now comes from assets/trapcraft/textures/mob_effect/baked.png,
    // the vanilla path any client running this mod already reads.
}
