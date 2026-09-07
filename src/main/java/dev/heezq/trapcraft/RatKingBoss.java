package dev.heezq.trapcraft;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.Item;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;

import java.util.List;

/**
 * Król Szczurów. The rat king.
 *
 * Under the town the sewers are full of rats, and one of them wears a
 * crown. He fights in a cistern with six tunnels in the wall and a cross
 * of water in the floor: the rats come out of the tunnels, he goes into
 * them, and the water is where his Zaraza washes off. Enclosed, wet and
 * dim -- the opposite of the roof.
 */
public final class RatKingBoss extends ArenaBoss {
    public static final RatKingBoss INSTANCE = new RatKingBoss();

    private static final BlockPos ORIGIN = new BlockPos(0, 64, 2000);
    private static final double RADIUS = 18.5;
    private static final BlockState DIM = Blocks.DARK_PRISMARINE.getDefaultState();
    private static final BlockState DARK = Blocks.MUD.getDefaultState();

    private ArenaBlueprint plan;

    private RatKingBoss() {
    }

    @Override
    public String id() {
        return "ratking";
    }

    @Override
    public String displayName() {
        return "KRÓL SZCZURÓW";
    }

    @Override
    public Formatting colour() {
        return Formatting.DARK_GREEN;
    }

    @Override
    public BossBar.Color barColour(int phase) {
        return phase == 3 ? BossBar.Color.RED : phase == 2 ? BossBar.Color.PINK : BossBar.Color.GREEN;
    }

    @Override
    public BlockPos origin() {
        return ORIGIN;
    }

    @Override
    public double pitRadius() {
        return RADIUS;
    }

    @Override
    public int pitCeiling() {
        return 8;
    }

    /** Inside the pipe from the south, facing the chamber. */
    @Override
    public Vec3d gate() {
        return new Vec3d(ORIGIN.getX() + 0.5, ORIGIN.getY() + 1.0, ORIGIN.getZ() + 27.5);
    }

    @Override
    public float gateYaw() {
        return 180.0F;
    }

    /** The balcony under the ceiling, behind the bars. */
    @Override
    public Vec3d standsSpot(Random random) {
        double angle = random.nextDouble() * Math.PI * 2;
        Vec3d centre = centre();
        return new Vec3d(centre.x + Math.cos(angle) * 16.7, ORIGIN.getY() + 6, centre.z + Math.sin(angle) * 16.7);
    }

    @Override
    public ArenaBossEntity spawn(ServerWorld world, int players) {
        RatKingEntity boss = new RatKingEntity(RatKingEntity.TYPE, world);
        Vec3d centre = centre();
        boss.refreshPositionAndAngles(centre.x, centre.y, centre.z, 0.0F, 0.0F);
        boss.sizeFor(players);
        world.spawnEntity(boss);
        return boss;
    }

    @Override
    public List<String> abilities() {
        return RatKingEntity.ABILITY_IDS;
    }

    /** The six tunnel mouths, on the floor. */
    public List<BlockPos> tunnels() {
        return plan == null ? List.of() : plan.tagged("tunnel");
    }

    // --- words ---------------------------------------------------------------

    @Override
    public Text omen(String clock) {
        return Text.empty()
                .append(Text.literal("Coś ryje pod miastem.\n").formatted(Formatting.DARK_GREEN))
                .append(Text.literal("Kanały pełne szczurów, a wśród nich jeden w koronie. "
                        + "Wszystko, co spływa, jest jego.\n").formatted(Formatting.GRAY))
                .append(Text.literal("Arena otwiera się za " + clock + ". ").formatted(Formatting.WHITE))
                .append(Text.literal("Nikt tam nie ginie: nokaut, trybuny, powrót.\n")
                        .formatted(Formatting.DARK_GRAY, Formatting.ITALIC));
    }

    @Override
    public Text nobodyCame() {
        return Text.literal("Nikt nie przyszedł. ").formatted(Formatting.GRAY, Formatting.ITALIC)
                .append(Text.literal("Kanały zamknęły się nad koroną.").formatted(Formatting.DARK_GRAY));
    }

    @Override
    public Line fightStart() {
        return new Line(Text.literal("KRÓL SZCZURÓW").formatted(Formatting.DARK_GREEN, Formatting.BOLD),
                Text.literal("Wszystko, co spływa, jest jego.").formatted(Formatting.GREEN));
    }

    @Override
    public Text fightBroadcast() {
        return Text.literal("Król Szczurów jest w kanałach. ").formatted(Formatting.DARK_GREEN, Formatting.BOLD)
                .append(Text.literal("Dziesięć minut, zanim znów zniknie pod miastem. ").formatted(Formatting.GRAY));
    }

    @Override
    public Line phase(int phase) {
        return new Line(Text.literal(phase == 2 ? "POD ZIEMIĄ" : "ZARAZA").formatted(Formatting.DARK_GREEN, Formatting.BOLD),
                Text.literal(phase == 2 ? "Ryje pod tobą." : "Woda ją zmywa.").formatted(Formatting.GRAY));
    }

    @Override
    public Text phaseBroadcast(int phase) {
        return Text.literal("Król Szczurów: ").formatted(Formatting.DARK_GREEN)
                .append(Text.literal(phase == 2 ? "faza II. Ryje pod ziemią." : "faza III. Zaraza.")
                        .formatted(Formatting.GRAY));
    }

    @Override
    public Line enrage() {
        return new Line(Text.literal("UCIEKŁ W KANAŁY").formatted(Formatting.RED, Formatting.BOLD),
                Text.literal("Za wolno.").formatted(Formatting.GRAY));
    }

    @Override
    public Text enrageBroadcast() {
        return Text.literal("Król Szczurów uciekł w kanały. ").formatted(Formatting.RED, Formatting.BOLD)
                .append(Text.literal("Korona została na jego głowie.").formatted(Formatting.GRAY));
    }

    @Override
    public Line victory() {
        return new Line(Text.literal("KORONA SPADŁA").formatted(Formatting.GOLD, Formatting.BOLD),
                Text.literal("Kanały ucichły.").formatted(Formatting.YELLOW));
    }

    @Override
    public String victoryHeader() {
        return "✦ KORONA SPADŁA ✦";
    }

    @Override
    public Text joinRefused() {
        return Text.literal("W kanałach jest cicho. Król jeszcze nie wyszedł.").formatted(Formatting.RED);
    }

    // --- loot -------------------------------------------------------------------

    @Override
    public Item trophy() {
        return TrapContent.ratCrown;
    }

    @Override
    public String trophyName() {
        return "Korona Szczurów";
    }

    @Override
    public String killAward() {
        return "ratking";
    }

    @Override
    public int[] showColours() {
        return new int[]{0x6bb51a, 0xffd54a};
    }

    // --- the cistern --------------------------------------------------------------

    @Override
    public void onBuilt(ServerWorld world, ArenaBlueprint blueprint) {
        plan = blueprint;
        lights(world, 1);
        sigil(world, false);
    }

    @Override
    public void onFightStart(ServerWorld world, ArenaBlueprint blueprint) {
        plan = blueprint;
        sigil(world, true);
    }

    @Override
    public void onPhase(ServerWorld world, ArenaBlueprint blueprint, int phase) {
        lights(world, phase);
    }

    @Override
    public void onEnd(ServerWorld world, ArenaBlueprint blueprint) {
        plan = blueprint;
        lights(world, 1);
        sigil(world, false);
    }

    private void lights(ServerWorld world, int phase) {
        if (plan == null) {
            return;
        }
        for (BlockPos pos : plan.tagged("vein")) {
            BlockState state = phase == 1 ? plan.original(pos) : phase == 2 ? DIM : DARK;
            world.setBlockState(pos, state, Block.NOTIFY_LISTENERS);
        }
        if (phase > 1) {
            Vec3d centre = centre();
            world.playSound(null, centre.x, centre.y, centre.z, SoundEvents.BLOCK_BEACON_DEACTIVATE,
                    SoundCategory.HOSTILE, 2.0F, phase == 3 ? 0.5F : 0.8F);
        }
    }

    private void sigil(ServerWorld world, boolean on) {
        if (plan == null) {
            return;
        }
        for (BlockPos pos : plan.tagged("sigil")) {
            world.setBlockState(pos, on ? TrapArena.LIT : plan.original(pos), Block.NOTIFY_LISTENERS);
        }
    }

    // --- Zaraza -----------------------------------------------------------------------

    @Override
    public void onLeave(ServerPlayerEntity player) {
        player.removeStatusEffect(TrapContent.plagueEffect);
    }

    @Override
    public void onKnockout(ServerPlayerEntity player) {
        player.removeStatusEffect(TrapContent.plagueEffect);
    }

    /**
     * What Zaraza does, once a second, from outside the effect loop: two
     * stacks make the screen swim, three bleed, and water takes a stack a
     * second off anyone standing in it.
     */
    @Override
    public void debuffTick(ServerWorld world, List<ServerPlayerEntity> here, int now) {
        for (ServerPlayerEntity player : here) {
            StatusEffectInstance plague = player.getStatusEffect(TrapContent.plagueEffect);
            if (plague == null) {
                continue;
            }
            int stacks = plague.getAmplifier();
            if (stacks >= 1 && now % 60 == 0) {
                player.addStatusEffect(new StatusEffectInstance(StatusEffects.NAUSEA, 70, 0, true, false, false));
            }
            if (stacks >= 2 && now % 40 == 0) {
                player.damage(world, world.getDamageSources().magic(), 1.0F);
                player.playSoundToPlayer(SoundEvents.ENTITY_SLIME_SQUISH, SoundCategory.HOSTILE, 0.7F, 0.5F);
            }
            if (!player.isTouchingWater()) {
                continue;
            }
            int left = plague.getDuration();
            player.removeStatusEffect(TrapContent.plagueEffect);
            if (stacks > 0) {
                player.addStatusEffect(new StatusEffectInstance(TrapContent.plagueEffect, left, stacks - 1,
                        false, true, true));
            }
            world.spawnParticles(ParticleTypes.BUBBLE_POP, player.getX(), player.getY() + 0.6, player.getZ(),
                    10, 0.3, 0.3, 0.3, 0.05);
            player.playSoundToPlayer(SoundEvents.ENTITY_GENERIC_SPLASH, SoundCategory.PLAYERS, 0.5F, 1.3F);
        }
    }

    /** Add Zaraza stacks. Fresh instance rather than a longer one, so the icon's amplifier moves. */
    public static void plague(ServerPlayerEntity player, int add) {
        TrapArena.stack(player, TrapContent.plagueEffect, add, ArenaMath.PLAGUE_MAX, ArenaMath.PLAGUE_TICKS);
    }
}
