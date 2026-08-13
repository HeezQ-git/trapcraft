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
    public static final int RADIUS = 11;
    /**
     * How far up and down the scan reaches.
     *
     * It used to be a single flat slice at the plant's own Y, which meant a
     * grow on two floors read as two unrelated small grows and neither ever
     * got hot. A basement under a field is one operation and the authorities
     * would treat it as one.
     */
    public static final int HEIGHT = 5;

    /** Only scan on 1 in N mature random ticks. */
    public static final int SCAN_CHANCE = 24;

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
    private static final int[] PILLAGERS = {2, 4, 5, 6};
    private static final int[] VINDICATORS = {1, 2, 3, 4};
    private static final int[] RAVAGERS = {0, 0, 1, 2};

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

    /**
     * /heat tells you where you stand. /raid sends one now.
     *
     * The diagnostic exists because "nothing is happening" and "it is broken"
     * are the same thing from inside the game, and the only way to tell them
     * apart was reading the source. It reports the SAME measureHeat the raid
     * uses, so it cannot disagree with reality.
     */
    public static void registerCommands() {
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register(
                (dispatcher, access, env) -> {
                    dispatcher.register(net.minecraft.server.command.CommandManager
                            .literal("heat")
                            .executes(context -> report(context.getSource())));
                    dispatcher.register(net.minecraft.server.command.CommandManager
                            .literal("raid")
                            .requires(source -> source.hasPermissionLevel(2))
                            .executes(context -> send(context.getSource(), 0))
                            .then(net.minecraft.server.command.CommandManager
                                    .argument("tier", com.mojang.brigadier.arguments
                                            .IntegerArgumentType.integer(0, 3))
                                    .executes(context -> send(context.getSource(),
                                            com.mojang.brigadier.arguments.IntegerArgumentType
                                                    .getInteger(context, "tier")))));
                });
    }

    private static int report(net.minecraft.server.command.ServerCommandSource source) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        ServerWorld world = player.getWorld();
        BlockPos where = player.getBlockPos();
        int heat = measureHeat(world, where);
        int tier = tierFor(heat);
        long cooling = cooldownLeft(world, tier);

        var out = Text.literal("Uwaga policji  ").formatted(Formatting.GOLD, Formatting.BOLD)
                .append(Text.literal(heat + " pkt tutaj").formatted(
                        tier < 0 ? Formatting.GREEN : Formatting.RED))
                .append(Text.literal("   (" + RADIUS * 2 + " bloków wszerz, "
                                + HEIGHT * 2 + " w pionie)").formatted(Formatting.DARK_GRAY))
                .append(Text.literal("\n  " + (tier < 0
                                ? "Nic wartego wizyty. Pierwszy próg przy " + THRESHOLDS[0] + "."
                                : "Próg " + (tier + 1) + " -- " + squadOf(tier)))
                        .formatted(tier < 0 ? Formatting.GRAY : Formatting.RED))
                .append(Text.literal("\n  Progi: " + joinInts(THRESHOLDS))
                        .formatted(Formatting.DARK_GRAY))
                .append(Text.literal("\n  Dojrzała na widoku 3, ukryta 2, rosnąca "
                        + "na widoku 1, pełna suszarka 1.")
                        .formatted(Formatting.DARK_GRAY));
        if (cooling > 0) {
            out.append(Text.literal("\n  Przerwa jeszcze przez "
                    + cooling / 20 / 60 + "m " + cooling / 20 % 60 + "s.")
                    .formatted(Formatting.AQUA));
        }
        // The other way they come for you, which has nothing to do with the
        // farm underneath your feet and everything to do with who you are.
        int odds = TrapStickup.oddsPercent(player, 8, 3);
        out.append(Text.literal("\n  Sprzedaż z ręki: około " + odds
                        + "% szans, że ósemka towaru ściągnie napad.")
                .formatted(odds >= 12 ? Formatting.RED : Formatting.DARK_GRAY));
        player.sendMessage(out, false);
        return 1;
    }

    private static int send(net.minecraft.server.command.ServerCommandSource source, int tier) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        force(player.getWorld(), player.getBlockPos(), tier);
        source.sendFeedback(() -> Text.literal("Wysłano patrol progu " + (tier + 1)
                + ": " + squadOf(tier)).formatted(Formatting.RED), false);
        return 1;
    }

    private static String joinInts(int[] values) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            out.append(i > 0 ? " / " : "").append(values[i]);
        }
        return out.toString();
    }

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

        int heat = measureHeat(world, pos);
        int tier = tierFor(heat);
        if (tier < 0) {
            return;
        }

        // Cooldown is checked AFTER the tier is known, because the tier decides
        // how long it is. Checking first would have applied the smallest farm's
        // cooldown to the biggest one.
        long now = world.getTime();
        Long last = lastRaid.get(world.getRegistryKey());
        if (last != null && now - last < cooldownFor(tier, heat)) {
            return;
        }

        lastRaid.put(world.getRegistryKey(), now);
        warn(world, pos, tier);
        spawnPatrol(world, pos, random, tier);
    }

    /** Guide-book copy, built from the arrays so the book can't disagree. */
    public static String squadOf(int tier) {
        StringBuilder out = new StringBuilder();
        out.append(PILLAGERS[tier]).append(" grabieżców");
        if (VINDICATORS[tier] > 0) {
            out.append(", ").append(VINDICATORS[tier]).append(" mścicieli");
        }
        if (RAVAGERS[tier] > 0) {
            out.append(", niszczyciel");
        }
        return out.toString();
    }

    public static long cooldownMinutes(int tier) {
        return COOLDOWN_TICKS[tier] / 20 / 60;
    }

    /** The least time that may ever pass between two visits. */
    private static final long COOLDOWN_FLOOR = 20L * 90;

    /**
     * How long until the next visit, given how far PAST the top tier you are.
     *
     * The tier ladder stops at {@link #THRESHOLDS}, so before this there was
     * no difference at all between an operation at the cap and one at four
     * times the cap -- both got the same squad on the same four-minute clock,
     * and the correct play was therefore to build the biggest grow you could
     * physically fit, because nothing past 175 heat cost you anything.
     *
     * Now the cooldown shortens in proportion. Twice the cap is twice as
     * often, three times is three times, down to a floor of ninety seconds so
     * a mega-farm is under near-constant pressure rather than under an
     * unsurvivable one. The tier -- who turns up -- is unchanged; this is
     * purely how often they do.
     */
    private static long cooldownFor(int tier, int heat) {
        // The Watch. A city that has paid for patrols of its own gets fewer
        // of the other sort -- which is the first thing the purse buys that
        // the growers care about, and deliberately so.
        long base = TrapCity.built(TrapCity.Work.WATCH)
                ? Math.round(COOLDOWN_TICKS[tier] * TrapCity.WATCH_COOLDOWN)
                : COOLDOWN_TICKS[tier];
        int cap = THRESHOLDS[THRESHOLDS.length - 1];
        if (tier < THRESHOLDS.length - 1 || heat <= cap) {
            return base;
        }
        return Math.max(COOLDOWN_FLOOR, Math.round(base * (cap / (double) heat)));
    }

    /** For /heat, so a player can see what their size is actually costing. */
    public static long cooldownMinutesAt(int heat) {
        int tier = tierFor(heat);
        return tier < 0 ? 0 : cooldownFor(tier, heat) / 20 / 60;
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
    /**
     * How obvious this operation is from outside.
     *
     * Counts three things, because one was never enough. MATURE PLANTS are the
     * headline, and sky-visible ones count double -- a field anybody can see
     * from the air is the giveaway. DRYING RACKS count too: a wall of them is
     * as damning as the plants and, until now, was completely invisible to
     * this. And it reaches five blocks up and down, so a basement under a
     * field reads as one operation instead of two innocent ones.
     *
     * Public so /heat can show a player the same number this uses. A
     * diagnostic that computes its own answer is a diagnostic that lies.
     */
    /**
     * What running both lines in one place costs you.
     *
     * A field of weed is a farm somebody might explain away. A field of weed
     * with the coca line running through it is an OPERATION, and the whole
     * point of heat is that the size of the thing is what gets noticed. Coca
     * used to be worth nothing at all here -- a full refinery generated
     * literally zero heat -- so the safest possible layout was to put the
     * expensive half of the mod next to the cheap half and let the cheap half
     * take all the risk.
     *
     * Applied only when BOTH are present, so a pure coca grower and a pure
     * weed grower are each measured on their own merits and only the person
     * doing both pays for doing both.
     *
     * Compounds per extra line since the poppy arrived: two trades in one
     * place is 1.35x, three is 1.35 squared. Somebody running the whole mod
     * off one plot is not doing something 35% more obvious than somebody
     * running two thirds of it.
     */
    public static final float MIXED_TRADE = 1.35f;

    public static int measureHeat(ServerWorld world, BlockPos centre) {
        int weed = 0;
        int coca = 0;
        int poppy = 0;
        BlockPos.Mutable cursor = new BlockPos.Mutable();
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                for (int dy = -HEIGHT; dy <= HEIGHT; dy++) {
                    cursor.set(centre.getX() + dx, centre.getY() + dy, centre.getZ() + dz);
                    var state = world.getBlockState(cursor);
                    if (state.getBlock() instanceof CannabisCropBlock crop) {
                        // Seedlings count too. A tilled field full of young
                        // plants is just as obvious from the air as a ripe
                        // one, and counting only mature ones meant a big
                        // plantation read as almost nothing for most of its
                        // life -- doubly so since growth was slowed.
                        boolean seen = world.isSkyVisible(cursor);
                        if (crop.isMature(state)) {
                            weed += seen ? 3 : 2;
                        } else {
                            weed += seen ? 1 : 0;
                        }
                    } else if (state.getBlock() instanceof CocaCropBlock bush) {
                        boolean seen = world.isSkyVisible(cursor);
                        coca += bush.isMature(state) ? (seen ? 3 : 2) : (seen ? 1 : 0);
                    } else if (state.getBlock() instanceof PoppyCropBlock flower) {
                        // Worth more per plant than either of the others, and
                        // that is forced rather than chosen: a poppy needs
                        // light 12 to grow at all, so a poppy field is a thing
                        // under the sky by definition. The one crop you cannot
                        // hide should be the one that costs the most to have.
                        boolean seen = world.isSkyVisible(cursor);
                        poppy += flower.isMature(state) ? (seen ? 4 : 3) : (seen ? 2 : 1);
                    } else if (state.getBlock() instanceof DryingRackBlock
                            && state.get(DryingRackBlock.OCCUPIED)) {
                        weed += 1;
                    } else if (state.getBlock() instanceof RefinerBlock
                            || state.getBlock() instanceof LeafPressBlock) {
                        // The machinery is as damning as the plants. A shed of
                        // presses is not a hobby.
                        coca += 2;
                    } else if (state.getBlock() instanceof ScoringTableBlock
                            || state.getBlock() instanceof WashPotBlock
                            || state.getBlock() instanceof AcetylatorBlock) {
                        poppy += 2;
                    }
                }
            }
        }
        int total = weed + coca + poppy;
        int lines = (weed > 0 ? 1 : 0) + (coca > 0 ? 1 : 0) + (poppy > 0 ? 1 : 0);
        return lines > 1
                ? Math.round(total * (float) Math.pow(MIXED_TRADE, lines - 1))
                : total;
    }

    /** What tier this heat reaches, or -1. Public for the diagnostic. */
    public static int tierOf(int heat) {
        return tierFor(heat);
    }

    /** Ticks until this dimension could be raided again, or 0. */
    public static long cooldownLeft(ServerWorld world, int tier) {
        Long last = lastRaid.get(world.getRegistryKey());
        if (last == null || tier < 0) {
            return 0;
        }
        return Math.max(0, COOLDOWN_TICKS[tier] - (world.getTime() - last));
    }

    /**
     * Word gets around.
     *
     * Product moving on the street brings the next patrol forward. Not a raid
     * on its own -- you still need a grow worth raiding -- but it means a big
     * dealing operation is raided more often than a quiet one, which is the
     * connection between having dealers and having a problem.
     */
    public static void stirTheStreet(ServerWorld world, int itemsSold) {
        Long last = lastRaid.get(world.getRegistryKey());
        if (last == null) {
            return;
        }
        // Each item shifted ages the cooldown by an extra two seconds.
        lastRaid.put(world.getRegistryKey(), last - itemsSold * 40L);
    }

    /** Send one now, ignoring heat and cooldown. For /raid. */
    public static void force(ServerWorld world, BlockPos pos, int tier) {
        int clamped = Math.max(0, Math.min(THRESHOLDS.length - 1, tier));
        lastRaid.put(world.getRegistryKey(), world.getTime());
        warn(world, pos, clamped);
        spawnPatrol(world, pos, world.getRandom(), clamped);
    }

    /** Tells you roughly what's coming, so the tier is legible before it arrives. */
    private static final String[] WARNINGS = {
            "Ktoś wypytywał o twoją plantację.",
            "Rozniosło się. Przyjdą się rozejrzeć.",
            "Twój interes trafił na czyjąś listę.",
            "Idą z całą siłą. Przenieś rośliny albo broń terenu.",
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
        return spawn(world, pos, random, type, count, SPAWN_MIN, SPAWN_RANGE);
    }

    /**
     * Put armed raiders on the ground in a ring around a point.
     *
     * Shared with {@link TrapStickup}, which ambushes at close range rather
     * than massing at the edge of a field. Shared rather than copied because
     * the initialize() call below is the single most important line in the
     * mod's combat and it has already been forgotten once.
     */
    public static java.util.List<net.minecraft.entity.mob.MobEntity> spawn(
            ServerWorld world, BlockPos pos, Random random,
            EntityType<? extends net.minecraft.entity.mob.MobEntity> type,
            int count, int near, int spread) {
        java.util.List<net.minecraft.entity.mob.MobEntity> spawned = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            BlockPos spawn = findSpot(world, pos, random, near, spread);
            if (spawn == null) {
                continue;   // nowhere safe within reach; drop this one
            }
            var mob = type.create(world, SpawnReason.EVENT);
            if (mob == null) {
                continue;
            }
            mob.refreshPositionAndAngles(spawn, random.nextFloat() * 360.0F, 0.0F);
            // initialize() is what arms them. Without it a pillager spawns
            // holding nothing at all -- no crossbow, no armour, no captain --
            // and a raid of unarmed pillagers is a mob of people jogging at
            // you. This is the whole reason raids were not frightening.
            mob.initialize(world, world.getLocalDifficulty(spawn),
                    SpawnReason.EVENT, null);
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
        return findSpot(world, pos, random, SPAWN_MIN, SPAWN_RANGE);
    }

    private static BlockPos findSpot(ServerWorld world, BlockPos pos, Random random,
                                     int near, int spread) {
        for (int attempt = 0; attempt < SPAWN_ATTEMPTS; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2;
            int distance = near + random.nextInt(Math.max(1, spread));
            int x = pos.getX() + (int) (Math.cos(angle) * distance);
            int z = pos.getZ() + (int) (Math.sin(angle) * distance);

            // Only spawn into loaded chunks -- getTopY on an unloaded chunk
            // would force-generate terrain from a random tick.
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                continue;
            }
            // MOTION_BLOCKING, not WORLD_SURFACE: the latter counts grass and
            // flowers, so on any meadow it points a block into the air with a
            // plant for a floor -- which standable() now correctly refuses,
            // and a raid that finds nowhere to stand is a raid that never
            // arrives.
            int surface = world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z);
            BlockPos spot = new BlockPos(x, surface, z);
            if (standable(world, spot)) {
                return spot;
            }
            // Underground: a basement grow, a cellar, a deal done in a mine.
            // Putting the squad on the roof thirty blocks up is the same thing
            // as not sending one -- you hear the horn and nothing ever
            // arrives, which is exactly what a broken raid looks like. Only
            // when the reference really is buried, so a surface raid is
            // unaffected.
            if (Math.abs(surface - pos.getY()) > 10) {
                for (int y = pos.getY() + 2; y >= pos.getY() - 6; y--) {
                    BlockPos under = new BlockPos(x, y, z);
                    if (standable(world, under)) {
                        return under;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Three clear blocks, and a floor under them.
     *
     * A ravager is two high and the squads include one, and half-burying it
     * would be worse than skipping it. The floor is the newer half: three
     * blocks of air is also what the top of a lava lake looks like, and a
     * heightmap will hand you one of those without comment.
     */
    private static boolean standable(ServerWorld world, BlockPos spot) {
        return TrapSpawn.safe(world, spot, 3);
    }
}
