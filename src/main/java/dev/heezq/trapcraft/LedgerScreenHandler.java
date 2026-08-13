package dev.heezq.trapcraft;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * The search results, as a chest.
 *
 * Same trick as the mixing station: a vanilla 9x6 container type so unmodded
 * clients draw it for free. Nothing here is a real inventory -- every slot is
 * a read-only display, and a click is a query rather than a pickup.
 */
public class LedgerScreenHandler extends ScreenHandler {
    private static final int ROWS = 6;
    private static final int SIZE = ROWS * 9;

    private final SimpleInventory display = new SimpleInventory(SIZE);
    private final List<Item> rowItems = new ArrayList<>();
    private final LedgerItem.Scan scan;
    private final PlayerEntity owner;

    public LedgerScreenHandler(int syncId, PlayerInventory playerInventory, LedgerItem.Scan scan) {
        super(ScreenHandlerType.GENERIC_9X6, syncId);
        this.owner = playerInventory.player;
        this.scan = scan;

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

        fill();
    }

    private void fill() {
        int index = 0;
        for (TrapMath.Tally<Item> row : scan.rows()) {
            if (index >= SIZE) {
                break;
            }
            List<BlockPos> positions = scan.where().getOrDefault(row.key(), List.of());
            ItemStack entry = new ItemStack(row.key(), Math.min(99, row.total()));
            entry.set(DataComponentTypes.CUSTOM_NAME,
                    Text.literal(row.key().getName().getString() + "  x" + row.total())
                            .formatted(Formatting.WHITE)
                            .styled(style -> style.withItalic(false)));
            entry.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                    Text.literal("pojemników: " + row.containers()
                                    + "  ·  najbliższy " + describeNearest(positions))
                            .formatted(Formatting.DARK_GRAY)
                            .styled(style -> style.withItalic(false)),
                    Text.literal("Kliknij, żeby namierzyć")
                            .formatted(Formatting.DARK_AQUA)
                            .styled(style -> style.withItalic(false)))));

            display.setStack(index, entry);
            rowItems.add(row.key());
            index++;
        }
    }

    /** "12 blocks NE" -- a bearing beats raw coordinates when you're walking. */
    private String describeNearest(List<BlockPos> positions) {
        BlockPos origin = scan.origin();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : positions) {
            double distance = origin.getSquaredDistance(pos);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = pos;
            }
        }
        if (best == null) {
            return "?";
        }
        int dx = best.getX() - origin.getX();
        int dz = best.getZ() - origin.getZ();
        StringBuilder bearing = new StringBuilder();
        if (dz < -2) {
            bearing.append('N');
        } else if (dz > 2) {
            bearing.append('S');
        }
        if (dx > 2) {
            bearing.append('E');
        } else if (dx < -2) {
            bearing.append('W');
        }
        if (bearing.isEmpty()) {
            bearing.append("here");
        }
        return (int) Math.sqrt(bestDistance) + " bloków " + bearing;
    }

    /**
     * A click is a query. Nothing can ever be taken out of this screen, so the
     * whole vanilla pickup path is bypassed rather than guarded.
     */
    @Override
    public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity player) {
        if (slotIndex < 0 || slotIndex >= SIZE || !(player instanceof ServerPlayerEntity actor)) {
            return;
        }
        if (slotIndex >= rowItems.size()) {
            return;
        }
        Item picked = rowItems.get(slotIndex);
        List<BlockPos> positions = scan.where().getOrDefault(picked, List.of());

        // Close first: the trail starts at the player's eyes and they cannot
        // see it through an open chest screen.
        actor.closeHandledScreen();
        LedgerItem.ping(actor, positions);
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
