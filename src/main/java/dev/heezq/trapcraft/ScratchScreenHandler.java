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
 * Buy a card. Scratch it one panel at a time.
 *
 * The only game on the floor with no animation and no clock, because it does
 * not need either: the card is already decided the moment you pay for it, and
 * everything after that is you choosing which square to uncover next. Two
 * diamonds showing and seven panels still silver is the best half-minute in
 * the building, and it costs nothing to build.
 *
 * Which is also the design rule. Nothing here may look at the card before you
 * do -- no "one more panel and you'd have had it", no nudge, no last-square
 * drama. The order you scratch in cannot change what the card is worth, and if
 * it ever could, the game would be a slot machine wearing a costume.
 */
public class ScratchScreenHandler extends ScreenHandler {
    private static final int ROWS = 3;
    private static final int SIZE = ROWS * 9;

    /** The 3x3 face, in the middle three columns. */
    private static final int[] PANELS = {3, 4, 5, 12, 13, 14, 21, 22, 23};

    private static final int INFO_SLOT = 0;
    private static final int THEME_SLOT = 1;
    private static final int PRIZES_SLOT = 9;
    private static final int STAKE_SLOT = 18;
    private static final int PURSE_SLOT = 8;
    private static final int ALL_SLOT = 17;
    private static final int BUY_SLOT = 26;

    private static final int[] STAKES = TrapMath.STAKES;

    private final SimpleInventory display = new SimpleInventory(SIZE);
    private final ServerPlayerEntity player;
    /** Whose money is on the other side of the table. Null means nobody's. */
    private final TrapHouse.House house;

    private int stakeChoice = 0;
    private int themeChoice;
    /** The card, or null when there isn't one on the counter. */
    private int[] card;
    private final boolean[] shown = new boolean[TrapMath.SCRATCH_PANELS];
    private int paid;
    private boolean settled;

    public ScratchScreenHandler(int syncId, PlayerInventory playerInventory,
                                TrapHouse.House house) {
        super(ScreenHandlerType.GENERIC_9X3, syncId);
        this.player = (ServerPlayerEntity) playerInventory.player;
        this.house = house;
        this.themeChoice = CHOSEN.getOrDefault(this.player.getUuid(), 0) % THEMES.length;

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

    // --- the faces ------------------------------------------------------------

    /**
     * What the card is wearing.
     *
     * Six ranks, six items, six words, and nothing else. A motyw cannot touch
     * what a face is WORTH -- that lives in TrapMath and is the same on every
     * card -- which is also why the colours are not in here: colour is rank,
     * and it has to mean the same thing on the payout table whichever skin is
     * on the counter. Being unable to change the odds is what makes it safe to
     * let somebody switch mid-card.
     */
    private record Theme(String name, Item foil, Item[] faces, String[] names) {
    }

    private static final Theme[] THEMES = {
            new Theme("Klasyk", Items.LIGHT_GRAY_STAINED_GLASS_PANE,
                    new Item[]{Items.COAL, Items.GOLD_NUGGET, Items.EMERALD,
                            Items.BELL, Items.DIAMOND, Items.NETHER_STAR},
                    new String[]{"Pusto", "Samorodek", "Szmaragd",
                            "Dzwonek", "Diament", "Gwiazda"}),
            new Theme("Kopalnia", Items.GRAY_STAINED_GLASS_PANE,
                    new Item[]{Items.COBBLESTONE, Items.RAW_COPPER, Items.RAW_IRON,
                            Items.RAW_GOLD, Items.DIAMOND, Items.ANCIENT_DEBRIS},
                    new String[]{"Skała", "Miedź", "Żelazo",
                            "Złoto", "Diament", "Debris"}),
            new Theme("Morski", Items.LIGHT_BLUE_STAINED_GLASS_PANE,
                    new Item[]{Items.KELP, Items.COD, Items.PRISMARINE_SHARD,
                            Items.NAUTILUS_SHELL, Items.HEART_OF_THE_SEA, Items.TRIDENT},
                    new String[]{"Wodorost", "Dorsz", "Pryzmaryn",
                            "Muszla", "Serce morza", "Trójząb"}),
            new Theme("Netherowy", Items.RED_STAINED_GLASS_PANE,
                    new Item[]{Items.NETHERRACK, Items.QUARTZ, Items.BLAZE_ROD,
                            Items.GHAST_TEAR, Items.NETHERITE_SCRAP, Items.NETHERITE_INGOT},
                    new String[]{"Netherrack", "Kwarc", "Płonący pręt",
                            "Łza ghasta", "Złom", "Netheryt"}),
            new Theme("Ogród", Items.LIME_STAINED_GLASS_PANE,
                    new Item[]{Items.STICK, Items.WHEAT, Items.APPLE,
                            Items.HONEYCOMB, Items.GOLDEN_APPLE, Items.ENCHANTED_GOLDEN_APPLE},
                    new String[]{"Patyk", "Pszenica", "Jabłko",
                            "Plaster miodu", "Złote jabłko", "Enchant"}),
            new Theme("Zaświaty", Items.PURPLE_STAINED_GLASS_PANE,
                    new Item[]{Items.ROTTEN_FLESH, Items.BONE, Items.ENDER_PEARL,
                            Items.PHANTOM_MEMBRANE, Items.ENDER_EYE, Items.DRAGON_BREATH},
                    new String[]{"Zgnilizna", "Kość", "Perła",
                            "Błona", "Oko Endera", "Oddech smoka"}),
    };

    /**
     * Which skin each player last picked. In memory, like TrapCasino's set:
     * the worst a restart can do is put somebody back on the Klasyk, and it is
     * one int per player who has ever opened a card.
     */
    private static final java.util.Map<java.util.UUID, Integer> CHOSEN =
            new java.util.HashMap<>();

    private Theme theme() {
        return THEMES[themeChoice];
    }

    private Item faceItem(int face) {
        return theme().faces()[face];
    }

    private String faceName(int face) {
        return theme().names()[face];
    }

    private static Formatting faceColour(int face) {
        return switch (face) {
            case 1 -> Formatting.YELLOW;
            case 2 -> Formatting.GREEN;
            case 3 -> Formatting.GOLD;
            case 4 -> Formatting.AQUA;
            case 5 -> Formatting.LIGHT_PURPLE;
            default -> Formatting.DARK_GRAY;
        };
    }

    // --- the counter ----------------------------------------------------------

    private void paint() {
        ItemStack filler = new ItemStack(Items.BLACK_STAINED_GLASS_PANE);
        filler.set(DataComponentTypes.CUSTOM_NAME, plain(" "));
        for (int index = 0; index < SIZE; index++) {
            display.setStack(index, filler.copy());
        }

        for (int panel = 0; panel < PANELS.length; panel++) {
            display.setStack(PANELS[panel], panelTag(panel));
        }
        display.setStack(INFO_SLOT, infoTag());
        display.setStack(THEME_SLOT, themeTag());
        display.setStack(PRIZES_SLOT, prizeTag());
        display.setStack(STAKE_SLOT, stakeTag());
        display.setStack(PURSE_SLOT, purseTag());
        display.setStack(ALL_SLOT, allTag());
        display.setStack(BUY_SLOT, buyTag());
        sendContentUpdates();
    }

    private ItemStack panelTag(int panel) {
        if (card == null) {
            ItemStack blank = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
            blank.set(DataComponentTypes.CUSTOM_NAME,
                    plain("No card").formatted(Formatting.DARK_GRAY));
            return blank;
        }
        if (!shown[panel]) {
            ItemStack foil = new ItemStack(theme().foil());
            foil.set(DataComponentTypes.CUSTOM_NAME,
                    plain("? ? ?").formatted(Formatting.WHITE, Formatting.BOLD));
            foil.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                    line("Kliknij, żeby zdrapać.", Formatting.YELLOW))));
            return foil;
        }
        int face = card[panel];
        ItemStack tag = new ItemStack(faceItem(face));
        boolean winner = settled && face == TrapMath.scratchWinner(card);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain(faceName(face)).formatted(faceColour(face), Formatting.BOLD));
        if (winner) {
            tag.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        }
        return tag;
    }

    private ItemStack infoTag() {
        ItemStack tag = new ItemStack(Items.BOOK);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Scratchers").formatted(Formatting.YELLOW, Formatting.BOLD));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line("Kup kartę i klikaj pola.", Formatting.GRAY),
                line("Trzy takie same płacą. Więcej płaci więcej.", Formatting.GRAY),
                Text.empty(),
                line("Trzy w rzędzie, kolumnie albo po", Formatting.WHITE),
                line("przekątnej płacą PODWÓJNIE.", Formatting.WHITE),
                Text.empty(),
                line("Jedna nagroda na kartę -- najwyższa.", Formatting.DARK_GRAY),
                Text.empty(),
                line("Około " + Math.round(TrapMath.SCRATCH_MEASURED_WIN_RATE * 100)
                        + " kart na 100 coś wypłaca,", Formatting.DARK_GRAY),
                line("a większość z nich zwraca mniej, niż", Formatting.DARK_GRAY),
                line("kosztowała karta. Kasyno bierze około "
                        + Math.round((1 - TrapMath.SCRATCH_MEASURED_RTP) * 100) + "%.",
                        Formatting.DARK_GRAY))));
        return tag;
    }

    private ItemStack themeTag() {
        ItemStack tag = new ItemStack(faceItem(TrapMath.SCRATCH_FACES - 1));
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Motyw: ").formatted(Formatting.GRAY)
                        .append(plain(theme().name())
                                .formatted(Formatting.AQUA, Formatting.BOLD)));
        List<Text> lore = new ArrayList<>();
        lore.add(line("Klik: następny, prawy klik: poprzedni.", Formatting.YELLOW));
        lore.add(Text.empty());
        for (int index = 0; index < THEMES.length; index++) {
            lore.add(line((index == themeChoice ? " > " : "   ") + THEMES[index].name(),
                    index == themeChoice ? Formatting.WHITE : Formatting.DARK_GRAY));
        }
        lore.add(Text.empty());
        lore.add(line("Sam wygląd. Szanse i wypłaty", Formatting.DARK_GRAY));
        lore.add(line("są takie same na każdej karcie.", Formatting.DARK_GRAY));
        tag.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return tag;
    }

    private ItemStack prizeTag() {
        ItemStack tag = new ItemStack(Items.PAINTING);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Tabela wypłat").formatted(Formatting.GOLD, Formatting.BOLD));
        List<Text> lore = new ArrayList<>();
        lore.add(line("Trzy takie same...", Formatting.DARK_GRAY));
        for (int face = TrapMath.SCRATCH_FACES - 1; face >= 1; face--) {
            lore.add(plain("  " + faceName(face)).formatted(faceColour(face))
                    .append(plain("   " + trim(TrapMath.SCRATCH_PRIZES[face]) + "x")
                            .formatted(Formatting.WHITE)));
        }
        lore.add(Text.empty());
        lore.add(line("Cztery takie  x" + trim(TrapMath.SCRATCH_SIZES[4]), Formatting.GRAY));
        lore.add(line("Pięć takich  x" + trim(TrapMath.SCRATCH_SIZES[5]), Formatting.GRAY));
        lore.add(line("Sześć lub więcej   x" + trim(TrapMath.SCRATCH_SIZES[6]), Formatting.GRAY));
        lore.add(line("W linii     x" + trim(TrapMath.SCRATCH_LINE_BONUS), Formatting.GRAY));
        lore.add(Text.empty());
        lore.add(line("Wszystko jako wielokrotność ceny karty.", Formatting.DARK_GRAY));
        tag.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return tag;
    }

    private ItemStack stakeTag() {
        ItemStack tag = new ItemStack(Items.EMERALD,
                Math.max(1, Math.min(64, STAKES[stakeChoice] / 8)));
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Karta: ").formatted(Formatting.GRAY)
                        .append(plain(STAKES[stakeChoice] + "e")
                                .formatted(Formatting.GREEN, Formatting.BOLD)));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line(card != null ? "Najpierw dokończ tę kartę."
                        : "Klik: wyżej, prawy klik: niżej.", Formatting.DARK_GRAY))));
        return tag;
    }

    private ItemStack purseTag() {
        ItemStack tag = new ItemStack(Items.GOLD_NUGGET);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Kasa: ").formatted(Formatting.GRAY)
                        .append(plain(TrapMarket.wealthOf(player) + "e")
                                .formatted(Formatting.GREEN, Formatting.BOLD)));
        tag.set(DataComponentTypes.LORE, new LoreComponent(
                TrapHouse.tableNote(house, TrapHouse.TOP_SCRATCH)));
        return tag;
    }

    private ItemStack allTag() {
        boolean can = card != null && !settled;
        ItemStack tag = new ItemStack(can ? Items.SHEARS : Items.GRAY_DYE);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain("Zdrap wszystko")
                        .formatted(can ? Formatting.YELLOW : Formatting.DARK_GRAY,
                                Formatting.BOLD));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line(can ? "Gdy nie masz cierpliwości." : "Nie ma czego zdrapywać.",
                        can ? Formatting.GRAY : Formatting.DARK_GRAY),
                Text.empty(),
                line("Karta była już taka, zanim zacząłeś", Formatting.DARK_GRAY),
                line("ją zdrapywać. Kolejność nic nie zmienia.", Formatting.DARK_GRAY))));
        return tag;
    }

    private ItemStack buyTag() {
        boolean fresh = card == null || settled;
        ItemStack tag = new ItemStack(fresh ? Items.PAPER : Items.GRAY_DYE);
        tag.set(DataComponentTypes.CUSTOM_NAME,
                plain(card == null ? "Kup kartę" : settled ? "Jeszcze jedna" : "Karta w grze")
                        .formatted(fresh ? Formatting.GREEN : Formatting.DARK_GRAY,
                                Formatting.BOLD));
        tag.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                line(fresh ? STAKES[stakeChoice] + "e." : "Dokończ tę, którą masz.",
                        fresh ? Formatting.GRAY : Formatting.DARK_GRAY))));
        return tag;
    }

    /** 4.25 rather than 4.25x when it's whole; nobody writes "3.0x" on a board. */
    private static String trim(float value) {
        return value == Math.rint(value)
                ? String.valueOf((int) value) : String.valueOf(value);
    }

    // --- playing --------------------------------------------------------------

    @Override
    public void onSlotClick(int index, int button, SlotActionType type, PlayerEntity clicker) {
        if (index < 0 || index >= SIZE) {
            super.onSlotClick(index, button, type, clicker);
            return;
        }
        if (index == STAKE_SLOT) {
            if (card != null && !settled) {
                deny();
                return;
            }
            stakeChoice = TrapMath.cycle(stakeChoice, STAKES.length, button == 1);
            click(1.4F);
            paint();
            return;
        }
        // Allowed mid-card on purpose: a costume cannot reach the numbers, so
        // there is nothing to protect the card from here.
        if (index == THEME_SLOT) {
            themeChoice = TrapMath.cycle(themeChoice, THEMES.length, button == 1);
            CHOSEN.put(player.getUuid(), themeChoice);
            click(1.1F);
            paint();
            return;
        }
        if (index == BUY_SLOT) {
            buy();
            return;
        }
        if (index == ALL_SLOT) {
            if (card == null || settled) {
                deny();
                return;
            }
            for (int panel = 0; panel < shown.length; panel++) {
                shown[panel] = true;
            }
            click(0.7F);
            finish();
            return;
        }
        for (int panel = 0; panel < PANELS.length; panel++) {
            if (index == PANELS[panel]) {
                scratch(panel);
                return;
            }
        }
    }

    private void buy() {
        if (card != null && !settled) {
            deny();
            return;
        }
        int stake = STAKES[stakeChoice];
        if (!TrapHouse.covers(house, stake, TrapHouse.TOP_SCRATCH)) {
            deny();
            player.sendMessage(plain("Kasyno nie sprzeda ci tej karty -- nie ma na nią "
                    + "pokrycia w skarbcu.").formatted(Formatting.GRAY), false);
            return;
        }
        if (TrapMarket.wealthOf(player) < stake) {
            deny();
            player.sendMessage(plain("Nie stać cię na kartę za " + stake + "e.")
                    .formatted(Formatting.GRAY), false);
            return;
        }
        TrapHouse.stake(player, house, stake);
        paid = stake;
        // Printed here, in full, before a single panel is uncovered. What you
        // find is what was already on it.
        card = TrapMath.scratchCard(new java.util.Random(
                player.getWorld().getRandom().nextLong()));
        java.util.Arrays.fill(shown, false);
        settled = false;

        player.getWorld().playSound(null, player.getBlockPos(),
                SoundEvents.ITEM_BOOK_PAGE_TURN, SoundCategory.PLAYERS, 0.9F, 1.4F);
        paint();
    }

    private void scratch(int panel) {
        if (card == null || settled || shown[panel]) {
            deny();
            return;
        }
        shown[panel] = true;
        int face = card[panel];

        // Pitch climbs with how many of that face are already showing, so the
        // sound itself tells you the card is coming together before you have
        // counted. It reports what is ALREADY uncovered -- reading the whole
        // card here would be the machine telling you what is coming.
        int matching = 0;
        for (int other = 0; other < shown.length; other++) {
            if (shown[other] && card[other] == face && face != 0) {
                matching++;
            }
        }
        player.getWorld().playSound(null, player.getBlockPos(),
                SoundEvents.BLOCK_NOTE_BLOCK_HAT.value(), SoundCategory.PLAYERS,
                0.6F, 1.0F + Math.min(3, matching) * 0.22F);
        if (matching >= 2) {
            player.getWorld().playSound(null, player.getBlockPos(),
                    SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), SoundCategory.PLAYERS,
                    0.5F, 0.9F + matching * 0.15F);
        }

        for (boolean seen : shown) {
            if (!seen) {
                paint();
                return;
            }
        }
        finish();
    }

    private void finish() {
        settled = true;
        float multiple = TrapMath.scratchPay(card);
        int won = Math.round(paid * multiple);
        int face = TrapMath.scratchWinner(card);
        var world = player.getWorld();

        if (won <= 0) {
            world.playSound(null, player.getBlockPos(),
                    SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), SoundCategory.PLAYERS, 0.6F, 0.6F);
            player.sendMessage(plain("Nic na niej nie ma.").formatted(Formatting.GRAY), false);
            paint();
            return;
        }

        won = TrapHouse.payout(player, house, won);
        int count = TrapMath.scratchCount(card, face);
        boolean lined = count == 3 && TrapMath.scratchInLine(card, face);
        if (won > paid) {
            TrapCasino.won(player, "scratch");
        }
        if (multiple >= 10.0f) {
            TrapAwards.grant(player, "jackpot");
            world.spawnParticles(ParticleTypes.TOTEM_OF_UNDYING,
                    player.getX(), player.getY() + 1.3, player.getZ(), 70, 0.6, 0.6, 0.6, 0.45);
            world.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_PLAYER_LEVELUP,
                    SoundCategory.PLAYERS, 1.0F, 0.9F);
        } else {
            world.spawnParticles(ParticleTypes.HAPPY_VILLAGER,
                    player.getX(), player.getY() + 1.2, player.getZ(), 14, 0.4, 0.4, 0.4, 0.05);
            world.playSound(null, player.getBlockPos(),
                    SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), SoundCategory.PLAYERS, 0.8F, 1.5F);
        }

        int net = won - paid;
        player.sendMessage(plain(count + "x ").formatted(Formatting.WHITE)
                .append(plain(faceName(face)).formatted(faceColour(face), Formatting.BOLD))
                .append(plain(lined ? "  W LINII" : "")
                        .formatted(Formatting.GOLD, Formatting.BOLD))
                .append(plain("   ").formatted(Formatting.GRAY))
                .append(plain("+" + won + "e").formatted(Formatting.GREEN, Formatting.BOLD))
                .append(plain(net >= 0 ? "   (" + net + "e up)" : "   (" + (-net) + "e down)")
                        .formatted(net >= 0 ? Formatting.DARK_GRAY : Formatting.RED)), false);
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

    /**
     * An unfinished card is paid out on the way out.
     *
     * You bought it, it is already worth whatever it is worth, and closing a
     * screen must never be able to lose you a prize you had not got round to
     * uncovering. The panels were only ever a way of finding out.
     */
    @Override
    public void onClosed(PlayerEntity closer) {
        super.onClosed(closer);
        if (card == null || settled) {
            return;
        }
        settled = true;
        int won = Math.round(paid * TrapMath.scratchPay(card));
        if (won > 0) {
            TrapHouse.payout(player, house, won);
            player.sendMessage(plain("Zostawiłeś ").formatted(Formatting.GRAY)
                    .append(plain(won + "e").formatted(Formatting.GREEN, Formatting.BOLD))
                    .append(plain(" na ladzie. Wysłano pocztą.")
                            .formatted(Formatting.GRAY)), false);
        }
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
