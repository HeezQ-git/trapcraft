package dev.heezq.trapcraft;

import com.mojang.serialization.Codec;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.StringIdentifiable;

import java.util.List;
import java.util.function.Supplier;

/**
 * The six phenotypes: three you can find, three you have to breed.
 *
 * The Baked amplifier is the strain's ORDINAL, not a potency level. That's how
 * the client knows which strain you smoked -- it only ever sees the status
 * effect, so the amplifier is the channel. It used to double as intensity,
 * which worked with three strains and would silently collide at six; potency
 * now lives in {@link #intensity} and is looked up, not inferred.
 */
public enum Strain implements StringIdentifiable {
    KUSH("kush", "Kush", 0x4a9a3c, 90, 2,
            "Heavy body high. Slows you right down, but patches you up.",
            () -> List.of(
                    new StatusEffectInstance(StatusEffects.SLOWNESS, 90 * 20, 0, false, true),
                    new StatusEffectInstance(StatusEffects.REGENERATION, 30 * 20, 0, false, true))),

    HAZE("haze", "Haze", 0x9ec43a, 60, 1,
            "Light and quick. Speed, and spring in your step.",
            () -> List.of(
                    new StatusEffectInstance(StatusEffects.SPEED, 60 * 20, 0, false, true),
                    new StatusEffectInstance(StatusEffects.JUMP_BOOST, 60 * 20, 0, false, true))),

    PURP("purp", "Purp", 0x7a4fa8, 120, 3,
            "See in the dark. Costs you your balance for a bit.",
            () -> List.of(
                    new StatusEffectInstance(StatusEffects.NIGHT_VISION, 120 * 20, 0, false, true),
                    new StatusEffectInstance(StatusEffects.NAUSEA, 20 * 20, 0, false, true))),

    // --- hybrids: only obtainable by cross-breeding -------------------------

    DIESEL("diesel", "Diesel", 0x7fa86a, 100, 2,
            "Kush body, Haze legs. Somehow both at once.",
            () -> List.of(
                    new StatusEffectInstance(StatusEffects.SPEED, 100 * 20, 0, false, true),
                    new StatusEffectInstance(StatusEffects.REGENERATION, 40 * 20, 0, false, true),
                    new StatusEffectInstance(StatusEffects.RESISTANCE, 60 * 20, 0, false, true))),

    MIDNIGHT("midnight", "Midnight", 0x4a4a8a, 140, 4,
            "Sinks you into the floor and turns the lights on.",
            () -> List.of(
                    new StatusEffectInstance(StatusEffects.NIGHT_VISION, 140 * 20, 0, false, true),
                    new StatusEffectInstance(StatusEffects.SLOWNESS, 100 * 20, 0, false, true),
                    new StatusEffectInstance(StatusEffects.RESISTANCE, 80 * 20, 0, false, true))),

    SUNSET("sunset", "Sunset", 0xc47f3a, 90, 2,
            "Warm, weightless, and you can see in the dark.",
            () -> List.of(
                    new StatusEffectInstance(StatusEffects.JUMP_BOOST, 90 * 20, 1, false, true),
                    new StatusEffectInstance(StatusEffects.NIGHT_VISION, 90 * 20, 0, false, true),
                    new StatusEffectInstance(StatusEffects.SLOW_FALLING, 30 * 20, 0, false, true)));

    public static final Codec<Strain> CODEC = StringIdentifiable.createCodec(Strain::values);

    private final String id;
    private final String display;
    private final int colour;
    private final int seconds;
    private final int intensity;
    private final String blurb;
    private final Supplier<List<StatusEffectInstance>> extras;

    Strain(String id, String display, int colour, int seconds, int intensity,
           String blurb, Supplier<List<StatusEffectInstance>> extras) {
        this.id = id;
        this.display = display;
        this.colour = colour;
        this.seconds = seconds;
        this.intensity = intensity;
        this.blurb = blurb;
        this.extras = extras;
    }

    public String id() {
        return id;
    }

    public String display() {
        return display;
    }

    public int colour() {
        return colour;
    }

    /**
     * Darkened for the cream book page. Haze and Diesel in particular are
     * near-invisible on paper at their item brightness.
     */
    public int bookColour() {
        int r = (int) (((colour >> 16) & 0xFF) * 0.55F);
        int g = (int) (((colour >> 8) & 0xFF) * 0.55F);
        int b = (int) ((colour & 0xFF) * 0.55F);
        return (r << 16) | (g << 8) | b;
    }

    /** Drives how hard Baked burns hunger. Not the same as the amplifier. */
    public int intensity() {
        return intensity;
    }

    /**
     * Base duration before potency scaling.
     *
     * Public because the client works backwards from it: every code path
     * applies Baked for {@code seconds * 20 * potency} ticks, so a duration
     * divided by this IS the potency of the hit. That's how the visuals know
     * whether you took a swill joint or a fire tlok without a packet.
     */
    public int seconds() {
        return seconds;
    }

    public boolean isHybrid() {
        return ordinal() >= DIESEL.ordinal();
    }

    public static Strain byIndex(int index) {
        Strain[] values = values();
        return values[Math.max(0, Math.min(index, values.length - 1))];
    }

    /**
     * What you get from two different strains growing side by side. Order
     * doesn't matter, and hybrids don't breed further -- three is enough to
     * chase without turning into a combinatorial mess.
     */
    public static Strain hybridOf(Strain a, Strain b) {
        if (a == b || a.isHybrid() || b.isHybrid()) {
            return null;
        }
        int pair = (1 << a.ordinal()) | (1 << b.ordinal());
        if (pair == ((1 << KUSH.ordinal()) | (1 << HAZE.ordinal()))) {
            return DIESEL;
        }
        if (pair == ((1 << KUSH.ordinal()) | (1 << PURP.ordinal()))) {
            return MIDNIGHT;
        }
        if (pair == ((1 << HAZE.ordinal()) | (1 << PURP.ordinal()))) {
            return SUNSET;
        }
        return null;
    }

    /**
     * Built fresh per use -- StatusEffectInstance is mutable and gets consumed
     * by the entity, so a cached list would hand out already-ticked instances.
     */
    public List<StatusEffectInstance> effects(RegistryEntry<StatusEffect> baked, Quality grade) {
        float potency = grade.potency();
        var list = new java.util.ArrayList<StatusEffectInstance>();
        // Amplifier = ordinal, so the client can tell strains apart.
        list.add(new StatusEffectInstance(baked, Math.round(seconds * 20 * potency),
                ordinal(), false, true));
        for (StatusEffectInstance extra : extras.get()) {
            list.add(new StatusEffectInstance(extra.getEffectType(),
                    Math.round(extra.getDuration() * potency),
                    extra.getAmplifier(), extra.isAmbient(), extra.shouldShowParticles()));
        }
        return list;
    }

    /**
     * Guide-book copy. The prose is hand-written but the numbers come from the
     * fields above, so the book can't quietly disagree with the code.
     */
    public String describe() {
        return blurb + " Lasts " + seconds + "s.";
    }

    @Override
    public String asString() {
        return id;
    }
}
