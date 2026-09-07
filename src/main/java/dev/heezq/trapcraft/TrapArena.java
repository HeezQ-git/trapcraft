package dev.heezq.trapcraft;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import it.unimi.dsi.fastutil.ints.IntList;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FireworkExplosionComponent;
import net.minecraft.component.type.FireworksComponent;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.projectile.FireworkRocketEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The arena: an event the whole server can walk into.
 *
 * Every so often, with company online, an omen goes out -- a line in chat
 * with a link on it -- and for two minutes anybody who clicks is stood at
 * the gate of a pit in its own dimension. Then the witness rises, and the
 * next ten minutes are {@link WitnessEntity}'s.
 *
 * This class is the clock and the doorman: the roll, the gathering, the
 * bars, who came in and where they came from, knockouts (nobody dies here,
 * they sit in the stands for twenty seconds), the lights, the loot, and the
 * way home. Origins are written to {@code world/trapcraft-arena.txt} so a
 * restart mid-fight still knows where everybody lives.
 *
 * <h2>Money</h2>
 *
 * The bounty is the only emerald the arena creates and it goes through
 * {@link TrapMarket#pay} -- money entering the world, the pair that moves
 * the index. Cases come out of the boss for free, the way cases always do;
 * the keys stay on the shelf at 22,000e, so every fight is a reason to
 * spend, which is what a sink looks like from the fun side.
 */
public final class TrapArena {
    public enum Stage { IDLE, GATHERING, FIGHT, VICTORY }

    public static final RegistryKey<World> WORLD_KEY = RegistryKey.of(RegistryKeys.WORLD, TrapCraft.id("arena"));
    /** The blueprint's (0, 0, 0): the block under the centre of the pit floor. */
    public static final BlockPos ORIGIN = new BlockPos(0, 64, 0);
    public static final Vec3d CENTRE = new Vec3d(0.5, 65.0, 0.5);
    public static final double PIT_RADIUS = 20.5;
    private static final Vec3d GATE = new Vec3d(0.5, 65.0, 27.5);
    private static final float GATE_YAW = 180.0F;
    private static final double STANDS_RADIUS = 28.5;
    private static final int STANDS_Y = 70;
    private static final int VICTORY_TICKS = 20 * 90;
    private static final int FIREWORK_TICKS = 20 * 8;

    private static final BlockState LIT = Blocks.SEA_LANTERN.getDefaultState();
    private static final BlockState DIM = Blocks.CRYING_OBSIDIAN.getDefaultState();
    private static final BlockState DARK = Blocks.POLISHED_BLACKSTONE.getDefaultState();
    private static final BlockState DARK_WALL = Blocks.POLISHED_BLACKSTONE_BRICKS.getDefaultState();

    private record Origin(String world, double x, double y, double z, float yaw, float pitch) {
        static Origin of(ServerPlayerEntity player) {
            return new Origin(player.getWorld().getRegistryKey().getValue().toString(),
                    player.getX(), player.getY(), player.getZ(), player.getYaw(), player.getPitch());
        }
    }

    private static MinecraftServer server;
    private static Stage stage = Stage.IDLE;
    private static int stageStart;
    private static int gatherTicks;
    private static WitnessEntity boss;
    private static ArenaBlueprint blueprint;
    private static final Map<UUID, Origin> ORIGINS = new HashMap<>();
    private static final Map<UUID, Float> DAMAGE = new HashMap<>();
    private static final Map<UUID, String> NAMES = new HashMap<>();
    private static final Map<UUID, Integer> KNOCKED_OUT = new HashMap<>();
    private static int knockouts;
    private static long lastEventEnd = Long.MIN_VALUE / 4;
    private static ServerBossBar countdownBar;
    private static ServerBossBar fightBar;
    private static ServerBossBar watchBar;
    private static int fireworksUntil;
    private static Path saveFile;

    private TrapArena() {
    }

    public static void register() {
        WitnessEntity.register();
        WitnessEyeEntity.register();
        WitnessEyeItem.register();

        ServerLifecycleEvents.SERVER_STARTED.register(TrapArena::load);
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
            if (stage != Stage.IDLE) {
                endEvent(false, "restart");
            } else {
                for (ServerPlayerEntity player : arenaPlayers()) {
                    sendHome(player, "Serwer się zamyka. Wracasz.");
                }
            }
            save();
        });
        ServerTickEvents.END_SERVER_TICK.register(TrapArena::tick);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> onJoin(handler.getPlayer()));
        ServerLivingEntityEvents.ALLOW_DEATH.register(TrapArena::allowDeath);
        CommandRegistrationCallback.EVENT.register((dispatcher, access, env) -> commands(dispatcher));
    }

    // --- state everybody asks about ----------------------------------------------

    public static Stage stage() {
        return stage;
    }

    private static ServerWorld world() {
        return server == null ? null : server.getWorld(WORLD_KEY);
    }

    public static boolean inArena(ServerPlayerEntity player) {
        return player != null && player.getWorld().getRegistryKey() == WORLD_KEY;
    }

    /** Everybody in the arena dimension, stands included. */
    public static List<ServerPlayerEntity> arenaPlayers() {
        ServerWorld world = world();
        return world == null ? List.of() : new ArrayList<>(world.getPlayers());
    }

    /** Somebody the boss may go for: in the pit, alive, survival, and not sat out. */
    public static boolean isCombatant(net.minecraft.entity.Entity entity) {
        if (!(entity instanceof ServerPlayerEntity player) || !inArena(player) || !player.isAlive()) {
            return false;
        }
        if (player.isSpectator() || player.isCreative() || isKnockedOut(player)) {
            return false;
        }
        double dx = player.getX() - CENTRE.x;
        double dz = player.getZ() - CENTRE.z;
        return dx * dx + dz * dz <= (PIT_RADIUS + 0.5) * (PIT_RADIUS + 0.5)
                && player.getY() > ORIGIN.getY() - 2 && player.getY() < ORIGIN.getY() + 14;
    }

    public static List<ServerPlayerEntity> combatants() {
        List<ServerPlayerEntity> out = new ArrayList<>();
        for (ServerPlayerEntity player : arenaPlayers()) {
            if (isCombatant(player)) {
                out.add(player);
            }
        }
        return out;
    }

    public static boolean isKnockedOut(ServerPlayerEntity player) {
        return KNOCKED_OUT.containsKey(player.getUuid());
    }

    public static ServerPlayerEntity topDamage(List<ServerPlayerEntity> among) {
        ServerPlayerEntity best = null;
        float most = 0.0F;
        for (ServerPlayerEntity player : among) {
            float dealt = DAMAGE.getOrDefault(player.getUuid(), 0.0F);
            if (dealt > most) {
                most = dealt;
                best = player;
            }
        }
        return best;
    }

    public static void recordDamage(ServerPlayerEntity player, float dealt) {
        if (dealt <= 0.0F) {
            return;
        }
        DAMAGE.merge(player.getUuid(), dealt, Float::sum);
        NAMES.put(player.getUuid(), player.getNameForScoreboard());
    }

    /** Add Groza stacks. Fresh instance rather than a longer one, so the icon's amplifier moves. */
    public static void dread(ServerPlayerEntity player, int add) {
        if (player == null || !player.isAlive()) {
            return;
        }
        StatusEffectInstance current = player.getStatusEffect(TrapContent.dreadEffect);
        int amplifier = current == null ? add - 1 : Math.min(ArenaMath.DREAD_MAX, current.getAmplifier() + add);
        player.removeStatusEffect(TrapContent.dreadEffect);
        player.addStatusEffect(new StatusEffectInstance(TrapContent.dreadEffect, ArenaMath.DREAD_TICKS,
                Math.max(0, amplifier), false, true, true));
    }

    public static void title(ServerPlayerEntity player, Text title, Text subtitle, int in, int stay, int out) {
        player.networkHandler.sendPacket(new TitleFadeS2CPacket(in, stay, out));
        player.networkHandler.sendPacket(new SubtitleS2CPacket(subtitle));
        player.networkHandler.sendPacket(new TitleS2CPacket(title));
    }

    // --- the clock ----------------------------------------------------------------

    private static void tick(MinecraftServer s) {
        if (server == null || world() == null) {
            return;
        }
        int now = server.getTicks();
        switch (stage) {
            case IDLE -> rollOmen(now);
            case GATHERING -> tickGathering(now);
            case FIGHT -> tickFight(now);
            case VICTORY -> tickVictory(now);
        }
        if (stage != Stage.IDLE) {
            voidCatch();
            knockoutReturns(now);
            if (now % 20 == 0) {
                dreadTick(now);
            }
        }
    }

    private static void rollOmen(int now) {
        if (now % ArenaMath.ROLL_PERIOD_TICKS != 0) {
            return;
        }
        long since = System.currentTimeMillis() / 1000 - lastEventEnd;
        int online = server.getPlayerManager().getPlayerList().size();
        if (ArenaMath.omenRolls(since, online, server.getOverworld().getRandom().nextFloat())) {
            startGathering(ArenaMath.GATHER_TICKS);
        }
    }

    private static void startGathering(int ticks) {
        ServerWorld world = world();
        stage = Stage.GATHERING;
        stageStart = server.getTicks();
        gatherTicks = ticks;
        DAMAGE.clear();
        NAMES.clear();
        KNOCKED_OUT.clear();
        knockouts = 0;
        blueprint.build(world, ORIGIN);
        setLights(1);
        sigil(false);

        countdownBar = new ServerBossBar(Text.literal("OBSERWATOR"), BossBar.Color.PURPLE, BossBar.Style.NOTCHED_10);
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            countdownBar.addPlayer(player);
            player.playSoundToPlayer(SoundEvents.EVENT_RAID_HORN.value(), SoundCategory.HOSTILE, 1.0F, 0.6F);
            player.playSoundToPlayer(SoundEvents.BLOCK_BELL_RESONATE, SoundCategory.HOSTILE, 0.6F, 0.5F);
        }
        announce(ticks, true);
        TrapCraft.LOGGER.info("arena: omen, gathering for {} ticks", ticks);
    }

    private static void tickGathering(int now) {
        int left = gatherTicks - (now - stageStart);
        if (countdownBar != null) {
            countdownBar.setName(Text.literal("OBSERWATOR ").formatted(Formatting.LIGHT_PURPLE, Formatting.BOLD)
                    .append(Text.literal("· arena otwiera się za " + ArenaMath.clock(left)).formatted(Formatting.WHITE)));
            countdownBar.setPercent(Math.max(0.0F, Math.min(1.0F, left / (float) gatherTicks)));
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                if (!countdownBar.getPlayers().contains(player)) {
                    countdownBar.addPlayer(player);
                }
            }
        }
        if ((left == 1200 || left == 300) && gatherTicks > left) {
            announce(left, false);
        }
        if (left <= 0) {
            if (arenaPlayers().isEmpty()) {
                broadcast(Text.literal("Nikt nie przyszedł. ").formatted(Formatting.GRAY, Formatting.ITALIC)
                        .append(Text.literal("Obserwator wrócił na krawędź mapy.").formatted(Formatting.DARK_GRAY)));
                endEvent(false, "nobody came");
            } else {
                startFight(now);
            }
        }
    }

    private static void startFight(int now) {
        ServerWorld world = world();
        stage = Stage.FIGHT;
        stageStart = now;
        if (countdownBar != null) {
            countdownBar.clearPlayers();
            countdownBar = null;
        }
        boss = new WitnessEntity(WitnessEntity.TYPE, world);
        boss.refreshPositionAndAngles(CENTRE.x, CENTRE.y, CENTRE.z, 180.0F, 0.0F);
        boss.sizeFor(Math.max(1, arenaPlayers().size()));
        world.spawnEntity(boss);

        fightBar = new ServerBossBar(Text.literal("OBSERWATOR"), BossBar.Color.PURPLE, BossBar.Style.PROGRESS);
        fightBar.setDragonMusic(true);
        watchBar = new ServerBossBar(Text.literal("OBSERWATOR"), BossBar.Color.PURPLE, BossBar.Style.PROGRESS);
        sigil(true);
        for (ServerPlayerEntity player : arenaPlayers()) {
            title(player, Text.literal("OBSERWATOR").formatted(Formatting.DARK_PURPLE, Formatting.BOLD),
                    Text.literal("Patrzył od pierwszego dnia.").formatted(Formatting.LIGHT_PURPLE), 20, 70, 20);
            player.playSoundToPlayer(SoundEvents.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.HOSTILE, 1.0F, 0.5F);
            TrapNet.flash(player, 0x2a1b3d, 30);
        }
        broadcast(Text.literal("Obserwator jest na arenie. ").formatted(Formatting.LIGHT_PURPLE, Formatting.BOLD)
                .append(Text.literal("Dziesięć minut, zanim znów zniknie. ").formatted(Formatting.GRAY))
                .append(link("[ WCHODZĘ ]", "/arena join", "Teleport na arenę")));
        TrapCraft.LOGGER.info("arena: the witness rises for {} players", arenaPlayers().size());
    }

    /** The intro is over; the boss is in play. */
    public static void fightBegins(WitnessEntity witness) {
        for (ServerPlayerEntity player : arenaPlayers()) {
            player.sendMessage(Text.literal("Patrzy na ciebie.").formatted(Formatting.DARK_PURPLE), true);
        }
    }

    private static void tickFight(int now) {
        if (boss == null || boss.isRemoved()) {
            endEvent(false, "the witness is gone");
            return;
        }
        if (boss.isDead()) {
            return;
        }
        int left = ArenaMath.FIGHT_TICKS - (now - stageStart);
        float fraction = Math.max(0.0F, boss.getHealth() / boss.getMaxHealth());
        Text name = Text.literal("OBSERWATOR ").formatted(Formatting.LIGHT_PURPLE, Formatting.BOLD)
                .append(Text.literal("· " + roman(boss.phase()) + " · " + ArenaMath.clock(left))
                        .formatted(Formatting.WHITE));
        for (ServerBossBar bar : new ServerBossBar[]{fightBar, watchBar}) {
            bar.setName(name);
            bar.setPercent(fraction);
            bar.setColor(boss.phase() == 3 ? BossBar.Color.RED : boss.phase() == 2 ? BossBar.Color.PINK
                    : BossBar.Color.PURPLE);
        }
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            boolean here = inArena(player);
            if (here && !fightBar.getPlayers().contains(player)) {
                fightBar.addPlayer(player);
                watchBar.removePlayer(player);
            } else if (!here && !watchBar.getPlayers().contains(player)) {
                watchBar.addPlayer(player);
                fightBar.removePlayer(player);
            }
        }
        if (left <= 0) {
            enrage();
        }
    }

    private static void enrage() {
        for (ServerPlayerEntity player : arenaPlayers()) {
            title(player, Text.literal("ZNIKNĄŁ").formatted(Formatting.RED, Formatting.BOLD),
                    Text.literal("Za wolno.").formatted(Formatting.GRAY), 10, 60, 20);
            player.playSoundToPlayer(SoundEvents.ENTITY_WITHER_DEATH, SoundCategory.HOSTILE, 0.8F, 0.4F);
        }
        broadcast(Text.literal("Obserwator zniknął. ").formatted(Formatting.RED, Formatting.BOLD)
                .append(Text.literal("Nikt nie zamknął mu oka na czas.").formatted(Formatting.GRAY)));
        ServerWorld world = world();
        if (boss != null && world != null) {
            Vec3d at = boss.getPos();
            world.spawnParticles(ParticleTypes.REVERSE_PORTAL, at.x, at.y + 1.5, at.z, 80, 0.8, 1.5, 0.8, 0.5);
        }
        endEvent(false, "the clock ran out");
    }

    // --- phases, death, loot ------------------------------------------------------------

    public static void onPhase(WitnessEntity witness, int phase) {
        setLights(phase);
        if (phase == 3) {
            sigil(false);
        }
        Text head = Text.literal(phase == 2 ? "FAZA II" : "FAZA III").formatted(Formatting.LIGHT_PURPLE, Formatting.BOLD);
        Text sub = Text.literal(phase == 2 ? "Nie mruga." : "Gasną światła.").formatted(Formatting.GRAY);
        for (ServerPlayerEntity player : arenaPlayers()) {
            title(player, head, sub, 5, 40, 15);
            TrapNet.shake(player, 1.0F, 15);
            TrapNet.flash(player, phase == 3 ? 0x000000 : 0x5a2d9c, 20);
        }
        for (ServerPlayerEntity player : combatants()) {
            player.addStatusEffect(new StatusEffectInstance(TrapContent.adrenalineEffect,
                    ArenaMath.ADRENALINE_PHASE_TICKS, 0, false, true, true));
            player.heal(4.0F);
        }
        broadcast(Text.literal("Obserwator: ").formatted(Formatting.DARK_PURPLE)
                .append(Text.literal(phase == 2 ? "faza II. Nie mruga." : "faza III. Gasną światła.").formatted(Formatting.GRAY)));
    }

    public static void onBossDeath(WitnessEntity witness) {
        if (stage != Stage.FIGHT) {
            return;
        }
        stage = Stage.VICTORY;
        stageStart = server.getTicks();
        Text name = Text.literal("OBSERWATOR ").formatted(Formatting.GOLD, Formatting.BOLD)
                .append(Text.literal("· oko zamknięte").formatted(Formatting.WHITE));
        for (ServerBossBar bar : new ServerBossBar[]{fightBar, watchBar}) {
            if (bar != null) {
                bar.setName(name);
                bar.setPercent(0.0F);
                bar.setColor(BossBar.Color.YELLOW);
            }
        }
        for (ServerPlayerEntity player : arenaPlayers()) {
            title(player, Text.literal("OKO ZAMKNIĘTE").formatted(Formatting.GOLD, Formatting.BOLD),
                    Text.literal("Już nie patrzy.").formatted(Formatting.YELLOW), 10, 80, 30);
            TrapNet.shake(player, 1.2F, 20);
            TrapAwards.grant(player, "witness");
            if (knockouts == 0 && DAMAGE.getOrDefault(player.getUuid(), 0.0F) > 0.0F) {
                TrapAwards.grant(player, "unbroken");
            }
        }
    }

    /** The body has gone up. Loot, fireworks, the table. */
    public static void victoryBurst(WitnessEntity witness) {
        ServerWorld world = world();
        if (world == null || stage != Stage.VICTORY) {
            // /kill on one an op summoned to look at: the rig still dies
            // properly, but nobody won anything.
            return;
        }
        Vec3d at = witness.getPos().add(0.0, 1.5, 0.0);
        List<Map.Entry<UUID, Float>> ranked = new ArrayList<>(DAMAGE.entrySet());
        ranked.removeIf(entry -> entry.getValue() <= 0.0F);
        ranked.sort(Map.Entry.<UUID, Float>comparingByValue().reversed());
        int players = ranked.size();
        float total = 0.0F;
        float[] dealt = new float[players];
        for (int i = 0; i < players; i++) {
            dealt[i] = ranked.get(i).getValue();
            total += dealt[i];
        }
        int pool = ArenaMath.bountyPool(players);
        int[] shares = ArenaMath.bountyShares(dealt, pool);

        MutableText table = Text.empty()
                .append(Text.literal("\n✦ OKO ZAMKNIĘTE ✦\n").formatted(Formatting.GOLD, Formatting.BOLD));
        String top = null;
        for (int i = 0; i < players; i++) {
            UUID id = ranked.get(i).getKey();
            String name = NAMES.getOrDefault(id, "?");
            if (i == 0) {
                top = name;
            }
            int percent = total > 0.0F ? Math.round(100.0F * dealt[i] / total) : 0;
            table.append(Text.literal("  " + (i + 1) + ". ").formatted(Formatting.GRAY))
                    .append(Text.literal(name).formatted(i == 0 ? Formatting.GOLD : Formatting.WHITE))
                    .append(Text.literal("  " + Math.round(dealt[i]) + " (" + percent + "%)  ").formatted(Formatting.GRAY))
                    .append(Text.literal("+" + shares[i] + "e\n").formatted(Formatting.GREEN));

            ServerPlayerEntity player = server.getPlayerManager().getPlayer(id);
            if (player == null) {
                continue;
            }
            TrapMarket.pay(player, shares[i]);
            player.getInventory().offerOrDrop(new ItemStack(TrapContent.CASES.get(CaseOdds.Tier.PHANTOM)));
            if (i == 0) {
                player.getInventory().offerOrDrop(new ItemStack(TrapContent.KEYS.get(CaseOdds.Tier.PHANTOM)));
                player.getInventory().offerOrDrop(new ItemStack(TrapContent.witnessEye));
            }
            player.addStatusEffect(new StatusEffectInstance(TrapContent.adrenalineEffect,
                    ArenaMath.ADRENALINE_WIN_TICKS, 0, false, true, true));
            player.playSoundToPlayer(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 1.0F, 1.0F);
            player.playSoundToPlayer(SoundEvents.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 0.8F, 0.8F);
        }
        table.append(Text.literal("  Skrzynka Widmo dla każdego").formatted(Formatting.LIGHT_PURPLE));
        if (top != null) {
            table.append(Text.literal(", Klucz Widmo i Oko Obserwatora dla " + top).formatted(Formatting.LIGHT_PURPLE));
        }
        table.append(Text.literal(".\n").formatted(Formatting.LIGHT_PURPLE))
                .append(Text.literal("  Brudne szmaragdy leżą na arenie. Kto pierwszy.\n").formatted(Formatting.GRAY, Formatting.ITALIC))
                .append(Text.literal("  ")).append(link("[ WRACAM ]", "/arena leave", "Wracasz tam, gdzie byłeś"))
                .append(Text.literal("\n"));
        broadcast(table);

        // The fountain: dirty money, launched, for whoever is quickest.
        Random random = world.getRandom();
        for (int i = 0; i < ArenaMath.dirtyBlocks(players); i++) {
            ItemEntity drop = new ItemEntity(world, at.x, at.y, at.z,
                    new ItemStack(TrapContent.dirtyEmeraldBlockItem),
                    (random.nextDouble() - 0.5) * 0.7, 0.55 + random.nextDouble() * 0.5,
                    (random.nextDouble() - 0.5) * 0.7);
            drop.setPickupDelay(30);
            world.spawnEntity(drop);
        }
        ExperienceOrbEntity.spawn(world, at, ArenaMath.XP_TOTAL);
        fireworksUntil = server.getTicks() + FIREWORK_TICKS;
        TrapCraft.LOGGER.info("arena: the witness fell to {} players, pool {}e", players, pool);
    }

    private static void tickVictory(int now) {
        ServerWorld world = world();
        if (world != null && now < fireworksUntil && now % 6 == 0) {
            firework(world);
            firework(world);
        }
        if (now - stageStart == 200) {
            for (ServerBossBar bar : new ServerBossBar[]{fightBar, watchBar}) {
                if (bar != null) {
                    bar.clearPlayers();
                }
            }
            fightBar = null;
            watchBar = null;
        }
        if (now - stageStart >= VICTORY_TICKS) {
            endEvent(true, "won");
        }
    }

    private static final FireworkExplosionComponent.Type[] SHAPES = {
            FireworkExplosionComponent.Type.LARGE_BALL, FireworkExplosionComponent.Type.STAR,
            FireworkExplosionComponent.Type.BURST, FireworkExplosionComponent.Type.CREEPER,
            FireworkExplosionComponent.Type.SMALL_BALL};
    private static final int[] COLOURS = {0x8a4fd8, 0x34d8ea, 0xffc24a, 0xc59bff, 0xff2d55, 0xffffff};

    private static void firework(ServerWorld world) {
        Random random = world.getRandom();
        double angle = random.nextDouble() * Math.PI * 2;
        double r = 8.0 + random.nextDouble() * 11.0;
        ItemStack rocket = new ItemStack(Items.FIREWORK_ROCKET);
        FireworkExplosionComponent burst = new FireworkExplosionComponent(
                SHAPES[random.nextInt(SHAPES.length)],
                IntList.of(COLOURS[random.nextInt(COLOURS.length)], COLOURS[random.nextInt(COLOURS.length)]),
                IntList.of(COLOURS[random.nextInt(COLOURS.length)]),
                random.nextBoolean(), random.nextBoolean());
        rocket.set(DataComponentTypes.FIREWORKS, new FireworksComponent(1 + random.nextInt(2), List.of(burst)));
        world.spawnEntity(new FireworkRocketEntity(world, CENTRE.x + Math.cos(angle) * r, CENTRE.y,
                CENTRE.z + Math.sin(angle) * r, rocket));
    }

    private static void endEvent(boolean won, String why) {
        for (ServerBossBar bar : new ServerBossBar[]{countdownBar, fightBar, watchBar}) {
            if (bar != null) {
                bar.clearPlayers();
            }
        }
        countdownBar = null;
        fightBar = null;
        watchBar = null;
        if (boss != null && !boss.isRemoved()) {
            boss.discard();
        }
        boss = null;
        for (ServerPlayerEntity player : arenaPlayers()) {
            sendHome(player, won ? "Arena zamknięta. Wracasz z łupem." : "Arena zamknięta. Wracasz.");
        }
        setLights(1);
        sigil(false);
        stage = Stage.IDLE;
        lastEventEnd = System.currentTimeMillis() / 1000;
        KNOCKED_OUT.clear();
        save();
        TrapCraft.LOGGER.info("arena: event over ({})", why);
    }

    // --- coming and going -----------------------------------------------------------

    private static int join(ServerCommandSource source) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            return err(source, "tylko dla graczy");
        }
        if (stage == Stage.IDLE) {
            return err(source, "Na arenie jest cicho. Obserwator jeszcze nie zszedł z krawędzi.");
        }
        if (inArena(player)) {
            return err(source, "Już tu jesteś.");
        }
        ServerWorld world = world();
        if (world == null) {
            return err(source, "Arena nie istnieje na tym serwerze.");
        }
        ORIGINS.putIfAbsent(player.getUuid(), Origin.of(player));
        NAMES.put(player.getUuid(), player.getNameForScoreboard());
        save();
        player.teleport(world, GATE.x, GATE.y, GATE.z, Set.of(), GATE_YAW, 0.0F, true);
        player.playSoundToPlayer(SoundEvents.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 1.0F, 0.7F);
        player.playSoundToPlayer(SoundEvents.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, 0.8F, 0.5F);
        TrapNet.flash(player, 0x2a1b3d, 25);
        player.sendMessage(Text.literal("Jesteś na arenie. ").formatted(Formatting.LIGHT_PURPLE)
                .append(link("/arena leave", "/arena leave", "Wracasz tam, gdzie byłeś"))
                .append(Text.literal(" wraca tam, gdzie byłeś. Nikt tu nie ginie: nokaut to 20 s w trybunach.")
                        .formatted(Formatting.GRAY)), false);
        if (stage == Stage.FIGHT && fightBar != null) {
            fightBar.addPlayer(player);
            if (watchBar != null) {
                watchBar.removePlayer(player);
            }
        }
        for (ServerPlayerEntity other : arenaPlayers()) {
            if (other != player) {
                other.sendMessage(Text.literal(player.getNameForScoreboard() + " wchodzi na arenę.")
                        .formatted(Formatting.DARK_GRAY), false);
            }
        }
        return 1;
    }

    private static int leave(ServerCommandSource source) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            return err(source, "tylko dla graczy");
        }
        if (!inArena(player)) {
            return err(source, "Nie jesteś na arenie.");
        }
        sendHome(player, "Wróciłeś.");
        return 1;
    }

    private static void sendHome(ServerPlayerEntity player, String why) {
        Origin origin = ORIGINS.remove(player.getUuid());
        save();
        player.removeStatusEffect(TrapContent.dreadEffect);
        KNOCKED_OUT.remove(player.getUuid());
        ServerWorld target = null;
        if (origin != null) {
            Identifier id = Identifier.tryParse(origin.world());
            target = id == null ? null : server.getWorld(RegistryKey.of(RegistryKeys.WORLD, id));
        }
        if (target == null || target.getRegistryKey() == WORLD_KEY) {
            ServerWorld overworld = server.getOverworld();
            BlockPos spawn = overworld.getSpawnPos();
            player.teleport(overworld, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, Set.of(),
                    player.getYaw(), player.getPitch(), true);
        } else {
            player.teleport(target, origin.x(), origin.y(), origin.z(), Set.of(), origin.yaw(), origin.pitch(), true);
        }
        player.sendMessage(Text.literal(why).formatted(Formatting.GRAY), false);
        player.playSoundToPlayer(SoundEvents.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, 0.8F, 0.6F);
    }

    private static void onJoin(ServerPlayerEntity player) {
        if (!inArena(player)) {
            return;
        }
        if (stage == Stage.IDLE) {
            sendHome(player, "Arena jest zamknięta. Wracasz.");
            return;
        }
        if (stage == Stage.FIGHT && fightBar != null) {
            fightBar.addPlayer(player);
        } else if (stage == Stage.GATHERING && countdownBar != null) {
            countdownBar.addPlayer(player);
        }
    }

    // --- nobody dies here ------------------------------------------------------------

    private static boolean allowDeath(LivingEntity entity, DamageSource source, float amount) {
        if (!(entity instanceof ServerPlayerEntity player) || !inArena(player)) {
            return true;
        }
        ServerWorld world = world();
        if (world == null) {
            return true;
        }
        knockout(player, world);
        return false;
    }

    private static void knockout(ServerPlayerEntity player, ServerWorld world) {
        player.setHealth(player.getMaxHealth());
        player.setFireTicks(0);
        player.getHungerManager().setFoodLevel(Math.max(player.getHungerManager().getFoodLevel(), 12));
        player.removeStatusEffect(TrapContent.dreadEffect);
        player.fallDistance = 0.0;
        // Anywhere in the stands but the gate's own sector, which is where
        // the landing is and where a knocked-out player would fall to the
        // floor of it instead.
        double angle = Math.toRadians(110 + world.getRandom().nextInt(320));
        double x = CENTRE.x + Math.cos(angle) * STANDS_RADIUS;
        double z = CENTRE.z + Math.sin(angle) * STANDS_RADIUS;
        float yaw = (float) Math.toDegrees(Math.atan2(-(CENTRE.x - x), CENTRE.z - z));
        player.teleport(world, x, STANDS_Y, z, Set.of(), yaw, 10.0F, true);
        KNOCKED_OUT.put(player.getUuid(), server.getTicks() + ArenaMath.KNOCKOUT_TICKS);
        knockouts++;
        title(player, Text.literal("NOKAUT").formatted(Formatting.RED, Formatting.BOLD),
                Text.literal("Wracasz za " + ArenaMath.KNOCKOUT_TICKS / 20 + " s.").formatted(Formatting.GRAY), 5, 40, 20);
        player.playSoundToPlayer(SoundEvents.ITEM_TOTEM_USE, SoundCategory.PLAYERS, 1.0F, 0.5F);
        TrapNet.flash(player, 0x000000, 25);
        if (stage == Stage.FIGHT && boss != null && boss.isAlive()) {
            boss.heal(boss.getMaxHealth() * ArenaMath.KNOCKOUT_HEAL);
            world.playSound(null, boss.getX(), boss.getY() + 2.0, boss.getZ(), SoundEvents.ENTITY_PILLAGER_CELEBRATE,
                    SoundCategory.HOSTILE, 1.5F, 0.5F);
            Text line = Text.literal(player.getNameForScoreboard() + " padł. ").formatted(Formatting.RED)
                    .append(Text.literal("Obserwator odzyskuje " + Math.round(ArenaMath.KNOCKOUT_HEAL * 100) + "%.")
                            .formatted(Formatting.GRAY));
            for (ServerPlayerEntity other : arenaPlayers()) {
                other.sendMessage(line, false);
            }
        }
    }

    private static void knockoutReturns(int now) {
        if (KNOCKED_OUT.isEmpty()) {
            return;
        }
        List<UUID> back = new ArrayList<>();
        for (Map.Entry<UUID, Integer> entry : KNOCKED_OUT.entrySet()) {
            if (now >= entry.getValue()) {
                back.add(entry.getKey());
            }
        }
        for (UUID id : back) {
            KNOCKED_OUT.remove(id);
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(id);
            ServerWorld world = world();
            if (player == null || world == null || !inArena(player) || stage != Stage.FIGHT) {
                continue;
            }
            player.teleport(world, GATE.x, GATE.y, GATE.z, Set.of(), GATE_YAW, 0.0F, true);
            title(player, Text.literal("WRACASZ").formatted(Formatting.GREEN, Formatting.BOLD),
                    Text.literal("Do roboty.").formatted(Formatting.GRAY), 5, 30, 10);
            player.playSoundToPlayer(SoundEvents.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 1.0F, 1.2F);
        }
    }

    private static void voidCatch() {
        ServerWorld world = world();
        if (world == null) {
            return;
        }
        for (ServerPlayerEntity player : arenaPlayers()) {
            if (player.getY() < ArenaMath.VOID_Y) {
                player.fallDistance = 0.0;
                player.teleport(world, GATE.x, GATE.y, GATE.z, Set.of(), GATE_YAW, 0.0F, true);
                player.sendMessage(Text.literal("Pod areną nie ma nic. Wracasz na bramę.").formatted(Formatting.GRAY), true);
            }
        }
    }

    /**
     * What Groza does, once a second, from outside the effect loop.
     *
     * Two stacks pulse the screen dark, three bleed, and every stack fades
     * on company: the same rule Paranoia taught everyone.
     */
    private static void dreadTick(int now) {
        List<ServerPlayerEntity> here = arenaPlayers();
        for (ServerPlayerEntity player : here) {
            StatusEffectInstance dread = player.getStatusEffect(TrapContent.dreadEffect);
            if (dread == null) {
                continue;
            }
            int stacks = dread.getAmplifier();
            if (stacks >= 2 && now % 60 == 0) {
                player.addStatusEffect(new StatusEffectInstance(StatusEffects.DARKNESS, 40, 0,
                        true, false, false));
            }
            if (stacks >= 3 && now % 40 == 0) {
                player.damage(player.getWorld(), player.getWorld().getDamageSources().magic(), 1.0F);
                player.playSoundToPlayer(SoundEvents.ENTITY_WARDEN_HEARTBEAT, SoundCategory.HOSTILE, 0.8F, 0.9F);
            }
            boolean company = false;
            for (ServerPlayerEntity other : here) {
                if (other != player && other.isAlive()
                        && other.squaredDistanceTo(player) <= ArenaMath.COMPANY_RANGE * ArenaMath.COMPANY_RANGE) {
                    company = true;
                    break;
                }
            }
            if (!company) {
                continue;
            }
            int amplifier = dread.getAmplifier();
            int left = dread.getDuration();
            player.removeStatusEffect(TrapContent.dreadEffect);
            if (amplifier > 0) {
                player.addStatusEffect(new StatusEffectInstance(TrapContent.dreadEffect, left, amplifier - 1,
                        false, true, true));
            }
        }
    }

    // --- the lights ------------------------------------------------------------------

    private static void setLights(int phase) {
        ServerWorld world = world();
        if (world == null || blueprint == null) {
            return;
        }
        for (BlockPos pos : blueprint.tagged("vein", ORIGIN)) {
            BlockState state = phase == 1 ? blueprint.original(pos, ORIGIN) : phase == 2 ? DIM : DARK;
            world.setBlockState(pos, state, Block.NOTIFY_LISTENERS);
        }
        for (BlockPos pos : blueprint.tagged("glow", ORIGIN)) {
            BlockState state = phase == 3 ? DARK_WALL : blueprint.original(pos, ORIGIN);
            world.setBlockState(pos, state, Block.NOTIFY_LISTENERS);
        }
        if (phase == 3) {
            world.playSound(null, CENTRE.x, CENTRE.y, CENTRE.z, SoundEvents.BLOCK_BEACON_DEACTIVATE,
                    SoundCategory.HOSTILE, 2.0F, 0.5F);
        }
    }

    private static void sigil(boolean on) {
        ServerWorld world = world();
        if (world == null || blueprint == null) {
            return;
        }
        for (BlockPos pos : blueprint.tagged("sigil", ORIGIN)) {
            world.setBlockState(pos, on ? LIT : blueprint.original(pos, ORIGIN), Block.NOTIFY_LISTENERS);
        }
    }

    // --- words --------------------------------------------------------------------------

    private static void announce(int ticksLeft, boolean first) {
        MutableText text = Text.empty();
        if (first) {
            text.append(Text.literal("\n✦ ARENA ✦\n").formatted(Formatting.DARK_PURPLE, Formatting.BOLD))
                    .append(Text.literal("Obserwator zszedł z krawędzi mapy.\n").formatted(Formatting.LIGHT_PURPLE))
                    .append(Text.literal("Ta sylwetka na granicy widoku, która znikała, gdy się odwracałeś. "
                            + "Dziś nie zniknie.\n").formatted(Formatting.GRAY))
                    .append(Text.literal("Arena otwiera się za " + ArenaMath.clock(ticksLeft) + ". ").formatted(Formatting.WHITE))
                    .append(Text.literal("Nikt tam nie ginie: nokaut, trybuny, powrót.\n").formatted(Formatting.DARK_GRAY, Formatting.ITALIC));
        } else {
            text.append(Text.literal("Obserwator czeka. ").formatted(Formatting.LIGHT_PURPLE))
                    .append(Text.literal(ArenaMath.clock(ticksLeft) + ".\n").formatted(Formatting.WHITE));
        }
        text.append(Text.literal("   ")).append(link("[ WCHODZĘ NA ARENĘ ]", "/arena join",
                "Teleport na arenę. Wracasz przez /arena leave.")).append(Text.literal("\n"));
        broadcast(text);
    }

    private static MutableText link(String label, String command, String hover) {
        return Text.literal(label).formatted(Formatting.GREEN, Formatting.BOLD)
                .styled(style -> style.withClickEvent(new ClickEvent.RunCommand(command))
                        .withHoverEvent(new HoverEvent.ShowText(Text.literal(hover))));
    }

    private static void broadcast(Text text) {
        server.getPlayerManager().broadcast(text, false);
    }

    private static String roman(int phase) {
        return phase == 3 ? "III" : phase == 2 ? "II" : "I";
    }

    private static int ok(ServerCommandSource source, String msg) {
        source.sendFeedback(() -> Text.literal(msg).formatted(Formatting.GRAY), false);
        return 1;
    }

    private static int err(ServerCommandSource source, String msg) {
        source.sendFeedback(() -> Text.literal(msg).formatted(Formatting.RED), false);
        return 0;
    }

    // --- commands -------------------------------------------------------------------------

    private static void commands(CommandDispatcher<ServerCommandSource> dispatcher) {
        var root = CommandManager.literal("arena")
                .executes(context -> {
                    context.getSource().sendFeedback(() -> Text.empty()
                            .append(Text.literal("Arena\n").formatted(Formatting.DARK_PURPLE, Formatting.BOLD))
                            .append(Text.literal("  /arena join   wejdź, kiedy Obserwator jest na arenie\n").formatted(Formatting.GRAY))
                            .append(Text.literal("  /arena leave  wróć tam, gdzie byłeś\n").formatted(Formatting.GRAY))
                            .append(Text.literal("  /guide arena  jak z nim walczyć").formatted(Formatting.GRAY)), false);
                    return 1;
                })
                .then(CommandManager.literal("join").executes(context -> join(context.getSource())))
                .then(CommandManager.literal("leave").executes(context -> leave(context.getSource())))
                .then(CommandManager.literal("start")
                        .requires(source -> source.hasPermissionLevel(2))
                        .executes(context -> start(context.getSource(), ArenaMath.GATHER_TICKS / 20))
                        .then(CommandManager.argument("seconds", IntegerArgumentType.integer(5, 600))
                                .executes(context -> start(context.getSource(),
                                        IntegerArgumentType.getInteger(context, "seconds")))))
                .then(CommandManager.literal("stop")
                        .requires(source -> source.hasPermissionLevel(2))
                        .executes(context -> {
                            if (stage == Stage.IDLE) {
                                return err(context.getSource(), "nic nie trwa");
                            }
                            broadcast(Text.literal("Arena przerwana.").formatted(Formatting.GRAY));
                            endEvent(false, "stopped by " + context.getSource().getName());
                            return ok(context.getSource(), "przerwane");
                        }))
                .then(CommandManager.literal("tp")
                        .requires(source -> source.hasPermissionLevel(2))
                        .executes(context -> visit(context.getSource())))
                .then(CommandManager.literal("build")
                        .requires(source -> source.hasPermissionLevel(2))
                        .executes(context -> {
                            ServerWorld world = world();
                            if (world == null) {
                                return err(context.getSource(), "brak wymiaru areny");
                            }
                            blueprint.build(world, ORIGIN);
                            setLights(1);
                            return ok(context.getSource(), "arena zbudowana: " + blueprint.size() + " bloków");
                        }));
        var cast = CommandManager.literal("cast").requires(source -> source.hasPermissionLevel(2));
        for (WitnessEntity.Ability ability : WitnessEntity.Ability.values()) {
            cast.then(CommandManager.literal(ability.name().toLowerCase(java.util.Locale.ROOT))
                    .executes(context -> {
                        if (boss == null || !boss.isAlive() || stage != Stage.FIGHT) {
                            return err(context.getSource(), "nie ma teraz bossa");
                        }
                        boss.begin(ability);
                        return ok(context.getSource(), "cast " + ability);
                    }));
        }
        root.then(cast);
        dispatcher.register(root);
    }

    private static int start(ServerCommandSource source, int seconds) {
        if (world() == null) {
            return err(source, "brak wymiaru areny -- sprawdź log startu");
        }
        if (stage != Stage.IDLE) {
            return err(source, "arena już trwa");
        }
        startGathering(seconds * 20);
        return ok(source, "zwiastun wysłany, " + seconds + " s do walki");
    }

    /** Have a look at the pit without an event. Same door home. */
    private static int visit(ServerCommandSource source) {
        ServerPlayerEntity player = source.getPlayer();
        ServerWorld world = world();
        if (player == null || world == null) {
            return err(source, "brak gracza albo wymiaru");
        }
        if (stage == Stage.IDLE) {
            blueprint.build(world, ORIGIN);
            setLights(1);
        }
        ORIGINS.putIfAbsent(player.getUuid(), Origin.of(player));
        save();
        player.teleport(world, GATE.x, GATE.y, GATE.z, Set.of(), GATE_YAW, 0.0F, true);
        return ok(source, "arena; /arena leave wraca");
    }

    // --- storage ----------------------------------------------------------------------------

    private static void load(MinecraftServer s) {
        server = s;
        saveFile = s.getSavePath(WorldSavePath.ROOT).resolve("trapcraft-arena.txt");
        blueprint = ArenaBlueprint.load();
        try {
            if (Files.exists(saveFile)) {
                for (String line : Files.readAllLines(saveFile)) {
                    String[] parts = line.trim().split(" ");
                    if (parts.length == 2 && parts[0].equals("last")) {
                        lastEventEnd = Long.parseLong(parts[1]);
                    } else if (parts.length == 8 && parts[0].equals("origin")) {
                        ORIGINS.put(UUID.fromString(parts[1]), new Origin(parts[2],
                                Double.parseDouble(parts[3]), Double.parseDouble(parts[4]),
                                Double.parseDouble(parts[5]), Float.parseFloat(parts[6]),
                                Float.parseFloat(parts[7])));
                    }
                }
            }
        } catch (Exception e) {
            TrapCraft.LOGGER.error("arena state unreadable, starting empty", e);
        }
        if (world() == null) {
            TrapCraft.LOGGER.error("arena: dimension {} is not loaded -- the event is OFF. "
                    + "Is data/trapcraft/dimension/arena.json in the jar?", WORLD_KEY.getValue());
        } else {
            TrapCraft.LOGGER.info("arena: dimension {} ready, {} origins on file", WORLD_KEY.getValue(),
                    ORIGINS.size());
        }
    }

    private static void save() {
        if (saveFile == null) {
            return;
        }
        StringBuilder out = new StringBuilder();
        out.append("last ").append(lastEventEnd).append('\n');
        for (Map.Entry<UUID, Origin> entry : ORIGINS.entrySet()) {
            Origin o = entry.getValue();
            out.append("origin ").append(entry.getKey()).append(' ').append(o.world()).append(' ')
                    .append(o.x()).append(' ').append(o.y()).append(' ').append(o.z()).append(' ')
                    .append(o.yaw()).append(' ').append(o.pitch()).append('\n');
        }
        try {
            Files.writeString(saveFile, out.toString());
        } catch (Exception e) {
            TrapCraft.LOGGER.error("could not save arena state", e);
        }
    }
}
