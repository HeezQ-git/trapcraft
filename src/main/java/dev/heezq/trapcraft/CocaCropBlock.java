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
     * Grows on its own clock, not vanilla's.
     *
     * This used to gate {@code super.randomTick}, which is vanilla crop growth
     * -- and vanilla scales growth by moisture read off {@code Blocks.FARMLAND}
     * and nothing else. A bush on plain dirt, which {@link #canPlantOnTop}
     * explicitly allows, scored the floor value of 1.0 and needed 26 rolls a
     * stage; gated by four on top of that, it was around two hours per stage
     * and six to twelve from seed to ripe. Nobody ever saw the last stage, and
     * they were right not to wait for it.
     *
     * Cannabis survives the same formula because Quality pays three points for
     * moisture 7, so it gets planted on wet farmland by people chasing grades.
     * Coca has no grade -- the whole line's value is in the press and the
     * refiner -- so the substrate was a trap with no tell. Flat rate, light
     * still required, and {@link TrapMath#COCA_GROWTH_ROLLS} is the one number
     * that governs it.
     */
    @Override
    protected void randomTick(BlockState state, ServerWorld world, BlockPos pos, Random random) {
        // The one vanilla rule kept: a plant still needs light, so a cellar
        // farm still doesn't work and still says so by simply not growing.
        if (world.getBaseLightLevel(pos, 0) < 9) {
            return;
        }
        int age = getAge(state);
        if (age < getMaxAge() && random.nextInt(TrapMath.COCA_GROWTH_ROLLS) == 0) {
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
            for (ItemStack picked : harvest(server, pos, state)) {
                dropStack(world, pos, picked);
            }
            world.playSound(null, pos, SoundEvents.ITEM_CROP_PLANT, SoundCategory.BLOCKS, 1.0F, 1.1F);
        }
        return ActionResult.SUCCESS;
    }

    /** Pick it and leave it standing. See the note on the cannabis version. */
    public java.util.List<ItemStack> harvest(ServerWorld world, BlockPos pos, BlockState state) {
        java.util.List<ItemStack> picked = new java.util.ArrayList<>();
        if (!isMature(state)) {
            return picked;
        }
        Random random = world.getRandom();
        // Generous leaf yield -- it takes 3 to make one paste, so this
        // should feel like the easy part of a long chain.
        picked.add(new ItemStack(TrapContent.cocaLeaves, 2 + random.nextInt(3)));
        if (random.nextInt(4) == 0) {
            picked.add(new ItemStack(TrapContent.cocaSeeds, 1));
        }
        world.setBlockState(pos, withAge(0), Block.NOTIFY_LISTENERS);
        return picked;
    }

    @Override
    public BlockState getPolymerBlockState(BlockState state, PacketContext context) {
        return polymerStates[state.get(AGE)];
    }
}
