package dev.heezq.trapcraft;

import eu.pb4.polymer.blocks.api.BlockModelType;
import eu.pb4.polymer.blocks.api.PolymerTexturedBlock;
import eu.pb4.polymer.core.api.block.PolymerBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.ParticleTypes;
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
import net.minecraft.world.World;
import xyz.nucleoid.packettweaker.PacketContext;

import java.util.Map;

/**
 * The bell by the garage door.
 *
 * Fourth building to use this grammar and the fourth time that is the whole
 * point: stand it in the room, click it, and be told either that the town has
 * a fire brigade or exactly what the room is short of. A mailbox taught it, a
 * ward reused it, a nick reused it, and nobody has had to learn a new way of
 * putting a building on a register since.
 *
 * <h2>No screen</h2>
 *
 * Deliberately, and this is where it parts company with the ward and the nick.
 * Both of those have a screen because both have a LIST -- who is in a bed, who
 * is in a cell -- and a list wants somewhere to live. A remiza has a number of
 * engines and a coverage radius, which is a sentence rather than a menu, and
 * the sentence is better said in chat where it can be read next to the fire it
 * is about. {@code /fires} is the rest of it, the way {@code /stalls} and
 * {@code /visitors} are.
 *
 * <h2>Why breaking it sends the engines home</h2>
 *
 * The shed IS the survey point, exactly as a station is: the garage is the
 * room around this block and the engines are what fits in it. There is nothing
 * to move it to, so a remiza with no block is a remiza with no engines -- and
 * anything it was on its way to stops being answered mid-run, which is the
 * honest consequence of knocking a garage down during a fire.
 */
public class FireHouseBlock extends TurnableBlock
        implements PolymerBlock, PolymerTexturedBlock, SurveyAnchor {
    private final Map<Direction, BlockState> carriers;

    public FireHouseBlock(Settings settings) {
        super(settings);
        // FULL_BLOCK, like the nick, the ward and the vault. It is a solid
        // cube with no daylight in the model, and the see-through pool has
        // none to spare on this pack -- see TrapPolymer.
        this.carriers = carriers(
                BlockModelType.FULL_BLOCK, "fire_house",
                () -> Blocks.BRICKS.getDefaultState());
    }

    @Override
    public BlockState getPolymerBlockState(BlockState state, PacketContext context) {
        return carriers.get(state.get(FACING));
    }

    @Override
    public BlockState getPolymerBreakEventBlockState(BlockState state, PacketContext context) {
        return Blocks.BRICKS.getDefaultState();
    }

    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos,
                                 PlayerEntity player, BlockHitResult hit) {
        if (world.isClient() || !(player instanceof ServerPlayerEntity who)) {
            return ActionResult.SUCCESS;
        }
        ServerWorld ground = (ServerWorld) world;
        TrapFires.Brigade shed = TrapFires.at(ground, pos);
        if (shed != null) {
            ground.playSound(null, pos, SoundEvents.ITEM_BOOK_PAGE_TURN,
                    SoundCategory.BLOCKS, 0.8F, 1.0F);
            TrapFires.report(who);
            return ActionResult.SUCCESS;
        }
        String no = TrapFires.found(who, ground, pos);
        if (no != null) {
            // "No" with a list is a job; "no" on its own is a wall. The ward's
            // reasoning, and the reason the readout follows the refusal.
            who.sendMessage(Text.literal(no).formatted(Formatting.GRAY), false);
            ground.playSound(null, pos, SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(),
                    SoundCategory.BLOCKS, 0.8F, 0.7F);
            return ActionResult.SUCCESS;
        }
        shed = TrapFires.at(ground, pos);
        ground.playSound(null, pos, SoundEvents.BLOCK_BELL_USE,
                SoundCategory.BLOCKS, 1.0F, 0.8F);
        ground.spawnParticles(ParticleTypes.FLAME, pos.getX() + 0.5,
                pos.getY() + 1.2, pos.getZ() + 0.5, 24, 0.4, 0.5, 0.4, 0.01);
        who.sendMessage(TrapNotes.headline("REMIZA OTWARTA", Formatting.GOLD)
                .append(TrapNotes.say("   " + (shed == null ? 0 : shed.engines())
                        + " wozów", Formatting.WHITE))
                .append(TrapNotes.under("Wyjeżdżają same, do " + TrapFires.REACH
                        + " bloków. Każdy wyjazd kosztuje miasto "
                        + TrapFires.CALLOUT + "e.")), false);
        TrapFires.report(who);
        return ActionResult.SUCCESS;
    }

    @Override
    protected void onStateReplaced(BlockState state, ServerWorld world, BlockPos pos,
                                   boolean moved) {
        if (!state.isOf(this) || moved) {
            return;
        }
        TrapFires.lost(world, pos);
        super.onStateReplaced(state, world, pos, moved);
    }
}
