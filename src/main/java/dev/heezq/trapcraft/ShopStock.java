package dev.heezq.trapcraft;

import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

/**
 * What the market sells, and what it's worth on a flat day.
 *
 * Prices are in emeralds for the bundle named in `count`, before the daily
 * index and drift. They are set against what this server actually earns: a
 * Fire joint pays 13, a good customer visit runs to about a hundred, a fat
 * contract to a couple of hundred. So staples cost pocket change, a diamond
 * is an afternoon, and the endgame shelf is a project you save for rather
 * than something you pick up on the way past.
 *
 * The expensive things are deliberately NOT a shortcut. A nether star at 1400
 * is several full contract runs -- cheaper than fighting a wither if you hate
 * fighting withers, and dear enough that nobody does it by accident.
 */
public final class ShopStock {

    /** One shelf of the market. */
    public record Category(String id, String title, Item icon, Formatting colour, String blurb) {
    }

    /** One line: what it is, how many you get, and the flat-day price. */
    public record Entry(Category category, Item item, int count, int base) {
        public String id() {
            return net.minecraft.registry.Registries.ITEM.getId(item).toString();
        }
    }

    public static final Category BUILDING = new Category("building", "Building",
            Items.BRICKS, Formatting.GOLD, "Blocks by the stack");
    public static final Category FARMING = new Category("farming", "Farm & Garden",
            Items.WHEAT_SEEDS, Formatting.GREEN, "Seeds, saplings, stock");
    public static final Category FOOD = new Category("food", "Food",
            Items.COOKED_BEEF, Formatting.RED, "Something to eat");
    public static final Category MATERIALS = new Category("materials", "Materials",
            Items.IRON_INGOT, Formatting.AQUA, "Ore, ingots, gems");
    public static final Category UTILITY = new Category("utility", "Utility",
            Items.REDSTONE, Formatting.LIGHT_PURPLE, "Redstone and hardware");
    public static final Category RARE = new Category("rare", "The Good Stuff",
            Items.NETHER_STAR, Formatting.DARK_PURPLE, "If you can afford it");

    public static final List<Category> CATEGORIES =
            List.of(BUILDING, FARMING, FOOD, MATERIALS, UTILITY, RARE);

    private static final List<Entry> STOCK = new ArrayList<>();

    /**
     * Items the market must never trade, at any price.
     *
     * These ARE the money. An emerald block was listed at 8e and is nine
     * emeralds in a coat: buy, uncraft, repeat, and the economy is over. Any
     * item convertible to currency by a vanilla recipe has the same hole, and
     * the price moves daily, so "the number happens to be safe today" is not
     * a defence.
     */
    private static final List<Item> CURRENCY = List.of(Items.EMERALD, Items.EMERALD_BLOCK);

    private static void add(Category category, Item item, int count, int base) {
        // Fails at startup rather than silently shipping a money printer.
        if (CURRENCY.contains(item)) {
            throw new IllegalArgumentException(
                    "the market cannot trade its own currency: " + item);
        }
        STOCK.add(new Entry(category, item, count, base));
    }

    static {
        // --- Building: sold by the stack, priced as bulk ----------------------
        add(BUILDING, Items.STONE, 64, 6);
        add(BUILDING, Items.COBBLESTONE, 64, 4);
        add(BUILDING, Items.DEEPSLATE, 64, 6);
        add(BUILDING, Items.OAK_LOG, 32, 8);
        add(BUILDING, Items.OAK_PLANKS, 64, 5);
        add(BUILDING, Items.GLASS, 64, 12);
        add(BUILDING, Items.SAND, 64, 5);
        add(BUILDING, Items.GRAVEL, 64, 5);
        add(BUILDING, Items.CLAY_BALL, 32, 8);
        add(BUILDING, Items.BRICKS, 32, 16);
        add(BUILDING, Items.QUARTZ_BLOCK, 16, 22);
        add(BUILDING, Items.WHITE_CONCRETE_POWDER, 32, 10);
        add(BUILDING, Items.TERRACOTTA, 32, 9);
        add(BUILDING, Items.OBSIDIAN, 8, 34);
        add(BUILDING, Items.SCAFFOLDING, 32, 9);

        // --- Farm & Garden ---------------------------------------------------
        add(FARMING, Items.WHEAT_SEEDS, 16, 3);
        add(FARMING, Items.BEETROOT_SEEDS, 16, 3);
        add(FARMING, Items.CARROT, 16, 4);
        add(FARMING, Items.POTATO, 16, 4);
        add(FARMING, Items.PUMPKIN_SEEDS, 8, 5);
        add(FARMING, Items.MELON_SEEDS, 8, 5);
        add(FARMING, Items.SUGAR_CANE, 16, 6);
        add(FARMING, Items.COCOA_BEANS, 16, 7);
        add(FARMING, Items.NETHER_WART, 16, 14);
        add(FARMING, Items.OAK_SAPLING, 8, 5);
        add(FARMING, Items.BONE_MEAL, 32, 9);
        add(FARMING, Items.HONEYCOMB, 8, 16);
        add(FARMING, Items.SWEET_BERRIES, 16, 5);
        add(FARMING, Items.BAMBOO, 32, 4);

        // --- Food ------------------------------------------------------------
        add(FOOD, Items.BREAD, 16, 6);
        add(FOOD, Items.COOKED_BEEF, 12, 10);
        add(FOOD, Items.COOKED_PORKCHOP, 12, 10);
        add(FOOD, Items.COOKED_CHICKEN, 12, 8);
        add(FOOD, Items.COOKED_SALMON, 12, 9);
        add(FOOD, Items.BAKED_POTATO, 16, 6);
        add(FOOD, Items.PUMPKIN_PIE, 8, 12);
        add(FOOD, Items.CAKE, 1, 14);
        add(FOOD, Items.GOLDEN_CARROT, 8, 46);
        add(FOOD, Items.GOLDEN_APPLE, 1, 60);
        add(FOOD, Items.MILK_BUCKET, 1, 12);
        add(FOOD, Items.SUGAR, 16, 5);

        // --- Materials -------------------------------------------------------
        add(MATERIALS, Items.COAL, 32, 9);
        add(MATERIALS, Items.CHARCOAL, 32, 8);
        add(MATERIALS, Items.IRON_INGOT, 8, 18);
        add(MATERIALS, Items.COPPER_INGOT, 16, 10);
        add(MATERIALS, Items.GOLD_INGOT, 8, 26);
        add(MATERIALS, Items.REDSTONE, 32, 12);
        add(MATERIALS, Items.LAPIS_LAZULI, 32, 14);
        add(MATERIALS, Items.QUARTZ, 32, 18);
        add(MATERIALS, Items.DIAMOND, 1, 42);
        add(MATERIALS, Items.AMETHYST_SHARD, 8, 20);
        add(MATERIALS, Items.NETHERITE_SCRAP, 1, 260);
        add(MATERIALS, Items.ANCIENT_DEBRIS, 1, 300);
        add(MATERIALS, Items.STRING, 16, 6);
        add(MATERIALS, Items.LEATHER, 8, 12);
        add(MATERIALS, Items.SLIME_BALL, 8, 18);
        add(MATERIALS, Items.BLAZE_ROD, 4, 40);
        add(MATERIALS, Items.ENDER_PEARL, 4, 44);
        add(MATERIALS, Items.GUNPOWDER, 16, 16);

        // --- Utility ---------------------------------------------------------
        add(UTILITY, Items.TORCH, 32, 4);
        add(UTILITY, Items.CHEST, 4, 8);
        add(UTILITY, Items.HOPPER, 2, 26);
        add(UTILITY, Items.RAIL, 32, 22);
        add(UTILITY, Items.POWERED_RAIL, 8, 34);
        add(UTILITY, Items.MINECART, 1, 14);
        add(UTILITY, Items.REPEATER, 4, 14);
        add(UTILITY, Items.COMPARATOR, 4, 16);
        add(UTILITY, Items.PISTON, 4, 22);
        add(UTILITY, Items.OBSERVER, 4, 24);
        add(UTILITY, Items.REDSTONE_LAMP, 4, 18);
        add(UTILITY, Items.LANTERN, 8, 14);
        add(UTILITY, Items.BUCKET, 1, 10);
        add(UTILITY, Items.SHULKER_BOX, 1, 150);
        add(UTILITY, Items.ENDER_CHEST, 1, 90);
        add(UTILITY, Items.ANVIL, 1, 70);
        add(UTILITY, Items.BOOKSHELF, 4, 26);
        add(UTILITY, Items.NAME_TAG, 1, 40);
        add(UTILITY, Items.LEAD, 2, 10);

        // --- The Good Stuff: saved for, not stumbled into --------------------
        add(RARE, Items.DIAMOND_BLOCK, 1, 380);
        add(RARE, Items.NETHERITE_INGOT, 1, 1150);
        add(RARE, Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 1, 900);
        add(RARE, Items.ENCHANTED_GOLDEN_APPLE, 1, 850);
        add(RARE, Items.TOTEM_OF_UNDYING, 1, 700);
        add(RARE, Items.SHULKER_SHELL, 2, 260);
        add(RARE, Items.ELYTRA, 1, 1600);
        add(RARE, Items.NETHER_STAR, 1, 1400);
        add(RARE, Items.BEACON, 1, 1500);
        add(RARE, Items.HEART_OF_THE_SEA, 1, 480);
        add(RARE, Items.CONDUIT, 1, 900);
        add(RARE, Items.DRAGON_BREATH, 4, 220);
        add(RARE, Items.EXPERIENCE_BOTTLE, 16, 120);
        add(RARE, Items.ENCHANTING_TABLE, 1, 210);
        add(RARE, Items.TRIDENT, 1, 620);
        add(RARE, Items.SADDLE, 1, 55);
    }

    private ShopStock() {
    }

    public static List<Entry> all() {
        return STOCK;
    }

    public static List<Entry> of(Category category) {
        return STOCK.stream().filter(entry -> entry.category() == category).toList();
    }

    /** The catalogue line for an item, or null if the market doesn't deal in it. */
    public static Entry find(Item item) {
        for (Entry entry : STOCK) {
            if (entry.item() == item) {
                return entry;
            }
        }
        return null;
    }
}
