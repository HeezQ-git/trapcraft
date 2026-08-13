package dev.heezq.trapcraft;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

/**
 * The market stall's screen.
 *
 * A vanilla 9x6 container, like every other screen in this mod: the client
 * draws stacks the server sets and computes nothing, which is the only kind of
 * screen that behaves with Polymer items. See docs/TRAPS.md.
 *
 * Two pages -- the shelves, then one shelf's goods -- because six categories
 * of sixty items in one grid is a wall, and a wall is where prices go to be
 * ignored.
 */
public class ShopScreenHandler extends ScreenHandler {
    private static final int ROWS = 6;
    private static final int SIZE = ROWS * 9;
    /** Bottom row is furniture: back, balance, the market's mood. */
    private static final int FOOTER = SIZE - 9;

    private static final int BACK_SLOT = FOOTER;
    private static final int MOOD_SLOT = FOOTER + 4;
    private static final int PURSE_SLOT = FOOTER + 8;
    private static final int PREV_SLOT = FOOTER + 2;
    /** The way through to the coin market, on the exchange page only. */
    private static final int COINS_SLOT = FOOTER + 6;
    private static final int NEXT_SLOT = FOOTER + 6;

    /**
     * Three rows of five, so the shelves read as a shopfront.
     *
     * Sized to CATEGORIES: a category with no slot silently doesn't appear,
     * which is exactly how the enchantments shelf nearly shipped invisible and
     * how The Good Stuff -- the netherite shelf, thirteenth of thirteen -- did
     * ship invisible. Widened to every other column rather than paged: thirteen
     * shelves behind a Next button is thirteen shelves somebody has to remember
     * exist. Room for two more before that argument has to be had again, and
     * the warning below is what will start it.
     */
    private static final int[] SHELF_SPOTS = {
            9, 11, 13, 15, 17,
            18, 20, 22, 24, 26,
            27, 29, 31, 33, 35,
    };
    /** The counter and the exchange desk, off on their own below the shelves. */
    private static final int SELL_SPOT = 38;
    private static final int INVEST_SPOT = 42;

    /** Top-left of the exchange's offer grid: a row per term, a column per stake. */
    private static final int OFFER_ORIGIN = 19;
    /** Open positions run along the row above it, one slip each. */
    private static final int SLIP_ORIGIN = 9;

    private final SimpleInventory display = new SimpleInventory(SIZE);
    private final ServerPlayerEntity shopper;
    private final List<ShopStock.Entry> shown = new ArrayList<>();
    /**
     * Which offer got painted in which slot, written down as it is drawn.
     *
     * The click handler reads this instead of re-deriving the grid from the
     * slot number. A click can only arrive on a screen that has been painted,
     * so it is always filled in -- and a layout that is worked out once cannot
     * disagree with itself about whether you pressed 64e or 4096e. Cleared by
     * blank(), which every page runs before it draws.
     */
    private final java.util.Map<Integer, TrapInvest.Offer> offers = new java.util.HashMap<>();
    private ShopStock.Category open;
    /** Some shelves carry more than one screenful. */
    private int page;
    /** True while the exchange is open instead of a goods shelf. */
    private boolean exchange;

    /**
     * Shops currently open, repainted on every market beat.
     *
     * Without this the board freezes the moment you open it and the whole
     * point -- prices that move while you're deciding -- is invisible.
     */
    private static final List<ShopScreenHandler> OPEN = new ArrayList<>();

    /** Repaint every open shop. Called from the market's beat. */
    public static void refreshAll() {
        // A disconnect doesn't always run onClosed, and a handler nobody can
        // see is a handler that repaints forever.
        OPEN.removeIf(shop -> shop.shopper.isDisconnected());
        for (ShopScreenHandler shop : List.copyOf(OPEN)) {
            shop.repaint();
        }
    }

    private void repaint() {
        if (open != null) {
            showShelf(open, page);
        } else if (!exchange) {
            showShelves();
        }
    }

    @Override
    public void onClosed(net.minecraft.entity.player.PlayerEntity player) {
        OPEN.remove(this);
        super.onClosed(player);
    }

    public ShopScreenHandler(int syncId, PlayerInventory playerInventory) {
        super(ScreenHandlerType.GENERIC_9X6, syncId);
        this.shopper = (ServerPlayerEntity) playerInventory.player;
        OPEN.add(this);

        for (int index = 0; index < SIZE; index++) {
            this.addSlot(new ReadOnlySlot(display, index,
                    8 + (index % 9) * 18, 18 + (index / 9) * 18));
        }
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInventory, col + row * 9 + 9,
                        8 + col * 18, 103 + row * 18 + (ROWS - 4) * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInventory, col, 8 + col * 18, 161 + (ROWS - 4) * 18));
        }

        showShelves();
    }

    // --- pages ----------------------------------------------------------------

    private void showShelves() {
        exchange = false;
        open = null;
        shown.clear();
        blank();

        // Three rows of five, every other column, so the shelves read as a
        // shopfront rather than a list.
        int[] spots = SHELF_SPOTS;
        if (ShopStock.CATEGORIES.size() > spots.length) {
            TrapCraft.LOGGER.warn("shopfront has {} slots for {} categories -- some are hidden",
                    spots.length, ShopStock.CATEGORIES.size());
        }
        for (int i = 0; i < ShopStock.CATEGORIES.size() && i < spots.length; i++) {
            ShopStock.Category category = ShopStock.CATEGORIES.get(i);
            ItemStack icon = new ItemStack(category.icon());
            icon.set(DataComponentTypes.CUSTOM_NAME,
                    plain(category.title()).formatted(category.colour(), Formatting.BOLD));
            icon.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                    line(category.blurb(), Formatting.GRAY),
                    Text.empty(),
                    line("pozycji: " + ShopStock.of(category).size(), Formatting.DARK_GRAY))));
            display.setStack(spots[i], icon);
        }

        ItemStack counter = new ItemStack(Items.HOPPER);
        counter.set(DataComponentTypes.CUSTOM_NAME,
                plain("Lada skupu").formatted(Formatting.YELLOW, Formatting.BOLD));
        counter.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line("Kupuje wszystko, nie tylko rzeczy z listy.", Formatting.GRAY),
                Text.empty(),
                line("Wsyp wszystko i sprzedaj za jednym razem.", Formatting.DARK_GRAY),
                line("Czego nie weźmie, to wróci do ciebie.", Formatting.DARK_GRAY))));
        display.setStack(SELL_SPOT, counter);

        ItemStack desk = new ItemStack(Items.GOLD_INGOT);
        desk.set(DataComponentTypes.CUSTOM_NAME,
                plain("Giełda").formatted(Formatting.GOLD, Formatting.BOLD));
        int open = TrapInvest.of(shopper).size();
        desk.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line("Odłóż szmaragdy i niech pracują.", Formatting.GRAY),
                Text.empty(),
                line(open == 0 ? "Brak lokat" : "otwartych lokat: " + open,
                        Formatting.DARK_GRAY))));
        display.setStack(INVEST_SPOT, desk);

        footer();
        sendContentUpdates();
    }

    /**
     * The exchange: what you hold, and what you can open.
     *
     * Deliberately one screen with no paging. Nine positions is the cap because
     * nine slips is one row, so everything you have riding on the market is
     * visible at once -- money you have to scroll to find is money you forget
     * about. Below them, a row per term and a column per stake size.
     */
    private void showExchange() {
        exchange = true;
        open = null;
        shown.clear();
        blank();

        long beat = TrapMarket.beat();
        ItemStack floor = new ItemStack(Items.DIAMOND);
        floor.set(DataComponentTypes.CUSTOM_NAME,
                plain("Rynek kryptowalut").formatted(Formatting.LIGHT_PURPLE, Formatting.BOLD));
        int worth = TrapCoins.portfolio(shopper, beat);
        floor.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line("Sześć monet. Kupuj i sprzedawaj kiedy chcesz.", Formatting.GRAY),
                line("Niektóre spadają do zera na stałe.", Formatting.RED),
                Text.empty(),
                line(worth > 0 ? "Trzymasz monety warte " + worth + "e."
                                : "Nie masz żadnych monet.",
                        worth > 0 ? Formatting.GREEN : Formatting.DARK_GRAY))));
        display.setStack(COINS_SLOT, floor);

        List<TrapInvest.Position> held = TrapInvest.of(shopper);
        long today = TrapMarket.today(shopper.getServer());
        for (int i = 0; i < held.size(); i++) {
            TrapInvest.Position position = held.get(i);
            boolean ready = position.matured(today);
            ItemStack slip = new ItemStack(ready ? Items.GOLD_INGOT : Items.PAPER);
            slip.set(DataComponentTypes.CUSTOM_NAME,
                    plain(position.principal() + "e na " + position.days() + " dni")
                            .formatted(ready ? Formatting.GOLD : Formatting.WHITE));
            List<Text> lore = new ArrayList<>();
            if (ready) {
                lore.add(line("Zakończona. Wartość ", Formatting.GRAY)
                        .append(plain(TrapInvest.projected(shopper, position) + "e")
                                .formatted(Formatting.GREEN)));
                lore.add(Text.empty());
                lore.add(line("Kliknij, żeby odebrać", Formatting.YELLOW));
            } else {
                lore.add(line("pozostało dni: " + (position.maturesOn() - today),
                        Formatting.GRAY));
                lore.add(Text.empty());
                lore.add(line("Nie da się wypłacić wcześniej.", Formatting.DARK_GRAY));
            }
            slip.set(DataComponentTypes.LORE, new LoreComponent(lore));
            display.setStack(SLIP_ORIGIN + i, slip);
        }

        int purse = TrapMarket.wealthOf(shopper);
        for (TrapInvest.Term term : TrapInvest.Term.values()) {
            for (int size = 0; size < TrapInvest.STAKES.length; size++) {
                int stake = TrapInvest.STAKES[size];
                // The stack count is the ladder: 1, 4, 16, 64 emeralds in the
                // slot for 64e, 256e, 1024e, 4096e. Which rung you are looking
                // at is then readable without hovering anything.
                ItemStack offer = new ItemStack(Items.EMERALD, Math.min(64, stake / 64));
                boolean afford = purse >= stake;
                offer.set(DataComponentTypes.CUSTOM_NAME,
                        plain("Zainwestuj " + stake + "e")
                                .formatted(afford ? Formatting.GREEN : Formatting.DARK_GRAY)
                                .append(plain("  " + term.label).formatted(Formatting.GRAY)));
                offer.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                        line("Zablokowane na " + term.days + " dni.", Formatting.GRAY),
                        line("Im dłużej czekasz, tym więcej płaci,", Formatting.DARK_GRAY),
                        line("i tym więcej, gdy indeks rośnie.", Formatting.DARK_GRAY),
                        Text.empty(),
                        line(afford ? "Może wrócić mniej, niż włożyłeś."
                                        : "Brakuje ci " + (stake - purse) + "e.",
                                Formatting.RED))));
                int slot = OFFER_ORIGIN + term.ordinal() * 9 + size * 2;
                offers.put(slot, new TrapInvest.Offer(stake, term));
                display.setStack(slot, offer);
            }
        }
        footer();
        sendContentUpdates();
    }

    private void showShelf(ShopStock.Category category, int wanted) {
        exchange = false;
        open = category;
        shown.clear();
        shown.addAll(ShopStock.of(category));
        page = Math.max(0, Math.min(wanted, lastPage()));
        blank();

        int from = page * FOOTER;
        for (int i = 0; i + from < shown.size() && i < FOOTER; i++) {
            display.setStack(i, priceTag(shown.get(i + from)));
        }
        footer();
        sendContentUpdates();
    }

    /**
     * One item, priced, with what it does spelled out.
     *
     * The stack shows the bundle size, so what you see in the slot is exactly
     * what lands in your inventory.
     */
    private ItemStack priceTag(ShopStock.Entry entry) {
        int buy = TrapMarket.buyPrice(shopper.getServer(), entry);
        int sell = TrapMarket.sellPrice(shopper.getServer(), entry);
        int move = TrapMarket.movement(shopper.getServer(), entry);
        int held = TrapMarket.bundlesHeld(shopper, entry);

        ItemStack tag = entry.stack();
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain(entry.label())
                        .formatted(Formatting.WHITE)
                        .append(plain("  x" + entry.count()).formatted(Formatting.DARK_GRAY)));

        List<Text> lore = new ArrayList<>();
        // An item that describes itself keeps that description on the shelf.
        // The tag rewrites the lore wholesale, so without this the wands --
        // the one listing where WHAT IT DOES is the thing being decided, not
        // the price -- would be five identical sticks at five different
        // numbers.
        LoreComponent says = tag.get(DataComponentTypes.LORE);
        if (says != null && !says.lines().isEmpty()) {
            lore.addAll(says.lines());
            lore.add(Text.empty());
        }
        float flow = TrapMarket.pressureOf(entry);
        // Prices on the shelf are what you actually hand over, duty included.
        // A ticket that says one number and a till that charges another is how
        // every shop in the world annoys people.
        TrapCity.Duty band = TrapCity.forGoods(entry.category());
        int vat = TrapCity.dutyOn(buy, band);
        int income = TrapCity.dutyOn(sell, TrapCity.Duty.INCOME);
        lore.add(line("Kupno    ", Formatting.DARK_GRAY)
                .append(plain((buy + vat) + "e").formatted(Formatting.GREEN))
                .append(plain(vat == 0 ? "" : "   w tym " + vat + "e "
                        + band.display().toLowerCase(java.util.Locale.ROOT))
                        .formatted(Formatting.DARK_GRAY))
                .append(plain(move == 0 ? "" : move > 0 ? "   +" + move + "%" : "   " + move + "%")
                        .formatted(move > 0 ? Formatting.RED : Formatting.AQUA)));
        lore.add(line("Skup   ", Formatting.DARK_GRAY)
                .append(sell > 0
                        ? plain((sell - income) + "e").formatted(Formatting.GOLD)
                        : plain("tego tu nie skupują").formatted(Formatting.DARK_GRAY))
                .append(plain(sell > 0 && income > 0 ? "   po " + income + "e podatku" : "")
                        .formatted(Formatting.DARK_GRAY)));
        if (Math.abs(flow) > 0.02f) {
            // Order flow is the part of the price a player caused, so say so
            // plainly rather than burying it in the percentage.
            boolean bought = flow > 0;
            String heat = Math.abs(flow) > 0.4f ? (bought ? "Wszyscy kupują" : "Wszyscy wyprzedają")
                    : Math.abs(flow) > 0.15f ? (bought ? "Schodzi szybko" : "Tanieje")
                    : (bought ? "Rusza się" : "Zwalnia");
            lore.add(line("Ruch   ", Formatting.DARK_GRAY)
                    .append(plain(heat).formatted(bought ? Formatting.RED : Formatting.AQUA))
                    .append(plain("  (wyrówna się w kilka minut)").formatted(Formatting.DARK_GRAY)));
        }
        // Somebody in town has this spare and cheaper. The counter is the
        // BACKSTOP -- it never runs out and it never haggles -- so the useful
        // thing it can do is point at a neighbour who will do better, which is
        // also the only reason anybody would walk across a city they built.
        TrapStalls.Stall seller = TrapStalls.sellerOf(shopper.getServer(), entry);
        if (seller != null && !seller.owner().equals(shopper.getUuid())) {
            int there = TrapMath.stallPrice(buy);
            lore.add(line("Taniej u ", Formatting.DARK_GRAY)
                    .append(plain(seller.ownerName() + " na straganie").formatted(Formatting.GOLD))
                    .append(plain("  " + there + "e").formatted(Formatting.GREEN)));
            lore.add(line("           " + seller.pos().getX() + " " + seller.pos().getY()
                    + " " + seller.pos().getZ(), Formatting.DARK_GRAY));
        }

        lore.add(Text.empty());
        lore.add(line("LPM", Formatting.YELLOW).append(plain(" kupuje jedną partię")
                .formatted(Formatting.GRAY)));
        lore.add(line("Shift+LPM", Formatting.YELLOW).append(plain(" kupuje cztery")
                .formatted(Formatting.GRAY)));
        if (sell > 0) {
            lore.add(line("PPM", Formatting.YELLOW)
                    .append(plain(held > 0 ? " sprzedaje jedną partię" : " sprzedaje (nic nie masz)")
                            .formatted(Formatting.GRAY)));
        }
        tag.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return tag;
    }

    /** Zero-based index of the final page of the open shelf. */
    private int lastPage() {
        return shown.isEmpty() ? 0 : (shown.size() - 1) / FOOTER;
    }

    private void footer() {
        if (open != null) {
            if (page > 0) {
                display.setStack(PREV_SLOT, arrow("Poprzednia strona"));
            }
            if (page < lastPage()) {
                display.setStack(NEXT_SLOT, arrow("Następna strona  ("
                        + (page + 2) + "/" + (lastPage() + 1) + ")"));
            }
        }
        if (open != null || exchange) {
            ItemStack back = new ItemStack(Items.ARROW);
            back.set(DataComponentTypes.CUSTOM_NAME,
                    plain("Powrót do półek").formatted(Formatting.WHITE));
            display.setStack(BACK_SLOT, back);
        }

        float index = TrapMarket.index();
        ItemStack mood = new ItemStack(index > 1.15f ? Items.REDSTONE
                : index < 0.9f ? Items.LAPIS_LAZULI : Items.PAPER);
        mood.set(DataComponentTypes.CUSTOM_NAME,
                plain("Rynek").formatted(Formatting.GOLD, Formatting.BOLD));
        mood.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line(index > 1.15f ? "Ceny w górę. Za dużo pieniądza w obiegu."
                        : index < 0.9f ? "Ceny niskie. Pieniądza mało."
                        : "Ceny stabilne.", Formatting.GRAY),
                Text.empty(),
                line("Indeks  " + Math.round(index * 100) + "%", Formatting.DARK_GRAY),
                line("Każdy wydany, wygrany i wypłacony", Formatting.DARK_GRAY),
                line("szmaragd go rusza. Ceny co 30s.", Formatting.DARK_GRAY))));
        display.setStack(MOOD_SLOT, mood);

        int purse = TrapMarket.wealthOf(shopper);
        ItemStack wallet = new ItemStack(Items.EMERALD);
        wallet.set(DataComponentTypes.CUSTOM_NAME,
                plain("Twoja kasa: ").formatted(Formatting.GRAY)
                        .append(plain(purse + "e").formatted(Formatting.GREEN, Formatting.BOLD)));
        wallet.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line("Liczą się szmaragdy i bloki szmaragdów.", Formatting.DARK_GRAY))));
        display.setStack(PURSE_SLOT, wallet);
    }

    private ItemStack arrow(String label) {
        ItemStack stack = new ItemStack(Items.SPECTRAL_ARROW);
        stack.set(DataComponentTypes.CUSTOM_NAME, plain(label).formatted(Formatting.WHITE));
        return stack;
    }

    private void blank() {
        offers.clear();
        ItemStack pane = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
        pane.set(DataComponentTypes.CUSTOM_NAME, Text.empty());
        for (int index = 0; index < SIZE; index++) {
            display.setStack(index, index >= FOOTER ? pane.copy() : ItemStack.EMPTY);
        }
    }

    // --- trading --------------------------------------------------------------

    @Override
    public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity player) {
        if (slotIndex < 0 || slotIndex >= SIZE) {
            return;
        }
        boolean shift = actionType == SlotActionType.QUICK_MOVE;
        boolean right = button == 1;

        if (exchange) {
            exchangeClick(slotIndex);
            return;
        }
        if (open == null) {
            if (slotIndex == INVEST_SPOT) {
                click(1.1F);
                showExchange();
                return;
            }
            if (slotIndex == SELL_SPOT) {
                click(1.3F);
                // Its own screen rather than another page of this one: it
                // needs forty-five real slots, and this handler's are all
                // read-only by design.
                shopper.openHandledScreen(new net.minecraft.screen.SimpleNamedScreenHandlerFactory(
                        (id, inventory, ignored) -> new SellScreenHandler(id, inventory),
                        plain("Lada skupu").formatted(Formatting.GOLD, Formatting.BOLD)));
                return;
            }
            int[] spots = SHELF_SPOTS;
            for (int i = 0; i < spots.length && i < ShopStock.CATEGORIES.size(); i++) {
                if (spots[i] == slotIndex) {
                    click(0.9F);
                    showShelf(ShopStock.CATEGORIES.get(i), 0);
                    return;
                }
            }
            return;
        }
        if (slotIndex == BACK_SLOT) {
            click(0.7F);
            showShelves();
            return;
        }
        if (slotIndex == PREV_SLOT && page > 0) {
            click(0.8F);
            showShelf(open, page - 1);
            return;
        }
        if (slotIndex == NEXT_SLOT && page < lastPage()) {
            click(1.0F);
            showShelf(open, page + 1);
            return;
        }
        int index = page * FOOTER + slotIndex;
        if (slotIndex >= FOOTER || index >= shown.size()) {
            return;
        }

        ShopStock.Entry entry = shown.get(index);
        if (right) {
            sell(entry);
        } else {
            buy(entry, shift ? 4 : 1);
        }
        showShelf(open, page);
    }

    private void exchangeClick(int slotIndex) {
        if (slotIndex == BACK_SLOT) {
            click(0.7F);
            showShelves();
            return;
        }
        if (slotIndex == COINS_SLOT) {
            click(1.3F);
            // Its own screen: six listings with a chart and four buttons each
            // needs the whole grid, and this page is already a list of slips.
            shopper.openHandledScreen(new net.minecraft.screen.SimpleNamedScreenHandlerFactory(
                    (id, inventory, ignored) -> new CoinScreenHandler(id, inventory),
                    plain("Rynek kryptowalut").formatted(Formatting.LIGHT_PURPLE, Formatting.BOLD)));
            return;
        }

        List<TrapInvest.Position> held = TrapInvest.of(shopper);
        int slip = slotIndex - SLIP_ORIGIN;
        if (slip >= 0 && slip < held.size()) {
            TrapInvest.Position position = held.get(slip);
            int paid = TrapInvest.collect(shopper, position);
            if (paid < 0) {
                deny();
                shopper.sendMessage(plain("Ta lokata jeszcze nie dojrzała.")
                        .formatted(Formatting.GRAY), false);
                return;
            }
            int change = paid - position.principal();
            till();
            shopper.sendMessage(plain("Odebrano ").formatted(Formatting.GRAY)
                    .append(plain(paid + "e").formatted(Formatting.GREEN))
                    .append(plain(change >= 0
                                    ? "   zysk " + change + " z wpłaconych " + position.principal()
                                    : "   strata " + (-change) + " z wpłaconych " + position.principal())
                            .formatted(change >= 0 ? Formatting.DARK_GRAY : Formatting.RED)), false);
            showExchange();
            return;
        }

        TrapInvest.Offer picked = offers.get(slotIndex);
        if (picked == null) {
            return;
        }
        TrapInvest.Term term = picked.term();
        int stake = picked.stake();

        if (!TrapInvest.canOpen(shopper)) {
            deny();
            shopper.sendMessage(plain("Masz już tyle lokat, ile giełda przyjmie.")
                    .formatted(Formatting.GRAY), false);
            return;
        }
        if (TrapMarket.wealthOf(shopper) < stake) {
            deny();
            shopper.sendMessage(plain("Potrzebujesz ").formatted(Formatting.GRAY)
                    .append(plain(stake + "e").formatted(Formatting.RED))
                    .append(plain(" przy sobie.").formatted(Formatting.GRAY)), false);
            return;
        }

        TrapMarket.take(shopper, stake);
        TrapInvest.open(shopper, stake, term);
        till();
        shopper.sendMessage(plain("Odłożono ").formatted(Formatting.GRAY)
                .append(plain(stake + "e").formatted(Formatting.GREEN))
                .append(plain(" na " + term.days + " dni. Wróć po odbiór.")
                        .formatted(Formatting.GRAY)), false);
        showExchange();
    }

    private void buy(ShopStock.Entry entry, int lots) {
        int each = TrapMarket.buyPrice(shopper.getServer(), entry);
        // VAT rides on top of the shelf price and the affordability sum has to
        // know about it, or the shop offers four and charges for four and a
        // bit -- which is the one thing a counter must never do.
        TrapCity.Duty band = TrapCity.forGoods(entry.category());
        int withDuty = each + TrapCity.dutyOn(each, band);
        int purse = TrapMarket.wealthOf(shopper);

        // Buy as many as they can afford rather than refusing outright: asking
        // for four and getting three is a better shop than getting nothing.
        int affordable = Math.min(lots, purse / withDuty);
        if (affordable <= 0) {
            deny();
            shopper.sendMessage(plain("Brakuje ci ").formatted(Formatting.GRAY)
                    .append(plain((withDuty - purse) + "e").formatted(Formatting.RED))
                    .append(plain(" na: " + name(entry) + ".").formatted(Formatting.GRAY)), false);
            return;
        }

        int cost = affordable * each;
        TrapMarket.take(shopper, cost);
        TrapLedger.record(shopper, TrapLedger.Source.MARKET, -cost);
        int duty = TrapCity.charge(shopper, cost, band);
        TrapMarket.traded(entry, affordable, true);
        for (int i = 0; i < affordable; i++) {
            shopper.getInventory().offerOrDrop(entry.stack());
        }

        till();
        shopper.sendMessage(plain("Kupiono ").formatted(Formatting.GRAY)
                .append(plain((affordable * entry.count()) + "x ").formatted(Formatting.WHITE))
                .append(plain(name(entry)).formatted(Formatting.WHITE))
                .append(plain(" za ").formatted(Formatting.GRAY))
                .append(plain((cost + duty) + "e").formatted(Formatting.GREEN))
                .append(plain(duty > 0 ? "   w tym " + duty + "e podatku (" + band.display()
                        .toLowerCase(java.util.Locale.ROOT) + ")" : "")
                        .formatted(Formatting.DARK_GRAY))
                .append(plain(affordable < lots ? "   tyle było cię stać" : "")
                        .formatted(Formatting.DARK_GRAY)), false);
    }

    private void sell(ShopStock.Entry entry) {
        int each = TrapMarket.sellPrice(shopper.getServer(), entry);
        if (each <= 0) {
            deny();
            shopper.sendMessage(plain("Nikt tu nie skupuje: " + name(entry) + ".")
                    .formatted(Formatting.GRAY), false);
            return;
        }
        if (TrapMarket.bundlesHeld(shopper, entry) < 1) {
            deny();
            shopper.sendMessage(plain("Potrzebujesz ").formatted(Formatting.GRAY)
                    .append(plain(entry.count() + "x " + name(entry)).formatted(Formatting.WHITE))
                    .append(plain(" na jedną partię.").formatted(Formatting.GRAY)), false);
            return;
        }

        TrapMarket.takeGoods(shopper, entry, 1);
        TrapMarket.pay(shopper, each);
        TrapLedger.record(shopper, TrapLedger.Source.MARKET, each);
        // Paid gross and taxed after, which is the same emerald count as
        // paying net but keeps the market index honest: the counter really did
        // create `each`, and the city really did take a slice of it.
        int duty = TrapCity.charge(shopper, each, TrapCity.Duty.INCOME);
        TrapMarket.traded(entry, 1, false);

        till();
        shopper.sendMessage(plain("Sprzedano ").formatted(Formatting.GRAY)
                .append(plain(entry.count() + "x ").formatted(Formatting.WHITE))
                .append(plain(name(entry)).formatted(Formatting.WHITE))
                .append(plain(" za ").formatted(Formatting.GRAY))
                .append(plain((each - duty) + "e").formatted(Formatting.GOLD))
                .append(plain(duty > 0 ? "   po " + duty + "e podatku dochodowego" : "")
                        .formatted(Formatting.DARK_GRAY)), false);
    }

    // --- trimmings ------------------------------------------------------------

    private void till() {
        shopper.getWorld().playSound(null, shopper.getBlockPos(),
                SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), SoundCategory.PLAYERS, 0.6F, 1.6F);
        shopper.getWorld().playSound(null, shopper.getBlockPos(),
                SoundEvents.ENTITY_VILLAGER_YES, SoundCategory.PLAYERS, 0.4F, 1.2F);
    }

    private void click(float pitch) {
        shopper.getWorld().playSound(null, shopper.getBlockPos(),
                SoundEvents.UI_BUTTON_CLICK.value(), SoundCategory.PLAYERS, 0.5F, pitch);
    }

    private void deny() {
        shopper.getWorld().playSound(null, shopper.getBlockPos(),
                SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), SoundCategory.PLAYERS, 0.7F, 0.6F);
    }

    private static String name(ShopStock.Entry entry) {
        return entry.label();
    }

    private static MutableText plain(String text) {
        return Text.literal(text).styled(style -> style.withItalic(false));
    }

    private static MutableText line(String text, Formatting colour) {
        return plain(text).formatted(colour);
    }

    @Override
    public ItemStack quickMove(PlayerEntity player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canUse(PlayerEntity player) {
        return player == shopper;
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
