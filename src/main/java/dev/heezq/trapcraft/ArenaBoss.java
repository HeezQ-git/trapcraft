package dev.heezq.trapcraft;

import net.minecraft.entity.boss.BossBar;
import net.minecraft.item.Item;
import net.minecraft.particle.DustColorTransitionParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;

import java.util.List;

/**
 * One kind of boss: who it is, where it fights, what it says, what it drops.
 *
 * {@link TrapArena} runs one event loop -- omen, gathering, fight, victory,
 * home -- and everything that differs between bosses is asked of the
 * {@code ArenaBoss} it picked. A new boss is a subclass of this, a subclass
 * of {@link ArenaBossEntity} for the fight itself, a {@link DisplayRig} for
 * the body, a blueprint from {@code tools/gen_arena.py}, and a line in
 * {@link ArenaBosses}. Nothing in the event loop knows a witness from a
 * slot machine.
 *
 * All four arenas live in the one dimension, far apart; each boss says
 * where its own is through {@link #origin}. Positions handed back from here
 * are absolute.
 */
public abstract class ArenaBoss {

    /** A title and its subtitle, the way the screen shows them. */
    public record Line(Text title, Text subtitle) {
    }

    // --- identity ---------------------------------------------------------------

    public abstract String id();

    /** As it appears on bars and titles: upper case, no styling. */
    public abstract String displayName();

    /** The colour its name is written in. */
    public abstract Formatting colour();

    public BossBar.Color barColour(int phase) {
        return phase == 3 ? BossBar.Color.RED : phase == 2 ? BossBar.Color.PINK : BossBar.Color.PURPLE;
    }

    public MutableText styledName() {
        return Text.literal(displayName()).formatted(colour(), Formatting.BOLD);
    }

    // --- geometry -----------------------------------------------------------------

    /** The blueprint's (0, 0, 0): the block under the middle of the floor. */
    public abstract BlockPos origin();

    public Vec3d centre() {
        return new Vec3d(origin().getX() + 0.5, origin().getY() + 1.0, origin().getZ() + 0.5);
    }

    /** How far from the centre somebody still counts as in the fight. */
    public double pitRadius() {
        return 20.5;
    }

    /** How high above the floor the pit still reaches. */
    public int pitCeiling() {
        return 14;
    }

    /** Below this the void sends you back to the gate. */
    public int voidY() {
        return origin().getY() - 24;
    }

    /** Where a player lands on arrival, and which way they face. */
    public abstract Vec3d gate();

    public abstract float gateYaw();

    /** Somewhere in the stands for a knocked-out player. */
    public abstract Vec3d standsSpot(Random random);

    // --- the fight ------------------------------------------------------------------

    public abstract ArenaBossEntity spawn(ServerWorld world, int players);

    /** The ability ids the debug command may force. */
    public abstract List<String> abilities();

    // --- words ----------------------------------------------------------------------

    /** The first omen, minus the link. Ends with a newline. */
    public abstract Text omen(String clock);

    /** The reminder, minus the link. Ends with a newline. */
    public Text omenAgain(String clock) {
        return Text.literal(cap(displayName()) + " czeka. ").formatted(colour())
                .append(Text.literal(clock + ".\n").formatted(Formatting.WHITE));
    }

    public abstract Text nobodyCame();

    public abstract Line fightStart();

    public abstract Text fightBroadcast();

    public abstract Line phase(int phase);

    public abstract Text phaseBroadcast(int phase);

    public abstract Line enrage();

    public abstract Text enrageBroadcast();

    public abstract Line victory();

    /** The chat table's header, e.g. "✦ OKO ZAMKNIĘTE ✦". */
    public abstract String victoryHeader();

    public abstract Text joinRefused();

    public Text knockedOutSwing() {
        return Text.literal("Jesteś w nokaucie. " + cap(displayName()) + " cię nie widzi.")
                .formatted(Formatting.RED);
    }

    public Text knockoutLine(String name) {
        return Text.literal(name + " padł. ").formatted(Formatting.RED)
                .append(Text.literal(cap(displayName()) + " odzyskuje "
                        + Math.round(ArenaMath.KNOCKOUT_HEAL * 100) + "%.").formatted(Formatting.GRAY));
    }

    /** "Obserwator", from "OBSERWATOR": for the middle of a sentence. */
    public static String cap(String upper) {
        String lower = upper.toLowerCase(java.util.Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    // --- loot ------------------------------------------------------------------------

    public abstract Item trophy();

    public abstract String trophyName();

    /** The advancement granted to everyone present at the kill. */
    public abstract String killAward();

    // --- hooks ------------------------------------------------------------------------

    /** After the blueprint is stamped: put the lights in their resting state. */
    public void onBuilt(ServerWorld world, ArenaBlueprint blueprint) {
    }

    public void onFightStart(ServerWorld world, ArenaBlueprint blueprint) {
    }

    public void onPhase(ServerWorld world, ArenaBlueprint blueprint, int phase) {
    }

    public void onEnd(ServerWorld world, ArenaBlueprint blueprint) {
    }

    /** Somebody just arrived at the gate: lend what the fight needs. */
    public void onEnter(ServerPlayerEntity player) {
    }

    /** Somebody left the arena, by any door: take back what was lent, drop what was stacked. */
    public void onLeave(ServerPlayerEntity player) {
    }

    /** Somebody was knocked out: clear what should not follow them to the stands. */
    public void onKnockout(ServerPlayerEntity player) {
    }

    /** Once a second during an event, for the boss's own debuff rules. */
    public void debuffTick(ServerWorld world, List<ServerPlayerEntity> here, int now) {
    }

    /** The victory show's two colours: the dust rings run from the first to the second. */
    public int[] showColours() {
        return new int[]{0x34d8ea, 0x8a4fd8};
    }

    /**
     * Eight seconds of light, none of it an entity.
     *
     * Bursts overhead with a flash and the firework blast sound, dust rings
     * running out from the middle, and whatever {@code veins} the arena
     * tagged coming back on one by one with a rising chime. Reads as
     * fireworks from inside and cannot crash anybody: firework rockets do,
     * on this pack.
     */
    public void showTick(ServerWorld world, ArenaBlueprint blueprint, List<BlockPos> veins, int t) {
        Random random = world.getRandom();
        Vec3d centre = centre();
        if (t % 2 == 0 && !veins.isEmpty()) {
            int step = t / 2;
            if (step < veins.size()) {
                BlockPos vein = veins.get(step);
                world.setBlockState(vein, TrapArena.LIT, net.minecraft.block.Block.NOTIFY_LISTENERS);
                world.playSound(null, vein.getX() + 0.5, vein.getY() + 1.0, vein.getZ() + 0.5,
                        SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.HOSTILE, 1.2F,
                        0.6F + 1.2F * step / (float) veins.size());
                world.spawnParticles(ParticleTypes.END_ROD, vein.getX() + 0.5, vein.getY() + 1.2,
                        vein.getZ() + 0.5, 12, 0.2, 0.6, 0.2, 0.08);
            }
        }
        if (t % 8 == 0) {
            double angle = random.nextDouble() * Math.PI * 2;
            double r = 3.0 + random.nextDouble() * (pitRadius() - 5.0);
            double x = centre.x + Math.cos(angle) * r;
            double y = centre.y + 4.0 + random.nextDouble() * 6.0;
            double z = centre.z + Math.sin(angle) * r;
            world.spawnParticles(ParticleTypes.TOTEM_OF_UNDYING, x, y, z, 70, 0.2, 0.2, 0.2, 0.55);
            world.spawnParticles(ParticleTypes.FLASH, x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
            world.spawnParticles(ParticleTypes.END_ROD, x, y, z, 25, 0.3, 0.3, 0.3, 0.18);
            world.playSound(null, x, y, z, SoundEvents.ENTITY_FIREWORK_ROCKET_LARGE_BLAST,
                    SoundCategory.HOSTILE, 1.5F, 0.8F + random.nextFloat() * 0.4F);
            if (random.nextBoolean()) {
                world.playSound(null, x, y, z, SoundEvents.ENTITY_FIREWORK_ROCKET_TWINKLE,
                        SoundCategory.HOSTILE, 1.0F, 1.0F);
            }
        }
        if (t % 40 < 20) {
            int[] colours = showColours();
            DustColorTransitionParticleEffect ring = new DustColorTransitionParticleEffect(colours[0], colours[1], 1.4F);
            double radius = (t % 40) * 1.0 + 1.0;
            int points = (int) (radius * 4);
            for (int i = 0; i < points; i++) {
                double a = i * Math.PI * 2 / points;
                world.spawnParticles(ring, centre.x + Math.cos(a) * radius, centre.y + 0.6,
                        centre.z + Math.sin(a) * radius, 1, 0.0, 0.1, 0.0, 0.0);
            }
            if (t % 40 == 0) {
                world.playSound(null, centre.x, centre.y, centre.z, SoundEvents.BLOCK_BELL_RESONATE,
                        SoundCategory.HOSTILE, 1.2F, 0.7F);
            }
        }
    }
}
