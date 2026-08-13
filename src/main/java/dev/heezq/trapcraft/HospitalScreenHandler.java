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
 * The board at the end of the ward.
 *
 * Deliberately the mailbox screen with different rows, because it is the same
 * job: a checklist that goes from grey to real as the building gets closer to
 * passing, and a list of the people in it.
 *
 *   [the ward] . [beds][floor][sealed][lit][built][cupboard] . [again]
 *   [who is in, one slot each] .
 *   [what the city is paying] . [what's next] . . .
 *
 * Nothing is buyable and nothing is takeable. The only button is "look again".
 */
public class HospitalScreenHandler extends ScreenHandler {
    private static final int ROWS = 3;
    private static final int SIZE = ROWS * 9;

    private static final int WARD_SLOT = 0;
    private static final int BEDS_SLOT = 2;
    private static final int FLOOR_SLOT = 3;
    private static final int SEALED_SLOT = 4;
    private static final int LIGHT_SLOT = 5;
    private static final int SHELL_SLOT = 6;
    private static final int SUPPLY_SLOT = 7;
    private static final int AGAIN_SLOT = 8;
    private static final int LIST_FROM = 9;
    private static final int BILL_SLOT = 18;
    private static final int NEXT_SLOT = 22;

    /** Patient slots on the middle row, and no more. Nobody reads the tenth. */
    private static final int SHOWN = 9;

    private final SimpleInventory display = new SimpleInventory(SIZE);
    private final ServerPlayerEntity who;
    private final BlockPos pos;
    private TrapHospitals.Ward ward;
    private TrapHomes.Readout reading;

    public HospitalScreenHandler(int syncId, PlayerInventory playerInventory,
                                 TrapHospitals.Ward ward, BlockPos pos) {
        super(ScreenHandlerType.GENERIC_9X3, syncId);
        this.who = (ServerPlayerEntity) playerInventory.player;
        this.ward = ward;
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
        // Re-read the register as well as the room: a ward opened from THIS
        // screen a second ago is one this handler was constructed without.
        ward = TrapHospitals.at(world, pos);
        reading = ward == null ? TrapHospitals.look(world, pos)
                : TrapHospitals.inspect(world, ward);
        paint();
    }

    // --- drawing --------------------------------------------------------------

    private void paint() {
        ItemStack filler = new ItemStack(Items.BLACK_STAINED_GLASS_PANE);
        filler.set(DataComponentTypes.CUSTOM_NAME, plain(" "));
        for (int index = 0; index < SIZE; index++) {
            display.setStack(index, filler.copy());
        }

        display.setStack(WARD_SLOT, plaque());
        display.setStack(BEDS_SLOT, count(Items.WHITE_BED, "Łóżka",
                reading.beds() + " z wymaganych " + TrapHospitals.MIN_BEDS,
                reading.beds() >= TrapHospitals.MIN_BEDS
                        ? "Każde łóżko to jeden pacjent leczony naraz."
                        : "Jedno łóżko to pokój gościnny, nie oddział.",
                reading.beds() >= TrapHospitals.MIN_BEDS));
        display.setStack(FLOOR_SLOT, count(Items.SMOOTH_QUARTZ, "Podłoga",
                reading.floor() + " z " + TrapHospitals.MIN_FLOOR + " kratek",
                reading.floor() >= TrapHospitals.MIN_FLOOR
                        ? "Dość miejsca, żeby przewieźć nosze."
                        : "Za ciasno, żeby pracować.",
                reading.floor() >= TrapHospitals.MIN_FLOOR));
        display.setStack(SEALED_SLOT, count(Items.BRICKS, "Szczelność",
                reading.sealed() ? "Szczelny" : reading.clash() ? "Czyjś dom" : "Dziurawy",
                reading.sealed() ? "Ściany, podłoga, dach i drzwi."
                        : reading.clash()
                        ? "Nachodzi na zarejestrowany dom. Szpital potrzebuje "
                        + "własnego budynku."
                        : "Jest dziura. Potrzebne ściany, podłoga, sufit i drzwi.",
                reading.sealed()));
        display.setStack(LIGHT_SLOT, count(Items.LANTERN, "Światło",
                reading.dark() == 0 ? "Wszędzie" : "ciemnych: " + reading.dark(),
                reading.dark() == 0 ? "Nikt tu nie operuje przy świeczce."
                        : "Oddział nie może mieć ciemnych kątów. Jaśniej niż "
                        + HomeSurvey.DARK_AT + " na wysokości głowy, wszędzie.",
                reading.sealed() && reading.dark() == 0));
        display.setStack(SHELL_SLOT, count(Items.BRICK, "Zbudowane, nie wykopane",
                Math.round(reading.finished() * 100) + "% z "
                        + Math.round(TrapHospitals.MIN_SHELL * 100) + "%",
                reading.finished() >= TrapHospitals.MIN_SHELL
                        ? "Czyste ściany."
                        : "Ziemia, piasek, żwir i goły kamień się nie liczą."
                        + (reading.roughest().isEmpty() ? ""
                        : " Głównie " + reading.roughest() + "."),
                reading.finished() >= TrapHospitals.MIN_SHELL));
        display.setStack(SUPPLY_SLOT, count(Items.CHEST, "Zaopatrzenie",
                reading.storage() ? "Jest szafka" : "Brak",
                reading.storage() ? "Jest gdzie trzymać bandaże."
                        : "Skrzynia albo beczka, w środku.",
                reading.storage()));
        display.setStack(AGAIN_SLOT, again());

        List<TrapHospitals.Patient> here = new ArrayList<>();
        if (ward != null) {
            for (TrapHospitals.Patient patient : TrapHospitals.patients()) {
                if (ward.id().equals(patient.ward())) {
                    here.add(patient);
                }
            }
        }
        for (int at = 0; at < Math.min(SHOWN, ward == null ? 0 : ward.beds()); at++) {
            display.setStack(LIST_FROM + at, at < here.size() ? patient(here.get(at)) : empty());
        }
        display.setStack(BILL_SLOT, bill());
        display.setStack(NEXT_SLOT, next());
        sendContentUpdates();
    }

    private ItemStack plaque() {
        ItemStack tag = new ItemStack(ward == null ? Items.BARRIER
                : ward.open() ? Items.GLISTERING_MELON_SLICE : Items.WITHER_ROSE);
        tag.set(DataComponentTypes.CUSTOM_NAME, plain(ward == null ? "To nie jest szpital"
                        : ward.name())
                .formatted(ward == null ? Formatting.RED : Formatting.WHITE, Formatting.BOLD));
        List<Text> lore = new ArrayList<>();
        if (ward == null) {
            lore.add(line("Kliknij blok jeszcze raz, gdy lista", Formatting.GRAY));
            lore.add(line("poniżej będzie zielona.", Formatting.GRAY));
        } else {
            lore.add(line(ward.open() ? "Otwarty  --  wolnych łóżek " + ward.free() + " z " + ward.beds()
                    : "Zamknięty", ward.open() ? Formatting.GREEN : Formatting.RED,
                    Formatting.BOLD));
            lore.add(line("Właściciel: " + ward.ownerName(), Formatting.DARK_GRAY));
            lore.add(Text.empty());
            lore.add(line("wyleczonych: " + ward.treated(), Formatting.GRAY));
            lore.add(Text.empty());
            lore.add(line("Weź przedmiot nazwany na kowadle", Formatting.YELLOW));
            lore.add(line("i kliknij tutaj, żeby zmienić nazwę.", Formatting.YELLOW));
        }
        tag.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return tag;
    }

    private ItemStack patient(TrapHospitals.Patient patient) {
        ItemStack tag = new ItemStack(Items.ROTTEN_FLESH);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain(patient.who()).formatted(Formatting.AQUA, Formatting.BOLD));
        TrapHomes.Home home = TrapHomes.byId(patient.home());
        List<Text> lore = new ArrayList<>();
        lore.add(line(home == null ? "Bez stałego adresu" : "z " + home.name(),
                Formatting.DARK_GRAY));
        lore.add(Text.empty());
        lore.add(patient.untreated() > 0
                ? line("Nieleczony -- dzień " + patient.untreated() + " z "
                        + TrapHospitals.LOST_DAYS, Formatting.RED)
                : line("W trakcie leczenia. Wyjdzie jutro.", Formatting.GREEN));
        lore.add(line("Dopóki tu leży, nic nie zarabia.", Formatting.GRAY));
        tag.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return tag;
    }

    private ItemStack empty() {
        ItemStack tag = new ItemStack(Items.LIGHT_GRAY_STAINED_GLASS_PANE);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Wolne łóżko").formatted(Formatting.DARK_GRAY));
        return tag;
    }

    /** What the city is being charged, and what happens when it cannot be. */
    private ItemStack bill() {
        ItemStack tag = new ItemStack(Items.EMERALD);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Miasto płaci").formatted(Formatting.GOLD, Formatting.BOLD)
                        .append(plain("  " + TrapHospitals.bill() + "e dziennie za pacjenta")
                                .formatted(Formatting.GRAY)));
        List<Text> lore = new ArrayList<>();
        lore.add(line("Z kasy miasta do kieszeni lekarzy --", Formatting.GRAY));
        lore.add(line("a stamtąd z powrotem do twoich sklepów.", Formatting.GRAY));
        lore.add(Text.empty());
        lore.add(line("Kasa miasta: " + TrapCity.treasury() + "e", TrapCity.treasury()
                >= TrapHospitals.bill() ? Formatting.GREEN : Formatting.RED));
        lore.add(TrapCity.built(TrapCity.Work.CLINIC)
                ? line("The Clinic is built: " + Math.round((1 - TrapHospitals.CLINIC_OFF) * 100)
                        + "% taniej za każde leczenie.", Formatting.GREEN)
                : line("Zbuduj Przychodnię, a każdy rachunek spadnie o "
                        + Math.round((1 - TrapHospitals.CLINIC_OFF) * 100) + "%.",
                        Formatting.DARK_GRAY));
        lore.add(Text.empty());
        lore.add(line("Pusta kasa miasta = nikt nie jest leczony,", Formatting.DARK_GRAY));
        lore.add(line("a " + TrapHospitals.LOST_DAYS + " dni bez leczenia to pogrzeb.",
                Formatting.DARK_GRAY));
        tag.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return tag;
    }

    /** One thing to do next, in one sentence. */
    private ItemStack next() {
        String say = TrapHospitals.fault(reading);
        if (say == null) {
            say = ward == null
                    ? "Nic -- kliknij sam blok, a się otworzy."
                    : TrapCity.treasury() < TrapHospitals.bill()
                    ? "Nikogo. Kasa miasta nie ma na lekarzy, "
                    + "though: " + TrapCity.treasury() + "e."
                    : "Nikogo. Czekaj, aż ktoś zostanie ugryziony -- i licz, że nie.";
        }
        ItemStack tag = new ItemStack(ward != null && ward.open()
                ? Items.GOLDEN_APPLE : Items.WRITABLE_BOOK);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Co dalej").formatted(Formatting.GOLD, Formatting.BOLD));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line(say, Formatting.WHITE))));
        return tag;
    }

    private ItemStack again() {
        ItemStack tag = new ItemStack(Items.SPYGLASS);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Look again").formatted(Formatting.YELLOW, Formatting.BOLD));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line("Obchodzi ściany i sprawdza od nowa.", Formatting.GRAY),
                Text.empty(),
                line("I tak robi to samo co kilka", Formatting.DARK_GRAY),
                line("of minutes anyway.", Formatting.DARK_GRAY))));
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
        // gesture as a shop's till: hold something you named in an anvil and
        // click. Any ward, not just a new one -- a hospital outlives the
        // afternoon somebody happened to place the block.
        if (index == WARD_SLOT && ward != null) {
            Text named = who.getMainHandStack().get(DataComponentTypes.CUSTOM_NAME);
            if (named == null || named.getString().isBlank()) {
                who.sendMessage(plain("Weź przedmiot nazwany na kowadle i kliknij "
                        + "tabliczkę, żeby nazwać szpital.").formatted(Formatting.GRAY), true);
                return;
            }
            TrapHospitals.rename(ward, named.getString());
            who.getWorld().playSound(null, who.getBlockPos(), SoundEvents.BLOCK_ANVIL_USE,
                    SoundCategory.PLAYERS, 0.6F, 1.4F);
            who.sendMessage(plain("Now known as ").formatted(Formatting.GRAY)
                    .append(plain(ward.name()).formatted(Formatting.GOLD)), true);
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
