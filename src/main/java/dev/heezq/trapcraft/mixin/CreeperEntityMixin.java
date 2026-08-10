package dev.heezq.trapcraft.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.world.World;
import net.minecraft.world.explosion.Explosion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Creepers still hurt. They just stop eating the neighbourhood.
 *
 * {@code explode()} calls {@code createExplosion(..., ExplosionSourceType.MOB)},
 * and MOB is the only thing standing between a build and a hole -- it resolves
 * to KEEP or DESTROY depending on the mobGriefing gamerule. NONE resolves to
 * KEEP unconditionally, and KEEP still runs the full explosion: entity damage,
 * knockback, the S2C packet. Only the block pass is skipped. So the swap is one
 * enum constant and nothing about a creeper being dangerous changes.
 *
 * The gamerule would have been free, but it is server-wide -- endermen stop
 * picking up blocks, ghasts stop cratering the Nether, villagers stop farming.
 * This is the creeper only.
 *
 * A {@code @Redirect} rather than {@code @ModifyArg} because the 12-arg overload
 * underneath also takes the particle and the sound, and a bubble explosion that
 * still goes BOOM in a cloud of black smoke isn't a bubble explosion.
 *
 * On the particles: BUBBLE is the obvious pick and the wrong one --
 * {@code WaterBubbleParticle.tick()} marks itself dead the moment it isn't
 * standing in a water fluidstate, so on land it never draws a frame. BUBBLE_POP
 * has no such check (4-tick sprite animation, the pop itself) and SPLASH has no
 * such check either and lives ~10-40 ticks, so the pops read as the burst and
 * the splash droplets are what's still hanging in the air after.
 *
 * On the sound: the client plays the explosion sound at volume 4 and pitch
 * {@code (1 + (rand-rand)*0.2) * 0.7} -- always pitched DOWN, which is what
 * makes a belch land as a deep wet BLORP rather than a squeak, and why these
 * three were picked rather than something already low.
 */
@Mixin(CreeperEntity.class)
public abstract class CreeperEntityMixin {
    @Redirect(
            method = "explode",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/world/ServerWorld;createExplosion("
                            + "Lnet/minecraft/entity/Entity;DDDF"
                            + "Lnet/minecraft/world/World$ExplosionSourceType;)V"))
    private void trapcraft$bubbleBurst(ServerWorld world, Entity creeper,
                                       double x, double y, double z,
                                       float power, World.ExplosionSourceType ignored) {
        // ponytail: three fixed sounds, rolled per explosion so the joke doesn't
        // wear out by the third creeper. A config knob if anyone ever tires of it.
        SoundEvent pop = switch (world.getRandom().nextInt(3)) {
            case 0 -> SoundEvents.ENTITY_PLAYER_BURP;
            case 1 -> SoundEvents.ENTITY_PUFFER_FISH_BLOW_OUT;
            default -> SoundEvents.ENTITY_SLIME_SQUISH;
        };

        // Same call vanilla makes one frame down, with NONE for the destruction
        // type and bubbles where the smoke and the bang used to be.
        world.createExplosion(
                creeper, Explosion.createDamageSource(world, creeper), null,
                x, y, z, power, false, World.ExplosionSourceType.NONE,
                ParticleTypes.BUBBLE_POP, ParticleTypes.BUBBLE_POP,
                RegistryEntry.of(pop));

        // The packet above carries a single centre particle, which is nothing to
        // look at. This is the actual burst. power is 3 normally and 6 charged,
        // so a charged creeper gets a proportionally bigger cloud for free.
        //
        // Two shells rather than one big gaussian: a single spawnParticles call
        // clumps everything at the centre and reads as a blob. A tight core plus
        // a wide loose cloud reads as a mass of separate bubbles.
        double spread = power * 0.4;
        world.spawnParticles(ParticleTypes.BUBBLE_POP, x, y + 0.6, z,
                (int) (power * 110), spread * 0.55, spread * 0.5, spread * 0.55, 0.08);
        world.spawnParticles(ParticleTypes.BUBBLE_POP, x, y + 0.6, z,
                (int) (power * 70), spread * 1.4, spread * 1.1, spread * 1.4, 0.25);
        world.spawnParticles(ParticleTypes.SPLASH, x, y + 0.6, z,
                (int) (power * 35), spread * 0.7, spread * 0.5, spread * 0.7, 0.3);
    }
}
