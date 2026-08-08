package dev.heezq.trapcraft;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.mob.PillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.Difficulty;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;

import net.minecraft.server.network.ServerPlayerEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Big grows attract attention.
 *
 * Tuned to be a nuisance, not a disaster: it takes a genuinely large farm to
 * trip, you're warned before anything arrives, they spawn far enough away to
 * see coming, it's two pillagers rather than a raid, and it can't fire again
 * for ten minutes. Hiding the grow underground halves the heat, which is the
 * actual strategic choice this is here to create.
 */
public final class TrapHeat {
    private TrapHeat() {
    }

    /**
     * Horizontal scan radius around the plant that ticked.
     *
     * Was 4, which made the whole thing unable to tell a big farm from a small
     * one: a 9x9 window saturates at 162 heat whether the plantation around it
     * is 9x9 or 90x90, so there was nothing for the scale to read. 15x15 gives
     * a 450 ceiling and room for the tiers below to mean something.
     *
     * 225 lookups on 1-in-64 mature random ticks works out to a scan every
     * ~40 seconds on a busy farm, which is nothing.
     */
    public static final int RADIUS = 7;

    /** Only scan on 1 in N mature random ticks. */
    public static final int SCAN_CHANCE = 64;

    /**
     * Heat needed for each tier. Sky-exposed mature plants count 2, hidden 1.
     *
     * The old single threshold of 90 over a 9x9 needed 45 mature sky-exposed
     * plants standing AT ONCE -- a solid 7x7 block of them -- and harvesting
     * resets a plant to age 0, so in practice it never happened. These start
     * where a real small grow lives: tier 1 is about fifteen plants out in the
     * open, which is a plot you'd actually build.
     */
    public static final int[] THRESHOLDS = {28, 60, 108, 175};

    /**
     * Who turns up, per tier. Pillagers are ranged and cheap to disengage from;
     * vindicators close fast and are the real pressure; the ravager only shows
     * up for a genuine plantation, and it trampling your crops on the way in is
     * the point rather than a side effect.
     */
    private static final int[] PILLAGERS = {2, 3, 4, 4};
    private static final int[] VINDICATORS = {0, 1, 2, 3};
    private static final int[] RAVAGERS = {0, 0, 0, 1};

    /**
     * Cooldown per tier. A bigger operation is watched more closely, so the
     * scale drives how OFTEN as well as how hard -- ten flat minutes meant a
     * plantation and a window box were equally interesting to the authorities.
     */
    private static final long[] COOLDOWN_TICKS = {
            20L * 60 * 8, 20L * 60 * 7, 20L * 60 * 5, 20L * 60 * 4};

    public static final int SPAWN_MIN = 34;
    public static final int SPAWN_RANGE = 16;                  // 34..50 blocks
    public static final int PLAYER_RANGE = 64;
    /** Positions tried per mob before giving up on it. */
    private static final int SPAWN_ATTEMPTS = 8;

    /**
     * Per-dimension, in memory only. Resetting on restart is fine and is the
     * safe direction to fail: worst case someone gets one extra visit.
     */
    private static final Map<RegistryKey<World>, Long> lastRaid = new HashMap<>();

    /** Called from a mature plant's random tick. Must stay cheap. */
    public static void onMatureTick(ServerWorld world, BlockPos pos, Random random) {
        if (random.nextInt(SCAN_CHANCE) != 0) {
            return;
        }
        if (world.getDifficulty() == Difficulty.PEACEFUL) {
            return;
        }

        // Somebody has to be around to be raided; don't spawn mobs at an
        // unattended farm in a loaded chunk.
        PlayerEntity nearby = world.getClosestPlayer(
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, PLAYER_RANGE, false);
        if (nearby == null) {
            return;
        }

        int tier = tierFor(measureHeat(world, pos));
        if (tier < 0) {
            return;
        }

        // Cooldown is checked AFTER the tier is known, because the tier decides
        // how long it is. Checking first would have applied the smallest farm's
        // cooldown to the biggest one.
        long now = world.getTime();
        Long last = lastRaid.get(world.getRegistryKey());
        if (last != null && now - last < COOLDOWN_TICKS[tier]) {
            return;
        }

        lastRaid.put(world.getRegistryKey(), now);
        warn(world, pos, tier);
        spawnPatrol(world, pos, random, tier);
    }

    /** Guide-book copy, built from the arrays so the book can't disagree. */
    public static String squadOf(int tier) {
        StringBuilder out = new StringBuilder();
        out.append(PILLAGERS[tier]).append(" pillager");
        if (VINDICATORS[tier] > 0) {
            out.append(", ").append(VINDICATORS[tier]).append(" vind");
        }
        if (RAVAGERS[tier] > 0) {
            out.append(", ravager");
        }
        return out.toString();
    }

    public static long cooldownMinutes(int tier) {
        return COOLDOWN_TICKS[tier] / 20 / 60;
    }

    /** Highest tier this heat reaches, or -1 for "nobody cares yet". */
    private static int tierFor(int heat) {
        int tier = -1;
        for (int i = 0; i < THRESHOLDS.length; i++) {
            if (heat >= THRESHOLDS[i]) {
                tier = i;
            }
        }
        return tier;
    }

    /**
     * Heat you brought with you rather than grew.
     *
     * Taking a contract makes you interesting to the same people a big farm
     * does, but there is no farm to measure -- so it is carried on the player
     * and decays. In memory only, same as the raid cooldown: losing it on
     * restart fails in the player's favour.
     */
    private static final Map<UUID, long[]> CARRIED = new HashMap<>();

    /** Add {@code tiers} of heat to a player for {@code ticks}. */
    public static void addCarriedHeat(ServerPlayerEntity player, int tiers, int ticks) {
        CARRIED.put(player.getUuid(),
                new long[]{tiers, player.getWorld().getTime() + ticks});
    }

    /** Tiers of carried heat still in effect, 0 when clean. */
    public static int carryingHeat(ServerPlayerEntity player) {
        long[] entry = CARRIED.get(player.getUuid());
        if (entry == null) {
            return 0;
        }
        if (player.getWorld().getTime() >= entry[1]) {
            CARRIED.remove(player.getUuid());
            return 0;
        }
        return (int) entry[0];
    }

    /**
     * How hot it is where somebody is standing, -1 for "nobody cares yet".
     *
     * Paranoia reads this rather than the raid path: the raid fires from a
     * plant's random tick, which is far too rare and too tied to one block to
     * drive a meter that has to feel continuous.
     */
    public static int tierAt(ServerWorld world, BlockPos pos) {
        return tierFor(measureHeat(world, pos));
    }

    /** Counts mature cannabis on the plant's own layer. Open sky counts double. */
    private static int measureHeat(ServerWorld world, BlockPos centre) {
        int heat = 0;
        BlockPos.Mutable cursor = new BlockPos.Mutable();
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                cursor.set(centre.getX() + dx, centre.getY(), centre.getZ() + dz);
                var state = world.getBlockState(cursor);
                if (state.getBlock() instanceof CannabisCropBlock crop && crop.isMature(state)) {
                    heat += world.isSkyVisible(cursor) ? 2 : 1;
                }
            }
        }
        return heat;
    }

    /** Tells you roughly what's coming, so the tier is legible before it arrives. */
    private static final String[] WARNINGS = {
            "Somebody's been asking about your farm.",
            "Word's got round. They're coming for a look.",
            "Your operation's on somebody's list.",
            "They're bringing everything. Move the plants or hold the ground.",
    };

    private static void warn(ServerWorld world, BlockPos pos, int tier) {
        Text message = Text.literal(WARNINGS[tier])
                .formatted(Formatting.RED, Formatting.ITALIC);
        for (PlayerEntity player : world.getPlayers()) {
            if (player.getBlockPos().isWithinDistance(pos, PLAYER_RANGE)) {
                player.sendMessage(message, true);   // action bar, not chat spam
            }
        }
        // Pitch drops as the tier climbs, so the horn itself tells you how bad
        // this one is before anything crests the hill.
        world.playSound(null, pos, SoundEvents.EVENT_RAID_HORN.value(),
                SoundCategory.HOSTILE, 0.7F + 0.1F * tier, 1.0F - 0.12F * tier);
    }

    private static void spawnPatrol(ServerWorld world, BlockPos pos, Random random, int tier) {
        java.util.List<net.minecraft.entity.mob.MobEntity> patrol = new java.util.ArrayList<>();
        patrol.addAll(spawn(world, pos, random, EntityType.PILLAGER, PILLAGERS[tier]));
        patrol.addAll(spawn(world, pos, random, EntityType.VINDICATOR, VINDICATORS[tier]));
        patrol.addAll(spawn(world, pos, random, EntityType.RAVAGER, RAVAGERS[tier]));
        // They didn't come for a fight, they came for the stash.
        TrapRaid.begin(world, pos, patrol);
    }

    private static java.util.List<net.minecraft.entity.mob.MobEntity> spawn(
            ServerWorld world, BlockPos pos, Random random,
            EntityType<? extends net.minecraft.entity.mob.MobEntity> type,
            int count) {
        java.util.List<net.minecraft.entity.mob.MobEntity> spawned = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            BlockPos spawn = findSpot(world, pos, random);
            if (spawn == null) {
                continue;   // nowhere safe within reach; drop this one
            }
            var mob = type.create(world, SpawnReason.EVENT);
            if (mob == null) {
                continue;
            }
            mob.refreshPositionAndAngles(spawn, random.nextFloat() * 360.0F, 0.0F);
            mob.setPersistent();   // don't despawn halfway across the field
            world.spawnEntity(mob);
            spawned.add(mob);
        }
        return spawned;
    }

    /**
     * A loaded, open surface position ringing the farm at spawn distance.
     *
     * Retries rather than skipping on the first bad roll -- with squads of up
     * to eight, a single attempt each meant a raid in hilly or built-up ground
     * quietly arrived at half strength.
     */
    private static BlockPos findSpot(ServerWorld world, BlockPos pos, Random random) {
        for (int attempt = 0; attempt < SPAWN_ATTEMPTS; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2;
            int distance = SPAWN_MIN + random.nextInt(SPAWN_RANGE);
            int x = pos.getX() + (int) (Math.cos(angle) * distance);
            int z = pos.getZ() + (int) (Math.sin(angle) * distance);

            // Only spawn into loaded chunks -- getTopY on an unloaded chunk
            // would force-generate terrain from a random tick.
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                continue;
            }
            BlockPos spot = new BlockPos(x, world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z), z);
            // Three clear blocks: a ravager is two high and the squads now
            // include one, and half-burying it would be worse than skipping it.
            if (world.getBlockState(spot).isAir()
                    && world.getBlockState(spot.up()).isAir()
                    && world.getBlockState(spot.up(2)).isAir()) {
                return spot;
            }
        }
        return null;
    }
}
