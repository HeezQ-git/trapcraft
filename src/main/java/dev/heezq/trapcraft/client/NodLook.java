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
 * The long line's screen effect. The heaviest look in the mod, and the reason
 * is straightforward: dope sits above the powder on every other axis in this
 * thing -- price, hook, decay, the chain behind it -- and until now it was the
 * one drug you could take without your screen admitting anything had happened.
 * Two particles above your head and nothing else. A player who paid four times
 * a gram's price got less to look at than a swill joint.
 *
 * <p>Where it sits against the other two:
 *
 * <pre>
 *   Baked  -- drifts and smears. A colour over the world, breathing.
 *   Wired  -- twitches. Cold, sharp, fast, over-lit.
 *   Nod    -- everything at once, at a quarter speed.
 * </pre>
 *
 * The trick that keeps it from reading as "a blend with a brown tint" is the
 * clock. Blends are the trippiest thing the weed line does and they are FAST --
 * hue racing, ripples crawling, the room busy. This runs the same machinery at
 * a third of the rate: the world bends further than anything else can bend it
 * and takes eight seconds to do it. Slow and enormous is what makes it read as
 * opiate rather than psychedelic.
 *
 * <p>And the signature is {@link #nod()}: the head-drop. Every seven and a half
 * seconds the whole picture sags -- camera pitches down, the lids come in, the
 * warmth thickens -- and then catches itself. Nothing else in the mod does
 * anything on that kind of cycle, so a nodding player is recognisable from a
 * two-second clip.
 *
 * <p>Band is the amplifier, which is the purity, and it never changes for the
 * life of one dose. That matters: {@link TrapCraftClient#setBlur} reloads the
 * shader chain on every change and blanks a frame doing it, and unlike Wired --
 * which follows a live crash level and can cross its threshold mid-effect --
 * this can only move when a fresh hit lands, where a flash reads as the hit.
 */
@Environment(EnvType.CLIENT)
public final class NodLook {
    private static final Identifier NOD_ID = Identifier.of("trapcraft", "nod");

    /**
     * Comes on slowly, leaves more slowly still.
     *
     * Asymmetric on purpose and by a factor of three. Wired snaps on in half a
     * second because that is what powder does; this should arrive like the room
     * getting warm, and it should still be letting go of you well after the
     * effect timer has run out.
     */
    private static final float RISE_PER_TICK = 0.010F;   // ~5s in
    private static final float FALL_PER_TICK = 0.0035F;  // ~14s out

    private static final float PHASE_PER_TICK = 0.05F;

    /** Ticks per head-drop. 7.5s -- slow enough to be a cycle, not a wobble. */
    private static final int NOD_PERIOD = 150;

    /** Fractions of that spent upright, then sinking. The rest is the catch. */
    private static final float NOD_HOLD = 0.55F;
    private static final float NOD_FALL = 0.35F;

    /** Amber, gold, and the umber it sinks into at the bottom of a drop. */
    private static final int AMBER = 0xC8823C;
    private static final int GOLD = 0xF2C879;
    private static final int UMBER = 0x35190A;

    private static float intensity;
    private static float phase;
    private static float nodPhase;
    private static int band;

    private NodLook() {
    }

    private static Optional<RegistryEntry.Reference<StatusEffect>> effect() {
        return Registries.STATUS_EFFECT.getEntry(NOD_ID);
    }

    public static void tick(ClientPlayerEntity player) {
        float target = 0.0F;

        if (player != null) {
            StatusEffectInstance instance = effect().map(player::getStatusEffect).orElse(null);
            if (instance != null) {
                band = MathHelper.clamp(instance.getAmplifier(), 0, 2);
                // Ramp down over the last five seconds. Longer than Baked's
                // three because everything here is slower, and a look this
                // heavy snapping off would read as a bug rather than as coming
                // back to yourself.
                int left = instance.getDuration();
                target = left < 100 ? left / 100.0F : 1.0F;
            }
        }

        float step = target > intensity ? RISE_PER_TICK : FALL_PER_TICK;
        intensity += MathHelper.clamp(target - intensity, -step, step);
        intensity = MathHelper.clamp(intensity, 0.0F, 1.0F);

        if (intensity > 0.001F) {
            phase += PHASE_PER_TICK;
            nodPhase += 1.0F;
            if (nodPhase >= NOD_PERIOD) {
                nodPhase -= NOD_PERIOD;
            }
        } else {
            // Start the next dose with your head up rather than halfway
            // through a drop you never took.
            nodPhase = 0.0F;
        }
    }

    public static float strength() {
        return intensity;
    }

    /** True while this should own the camera and the post processor. */
    public static boolean active() {
        return intensity > 0.001F;
    }

    /**
     * Everything below scales by this rather than by {@link #strength()} alone,
     * so Cięte is a warm blur and Idealne is the room folding over. A flat
     * strength would make the four purities look identical, which is the exact
     * complaint that started this file.
     */
    private static float gain() {
        return intensity * (0.55F + 0.45F * band / 2.0F);
    }

    /** Which pipeline stem and band the long line wants. Purity, straight through. */
    public static String stem() {
        return "nod";
    }

    public static int band() {
        return band;
    }

    /**
     * The head-drop, 0 (up) to 1 (chin on your chest).
     *
     * Three phases across the 7.5 seconds: four seconds upright, two and a half
     * sinking, three quarters of one catching yourself. The asymmetry is the
     * whole read -- a symmetric wave is a bob, and only a slow fall against a
     * fast recovery looks like someone losing and regaining their head.
     *
     * The flat stretch is load-bearing rather than laziness. It started as a
     * raised cosine squared, on the theory that squaring would keep the head up
     * for most of the cycle; {@code tools/check_pipelines.py} measured the duty
     * at 38%, because squaring a raised cosine only moves its mean from 0.5 to
     * 0.375. Somebody 38% of the way into a nod at all times is not nodding,
     * they have passed out -- and passing out is a different mechanic that
     * lives in {@code HeroinItem.overdose}. Explicit phases put it at 22%.
     */
    private static float nod() {
        float f = nodPhase / (float) NOD_PERIOD;
        if (f < NOD_HOLD) {
            return 0.0F;
        }
        if (f < NOD_HOLD + NOD_FALL) {
            return 0.5F - 0.5F * MathHelper.cos(MathHelper.PI * (f - NOD_HOLD) / NOD_FALL);
        }
        float caught = (f - NOD_HOLD - NOD_FALL) / (1.0F - NOD_HOLD - NOD_FALL);
        return 0.5F + 0.5F * MathHelper.cos(MathHelper.PI * caught);
    }

    // --- camera --------------------------------------------------------------

    /**
     * A wide slow lean, on frequencies far below anything else here.
     *
     * Baked's fastest strain sways at 2.2 units of rate and Wired twitches at
     * 8Hz. This runs at 0.08 -- a full lean takes the better part of a minute
     * -- and is allowed to be three times wider for it. Speed is what the eye
     * reads as agitation; amplitude on its own just reads as weight.
     */
    public static float driftYaw(float tickProgress) {
        float p = phase + tickProgress * PHASE_PER_TICK;
        float lean = MathHelper.sin(p * 0.08F) * 1.8F + MathHelper.sin(p * 0.13F + 1.7F) * 0.9F;
        return lean * gain();
    }

    public static float driftPitch(float tickProgress) {
        float p = phase + tickProgress * PHASE_PER_TICK;
        float lean = MathHelper.sin(p * 0.11F + 0.6F) * 1.2F;
        // Pitch is where the nod lives: down is positive, so the drop is added
        // rather than subtracted, and it is the biggest single camera move in
        // the mod at roughly nine degrees on Idealne.
        return (lean + nod() * 9.0F * (0.6F + 0.4F * band / 2.0F)) * gain();
    }

    /**
     * The loll. Slow enough that you catch the horizon being wrong rather than
     * watching it move -- and unlike Baked's roll it is not gated behind a big
     * hit, because on this line even the weakest dose is meant to be a lot.
     */
    public static float roll(float tickProgress) {
        float p = phase + tickProgress * PHASE_PER_TICK;
        float wave = MathHelper.sin(p * 0.09F) * 0.75F + MathHelper.sin(p * 0.05F + 2.1F) * 0.25F;
        return wave * 6.5F * gain();
    }

    /** A deep slow breathe, with the nod pulling the walls in on top of it. */
    public static float fovScale(float tickProgress) {
        float p = phase + tickProgress * PHASE_PER_TICK;
        float breathe = MathHelper.sin(p * 0.12F) * 0.045F;
        float sink = -nod() * 0.055F;
        return 1.0F + (breathe + sink) * gain();
    }

    // --- overlay -------------------------------------------------------------

    public static void render(DrawContext context, float tickProgress) {
        if (!active()) {
            return;
        }
        int width = context.getScaledWindowWidth();
        int height = context.getScaledWindowHeight();
        float p = phase + tickProgress * PHASE_PER_TICK;
        float g = gain();
        float drop = nod();

        // Wash: amber wandering into gold, sinking toward umber as the head
        // goes down. Warm and thick, where Wired's is cold and thin.
        float wander = 0.5F + 0.5F * MathHelper.sin(p * 0.06F);
        int rgb = blend(blend(AMBER, GOLD, wander), UMBER, drop * 0.65F);
        int wash = (int) ((52 + 78 * drop) * g);
        if (wash > 0) {
            context.fill(0, 0, width, height, (Math.min(wash, 190) << 24) | rgb);
        }

        eyelids(context, width, height, g, drop);
        bloom(context, width, height, p, g, drop);
        drips(context, width, height, p, g);
    }

    /**
     * Lids, not a vignette.
     *
     * The difference is that they are asymmetric: top and bottom close several
     * times further than the sides do, which is what makes it read as eyes
     * rather than as a tunnel. They breathe shut on their own and slam most of
     * the way closed at the bottom of a nod -- but never all the way, because
     * a genuinely black screen is a disconnect button, not an effect.
     */
    private static void eyelids(DrawContext context, int width, int height,
                                float g, float drop) {
        float shut = MathHelper.clamp((0.06F + 0.34F * drop) * g, 0.0F, 0.42F);
        if (shut <= 0.002F) {
            return;
        }
        int steps = 18;
        float lid = height * shut;
        float side = Math.min(width, height) * shut * 0.30F;

        for (int i = 0; i < steps; i++) {
            // Opaque for the first two thirds of the depth and soft only over
            // the last third. This started as a squared falloff copied from
            // the vignette next door and it was the one thing in here that
            // didn't work: squared, nine tenths of the depth sits at an alpha
            // you cannot see, so a lid closing 40% of the screen read as a
            // slightly darker border. A lid is a shutter with a blurred lip.
            float f = (i + 0.5F) / steps;
            float weight = f < 0.62F ? 1.0F : 1.0F - (f - 0.62F) / 0.38F;
            int alpha = (int) (245 * weight * weight * g);
            if (alpha <= 0) {
                continue;
            }
            int colour = (Math.min(alpha, 245) << 24) | UMBER;

            int near = (int) (lid * i / steps);
            int far = (int) (lid * (i + 1) / steps);
            if (far > near) {
                context.fill(0, near, width, far, colour);
                context.fill(0, height - far, width, height - near, colour);
            }
            int nearX = (int) (side * i / steps);
            int farX = (int) (side * (i + 1) / steps);
            if (farX > nearX) {
                context.fill(nearX, 0, farX, height, colour);
                context.fill(width - farX, 0, width - nearX, height, colour);
            }
        }
    }

    /**
     * A warm glow in the middle, the inverse of the lids.
     *
     * Wired paints bright edges and a dark centre; this is the other way round,
     * and between them the two effects never look like each other for a frame.
     * Drawn as expanding rings from the centre out, brightest where you are
     * actually looking, which is the "everything is fine" the drug is selling.
     */
    private static void bloom(DrawContext context, int width, int height,
                              float p, float g, float drop) {
        int rings = 18;
        float reach = Math.min(width, height) * (0.30F + 0.10F * MathHelper.sin(p * 0.10F));
        float peak = 24 * g * (1.0F - 0.5F * drop);
        int cx = width / 2;
        int cy = height / 2;

        for (int i = rings - 1; i >= 0; i--) {
            float falloff = 1.0F - (i / (float) rings);
            int alpha = (int) (peak * falloff * falloff);
            if (alpha <= 0) {
                continue;
            }
            int r = (int) (reach * (i + 1) / rings);
            // Squares rather than circles: fill() is all the HUD layer has, and
            // at this opacity nobody has ever seen the corners.
            context.fill(cx - r, cy - r, cx + r, cy + r,
                    (Math.min(alpha, 60) << 24) | GOLD);
        }
    }

    /**
     * Slow warm runs down the glass, on the same clock as the honey particles
     * the server is already spawning around your head.
     *
     * Columns rather than a warp, for the same reason Baked's ripple is rows:
     * the HUD layer can only fill rectangles. Each column has its own speed and
     * its own offset from a cheap hash of its index, so they never march.
     */
    private static void drips(DrawContext context, int width, int height,
                              float p, float g) {
        int columns = 40;
        float colWidth = width / (float) columns;
        float length = height * 0.22F;

        for (int i = 0; i < columns; i++) {
            // Two irrational-ish multipliers off the index: no shared period,
            // so no two columns ever fall together twice.
            float speed = 0.010F + 0.014F * fract(i * 0.6180F);
            float offset = fract(i * 0.3820F);
            float head = fract(offset + p * speed) * (height + length) - length;

            int alpha = (int) (40 * g * (0.5F + 0.5F * MathHelper.sin(p * 0.2F + i)));
            if (alpha <= 0) {
                continue;
            }
            // A run a third of the column wide at its own offset inside it,
            // rather than the full column. Full-width columns read as banding
            // across the screen; these read as something going down it.
            int left = (int) (i * colWidth + colWidth * 0.30F * fract(i * 0.7071F));
            int right = left + Math.max(1, (int) (colWidth * 0.35F));
            // Four segments fading behind the head, so each run tapers off
            // instead of being a bar sliding down the screen.
            for (int s = 0; s < 4; s++) {
                int top = (int) (head - length * s / 4.0F);
                int bottom = (int) (head - length * (s + 1) / 4.0F);
                int a = (int) (alpha * (1.0F - s / 4.0F));
                if (a <= 0) {
                    continue;
                }
                int y0 = Math.max(0, Math.min(bottom, top));
                int y1 = Math.min(height, Math.max(bottom, top));
                if (y1 > y0) {
                    context.fill(left, y0, right, y1, (Math.min(a, 70) << 24) | GOLD);
                }
            }
        }
    }

    private static float fract(float v) {
        return v - MathHelper.floor(v);
    }

    private static int blend(int a, int b, float t) {
        t = MathHelper.clamp(t, 0.0F, 1.0F);
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return ((int) (ar + (br - ar) * t) << 16)
                | ((int) (ag + (bg - ag) * t) << 8)
                | (int) (ab + (bb - ab) * t);
    }
}
