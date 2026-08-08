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
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

/**
 * The mixing station's menu.
 *
 * A chest, because the crafting screen could not work here. The client
 * recomputes a crafting result slot itself from the recipe book, finds no
 * vanilla recipe matching four Polymer buds, and paints an empty square over
 * whatever the server put there -- the same failure as the merchant screen's
 * payout slot, from the same cause. No amount of re-sending the slot wins
 * against the client's own recomputation.
 *
 * So: real slots for the buds, because dragging a bud into a slot is the
 * clearest possible way to say "put your buds here", and a BUTTON for the
 * result instead of a result slot. The preview is a read-only display the
 * client has no opinion about, and clicking it does the mix server-side.
 *
 *   [bud][bud]        ->     ( MIX )
 *   [bud][bud]
 */
public class MixerScreenHandler extends ScreenHandler {
    private static final int ROWS = 3;
    private static final int SIZE = ROWS * 9;

    /** A 2x2 block of real slots, which is what MAX_PARTS wants to look like. */
    private static final int[] INPUT_SLOTS = {11, 12, 20, 21};
    private static final int ARROW_SLOT = 14;
    private static final int RESULT_SLOT = 16;
    private static final int HELP_SLOT = 4;

    private final SimpleInventory inputs = new SimpleInventory(INPUT_SLOTS.length);
    private final SimpleInventory display = new SimpleInventory(SIZE);
    private final PlayerEntity owner;

    public MixerScreenHandler(int syncId, PlayerInventory playerInventory) {
        super(ScreenHandlerType.GENERIC_9X3, syncId);
        this.owner = playerInventory.player;

        for (int index = 0; index < SIZE; index++) {
            int x = 8 + (index % 9) * 18;
            int y = 18 + (index / 9) * 18;
            int input = inputIndex(index);
            this.addSlot(input < 0
                    ? new ReadOnlySlot(display, index, x, y)
                    : new BudSlot(inputs, input, x, y));
        }
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInventory, col + row * 9 + 9,
                        8 + col * 18, 84 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInventory, col, 8 + col * 18, 142));
        }

        // A SimpleInventory does NOT call ScreenHandler.onContentChanged --
        // only a CraftingInventory does, because it holds a back-reference to
        // its handler. Overriding onContentChanged and expecting drags to
        // reach it is why this screen appeared to do nothing: the preview only
        // ever updated on a shift-click, which called it by hand.
        inputs.addListener(sender -> paint());
        paint();
    }

    /** Which input a chest position is, or -1 for scenery. */
    private static int inputIndex(int slot) {
        for (int i = 0; i < INPUT_SLOTS.length; i++) {
            if (INPUT_SLOTS[i] == slot) {
                return i;
            }
        }
        return -1;
    }

    // --- what's in the bowl ---------------------------------------------------

    /** What the inputs currently make, or null if they don't make anything. */
    private Blend preview() {
        List<Strain> parts = new ArrayList<>();
        int worst = Quality.FIRE.index();
        for (int i = 0; i < inputs.size(); i++) {
            ItemStack stack = inputs.getStack(i);
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

    private int loaded() {
        int count = 0;
        for (int i = 0; i < inputs.size(); i++) {
            if (!inputs.getStack(i).isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private void paint() {
        ItemStack filler = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
        filler.set(DataComponentTypes.CUSTOM_NAME, plain(" "));
        for (int index = 0; index < SIZE; index++) {
            if (inputIndex(index) < 0) {
                display.setStack(index, filler.copy());
            }
        }

        ItemStack help = new ItemStack(Items.BOOK);
        help.set(DataComponentTypes.CUSTOM_NAME,
                plain("Mixing Station").formatted(Formatting.LIGHT_PURPLE, Formatting.BOLD));
        help.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line("1.", Formatting.YELLOW).append(plain(" Put " + Blend.MIN_PARTS + " to "
                        + Blend.MAX_PARTS + " dried buds in the four").formatted(Formatting.GRAY)),
                line("    slots on the left.", Formatting.GRAY),
                line("2.", Formatting.YELLOW).append(plain(" Click the jar on the right.")
                        .formatted(Formatting.GRAY)),
                Text.empty(),
                line("Repeats count. Two Kush and a Purp is", Formatting.DARK_GRAY),
                line("not the same as one of each.", Formatting.DARK_GRAY),
                line("Some combinations have names.", Formatting.DARK_GRAY))));
        display.setStack(HELP_SLOT, help);

        ItemStack arrow = new ItemStack(Items.ARROW);
        arrow.set(DataComponentTypes.CUSTOM_NAME, plain("-->").formatted(Formatting.DARK_GRAY));
        display.setStack(ARROW_SLOT, arrow);

        Blend blend = preview();
        int count = loaded();
        if (blend == null) {
            ItemStack empty = new ItemStack(Items.GLASS_BOTTLE);
            empty.set(DataComponentTypes.CUSTOM_NAME,
                    plain("Nothing to mix yet").formatted(Formatting.GRAY, Formatting.BOLD));
            empty.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                    line(count == 0 ? "The slots are empty."
                                    : count < Blend.MIN_PARTS
                                    ? "One bud isn't a mix. Add another."
                                    : "Only dried buds go in here.",
                            Formatting.GRAY),
                    line(count + " of " + Blend.MAX_PARTS + " slots filled",
                            Formatting.DARK_GRAY))));
            display.setStack(RESULT_SLOT, empty);
        } else {
            ItemStack result = TrapContent.blendBud(blend);
            List<Text> lore = new ArrayList<>(
                    result.getOrDefault(DataComponentTypes.LORE, LoreComponent.DEFAULT).lines());
            lore.add(Text.empty());
            lore.add(line("Click to mix", Formatting.YELLOW, Formatting.BOLD));
            lore.add(line("Uses all " + count + " buds.", Formatting.DARK_GRAY));
            result.set(DataComponentTypes.LORE, new LoreComponent(lore));
            display.setStack(RESULT_SLOT, result);
        }

        sendContentUpdates();
    }

    // --- mixing ---------------------------------------------------------------

    @Override
    public void onSlotClick(int index, int button, SlotActionType type, PlayerEntity player) {
        if (index != RESULT_SLOT) {
            super.onSlotClick(index, button, type, player);
            return;
        }
        Blend blend = preview();
        if (blend == null) {
            deny();
            return;
        }
        for (int i = 0; i < inputs.size(); i++) {
            inputs.getStack(i).decrement(1);
        }
        if (blend.named() != null && player instanceof net.minecraft.server.network.ServerPlayerEntity who) {
            TrapAwards.grant(who, "named_blend");
        }
        player.getInventory().offerOrDrop(TrapContent.blendBud(blend));
        celebrate();
        paint();
    }

    /**
     * Shift-clicking a bud from your bag loads the next free slot.
     *
     * Without this the only way to load the station is dragging one bud at a
     * time, which for a four-part mix is four drags.
     */
    @Override
    public ItemStack quickMove(PlayerEntity player, int index) {
        if (index < SIZE) {
            // Out of the station: only the input slots hold anything real.
            int input = inputIndex(index);
            if (input < 0) {
                return ItemStack.EMPTY;
            }
            ItemStack stack = inputs.getStack(input);
            if (stack.isEmpty() || !this.insertItem(stack, SIZE, this.slots.size(), true)) {
                return ItemStack.EMPTY;
            }
            paint();
            return ItemStack.EMPTY;
        }

        Slot from = this.slots.get(index);
        ItemStack stack = from.getStack();
        if (TrapContent.strainOfDriedBud(stack.getItem()) == null) {
            return ItemStack.EMPTY;
        }
        for (int i = 0; i < inputs.size(); i++) {
            if (inputs.getStack(i).isEmpty()) {
                inputs.setStack(i, stack.split(1));
                from.markDirty();
                paint();
                return ItemStack.EMPTY;
            }
        }
        return ItemStack.EMPTY;
    }

    @Override
    public void onClosed(PlayerEntity player) {
        super.onClosed(player);
        // Hand the buds back. Losing your ingredients to a closed menu is the
        // kind of thing that stops people experimenting, which is the whole
        // point of the block.
        for (int i = 0; i < inputs.size(); i++) {
            ItemStack stack = inputs.getStack(i);
            if (!stack.isEmpty()) {
                player.getInventory().offerOrDrop(stack.copy());
                inputs.setStack(i, ItemStack.EMPTY);
            }
        }
    }

    @Override
    public boolean canUse(PlayerEntity player) {
        return player == owner;
    }

    // --- trimmings ------------------------------------------------------------

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

    private void deny() {
        if (owner instanceof net.minecraft.server.network.ServerPlayerEntity player) {
            player.getWorld().playSound(null, player.getBlockPos(),
                    net.minecraft.sound.SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(),
                    net.minecraft.sound.SoundCategory.PLAYERS, 0.7F, 0.6F);
        }
    }

    private static MutableText plain(String text) {
        return Text.literal(text).styled(style -> style.withItalic(false));
    }

    private static MutableText line(String text, Formatting... colours) {
        return plain(text).formatted(colours);
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
