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
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * The duty desk.
 *
 * The ward's board with different rows, because it is the same job twice: a
 * checklist that goes from grey to real as the building gets closer to
 * passing, and a list of who is currently inside it.
 *
 *   [the nick] . [cells][floor][sealed][lit][built][armoury] . [again]
 *   [cells, one slot each] .
 *   [the shift] . [the blotter] . [what's next] . [this nick's record]
 *
 * Nothing is buyable and nothing is takeable. The budget is deliberately a
 * READOUT here and a dial at the vault: what the force costs is the council's
 * decision and the council meets at the treasury, not at the front desk of
 * whichever nick somebody happens to be standing in.
 */
public class PoliceScreenHandler extends ScreenHandler {
    private static final int ROWS = 3;
    private static final int SIZE = ROWS * 9;

    private static final int NICK_SLOT = 0;
    private static final int CELLS_SLOT = 2;
    private static final int FLOOR_SLOT = 3;
    private static final int SEALED_SLOT = 4;
    private static final int LIGHT_SLOT = 5;
    private static final int SHELL_SLOT = 6;
    private static final int ARMS_SLOT = 7;
    private static final int AGAIN_SLOT = 8;
    private static final int LIST_FROM = 9;
    private static final int SHIFT_SLOT = 18;
    private static final int BLOTTER_SLOT = 20;
    private static final int NEXT_SLOT = 22;
    private static final int RECORD_SLOT = 24;

    /** Cell slots on the middle row, and no more. Nobody reads the tenth. */
    private static final int SHOWN = 9;

    private final SimpleInventory display = new SimpleInventory(SIZE);
    private final ServerPlayerEntity who;
    private final BlockPos pos;
    private TrapPolice.Station station;
    private TrapHomes.Readout reading;

    public PoliceScreenHandler(int syncId, PlayerInventory playerInventory,
                               TrapPolice.Station station, BlockPos pos) {
        super(ScreenHandlerType.GENERIC_9X3, syncId);
        this.who = (ServerPlayerEntity) playerInventory.player;
        this.station = station;
        this.pos = pos;

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
        survey();
    }

    private void survey() {
        ServerWorld world = (ServerWorld) who.getWorld();
        // Re-read the register as well as the room: a station opened from THIS
        // screen a second ago is one this handler was constructed without.
        station = TrapPolice.at(world, pos);
        reading = station == null ? TrapPolice.look(world, pos)
                : TrapPolice.inspect(world, station);
        paint();
    }

    // --- drawing --------------------------------------------------------------

    private void paint() {
        ItemStack filler = new ItemStack(Items.BLACK_STAINED_GLASS_PANE);
        filler.set(DataComponentTypes.CUSTOM_NAME, plain(" "));
        for (int index = 0; index < SIZE; index++) {
            display.setStack(index, filler.copy());
        }

        display.setStack(NICK_SLOT, plaque());
        display.setStack(CELLS_SLOT, count(Items.IRON_BARS, "Cele",
                reading.beds() + " z wymaganych " + TrapPolice.MIN_CELLS,
                reading.beds() >= TrapPolice.MIN_CELLS
                        ? "Każde łóżko to jedna cela -- i jeden etat, "
                        + "który miasto może obsadzić."
                        : "Jedna prycza to pokój gościnny, nie areszt.",
                reading.beds() >= TrapPolice.MIN_CELLS));
        display.setStack(FLOOR_SLOT, count(Items.STONE_BRICKS, "Podłoga",
                reading.floor() + " z " + TrapPolice.MIN_FLOOR + " kratek",
                reading.floor() >= TrapPolice.MIN_FLOOR
                        ? "Dość miejsca na dyżurkę i cele."
                        : "Za ciasno na komisariat.",
                reading.floor() >= TrapPolice.MIN_FLOOR));
        // Four states, not two. "Zamurowany" and "Dziurawy" are opposite
        // problems that both arrive as sealed==false, and the leak's
        // coordinates are the only thing that turns "there is a hole" into a
        // job somebody can go and do. Copied from the mailbox board, which has
        // told the truth about this since the day it shipped.
        display.setStack(SEALED_SLOT, count(Items.BRICKS, "Szczelność",
                reading.sealed() ? "Szczelny" : reading.clash() ? "Czyjś dom"
                        : reading.buried() ? "Zamurowany" : "Dziurawy",
                reading.sealed() ? "Ściany, podłoga, dach i drzwi."
                        : reading.clash()
                        ? "Nachodzi na zarejestrowany dom. Komisariat potrzebuje "
                        + "własnego budynku."
                        : reading.buried()
                        ? "Blok stoi w litej ścianie. Postaw go w powietrzu "
                        + "wewnątrz pomieszczenia."
                        : "Jest dziura. Pomiar od " + TrapPolice.where(reading.measuredFrom())
                        + ", ucieka na " + TrapPolice.where(reading.leak()) + ".",
                reading.sealed()));
        display.setStack(LIGHT_SLOT, count(Items.LANTERN, "Światło",
                reading.dark() == 0 ? "Wszędzie" : "ciemnych: " + reading.dark(),
                reading.dark() == 0 ? "Na komendzie pali się całą noc."
                        : "Komisariat nie może mieć ciemnych kątów. Jaśniej niż "
                        + HomeSurvey.DARK_AT + " na wysokości głowy, wszędzie.",
                reading.sealed() && reading.dark() == 0));
        display.setStack(SHELL_SLOT, count(Items.BRICK, "Zbudowane, nie wykopane",
                Math.round(reading.finished() * 100) + "% z "
                        + Math.round(TrapPolice.MIN_SHELL * 100) + "%",
                reading.finished() >= TrapPolice.MIN_SHELL
                        ? "Porządne mury."
                        : "Ziemia, piasek, żwir i goły kamień się nie liczą."
                        + (reading.roughest().isEmpty() ? ""
                        : " Głównie " + reading.roughest() + "."),
                reading.finished() >= TrapPolice.MIN_SHELL));
        display.setStack(ARMS_SLOT, count(Items.CHEST, "Zbrojownia",
                reading.storage() ? "Jest" : "Brak",
                reading.storage() ? "Jest gdzie trzymać pałki i kajdanki."
                        : "Skrzynia albo beczka, w środku.",
                reading.storage()));
        display.setStack(AGAIN_SLOT, again());

        List<TrapPolice.Prisoner> here = new ArrayList<>();
        if (station != null) {
            for (TrapPolice.Prisoner prisoner : TrapPolice.prisoners()) {
                if (station.id().equals(prisonerStation(prisoner))) {
                    here.add(prisoner);
                }
            }
        }
        for (int at = 0; at < Math.min(SHOWN, station == null ? 0 : station.cells()); at++) {
            display.setStack(LIST_FROM + at, at < here.size() ? held(here.get(at)) : empty());
        }
        display.setStack(SHIFT_SLOT, shift());
        display.setStack(BLOTTER_SLOT, blotter());
        display.setStack(NEXT_SLOT, next());
        display.setStack(RECORD_SLOT, record());
        sendContentUpdates();
    }

    /**
     * Which nick this prisoner is in.
     *
     * A reach into the record rather than a getter, and deliberately the only
     * one: the station id is the single field this screen needs and exposing
     * it as public API would invite somebody to re-home a prisoner from
     * outside the file that runs the cells.
     */
    private static java.util.UUID prisonerStation(TrapPolice.Prisoner prisoner) {
        return prisoner.station;
    }

    private ItemStack plaque() {
        ItemStack tag = new ItemStack(station == null ? Items.BARRIER
                : station.open() ? Items.SHIELD : Items.WITHER_ROSE);
        tag.set(DataComponentTypes.CUSTOM_NAME, plain(station == null
                        ? "To nie jest komisariat" : station.name())
                .formatted(station == null ? Formatting.RED : Formatting.WHITE,
                        Formatting.BOLD));
        List<Text> lore = new ArrayList<>();
        if (station == null) {
            lore.add(line("Kliknij blok jeszcze raz, gdy lista", Formatting.GRAY));
            lore.add(line("poniżej będzie zielona.", Formatting.GRAY));
        } else {
            lore.add(line(station.open()
                            ? "Otwarty  --  na służbie " + station.onShift()
                            + ", wolnych cel " + station.free() + " z " + station.cells()
                            : "Zamknięty",
                    station.open() ? Formatting.GREEN : Formatting.RED, Formatting.BOLD));
            lore.add(line("Właściciel: " + station.ownerName(), Formatting.DARK_GRAY));
            lore.add(Text.empty());
            lore.add(line("Weź przedmiot nazwany na kowadle", Formatting.YELLOW));
            lore.add(line("i kliknij tutaj, żeby zmienić nazwę.", Formatting.YELLOW));
        }
        tag.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return tag;
    }

    private ItemStack held(TrapPolice.Prisoner prisoner) {
        ItemStack tag = new ItemStack(Items.CHAIN);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain(prisoner.who()).formatted(Formatting.WHITE, Formatting.BOLD));
        long left = prisoner.until() - TrapMarket.today(who.getServer());
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line(prisoner.crime(), Formatting.RED),
                Text.empty(),
                line(left <= 0 ? "Wychodzi dziś." : "Zostało " + left + " dni.",
                        left <= 0 ? Formatting.GREEN : Formatting.GRAY),
                line("Zajęta cela to jeden etat mniej", Formatting.DARK_GRAY),
                line("do obsadzenia na tym komisariacie.", Formatting.DARK_GRAY))));
        return tag;
    }

    private ItemStack empty() {
        ItemStack tag = new ItemStack(Items.LIGHT_GRAY_STAINED_GLASS_PANE);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Pusta cela").formatted(Formatting.DARK_GRAY));
        return tag;
    }

    /** What the city is buying, and the one place it can be changed. */
    private ItemStack shift() {
        int budget = TrapPolice.budget();
        int funded = TrapPolice.funded();
        boolean paid = funded >= budget;
        ItemStack tag = new ItemStack(budget > 0 ? Items.EMERALD : Items.GRAY_DYE);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Budżet komendy").formatted(Formatting.GOLD, Formatting.BOLD)
                        .append(plain("  " + budget + "e dziennie")
                                .formatted(budget > 0 ? Formatting.WHITE : Formatting.RED)));
        List<Text> lore = new ArrayList<>();
        lore.add(line("Na etacie " + TrapPolice.force() + ", na ulicy "
                + TrapPolice.onDuty() + ".", Formatting.WHITE));
        lore.add(line("Wyposażenie " + TrapPolice.gear() + " z " + TrapPolice.TOP_GEAR
                + "  --  szybsi, dalej widzą, mocniej biją.", Formatting.GRAY));
        lore.add(line("Golemy " + TrapPolice.onGuard() + " z " + TrapPolice.golems()
                        + (TrapCity.level(TrapCity.Work.GOLEMS) > 0
                        ? "  --  wychodzi najwyżej tylu, ilu jest funkcjonariuszy."
                        : "  --  do kupienia w inwestycjach miejskich."),
                TrapPolice.onGuard() > 0 ? Formatting.GRAY : Formatting.DARK_GRAY));
        lore.add(Text.empty());
        lore.add(line("Jeden funkcjonariusz: " + TrapPolice.WAGE + "e dziennie.",
                Formatting.DARK_GRAY));
        lore.add(line("Każde " + TrapPolice.GEAR_AT + "e budżetu to stopień wyposażenia.",
                Formatting.DARK_GRAY));
        lore.add(line("Więcej etatów niż cel się nie da obsadzić.", Formatting.DARK_GRAY));
        lore.add(Text.empty());
        if (!paid) {
            lore.add(line("Kasa miasta dała tylko " + funded + "e.", Formatting.RED));
        }
        lore.add(line("Kasa miasta: " + TrapCity.treasury() + "e",
                TrapCity.treasury() >= budget ? Formatting.GREEN : Formatting.RED));
        lore.add(Text.empty());
        lore.add(line("Suwak jest przy SKARBCU MIASTA.", Formatting.YELLOW));
        lore.add(line("To rada uchwala budżet, nie dyżurny.", Formatting.DARK_GRAY));
        tag.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return tag;
    }

    /** What the town is doing to itself, in five lines. */
    private ItemStack blotter() {
        ItemStack tag = new ItemStack(Items.WRITTEN_BOOK);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Statystyka").formatted(Formatting.GOLD, Formatting.BOLD));
        List<Text> lore = new ArrayList<>();
        for (TrapCrime.Kind kind : TrapCrime.Kind.values()) {
            lore.add(line(kind.display() + "  " + TrapCrime.countOf(kind),
                    kind == TrapCrime.Kind.MURDER ? Formatting.DARK_RED : Formatting.WHITE));
        }
        lore.add(Text.empty());
        lore.add(line("Wykrytych " + TrapCrime.solved() + " z " + TrapCrime.total()
                + ", umorzonych " + TrapCrime.wentCold() + ".", Formatting.GRAY));
        lore.add(line("Skradziono " + TrapCrime.stolen() + "e, odzyskano "
                + TrapCrime.recovered() + "e.", Formatting.GRAY));
        lore.add(Text.empty());
        lore.add(line("Otwartych spraw: " + TrapCrime.open().size(),
                TrapCrime.open().isEmpty() ? Formatting.DARK_GRAY : Formatting.YELLOW));
        lore.add(line("/crime pokazuje, co je napędza.", Formatting.DARK_GRAY));
        tag.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return tag;
    }

    /** One thing to do next, in one sentence. */
    private ItemStack next() {
        String say = TrapPolice.fault(reading);
        if (say == null) {
            say = station == null
                    ? "Nic -- kliknij sam blok, a się otworzy."
                    : TrapPolice.budget() < TrapPolice.WAGE
                    ? "Podnieś budżet przy skarbcu. Poniżej " + TrapPolice.WAGE
                    + "e dziennie nikt nie wychodzi na ulicę."
                    : TrapPolice.force() < TrapPolice.budget() / TrapPolice.WAGE
                    ? "Dostaw cele. Miasto płaci za "
                    + TrapPolice.budget() / TrapPolice.WAGE + " etatów, a cel jest "
                    + TrapPolice.cells() + "."
                    : "Nic. Patrol jest na mieście.";
        }
        ItemStack tag = new ItemStack(station != null && station.open()
                ? Items.SHIELD : Items.WRITABLE_BOOK);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Co dalej").formatted(Formatting.GOLD, Formatting.BOLD));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line(say, Formatting.WHITE))));
        return tag;
    }

    /** What this particular nick has done, which is the plaque nobody else has. */
    private ItemStack record() {
        ItemStack tag = new ItemStack(Items.PAPER);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Dorobek komisariatu").formatted(Formatting.GOLD, Formatting.BOLD));
        List<Text> lore = new ArrayList<>();
        lore.add(line("Zatrzymań: " + (station == null ? 0 : station.arrests()),
                Formatting.WHITE));
        lore.add(line("Potworów zdjętych z ulicy: "
                + (station == null ? 0 : station.calls()), Formatting.WHITE));
        lore.add(Text.empty());
        lore.add(line("Mandatów wystawiono na " + TrapPolice.fines() + "e.",
                Formatting.GRAY));
        lore.add(line("Wypłat dla funkcjonariuszy: " + TrapPolice.spent() + "e.",
                Formatting.GRAY));
        lore.add(Text.empty());
        lore.add(line("Odstraszanie " + Math.round(TrapPolice.deterrence() * 100)
                + "% -- tyle przestępstw", Formatting.DARK_GRAY));
        lore.add(line("w ogóle nie dochodzi do skutku.", Formatting.DARK_GRAY));
        tag.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return tag;
    }

    private ItemStack again() {
        ItemStack tag = new ItemStack(Items.SPYGLASS);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Sprawdź ponownie").formatted(Formatting.YELLOW, Formatting.BOLD));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line("Obchodzi ściany i sprawdza od nowa.", Formatting.GRAY),
                Text.empty(),
                line("I tak robi to samo co kilka", Formatting.DARK_GRAY),
                line("minut.", Formatting.DARK_GRAY))));
        return tag;
    }

    private ItemStack count(Item icon, String title, String value, String blurb, boolean good) {
        ItemStack tag = new ItemStack(good ? icon : Items.GRAY_DYE);
        tag.set(DataComponentTypes.CUSTOM_NAME, plain(title)
                .formatted(good ? Formatting.WHITE : Formatting.RED, Formatting.BOLD)
                .append(plain("  " + value).formatted(Formatting.GRAY)));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line(blurb, good ? Formatting.GRAY : Formatting.RED))));
        return tag;
    }

    // --- clicking -------------------------------------------------------------

    @Override
    public void onSlotClick(int index, int button, SlotActionType type, PlayerEntity clicker) {
        if (index < 0 || index >= SIZE) {
            super.onSlotClick(index, button, type, clicker);
            return;
        }
        if (index == AGAIN_SLOT) {
            survey();
            who.getWorld().playSound(null, who.getBlockPos(), SoundEvents.ITEM_SPYGLASS_USE,
                    SoundCategory.PLAYERS, 0.7F, 1.2F);
            return;
        }
        // The plaque is the name, so the plaque is where you change it. Same
        // gesture as a ward's and a till's: hold something you named in an
        // anvil and click.
        if (index == NICK_SLOT && station != null) {
            Text named = who.getMainHandStack().get(DataComponentTypes.CUSTOM_NAME);
            if (named == null || named.getString().isBlank()) {
                who.sendMessage(plain("Weź przedmiot nazwany na kowadle i kliknij "
                        + "tabliczkę, żeby nazwać komisariat.")
                        .formatted(Formatting.GRAY), true);
                return;
            }
            TrapPolice.rename(station, named.getString());
            who.getWorld().playSound(null, who.getBlockPos(), SoundEvents.BLOCK_ANVIL_USE,
                    SoundCategory.PLAYERS, 0.6F, 1.4F);
            who.sendMessage(plain("Nowa nazwa: ").formatted(Formatting.GRAY)
                    .append(plain(station.name()).formatted(Formatting.GOLD)), true);
            paint();
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

    /**
     * A span that inherits nothing it did not ask for.
     *
     * Bold is cleared as well as italic, and that is not cosmetic: a slot name
     * built as {@code plain(title).formatted(BOLD).append(plain(value))} hands
     * the child the parent's bold, so the VALUE ends up shouting alongside the
     * label and the eye has nowhere to land. Same bug, same fix, same reason
     * as {@link TrapNotes}.
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
