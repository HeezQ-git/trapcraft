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
 * The sign over the door, and the thing that makes a room into a ward.
 *
 * Stood inside the building and clicked, the same way a mailbox is: it walks
 * the walls, counts the beds, and either opens a hospital or tells you what
 * the room is short of. That is deliberate mimicry -- registering a building
 * is a grammar this mod has already taught once, and a second one with its own
 * rules would be a second thing to learn for no reason.
 *
 * <h2>Why breaking it closes the hospital</h2>
 *
 * Unlike a mailbox, this block cannot be carried outside and nailed to a wall.
 * A mailbox is POST -- it points at a house measured somewhere else -- and this
 * is the ward itself: the survey is taken from where it stands, the doctor
 * stands next to it, and patients are put in beds around it. There is nothing
 * for it to point at, so there is nothing to move.
 */
public class HospitalBlock extends TurnableBlock
        implements PolymerBlock, PolymerTexturedBlock, SurveyAnchor {
    private final Map<Direction, BlockState> carriers;

    public HospitalBlock(Settings settings) {
        super(settings);
        // A solid cube, so FULL_BLOCK rather than the crowded see-through
        // pool: this is a tiled wall panel with a cross on it, and there is no
        // daylight anywhere in the model for check_models.py to object to.
        this.carriers = carriers(
                BlockModelType.FULL_BLOCK, "hospital",
                () -> Blocks.QUARTZ_BLOCK.getDefaultState());
    }

    @Override
    public BlockState getPolymerBlockState(BlockState state, PacketContext context) {
        return carriers.get(state.get(FACING));
    }

    @Override
    public BlockState getPolymerBreakEventBlockState(BlockState state, PacketContext context) {
        return Blocks.QUARTZ_BLOCK.getDefaultState();
    }

    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos,
                                 PlayerEntity player, BlockHitResult hit) {
        if (world.isClient() || !(player instanceof ServerPlayerEntity who)) {
            return ActionResult.SUCCESS;
        }
        ServerWorld ground = (ServerWorld) world;
        TrapHospitals.Ward ward = TrapHospitals.at(ground, pos);
        if (ward == null) {
            String no = TrapHospitals.found(who, ground, pos);
            if (no != null) {
                // Refused. The board still opens, because the whole point of
                // the checklist is that it is readable BEFORE it passes --
                // "no" with a list is a job, "no" on its own is a wall.
                who.sendMessage(Text.literal(no).formatted(Formatting.GRAY), false);
                ground.playSound(null, pos, SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(),
                        SoundCategory.BLOCKS, 0.8F, 0.7F);
                open(who, ground, pos, null);
                return ActionResult.SUCCESS;
            }
            ward = TrapHospitals.at(ground, pos);
            ground.playSound(null, pos, SoundEvents.BLOCK_VAULT_ACTIVATE,
                    SoundCategory.BLOCKS, 0.8F, 1.3F);
            ground.spawnParticles(ParticleTypes.HAPPY_VILLAGER, pos.getX() + 0.5,
                    pos.getY() + 1.2, pos.getZ() + 0.5, 30, 0.4, 0.5, 0.4, 0.05);
            who.sendMessage(Text.literal("Otwarty. ").formatted(Formatting.GREEN, Formatting.BOLD)
                    .append(Text.literal((ward == null ? 0 : ward.beds())
                            + " łóżek. Każdy ugryziony w tym mieście trafia tutaj, a kasa "
                            + "miasta płaci lekarzom " + TrapHospitals.bill()
                            + "e dziennie za leczenie.").formatted(Formatting.GRAY)), false);
            open(who, ground, pos, ward);
            return ActionResult.SUCCESS;
        }
        ground.playSound(null, pos, SoundEvents.ITEM_BOOK_PAGE_TURN,
                SoundCategory.BLOCKS, 0.8F, 1.0F);
        open(who, ground, pos, ward);
        return ActionResult.SUCCESS;
    }

    private static void open(ServerPlayerEntity who, ServerWorld ground, BlockPos pos,
                             TrapHospitals.Ward ward) {
        who.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, inventory, ignored) ->
                        new HospitalScreenHandler(syncId, inventory, ward, pos),
                Text.literal(ward == null ? "To jeszcze nie szpital" : ward.name())
                        .formatted(ward == null ? Formatting.GRAY : Formatting.WHITE)));
    }

    @Override
    protected void onStateReplaced(BlockState state, ServerWorld world, BlockPos pos,
                                   boolean moved) {
        if (!state.isOf(this) || moved) {
            return;
        }
        TrapHospitals.lost(world, pos);
        super.onStateReplaced(state, world, pos, moved);
    }
}
