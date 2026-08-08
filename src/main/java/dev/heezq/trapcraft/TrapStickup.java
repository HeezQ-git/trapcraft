package dev.heezq.trapcraft;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Somebody followed the money.
 *
 * The farm raid in {@link TrapHeat} comes for the plants. This comes for YOU,
 * at the moment you hand product over in person -- a customer at the door, a
 * buyer at a drop -- and it is the price of the fact that dealing yourself
 * pays full whack while a dealer takes a cut.
 *
 * Which is the whole point of it existing. Before this, hiring dealers was a
 * convenience with a fee attached and no reason beyond time; now it is buying
 * somebody else's risk, and that is a decision worth making.
 *
 * <h2>What decides it</h2>
 *
 * The odds are in {@link TrapMath#stickupChance} and the squad in
 * {@link TrapMath#stickupSquad}. Rep is the biggest lever on both -- a name
 * people know is a name people come for -- with carried heat, the size of the
 * handover and the grade of what changed hands behind it. Daylight and company
 * cut the odds, matching Paranoia's company rule so there is one lesson to
 * learn rather than two.
 *
 * A dealer's sales are deliberately exempt. That is what you are paying them
 * for.
 */
public final class TrapStickup {
    /**
     * Quiet period per player after one lands.
     *
     * A customer buys eight at a time in as many clicks, and a roll per click
     * would mean one unlucky handover became four raids stacked on top of each
     * other. One visit per deal, at most one visit per this.
     */
    private static final int COOLDOWN_TICKS = 20 * 60 * 6;
    /**
     * How close they come out of the dark.
     *
     * Much nearer than the farm raid's 34-50. That one masses at the edge of a
     * field and walks in; this one is an ambush, and an ambush you can see
     * forming from fifty blocks away is a patrol.
     */
    private static final int NEAR = 13;
    private static final int SPREAD = 8;

    private static final Map<UUID, Long> LAST = new HashMap<>();

    private TrapStickup() {
    }

    /**
     * Roll for a deal that just happened in person.
     *
     * @param units      how much changed hands in this exchange
     * @param gradeIndex the grade handed over
     */
    public static void afterDeal(ServerPlayerEntity player, int units, int gradeIndex) {
        if (player == null || units <= 0) {
            return;
        }
        ServerWorld world = player.getWorld();
        Long last = LAST.get(player.getUuid());
        if (last != null && world.getTime() - last < COOLDOWN_TICKS) {
            return;
        }
        int rep = TrapContracts.repOf(TrapContracts.findPhone(player));
        int heat = TrapHeat.carryingHeat(player);
        float chance = TrapMath.stickupChance(heat, rep, units, gradeIndex,
                alone(player), night(world));
        if (world.getRandom().nextFloat() >= chance) {
            return;
        }
        LAST.put(player.getUuid(), world.getTime());
        jump(player, TrapMath.stickupSquad(rep, heat, gradeIndex, units));
    }

    /** Nobody else within the same range Paranoia counts as company. */
    private static boolean alone(ServerPlayerEntity player) {
        for (var other : player.getWorld().getPlayers()) {
            if (other != player && other.isAlive()
                    && other.getBlockPos().isWithinDistance(player.getBlockPos(),
                    TrapParanoia.COMPANY_RANGE)) {
                return false;
            }
        }
        return true;
    }

    /** Dark outside, whatever the roof over your head says. */
    private static boolean night(ServerWorld world) {
        long time = world.getTimeOfDay() % 24000L;
        return time > 13000L && time < 23000L;
    }

    /**
     * Put them on the ground and point them at the player.
     *
     * Targeted explicitly rather than left to their own aggro: they came for
     * one person, and a squad that spawns behind you and then wanders off
     * looking for a village is not a robbery.
     */
    public static void jump(ServerPlayerEntity player, int[] squad) {
        ServerWorld world = player.getWorld();
        BlockPos where = player.getBlockPos();
        Random random = world.getRandom();

        List<MobEntity> crew = new ArrayList<>();
        crew.addAll(TrapHeat.spawn(world, where, random, EntityType.PILLAGER,
                squad[0], NEAR, SPREAD));
        crew.addAll(TrapHeat.spawn(world, where, random, EntityType.VINDICATOR,
                squad[1], NEAR, SPREAD));
        crew.addAll(TrapHeat.spawn(world, where, random, EntityType.RAVAGER,
                squad[2], NEAR, SPREAD));
        if (crew.isEmpty()) {
            // Nowhere to put them -- a cellar, a boat, the middle of an ocean.
            // Give the cooldown back rather than eating the roll silently.
            LAST.remove(player.getUuid());
            return;
        }
        for (MobEntity raider : crew) {
            raider.setTarget(player);
        }

        TrapAwards.grant(player, "followed");
        player.sendMessage(Text.literal("You were followed.")
                .formatted(Formatting.RED, Formatting.BOLD), false);
        player.sendMessage(Text.literal(crew.size() + " of them, and they know what you're "
                + "carrying.").formatted(Formatting.RED), false);

        world.playSound(null, where, SoundEvents.EVENT_RAID_HORN.value(),
                SoundCategory.HOSTILE, 1.0F, 0.75F);
        world.playSound(null, where, SoundEvents.ENTITY_PILLAGER_AMBIENT,
                SoundCategory.HOSTILE, 1.0F, 0.7F);
        world.spawnParticles(ParticleTypes.ANGRY_VILLAGER,
                player.getX(), player.getEyeY() + 0.6, player.getZ(), 18, 0.6, 0.4, 0.6, 0.02);
        // Dealing in person is what brought them, so it is also what keeps
        // bringing them: the visit itself is heat you now carry around.
        TrapHeat.addCarriedHeat(player, 1, 20 * 60 * 4);
    }

    /**
     * /stickup -- send one now.
     *
     * Same reason /raid exists. A one-in-twenty event that depends on four
     * inputs is untestable by playing, and "I dealt all evening and nothing
     * happened" is indistinguishable from a bug.
     */
    public static void registerCommands() {
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register(
                (dispatcher, access, env) -> dispatcher.register(
                        net.minecraft.server.command.CommandManager.literal("stickup")
                                .requires(source -> source.hasPermissionLevel(2))
                                .executes(context -> {
                                    ServerPlayerEntity player = context.getSource().getPlayer();
                                    if (player == null) {
                                        return 0;
                                    }
                                    int rep = TrapContracts.repOf(
                                            TrapContracts.findPhone(player));
                                    int heat = TrapHeat.carryingHeat(player);
                                    int[] squad = TrapMath.stickupSquad(rep, heat, 3, 8);
                                    context.getSource().sendFeedback(() -> Text.literal(
                                            "rep " + rep + ", heat " + heat + " -> "
                                                    + squad[0] + " pillagers, " + squad[1]
                                                    + " vindicators, " + squad[2] + " ravagers")
                                            .formatted(Formatting.GRAY), false);
                                    jump(player, squad);
                                    return 1;
                                })));
    }

    /** What the odds are for this player right now, for /heat to report. */
    public static int oddsPercent(ServerPlayerEntity player, int units, int gradeIndex) {
        return Math.round(100.0f * TrapMath.stickupChance(
                TrapHeat.carryingHeat(player),
                TrapContracts.repOf(TrapContracts.findPhone(player)),
                units, gradeIndex, alone(player), night(player.getWorld())));
    }
}
