package dev.heezq.trapcraft;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
 * arithmetic wouldn't give you. Fifty-three of the 203 are named, and the fifty
 * that follow a rule -- every mix whose strains are all different -- are what
 * make the station worth walking to with whatever you happen to be carrying.
 */
public record Blend(List<Strain> parts, int grade) {
    /**
     * Sorted on the way in, and that is the whole of a real bug.
     *
     * A Blend rides on the itemstack as a data component, and components are
     * compared by VALUE -- so a list is only equal to a list with the same
     * things in the same order. Kush-then-Haze and Haze-then-Kush are the same
     * mix by every rule this class has ({@link #named} sorts before matching,
     * {@link #shares} is an EnumMap, the display name comes out identical) but
     * they were two different components, so two jars of visibly the same thing
     * refused to stack and there was nothing on either to say why.
     *
     * Canonicalising here rather than at the mixing station catches every route
     * in -- the station, the codec, and anything added later -- and it also
     * quietly repairs old items, because loading one decodes through this
     * constructor and re-encodes sorted.
     */
    public Blend {
        parts = parts.stream().sorted().toList();
    }

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
     * A bonus effect, in seconds. Fifty-three recipes' worth of
     * {@code new StatusEffectInstance(x, 90 * 20, 0, false, true)} is a wall
     * nobody can read a mistake out of.
     */
    private static StatusEffectInstance fx(RegistryEntry<StatusEffect> type, int seconds, int amp) {
        return new StatusEffectInstance(type, seconds * 20, amp, false, true);
    }

    /**
     * The combinations worth finding, and there is a rule to them: EVERY mix of
     * strains that are all different has a name. Two of six is fifteen names,
     * three is twenty, four is fifteen -- fifty in total, plus the three below
     * that want the same bud twice.
     *
     * The rule is the point. Before it, six of the two hundred and three
     * possible mixes were named, so the overwhelmingly likely outcome of
     * walking up to the station with an armful of buds was a nameless jar worth
     * less than what went into it, and the station was a thing people tried
     * once. Now the question at the hopper is "which mix", not "will this be
     * anything" -- and a mix that ISN'T anything is specifically one where you
     * doubled a bud up, which is a thing you did on purpose.
     *
     * Order doesn't matter and duplicates do, so these match as multisets.
     * Everything not listed here still blends -- it just doesn't get a name.
     */
    private static final List<Recipe> NAMED = List.of(
            // --- two strains: every pair has a name
            new Recipe(List.of(Strain.KUSH, Strain.HAZE),
                    "Bootleg", 0x74A83C, 1.22F,
                    List.of(fx(StatusEffects.HASTE, 90, 0))),

            new Recipe(List.of(Strain.KUSH, Strain.PURP),
                    "Molasses", 0x5E4E70, 1.20F,
                    List.of(fx(StatusEffects.ABSORPTION, 90, 0))),

            new Recipe(List.of(Strain.KUSH, Strain.DIESEL),
                    "Anvil", 0x5F7A3E, 1.24F,
                    List.of(fx(StatusEffects.STRENGTH, 60, 0))),

            new Recipe(List.of(Strain.KUSH, Strain.MIDNIGHT),
                    "Undertow", 0x3E5C63, 1.22F,
                    List.of(fx(StatusEffects.WATER_BREATHING, 120, 0))),

            new Recipe(List.of(Strain.KUSH, Strain.SUNSET),
                    "Ember", 0x8E7A38, 1.20F,
                    List.of(fx(StatusEffects.FIRE_RESISTANCE, 90, 0))),

            new Recipe(List.of(Strain.HAZE, Strain.PURP),
                    "Static", 0x8C7ABF, 1.18F,
                    List.of(fx(StatusEffects.GLOWING, 60, 0))),

            new Recipe(List.of(Strain.HAZE, Strain.DIESEL),
                    "Courier", 0x8FBE4E, 1.26F,
                    List.of(fx(StatusEffects.SATURATION, 15, 0))),

            new Recipe(List.of(Strain.HAZE, Strain.MIDNIGHT),
                    "Bluebird", 0x7A93C6, 1.22F,
                    List.of(fx(StatusEffects.LUCK, 120, 0))),

            new Recipe(List.of(Strain.HAZE, Strain.SUNSET),
                    "Daybreak", 0xFFC65A, 1.20F,
                    List.of(fx(StatusEffects.SLOW_FALLING, 90, 0))),

            new Recipe(List.of(Strain.PURP, Strain.DIESEL),
                    "Swamp", 0x6E7A52, 1.24F,
                    List.of(fx(StatusEffects.HEALTH_BOOST, 120, 0))),

            new Recipe(List.of(Strain.PURP, Strain.MIDNIGHT),
                    "Ghost", 0x5A4A8E, 1.26F,
                    List.of(fx(StatusEffects.INVISIBILITY, 20, 0))),

            new Recipe(List.of(Strain.PURP, Strain.SUNSET),
                    "Sherbet", 0xC77AA8, 1.20F,
                    List.of(fx(StatusEffects.REGENERATION, 60, 0))),

            new Recipe(List.of(Strain.DIESEL, Strain.MIDNIGHT),
                    "Trawler", 0x5E7A78, 1.24F,
                    List.of(fx(StatusEffects.DOLPHINS_GRACE, 90, 0))),

            new Recipe(List.of(Strain.DIESEL, Strain.SUNSET),
                    "Payday", 0xA8934A, 1.22F,
                    List.of(fx(StatusEffects.HERO_OF_THE_VILLAGE, 45, 0))),

            new Recipe(List.of(Strain.MIDNIGHT, Strain.SUNSET),
                    "Tide", 0x7A6A9E, 1.24F,
                    List.of(fx(StatusEffects.CONDUIT_POWER, 90, 0))),

            // --- three strains
            new Recipe(List.of(Strain.KUSH, Strain.HAZE, Strain.PURP),
                    "Trinity", 0xE8D44A, 1.35F,
                    List.of(fx(StatusEffects.HERO_OF_THE_VILLAGE, 60, 0))),

            new Recipe(List.of(Strain.KUSH, Strain.HAZE, Strain.DIESEL),
                    "Overtime", 0x7E9A44, 1.38F,
                    List.of(fx(StatusEffects.HASTE, 120, 1),
                            fx(StatusEffects.SATURATION, 10, 0))),

            new Recipe(List.of(Strain.KUSH, Strain.HAZE, Strain.MIDNIGHT),
                    "Graveyard", 0x4A6470, 1.36F,
                    List.of(fx(StatusEffects.HASTE, 150, 0),
                            fx(StatusEffects.WATER_BREATHING, 150, 0))),

            new Recipe(List.of(Strain.KUSH, Strain.HAZE, Strain.SUNSET),
                    "Brunch", 0xA8B04A, 1.34F,
                    List.of(fx(StatusEffects.SATURATION, 15, 0),
                            fx(StatusEffects.ABSORPTION, 120, 0))),

            new Recipe(List.of(Strain.KUSH, Strain.PURP, Strain.DIESEL),
                    "Roadside", 0x6E6A50, 1.38F,
                    List.of(fx(StatusEffects.STRENGTH, 90, 0),
                            fx(StatusEffects.HEALTH_BOOST, 150, 0))),

            new Recipe(List.of(Strain.KUSH, Strain.PURP, Strain.MIDNIGHT),
                    "Cellar", 0x4A3E64, 1.40F,
                    List.of(fx(StatusEffects.INVISIBILITY, 30, 0),
                            fx(StatusEffects.ABSORPTION, 120, 0))),

            new Recipe(List.of(Strain.KUSH, Strain.PURP, Strain.SUNSET),
                    "Jam", 0x9A5E70, 1.34F,
                    List.of(fx(StatusEffects.FIRE_RESISTANCE, 150, 0),
                            fx(StatusEffects.ABSORPTION, 90, 0))),

            new Recipe(List.of(Strain.KUSH, Strain.DIESEL, Strain.MIDNIGHT),
                    "Foreman", 0x4E6A50, 1.42F,
                    List.of(fx(StatusEffects.STRENGTH, 90, 0),
                            fx(StatusEffects.HASTE, 120, 0))),

            new Recipe(List.of(Strain.KUSH, Strain.DIESEL, Strain.SUNSET),
                    "Backfire", 0x8A8A3E, 1.38F,
                    List.of(fx(StatusEffects.FIRE_RESISTANCE, 180, 0),
                            fx(StatusEffects.HASTE, 120, 1))),

            new Recipe(List.of(Strain.KUSH, Strain.MIDNIGHT, Strain.SUNSET),
                    "Lullaby", 0x6A6A8E, 1.36F,
                    List.of(fx(StatusEffects.SLOW_FALLING, 150, 0),
                            fx(StatusEffects.HEALTH_BOOST, 150, 0))),

            new Recipe(List.of(Strain.HAZE, Strain.PURP, Strain.DIESEL),
                    "Kerosene", 0x8ABF5E, 1.40F,
                    List.of(fx(StatusEffects.SPEED, 120, 1),
                            fx(StatusEffects.GLOWING, 60, 0))),

            new Recipe(List.of(Strain.HAZE, Strain.PURP, Strain.MIDNIGHT),
                    "Redeye", 0x6A6ABF, 1.42F,
                    List.of(fx(StatusEffects.INVISIBILITY, 40, 0),
                            fx(StatusEffects.LUCK, 120, 0))),

            new Recipe(List.of(Strain.HAZE, Strain.PURP, Strain.SUNSET),
                    "Carnival", 0xC78ABF, 1.36F,
                    List.of(fx(StatusEffects.LEVITATION, 4, 0),
                            fx(StatusEffects.SLOW_FALLING, 90, 0))),

            new Recipe(List.of(Strain.HAZE, Strain.DIESEL, Strain.MIDNIGHT),
                    "Nightrun", 0x6E93A8, 1.44F,
                    List.of(fx(StatusEffects.SPEED, 150, 1),
                            fx(StatusEffects.DOLPHINS_GRACE, 120, 0))),

            new Recipe(List.of(Strain.HAZE, Strain.DIESEL, Strain.SUNSET),
                    "Highway", 0xA8B84E, 1.40F,
                    List.of(fx(StatusEffects.SPEED, 120, 1),
                            fx(StatusEffects.HASTE, 120, 0))),

            new Recipe(List.of(Strain.HAZE, Strain.MIDNIGHT, Strain.SUNSET),
                    "Aurora", 0x8AA8C6, 1.38F,
                    List.of(fx(StatusEffects.CONDUIT_POWER, 120, 0),
                            fx(StatusEffects.SLOW_FALLING, 120, 0))),

            new Recipe(List.of(Strain.PURP, Strain.DIESEL, Strain.MIDNIGHT),
                    "Refinery", 0x5E6A6E, 1.44F,
                    List.of(fx(StatusEffects.HEALTH_BOOST, 180, 0),
                            fx(StatusEffects.STRENGTH, 90, 0))),

            new Recipe(List.of(Strain.PURP, Strain.DIESEL, Strain.SUNSET),
                    "Sunstroke", 0xB8865E, 1.38F,
                    List.of(fx(StatusEffects.FIRE_RESISTANCE, 180, 0),
                            fx(StatusEffects.NAUSEA, 30, 0))),

            new Recipe(List.of(Strain.PURP, Strain.MIDNIGHT, Strain.SUNSET),
                    "Twilight", 0x8A6ABF, 1.42F,
                    List.of(fx(StatusEffects.CONDUIT_POWER, 150, 0),
                            fx(StatusEffects.INVISIBILITY, 25, 0))),

            new Recipe(List.of(Strain.DIESEL, Strain.MIDNIGHT, Strain.SUNSET),
                    "Dockyard", 0x7A8A6E, 1.44F,
                    List.of(fx(StatusEffects.DOLPHINS_GRACE, 150, 0),
                            fx(StatusEffects.WATER_BREATHING, 180, 0))),

            // --- four strains: the whole board
            new Recipe(List.of(Strain.KUSH, Strain.HAZE, Strain.PURP, Strain.DIESEL),
                    "Cathedral", 0x8A9A5E, 1.52F,
                    List.of(fx(StatusEffects.HERO_OF_THE_VILLAGE, 120, 0),
                            fx(StatusEffects.HASTE, 150, 1))),

            new Recipe(List.of(Strain.KUSH, Strain.HAZE, Strain.PURP, Strain.MIDNIGHT),
                    "Blackout", 0x4A4A7A, 1.58F,
                    List.of(fx(StatusEffects.INVISIBILITY, 60, 0),
                            fx(StatusEffects.STRENGTH, 90, 0))),

            new Recipe(List.of(Strain.KUSH, Strain.HAZE, Strain.PURP, Strain.SUNSET),
                    "Sundial", 0xBF9A5E, 1.54F,
                    List.of(fx(StatusEffects.SLOW_FALLING, 180, 0),
                            fx(StatusEffects.LUCK, 180, 0))),

            new Recipe(List.of(Strain.KUSH, Strain.HAZE, Strain.DIESEL, Strain.MIDNIGHT),
                    "Freight", 0x5E7A7A, 1.62F,
                    List.of(fx(StatusEffects.HASTE, 180, 1),
                            fx(StatusEffects.STRENGTH, 90, 1))),

            new Recipe(List.of(Strain.KUSH, Strain.HAZE, Strain.DIESEL, Strain.SUNSET),
                    "Firecracker", 0xA8A83E, 1.56F,
                    List.of(fx(StatusEffects.FIRE_RESISTANCE, 240, 0),
                            fx(StatusEffects.STRENGTH, 120, 1))),

            new Recipe(List.of(Strain.KUSH, Strain.HAZE, Strain.MIDNIGHT, Strain.SUNSET),
                    "Fogbank", 0x7A8AA8, 1.56F,
                    List.of(fx(StatusEffects.CONDUIT_POWER, 180, 0),
                            fx(StatusEffects.SLOW_FALLING, 180, 0))),

            new Recipe(List.of(Strain.KUSH, Strain.PURP, Strain.DIESEL, Strain.MIDNIGHT),
                    "Foundry", 0x5E5E5E, 1.66F,
                    List.of(fx(StatusEffects.STRENGTH, 120, 1),
                            fx(StatusEffects.HEALTH_BOOST, 240, 0))),

            new Recipe(List.of(Strain.KUSH, Strain.PURP, Strain.DIESEL, Strain.SUNSET),
                    "Harvest", 0x9A8A4A, 1.58F,
                    List.of(fx(StatusEffects.SATURATION, 20, 0),
                            fx(StatusEffects.ABSORPTION, 180, 1))),

            new Recipe(List.of(Strain.KUSH, Strain.PURP, Strain.MIDNIGHT, Strain.SUNSET),
                    "Seance", 0x6A5E9A, 1.64F,
                    List.of(fx(StatusEffects.INVISIBILITY, 60, 0),
                            fx(StatusEffects.LUCK, 180, 0))),

            new Recipe(List.of(Strain.KUSH, Strain.DIESEL, Strain.MIDNIGHT, Strain.SUNSET),
                    "Drydock", 0x6A7A6A, 1.62F,
                    List.of(fx(StatusEffects.DOLPHINS_GRACE, 180, 0),
                            fx(StatusEffects.WATER_BREATHING, 240, 0))),

            new Recipe(List.of(Strain.HAZE, Strain.PURP, Strain.DIESEL, Strain.MIDNIGHT),
                    "Neon", 0x6ABFA8, 1.68F,
                    List.of(fx(StatusEffects.SPEED, 150, 2),
                            fx(StatusEffects.GLOWING, 90, 0))),

            new Recipe(List.of(Strain.HAZE, Strain.PURP, Strain.DIESEL, Strain.SUNSET),
                    "Kaleidoscope", 0xD86ACF, 1.60F,
                    List.of(fx(StatusEffects.NIGHT_VISION, 120, 0),
                            fx(StatusEffects.JUMP_BOOST, 120, 1))),

            new Recipe(List.of(Strain.HAZE, Strain.PURP, Strain.MIDNIGHT, Strain.SUNSET),
                    "Prism", 0xA88ACF, 1.66F,
                    List.of(fx(StatusEffects.LEVITATION, 5, 0),
                            fx(StatusEffects.SLOW_FALLING, 200, 0))),

            new Recipe(List.of(Strain.HAZE, Strain.DIESEL, Strain.MIDNIGHT, Strain.SUNSET),
                    "Redline", 0x8AA86E, 1.70F,
                    List.of(fx(StatusEffects.SPEED, 180, 2),
                            fx(StatusEffects.HASTE, 180, 1))),

            new Recipe(List.of(Strain.PURP, Strain.DIESEL, Strain.MIDNIGHT, Strain.SUNSET),
                    "Eclipse", 0x4A5A6A, 1.70F,
                    List.of(fx(StatusEffects.CONDUIT_POWER, 200, 0),
                            fx(StatusEffects.INVISIBILITY, 60, 0))),

            // --- and three that want the same bud twice
            new Recipe(List.of(Strain.MIDNIGHT, Strain.MIDNIGHT, Strain.PURP),
                    "Void", 0x2A1B4A, 1.45F,
                    List.of(fx(StatusEffects.INVISIBILITY, 40, 0))),

            new Recipe(List.of(Strain.DIESEL, Strain.DIESEL, Strain.HAZE),
                    "Turbo", 0xB8E04A, 1.30F,
                    List.of(fx(StatusEffects.SPEED, 80, 2))),

            new Recipe(List.of(Strain.KUSH, Strain.KUSH, Strain.MIDNIGHT),
                    "Tar", 0x2F3A28, 1.40F,
                    List.of(fx(StatusEffects.RESISTANCE, 90, 1),
                            fx(StatusEffects.SLOWNESS, 90, 1))));

    /**
     * Keyed by the sorted parts, which is exactly the shape {@link #parts} is
     * kept in -- so a lookup is one hash instead of fifty-three list compares,
     * each of which used to sort both sides first. That mattered once the table
     * stopped being six long: {@link TrapContent#blendBud} asks four times per
     * stack it describes, and a dealer describes every stack it holds.
     *
     * toUnmodifiableMap throws on a duplicate key, so two recipes claiming the
     * same combination is a crash at class-load rather than one of them
     * silently never being reachable.
     */
    private static final Map<List<Strain>, Recipe> BY_PARTS = NAMED.stream()
            .collect(Collectors.toUnmodifiableMap(
                    recipe -> recipe.needs().stream().sorted().toList(), recipe -> recipe));

    /** Every named mix, for the guide book. */
    public static List<Recipe> recipes() {
        return NAMED;
    }

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
        // parts is sorted by the constructor and so are the keys, so this is
        // the multiset match the old loop was doing the long way round.
        return BY_PARTS.get(parts);
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
