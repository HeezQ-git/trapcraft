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
     * Three rows of four, so the shelves read as a shopfront.
     *
     * Sized to CATEGORIES: add a thirteenth category and it silently wouldn't
     * appear, which is exactly how the enchantments shelf nearly shipped
     * invisible. Widened from three columns when the garden shelf went in and
     * there was nowhere to put it.
     */
    private static final int[] SHELF_SPOTS = {
            10, 12, 14, 16,
            19, 21, 23, 25,
            28, 30, 32, 34,
    };
    /** The counter and the exchange desk, off on their own below the shelves. */
    private static final int SELL_SPOT = 38;
    private static final int INVEST_SPOT = 42;

    private final SimpleInventory display = new SimpleInventory(SIZE);
    private final ServerPlayerEntity shopper;
    private final List<ShopStock.Entry> shown = new ArrayList<>();
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

        // Two rows of three, centred, so the shelves read as a shopfront rather
        // than a list.
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
                    line(ShopStock.of(category).size() + " lines", Formatting.DARK_GRAY))));
            display.setStack(spots[i], icon);
        }

        ItemStack counter = new ItemStack(Items.HOPPER);
        counter.set(DataComponentTypes.CUSTOM_NAME,
                plain("The Counter").formatted(Formatting.YELLOW, Formatting.BOLD));
        counter.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line("Sell anything, not just what's listed.", Formatting.GRAY),
                Text.empty(),
                line("Tip the lot in and sell it in one go.", Formatting.DARK_GRAY),
                line("Whatever it won't take comes back.", Formatting.DARK_GRAY))));
        display.setStack(SELL_SPOT, counter);

        ItemStack desk = new ItemStack(Items.GOLD_INGOT);
        desk.set(DataComponentTypes.CUSTOM_NAME,
                plain("The Exchange").formatted(Formatting.GOLD, Formatting.BOLD));
        int open = TrapInvest.of(shopper).size();
        desk.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line("Put emeralds away and let them work.", Formatting.GRAY),
                Text.empty(),
                line(open == 0 ? "Nothing invested" : open + " position(s) open",
                        Formatting.DARK_GRAY))));
        display.setStack(INVEST_SPOT, desk);

        footer();
        sendContentUpdates();
    }

    /**
     * The exchange: what you hold, and what you can open.
     *
     * Deliberately one screen with no paging. Five positions is the cap, so
     * everything you have riding on the market is visible at once -- money you
     * have to scroll to find is money you forget about.
     */
    private void showExchange() {
        exchange = true;
        open = null;
        shown.clear();
        blank();

        long beat = TrapMarket.beat();
        ItemStack floor = new ItemStack(Items.DIAMOND);
        floor.set(DataComponentTypes.CUSTOM_NAME,
                plain("The Coin Market").formatted(Formatting.LIGHT_PURPLE, Formatting.BOLD));
        int worth = TrapCoins.portfolio(shopper, beat);
        floor.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line("Six listings. Buy in, sell whenever.", Formatting.GRAY),
                line("Some of them go to nothing.", Formatting.RED),
                Text.empty(),
                line(worth > 0 ? "You're holding " + worth + "e of coin."
                                : "You're not in anything.",
                        worth > 0 ? Formatting.GREEN : Formatting.DARK_GRAY))));
        display.setStack(COINS_SLOT, floor);

        List<TrapInvest.Position> held = TrapInvest.of(shopper);
        long today = TrapMarket.today(shopper.getServer());
        for (int i = 0; i < held.size(); i++) {
            TrapInvest.Position position = held.get(i);
            boolean ready = position.matured(today);
            ItemStack slip = new ItemStack(ready ? Items.GOLD_INGOT : Items.PAPER);
            slip.set(DataComponentTypes.CUSTOM_NAME,
                    plain(position.principal() + "e for " + position.days() + "d")
                            .formatted(ready ? Formatting.GOLD : Formatting.WHITE));
            List<Text> lore = new ArrayList<>();
            if (ready) {
                lore.add(line("Matured. Worth ", Formatting.GRAY)
                        .append(plain(TrapInvest.projected(shopper, position) + "e")
                                .formatted(Formatting.GREEN)));
                lore.add(Text.empty());
                lore.add(line("Click to collect", Formatting.YELLOW));
            } else {
                lore.add(line((position.maturesOn() - today) + " day(s) to go",
                        Formatting.GRAY));
                lore.add(Text.empty());
                lore.add(line("No early withdrawals.", Formatting.DARK_GRAY));
            }
            slip.set(DataComponentTypes.LORE, new LoreComponent(lore));
            display.setStack(9 + i, slip);
        }

        int spot = 28;
        for (TrapInvest.Term term : TrapInvest.Term.values()) {
            for (int stake : new int[]{64, 256}) {
                ItemStack offer = new ItemStack(Items.EMERALD, 1);
                offer.set(DataComponentTypes.CUSTOM_NAME,
                        plain("Invest " + stake + "e").formatted(Formatting.GREEN)
                                .append(plain("  " + term.label).formatted(Formatting.GRAY)));
                offer.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                        line("Locked for " + term.days + " day(s).", Formatting.GRAY),
                        line("Pays more the longer you wait,", Formatting.DARK_GRAY),
                        line("and more if the market rises.", Formatting.DARK_GRAY),
                        Text.empty(),
                        line("It can come back smaller.", Formatting.RED))));
                display.setStack(spot++, offer);
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
        float flow = TrapMarket.pressureOf(entry);
        lore.add(line("Buy    ", Formatting.DARK_GRAY)
                .append(plain(buy + "e").formatted(Formatting.GREEN))
                .append(plain(move == 0 ? "" : move > 0 ? "   +" + move + "%" : "   " + move + "%")
                        .formatted(move > 0 ? Formatting.RED : Formatting.AQUA)));
        lore.add(line("Sell   ", Formatting.DARK_GRAY)
                .append(sell > 0
                        ? plain(sell + "e").formatted(Formatting.GOLD)
                        : plain("not bought here").formatted(Formatting.DARK_GRAY)));
        if (Math.abs(flow) > 0.02f) {
            // Order flow is the part of the price a player caused, so say so
            // plainly rather than burying it in the percentage.
            boolean bought = flow > 0;
            String heat = Math.abs(flow) > 0.4f ? (bought ? "Everyone's buying" : "Everyone's dumping")
                    : Math.abs(flow) > 0.15f ? (bought ? "Selling fast" : "Going cheap")
                    : (bought ? "Moving" : "Slowing");
            lore.add(line("Flow   ", Formatting.DARK_GRAY)
                    .append(plain(heat).formatted(bought ? Formatting.RED : Formatting.AQUA))
                    .append(plain("  (settles in a few minutes)").formatted(Formatting.DARK_GRAY)));
        }
        // Somebody in town has this spare and cheaper. The counter is the
        // BACKSTOP -- it never runs out and it never haggles -- so the useful
        // thing it can do is point at a neighbour who will do better, which is
        // also the only reason anybody would walk across a city they built.
        TrapStalls.Stall seller = TrapStalls.sellerOf(shopper.getServer(), entry);
        if (seller != null && !seller.owner().equals(shopper.getUuid())) {
            int there = TrapMath.stallPrice(buy);
            lore.add(line("Cheaper at ", Formatting.DARK_GRAY)
                    .append(plain(seller.ownerName() + "'s stall").formatted(Formatting.GOLD))
                    .append(plain("  " + there + "e").formatted(Formatting.GREEN)));
            lore.add(line("           " + seller.pos().getX() + " " + seller.pos().getY()
                    + " " + seller.pos().getZ(), Formatting.DARK_GRAY));
        }

        lore.add(Text.empty());
        lore.add(line("Click", Formatting.YELLOW).append(plain(" to buy one lot")
                .formatted(Formatting.GRAY)));
        lore.add(line("Shift-click", Formatting.YELLOW).append(plain(" to buy four")
                .formatted(Formatting.GRAY)));
        if (sell > 0) {
            lore.add(line("Right-click", Formatting.YELLOW)
                    .append(plain(held > 0 ? " to sell one lot" : " to sell (you have none)")
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
                display.setStack(PREV_SLOT, arrow("Previous page"));
            }
            if (page < lastPage()) {
                display.setStack(NEXT_SLOT, arrow("Next page  ("
                        + (page + 2) + "/" + (lastPage() + 1) + ")"));
            }
        }
        if (open != null || exchange) {
            ItemStack back = new ItemStack(Items.ARROW);
            back.set(DataComponentTypes.CUSTOM_NAME,
                    plain("Back to the shelves").formatted(Formatting.WHITE));
            display.setStack(BACK_SLOT, back);
        }

        float index = TrapMarket.index();
        ItemStack mood = new ItemStack(index > 1.15f ? Items.REDSTONE
                : index < 0.9f ? Items.LAPIS_LAZULI : Items.PAPER);
        mood.set(DataComponentTypes.CUSTOM_NAME,
                plain("The Market").formatted(Formatting.GOLD, Formatting.BOLD));
        mood.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line(index > 1.15f ? "Prices are up. Too much money about."
                        : index < 0.9f ? "Prices are soft. Money's tight."
                        : "Prices are steady.", Formatting.GRAY),
                Text.empty(),
                line("Index  " + Math.round(index * 100) + "%", Formatting.DARK_GRAY),
                line("Every emerald spent, won or paid out", Formatting.DARK_GRAY),
                line("moves this. Prices step every 30s.", Formatting.DARK_GRAY))));
        display.setStack(MOOD_SLOT, mood);

        int purse = TrapMarket.wealthOf(shopper);
        ItemStack wallet = new ItemStack(Items.EMERALD);
        wallet.set(DataComponentTypes.CUSTOM_NAME,
                plain("Your purse: ").formatted(Formatting.GRAY)
                        .append(plain(purse + "e").formatted(Formatting.GREEN, Formatting.BOLD)));
        wallet.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line("Emeralds and emerald blocks both count.", Formatting.DARK_GRAY))));
        display.setStack(PURSE_SLOT, wallet);
    }

    private ItemStack arrow(String label) {
        ItemStack stack = new ItemStack(Items.SPECTRAL_ARROW);
        stack.set(DataComponentTypes.CUSTOM_NAME, plain(label).formatted(Formatting.WHITE));
        return stack;
    }

    private void blank() {
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
                        plain("The Counter").formatted(Formatting.GOLD, Formatting.BOLD)));
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
                    plain("The Coin Market").formatted(Formatting.LIGHT_PURPLE, Formatting.BOLD)));
            return;
        }

        List<TrapInvest.Position> held = TrapInvest.of(shopper);
        int slip = slotIndex - 9;
        if (slip >= 0 && slip < held.size()) {
            TrapInvest.Position position = held.get(slip);
            int paid = TrapInvest.collect(shopper, position);
            if (paid < 0) {
                deny();
                shopper.sendMessage(plain("That one isn't ready yet.")
                        .formatted(Formatting.GRAY), false);
                return;
            }
            int change = paid - position.principal();
            till();
            shopper.sendMessage(plain("Collected ").formatted(Formatting.GRAY)
                    .append(plain(paid + "e").formatted(Formatting.GREEN))
                    .append(plain(change >= 0
                                    ? "   up " + change + " on the " + position.principal() + " you put in"
                                    : "   down " + (-change) + " on the " + position.principal() + " you put in")
                            .formatted(change >= 0 ? Formatting.DARK_GRAY : Formatting.RED)), false);
            showExchange();
            return;
        }

        int offer = slotIndex - 28;
        TrapInvest.Term[] terms = TrapInvest.Term.values();
        if (offer < 0 || offer >= terms.length * 2) {
            return;
        }
        TrapInvest.Term term = terms[offer / 2];
        int stake = (offer % 2 == 0) ? 64 : 256;

        if (!TrapInvest.canOpen(shopper)) {
            deny();
            shopper.sendMessage(plain("You've got as much riding on the market as they'll take.")
                    .formatted(Formatting.GRAY), false);
            return;
        }
        if (TrapMarket.wealthOf(shopper) < stake) {
            deny();
            shopper.sendMessage(plain("You'd need ").formatted(Formatting.GRAY)
                    .append(plain(stake + "e").formatted(Formatting.RED))
                    .append(plain(" in hand for that.").formatted(Formatting.GRAY)), false);
            return;
        }

        TrapMarket.take(shopper, stake);
        TrapInvest.open(shopper, stake, term);
        till();
        shopper.sendMessage(plain("Put ").formatted(Formatting.GRAY)
                .append(plain(stake + "e").formatted(Formatting.GREEN))
                .append(plain(" away for " + term.days + " day(s). Come back for it.")
                        .formatted(Formatting.GRAY)), false);
        showExchange();
    }

    private void buy(ShopStock.Entry entry, int lots) {
        int each = TrapMarket.buyPrice(shopper.getServer(), entry);
        int purse = TrapMarket.wealthOf(shopper);

        // Buy as many as they can afford rather than refusing outright: asking
        // for four and getting three is a better shop than getting nothing.
        int affordable = Math.min(lots, purse / each);
        if (affordable <= 0) {
            deny();
            shopper.sendMessage(plain("You're ").formatted(Formatting.GRAY)
                    .append(plain((each - purse) + "e").formatted(Formatting.RED))
                    .append(plain(" short of a " + name(entry) + ".").formatted(Formatting.GRAY)), false);
            return;
        }

        int cost = affordable * each;
        TrapMarket.take(shopper, cost);
        TrapLedger.record(shopper, TrapLedger.Source.MARKET, -cost);
        TrapMarket.traded(entry, affordable, true);
        for (int i = 0; i < affordable; i++) {
            shopper.getInventory().offerOrDrop(entry.stack());
        }

        till();
        shopper.sendMessage(plain("Bought ").formatted(Formatting.GRAY)
                .append(plain((affordable * entry.count()) + "x ").formatted(Formatting.WHITE))
                .append(plain(name(entry)).formatted(Formatting.WHITE))
                .append(plain(" for ").formatted(Formatting.GRAY))
                .append(plain(cost + "e").formatted(Formatting.GREEN))
                .append(plain(affordable < lots ? "   that's all you could afford" : "")
                        .formatted(Formatting.DARK_GRAY)), false);
    }

    private void sell(ShopStock.Entry entry) {
        int each = TrapMarket.sellPrice(shopper.getServer(), entry);
        if (each <= 0) {
            deny();
            shopper.sendMessage(plain("Nobody's buying " + name(entry) + " here.")
                    .formatted(Formatting.GRAY), false);
            return;
        }
        if (TrapMarket.bundlesHeld(shopper, entry) < 1) {
            deny();
            shopper.sendMessage(plain("You'd need ").formatted(Formatting.GRAY)
                    .append(plain(entry.count() + "x " + name(entry)).formatted(Formatting.WHITE))
                    .append(plain(" to sell a lot.").formatted(Formatting.GRAY)), false);
            return;
        }

        TrapMarket.takeGoods(shopper, entry, 1);
        TrapMarket.pay(shopper, each);
        TrapLedger.record(shopper, TrapLedger.Source.MARKET, each);
        TrapMarket.traded(entry, 1, false);

        till();
        shopper.sendMessage(plain("Sold ").formatted(Formatting.GRAY)
                .append(plain(entry.count() + "x ").formatted(Formatting.WHITE))
                .append(plain(name(entry)).formatted(Formatting.WHITE))
                .append(plain(" for ").formatted(Formatting.GRAY))
                .append(plain(each + "e").formatted(Formatting.GOLD)), false);
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
