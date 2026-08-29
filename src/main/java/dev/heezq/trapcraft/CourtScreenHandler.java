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
 * The wokanda: your cases, what they are worth, and what a lawyer costs.
 *
 * One row of listings and one plaque, because that is genuinely all there is
 * to do here -- a case is a date you cannot move and a number you can raise,
 * and the only button on it is "spend money on this one". A court with more
 * controls than that would be pretending the player has choices they do not.
 *
 *   [case][case][case][case][case][case][case] . [plaque]
 *
 * Only cases the clicker is the victim in. A courthouse hears the whole town's
 * business, but a player has no standing in somebody else's burglary and no
 * reason to be shown it.
 */
public class CourtScreenHandler extends ScreenHandler {
    private static final int SIZE = 9;
    private static final int PLAQUE_SLOT = 8;
    private static final int CASES = 7;

    private final SimpleInventory display = new SimpleInventory(SIZE);
    private final ServerPlayerEntity who;
    private final TrapCourt.Court court;
    private List<TrapCourt.Trial> listed = List.of();

    public CourtScreenHandler(int syncId, PlayerInventory playerInventory,
                              TrapCourt.Court court) {
        super(ScreenHandlerType.GENERIC_9X1, syncId);
        this.who = (ServerPlayerEntity) playerInventory.player;
        this.court = court;
        for (int index = 0; index < SIZE; index++) {
            this.addSlot(new ReadOnlySlot(display, index, 8 + index * 18, 18));
        }
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInventory, col + row * 9 + 9,
                        8 + col * 18, 49 + row * 18));
            }
        }
        // The hotbar, which is not optional: a GENERIC_9X1 screen is nine
        // slots plus the player's whole inventory, and a handler that is nine
        // short of what the client is laying out is a desync rather than a
        // missing row.
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInventory, col, 8 + col * 18, 107));
        }
        paint();
    }

    private void paint() {
        listed = TrapCourt.mine(who);
        ItemStack filler = new ItemStack(Items.BLACK_STAINED_GLASS_PANE);
        filler.set(DataComponentTypes.CUSTOM_NAME, plain(" "));
        for (int index = 0; index < SIZE; index++) {
            display.setStack(index, filler.copy());
        }
        for (int i = 0; i < listed.size() && i < CASES; i++) {
            display.setStack(i, caseTag(listed.get(i)));
        }
        if (listed.isEmpty()) {
            display.setStack(0, empty());
        }
        display.setStack(PLAQUE_SLOT, plaque());
        sendContentUpdates();
    }

    private ItemStack caseTag(TrapCourt.Trial trial) {
        long wait = trial.waiting(who.getServer());
        int fee = trial.nextFee();
        boolean can = fee > 0 && TrapMarket.wealthOf(who) >= fee;
        ItemStack tag = new ItemStack(wait == 0 ? Items.BELL : Items.WRITABLE_BOOK);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain(trial.kind().display()).formatted(Formatting.GOLD, Formatting.BOLD)
                        .append(plain("  " + trial.loot() + "e")
                                .formatted(Formatting.WHITE)));
        List<Text> lore = new ArrayList<>();
        lore.add(line("Poszkodowany: " + trial.victim(), Formatting.GRAY));
        lore.add(line("Oskarżony: " + trial.suspect(), Formatting.GRAY));
        lore.add(Text.empty());
        lore.add(wait == 0
                ? line("Rozprawa dziś. Musisz być na serwerze.", Formatting.YELLOW)
                : line("Rozprawa za " + wait + (wait == 1 ? " dzień." : " dni."),
                Formatting.GRAY));
        // The number the whole building exists to move, and the two things
        // behind it -- so nobody has to guess whether the lawyer is doing
        // anything or whether the police budget was worth it.
        lore.add(line("Szanse: ", Formatting.DARK_GRAY)
                .append(plain(TrapCourt.percent(trial.odds())).formatted(Formatting.AQUA)));
        lore.add(line("Prawnik " + trial.lawyer() + " z " + TrapMath.LAWYERS
                + (trial.spent() > 0 ? "  (" + trial.spent() + "e)" : ""),
                Formatting.DARK_GRAY));
        lore.add(Text.empty());
        lore.add(line("Wygrana: ", Formatting.DARK_GRAY)
                .append(plain((trial.loot() + TrapMath.damages(trial.loot())) + "e")
                        .formatted(Formatting.GREEN))
                .append(plain("  (z odszkodowaniem)").formatted(Formatting.DARK_GRAY)));
        lore.add(line("Przegrana: ", Formatting.DARK_GRAY)
                .append(plain("nic").formatted(Formatting.RED)));
        lore.add(Text.empty());
        if (fee == 0) {
            lore.add(line("Masz najlepszego prawnika w mieście.", Formatting.GOLD));
        } else {
            lore.add(line(fee + "e", Formatting.GOLD)
                    .append(plain(" za lepszego prawnika, +"
                            + Math.round(TrapMath.COURT_PER_LAWYER * 100) + " pkt szans.")
                            .formatted(Formatting.DARK_GRAY)));
            lore.add(line(can ? "Kliknij, żeby wynająć." : "Nie stać cię.",
                    can ? Formatting.YELLOW : Formatting.DARK_GRAY));
        }
        tag.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return tag;
    }

    private ItemStack empty() {
        ItemStack tag = new ItemStack(Items.PAPER);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Wokanda pusta").formatted(Formatting.GRAY, Formatting.BOLD));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line("Nikt cię ostatnio nie okradł, albo", Formatting.GRAY),
                line("nikogo za to nie złapano.", Formatting.GRAY),
                Text.empty(),
                line("Sprawa trafia tu dopiero wtedy, kiedy", Formatting.DARK_GRAY),
                line("policja kogoś zatrzyma.", Formatting.DARK_GRAY))));
        return tag;
    }

    private ItemStack plaque() {
        boolean mine = court != null && who.getUuid().equals(court.owner());
        int fees = court == null ? 0 : court.fees();
        ItemStack tag = new ItemStack(mine && fees > 0 ? Items.EMERALD : Items.GOLD_INGOT);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain(court == null ? "Sąd" : court.name())
                        .formatted(Formatting.GOLD, Formatting.BOLD));
        List<Text> lore = new ArrayList<>();
        if (court != null) {
            lore.add(line("Sędzia: " + court.ownerName(), Formatting.GRAY));
            lore.add(line("Rozpraw: " + court.heard() + ", wygranych "
                    + court.won(), Formatting.DARK_GRAY));
            lore.add(Text.empty());
        }
        lore.add(line("Policja stawia tu każdą kradzież", Formatting.GRAY));
        lore.add(line("i każdy rozbój, jak tylko kogoś złapie.", Formatting.GRAY));
        lore.add(line("Bez sądu pieniądze wracają od razu", Formatting.DARK_GRAY));
        lore.add(line("-- ale tylko tyle, ile miasto ma.", Formatting.DARK_GRAY));
        lore.add(Text.empty());
        lore.add(line("Lepszy komisariat = mocniejsze dowody.", Formatting.AQUA));
        if (mine) {
            lore.add(Text.empty());
            lore.add(fees > 0
                    ? line("Opłaty kancelaryjne: ", Formatting.DARK_GRAY)
                    .append(plain(fees + "e").formatted(Formatting.GREEN))
                    .append(plain("  Kliknij.").formatted(Formatting.YELLOW))
                    : line("Opłat kancelaryjnych brak.", Formatting.DARK_GRAY));
        }
        tag.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return tag;
    }

    @Override
    public void onSlotClick(int index, int button, SlotActionType type, PlayerEntity clicker) {
        if (index < 0 || index >= SIZE) {
            super.onSlotClick(index, button, type, clicker);
            return;
        }
        if (index == PLAQUE_SLOT) {
            if (court != null) {
                int took = TrapCourt.collect(who, court);
                if (took > 0) {
                    who.sendMessage(Text.literal("Odebrane: " + took + "e.")
                            .formatted(Formatting.GREEN), true);
                    click(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.2F);
                }
            }
            paint();
            return;
        }
        if (index < listed.size()) {
            String no = TrapCourt.hire(who, listed.get(index).caseId());
            if (no == null) {
                click(SoundEvents.ENTITY_VILLAGER_YES, 1.0F);
            } else {
                who.sendMessage(Text.literal(no).formatted(Formatting.GRAY), true);
                click(SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), 0.7F);
            }
        }
        paint();
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
