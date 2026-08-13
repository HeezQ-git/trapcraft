package dev.heezq.trapcraft;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Running a grow op makes the world feel like it's watching you.
 *
 * Everything here is a lie told to one client through {@link TrapPhantom} --
 * nothing spawns, nothing damages, nothing touches the world. That is a hard
 * rule and not a stylistic one: a scare mechanic that can actually kill you
 * stops being atmosphere and starts being a reason to turn the mod off.
 *
 * The meter is driven mostly by Heat, so it is really a readout of how exposed
 * your operation is. Being high multiplies it, darkness and night add to it,
 * and standing near another player cuts it hard -- see
 * {@link TrapMath#pressure}, where the ordering of that last term matters more
 * than it looks.
 */
public final class TrapParanoia {
    /** Recompute once a second. Ticking illusions at 20Hz is unbearable. */
    private static final int PERIOD = 20;
    /** Heat costs 225 block lookups, so re-measure it far less often. */
    private static final int HEAT_PERIOD = 100;

    public static final float MAX = 100.0f;
    /** Points per second at full pressure, and shed per second at none. */
    public static final float RISE = 1.6f;
    public static final float FALL = 2.4f;

    /** Another player this close cuts your pressure. */
    public static final int COMPANY_RANGE = 48;

    /** Meter value at which each tier begins. Tier 0 is "fine". */
    public static final int[] TIERS = {25, 50, 75, 92};

    /** No illusions for a minute after you respawn. */
    private static final long GRACE_TICKS = 20L * 60;

    private static final Map<UUID, Float> METER = new HashMap<>();
    private static final Map<UUID, Integer> LAST_TIER = new HashMap<>();
    private static final Map<UUID, Integer> HEAT_TIER = new HashMap<>();
    private static final Map<UUID, Vec3d> LAST_POS = new HashMap<>();
    private static final Map<UUID, Long> IMMUNE_UNTIL = new HashMap<>();
    /** Meter pinned by /paranoia &lt;level&gt;, so a forced tier doesn't drain away. */
    private static final Map<UUID, Long> HELD_UNTIL = new HashMap<>();
    private static final long HOLD_TICKS = 20L * 120;
    private static final Set<UUID> OPTED_OUT = new HashSet<>();

    /** The one figure a player can be haunted by at a time. */
    private record Figure(int id, Vec3d at, long removeAtTick) {
    }

    /** Close enough to make it out properly. */
    private static final double WATCHER_FLEE_RANGE = 20.0;
    /** cos(~10 degrees): looking near enough at it to be sure. */
    private static final double WATCHER_LOOK_DOT = 0.985;

    private static final Map<UUID, Figure> FIGURES = new HashMap<>();

    /**
     * The nerve bar.
     *
     * A boss bar rather than the actionbar or a client HUD: the actionbar is
     * already carrying the contract countdown and tier warnings, and a real HUD
     * element next to the hearts would need the optional client module, so only
     * modded players would ever see their own meter. A boss bar is server-side,
     * renders on a stock client, and is the only always-visible gauge vanilla
     * gives a server.
     *
     * It reads as SANITY -- full and green when you're fine, draining to red --
     * because a bar that fills up as things get worse reads as progress toward
     * something good.
     */
    private static final Map<UUID, ServerBossBar> BARS = new HashMap<>();

    private static final String[] TIER_WORDS = {
            "You feel watched.",
            "Coś się poruszyło. Jesteś tego pewien.",
            "Nie wiesz już, co jest prawdziwe.",
            "Oni tu są. Są tu już od dłuższego czasu.",
    };

    private TrapParanoia() {
    }

    public static void register() {
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED
                .register(TrapParanoia::loadOptOuts);

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            long time = server.getOverworld().getTime();
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                if (time % HEAT_PERIOD == 0) {
                    refreshHeat(player);
                }
                if (time % PERIOD == 0) {
                    tick(player);
                }
                expireFigure(player, time);
            }
        });

        // Dying and immediately being haunted reads as the game kicking you
        // while you're down, so a fresh spawn gets a minute of quiet.
        net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents.AFTER_RESPAWN.register(
                (oldPlayer, newPlayer, alive) -> grace(newPlayer));

        // A boss bar holds a reference to its viewers, so one left attached to a
        // player who logged out leaks and follows them back in on relog.
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register(
                (handler, server) -> {
                    ServerPlayerEntity player = handler.getPlayer();
                    ServerBossBar bar = BARS.remove(player.getUuid());
                    if (bar != null) {
                        bar.removePlayer(player);
                    }
                    UUID id = player.getUuid();
                    METER.remove(id);
                    LAST_TIER.remove(id);
                    FIGURES.remove(id);
                    HEAT_TIER.remove(id);
                    LAST_POS.remove(id);
                    IMMUNE_UNTIL.remove(id);
                    HELD_UNTIL.remove(id);
                });

        CommandRegistrationCallback.EVENT.register((dispatcher, access, env) ->
                dispatcher.register(CommandManager.literal("paranoia")
                        .executes(context -> toggle(context.getSource().getPlayer()))
                        // Ops can jump the meter straight to a value. Tier 3 and
                        // 4 are otherwise an hour of farming away, which is how
                        // they went untested long enough to ship broken.
                        // The escape hatch. Nothing here writes to the world, so
                        // anything odd on screen is a client that has been told
                        // something untrue -- this restates the truth for every
                        // block we ever lied about, without a relog.
                        .then(CommandManager.literal("clear")
                                .executes(context -> clear(context.getSource().getPlayer())))
                        .then(CommandManager.argument("level",
                                        com.mojang.brigadier.arguments.IntegerArgumentType
                                                .integer(0, (int) MAX))
                                .requires(source -> source.hasPermissionLevel(2))
                                .executes(context -> set(
                                        context.getSource().getPlayer(),
                                        com.mojang.brigadier.arguments.IntegerArgumentType
                                                .getInteger(context, "level"))))));
    }

    // --- the meter ------------------------------------------------------------

    private static void refreshHeat(ServerPlayerEntity player) {
        HEAT_TIER.put(player.getUuid(),
                TrapHeat.tierAt(player.getWorld(), player.getBlockPos()));
    }

    private static void tick(ServerPlayerEntity player) {
        UUID id = player.getUuid();
        if (player.isSpectator() || OPTED_OUT.contains(id)) {
            METER.put(id, 0.0f);
            return;
        }

        ServerWorld world = player.getWorld();
        long now = world.getTime();
        Long immuneUntil = IMMUNE_UNTIL.get(id);
        boolean immune = immuneUntil != null && now < immuneUntil;

        Long heldUntil = HELD_UNTIL.get(id);
        if (heldUntil != null && now < heldUntil) {
            // Pinned by the debug command: still haunt, just don't drift.
            float held = METER.getOrDefault(id, 0.0f);
            updateBar(player, held);
            haunt(player, tierOf(held), world.getRandom());
            LAST_POS.put(id, player.getPos());
            return;
        }

        float pressure = immune ? 0.0f : pressureFor(player);
        float current = METER.getOrDefault(id, 0.0f);
        // Pressure is a TARGET, not a direction. Half-pressure settles you at
        // half the meter and holds there; it does not creep to the top just
        // because it is above zero. See TrapMath#approach.
        float next = Math.max(0.0f, Math.min(MAX,
                TrapMath.approach(current, pressure * MAX, RISE, FALL)));
        METER.put(id, next);

        announce(player, tierOf(next));
        updateBar(player, next);
        if (!immune) {
            haunt(player, tierOf(next), world.getRandom());
        }
        LAST_POS.put(id, player.getPos());
    }

    private static float pressureFor(ServerPlayerEntity player) {
        ServerWorld world = player.getWorld();
        BlockPos pos = player.getBlockPos();

        // tierAt gives -1 for "nobody cares", so shift into 0..THRESHOLDS.length.
        // Carried heat from an accepted contract stacks on top: a delivery run
        // is supposed to feel like the walk home with something in your bag.
        int heat = Math.min(TrapHeat.THRESHOLDS.length,
                HEAT_TIER.getOrDefault(player.getUuid(), -1) + 1
                        + TrapHeat.carryingHeat(player));

        int amplifier = Math.max(
                amplifierOf(player, TrapContent.bakedEffect),
                amplifierOf(player, TrapContent.wiredEffect));

        return TrapMath.pressure(heat, TrapHeat.THRESHOLDS.length, amplifier,
                world.getLightLevel(pos), !world.isDay(), isAlone(player));
    }

    /** 0 when the effect is absent, otherwise level (I = 1, II = 2, ...). */
    private static int amplifierOf(ServerPlayerEntity player,
                                   net.minecraft.registry.entry.RegistryEntry<
                                           net.minecraft.entity.effect.StatusEffect> effect) {
        if (effect == null) {
            return 0;
        }
        StatusEffectInstance instance = player.getStatusEffect(effect);
        return instance == null ? 0 : instance.getAmplifier() + 1;
    }

    private static boolean isAlone(ServerPlayerEntity player) {
        for (ServerPlayerEntity other : player.getWorld().getPlayers()) {
            if (other != player && !other.isSpectator()
                    && other.squaredDistanceTo(player) <= COMPANY_RANGE * COMPANY_RANGE) {
                return false;
            }
        }
        return true;
    }

    /** 0 for calm, 1..4 for the tiers. */
    public static int tierOf(float meter) {
        int tier = 0;
        for (int i = 0; i < TIERS.length; i++) {
            if (meter >= TIERS[i]) {
                tier = i + 1;
            }
        }
        return tier;
    }

    /**
     * Draw the nerve bar, or take it away when there is nothing to report.
     *
     * Hidden at zero on purpose: a permanent full green bar is screen furniture
     * that people stop seeing, and the bar appearing is itself the first hint
     * that something has started.
     */
    private static void updateBar(ServerPlayerEntity player, float meter) {
        UUID id = player.getUuid();
        ServerBossBar bar = BARS.get(id);

        if (meter <= 0.5f) {
            if (bar != null) {
                bar.removePlayer(player);
                BARS.remove(id);
            }
            return;
        }

        int tier = tierOf(meter);
        float sanity = 1.0f - meter / MAX;
        if (bar == null) {
            bar = new ServerBossBar(Text.empty(), BossBar.Color.GREEN, BossBar.Style.NOTCHED_10);
            bar.addPlayer(player);
            BARS.put(id, bar);
        }
        bar.setPercent(Math.max(0.0f, Math.min(1.0f, sanity)));
        bar.setColor(BAR_COLOURS[tier]);
        bar.setName(Text.literal("Nerwy")
                .formatted(Formatting.GRAY)
                .append(Text.literal("  " + BAR_WORDS[tier]).formatted(BAR_TEXT[tier])));
    }

    private static final BossBar.Color[] BAR_COLOURS = {
            BossBar.Color.GREEN, BossBar.Color.GREEN, BossBar.Color.YELLOW,
            BossBar.Color.RED, BossBar.Color.PURPLE,
    };
    private static final Formatting[] BAR_TEXT = {
            Formatting.GREEN, Formatting.GREEN, Formatting.YELLOW,
            Formatting.RED, Formatting.LIGHT_PURPLE,
    };
    private static final String[] BAR_WORDS = {
            "steady", "twitchy", "watched", "unravelling", "gone",
    };

    /** Drop the bar entirely -- logout, opt-out, or a tonic. */
    private static void clearBar(ServerPlayerEntity player) {
        ServerBossBar bar = BARS.remove(player.getUuid());
        if (bar != null) {
            bar.removePlayer(player);
        }
    }

    private static void announce(ServerPlayerEntity player, int tier) {
        UUID id = player.getUuid();
        int previous = LAST_TIER.getOrDefault(id, 0);
        if (tier == previous) {
            return;
        }
        LAST_TIER.put(id, tier);
        // Only on the way up. Being told you're calming down breaks the spell.
        if (tier > previous) {
            player.sendMessage(Text.literal(TIER_WORDS[tier - 1])
                    .formatted(Formatting.DARK_GRAY, Formatting.ITALIC), true);
        }
    }

    // --- the illusions --------------------------------------------------------

    private static void haunt(ServerPlayerEntity player, int tier, Random random) {
        if (tier >= 1 && random.nextInt(8) == 0) {
            behindYou(player, random);
        }
        if (tier >= 2) {
            if (random.nextInt(6) == 0) {
                footstep(player);
            }
            if (random.nextInt(10) == 0) {
                phantomFlame(player, random);
            }
        }
        if (tier >= 3 && random.nextInt(6) == 0) {
            flicker(player, random);
        }
        if (tier >= 4 && random.nextInt(14) == 0) {
            watcher(player, random);
        }
    }

    private static final SoundEvent[] BEHIND = {
            SoundEvents.BLOCK_WOODEN_DOOR_OPEN,
            SoundEvents.BLOCK_CHEST_OPEN,
            SoundEvents.BLOCK_BAMBOO_BREAK,
            SoundEvents.ENTITY_ITEM_FRAME_BREAK,
    };

    /** A noise from a place you are not looking. */
    private static void behindYou(ServerPlayerEntity player, Random random) {
        Vec3d back = player.getRotationVec(1.0F).multiply(-(4 + random.nextInt(5)));
        Vec3d at = player.getPos().add(back).add(random.nextGaussian(), 0.0, random.nextGaussian());
        TrapPhantom.sound(player, at, BEHIND[random.nextInt(BEHIND.length)], 0.5F,
                0.85F + random.nextFloat() * 0.2F);
    }

    /** Something walking where you were standing a second ago. */
    private static void footstep(ServerPlayerEntity player) {
        Vec3d was = LAST_POS.get(player.getUuid());
        if (was == null || was.squaredDistanceTo(player.getPos()) < 1.0) {
            return;
        }
        TrapPhantom.sound(player, was, SoundEvents.ENTITY_PLAYER_BIG_FALL, 0.18F, 1.4F);
    }

    /** A flame with nothing burning under it. */
    private static void phantomFlame(ServerPlayerEntity player, Random random) {
        Vec3d at = player.getPos()
                .add(random.nextGaussian() * 5.0, 1.0 + random.nextGaussian(),
                        random.nextGaussian() * 5.0);
        TrapPhantom.particles(player, ParticleTypes.SMALL_FLAME, at, 2, 0.05, 0.0);
    }

    /**
     * What a block is briefly mistaken for.
     *
     * Full opaque cubes only, and nothing with a block entity. A chest or a
     * mob head has a different collision shape from the block it replaces, so
     * any imperfection in the revert leaves a wrongly-shaped outline hanging in
     * the air -- and a chest additionally implies a container the client will
     * try to open. Swapping one full cube for another cannot change the shape
     * at all, so the worst case is a wrong texture until the next chunk update.
     */
    private static final BlockState[] WRONG = {
            Blocks.SOUL_SAND.getDefaultState(),
            Blocks.MAGMA_BLOCK.getDefaultState(),
            Blocks.SCULK.getDefaultState(),
            Blocks.COAL_BLOCK.getDefaultState(),
            Blocks.MOSS_BLOCK.getDefaultState(),
    };

    /** How long a wrong block stays wrong. */
    private static final int FLICKER_TICKS = 40;

    /** A block that is briefly the wrong block. */
    private static void flicker(ServerPlayerEntity player, Random random) {
        BlockPos target = solidInView(player, random);
        if (target != null) {
            TrapPhantom.fakeBlock(player, target,
                    WRONG[random.nextInt(WRONG.length)], FLICKER_TICKS);
        }
    }

    /**
     * A solid block the player can plausibly see, or null if there isn't one.
     *
     * Searches along the look vector rather than in a box around the player.
     * The box version picked mostly air whenever you were outdoors and bailed
     * out, and on the rare hit it could just as easily land behind your head --
     * so the effect that is supposed to be the centrepiece of tier three almost
     * never actually appeared in front of anyone.
     */
    private static BlockPos solidInView(ServerPlayerEntity player, Random random) {
        Vec3d eye = player.getEyePos();
        Vec3d look = player.getRotationVec(1.0F);
        BlockPos feet = player.getBlockPos();

        for (int attempt = 0; attempt < 12; attempt++) {
            double distance = 3.0 + random.nextDouble() * 6.0;
            Vec3d probe = eye.add(look.multiply(distance)).add(
                    (random.nextDouble() - 0.5) * 4.0,
                    (random.nextDouble() - 0.5) * 3.0,
                    (random.nextDouble() - 0.5) * 4.0);
            BlockPos pos = BlockPos.ofFloored(probe);

            // Never the block holding you up or the one you're stood in -- a
            // fake block underfoot reads as a physics bug, not a hallucination.
            if (pos.equals(feet) || pos.equals(feet.down()) || pos.equals(feet.up())) {
                continue;
            }
            if (canLieAbout(player.getWorld().getBlockState(pos))) {
                return pos;
            }
        }
        return null;
    }

    /**
     * Whether a block may be misrepresented.
     *
     * Full opaque cubes only, and never anything holding a block entity. Two
     * separate reasons, both learned the hard way:
     *
     * Shape -- swapping a snow layer or a plant for a full cube changes the
     * collision box, so any hiccup in the revert leaves a cube-shaped outline
     * floating where a flower used to be. Cube-for-cube cannot change shape at
     * all, so the worst possible failure is a wrong texture until the next
     * chunk update.
     *
     * Contents -- a chest, barrel or furnace is somebody's storage. Lying about
     * one invites a click that the server will answer honestly and confusingly,
     * and there is no version of this feature worth going near a player's
     * things for.
     */
    private static boolean canLieAbout(BlockState state) {
        return !state.isAir() && state.isOpaqueFullCube() && !state.hasBlockEntity();
    }

    /**
     * Someone standing a long way off, not moving.
     *
     * Removed the instant you look near it or close on it, which is what sells
     * it: you are never allowed to confirm it was there.
     */
    private static void watcher(ServerPlayerEntity player, Random random) {
        if (FIGURES.containsKey(player.getUuid())) {
            return;
        }
        Vec3d look = player.getRotationVec(1.0F);
        // Just beyond the range at which it flees. Forty to sixty blocks put it
        // near the fog and it read as scenery; this is close enough to make out
        // that it is a person, and far enough that stepping toward it is what
        // makes it disappear.
        double distance = WATCHER_FLEE_RANGE + 4 + random.nextInt(11);
        Vec3d at = player.getPos()
                .add(look.multiply(distance))
                .add(random.nextGaussian() * 4.0, 0.0, random.nextGaussian() * 4.0);

        BlockPos ground = player.getWorld().getTopPosition(
                net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,
                BlockPos.ofFloored(at));
        Vec3d standing = new Vec3d(at.x, ground.getY(), at.z);

        float yaw = (float) Math.toDegrees(Math.atan2(
                player.getZ() - standing.z, player.getX() - standing.x)) - 90.0F;
        int id = TrapPhantom.figure(player, EntityType.PILLAGER, standing, yaw);
        FIGURES.put(player.getUuid(),
                new Figure(id, standing, player.getWorld().getTime() + 20L * 12));

        if (random.nextInt(3) == 0) {
            TrapPhantom.sound(player, standing, SoundEvents.AMBIENT_CAVE.value(), 0.6F, 0.7F);
        }
    }

    /**
     * Take the figure away before the player can prove it exists.
     *
     * The timer alone was not enough, and its absence was the whole problem:
     * a pillager standing motionless for twelve seconds that you can walk up
     * to and inspect isn't unsettling, it's a bug you've found. It has to be
     * gone the instant you try to confirm it.
     */
    private static void expireFigure(ServerPlayerEntity player, long time) {
        Figure figure = FIGURES.get(player.getUuid());
        if (figure == null) {
            return;
        }
        if (time >= figure.removeAtTick() || spotted(player, figure)) {
            drop(player, figure);
        }
    }

    /** Has the player looked straight at it, or got close enough to be sure? */
    private static boolean spotted(ServerPlayerEntity player, Figure figure) {
        Vec3d toFigure = figure.at().subtract(player.getEyePos());
        double distance = toFigure.length();
        if (distance < WATCHER_FLEE_RANGE) {
            return true;
        }
        if (distance < 1.0e-4) {
            return true;
        }
        return player.getRotationVec(1.0F).dotProduct(toFigure.multiply(1.0 / distance))
                > WATCHER_LOOK_DOT;
    }

    private static void drop(ServerPlayerEntity player, Figure figure) {
        TrapPhantom.clearFigure(player, figure.id());
        FIGURES.remove(player.getUuid());
    }

    // --- outside hooks --------------------------------------------------------

    /** The Nerve Tonic: wipe the meter and hold it down for a while. */
    public static void calm(ServerPlayerEntity player, int ticks) {
        UUID id = player.getUuid();
        METER.put(id, 0.0f);
        LAST_TIER.put(id, 0);
        IMMUNE_UNTIL.put(id, player.getWorld().getTime() + ticks);
        HELD_UNTIL.remove(id);   // a tonic beats a debug pin
        Figure figure = FIGURES.get(id);
        if (figure != null) {
            drop(player, figure);
        }
        TrapPhantom.clearAll(player);
        clearBar(player);
    }

    /** Called on respawn so a death isn't immediately followed by a scare. */
    public static void grace(ServerPlayerEntity player) {
        calm(player, (int) GRACE_TICKS);
    }

    public static float meterOf(ServerPlayerEntity player) {
        return METER.getOrDefault(player.getUuid(), 0.0f);
    }

    // --- opt-out, persisted ---------------------------------------------------

    /**
     * Who has turned paranoia off, kept on disk.
     *
     * This is a comfort setting. Holding it in memory meant a restart silently
     * switched the scares back on for somebody who had explicitly said they did
     * not want them, which is the one failure here that actually upsets a
     * person rather than being untidy.
     *
     * A flat file of UUIDs next to the world rather than PersistentState: it is
     * a list of strings, it is written only when somebody toggles, and a
     * corrupt read should cost the setting rather than the world.
     */
    private static Path optOutFile;

    private static void loadOptOuts(MinecraftServer server) {
        optOutFile = server.getSavePath(WorldSavePath.ROOT).resolve("trapcraft-paranoia-off.txt");
        OPTED_OUT.clear();
        try {
            if (Files.exists(optOutFile)) {
                for (String line : Files.readAllLines(optOutFile)) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) {
                        OPTED_OUT.add(UUID.fromString(trimmed));
                    }
                }
            }
        } catch (Exception failure) {
            // Losing the list is survivable; refusing to boot over it is not.
            TrapCraft.LOGGER.warn("couldn't read paranoia opt-outs: {}", failure.toString());
        }
    }

    private static void saveOptOuts() {
        if (optOutFile == null) {
            return;
        }
        try {
            Files.write(optOutFile, OPTED_OUT.stream().map(UUID::toString).toList());
        } catch (Exception failure) {
            TrapCraft.LOGGER.warn("couldn't save paranoia opt-outs: {}", failure.toString());
        }
    }

    /** Wipe every illusion and resync the blocks they touched. */
    private static int clear(ServerPlayerEntity player) {
        if (player == null) {
            return 0;
        }
        calm(player, 0);
        player.sendMessage(Text.literal("Wyciszone. Tak naprawdę nigdy nic tam nie było.")
                .formatted(Formatting.GRAY), false);
        return 1;
    }

    /** Force the meter, for testing the tiers without farming heat for one. */
    private static int set(ServerPlayerEntity player, int level) {
        if (player == null) {
            return 0;
        }
        if (OPTED_OUT.remove(player.getUuid())) {
            saveOptOuts();
        }
        IMMUNE_UNTIL.remove(player.getUuid());
        HELD_UNTIL.put(player.getUuid(), player.getWorld().getTime() + HOLD_TICKS);
        METER.put(player.getUuid(), (float) level);
        LAST_TIER.put(player.getUuid(), tierOf(level));
        updateBar(player, level);
        player.sendMessage(Text.literal("Nerwy: " + level + "  (tier "
                + tierOf(level) + ")").formatted(Formatting.GRAY), false);
        return 1;
    }

    private static int toggle(ServerPlayerEntity player) {
        if (player == null) {
            return 0;
        }
        UUID id = player.getUuid();
        boolean nowOff = OPTED_OUT.add(id);
        if (!nowOff) {
            OPTED_OUT.remove(id);
        } else {
            calm(player, 0);
        }
        saveOptOuts();
        player.sendMessage(Text.literal(nowOff
                        ? "Paranoja wyłączona. Nic tam nie ma."
                        : "Paranoia on. Watch yourself.")
                .formatted(Formatting.GRAY), false);
        return 1;
    }
}
