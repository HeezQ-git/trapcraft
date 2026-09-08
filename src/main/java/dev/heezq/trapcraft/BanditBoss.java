package dev.heezq.trapcraft;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.boss.BossBar;
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
 * Bandyta. The one-armed bandit.
 *
 * A slot machine that stopped paying out and started walking, on the roof
 * of a casino under neon. Everything it does is a bet placed on your
 * behalf: the reels decide what happens next, the roulette in the floor
 * decides who gets hurt, and its currency, Stawka, makes you hit harder
 * and get hit harder in the same breath. Loud where the witness is quiet.
 */
public final class BanditBoss extends ArenaBoss {
    public static final BanditBoss INSTANCE = new BanditBoss();

    private static final BlockPos ORIGIN = new BlockPos(2000, 64, 0);
    /** The roulette ring's radii and colours, as gen_arena.py laid them. */
    static final double RING_INNER = 6.5;
    static final double RING_OUTER = 15.5;
    static final int SECTORS = 12;
    static final BlockState[] RING = {
            Blocks.RED_CONCRETE.getDefaultState(),
            Blocks.BLACK_CONCRETE.getDefaultState(),
            Blocks.LIME_CONCRETE.getDefaultState()};
    static final String[] COLOUR_NAMES = {"CZERWONE", "CZARNE", "ZIELONE"};
    static final Formatting[] COLOUR_STYLES = {Formatting.RED, Formatting.GRAY, Formatting.GREEN};
    private static final BlockState[] NEON = {
            null,
            Blocks.VERDANT_FROGLIGHT.getDefaultState(),
            Blocks.OCHRE_FROGLIGHT.getDefaultState()};
    private static final BlockState FLASH = Blocks.SHROOMLIGHT.getDefaultState();

    private ArenaBlueprint plan;
    private int phaseNow = 1;
    private int flashUntil = -1;

    private BanditBoss() {
    }

    @Override
    public String id() {
        return "bandit";
    }

    @Override
    public String displayName() {
        return "BANDYTA";
    }

    @Override
    public Formatting colour() {
        return Formatting.GOLD;
    }

    @Override
    public BossBar.Color barColour(int phase) {
        return phase == 3 ? BossBar.Color.RED : phase == 2 ? BossBar.Color.PINK : BossBar.Color.YELLOW;
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
        double angle = Math.toRadians(110 + random.nextInt(320));
        Vec3d centre = centre();
        return new Vec3d(centre.x + Math.cos(angle) * 28.5, ORIGIN.getY() + 6, centre.z + Math.sin(angle) * 28.5);
    }

    @Override
    public ArenaBossEntity spawn(ServerWorld world, int players) {
        BanditEntity boss = new BanditEntity(BanditEntity.TYPE, world);
        Vec3d centre = centre();
        boss.refreshPositionAndAngles(centre.x, centre.y, centre.z, 0.0F, 0.0F);
        boss.sizeFor(players);
        world.spawnEntity(boss);
        return boss;
    }

    @Override
    public List<String> abilities() {
        return BanditEntity.ABILITY_IDS;
    }

    // --- words ---------------------------------------------------------------

    @Override
    public Text omen(String clock) {
        return Text.empty()
                .append(Text.literal("Bandyta wyjechał na dach kasyna.\n").formatted(Formatting.GOLD))
                .append(Text.literal("Automat, który przestał wypłacać i zaczął chodzić. "
                        + "Kasyno zawsze wygrywa. Do dziś.\n").formatted(Formatting.GRAY))
                .append(Text.literal("Arena otwiera się za " + clock + ". ").formatted(Formatting.WHITE))
                .append(Text.literal("Nikt tam nie ginie: nokaut, trybuny, powrót.\n")
                        .formatted(Formatting.DARK_GRAY, Formatting.ITALIC));
    }

    @Override
    public Text nobodyCame() {
        return Text.literal("Nikt nie przyszedł. ").formatted(Formatting.GRAY, Formatting.ITALIC)
                .append(Text.literal("Bandyta wrócił do kasyna. Z waszymi pieniędzmi.").formatted(Formatting.DARK_GRAY));
    }

    @Override
    public Line fightStart() {
        return new Line(Text.literal("BANDYTA").formatted(Formatting.GOLD, Formatting.BOLD),
                Text.literal("Kasyno zawsze wygrywa.").formatted(Formatting.YELLOW));
    }

    @Override
    public Text fightBroadcast() {
        return Text.literal("Bandyta jest na dachu. ").formatted(Formatting.GOLD, Formatting.BOLD)
                .append(Text.literal("Dziesięć minut do zamknięcia kasyna. ").formatted(Formatting.GRAY));
    }

    @Override
    public Line phase(int phase) {
        return new Line(Text.literal(phase == 2 ? "WYSOKIE STAWKI" : "WYPŁATA").formatted(Formatting.GOLD, Formatting.BOLD),
                Text.literal(phase == 2 ? "Ruletka się kręci." : "Automat się otwiera.").formatted(Formatting.GRAY));
    }

    @Override
    public Text phaseBroadcast(int phase) {
        return Text.literal("Bandyta: ").formatted(Formatting.GOLD)
                .append(Text.literal(phase == 2 ? "faza II. Wysokie stawki." : "faza III. Wypłata.")
                        .formatted(Formatting.GRAY));
    }

    @Override
    public Line enrage() {
        return new Line(Text.literal("KASYNO WYGRAŁO").formatted(Formatting.RED, Formatting.BOLD),
                Text.literal("Zamknięte.").formatted(Formatting.GRAY));
    }

    @Override
    public Text enrageBroadcast() {
        return Text.literal("Kasyno wygrało. ").formatted(Formatting.RED, Formatting.BOLD)
                .append(Text.literal("Bandyta zjechał z dachu z całą pulą.").formatted(Formatting.GRAY));
    }

    @Override
    public Line victory() {
        return new Line(Text.literal("JACKPOT").formatted(Formatting.GOLD, Formatting.BOLD),
                Text.literal("Kasyno przegrało.").formatted(Formatting.YELLOW));
    }

    @Override
    public String victoryHeader() {
        return "✦ JACKPOT ✦";
    }

    @Override
    public Text joinRefused() {
        return Text.literal("Na dachu jest cicho. Bandyta jeszcze nie wyjechał.").formatted(Formatting.RED);
    }

    // --- loot -------------------------------------------------------------------

    @Override
    public Item trophy() {
        return TrapContent.goldenLever;
    }

    @Override
    public String trophyName() {
        return "Złota Dźwignia";
    }

    @Override
    public String killAward() {
        return "bandit";
    }

    @Override
    public int[] showColours() {
        return new int[]{0xffc24a, 0xff3355};
    }

    // --- the roof ---------------------------------------------------------------

    @Override
    public void onBuilt(ServerWorld world, ArenaBlueprint blueprint) {
        plan = blueprint;
        phaseNow = 1;
        flashUntil = -1;
        neon(world, 1);
        sigil(world, false);
    }

    @Override
    public void onFightStart(ServerWorld world, ArenaBlueprint blueprint) {
        plan = blueprint;
        sigil(world, true);
    }

    @Override
    public void onPhase(ServerWorld world, ArenaBlueprint blueprint, int phase) {
        phaseNow = phase;
        neon(world, phase);
        Vec3d centre = centre();
        world.playSound(null, centre.x, centre.y, centre.z, SoundEvents.BLOCK_BEACON_POWER_SELECT,
                SoundCategory.HOSTILE, 2.0F, phase == 3 ? 0.6F : 1.2F);
    }

    @Override
    public void onEnd(ServerWorld world, ArenaBlueprint blueprint) {
        plan = blueprint;
        spinRing(world, 0);
        neon(world, 1);
        sigil(world, false);
        phaseNow = 1;
        flashUntil = -1;
    }

    @Override
    public void onLeave(ServerPlayerEntity player) {
        player.removeStatusEffect(TrapContent.stakeEffect);
        player.setGlowing(false);
    }

    @Override
    public void onKnockout(ServerPlayerEntity player) {
        player.removeStatusEffect(TrapContent.stakeEffect);
    }

    @Override
    public void debuffTick(ServerWorld world, List<ServerPlayerEntity> here, int now) {
        if (flashUntil >= 0 && now >= flashUntil) {
            flashUntil = -1;
            neon(world, phaseNow);
        }
    }

    /** The tubes: their own colour, green for high stakes, amber for the payout. */
    private void neon(ServerWorld world, int phase) {
        if (plan == null) {
            return;
        }
        for (BlockPos pos : plan.tagged("neon")) {
            BlockState state = NEON[phase - 1] == null ? plan.original(pos) : NEON[phase - 1];
            world.setBlockState(pos, state, Block.NOTIFY_LISTENERS);
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

    /** Every tube white for two seconds: the jackpot. */
    public void flash(ServerWorld world, int now) {
        if (plan == null) {
            return;
        }
        for (BlockPos pos : plan.tagged("neon")) {
            world.setBlockState(pos, FLASH, Block.NOTIFY_LISTENERS);
        }
        flashUntil = now + 40;
    }

    /** The roulette ring turned by {@code shift} colours; 0 puts it back. */
    public void spinRing(ServerWorld world, int shift) {
        if (plan == null) {
            return;
        }
        for (BlockPos pos : plan.tagged("ring")) {
            int sector = sector(pos.getX() - ORIGIN.getX(), pos.getZ() - ORIGIN.getZ());
            BlockState state = RING[(sector + shift) % 3];
            if (world.getBlockState(pos) != state) {
                world.setBlockState(pos, state, Block.NOTIFY_LISTENERS);
            }
        }
    }

    /** The same arithmetic as gen_arena.py's ring_sector, so the colours agree. */
    static int sector(int x, int z) {
        double angle = Math.toDegrees(Math.atan2(z, x));
        if (angle < 0) {
            angle += 360.0;
        }
        return (int) (angle / (360.0 / SECTORS)) % SECTORS;
    }

    /** 0 red, 1 black, 2 green for somebody standing on the ring; -1 off it. */
    public int colourUnder(ServerWorld world, ServerPlayerEntity player) {
        Vec3d centre = centre();
        double dx = player.getX() - centre.x;
        double dz = player.getZ() - centre.z;
        double r = Math.sqrt(dx * dx + dz * dz);
        if (r < RING_INNER || r > RING_OUTER + 0.5 || player.getY() > ORIGIN.getY() + 2.5) {
            return -1;
        }
        BlockState under = world.getBlockState(BlockPos.ofFloored(player.getX(), player.getY() - 0.1, player.getZ()));
        for (int i = 0; i < RING.length; i++) {
            if (under == RING[i]) {
                return i;
            }
        }
        return -1;
    }
}
