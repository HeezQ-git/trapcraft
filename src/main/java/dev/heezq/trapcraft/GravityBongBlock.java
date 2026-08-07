package dev.heezq.trapcraft;

import com.mojang.serialization.MapCodec;
import eu.pb4.polymer.blocks.api.BlockModelType;
import eu.pb4.polymer.blocks.api.PolymerBlockModel;
import eu.pb4.polymer.blocks.api.PolymerTexturedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.IntProperty;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.block.ShapeContext;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import xyz.nucleoid.packettweaker.PacketContext;

/**
 * The "tlok" -- gravity bong. Bottle in water, bowl on top, light it, then pull
 * the bottle up so falling water drags the smoke through.
 *
 * Simplified to four right-clicks: fill, load, light, pull. The real thing has
 * more fiddling with a cut bottle and a cap; this keeps the SHAPE of it (water
 * does the work, and you have to pull at the right moment) without pretending
 * to be a build guide.
 *
 * Hardest to set up, hits hardest, and punishes you most -- the top of the
 * three smoking methods.
 */
public class GravityBongBlock extends Block implements PolymerTexturedBlock {
    public static final MapCodec<GravityBongBlock> CODEC = createCodec(GravityBongBlock::new);

    public static final int EMPTY = 0;
    public static final int WATER = 1;
    public static final int LOADED = 2;
    public static final int BURNING = 3;
    public static final int STALE = 4;
    public static final IntProperty STAGE = IntProperty.of("stage", 0, 4);
    public static final net.minecraft.state.property.EnumProperty<Strain> STRAIN =
            net.minecraft.state.property.EnumProperty.of("strain", Strain.class);
    public static final IntProperty GRADE = IntProperty.of("grade", 0, 3);

    public static final float POTENCY = 2.2F;
    public static final int EXTRA_TOLERANCE = 2;
    /** How long the smoke stays good after lighting. Miss it and it goes stale. */
    private static final int BURN_TICKS = 200;

    private final BlockState[] states = new BlockState[STALE + 1];

    public GravityBongBlock(Settings settings) {
        super(settings);
        this.setDefaultState(this.stateManager.getDefaultState().with(STAGE, EMPTY)
                .with(STRAIN, Strain.KUSH).with(GRADE, Quality.MIDS.index()));
        for (int stage = 0; stage <= STALE; stage++) {
            String name = "gravity_bong_" + stage;
            this.states[stage] = TrapPolymer.requestOrFallback(
                    BlockModelType.TRANSPARENT_BLOCK,
                    PolymerBlockModel.of(Identifier.of("trapcraft:block/" + name)),
                    () -> Blocks.GLASS.getDefaultState(), name);
        }
    }

    /**
     * Bucket plus bottle, not a full cube.
     *
     * Deliberately one shape for all five stages rather than tracking the
     * bottle as it rises: a hitbox that moves under the cursor makes the block
     * feel like it's dodging you, and the whole interaction is right-clicking
     * the same spot four times.
     */
    private static final VoxelShape SHAPE = VoxelShapes.union(
            createCuboidShape(2, 0, 2, 14, 4, 14),
            createCuboidShape(4, 4, 4, 12, 16, 12));

    @Override
    protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos,
                                         ShapeContext context) {
        return SHAPE;
    }

    @Override
    protected MapCodec<? extends Block> getCodec() {
        return CODEC;
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(STAGE, STRAIN, GRADE);
    }

    @Override
    protected ActionResult onUseWithItem(ItemStack stack, BlockState state, World world, BlockPos pos,
                                         PlayerEntity player, Hand hand, BlockHitResult hit) {
        int stage = state.get(STAGE);

        if (stage == EMPTY && stack.isOf(Items.WATER_BUCKET)) {
            if (!world.isClient) {
                if (!player.isCreative()) {
                    player.setStackInHand(hand, new ItemStack(Items.BUCKET));
                }
                advance(world, pos, state, WATER, SoundEvents.ITEM_BUCKET_EMPTY, 1.0F);
            }
            return ActionResult.SUCCESS;
        }

        if (stage == WATER) {
            Strain strain = TrapContent.strainOfDriedBud(stack.getItem());
            if (strain == null) {
                return ActionResult.PASS;
            }
            if (!world.isClient) {
                stack.decrementUnlessCreative(1, player);
                world.setBlockState(pos, state.with(STAGE, LOADED)
                        .with(STRAIN, strain)
                        .with(GRADE, TrapComponents.get(stack).index()));
                world.playSound(null, pos, SoundEvents.BLOCK_GRASS_PLACE,
                        SoundCategory.BLOCKS, 0.9F, 1.3F);
            }
            return ActionResult.SUCCESS;
        }

        if (stage == LOADED && stack.isOf(Items.FLINT_AND_STEEL)) {
            if (!world.isClient) {
                stack.damage(1, player, net.minecraft.entity.EquipmentSlot.MAINHAND);
                advance(world, pos, state, BURNING, SoundEvents.ITEM_FLINTANDSTEEL_USE, 1.0F);
                // Stale after a while: pulling at the right moment is the point.
                world.scheduleBlockTick(pos, this, BURN_TICKS);
            }
            return ActionResult.SUCCESS;
        }

        return pull(state, world, pos, player);
    }

    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player,
                                 BlockHitResult hit) {
        return pull(state, world, pos, player);
    }

    /** The pull: lift it and take the hit. */
    private ActionResult pull(BlockState state, World world, BlockPos pos, PlayerEntity player) {
        int stage = state.get(STAGE);
        if (stage != BURNING && stage != STALE) {
            if (!world.isClient && stage == EMPTY) {
                player.sendMessage(Text.literal("Water, bud, flint and steel, then pull")
                        .formatted(Formatting.GRAY), true);
            }
            return stage == EMPTY ? ActionResult.SUCCESS : ActionResult.PASS;
        }
        if (world instanceof ServerWorld server) {
            // Fires before the hit so the lift reads as causing it, not
            // following it -- the effects land mid-animation, around the pull.
            TrapNet.play(player, TrapNet.TLOK_PULL);
            // Stale still works, just weakly -- a wasted setup, not a lost bud.
            float potency = stage == BURNING ? POTENCY : 0.7F;
            TrapContent.hit(server, player, state.get(STRAIN),
                    Quality.byIndex(state.get(GRADE)), potency, EXTRA_TOLERANCE);
            world.setBlockState(pos, state.with(STAGE, EMPTY));
            // Was 25 CLOUD, which is gone inside half a second -- the back
            // half of the pull animation played over an empty screen.
            server.spawnParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE,
                    pos.getX() + 0.5, pos.getY() + 0.9, pos.getZ() + 0.5, 22, 0.28, 0.2, 0.28, 0.015);
            TrapSmoke.exhale(server, player, 1.5F);
            world.playSound(null, pos, SoundEvents.ENTITY_GENERIC_SPLASH, SoundCategory.BLOCKS, 0.8F, 0.6F);
        }
        return ActionResult.SUCCESS;
    }

    private void advance(World world, BlockPos pos, BlockState state, int stage,
                         net.minecraft.sound.SoundEvent sound, float pitch) {
        world.setBlockState(pos, state.with(STAGE, stage));
        world.playSound(null, pos, sound, SoundCategory.BLOCKS, 0.9F, pitch);
    }

    @Override
    protected void scheduledTick(BlockState state, ServerWorld world, BlockPos pos, Random random) {
        if (state.get(STAGE) == BURNING) {
            world.setBlockState(pos, state.with(STAGE, STALE));
            world.spawnParticles(ParticleTypes.SMOKE,
                    pos.getX() + 0.5, pos.getY() + 0.9, pos.getZ() + 0.5, 6, 0.2, 0.1, 0.2, 0.01);
        }
    }

    /** Break as a bottle in a bucket, so the sound and particles match the model. */
    @Override
    public BlockState getPolymerBreakEventBlockState(BlockState state, PacketContext context) {
        return Blocks.GLASS.getDefaultState();
    }

    @Override
    public BlockState getPolymerBlockState(BlockState state, PacketContext context) {
        return states[state.get(STAGE)];
    }
}
