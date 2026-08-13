package dev.heezq.trapcraft;

import eu.pb4.polymer.core.api.item.PolymerItem;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import xyz.nucleoid.packettweaker.PacketContext;

import java.util.ArrayList;
import java.util.List;

/**
 * The deed to a casino, in your hand.
 *
 * Crafted blank. Right-click the air and you have opened a casino; right-click
 * a machine and that machine now pays into it; right-click the air again and
 * you are standing in the counting room.
 *
 * It carries the casino's id and nothing else -- the money is in the ledger,
 * see {@link TrapHouse} for why. The balance printed on the back is redrawn
 * from the ledger while the card sits in your pocket, so a card in a chest is
 * a card telling the truth about a business that is still running.
 *
 * <h2>The name</h2>
 *
 * Whatever the card is called is what the casino is called, and an anvil is
 * how you call it something. A rename is noticed on the next tick and adopted,
 * which is the only text-entry this mod has anywhere and the only one it
 * needs: there is no way to type into a chest GUI, and the anvil already does
 * this job for every other item in the game.
 */
public class CasinoCardItem extends Item implements PolymerItem {

    private final Identifier model;

    public CasinoCardItem(Settings settings, Identifier model) {
        super(settings);
        this.model = model;
    }

    @Override
    public Item getPolymerItem(ItemStack stack, PacketContext context) {
        return Items.PAPER;
    }

    @Override
    public Identifier getPolymerItemModel(ItemStack stack, PacketContext context) {
        return model;
    }

    // --- what it says ---------------------------------------------------------

    /**
     * Redraw the face of the card.
     *
     * Everything readable on it is written server-side from the ledger, for
     * the same reason the wallet's is: the casino component is stripped on the
     * way to the client, so a card that only carried the id would look like a
     * blank piece of paper in your hand.
     */
    public static void restamp(ItemStack card, TrapHouse.House house) {
        if (house == null) {
            card.set(DataComponentTypes.CUSTOM_NAME,
                    plain("Niepodpisana licencja").formatted(Formatting.GRAY, Formatting.BOLD));
            card.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                    line("Niepodpisana.", Formatting.DARK_GRAY),
                    Text.empty(),
                    line("Kliknij PPM w powietrze, żeby otworzyć kasyno.", Formatting.YELLOW),
                    line("Najpierw nazwij licencję na kowadle, jeśli", Formatting.DARK_GRAY),
                    line("chcesz mu nadać własną nazwę.", Formatting.DARK_GRAY))));
            return;
        }

        card.set(DataComponentTypes.CUSTOM_NAME,
                plain(house.name).formatted(Formatting.GOLD, Formatting.BOLD));
        // Mirrored onto the wallet's component purely so the tick loop can tell
        // a stale card from a current one with an integer compare instead of
        // rebuilding this lore sixty times a second.
        card.set(TrapComponents.balance, (int) Math.min(Integer.MAX_VALUE, house.balance));

        int machines = TrapHouse.machineCount(house);
        List<Text> lore = new ArrayList<>();
        lore.add(plain("Skarbiec  ").formatted(Formatting.GRAY)
                .append(plain(house.balance + "e")
                        .formatted(house.balance > 0 ? Formatting.GREEN : Formatting.RED,
                                Formatting.BOLD)));
        lore.add(line("automatów na sali: " + machines,
                Formatting.DARK_GRAY));
        // The gauge that was never there. Wear has been accumulating on every
        // cabinet since the day this mod shipped and nothing anywhere showed
        // it, so "do the machines break?" was a question the game gave its
        // owner no way to answer. They do, at 100.
        // Read off the WHOLE floor, not off its worst cabinet. On the single
        // worst it is a gauge that works on a floor of four and is broken on a
        // floor of fifty: one dead machine out of fifty-one pins it at 0% and
        // it never moves again, so an owner whose room averages 44 is told the
        // place is in pieces and has no way to tell a bad night from a bad
        // year. The average is also the number the house screen shows and the
        // number the floor's name is actually judged on -- see
        // TrapMath.houseRepTarget -- so the two now agree. The worst is still
        // named, because the hammer needs somewhere to go.
        int worn = TrapHouse.averageWear(house);
        int worst = TrapHouse.worstWear(house);
        if (machines > 0) {
            lore.add(line("Condition  ", Formatting.GRAY)
                    .append(plain((100 - worn) + "%")
                            .formatted(worn >= TrapMath.JAM_FROM ? Formatting.RED
                                    : Formatting.WHITE))
                    .append(plain(worst >= TrapMath.JAM_FROM
                                    ? "  średnio -- najgorszy automat " + (100 - worst)
                                    + "%, odstrasza graczy"
                                    : "  wszystkie sprawne").formatted(Formatting.DARK_GRAY)));
        }
        int town = TrapHomes.population();
        lore.add(line("Trade  ", Formatting.GRAY)
                .append(plain(String.format("%.2fx", house.pull()))
                        .formatted(Formatting.WHITE))
                .append(plain("  od " + town + " mieszkańców")
                        .formatted(town >= TrapMath.PULL_AT ? Formatting.DARK_GRAY
                                : Formatting.RED)));
        if (house.handle > 0) {
            // Net of upkeep and the cut. The gross figure flattered a
            // ten-machine floor by a third, and a business you are judging by
            // the wrong number is one you cannot make decisions about.
            lore.add(line("Zysk " + house.profit() + "e z " + house.handle
                    + "e obrotu  (" + house.edge() + "% po kosztach)",
                    house.profit() >= 0 ? Formatting.DARK_GRAY : Formatting.RED));
        }
        lore.add(Text.empty());
        if (machines == 0) {
            lore.add(line("Kliknij PPM automat, żeby go podłączyć.", Formatting.YELLOW));
        } else if (house.balance <= 0) {
            lore.add(line("Pusty. Twoje automaty nie przyjmą zakładu.", Formatting.RED));
        }
        lore.add(line("Kliknij PPM w powietrze, żeby otworzyć kantorek.", Formatting.YELLOW));
        lore.add(Text.empty());
        lore.add(line("Kto trzyma licencję, ten jest właścicielem.", Formatting.DARK_GRAY));
        card.set(DataComponentTypes.LORE, new LoreComponent(lore));
    }

    /**
     * Keep the card honest, and let an anvil rename the business.
     *
     * Cheap in the common case: one map lookup and an integer compare, and the
     * lore is only rebuilt when a number on it has actually moved.
     */
    @Override
    public void inventoryTick(ItemStack stack, ServerWorld world, Entity holder,
                              EquipmentSlot slot) {
        TrapHouse.House house = TrapHouse.of(stack);
        if (house == null) {
            if (stack.get(DataComponentTypes.LORE) == null) {
                restamp(stack, null);
            }
            return;
        }
        Text named = stack.get(DataComponentTypes.CUSTOM_NAME);
        String showing = named == null ? "" : named.getString();
        if (!showing.equals(house.name)) {
            // An anvil got to it. The card is the deed, so the card wins.
            if (!showing.isBlank()) {
                house.name = showing;
                TrapHouse.touch();
            }
            restamp(stack, house);
            return;
        }
        Integer shown = stack.get(TrapComponents.balance);
        if (shown == null || shown != (int) Math.min(Integer.MAX_VALUE, house.balance)) {
            restamp(stack, house);
        }
    }

    // --- using it -------------------------------------------------------------

    @Override
    public ActionResult use(World world, PlayerEntity user, Hand hand) {
        ItemStack card = user.getStackInHand(hand);
        if (world.isClient() || !(user instanceof ServerPlayerEntity owner)) {
            return ActionResult.SUCCESS;
        }
        TrapHouse.House house = TrapHouse.of(card);
        if (house == null) {
            open(owner, card);
            return ActionResult.SUCCESS;
        }
        world.playSound(null, owner.getBlockPos(), SoundEvents.BLOCK_VAULT_OPEN_SHUTTER,
                SoundCategory.PLAYERS, 0.6F, 1.3F);
        owner.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, inventory, ignored) ->
                        new HouseScreenHandler(syncId, inventory, card),
                plain(house.name).formatted(Formatting.GOLD, Formatting.BOLD)));
        return ActionResult.SUCCESS;
    }

    /** Sign the card. From here on there is a business behind it. */
    private void open(ServerPlayerEntity owner, ItemStack card) {
        Text named = card.get(DataComponentTypes.CUSTOM_NAME);
        String wanted = named == null ? "" : named.getString().trim();
        String name = wanted.isBlank() || wanted.equals("Niepodpisana licencja")
                ? owner.getGameProfile().getName() + "'s"
                : wanted;

        TrapHouse.House house = TrapHouse.found(owner, card, name);
        restamp(card, house);

        ServerWorld world = owner.getWorld();
        world.spawnParticles(ParticleTypes.TOTEM_OF_UNDYING,
                owner.getX(), owner.getY() + 1.1, owner.getZ(), 60, 0.5, 0.6, 0.5, 0.35);
        world.spawnParticles(ParticleTypes.END_ROD,
                owner.getX(), owner.getY() + 1.4, owner.getZ(), 25, 0.4, 0.5, 0.4, 0.05);
        world.playSound(null, owner.getBlockPos(), SoundEvents.BLOCK_VAULT_ACTIVATE,
                SoundCategory.PLAYERS, 0.8F, 1.0F);
        world.playSound(null, owner.getBlockPos(), SoundEvents.ENTITY_PLAYER_LEVELUP,
                SoundCategory.PLAYERS, 0.5F, 1.5F);

        owner.sendMessage(plain("").append(plain(house.name)
                        .formatted(Formatting.GOLD, Formatting.BOLD))
                .append(plain(" jest otwarte.").formatted(Formatting.GRAY)), false);
        owner.sendMessage(plain("Wpłać kasę do skarbca, a potem kliknij automaty PPM "
                + "tą licencją, żeby je podłączyć.").formatted(Formatting.DARK_GRAY), false);
    }

    private static MutableText plain(String text) {
        return Text.literal(text).styled(style -> style.withItalic(false));
    }

    private static MutableText line(String text, Formatting... colours) {
        return plain(text).formatted(colours);
    }
}
