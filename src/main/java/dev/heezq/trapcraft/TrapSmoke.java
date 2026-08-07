package dev.heezq.trapcraft;

import net.minecraft.entity.LivingEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * What a smoke looks and sounds like while it's happening.
 *
 * The joint used to be 3.2 seconds of silence: consumeParticles(false) turned
 * off vanilla's eating crumbs -- which are wrong, they're bits of food flying
 * out of your mouth -- and nothing replaced them. The use animation played, the
 * effects landed at the end, and in between there was no evidence anything was
 * going on. First-time players read that as the game being stuck.
 *
 * Everything here is beat-matched to joint_smoke.json, so the ember flares
 * exactly when the animation brings it to the lips and the plume lands on the
 * exhale rather than at some unrelated moment.
 */
public final class TrapSmoke {
    // Phase boundaries in ticks elapsed, matching the animation's keyframes.
    private static final int LIT = 4;        // ember catches
    private static final int DRAW_FROM = 16; // at the lips
    private static final int DRAW_TO = 32;
    private static final int EXHALE = 44;

    private TrapSmoke() {
    }

    /**
     * Called every tick of a joint's use. Server side only -- the particles
     * and sounds broadcast from here, so bystanders see and hear it too, which
     * is half the point of a shared server.
     */
    public static void usageTick(World world, LivingEntity user, int remainingUseTicks, int maxUse) {
        if (!(world instanceof ServerWorld server)) {
            return;
        }
        int elapsed = maxUse - remainingUseTicks;
        Vec3d look = user.getRotationVec(1.0F);
        // Roughly where the joint is: out in front, and a little below the eyes
        // during the draw, dropping away once it comes down.
        boolean atLips = elapsed >= DRAW_FROM && elapsed < DRAW_TO;
        Vec3d tip = user.getEyePos()
                .add(look.multiply(atLips ? 0.32 : 0.45))
                .add(0.0, atLips ? -0.08 : -0.35, 0.0);

        if (elapsed < LIT) {
            return;
        }

        // The ember. Brighter and steadier while you're actually pulling on it,
        // which is the single clearest "this is lit and I am using it" signal.
        if (atLips || elapsed % 4 == 0) {
            server.spawnParticles(ParticleTypes.SMALL_FLAME,
                    tip.x, tip.y, tip.z, 1, 0.01, 0.01, 0.01, 0.0);
        }

        // Smoke trickling off it the whole time. Cosy smoke rather than plain
        // smoke because it rises slowly and lingers -- plain SMOKE puffs out
        // and is gone before the next tick draws.
        if (elapsed % 3 == 0) {
            server.spawnParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE,
                    tip.x, tip.y + 0.04, tip.z, 1, 0.03, 0.02, 0.03, 0.002);
        }

        if (atLips) {
            // Crackle rising in pitch across the draw: the ember getting hotter
            // as you pull. One flat repeated sample reads as a loop bug.
            if ((elapsed - DRAW_FROM) % 5 == 0) {
                float through = (elapsed - DRAW_FROM) / (float) (DRAW_TO - DRAW_FROM);
                server.playSound(null, user.getX(), user.getY(), user.getZ(),
                        SoundEvents.BLOCK_CAMPFIRE_CRACKLE, SoundCategory.PLAYERS,
                        0.55F, 0.85F + through * 0.5F);
            }
            // Ash falling off the tip while it burns down.
            if (elapsed % 6 == 0) {
                server.spawnParticles(ParticleTypes.WHITE_ASH,
                        tip.x, tip.y - 0.05, tip.z, 1, 0.02, 0.0, 0.02, 0.0);
            }
        }

        if (elapsed == EXHALE) {
            exhale(server, user, 1.0F);
        }
    }

    /**
     * The plume. Also used by the bong and tlok, where it's the visible half of
     * a hit that would otherwise be over in a single frame.
     *
     * Cosy smoke lingers for a couple of seconds, which matters because the
     * gesture animations run for two to three -- a burst of short-lived CLOUD
     * particles was gone before the player's arms had finished moving, so the
     * back half of every animation played over nothing.
     */
    public static void exhale(ServerWorld world, LivingEntity user, float strength) {
        Vec3d look = user.getRotationVec(1.0F);
        Vec3d mouth = user.getEyePos().add(look.multiply(0.35));
        int count = Math.round(14 * strength);

        world.spawnParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE,
                mouth.x, mouth.y, mouth.z, count, 0.12, 0.08, 0.12, 0.012 * strength);
        // A second, wider and slower cloud so the plume has some body to it
        // rather than being a single thin stream.
        world.spawnParticles(ParticleTypes.CLOUD,
                mouth.x, mouth.y, mouth.z, count / 2, 0.2, 0.12, 0.2, 0.004);

        world.playSound(null, user.getX(), user.getY(), user.getZ(),
                SoundEvents.BLOCK_LAVA_EXTINGUISH, SoundCategory.PLAYERS,
                0.35F * strength, 1.5F);
    }
}
