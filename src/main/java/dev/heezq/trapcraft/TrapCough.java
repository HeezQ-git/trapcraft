package dev.heezq.trapcraft;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The chance you choke on it.
 *
 * A fit is two coughs, not one -- a single splutter reads as a random noise,
 * whereas a second one a third of a second later is unmistakably somebody
 * coughing. That's the whole reason this needs a tick hook rather than living
 * inline in the two call sites.
 *
 * Chance scales with the strength of the hit rather than sitting flat. A mild
 * joint lands on the asked-for 1-in-3; a fire tlok is closer to one in two.
 * The scaling comes free, because potency already has grade, method and your
 * tolerance folded in -- a seasoned smoker on a weak joint coughs least.
 */
public final class TrapCough {
    /** Chance at reference potency: one mids joint, clear head. */
    private static final float BASE = 0.33F;
    private static final float MIN = 0.15F;
    private static final float MAX = 0.65F;

    /** Ticks between the first cough and the second. */
    private static final int FOLLOW_UP = 7;

    /** Who owes a second cough, and in how many ticks. Drains itself. */
    private static final Map<UUID, Integer> PENDING = new HashMap<>();

    private TrapCough() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (PENDING.isEmpty()) {
                return;
            }
            var it = PENDING.entrySet().iterator();
            while (it.hasNext()) {
                var entry = it.next();
                int left = entry.getValue() - 1;
                if (left > 0) {
                    entry.setValue(left);
                    continue;
                }
                it.remove();
                ServerPlayerEntity player = server.getPlayerManager().getPlayer(entry.getKey());
                if (player != null) {
                    cough(player.getWorld(), player);
                }
            }
        });
    }

    /**
     * Roll for a cough. Call once per hit, with the hit's effective potency.
     */
    public static void maybe(ServerWorld world, LivingEntity smoker, float potency) {
        float chance = MathHelper.clamp(BASE * (0.6F + 0.4F * potency), MIN, MAX);
        if (world.getRandom().nextFloat() >= chance) {
            return;
        }
        cough(world, smoker);
        PENDING.put(smoker.getUuid(), FOLLOW_UP);
    }

    private static void cough(ServerWorld world, LivingEntity smoker) {
        // Panda sneeze pitched down. There is no cough in vanilla, and this is
        // the only sample that's a wet involuntary bark rather than a grunt or
        // a hurt noise -- dropping the pitch takes the cute out of it.
        world.playSound(null, smoker.getX(), smoker.getY(), smoker.getZ(),
                SoundEvents.ENTITY_PANDA_SNEEZE, SoundCategory.PLAYERS,
                0.8F, 0.60F + world.getRandom().nextFloat() * 0.18F);

        // Smoke out of the face, in whatever direction they're looking.
        Vec3d look = smoker.getRotationVec(1.0F);
        Vec3d mouth = smoker.getEyePos().add(look.multiply(0.4));
        world.spawnParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE,
                mouth.x, mouth.y, mouth.z, 10,
                0.14, 0.10, 0.14, 0.015);

        // Amplifier 1, not 0: Kush already applies Slowness 0 for 90s, and a
        // level-0 cough would be silently swallowed by it and do nothing.
        // No icon -- this is a two-second stumble, not a status to read.
        smoker.addStatusEffect(new StatusEffectInstance(
                StatusEffects.SLOWNESS, 40, 1, false, false, false));

        if (smoker instanceof ServerPlayerEntity player) {
            player.addExhaustion(1.5F);
            player.sendMessage(Text.literal("*cough*").formatted(Formatting.GRAY), true);
        }
    }
}
