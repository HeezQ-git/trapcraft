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
import java.util.UUID;

/**
 * The city giving its money to somebody who runs a table.
 *
 * A casino needs a float before it can open, and a floor that cannot cover a
 * bet turns people away -- so the one thing a town can do for a business that
 * a shop cannot do for itself is put money behind the counter. That is what
 * this page is. It is a grant, not an investment: the purse gets nothing
 * back, and everybody hears about it, which is the same accountability the
 * rest of the vault runs on.
 *
 * Two halves, because a donation is two questions and a screen that asks them
 * both at once is a screen you misclick:
 *
 *   [purse] [who][who][who][who][who][who][who] [about]
 *   [-1000][-100][-10][-1] [HOW MUCH] [+1][+10][+100][+1000]
 *   [back] . . . [GIVE IT] . . . [the lot]
 *
 * The dial is a stepper rather than a text box on purpose. A vanilla client
 * has exactly one place it can type into a server-made screen -- an anvil --
 * and hanging a rename field off this would mean a second window, a second
 * packet path and a number that arrives as a string somebody can put "12e" or
 * "-4" or "1e9" into. Four steps in each direction reach any figure the purse
 * can hold in a handful of clicks, and no figure it cannot.
 */
public class DonationScreenHandler extends ScreenHandler {
    private static final int SIZE = 27;

    private static final int PURSE_SLOT = 0;
    private static final int HOUSES_FROM = 1;
    private static final int HOUSE_SLOTS = 7;
    private static final int ABOUT_SLOT = 8;
    private static final int DIAL_FROM = 9;
    private static final int AMOUNT_SLOT = 13;
    private static final int BACK_SLOT = 18;
    private static final int GIVE_SLOT = 22;
    private static final int ALL_SLOT = 26;

    /** What each seat on the dial moves the figure by. The 0 is the readout. */
    private static final int[] STEPS = {-1000, -100, -10, -1, 0, 1, 10, 100, 1000};

    /** What the dial opens on, when the purse can afford it. */
    private static final int OPENING_OFFER = 64;

    static {
        // The dial is drawn by index off one array, so a tenth step or a
        // readout moved off centre would silently paint over the row below.
        if (DIAL_FROM + STEPS.length != BACK_SLOT
                || STEPS[AMOUNT_SLOT - DIAL_FROM] != 0
                || HOUSES_FROM + HOUSE_SLOTS != ABOUT_SLOT) {
            throw new IllegalStateException("donations: the board doesn't line up");
        }
    }

    private final SimpleInventory display = new SimpleInventory(SIZE);
    private final ServerPlayerEntity who;

    /** Held by id, not by reference: a casino can be dissolved mid-page. */
    private UUID target;
    private int amount;

    public DonationScreenHandler(int syncId, PlayerInventory playerInventory) {
        super(ScreenHandlerType.GENERIC_9X3, syncId);
        this.who = (ServerPlayerEntity) playerInventory.player;

        List<TrapHouse.House> houses = houses();
        this.target = houses.isEmpty() ? null : houses.get(0).id;
        this.amount = TrapMath.stepped(0, OPENING_OFFER, TrapCity.treasury());

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

    /** ponytail: the first seven. Page it the day somebody founds an eighth. */
    private static List<TrapHouse.House> houses() {
        List<TrapHouse.House> all = new ArrayList<>(TrapHouse.all());
        return all.size() > HOUSE_SLOTS ? all.subList(0, HOUSE_SLOTS) : all;
    }

    /** The chosen casino, or null if it was dissolved while this was open. */
    private TrapHouse.House chosen() {
        return target == null ? null : TrapHouse.byId(target);
    }

    private void paint() {
        ItemStack filler = new ItemStack(Items.BLACK_STAINED_GLASS_PANE);
        filler.set(DataComponentTypes.CUSTOM_NAME, plain(" "));
        for (int index = 0; index < SIZE; index++) {
            display.setStack(index, filler.copy());
        }

        // The purse can be emptied by somebody else at the other counter while
        // this page sits open, so the dial is re-clamped on every repaint
        // rather than only when it is turned.
        amount = TrapMath.stepped(amount, 0, TrapCity.treasury());

        display.setStack(PURSE_SLOT, purse());
        List<TrapHouse.House> houses = houses();
        for (int i = 0; i < houses.size(); i++) {
            TrapHouse.House house = houses.get(i);
            display.setStack(HOUSES_FROM + i, house(house, house.id.equals(target)));
        }
        if (houses.isEmpty()) {
            display.setStack(HOUSES_FROM + 3, nobody());
        }
        display.setStack(ABOUT_SLOT, about());
        for (int i = 0; i < STEPS.length; i++) {
            display.setStack(DIAL_FROM + i,
                    STEPS[i] == 0 ? dialled() : step(STEPS[i]));
        }
        display.setStack(BACK_SLOT, back());
        display.setStack(GIVE_SLOT, give());
        display.setStack(ALL_SLOT, theLot());
        sendContentUpdates();
    }

    private ItemStack purse() {
        long money = TrapCity.treasury();
        ItemStack tag = new ItemStack(money > 0 ? Items.EMERALD_BLOCK : Items.BARRIER);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Kasa miasta").formatted(Formatting.GOLD, Formatting.BOLD));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line(money + "e", money > 0 ? Formatting.GREEN : Formatting.RED,
                        Formatting.BOLD),
                Text.empty(),
                line("Po tej dotacji  ", Formatting.DARK_GRAY)
                        .append(plain((money - amount) + "e").formatted(Formatting.WHITE)),
                Text.empty(),
                line("Wspólna. Rozdać może każdy,", Formatting.DARK_GRAY),
                line("a wszyscy dostają info kto.", Formatting.DARK_GRAY))));
        return tag;
    }

    private ItemStack house(TrapHouse.House house, boolean chosen) {
        ItemStack tag = new ItemStack(chosen ? TrapContent.casinoCard : Items.PAPER);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain(house.name).formatted(
                        chosen ? Formatting.LIGHT_PURPLE : Formatting.WHITE, Formatting.BOLD));
        List<Text> lore = new ArrayList<>();
        lore.add(line("Założyciel: " + house.founder, Formatting.GRAY));
        lore.add(Text.empty());
        lore.add(line("W skarbcu  ", Formatting.DARK_GRAY)
                .append(plain(house.balance + "e").formatted(Formatting.WHITE)));
        lore.add(line("Podłączonych automatów  ", Formatting.DARK_GRAY)
                .append(plain(String.valueOf(TrapHouse.machineCount(house)))
                        .formatted(Formatting.WHITE)));
        lore.add(Text.empty());
        lore.add(chosen
                ? line("To kasyno dostanie dotację.", Formatting.GREEN)
                : line("Kliknij, żeby wysłać tutaj.", Formatting.YELLOW));
        tag.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return tag;
    }

    private ItemStack nobody() {
        ItemStack tag = new ItemStack(Items.BARRIER);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Nie ma komu dać").formatted(Formatting.RED, Formatting.BOLD));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line("Nikt jeszcze nie założył kasyna.", Formatting.GRAY),
                Text.empty(),
                line("Ktoś musi najpierw podpisać licencję", Formatting.DARK_GRAY),
                line("i podłączyć do niej automat.", Formatting.DARK_GRAY))));
        return tag;
    }

    private ItemStack step(int by) {
        int after = TrapMath.stepped(amount, by, TrapCity.treasury());
        boolean can = after != amount;
        boolean up = by > 0;
        ItemStack tag = new ItemStack(!can ? Items.GRAY_DYE
                : up ? Items.LIME_DYE : Items.RED_DYE);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain((up ? "+" : "") + by + "e").formatted(
                        !can ? Formatting.DARK_GRAY : up ? Formatting.GREEN : Formatting.RED,
                        Formatting.BOLD));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line(can ? "Ustawi na " + after + "e."
                                : up ? "Kasa miasta na tyle nie starczy."
                                : "Już jest zero.",
                        can ? Formatting.GRAY : Formatting.DARK_GRAY))));
        return tag;
    }

    private ItemStack dialled() {
        // The stack count is the figure, up to a stack of it. Free feedback:
        // you can see the pile grow without reading the number.
        ItemStack tag = new ItemStack(amount > 0 ? Items.EMERALD : Items.BARRIER,
                Math.max(1, Math.min(64, amount)));
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain(amount + "e").formatted(
                        amount > 0 ? Formatting.GOLD : Formatting.DARK_GRAY, Formatting.BOLD));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line("Tyle miasto zaraz przekaże.", Formatting.GRAY),
                Text.empty(),
                line("Ustaw pokrętłami po bokach albo", Formatting.DARK_GRAY),
                line("weź wszystko z rogu planszy.", Formatting.DARK_GRAY))));
        return tag;
    }

    private ItemStack give() {
        TrapHouse.House house = chosen();
        boolean can = house != null && amount > 0 && amount <= TrapCity.treasury();
        ItemStack tag = new ItemStack(can ? Items.EMERALD_BLOCK : Items.GRAY_DYE);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain(can ? "Przekaż " + amount + "e" : "Przekaż")
                        .formatted(can ? Formatting.YELLOW : Formatting.DARK_GRAY,
                                Formatting.BOLD));
        List<Text> lore = new ArrayList<>();
        if (house == null) {
            lore.add(line("Najpierw wybierz odbiorcę.", Formatting.DARK_GRAY));
        } else if (amount <= 0) {
            lore.add(line("Najpierw ustaw kwotę.", Formatting.DARK_GRAY));
        } else {
            lore.add(line("Prosto do ", Formatting.GRAY)
                    .append(plain(house.name).formatted(Formatting.LIGHT_PURPLE)));
            lore.add(Text.empty());
            lore.add(line("Podnosi limit stołu, a miasto nie", Formatting.DARK_GRAY));
            lore.add(line("dostaje z tego nic z powrotem.", Formatting.DARK_GRAY));
            lore.add(Text.empty());
            lore.add(line("Wszyscy na serwerze dostaną info.", Formatting.DARK_GRAY));
        }
        tag.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return tag;
    }

    private ItemStack theLot() {
        long purse = TrapCity.treasury();
        boolean can = purse > 0 && amount < purse;
        ItemStack tag = new ItemStack(can ? Items.HOPPER : Items.GRAY_DYE);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Wszystko").formatted(can ? Formatting.YELLOW : Formatting.DARK_GRAY,
                        Formatting.BOLD));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line(can ? "Ustawi na całe " + purse + "e."
                        : purse > 0 ? "Już ustawione na maksimum."
                        : "Nie ma czego ustawiać.",
                        can ? Formatting.GRAY : Formatting.DARK_GRAY),
                Text.empty(),
                line("Ustawia kwotę. Jeszcze nie przekazuje.", Formatting.DARK_GRAY))));
        return tag;
    }

    private ItemStack back() {
        ItemStack tag = new ItemStack(Items.ARROW);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Powrót do kasy").formatted(Formatting.GRAY, Formatting.BOLD));
        return tag;
    }

    private ItemStack about() {
        ItemStack tag = new ItemStack(Items.BELL);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Czym jest dotacja").formatted(Formatting.GOLD, Formatting.BOLD));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line("Kasyno bez pieniędzy odrzuca zakłady", Formatting.GRAY),
                line("i traci na tym reputację.", Formatting.GRAY),
                Text.empty(),
                line("Miasto może mu tych pieniędzy dołożyć.", Formatting.GRAY),
                Text.empty(),
                line("To dotacja, nie pożyczka. Wraca tylko", Formatting.DARK_GRAY),
                line("podatek od postawionych zakładów.", Formatting.DARK_GRAY))));
        return tag;
    }

    // --- clicking -------------------------------------------------------------

    @Override
    public void onSlotClick(int index, int button, SlotActionType type, PlayerEntity clicker) {
        if (index < 0 || index >= SIZE) {
            super.onSlotClick(index, button, type, clicker);
            return;
        }
        if (index == BACK_SLOT) {
            click(SoundEvents.BLOCK_VAULT_OPEN_SHUTTER, 0.9F);
            who.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                    (syncId, inventory, ignored) -> new CityScreenHandler(syncId, inventory),
                    Text.literal("Kasa miasta").formatted(Formatting.GOLD)));
            return;
        }
        List<TrapHouse.House> houses = houses();
        int seat = index - HOUSES_FROM;
        if (seat >= 0 && seat < houses.size()) {
            target = houses.get(seat).id;
            click(SoundEvents.UI_BUTTON_CLICK.value(), 1.2F);
            paint();
            return;
        }
        int dial = index - DIAL_FROM;
        if (dial >= 0 && dial < STEPS.length && STEPS[dial] != 0) {
            int after = TrapMath.stepped(amount, STEPS[dial], TrapCity.treasury());
            boolean moved = after != amount;
            amount = after;
            // Up the scale as the figure climbs, so a dial being turned reads
            // as one gesture rather than nine unrelated clicks.
            click(SoundEvents.BLOCK_NOTE_BLOCK_HAT.value(),
                    moved ? 0.8F + dial * 0.1F : 0.5F);
            paint();
            return;
        }
        if (index == ALL_SLOT) {
            amount = TrapMath.stepped(0, Integer.MAX_VALUE, TrapCity.treasury());
            click(SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), 1.4F);
            paint();
            return;
        }
        if (index == GIVE_SLOT) {
            String no = TrapCity.donate(who, chosen(), amount);
            if (no != null) {
                who.sendMessage(Text.literal(no).formatted(Formatting.GRAY), true);
                click(SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), 0.7F);
            } else {
                amount = 0;
                click(SoundEvents.BLOCK_VAULT_INSERT_ITEM, 1.0F);
                who.getWorld().playSound(null, who.getBlockPos(),
                        SoundEvents.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 0.5F, 1.6F);
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
