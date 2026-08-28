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
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
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
 * The phone's other page: who works for you, and who's going.
 *
 * Top row is your network -- one head each, click to call them in. Bottom row
 * is who's available tonight, with what they'd cost. Both on one screen because
 * the decision "do I hire a fourth" is really the decision "are my three
 * already stepping on each other", and that is only answerable side by side.
 */
public class NetworkScreenHandler extends ScreenHandler {
    private static final int ROWS = 3;
    private static final int SIZE = ROWS * 9;
    private static final int MINE_ROW = 0;
    private static final int HELP_SLOT = 13;
    private static final int REROLL_SLOT = 17;
    private static final int OFFER_ROW = 2;
    /**
     * The market, from wherever you are standing.
     *
     * On this page and not behind its own gesture because the phone only has
     * two -- click and sneak-click -- and both were spoken for. The far end of
     * the middle row, mirroring the reroll button.
     */
    private static final int MARKET_SLOT = 9;

    private final SimpleInventory display = new SimpleInventory(SIZE);
    private final ServerPlayerEntity boss;
    private List<TrapDealers.Dealer> mine = new ArrayList<>();
    private List<TrapDealers.Dealer> offers = new ArrayList<>();

    public NetworkScreenHandler(int syncId, PlayerInventory playerInventory) {
        super(ScreenHandlerType.GENERIC_9X3, syncId);
        this.boss = (ServerPlayerEntity) playerInventory.player;

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
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInventory, col, 8 + col * 18, 142));
        }
        paint();
    }

    private void paint() {
        ItemStack filler = new ItemStack(Items.BLACK_STAINED_GLASS_PANE);
        filler.set(DataComponentTypes.CUSTOM_NAME, plain(" "));
        for (int index = 0; index < SIZE; index++) {
            display.setStack(index, filler.copy());
        }

        mine = TrapDealers.of(boss);
        offers = TrapDealers.board(boss);

        for (int i = 0; i < mine.size() && i < 9; i++) {
            display.setStack(MINE_ROW * 9 + i, onTheBooks(mine.get(i)));
        }
        for (int i = 0; i < offers.size() && i < 9; i++) {
            display.setStack(OFFER_ROW * 9 + i, going(offers.get(i)));
        }
        display.setStack(HELP_SLOT, help());
        display.setStack(REROLL_SLOT, rerollTag());
        display.setStack(MARKET_SLOT, marketTag());
        sendContentUpdates();
    }

    private ItemStack marketTag() {
        ItemStack tag = new ItemStack(Items.EMERALD);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Rynek").formatted(Formatting.GREEN, Formatting.BOLD));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line("Ten sam stragan, tylko przez telefon.", Formatting.GRAY),
                Text.empty(),
                line("W kieszeni: ", Formatting.DARK_GRAY)
                        .append(plain(TrapMarket.wealthOf(boss) + "e").formatted(Formatting.GREEN)),
                Text.empty(),
                line("Kliknij, żeby otworzyć.", Formatting.YELLOW))));
        return tag;
    }

    private ItemStack help() {
        ItemStack tag = new ItemStack(Items.BOOK);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Siatka dilerów").formatted(Formatting.GOLD, Formatting.BOLD));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line("Górny rząd: twoi. Kliknij, żeby wezwać.", Formatting.GRAY),
                line("Dolny rząd: chętni do pracy.", Formatting.GRAY),
                line("Po lewej: rynek, bez chodzenia na stragan.", Formatting.GRAY),
                Text.empty(),
                line("Sprzedają, kiedy cię nie ma. Najlepiej w", Formatting.WHITE),
                line("nocy, najgorzej koło południa.", Formatting.WHITE),
                Text.empty(),
                line("Każdy kolejny diler w tej samej okolicy", Formatting.DARK_GRAY),
                line("sprzedaje mniej niż poprzedni.", Formatting.DARK_GRAY),
                Text.empty(),
                line("zatrudnionych: " + mine.size() + " z " + TrapDealers.MAX_DEALERS + ".",
                        Formatting.DARK_GRAY))));
        return tag;
    }

    private ItemStack rerollTag() {
        boolean can = TrapMarket.wealthOf(boss) >= TrapDealers.REROLL_COST;
        ItemStack tag = new ItemStack(can ? Items.ENDER_EYE : Items.GRAY_DYE);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Popytaj").formatted(can ? Formatting.AQUA : Formatting.DARK_GRAY,
                        Formatting.BOLD));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line(TrapDealers.REROLL_COST + "e za trzy nowe osoby.", Formatting.GRAY),
                Text.empty(),
                line("Lista odświeża się sama co", Formatting.DARK_GRAY),
                line("jakieś dziesięć minut i tak.", Formatting.DARK_GRAY),
                Text.empty(),
                line("Wysoka reputacja przyciąga lepszych ludzi.", Formatting.WHITE),
                line("Twoja: " + rep() + ".", Formatting.DARK_GRAY))));
        return tag;
    }

    private int rep() {
        return TrapContracts.repOf(TrapContracts.findPhone(boss));
    }

    private ItemStack onTheBooks(TrapDealers.Dealer dealer) {
        boolean here = dealer.mob != null;
        ItemStack tag = new ItemStack(here ? Items.BELL : Items.PLAYER_HEAD);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain(dealer.name).formatted(Formatting.GOLD, Formatting.BOLD)
                        .append(plain("  L" + dealer.level).formatted(Formatting.WHITE)));
        List<Text> lore = new ArrayList<>();
        lore.add(line("Ma przy sobie " + dealer.carrying() + " z "
                + dealer.slots() + " slots", Formatting.GRAY));
        lore.add(line("Utarg  ", Formatting.DARK_GRAY)
                .append(plain(dealer.earnings + "e").formatted(Formatting.GREEN)));
        lore.add(line(dealer.stock.isEmpty()
                        ? "Bezczynny -- nie ma czego sprzedawać." : "W trasie.",
                dealer.stock.isEmpty() ? Formatting.RED : Formatting.DARK_GRAY));
        // They do level up on their own, off what they sell -- but with no
        // number on the screen the only way to find out was to wait long
        // enough, and the honest answer to "is this thing progressing?" has to
        // be visible or it may as well not be true.
        lore.add(dealer.level >= TrapMath.DEALER_MAX_LEVEL
                ? line("Maksymalny poziom.", Formatting.GOLD)
                : line("jeszcze " + dealer.toNextLevel() + " sprzedaży", Formatting.DARK_GRAY)
                .append(plain(" to L" + (dealer.level + 1)).formatted(Formatting.WHITE)));
        // Your rep is in this number, because it is in the real one. Quoting
        // the rate a nobody would get would make the screen wrong for exactly
        // the people who worked hardest on it.
        lore.add(line("Sprzedaje około " + Math.round(
                        TrapMath.dealerRate(dealer.level, mine.size(), 0, rep()) * 30)
                + " na godzinę na tym poziomie.", Formatting.DARK_GRAY));
        lore.add(Text.empty());
        lore.add(here
                ? line("Już stoi przed tobą.", Formatting.DARK_GRAY)
                : line("Kliknij, żeby go wezwać.", Formatting.YELLOW));
        tag.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return tag;
    }

    private ItemStack going(TrapDealers.Dealer offer) {
        int full = TrapMath.dealerHireCost(offer.level);
        int cost = TrapMath.dealerHireCost(offer.level, rep());
        boolean can = TrapMarket.wealthOf(boss) >= cost
                && mine.size() < TrapDealers.MAX_DEALERS;
        ItemStack tag = new ItemStack(can ? Items.PAPER : Items.GRAY_DYE);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain(offer.name).formatted(can ? Formatting.WHITE : Formatting.DARK_GRAY,
                        Formatting.BOLD)
                        .append(plain("  L" + offer.level)
                                .formatted(can ? Formatting.GOLD : Formatting.DARK_GRAY)));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                cost < full
                        ? line("Chce " + cost + "e z góry  ", Formatting.GOLD)
                        .append(plain("(było " + full + ", reputacja pomaga)")
                                .formatted(Formatting.DARK_GRAY))
                        : line("Chce " + cost + "e z góry.", Formatting.GOLD),
                line("Zatrzymuje " + Math.round(TrapMath.dealerCut(offer.level) * 100)
                        + "% tego, co sprzedadzą.", Formatting.GRAY),
                line("Slotów: " + TrapMath.dealerSlots(offer.level) + ".",
                        Formatting.GRAY),
                Text.empty(),
                line(offer.level < 3
                                ? "Zielony. Częściej go okradają."
                                : "Doświadczony. Rzadko go ruszają.",
                        offer.level < 3 ? Formatting.RED : Formatting.DARK_GRAY),
                Text.empty(),
                line(can ? "Kliknij, żeby go zatrudnić."
                                : mine.size() >= TrapDealers.MAX_DEALERS
                                ? "Nie masz już miejsca." : "Nie stać cię.",
                        can ? Formatting.YELLOW : Formatting.DARK_GRAY))));
        return tag;
    }

    @Override
    public void onSlotClick(int index, int button, SlotActionType type, PlayerEntity clicker) {
        if (index < 0 || index >= SIZE) {
            super.onSlotClick(index, button, type, clicker);
            return;
        }
        int row = index / 9;
        int col = index % 9;

        if (row == MINE_ROW && col < mine.size()) {
            TrapDealers.Dealer dealer = mine.get(col);
            String no = TrapDealers.call(boss, dealer);
            if (no != null) {
                deny();
                boss.sendMessage(plain(no).formatted(Formatting.GRAY), false);
            } else {
                boss.closeHandledScreen();
            }
            return;
        }
        if (index == MARKET_SLOT) {
            boss.getWorld().playSound(null, boss.getBlockPos(),
                    SoundEvents.BLOCK_NOTE_BLOCK_BIT.value(), SoundCategory.PLAYERS, 0.5F, 1.6F);
            boss.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                    (id, inventory, ignored) -> new ShopScreenHandler(id, inventory),
                    plain("Rynek").formatted(Formatting.DARK_GREEN)));
            return;
        }
        if (index == REROLL_SLOT) {
            String no = TrapDealers.payToReroll(boss);
            if (no != null) {
                deny();
                boss.sendMessage(plain(no).formatted(Formatting.GRAY), false);
                return;
            }
            boss.getWorld().playSound(null, boss.getBlockPos(),
                    SoundEvents.BLOCK_NOTE_BLOCK_BIT.value(), SoundCategory.PLAYERS, 0.6F, 1.2F);
            paint();
            return;
        }
        if (row == OFFER_ROW && col < offers.size()) {
            String no = TrapDealers.hire(boss, offers.get(col));
            if (no != null) {
                deny();
                boss.sendMessage(plain(no).formatted(Formatting.GRAY), false);
                return;
            }
            boss.getWorld().playSound(null, boss.getBlockPos(),
                    SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), SoundCategory.PLAYERS, 0.6F, 1.4F);
            paint();
        }
    }

    private void deny() {
        boss.getWorld().playSound(null, boss.getBlockPos(),
                SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), SoundCategory.PLAYERS, 0.7F, 0.6F);
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
        return user == boss;
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
