package dev.heezq.trapcraft;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;

import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Essentials-lite for TrapPack: short aliases for the commands people actually
 * type, and nothing else.
 *
 * The whole point of /gm over vanilla /gamemode is the `false` in every
 * sendFeedback() call -- it means "don't broadcast this to the ops", so
 * switching to creative is between you and the server log.
 */
public final class TrapEssentials {
    private static final int OP = 2;
    private static final List<String> MODES = List.of("c", "s", "a", "sp", "0", "1", "2", "3");
    private static final int MAX_HOMES = 10;

    private static final Gson GSON = new Gson();
    private static final Type HOMES_TYPE = new TypeToken<Map<String, Map<String, Loc>>>() {}.getType();

    private static Path saveFile;
    private static Map<String, Map<String, Loc>> homes = new HashMap<>();

    // ponytail: in-memory only. /back forgets on restart, which is the correct
    // amount of memory for "oops I fell in lava" to have.
    // No /tpa here on purpose -- tpa-utilities is already in the pack.
    private static final Map<UUID, Loc> back = new HashMap<>();

    private TrapEssentials() {}

    /** Plain fields, not a record: Gson deserialises those without a codec. */
    public static final class Loc {
        public String world;
        public double x, y, z;
        public float yaw, pitch;

        static Loc of(ServerPlayerEntity p) {
            Loc l = new Loc();
            l.world = p.getWorld().getRegistryKey().getValue().toString();
            l.x = p.getX();
            l.y = p.getY();
            l.z = p.getZ();
            l.yaw = p.getYaw();
            l.pitch = p.getPitch();
            return l;
        }
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(TrapEssentials::load);

        // /back after dying is the feature; the pre-teleport one is a bonus.
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damage) -> {
            if (entity instanceof ServerPlayerEntity p) back.put(p.getUuid(), Loc.of(p));
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, access, env) -> {
            gamemode(dispatcher);

            simple(dispatcher, "day", OP, c -> setTime(c.getSource(), 1000, "day"));
            simple(dispatcher, "noon", OP, c -> setTime(c.getSource(), 6000, "noon"));
            simple(dispatcher, "night", OP, c -> setTime(c.getSource(), 13000, "night"));
            simple(dispatcher, "midnight", OP, c -> setTime(c.getSource(), 18000, "midnight"));

            simple(dispatcher, "sun", OP, c -> weather(c.getSource(), false, false, "clear"));
            simple(dispatcher, "rain", OP, c -> weather(c.getSource(), true, false, "rain"));
            simple(dispatcher, "storm", OP, c -> weather(c.getSource(), true, true, "storm"));

            onPlayers(dispatcher, "heal", OP, p -> {
                p.setHealth(p.getMaxHealth());
                p.getHungerManager().setFoodLevel(20);
                p.getHungerManager().setSaturationLevel(20f);
                p.clearStatusEffects();
                return "healed " + p.getNameForScoreboard();
            });
            onPlayers(dispatcher, "feed", OP, p -> {
                p.getHungerManager().setFoodLevel(20);
                p.getHungerManager().setSaturationLevel(20f);
                return "fed " + p.getNameForScoreboard();
            });
            onPlayers(dispatcher, "fly", OP, p -> {
                boolean on = !p.getAbilities().allowFlying;
                p.getAbilities().allowFlying = on;
                if (!on) p.getAbilities().flying = false;
                p.sendAbilitiesUpdate();
                return "fly " + (on ? "on" : "off") + " for " + p.getNameForScoreboard();
            });

            homeCommands(dispatcher);
            travelCommands(dispatcher);
        });
    }

    // --- registration helpers -------------------------------------------------

    private static void simple(CommandDispatcher<ServerCommandSource> d, String name, int perm,
                               Command<ServerCommandSource> run) {
        d.register(CommandManager.literal(name).requires(s -> s.hasPermissionLevel(perm)).executes(run));
    }

    /** `/x` on yourself, `/x <targets>` on others -- same body either way. */
    private static void onPlayers(CommandDispatcher<ServerCommandSource> d, String name, int perm,
                                  Function<ServerPlayerEntity, String> action) {
        d.register(CommandManager.literal(name)
                .requires(s -> s.hasPermissionLevel(perm))
                .executes(c -> {
                    ServerPlayerEntity self = c.getSource().getPlayer();
                    if (self == null) return err(c.getSource(), "players only");
                    return ok(c.getSource(), action.apply(self));
                })
                .then(CommandManager.argument("targets", EntityArgumentType.players())
                        .executes(c -> {
                            String last = "nobody matched";
                            for (ServerPlayerEntity p : EntityArgumentType.getPlayers(c, "targets")) {
                                last = action.apply(p);
                            }
                            return ok(c.getSource(), last);
                        })));
    }

    private static void gamemode(CommandDispatcher<ServerCommandSource> d) {
        d.register(CommandManager.literal("gm")
                .requires(s -> s.hasPermissionLevel(OP))
                .then(CommandManager.argument("mode", StringArgumentType.word())
                        .suggests((c, b) -> CommandSource.suggestMatching(MODES, b))
                        .executes(c -> {
                            GameMode mode = mode(StringArgumentType.getString(c, "mode"));
                            ServerPlayerEntity self = c.getSource().getPlayer();
                            if (mode == null) return err(c.getSource(), "modes: c s a sp (or 0-3)");
                            if (self == null) return err(c.getSource(), "players only");
                            self.changeGameMode(mode);
                            return ok(c.getSource(), name(mode));
                        })
                        .then(CommandManager.argument("targets", EntityArgumentType.players())
                                .executes(c -> {
                                    GameMode mode = mode(StringArgumentType.getString(c, "mode"));
                                    if (mode == null) return err(c.getSource(), "modes: c s a sp (or 0-3)");
                                    int n = 0;
                                    for (ServerPlayerEntity p : EntityArgumentType.getPlayers(c, "targets")) {
                                        p.changeGameMode(mode);
                                        tell(p, name(mode));
                                        n++;
                                    }
                                    return ok(c.getSource(), name(mode) + " for " + n);
                                }))));
    }

    private static void homeCommands(CommandDispatcher<ServerCommandSource> d) {
        d.register(CommandManager.literal("sethome")
                .executes(c -> setHome(c.getSource(), "home"))
                .then(CommandManager.argument("name", StringArgumentType.word())
                        .executes(c -> setHome(c.getSource(), StringArgumentType.getString(c, "name")))));

        d.register(CommandManager.literal("home")
                .executes(c -> goHome(c.getSource(), "home"))
                .then(CommandManager.argument("name", StringArgumentType.word())
                        .suggests((c, b) -> CommandSource.suggestMatching(myHomes(c.getSource()), b))
                        .executes(c -> goHome(c.getSource(), StringArgumentType.getString(c, "name")))));

        d.register(CommandManager.literal("delhome")
                .then(CommandManager.argument("name", StringArgumentType.word())
                        .suggests((c, b) -> CommandSource.suggestMatching(myHomes(c.getSource()), b))
                        .executes(c -> {
                            ServerPlayerEntity p = c.getSource().getPlayer();
                            if (p == null) return err(c.getSource(), "players only");
                            String name = StringArgumentType.getString(c, "name");
                            if (mine(p).remove(name) == null) return err(c.getSource(), "no home '" + name + "'");
                            save();
                            return ok(c.getSource(), "deleted " + name);
                        })));

        simple(d, "homes", 0, c -> ok(c.getSource(), String.join(", ", myHomes(c.getSource()))));
    }

    private static void travelCommands(CommandDispatcher<ServerCommandSource> d) {
        simple(d, "spawn", 0, c -> {
            ServerPlayerEntity p = c.getSource().getPlayer();
            if (p == null) return err(c.getSource(), "players only");
            ServerWorld w = c.getSource().getServer().getOverworld();
            BlockPos s = w.getSpawnPos();
            teleport(p, w, s.getX() + 0.5, s.getY(), s.getZ() + 0.5, p.getYaw(), p.getPitch());
            return ok(c.getSource(), "spawn");
        });

        simple(d, "back", 0, c -> {
            ServerPlayerEntity p = c.getSource().getPlayer();
            if (p == null) return err(c.getSource(), "players only");
            Loc l = back.get(p.getUuid());
            if (l == null) return err(c.getSource(), "nowhere to go back to");
            return goTo(c.getSource(), p, l, "back");
        });
    }

    // --- bodies ---------------------------------------------------------------

    private static GameMode mode(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "s", "0", "survival" -> GameMode.SURVIVAL;
            case "c", "1", "creative" -> GameMode.CREATIVE;
            case "a", "2", "adventure" -> GameMode.ADVENTURE;
            case "sp", "3", "spectator" -> GameMode.SPECTATOR;
            default -> null;
        };
    }

    private static String name(GameMode m) {
        return m.name().toLowerCase(Locale.ROOT);
    }

    private static int setTime(ServerCommandSource src, long target, String label) {
        for (ServerWorld w : src.getServer().getWorlds()) {
            // Roll forward to the next occurrence instead of vanilla's absolute
            // set, so the day counter survives someone spamming /day.
            long now = w.getTimeOfDay();
            long next = now - Math.floorMod(now, 24000L) + target;
            if (next <= now) next += 24000L;
            w.setTimeOfDay(next);
        }
        return ok(src, label);
    }

    private static int weather(ServerCommandSource src, boolean raining, boolean thundering, String label) {
        ServerWorld w = src.getServer().getOverworld();
        w.setWeather(raining ? 0 : 12000, raining ? 12000 : 0, raining, thundering);
        return ok(src, label);
    }

    private static Map<String, Loc> mine(ServerPlayerEntity p) {
        return homes.computeIfAbsent(p.getUuidAsString(), k -> new HashMap<>());
    }

    private static List<String> myHomes(ServerCommandSource src) {
        ServerPlayerEntity p = src.getPlayer();
        return p == null ? List.of() : new ArrayList<>(mine(p).keySet());
    }

    private static int setHome(ServerCommandSource src, String name) {
        ServerPlayerEntity p = src.getPlayer();
        if (p == null) return err(src, "players only");
        if (name.length() > 16) return err(src, "name too long");
        Map<String, Loc> mine = mine(p);
        if (mine.size() >= MAX_HOMES && !mine.containsKey(name)) return err(src, "max " + MAX_HOMES + " homes");
        mine.put(name, Loc.of(p));
        save();
        return ok(src, "home '" + name + "' set");
    }

    private static int goHome(ServerCommandSource src, String name) {
        ServerPlayerEntity p = src.getPlayer();
        if (p == null) return err(src, "players only");
        Loc l = mine(p).get(name);
        if (l == null) return err(src, "no home '" + name + "' -- /sethome first");
        return goTo(src, p, l, name);
    }

    private static int goTo(ServerCommandSource src, ServerPlayerEntity p, Loc l, String label) {
        Identifier id = Identifier.tryParse(l.world);
        ServerWorld w = id == null ? null : src.getServer().getWorld(RegistryKey.of(RegistryKeys.WORLD, id));
        if (w == null) return err(src, "that world is gone");
        teleport(p, w, l.x, l.y, l.z, l.yaw, l.pitch);
        return ok(src, label);
    }

    private static void teleport(ServerPlayerEntity p, ServerWorld w, double x, double y, double z,
                                 float yaw, float pitch) {
        back.put(p.getUuid(), Loc.of(p));
        p.teleport(w, x, y, z, Set.of(), yaw, pitch, true);
    }

    // --- output ---------------------------------------------------------------

    /** `false` = do not broadcast to ops. That is the entire feature. */
    private static int ok(ServerCommandSource src, String msg) {
        src.sendFeedback(() -> Text.literal(msg).formatted(Formatting.GRAY), false);
        return 1;
    }

    private static int err(ServerCommandSource src, String msg) {
        src.sendFeedback(() -> Text.literal(msg).formatted(Formatting.RED), false);
        return 0;
    }

    private static void tell(ServerPlayerEntity p, String msg) {
        p.sendMessage(Text.literal(msg).formatted(Formatting.GRAY), false);
    }

    // --- storage --------------------------------------------------------------

    private static void load(MinecraftServer server) {
        saveFile = server.getSavePath(WorldSavePath.ROOT).resolve("trapessentials.json");
        try {
            if (Files.exists(saveFile)) homes = GSON.fromJson(Files.readString(saveFile), HOMES_TYPE);
        } catch (Exception e) {
            TrapCraft.LOGGER.error("homes unreadable, starting empty", e);
        }
        if (homes == null) homes = new HashMap<>();
    }

    /** ponytail: whole-file rewrite per /sethome. Homes change a few times a day. */
    private static void save() {
        try {
            Files.writeString(saveFile, GSON.toJson(homes));
        } catch (Exception e) {
            TrapCraft.LOGGER.error("could not save homes", e);
        }
    }
}
