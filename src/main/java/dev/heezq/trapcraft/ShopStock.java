package dev.heezq.trapcraft;

import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the market sells, and what it's worth on a flat day.
 *
 * Everything is declared by ITEM ID rather than by class reference, and
 * resolved once the server has finished registering. That is what lets the
 * shelves carry modded goods on a 136-mod pack without this mod depending on
 * any of them: an id that nobody provides is dropped at startup and nothing
 * else notices. Remove Farmer's Delight tomorrow and the market simply stops
 * selling tomatoes.
 *
 * Prices are emeralds for the bundle in `count`, before the daily index and
 * drift, and are set against what this server actually earns -- a Fire joint
 * pays 13, a good customer visit runs to about a hundred, a fat contract to a
 * couple of hundred. So staples are pocket change, a diamond is an afternoon,
 * and the top shelf is a project you save for.
 */
public final class ShopStock {

    public record Category(String id, String title, String iconId, Formatting colour, String blurb) {
        public Item icon() {
            return resolve(iconId, Items.PAPER);
        }
    }

    public record Entry(Category category, Item item, String id, int count, int base) {
    }

    public static final Category BUILDING = new Category("building", "Building",
            "minecraft:bricks", Formatting.GOLD, "Stone, brick and glass");
    public static final Category WOOD = new Category("wood", "Timber & Nature",
            "minecraft:oak_log", Formatting.DARK_GREEN, "Logs, leaves, growing things");
    public static final Category DECOR = new Category("decor", "Decoration",
            "minecraft:cyan_terracotta", Formatting.LIGHT_PURPLE, "Colour and trim");
    public static final Category FARMING = new Category("farming", "Seeds & Crops",
            "minecraft:wheat_seeds", Formatting.GREEN, "Everything that grows");
    public static final Category FOOD = new Category("food", "Kitchen",
            "minecraft:cooked_beef", Formatting.RED, "Something to eat");
    public static final Category MATERIALS = new Category("materials", "Materials",
            "minecraft:iron_ingot", Formatting.AQUA, "Ore, ingots, drops");
    public static final Category UTILITY = new Category("utility", "Utility",
            "minecraft:redstone", Formatting.YELLOW, "Redstone and hardware");
    public static final Category RARE = new Category("rare", "The Good Stuff",
            "minecraft:nether_star", Formatting.DARK_PURPLE, "If you can afford it");

    public static final List<Category> CATEGORIES =
            List.of(BUILDING, WOOD, DECOR, FARMING, FOOD, MATERIALS, UTILITY, RARE);

    /**
     * Items the market must never trade, at any price.
     *
     * These ARE the money. An emerald block was once listed at 8e and is nine
     * emeralds in a coat: buy, uncraft, repeat. Anything convertible to
     * currency by a vanilla recipe has the same hole, and since the price
     * moves daily, "the number happens to be safe today" is not a defence.
     */
    private static final List<String> CURRENCY =
            List.of("minecraft:emerald", "minecraft:emerald_block");

    /** Declared catalogue, before the registry has been consulted. */
    private static final Map<String, Object[]> DECLARED = new LinkedHashMap<>();
    private static final List<Entry> STOCK = new ArrayList<>();
    private static boolean built = false;

    private static void add(Category category, String id, int count, int base) {
        if (CURRENCY.contains(id)) {
            throw new IllegalArgumentException("the market cannot trade its own currency: " + id);
        }
        DECLARED.put(id, new Object[]{category, count, base});
    }

    private static Item resolve(String id, Item fallback) {
        Identifier key = Identifier.tryParse(id);
        if (key == null) {
            return fallback;
        }
        return Registries.ITEM.getOptionalValue(key).orElse(fallback);
    }

    /**
     * Turn declarations into stock, dropping anything nobody provides.
     *
     * Must run AFTER registration -- a static initialiser would resolve every
     * modded id to air, because none of them exist yet when this class first
     * loads.
     */
    public static void build() {
        if (built) {
            return;
        }
        built = true;
        int missing = 0;
        for (var declaration : DECLARED.entrySet()) {
            Item item = resolve(declaration.getKey(), Items.AIR);
            if (item == Items.AIR) {
                missing++;
                continue;
            }
            Object[] spec = declaration.getValue();
            STOCK.add(new Entry((Category) spec[0], item, declaration.getKey(),
                    (Integer) spec[1], (Integer) spec[2]));
        }
        TrapCraft.LOGGER.info("market: {} lines stocked, {} skipped (mod not present)",
                STOCK.size(), missing);
    }

    static {
        building();
        timber();
        decoration();
        seeds();
        kitchen();
        materials();
        utility();
        theGoodStuff();
    }

    private static void building() {
        Category c = BUILDING;
        add(c, "minecraft:cobblestone", 64, 4);
        add(c, "minecraft:stone", 64, 6);
        add(c, "minecraft:smooth_stone", 64, 8);
        add(c, "minecraft:stone_bricks", 64, 8);
        add(c, "minecraft:mossy_stone_bricks", 32, 10);
        add(c, "minecraft:chiseled_stone_bricks", 32, 10);
        add(c, "minecraft:andesite", 64, 5);
        add(c, "minecraft:diorite", 64, 5);
        add(c, "minecraft:granite", 64, 5);
        add(c, "minecraft:calcite", 32, 8);
        add(c, "minecraft:tuff", 64, 6);
        add(c, "minecraft:deepslate", 64, 6);
        add(c, "minecraft:cobbled_deepslate", 64, 5);
        add(c, "minecraft:polished_deepslate", 32, 9);
        add(c, "minecraft:deepslate_bricks", 32, 10);
        add(c, "minecraft:blackstone", 32, 9);
        add(c, "minecraft:basalt", 32, 7);
        add(c, "minecraft:sandstone", 64, 7);
        add(c, "minecraft:red_sandstone", 64, 7);
        add(c, "minecraft:sand", 64, 5);
        add(c, "minecraft:red_sand", 64, 6);
        add(c, "minecraft:gravel", 64, 5);
        add(c, "minecraft:dirt", 64, 3);
        add(c, "minecraft:coarse_dirt", 64, 4);
        add(c, "minecraft:rooted_dirt", 32, 6);
        add(c, "minecraft:clay_ball", 32, 8);
        add(c, "minecraft:bricks", 32, 16);
        add(c, "minecraft:glass", 64, 12);
        add(c, "minecraft:glass_pane", 64, 8);
        add(c, "minecraft:tinted_glass", 16, 18);
        add(c, "minecraft:quartz_block", 16, 22);
        add(c, "minecraft:smooth_quartz", 16, 22);
        add(c, "minecraft:prismarine", 16, 20);
        add(c, "minecraft:sea_lantern", 8, 30);
        add(c, "minecraft:obsidian", 8, 34);
        add(c, "minecraft:crying_obsidian", 4, 44);
        add(c, "minecraft:netherrack", 64, 4);
        add(c, "minecraft:nether_bricks", 32, 14);
        add(c, "minecraft:end_stone", 32, 18);
        add(c, "minecraft:purpur_block", 16, 20);
        add(c, "minecraft:scaffolding", 32, 9);
        add(c, "minecraft:ladder", 16, 6);
        add(c, "minecraft:iron_bars", 16, 12);
        add(c, "minecraft:amethyst_block", 8, 26);
        add(c, "minecraft:copper_block", 4, 34);
        add(c, "minecraft:mud_bricks", 32, 9);
        add(c, "minecraft:packed_ice", 16, 16);
        add(c, "minecraft:snow_block", 32, 5);
    }

    private static void timber() {
        Category c = WOOD;
        for (String wood : new String[]{"oak", "spruce", "birch", "jungle", "acacia",
                "dark_oak", "mangrove", "cherry"}) {
            add(c, "minecraft:" + wood + "_log", 32, 8);
            add(c, "minecraft:" + wood + "_planks", 64, 5);
            add(c, "minecraft:" + wood + "_sapling", 8, 5);
        }
        add(c, "minecraft:crimson_stem", 32, 12);
        add(c, "minecraft:warped_stem", 32, 12);
        add(c, "minecraft:bamboo", 32, 4);
        add(c, "minecraft:moss_block", 16, 10);
        add(c, "minecraft:vine", 16, 6);
        add(c, "minecraft:lily_pad", 16, 8);
        add(c, "minecraft:oak_leaves", 64, 4);
        add(c, "minecraft:podzol", 32, 8);
        add(c, "minecraft:mycelium", 16, 14);
        add(c, "minecraft:brown_mushroom", 16, 6);
        add(c, "minecraft:red_mushroom", 16, 6);
        add(c, "minecraft:kelp", 16, 5);
        add(c, "minecraft:sea_pickle", 8, 12);
        add(c, "minecraft:cactus", 16, 6);
        add(c, "minecraft:dead_bush", 8, 4);
    }

    private static void decoration() {
        Category c = DECOR;
        for (String dye : new String[]{"white", "orange", "magenta", "light_blue", "yellow",
                "lime", "pink", "gray", "light_gray", "cyan", "purple", "blue", "brown",
                "green", "red", "black"}) {
            add(c, "minecraft:" + dye + "_dye", 16, 6);
            add(c, "minecraft:" + dye + "_wool", 16, 8);
            add(c, "minecraft:" + dye + "_concrete_powder", 32, 10);
            add(c, "minecraft:" + dye + "_terracotta", 32, 11);
        }
        add(c, "minecraft:terracotta", 32, 9);
        add(c, "minecraft:item_frame", 4, 10);
        add(c, "minecraft:painting", 4, 10);
        add(c, "minecraft:flower_pot", 4, 6);
        add(c, "minecraft:armor_stand", 2, 12);
        add(c, "minecraft:candle", 8, 8);
        add(c, "minecraft:glow_ink_sac", 4, 20);
        add(c, "minecraft:ink_sac", 8, 8);
        add(c, "minecraft:book", 8, 12);
        add(c, "minecraft:paper", 32, 6);
    }

    private static void seeds() {
        Category c = FARMING;
        add(c, "minecraft:wheat_seeds", 16, 3);
        add(c, "minecraft:beetroot_seeds", 16, 3);
        add(c, "minecraft:melon_seeds", 8, 5);
        add(c, "minecraft:pumpkin_seeds", 8, 5);
        add(c, "minecraft:torchflower_seeds", 1, 40);
        add(c, "minecraft:pitcher_pod", 1, 40);
        add(c, "minecraft:carrot", 16, 4);
        add(c, "minecraft:potato", 16, 4);
        add(c, "minecraft:wheat", 32, 6);
        add(c, "minecraft:beetroot", 16, 5);
        add(c, "minecraft:sugar_cane", 16, 6);
        add(c, "minecraft:cocoa_beans", 16, 7);
        add(c, "minecraft:nether_wart", 16, 14);
        add(c, "minecraft:sweet_berries", 16, 5);
        add(c, "minecraft:glow_berries", 16, 9);
        add(c, "minecraft:bone_meal", 32, 9);
        add(c, "minecraft:honeycomb", 8, 16);
        add(c, "minecraft:hay_block", 8, 10);
        add(c, "minecraft:egg", 16, 5);
        add(c, "minecraft:wheat_seeds", 16, 3);

        // --- modded crops. Absent ids drop out at startup. ---
        add(c, "farmersdelight:tomato_seeds", 8, 8);
        add(c, "farmersdelight:cabbage_seeds", 8, 8);
        add(c, "farmersdelight:rice", 16, 8);
        add(c, "farmersdelight:tomato", 12, 8);
        add(c, "farmersdelight:cabbage", 8, 8);
        add(c, "farmersdelight:onion", 12, 7);
        add(c, "farmersdelight:straw", 16, 5);
        add(c, "culturaldelights:cucumber_seeds", 8, 8);
        add(c, "culturaldelights:eggplant_seeds", 8, 8);
        add(c, "culturaldelights:corn_kernels", 8, 8);
        add(c, "culturaldelights:cucumber", 12, 7);
        add(c, "culturaldelights:eggplant", 12, 7);
        add(c, "culturaldelights:corn_cob", 12, 8);
        add(c, "culturaldelights:avocado", 8, 12);
        add(c, "culturaldelights:avocado_sapling", 4, 14);
        add(c, "rusticdelight:bell_pepper_seeds", 8, 8);
        add(c, "rusticdelight:cotton_seeds", 8, 9);
        add(c, "rusticdelight:coffee_beans", 12, 12);
        add(c, "rusticdelight:bell_pepper_red", 8, 9);
        add(c, "rusticdelight:bell_pepper_green", 8, 9);
        add(c, "rusticdelight:bell_pepper_yellow", 8, 9);
        add(c, "rusticdelight:cotton_boll", 12, 9);
        add(c, "alcocraftplus:hop_seeds", 8, 10);
    }

    private static void kitchen() {
        Category c = FOOD;
        add(c, "minecraft:bread", 16, 6);
        add(c, "minecraft:cooked_beef", 12, 10);
        add(c, "minecraft:cooked_porkchop", 12, 10);
        add(c, "minecraft:cooked_chicken", 12, 8);
        add(c, "minecraft:cooked_mutton", 12, 9);
        add(c, "minecraft:cooked_rabbit", 12, 9);
        add(c, "minecraft:cooked_salmon", 12, 9);
        add(c, "minecraft:cooked_cod", 12, 8);
        add(c, "minecraft:baked_potato", 16, 6);
        add(c, "minecraft:pumpkin_pie", 8, 12);
        add(c, "minecraft:cake", 1, 14);
        add(c, "minecraft:cookie", 16, 6);
        add(c, "minecraft:apple", 16, 7);
        add(c, "minecraft:melon_slice", 16, 5);
        add(c, "minecraft:dried_kelp", 16, 5);
        add(c, "minecraft:mushroom_stew", 4, 8);
        add(c, "minecraft:rabbit_stew", 4, 14);
        add(c, "minecraft:beetroot_soup", 4, 9);
        add(c, "minecraft:suspicious_stew", 2, 18);
        add(c, "minecraft:golden_carrot", 8, 46);
        add(c, "minecraft:golden_apple", 1, 60);
        add(c, "minecraft:milk_bucket", 1, 12);
        add(c, "minecraft:honey_bottle", 4, 14);
        add(c, "minecraft:sugar", 16, 5);

        add(c, "farmersdelight:hamburger", 4, 18);
        add(c, "farmersdelight:chicken_sandwich", 4, 16);
        add(c, "farmersdelight:bacon_sandwich", 4, 17);
        add(c, "farmersdelight:egg_sandwich", 4, 14);
        add(c, "farmersdelight:beef_stew", 4, 20);
        add(c, "farmersdelight:chicken_soup", 4, 18);
        add(c, "farmersdelight:vegetable_soup", 4, 16);
        add(c, "farmersdelight:fish_stew", 4, 18);
        add(c, "farmersdelight:pumpkin_soup", 4, 16);
        add(c, "farmersdelight:noodle_soup", 4, 19);
        add(c, "farmersdelight:fried_rice", 4, 17);
        add(c, "farmersdelight:mixed_salad", 4, 15);
        add(c, "farmersdelight:roast_chicken", 2, 26);
        add(c, "farmersdelight:honey_glazed_ham", 2, 28);
        add(c, "farmersdelight:shepherds_pie", 2, 26);
        add(c, "farmersdelight:steak_and_potatoes", 2, 27);
        add(c, "farmersdelight:stuffed_pumpkin", 2, 24);
        add(c, "farmersdelight:apple_pie_slice", 8, 12);
        add(c, "farmersdelight:sweet_berry_cheesecake_slice", 8, 13);
        add(c, "farmersdelight:chocolate_pie_slice", 8, 13);
        add(c, "farmersdelight:fruit_salad", 4, 14);
        add(c, "farmersdelight:melon_popsicle", 8, 10);
        add(c, "farmersdelight:hot_cocoa", 4, 12);
        add(c, "farmersdelight:apple_cider", 4, 13);
        add(c, "farmersdelight:melon_juice", 4, 11);
        add(c, "farmersdelight:cooked_bacon", 12, 11);
        add(c, "farmersdelight:cooked_rice", 8, 10);
        add(c, "farmersdelight:pie_crust", 8, 9);
        add(c, "farmersdelight:wheat_dough", 8, 7);

        add(c, "culturaldelights:beef_burrito", 4, 20);
        add(c, "culturaldelights:chicken_taco", 4, 17);
        add(c, "culturaldelights:fish_taco", 4, 17);
        add(c, "culturaldelights:eggplant_burger", 4, 18);
        add(c, "culturaldelights:avocado_toast", 4, 15);
        add(c, "culturaldelights:hearty_salad", 4, 16);
        add(c, "culturaldelights:spicy_curry", 4, 21);
        add(c, "culturaldelights:elote", 4, 13);
        add(c, "culturaldelights:popcorn", 8, 9);
        add(c, "culturaldelights:tortilla", 8, 8);
        add(c, "culturaldelights:tortilla_chips", 8, 9);
        add(c, "culturaldelights:rice_ball", 8, 11);
        add(c, "culturaldelights:cooked_calamari", 8, 14);

        add(c, "rusticdelight:pancake", 8, 12);
        add(c, "rusticdelight:honey_pancake", 8, 14);
        add(c, "rusticdelight:chocolate_pancake", 8, 14);
        add(c, "rusticdelight:coffee", 4, 12);
        add(c, "rusticdelight:milk_coffee", 4, 13);
        add(c, "rusticdelight:honey_coffee", 4, 14);
        add(c, "rusticdelight:fried_chicken", 4, 19);
        add(c, "rusticdelight:potato_salad", 4, 14);
        add(c, "rusticdelight:spring_rolls", 4, 16);
        add(c, "rusticdelight:fried_dumplings", 4, 16);
        add(c, "rusticdelight:bell_pepper_soup", 4, 17);
        add(c, "rusticdelight:sweet_salad", 4, 15);
        add(c, "rusticdelight:cooking_oil", 4, 10);
        add(c, "rusticdelight:syrup", 4, 11);
    }

    private static void materials() {
        Category c = MATERIALS;
        add(c, "minecraft:coal", 32, 9);
        add(c, "minecraft:charcoal", 32, 8);
        add(c, "minecraft:raw_iron", 16, 16);
        add(c, "minecraft:iron_ingot", 8, 18);
        add(c, "minecraft:iron_nugget", 32, 6);
        add(c, "minecraft:raw_copper", 32, 10);
        add(c, "minecraft:copper_ingot", 16, 10);
        add(c, "minecraft:raw_gold", 8, 24);
        add(c, "minecraft:gold_ingot", 8, 26);
        add(c, "minecraft:gold_nugget", 32, 5);
        add(c, "minecraft:redstone", 32, 12);
        add(c, "minecraft:lapis_lazuli", 32, 14);
        add(c, "minecraft:quartz", 32, 18);
        add(c, "minecraft:diamond", 1, 42);
        add(c, "minecraft:amethyst_shard", 8, 20);
        add(c, "minecraft:netherite_scrap", 1, 260);
        add(c, "minecraft:ancient_debris", 1, 300);
        add(c, "minecraft:flint", 16, 5);
        add(c, "minecraft:string", 16, 6);
        add(c, "minecraft:leather", 8, 12);
        add(c, "minecraft:rabbit_hide", 8, 9);
        add(c, "minecraft:feather", 16, 6);
        add(c, "minecraft:bone", 16, 6);
        add(c, "minecraft:slime_ball", 8, 18);
        add(c, "minecraft:honeycomb", 8, 16);
        add(c, "minecraft:blaze_rod", 4, 40);
        add(c, "minecraft:ender_pearl", 4, 44);
        add(c, "minecraft:ghast_tear", 2, 60);
        add(c, "minecraft:magma_cream", 8, 22);
        add(c, "minecraft:gunpowder", 16, 16);
        add(c, "minecraft:spider_eye", 16, 8);
        add(c, "minecraft:rotten_flesh", 32, 4);
        add(c, "minecraft:phantom_membrane", 4, 34);
        add(c, "minecraft:prismarine_shard", 16, 14);
        add(c, "minecraft:prismarine_crystals", 8, 18);
        add(c, "minecraft:nautilus_shell", 2, 55);
        add(c, "minecraft:scute", 2, 45);
        add(c, "minecraft:echo_shard", 2, 90);
        add(c, "minecraft:glowstone_dust", 16, 12);
        add(c, "minecraft:nether_brick", 32, 10);
        add(c, "minecraft:blaze_powder", 8, 24);
        add(c, "minecraft:fermented_spider_eye", 4, 14);
        add(c, "minecraft:brown_mushroom_block", 8, 8);
    }

    private static void utility() {
        Category c = UTILITY;
        add(c, "minecraft:torch", 32, 4);
        add(c, "minecraft:lantern", 8, 14);
        add(c, "minecraft:soul_lantern", 8, 18);
        add(c, "minecraft:chest", 4, 8);
        add(c, "minecraft:barrel", 4, 9);
        add(c, "minecraft:furnace", 2, 8);
        add(c, "minecraft:blast_furnace", 1, 16);
        add(c, "minecraft:smoker", 1, 14);
        add(c, "minecraft:crafting_table", 2, 6);
        add(c, "minecraft:hopper", 2, 26);
        add(c, "minecraft:dropper", 4, 14);
        add(c, "minecraft:dispenser", 4, 18);
        add(c, "minecraft:rail", 32, 22);
        add(c, "minecraft:powered_rail", 8, 34);
        add(c, "minecraft:detector_rail", 8, 20);
        add(c, "minecraft:minecart", 1, 14);
        add(c, "minecraft:repeater", 4, 14);
        add(c, "minecraft:comparator", 4, 16);
        add(c, "minecraft:piston", 4, 22);
        add(c, "minecraft:sticky_piston", 4, 28);
        add(c, "minecraft:observer", 4, 24);
        add(c, "minecraft:redstone_lamp", 4, 18);
        add(c, "minecraft:redstone_torch", 8, 8);
        add(c, "minecraft:lever", 8, 5);
        add(c, "minecraft:tripwire_hook", 8, 8);
        add(c, "minecraft:target", 4, 12);
        add(c, "minecraft:note_block", 4, 10);
        add(c, "minecraft:jukebox", 1, 24);
        add(c, "minecraft:bucket", 1, 10);
        add(c, "minecraft:water_bucket", 1, 12);
        add(c, "minecraft:lava_bucket", 1, 26);
        add(c, "minecraft:shears", 1, 12);
        add(c, "minecraft:flint_and_steel", 1, 12);
        add(c, "minecraft:compass", 1, 20);
        add(c, "minecraft:clock", 1, 22);
        add(c, "minecraft:spyglass", 1, 30);
        add(c, "minecraft:name_tag", 1, 40);
        add(c, "minecraft:lead", 2, 10);
        add(c, "minecraft:saddle", 1, 55);
        add(c, "minecraft:bookshelf", 4, 26);
        add(c, "minecraft:anvil", 1, 70);
        add(c, "minecraft:grindstone", 1, 18);
        add(c, "minecraft:smithing_table", 1, 18);
        add(c, "minecraft:cartography_table", 1, 16);
        add(c, "minecraft:loom", 1, 14);
        add(c, "minecraft:composter", 1, 10);
        add(c, "minecraft:cauldron", 1, 18);
        add(c, "minecraft:brewing_stand", 1, 40);
        add(c, "minecraft:ender_chest", 1, 90);
        add(c, "minecraft:shulker_box", 1, 150);
        add(c, "minecraft:tnt", 4, 30);
        add(c, "minecraft:bow", 1, 20);
        add(c, "minecraft:arrow", 32, 12);
        add(c, "minecraft:fishing_rod", 1, 16);
        add(c, "minecraft:boat", 1, 8);
    }

    private static void theGoodStuff() {
        Category c = RARE;
        add(c, "minecraft:diamond_block", 1, 380);
        add(c, "minecraft:netherite_ingot", 1, 1150);
        add(c, "minecraft:netherite_upgrade_smithing_template", 1, 900);
        add(c, "minecraft:enchanted_golden_apple", 1, 850);
        add(c, "minecraft:totem_of_undying", 1, 700);
        add(c, "minecraft:shulker_shell", 2, 260);
        add(c, "minecraft:elytra", 1, 1600);
        add(c, "minecraft:nether_star", 1, 1400);
        add(c, "minecraft:beacon", 1, 1500);
        add(c, "minecraft:heart_of_the_sea", 1, 480);
        add(c, "minecraft:conduit", 1, 900);
        add(c, "minecraft:dragon_breath", 4, 220);
        add(c, "minecraft:experience_bottle", 16, 120);
        add(c, "minecraft:enchanting_table", 1, 210);
        add(c, "minecraft:trident", 1, 620);
        add(c, "minecraft:golden_apple", 8, 400);
        add(c, "minecraft:diamond_pickaxe", 1, 190);
        add(c, "minecraft:diamond_sword", 1, 160);
        add(c, "minecraft:diamond_chestplate", 1, 340);
        add(c, "minecraft:diamond_helmet", 1, 220);
        add(c, "minecraft:diamond_leggings", 1, 300);
        add(c, "minecraft:diamond_boots", 1, 200);
        add(c, "minecraft:sculk_catalyst", 1, 260);
        add(c, "minecraft:sculk_shrieker", 1, 200);
        add(c, "minecraft:recovery_compass", 1, 520);
        add(c, "minecraft:music_disc_pigstep", 1, 480);
        add(c, "minecraft:wither_skeleton_skull", 1, 420);
        add(c, "minecraft:dragon_head", 1, 1100);
        add(c, "minecraft:end_crystal", 2, 260);
    }

    private ShopStock() {
    }

    public static List<Entry> all() {
        return STOCK;
    }

    public static List<Entry> of(Category category) {
        return STOCK.stream().filter(entry -> entry.category() == category).toList();
    }

    public static Entry find(Item item) {
        for (Entry entry : STOCK) {
            if (entry.item() == item) {
                return entry;
            }
        }
        return null;
    }
}
