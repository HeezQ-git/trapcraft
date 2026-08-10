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
import net.minecraft.loot.context.LootContextParameters;
import net.minecraft.loot.context.LootWorldContext;
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
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import xyz.nucleoid.packettweaker.PacketContext;

import java.util.List;

/**
 * The thing that makes a room into an address.
 *
 * Put it down inside, right-click it, and it walks the walls and tells you
 * what you have built. Pass, and the room is on the register. From then on
 * this box is that house's post, and it can be pulled up and nailed to the
 * wall by the street without the house forgetting anything -- the survey was
 * taken from where the box FIRST stood, and that spot never moves.
 *
 * <h2>Why the drop is stamped rather than replaced</h2>
 *
 * The item that comes off a registered box carries the house's id, so the
 * loot table stays an ordinary one and the id is written onto whatever it
 * produced. Dropping the stack by hand instead would have been three lines
 * shorter and wrong in three places: a creative break would drop one, an
 * explosion would drop one, and silk touch would mean nothing.
 */
public class MailboxBlock extends Block implements PolymerBlock, PolymerTexturedBlock {
    private final BlockState carrier;

    public MailboxBlock(Settings settings) {
        super(settings);
        // TRANSPARENT_BLOCK, not FULL_BLOCK. A post box on a post is mostly
        // daylight, and a hollow model on a solid carrier is the X-ray hole
        // check_models.py exists to catch.
        this.carrier = TrapPolymer.requestOrFallback(
                BlockModelType.TRANSPARENT_BLOCK,
                PolymerBlockModel.of(Identifier.of("trapcraft:block/mailbox")),
                () -> Blocks.OAK_FENCE.getDefaultState(), "mailbox");
    }

    @Override
    public BlockState getPolymerBlockState(BlockState state, PacketContext context) {
        return carrier;
    }

    @Override
    public BlockState getPolymerBreakEventBlockState(BlockState state, PacketContext context) {
        return Blocks.OAK_PLANKS.getDefaultState();
    }

    /** A stamped box remembers which house it belongs to. */
    @Override
    public void onPlaced(World world, BlockPos pos, BlockState state,
                         LivingEntity placer, ItemStack stack) {
        if (!(world instanceof ServerWorld ground)) {
            return;
        }
        TrapHomes.Home home = MailboxItem.homeOf(stack);
        if (home == null) {
            return;
        }
        TrapHomes.reattach(home, pos);
        if (placer instanceof ServerPlayerEntity who) {
            who.sendMessage(Text.literal("The post for ").formatted(Formatting.GRAY)
                    .append(Text.literal(home.name()).formatted(Formatting.GOLD))
                    .append(Text.literal(" goes here now.").formatted(Formatting.GRAY)), true);
        }
        ground.playSound(null, pos, SoundEvents.BLOCK_LANTERN_PLACE,
                SoundCategory.BLOCKS, 0.8F, 1.2F);
    }

    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos,
                                 PlayerEntity player, BlockHitResult hit) {
        if (world.isClient() || !(player instanceof ServerPlayerEntity who)) {
            return ActionResult.SUCCESS;
        }
        ServerWorld ground = (ServerWorld) world;
        TrapHomes.Home home = TrapHomes.atMailbox(ground, pos);

        if (home == null) {
            String no = TrapHomes.found(who, ground, pos);
            if (no != null) {
                who.sendMessage(Text.literal(no).formatted(Formatting.GRAY), false);
                ground.playSound(null, pos, SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(),
                        SoundCategory.BLOCKS, 0.8F, 0.7F);
                return ActionResult.SUCCESS;
            }
            home = TrapHomes.atMailbox(ground, pos);
            ground.playSound(null, pos, SoundEvents.BLOCK_VAULT_ACTIVATE,
                    SoundCategory.BLOCKS, 0.7F, 1.2F);
            ground.spawnParticles(ParticleTypes.END_ROD, pos.getX() + 0.5, pos.getY() + 1.0,
                    pos.getZ() + 0.5, 24, 0.35, 0.4, 0.35, 0.04);
            who.sendMessage(Text.literal("On the register. ")
                    .formatted(Formatting.GREEN, Formatting.BOLD)
                    .append(Text.literal("Grade " + (home == null ? 0 : home.tier())
                            + ". Right-click again for the survey.")
                            .formatted(Formatting.GRAY)), false);
            return ActionResult.SUCCESS;
        }

        TrapHomes.Home reading = home;
        ground.playSound(null, pos, SoundEvents.ITEM_BOOK_PAGE_TURN,
                SoundCategory.BLOCKS, 0.8F, 1.0F);
        who.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, inventory, ignored) -> new MailboxScreenHandler(syncId, inventory,
                        reading),
                Text.literal(home.name()).formatted(Formatting.GOLD)));
        return ActionResult.SUCCESS;
    }

    /**
     * The address goes on the item on the way out.
     *
     * Runs before the block is actually removed -- the world drops first and
     * clears the position afterwards -- so the register still knows whose box
     * this was.
     */
    @Override
    protected List<ItemStack> getDroppedStacks(BlockState state, LootWorldContext.Builder builder) {
        List<ItemStack> drops = super.getDroppedStacks(state, builder);
        Vec3d origin = builder.getOptional(LootContextParameters.ORIGIN);
        if (origin == null) {
            return drops;
        }
        TrapHomes.Home home = TrapHomes.atMailbox(builder.getWorld(), BlockPos.ofFloored(origin));
        if (home == null) {
            return drops;
        }
        for (ItemStack drop : drops) {
            if (drop.isOf(TrapContent.mailboxItem)) {
                MailboxItem.stamp(drop, home);
            }
        }
        return drops;
    }

    /**
     * Pulled up. The house keeps everything except somewhere to put the post.
     *
     * Separate from the drop on purpose: this has to happen whether anything
     * dropped or not, and in creative nothing does.
     */
    @Override
    protected void onStateReplaced(BlockState state, ServerWorld world, BlockPos pos,
                                   boolean moved) {
        if (!state.isOf(this) || moved) {
            return;
        }
        TrapHomes.Home home = TrapHomes.atMailbox(world, pos);
        if (home != null) {
            TrapHomes.detach(home);
        }
        super.onStateReplaced(state, world, pos, moved);
    }
}
