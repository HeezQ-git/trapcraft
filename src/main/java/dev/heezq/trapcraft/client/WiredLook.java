package dev.heezq.trapcraft.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

import java.util.Optional;

/**
 * The coca line's screen effect, and its comedown.
 *
 * The whole point is that it must not read as Baked in another colour. Baked
 * drifts, sinks and smears -- slow sine sway, long trails, a warm wash that
 * breathes. Wired is built as its opposite on every axis: a fast twitch instead
 * of a drift, a cold near-white wash instead of a colour, bright edges instead
 * of a dark vignette, and short trails so the image stays sharp.
 *
 * The crash gets its own look again, because it's the most interesting thing
 * the coca line does. The client can see it coming without being told: Wired's
 * remaining duration is right there on the status effect, so the sag starts
 * before the effect ends rather than after.
 */
@Environment(EnvType.CLIENT)
public final class WiredLook {
    private static final Identifier WIRED_ID = Identifier.of("trapcraft", "wired");

    /** Matches WiredStatusEffect.CRASH_AT plus the run-up we fade over. */
    private static final int CRASH_WARNING_TICKS = 90;

    private static final float EASE_PER_TICK = 0.04F;   // twice Baked's: it hits fast

    private static float intensity;
    private static float crash;
    private static float phase;

    private WiredLook() {
    }

    private static Optional<RegistryEntry.Reference<StatusEffect>> wired() {
        return Registries.STATUS_EFFECT.getEntry(WIRED_ID);
    }

    public static void tick(ClientPlayerEntity player) {
        float target = 0.0F;
        float crashTarget = 0.0F;

        if (player != null) {
            StatusEffectInstance instance = wired().map(player::getStatusEffect).orElse(null);
            if (instance != null) {
                target = 1.0F;
                int left = instance.getDuration();
                if (left < CRASH_WARNING_TICKS) {
                    // Ramps in over the last four and a half seconds, so you
                    // get a moment of "oh no" before the effect actually ends.
                    crashTarget = 1.0F - left / (float) CRASH_WARNING_TICKS;
                }
            }
        }

        intensity += MathHelper.clamp(target - intensity, -EASE_PER_TICK, EASE_PER_TICK);
        intensity = MathHelper.clamp(intensity, 0.0F, 1.0F);
        // The crash decays slower than it builds -- it should outlast the
        // effect that caused it, matching the Slowness/Weakness the server
        // actually applies.
        crash += MathHelper.clamp(crashTarget - crash, -0.006F, 0.05F);
        crash = MathHelper.clamp(crash, 0.0F, 1.0F);
        if (intensity > 0.001F || crash > 0.001F) {
            phase += 0.05F;
        }
    }

    public static float strength() {
        return intensity;
    }

    public static float crash() {
        return crash;
    }

    /** True while this should own the post processor. */
    public static boolean active() {
        return intensity > 0.001F || crash > 0.001F;
    }

    /** Which pipeline stem and band the coca line wants right now. */
    public static String stem() {
        return crash > 0.25F ? "crash" : "wired";
    }

    public static int band() {
        float level = crash > 0.25F ? crash : intensity;
        return level < 0.45F ? 0 : level < 0.8F ? 1 : 2;
    }

    // --- camera --------------------------------------------------------------

    /**
     * A twitch, not a drift.
     *
     * Deliberately high frequency and low amplitude -- around a quarter of a
     * degree at 8Hz. Baked's sway is a slow wide arc you notice; this is a
     * jitter you feel. Under the crash it inverts into something slow and
     * heavy, which is the same trick in reverse.
     */
    public static float jitterYaw(float tickProgress) {
        float p = phase + tickProgress * 0.05F;
        float twitch = MathHelper.sin(p * 8.3F) * 0.16F + MathHelper.sin(p * 13.7F) * 0.09F;
        float sag = MathHelper.sin(p * 0.19F) * 1.4F;
        return twitch * intensity * (1.0F - crash) + sag * crash;
    }

    public static float jitterPitch(float tickProgress) {
        float p = phase + tickProgress * 0.05F;
        float twitch = MathHelper.sin(p * 6.9F + 1.1F) * 0.12F;
        float sag = MathHelper.sin(p * 0.13F + 2.2F) * 0.9F;
        return twitch * intensity * (1.0F - crash) + sag * crash;
    }

    /** Shallow and fast while up; a slow squeeze inward on the way down. */
    public static float fovScale(float tickProgress) {
        float p = phase + tickProgress * 0.05F;
        float alert = MathHelper.sin(p * 3.1F) * 0.009F * intensity * (1.0F - crash);
        float heavy = (MathHelper.sin(p * 0.21F) - 1.0F) * 0.012F * crash;
        return 1.0F + alert + heavy;
    }

    // --- overlay -------------------------------------------------------------

    public static void render(DrawContext context, float tickProgress) {
        if (!active()) {
            return;
        }
        int width = context.getScaledWindowWidth();
        int height = context.getScaledWindowHeight();
        float p = phase + tickProgress * 0.05F;
        float up = intensity * (1.0F - crash);

        if (up > 0.002F) {
            // Cold near-white, barely there but flickering fast. A heavy wash
            // would fight the shader's saturation boost; the point is that the
            // world looks over-lit, not tinted.
            float flicker = 0.75F + 0.25F * MathHelper.sin(p * 4.7F)
                    + 0.10F * MathHelper.sin(p * 11.3F);
            int alpha = (int) (16 * up * flicker);
            if (alpha > 0) {
                context.fill(0, 0, width, height, (alpha << 24) | 0xD8F4FF);
            }

            // Bright inner border rather than a dark vignette -- the inverse of
            // Baked's edges, and it reads as tunnel vision from the wrong end.
            int steps = 8;
            float reach = Math.min(width, height) * 0.09F;
            for (int i = 0; i < steps; i++) {
                int near = (int) (reach * i / steps);
                int far = (int) (reach * (i + 1) / steps);
                if (far <= near) {
                    continue;
                }
                float falloff = 1.0F - (i / (float) steps);
                int edge = (int) (30 * up * flicker * falloff * falloff);
                if (edge <= 0) {
                    continue;
                }
                int colour = (Math.min(edge, 90) << 24) | 0xEAFBFF;
                context.fill(0, near, width, far, colour);
                context.fill(0, height - far, width, height - near, colour);
                context.fill(near, 0, far, height, colour);
                context.fill(width - far, 0, width - near, height, colour);
            }

            // Fast scrolling scanlines. Baked's ripple crawls; this races, and
            // that difference in speed is most of what separates the two.
            int rows = 60;
            float rowHeight = height / (float) rows;
            for (int i = 0; i < rows; i++) {
                float wave = MathHelper.sin(i / (float) rows * 41.0F + p * 3.9F);
                if (wave <= 0.6F) {
                    continue;   // only the sharp crests, so they read as lines
                }
                int alpha2 = (int) (26 * up * (wave - 0.6F) / 0.4F);
                int top = (int) (i * rowHeight);
                int bottom = (int) (i * rowHeight + Math.max(1, rowHeight * 0.35F));
                if (alpha2 > 0 && bottom > top) {
                    context.fill(0, top, width, bottom, (alpha2 << 24) | 0xFFFFFF);
                }
            }
        }

        if (crash > 0.002F) {
            // Grey, heavy, closing in. The shader is draining the colour out at
            // the same time; this is the weight on top of it.
            float sway = 0.85F + 0.15F * MathHelper.sin(p * 0.17F);
            int alpha = (int) (46 * crash * sway);
            if (alpha > 0) {
                context.fill(0, 0, width, height, (alpha << 24) | 0x2A2C33);
            }
            int steps = 20;
            float band = Math.min(width, height) * (0.34F + 0.10F * crash) * sway;
            for (int i = 0; i < steps; i++) {
                int near = (int) (band * i / steps);
                int far = (int) (band * (i + 1) / steps);
                if (far <= near) {
                    continue;
                }
                float falloff = 1.0F - (i / (float) steps);
                falloff = falloff * falloff * falloff;
                int edge = (int) (150 * crash * falloff);
                if (edge <= 0) {
                    continue;
                }
                int colour = (Math.min(edge, 190) << 24) | 0x0C0D10;
                context.fill(0, near, width, far, colour);
                context.fill(0, height - far, width, height - near, colour);
                context.fill(near, 0, far, height, colour);
                context.fill(width - far, 0, width - near, height, colour);
            }
        }
    }
}
