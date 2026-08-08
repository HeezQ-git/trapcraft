package dev.heezq.trapcraft;

import com.mojang.serialization.MapCodec;
import eu.pb4.polymer.blocks.api.BlockModelType;
import eu.pb4.polymer.blocks.api.PolymerBlockModel;
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
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import xyz.nucleoid.packettweaker.PacketContext;

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
public class MixerBlock extends Block implements PolymerTexturedBlock {
    public static final MapCodec<MixerBlock> CODEC = createCodec(MixerBlock::new);

    private final BlockState visual;

    public MixerBlock(Settings settings) {
        super(settings);
        this.visual = TrapPolymer.requestOrFallback(
                // TRANSPARENT_BLOCK, not FULL_BLOCK. The carrier is what the
                // client believes about this block, and believing a table with
                // legs is a solid cube makes it cull the faces of whatever is
                // underneath -- so you stand on a floor above a cave and see
                // straight through into it. Any model that doesn't fill the
                // cube has to say so.
                BlockModelType.TRANSPARENT_BLOCK,
                PolymerBlockModel.of(Identifier.of("trapcraft:block/mixing_station")),
                () -> Blocks.CRAFTING_TABLE.getDefaultState(), "mixing_station");
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
        return visual;
    }
}
