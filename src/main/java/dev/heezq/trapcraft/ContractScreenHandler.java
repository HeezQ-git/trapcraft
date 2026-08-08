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
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

/**
 * The job board.
 *
 * One paper per job, one job per hopper slot. Clicking a paper takes the job --
 * there is no confirm step on purpose, the clock starting the instant you
 * commit is part of the feel.
 */
public class ContractScreenHandler extends ScreenHandler {
    /**
     * A hopper, not a chest.
     *
     * The board is exactly {@link TrapContracts#BOARD_SIZE} jobs and a hopper
     * is exactly five slots in a row, so the shape of the screen IS the list.
     * The chest version needed twenty-two filler panes to stop it looking like
     * a storage container with holes in it, and filler is a sign the wrong
     * container type was picked.
     */
    private static final int SIZE = 5;

    private final SimpleInventory display = new SimpleInventory(SIZE);
    private final List<Contract> jobs;
    private final ItemStack phone;
    private final PlayerEntity owner;

    public ContractScreenHandler(int syncId, PlayerInventory playerInventory,
                                 List<Contract> jobs, ItemStack phone) {
        super(ScreenHandlerType.HOPPER, syncId);
        this.owner = playerInventory.player;
        this.jobs = jobs;
        this.phone = phone;

        // Vanilla hopper geometry: five slots in a row, then the player's
        // inventory and hotbar below it.
        for (int index = 0; index < SIZE; index++) {
            this.addSlot(new ReadOnlySlot(display, index, 44 + index * 18, 20));
        }
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInventory, col + row * 9 + 9,
                        8 + col * 18, 51 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInventory, col, 8 + col * 18, 109));
        }

        fill(playerInventory.player);
    }

    private void fill(PlayerEntity player) {
        long now = player.getWorld().getTime();
        for (int slot = 0; slot < jobs.size() && slot < SIZE; slot++) {
            Contract job = jobs.get(slot);
            int distance = (int) Math.sqrt(
                    player.getBlockPos().getSquaredDistance(job.destination()));
            int seconds = job.secondsLeft(now);

            ItemStack paper = new ItemStack(Items.PAPER);
            // Named in the strain's own colour, so the five jobs are told
            // apart at a glance instead of being five identical gold lines.
            paper.set(DataComponentTypes.CUSTOM_NAME,
                    plain(job.strainValue().display() + "  x" + job.quantity())
                            .withColor(job.strainValue().colour()));
            paper.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                    rule(),
                    field("Wants", job.formValue().label,
                            job.formValue() == Contract.Form.EITHER
                                    ? Formatting.WHITE : Formatting.YELLOW),
                    field("Grade", job.gradeValue().display() + " or better",
                            job.gradeValue().colour()),
                    field("Distance", distance + " blocks", Formatting.WHITE),
                    field("Deadline", String.format("%d:%02d", seconds / 60, seconds % 60),
                            seconds <= 300 ? Formatting.RED : Formatting.WHITE),
                    rule(),
                    field("Pays", job.payout() + " emeralds", Formatting.GREEN),
                    field("Rep", "+" + job.rep(), Formatting.GREEN),
                    Text.empty(),
                    plain("▸ Click to take it").formatted(Formatting.DARK_AQUA))));
            display.setStack(slot, paper);
        }
    }

    /**
     * A label and its value on one line.
     *
     * Minecraft's font is not monospaced, so the padding here lines the values
     * up approximately rather than exactly -- which still reads as a table,
     * where run-on "27 emeralds · +3 rep" lines did not.
     */
    private static Text field(String label, String value, Formatting colour) {
        String padded = label + " ".repeat(Math.max(1, 10 - label.length()));
        return plain(" " + padded).formatted(Formatting.DARK_GRAY)
                .append(plain(value).formatted(colour));
    }

    private static Text rule() {
        return plain(" " + "─".repeat(16)).formatted(Formatting.DARK_GRAY);
    }

    /** Lore is italic by default, which reads as a hint rather than data. */
    private static MutableText plain(String text) {
        return Text.literal(text).styled(style -> style.withItalic(false));
    }

    @Override
    public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity player) {
        if (slotIndex < 0 || slotIndex >= SIZE || slotIndex >= jobs.size()
                || !(player instanceof ServerPlayerEntity actor)) {
            return;
        }
        actor.closeHandledScreen();
        TrapContracts.accept(actor, phone, jobs.get(slotIndex));
    }

    @Override
    public ItemStack quickMove(PlayerEntity player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canUse(PlayerEntity player) {
        return player == owner;
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
