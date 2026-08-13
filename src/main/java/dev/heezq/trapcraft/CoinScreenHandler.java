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
 * The trading floor.
 *
 * One row per coin: the listing on the left, your position in the middle, buy
 * and sell on the right. Everything a decision needs is on the row -- price,
 * where it has been, what you paid, what you are up or down -- because a market
 * screen that makes you remember your own cost basis is a spreadsheet.
 *
 *   col 0    the coin, its risk, and an hour of chart
 *   col 2    what you hold and what it's worth
 *   col 4-6  buy small, buy big, buy and lock
 *   col 8    sell the lot
 */
public class CoinScreenHandler extends ScreenHandler {
    private static final int ROWS = 6;
    private static final int SIZE = ROWS * 9;
    private static final int FOOTER = SIZE - 9;

    private static final int COIN_COL = 0;
    private static final int HOLDING_COL = 2;
    private static final int BUY_COL = 4;
    private static final int BIG_COL = 5;
    private static final int LOCK_COL = 6;
    private static final int SELL_COL = 8;

    private static final int BACK_SLOT = FOOTER;
    private static final int SPEND_SLOT = FOOTER + 4;
    private static final int PURSE_SLOT = FOOTER + 8;

    /** What one click of "buy" spends. The big button is eight of these. */
    private static final int[] SPENDS = {32, 128, 512, 2048};
    private static final int BIG = 8;

    private final SimpleInventory display = new SimpleInventory(SIZE);
    private final ServerPlayerEntity trader;
    private int spendChoice = 1;

    public CoinScreenHandler(int syncId, PlayerInventory playerInventory) {
        super(ScreenHandlerType.GENERIC_9X6, syncId);
        this.trader = (ServerPlayerEntity) playerInventory.player;

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
        paint();
    }

    // --- the board ------------------------------------------------------------

    private void paint() {
        ItemStack filler = new ItemStack(Items.BLACK_STAINED_GLASS_PANE);
        filler.set(DataComponentTypes.CUSTOM_NAME, plain(" "));
        for (int index = 0; index < SIZE; index++) {
            display.setStack(index, filler.copy());
        }

        long beat = TrapMarket.beat();
        for (int row = 0; row < TrapCoins.BOARD.size() && row < ROWS - 1; row++) {
            TrapCoins.Coin coin = TrapCoins.BOARD.get(row);
            TrapCoins.Holding mine = TrapCoins.held(trader, coin);
            display.setStack(row * 9 + COIN_COL, listing(coin, beat));
            display.setStack(row * 9 + HOLDING_COL, position(coin, mine, beat));
            display.setStack(row * 9 + BUY_COL, buyTag(coin, beat, SPENDS[spendChoice], false));
            display.setStack(row * 9 + BIG_COL, buyTag(coin, beat, SPENDS[spendChoice] * BIG, false));
            display.setStack(row * 9 + LOCK_COL, buyTag(coin, beat, SPENDS[spendChoice], true));
            display.setStack(row * 9 + SELL_COL, sellTag(coin, mine, beat));
        }

        display.setStack(BACK_SLOT, button(Items.ARROW, "Powrót do lady", Formatting.GRAY));
        display.setStack(SPEND_SLOT, spendTag(beat));
        display.setStack(PURSE_SLOT, purseTag());
        sendContentUpdates();
    }

    /** The listing: price, where it's been, and how dangerous it is. */
    private ItemStack listing(TrapCoins.Coin coin, long beat) {
        boolean dead = coin.dead(beat);
        float price = coin.price(beat);
        int hour = TrapMath.coinMove(beat, coin.id(), coin.base(),
                coin.risk().volatility, coin.risk().rugChance, 120);

        ItemStack tag = new ItemStack(dead ? Items.BROWN_CARPET : coin.icon());
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain(coin.ticker() + "  ").formatted(Formatting.WHITE, Formatting.BOLD)
                        .append(plain(money(price)).formatted(
                                dead ? Formatting.DARK_RED : Formatting.GREEN)));

        List<Text> lore = new ArrayList<>();
        lore.add(line(coin.name(), Formatting.GRAY)
                .append(plain("   " + coin.risk().label).formatted(coin.risk().colour)));
        lore.add(line(TrapCoins.sparkline(coin, beat, 120, 16), Formatting.DARK_AQUA));
        lore.add(line("Ostatnia godzina  ", Formatting.DARK_GRAY)
                .append(plain((hour >= 0 ? "+" : "") + hour + "%")
                        .formatted(hour >= 0 ? Formatting.GREEN : Formatting.RED)));
        lore.add(Text.empty());
        if (dead) {
            lore.add(line("PADŁA.", Formatting.DARK_RED, Formatting.BOLD));
            lore.add(line("Spadła do zera. Kiedyś wróci na listę.", Formatting.GRAY));
        } else if (coin.risk().rugChance > 0) {
            lore.add(line("Około " + Math.round(coin.risk().rugChance * 100)
                    + "% szans, że spadnie do zera", Formatting.DARK_GRAY));
            lore.add(line("w dowolnym dniu. To się zdarza.", Formatting.DARK_GRAY));
        } else {
            lore.add(line("Nie padnie. Ale też cię nie wzbogaci.", Formatting.DARK_GRAY));
        }
        tag.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return tag;
    }

    /** Your side of it: units, cost basis, and the profit line. */
    private ItemStack position(TrapCoins.Coin coin, TrapCoins.Holding mine, long beat) {
        if (mine == null) {
            ItemStack none = new ItemStack(Items.GRAY_DYE);
            none.set(DataComponentTypes.CUSTOM_NAME,
                    plain("Brak pozycji").formatted(Formatting.DARK_GRAY));
            return none;
        }
        int worth = TrapMath.coinSellValue(coin.price(beat), mine.units());
        int profit = worth - mine.spent();
        int percent = Math.round(profit / (float) Math.max(1, mine.spent()) * 100);

        ItemStack tag = new ItemStack(profit >= 0 ? Items.EMERALD : Items.REDSTONE);
        tag.setCount(Math.max(1, Math.min(64, mine.units())));
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain(mine.units() + " szt.  ").formatted(Formatting.WHITE)
                        .append(plain((profit >= 0 ? "+" : "") + percent + "%")
                                .formatted(profit >= 0 ? Formatting.GREEN : Formatting.RED,
                                        Formatting.BOLD)));

        List<Text> lore = new ArrayList<>();
        lore.add(line("Zapłacono  ", Formatting.DARK_GRAY)
                .append(plain(mine.spent() + "e").formatted(Formatting.WHITE))
                .append(plain("   po " + money(mine.average()))
                        .formatted(Formatting.DARK_GRAY)));
        lore.add(line("Wartość   ", Formatting.DARK_GRAY)
                .append(plain(worth + "e").formatted(Formatting.WHITE)));
        lore.add(line((profit >= 0 ? "Zysk    " : "Strata  "), Formatting.DARK_GRAY)
                .append(plain(Math.abs(profit) + "e")
                        .formatted(profit >= 0 ? Formatting.GREEN : Formatting.RED,
                                Formatting.BOLD)));
        if (mine.locked(beat)) {
            lore.add(Text.empty());
            lore.add(line("Zablokowane jeszcze przez "
                    + Math.max(1, (mine.lockedUntil() - beat) / 2) + " min",
                    Formatting.GOLD));
            lore.add(line("Wypłaci " + Math.round(TrapCoins.LOCK_BONUS * 100)
                    + "% premii, gdy blokada minie.", Formatting.DARK_GRAY));
        } else if (mine.lockedUntil() > 0) {
            lore.add(Text.empty());
            lore.add(line("Blokada minęła. Sprzedaż wypłaca "
                    + Math.round(TrapCoins.LOCK_BONUS * 100) + "% bonus.", Formatting.GOLD));
        }
        tag.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return tag;
    }

    private ItemStack buyTag(TrapCoins.Coin coin, long beat, int spend, boolean lock) {
        float price = coin.price(beat);
        int units = (int) (spend / (price * (1.0f + TrapMath.COIN_SPREAD)));
        boolean can = units > 0 && TrapMarket.wealthOf(trader) >= spend;

        ItemStack tag = new ItemStack(lock ? Items.IRON_BARS
                : can ? Items.LIME_STAINED_GLASS_PANE : Items.GRAY_STAINED_GLASS_PANE);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain(lock ? "BLOKADA " + spend + "e" : "KUP " + spend + "e")
                        .formatted(lock ? Formatting.GOLD
                                : can ? Formatting.GREEN : Formatting.DARK_GRAY,
                                Formatting.BOLD));
        List<Text> lore = new ArrayList<>();
        lore.add(line(units > 0 ? "Około " + units + " szt. po " + money(price)
                : "Za mało nawet na jedną sztukę.", Formatting.GRAY));
        lore.add(line("Spread wynosi " + Math.round(TrapMath.COIN_SPREAD * 100)
                + "% w każdą stronę.", Formatting.DARK_GRAY));
        if (lock) {
            lore.add(Text.empty());
            lore.add(line("Nie do sprzedania przez "
                    + TrapCoins.LOCK_BEATS / 2 + " minut.", Formatting.GRAY));
            lore.add(line("Wypłaci " + Math.round(TrapCoins.LOCK_BONUS * 100)
                    + "% premii za czekanie.", Formatting.GOLD));
            lore.add(line("Blokada nie chroni przed spadkiem do zera.", Formatting.RED));
        }
        tag.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return tag;
    }

    private ItemStack sellTag(TrapCoins.Coin coin, TrapCoins.Holding mine, long beat) {
        if (mine == null) {
            ItemStack none = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
            none.set(DataComponentTypes.CUSTOM_NAME, plain(" "));
            return none;
        }
        boolean locked = mine.locked(beat);
        int worth = TrapMath.coinSellValue(coin.price(beat), mine.units());
        ItemStack tag = new ItemStack(locked ? Items.IRON_BARS : Items.HOPPER);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain(locked ? "Zablokowane" : "SPRZEDAJ " + worth + "e")
                        .formatted(locked ? Formatting.DARK_GRAY : Formatting.GOLD,
                                Formatting.BOLD));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line(locked ? "Dopiero po upływie blokady." : "Sprzedaje cały pakiet.",
                        Formatting.GRAY))));
        return tag;
    }

    private ItemStack spendTag(long beat) {
        ItemStack tag = new ItemStack(Items.EMERALD,
                Math.max(1, Math.min(64, SPENDS[spendChoice] / 32)));
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Wartość kliknięcia: ").formatted(Formatting.GRAY)
                        .append(plain(SPENDS[spendChoice] + "e")
                                .formatted(Formatting.GREEN, Formatting.BOLD)));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line("Kliknij, żeby zmienić kwotę jednego zakupu.", Formatting.DARK_GRAY),
                Text.empty(),
                line("Portfolio  ", Formatting.GRAY)
                        .append(plain(TrapCoins.portfolio(trader, beat) + "e")
                                .formatted(Formatting.GREEN, Formatting.BOLD)))));
        return tag;
    }

    private ItemStack purseTag() {
        ItemStack tag = new ItemStack(Items.GOLD_NUGGET);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Kasa: ").formatted(Formatting.GRAY)
                        .append(plain(TrapMarket.wealthOf(trader) + "e")
                                .formatted(Formatting.GREEN, Formatting.BOLD)));
        return tag;
    }

    private static String money(float price) {
        return price >= 100 ? Math.round(price) + "e" : String.format("%.2fe", price);
    }

    // --- trading --------------------------------------------------------------

    @Override
    public void onSlotClick(int index, int button, SlotActionType type, PlayerEntity clicker) {
        if (index < 0 || index >= SIZE) {
            super.onSlotClick(index, button, type, clicker);
            return;
        }
        if (index == SPEND_SLOT) {
            spendChoice = (spendChoice + 1) % SPENDS.length;
            click(1.4F);
            paint();
            return;
        }
        if (index == BACK_SLOT) {
            trader.openHandledScreen(new net.minecraft.screen.SimpleNamedScreenHandlerFactory(
                    (id, inventory, ignored) -> new ShopScreenHandler(id, inventory),
                    plain("Rynek").formatted(Formatting.GOLD, Formatting.BOLD)));
            return;
        }

        int row = index / 9;
        int col = index % 9;
        if (row >= TrapCoins.BOARD.size()) {
            return;
        }
        TrapCoins.Coin coin = TrapCoins.BOARD.get(row);

        switch (col) {
            case BUY_COL -> buy(coin, SPENDS[spendChoice], false);
            case BIG_COL -> buy(coin, SPENDS[spendChoice] * BIG, false);
            case LOCK_COL -> buy(coin, SPENDS[spendChoice], true);
            case SELL_COL -> sell(coin);
            default -> {
            }
        }
    }

    private void buy(TrapCoins.Coin coin, int spend, boolean lock) {
        long beat = TrapMarket.beat();
        float price = coin.price(beat);
        int units = (int) (spend / (price * (1.0f + TrapMath.COIN_SPREAD)));
        if (units <= 0) {
            deny();
            trader.sendMessage(plain("Za to nie kupisz nawet jednej sztuki " + coin.ticker() + ".")
                    .formatted(Formatting.GRAY), false);
            return;
        }
        String no = TrapCoins.buy(trader, coin, units, lock);
        if (no != null) {
            deny();
            trader.sendMessage(plain(no).formatted(Formatting.GRAY), false);
            return;
        }
        chime(1.2F);
        trader.sendMessage(plain("Kupiono ").formatted(Formatting.GRAY)
                .append(plain(units + " " + coin.ticker()).formatted(Formatting.WHITE))
                .append(plain(" po " + money(price)).formatted(Formatting.DARK_GRAY))
                .append(plain(lock ? "   z blokadą" : "").formatted(Formatting.GOLD)), false);
        paint();
    }

    private void sell(TrapCoins.Coin coin) {
        int paid = TrapCoins.sell(trader, coin);
        if (paid == -2) {
            return;
        }
        if (paid == -1) {
            deny();
            trader.sendMessage(plain("Ta pozycja jest zablokowana. Poczekaj.")
                    .formatted(Formatting.GRAY), false);
            return;
        }
        chime(0.9F);
        trader.sendMessage(plain("Sprzedano całość: ").formatted(Formatting.GRAY)
                .append(plain(coin.ticker()).formatted(Formatting.WHITE))
                .append(plain(" za ").formatted(Formatting.GRAY))
                .append(plain(paid + "e").formatted(Formatting.GREEN, Formatting.BOLD)), false);
        paint();
    }

    // --- trimmings ------------------------------------------------------------

    private void chime(float pitch) {
        trader.getWorld().playSound(null, trader.getBlockPos(),
                SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), SoundCategory.PLAYERS, 0.5F, pitch);
    }

    private void click(float pitch) {
        trader.getWorld().playSound(null, trader.getBlockPos(),
                SoundEvents.UI_BUTTON_CLICK.value(), SoundCategory.PLAYERS, 0.5F, pitch);
    }

    private void deny() {
        trader.getWorld().playSound(null, trader.getBlockPos(),
                SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), SoundCategory.PLAYERS, 0.7F, 0.6F);
    }

    private static ItemStack button(net.minecraft.item.Item item, String name, Formatting colour) {
        ItemStack tag = new ItemStack(item);
        tag.set(DataComponentTypes.CUSTOM_NAME, plain(name).formatted(colour, Formatting.BOLD));
        return tag;
    }

    private static MutableText plain(String text) {
        return Text.literal(text).styled(style -> style.withItalic(false));
    }

    private static MutableText line(String text, Formatting... colours) {
        return plain(text).formatted(colours);
    }

    @Override
    public ItemStack quickMove(PlayerEntity mover, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canUse(PlayerEntity user) {
        return user == trader;
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
        public boolean canTakeItems(PlayerEntity taker) {
            return false;
        }
    }
}
