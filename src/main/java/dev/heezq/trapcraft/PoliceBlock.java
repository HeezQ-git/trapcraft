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
import net.minecraft.world.World;
import xyz.nucleoid.packettweaker.PacketContext;

import java.util.Map;

/**
 * The blue lamp over the door.
 *
 * Stood inside the building and clicked, exactly the way a hospital block is:
 * it walks the walls, counts the cells, and either opens a station or tells you
 * what the room is short of. Third time this grammar has been used and that is
 * the whole point of using it -- a mailbox taught it, a ward reused it, and
 * nobody has to learn a third way of putting a building on the register.
 *
 * <h2>Why breaking it lets everybody out</h2>
 *
 * Because the station IS the survey point: the shift parades from where this
 * stands, the cells are the beds around it, and a prisoner is a body standing
 * next to it. There is nothing for it to point at, so there is nothing to move
 * -- and a nick with no building is a nick with no cells, which is the same
 * thing as an open door.
 */
public class PoliceBlock extends TurnableBlock
        implements PolymerBlock, PolymerTexturedBlock, SurveyAnchor {
    private final Map<Direction, BlockState> carriers;

    public PoliceBlock(Settings settings) {
        super(settings);
        // A solid cube, so FULL_BLOCK rather than the crowded see-through
        // pool -- the same trade the hospital and the vault make. There is no
        // daylight anywhere in the model for check_models.py to object to, and
        // FULL_BLOCK has four figures of headroom while NON_SOLID has none.
        this.carriers = carriers(
                BlockModelType.FULL_BLOCK, "police",
                () -> Blocks.STONE_BRICKS.getDefaultState());
    }

    @Override
    public BlockState getPolymerBlockState(BlockState state, PacketContext context) {
        return carriers.get(state.get(FACING));
    }

    @Override
    public BlockState getPolymerBreakEventBlockState(BlockState state, PacketContext context) {
        return Blocks.STONE_BRICKS.getDefaultState();
    }

    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos,
                                 PlayerEntity player, BlockHitResult hit) {
        if (world.isClient() || !(player instanceof ServerPlayerEntity who)) {
            return ActionResult.SUCCESS;
        }
        ServerWorld ground = (ServerWorld) world;
        TrapPolice.Station station = TrapPolice.at(ground, pos);
        if (station == null) {
            String no = TrapPolice.found(who, ground, pos);
            if (no != null) {
                // Refused, and the board still opens: "no" with a list is a
                // job, "no" on its own is a wall. The ward's reasoning.
                who.sendMessage(Text.literal(no).formatted(Formatting.GRAY), false);
                ground.playSound(null, pos, SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(),
                        SoundCategory.BLOCKS, 0.8F, 0.7F);
                open(who, ground, pos, null);
                return ActionResult.SUCCESS;
            }
            station = TrapPolice.at(ground, pos);
            ground.playSound(null, pos, SoundEvents.BLOCK_VAULT_ACTIVATE,
                    SoundCategory.BLOCKS, 0.8F, 1.1F);
            ground.spawnParticles(ParticleTypes.END_ROD, pos.getX() + 0.5,
                    pos.getY() + 1.2, pos.getZ() + 0.5, 30, 0.4, 0.5, 0.4, 0.02);
            who.sendMessage(TrapNotes.headline("KOMISARIAT OTWARTY", Formatting.AQUA)
                    .append(TrapNotes.say("   " + (station == null ? 0 : station.cells())
                            + " cel", Formatting.WHITE))
                    .append(TrapNotes.under("Ilu wyjdzie na ulicę, decyduje budżet przy "
                            + "skarbcu miasta -- " + TrapPolice.WAGE
                            + "e dziennie za funkcjonariusza.")), false);
            open(who, ground, pos, station);
            return ActionResult.SUCCESS;
        }
        ground.playSound(null, pos, SoundEvents.ITEM_BOOK_PAGE_TURN,
                SoundCategory.BLOCKS, 0.8F, 1.0F);
        open(who, ground, pos, station);
        return ActionResult.SUCCESS;
    }

    private static void open(ServerPlayerEntity who, ServerWorld ground, BlockPos pos,
                             TrapPolice.Station station) {
        who.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, inventory, ignored) ->
                        new PoliceScreenHandler(syncId, inventory, station, pos),
                Text.literal(station == null ? "To jeszcze nie komisariat" : station.name())
                        .formatted(station == null ? Formatting.GRAY : Formatting.WHITE)));
    }

    @Override
    protected void onStateReplaced(BlockState state, ServerWorld world, BlockPos pos,
                                   boolean moved) {
        if (!state.isOf(this) || moved) {
            return;
        }
        TrapPolice.lost(world, pos);
        super.onStateReplaced(state, world, pos, moved);
    }
}
