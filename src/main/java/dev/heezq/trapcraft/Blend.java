package dev.heezq.trapcraft;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * A custom mix: two to four buds put through the mixing station.
 *
 * Where {@link Strain} is a fixed enum of six phenotypes, a Blend is data --
 * it rides on the itemstack as a component, so the set of possible mixes is
 * open. Six strains taken two to four at a time, order irrelevant and repeats
 * allowed, is 21 + 56 + 126 = 203 distinct recipes, well past what an enum
 * could carry.
 *
 * Effects are the union of the parts, each scaled by its share, so a mix that
 * is three-quarters Kush feels mostly like Kush. On top of that sit
 * {@link Recipe named synergies}: specific combinations that do something the
 * arithmetic wouldn't give you. Those are the reason to experiment rather than
 * just picking your favourite strain and doubling it.
 */
public record Blend(List<Strain> parts, int grade) {
    /** Four is the cap: past that everything averages into the same grey mush. */
    public static final int MAX_PARTS = 4;
    public static final int MIN_PARTS = 2;

    public static final Codec<Blend> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Strain.CODEC.listOf().fieldOf("parts").forGetter(Blend::parts),
            Codec.INT.fieldOf("grade").forGetter(Blend::grade)
    ).apply(instance, Blend::new));

    /**
     * A combination worth finding.
     *
     * The bonus is deliberately not "more of the same" -- each named blend
     * does something none of its parts do alone, so the reward for finding one
     * is a new toy rather than a bigger number.
     *
     * @param needs   exact multiset of strains, order irrelevant
     * @param display what it's called once you've made it
     * @param colour  overrides the averaged colour
     * @param potency multiplier on top of the usual grade scaling
     * @param bonus   effects that only this combination gives
     */
    public record Recipe(List<Strain> needs, String display, int colour, float potency,
                         List<StatusEffectInstance> bonus) {
    }

    /**
     * Order doesn't matter and duplicates do, so these match as multisets.
     * Everything not listed here still blends -- it just doesn't get a name.
     */
    private static final List<Recipe> NAMED = List.of(
            new Recipe(List.of(Strain.KUSH, Strain.HAZE, Strain.PURP),
                    "Trinity", 0xE8D44A, 1.35F,
                    List.of(new StatusEffectInstance(
                            net.minecraft.entity.effect.StatusEffects.HERO_OF_THE_VILLAGE,
                            60 * 20, 0, false, true))),

            new Recipe(List.of(Strain.MIDNIGHT, Strain.MIDNIGHT, Strain.PURP),
                    "Void", 0x2A1B4A, 1.45F,
                    List.of(new StatusEffectInstance(
                            net.minecraft.entity.effect.StatusEffects.INVISIBILITY,
                            40 * 20, 0, false, true))),

            new Recipe(List.of(Strain.HAZE, Strain.SUNSET),
                    "Daybreak", 0xFFC65A, 1.20F,
                    List.of(new StatusEffectInstance(
                            net.minecraft.entity.effect.StatusEffects.SLOW_FALLING,
                            90 * 20, 0, false, true))),

            new Recipe(List.of(Strain.DIESEL, Strain.DIESEL, Strain.HAZE),
                    "Turbo", 0xB8E04A, 1.30F,
                    List.of(new StatusEffectInstance(
                            net.minecraft.entity.effect.StatusEffects.SPEED,
                            80 * 20, 2, false, true))),

            new Recipe(List.of(Strain.KUSH, Strain.KUSH, Strain.MIDNIGHT),
                    "Tar", 0x2F3A28, 1.40F,
                    List.of(new StatusEffectInstance(
                            net.minecraft.entity.effect.StatusEffects.RESISTANCE,
                            90 * 20, 1, false, true),
                            new StatusEffectInstance(
                                    net.minecraft.entity.effect.StatusEffects.SLOWNESS,
                                    90 * 20, 1, false, true))),

            new Recipe(List.of(Strain.PURP, Strain.SUNSET, Strain.HAZE, Strain.DIESEL),
                    "Kaleidoscope", 0xD86ACF, 1.60F,
                    List.of(new StatusEffectInstance(
                            net.minecraft.entity.effect.StatusEffects.NIGHT_VISION,
                            120 * 20, 0, false, true),
                            new StatusEffectInstance(
                                    net.minecraft.entity.effect.StatusEffects.JUMP_BOOST,
                                    120 * 20, 1, false, true))));

    public Quality quality() {
        return Quality.byIndex(grade);
    }

    /** Distinct strains involved. Drives how unhinged the visuals get. */
    public int spread() {
        return (int) parts.stream().distinct().count();
    }

    /** How much of the mix each strain is, 0..1. */
    public Map<Strain, Float> shares() {
        Map<Strain, Float> out = new EnumMap<>(Strain.class);
        for (Strain part : parts) {
            out.merge(part, 1.0F / parts.size(), Float::sum);
        }
        return out;
    }

    /** The named recipe this is, or null for an ordinary mix. */
    public Recipe named() {
        for (Recipe recipe : NAMED) {
            if (recipe.needs().size() == parts.size()
                    && recipe.needs().stream().sorted().toList()
                    .equals(parts.stream().sorted().toList())) {
                return recipe;
            }
        }
        return null;
    }

    public String display() {
        Recipe recipe = named();
        if (recipe != null) {
            return recipe.display();
        }
        // Unnamed mixes are called after their two biggest contributors, so
        // "Kush-Purp" tells you what you're holding without a lookup table.
        List<Strain> ranked = shares().entrySet().stream()
                .sorted((a, b) -> Float.compare(b.getValue(), a.getValue()))
                .map(Map.Entry::getKey)
                .toList();
        if (ranked.size() == 1) {
            return ranked.get(0).display() + " Cut";
        }
        return ranked.get(0).display() + "-" + ranked.get(1).display();
    }

    /** Averaged from the parts, weighted by share, unless a named mix overrides. */
    public int colour() {
        Recipe recipe = named();
        if (recipe != null) {
            return recipe.colour();
        }
        float r = 0, g = 0, b = 0;
        for (var entry : shares().entrySet()) {
            int tint = entry.getKey().colour();
            r += ((tint >> 16) & 0xFF) * entry.getValue();
            g += ((tint >> 8) & 0xFF) * entry.getValue();
            b += (tint & 0xFF) * entry.getValue();
        }
        return ((int) r << 16) | ((int) g << 8) | (int) b;
    }

    /**
     * Mixing is worth doing: a plain two-strain blend already beats either
     * parent, and the spread bonus rewards going wider rather than just
     * stacking four of the same bud.
     */
    public float potency() {
        Recipe recipe = named();
        float base = recipe != null ? recipe.potency() : 1.0F + 0.08F * (spread() - 1);
        return base * quality().potency();
    }

    /** Weighted average of the parts. Long strains drag a mix longer. */
    public int seconds() {
        float total = 0;
        for (var entry : shares().entrySet()) {
            total += entry.getKey().seconds() * entry.getValue();
        }
        return Math.round(total);
    }

    /**
     * Everything the parts do, each scaled by its share, plus the synergy.
     *
     * Durations are scaled but amplifiers are not: a strain that's a quarter of
     * the mix gives you a quarter of the time, not a weaker version. Halving
     * amplifiers would round most of them to zero and quietly delete the
     * effect, which is worse than it simply being brief.
     */
    public List<StatusEffectInstance> effects(RegistryEntry<StatusEffect> baked) {
        List<StatusEffectInstance> list = new ArrayList<>();
        float potency = potency();

        // Baked itself. Amplifier is the client's channel for "what am I
        // looking at" -- see BLEND_AMPLIFIER_BASE.
        list.add(new StatusEffectInstance(baked, Math.round(seconds() * 20 * potency),
                blendAmplifier(), false, true));

        Map<Strain, Float> shares = shares();
        for (var entry : shares.entrySet()) {
            // Pull each part's own extras out by asking it for a mids-grade
            // list, then scale the durations by that part's share.
            for (StatusEffectInstance extra : entry.getKey().effects(baked, Quality.MIDS)) {
                if (extra.getEffectType().equals(baked)) {
                    continue; // ours is already in the list
                }
                int duration = Math.round(extra.getDuration() * entry.getValue() * potency);
                if (duration < 20) {
                    continue; // under a second isn't an effect, it's a flicker
                }
                list.add(new StatusEffectInstance(extra.getEffectType(), duration,
                        extra.getAmplifier(), extra.isAmbient(), extra.shouldShowParticles()));
            }
        }

        Recipe recipe = named();
        if (recipe != null) {
            for (StatusEffectInstance bonus : recipe.bonus()) {
                list.add(new StatusEffectInstance(bonus.getEffectType(),
                        Math.round(bonus.getDuration() * quality().potency()),
                        bonus.getAmplifier(), bonus.isAmbient(), bonus.shouldShowParticles()));
            }
        }
        return list;
    }

    /**
     * Amplifiers 0-5 are the six strains. Blends continue the same channel at
     * 6, 7, 8 for two, three and four distinct strains.
     *
     * This is the half of the blend identity that survives everything -- relog,
     * death, /effect -- because it's part of the status effect. The exact mix
     * comes over {@link TrapNet} for the colours, and if that's missed the
     * client still knows it's looking at a three-way blend and picks the right
     * character. Degraded, not broken.
     */
    public int blendAmplifier() {
        // The clamp to MIN_PARTS is load-bearing, not defensive. A same-strain
        // mix -- Kush + Kush, which the station happily accepts -- has a spread
        // of 1, and without this floor the arithmetic lands on 5. That's
        // Sunset's amplifier: the high would render as a strain you never
        // smoked, and isBlendAmplifier() would say it wasn't a blend at all.
        int distinct = MathHelper.clamp(spread(), MIN_PARTS, MAX_PARTS);
        return BLEND_AMPLIFIER_BASE + distinct - MIN_PARTS;
    }

    public static final int BLEND_AMPLIFIER_BASE = 6;

    public static boolean isBlendAmplifier(int amplifier) {
        return amplifier >= BLEND_AMPLIFIER_BASE;
    }
}
