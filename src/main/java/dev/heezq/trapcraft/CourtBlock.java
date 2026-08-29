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
 * The bench.
 *
 * Put it down and click it and the town has a courthouse -- no survey, no
 * cells to count, no inspection to fail. That is deliberate and it is the one
 * place this block breaks the grammar the station, the ward and the fire house
 * all share: those three are BUILDINGS whose rooms decide what they can do,
 * and what a court can do is decided by nothing except existing. A judge does
 * not need four walls; they need somewhere to sit and somebody to bring them a
 * case.
 *
 * <h2>Why it is not owned in any way that matters</h2>
 *
 * Whoever puts it down gets their name on it and the clerk's cut of the fees.
 * They do not get to choose what is heard, who wins, or whose cases go in the
 * diary -- every theft in town is listed here whoever it happened to. A court
 * that only tried its owner's cases would be a way of taxing your neighbours
 * for being robbed, which is a different building.
 *
 * <h2>Breaking it</h2>
 *
 * Closes the courthouse and NOTHING else. The diary survives, because a
 * pending case is somebody's money and a pickaxe is not a legal argument --
 * see {@link TrapCourt#closeCourt}.
 */
public class CourtBlock extends TurnableBlock
        implements PolymerBlock, PolymerTexturedBlock, SurveyAnchor {
    private final Map<Direction, BlockState> carriers;

    public CourtBlock(Settings settings) {
        super(settings);
        // A solid cube on a FULL_BLOCK carrier, the station's trade: there is
        // no daylight anywhere in the model for check_models.py to object to,
        // and the see-through pool has none of the headroom this would need.
        this.carriers = carriers(
                BlockModelType.FULL_BLOCK, "court",
                () -> Blocks.POLISHED_ANDESITE.getDefaultState());
    }

    @Override
    public BlockState getPolymerBlockState(BlockState state, PacketContext context) {
        return carriers.get(state.get(FACING));
    }

    @Override
    public BlockState getPolymerBreakEventBlockState(BlockState state, PacketContext context) {
        return Blocks.POLISHED_ANDESITE.getDefaultState();
    }

    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos,
                                 PlayerEntity player, BlockHitResult hit) {
        if (world.isClient() || !(player instanceof ServerPlayerEntity who)) {
            return ActionResult.SUCCESS;
        }
        ServerWorld ground = (ServerWorld) world;
        TrapCourt.Court court = TrapCourt.at(ground, pos);
        if (court == null) {
            TrapCourt.open(ground, pos, who);
            court = TrapCourt.at(ground, pos);
            ground.playSound(null, pos, SoundEvents.BLOCK_VAULT_ACTIVATE,
                    SoundCategory.BLOCKS, 0.8F, 0.9F);
            ground.spawnParticles(ParticleTypes.END_ROD, pos.getX() + 0.5,
                    pos.getY() + 1.2, pos.getZ() + 0.5, 30, 0.4, 0.5, 0.4, 0.02);
            who.sendMessage(TrapNotes.headline("SĄD OTWARTY", Formatting.GOLD)
                    .append(TrapNotes.under("Od teraz policja stawia przed nim każdą "
                            + "kradzież i każdy rozbój w mieście. Rozprawa jest "
                            + TrapCourt.LISTING_DAYS + " dni po zatrzymaniu.")), false);
            TrapAwards.grant(who, "bench");
        } else {
            ground.playSound(null, pos, SoundEvents.ITEM_BOOK_PAGE_TURN,
                    SoundCategory.BLOCKS, 0.8F, 1.0F);
        }
        open(who, pos, court);
        return ActionResult.SUCCESS;
    }

    private static void open(ServerPlayerEntity who, BlockPos pos, TrapCourt.Court court) {
        who.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, inventory, ignored) ->
                        new CourtScreenHandler(syncId, inventory, court),
                Text.literal(court == null ? "Sąd" : court.name())
                        .formatted(Formatting.GOLD)));
    }

    @Override
    protected void onStateReplaced(BlockState state, ServerWorld world, BlockPos pos,
                                   boolean moved) {
        if (!state.isOf(this) || moved) {
            return;
        }
        TrapCourt.closeCourt(world, pos);
        super.onStateReplaced(state, world, pos, moved);
    }
}
