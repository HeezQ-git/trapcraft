package dev.heezq.trapcraft.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import dev.heezq.trapcraft.Blend;
import dev.heezq.trapcraft.Strain;
import dev.heezq.trapcraft.TrapNet;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import dev.heezq.trapcraft.client.mixin.GameRendererInvoker;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

import java.util.Optional;

/**
 * The optional half of TrapCraft. Without this installed everything still
 * works -- you just don't get the screen effect. That's deliberate: keeping it
 * optional means adding a strain never forces anyone to reinstall the pack.
 *
 * Two axes drive everything here. WHICH strain you smoked picks the character
 * (colour, speed, weight) out of {@link #STYLES}; HOW HARD you hit it drives
 * {@link #trip}, which scales every layer and switches extra ones on. A swill
 * joint and a fire tlok used to look identical. They are now nearly 20x apart.
 */
@Environment(EnvType.CLIENT)
public class TrapCraftClient implements ClientModInitializer {
    private static final float EASE_PER_TICK = 0.02F;    // ~2.5s to fade in or out
    private static final float PHASE_PER_TICK = 0.05F;

    /**
     * Per-strain character. Previously every strain shared one look and only
     * the colour changed, which made them feel like the same high three times.
     *
     * @param tint      main screen wash
     * @param tint2     colour it drifts toward
     * @param sway      camera drift, degrees (visual only, aim untouched)
     * @param swayRate  drift speed -- low reads as heavy, high as jittery
     * @param fov       FOV breathe, fraction
     * @param alpha     wash opacity of 255
     * @param vignette  edge darkening relative to the wash
     * @param pulseRate breathing speed
     * @param drift     how far the colour wanders toward tint2
     * @param blur      post effect id stem, blend baked in per strain
     */
    private record HighStyle(int tint, int tint2, float sway, float swayRate, float fov,
                             int alpha, float vignette, float pulseRate, float drift,
                             String blur) {
    }

    /**
     * Indexed by amplifier, which is the strain ordinal.
     *
     * These are the look at trip 1.0 -- one average joint. Everything below
     * scales them; nothing here is pre-scaled, so a strain's character stays
     * recognisable whether you took one puff or six.
     */
    private static final HighStyle[] STYLES = {
            // Haze: small drift but the quickest, and by far the most FOV
            // breathe -- that pulsing zoom is its signature.
            new HighStyle(0x9EC43A, 0xD8E06A, 0.70F, 2.20F, 0.034F,
                    30, 1.7F, 1.20F, 0.12F, "haze"),
            // Kush: heavy body. Slowest, widest drift, strongest vignette and
            // the longest trails -- the screen feels like it's sinking.
            new HighStyle(0x4A9A3C, 0x2F6B28, 0.80F, 0.42F, 0.007F,
                    33, 3.4F, 0.24F, 0.08F, "kush"),
            // Purp: trippy. Moderate drift but the colour wanders hard between
            // purple and pink, so the whole screen keeps shifting hue.
            new HighStyle(0x7A4FA8, 0xC47FD8, 1.05F, 0.85F, 0.022F,
                    44, 2.3F, 0.55F, 0.55F, "purp"),
            // --- hybrids: each borrows from both parents, none is just a
            // blend of the numbers, or they'd all feel like the average.
            // Diesel: Kush's weight with Haze's speed -- wide drift, but quick.
            new HighStyle(0x7FA86A, 0xD8D8A0, 1.00F, 1.60F, 0.026F,
                    36, 2.4F, 0.85F, 0.20F, "diesel"),
            // Midnight: the heaviest of all six. Slow, dark, long trails.
            new HighStyle(0x4A4A8A, 0x8A8AD8, 1.25F, 0.35F, 0.012F,
                    52, 3.6F, 0.20F, 0.40F, "midnight"),
            // Sunset: warm and weightless -- gentlest drift, most FOV float.
            new HighStyle(0xC47F3A, 0xF0C060, 0.75F, 1.10F, 0.036F,
                    40, 2.0F, 0.95F, 0.45F, "sunset"),
            // --- blends: amplifiers 6, 7 and 8 for two, three and four
            // distinct strains. These tints are only the fallback -- when the
            // mix packet arrives the wash is built from the real constituents.
            // Everything else here escalates hard, because a blend is meant to
            // be visibly a different order of thing from a single strain.
            new HighStyle(0xB88FD0, 0xE0C070, 1.30F, 1.25F, 0.030F,
                    48, 2.6F, 1.10F, 0.60F, "blend2"),
            new HighStyle(0xD07FB0, 0x70E0C0, 1.60F, 1.55F, 0.038F,
                    56, 3.0F, 1.40F, 0.80F, "blend3"),
            new HighStyle(0xE86AC0, 0x50E8FF, 2.00F, 1.90F, 0.046F,
                    64, 3.4F, 1.75F, 1.00F, "blend4"),
    };

    private static final Identifier BAKED_ID = Identifier.of("trapcraft", "baked");

    // --- the trip meter ------------------------------------------------------

    /**
     * Ceiling on accumulated potency. A fire tlok on a clear head is ~4.4, so
     * two back-to-back peg it -- deliberately reachable, but only on purpose.
     */
    private static final float TRIP_MAX = 8.0F;

    /** Per-tick decay. Halves in about 50s, so the peak fades with the high. */
    private static final float TRIP_DECAY = 0.99930F;

    /** The mix forgets faster than the trip, so old strains drop out of the blend. */
    private static final float MIX_DECAY = 0.99860F;

    /** Below this a strain isn't really in the mix any more. */
    private static final float MIX_FLOOR = 0.20F;

    /** Where the extra layers start and where they max out. */
    private static final float CHAOS_START = 1.20F;
    private static final float CHAOS_FULL = 4.50F;

    private static float intensity;
    private static float phase;
    private static int amplifier;

    /** Accumulated potency across every hit that hasn't worn off. */
    private static float trip;

    /** Per-strain share of the current trip, decaying. Drives the colour blend. */
    private static final float[] mix = new float[Strain.values().length];

    /** Exact wash colour of the current blend, or -1 for a single strain. */
    private static int blendColour = -1;

    /** Previous tick's Baked instance, to spot a fresh hit landing. */
    private static int lastAmplifier = -1;
    private static int lastDuration;

    /**
     * Trail length band, latched at hit time rather than followed live.
     *
     * Changing the post processor reloads the shader chain and blanks a frame
     * or two -- that was the old black-flash bug. Latching means the only time
     * it can happen is the instant you take a hit, where a flash reads as the
     * hit landing rather than as a glitch.
     */
    private static int blurBand;

    /** Which blur is currently applied, or null. Strain and band dependent. */
    private static Identifier activeBlur;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(TrapCraftClient::tick);
        // HudElementRegistry, not HudRenderCallback: the latter is deprecated
        // since the 1.21.6 HUD rewrite. addFirst draws us underneath the vanilla
        // HUD, so the tint colours the world without dimming your hotbar.
        HudElementRegistry.addFirst(Identifier.of("trapcraft", "high_overlay"),
                TrapCraftClient::render);

        // Gestures are optional the same way the screen effect is. Both the
        // factory and the receiver live behind this check, so without the
        // library the server's canSend() sees no receiver and never sends.
        boolean gestures = net.fabricmc.loader.api.FabricLoader.getInstance()
                .isModLoaded("player_animation_library");
        if (gestures) {
            TrapAnimations.register();
            ClientPlayNetworking.registerGlobalReceiver(TrapNet.PlayAnim.ID,
                    (payload, context) -> context.client().execute(
                            () -> TrapAnimations.play(payload.entityId(), payload.anim())));
        }

        // Not behind the gesture check: the mix drives the screen effect,
        // which works with or without the animation library.
        ClientPlayNetworking.registerGlobalReceiver(TrapNet.BlendMix.ID,
                (payload, context) -> context.client().execute(
                        () -> onBlendMix(payload.parts(), payload.colour())));

        // Logged so "is the client half even running?" is answerable from the
        // log instead of by guessing at a screen nobody else can see.
        dev.heezq.trapcraft.TrapCraft.LOGGER.info(
                "TrapCraft client visuals active ({} strain styles, gestures {})",
                STYLES.length, gestures ? "on" : "off -- no player_animation_library");
    }

    // --- state ---------------------------------------------------------------

    private static Optional<RegistryEntry.Reference<StatusEffect>> baked() {
        return Registries.STATUS_EFFECT.getEntry(BAKED_ID);
    }

    private static HighStyle style() {
        return STYLES[MathHelper.clamp(amplifier, 0, STYLES.length - 1)];
    }

    private static Identifier blurId() {
        return Identifier.of("trapcraft", "motion_blur_" + style().blur() + "_" + blurBand);
    }

    private static void tick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        float target = 0.0F;

        if (player != null) {
            StatusEffectInstance instance = baked().map(player::getStatusEffect).orElse(null);
            if (instance != null) {
                int amp = MathHelper.clamp(instance.getAmplifier(), 0, STYLES.length - 1);
                int duration = instance.getDuration();

                // Baked normally loses exactly one tick per tick. Anything else
                // -- the clock jumping up, or the strain changing under us -- is
                // a fresh hit landing, and that's the only event we need.
                if (amp != lastAmplifier || duration > lastDuration) {
                    onHit(amp, duration);
                }
                lastAmplifier = amp;
                lastDuration = duration;
                amplifier = amp;

                // Ramp down over the last 3s so it lifts rather than snapping off.
                target = duration < 60 ? duration / 60.0F : 1.0F;
            } else {
                lastAmplifier = -1;
                lastDuration = 0;
            }
        }

        intensity += MathHelper.clamp(target - intensity, -EASE_PER_TICK, EASE_PER_TICK);
        intensity = MathHelper.clamp(intensity, 0.0F, 1.0F);
        if (intensity > 0.001F) {
            phase += PHASE_PER_TICK;
        }

        // Sober means sober: without this the trip would linger into the next
        // session and the first joint after would look like the sixth.
        trip *= target > 0.0F ? TRIP_DECAY : 0.90F;
        for (int i = 0; i < mix.length; i++) {
            mix[i] *= target > 0.0F ? MIX_DECAY : 0.90F;
        }
        if (trip < 0.01F) {
            trip = 0.0F;
            blendColour = -1;
        }

        WiredLook.tick(player);
        updateBlur(client);
    }

    /**
     * A hit landed. Work out how hard it was and fold it into the trip.
     *
     * Every path that applies Baked -- joint, bong, tlok -- does so for
     * {@code strain.seconds() * 20 * potency} ticks, where potency already has
     * the grade, the method and your tolerance multiplied into it. So the
     * duration divided by the strain's base IS the strength of that hit, and
     * no packet is needed to learn it.
     */
    private static void onHit(int amplifier, int duration) {
        // A blend's Baked duration comes from the weighted average of its
        // parts, which the client can't reconstruct from the amplifier alone --
        // that only says how many distinct strains. Kush's 90s is the middle of
        // the six and the least wrong single guess.
        int base = (Blend.isBlendAmplifier(amplifier)
                ? Strain.KUSH.seconds()
                : Strain.byIndex(amplifier).seconds()) * 20;
        float potency = base <= 0 ? 1.0F : duration / (float) base;

        trip = Math.min(TRIP_MAX, trip + potency);
        if (!Blend.isBlendAmplifier(amplifier)) {
            mix[amplifier] += potency;
            // Drop the previous blend's colour. It used to survive until the
            // trip decayed to nothing, so a plain Kush joint smoked while a
            // blend was wearing off came out tinted by the blend -- the strain
            // styles exist precisely so that doesn't happen.
            blendColour = -1;
        }
        // Blends start a band higher: the mirror and echo layers only exist on
        // the blend pipelines, and gating them behind a big trip as well would
        // mean most blends never showed the thing that makes them blends.
        float effective = Blend.isBlendAmplifier(amplifier) ? trip + 1.2F : trip;
        blurBand = effective < 1.6F ? 0 : effective < 3.2F ? 1 : 2;
    }

    /**
     * The mix behind a blend high, straight from the server.
     *
     * Seeds the per-strain shares so everything downstream -- the colour
     * blending, the clash term, the second sway frequency -- works on the real
     * constituents rather than treating a blend as one opaque thing. A four-way
     * mix lands at clash 1.0 and gets the full colour fight for free.
     */
    public static void onBlendMix(java.util.List<Integer> parts, int colour) {
        blendColour = colour;
        float share = 1.6F / Math.max(1, parts.size());
        for (int ordinal : parts) {
            if (ordinal >= 0 && ordinal < mix.length) {
                mix[ordinal] += share;
            }
        }
    }

    // --- derived scalars -----------------------------------------------------

    /**
     * 0 when sober, 1 while high. Purely the fade in/out envelope.
     *
     * This deliberately does NOT scale by amplifier. It used to, from back when
     * all strains shared one look and the amplifier was the only thing telling
     * them apart. Once STYLES gave each strain its own numbers that multiplier
     * double-counted. Per-strain intensity belongs in STYLES; per-HIT intensity
     * belongs in {@link #trip}. Neither belongs here.
     */
    public static float strength() {
        return intensity;
    }

    /** Whether anything at all wants the camera. Read by the mixins. */
    public static boolean anyLook() {
        return intensity > 0.001F || WiredLook.active();
    }

    /**
     * How far past "one ordinary joint" you are, 0 to 1.
     *
     * Gates every layer that doesn't exist at low potency. Nothing new appears
     * until you've clearly gone past a single mild hit, so a casual smoke still
     * looks like the mod always did.
     */
    private static float chaos() {
        return MathHelper.clamp((trip - CHAOS_START) / (CHAOS_FULL - CHAOS_START), 0.0F, 1.0F);
    }

    /**
     * How many strains are jostling, as 0 (one) to 1 (three or more).
     *
     * Mixing is its own kind of trippy and gets its own multiplier rather than
     * just adding potency: colours fight instead of blending, and the drift
     * picks up a second frequency that doesn't agree with the first.
     */
    private static float clash() {
        int active = 0;
        for (float m : mix) {
            if (m > MIX_FLOOR) {
                active++;
            }
        }
        return MathHelper.clamp((active - 1) / 2.0F, 0.0F, 1.0F);
    }

    /** Camera drift gain. 1.0 at one joint, up to ~2.5 when you're gone. */
    private static float swayGain() {
        return 0.50F + 0.50F * Math.min(trip, 1.0F) + 1.30F * chaos() + 0.25F * clash();
    }

    /**
     * FOV gain. Grows much harder than sway past the halfway mark.
     *
     * The zoom used to top out around 1.85x, which on Kush's 0.007 base is a
     * swing you'd struggle to notice. It now reaches ~4.4x, so a fire tlok on
     * Haze breathes about 15% of your field of view in and out -- firmly in
     * "the room is inhaling" territory rather than a subtle drift.
     */
    private static float fovGain() {
        return 0.55F + 0.45F * Math.min(trip, 1.0F) + 3.40F * chaos();
    }

    /** Wash opacity gain. */
    private static float washGain() {
        return 0.55F + 0.45F * Math.min(trip, 1.0F) + 0.85F * chaos();
    }

    private static float phaseAt(float tickProgress) {
        return phase + tickProgress * PHASE_PER_TICK;
    }

    // --- read by the mixins --------------------------------------------------

    /** Two incommensurate frequencies, so the drift never visibly repeats. */
    public static float swayYaw(float tickProgress) {
        HighStyle s = style();
        float p = phaseAt(tickProgress);
        float base = MathHelper.sin(p * 0.70F * s.swayRate());
        // A mixed high gets a second, deliberately unrelated wobble on top --
        // two strains pulling the camera in disagreement.
        float mixed = clash() * 0.45F * MathHelper.sin(p * 1.63F * s.swayRate() + 0.7F);
        return (base + mixed) * s.sway() * swayGain() * strength()
                + WiredLook.jitterYaw(tickProgress);
    }

    public static float swayPitch(float tickProgress) {
        HighStyle s = style();
        float p = phaseAt(tickProgress);
        float base = MathHelper.sin(p * 0.43F * s.swayRate() + 1.3F);
        float mixed = clash() * 0.45F * MathHelper.sin(p * 1.11F * s.swayRate() + 2.9F);
        return (base + mixed) * s.sway() * 0.6F * swayGain() * strength()
                + WiredLook.jitterPitch(tickProgress);
    }

    /**
     * Camera roll, degrees. Only exists past {@link #CHAOS_START}.
     *
     * Held back for the heavy end on purpose: rolling the horizon is the single
     * most disorienting thing here, so it's the reward for a big hit rather
     * than something you feel every time you light up.
     */
    public static float swayRoll(float tickProgress) {
        float c = chaos();
        if (c <= 0.001F) {
            return 0.0F;
        }
        HighStyle s = style();
        float p = phaseAt(tickProgress);
        float wave = MathHelper.sin(p * 0.31F * s.swayRate()) * 0.70F
                + MathHelper.sin(p * 0.17F * s.swayRate() + 2.4F) * 0.30F;
        return wave * 7.5F * c * (1.0F + 0.6F * clash()) * strength();
    }

    /**
     * The breathe, plus a second faster wave that only shows up once you're
     * past the threshold.
     *
     * One sine reads as a slow pumping zoom no matter how far you push it.
     * Adding a quicker beat on top turns it into something that surges and
     * catches, which is where "strong" comes from rather than just "bigger".
     */
    public static float fovScale(float tickProgress) {
        HighStyle s = style();
        float p = phaseAt(tickProgress);
        float wave = MathHelper.sin(p * 0.50F)
                + chaos() * 0.55F * MathHelper.sin(p * 1.37F + 0.8F);
        // Multiplied, not added: two independent breathes that happen to
        // overlap shouldn't cancel each other out.
        return (1.0F + wave * s.fov() * fovGain() * strength())
                * WiredLook.fovScale(tickProgress);
    }

    // --- blur ----------------------------------------------------------------

    /**
     * Accumulation blur stays on for the whole high -- no speed threshold.
     *
     * That's deliberate on two counts. The effect is temporal, so it only
     * smears things that actually move; leaving it on costs nothing visually
     * when you're still. And toggling was the bug: setPostProcessor reloads the
     * shader chain, which blanks the screen for a frame or two, so switching it
     * on and off as you turned produced black flashes.
     */
    /**
     * Only one post processor can be live at a time, so Baked and Wired have
     * to take turns. Baked wins ties because it's the bigger, slower look --
     * being coked up during a weed high should feel like a texture on top of
     * it, not replace it. The crash overrides both: it's the whole point of
     * the coca line and it's over in seconds.
     */
    private static void updateBlur(MinecraftClient client) {
        if (WiredLook.crash() > 0.25F) {
            setBlur(client, Identifier.of("trapcraft",
                    "motion_blur_" + WiredLook.stem() + "_" + WiredLook.band()));
        } else if (strength() > 0.001F) {
            setBlur(client, blurId());
        } else if (WiredLook.active()) {
            setBlur(client, Identifier.of("trapcraft",
                    "motion_blur_" + WiredLook.stem() + "_" + WiredLook.band()));
        } else {
            setBlur(client, null);
        }
    }

    private static void setBlur(MinecraftClient client, Identifier want) {
        if (java.util.Objects.equals(want, activeBlur)) {
            return; // setPostProcessor reloads shaders; only touch it on change
        }
        GameRenderer renderer = client.gameRenderer;
        if (want != null) {
            ((GameRendererInvoker) renderer).trapcraft$setPostProcessor(want);
        } else if (activeBlur != null && activeBlur.equals(renderer.getPostProcessorId())) {
            // Only clear if the active processor is still ours -- otherwise we
            // would rip away spectator creeper/spider vision.
            renderer.clearPostProcessor();
        }
        activeBlur = want;
    }

    // --- overlay -------------------------------------------------------------

    private static void render(DrawContext context, RenderTickCounter counter) {
        WiredLook.render(context, counter.getTickProgress(false));
        float s = strength();
        if (s <= 0.001F) {
            return;
        }

        int width = context.getScaledWindowWidth();
        int height = context.getScaledWindowHeight();
        float p = phaseAt(counter.getTickProgress(false));
        HighStyle st = style();
        float c = chaos();
        float cl = clash();

        // Two frequencies that don't divide evenly, so the breathing never
        // settles into an obvious loop the way a single sine does. Chaos deepens
        // the swing rather than speeding it up -- faster reads as strobing.
        float swing = 0.20F + 0.30F * c;
        float pulse = (1.0F - swing) + swing * MathHelper.sin(p * 0.35F * st.pulseRate())
                + 0.10F * MathHelper.sin(p * 0.11F * st.pulseRate() + 2.1F);

        int rgb = washColour(st, p, c, cl);
        int washAlpha = (int) (st.alpha() * s * pulse * washGain());
        if (washAlpha > 0) {
            context.fill(0, 0, width, height, (Math.min(washAlpha, 200) << 24) | rgb);
        }

        vignette(context, width, height, p, s, pulse, st, rgb, c);

        if (c > 0.02F) {
            ripple(context, width, height, p, s, st, rgb, c);
            chroma(context, width, height, p, s, st, c, counter.getTickProgress(false));
        }
    }

    /**
     * The wash colour: the strain's own drift, blended across whatever else is
     * in your system, then hue-cycled once you're properly gone.
     */
    private static int washColour(HighStyle st, float p, float c, float cl) {
        // Wander between the strain's two colours. Purp swings hard here; Kush
        // barely moves, which is most of why they read differently.
        int own = blend(st.tint(), st.tint2(),
                st.drift() * (0.5F + 0.5F * MathHelper.sin(p * 0.07F)));
        if (blendColour >= 0) {
            // The mix's own colour is the anchor -- it's what the item in
            // your hand looked like, so the high should match it -- but it
            // still drifts, or a blend would be the one flat wash here.
            own = blend(blendColour, own, 0.25F + 0.20F * MathHelper.sin(p * 0.09F));
        }

        // Mixed high: pull toward the weighted average of everything active.
        // Blended smoothly it just muddies to grey, so the pull itself
        // oscillates -- the colours take turns instead of averaging out.
        if (cl > 0.001F) {
            int other = mixedTint(p);
            float take = cl * (0.35F + 0.35F * MathHelper.sin(p * 0.29F));
            own = blend(own, other, take);
        }

        // Full chaos rotates the hue continuously. Slow enough to read as the
        // world shifting colour rather than as a disco light.
        if (c > 0.3F) {
            own = hueShift(own, (p * 6.0F) * (c - 0.3F) / 0.7F);
        }
        return own;
    }

    /** Weighted average of every strain currently in the mix. */
    private static int mixedTint(float p) {
        float total = 0.0F;
        float r = 0.0F, g = 0.0F, b = 0.0F;
        for (int i = 0; i < mix.length; i++) {
            if (mix[i] <= MIX_FLOOR) {
                continue;
            }
            // Weight each strain by its share, wobbled slightly out of phase so
            // the blend keeps moving instead of settling.
            float w = mix[i] * (0.75F + 0.25F * MathHelper.sin(p * 0.13F + i));
            int tint = STYLES[i].tint();
            r += ((tint >> 16) & 0xFF) * w;
            g += ((tint >> 8) & 0xFF) * w;
            b += (tint & 0xFF) * w;
            total += w;
        }
        if (total <= 0.0F) {
            return 0xFFFFFF;
        }
        return ((int) (r / total) << 16) | ((int) (g / total) << 8) | (int) (b / total);
    }

    /**
     * Vignette as stacked bands rather than a texture: fillGradient only goes
     * vertical, and this way all four edges match.
     *
     * Band edges are computed as float->int per step and each band spans
     * [edge(i), edge(i+1)) exactly. An earlier version derived a single
     * thickness and offset by integer division, so bands overlapped in places
     * and left uncovered rows in others -- those gaps were the hard lines
     * across the edges of the screen.
     */
    private static void vignette(DrawContext context, int width, int height, float p,
                                 float s, float pulse, HighStyle st, int rgb, float c) {
        int steps = 24;
        // Chaos makes the edges breathe several times harder -- the walls
        // closing in and opening out is most of the "gone" feeling.
        float breathe = (0.03F + 0.13F * c) * MathHelper.sin(p * 0.23F);
        float band = Math.min(width, height) * (0.30F + breathe);
        for (int i = 0; i < steps; i++) {
            int near = (int) (band * i / steps);
            int far = (int) (band * (i + 1) / steps);
            if (far <= near) {
                continue; // sub-pixel band at this resolution
            }
            float falloff = 1.0F - (i / (float) steps);
            falloff = falloff * falloff * falloff;
            int alpha = (int) (st.alpha() * st.vignette() * s * pulse * falloff * washGain());
            if (alpha <= 0) {
                continue;
            }
            int colour = (Math.min(alpha, 255) << 24) | rgb;
            context.fill(0, near, width, far, colour);
            context.fill(0, height - far, width, height - near, colour);
            context.fill(near, 0, far, height, colour);
            context.fill(width - far, 0, width - near, height, colour);
        }
    }

    /**
     * Horizontal waves crawling up the screen, like heat off tarmac.
     *
     * Rows rather than a warp because the HUD layer can only fill rectangles --
     * it can't resample the world image. Modulating alpha per row at a
     * frequency that drifts gets most of the way there, and unlike a real warp
     * it can't push anything off-screen or hide a mob from you.
     */
    private static void ripple(DrawContext context, int width, int height, float p,
                               float s, HighStyle st, int rgb, float c) {
        int rows = 44;
        float rowHeight = height / (float) rows;
        // The second frequency is irrational against the first, so the pattern
        // never lines up with itself twice.
        float freqA = 7.0F + 5.0F * c;
        float freqB = freqA * 0.6180F;
        float peak = st.alpha() * s * c * 0.55F;

        for (int i = 0; i < rows; i++) {
            float fy = i / (float) rows;
            float wave = MathHelper.sin(fy * freqA + p * 0.42F)
                    * MathHelper.sin(fy * freqB - p * 0.19F);
            if (wave <= 0.0F) {
                continue; // only the crests draw, or it's just a flat wash
            }
            int alpha = (int) (peak * wave);
            if (alpha <= 0) {
                continue;
            }
            int top = (int) (i * rowHeight);
            int bottom = (int) ((i + 1) * rowHeight);
            if (bottom > top) {
                context.fill(0, top, width, bottom, (Math.min(alpha, 120) << 24) | rgb);
            }
        }
    }

    /**
     * Warm on one edge, cool on the other, sliding with the camera drift.
     *
     * Real chromatic aberration splits the channels across the whole frame,
     * which needs the world texture. This fakes the part you actually notice --
     * coloured fringing at the edges that smears when you turn -- by tying the
     * offset to the same sway that's already moving the camera.
     */
    private static void chroma(DrawContext context, int width, int height, float p,
                               float s, HighStyle st, float c, float tickProgress) {
        int steps = 10;
        // Driven by the sway, so the fringe leads the turn instead of pulsing
        // on its own clock. That coupling is what sells it.
        float lean = MathHelper.clamp(swayYaw(tickProgress) / 2.0F, -1.0F, 1.0F);
        float reach = Math.min(width, height) * 0.16F * c;
        float peak = st.alpha() * s * c * 0.85F;

        for (int i = 0; i < steps; i++) {
            float falloff = 1.0F - (i / (float) steps);
            falloff = falloff * falloff;
            int near = (int) (reach * i / steps);
            int far = (int) (reach * (i + 1) / steps);
            if (far <= near) {
                continue;
            }
            int warm = (int) (peak * falloff * (0.5F + 0.5F * lean));
            int cool = (int) (peak * falloff * (0.5F - 0.5F * lean));
            if (warm > 0) {
                context.fill(near, 0, far, height, (Math.min(warm, 90) << 24) | 0xFF3A6A);
            }
            if (cool > 0) {
                context.fill(width - far, 0, width - near, height,
                        (Math.min(cool, 90) << 24) | 0x3AE0FF);
            }
        }
    }

    // --- colour helpers ------------------------------------------------------

    private static int blend(int a, int b, float t) {
        t = MathHelper.clamp(t, 0.0F, 1.0F);
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = (int) (ar + (br - ar) * t);
        int g = (int) (ag + (bg - ag) * t);
        int bl = (int) (ab + (bb - ab) * t);
        return (r << 16) | (g << 8) | bl;
    }

    /**
     * Rotate hue, keeping saturation and value.
     *
     * The matrix form rather than a round trip through HSV: it's branch-free,
     * and it degrades to a slight desaturation at the extremes instead of the
     * banding you get from quantising hue back into 8-bit RGB every frame.
     */
    private static int hueShift(int rgb, float degrees) {
        float rad = degrees * MathHelper.RADIANS_PER_DEGREE;
        float cos = MathHelper.cos(rad);
        float sin = MathHelper.sin(rad);
        float r = ((rgb >> 16) & 0xFF) / 255.0F;
        float g = ((rgb >> 8) & 0xFF) / 255.0F;
        float b = (rgb & 0xFF) / 255.0F;

        // Luminance stays put; only the chroma plane spins.
        float m00 = 0.213F + cos * 0.787F - sin * 0.213F;
        float m01 = 0.715F - cos * 0.715F - sin * 0.715F;
        float m02 = 0.072F - cos * 0.072F + sin * 0.928F;
        float m10 = 0.213F - cos * 0.213F + sin * 0.143F;
        float m11 = 0.715F + cos * 0.285F + sin * 0.140F;
        float m12 = 0.072F - cos * 0.072F - sin * 0.283F;
        float m20 = 0.213F - cos * 0.213F - sin * 0.787F;
        float m21 = 0.715F - cos * 0.715F + sin * 0.715F;
        float m22 = 0.072F + cos * 0.928F + sin * 0.072F;

        int nr = (int) (MathHelper.clamp(r * m00 + g * m01 + b * m02, 0.0F, 1.0F) * 255);
        int ng = (int) (MathHelper.clamp(r * m10 + g * m11 + b * m12, 0.0F, 1.0F) * 255);
        int nb = (int) (MathHelper.clamp(r * m20 + g * m21 + b * m22, 0.0F, 1.0F) * 255);
        return (nr << 16) | (ng << 8) | nb;
    }
}
