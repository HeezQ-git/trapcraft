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

    /**
     * How far a spare box will look for a house of yours to serve.
     *
     * The mailbox is meant to end up OUTSIDE, by the door, on the street --
     * which is exactly where a survey cannot be taken from. So a box that is
     * standing outdoors does not try to found anything; it looks around for
     * one of your houses that has lost its post and takes the job.
     */
    private static final int LOOKS_FOR = 24;

    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos,
                                 PlayerEntity player, BlockHitResult hit) {
        if (world.isClient() || !(player instanceof ServerPlayerEntity who)) {
            return ActionResult.SUCCESS;
        }
        ServerWorld ground = (ServerWorld) world;
        TrapHomes.Home home = TrapHomes.atMailbox(ground, pos);

        // Sneak empty-handed and it comes off the wall into your hand,
        // address and all. Breaking one works too, but breaking is how this
        // went wrong in the first place and picking a thing up should not
        // require a pickaxe and a leap of faith.
        //
        // Vanilla only routes a sneaking click here when BOTH hands are empty,
        // so the check below is belt and braces -- and the note in the book
        // says "empty-handed" rather than "empty hand" because of it.
        if (player.isSneaking() && player.getMainHandStack().isEmpty()) {
            ItemStack take = new ItemStack(TrapContent.mailboxItem);
            if (home != null) {
                MailboxItem.stamp(take, home);
            }
            ground.removeBlock(pos, false);
            who.getInventory().offerOrDrop(take);
            ground.playSound(null, pos, SoundEvents.BLOCK_LANTERN_BREAK,
                    SoundCategory.BLOCKS, 0.8F, 1.1F);
            who.sendMessage(home == null
                    ? Text.literal("Picked it up.").formatted(Formatting.GRAY)
                    : Text.literal("Picked up the post for ").formatted(Formatting.GRAY)
                            .append(Text.literal(home.name()).formatted(Formatting.GOLD))
                            .append(Text.literal(". Put it wherever it should go.")
                                    .formatted(Formatting.GRAY)), true);
            return ActionResult.SUCCESS;
        }

        if (home == null) {
            home = claim(who, ground, pos);
            if (home == null) {
                return ActionResult.SUCCESS;
            }
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
     * What an unstamped box does when somebody clicks it, in order.
     *
     * <ol>
     *   <li><b>Standing inside a claim</b> -- take that house's post. This is
     *       what a box whose stamp went missing does, and it means the answer
     *       to "my mailbox forgot which house it was" is to put it back in the
     *       room and click it.
     *   <li><b>Otherwise, survey from here.</b> A sealed room becomes a new
     *       house, as it always did.
     *   <li><b>The survey says "not sealed"</b> -- so it is outdoors, which is
     *       where a mailbox is supposed to live. Look for one of yours nearby
     *       that has no box and serve that instead.
     * </ol>
     *
     * The order matters. Trying the neighbours first would mean a second room
     * built twenty blocks from the first quietly became a second postbox for
     * the first, instead of a house.
     *
     * @return the house this box now serves, or null having already said why not
     */
    private TrapHomes.Home claim(ServerPlayerEntity who, ServerWorld ground, BlockPos pos) {
        TrapHomes.Home inside = TrapHomes.covering(ground, pos);
        if (inside != null) {
            if (!inside.owner().equals(who.getUuid()) && !who.hasPermissionLevel(2)) {
                refuse(who, ground, pos, "That's inside " + inside.name() + ", "
                        + inside.ownerName() + "'s. Their post, not yours.");
                return null;
            }
            TrapHomes.reattach(inside, pos);
            good(ground, pos);
            who.sendMessage(Text.literal("This is the post for ").formatted(Formatting.GREEN)
                    .append(Text.literal(inside.name())
                            .formatted(Formatting.GOLD, Formatting.BOLD))
                    .append(Text.literal(" now.").formatted(Formatting.GRAY)), false);
            return inside;
        }

        String no = TrapHomes.found(who, ground, pos);
        if (no == null) {
            TrapHomes.Home fresh = TrapHomes.atMailbox(ground, pos);
            good(ground, pos);
            who.sendMessage(Text.literal("On the register. ")
                    .formatted(Formatting.GREEN, Formatting.BOLD)
                    .append(Text.literal("Grade " + (fresh == null ? 0 : fresh.tier())
                            + ".").formatted(Formatting.GRAY))
                    .append(Text.literal("\n  Now sneak-click to pick it up and nail it "
                            + "outside -- the house stays measured where it is.")
                            .formatted(Formatting.DARK_GRAY)), false);
            return fresh;
        }

        TrapHomes.Home spare = TrapHomes.spareOf(who, ground, pos, LOOKS_FOR);
        if (spare != null) {
            TrapHomes.reattach(spare, pos);
            good(ground, pos);
            who.sendMessage(Text.literal("Post for ").formatted(Formatting.GREEN)
                    .append(Text.literal(spare.name())
                            .formatted(Formatting.GOLD, Formatting.BOLD))
                    .append(Text.literal(" arrives here now.").formatted(Formatting.GRAY)), false);
            return spare;
        }
        refuse(who, ground, pos, no);
        return null;
    }

    private static void good(ServerWorld ground, BlockPos pos) {
        ground.playSound(null, pos, SoundEvents.BLOCK_VAULT_ACTIVATE,
                SoundCategory.BLOCKS, 0.7F, 1.2F);
        ground.spawnParticles(ParticleTypes.END_ROD, pos.getX() + 0.5, pos.getY() + 1.0,
                pos.getZ() + 0.5, 24, 0.35, 0.4, 0.35, 0.04);
    }

    private static void refuse(ServerPlayerEntity who, ServerWorld ground, BlockPos pos,
                               String why) {
        who.sendMessage(Text.literal(why).formatted(Formatting.GRAY), false);
        ground.playSound(null, pos, SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(),
                SoundCategory.BLOCKS, 0.8F, 0.7F);
    }

    /**
     * The address goes on the item on the way out.
     *
     * This used to be paired with clearing the register's mailbox position in
     * {@code onStateReplaced}, on the reasonable-sounding belief that a world
     * drops a block before it removes it. A world does. A PLAYER does not:
     * {@code ServerPlayerInteractionManager.tryBreakBlock} calls
     * {@code removeBlock} first and {@code afterBreak} -- which is what
     * produces the drops -- several lines later. So every mailbox mined by
     * hand came out blank, and putting it back down founded a second house
     * that immediately collided with the first.
     *
     * Nothing here depends on the order any more: the register keeps pointing
     * at the old spot until another box claims it, which also means putting a
     * blank one back in the same hole simply works.
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

}
