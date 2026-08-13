package dev.heezq.trapcraft;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.LockableContainerBlockEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventories;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.text.Text;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.BlockPos;

/**
 * The stock, in the shelf.
 *
 * A shelf used to be a counter with a chest hidden under it, which is a
 * cellar rather than a shelf: you stocked the floor and the thing people
 * queued at held nothing. This is a chest that happens to be a shop counter --
 * twenty-seven slots, hoppers feed it, and the till sells straight off it.
 *
 * Chests under the till and under the shelves still count, so nobody's back
 * room stopped working the day this landed.
 *
 * <h2>The first block entity in this mod</h2>
 *
 * Which is why {@link TrapContent} tells Polymer the type is server-side only.
 * A vanilla client sent a block entity its registry has never heard of does
 * not fail politely.
 */
public class MarketShelfBlockEntity extends LockableContainerBlockEntity {
    public static final int SIZE = 27;

    private DefaultedList<ItemStack> stock = DefaultedList.ofSize(SIZE, ItemStack.EMPTY);

    public MarketShelfBlockEntity(BlockPos pos, BlockState state) {
        super(TrapContent.marketShelfEntity, pos, state);
    }

    @Override
    public int size() {
        return SIZE;
    }

    @Override
    protected DefaultedList<ItemStack> getHeldStacks() {
        return stock;
    }

    @Override
    protected void setHeldStacks(DefaultedList<ItemStack> stacks) {
        this.stock = stacks;
    }

    @Override
    protected Text getContainerName() {
        return Text.literal("Shelf");
    }

    @Override
    protected ScreenHandler createScreenHandler(int syncId, PlayerInventory inventory) {
        return GenericContainerScreenHandler.createGeneric9x3(syncId, inventory, this);
    }

    @Override
    protected void writeData(WriteView view) {
        super.writeData(view);
        Inventories.writeData(view, stock);
    }

    @Override
    protected void readData(ReadView view) {
        super.readData(view);
        Inventories.readData(view, stock);
    }
}
