package dev.heezq.trapcraft;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.util.List;

/**
 * One packet: "this player just did this gesture".
 *
 * Everything else in the mod is server-authoritative blockstates that vanilla
 * clients render for free. Player animations can't work that way -- the pose
 * lives on the client model -- so this is the one place we have to talk.
 *
 * Deliberately a plain animation id rather than a "tlok pull" packet: the bong
 * and the joint will want the same thing, and an Identifier costs no more to
 * send than a boolean would.
 */
public final class TrapNet {
    public record PlayAnim(Identifier anim, int entityId) implements CustomPayload {
        public static final CustomPayload.Id<PlayAnim> ID = new CustomPayload.Id<>(TrapCraft.id("play_anim"));

        public static final PacketCodec<RegistryByteBuf, PlayAnim> CODEC = PacketCodec.tuple(
                Identifier.PACKET_CODEC, PlayAnim::anim,
                PacketCodecs.VAR_INT, PlayAnim::entityId,
                PlayAnim::new);

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /**
     * The exact mix behind a blend high.
     *
     * The Baked amplifier already tells the client it's a two/three/four-way
     * blend, and that half survives everything because it lives on the status
     * effect. This carries WHICH strains, so the wash can be built from the
     * real colours instead of a generic blend palette. Lost on relog, at which
     * point the client falls back to the amplifier -- less specific, still
     * correct in character.
     */
    public record BlendMix(List<Integer> parts, int colour) implements CustomPayload {
        public static final CustomPayload.Id<BlendMix> ID =
                new CustomPayload.Id<>(TrapCraft.id("blend_mix"));

        public static final PacketCodec<RegistryByteBuf, BlendMix> CODEC = PacketCodec.tuple(
                PacketCodecs.VAR_INT.collect(PacketCodecs.toList()), BlendMix::parts,
                PacketCodecs.VAR_INT, BlendMix::colour,
                BlendMix::new);

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /**
     * The arena wants the camera.
     *
     * A shake for the slam and the phase breaks, a flash for a hit and a
     * teleport. Both are one number and a length, both are optional the same
     * way every other packet here is: a client without the mod never
     * registered a receiver and simply feels nothing.
     */
    public record Shake(float strength, int ticks) implements CustomPayload {
        public static final CustomPayload.Id<Shake> ID = new CustomPayload.Id<>(TrapCraft.id("shake"));

        public static final PacketCodec<RegistryByteBuf, Shake> CODEC = PacketCodec.tuple(
                PacketCodecs.FLOAT, Shake::strength,
                PacketCodecs.VAR_INT, Shake::ticks,
                Shake::new);

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    public record Flash(int colour, int ticks) implements CustomPayload {
        public static final CustomPayload.Id<Flash> ID = new CustomPayload.Id<>(TrapCraft.id("flash"));

        public static final PacketCodec<RegistryByteBuf, Flash> CODEC = PacketCodec.tuple(
                PacketCodecs.INTEGER, Flash::colour,
                PacketCodecs.VAR_INT, Flash::ticks,
                Flash::new);

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    public static final Identifier TLOK_PULL = TrapCraft.id("tlok_pull");
    public static final Identifier BONG_HIT = TrapCraft.id("bong_hit");
    public static final Identifier MIX_STIR = TrapCraft.id("mix_stir");
    public static final Identifier JOINT_SMOKE = TrapCraft.id("joint_smoke");
    public static final Identifier SHOOT_UP = TrapCraft.id("shoot_up");

    private TrapNet() {
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(PlayAnim.ID, PlayAnim.CODEC);
        PayloadTypeRegistry.playS2C().register(BlendMix.ID, BlendMix.CODEC);
        PayloadTypeRegistry.playS2C().register(Shake.ID, Shake.CODEC);
        PayloadTypeRegistry.playS2C().register(Flash.ID, Flash.CODEC);
    }

    /** Rattle one player's camera. Silently nothing for a client without the mod. */
    public static void shake(ServerPlayerEntity player, float strength, int ticks) {
        if (player != null && ServerPlayNetworking.canSend(player, Shake.ID)) {
            ServerPlayNetworking.send(player, new Shake(strength, ticks));
        }
    }

    /** A colour over one player's screen, fading over {@code ticks}. */
    public static void flash(ServerPlayerEntity player, int colour, int ticks) {
        if (player != null && ServerPlayNetworking.canSend(player, Flash.ID)) {
            ServerPlayNetworking.send(player, new Flash(colour, ticks));
        }
    }

    /** Tell one player's client what they just smoked. */
    public static void sendBlend(PlayerEntity player, Blend blend) {
        if (!(player instanceof ServerPlayerEntity actor)
                || !ServerPlayNetworking.canSend(actor, BlendMix.ID)) {
            return;
        }
        ServerPlayNetworking.send(actor, new BlendMix(
                blend.parts().stream().map(Enum::ordinal).toList(), blend.colour()));
    }

    /**
     * Send to everyone who can see the player, including the player.
     *
     * canSend() is the whole reason this degrades gracefully: a client without
     * TrapCraft (or without the animation library) never registered the
     * receiver, so it's skipped and simply sees no gesture. Nothing breaks.
     */
    public static void play(PlayerEntity player, Identifier anim) {
        if (!(player instanceof ServerPlayerEntity actor)) {
            return;
        }
        PlayAnim payload = new PlayAnim(anim, actor.getId());
        // tracking() is everyone watching the entity, which never includes the
        // entity itself -- so the actor is sent to separately.
        for (ServerPlayerEntity viewer : PlayerLookup.tracking(actor)) {
            if (ServerPlayNetworking.canSend(viewer, PlayAnim.ID)) {
                ServerPlayNetworking.send(viewer, payload);
            }
        }
        if (ServerPlayNetworking.canSend(actor, PlayAnim.ID)) {
            ServerPlayNetworking.send(actor, payload);
        }
    }
}
