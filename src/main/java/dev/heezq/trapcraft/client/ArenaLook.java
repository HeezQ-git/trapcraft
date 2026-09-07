package dev.heezq.trapcraft.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.MathHelper;

/**
 * What the arena does to the screen: a shake and a flash.
 *
 * Both arrive as packets from {@link dev.heezq.trapcraft.TrapArena} and both
 * are optional the way the whole client half is -- the fight is identical
 * without them, it just does not rattle. The shake is a decaying jitter fed
 * into the same camera hooks the high uses; the flash is a colour over the
 * HUD that fades out over however many ticks the server asked for.
 */
@Environment(EnvType.CLIENT)
public final class ArenaLook {
    private static float shakeStrength;
    private static int shakeTicks;
    private static int shakeTotal;
    private static int flashColour;
    private static int flashTicks;
    private static int flashTotal;
    private static float phase;

    private ArenaLook() {
    }

    public static void shake(float strength, int ticks) {
        // The stronger of the two wins rather than stacking: three slams in a
        // row should not throw the camera through the floor.
        shakeStrength = Math.max(shakeStrength * remaining(), strength);
        shakeTicks = shakeTotal = Math.max(1, ticks);
    }

    public static void flash(int colour, int ticks) {
        flashColour = colour;
        flashTicks = flashTotal = Math.max(1, ticks);
    }

    public static void tick() {
        if (shakeTicks > 0) {
            shakeTicks--;
        }
        if (flashTicks > 0) {
            flashTicks--;
        }
        phase += 1.0F;
    }

    public static boolean active() {
        return shakeTicks > 0;
    }

    private static float remaining() {
        return shakeTotal == 0 ? 0.0F : shakeTicks / (float) shakeTotal;
    }

    /** Degrees of yaw to add this frame. Two fast sines, so it reads as a rattle, not a sway. */
    public static float jitterYaw(float tickProgress) {
        if (shakeTicks <= 0) {
            return 0.0F;
        }
        float p = phase + tickProgress;
        float envelope = remaining();
        return (MathHelper.sin(p * 3.1F) * 0.6F + MathHelper.sin(p * 7.3F + 1.0F) * 0.4F)
                * 2.2F * shakeStrength * envelope;
    }

    public static float jitterPitch(float tickProgress) {
        if (shakeTicks <= 0) {
            return 0.0F;
        }
        float p = phase + tickProgress;
        float envelope = remaining();
        return (MathHelper.sin(p * 4.7F + 0.5F) * 0.6F + MathHelper.sin(p * 9.1F) * 0.4F)
                * 1.6F * shakeStrength * envelope;
    }

    public static void render(DrawContext context, float tickProgress) {
        if (flashTicks <= 0) {
            return;
        }
        float left = (flashTicks - tickProgress) / (float) flashTotal;
        int alpha = (int) (170 * MathHelper.clamp(left, 0.0F, 1.0F));
        if (alpha <= 0) {
            return;
        }
        context.fill(0, 0, context.getScaledWindowWidth(), context.getScaledWindowHeight(),
                (alpha << 24) | (flashColour & 0xFFFFFF));
    }
}
