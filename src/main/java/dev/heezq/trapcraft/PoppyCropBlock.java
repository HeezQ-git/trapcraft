package dev.heezq.trapcraft;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import eu.pb4.polymer.blocks.api.BlockModelType;
import eu.pb4.polymer.blocks.api.PolymerBlockModel;
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
import net.minecraft.state.StateManager;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import xyz.nucleoid.packettweaker.PacketContext;

/**
 * Opium poppy. The slowest thing you can plant and the only one that insists on
 * real daylight.
 *
 * The light gate is the point rather than flavour. Cannabis grows at light 9,
 * which a torch provides, so a cannabis farm can be a windowless cellar with no
 * sky above it and no {@link TrapHeat} to speak of. A poppy field cannot: at
 * {@link #NEEDS_LIGHT} it wants a skylight or a roof off, which means the one
 * crop worth the most money is also the one you cannot hide, and the heat that
 * comes with a visible field is part of the price of the long line.
 *
 * The harvest is pods, not petals. They are worth nothing at all until they
 * have been through three machines -- even more so than coca, where at least
 * the leaves have a paste in them after one step.
 */
public class PoppyCropBlock extends CropBlock implements PolymerTexturedBlock {
    public static final MapCodec<PoppyCropBlock> CODEC = RecordCodecBuilder.mapCodec(
            instance -> instance.group(createSettingsCodec()).apply(instance, PoppyCropBlock::new));

    public static final IntProperty AGE = Properties.AGE_3;
    private static final int MAX_AGE = 3;
    private static final int[] WHEAT_STAGES = {0, 2, 5, 7};

    /** Full daylight, not torchlight. See the class note. */
    public static final int NEEDS_LIGHT = 12;

    /** Pods off one ripe plant. */
    public static final int MIN_PODS = 2;
    public static final int MAX_PODS = 4;

    private final BlockState[] polymerStates = new BlockState[MAX_AGE + 1];

    public PoppyCropBlock(Settings settings) {
        super(settings);
        this.setDefaultState(this.stateManager.getDefaultState().with(AGE, 0));

        for (int age = 0; age <= MAX_AGE; age++) {
            final int stage = age;
            // VINES_BLOCK for the same reason the other two crops use it: it is
            // collisionless and it is the one plant pool this pack has spare
            // states in. Four more takes trapcraft to 33 of about 98.
            this.polymerStates[age] = TrapPolymer.requestOrFallback(
                    BlockModelType.VINES_BLOCK,
                    PolymerBlockModel.of(Identifier.of("trapcraft:block/poppy_crop_age" + age)),
                    () -> Blocks.WHEAT.getDefaultState().with(Properties.AGE_7, WHEAT_STAGES[stage]),
                    "poppy_crop_age" + age);
        }
    }

    /**
     * Its own clock, like coca's -- see the long note on
     * {@link CocaCropBlock#randomTick} for why vanilla's moisture formula is
     * the wrong one for a crop with no grade to chase.
     */
    @Override
    protected void randomTick(BlockState state, ServerWorld world, BlockPos pos, Random random) {
        if (world.getBaseLightLevel(pos, 0) < NEEDS_LIGHT) {
            return;
        }
        int age = getAge(state);
        if (age < getMaxAge() && random.nextInt(TrapMath.POPPY_GROWTH_ROLLS) == 0) {
            world.setBlockState(pos, withAge(age + 1), Block.NOTIFY_LISTENERS);
        }
    }

    @Override
    public MapCodec<? extends CropBlock> getCodec() {
        return CODEC;
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(AGE);
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
        return TrapContent.poppySeeds;
    }

    @Override
    protected boolean canPlantOnTop(BlockState floor, BlockView world, BlockPos pos) {
        return CannabisCropBlock.isFarmland(floor) || floor.isIn(BlockTags.DIRT);
    }

    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player,
                                 BlockHitResult hit) {
        if (!isMature(state)) {
            return ActionResult.PASS;
        }
        if (world instanceof ServerWorld server) {
            for (ItemStack picked : harvest(server, pos, state)) {
                dropStack(world, pos, picked);
            }
            world.playSound(null, pos, SoundEvents.ITEM_CROP_PLANT, SoundCategory.BLOCKS, 1.0F, 0.9F);
        }
        return ActionResult.SUCCESS;
    }

    /** Pick it and leave it standing, the same as the other two crops. */
    public java.util.List<ItemStack> harvest(ServerWorld world, BlockPos pos, BlockState state) {
        java.util.List<ItemStack> picked = new java.util.ArrayList<>();
        if (!isMature(state)) {
            return picked;
        }
        Random random = world.getRandom();
        picked.add(new ItemStack(TrapContent.poppyPod,
                MIN_PODS + random.nextInt(MAX_PODS - MIN_PODS + 1)));
        // Seeds back a third of the time. Stingier than coca's quarter is
        // tempting, but a field that shrinks is a field nobody plants twice --
        // the gate on this line is the wait, not the seed supply.
        if (random.nextInt(3) == 0) {
            picked.add(new ItemStack(TrapContent.poppySeeds, 1));
        }
        world.setBlockState(pos, withAge(0), Block.NOTIFY_LISTENERS);
        return picked;
    }

    @Override
    public BlockState getPolymerBlockState(BlockState state, PacketContext context) {
        return polymerStates[state.get(AGE)];
    }
}
