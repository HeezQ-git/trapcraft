package dev.heezq.trapcraft;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
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
 * the gate of one of the arenas in {@code trapcraft:arena}. Then the boss
 * the draw picked rises, and the next ten minutes are its.
 *
 * This class is the clock and the doorman, and it knows no boss by name:
 * the roll and the draw, the gathering, the bars, who came in and where
 * they came from, knockouts (nobody dies here, they sit in the stands for
 * twenty seconds), the loot, the show, and the way home. Everything a boss
 * decides -- its arena, its words, its lights, its trophy -- is asked of
 * the {@link ArenaBoss} on duty. Origins and the last draw are written to
 * {@code world/trapcraft-arena.txt} so a restart mid-fight still knows where
 * everybody lives and who fought last.
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
    public static final BlockState LIT = Blocks.SEA_LANTERN.getDefaultState();
    private static final int VICTORY_TICKS = 20 * 90;
    private static final int SHOW_TICKS = 20 * 8;

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
    private static ArenaBoss current;
    private static ArenaBossEntity boss;
    private static ArenaBlueprint blueprint;
    private static final Map<String, ArenaBlueprint> BLUEPRINTS = new HashMap<>();
    private static final Map<UUID, Origin> ORIGINS = new HashMap<>();
    private static final Map<UUID, Float> DAMAGE = new HashMap<>();
    private static final Map<UUID, String> NAMES = new HashMap<>();
    private static final Map<UUID, Integer> KNOCKED_OUT = new HashMap<>();
    private static final List<Entity> ADDS = new ArrayList<>();
    private static int knockouts;
    private static long lastEventEnd = Long.MIN_VALUE / 4;
    private static String lastBossId;
    private static ServerBossBar countdownBar;
    private static ServerBossBar fightBar;
    private static ServerBossBar watchBar;
    private static int showUntil;
    private static List<BlockPos> showVeins = List.of();
    private static Path saveFile;

    private TrapArena() {
    }

    public static void register() {
        WitnessEntity.register();
        BanditEntity.register();
        RatKingEntity.register();
        RatEntity.register();
        StormEntity.register();
        ArenaProjectileEntity.register();
        WitnessEyeItem.register();
        ArenaBosses.register(WitnessBoss.INSTANCE);
        ArenaBosses.register(BanditBoss.INSTANCE);
        ArenaBosses.register(RatKingBoss.INSTANCE);
        ArenaBosses.register(StormBoss.INSTANCE);

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

    /** The boss on duty, or null between events. */
    public static ArenaBoss current() {
        return current;
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
    public static boolean isCombatant(Entity entity) {
        if (!(entity instanceof ServerPlayerEntity player) || !inArena(player) || !player.isAlive()) {
            return false;
        }
        if (player.isSpectator() || player.isCreative() || isKnockedOut(player) || current == null) {
            return false;
        }
        Vec3d centre = current.centre();
        double dx = player.getX() - centre.x;
        double dz = player.getZ() - centre.z;
        double r = current.pitRadius() + 0.5;
        int floor = current.origin().getY();
        return dx * dx + dz * dz <= r * r && player.getY() > floor - 2 && player.getY() < floor + current.pitCeiling();
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

    /** An add the boss called: cleared with the event. */
    public static void track(Entity add) {
        ADDS.add(add);
    }

    /**
     * Stack an arena debuff. Fresh instance rather than a longer one, so the
     * icon's amplifier moves; called from a boss's tick, never from inside an
     * effect's own update.
     */
    public static void stack(ServerPlayerEntity player, RegistryEntry<StatusEffect> effect, int add, int max, int ticks) {
        if (player == null || !player.isAlive()) {
            return;
        }
        StatusEffectInstance now = player.getStatusEffect(effect);
        int amplifier = now == null ? add - 1 : Math.min(max, now.getAmplifier() + add);
        player.removeStatusEffect(effect);
        player.addStatusEffect(new StatusEffectInstance(effect, ticks, Math.max(0, amplifier), false, true, true));
    }

    public static void title(ServerPlayerEntity player, Text title, Text subtitle, int in, int stay, int out) {
        player.networkHandler.sendPacket(new TitleFadeS2CPacket(in, stay, out));
        player.networkHandler.sendPacket(new SubtitleS2CPacket(subtitle));
        player.networkHandler.sendPacket(new TitleS2CPacket(title));
    }

    private static ArenaBlueprint blueprintOf(ArenaBoss kind) {
        return BLUEPRINTS.computeIfAbsent(kind.id(), id -> ArenaBlueprint.load(id, kind.origin()));
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
            if (now % 20 == 0 && current != null) {
                current.debuffTick(world(), arenaPlayers(), now);
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
            ArenaBoss drawn = ArenaBosses.draw(server.getOverworld().getRandom(), lastBossId);
            if (drawn != null) {
                startGathering(drawn, ArenaMath.GATHER_TICKS);
            }
        }
    }

    private static void startGathering(ArenaBoss kind, int ticks) {
        ServerWorld world = world();
        current = kind;
        blueprint = blueprintOf(kind);
        stage = Stage.GATHERING;
        stageStart = server.getTicks();
        gatherTicks = ticks;
        DAMAGE.clear();
        NAMES.clear();
        KNOCKED_OUT.clear();
        knockouts = 0;
        blueprint.build(world);
        kind.onBuilt(world, blueprint);

        countdownBar = new ServerBossBar(kind.styledName(), kind.barColour(1), BossBar.Style.NOTCHED_10);
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            countdownBar.addPlayer(player);
            player.playSoundToPlayer(SoundEvents.EVENT_RAID_HORN.value(), SoundCategory.HOSTILE, 1.0F, 0.6F);
            player.playSoundToPlayer(SoundEvents.BLOCK_BELL_RESONATE, SoundCategory.HOSTILE, 0.6F, 0.5F);
        }
        announce(ticks, true);
        TrapCraft.LOGGER.info("arena: omen for {}, gathering for {} ticks", kind.id(), ticks);
    }

    private static void tickGathering(int now) {
        int left = gatherTicks - (now - stageStart);
        if (countdownBar != null) {
            countdownBar.setName(current.styledName().append(Text.literal(" · arena otwiera się za "
                    + ArenaMath.clock(left)).formatted(Formatting.WHITE)));
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
                broadcast(current.nobodyCame());
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
        boss = current.spawn(world, Math.max(1, arenaPlayers().size()));
        fightBar = new ServerBossBar(current.styledName(), current.barColour(1), BossBar.Style.PROGRESS);
        watchBar = new ServerBossBar(current.styledName(), current.barColour(1), BossBar.Style.PROGRESS);
        current.onFightStart(world, blueprint);
        ArenaBoss.Line line = current.fightStart();
        for (ServerPlayerEntity player : arenaPlayers()) {
            title(player, line.title(), line.subtitle(), 20, 70, 20);
            player.playSoundToPlayer(SoundEvents.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.HOSTILE, 1.0F, 0.5F);
            TrapNet.flash(player, 0x2a1b3d, 30);
        }
        broadcast(current.fightBroadcast().copy().append(link("[ WCHODZĘ ]", "/arena join", "Teleport na arenę")));
        TrapCraft.LOGGER.info("arena: {} rises for {} players", current.id(), arenaPlayers().size());
    }

    /** The intro is over; the boss is in play. */
    public static void fightBegins(ArenaBossEntity witness) {
        for (ServerPlayerEntity player : arenaPlayers()) {
            player.sendMessage(Text.literal("Patrzy na ciebie.").formatted(current.colour()), true);
        }
    }

    private static void tickFight(int now) {
        if (boss == null || boss.isRemoved()) {
            endEvent(false, "the boss is gone");
            return;
        }
        if (boss.isDead()) {
            return;
        }
        int left = ArenaMath.FIGHT_TICKS - (now - stageStart);
        float fraction = Math.max(0.0F, boss.getHealth() / boss.getMaxHealth());
        Text name = current.styledName().append(Text.literal(" · " + roman(boss.phase()) + " · "
                + ArenaMath.clock(left)).formatted(Formatting.WHITE));
        for (ServerBossBar bar : new ServerBossBar[]{fightBar, watchBar}) {
            bar.setName(name);
            bar.setPercent(fraction);
            bar.setColor(current.barColour(boss.phase()));
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
        ArenaBoss.Line line = current.enrage();
        for (ServerPlayerEntity player : arenaPlayers()) {
            title(player, line.title(), line.subtitle(), 10, 60, 20);
            player.playSoundToPlayer(SoundEvents.ENTITY_WITHER_DEATH, SoundCategory.HOSTILE, 0.8F, 0.4F);
        }
        broadcast(current.enrageBroadcast());
        ServerWorld world = world();
        if (boss != null && world != null) {
            Vec3d at = boss.getPos();
            world.spawnParticles(net.minecraft.particle.ParticleTypes.REVERSE_PORTAL, at.x, at.y + 1.5, at.z,
                    80, 0.8, 1.5, 0.8, 0.5);
        }
        endEvent(false, "the clock ran out");
    }

    // --- phases, death, loot ------------------------------------------------------------

    public static void onPhase(ArenaBossEntity witness, int phase) {
        ServerWorld world = world();
        current.onPhase(world, blueprint, phase);
        ArenaBoss.Line line = current.phase(phase);
        for (ServerPlayerEntity player : arenaPlayers()) {
            title(player, line.title(), line.subtitle(), 5, 40, 15);
            TrapNet.shake(player, 1.0F, 15);
            TrapNet.flash(player, phase == 3 ? 0x000000 : 0x5a2d9c, 20);
        }
        for (ServerPlayerEntity player : combatants()) {
            player.addStatusEffect(new StatusEffectInstance(TrapContent.adrenalineEffect,
                    ArenaMath.ADRENALINE_PHASE_TICKS, 0, false, true, true));
            player.heal(4.0F);
        }
        broadcast(current.phaseBroadcast(phase));
    }

    public static void onBossDeath(ArenaBossEntity witness) {
        if (stage != Stage.FIGHT) {
            return;
        }
        stage = Stage.VICTORY;
        stageStart = server.getTicks();
        Text name = current.styledName().copy().formatted(Formatting.GOLD)
                .append(Text.literal(" · pokonany").formatted(Formatting.WHITE));
        for (ServerBossBar bar : new ServerBossBar[]{fightBar, watchBar}) {
            if (bar != null) {
                bar.setName(name);
                bar.setPercent(0.0F);
                bar.setColor(BossBar.Color.YELLOW);
            }
        }
        ArenaBoss.Line line = current.victory();
        for (ServerPlayerEntity player : arenaPlayers()) {
            title(player, line.title(), line.subtitle(), 10, 80, 30);
            TrapNet.shake(player, 1.2F, 20);
            TrapAwards.grant(player, current.killAward());
            if (knockouts == 0 && DAMAGE.getOrDefault(player.getUuid(), 0.0F) > 0.0F) {
                TrapAwards.grant(player, "unbroken");
            }
        }
        clearAdds();
    }

    /** The body has gone up. Loot, the light show, the table. */
    public static void victoryBurst(ArenaBossEntity witness) {
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
                .append(Text.literal("\n" + current.victoryHeader() + "\n").formatted(Formatting.GOLD, Formatting.BOLD));
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
                player.getInventory().offerOrDrop(new ItemStack(current.trophy()));
            }
            player.addStatusEffect(new StatusEffectInstance(TrapContent.adrenalineEffect,
                    ArenaMath.ADRENALINE_WIN_TICKS, 0, false, true, true));
            player.playSoundToPlayer(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 1.0F, 1.0F);
            player.playSoundToPlayer(SoundEvents.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 0.8F, 0.8F);
        }
        table.append(Text.literal("  Skrzynka Widmo dla każdego").formatted(Formatting.LIGHT_PURPLE));
        if (top != null) {
            table.append(Text.literal(", Klucz Widmo i " + current.trophyName() + " dla " + top)
                    .formatted(Formatting.LIGHT_PURPLE));
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

        // The show: veins relit one by one round the ring, bursts overhead.
        // Particles and block updates only. Firework rockets crashed every
        // client on this pack (Farmer's Delight hands the firework particle
        // a StarParticle and vanilla casts it), and a rocket frozen in an
        // unloaded chunk crashed them again on every join until it was killed.
        showUntil = server.getTicks() + SHOW_TICKS;
        showVeins = new ArrayList<>(blueprint.tagged("vein"));
        Vec3d centre = current.centre();
        showVeins.sort(Comparator.comparingDouble(pos ->
                Math.atan2(pos.getZ() + 0.5 - centre.z, pos.getX() + 0.5 - centre.x)));
        TrapCraft.LOGGER.info("arena: {} fell to {} players, pool {}e", current.id(), players, pool);
    }

    private static void tickVictory(int now) {
        ServerWorld world = world();
        if (world != null && now < showUntil) {
            current.showTick(world, blueprint, showVeins, now - (showUntil - SHOW_TICKS));
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

    private static void clearAdds() {
        for (Entity add : ADDS) {
            if (!add.isRemoved()) {
                add.discard();
            }
        }
        ADDS.clear();
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
        clearAdds();
        for (ServerPlayerEntity player : arenaPlayers()) {
            sendHome(player, won ? "Arena zamknięta. Wracasz z łupem." : "Arena zamknięta. Wracasz.");
        }
        if (current != null && world() != null) {
            current.onEnd(world(), blueprint);
            lastBossId = current.id();
        }
        stage = Stage.IDLE;
        String was = current == null ? "?" : current.id();
        current = null;
        lastEventEnd = System.currentTimeMillis() / 1000;
        KNOCKED_OUT.clear();
        save();
        TrapCraft.LOGGER.info("arena: {} over ({})", was, why);
    }

    // --- coming and going -----------------------------------------------------------

    private static int join(ServerCommandSource source) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            return err(source, "tylko dla graczy");
        }
        if (stage == Stage.IDLE || current == null) {
            source.sendFeedback(() -> Text.literal("Na arenie jest cicho. Zwiastun przyjdzie na czat.")
                    .formatted(Formatting.RED), false);
            return 0;
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
        Vec3d gate = current.gate();
        player.teleport(world, gate.x, gate.y, gate.z, Set.of(), current.gateYaw(), 0.0F, true);
        player.playSoundToPlayer(SoundEvents.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 1.0F, 0.7F);
        player.playSoundToPlayer(SoundEvents.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, 0.8F, 0.5F);
        TrapNet.flash(player, 0x2a1b3d, 25);
        current.onEnter(player);
        player.sendMessage(Text.literal("Jesteś na arenie. ").formatted(current.colour())
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
        for (ArenaBoss kind : ArenaBosses.all()) {
            kind.onLeave(player);
        }
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
        player.setFrozenTicks(0);
        player.getHungerManager().setFoodLevel(Math.max(player.getHungerManager().getFoodLevel(), 12));
        for (ArenaBoss kind : ArenaBosses.all()) {
            kind.onKnockout(player);
        }
        player.fallDistance = 0.0;
        ArenaBoss kind = current == null ? WitnessBoss.INSTANCE : current;
        Vec3d spot = kind.standsSpot(world.getRandom());
        Vec3d centre = kind.centre();
        float yaw = (float) Math.toDegrees(Math.atan2(-(centre.x - spot.x), centre.z - spot.z));
        player.teleport(world, spot.x, spot.y, spot.z, Set.of(), yaw, 10.0F, true);
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
            Text line = current.knockoutLine(player.getNameForScoreboard());
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
            if (player == null || world == null || !inArena(player) || stage != Stage.FIGHT || current == null) {
                continue;
            }
            Vec3d gate = current.gate();
            player.teleport(world, gate.x, gate.y, gate.z, Set.of(), current.gateYaw(), 0.0F, true);
            title(player, Text.literal("WRACASZ").formatted(Formatting.GREEN, Formatting.BOLD),
                    Text.literal("Do roboty.").formatted(Formatting.GRAY), 5, 30, 10);
            player.playSoundToPlayer(SoundEvents.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 1.0F, 1.2F);
        }
    }

    private static void voidCatch() {
        ServerWorld world = world();
        if (world == null || current == null) {
            return;
        }
        for (ServerPlayerEntity player : arenaPlayers()) {
            if (player.getY() < current.voidY()) {
                player.fallDistance = 0.0;
                Vec3d gate = current.gate();
                player.teleport(world, gate.x, gate.y, gate.z, Set.of(), current.gateYaw(), 0.0F, true);
                player.sendMessage(Text.literal("Pod areną nie ma nic. Wracasz na bramę.").formatted(Formatting.GRAY), true);
            }
        }
    }

    // --- words --------------------------------------------------------------------------

    private static void announce(int ticksLeft, boolean first) {
        MutableText text = Text.empty();
        if (first) {
            text.append(Text.literal("\n✦ ARENA ✦\n").formatted(current.colour(), Formatting.BOLD));
            text.append(current.omen(ArenaMath.clock(ticksLeft)));
        } else {
            text.append(current.omenAgain(ArenaMath.clock(ticksLeft)));
        }
        text.append(Text.literal("   ")).append(link("[ WCHODZĘ NA ARENĘ ]", "/arena join",
                "Teleport na arenę. Wracasz przez /arena leave.")).append(Text.literal("\n"));
        broadcast(text);
    }

    public static MutableText link(String label, String command, String hover) {
        return Text.literal(label).formatted(Formatting.GREEN, Formatting.BOLD)
                .styled(style -> style.withClickEvent(new ClickEvent.RunCommand(command))
                        .withHoverEvent(new HoverEvent.ShowText(Text.literal(hover))));
    }

    public static void broadcast(Text text) {
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
                    MutableText help = Text.empty()
                            .append(Text.literal("Arena\n").formatted(Formatting.DARK_PURPLE, Formatting.BOLD))
                            .append(Text.literal("  /arena join   wejdź, kiedy boss jest na arenie\n").formatted(Formatting.GRAY))
                            .append(Text.literal("  /arena leave  wróć tam, gdzie byłeś\n").formatted(Formatting.GRAY))
                            .append(Text.literal("  /guide arena  jak z nimi walczyć\n").formatted(Formatting.GRAY))
                            .append(Text.literal("  Bossowie: ").formatted(Formatting.DARK_GRAY));
                    for (ArenaBoss kind : ArenaBosses.all()) {
                        help.append(Text.literal(ArenaBoss.cap(kind.displayName()) + " ").formatted(kind.colour()));
                    }
                    context.getSource().sendFeedback(() -> help, false);
                    return 1;
                })
                .then(CommandManager.literal("join").executes(context -> join(context.getSource())))
                .then(CommandManager.literal("leave").executes(context -> leave(context.getSource())));

        var start = CommandManager.literal("start")
                .requires(source -> source.hasPermissionLevel(2))
                .executes(context -> start(context.getSource(), null, ArenaMath.GATHER_TICKS / 20))
                .then(CommandManager.argument("seconds", IntegerArgumentType.integer(5, 600))
                        .executes(context -> start(context.getSource(), null,
                                IntegerArgumentType.getInteger(context, "seconds"))));
        var tp = CommandManager.literal("tp").requires(source -> source.hasPermissionLevel(2));
        var build = CommandManager.literal("build").requires(source -> source.hasPermissionLevel(2));
        for (ArenaBoss kind : ArenaBosses.all()) {
            start.then(CommandManager.literal(kind.id())
                    .executes(context -> start(context.getSource(), kind, ArenaMath.GATHER_TICKS / 20))
                    .then(CommandManager.argument("seconds", IntegerArgumentType.integer(5, 600))
                            .executes(context -> start(context.getSource(), kind,
                                    IntegerArgumentType.getInteger(context, "seconds")))));
            tp.then(CommandManager.literal(kind.id()).executes(context -> visit(context.getSource(), kind)));
            build.then(CommandManager.literal(kind.id()).executes(context -> {
                ServerWorld world = world();
                if (world == null) {
                    return err(context.getSource(), "brak wymiaru areny");
                }
                ArenaBlueprint plan = blueprintOf(kind);
                plan.build(world);
                kind.onBuilt(world, plan);
                return ok(context.getSource(), kind.id() + ": " + plan.size() + " bloków");
            }));
        }
        root.then(start).then(tp).then(build)
                .then(CommandManager.literal("stop")
                        .requires(source -> source.hasPermissionLevel(2))
                        .executes(context -> {
                            if (stage == Stage.IDLE) {
                                return err(context.getSource(), "nic nie trwa");
                            }
                            broadcast(Text.literal("Arena przerwana.").formatted(Formatting.GRAY));
                            endEvent(false, "stopped by " + context.getSource().getName());
                            return ok(context.getSource(), "przerwane");
                        }));

        var cast = CommandManager.literal("cast").requires(source -> source.hasPermissionLevel(2));
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        ids.add("phase");
        for (ArenaBoss kind : ArenaBosses.all()) {
            ids.addAll(kind.abilities());
        }
        for (String id : ids) {
            cast.then(CommandManager.literal(id).executes(context -> {
                if (boss == null || !boss.isAlive() || stage != Stage.FIGHT) {
                    return err(context.getSource(), "nie ma teraz bossa");
                }
                if (!boss.begin(id)) {
                    return err(context.getSource(), current.displayName() + " nie ma ataku " + id);
                }
                return ok(context.getSource(), "cast " + id);
            }));
        }
        root.then(cast);
        dispatcher.register(root);
    }

    private static int start(ServerCommandSource source, ArenaBoss kind, int seconds) {
        if (world() == null) {
            return err(source, "brak wymiaru areny -- sprawdź log startu");
        }
        if (stage != Stage.IDLE) {
            return err(source, "arena już trwa");
        }
        if (kind == null) {
            kind = ArenaBosses.draw(server.getOverworld().getRandom(), lastBossId);
        }
        if (kind == null) {
            return err(source, "nie ma żadnego bossa");
        }
        startGathering(kind, seconds * 20);
        return ok(source, kind.id() + ": zwiastun wysłany, " + seconds + " s do walki");
    }

    /** Have a look at a pit without an event. Same door home. */
    private static int visit(ServerCommandSource source, ArenaBoss kind) {
        ServerPlayerEntity player = source.getPlayer();
        ServerWorld world = world();
        if (player == null || world == null) {
            return err(source, "brak gracza albo wymiaru");
        }
        if (stage == Stage.IDLE) {
            ArenaBlueprint plan = blueprintOf(kind);
            plan.build(world);
            kind.onBuilt(world, plan);
        }
        ORIGINS.putIfAbsent(player.getUuid(), Origin.of(player));
        save();
        Vec3d gate = kind.gate();
        player.teleport(world, gate.x, gate.y, gate.z, Set.of(), kind.gateYaw(), 0.0F, true);
        return ok(source, kind.id() + "; /arena leave wraca");
    }

    // --- storage ----------------------------------------------------------------------------

    private static void load(MinecraftServer s) {
        server = s;
        saveFile = s.getSavePath(WorldSavePath.ROOT).resolve("trapcraft-arena.txt");
        BLUEPRINTS.clear();
        for (ArenaBoss kind : ArenaBosses.all()) {
            blueprintOf(kind);
        }
        try {
            if (Files.exists(saveFile)) {
                for (String line : Files.readAllLines(saveFile)) {
                    String[] parts = line.trim().split(" ");
                    if (parts.length >= 2 && parts[0].equals("last")) {
                        lastEventEnd = Long.parseLong(parts[1]);
                        if (parts.length >= 3) {
                            lastBossId = parts[2];
                        }
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
            TrapCraft.LOGGER.info("arena: dimension {} ready, {} bosses, {} origins on file, last was {}",
                    WORLD_KEY.getValue(), ArenaBosses.all().size(), ORIGINS.size(), lastBossId);
        }
    }

    private static void save() {
        if (saveFile == null) {
            return;
        }
        StringBuilder out = new StringBuilder();
        out.append("last ").append(lastEventEnd).append(' ').append(lastBossId == null ? "-" : lastBossId).append('\n');
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
