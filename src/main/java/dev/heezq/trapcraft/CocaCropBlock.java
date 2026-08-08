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
 * Coca bush. Slower than cannabis and yields leaves, which are worth nothing
 * until they've been through the press and the refiner -- the value in this
 * line is entirely in the processing, not the farming.
 */
public class CocaCropBlock extends CropBlock implements PolymerTexturedBlock {
    public static final MapCodec<CocaCropBlock> CODEC = RecordCodecBuilder.mapCodec(
            instance -> instance.group(createSettingsCodec()).apply(instance, CocaCropBlock::new));

    public static final IntProperty AGE = Properties.AGE_3;
    private static final int MAX_AGE = 3;
    private static final int[] WHEAT_STAGES = {0, 2, 5, 7};

    private final BlockState[] polymerStates = new BlockState[MAX_AGE + 1];

    public CocaCropBlock(Settings settings) {
        super(settings);
        this.setDefaultState(this.stateManager.getDefaultState().with(AGE, 0));

        for (int age = 0; age <= MAX_AGE; age++) {
            final int stage = age;
            this.polymerStates[age] = TrapPolymer.requestOrFallback(
                    BlockModelType.VINES_BLOCK,
                    PolymerBlockModel.of(Identifier.of("trapcraft:block/coca_crop_age" + age)),
                    () -> Blocks.WHEAT.getDefaultState().with(Properties.AGE_7, WHEAT_STAGES[stage]),
                    "coca_crop_age" + age);
        }
    }

    /**
     * Same four-stage problem as cannabis: half the stages of wheat means a
     * plain crop tick moves it twice as far. Gated to match, so the two
     * product lines take comparable time to bring in.
     */
    private static final int GROWTH_PATIENCE = 4;

    @Override
    protected void randomTick(BlockState state, ServerWorld world, BlockPos pos, Random random) {
        if (random.nextInt(GROWTH_PATIENCE) == 0) {
            super.randomTick(state, world, pos, random);
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
        return TrapContent.cocaSeeds;
    }

    /** Same breadth as cannabis -- this pack's farmland is often a mod's. */
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
            Random random = server.getRandom();
            // Generous leaf yield -- it takes 3 to make one paste, so this
            // should feel like the easy part of a long chain.
            dropStack(world, pos, new ItemStack(TrapContent.cocaLeaves, 2 + random.nextInt(3)));
            if (random.nextInt(4) == 0) {
                dropStack(world, pos, new ItemStack(TrapContent.cocaSeeds, 1));
            }
            world.setBlockState(pos, withAge(0), Block.NOTIFY_LISTENERS);
            world.playSound(null, pos, SoundEvents.ITEM_CROP_PLANT, SoundCategory.BLOCKS, 1.0F, 1.1F);
        }
        return ActionResult.SUCCESS;
    }

    @Override
    public BlockState getPolymerBlockState(BlockState state, PacketContext context) {
        return polymerStates[state.get(AGE)];
    }
}
