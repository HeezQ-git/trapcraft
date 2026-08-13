package dev.heezq.trapcraft;

import eu.pb4.polymer.core.api.item.PolymerItem;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import xyz.nucleoid.packettweaker.PacketContext;

import java.util.List;

/**
 * The job board, in your pocket.
 *
 * Also where your reputation lives -- as a component on this stack rather than
 * in any persistent store. That means standing survives restarts for free and
 * is lost with the phone, which is a real stake: the handset is four ingots,
 * the twenty rep on it is not.
 */
public class BurnerPhoneItem extends Item implements PolymerItem {
    private final Identifier model;

    public BurnerPhoneItem(Settings settings, Identifier model) {
        super(settings);
        this.model = model;
    }

    @Override
    public Item getPolymerItem(ItemStack stack, PacketContext context) {
        return Items.STICK;
    }

    @Override
    public Identifier getPolymerItemModel(ItemStack stack, PacketContext context) {
        return model;
    }

    @Override
    public ActionResult use(World world, PlayerEntity user, Hand hand) {
        if (!(world instanceof ServerWorld server) || !(user instanceof ServerPlayerEntity player)) {
            return ActionResult.SUCCESS;
        }
        ItemStack phone = user.getStackInHand(hand);

        // Sneak for the network. The jobs board is what you reach for most, so
        // it keeps the plain click; the dealers are the deliberate trip.
        if (player.isSneaking()) {
            server.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.BLOCK_NOTE_BLOCK_BIT.value(), SoundCategory.PLAYERS, 0.5F, 1.2F);
            player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                    (syncId, inventory, ignored) -> new NetworkScreenHandler(syncId, inventory),
                    Text.literal("Siatka dilerów").formatted(Formatting.GOLD, Formatting.BOLD)));
            return ActionResult.SUCCESS;
        }

        Contract active = phone.get(TrapComponents.contract);
        if (active != null) {
            player.sendMessage(Text.literal("Masz już przyjęte zlecenie.")
                    .formatted(Formatting.GRAY), true);
            return ActionResult.SUCCESS;
        }

        List<Contract> jobs = TrapContracts.board(server, player, TrapContracts.repOf(phone));
        if (jobs.isEmpty()) {
            player.sendMessage(Text.literal("Tu nie ma zasięgu.")
                    .formatted(Formatting.GRAY), true);
            server.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), SoundCategory.PLAYERS, 0.6F, 0.6F);
            return ActionResult.SUCCESS;
        }

        // Two layers: a mechanical click and a tone. One alone reads as a UI
        // beep, the pair reads as a cheap handset flipping open.
        server.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.BLOCK_LEVER_CLICK, SoundCategory.PLAYERS, 0.7F, 1.4F);
        server.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.BLOCK_NOTE_BLOCK_BIT.value(), SoundCategory.PLAYERS, 0.5F, 1.8F);

        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, inventory, ignored) ->
                        new ContractScreenHandler(syncId, inventory, jobs, phone),
                Text.literal("Zlecenia  ·  reputacja " + TrapContracts.repOf(phone))
                        .formatted(Formatting.DARK_GREEN)));
        return ActionResult.SUCCESS;
    }
}
