package dev.heezq.trapcraft;

import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.block.BlockState;
import net.minecraft.entity.EntityType;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitiesDestroyS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Show one player something that isn't there.
 *
 * The world is never modified -- every fake block here is a render lie sent to
 * a single client, and the real chunk data is untouched. That only STAYS true
 * because every lie is tracked and reverted: on expiry, on disconnect, on
 * respawn, and on world unload. A leaked fake block is indistinguishable from
 * world corruption to the player it is stuck on, so the bookkeeping lives in
 * one place rather than in each feature that wants an illusion.
 *
 * Two very different callers: Paranoia uses this to unsettle you, the Ledger
 * uses it to help you find your iron. That is why it is a general "lie to one
 * client" utility and not a scare library.
 */
public final class TrapPhantom {
    /**
     * Fake entity ids count down from here.
     *
     * Real entity ids come from a counter that starts at 0 and climbs, so
     * starting two million below zero means a phantom can never collide with a
     * real entity -- which would otherwise let a hallucination delete
     * somebody's minecart client-side.
     */
    private static final int ID_FLOOR = -2_000_000;
    private static int nextId = ID_FLOOR;

    private record Lie(BlockPos pos, long expiresAtTick) {
    }

    private static final Map<UUID, List<Lie>> BLOCKS = new HashMap<>();
    private static final Map<UUID, Set<Integer>> FIGURES = new HashMap<>();

    private TrapPhantom() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                tick(player);
            }
        });

        // forget() rather than clearAll(): the connection is already closing, so
        // revert packets would go nowhere, and the client will re-request every
        // chunk on next login anyway.
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                forget(handler.getPlayer().getUuid()));

        // Respawn reloads chunks client-side, so the lies are gone visually and
        // only the bookkeeping needs dropping.
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) ->
                forget(newPlayer.getUuid()));
    }

    // --- fake blocks ----------------------------------------------------------

    /** Render {@code state} at {@code pos} for this player only, for {@code ticks}. */
    public static void fakeBlock(ServerPlayerEntity player, BlockPos pos, BlockState state, int ticks) {
        player.networkHandler.sendPacket(new BlockUpdateS2CPacket(pos, state));
        BLOCKS.computeIfAbsent(player.getUuid(), key -> new ArrayList<>())
                .add(new Lie(pos.toImmutable(), player.getWorld().getTime() + ticks));
    }

    /**
     * Put the truth back.
     *
     * Goes through the server's own markForUpdate rather than a hand-built
     * packet. A hand-built one carries the raw server-side state and skips the
     * path Polymer's translation mixins hook, so a reverted block could arrive
     * as an id the client couldn't resolve. The client then kept the fake
     * block's COLLISION SHAPE while rendering nothing -- a floating outline you
     * could put a crosshair on but never touch.
     *
     * This rebroadcasts to everyone tracking the chunk rather than just the one
     * player, and that is the point: it is the server restating what is
     * actually there, so whatever any client believed gets overwritten.
     */
    private static void revert(ServerPlayerEntity player, BlockPos pos) {
        player.getWorld().getChunkManager().markForUpdate(pos);
    }

    /** Expire whatever is due for this player. Cheap when there is nothing. */
    private static void tick(ServerPlayerEntity player) {
        List<Lie> lies = BLOCKS.get(player.getUuid());
        if (lies == null || lies.isEmpty()) {
            return;
        }
        long now = player.getWorld().getTime();
        lies.removeIf(lie -> {
            if (now < lie.expiresAtTick()) {
                return false;
            }
            revert(player, lie.pos());
            return true;
        });
    }

    // --- senders --------------------------------------------------------------

    /**
     * A sound only this player hears, from somewhere they did not expect.
     *
     * Takes a bare SoundEvent and wraps it because SoundEvents is inconsistent:
     * most fields are plain SoundEvent, a handful are RegistryEntry.Reference.
     * Wrapping here means callers never have to care which kind they grabbed.
     */
    public static void sound(ServerPlayerEntity player, Vec3d pos, SoundEvent event,
                             float volume, float pitch) {
        sound(player, pos, event, SoundCategory.AMBIENT, volume, pitch);
    }

    /** As above, but for sounds that belong to a category other than ambience. */
    public static void sound(ServerPlayerEntity player, Vec3d pos, SoundEvent event,
                             SoundCategory category, float volume, float pitch) {
        player.networkHandler.sendPacket(new PlaySoundS2CPacket(
                RegistryEntry.of(event), category,
                pos.x, pos.y, pos.z, volume, pitch,
                player.getWorld().getRandom().nextLong()));
    }

    /** Particles only this player sees. */
    public static void particles(ServerPlayerEntity player, ParticleEffect effect,
                                 Vec3d pos, int count, double spread, double speed) {
        // force=true so they still render at distance; important=false because
        // no illusion is worth overriding a client's particle settings.
        player.getWorld().spawnParticles(player, effect, true, false,
                pos.x, pos.y, pos.z, count, spread, spread, spread, speed);
    }

    // --- figures --------------------------------------------------------------

    /**
     * Something standing where nothing stands. Returns its id.
     *
     * The caller MUST hand that id back to {@link #clearFigure}; otherwise it
     * stands there until the player relogs, and a pillager frozen in a field
     * forever stops being frightening and starts being a bug report.
     */
    public static int figure(ServerPlayerEntity player, EntityType<?> type, Vec3d pos, float yaw) {
        int id = reserveId();
        // headYaw must match the body yaw. Passing 0 there turned the body to
        // face the player while leaving the HEAD pointing due south, so it read
        // as a mob idly looking away rather than something watching you.
        player.networkHandler.sendPacket(new EntitySpawnS2CPacket(
                id, UUID.randomUUID(), pos.x, pos.y, pos.z,
                0.0F, yaw, type, 0, Vec3d.ZERO, yaw));
        FIGURES.computeIfAbsent(player.getUuid(), key -> new HashSet<>()).add(id);
        return id;
    }

    public static void clearFigure(ServerPlayerEntity player, int id) {
        Set<Integer> ids = FIGURES.get(player.getUuid());
        if (ids != null && ids.remove(id)) {
            player.networkHandler.sendPacket(new EntitiesDestroyS2CPacket(id));
        }
    }

    private static synchronized int reserveId() {
        return nextId--;
    }

    // --- lifecycle ------------------------------------------------------------

    /** Everything, for one player. Safe when there is nothing to clear. */
    public static void clearAll(ServerPlayerEntity player) {
        List<Lie> lies = BLOCKS.remove(player.getUuid());
        if (lies != null) {
            for (Lie lie : lies) {
                revert(player, lie.pos());
            }
        }
        Set<Integer> ids = FIGURES.remove(player.getUuid());
        if (ids != null && !ids.isEmpty()) {
            player.networkHandler.sendPacket(new EntitiesDestroyS2CPacket(
                    ids.stream().mapToInt(Integer::intValue).toArray()));
        }
    }

    /** Drop state without sending anything -- for a player already gone. */
    public static void forget(UUID player) {
        BLOCKS.remove(player);
        FIGURES.remove(player);
    }
}
