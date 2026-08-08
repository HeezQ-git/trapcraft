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
import net.minecraft.particle.ParticleTypes;
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
 * The one-armed bandit, as a real reel window.
 *
 * A 9x6 chest laid out like a cabinet: three reels three symbols tall, with the
 * middle row marked as the payline. The reels scroll a strip of symbols
 * downward and stop left to right, which is the only reason the third reel is
 * ever exciting.
 *
 * The reels are theatre. The outcome comes out of {@link TrapMath#slotPayout}
 * BEFORE any symbol is chosen, and the strips are then built to land on
 * symbols that agree with it -- which is how real machines work, and the only
 * way the return rate is a number anyone can check. It pays back 85% over
 * time, and roughly three spins in four pay nothing.
 */
public class SlotScreenHandler extends ScreenHandler {
    private static final int ROWS = 6;
    private static final int SIZE = ROWS * 9;

    /** Reel columns sit at grid columns 3, 4 and 5, rows 1 to 3. */
    private static final int WINDOW_TOP = 12;
    private static final int PAYLINE = 21;
    private static final int MARKER_LEFT = 20;
    private static final int MARKER_RIGHT = 24;

    private static final int STAKE_SLOT = 47;
    private static final int LEVER_SLOT = 49;
    private static final int PURSE_SLOT = 51;

    /** Reel faces, worst to best. The last is the jackpot. */
    private static final Item[] FACES = {
            Items.COAL, Items.COPPER_INGOT, Items.IRON_INGOT,
            Items.GOLD_INGOT, Items.DIAMOND, Items.NETHER_STAR,
    };
    private static final String[] FACE_NAMES = {
            "Coal", "Copper", "Iron", "Gold", "Diamond", "Star",
    };

    private static final int[] STAKES = {8, 32, 128};

    /** Ticks a reel spins before it locks, first to last. */
    private static final int[] STOPS = {26, 34, 44};
    private static final int SPIN_TICKS = 48;

    private final SimpleInventory display = new SimpleInventory(SIZE);
    private final ServerPlayerEntity player;
    private int stakeChoice = 0;

    private int spinning;
    private float pending;
    /** Where each reel has come to rest, once it has. */
    private final int[] landed = new int[3];
    /** Scroll offset per reel while it's still moving. */
    private final int[] offset = new int[3];

    public SlotScreenHandler(int syncId, PlayerInventory playerInventory) {
        super(ScreenHandlerType.GENERIC_9X6, syncId);
        this.player = (ServerPlayerEntity) playerInventory.player;

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

        for (int reel = 0; reel < 3; reel++) {
            landed[reel] = reel + 1;
        }
        repaint();
    }

    // --- painting -------------------------------------------------------------

    private void repaint() {
        ItemStack black = pane(Items.BLACK_STAINED_GLASS_PANE);
        ItemStack red = pane(Items.RED_STAINED_GLASS_PANE);
        for (int index = 0; index < SIZE; index++) {
            display.setStack(index, (index / 9) % 2 == 0 ? red.copy() : black.copy());
        }

        // The window: three columns, three rows, middle row is the payline.
        for (int reel = 0; reel < 3; reel++) {
            for (int row = 0; row < 3; row++) {
                int slot = WINDOW_TOP + reel + row * 9;
                int symbol = symbolAt(reel, row);
                display.setStack(slot, face(symbol, row == 1));
            }
        }
        display.setStack(MARKER_LEFT, marker());
        display.setStack(MARKER_RIGHT, marker());

        display.setStack(STAKE_SLOT, stakeTag());
        display.setStack(LEVER_SLOT, leverTag());
        display.setStack(PURSE_SLOT, purseTag());
        sendContentUpdates();
    }

    /** Which face shows in a given cell, spinning or settled. */
    private int symbolAt(int reel, int row) {
        int centre = spinning > 0 && spinning > SPIN_TICKS - STOPS[reel]
                ? offset[reel]
                : landed[reel];
        return Math.floorMod(centre + row - 1, FACES.length);
    }

    private ItemStack face(int symbol, boolean onLine) {
        ItemStack tag = new ItemStack(FACES[symbol]);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain(FACE_NAMES[symbol]).formatted(onLine ? Formatting.YELLOW : Formatting.DARK_GRAY));
        return tag;
    }

    private ItemStack marker() {
        ItemStack tag = new ItemStack(Items.ARROW);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Payline").formatted(Formatting.YELLOW, Formatting.BOLD));
        return tag;
    }

    private ItemStack pane(Item item) {
        ItemStack tag = new ItemStack(item);
        tag.set(DataComponentTypes.CUSTOM_NAME, Text.empty());
        return tag;
    }

    private ItemStack stakeTag() {
        ItemStack tag = new ItemStack(Items.EMERALD, Math.max(1, STAKES[stakeChoice] / 8));
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Stake ").formatted(Formatting.GRAY)
                        .append(plain(STAKES[stakeChoice] + "e")
                                .formatted(Formatting.GREEN, Formatting.BOLD)));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line("Click to change.", Formatting.YELLOW))));
        return tag;
    }

    private ItemStack leverTag() {
        ItemStack tag = new ItemStack(spinning > 0 ? Items.REDSTONE_TORCH : Items.LEVER);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain(spinning > 0 ? "Spinning" : "PULL")
                        .formatted(spinning > 0 ? Formatting.GRAY : Formatting.GOLD,
                                Formatting.BOLD));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line("Three stars", Formatting.WHITE)
                        .append(plain("   x20").formatted(Formatting.GOLD)),
                line("Three diamonds", Formatting.WHITE)
                        .append(plain("   x8").formatted(Formatting.GOLD)),
                line("Three of a kind", Formatting.WHITE)
                        .append(plain("   x3").formatted(Formatting.GOLD)),
                line("Any pair", Formatting.WHITE)
                        .append(plain("   x1.5").formatted(Formatting.GOLD)),
                Text.empty(),
                line("The house keeps about "
                        + Math.round((1 - TrapMath.slotReturnToPlayer()) * 100)
                        + "%.", Formatting.DARK_GRAY))));
        return tag;
    }

    private ItemStack purseTag() {
        ItemStack tag = new ItemStack(Items.GOLD_NUGGET);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Purse ").formatted(Formatting.GRAY)
                        .append(plain(TrapMarket.wealthOf(player) + "e")
                                .formatted(Formatting.GREEN, Formatting.BOLD)));
        return tag;
    }

    // --- spinning -------------------------------------------------------------

    @Override
    public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity who) {
        if (spinning > 0) {
            return;
        }
        if (slotIndex == STAKE_SLOT) {
            stakeChoice = (stakeChoice + 1) % STAKES.length;
            beep(1.4F);
            repaint();
            return;
        }
        if (slotIndex != LEVER_SLOT) {
            return;
        }

        int stake = STAKES[stakeChoice];
        if (TrapMarket.wealthOf(player) < stake) {
            beep(0.5F);
            player.sendMessage(plain("You can't cover a " + stake + "e spin.")
                    .formatted(Formatting.GRAY), false);
            return;
        }

        TrapMarket.take(player, stake);
        // Outcome first, symbols afterwards.
        pending = TrapMath.slotPayout(player.getWorld().getRandom().nextFloat());
        chooseSymbols();
        spinning = SPIN_TICKS;
        SlotMachineBlock.watch(this);

        player.getWorld().playSound(null, player.getBlockPos(),
                SoundEvents.BLOCK_LEVER_CLICK, SoundCategory.PLAYERS, 0.9F, 0.6F);
        repaint();
    }

    /**
     * Pick the three faces the reels will stop on, given a decided payout.
     *
     * Three of a kind for the big tiers, a genuine pair for the small one, and
     * three different faces for a loss -- so what you see on the payline always
     * explains what you were paid.
     */
    private void chooseSymbols() {
        var random = player.getWorld().getRandom();
        if (pending >= 20.0f) {
            landed[0] = landed[1] = landed[2] = 5;
        } else if (pending >= 8.0f) {
            landed[0] = landed[1] = landed[2] = 4;
        } else if (pending >= 3.0f) {
            int hit = random.nextInt(4);
            landed[0] = landed[1] = landed[2] = hit;
        } else if (pending > 0.0f) {
            int pair = random.nextInt(FACES.length);
            int odd = (pair + 1 + random.nextInt(FACES.length - 1)) % FACES.length;
            landed[0] = landed[1] = pair;
            landed[2] = odd;
            // Put the odd one out in a random column so a pair isn't always
            // the first two reels.
            int shuffle = random.nextInt(3);
            int spare = landed[shuffle];
            landed[shuffle] = landed[2];
            landed[2] = spare;
        } else {
            landed[0] = random.nextInt(FACES.length);
            landed[1] = (landed[0] + 1 + random.nextInt(FACES.length - 1)) % FACES.length;
            do {
                landed[2] = random.nextInt(FACES.length);
            } while (landed[2] == landed[0] || landed[2] == landed[1]);
        }
    }

    /** Called each server tick while the reels are moving. */
    public boolean tick() {
        if (spinning <= 0) {
            return false;
        }
        spinning--;

        for (int reel = 0; reel < 3; reel++) {
            boolean stillMoving = spinning > SPIN_TICKS - STOPS[reel];
            if (stillMoving) {
                offset[reel] = Math.floorMod(offset[reel] + 1, FACES.length);
            } else if (offset[reel] != landed[reel]) {
                offset[reel] = landed[reel];
                // A click as each reel locks: the sound of it landing is most
                // of what makes the last one tense.
                beep(0.8F + reel * 0.25F);
            }
        }
        repaint();

        if (spinning % 3 == 0) {
            player.getWorld().playSound(null, player.getBlockPos(),
                    SoundEvents.UI_BUTTON_CLICK.value(), SoundCategory.PLAYERS, 0.15F, 1.9F);
        }
        if (spinning == 0) {
            settle();
            return false;
        }
        return true;
    }

    private void settle() {
        int stake = STAKES[stakeChoice];
        int won = Math.round(stake * pending);
        var world = player.getWorld();

        if (won <= 0) {
            world.playSound(null, player.getBlockPos(),
                    SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), SoundCategory.PLAYERS, 0.8F, 0.6F);
            player.sendMessage(plain("Nothing. ").formatted(Formatting.GRAY)
                    .append(plain("-" + stake + "e").formatted(Formatting.RED)), false);
        } else {
            TrapMarket.pay(player, won);
            boolean big = pending >= 8.0f;
            world.playSound(null, player.getBlockPos(),
                    big ? SoundEvents.ENTITY_PLAYER_LEVELUP
                            : SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(),
                    SoundCategory.PLAYERS, 1.0F, big ? 1.0F : 1.5F);
            world.spawnParticles(big ? ParticleTypes.TOTEM_OF_UNDYING : ParticleTypes.HAPPY_VILLAGER,
                    player.getX(), player.getY() + 1.2, player.getZ(),
                    big ? 50 : 14, 0.4, 0.6, 0.4, big ? 0.35 : 0.05);
            player.sendMessage(plain(big ? "JACKPOT.  " : "Winner.  ")
                            .formatted(big ? Formatting.GOLD : Formatting.GREEN, Formatting.BOLD)
                            .append(plain("+" + won + "e").formatted(Formatting.GREEN))
                            .append(plain("   on a " + stake + "e spin")
                                    .formatted(Formatting.DARK_GRAY)),
                    false);
        }
        repaint();
    }

    private void beep(float pitch) {
        player.getWorld().playSound(null, player.getBlockPos(),
                SoundEvents.BLOCK_NOTE_BLOCK_HAT.value(), SoundCategory.PLAYERS, 0.6F, pitch);
    }

    private static MutableText plain(String text) {
        return Text.literal(text).styled(style -> style.withItalic(false));
    }

    private static MutableText line(String text, Formatting colour) {
        return plain(text).formatted(colour);
    }

    @Override
    public ItemStack quickMove(PlayerEntity who, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canUse(PlayerEntity who) {
        return who == player;
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
        public boolean canTakeItems(PlayerEntity who) {
            return false;
        }
    }
}
