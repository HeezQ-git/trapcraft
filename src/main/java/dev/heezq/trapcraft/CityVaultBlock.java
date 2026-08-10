package dev.heezq.trapcraft;

import eu.pb4.polymer.blocks.api.BlockModelType;
import eu.pb4.polymer.blocks.api.PolymerBlockModel;
import eu.pb4.polymer.blocks.api.PolymerTexturedBlock;
import eu.pb4.polymer.core.api.block.PolymerBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
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
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import xyz.nucleoid.packettweaker.PacketContext;

/**
 * The block that turns three people with farms into a city.
 *
 * Nothing is taxed until one of these is standing somewhere, and no house can
 * be registered either -- there is nobody to register it with. Put one down
 * and both start, server-wide, with an announcement. That single moment is the
 * point of the block existing at all: a city ought to have been founded rather
 * than to have always been the case.
 *
 * Only one, and the second refuses with the coordinates of the first. There is
 * one purse, so there is one counter to queue at.
 *
 * <h2>Breaking it does not spend anything</h2>
 *
 * The money is in {@link TrapCity}, not in here. Knocking the vault down means
 * nobody can reach the purse or file a house until one is stood up again, and
 * every emerald is still there when it is. Same reason the casino keeps its
 * balance in the ledger and not on the card.
 */
public class CityVaultBlock extends Block implements PolymerBlock, PolymerTexturedBlock {
    private final BlockState carrier;

    public CityVaultBlock(Settings settings) {
        super(settings);
        this.carrier = TrapPolymer.requestOrFallback(
                BlockModelType.FULL_BLOCK,
                PolymerBlockModel.of(Identifier.of("trapcraft:block/city_vault")),
                () -> Blocks.CHISELED_STONE_BRICKS.getDefaultState(), "city_vault");
    }

    @Override
    public BlockState getPolymerBlockState(BlockState state, PacketContext context) {
        return carrier;
    }

    @Override
    public BlockState getPolymerBreakEventBlockState(BlockState state, PacketContext context) {
        return Blocks.CHISELED_STONE_BRICKS.getDefaultState();
    }

    /** Founding the city, or being told there already is one. */
    @Override
    public void onPlaced(World world, BlockPos pos, BlockState state,
                         LivingEntity placer, ItemStack stack) {
        if (!(world instanceof ServerWorld ground) || !(placer instanceof ServerPlayerEntity who)) {
            return;
        }
        String no = TrapCity.found(ground, pos, who);
        if (no != null) {
            // Refused, so put it straight back in their hand rather than
            // leaving a second vault standing about looking official.
            who.sendMessage(Text.literal(no).formatted(Formatting.GRAY), false);
            ground.removeBlock(pos, false);
            who.getInventory().offerOrDrop(new ItemStack(TrapContent.cityVaultItem));
            ground.playSound(null, pos, SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(),
                    SoundCategory.BLOCKS, 0.8F, 0.7F);
            return;
        }
        ground.playSound(null, pos, SoundEvents.BLOCK_VAULT_ACTIVATE,
                SoundCategory.BLOCKS, 1.0F, 1.0F);
        ground.spawnParticles(ParticleTypes.TOTEM_OF_UNDYING, pos.getX() + 0.5,
                pos.getY() + 1.2, pos.getZ() + 0.5, 50, 0.5, 0.6, 0.5, 0.3);
    }

    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos,
                                 PlayerEntity player, BlockHitResult hit) {
        if (world.isClient() || !(player instanceof ServerPlayerEntity who)) {
            return ActionResult.SUCCESS;
        }
        world.playSound(null, pos, SoundEvents.BLOCK_VAULT_OPEN_SHUTTER,
                SoundCategory.BLOCKS, 0.7F, 1.1F);
        who.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, inventory, ignored) -> new CityScreenHandler(syncId, inventory),
                Text.literal("The City Purse").formatted(Formatting.GOLD)));
        return ActionResult.SUCCESS;
    }

    @Override
    protected void onStateReplaced(BlockState state, ServerWorld world, BlockPos pos,
                                   boolean moved) {
        if (!state.isOf(this) || moved) {
            return;
        }
        TrapCity.lost(world, pos);
        super.onStateReplaced(state, world, pos, moved);
    }
}
