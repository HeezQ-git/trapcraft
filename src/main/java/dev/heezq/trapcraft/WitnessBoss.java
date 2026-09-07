package dev.heezq.trapcraft;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.Item;
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
 * Obserwator. The watcher.
 *
 * The motionless figure Paranoia shows at render distance -- the one that
 * vanishes when you turn to look -- was never a pillager; it wore the
 * shape. Once in a while it stops vanishing, comes down off the edge of the
 * map, and stands in a pit under a fixed midnight. Its currency is Groza:
 * every hit it lands is a stack, and company is what takes a stack off.
 */
public final class WitnessBoss extends ArenaBoss {
    public static final WitnessBoss INSTANCE = new WitnessBoss();

    private static final BlockPos ORIGIN = new BlockPos(0, 64, 0);
    private static final BlockState DIM = Blocks.CRYING_OBSIDIAN.getDefaultState();
    private static final BlockState DARK = Blocks.POLISHED_BLACKSTONE.getDefaultState();
    private static final BlockState DARK_WALL = Blocks.POLISHED_BLACKSTONE_BRICKS.getDefaultState();

    private WitnessBoss() {
    }

    @Override
    public String id() {
        return "witness";
    }

    @Override
    public String displayName() {
        return "OBSERWATOR";
    }

    @Override
    public Formatting colour() {
        return Formatting.LIGHT_PURPLE;
    }

    @Override
    public BlockPos origin() {
        return ORIGIN;
    }

    @Override
    public Vec3d gate() {
        return new Vec3d(ORIGIN.getX() + 0.5, ORIGIN.getY() + 1.0, ORIGIN.getZ() + 27.5);
    }

    @Override
    public float gateYaw() {
        return 180.0F;
    }

    @Override
    public Vec3d standsSpot(Random random) {
        // Anywhere in the stands but the gate's own sector, where the landing
        // is and a knocked-out player would fall to the floor of it instead.
        double angle = Math.toRadians(110 + random.nextInt(320));
        Vec3d centre = centre();
        return new Vec3d(centre.x + Math.cos(angle) * 28.5, ORIGIN.getY() + 6, centre.z + Math.sin(angle) * 28.5);
    }

    @Override
    public ArenaBossEntity spawn(ServerWorld world, int players) {
        WitnessEntity boss = new WitnessEntity(WitnessEntity.TYPE, world);
        Vec3d centre = centre();
        boss.refreshPositionAndAngles(centre.x, centre.y, centre.z, 180.0F, 0.0F);
        boss.sizeFor(players);
        world.spawnEntity(boss);
        return boss;
    }

    @Override
    public List<String> abilities() {
        return WitnessEntity.ABILITY_IDS;
    }

    // --- words ---------------------------------------------------------------

    @Override
    public Text omen(String clock) {
        return Text.empty()
                .append(Text.literal("Obserwator zszedł z krawędzi mapy.\n").formatted(Formatting.LIGHT_PURPLE))
                .append(Text.literal("Ta sylwetka na granicy widoku, która znikała, gdy się odwracałeś. "
                        + "Dziś nie zniknie.\n").formatted(Formatting.GRAY))
                .append(Text.literal("Arena otwiera się za " + clock + ". ").formatted(Formatting.WHITE))
                .append(Text.literal("Nikt tam nie ginie: nokaut, trybuny, powrót.\n")
                        .formatted(Formatting.DARK_GRAY, Formatting.ITALIC));
    }

    @Override
    public Text nobodyCame() {
        return Text.literal("Nikt nie przyszedł. ").formatted(Formatting.GRAY, Formatting.ITALIC)
                .append(Text.literal("Obserwator wrócił na krawędź mapy.").formatted(Formatting.DARK_GRAY));
    }

    @Override
    public Line fightStart() {
        return new Line(Text.literal("OBSERWATOR").formatted(Formatting.DARK_PURPLE, Formatting.BOLD),
                Text.literal("Patrzył od pierwszego dnia.").formatted(Formatting.LIGHT_PURPLE));
    }

    @Override
    public Text fightBroadcast() {
        return Text.literal("Obserwator jest na arenie. ").formatted(Formatting.LIGHT_PURPLE, Formatting.BOLD)
                .append(Text.literal("Dziesięć minut, zanim znów zniknie. ").formatted(Formatting.GRAY));
    }

    @Override
    public Line phase(int phase) {
        return new Line(Text.literal(phase == 2 ? "FAZA II" : "FAZA III").formatted(Formatting.LIGHT_PURPLE, Formatting.BOLD),
                Text.literal(phase == 2 ? "Nie mruga." : "Gasną światła.").formatted(Formatting.GRAY));
    }

    @Override
    public Text phaseBroadcast(int phase) {
        return Text.literal("Obserwator: ").formatted(Formatting.DARK_PURPLE)
                .append(Text.literal(phase == 2 ? "faza II. Nie mruga." : "faza III. Gasną światła.")
                        .formatted(Formatting.GRAY));
    }

    @Override
    public Line enrage() {
        return new Line(Text.literal("ZNIKNĄŁ").formatted(Formatting.RED, Formatting.BOLD),
                Text.literal("Za wolno.").formatted(Formatting.GRAY));
    }

    @Override
    public Text enrageBroadcast() {
        return Text.literal("Obserwator zniknął. ").formatted(Formatting.RED, Formatting.BOLD)
                .append(Text.literal("Nikt nie zamknął mu oka na czas.").formatted(Formatting.GRAY));
    }

    @Override
    public Line victory() {
        return new Line(Text.literal("OKO ZAMKNIĘTE").formatted(Formatting.GOLD, Formatting.BOLD),
                Text.literal("Już nie patrzy.").formatted(Formatting.YELLOW));
    }

    @Override
    public String victoryHeader() {
        return "✦ OKO ZAMKNIĘTE ✦";
    }

    @Override
    public Text joinRefused() {
        return Text.literal("Na arenie jest cicho. Obserwator jeszcze nie zszedł z krawędzi.")
                .formatted(Formatting.RED);
    }

    // --- loot -------------------------------------------------------------------

    @Override
    public Item trophy() {
        return TrapContent.witnessEye;
    }

    @Override
    public String trophyName() {
        return "Oko Obserwatora";
    }

    @Override
    public String killAward() {
        return "witness";
    }

    // --- the lights -------------------------------------------------------------

    @Override
    public void onBuilt(ServerWorld world, ArenaBlueprint blueprint) {
        lights(world, blueprint, 1);
        sigil(world, blueprint, false);
    }

    @Override
    public void onFightStart(ServerWorld world, ArenaBlueprint blueprint) {
        sigil(world, blueprint, true);
    }

    @Override
    public void onPhase(ServerWorld world, ArenaBlueprint blueprint, int phase) {
        lights(world, blueprint, phase);
        if (phase == 3) {
            sigil(world, blueprint, false);
        }
    }

    @Override
    public void onEnd(ServerWorld world, ArenaBlueprint blueprint) {
        lights(world, blueprint, 1);
        sigil(world, blueprint, false);
    }

    private void lights(ServerWorld world, ArenaBlueprint blueprint, int phase) {
        for (BlockPos pos : blueprint.tagged("vein")) {
            BlockState state = phase == 1 ? blueprint.original(pos) : phase == 2 ? DIM : DARK;
            world.setBlockState(pos, state, Block.NOTIFY_LISTENERS);
        }
        for (BlockPos pos : blueprint.tagged("glow")) {
            BlockState state = phase == 3 ? DARK_WALL : blueprint.original(pos);
            world.setBlockState(pos, state, Block.NOTIFY_LISTENERS);
        }
        if (phase == 3) {
            Vec3d centre = centre();
            world.playSound(null, centre.x, centre.y, centre.z, SoundEvents.BLOCK_BEACON_DEACTIVATE,
                    SoundCategory.HOSTILE, 2.0F, 0.5F);
        }
    }

    private void sigil(ServerWorld world, ArenaBlueprint blueprint, boolean on) {
        for (BlockPos pos : blueprint.tagged("sigil")) {
            world.setBlockState(pos, on ? TrapArena.LIT : blueprint.original(pos), Block.NOTIFY_LISTENERS);
        }
    }

    // --- Groza --------------------------------------------------------------------

    /**
     * What Groza does, once a second, from outside the effect loop.
     *
     * Two stacks pulse the screen dark, three bleed, and every stack fades
     * on company: the same rule Paranoia taught everyone.
     */
    @Override
    public void debuffTick(ServerWorld world, List<ServerPlayerEntity> here, int now) {
        for (ServerPlayerEntity player : here) {
            StatusEffectInstance dread = player.getStatusEffect(TrapContent.dreadEffect);
            if (dread == null) {
                continue;
            }
            int stacks = dread.getAmplifier();
            if (stacks >= 2 && now % 60 == 0) {
                player.addStatusEffect(new StatusEffectInstance(StatusEffects.DARKNESS, 40, 0, true, false, false));
            }
            if (stacks >= 3 && now % 40 == 0) {
                player.damage(world, world.getDamageSources().magic(), 1.0F);
                player.playSoundToPlayer(SoundEvents.ENTITY_WARDEN_HEARTBEAT, SoundCategory.HOSTILE, 0.8F, 0.9F);
            }
            boolean company = false;
            for (ServerPlayerEntity other : here) {
                if (other != player && other.isAlive()
                        && other.squaredDistanceTo(player) <= ArenaMath.COMPANY_RANGE * ArenaMath.COMPANY_RANGE) {
                    company = true;
                    break;
                }
            }
            if (!company) {
                continue;
            }
            int left = dread.getDuration();
            player.removeStatusEffect(TrapContent.dreadEffect);
            if (stacks > 0) {
                player.addStatusEffect(new StatusEffectInstance(TrapContent.dreadEffect, left, stacks - 1,
                        false, true, true));
            }
        }
    }

    /** Add Groza stacks. Fresh instance rather than a longer one, so the icon's amplifier moves. */
    public static void dread(ServerPlayerEntity player, int add) {
        TrapArena.stack(player, TrapContent.dreadEffect, add, ArenaMath.DREAD_MAX, ArenaMath.DREAD_TICKS);
    }
}
