package dev.heezq.trapcraft;

import eu.pb4.polymer.blocks.api.BlockModelType;
import eu.pb4.polymer.blocks.api.PolymerTexturedBlock;
import eu.pb4.polymer.core.api.block.PolymerBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
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
import net.minecraft.world.World;
import xyz.nucleoid.packettweaker.PacketContext;

/**
 * The booth, and therefore the club.
 *
 * One block makes a room a business, the way a till does. Everything else
 * about the place is the room you built round it: the lights, the floor to
 * stand on, whatever you put on the walls. The mod does not grade any of that
 * and deliberately so -- a nightclub is the one building where taste is the
 * whole point, and a checklist telling somebody their club needs two more
 * lamps would be the mod designing it for them.
 *
 * <p>Deliberately NOT directional. A facing block is four carrier states out
 * of Polymer's pool instead of one, and this is scenery with a screen on it
 * rather than something whose front matters. See {@link TrapPolymer}.
 */
public class NightclubBlock extends Block implements PolymerBlock, PolymerTexturedBlock {
    private final BlockState carrier;

    public NightclubBlock(Settings settings) {
        super(settings);
        this.carrier = TrapPolymer.requestOrFallback(
                BlockModelType.FULL_BLOCK,
                eu.pb4.polymer.blocks.api.PolymerBlockModel.of(
                        net.minecraft.util.Identifier.of("trapcraft:block/nightclub")),
                () -> Blocks.NOTE_BLOCK.getDefaultState(), "nightclub");
    }

    @Override
    public BlockState getPolymerBlockState(BlockState state, PacketContext context) {
        return carrier;
    }

    @Override
    public BlockState getPolymerBreakEventBlockState(BlockState state, PacketContext context) {
        return Blocks.NOTE_BLOCK.getDefaultState();
    }

    @Override
    public void onPlaced(World world, BlockPos pos, BlockState state,
                         LivingEntity placer, ItemStack stack) {
        if (world instanceof ServerWorld ground && placer instanceof ServerPlayerEntity owner) {
            TrapClubs.open(ground, pos, owner);
            // A named booth is a named club the moment it lands, the way a
            // named till is a named shop.
            Text named = stack.get(DataComponentTypes.CUSTOM_NAME);
            TrapClubs.Club club = named == null ? null : TrapClubs.at(ground, pos);
            if (club != null) {
                TrapClubs.rename(club, named.getString());
            }
        }
    }

    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos,
                                 PlayerEntity player, BlockHitResult hit) {
        if (world.isClient() || !(player instanceof ServerPlayerEntity who)
                || !(world instanceof ServerWorld ground)) {
            return ActionResult.SUCCESS;
        }
        TrapClubs.Club club = TrapClubs.at(ground, pos);
        if (club == null) {
            // Placed by something that is not a player -- a dispenser, a
            // structure, an older version of this mod. Adopted by whoever
            // opens it rather than left as a block that does nothing forever.
            TrapClubs.open(ground, pos, who);
            club = TrapClubs.at(ground, pos);
        }
        if (club == null) {
            return ActionResult.SUCCESS;
        }
        final TrapClubs.Club open = club;
        who.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, inventory, ignored) -> new ClubScreenHandler(syncId, inventory, open),
                Text.literal(open.name()).formatted(Formatting.LIGHT_PURPLE,
                        Formatting.BOLD)));
        world.playSound(null, pos, SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(),
                SoundCategory.BLOCKS, 0.6F, 0.7F);
        return ActionResult.SUCCESS;
    }

    /** Taken down: the club closes and the till spills. */
    @Override
    protected void onStateReplaced(BlockState state, ServerWorld world, BlockPos pos,
                                   boolean moved) {
        if (!state.isOf(this) || moved) {
            return;
        }
        TrapClubs.close(world, pos);
        super.onStateReplaced(state, world, pos, moved);
    }

    /**
     * It thumps when there is somebody in.
     *
     * Server-side, because a Polymer block's client never knows it is there --
     * see the note in {@link TrapPolymer}. Hung off the random tick rather
     * than a scan, and only when the room is actually busy: an empty club is
     * a quiet block, which is the difference you want to be able to see from
     * outside.
     */
    @Override
    protected void randomTick(BlockState state, ServerWorld world, BlockPos pos,
                              net.minecraft.util.math.random.Random random) {
        TrapClubs.Club club = TrapClubs.at(world, pos);
        if (club == null || club.inside() <= 0) {
            return;
        }
        world.spawnParticles(ParticleTypes.NOTE, pos.getX() + 0.5, pos.getY() + 1.1,
                pos.getZ() + 0.5, 2, 0.4, 0.2, 0.4, 1.0);
        if (random.nextInt(3) == 0) {
            world.playSound(null, pos, SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(),
                    SoundCategory.RECORDS, 0.5F, 0.6F);
        }
    }
}
