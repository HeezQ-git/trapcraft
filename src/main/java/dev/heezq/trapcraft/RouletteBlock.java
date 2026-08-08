package dev.heezq.trapcraft;

import eu.pb4.polymer.blocks.api.BlockModelType;
import eu.pb4.polymer.blocks.api.PolymerBlockModel;
import eu.pb4.polymer.blocks.api.PolymerTexturedBlock;
import eu.pb4.polymer.core.api.block.PolymerBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.network.ServerPlayerEntity;
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
 * The other table in the casino.
 *
 * One block, not two: you stand AT a roulette table rather than in front of
 * it, and a waist-high table next to the slot machine's cabinet is what makes
 * a room of them read as a casino floor instead of a row of vending machines.
 *
 * The game is in {@link RouletteScreenHandler}; this is the furniture.
 */
public class RouletteBlock extends Block implements PolymerBlock, PolymerTexturedBlock {
    private final BlockState carrier;

    public RouletteBlock(Settings settings) {
        super(settings);
        this.carrier = TrapPolymer.requestOrFallback(
                BlockModelType.FULL_BLOCK,
                PolymerBlockModel.of(Identifier.of("trapcraft:block/roulette")),
                () -> Blocks.GREEN_TERRACOTTA.getDefaultState(), "roulette");
    }

    @Override
    public BlockState getPolymerBlockState(BlockState state, PacketContext context) {
        return carrier;
    }

    /** Break as wood: it's a table, whatever the felt says. */
    @Override
    public BlockState getPolymerBreakEventBlockState(BlockState state, PacketContext context) {
        return Blocks.OAK_PLANKS.getDefaultState();
    }

    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos,
                                 PlayerEntity player, BlockHitResult hit) {
        if (world.isClient() || !(player instanceof ServerPlayerEntity gambler)) {
            return ActionResult.SUCCESS;
        }
        world.playSound(null, pos, SoundEvents.BLOCK_NOTE_BLOCK_HAT.value(),
                SoundCategory.BLOCKS, 0.7F, 1.2F);
        gambler.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, inventory, ignored) -> new RouletteScreenHandler(syncId, inventory),
                Text.literal("Roulette").formatted(Formatting.GREEN, Formatting.BOLD)));
        return ActionResult.SUCCESS;
    }
}
