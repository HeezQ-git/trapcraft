package dev.heezq.trapcraft;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
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
 * The counting room for everybody's money.
 *
 * Anyone may take from it and everyone is told when somebody does. That is a
 * decision, not an oversight: three friends on a call can agree what the
 * money is for in ten seconds, and a voting interface for a server with three
 * people on it is a menu standing where a conversation should be. The
 * announcement is the whole of the accountability, and it is enough.
 *
 *   [purse] . [essentials][materials][luxury][income][gaming][rent][ledger]
 *   . . [take 64][take 256][take 1024][take all] . [donations] .
 *   [works..] . [what it is for]
 */
public class CityScreenHandler extends ScreenHandler {
    private static final int SIZE = 27;

    private static final int PURSE_SLOT = 0;
    private static final int PAY_SLOT = 1;
    private static final int RATES_FROM = 2;
    private static final int LEDGER_SLOT = 8;
    private static final int TAKE_FROM = 11;
    private static final int GIVE_SLOT = 16;
    private static final int ABOUT_SLOT = 26;
    private static final int WORKS_FROM = 18;

    /** What each withdraw button is worth. Null means everything. */
    private static final Integer[] TAKES = {64, 256, 1024, null};

    /** What one click of "pay in" is worth. Right-click gives everything. */
    private static final int PAY_STEP = 1024;

    /** One per public work, in declaration order. */
    private static final Item[] WORK_ICONS = {
            Items.CROSSBOW, Items.STONE_BRICKS, Items.LANTERN, Items.GOLD_INGOT,
            Items.GLISTERING_MELON_SLICE, Items.MINECART, Items.BOOK};

    private static final Item[] ICONS = {
            Items.BREAD, Items.BRICKS, Items.AMETHYST_SHARD, Items.PAPER,
            Items.GOLD_NUGGET, Items.RED_BED};

    static {
        // One icon per duty, and the row is drawn by index. A sixth duty would
        // otherwise walk off the end of this array the first time somebody
        // opened the vault.
        if (ICONS.length != TrapCity.Duty.values().length
                || RATES_FROM + ICONS.length > LEDGER_SLOT) {
            throw new IllegalStateException("city board: "
                    + TrapCity.Duty.values().length + " duties won't fit");
        }
        // A fifth withdraw button would land on the donations door, and the
        // symptom would be "Take 4096e opened a different screen".
        if (GIVE_SLOT >= TAKE_FROM && GIVE_SLOT < TAKE_FROM + TAKES.length) {
            throw new IllegalStateException("city board: the take row reaches the donations door");
        }
        if (WORK_ICONS.length != TrapCity.Work.values().length
                || WORKS_FROM + WORK_ICONS.length > SIZE
                // ...and must not run over the blurb. Seven works reached slot
                // 22, which was ABOUT_SLOT, so the last one would have drawn
                // over it and clicking the blurb would have bought a school.
                || (ABOUT_SLOT >= WORKS_FROM
                        && ABOUT_SLOT < WORKS_FROM + WORK_ICONS.length)) {
            throw new IllegalStateException("city board: "
                    + TrapCity.Work.values().length + " works won't fit");
        }
    }

    private final SimpleInventory display = new SimpleInventory(SIZE);
    private final ServerPlayerEntity who;

    public CityScreenHandler(int syncId, PlayerInventory playerInventory) {
        super(ScreenHandlerType.GENERIC_9X3, syncId);
        this.who = (ServerPlayerEntity) playerInventory.player;

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
        paint();
    }

    private void paint() {
        ItemStack filler = new ItemStack(Items.BLACK_STAINED_GLASS_PANE);
        filler.set(DataComponentTypes.CUSTOM_NAME, plain(" "));
        for (int index = 0; index < SIZE; index++) {
            display.setStack(index, filler.copy());
        }

        display.setStack(PURSE_SLOT, purse());
        display.setStack(PAY_SLOT, payIn());
        TrapCity.Duty[] duties = TrapCity.Duty.values();
        for (int i = 0; i < duties.length; i++) {
            display.setStack(RATES_FROM + i, rate(duties[i], ICONS[i]));
        }
        display.setStack(LEDGER_SLOT, raised());
        for (int i = 0; i < TAKES.length; i++) {
            display.setStack(TAKE_FROM + i, take(TAKES[i]));
        }
        display.setStack(GIVE_SLOT, donations());
        TrapCity.Work[] works = TrapCity.Work.values();
        for (int i = 0; i < works.length; i++) {
            display.setStack(WORKS_FROM + i, work(works[i], WORK_ICONS[i]));
        }
        display.setStack(ABOUT_SLOT, about());
        sendContentUpdates();
    }

    private ItemStack purse() {
        long money = TrapCity.treasury();
        ItemStack tag = new ItemStack(money > 0 ? Items.EMERALD_BLOCK : Items.BARRIER);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Kasa miasta").formatted(Formatting.GOLD, Formatting.BOLD));
        List<Text> lore = new ArrayList<>();
        lore.add(line(money + "e", money > 0 ? Formatting.GREEN : Formatting.RED,
                Formatting.BOLD));
        lore.add(Text.empty());
        lore.add(line(money < TrapCity.BROKE
                        ? "Pusta. Podatki wzrosną przy następnym budżecie."
                        : money > TrapCity.FLUSH
                        ? "Pełna. Podatki spadną przy następnym budżecie."
                        : "W dobrym stanie. Podatki tylko lekko drgają.",
                money < TrapCity.BROKE ? Formatting.RED
                        : money > TrapCity.FLUSH ? Formatting.AQUA : Formatting.GRAY));
        lore.add(Text.empty());
        lore.add(line("Wspólna. Wydać może każdy,", Formatting.DARK_GRAY));
        lore.add(line("a wszyscy dostają info kto.", Formatting.DARK_GRAY));
        tag.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return tag;
    }

    private ItemStack rate(TrapCity.Duty duty, Item icon) {
        int now = TrapCity.rateOf(duty);
        ItemStack tag = new ItemStack(icon);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain(duty.display()).formatted(Formatting.WHITE, Formatting.BOLD)
                        .append(plain("   " + now + "%").formatted(Formatting.GOLD)));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line(duty.blurb(), Formatting.GRAY),
                Text.empty(),
                line("Nigdy poniżej " + duty.floor() + "%, nigdy powyżej "
                        + duty.ceiling() + "%.", Formatting.DARK_GRAY),
                line("Zebrano dotąd " + TrapCity.takenBy(duty) + "e.",
                        Formatting.DARK_GRAY))));
        return tag;
    }

    private ItemStack raised() {
        int total = 0;
        List<Text> lore = new ArrayList<>();
        for (TrapCity.Duty duty : TrapCity.Duty.values()) {
            total += TrapCity.takenBy(duty);
            lore.add(line(duty.display() + "  ", Formatting.DARK_GRAY)
                    .append(plain(TrapCity.takenBy(duty) + "e").formatted(Formatting.WHITE)));
        }
        ItemStack tag = new ItemStack(Items.WRITTEN_BOOK);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Zebrane od początku").formatted(Formatting.GOLD, Formatting.BOLD)
                        .append(plain("   " + total + "e").formatted(Formatting.WHITE)));
        lore.add(Text.empty());
        lore.add(line("Sprzedaż klientom z ulicy i dilerom", Formatting.DARK_GRAY));
        lore.add(line("w ogóle się tu nie pojawia.", Formatting.DARK_GRAY));
        tag.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return tag;
    }

    /**
     * The other direction, which the vault has never had.
     *
     * Sat next to the purse rather than beside the take buttons on purpose:
     * the first thing anybody does at this screen is look at the number, and
     * the way to change it should be the next thing their eye lands on.
     */
    private ItemStack payIn() {
        int held = TrapMarket.wealthOf(who);
        boolean can = held > 0;
        ItemStack tag = new ItemStack(can ? Items.EMERALD_BLOCK : Items.GRAY_DYE);
        tag.set(DataComponentTypes.CUSTOM_NAME, plain("Wpłać")
                .formatted(can ? Formatting.GREEN : Formatting.DARK_GRAY, Formatting.BOLD));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line(can ? "Masz przy sobie " + held + "e." : "Nie masz przy sobie pieniędzy.",
                        can ? Formatting.GREEN : Formatting.DARK_GRAY),
                Text.empty(),
                line("LPM wpłaca " + PAY_STEP + "e.", Formatting.YELLOW),
                line("PPM wpłaca wszystko.", Formatting.YELLOW),
                Text.empty(),
                line("Z tej kasy opłacane są inwestycje,", Formatting.DARK_GRAY),
                line("a nic innego jej nie zasila.", Formatting.DARK_GRAY))));
        return tag;
    }

    private ItemStack take(Integer amount) {
        long purse = TrapCity.treasury();
        long wanted = amount == null ? purse : Math.min(amount, purse);
        boolean can = wanted > 0;
        ItemStack tag = new ItemStack(can ? Items.EMERALD : Items.GRAY_DYE);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain(amount == null ? "Wypłać wszystko" : "Wypłać " + amount + "e")
                        .formatted(can ? Formatting.YELLOW : Formatting.DARK_GRAY,
                                Formatting.BOLD));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line(can ? "Dostaniesz " + wanted + "e." : "Nie ma tam tyle.",
                        can ? Formatting.GREEN : Formatting.DARK_GRAY),
                Text.empty(),
                line("Wszyscy na serwerze dostaną info.", Formatting.DARK_GRAY))));
        return tag;
    }

    /** The door to the page that hands the purse to somebody who runs a floor. */
    private ItemStack donations() {
        int floors = TrapHouse.all().size();
        ItemStack tag = new ItemStack(floors > 0 ? TrapContent.casinoCard : Items.GRAY_DYE);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Dotacje").formatted(floors > 0 ? Formatting.LIGHT_PURPLE
                        : Formatting.DARK_GRAY, Formatting.BOLD));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line(floors > 0
                                ? "Kasyn do wsparcia: " + floors + "."
                                : "Nikt jeszcze nie założył kasyna.",
                        floors > 0 ? Formatting.GRAY : Formatting.DARK_GRAY),
                Text.empty(),
                line("Bezzwrotna dotacja z kasy miasta prosto", Formatting.DARK_GRAY),
                line("do kasyna. Wybierz komu i ile.", Formatting.DARK_GRAY))));
        return tag;
    }

    /**
     * Something the purse can be spent ON.
     *
     * A treasury with no sink is a scoreboard, and a scoreboard nobody can
     * spend is a reason to stop collecting.
     */
    private ItemStack work(TrapCity.Work work, Item icon) {
        int level = TrapCity.level(work);
        int cost = TrapCity.nextCost(work);
        boolean finished = cost < 0;
        boolean can = !finished && TrapCity.treasury() >= cost;
        ItemStack tag = new ItemStack(level > 0 || can ? icon : Items.GRAY_DYE);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain(TrapCity.titleOf(work)).formatted(finished ? Formatting.GREEN
                        : can ? Formatting.YELLOW : level > 0 ? Formatting.WHITE
                        : Formatting.DARK_GRAY, Formatting.BOLD));
        List<Text> lore = new ArrayList<>();
        lore.add(line(work.blurb() + ".", Formatting.GRAY));
        lore.add(Text.empty());
        if (finished) {
            lore.add(line("Ukończone -- " + TrapCity.TOP_TIER + " z "
                    + TrapCity.TOP_TIER + ". Nic więcej do zapłaty.", Formatting.GREEN));
        } else {
            if (level > 0) {
                lore.add(line("Poziom " + level + " z " + TrapCity.TOP_TIER
                        + ". Działa, kiedy zbierasz na kolejny.", Formatting.GREEN));
                lore.add(Text.empty());
            }
            lore.add(line(cost + "e z kasy miasta", Formatting.GOLD));
            lore.add(line(can ? "Kliknij, żeby zbudować kolejny poziom."
                    : "Kasie miasta brakuje " + (cost - TrapCity.treasury()) + "e.",
                    can ? Formatting.YELLOW : Formatting.DARK_GRAY));
            lore.add(Text.empty());
            lore.add(line("Kupić może każdy. Wszyscy dostają info.", Formatting.DARK_GRAY));
        }
        tag.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return tag;
    }

    private ItemStack about() {
        ItemStack tag = new ItemStack(Items.BELL);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Po co to jest").formatted(Formatting.GOLD, Formatting.BOLD));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line("Drogi, mury, rynek, ratusz --", Formatting.GRAY),
                line("co tylko te pieniądze zbudują.", Formatting.GRAY),
                Text.empty(),
                line("Stawki zmieniają się same co kilka dni,", Formatting.DARK_GRAY),
                line("z ogłoszeniem na czacie. Pusta kasa", Formatting.DARK_GRAY),
                line("podnosi podatki.", Formatting.DARK_GRAY),
                Text.empty(),
                line("/city pokazuje to samo na czacie.", Formatting.DARK_GRAY))));
        return tag;
    }

    // --- clicking -------------------------------------------------------------

    @Override
    public void onSlotClick(int index, int button, SlotActionType type, PlayerEntity clicker) {
        if (index < 0 || index >= SIZE) {
            super.onSlotClick(index, button, type, clicker);
            return;
        }
        if (index == PAY_SLOT) {
            int all = TrapMarket.wealthOf(who);
            int amount = button == 1 || type == SlotActionType.QUICK_MOVE
                    ? all : Math.min(PAY_STEP, all);
            String no = TrapCity.payIn(who, amount);
            if (no != null) {
                who.sendMessage(Text.literal(no).formatted(Formatting.GRAY), true);
                click(SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), 0.7F);
            } else {
                click(SoundEvents.BLOCK_VAULT_INSERT_ITEM, 1.2F);
            }
            paint();
            return;
        }
        if (index == GIVE_SLOT) {
            click(SoundEvents.BLOCK_VAULT_OPEN_SHUTTER, 1.2F);
            who.openHandledScreen(new net.minecraft.screen.SimpleNamedScreenHandlerFactory(
                    (syncId, inventory, ignored) -> new DonationScreenHandler(syncId, inventory),
                    plain("Dotacje").formatted(Formatting.LIGHT_PURPLE, Formatting.BOLD)));
            return;
        }
        if (index >= WORKS_FROM && index < WORKS_FROM + WORK_ICONS.length) {
            String no = TrapCity.build(who, TrapCity.Work.values()[index - WORKS_FROM]);
            if (no != null) {
                who.sendMessage(Text.literal(no).formatted(Formatting.GRAY), true);
                click(SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), 0.7F);
            } else {
                click(SoundEvents.BLOCK_VAULT_ACTIVATE, 1.0F);
            }
            paint();
            return;
        }
        if (index >= TAKE_FROM && index < TAKE_FROM + TAKES.length) {
            Integer amount = TAKES[index - TAKE_FROM];
            String no = TrapCity.withdraw(who,
                    amount == null ? Integer.MAX_VALUE : amount);
            if (no != null) {
                who.sendMessage(Text.literal(no).formatted(Formatting.GRAY), true);
                click(SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), 0.7F);
            } else {
                click(SoundEvents.BLOCK_VAULT_INSERT_ITEM, 1.0F);
            }
            paint();
        }
    }

    private void click(net.minecraft.sound.SoundEvent sound, float pitch) {
        who.getWorld().playSound(null, who.getBlockPos(), sound,
                SoundCategory.PLAYERS, 0.7F, pitch);
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
