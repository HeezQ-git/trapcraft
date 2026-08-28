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

import java.util.List;

/**
 * Call it.
 *
 * The fastest thing on the floor and the only one you can play in four
 * seconds. Heads, tails, or -- if you fancy it -- the coin landing on its rim,
 * which happens about three times in two hundred and pays sixty-four to one.
 *
 * All three calls carry the same house edge to within half a percent, so the
 * rim is not a trap, it is a choice about variance. Which is the whole reason
 * it is there: everybody tries it once, nobody sensible tries it twice, and
 * one day somebody is going to hit it and never stop talking about it.
 */
public class TossScreenHandler extends ScreenHandler implements TrapTables.Playing {
    private static final int ROWS = 3;
    private static final int SIZE = ROWS * 9;

    private static final int COIN_SLOT = 4;
    private static final int HEADS_SLOT = 11;
    private static final int EDGE_SLOT = 13;
    private static final int TAILS_SLOT = 15;
    private static final int STAKE_SLOT = 18;
    private static final int PURSE_SLOT = 26;

    private static final int[] STAKES = TrapMath.STAKES;
    /** Ticks the coin is in the air. Short: this game's appeal is that it's fast. */
    private static final int FLIGHT = 26;

    private final SimpleInventory display = new SimpleInventory(SIZE);
    private final ServerPlayerEntity player;
    /** Whose money is on the other side of the table. Null means nobody's. */
    private final TrapHouse.House house;
    private int stakeChoice = 0;

    private int spinning;
    private int called = -1;
    private int result = -1;
    private int won;
    private int celebrating;
    private int flash;
    private boolean closed;

    public TossScreenHandler(int syncId, PlayerInventory playerInventory,
                             TrapHouse.House house) {
        super(ScreenHandlerType.GENERIC_9X3, syncId);
        this.player = (ServerPlayerEntity) playerInventory.player;
        this.house = house;

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
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInventory, col, 8 + col * 18, 142));
        }
        paint();
    }

    // --- the coin -------------------------------------------------------------

    private static String faceName(int side) {
        return switch (side) {
            case 0 -> "Heads";
            case 1 -> "Tails";
            default -> "Kant";
        };
    }

    private void paint() {
        ItemStack filler = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
        filler.set(DataComponentTypes.CUSTOM_NAME, plain(" "));
        for (int index = 0; index < SIZE; index++) {
            display.setStack(index, filler.copy());
        }

        display.setStack(COIN_SLOT, coinTag());
        display.setStack(HEADS_SLOT, callTag(0));
        display.setStack(EDGE_SLOT, callTag(2));
        display.setStack(TAILS_SLOT, callTag(1));
        display.setStack(STAKE_SLOT, stakeTag());
        display.setStack(PURSE_SLOT, purseTag());
        sendContentUpdates();
    }

    /** The coin itself: tumbling, or showing what it came down as. */
    private ItemStack coinTag() {
        int showing = spinning > 0 ? flash % 2 : Math.max(0, result);
        Item face = spinning > 0
                ? (showing == 0 ? Items.GOLD_INGOT : Items.IRON_INGOT)
                : result == 2 ? Items.NETHER_STAR
                : result == 0 ? Items.GOLD_INGOT
                : result == 1 ? Items.IRON_INGOT : Items.GOLD_NUGGET;

        ItemStack tag = new ItemStack(face);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain(spinning > 0 ? "..." : result < 0 ? "Obstaw" : faceName(result))
                        .formatted(result == 2 ? Formatting.LIGHT_PURPLE
                                        : spinning > 0 ? Formatting.GRAY : Formatting.GOLD,
                                Formatting.BOLD));
        if (result >= 0 && celebrating > 0) {
            tag.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        }
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line("Orzeł i reszka płacą "
                        + String.format("%.2f", TrapMath.TOSS_SIDE_PAY) + "x.", Formatting.GRAY),
                line("Kant płaci " + (int) TrapMath.TOSS_EDGE_PAY + "x i wypada w",
                        Formatting.GRAY),
                line("mniej więcej " + Math.round(TrapMath.TOSS_EDGE_CHANCE * 1000) / 10.0
                        + "% rzutów.", Formatting.GRAY),
                Text.empty(),
                line("Ta sama przewaga kasyna niezależnie od wyboru.", Formatting.DARK_GRAY))));
        return tag;
    }

    private ItemStack callTag(int side) {
        boolean rim = side == 2;
        ItemStack tag = new ItemStack(rim ? Items.NETHER_STAR
                : side == 0 ? Items.GOLD_BLOCK : Items.IRON_BLOCK);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain(faceName(side).toUpperCase(java.util.Locale.ROOT))
                        .formatted(rim ? Formatting.LIGHT_PURPLE
                                : side == 0 ? Formatting.GOLD : Formatting.WHITE,
                                Formatting.BOLD));
        float pays = rim ? TrapMath.TOSS_EDGE_PAY : TrapMath.TOSS_SIDE_PAY;
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line("Płaci " + (rim ? String.valueOf((int) pays) : String.format("%.2f", pays))
                        + "x  ->  " + Math.round(STAKES[stakeChoice] * pays) + "e",
                        Formatting.GOLD),
                line(rim ? "Czasem staje na kancie." : "Mniej więcej jeden do jednego.",
                        Formatting.DARK_GRAY),
                Text.empty(),
                line("Kliknij, żeby obstawić i rzucić.", Formatting.YELLOW))));
        return tag;
    }

    private ItemStack stakeTag() {
        ItemStack tag = new ItemStack(Items.EMERALD,
                Math.max(1, Math.min(64, STAKES[stakeChoice] / 8)));
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Stawka: ").formatted(Formatting.GRAY)
                        .append(plain(STAKES[stakeChoice] + "e")
                                .formatted(Formatting.GREEN, Formatting.BOLD)));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line("Klik: wyżej, prawy klik: niżej.", Formatting.DARK_GRAY))));
        return tag;
    }

    private ItemStack purseTag() {
        ItemStack tag = new ItemStack(Items.GOLD_NUGGET);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Kasa: ").formatted(Formatting.GRAY)
                        .append(plain(TrapMarket.wealthOf(player) + "e")
                                .formatted(Formatting.GREEN, Formatting.BOLD)));
        tag.set(DataComponentTypes.LORE, new LoreComponent(
                TrapHouse.tableNote(house, TrapHouse.TOP_TOSS)));
        return tag;
    }

    // --- tossing --------------------------------------------------------------

    @Override
    public void onSlotClick(int index, int button, SlotActionType type, PlayerEntity clicker) {
        if (index < 0 || index >= SIZE) {
            super.onSlotClick(index, button, type, clicker);
            return;
        }
        if (spinning > 0 || celebrating > 0) {
            return;
        }
        if (index == STAKE_SLOT) {
            stakeChoice = TrapMath.cycle(stakeChoice, STAKES.length, button == 1);
            click(1.4F);
            paint();
            return;
        }
        int side = switch (index) {
            case HEADS_SLOT -> 0;
            case TAILS_SLOT -> 1;
            case EDGE_SLOT -> 2;
            default -> -1;
        };
        if (side >= 0) {
            toss(side);
        }
    }

    private void toss(int side) {
        int stake = STAKES[stakeChoice];
        if (!TrapHouse.covers(house, stake, TrapHouse.TOP_TOSS)) {
            deny();
            player.sendMessage(plain("Kasyno tego nie przyjmie -- kant płaci 64 do jednego, "
                            + "a stół nie ma na to pokrycia.")
                    .formatted(Formatting.GRAY), false);
            return;
        }
        if (TrapMarket.wealthOf(player) < stake) {
            deny();
            player.sendMessage(plain("Nie stać cię na rzut za " + stake + "e.")
                    .formatted(Formatting.GRAY), false);
            return;
        }
        TrapHouse.stake(player, house, stake);
        called = side;
        result = TrapMath.tossResult(player.getWorld().getRandom().nextFloat());
        spinning = FLIGHT;
        TrapTables.watch(this);

        player.getWorld().playSound(null, player.getBlockPos(),
                SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), SoundCategory.PLAYERS, 0.8F, 1.8F);
        paint();
    }

    @Override
    public boolean tick() {
        flash++;
        if (closed) {
            return false;
        }
        if (celebrating > 0) {
            celebrating--;
            paint();
            if (celebrating == 0) {
                announce();
                return false;
            }
            return true;
        }
        if (spinning <= 0) {
            return false;
        }
        spinning--;
        paint();
        if (spinning % 3 == 0) {
            player.getWorld().playSound(null, player.getBlockPos(),
                    SoundEvents.BLOCK_NOTE_BLOCK_HAT.value(), SoundCategory.PLAYERS,
                    0.3F, 1.2F + spinning * 0.02F);
        }
        if (spinning == 0) {
            settle();
            celebrating = 18;
        }
        return true;
    }

    private void settle() {
        int stake = STAKES[stakeChoice];
        won = Math.round(stake * TrapMath.tossReturn(called, result));
        if (won > 0) {
            won = TrapHouse.payout(player, house, won);
            TrapCasino.won(player, "toss");
        }

        var world = player.getWorld();
        if (result == 2) {
            // The rim. Loud whether or not anybody called it -- watching one
            // land and not having it is most of the appeal.
            world.spawnParticles(ParticleTypes.TOTEM_OF_UNDYING,
                    player.getX(), player.getY() + 1.3, player.getZ(), 70, 0.6, 0.6, 0.6, 0.45);
            world.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_PLAYER_LEVELUP,
                    SoundCategory.PLAYERS, 1.0F, 0.9F);
            if (won > 0) {
                TrapAwards.grant(player, "jackpot");
                TrapAwards.grant(player, "rim");
            }
            return;
        }
        world.playSound(null, player.getBlockPos(),
                won > 0 ? SoundEvents.BLOCK_NOTE_BLOCK_BELL.value()
                        : SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(),
                SoundCategory.PLAYERS, 0.8F, won > 0 ? 1.5F : 0.6F);
        if (won > 0) {
            world.spawnParticles(ParticleTypes.HAPPY_VILLAGER,
                    player.getX(), player.getY() + 1.2, player.getZ(), 12, 0.4, 0.4, 0.4, 0.04);
        }
    }

    private void announce() {
        int stake = STAKES[stakeChoice];
        MutableText face = plain(faceName(result)).formatted(
                result == 2 ? Formatting.LIGHT_PURPLE
                        : result == 0 ? Formatting.GOLD : Formatting.WHITE, Formatting.BOLD);
        if (won <= 0) {
            player.sendMessage(plain("Wypadł ").formatted(Formatting.GRAY)
                    .append(face)
                    .append(plain(". Obstawiałeś " + faceName(called) + ".   ")
                            .formatted(Formatting.GRAY))
                    .append(plain("-" + stake + "e").formatted(Formatting.RED)), false);
        } else {
            player.sendMessage(plain("Wypadł ").formatted(Formatting.GRAY)
                    .append(face)
                    .append(plain(".   ").formatted(Formatting.GRAY))
                    .append(plain("+" + won + "e").formatted(Formatting.GREEN, Formatting.BOLD))
                    .append(plain(result == 2 ? "   NA KANCIE." : "")
                            .formatted(Formatting.LIGHT_PURPLE, Formatting.BOLD)), false);
        }
        result = -1;
        called = -1;
        paint();
    }

    // --- trimmings ------------------------------------------------------------

    private void click(float pitch) {
        player.getWorld().playSound(null, player.getBlockPos(),
                SoundEvents.UI_BUTTON_CLICK.value(), SoundCategory.PLAYERS, 0.5F, pitch);
    }

    private void deny() {
        player.getWorld().playSound(null, player.getBlockPos(),
                SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), SoundCategory.PLAYERS, 0.7F, 0.6F);
    }

    private static MutableText plain(String text) {
        return Text.literal(text).styled(style -> style.withItalic(false));
    }

    private static MutableText line(String text, Formatting... colours) {
        return plain(text).formatted(colours);
    }

    @Override
    public void onClosed(PlayerEntity closer) {
        super.onClosed(closer);
        closed = true;
    }

    @Override
    public ItemStack quickMove(PlayerEntity mover, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canUse(PlayerEntity user) {
        return user == player;
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
        public boolean canTakeItems(PlayerEntity taker) {
            return false;
        }
    }
}
