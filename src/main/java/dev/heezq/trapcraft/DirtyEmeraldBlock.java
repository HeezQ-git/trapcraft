package dev.heezq.trapcraft;

import eu.pb4.polymer.blocks.api.BlockModelType;
import eu.pb4.polymer.blocks.api.PolymerBlockModel;
import eu.pb4.polymer.blocks.api.PolymerTexturedBlock;
import eu.pb4.polymer.core.api.block.PolymerBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.Identifier;
import xyz.nucleoid.packettweaker.PacketContext;

/**
 * Nine dirty emeralds, stacked.
 *
 * Dirty money is an ITEM rather than a balance, and the black market pays in
 * lumps -- a good week off the street is a four-figure number of them, which
 * at sixty-four to a stack was sixteen stacks of inventory to carry to the
 * drum. That is not a difficulty worth having: the awkwardness of dirty money
 * is meant to be that it has to be WASHED, not that it has to be ferried.
 *
 * So it packs the way emeralds do, nine to a block and back again. Everything
 * else about it stays exactly as awkward as it was -- no shop takes it, the
 * market has never counted it, and it is not money until it comes out of a
 * drum.
 */
public class DirtyEmeraldBlock extends Block implements PolymerBlock, PolymerTexturedBlock {
    private final BlockState carrier;

    public DirtyEmeraldBlock(Settings settings) {
        super(settings);
        this.carrier = TrapPolymer.requestOrFallback(
                BlockModelType.FULL_BLOCK,
                PolymerBlockModel.of(Identifier.of("trapcraft:block/dirty_emerald_block")),
                // Emerald block rather than stone: if the pool ever runs dry
                // this still reads as a pile of money, which is the one thing
                // about it that must survive a fallback.
                () -> Blocks.EMERALD_BLOCK.getDefaultState(), "dirty_emerald_block");
    }

    @Override
    public BlockState getPolymerBlockState(BlockState state, PacketContext context) {
        return carrier;
    }

    @Override
    public BlockState getPolymerBreakEventBlockState(BlockState state, PacketContext context) {
        return Blocks.EMERALD_BLOCK.getDefaultState();
    }
}
