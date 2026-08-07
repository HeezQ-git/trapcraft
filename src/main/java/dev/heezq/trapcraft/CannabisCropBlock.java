package dev.heezq.trapcraft;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import eu.pb4.polymer.blocks.api.BlockModelType;
import eu.pb4.polymer.blocks.api.PolymerBlockModel;
import eu.pb4.polymer.blocks.api.PolymerBlockResourceUtils;
import eu.pb4.polymer.blocks.api.PolymerTexturedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemConvertible;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Properties;
import xyz.nucleoid.packettweaker.PacketContext;

/**
 * One instance per {@link Strain}. Four growth stages rather than wheat's
 * eight -- fewer blockstates out of Polymer's pool and the stages read more
 * distinctly at 16x16.
 */
public class CannabisCropBlock extends CropBlock implements PolymerTexturedBlock {
    public static final MapCodec<CannabisCropBlock> CODEC = RecordCodecBuilder.mapCodec(
            instance -> instance.group(
                    Strain.CODEC.fieldOf("strain").forGetter(block -> block.strain),
                    createSettingsCodec()
            ).apply(instance, CannabisCropBlock::new));

    /** Set when bone meal is used; costs a quality point at harvest. */
    public static final net.minecraft.state.property.BooleanProperty RUSHED =
            net.minecraft.state.property.BooleanProperty.of("rushed");

    public static final IntProperty AGE = Properties.AGE_3;
    private static final int MAX_AGE = 3;
    private static final int[] WHEAT_STAGES = {0, 2, 5, 7};
    private static final BlockModelType CARRIER = BlockModelType.VINES_BLOCK;

    private final Strain strain;
    // Index = age. Resolved once at construction; requestBlock permanently
    // claims an unused vanilla state, so calling it per-tick would exhaust the pool.
    private final BlockState[] polymerStates = new BlockState[MAX_AGE + 1];

    public CannabisCropBlock(Strain strain, Settings settings) {
        super(settings);
        this.strain = strain;
        this.setDefaultState(this.stateManager.getDefaultState().with(AGE, 0).with(RUSHED, false));

        for (int age = 0; age <= MAX_AGE; age++) {
            final int stage = age;
            // Immature stages share one neutral model across strains on purpose:
            // you shouldn't be able to identify a seedling by looking at it.
            String model = age < MAX_AGE
                    ? "trapcraft:block/cannabis_crop_age" + age
                    : "trapcraft:block/cannabis_crop_age3_" + strain.id();
            this.polymerStates[age] = TrapPolymer.requestOrFallback(
                    // NOT PLANT_BLOCK: this pack's other two Polymer mods leave
                    // it with 3 free states and we need 12. VINES_BLOCK has ~98,
                    // and is also collisionless, so it behaves like a crop.
                    // Check the "Polymer pool" lines at boot before changing this.
                    CARRIER,
                    PolymerBlockModel.of(Identifier.of(model)),
                    // Wheat's 8 stages spread over our 4 so growth still reads.
                    () -> Blocks.WHEAT.getDefaultState().with(Properties.AGE_7, WHEAT_STAGES[stage]),
                    "cannabis_crop_" + strain.id() + "_age" + age);
        }
    }

    public Strain strain() {
        return strain;
    }

    @Override
    public MapCodec<? extends CropBlock> getCodec() {
        return CODEC;
    }

    @Override
    protected void appendProperties(StateManager.Builder<net.minecraft.block.Block, BlockState> builder) {
        builder.add(AGE, RUSHED);
    }

    @Override
    protected IntProperty getAgeProperty() {
        return AGE;
    }

    @Override
    public int getMaxAge() {
        return MAX_AGE;
    }

    @Override
    protected ItemConvertible getSeedsItem() {
        return TrapContent.seeds(strain);
    }

    /**
     * Also allow plain dirt and grass, not just farmland.
     *
     * This is the fix for plants popping themselves off and scattering drops.
     * Unhydrated farmland reverts to dirt on a random tick, and vanilla's rule
     * is that anything standing on it is then invalid -- so the plant breaks
     * itself, exactly like wheat does. Nobody expects that of a plant they
     * watched grow, so cannabis simply tolerates dirt.
     */
    @Override
    protected boolean canPlantOnTop(BlockState floor, BlockView world, BlockPos pos) {
        return isFarmland(floor) || floor.isIn(BlockTags.DIRT);
    }

    /**
     * Farmland in the broad sense, not just {@code minecraft:farmland}.
     *
     * This pack tills into {@code borukva-food:better_farmland}, and checking
     * for the vanilla block by identity rejected it -- seeds simply refused to
     * plant on any modded soil while working fine on grass. Testing the CLASS
     * catches every mod farmland that extends the vanilla one, and the moisture
     * property catches the ones that don't but still behave like farmland.
     */
    static boolean isFarmland(BlockState floor) {
        return floor.getBlock() instanceof net.minecraft.block.FarmlandBlock
                || floor.contains(Properties.MOISTURE);
    }

    /**
     * Right-click a mature plant to pick it: you get the buds and the plant
     * stays, reset to a seedling. Breaking it still works and still returns
     * the seed, but harvesting shouldn't require destroying the thing.
     */
    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player,
                                 BlockHitResult hit) {
        if (!isMature(state)) {
            return ActionResult.PASS;
        }
        if (world instanceof ServerWorld server) {
            Random random = server.getRandom();
            Quality grade = gradeAt(server, pos, state);
            // Better grow = more buds AND better ones, so effort compounds.
            int count = 1 + random.nextInt(2) + (grade.index() >= Quality.LOUD.index() ? 1 : 0);
            dropStack(world, pos, TrapComponents.apply(
                    new ItemStack(TrapContent.rawBud(strain), count), grade));
            if (random.nextInt(3) == 0) {
                dropStack(world, pos, new ItemStack(TrapContent.seeds(strain), 1));
            }
            // Back to age 0 rather than removed -- replanting every harvest is
            // the tedium this is meant to remove.
            world.setBlockState(pos, withAge(0), Block.NOTIFY_LISTENERS);
            world.playSound(null, pos, SoundEvents.ITEM_CROP_PLANT, SoundCategory.BLOCKS, 1.0F, 0.9F);
        }
        return ActionResult.SUCCESS;
    }

    /**
     * Cross-pollination. A mature plant sitting next to a mature plant of a
     * different pure strain occasionally throws a hybrid seed.
     *
     * Runs off the existing random tick rather than a scan: no scheduler, no
     * per-chunk bookkeeping, and it naturally only fires where plants actually
     * are. Only checks the four cardinal neighbours, so a deliberate
     * checkerboard of two strains breeds and a scattered field mostly doesn't.
     */
    @Override
    protected void randomTick(BlockState state, ServerWorld world, BlockPos pos, Random random) {
        super.randomTick(state, world, pos, random);

        if (!isMature(state)) {
            return;
        }
        TrapHeat.onMatureTick(world, pos, random);
        shimmer(world, pos, random);

        if (strain.isHybrid() || random.nextInt(24) != 0) {
            return;
        }
        for (net.minecraft.util.math.Direction dir : net.minecraft.util.math.Direction.Type.HORIZONTAL) {
            BlockState neighbour = world.getBlockState(pos.offset(dir));
            if (!(neighbour.getBlock() instanceof CannabisCropBlock other)) {
                continue;
            }
            if (!other.isMature(neighbour)) {
                continue;
            }
            Strain hybrid = Strain.hybridOf(strain, other.strain);
            if (hybrid != null) {
                dropStack(world, pos, new ItemStack(TrapContent.seeds(hybrid), 1));
                world.playSound(null, pos, SoundEvents.BLOCK_SWEET_BERRY_BUSH_PICK_BERRIES,
                        SoundCategory.BLOCKS, 0.7F, 1.6F);
                return; // one seed per tick, however many neighbours qualify
            }
        }
    }

    /** Reads the conditions the plant actually grew in, at the moment you pick it. */
    private Quality gradeAt(ServerWorld world, BlockPos pos, BlockState state) {
        BlockState floor = world.getBlockState(pos.down());
        // Same breadth as placement: modded farmland should score for hydration
        // too, or growing on it would silently cost you 3 quality points.
        boolean hydrated = floor.contains(Properties.MOISTURE)
                && floor.get(Properties.MOISTURE) >= 7;
        int light = world.getBaseLightLevel(pos, 0);
        return Quality.fromConditions(hydrated, light, world.isSkyVisible(pos), state.get(RUSHED));
    }

    /**
     * Bone meal marks the plant as rushed, costing it a quality point.
     *
     * The flag lives on the blockstate rather than a BlockEntity -- it's one
     * bit, and it needs to survive until harvest. It doesn't affect the model,
     * so it costs no extra Polymer states.
     */
    @Override
    public void grow(ServerWorld world, Random random, BlockPos pos, BlockState state) {
        super.grow(world, random, pos, state);
        BlockState grown = world.getBlockState(pos);
        if (grown.getBlock() == this && !grown.get(RUSHED)) {
            world.setBlockState(pos, grown.with(RUSHED, true), Block.NOTIFY_LISTENERS);
        }
    }

    @Override
    public BlockState getPolymerBlockState(BlockState state, PacketContext context) {
        return polymerStates[state.get(AGE)];
    }

    /**
     * A ready plant catches the light now and then.
     *
     * Spawned server-side rather than in randomDisplayTick, which never
     * runs for these: Polymer sends the CARRIER blockstate to clients, so
     * as far as any client is concerned there is no crop here to tick.
     *
     * Random ticks are sparse -- roughly once a minute per block -- which
     * is exactly right. One plant twinkles occasionally; a field you've
     * let mature glitters constantly, and that's the signal.
     */
    private static void shimmer(ServerWorld world, BlockPos pos, Random random) {
        if (random.nextInt(3) != 0) {
            return;
        }
        world.spawnParticles(net.minecraft.particle.ParticleTypes.END_ROD,
                pos.getX() + 0.5, pos.getY() + 0.55, pos.getZ() + 0.5,
                1, 0.22, 0.22, 0.22, 0.0);
    }
}
