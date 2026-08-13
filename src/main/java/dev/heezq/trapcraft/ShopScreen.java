package dev.heezq.trapcraft;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

/**
 * The back office of one shop.
 *
 *   [register] . [prices] . [shelves] . . . [stock..]
 *   [what's on the shelves, priced as the town sees it]
 *
 * The takings come out on open, like the mailbox's rent, because a screen
 * that makes you find a second button to collect your own money is a screen
 * doing paperwork at somebody.
 */
public class ShopScreen extends ScreenHandler {
    private static final int SIZE = 27;
    private static final int TILL_SLOT = 0;
    private static final int PRICE_SLOT = 2;
    private static final int SHELVES_SLOT = 4;
    private static final int STAFF_SLOT = 6;
    private static final int LINES_FROM = 9;

    private final SimpleInventory display = new SimpleInventory(SIZE);
    private final ServerPlayerEntity who;
    private final TrapShops.Shop shop;
    /** Which page the bottom two rows are showing. */
    private boolean listingShelves;
    /** What each row on the shelves page points at. See StallScreenHandler. */
    private final List<TrapShops.Shelf> rows = new ArrayList<>();

    public ShopScreen(int syncId, PlayerInventory playerInventory, TrapShops.Shop shop) {
        super(ScreenHandlerType.GENERIC_9X3, syncId);
        this.who = (ServerPlayerEntity) playerInventory.player;
        this.shop = shop;

        for (int index = 0; index < SIZE; index++) {
            this.addSlot(new ReadOnlySlot(display, index,
                    8 + (index % 9) * 18, 18 + (index / 9) * 18));
        }
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInventory, col + row * 9 + 9,
                        8 + col * 18, 84 + row * 18));
            }
        }
        int takings = TrapShops.collect(who, shop);
        if (takings > 0) {
            who.sendMessage(Text.literal("Kasa: ").formatted(Formatting.DARK_GRAY)
                    .append(Text.literal("+" + takings + "e").formatted(Formatting.GREEN)),
                    false);
        }
        paint();
    }

    private void paint() {
        ItemStack filler = new ItemStack(Items.BLACK_STAINED_GLASS_PANE);
        filler.set(DataComponentTypes.CUSTOM_NAME, plain(" "));
        for (int index = 0; index < SIZE; index++) {
            display.setStack(index, filler.copy());
        }
        display.setStack(TILL_SLOT, till());
        display.setStack(PRICE_SLOT, prices());
        display.setStack(SHELVES_SLOT, shelves());
        display.setStack(STAFF_SLOT, staff());

        rows.clear();
        if (listingShelves) {
            List<TrapShops.Shelf> mine = TrapShops.shelvesOf(shop);
            for (int i = 0; i < mine.size() && LINES_FROM + i < SIZE; i++) {
                display.setStack(LINES_FROM + i, shelfRow(mine.get(i)));
                rows.add(mine.get(i));
            }
            if (mine.isEmpty()) {
                display.setStack(LINES_FROM, noShelves());
            }
        } else {
            List<TrapShops.Line> lines = TrapShops.onSale(who.getServer(),
                    (ServerWorld) who.getWorld(), shop);
            for (int i = 0; i < lines.size() && LINES_FROM + i < SIZE; i++) {
                display.setStack(LINES_FROM + i, priced(lines.get(i)));
            }
            if (lines.isEmpty()) {
                display.setStack(LINES_FROM, empty());
            }
        }
        sendContentUpdates();
    }

    /**
     * One counter: where it is, how far, and whether anything is under it.
     *
     * This is the whole answer to "which shelves are connected". The question
     * was never control -- shelves join the nearest till on their own and that
     * is deliberate -- it was that you could not SEE which ones had, and an
     * empty shelf looks identical to one belonging to somebody else's shop.
     */
    private ItemStack shelfRow(TrapShops.Shelf shelf) {
        ServerWorld world = (ServerWorld) who.getWorld();
        int stocked = count(TrapBoxes.at(world, shelf.pos()))
                + count(TrapBoxes.at(world, shelf.pos().down()));
        int away = (int) Math.round(Math.sqrt(shop.pos().getSquaredDistance(shelf.pos())));
        ItemStack tag = new ItemStack(TrapContent.marketShelfItem);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain(shelf.pos().getX() + " " + shelf.pos().getY() + " " + shelf.pos().getZ())
                        .formatted(Formatting.WHITE, Formatting.BOLD));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line(away + " bloków od kasy", Formatting.GRAY),
                line(stocked == 0 ? "Pusta" : "przedmiotów: " + stocked,
                        stocked == 0 ? Formatting.RED : Formatting.GREEN),
                Text.empty(),
                line("Kliknij, żeby ją podświetlić.", Formatting.YELLOW))));
        return tag;
    }

    /** What is in a container, counting nothing for one that isn't there. */
    private static int count(Inventory box) {
        int items = 0;
        for (int slot = 0; box != null && slot < box.size(); slot++) {
            items += box.getStack(slot).getCount();
        }
        return items;
    }

    private ItemStack noShelves() {
        ItemStack tag = new ItemStack(Items.BARRIER);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Brak półek").formatted(Formatting.RED, Formatting.BOLD));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line("Postaw półki w promieniu " + TrapShops.REACH + " bloków", Formatting.GRAY),
                line("od tej kasy, a same się podłączą.", Formatting.GRAY))));
        return tag;
    }

    private ItemStack till() {
        ItemStack tag = new ItemStack(Items.EMERALD_BLOCK);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain(shop.name()).formatted(Formatting.GOLD, Formatting.BOLD));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line("sprzedano: " + shop.sold() + ", obrót: " + shop.turnover() + "e",
                        Formatting.GRAY),
                Text.empty(),
                line("Kasa opróżnia się sama, kiedy to", Formatting.DARK_GRAY),
                line("otwierasz. To twoje pieniądze.", Formatting.DARK_GRAY),
                Text.empty(),
                line("Obrót to tyle, ile urząd skarbowy", Formatting.DARK_GRAY),
                line("uwierzy na temat reszty twojej kasy.", Formatting.DARK_GRAY),
                Text.empty(),
                line("Kliknij, trzymając przedmiot nazwany na", Formatting.YELLOW),
                line("kowadle, żeby zmienić nazwę sklepu.", Formatting.YELLOW))));
        return tag;
    }

    private ItemStack prices() {
        ItemStack tag = new ItemStack(Items.GOLD_NUGGET);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Ceny").formatted(Formatting.AQUA, Formatting.BOLD)
                        .append(plain("   " + shop.markupName()).formatted(Formatting.WHITE)));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line(shop.markup() + "% ceny, jakiej spodziewają się mieszkańcy.", Formatting.GRAY),
                Text.empty(),
                line("Tanio: przychodzi więcej ludzi.", Formatting.DARK_GRAY),
                line("Drogo: każdy zostawia więcej.", Formatting.DARK_GRAY),
                Text.empty(),
                line("Kliknij, żeby zmienić.", Formatting.YELLOW))));
        return tag;
    }

    private ItemStack shelves() {
        int count = TrapShops.shelvesOf(shop).size();
        ItemStack tag = new ItemStack(count > 0 ? TrapContent.marketShelfItem : Items.GRAY_DYE);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Półki").formatted(count > 0 ? Formatting.WHITE : Formatting.RED,
                        Formatting.BOLD)
                        .append(plain("   " + count).formatted(Formatting.GRAY)));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line(count > 0 ? "Wszystkie w promieniu " + TrapShops.REACH + " bloków od kasy."
                        : "Żadnej w promieniu " + TrapShops.REACH + " bloków. Nie ma czego sprzedawać.",
                        count > 0 ? Formatting.GRAY : Formatting.RED),
                Text.empty(),
                line("Podłączają się same -- półka należy", Formatting.DARK_GRAY),
                line("do najbliższej kasy.", Formatting.DARK_GRAY),
                Text.empty(),
                line("Otwórz półkę i włóż towar -- każda ma", Formatting.DARK_GRAY),
                line("własny zapas. Skrzynie pod nimi też się liczą.", Formatting.DARK_GRAY),
                Text.empty(),
                line(listingShelves ? "Kliknij, żeby zobaczyć towar."
                        : "Kliknij, żeby wypisać półki.", Formatting.YELLOW))));
        return tag;
    }

    private ItemStack staff() {
        boolean on = shop.staffed();
        ItemStack tag = new ItemStack(on ? Items.VILLAGER_SPAWN_EGG : Items.GRAY_DYE);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Sprzedawca").formatted(on ? Formatting.GREEN : Formatting.GRAY,
                        Formatting.BOLD)
                        .append(plain(on ? "   za ladą" : "   brak")
                                .formatted(Formatting.WHITE)));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line(TrapShops.KEEPER_WAGE + "e dziennie, z kasy sklepu.", Formatting.GRAY),
                Text.empty(),
                line("Stoi za ladą i sklep przyciąga", Formatting.DARK_GRAY),
                line("dużo więcej klientów.", Formatting.DARK_GRAY),
                Text.empty(),
                line("Sklep handluje, kiedy jesteś gdzie", Formatting.DARK_GRAY),
                line("indziej na serwerze -- ale NIE po", Formatting.DARK_GRAY),
                line("twoim wylogowaniu.", Formatting.DARK_GRAY),
                Text.empty(),
                line(on ? "Kliknij, żeby go zwolnić." : "Kliknij, żeby zatrudnić.", Formatting.YELLOW))));
        return tag;
    }

    private ItemStack priced(TrapShops.Line entry) {
        ItemStack tag = entry.sample().copy();
        int duty = TrapCity.dutyOn(entry.price(), entry.duty());
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain(entry.count() + "x " + entry.label()).formatted(Formatting.WHITE));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line("Klient płaci  ", Formatting.DARK_GRAY)
                        .append(plain((entry.price() + duty) + "e").formatted(Formatting.GREEN)),
                line("Ty dostajesz  ", Formatting.DARK_GRAY)
                        .append(plain(entry.price() + "e").formatted(Formatting.WHITE))
                        .append(plain(duty > 0 ? "   " + duty + "e podatku" : "")
                                .formatted(Formatting.DARK_GRAY)),
                Text.empty(),
                line(entry.duty() == TrapCity.Duty.LUXURY
                                ? "Sprzedane przez ladę: czyste i zgłoszone."
                                : "Zwykły towar.",
                        Formatting.DARK_GRAY))));
        return tag;
    }

    private ItemStack empty() {
        ItemStack tag = new ItemStack(Items.BARRIER);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Półki są puste").formatted(Formatting.RED, Formatting.BOLD));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line("Kliknij PPM którąś ze swoich półek", Formatting.GRAY),
                line("it. It holds stock like a chest.", Formatting.GRAY),
                Text.empty(),
                line("Jedzenie, bloki, narzędzia -- a także skręty,", Formatting.DARK_GRAY),
                line("susz i proszek: legalnie i z podatkiem.", Formatting.DARK_GRAY))));
        return tag;
    }

    @Override
    public void onSlotClick(int index, int button, SlotActionType type, PlayerEntity clicker) {
        if (index < 0 || index >= SIZE) {
            super.onSlotClick(index, button, type, clicker);
            return;
        }
        if (index == PRICE_SLOT) {
            TrapShops.repricePrices(shop);
            who.getWorld().playSound(null, who.getBlockPos(),
                    SoundEvents.UI_BUTTON_CLICK.value(), SoundCategory.PLAYERS, 0.7F, 1.3F);
            paint();
            return;
        }
        if (index == TILL_SLOT) {
            Text named = who.getMainHandStack().get(DataComponentTypes.CUSTOM_NAME);
            if (named == null || named.getString().isBlank()) {
                who.sendMessage(plain("Weź przedmiot nazwany na kowadle i kliknij "
                        + "tutaj, żeby nazwać sklep.").formatted(Formatting.GRAY), true);
            } else {
                TrapShops.rename(shop, named.getString());
                who.getWorld().playSound(null, who.getBlockPos(),
                        SoundEvents.BLOCK_ANVIL_USE, SoundCategory.PLAYERS, 0.6F, 1.4F);
                who.sendMessage(plain("Now trading as ").formatted(Formatting.GRAY)
                        .append(plain(shop.name()).formatted(Formatting.GOLD)), true);
            }
            paint();
            return;
        }
        if (index == STAFF_SLOT) {
            who.sendMessage(Text.literal(TrapShops.staff(who, shop))
                    .formatted(Formatting.GRAY), false);
            who.getWorld().playSound(null, who.getBlockPos(),
                    SoundEvents.ENTITY_VILLAGER_YES, SoundCategory.PLAYERS, 0.7F, 1.0F);
            paint();
            return;
        }
        if (index == SHELVES_SLOT) {
            listingShelves = !listingShelves;
            who.getWorld().playSound(null, who.getBlockPos(),
                    SoundEvents.ITEM_BOOK_PAGE_TURN, SoundCategory.PLAYERS, 0.8F, 1.0F);
            paint();
            return;
        }
        if (listingShelves && index >= LINES_FROM && index - LINES_FROM < rows.size()) {
            TrapShops.Shelf shelf = rows.get(index - LINES_FROM);
            ServerWorld world = (ServerWorld) who.getWorld();
            world.spawnParticles(ParticleTypes.END_ROD, shelf.pos().getX() + 0.5,
                    shelf.pos().getY() + 1.2, shelf.pos().getZ() + 0.5, 30, 0.3, 0.6, 0.3, 0.02);
            world.playSound(null, shelf.pos(), SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(),
                    SoundCategory.BLOCKS, 1.0F, 1.6F);
        }
    }

    @Override
    public ItemStack quickMove(PlayerEntity player, int index) {
        if (index < SIZE) {
            onSlotClick(index, 0, SlotActionType.QUICK_MOVE, player);
        }
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canUse(PlayerEntity player) {
        return player == who;
    }

    private static MutableText plain(String text) {
        return Text.literal(text).styled(style -> style.withItalic(false));
    }

    private static MutableText line(String text, Formatting... colours) {
        return plain(text).formatted(colours);
    }

    private static class ReadOnlySlot extends Slot {
        ReadOnlySlot(Inventory inventory, int index, int x, int y) {
            super(inventory, index, x, y);
        }

        @Override
        public boolean canInsert(ItemStack stack) {
            return false;
        }

        @Override
        public boolean canTakeItems(PlayerEntity player) {
            return false;
        }
    }
}
