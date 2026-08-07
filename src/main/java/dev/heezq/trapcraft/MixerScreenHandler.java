package dev.heezq.trapcraft;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.Slot;

import java.util.ArrayList;
import java.util.List;

/**
 * The mixing station's menu.
 *
 * Built on a vanilla container type rather than a bespoke screen: the client
 * draws it with no client-side code at all, which keeps the mod's "the server
 * owns everything" shape intact.
 *
 *   B B B          B = bud in, [R] = result
 *   B . .   ->  [R]
 *   . . .
 */
public class MixerScreenHandler extends ScreenHandler {
    /**
     * The crafting table screen, not a chest.
     *
     * A 9x3 chest gave twenty-two slots for a four-input recipe, so the extra
     * eighteen had to be filled with grey panes and a paper arrow to stop them
     * being usable -- and filler is the tell that the container type is wrong.
     *
     * A crafting grid fixes it without a single pane: vanilla already draws the
     * arrow and the result slot, and an EMPTY slot in a crafting grid reads as
     * normal rather than broken, which is exactly the problem panes were there
     * to paper over. It also says "combine these into that" without a word of
     * explanation, because every player already knows this screen.
     *
     * Slot indices are the vanilla crafting ones: 0 is the result, 1..9 are the
     * grid. Only the first MAX_PARTS grid slots accept buds.
     */
    private static final int RESULT = 0;
    private static final int GRID = 1;
    private static final int GRID_SIZE = 9;
    private static final int SIZE = GRID + GRID_SIZE;

    private final SimpleInventory inventory = new SimpleInventory(SIZE);
    private final PlayerEntity owner;

    public MixerScreenHandler(int syncId, PlayerInventory playerInventory) {
        super(ScreenHandlerType.CRAFTING, syncId);
        this.owner = playerInventory.player;

        this.addSlot(new ResultSlot(inventory, RESULT, 124, 35));
        for (int index = 0; index < GRID_SIZE; index++) {
            int x = 30 + (index % 3) * 18;
            int y = 17 + (index / 3) * 18;
            this.addSlot(isInput(GRID + index)
                    ? new BudSlot(inventory, GRID + index, x, y)
                    : new LockedSlot(inventory, GRID + index, x, y));
        }

        // Player inventory then hotbar, in the order the vanilla screen expects.
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInventory, col + row * 9 + 9,
                        8 + col * 18, 84 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInventory, col, 8 + col * 18, 142));
        }

        refresh();
    }

    /**
     * Which grid cells accept a bud, in fill order.
     *
     * Deliberately not "the first MAX_PARTS cells": at four parts that reads
     * across the top row and wraps onto the second, an L-shape that looks like
     * a mistake. This order fills a 2x2 block in the top-left first, which
     * reads as a deliberate area, and degrades sensibly if MAX_PARTS ever
     * changes.
     */
    private static final int[] FILL_ORDER = {0, 1, 3, 4, 2, 5, 6, 7, 8};

    private static boolean isInput(int index) {
        int slot = index - GRID;
        for (int i = 0; i < Blend.MAX_PARTS && i < FILL_ORDER.length; i++) {
            if (FILL_ORDER[i] == slot) {
                return true;
            }
        }
        return false;
    }

    /** What's currently in the input slots, as strains. Null if it isn't valid. */
    private Blend preview() {
        List<Strain> parts = new ArrayList<>();
        int worst = Quality.FIRE.index();
        for (int index = GRID; index < SIZE; index++) {
            if (!isInput(index)) {
                continue;
            }
            ItemStack stack = inventory.getStack(index);
            if (stack.isEmpty()) {
                continue;
            }
            Strain strain = TrapContent.strainOfDriedBud(stack.getItem());
            if (strain == null) {
                return null;
            }
            parts.add(strain);
            // The mix is only as good as its worst ingredient. Averaging would
            // let one Fire bud launder three Swill ones.
            worst = Math.min(worst, TrapComponents.get(stack).index());
        }
        if (parts.size() < Blend.MIN_PARTS || parts.size() > Blend.MAX_PARTS) {
            return null;
        }
        return new Blend(parts, worst);
    }

    private void refresh() {
        Blend blend = preview();
        inventory.setStack(RESULT, blend == null
                ? ItemStack.EMPTY
                : TrapContent.blendBud(blend));
        sendContentUpdates();
    }

    @Override
    public void onContentChanged(Inventory changed) {
        super.onContentChanged(changed);
        refresh();
    }

    @Override
    public ItemStack quickMove(PlayerEntity player, int index) {
        Slot slot = this.slots.get(index);
        if (!slot.hasStack()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getStack();

        if (index >= SIZE) {
            // From the player's inventory: try to land it in a free input.
            for (int input = GRID; input < SIZE; input++) {
                if (isInput(input) && inventory.getStack(input).isEmpty()
                        && this.slots.get(input).canInsert(stack)) {
                    this.slots.get(input).setStack(stack.split(1));
                    slot.markDirty();
                    onContentChanged(inventory);
                    return ItemStack.EMPTY;
                }
            }
            return ItemStack.EMPTY;
        }

        // Out of the station: only the input and output slots can give anything.
        if (!isInput(index) && index != RESULT) {
            return ItemStack.EMPTY;
        }
        ItemStack copy = stack.copy();
        if (!this.insertItem(stack, SIZE, this.slots.size(), true)) {
            return ItemStack.EMPTY;
        }
        // No consumeInputs() here for OUTPUT: onTakeItem does it, and calling
        // both fired the stir animation and both sounds twice on a shift-click.
        slot.onTakeItem(player, stack);
        onContentChanged(inventory);
        return copy;
    }

    private void consumeInputs() {
        for (int input = GRID; input < SIZE; input++) {
            if (isInput(input)) {
                inventory.getStack(input).decrement(1);
            }
        }
        celebrate();
    }

    /**
     * Something actually happened. Without this the station is the only
     * machine in the mod that works in total silence -- you click, an item
     * appears, and nothing tells you it was a real event.
     */
    private void celebrate() {
        if (!(owner instanceof net.minecraft.server.network.ServerPlayerEntity player)) {
            return;
        }
        TrapNet.play(player, TrapNet.MIX_STIR);
        net.minecraft.server.world.ServerWorld world = player.getWorld();
        // Two layers a semitone apart: one sample on its own reads as a UI
        // click, two stacked read as a thing being ground together.
        world.playSound(null, player.getX(), player.getY(), player.getZ(),
                net.minecraft.sound.SoundEvents.BLOCK_COMPOSTER_FILL_SUCCESS,
                net.minecraft.sound.SoundCategory.BLOCKS, 0.8F, 0.9F);
        world.playSound(null, player.getX(), player.getY(), player.getZ(),
                net.minecraft.sound.SoundEvents.BLOCK_BREWING_STAND_BREW,
                net.minecraft.sound.SoundCategory.BLOCKS, 0.5F, 1.3F);
        world.spawnParticles(net.minecraft.particle.ParticleTypes.COMPOSTER,
                player.getX(), player.getY() + 1.1, player.getZ(),
                14, 0.35, 0.25, 0.35, 0.02);
    }

    @Override
    public void onClosed(PlayerEntity player) {
        super.onClosed(player);
        // Hand the buds back. Losing your ingredients to a closed menu is the
        // kind of thing that stops people experimenting, which is the whole
        // point of the block.
        for (int input = GRID; input < SIZE; input++) {
            ItemStack stack = inventory.getStack(input);
            if (isInput(input) && !stack.isEmpty()) {
                player.getInventory().offerOrDrop(stack.copy());
                inventory.setStack(input, ItemStack.EMPTY);
            }
        }
    }

    @Override
    public boolean canUse(PlayerEntity player) {
        return player == owner;
    }

    // --- slots ---------------------------------------------------------------

    private static class BudSlot extends Slot {
        BudSlot(Inventory inventory, int index, int x, int y) {
            super(inventory, index, x, y);
        }

        @Override
        public boolean canInsert(ItemStack stack) {
            return TrapContent.strainOfDriedBud(stack.getItem()) != null;
        }

        @Override
        public int getMaxItemCount() {
            return 1; // one bud per slot: the mix ratio is which slots, not how many
        }
    }

    private class ResultSlot extends Slot {
        ResultSlot(Inventory inventory, int index, int x, int y) {
            super(inventory, index, x, y);
        }

        @Override
        public boolean canInsert(ItemStack stack) {
            return false;
        }

        @Override
        public void onTakeItem(PlayerEntity player, ItemStack stack) {
            consumeInputs();
            super.onTakeItem(player, stack);
            onContentChanged(inventory);
        }
    }

    private static class LockedSlot extends Slot {
        LockedSlot(Inventory inventory, int index, int x, int y) {
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
