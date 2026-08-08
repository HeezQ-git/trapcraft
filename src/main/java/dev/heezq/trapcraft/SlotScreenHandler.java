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
 * The one-armed bandit.
 *
 * A hopper screen: five slots in a row, which is exactly three reels with a
 * stake button on one side and the lever on the other. No filler.
 *
 * The reels are theatre. The outcome is drawn from {@link TrapMath#slotPayout}
 * before the reels are chosen, and the symbols are then picked to agree with
 * it -- which is how real machines work, and the only way the return rate is a
 * number anyone can actually check. It pays back 85% over time, and roughly
 * three spins in four pay nothing.
 */
public class SlotScreenHandler extends ScreenHandler {
    private static final int SIZE = 5;
    private static final int STAKE_SLOT = 0;
    private static final int REEL_ONE = 1;
    private static final int LEVER_SLOT = 4;

    /** Reel faces, worst to best. The last is the jackpot. */
    private static final Item[] FACES = {
            Items.COAL, Items.COPPER_INGOT, Items.IRON_INGOT,
            Items.GOLD_INGOT, Items.DIAMOND, Items.NETHER_STAR,
    };

    private static final int[] STAKES = {8, 32, 128};

    private final SimpleInventory display = new SimpleInventory(SIZE);
    private final ServerPlayerEntity player;
    private int stakeChoice = 0;
    /** Ticks left of the reels spinning; zero when idle. */
    private int spinning;
    private float pending;

    public SlotScreenHandler(int syncId, PlayerInventory playerInventory) {
        super(ScreenHandlerType.HOPPER, syncId);
        this.player = (ServerPlayerEntity) playerInventory.player;

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
        idle();
    }

    private void idle() {
        for (int reel = 0; reel < 3; reel++) {
            display.setStack(REEL_ONE + reel, face(FACES[reel % FACES.length], null));
        }
        display.setStack(STAKE_SLOT, stakeTag());
        display.setStack(LEVER_SLOT, leverTag());
        sendContentUpdates();
    }

    private ItemStack stakeTag() {
        ItemStack tag = new ItemStack(Items.EMERALD, Math.max(1, STAKES[stakeChoice] / 8));
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Stake: ").formatted(Formatting.GRAY)
                        .append(plain(STAKES[stakeChoice] + "e")
                                .formatted(Formatting.GREEN, Formatting.BOLD)));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line("Click to change.", Formatting.YELLOW))));
        return tag;
    }

    private ItemStack leverTag() {
        ItemStack tag = new ItemStack(spinning > 0 ? Items.REDSTONE_TORCH : Items.LEVER);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain(spinning > 0 ? "Spinning..." : "PULL")
                        .formatted(spinning > 0 ? Formatting.GRAY : Formatting.GOLD,
                                Formatting.BOLD));
        List<Text> lore = new ArrayList<>();
        lore.add(line("Three of a kind pays big.", Formatting.GRAY));
        lore.add(line("Two pays a little.", Formatting.GRAY));
        lore.add(Text.empty());
        lore.add(line("The house wins more than it loses.", Formatting.DARK_GRAY));
        tag.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return tag;
    }

    private ItemStack face(Item item, String label) {
        ItemStack tag = new ItemStack(item);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain(label == null ? " " : label).formatted(Formatting.WHITE));
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
            idle();
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
        // Outcome first, reels afterwards.
        pending = TrapMath.slotPayout(player.getWorld().getRandom().nextFloat());
        spinning = 22;
        SlotMachineBlock.watch(this);
        player.getWorld().playSound(null, player.getBlockPos(),
                SoundEvents.BLOCK_LEVER_CLICK, SoundCategory.PLAYERS, 0.9F, 0.7F);
        idle();
    }

    /** Called each server tick while the reels are moving. */
    public boolean tick() {
        if (spinning <= 0) {
            return false;
        }
        spinning--;
        var random = player.getWorld().getRandom();

        // Reels stop left to right, which is the whole reason the last one is
        // exciting.
        int settled = spinning < 6 ? 3 : spinning < 11 ? 2 : spinning < 16 ? 1 : 0;
        Item[] shown = symbolsFor(pending, random.nextInt(FACES.length));
        for (int reel = 0; reel < 3; reel++) {
            Item item = reel < settled ? shown[reel] : FACES[random.nextInt(FACES.length)];
            display.setStack(REEL_ONE + reel, face(item, null));
        }
        display.setStack(LEVER_SLOT, leverTag());
        sendContentUpdates();

        if (spinning % 4 == 0) {
            beep(0.9F + (22 - spinning) * 0.02F);
        }
        if (spinning == 0) {
            settle();
            return false;
        }
        return true;
    }

    /**
     * Reels that agree with an already-decided outcome.
     *
     * Three of a kind for the big payouts, a matching pair for the small one,
     * and a deliberate mismatch for a loss -- so what you see always explains
     * what you were paid.
     */
    private Item[] symbolsFor(float payout, int seed) {
        if (payout >= 20.0f) {
            return new Item[]{FACES[5], FACES[5], FACES[5]};
        }
        if (payout >= 8.0f) {
            return new Item[]{FACES[4], FACES[4], FACES[4]};
        }
        if (payout >= 3.0f) {
            Item hit = FACES[2 + seed % 2];
            return new Item[]{hit, hit, hit};
        }
        if (payout > 0.0f) {
            Item pair = FACES[seed % FACES.length];
            Item odd = FACES[(seed + 3) % FACES.length];
            return new Item[]{pair, pair, odd};
        }
        return new Item[]{FACES[seed % FACES.length],
                FACES[(seed + 2) % FACES.length], FACES[(seed + 4) % FACES.length]};
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
                    big ? 40 : 12, 0.4, 0.5, 0.4, big ? 0.3 : 0.05);
            player.sendMessage(plain(big ? "JACKPOT.  " : "Winner.  ")
                            .formatted(big ? Formatting.GOLD : Formatting.GREEN, Formatting.BOLD)
                            .append(plain(won + "e").formatted(Formatting.GREEN))
                            .append(plain("   on a " + stake + "e spin").formatted(Formatting.DARK_GRAY)),
                    false);
        }
        idle();
    }

    private void beep(float pitch) {
        player.getWorld().playSound(null, player.getBlockPos(),
                SoundEvents.UI_BUTTON_CLICK.value(), SoundCategory.PLAYERS, 0.4F, pitch);
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
