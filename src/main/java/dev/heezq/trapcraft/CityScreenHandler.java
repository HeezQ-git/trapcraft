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
    private static final int POLICE_SLOT = 9;
    private static final int TAKE_FROM = 11;
    private static final int GIVE_SLOT = 16;
    /**
     * The council's standing order.
     *
     * Slot 17 because it is the one square on the top two rows nothing else
     * wanted, and it sits at the end of the row above the public works -- which
     * is the right place for it, since the order only exists BECAUSE of them.
     */
    private static final int ORDER_SLOT = 17;
    private static final int ABOUT_SLOT = 26;
    private static final int WORKS_FROM = 18;

    /** What one click of the police dial is worth, and what a shift-click is. */
    private static final int BEAT_STEP = TrapPolice.BUDGET_STEP;
    private static final int BEAT_LEAP = TrapPolice.BUDGET_STEP * 4;

    /** What each withdraw button is worth. Null means everything. */
    private static final Integer[] TAKES = {64, 256, 1024, null};

    /** What one click of "pay in" is worth. Right-click gives everything. */
    private static final int PAY_STEP = 1024;

    /** One per public work, in declaration order. */
    private static final Item[] WORK_ICONS = {
            Items.CROSSBOW, Items.STONE_BRICKS, Items.LANTERN, Items.GOLD_INGOT,
            Items.GLISTERING_MELON_SLICE, Items.MINECART, Items.BOOK, Items.IRON_BLOCK};

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
        // The police dial is a click that SPENDS every day from then on, and
        // it sits one slot off the withdraw row. A fifth take button would
        // land on it and the symptom would be "Take 4096e hired four coppers".
        if (POLICE_SLOT >= TAKE_FROM || POLICE_SLOT == GIVE_SLOT
                || POLICE_SLOT == LEDGER_SLOT) {
            throw new IllegalStateException("city board: the police dial is under something");
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
        display.setStack(POLICE_SLOT, beat());
        for (int i = 0; i < TAKES.length; i++) {
            display.setStack(TAKE_FROM + i, take(TAKES[i]));
        }
        display.setStack(GIVE_SLOT, donations());
        display.setStack(ORDER_SLOT, order());
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

    /**
     * The one standing cost the council actually chooses.
     *
     * Everything else at this counter is a purchase you make once. This is a
     * subscription, and it is the only dial in the mod that a player can set
     * to a number the treasury cannot sustain -- deliberately, because the
     * interesting decision is exactly "can we afford this many coppers", and
     * a dial that refused to move past what today's purse covers would answer
     * the question for you.
     *
     * At the vault rather than at the station because a budget is the
     * council's, not the duty sergeant's. See the note on PoliceScreenHandler.
     */
    private ItemStack beat() {
        int budget = TrapPolice.budget();
        int stations = TrapPolice.all().size();
        ItemStack tag = new ItemStack(stations == 0 ? Items.GRAY_DYE
                : budget > 0 ? Items.SHIELD : Items.BARRIER);
        tag.set(DataComponentTypes.CUSTOM_NAME, plain("Policja")
                .formatted(stations == 0 ? Formatting.DARK_GRAY : Formatting.AQUA,
                        Formatting.BOLD)
                .append(plain("   " + budget + "e dziennie")
                        .formatted(budget > 0 ? Formatting.GOLD : Formatting.RED)));
        List<Text> lore = new ArrayList<>();
        if (stations == 0) {
            lore.add(line("Nie ma komisariatu. Postaw blok", Formatting.RED));
            lore.add(line("komisariatu w gotowym budynku z celami.", Formatting.RED));
            lore.add(Text.empty());
            lore.add(line("Bez niego te pieniądze nie mają dokąd pójść.",
                    Formatting.DARK_GRAY));
        } else {
            lore.add(line("Na etacie " + TrapPolice.force() + " z "
                            + budget / TrapPolice.WAGE + " opłaconych   (cel: "
                            + TrapPolice.cells() + ")",
                    TrapPolice.force() < budget / TrapPolice.WAGE
                            ? Formatting.YELLOW : Formatting.WHITE));
            lore.add(line("Wyposażenie " + TrapPolice.gear() + " z " + TrapPolice.TOP_GEAR
                    + "  --  szybsi i dalej widzą.", Formatting.WHITE));
            lore.add(line("Zbija przestępczość o "
                            + Math.round(TrapPolice.deterrence() * 100) + "%.",
                    TrapPolice.deterrence() > 0 ? Formatting.GREEN : Formatting.RED));
            lore.add(Text.empty());
            lore.add(line("LPM  +" + BEAT_STEP + "e     PPM  -" + BEAT_STEP + "e",
                    Formatting.YELLOW));
            lore.add(line("Shift  o " + BEAT_LEAP + "e naraz. Do " + TrapPolice.MAX_BUDGET
                    + "e.", Formatting.YELLOW));
            lore.add(Text.empty());
            lore.add(line("Jeden funkcjonariusz: " + TrapPolice.WAGE + "e dziennie,",
                    Formatting.DARK_GRAY));
            lore.add(line("każde " + TrapPolice.GEAR_AT + "e to stopień wyposażenia.",
                    Formatting.DARK_GRAY));
            if (TrapPolice.funded() < budget) {
                lore.add(Text.empty());
                lore.add(line("Dziś kasa dała tylko " + TrapPolice.funded() + "e.",
                        Formatting.RED));
            }
        }
        tag.set(DataComponentTypes.LORE, new LoreComponent(lore));
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

    /**
     * What the council wants delivered, and whether you are carrying it.
     *
     * The icon is the goods themselves, which is the only sensible choice: a
     * player should be able to read the order off the shape of the item
     * without opening the tooltip, exactly as they read a shop shelf.
     *
     * Every state this can be in says which one it is out loud, because the
     * three ways to see nothing here -- everything built, purse too thin,
     * already filled today -- are indistinguishable from a broken feature
     * otherwise. Same rule {@code /visitors} is written to.
     */
    private ItemStack order() {
        TrapCity.Order order = TrapCity.order(who.getServer());
        if (order == null) {
            ItemStack tag = new ItemStack(Items.PAPER);
            tag.set(DataComponentTypes.CUSTOM_NAME,
                    plain("Zlecenia miejskie").formatted(Formatting.GRAY, Formatting.BOLD));
            List<Text> lore = new ArrayList<>();
            lore.add(line(!TrapCity.founded() ? "Nie ma miasta."
                            : TrapCity.filledToday(who.getServer())
                            ? "Dzisiejsze zlecenie już wykonane. Wróć jutro."
                            : "Kasa miasta nie ma na to zapasu.",
                    Formatting.GRAY));
            lore.add(line("Rada kupuje materiały, dopóki ma co budować.",
                    Formatting.DARK_GRAY));
            tag.set(DataComponentTypes.LORE, new LoreComponent(lore));
            return tag;
        }
        int wanted = order.lots() * order.entry().count();
        int have = 0;
        for (int slot = 0; slot < who.getInventory().size(); slot++) {
            if (order.entry().matches(who.getInventory().getStack(slot))) {
                have += who.getInventory().getStack(slot).getCount();
            }
        }
        ItemStack tag = order.entry().stack();
        tag.setCount(1);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Zlecenie: " + order.entry().label())
                        .formatted(Formatting.AQUA, Formatting.BOLD));
        List<Text> lore = new ArrayList<>();
        lore.add(line(wanted + " sztuk za " + order.paid() + "e",
                Formatting.GOLD, Formatting.BOLD));
        lore.add(line(have >= wanted ? "Masz wszystko. Kliknij, żeby oddać."
                        : "Masz " + have + " z " + wanted + ".",
                have >= wanted ? Formatting.GREEN : Formatting.RED));
        lore.add(line("Rada płaci lepiej niż lada i gorzej, niż lada bierze.",
                Formatting.DARK_GRAY));
        lore.add(line("Jedno zlecenie dziennie, dopóki jest co budować.",
                Formatting.DARK_GRAY));
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
        if (index == POLICE_SLOT) {
            if (TrapPolice.all().isEmpty()) {
                who.sendMessage(Text.literal("Nie ma komisariatu, któremu można płacić.")
                        .formatted(Formatting.GRAY), true);
                click(SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), 0.7F);
                return;
            }
            int step = type == SlotActionType.QUICK_MOVE ? BEAT_LEAP : BEAT_STEP;
            int was = TrapPolice.budget();
            TrapPolice.setBudget(was + (button == 1 ? -step : step));
            int now = TrapPolice.budget();
            click(now == was ? SoundEvents.BLOCK_NOTE_BLOCK_BASS.value()
                            : SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(),
                    now > was ? 1.4F : 0.8F);
            who.sendMessage(Text.empty()
                    .append(TrapNotes.say("Budżet policji  ", Formatting.GRAY))
                    .append(TrapNotes.say(now + "e/dzień", Formatting.GOLD, Formatting.BOLD))
                    .append(TrapNotes.say("   " + TrapPolice.force() + " na etacie   "
                                    + "wyposażenie " + TrapPolice.gear(),
                            Formatting.DARK_GRAY)), true);
            paint();
            return;
        }
        if (index == ORDER_SLOT) {
            TrapCity.Order order = TrapCity.order(who.getServer());
            int paid = order == null ? 0 : TrapCity.fill(who, order);
            if (paid <= 0) {
                who.sendMessage(Text.literal(order == null
                                ? "Rada nic dziś nie kupuje."
                                : "Nie masz przy sobie całego zamówienia.")
                        .formatted(Formatting.GRAY), true);
                click(SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), 0.7F);
            } else {
                click(SoundEvents.BLOCK_VAULT_INSERT_ITEM, 1.2F);
                who.sendMessage(TrapNotes.headline("Dostarczone  ", Formatting.GREEN)
                        .append(TrapNotes.say("rada zapłaciła " + paid + "e.",
                                Formatting.GRAY)), false);
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

    /**
     * A span that inherits nothing it did not ask for.
     *
     * Bold as well as italic, because every slot on this board is built as
     * {@code plain(label).formatted(BOLD).append(plain(value))} and the value
     * was quietly inheriting the label's weight -- so the whole board read as
     * one bold block with no hierarchy in it. See {@link TrapNotes}.
     */
    private static MutableText plain(String text) {
        return Text.literal(text)
                .styled(style -> style.withItalic(false).withBold(false));
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
