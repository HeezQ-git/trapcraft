package dev.heezq.trapcraft;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.WorldSavePath;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The habit.
 *
 * {@link ToleranceStatusEffect} already answers "how much does the next one do
 * for me" -- it damps a fresh high and wears off in minutes. This answers the
 * other question, the one that outlives a session: how much do you NEED it.
 * They are deliberately separate meters. Tolerance is why the fourth joint is
 * disappointing; addiction is why there is a fourth joint.
 *
 * <h2>The shape of it</h2>
 *
 * Every drug in {@link Drug} has its own meter, 0 to {@link Drug#MAX}. Using
 * something puts points on ITS meter and no other -- a Purp habit is a Purp
 * habit, and a barn full of Kush is no help at all. That per-strain split is
 * the whole reason the meter is interesting rather than a single "drugs" bar:
 * it makes a monoculture farm a liability and gives the six phenotypes a
 * reason to exist beyond their effect lists.
 *
 * <h2>Pressure, not a timer</h2>
 *
 * <pre>pressure = (hook / MAX) * min(1, time since last hit / craving period)</pre>
 *
 * Two things have to be true at once for withdrawal to bite: the meter has to
 * be high AND you have to have been off it a while. That falls out of the
 * multiplication rather than needing a state machine, and it has the property
 * that matters -- a light habit can NEVER reach the bad bands, however long you
 * abstain, because its first factor caps the product. You have to have earned
 * being sick.
 *
 * <h2>The way out</h2>
 *
 * Time. The meter bleeds off at {@link Drug#decayPerMinute}, and it bleeds
 * twice as fast while you are properly sick, so riding out the worst of it IS
 * the cure and quietly nursing a small habit is the slow road. Nerve tonic
 * holds the symptoms off without touching the meter, which makes it a way to
 * get a day's work done rather than a way to get clean.
 *
 * Nothing here decays while you are logged out. Same rule as the coke crash:
 * a comedown you can dodge by quitting to the menu is not a comedown.
 */
public final class TrapAddiction {
    private TrapAddiction() {
    }

    // --- the dials ------------------------------------------------------------

    /** How often every online player's meters are looked at. */
    private static final int EVERY = 20 * 5;
    private static final float EVALS_PER_MINUTE = (20 * 60F) / EVERY;

    /** Riding it out is worth double. See the class note. */
    public static final float SICK_DECAY_BONUS = 2.0F;

    /**
     * Where the bands start, as a fraction of the worst possible pressure.
     *
     * Read them as meter floors, because that is what they are: with pressure
     * capped by hook/MAX, you cannot be SICK below a 72 meter or CRAVING below
     * 45 no matter how long you stay off it.
     *
     * Owned by {@link TrapMath} along with the formula that uses them, because
     * that class imports nothing from Minecraft and is therefore the only half
     * of this system a plain JUnit run can reach. Re-exported here so callers
     * that already have this class in hand do not have to know that.
     */
    public static final float ITCH_AT = TrapMath.HABIT_ITCH;
    public static final float CRAVE_AT = TrapMath.HABIT_CRAVE;
    public static final float SICK_AT = TrapMath.HABIT_SICK;

    /** Withdrawal effects are re-applied every pass, so they lapse on their own. */
    private static final int EFFECT_TICKS = EVERY + 20;

    /** One in this many passes, a sick body gets a reminder that costs blood. */
    private static final int SICK_DAMAGE_EVERY = 4;
    /** Withdrawal never kills. Below this many half-hearts it stops biting. */
    private static final float DAMAGE_FLOOR = 5.0F;

    /** How often the nag goes on the actionbar while something is itching. */
    private static final int NAG_EVERY = 6;

    public enum Band {
        CLEAN("clean", Formatting.DARK_GRAY),
        ITCH("itching", Formatting.YELLOW),
        CRAVING("craving", Formatting.GOLD),
        SICK("withdrawal", Formatting.RED);

        private final String label;
        private final Formatting colour;

        Band(String label, Formatting colour) {
            this.label = label;
            this.colour = colour;
        }

        public String label() {
            return label;
        }

        public Formatting colour() {
            return colour;
        }
    }

    // --- state ----------------------------------------------------------------

    /** Meter per player per drug. Absent means clean. */
    private static final Map<UUID, EnumMap<Drug, Float>> HOOKED = new HashMap<>();
    /** World tick of the last hit, per player per drug. */
    private static final Map<UUID, EnumMap<Drug, Long>> LAST = new HashMap<>();

    /**
     * How badly the neighbourhood wants what YOU sell, per dealer.
     *
     * The other half of the system, and the half that pays. Every hand-over
     * puts points on it, weighted by the drug's own hook -- so a season of
     * selling weed barely moves it and a fortnight of selling dope takes it to
     * the ceiling. What it buys you is customers: more of them, more often,
     * wanting the strong stuff specifically, and taking more per visit.
     *
     * Per dealer rather than per town on purpose. It is a description of a
     * client list, not of the weather, and two people running two operations
     * out of the same city should have two different client lists.
     */
    private static final Map<UUID, Float> STREET = new HashMap<>();

    /**
     * How the street's meter is written in the save file.
     *
     * Same four-column row as a player's, with this in the drug column. It is
     * not a {@link Drug} id and cannot collide with one, which is exactly what
     * makes it safe to share the file rather than open a second one for a
     * single number per player.
     */
    private static final String STREET_ROW = "street";

    /** Points shed per minute with nobody buying. */
    private static final float STREET_DECAY = 0.5F;

    /**
     * Symptoms suppressed until this world tick, per player.
     *
     * Set by nerve tonic. Deliberately does NOT touch the meter -- the tonic
     * is a way to hold it together for an hour, not a cure, and conflating the
     * two would make the whole system a shop purchase.
     */
    private static final Map<UUID, Long> MEDICATED = new HashMap<>();

    /**
     * Meters that have been all the way into withdrawal this session.
     *
     * Only exists so "came back from it" can be told apart from "never went".
     * In memory and not saved: losing it means somebody has to earn the
     * advancement in one sitting, which is the harder reading of it anyway.
     */
    private static final Map<UUID, java.util.EnumSet<Drug>> WAS_SICK = new HashMap<>();

    private static Path saveFile;
    private static int pass;

    // --- wiring ---------------------------------------------------------------

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(TrapAddiction::load);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> save());
        ServerTickEvents.END_SERVER_TICK.register(TrapAddiction::tick);
        registerCommand();
    }

    /**
     * /addiction -- the meter, on a page.
     *
     * Not ops-only and not hidden behind an item: a number the player is
     * expected to play around has to be readable, and this one governs whether
     * they can hold a pickaxe.
     */
    private static void registerCommand() {
        CommandRegistrationCallback.EVENT.register((dispatcher, access, env) -> {
            dispatcher.register(CommandManager.literal("addiction")
                    .executes(context -> report(context.getSource().getPlayer())));
            // The word people actually reach for. One line, and it saves
            // somebody discovering the feature and then not finding it again.
            dispatcher.register(CommandManager.literal("habit")
                    .executes(context -> report(context.getSource().getPlayer())));
        });
    }

    // --- taking something -----------------------------------------------------

    /**
     * A hit lands. Puts points on that drug's meter and answers the craving.
     *
     * Called from every consumption path there is -- joints, the two pipes,
     * blends, powder and the needle -- because a route into the meter that
     * some of them miss is a route that makes the habit ignorable.
     *
     * @param potency the size of the hit, 1.0 being an ordinary one
     */
    public static void hit(ServerPlayerEntity player, Drug drug, float potency) {
        long now = worldTime(player);
        Band before = band(player.getUuid(), drug, now);

        EnumMap<Drug, Float> meters = HOOKED.computeIfAbsent(player.getUuid(),
                key -> new EnumMap<>(Drug.class));
        float hooked = Math.min(Drug.MAX,
                meters.getOrDefault(drug, 0.0F) + drug.hookPerHit() * Math.max(0.25F, potency));
        meters.put(drug, hooked);
        LAST.computeIfAbsent(player.getUuid(), key -> new EnumMap<>(Drug.class)).put(drug, now);

        // The symptoms go with the hit that answered them, not at the next
        // pass five seconds later -- relief has to feel immediate or it reads
        // as the withdrawal having randomly stopped.
        if (before != Band.CLEAN) {
            clearSymptoms(player);
        }
        if (before == Band.CRAVING || before == Band.SICK) {
            relief(player, drug, before);
        } else if (crossed(hooked, drug, potency)) {
            // The one moment the habit announces itself. Fires once, at the
            // crossing, because a meter nobody is told about is a mechanic
            // that only ever surprises people.
            player.sendMessage(Text.literal("Potrzebujesz więcej ")
                    .formatted(Formatting.GRAY)
                    .append(Text.literal(drug.display()).formatted(drug.text()))
                    .append(Text.literal(" niż kiedyś.").formatted(Formatting.GRAY))
                    .append(Text.literal("   /addiction").formatted(Formatting.DARK_GRAY)), false);
        }
        save();
    }

    /** True if this hit is the one that took the meter over the itch line. */
    private static boolean crossed(float hooked, Drug drug, float potency) {
        float itchFloor = ITCH_AT * Drug.MAX;
        return hooked >= itchFloor
                && hooked - drug.hookPerHit() * Math.max(0.25F, potency) < itchFloor;
    }

    /**
     * The thing you were chasing.
     *
     * Scaled by how bad it had got, so feeding a real withdrawal is a genuinely
     * strong moment and topping up a mild craving barely registers -- which is
     * the loop the whole system exists to make: the worse it gets the better
     * the fix feels, and the better the fix feels the worse it gets.
     */
    private static void relief(ServerPlayerEntity player, Drug drug, Band was) {
        int seconds = was == Band.SICK ? 45 : 25;
        int level = was == Band.SICK ? 1 : 0;
        player.addStatusEffect(new StatusEffectInstance(
                StatusEffects.REGENERATION, seconds * 20, level, false, true));
        player.addStatusEffect(new StatusEffectInstance(
                StatusEffects.SPEED, seconds * 20, 0, false, true));

        ServerWorld world = player.getWorld();
        world.spawnParticles(ParticleTypes.HEART,
                player.getX(), player.getEyeY() + 0.3, player.getZ(), 6, 0.3, 0.3, 0.3, 0.01);
        world.playSound(null, player.getBlockPos(), SoundEvents.BLOCK_BEACON_ACTIVATE,
                SoundCategory.PLAYERS, 0.35F, 1.6F);
        player.sendMessage(Text.literal("Już lepiej. ").formatted(Formatting.GREEN)
                .append(Text.literal("The " + drug.display() + " " + was.label()
                        + " lets go.").formatted(Formatting.DARK_GRAY)), true);
    }

    // --- the other side of the counter ---------------------------------------

    /**
     * You sold somebody something. The street remembers.
     *
     * Called from every sale that puts product into somebody else's hands --
     * the customers at the door and the tenants in the street -- because a
     * client list that only counts one of the two would make whichever channel
     * was missed the free one.
     *
     * @param units how many went across
     */
    public static void sold(ServerPlayerEntity seller, Drug drug, int units) {
        if (drug == null || units <= 0) {
            return;
        }
        // A quarter of what it would do to a person, per unit. Selling eight
        // Pure bags of dope in a visit is about thirty points of client list;
        // eight joints is under four.
        float gained = drug.hookPerHit() * units * 0.25F;
        STREET.merge(seller.getUuid(), gained,
                (had, add) -> Math.min(Drug.MAX, had + add));
        save();
    }

    /**
     * What the neighbourhood's appetite for this dealer is, 0 to
     * {@link Drug#MAX}.
     *
     * Read by {@link TrapDealing} for how often somebody turns up and how much
     * they take, and by {@link TrapHomes} for whether a tenant has developed
     * expensive tastes.
     */
    public static float street(UUID seller) {
        return STREET.getOrDefault(seller, 0.0F);
    }

    /**
     * Hold the symptoms off for a while. Nerve tonic's second job.
     *
     * Reusing the tonic rather than minting a methadone item is deliberate:
     * it already exists, it is already cheap and first-day craftable, and the
     * counterplay to a scare mechanic and the counterplay to a habit want to
     * be the same bottle -- otherwise the answer to being hooked is a second
     * shopping trip.
     */
    public static void medicate(ServerPlayerEntity player, int ticks) {
        MEDICATED.put(player.getUuid(), worldTime(player) + ticks);
        clearSymptoms(player);
    }

    // --- the pass -------------------------------------------------------------

    private static void tick(MinecraftServer server) {
        if (server.getTicks() % EVERY != 0) {
            return;
        }
        pass++;
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            // Outside evaluate(), which bails early for a clean player -- a
            // dealer who has never touched the stuff still has a client list,
            // and it should still cool off while they are stood there.
            STREET.computeIfPresent(player.getUuid(),
                    (key, had) -> had <= STREET_DECAY / EVALS_PER_MINUTE
                            ? null : had - STREET_DECAY / EVALS_PER_MINUTE);
            evaluate(player);
        }
    }

    /**
     * One player's meters, once.
     *
     * Decay and symptoms happen in the same place on purpose: the sick band
     * pays double decay, so working out the band and then spending it has to
     * be one calculation or the two would drift.
     */
    private static void evaluate(ServerPlayerEntity player) {
        EnumMap<Drug, Float> meters = HOOKED.get(player.getUuid());
        if (meters == null || meters.isEmpty()) {
            return;
        }
        long now = worldTime(player);
        boolean medicated = now < MEDICATED.getOrDefault(player.getUuid(), 0L);

        Drug worstDrug = null;
        Band worst = Band.CLEAN;
        boolean changed = false;

        for (Drug drug : List.copyOf(meters.keySet())) {
            float hooked = meters.get(drug);
            Band band = bandOf(pressure(hooked, drug, sinceUse(player.getUuid(), drug, now)));

            if (band == Band.SICK
                    // add() answers "was this new", which doubles as the guard
                    // against granting the same advancement every five seconds
                    // for as long as somebody stays ill.
                    && WAS_SICK.computeIfAbsent(player.getUuid(),
                            key -> java.util.EnumSet.noneOf(Drug.class)).add(drug)) {
                TrapAwards.grant(player, "hooked");
            }

            float shed = drug.decayPerMinute() / EVALS_PER_MINUTE
                    * (band == Band.SICK ? SICK_DECAY_BONUS : 1.0F);
            float left = hooked - shed;
            changed = true;
            if (left <= 0.05F) {
                meters.remove(drug);
                EnumMap<Drug, Long> last = LAST.get(player.getUuid());
                if (last != null) {
                    last.remove(drug);
                }
                // Cold turkey: this meter was properly bad at some point and is
                // now nothing. Tracked as a flag set on the way up rather than
                // read off the meter here, because by the time a meter clears
                // it says 0.05 and remembers nothing about how high it got.
                var sick = WAS_SICK.get(player.getUuid());
                if (sick != null && sick.remove(drug)) {
                    TrapAwards.grant(player, "clean_sheet");
                }
                continue;
            }
            meters.put(drug, left);

            if (band.ordinal() > worst.ordinal()) {
                worst = band;
                worstDrug = drug;
            }
        }

        if (worst == Band.CLEAN || medicated) {
            clearSymptoms(player);
        } else {
            symptoms(player, worstDrug, worst);
        }
        if (changed && pass % 12 == 0) {
            // Meters move every pass; writing the file every pass would be a
            // disk write every five seconds for the life of the server.
            save();
        }
    }

    /**
     * What being short of it does to you.
     *
     * One set of symptoms for the worst drug rather than stacking every habit,
     * because three simultaneous withdrawals would be an unplayable pile of
     * debuffs and the player can only really feel the loudest one anyway. The
     * quieter habits are still on the meter and still nagging.
     */
    private static void symptoms(ServerPlayerEntity player, Drug drug, Band band) {
        player.addStatusEffect(new StatusEffectInstance(TrapContent.withdrawalEffect,
                EFFECT_TICKS, band.ordinal() - 1, false, true));

        if (band != Band.ITCH) {
            int level = band == Band.SICK ? 1 : 0;
            switch (drug) {
                case COKE -> {
                    // The jitters: hands are useless, and at its worst you
                    // cannot keep still enough to swing properly either.
                    add(player, StatusEffects.WEAKNESS, level + (band == Band.SICK ? 1 : 0));
                    add(player, StatusEffects.MINING_FATIGUE, level);
                    if (band == Band.SICK) {
                        add(player, StatusEffects.SLOWNESS, 0);
                    }
                }
                case DOPE -> {
                    // Dopesick: everything hurts and nothing works.
                    add(player, StatusEffects.SLOWNESS, level + 1);
                    add(player, StatusEffects.WEAKNESS, level + 1);
                    add(player, StatusEffects.MINING_FATIGUE, level + 1);
                    add(player, StatusEffects.NAUSEA, 0);
                }
                default -> {
                    // Irritable and hungry. Weed's withdrawal is real but it is
                    // the mildest thing in the game, and it should read that way.
                    add(player, StatusEffects.MINING_FATIGUE, level);
                    add(player, StatusEffects.HUNGER, 0);
                    if (band == Band.SICK) {
                        add(player, StatusEffects.WEAKNESS, 0);
                    }
                }
            }
        }

        ServerWorld world = player.getWorld();
        switch (band) {
            case ITCH -> world.spawnParticles(ParticleTypes.SMOKE,
                    player.getX(), player.getEyeY() + 0.4, player.getZ(), 2, 0.25, 0.15, 0.25, 0.0);
            case CRAVING -> {
                world.spawnParticles(ParticleTypes.ASH,
                        player.getX(), player.getEyeY() + 0.3, player.getZ(), 8, 0.35, 0.4, 0.35, 0.0);
                world.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_PLAYER_BREATH,
                        SoundCategory.PLAYERS, 0.25F, 0.7F);
            }
            case SICK -> {
                world.spawnParticles(ParticleTypes.ASH,
                        player.getX(), player.getEyeY() + 0.3, player.getZ(), 16, 0.4, 0.5, 0.4, 0.01);
                // A heartbeat you can hear is the cheapest way to make a debuff
                // feel like a body rather than a status bar.
                world.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_WARDEN_HEARTBEAT,
                        SoundCategory.PLAYERS, 0.6F, 1.3F);
                if (pass % SICK_DAMAGE_EVERY == 0 && player.getHealth() > DAMAGE_FLOOR) {
                    // Never fatal: it stops biting before it can kill, because
                    // a habit that empties your inventory onto the floor while
                    // you are AFK is a griefing mechanic, not a drama.
                    DamageSource source = player.getDamageSources().magic();
                    player.damage(world, source, 1.0F);
                }
            }
            default -> {
            }
        }

        if (pass % NAG_EVERY == 0) {
            player.sendMessage(Text.literal(nag(band))
                    .formatted(Formatting.GRAY)
                    .append(Text.literal(drug.display()).formatted(drug.text()))
                    .append(Text.literal(".").formatted(Formatting.GRAY)), true);
        }
    }

    private static String nag(Band band) {
        return switch (band) {
            case ITCH -> "Przydałoby ci się trochę: ";
            case CRAVING -> "Nie możesz przestać myśleć o: ";
            case SICK -> "Jesteś w kiepskim stanie bez: ";
            default -> "";
        };
    }

    private static void add(ServerPlayerEntity player, RegistryEntry<StatusEffect> effect, int level) {
        player.addStatusEffect(new StatusEffectInstance(effect, EFFECT_TICKS, level, false, true));
    }

    /**
     * Take the symptoms off, and only the symptoms.
     *
     * Every one of these is also something the game gives you for other
     * reasons, so this removes them wholesale rather than tracking which ones
     * were ours -- the alternative is a per-player bookkeeping map that would
     * still get it wrong the moment a witch throws a potion. The cost is that
     * a fix cures somebody else's weakness too, which is an acceptable lie.
     */
    private static void clearSymptoms(ServerPlayerEntity player) {
        if (player.getStatusEffect(TrapContent.withdrawalEffect) == null) {
            return;
        }
        player.removeStatusEffect(TrapContent.withdrawalEffect);
        player.removeStatusEffect(StatusEffects.MINING_FATIGUE);
        player.removeStatusEffect(StatusEffects.WEAKNESS);
        player.removeStatusEffect(StatusEffects.NAUSEA);
        player.removeStatusEffect(StatusEffects.HUNGER);
        player.removeStatusEffect(StatusEffects.SLOWNESS);
    }

    // --- reading it -----------------------------------------------------------

    /** How badly this player wants that one, right now. */
    public static Band band(UUID player, Drug drug, long now) {
        EnumMap<Drug, Float> meters = HOOKED.get(player);
        if (meters == null || !meters.containsKey(drug)) {
            return Band.CLEAN;
        }
        return bandOf(pressure(meters.get(drug), drug, sinceUse(player, drug, now)));
    }

    /** The meter itself, 0 to {@link Drug#MAX}. */
    public static float hooked(UUID player, Drug drug) {
        EnumMap<Drug, Float> meters = HOOKED.get(player);
        return meters == null ? 0.0F : meters.getOrDefault(drug, 0.0F);
    }

    /** See the class note for why this is a product rather than a timer. */
    public static float pressure(float hooked, Drug drug, long sinceUse) {
        return TrapMath.habitPressure(hooked, Drug.MAX, sinceUse,
                drug.cravePeriodMinutes());
    }

    public static Band bandOf(float pressure) {
        // Band's ordinals and TrapMath's BAND_* constants are the same four
        // values in the same order, which is asserted by the array length here
        // rather than left as a comment somebody can break.
        return Band.values()[TrapMath.habitBand(pressure)];
    }

    private static long sinceUse(UUID player, Drug drug, long now) {
        EnumMap<Drug, Long> last = LAST.get(player);
        if (last == null || !last.containsKey(drug)) {
            return Long.MAX_VALUE / 4;
        }
        return Math.max(0, now - last.get(drug));
    }

    private static long worldTime(ServerPlayerEntity player) {
        return player.getWorld().getTime();
    }

    private static int report(ServerPlayerEntity player) {
        if (player == null) {
            return 0;
        }
        long now = worldTime(player);
        EnumMap<Drug, Float> meters = HOOKED.get(player.getUuid());
        MutableText out = Text.literal("Twoje nałogi\n")
                .formatted(Formatting.DARK_GREEN, Formatting.BOLD);

        if (meters == null || meters.isEmpty()) {
            out.append(Text.literal("  Nic. Jesteś czysty.\n").formatted(Formatting.GRAY));
            streetLine(player, out);
            player.sendMessage(out, false);
            return 1;
        }
        // Worst first: the one that is about to make you drop your pickaxe is
        // the one somebody opened this to find out about.
        List<Drug> order = new ArrayList<>(meters.keySet());
        order.sort((a, b) -> Float.compare(meters.get(b), meters.get(a)));
        for (Drug drug : order) {
            float hooked = meters.get(drug);
            Band band = bandOf(pressure(hooked, drug, sinceUse(player.getUuid(), drug, now)));
            out.append(Text.literal("  " + bar(hooked)).withColor(drug.colour()))
                    .append(Text.literal(" " + Math.round(hooked) + "%  ")
                            .formatted(Formatting.WHITE))
                    .append(Text.literal(pad(drug.display(), 9)).withColor(drug.colour()))
                    .append(Text.literal(band.label() + "\n").formatted(band.colour()));
        }
        long medicated = MEDICATED.getOrDefault(player.getUuid(), 0L) - now;
        if (medicated > 0) {
            out.append(Text.literal("  Lek wycisza objawy jeszcze: " + (medicated / 20) + "s\n")
                    .formatted(Formatting.AQUA));
        }
        out.append(Text.literal("  Przetrzymanie najgorszego zbija licznik dwa razy szybciej.\n")
                .formatted(Formatting.DARK_GRAY));
        streetLine(player, out);
        player.sendMessage(out, false);
        return 1;
    }

    /**
     * The client list, on the same page as the habit.
     *
     * Both meters in one readout because they are the same mechanic pointed in
     * two directions, and because the interesting thing about them is the
     * comparison: a big client list and a clean sheet is a business, and the
     * other way round is a problem.
     */
    private static void streetLine(ServerPlayerEntity player, MutableText out) {
        float demand = street(player.getUuid());
        if (demand <= 0.5F) {
            return;
        }
        out.append(Text.literal("  " + bar(demand)).formatted(Formatting.DARK_GREEN))
                .append(Text.literal(" " + Math.round(demand) + "%  ").formatted(Formatting.WHITE))
                .append(Text.literal("ulica chce tego, co sprzedajesz\n")
                        .formatted(Formatting.GRAY));
    }

    /** Ten cells of meter. Same trick the coin chart uses. */
    private static String bar(float hooked) {
        int filled = Math.max(1, Math.round(hooked / Drug.MAX * 10));
        return "█".repeat(filled) + "░".repeat(10 - filled);
    }

    private static String pad(String text, int width) {
        return text.length() >= width ? text : text + " ".repeat(width - text.length());
    }

    // --- persistence ----------------------------------------------------------
    // One row per player per drug: "<uuid> <drug> <hook> <lastUseTick>". Flat
    // text like the rest of the mod's saves, so a broken row costs one habit
    // rather than the file.

    private static void load(MinecraftServer server) {
        saveFile = server.getSavePath(WorldSavePath.ROOT).resolve("trapcraft-addiction.txt");
        HOOKED.clear();
        LAST.clear();
        STREET.clear();
        try {
            if (!Files.exists(saveFile)) {
                return;
            }
            for (String line : Files.readAllLines(saveFile)) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length != 4) {
                    continue;
                }
                UUID player = UUID.fromString(parts[0]);
                if (STREET_ROW.equals(parts[1])) {
                    STREET.put(player, Float.parseFloat(parts[2]));
                    continue;
                }
                Drug drug = Drug.byId(parts[1]);
                if (drug == null) {
                    continue;   // a strain that has since been renamed away
                }
                HOOKED.computeIfAbsent(player, key -> new EnumMap<>(Drug.class))
                        .put(drug, Float.parseFloat(parts[2]));
                LAST.computeIfAbsent(player, key -> new EnumMap<>(Drug.class))
                        .put(drug, Long.parseLong(parts[3]));
            }
        } catch (Exception failure) {
            TrapCraft.LOGGER.error("couldn't read addiction meters: {}", failure.toString());
        }
    }

    private static void save() {
        if (saveFile == null) {
            return;
        }
        try {
            List<String> lines = new ArrayList<>();
            HOOKED.forEach((player, meters) -> meters.forEach((drug, hooked) -> {
                EnumMap<Drug, Long> last = LAST.get(player);
                long when = last == null ? 0L : last.getOrDefault(drug, 0L);
                lines.add(player + " " + drug.id() + " "
                        + String.format(java.util.Locale.ROOT, "%.2f", hooked) + " " + when);
            }));
            STREET.forEach((player, demand) -> lines.add(player + " " + STREET_ROW + " "
                    + String.format(java.util.Locale.ROOT, "%.2f", demand) + " 0"));
            Files.write(saveFile, lines);
        } catch (Exception failure) {
            TrapCraft.LOGGER.error("couldn't save addiction meters: {}", failure.toString());
        }
    }
}
