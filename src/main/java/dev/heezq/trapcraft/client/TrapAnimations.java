package dev.heezq.trapcraft.client;

import com.zigythebird.playeranim.animation.PlayerAnimResources;
import com.zigythebird.playeranim.animation.PlayerAnimationController;
import com.zigythebird.playeranim.api.PlayerAnimationAccess;
import com.zigythebird.playeranim.api.PlayerAnimationFactory;
import com.zigythebird.playeranimcore.api.firstPerson.FirstPersonConfiguration;
import com.zigythebird.playeranimcore.api.firstPerson.FirstPersonMode;
import com.zigythebird.playeranimcore.enums.PlayState;
import dev.heezq.trapcraft.TrapCraft;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.util.Identifier;

/**
 * Player gestures, via PlayerAnimationLib.
 *
 * Kept in its own class so nothing here is loaded unless the library is
 * actually present -- {@link TrapCraftClient} guards the one call into it.
 * The library ships with the pack (Emotecraft pulls it in), but the mod has to
 * survive without it or a friend on a bare client gets a crash instead of a
 * missing animation.
 *
 * Compiled against 1.1.2 specifically. Maven's newest is 2.0.2, which is a
 * different major with a different API -- see the comment in build.gradle.
 */
@Environment(EnvType.CLIENT)
public final class TrapAnimations {
    /** Our slot in the layer stack. High priority so it sits over idle poses. */
    private static final Identifier LAYER = TrapCraft.id("gestures");
    private static final int PRIORITY = 1000;

    /** Ticks to blend in from whatever the body was doing. */
    private static final float FADE = 3.0F;

    private static boolean complained = false;

    private TrapAnimations() {
    }

    public static void register() {
        PlayerAnimationFactory.ANIMATION_DATA_FACTORY.registerFactory(LAYER, PRIORITY, player -> {
            // STOP is right: this controller has no idle animation of its own
            // and is only ever driven by triggerAnimation().
            var controller = new PlayerAnimationController(
                    player, (c, data, setter) -> PlayState.STOP);

            // THIRD_PERSON_MODEL renders the animated body in first person too,
            // so you see your own hands do these things instead of watching
            // vanilla shove a held item across the middle of your screen.
            //
            // That vanilla behaviour is what made smoking look wrong: a joint
            // uses UseAction.TOOT_HORN, whose first-person transform is built
            // for a goat horn -- a big object held out front. On something the
            // size of a joint it just reads as a hand over the camera.
            //
            // Applies to every gesture, not just the joint, so the tlok pull
            // and the bong hit become things you can watch yourself do.
            controller.setFirstPersonMode(FirstPersonMode.THIRD_PERSON_MODEL);
            controller.setFirstPersonConfiguration(
                    // arms and held items, both hands: hiding the off hand
                    // makes it look amputated the moment it enters frame.
                    new FirstPersonConfiguration(true, true, true, true));
            return controller;
        });
    }

    public static void play(int entityId, Identifier anim) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null
                || !(client.world.getEntityById(entityId) instanceof AbstractClientPlayerEntity player)) {
            return;
        }

        if (!PlayerAnimResources.hasAnimation(anim)) {
            // Loud once, then quiet. The resource id is derived from the file
            // path by the library, and if that derivation isn't what we assumed
            // the animation silently does nothing -- so say which ids exist
            // rather than leaving a dead gesture to guess about.
            if (!complained) {
                complained = true;
                TrapCraft.LOGGER.warn("No animation {} -- library knows: {}",
                        anim, PlayerAnimResources.getAnimations().keySet());
            }
            return;
        }

        if (PlayerAnimationAccess.getPlayerAnimationLayer(player, LAYER)
                instanceof PlayerAnimationController controller) {
            controller.triggerAnimation(anim, FADE);
        }
    }
}
