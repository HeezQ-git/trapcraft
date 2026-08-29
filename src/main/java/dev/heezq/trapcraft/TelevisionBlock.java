package dev.heezq.trapcraft;

import eu.pb4.polymer.blocks.api.BlockModelType;
import eu.pb4.polymer.blocks.api.PolymerTexturedBlock;
import eu.pb4.polymer.core.api.block.PolymerBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;
import xyz.nucleoid.packettweaker.PacketContext;

import java.util.Map;

/**
 * The set in the corner of the betting shop.
 *
 * A closed cabinet with the tube recessed behind a proud bezel, so the carrier
 * can honestly claim FULL_BLOCK -- see the pool note in {@link TrapPolymer}.
 * The aerial and the control strip are bolted to the OUTSIDE of that cube,
 * which is free; a hollow one would have cost a leaf state and swayed in the
 * wind like a hedge.
 *
 * It flickers. A television that draws as a picture of a television is
 * furniture; one that throws a little light into a dark room at four in the
 * morning is the reason somebody put a chair in front of it.
 */
public class TelevisionBlock extends TurnableBlock implements PolymerBlock, PolymerTexturedBlock {
    private final Map<Direction, BlockState> carriers;

    public TelevisionBlock(Settings settings) {
        super(settings);
        this.carriers = carriers(BlockModelType.FULL_BLOCK, "television",
                () -> Blocks.BLACK_TERRACOTTA.getDefaultState());
    }

    @Override
    public BlockState getPolymerBlockState(BlockState state, PacketContext context) {
        return carriers.get(state.get(FACING));
    }

    @Override
    public BlockState getPolymerBreakEventBlockState(BlockState state, PacketContext context) {
        // Breaks and steps like the cabinet it is, not like the note block the
        // carrier happens to be. See docs/TRAPS.md.
        return Blocks.OAK_PLANKS.getDefaultState();
    }

    /**
     * Screen-light, thrown out of the front of the set.
     *
     * Server-side off the random tick, not randomDisplayTick: Polymer sends
     * the CARRIER blockstate, so as far as any client is concerned there is a
     * note block here and nothing to draw. See docs/TRAPS.md.
     */
    @Override
    protected void randomTick(BlockState state, ServerWorld world, BlockPos pos, Random random) {
        Direction facing = state.get(FACING);
        world.spawnParticles(ParticleTypes.END_ROD,
                pos.getX() + 0.5 + facing.getOffsetX() * 0.56,
                pos.getY() + 0.62,
                pos.getZ() + 0.5 + facing.getOffsetZ() * 0.56,
                2, 0.16, 0.14, 0.16, 0.0);
    }

    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos,
                                 PlayerEntity player, BlockHitResult hit) {
        if (world.isClient() || !(player instanceof ServerPlayerEntity punter)) {
            return ActionResult.SUCCESS;
        }
        world.playSound(null, pos, SoundEvents.BLOCK_NOTE_BLOCK_HAT.value(),
                SoundCategory.BLOCKS, 0.6F, 1.8F);
        if (world instanceof ServerWorld server) {
            server.spawnParticles(ParticleTypes.ELECTRIC_SPARK,
                    pos.getX() + 0.5 + state.get(FACING).getOffsetX() * 0.55,
                    pos.getY() + 0.7,
                    pos.getZ() + 0.5 + state.get(FACING).getOffsetZ() * 0.55,
                    6, 0.15, 0.15, 0.15, 0.0);
        }
        punter.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, inventory, ignored) ->
                        new TelevisionScreenHandler(syncId, inventory),
                Text.literal("Zakłady").formatted(Formatting.GOLD, Formatting.BOLD)));
        return ActionResult.SUCCESS;
    }
}
