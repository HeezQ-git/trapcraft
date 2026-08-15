package dev.heezq.trapcraft;

import com.mojang.serialization.MapCodec;
import eu.pb4.polymer.blocks.api.BlockModelType;
import eu.pb4.polymer.blocks.api.PolymerTexturedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import xyz.nucleoid.packettweaker.PacketContext;

import java.util.Map;

/**
 * The mixing station: where two to four buds become something that isn't any
 * of them.
 *
 * A full cube on purpose: Polymer serves it on a FULL_BLOCK carrier, and a
 * model shorter than its carrier leaks light round the edges.
 *
 * Stateless on purpose. The menu holds the ingredients while it's open and
 * hands them back on close, so the block itself needs no BlockEntity and no
 * saved inventory -- consistent with every other machine in the mod, all of
 * which keep their state in blockstate properties or not at all.
 */
public class MixerBlock extends TurnableBlock implements PolymerTexturedBlock {
    public static final MapCodec<MixerBlock> CODEC = createCodec(MixerBlock::new);

    private final Map<Direction, BlockState> carriers;

    public MixerBlock(Settings settings) {
        super(settings);
        this.carriers = carriers(
                // FULL_BLOCK (note block states), not the leaf pool. The old
                // comment here talked about a table with legs; the model has
                // been a plain full cube the whole time, so the see-through
                // carrier bought nothing except a shader pack waving the
                // station like a shrub. Solid cube model, solid carrier.
                BlockModelType.FULL_BLOCK, "mixing_station",
                () -> Blocks.CRAFTING_TABLE.getDefaultState());
    }

    @Override
    protected MapCodec<? extends Block> getCodec() {
        return CODEC;
    }

    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player,
                                 BlockHitResult hit) {
        if (world.isClient) {
            return ActionResult.SUCCESS;
        }
        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, inventory, p) -> new MixerScreenHandler(syncId, inventory),
                Text.literal("Mixing Station").formatted(Formatting.DARK_GREEN)));
        world.playSound(null, pos, SoundEvents.BLOCK_BARREL_OPEN, SoundCategory.BLOCKS, 0.7F, 1.4F);
        return ActionResult.SUCCESS;
    }

    /** Break as a bench: timber, with glass and steel fittings, so the sound and particles match the model. */
    @Override
    public BlockState getPolymerBreakEventBlockState(BlockState state, PacketContext context) {
        return Blocks.OAK_PLANKS.getDefaultState();
    }

    @Override
    public BlockState getPolymerBlockState(BlockState state, PacketContext context) {
        return carriers.get(state.get(FACING));
    }
}
