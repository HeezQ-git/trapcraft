package dev.heezq.trapcraft;

import net.minecraft.block.Block;
import net.minecraft.block.CropBlock;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.item.BlockItem;
import net.minecraft.block.FlowerBlock;
import net.minecraft.block.FlowerbedBlock;
import net.minecraft.block.LeavesBlock;
import net.minecraft.block.MushroomPlantBlock;
import net.minecraft.block.SaplingBlock;
import net.minecraft.block.TallPlantBlock;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Objects;
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

    /**
     * One line of the catalogue.
     *
     * The prototype is the stack itself, built once at startup, because a line
     * is not always just an item and a number: an enchanted book carries the
     * enchantment in a component, and "Sharpness V" and "Sharpness I" are the
     * same item at different prices. Buying copies the prototype, selling
     * compares against it, and the shelf shows it -- one source of truth, so
     * the shelf can't advertise something the till won't accept.
     */
    public record Entry(Category category, Item item, String id, int count, int base,
                        ItemStack prototype, String label) {

        public ItemStack stack() {
            return prototype.copy();
        }

        /** Would this stack in a player's bag count towards selling this line? */
        public boolean matches(ItemStack other) {
            if (!other.isOf(item)) {
                return false;
            }
            var wanted = prototype.get(DataComponentTypes.STORED_ENCHANTMENTS);
            if (wanted == null) {
                return true;
            }
            return Objects.equals(other.get(DataComponentTypes.STORED_ENCHANTMENTS), wanted);
        }
    }

    public static final Category BUILDING = new Category("building", "Building",
            "minecraft:bricks", Formatting.GOLD, "Stone, brick and glass");
    public static final Category WOOD = new Category("wood", "Timber & Nature",
            "minecraft:oak_log", Formatting.DARK_GREEN, "Logs, leaves, growing things");
    public static final Category DECOR = new Category("decor", "Decoration",
            "minecraft:cyan_terracotta", Formatting.LIGHT_PURPLE, "Colour and trim");
    public static final Category GARDEN = new Category("garden", "The Garden",
            "minecraft:peony", Formatting.LIGHT_PURPLE, "Flowers, pots, lanterns");
    public static final Category FARMING = new Category("farming", "Seeds & Crops",
            "minecraft:wheat_seeds", Formatting.GREEN, "Everything that grows");
    public static final Category FOOD = new Category("food", "Kitchen",
            "minecraft:cooked_beef", Formatting.RED, "Something to eat");
    public static final Category MATERIALS = new Category("materials", "Materials",
            "minecraft:iron_ingot", Formatting.AQUA, "Ore, ingots, drops");
    public static final Category UTILITY = new Category("utility", "Utility",
            "minecraft:redstone", Formatting.YELLOW, "Redstone and hardware");
    public static final Category ENCHANTS = new Category("enchants", "Enchantments",
            "minecraft:enchanted_book", Formatting.LIGHT_PURPLE, "Books, bought not ground for");
    public static final Category RARE = new Category("rare", "The Good Stuff",
            "minecraft:nether_star", Formatting.DARK_PURPLE, "If you can afford it");

    public static final List<Category> CATEGORIES =
            List.of(BUILDING, WOOD, DECOR, GARDEN, FARMING, FOOD,
                    MATERIALS, UTILITY, ENCHANTS, RARE);

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
        DECLARED.put(id, new Object[]{category, count, base, null, 0});
    }

    /** An enchanted book line. Keyed separately so levels don't collide. */
    private static void book(String enchantment, int level, int base) {
        DECLARED.put("minecraft:enchanted_book#" + enchantment + "#" + level,
                new Object[]{ENCHANTS, 1, base, enchantment, level});
    }

    private static Item resolve(String id, Item fallback) {
        Identifier key = Identifier.tryParse(id.contains("#") ? id.substring(0, id.indexOf('#')) : id);
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
    public static void build(MinecraftServer server) {
        if (built) {
            return;
        }
        built = true;
        // Enchantments are a datapack registry, so they only exist once a world
        // is loaded -- there is no static Enchantments.SHARPNESS to reference.
        Registry<Enchantment> spells = server.getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT);
        int missing = 0;
        for (var declaration : DECLARED.entrySet()) {
            Item item = resolve(declaration.getKey(), Items.AIR);
            if (item == Items.AIR) {
                missing++;
                continue;
            }
            Object[] spec = declaration.getValue();
            int count = (Integer) spec[1];
            String enchantment = (String) spec[3];
            ItemStack prototype = new ItemStack(item, count);
            String label = item.getName().getString();

            if (enchantment != null) {
                var found = spells.getEntry(Identifier.of(enchantment));
                if (found.isEmpty()) {
                    missing++;
                    continue;
                }
                int level = (Integer) spec[4];
                var written = new ItemEnchantmentsComponent.Builder(ItemEnchantmentsComponent.DEFAULT);
                written.add(found.get(), level);
                prototype.set(DataComponentTypes.STORED_ENCHANTMENTS, written.build());
                label = Enchantment.getName(found.get(), level).getString();
            }

            STOCK.add(new Entry((Category) spec[0], item, declaration.getKey(),
                    count, (Integer) spec[2], prototype, label));
        }
        int listed = STOCK.size();
        stockTheKitchen();
        stockTheGarden();
        TrapCraft.LOGGER.info(
                "market: {} lines stocked ({} listed, {} found in the registry), {} skipped",
                STOCK.size(), listed, STOCK.size() - listed, missing);
    }

    /**
     * Items the auto-stocker must never put on a shelf.
     *
     * Mostly things that are food by the game's reckoning but would be a joke
     * or a trap on a shelf. Currency is already refused by add().
     */
    private static final List<String> NEVER_STOCK = List.of(
            "minecraft:poisonous_potato", "minecraft:pufferfish", "minecraft:spider_eye",
            "minecraft:rotten_flesh", "minecraft:chicken", "minecraft:chorus_fruit",
            "minecraft:suspicious_stew", "minecraft:enchanted_golden_apple",
            "minecraft:golden_apple", "minecraft:cake");

    /**
     * Put every food and seed in the game on the shelves.
     *
     * Sixteen food mods are installed and between them they add hundreds of
     * ingredients, dishes and crops. Listing those by hand would be a
     * thousand lines that goes stale the moment a mod updates, and every id I
     * typed wrong would be silently dropped as "mod not present" -- so this
     * asks the registry instead. Anything with a food component is a food and
     * anything the villagers will plant is a seed, whoever added it.
     *
     * Hand-written lines always win: this only fills gaps, so the entries that
     * have been tuned against what this server actually earns keep their
     * prices and everything else gets a reasonable one.
     */
    private static void stockTheKitchen() {
        int foods = 0;
        int seeds = 0;
        for (Item item : Registries.ITEM) {
            String id = Registries.ITEM.getId(item).toString();
            if (DECLARED.containsKey(id) || NEVER_STOCK.contains(id) || CURRENCY.contains(id)) {
                continue;
            }
            FoodComponent food = item.getComponents().get(DataComponentTypes.FOOD);
            if (food != null) {
                int count = food.nutrition() >= 8 ? 1 : food.nutrition() >= 5 ? 2 : 4;
                int base = Math.max(3, Math.round(count
                        * (food.nutrition() * 0.8f + food.saturation() * 0.5f + 1.0f)));
                STOCK.add(new Entry(FOOD, item, id, count, base,
                        new ItemStack(item, count), item.getName().getString()));
                foods++;
            } else if (plantable(item)) {
                STOCK.add(new Entry(FARMING, item, id, 8, 14,
                        new ItemStack(item, 8), item.getName().getString()));
                seeds++;
            }
        }
        TrapCraft.LOGGER.info("market: found {} foods and {} seeds in the registry", foods, seeds);
    }

    /**
     * Put every flower, sapling and leaf in the game on the garden shelf.
     *
     * Same reasoning as the kitchen. There are a hundred and thirty-six mods
     * installed and between them they add hundreds of flowers nobody is going
     * to list by hand -- and every id typed wrong would be silently dropped as
     * "mod not present", which is the worst kind of missing.
     *
     * Asked of the registry two ways, because neither is enough on its own: a
     * tag catches modded flowers that were polite enough to join
     * #minecraft:flowers, and the block class catches the ones that weren't.
     * Between them almost nothing gets left outside.
     */
    private static void stockTheGarden() {
        int found = 0;
        for (Item item : Registries.ITEM) {
            String id = Registries.ITEM.getId(item).toString();
            if (DECLARED.containsKey(id) || NEVER_STOCK.contains(id) || CURRENCY.contains(id)) {
                continue;
            }
            int[] priced = gardenPrice(item);
            if (priced == null) {
                continue;
            }
            STOCK.add(new Entry(GARDEN, item, id, priced[0], priced[1],
                    new ItemStack(item, priced[0]), item.getName().getString()));
            found++;
        }
        TrapCraft.LOGGER.info("market: found {} garden lines in the registry", found);
    }

    /**
     * {bundle size, price} for anything you would plant to look at, or null.
     *
     * Nothing is priced below 5. A line cheap enough that the counter refuses
     * to buy it back once the index dips is a line that reads as broken --
     * see the sell floor in check_stock.py, which fails the deploy over it.
     */
    private static int[] gardenPrice(Item item) {
        ItemStack stack = item.getDefaultStack();
        Block block = Block.getBlockFromItem(item);

        if (stack.isIn(ItemTags.LEAVES) || block instanceof LeavesBlock) {
            return new int[]{16, 6};
        }
        if (stack.isIn(ItemTags.SAPLINGS) || block instanceof SaplingBlock) {
            return new int[]{4, 12};
        }
        if (stack.isIn(ItemTags.SMALL_FLOWERS) || block instanceof FlowerBlock
                || block instanceof FlowerbedBlock) {
            return new int[]{8, 7};
        }
        if (stack.isIn(ItemTags.FLOWERS) || block instanceof TallPlantBlock) {
            return new int[]{4, 9};
        }
        if (block instanceof MushroomPlantBlock) {
            return new int[]{8, 7};
        }
        return null;
    }

    /**
     * The hard landscaping, which no tag will find for you.
     *
     * Everything a garden needs that isn't alive: the pots to put the flowers
     * in, the light to see them by, and the paths and stone to lay it all out
     * on.
     */
    private static void garden() {
        // Anything already on another shelf is deliberately absent: DECLARED
        // is a map, so a second line silently wins and the price somebody
        // tuned is quietly replaced. check_stock.py fails the deploy over it,
        // which is how these twenty-nine were found.
        Category c = GARDEN;
        // Light
        add(c, "minecraft:soul_torch", 16, 7);
        add(c, "minecraft:end_rod", 8, 20);
        for (String dye : new String[]{"white", "orange", "magenta", "light_blue", "yellow",
                "lime", "pink", "gray", "light_gray", "cyan", "purple", "blue", "brown",
                "green", "red", "black"}) {
            add(c, "minecraft:" + dye + "_candle", 8, 9);
        }
        // Green cover
        add(c, "minecraft:moss_carpet", 16, 8);
        add(c, "minecraft:pale_moss_carpet", 16, 10);
        add(c, "minecraft:hanging_roots", 8, 9);
        add(c, "minecraft:big_dripleaf", 4, 14);
        add(c, "minecraft:small_dripleaf", 8, 10);
        add(c, "minecraft:azalea", 4, 16);
        add(c, "minecraft:flowering_azalea", 4, 20);
        add(c, "minecraft:grass_block", 16, 8);
        add(c, "minecraft:dirt_path", 16, 6);
        add(c, "minecraft:farmland", 16, 6);
        // Water and stone
        add(c, "minecraft:seagrass", 8, 8);
        add(c, "minecraft:pointed_dripstone", 8, 10);
        add(c, "minecraft:mossy_cobblestone", 16, 10);
        add(c, "minecraft:cobblestone_wall", 16, 9);
        add(c, "minecraft:mossy_cobblestone_wall", 16, 10);
        add(c, "minecraft:stone_brick_wall", 16, 9);
        add(c, "minecraft:brick_wall", 16, 10);
        // Furniture
        add(c, "minecraft:oak_fence", 16, 8);
        add(c, "minecraft:spruce_fence", 16, 8);
        add(c, "minecraft:birch_fence", 16, 8);
        add(c, "minecraft:dark_oak_fence", 16, 9);
        add(c, "minecraft:bamboo_fence", 16, 9);
        add(c, "minecraft:oak_trapdoor", 8, 10);
    }

    /** Anything a villager would plant, or that places a crop. */
    private static boolean plantable(Item item) {
        if (item.getDefaultStack().isIn(ItemTags.VILLAGER_PLANTABLE_SEEDS)) {
            return true;
        }
        return item instanceof BlockItem placed && placed.getBlock() instanceof CropBlock;
    }

    static {
        building();
        timber();
        decoration();
        garden();
        seeds();
        kitchen();
        materials();
        utility();
        enchantments();
        theGoodStuff();
    }

    private static void building() {
        Category c = BUILDING;
        add(c, "minecraft:copper_grate", 32, 16);
        add(c, "minecraft:chiseled_copper", 32, 18);
        add(c, "minecraft:copper_bulb", 8, 26);
        add(c, "minecraft:exposed_copper", 32, 14);
        add(c, "minecraft:weathered_copper", 32, 14);
        add(c, "minecraft:oxidized_copper", 32, 14);
        add(c, "minecraft:waxed_copper_block", 8, 28);
        add(c, "minecraft:cut_copper", 32, 16);
        add(c, "minecraft:magma_block", 16, 20);
        add(c, "minecraft:soul_sand", 32, 10);
        add(c, "minecraft:soul_soil", 32, 10);
        add(c, "minecraft:polished_blackstone_brick_slab", 32, 8);
        add(c, "minecraft:white_terracotta", 32, 11);
        add(c, "minecraft:orange_terracotta", 32, 11);
        add(c, "minecraft:light_blue_terracotta", 32, 11);
        add(c, "minecraft:green_terracotta", 32, 11);
        add(c, "minecraft:brown_terracotta", 32, 11);
        add(c, "minecraft:blue_ice", 8, 40);
        add(c, "minecraft:ice", 16, 12);
        add(c, "minecraft:honeycomb_block", 8, 22);
        add(c, "minecraft:mud", 64, 4);
        add(c, "minecraft:packed_mud", 64, 5);
        add(c, "minecraft:resin_bricks", 32, 10);
        add(c, "minecraft:smooth_sandstone", 32, 8);
        add(c, "minecraft:cut_sandstone", 32, 8);
        add(c, "minecraft:smooth_red_sandstone", 32, 8);
        add(c, "minecraft:chiseled_sandstone", 32, 9);
        add(c, "minecraft:chiseled_red_sandstone", 32, 9);
        add(c, "minecraft:smooth_basalt", 32, 8);
        add(c, "minecraft:polished_basalt", 32, 8);
        add(c, "minecraft:polished_blackstone", 32, 10);
        add(c, "minecraft:polished_blackstone_bricks", 32, 12);
        add(c, "minecraft:gilded_blackstone", 8, 45);
        add(c, "minecraft:cracked_stone_bricks", 32, 9);
        add(c, "minecraft:cracked_deepslate_bricks", 32, 11);
        add(c, "minecraft:chiseled_deepslate", 32, 11);
        add(c, "minecraft:deepslate_tiles", 32, 12);
        add(c, "minecraft:cracked_deepslate_tiles", 32, 12);
        add(c, "minecraft:chiseled_tuff", 32, 10);
        add(c, "minecraft:tuff_bricks", 32, 9);
        add(c, "minecraft:polished_tuff", 32, 9);
        add(c, "minecraft:end_stone_bricks", 32, 18);
        add(c, "minecraft:purpur_pillar", 16, 22);
        add(c, "minecraft:red_nether_bricks", 32, 16);
        add(c, "minecraft:chiseled_nether_bricks", 16, 15);
        add(c, "minecraft:chiseled_quartz_block", 32, 24);
        add(c, "minecraft:quartz_bricks", 32, 24);
        add(c, "minecraft:prismarine_bricks", 32, 24);
        add(c, "minecraft:dark_prismarine", 32, 26);
        add(c, "minecraft:glowstone", 16, 28);
        add(c, "minecraft:shroomlight", 8, 30);
        add(c, "minecraft:ochre_froglight", 4, 45);
        add(c, "minecraft:verdant_froglight", 4, 45);
        add(c, "minecraft:bone_block", 16, 18);
        add(c, "minecraft:dripstone_block", 32, 8);
        add(c, "minecraft:pale_moss_block", 16, 14);
        add(c, "minecraft:pearlescent_froglight", 4, 45);
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
        add(c, "minecraft:white_wool", 16, 10);
        add(c, "minecraft:light_gray_wool", 16, 10);
        add(c, "minecraft:gray_wool", 16, 10);
        add(c, "minecraft:brown_wool", 16, 10);
        add(c, "minecraft:pink_wool", 16, 10);
        add(c, "minecraft:magenta_wool", 16, 10);
        add(c, "minecraft:purple_wool", 16, 10);
        add(c, "minecraft:blue_wool", 16, 10);
        add(c, "minecraft:light_blue_wool", 16, 10);
        add(c, "minecraft:cyan_wool", 16, 10);
        add(c, "minecraft:lime_wool", 16, 10);
        add(c, "minecraft:yellow_wool", 16, 10);
        add(c, "minecraft:orange_wool", 16, 10);
        add(c, "minecraft:white_concrete", 32, 12);
        add(c, "minecraft:black_concrete", 32, 12);
        add(c, "minecraft:red_concrete", 32, 12);
        add(c, "minecraft:blue_concrete", 32, 12);
        add(c, "minecraft:yellow_concrete", 32, 12);
        add(c, "minecraft:green_concrete", 32, 12);
        add(c, "minecraft:white_glazed_terracotta", 8, 18);
        add(c, "minecraft:blue_glazed_terracotta", 8, 18);
        add(c, "minecraft:red_glazed_terracotta", 8, 18);
        add(c, "minecraft:white_stained_glass", 32, 12);
        add(c, "minecraft:black_stained_glass", 32, 12);
        add(c, "minecraft:gray_stained_glass", 32, 12);
        add(c, "minecraft:brown_stained_glass", 32, 12);
        add(c, "minecraft:glow_item_frame", 4, 20);
        add(c, "minecraft:chain", 16, 14);
        add(c, "minecraft:decorated_pot", 4, 14);
        add(c, "minecraft:chiseled_bookshelf", 4, 22);
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

    /**
     * Food, priced to be worth farming.
     *
     * Smaller lots at higher prices than the raw ingredients suggest, because
     * the sell side is 35% and a realistic price on a stack of bread means a
     * day in the fields pays about four emeralds. Food is the one thing on this
     * server that renews itself, so it earns like a crop rather than like
     * gravel. The luxuries -- golden apples, cake -- keep their old prices;
     * they are already anchored against the top shelf.
     */
    private static void kitchen() {
        Category c = FOOD;
        add(c, "minecraft:bread", 8, 16);
        add(c, "minecraft:cooked_beef", 6, 26);
        add(c, "minecraft:cooked_porkchop", 6, 26);
        add(c, "minecraft:cooked_chicken", 6, 21);
        add(c, "minecraft:cooked_mutton", 6, 23);
        add(c, "minecraft:cooked_rabbit", 6, 23);
        add(c, "minecraft:cooked_salmon", 6, 23);
        add(c, "minecraft:cooked_cod", 6, 21);
        add(c, "minecraft:baked_potato", 8, 16);
        add(c, "minecraft:pumpkin_pie", 4, 31);
        add(c, "minecraft:cake", 1, 14);
        add(c, "minecraft:cookie", 8, 16);
        add(c, "minecraft:apple", 8, 18);
        add(c, "minecraft:melon_slice", 8, 13);
        add(c, "minecraft:dried_kelp", 8, 13);
        add(c, "minecraft:mushroom_stew", 2, 21);
        add(c, "minecraft:rabbit_stew", 2, 36);
        add(c, "minecraft:beetroot_soup", 2, 23);
        add(c, "minecraft:suspicious_stew", 2, 18);
        add(c, "minecraft:golden_carrot", 8, 46);
        add(c, "minecraft:milk_bucket", 1, 12);
        add(c, "minecraft:honey_bottle", 2, 36);
        add(c, "minecraft:sugar", 8, 13);

        add(c, "farmersdelight:hamburger", 2, 47);
        add(c, "farmersdelight:chicken_sandwich", 2, 42);
        add(c, "farmersdelight:bacon_sandwich", 2, 44);
        add(c, "farmersdelight:egg_sandwich", 2, 36);
        add(c, "farmersdelight:beef_stew", 2, 52);
        add(c, "farmersdelight:chicken_soup", 2, 47);
        add(c, "farmersdelight:vegetable_soup", 2, 42);
        add(c, "farmersdelight:fish_stew", 2, 47);
        add(c, "farmersdelight:pumpkin_soup", 2, 42);
        add(c, "farmersdelight:noodle_soup", 2, 49);
        add(c, "farmersdelight:fried_rice", 2, 44);
        add(c, "farmersdelight:mixed_salad", 2, 39);
        add(c, "farmersdelight:roast_chicken", 1, 68);
        add(c, "farmersdelight:honey_glazed_ham", 1, 73);
        add(c, "farmersdelight:shepherds_pie", 1, 68);
        add(c, "farmersdelight:steak_and_potatoes", 1, 70);
        add(c, "farmersdelight:stuffed_pumpkin", 1, 62);
        add(c, "farmersdelight:apple_pie_slice", 4, 31);
        add(c, "farmersdelight:sweet_berry_cheesecake_slice", 4, 34);
        add(c, "farmersdelight:chocolate_pie_slice", 4, 34);
        add(c, "farmersdelight:fruit_salad", 2, 36);
        add(c, "farmersdelight:melon_popsicle", 4, 26);
        add(c, "farmersdelight:hot_cocoa", 2, 31);
        add(c, "farmersdelight:apple_cider", 2, 34);
        add(c, "farmersdelight:melon_juice", 2, 29);
        add(c, "farmersdelight:cooked_bacon", 6, 29);
        add(c, "farmersdelight:cooked_rice", 4, 26);
        add(c, "farmersdelight:pie_crust", 4, 23);
        add(c, "farmersdelight:wheat_dough", 4, 18);

        add(c, "culturaldelights:beef_burrito", 2, 52);
        add(c, "culturaldelights:chicken_taco", 2, 44);
        add(c, "culturaldelights:fish_taco", 2, 44);
        add(c, "culturaldelights:eggplant_burger", 2, 47);
        add(c, "culturaldelights:avocado_toast", 2, 39);
        add(c, "culturaldelights:hearty_salad", 2, 42);
        add(c, "culturaldelights:spicy_curry", 2, 55);
        add(c, "culturaldelights:elote", 2, 34);
        add(c, "culturaldelights:popcorn", 4, 23);
        add(c, "culturaldelights:tortilla", 4, 21);
        add(c, "culturaldelights:tortilla_chips", 4, 23);
        add(c, "culturaldelights:rice_ball", 4, 29);
        add(c, "culturaldelights:cooked_calamari", 4, 36);

        add(c, "rusticdelight:pancake", 4, 31);
        add(c, "rusticdelight:honey_pancake", 4, 36);
        add(c, "rusticdelight:chocolate_pancake", 4, 36);
        add(c, "rusticdelight:coffee", 2, 31);
        add(c, "rusticdelight:milk_coffee", 2, 34);
        add(c, "rusticdelight:honey_coffee", 2, 36);
        add(c, "rusticdelight:fried_chicken", 2, 49);
        add(c, "rusticdelight:potato_salad", 2, 36);
        add(c, "rusticdelight:spring_rolls", 2, 42);
        add(c, "rusticdelight:fried_dumplings", 2, 42);
        add(c, "rusticdelight:bell_pepper_soup", 2, 44);
        add(c, "rusticdelight:sweet_salad", 2, 39);
        add(c, "rusticdelight:cooking_oil", 2, 26);
        add(c, "rusticdelight:syrup", 2, 29);
    }

    private static void materials() {
        Category c = MATERIALS;
        add(c, "minecraft:coal_block", 8, 22);
        add(c, "minecraft:raw_iron_block", 2, 40);
        add(c, "minecraft:raw_copper_block", 2, 24);
        add(c, "minecraft:raw_gold_block", 2, 60);
        add(c, "minecraft:iron_block", 2, 40);
        add(c, "minecraft:gold_block", 2, 70);
        add(c, "minecraft:lapis_block", 4, 50);
        add(c, "minecraft:redstone_block", 4, 30);
        add(c, "minecraft:clay", 16, 10);
        add(c, "minecraft:brick", 32, 10);
        add(c, "minecraft:nether_wart_block", 8, 26);
        add(c, "minecraft:warped_wart_block", 8, 26);
        add(c, "minecraft:ender_eye", 4, 70);
        add(c, "minecraft:chorus_fruit", 16, 20);
        add(c, "minecraft:popped_chorus_fruit", 16, 24);
        add(c, "minecraft:glow_lichen", 8, 14);
        add(c, "minecraft:sculk", 16, 16);
        add(c, "minecraft:wither_rose", 4, 60);
        add(c, "minecraft:stick", 64, 4);
        add(c, "minecraft:bowl", 32, 6);
        add(c, "minecraft:slime_block", 4, 40);
        add(c, "minecraft:honey_block", 4, 34);
        add(c, "minecraft:dried_kelp_block", 8, 20);
        add(c, "minecraft:cobweb", 8, 26);
        add(c, "minecraft:turtle_scute", 2, 50);
        add(c, "minecraft:rabbit_foot", 2, 45);
        add(c, "minecraft:resin_clump", 16, 16);
        add(c, "minecraft:breeze_rod", 2, 120);
        add(c, "minecraft:wind_charge", 8, 30);
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
        add(c, "minecraft:crafter", 1, 40);
        add(c, "minecraft:respawn_anchor", 1, 180);
        add(c, "minecraft:hopper_minecart", 1, 45);
        add(c, "minecraft:chest_minecart", 1, 32);
        add(c, "minecraft:tnt_minecart", 1, 50);
        add(c, "minecraft:furnace_minecart", 1, 30);
        add(c, "minecraft:oak_boat", 2, 12);
        add(c, "minecraft:oak_chest_boat", 2, 18);
        add(c, "minecraft:crossbow", 1, 26);
        add(c, "minecraft:spectral_arrow", 8, 24);
        add(c, "minecraft:carrot_on_a_stick", 1, 20);
        add(c, "minecraft:warped_fungus_on_a_stick", 1, 28);
        add(c, "minecraft:map", 4, 12);
        add(c, "minecraft:filled_map", 1, 10);
        add(c, "minecraft:writable_book", 4, 14);
        add(c, "minecraft:goat_horn", 1, 70);
        add(c, "minecraft:trial_key", 1, 140);
        add(c, "minecraft:ominous_trial_key", 1, 260);
        add(c, "minecraft:ominous_bottle", 2, 90);
        add(c, "minecraft:mace", 1, 900);
        add(c, "minecraft:heavy_core", 1, 700);
        add(c, "minecraft:lightning_rod", 2, 24);
        add(c, "minecraft:daylight_detector", 2, 20);
        add(c, "minecraft:lectern", 2, 16);
        add(c, "minecraft:fletching_table", 1, 12);
        add(c, "minecraft:stonecutter", 1, 12);
        add(c, "minecraft:campfire", 4, 12);
        add(c, "minecraft:soul_campfire", 4, 18);
        add(c, "minecraft:beehive", 2, 20);
        add(c, "minecraft:bundle", 1, 26);
        add(c, "minecraft:activator_rail", 8, 26);
        add(c, "minecraft:firework_rocket", 16, 16);
        add(c, "minecraft:bell", 1, 45);
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

    /**
     * Books, priced against the grind they replace.
     *
     * The bands are effort, not power: a curse is worth what someone will pay
     * to be rid of it, a situational book is an afternoon, and the ones people
     * actually chase -- Mending, Fortune III, Protection IV -- cost about what
     * a fat contract pays. Deliberately not cheap enough to skip the enchanting
     * table, and deliberately not so dear that the table is the only route.
     *
     * Levels below the cap are listed only where somebody would genuinely buy
     * one; nobody wants Sharpness I on a shelf next to Sharpness V.
     */
    private static void enchantments() {
        // The chase list.
        book("minecraft:mending", 1, 340);
        book("minecraft:fortune", 3, 300);
        book("minecraft:fortune", 1, 90);
        book("minecraft:silk_touch", 1, 260);
        book("minecraft:infinity", 1, 250);
        book("minecraft:efficiency", 5, 270);
        book("minecraft:efficiency", 2, 70);
        book("minecraft:protection", 4, 290);
        book("minecraft:protection", 1, 80);
        book("minecraft:unbreaking", 3, 210);
        book("minecraft:unbreaking", 1, 60);
        book("minecraft:looting", 3, 260);
        book("minecraft:looting", 1, 75);
        book("minecraft:sharpness", 5, 240);
        book("minecraft:sharpness", 2, 70);
        book("minecraft:power", 5, 230);
        book("minecraft:power", 2, 65);

        // Worth having, nobody loses sleep over it.
        book("minecraft:fire_protection", 4, 160);
        book("minecraft:blast_protection", 4, 190);
        book("minecraft:projectile_protection", 4, 165);
        book("minecraft:feather_falling", 4, 200);
        book("minecraft:feather_falling", 2, 60);
        book("minecraft:respiration", 3, 140);
        book("minecraft:aqua_affinity", 1, 120);
        book("minecraft:depth_strider", 3, 155);
        book("minecraft:frost_walker", 2, 145);
        book("minecraft:soul_speed", 3, 175);
        book("minecraft:swift_sneak", 3, 195);
        book("minecraft:thorns", 3, 150);
        book("minecraft:fire_aspect", 2, 145);
        book("minecraft:knockback", 2, 95);
        book("minecraft:sweeping_edge", 3, 150);
        book("minecraft:smite", 5, 130);
        book("minecraft:bane_of_arthropods", 5, 110);
        book("minecraft:punch", 2, 105);
        book("minecraft:flame", 1, 150);
        book("minecraft:luck_of_the_sea", 3, 170);
        book("minecraft:lure", 3, 155);
        book("minecraft:quick_charge", 3, 160);
        book("minecraft:piercing", 4, 145);
        book("minecraft:multishot", 1, 165);

        // Trident and mace work: rarer to roll, so dearer to buy.
        book("minecraft:loyalty", 3, 210);
        book("minecraft:channeling", 1, 240);
        book("minecraft:impaling", 5, 195);
        book("minecraft:riptide", 3, 200);
        book("minecraft:density", 5, 215);
        book("minecraft:breach", 4, 225);
        book("minecraft:wind_burst", 3, 280);

        // Sold as curiosities. Somebody always buys one.
        book("minecraft:binding_curse", 1, 25);
        book("minecraft:vanishing_curse", 1, 20);
    }

    private static void theGoodStuff() {
        Category c = RARE;
        add(c, "minecraft:netherite_axe", 1, 1250);
        add(c, "minecraft:netherite_pickaxe", 1, 1250);
        add(c, "minecraft:netherite_sword", 1, 1200);
        add(c, "minecraft:netherite_helmet", 1, 1300);
        add(c, "minecraft:netherite_chestplate", 1, 1500);
        add(c, "minecraft:netherite_leggings", 1, 1450);
        add(c, "minecraft:netherite_boots", 1, 1300);
        add(c, "minecraft:netherite_block", 1, 10000);
        add(c, "minecraft:enchanted_book", 1, 120);
        add(c, "minecraft:dragon_egg", 1, 4000);
        add(c, "minecraft:sponge", 4, 120);
        add(c, "minecraft:wet_sponge", 4, 110);
        add(c, "minecraft:bee_nest", 1, 140);
        add(c, "minecraft:budding_amethyst", 1, 320);
        add(c, "minecraft:skeleton_skull", 1, 180);
        add(c, "minecraft:zombie_head", 1, 180);
        add(c, "minecraft:creeper_head", 1, 220);
        add(c, "minecraft:piglin_head", 1, 260);
        add(c, "minecraft:music_disc_otherside", 1, 420);
        add(c, "minecraft:music_disc_5", 1, 380);
        add(c, "minecraft:music_disc_relic", 1, 400);
        add(c, "minecraft:music_disc_creator", 1, 400);
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

    /**
     * The catalogue line this stack would sell as, or null.
     *
     * Matches on components as well as item, so a Sharpness V book finds the
     * Sharpness V line and not the Sharpness I one.
     */
    public static Entry matching(ItemStack stack) {
        for (Entry entry : STOCK) {
            if (entry.matches(stack)) {
                return entry;
            }
        }
        return null;
    }

    public static List<Entry> of(Category category) {
        return STOCK.stream().filter(entry -> entry.category() == category).toList();
    }
}
