package dev.heezq.trapcraft;

import eu.pb4.polymer.core.api.item.PolymerItem;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import xyz.nucleoid.packettweaker.PacketContext;

import java.util.List;
import java.util.UUID;

/**
 * A post box in your hand, and possibly a deed.
 *
 * Blank until a room passes its survey. After that the box that comes off the
 * wall carries the house's id, which is the whole reason "inspect it inside,
 * then move it outside" works at all: the id travels on the item, the survey
 * stays pinned to the spot it was taken from, and re-placing the box anywhere
 * on the map hands the post back to the same address.
 *
 * The casino card does the same trick for the same reason. See
 * {@link CasinoCardItem} -- and note that both write everything readable
 * server-side, because Polymer strips the component on the way to a client
 * that has never heard of it.
 */
public class MailboxItem extends BlockItem implements PolymerItem {
    private final Identifier model;

    public MailboxItem(net.minecraft.block.Block block, Settings settings, Identifier model) {
        super(block, settings);
        this.model = model;
    }

    /**
     * A stick, not a fence.
     *
     * Same trap the racks and tables hit: a placeable base item makes the
     * client predict placing THAT, so you get a fence post flickering into
     * existence a tick before the mailbox arrives. A stick places nothing, so
     * the client predicts nothing -- which is also why the place sound has to
     * be sent by hand below.
     */
    @Override
    public Item getPolymerItem(ItemStack stack, PacketContext context) {
        return Items.STICK;
    }

    @Override
    public ActionResult place(ItemPlacementContext context) {
        ActionResult result = super.place(context);
        if (result.isAccepted() && context.getPlayer() instanceof ServerPlayerEntity player) {
            BlockSoundGroup group = getBlock().getDefaultState().getSoundGroup();
            TrapPhantom.sound(player, Vec3d.ofCenter(context.getBlockPos()),
                    group.getPlaceSound(), SoundCategory.BLOCKS,
                    (group.getVolume() + 1.0F) / 2.0F, group.getPitch() * 0.8F);
        }
        return result;
    }

    @Override
    public Identifier getPolymerItemModel(ItemStack stack, PacketContext context) {
        return model;
    }

    /**
     * Keep the address honest, and let an anvil name the house.
     *
     * The casino card's trick, for the casino card's reason: an anvil is the
     * only text entry this mod has, and "HeezQ's place", "HeezQ's place 2",
     * "HeezQ's place 3" is a register nobody can read. Whatever the box is
     * called is what the house is called.
     */
    @Override
    public void inventoryTick(ItemStack stack, ServerWorld world, Entity holder,
                              EquipmentSlot slot) {
        TrapHomes.Home home = homeOf(stack);
        if (home == null) {
            return;
        }
        Text named = stack.get(DataComponentTypes.CUSTOM_NAME);
        String showing = named == null ? "" : named.getString();
        if (!showing.isBlank() && !showing.equals(home.name())) {
            TrapHomes.rename(home, showing);
            stamp(stack, home);
            return;
        }
        // The grade printed on the back goes stale on its own -- the house is
        // re-surveyed whether or not anybody is holding its post. Cheap enough
        // to redraw five times a minute and never think about it again.
        if (world.getTime() % 240 == 0) {
            stamp(stack, home);
        }
    }

    /** Which house this box is the post for, or null for a blank one. */
    public static TrapHomes.Home homeOf(ItemStack stack) {
        String id = stack.get(TrapComponents.home);
        if (id == null) {
            return null;
        }
        try {
            return TrapHomes.byId(UUID.fromString(id));
        } catch (IllegalArgumentException nonsense) {
            return null;
        }
    }

    /** Write the address on it. */
    public static void stamp(ItemStack stack, TrapHomes.Home home) {
        stack.set(TrapComponents.home, home.id().toString());
        stack.set(DataComponentTypes.CUSTOM_NAME,
                plain(home.name()).formatted(Formatting.GOLD, Formatting.BOLD));
        stack.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line("Grade " + home.tier() + ", " + home.ownerName() + "'s",
                        home.tier() == 0 ? Formatting.RED : Formatting.GREEN),
                line("Surveyed at " + home.anchor().getX() + " " + home.anchor().getY()
                        + " " + home.anchor().getZ(), Formatting.DARK_GRAY),
                Text.empty(),
                line("Put it back up anywhere -- by the door,", Formatting.GRAY),
                line("out on the street, wherever the post", Formatting.GRAY),
                line("should go. The house stays where it is.", Formatting.GRAY))));
    }

    private static MutableText plain(String text) {
        return Text.literal(text).styled(style -> style.withItalic(false));
    }

    private static MutableText line(String text, Formatting... colours) {
        return plain(text).formatted(colours);
    }
}
