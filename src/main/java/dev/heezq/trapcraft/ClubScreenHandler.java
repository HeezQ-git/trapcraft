package dev.heezq.trapcraft;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Item;
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
 * The office behind the booth: the door charge, the till, and last night.
 *
 * One row, because there are only three decisions to make and a screen with
 * room for more invites somebody to add a fourth that nobody needed. The
 * interesting one is the door charge -- everything else is a number to look
 * at and a button to empty.
 */
public class ClubScreenHandler extends ScreenHandler {
    private static final int SIZE = 9;

    private static final int SIGN_SLOT = 0;
    private static final int DOOR_SLOT = 2;
    private static final int TONIGHT_SLOT = 4;
    private static final int TILL_SLOT = 6;
    private static final int ABOUT_SLOT = 8;

    private final SimpleInventory display = new SimpleInventory(SIZE);
    private final ServerPlayerEntity who;
    private final TrapClubs.Club club;

    public ClubScreenHandler(int syncId, PlayerInventory inventory, TrapClubs.Club club) {
        super(ScreenHandlerType.GENERIC_9X1, syncId);
        this.who = (ServerPlayerEntity) inventory.player;
        this.club = club;
        for (int slot = 0; slot < SIZE; slot++) {
            addSlot(new Slot(display, slot, 8 + slot * 18, 18) {
                @Override
                public boolean canInsert(ItemStack stack) {
                    return false;
                }

                @Override
                public boolean canTakeItems(PlayerEntity player) {
                    return false;
                }
            });
        }
        paint();
    }

    private void paint() {
        ItemStack filler = new ItemStack(Items.BLACK_STAINED_GLASS_PANE);
        filler.set(DataComponentTypes.CUSTOM_NAME, plain(" "));
        for (int slot = 0; slot < SIZE; slot++) {
            display.setStack(slot, filler.copy());
        }
        display.setStack(SIGN_SLOT, sign());
        display.setStack(DOOR_SLOT, door());
        display.setStack(TONIGHT_SLOT, tonight());
        display.setStack(TILL_SLOT, till());
        display.setStack(ABOUT_SLOT, about());
        sendContentUpdates();
    }

    private ItemStack sign() {
        ItemStack tag = new ItemStack(Items.NETHER_STAR);
        tag.set(DataComponentTypes.CUSTOM_NAME, plain(club.name())
                .formatted(Formatting.LIGHT_PURPLE, Formatting.BOLD));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line(club.ownerName() + "'s", Formatting.DARK_GRAY),
                Text.empty(),
                line("gości od otwarcia: " + club.through(), Formatting.GRAY),
                line("utarg od otwarcia: " + club.turnover() + "e", Formatting.GRAY),
                Text.empty(),
                line("Weź przedmiot nazwany na kowadle i", Formatting.YELLOW),
                line("kliknij tutaj, żeby zmienić nazwę klubu.", Formatting.YELLOW))));
        return tag;
    }

    private ItemStack door() {
        ItemStack tag = new ItemStack(Items.IRON_DOOR);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain(club.doorName() + "  --  " + club.door() + "e")
                        .formatted(Formatting.GOLD, Formatting.BOLD));
        List<Text> lore = new ArrayList<>();
        lore.add(line("What it costs to get in.", Formatting.GRAY));
        lore.add(Text.empty());
        lore.add(line("Tanio zapełnia salę i mało zarabia,", Formatting.GRAY));
        lore.add(line("a head. Dear is a quiet room at four", Formatting.GRAY));
        lore.add(line("razy więcej kasy. Co jest lepsze,", Formatting.GRAY));
        lore.add(line("zależy od wielkości miasta.", Formatting.GRAY));
        lore.add(Text.empty());
        lore.add(line("Town: " + TrapHomes.population() + " people", Formatting.DARK_GRAY));
        lore.add(Text.empty());
        lore.add(line("Kliknij, żeby zmienić.", Formatting.YELLOW));
        tag.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return tag;
    }

    private ItemStack tonight() {
        boolean busy = club.inside() > 0;
        ItemStack tag = new ItemStack(busy ? Items.JUKEBOX : Items.GRAY_DYE);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain(busy ? "dziś w środku: " + club.inside() : "Pusto")
                        .formatted(busy ? Formatting.GREEN : Formatting.DARK_GRAY,
                                Formatting.BOLD));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line("Przychodzą po zmroku i idą do domu,", Formatting.GRAY),
                line("kiedy im się znudzi.", Formatting.GRAY),
                Text.empty(),
                line("Wszyscy tutaj mieszkają w twoich", Formatting.DARK_GRAY),
                line("domach. Wieczór w klubie to wieczór", Formatting.DARK_GRAY),
                line("poza domem i poza automatem.", Formatting.DARK_GRAY),
                Text.empty(),
                line("Pełna sala robi hałas w okolicy.", Formatting.RED))));
        return tag;
    }

    private ItemStack till() {
        boolean any = club.till() > 0;
        ItemStack tag = new ItemStack(any ? Items.EMERALD : Items.GRAY_DYE);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain(any ? club.till() + "e w kasie" : "Kasa pusta")
                        .formatted(any ? Formatting.GREEN : Formatting.DARK_GRAY,
                                Formatting.BOLD));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line(any ? "Kliknij, żeby wybrać utarg." : "Drzwi otwarte. Poczekaj na gości.",
                        any ? Formatting.YELLOW : Formatting.DARK_GRAY),
                Text.empty(),
                line("Rozbicie budki wysypie kasę na", Formatting.DARK_GRAY),
                line("floor rather than vanishing.", Formatting.DARK_GRAY))));
        return tag;
    }

    private ItemStack about() {
        ItemStack tag = new ItemStack(Items.PAPER);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("The room is yours").formatted(Formatting.WHITE, Formatting.BOLD));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line("Nic tu nie ocenia twojego budynku.", Formatting.GRAY),
                line("Klub to jedyne miejsce, gdzie liczy", Formatting.GRAY),
                line("się wyłącznie twój gust. Żadnej", Formatting.GRAY),
                line("listy wymagań.", Formatting.GRAY),
                Text.empty(),
                line("Zostaw im miejsce do stania, a", Formatting.DARK_GRAY),
                line("a sami znajdą drogę do środka.", Formatting.DARK_GRAY))));
        return tag;
    }

    @Override
    public void onSlotClick(int index, int button, SlotActionType type, PlayerEntity clicker) {
        if (index < 0 || index >= SIZE) {
            super.onSlotClick(index, button, type, clicker);
            return;
        }
        if (index == DOOR_SLOT) {
            TrapClubs.reprice(club);
            click(SoundEvents.UI_BUTTON_CLICK.value(), 1.3F);
            paint();
            return;
        }
        if (index == TILL_SLOT) {
            int lifted = TrapClubs.collect(who, club);
            if (lifted <= 0) {
                who.sendMessage(plain("Nothing to lift.").formatted(Formatting.GRAY), true);
                click(SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), 0.7F);
            } else {
                who.sendMessage(plain("Lifted ").formatted(Formatting.GRAY)
                        .append(plain(lifted + "e").formatted(Formatting.GREEN))
                        .append(plain(" out of " + club.name()).formatted(Formatting.GRAY)),
                        true);
                click(SoundEvents.BLOCK_VAULT_INSERT_ITEM, 1.1F);
            }
            paint();
            return;
        }
        if (index == SIGN_SLOT) {
            Text named = who.getMainHandStack().get(DataComponentTypes.CUSTOM_NAME);
            if (named == null || named.getString().isBlank()) {
                who.sendMessage(plain("Weź przedmiot nazwany na kowadle i kliknij "
                        + "the sign to name the club.").formatted(Formatting.GRAY), true);
                return;
            }
            TrapClubs.rename(club, named.getString());
            click(SoundEvents.BLOCK_ANVIL_USE, 1.4F);
            who.sendMessage(plain("Now trading as ").formatted(Formatting.GRAY)
                    .append(plain(club.name()).formatted(Formatting.LIGHT_PURPLE)), true);
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
        return true;
    }

    private static MutableText plain(String text) {
        return Text.literal(text).styled(style -> style.withItalic(false));
    }

    private static Text line(String text, Formatting colour) {
        return plain(text).formatted(colour);
    }
}
