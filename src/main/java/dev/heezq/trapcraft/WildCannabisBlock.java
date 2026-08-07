package dev.heezq.trapcraft;

import com.mojang.serialization.MapCodec;
import eu.pb4.polymer.blocks.api.BlockModelType;
import eu.pb4.polymer.blocks.api.PolymerBlockModel;
import eu.pb4.polymer.blocks.api.PolymerTexturedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.PlantBlock;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Identifier;
import xyz.nucleoid.packettweaker.PacketContext;

/**
 * Cannabis growing wild, for worldgen.
 *
 * Not a CropBlock: crops require farmland underneath and would pop off the
 * instant they generated on grass. PlantBlock's default canPlantOnTop already
 * accepts the dirt tag, which is exactly what wild growth wants.
 *
 * Single state, no growth stages -- it's scenery you harvest for seeds, not
 * something you tend. Deliberately uses the strain-neutral mid-growth art, so
 * a wild plant doesn't advertise a phenotype it doesn't have.
 */
public class WildCannabisBlock extends PlantBlock implements PolymerTexturedBlock {
    public static final MapCodec<WildCannabisBlock> CODEC = createCodec(WildCannabisBlock::new);

    private final BlockState polymerState;

    public WildCannabisBlock(Settings settings) {
        super(settings);
        this.polymerState = TrapPolymer.requestOrFallback(
                BlockModelType.VINES_BLOCK,
                PolymerBlockModel.of(Identifier.of("trapcraft:block/cannabis_crop_age2")),
                () -> Blocks.WHEAT.getDefaultState().with(Properties.AGE_7, 4),
                "wild_cannabis");
    }

    @Override
    protected MapCodec<? extends PlantBlock> getCodec() {
        return CODEC;
    }

    @Override
    public BlockState getPolymerBlockState(BlockState state, PacketContext context) {
        return polymerState;
    }
}
