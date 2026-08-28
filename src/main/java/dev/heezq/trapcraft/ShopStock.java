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
import net.minecraft.registry.tag.TagKey;
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

    public static final Category BUILDING = new Category("building", "Budowlane",
            "minecraft:bricks", Formatting.GOLD, "Kamień, cegła i szkło");
    public static final Category WOOD = new Category("wood", "Drewno i natura",
            "minecraft:oak_log", Formatting.DARK_GREEN, "Kłody, liście, rośliny");
    public static final Category DECOR = new Category("decor", "Ozdoby",
            "minecraft:cyan_terracotta", Formatting.LIGHT_PURPLE, "Kolory i wykończenia");
    public static final Category GARDEN = new Category("garden", "Ogród",
            "minecraft:peony", Formatting.LIGHT_PURPLE, "Kwiaty, donice, latarnie");
    public static final Category FARMING = new Category("farming", "Nasiona i plony",
            "minecraft:wheat_seeds", Formatting.GREEN, "Wszystko, co rośnie");
    public static final Category FOOD = new Category("food", "Kuchnia",
            "minecraft:cooked_beef", Formatting.RED, "Coś do jedzenia");
    public static final Category MATERIALS = new Category("materials", "Surowce",
            "minecraft:iron_ingot", Formatting.AQUA, "Rudy, sztabki, łupy");
    public static final Category UTILITY = new Category("utility", "Techniczne",
            "minecraft:redstone", Formatting.YELLOW, "Redstone i mechanizmy");
    public static final Category ENCHANTS = new Category("enchants", "Zaklęcia",
            "minecraft:enchanted_book", Formatting.LIGHT_PURPLE, "Księgi na sprzedaż, bez grindu");
    public static final Category NETHER = new Category("nether", "Nether",
            "minecraft:netherrack", Formatting.DARK_RED, "Wszystko z dołu");
    public static final Category RARE = new Category("rare", "Rzadkie skarby",
            "minecraft:nether_star", Formatting.DARK_PURPLE, "Jeśli cię na to stać");
    public static final Category FURNITURE = new Category("furniture", "Meble",
            "mcwfurnitures:oak_chair", Formatting.GOLD, "Krzesła, stoły, szafki");
    /**
     * Roofs, windows, rails and trim -- Macaw's building mods.
     *
     * Their own shelf rather than the building one, which is a deviation from
     * "one more tab" and worth the deviation: they are fifteen hundred lines
     * between them, and tipped into Building they would bury the stone under
     * thirty pages of window frames.
     */
    public static final Category FITTINGS = new Category("fittings", "Dachy i stolarka",
            "mcwroofs:oak_planks_upper_lower_roof", Formatting.YELLOW, "Dachy, okna, barierki");
    /**
     * The wands, and the fourteenth of fifteen shelves.
     *
     * The far end of the market on purpose: everything else here is something
     * you could have grown, mined or killed for, and the shelf that isn't is
     * what the emeralds are ultimately FOR. Nothing else costs five figures.
     */
    public static final Category MAGIC = new Category("magic", "Różdżki",
            "trapcraft:storm_wand", Formatting.LIGHT_PURPLE, "Drogo. Bardzo drogo");
    /**
     * Keys, and only keys.
     *
     * The cases themselves are deliberately absent and must stay absent: a
     * case you can buy is a shop with extra steps, and the whole mechanic is
     * that the box is free and the key is not. See {@link TrapCases}.
     */
    public static final Category KEYS = new Category("keys", "Klucze",
            "trapcraft:cartel_key", Formatting.YELLOW, "Do skrzynek. Bez zwrotów");

    public static final List<Category> CATEGORIES =
            List.of(BUILDING, FITTINGS, WOOD, DECOR, FURNITURE, GARDEN, FARMING, FOOD,
                    MATERIALS, NETHER, UTILITY, ENCHANTS, RARE, KEYS, MAGIC);

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

    /**
     * The stairs, slab and wall cut from one block, at the block's own price.
     *
     * Same bundle, same money: thirty-two stairs cost what thirty-two of the
     * stone costs. That is not laziness, it is the rule that keeps a
     * stonecutter from being a printer -- one block gives at most two slabs,
     * and two slabs sold back at 45% can never beat one block bought at full
     * price. Price a shape above its block and the loop opens immediately, on
     * a machine every base has, and nothing in the game would ever say so.
     *
     * Walls are optional because the game is inconsistent about them: tuff has
     * one, polished granite doesn't, and an id nobody provides would be
     * counted at startup as a mod that isn't installed.
     */
    private static void shapes(Category c, String family, int count, int price, boolean walls) {
        add(c, "minecraft:" + family + "_stairs", count, price);
        add(c, "minecraft:" + family + "_slab", count, price);
        if (walls) {
            add(c, "minecraft:" + family + "_wall", count, price);
        }
    }

    /** An enchanted book line. Keyed separately so levels don't collide. */
    private static void book(String enchantment, int level, int base) {
        DECLARED.put("minecraft:enchanted_book#" + enchantment + "#" + level,
                new Object[]{ENCHANTS, 1, base, enchantment, level});
    }

    /**
     * An armour trim template, priced by how far you'd have to go for it.
     *
     * Eighteen of them share one id shape, and the shape is most of the id.
     * Duplicating one costs seven diamonds, so nothing here is worth listing
     * above that -- the smithing table is the competition.
     */
    private static void trim(String pattern, int base) {
        add(RARE, "minecraft:" + pattern + "_armor_trim_smithing_template", 1, base);
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
        stockTheTimberYard();
        // Last, so it can see everything the others claimed and not list a
        // modded flower twice at two different prices.
        stockTheMods();
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
        int dearest = 0;
        for (Item item : Registries.ITEM) {
            String id = Registries.ITEM.getId(item).toString();
            if (DECLARED.containsKey(id) || NEVER_STOCK.contains(id) || CURRENCY.contains(id)) {
                continue;
            }
            FoodComponent food = item.getComponents().get(DataComponentTypes.FOOD);
            if (food != null) {
                int each = TrapMath.foodPrice(food.nutrition(), food.saturation(),
                        item.getDefaultStack().getMaxCount());
                int count = TrapMath.foodLot(each);
                STOCK.add(new Entry(FOOD, item, id, count, each * count,
                        new ItemStack(item, count), item.getName().getString()));
                foods++;
                dearest = each > dearest ? each : dearest;
            } else if (plantable(item)) {
                STOCK.add(new Entry(FARMING, item, id, 8, SEED_LOT,
                        new ItemStack(item, 8), item.getName().getString()));
                seeds++;
            }
        }
        // Logged with the top of the range, because a food mod that ships a
        // dish with absurd nutrition would quietly become the best money on the
        // server and there is nothing in game that would ever point at it.
        TrapCraft.LOGGER.info("market: found {} foods and {} seeds in the registry, "
                + "dearest helping {}e", foods, seeds, dearest);
    }

    /** What eight of anybody's seeds cost. Cheap: they come out of grass. */
    private static final int SEED_LOT = 12;

    /** One mod's whole catalogue, on one shelf, at one price. */
    private record Sweep(Category shelf, int count, int price) {
    }

    /**
     * Modded furniture, fittings and decoration, straight off the registry.
     *
     * Two and a half thousand items across eleven mods. The same argument as
     * the kitchen and the garden: listing those by hand is a file nobody can
     * maintain that goes stale on the next mod update, and every id typed
     * wrong vanishes silently as "mod not present".
     *
     * ONE PRICE PER MOD, and the number is measured rather than picked. Every
     * recipe in each of these jars was costed against this catalogue, and the
     * price here is about one and a half times the CHEAPEST thing that mod can
     * make. That matters more than it looks: the counter pays 45%, so a line
     * priced under 2.2x its own ingredients can never be worth crafting to
     * sell, whatever the recipe is. check_stock.py reads vanilla recipes only
     * -- these live in mod jars it cannot see -- so the margin is the guard,
     * and the measurements are in tools/ if the pack ever changes under it.
     *
     * Flat pricing has exactly one hole and Macaw's Windows falls in it: six
     * mosaic blocks make sixteen panes, and a pane priced like a block would
     * pay 20% a craft. Panes are priced as the third of a block they are.
     */
    private static final Map<String, Sweep> SWEEPS = Map.ofEntries(
            // Furniture, one piece at a time. Nobody wants eight of the same
            // chair, and a bundle is the only granularity this shop has -- so
            // the bundle is one. At 1e a piece that puts every one of these
            // lines under the counter's two-emerald floor, which is the whole
            // reason the price is allowed to double: the shop sells you a
            // chair and never buys it back, so no craft can be sold into it.
            // Cheapest Macaw's piece is 0.33e of wood and 0.45 x 1e clears it,
            // which would be a printer if any of this were bought back.
            Map.entry("mcwfurnitures", new Sweep(FURNITURE, 1, 1)),
            Map.entry("storagedelight", new Sweep(FURNITURE, 1, 1)),
            Map.entry("cratedelight", new Sweep(FURNITURE, 1, 13)),
            Map.entry("comforts", new Sweep(FURNITURE, 1, 2)),
            // Fittings. Built out of stairs and slabs, and priced like them.
            Map.entry("mcwroofs", new Sweep(FITTINGS, 32, 4)),
            Map.entry("mcwwindows", new Sweep(FITTINGS, 64, 4)),
            Map.entry("mcwfences", new Sweep(FITTINGS, 64, 6)),
            Map.entry("mcwstairs", new Sweep(FITTINGS, 64, 3)),
            Map.entry("mcwbridges", new Sweep(FITTINGS, 32, 4)),
            // Doors and trapdoors are joinery too, and they carry the vanilla
            // wood tags -- so they have to be swept by name here, or the
            // timber yard below would price five hundred Macaw's doors as
            // plain oak ones.
            Map.entry("mcwdoors", new Sweep(FITTINGS, 8, 2)),
            Map.entry("mcwtrpdoors", new Sweep(FITTINGS, 8, 2)),
            // Paving, on the building shelf and under the stone it is cut
            // from -- the stonecutter turns one andesite into FOUR paving
            // slabs, so the price comes off that four rather than off what a
            // path feels like it ought to cost. Swept, so it lands after the
            // hand-written stone and the shelf still opens on cobble.
            Map.entry("mcwpaths", new Sweep(BUILDING, 64, 2)),
            // Lanterns and lamps.
            Map.entry("mcwlights", new Sweep(DECOR, 16, 2)),
            // Sold, never bought back, and that is the only price that works:
            // fifty-five of this mod's recipes hand back more items than they
            // eat -- nine garlands from three, sixty-four snow from nine -- and
            // no flat price survives a recipe that triples the count. Whatever
            // the number is, nine of it at 45% beats three of it at full. Under
            // the counter's two-emerald floor there is nothing to sell into, so
            // all fifty-five close at once instead of one suffix at a time.
            Map.entry("mcwholidays", new Sweep(DECOR, 16, 1)),
            // Trim, and the blocks that are nine of something in a coat.
            Map.entry("beautify", new Sweep(DECOR, 16, 3)),
            Map.entry("stackedblocks", new Sweep(MATERIALS, 1, 8)));

    /** Panes, wherever they come from. Three to the block they are cut from. */
    private static final Sweep PANES = new Sweep(FITTINGS, 64, 3);

    /**
     * Every id on a shelf already, however it got there.
     *
     * The later sweeps need this because the earlier ones stock off the
     * registry rather than out of DECLARED: these mods add flowers and food
     * too. Listing a thing twice is not an error the game reports -- it is two
     * prices for one item and a shelf that disagrees with itself.
     */
    private static java.util.Set<String> stocked() {
        java.util.Set<String> already = new java.util.HashSet<>();
        for (Entry entry : STOCK) {
            already.add(entry.id());
        }
        return already;
    }

    /**
     * Every wood in the pack, at the prices vanilla wood already sells for.
     *
     * A hundred and thirty-six mods, and between them about two dozen more
     * trees: Oh The Biomes We've Gone alone adds twenty-five. The shelf
     * carried nine. Somebody building in aspen bought oak here and went back to
     * chopping, which is the same complaint the timber loop above was written
     * to answer -- one tree short of the pack rather than one plank short of a
     * roof.
     *
     * Asked of the vanilla tags rather than by name, because a wood mod has to
     * join #minecraft:planks for a crafting table to accept its planks at all.
     * The tags are load-bearing for the mod, so they are honest about what is a
     * plank in a way a list of ids I typed out never would be.
     *
     * Prices are the vanilla ones exactly, shape for shape. That is what makes
     * this safe without measuring anything: check_stock.py has already proved
     * the vanilla numbers can't be crafted into money, and a mod's oak-shaped
     * recipes are vanilla's recipes with a different log in them.
     */
    private static final Map<TagKey<Item>, int[]> TIMBER = new LinkedHashMap<>();

    static {
        TIMBER.put(ItemTags.LOGS, new int[]{32, 8});
        TIMBER.put(ItemTags.PLANKS, new int[]{64, 5});
        TIMBER.put(ItemTags.WOODEN_STAIRS, new int[]{64, 5});
        TIMBER.put(ItemTags.WOODEN_SLABS, new int[]{64, 5});
        TIMBER.put(ItemTags.WOODEN_FENCES, new int[]{32, 4});
        TIMBER.put(ItemTags.FENCE_GATES, new int[]{8, 3});
        TIMBER.put(ItemTags.WOODEN_DOORS, new int[]{16, 4});
        TIMBER.put(ItemTags.WOODEN_TRAPDOORS, new int[]{16, 4});
        TIMBER.put(ItemTags.WOODEN_BUTTONS, new int[]{32, 3});
        TIMBER.put(ItemTags.WOODEN_PRESSURE_PLATES, new int[]{16, 3});
        // Before SIGNS: a hanging sign is in both tags and is the dearer of
        // the two, and first match wins below.
        TIMBER.put(ItemTags.HANGING_SIGNS, new int[]{8, 4});
        TIMBER.put(ItemTags.SIGNS, new int[]{8, 3});
    }

    private static void stockTheTimberYard() {
        java.util.Set<String> already = stocked();
        Map<String, Integer> perMod = new LinkedHashMap<>();
        for (Item item : Registries.ITEM) {
            String id = Registries.ITEM.getId(item).toString();
            String mod = id.substring(0, id.indexOf(':'));
            // A mod with its own shelf keeps it. Macaw's doors and trapdoors
            // are five hundred lines of wooden door by the tags, and they are
            // joinery, not timber.
            if (SWEEPS.containsKey(mod) || already.contains(id) || DECLARED.containsKey(id)
                    || NEVER_STOCK.contains(id) || CURRENCY.contains(id)) {
                continue;
            }
            ItemStack stack = item.getDefaultStack();
            for (var shape : TIMBER.entrySet()) {
                if (!stack.isIn(shape.getKey())) {
                    continue;
                }
                int count = shape.getValue()[0];
                STOCK.add(new Entry(WOOD, item, id, count, shape.getValue()[1],
                        new ItemStack(item, count), item.getName().getString()));
                perMod.merge(mod, 1, Integer::sum);
                break;
            }
        }
        TrapCraft.LOGGER.info("market: swept {} timber lines {}",
                perMod.values().stream().mapToInt(Integer::intValue).sum(), perMod);
    }

    private static void stockTheMods() {
        java.util.Set<String> already = stocked();

        Map<String, Integer> perMod = new LinkedHashMap<>();
        for (Item item : Registries.ITEM) {
            String id = Registries.ITEM.getId(item).toString();
            String mod = id.substring(0, id.indexOf(':'));
            Sweep sweep = SWEEPS.get(mod);
            if (sweep == null || already.contains(id) || DECLARED.containsKey(id)
                    || NEVER_STOCK.contains(id) || CURRENCY.contains(id)) {
                continue;
            }
            if (id.endsWith("_pane")) {
                sweep = new Sweep(sweep.shelf(), PANES.count(), PANES.price());
            }
            STOCK.add(new Entry(sweep.shelf(), item, id, sweep.count(), sweep.price(),
                    new ItemStack(item, sweep.count()), item.getName().getString()));
            perMod.merge(mod, 1, Integer::sum);
        }
        // Per mod, because "0 furniture" and "the sweep never ran" look the
        // same from in game, and a mod that renames its namespace would go
        // quiet rather than loud.
        TrapCraft.LOGGER.info("market: swept {} modded lines {}",
                perMod.values().stream().mapToInt(Integer::intValue).sum(), perMod);
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
        // The walls, fences and trapdoors that used to be listed here are on
        // the building and timber shelves now, with the rest of their family.
        // They were also the shape of a money printer: an oak trapdoor sold
        // for two and a half times the planks it is made of.

        // Reef. Every coral is four blocks -- the plant, the fan, the block
        // and the bleached block you get by leaving it out of water -- and
        // none of them can be crafted, so a diver is the only supplier.
        for (String coral : new String[]{"tube", "brain", "bubble", "fire", "horn"}) {
            add(c, "minecraft:" + coral + "_coral_block", 8, 24);
            add(c, "minecraft:" + coral + "_coral", 8, 16);
            add(c, "minecraft:" + coral + "_coral_fan", 8, 16);
            add(c, "minecraft:dead_" + coral + "_coral_block", 8, 16);
        }
        // Autumn, which the seed shelf sells you the start of.
        add(c, "minecraft:pumpkin", 8, 16);
        add(c, "minecraft:carved_pumpkin", 8, 12);
        add(c, "minecraft:jack_o_lantern", 8, 16);
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
        masonry();
        copper();
        timber();
        decoration();
        garden();
        nether();
        seeds();
        kitchen();
        materials();
        utility();
        enchantments();
        theGoodStuff();
        wands();
        keys();
    }

    /**
     * The key shelf.
     *
     * Written as four literals and not as a loop over {@code CaseOdds.Tier},
     * which was the first version and was wrong: every tool in tools/ reads
     * this catalogue by pattern-matching {@code add(...)} lines, so a loop is
     * four lines that no check sees and that the wiki price list silently
     * omits. The prices genuinely live in {@link CaseOdds} -- CaseOddsTest
     * asserts the whole economy against them -- and check_stock.py compares
     * these four numbers against that file, so the copy cannot drift.
     *
     * The shop will not buy them back at any price -- see TrapScrap.refusal.
     * Keys turn up free in chests, and a counter that paid half of 22,000e for
     * one found in an end city would make exploring an emerald faucet rather
     * than a route into the cases.
     */
    private static void keys() {
        Category c = KEYS;
        add(c, "trapcraft:street_key", 1, 450);
        add(c, "trapcraft:docks_key", 1, 1600);
        add(c, "trapcraft:cartel_key", 1, 6000);
        add(c, "trapcraft:phantom_key", 1, 22000);
    }

    /**
     * The wand rack, priced as the thing you are saving for.
     *
     * These shipped at 4,500e to 18,000e and that was wrong: the players they
     * are for were sitting on 30-50k, so the whole rack cost about one stash
     * and the most powerful items in the mod were an afternoon's pocket money.
     * Re-cut so the cheapest is a real dent in a rich player's savings and the
     * storm wand is several times anybody's, which is the only way a sink
     * works on people who have already won the earning game.
     *
     * The shop does not buy them back at any price -- see TrapScrap.refusal.
     * Each of these can also be crafted, so the recipes carry one to three of
     * something that has to be fought for (see WANDS in gen_assets.py); a wand
     * off one nether star would make this shelf decoration.
     */
    private static void wands() {
        Category c = MAGIC;
        add(c, "trapcraft:boost_wand", 1, 25000);
        add(c, "trapcraft:harvest_wand", 1, 40000);
        add(c, "trapcraft:prospect_wand", 1, 55000);
        add(c, "trapcraft:builder_wand", 1, 80000);
        add(c, "trapcraft:storm_wand", 1, 120000);
    }

    /**
     * Every shape of every stone, at the price of the stone.
     *
     * Eighty-eight lines the market never carried, which is most of what
     * anybody actually places: nobody builds a wall out of full blocks and
     * nobody roofs anything without stairs. The bundle and price of each
     * family are its own block's, copied from {@link #building} above -- see
     * {@link #shapes} for why that number and not a nicer one.
     */
    private static void masonry() {
        Category c = BUILDING;
        shapes(c, "cobblestone", 64, 20, true);
        shapes(c, "mossy_cobblestone", 16, 10, true);
        shapes(c, "stone", 64, 24, false);
        shapes(c, "stone_brick", 64, 8, true);
        shapes(c, "mossy_stone_brick", 32, 10, true);
        // Smooth stone is the one family with a slab and no stairs.
        add(c, "minecraft:smooth_stone_slab", 64, 8);
        shapes(c, "granite", 64, 5, true);
        shapes(c, "polished_granite", 64, 6, false);
        shapes(c, "diorite", 64, 5, true);
        shapes(c, "polished_diorite", 64, 6, false);
        shapes(c, "andesite", 64, 5, true);
        shapes(c, "polished_andesite", 64, 6, false);
        shapes(c, "cobbled_deepslate", 64, 18, true);
        shapes(c, "polished_deepslate", 32, 9, true);
        shapes(c, "deepslate_brick", 32, 10, true);
        shapes(c, "deepslate_tile", 32, 12, true);
        shapes(c, "brick", 32, 16, true);
        shapes(c, "mud_brick", 32, 9, true);
        shapes(c, "sandstone", 64, 7, true);
        shapes(c, "smooth_sandstone", 32, 8, false);
        shapes(c, "red_sandstone", 64, 7, true);
        shapes(c, "smooth_red_sandstone", 32, 8, false);
        shapes(c, "prismarine", 16, 20, true);
        shapes(c, "prismarine_brick", 32, 24, false);
        shapes(c, "dark_prismarine", 32, 26, false);
        // Off the chiseled block, not the plain one: quartz shapes are cut
        // from whichever quartz block you have, so the shop has to assume the
        // cheapest of them or it is selling slabs for more than their stock.
        shapes(c, "quartz", 32, 24, false);
        shapes(c, "smooth_quartz", 16, 22, false);
        shapes(c, "purpur", 16, 20, false);
        shapes(c, "end_stone_brick", 32, 18, true);
        shapes(c, "tuff", 64, 6, true);
        shapes(c, "polished_tuff", 32, 9, true);
        shapes(c, "tuff_brick", 32, 9, true);
        shapes(c, "resin_brick", 32, 10, true);
        // The two slabs with no stairs to go with them.
        add(c, "minecraft:cut_sandstone_slab", 32, 8);
        add(c, "minecraft:cut_red_sandstone_slab", 32, 8);
    }

    /**
     * Copper, all seventy-two faces of it.
     *
     * The shelf carried nine copper lines picked apparently at random -- cut
     * copper but not its stairs, exposed copper but not exposed cut copper,
     * one waxed block out of eight. Copper is the one vanilla material whose
     * whole point is the palette, so the palette is what's sold: four ages,
     * waxed and not, nine shapes each.
     *
     * Every state is the same price. Oxidising is free and takes time, waxing
     * costs a honeycomb, and a shop that charged more for the green one would
     * just be selling patience -- which the weather gives away.
     */
    private static void copper() {
        Category c = BUILDING;
        for (String state : new String[]{"", "exposed_", "weathered_", "oxidized_",
                "waxed_", "waxed_exposed_", "waxed_weathered_", "waxed_oxidized_"}) {
            add(c, "minecraft:" + state + "cut_copper", 32, 16);
            shapes(c, state + "cut_copper", 32, 16, false);
            add(c, "minecraft:" + state + "chiseled_copper", 32, 18);
            add(c, "minecraft:" + state + "copper_grate", 32, 16);
            add(c, "minecraft:" + state + "copper_bulb", 8, 26);
            add(c, "minecraft:" + state + "copper_door", 8, 14);
            add(c, "minecraft:" + state + "copper_trapdoor", 8, 14);
        }
        // The plain block is the one form the game doesn't name to a pattern:
        // copper_block, then exposed_copper with no "block" on the end.
        for (String block : new String[]{"copper_block", "exposed_copper", "weathered_copper",
                "oxidized_copper", "waxed_copper_block", "waxed_exposed_copper",
                "waxed_weathered_copper", "waxed_oxidized_copper"}) {
            add(c, "minecraft:" + block, 4, 34);
        }
    }

    private static void building() {
        Category c = BUILDING;
        add(c, "minecraft:polished_granite", 64, 6);
        add(c, "minecraft:polished_diorite", 64, 6);
        add(c, "minecraft:polished_andesite", 64, 6);
        add(c, "minecraft:cut_red_sandstone", 32, 8);
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
        add(c, "minecraft:chiseled_quartz_block", 32, 24);
        add(c, "minecraft:quartz_bricks", 32, 24);
        add(c, "minecraft:prismarine_bricks", 32, 24);
        add(c, "minecraft:dark_prismarine", 32, 26);
        add(c, "minecraft:ochre_froglight", 4, 45);
        add(c, "minecraft:verdant_froglight", 4, 45);
        add(c, "minecraft:bone_block", 16, 18);
        add(c, "minecraft:dripstone_block", 32, 8);
        add(c, "minecraft:pale_moss_block", 16, 14);
        add(c, "minecraft:pearlescent_froglight", 4, 45);
        add(c, "minecraft:cobblestone", 64, 20);
        add(c, "minecraft:stone", 64, 24);
        add(c, "minecraft:smooth_stone", 64, 8);
        add(c, "minecraft:stone_bricks", 64, 8);
        add(c, "minecraft:mossy_stone_bricks", 32, 10);
        add(c, "minecraft:chiseled_stone_bricks", 32, 10);
        add(c, "minecraft:andesite", 64, 5);
        add(c, "minecraft:diorite", 64, 5);
        add(c, "minecraft:granite", 64, 5);
        add(c, "minecraft:calcite", 32, 8);
        add(c, "minecraft:tuff", 64, 6);
        add(c, "minecraft:deepslate", 64, 22);
        add(c, "minecraft:cobbled_deepslate", 64, 18);
        add(c, "minecraft:polished_deepslate", 32, 9);
        add(c, "minecraft:deepslate_bricks", 32, 10);
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
        add(c, "minecraft:end_stone", 32, 18);
        add(c, "minecraft:purpur_block", 16, 20);
        add(c, "minecraft:scaffolding", 32, 9);
        add(c, "minecraft:ladder", 16, 6);
        add(c, "minecraft:iron_bars", 16, 12);
        add(c, "minecraft:amethyst_block", 8, 26);
        add(c, "minecraft:mud_bricks", 32, 9);
        add(c, "minecraft:packed_ice", 16, 16);
        add(c, "minecraft:snow_block", 32, 5);
        add(c, "minecraft:snow", 64, 4);
        add(c, "minecraft:snowball", 32, 4);
        add(c, "minecraft:chiseled_tuff_bricks", 32, 9);
        add(c, "minecraft:resin_block", 8, 48);
        add(c, "minecraft:resin_brick", 32, 16);
        add(c, "minecraft:chiseled_resin_bricks", 32, 12);
        // The two doors nobody can make out of wood.
        add(c, "minecraft:iron_door", 8, 40);
        add(c, "minecraft:iron_trapdoor", 8, 50);
    }

    /**
     * The whole tree, nine times over.
     *
     * The shelf used to carry three lines per wood -- log, planks, sapling --
     * which is enough to start a build and not enough to finish one. Every
     * roof is stairs and slabs, every fence is fence and gate, and a stripped
     * log is a different material to the log it came off; none of it was for
     * sale, so a builder bought planks here and went back to chopping.
     *
     * Prices follow the planks, because everything below the log is planks by
     * the time you build with it, and planks are cheap on this server. The
     * arithmetic is in check_stock.py, which now reads this loop -- it did not
     * before, and the wooden trapdoor spent that whole time selling for two
     * and a half times the wood it is made of.
     *
     * Stripped wood is priced level with the log rather than above it. An axe
     * strips for free, so any gap is a loop; and the counter's 45% means a
     * level price can never be one.
     */
    private static void timber() {
        Category c = WOOD;
        for (String wood : new String[]{"oak", "spruce", "birch", "jungle", "acacia",
                "dark_oak", "pale_oak", "mangrove", "cherry"}) {
            add(c, "minecraft:" + wood + "_log", 32, 8);
            add(c, "minecraft:stripped_" + wood + "_log", 32, 8);
            add(c, "minecraft:" + wood + "_wood", 32, 8);
            add(c, "minecraft:stripped_" + wood + "_wood", 32, 8);
            add(c, "minecraft:" + wood + "_planks", 64, 5);
            shapes(c, wood, 64, 5, false);
            add(c, "minecraft:" + wood + "_fence", 32, 4);
            add(c, "minecraft:" + wood + "_fence_gate", 8, 3);
            add(c, "minecraft:" + wood + "_door", 16, 4);
            add(c, "minecraft:" + wood + "_trapdoor", 16, 4);
            add(c, "minecraft:" + wood + "_button", 32, 3);
            add(c, "minecraft:" + wood + "_pressure_plate", 16, 3);
            add(c, "minecraft:" + wood + "_sign", 8, 3);
            add(c, "minecraft:" + wood + "_hanging_sign", 8, 4);
        }
        // Saplings are not listed here any more. Mangrove hasn't got one --
        // it grows from a propagule -- so the loop was declaring an id that
        // has never existed, and the garden shelf stocks every sapling in the
        // game off the registry anyway, this mod's and the other hundred and
        // thirty-six mods'.

        // Bamboo: a plank family with no tree behind it.
        add(c, "minecraft:bamboo_block", 32, 8);
        add(c, "minecraft:stripped_bamboo_block", 32, 8);
        add(c, "minecraft:bamboo_planks", 64, 5);
        add(c, "minecraft:bamboo_mosaic", 64, 6);
        shapes(c, "bamboo", 64, 5, false);
        shapes(c, "bamboo_mosaic", 64, 6, false);
        add(c, "minecraft:bamboo_fence", 32, 4);
        add(c, "minecraft:bamboo_fence_gate", 8, 3);
        add(c, "minecraft:bamboo_door", 16, 4);
        add(c, "minecraft:bamboo_trapdoor", 16, 4);
        add(c, "minecraft:bamboo_button", 32, 3);
        add(c, "minecraft:bamboo_pressure_plate", 16, 3);
        add(c, "minecraft:bamboo_sign", 8, 3);
        add(c, "minecraft:bamboo_hanging_sign", 8, 4);
        add(c, "minecraft:bamboo", 32, 4);
        add(c, "minecraft:mangrove_roots", 32, 8);
        add(c, "minecraft:muddy_mangrove_roots", 32, 8);
        add(c, "minecraft:moss_block", 16, 10);
        add(c, "minecraft:vine", 16, 6);
        add(c, "minecraft:lily_pad", 16, 8);
        add(c, "minecraft:oak_leaves", 64, 4);
        add(c, "minecraft:podzol", 32, 8);
        add(c, "minecraft:mycelium", 16, 14);
        add(c, "minecraft:brown_mushroom", 16, 32);
        add(c, "minecraft:red_mushroom", 16, 32);
        add(c, "minecraft:kelp", 16, 32);
        add(c, "minecraft:sea_pickle", 8, 12);
        add(c, "minecraft:cactus", 16, 6);
        add(c, "minecraft:dead_bush", 8, 4);
    }

    /**
     * Colour, in all sixteen of every kind.
     *
     * Six concretes, four stained glasses and three glazed terracottas is a
     * decorating shelf that decides for you what your build is going to look
     * like. The colour families are all sixteen wide in the game and there is
     * no reason for the shop to carry a third of one -- so the loop at the
     * bottom carries the lot, and the hand-written lines that used to sit up
     * here are gone.
     *
     * Thirteen of them were dead anyway: the loop runs after them and DECLARED
     * is a map, so every wool price written out by hand had been silently
     * overwritten by the loop's for as long as both existed. check_stock.py
     * couldn't see it, because it only read string literals. It can now.
     */
    private static void decoration() {
        Category c = DECOR;
        add(c, "minecraft:glow_item_frame", 4, 20);
        add(c, "minecraft:chain", 16, 14);
        add(c, "minecraft:decorated_pot", 4, 14);
        add(c, "minecraft:chiseled_bookshelf", 4, 6);
        for (String dye : new String[]{"white", "orange", "magenta", "light_blue", "yellow",
                "lime", "pink", "gray", "light_gray", "cyan", "purple", "blue", "brown",
                "green", "red", "black"}) {
            add(c, "minecraft:" + dye + "_dye", 16, 6);
            add(c, "minecraft:" + dye + "_wool", 16, 8);
            add(c, "minecraft:" + dye + "_carpet", 32, 8);
            add(c, "minecraft:" + dye + "_concrete_powder", 32, 10);
            add(c, "minecraft:" + dye + "_concrete", 32, 12);
            add(c, "minecraft:" + dye + "_terracotta", 32, 11);
            // Glazed is a smelt away from plain terracotta, so it is priced
            // level with it. At the old 2.25e a block, a furnace was a mint.
            add(c, "minecraft:" + dye + "_glazed_terracotta", 32, 12);
            add(c, "minecraft:" + dye + "_stained_glass", 32, 12);
            add(c, "minecraft:" + dye + "_stained_glass_pane", 32, 8);
            add(c, "minecraft:" + dye + "_bed", 1, 3);
            add(c, "minecraft:" + dye + "_banner", 2, 10);
        }
        add(c, "minecraft:terracotta", 32, 9);
        add(c, "minecraft:item_frame", 4, 10);
        add(c, "minecraft:painting", 4, 10);
        add(c, "minecraft:flower_pot", 4, 6);
        add(c, "minecraft:armor_stand", 4, 4);
        add(c, "minecraft:candle", 8, 8);
        add(c, "minecraft:glow_ink_sac", 4, 20);
        add(c, "minecraft:ink_sac", 8, 8);
        add(c, "minecraft:book", 8, 12);
        add(c, "minecraft:paper", 32, 6);
    }

    /**
     * The raw end of the food chain, and the thing this market got worst.
     *
     * A stack of wheat used to fetch three emeralds. A field is an hour of
     * tilling, planting, waiting and walking back, and it paid less than
     * breaking one plant of the crop this mod is named after -- so the person
     * doing the farming was subsidising everybody else, and correctly said so.
     *
     * These are now priced so a nine-by-nine takes about what a customer visit
     * takes, which is the comparison that matters: it is a job on this server
     * rather than a chore somebody does out of politeness. Cooking is still the
     * better end of it, because a dish is several of these plus a fire.
     *
     * Every number here is bounded on both sides by check_stock.py: too low and
     * uncrafting hay bales into wheat mints money, too high and baking bread
     * does. The window is wide, but it is a window.
     */
    /**
     * The Nether shelf.
     *
     * These were scattered across Building, Timber, Materials and Utility,
     * which is defensible one line at a time and useless to somebody who has
     * just come back through a portal wanting to build with what they saw.
     * Prices are carried over exactly as they were -- this is a shelf, not a
     * repricing.
     *
     * Quartz and obsidian deliberately stay on the building shelf. They come
     * from down there, but nobody browsing for nether decoration is looking
     * for a quartz block, and everybody browsing for building stone is.
     */
    private static void nether() {
        Category c = NETHER;
        add(c, "minecraft:netherrack", 64, 4);
        add(c, "minecraft:nether_bricks", 32, 14);
        add(c, "minecraft:red_nether_bricks", 32, 16);
        add(c, "minecraft:chiseled_nether_bricks", 16, 15);
        add(c, "minecraft:magma_block", 16, 20);
        add(c, "minecraft:soul_sand", 32, 10);
        add(c, "minecraft:soul_soil", 32, 10);
        add(c, "minecraft:glowstone", 16, 28);
        add(c, "minecraft:shroomlight", 8, 30);
        add(c, "minecraft:crying_obsidian", 4, 44);
        add(c, "minecraft:gilded_blackstone", 8, 45);
        add(c, "minecraft:blackstone", 32, 9);
        add(c, "minecraft:basalt", 32, 7);
        add(c, "minecraft:smooth_basalt", 32, 8);
        add(c, "minecraft:polished_basalt", 32, 8);
        add(c, "minecraft:polished_blackstone", 32, 10);
        add(c, "minecraft:polished_blackstone_bricks", 32, 12);
        add(c, "minecraft:crimson_stem", 32, 12);
        add(c, "minecraft:warped_stem", 32, 12);
        add(c, "minecraft:nether_wart_block", 8, 26);
        add(c, "minecraft:warped_wart_block", 8, 26);
        add(c, "minecraft:nether_brick", 32, 10);
        add(c, "minecraft:soul_campfire", 4, 18);
        add(c, "minecraft:soul_lantern", 8, 18);
        add(c, "minecraft:soul_torch", 16, 7);

        // --- the rest of it, which the market never sold ---
        // Fungal wood, priced off the stems it comes from.
        add(c, "minecraft:crimson_planks", 64, 12);
        add(c, "minecraft:warped_planks", 64, 12);
        add(c, "minecraft:stripped_crimson_stem", 32, 12);
        add(c, "minecraft:stripped_warped_stem", 32, 12);
        add(c, "minecraft:crimson_hyphae", 32, 14);
        add(c, "minecraft:warped_hyphae", 32, 14);
        add(c, "minecraft:crimson_fence", 16, 10);
        add(c, "minecraft:warped_fence", 16, 10);
        add(c, "minecraft:crimson_door", 8, 8);
        add(c, "minecraft:warped_door", 8, 8);
        add(c, "minecraft:crimson_trapdoor", 8, 10);
        add(c, "minecraft:warped_trapdoor", 8, 10);
        // Ground cover and the things that grow out of it.
        add(c, "minecraft:crimson_nylium", 16, 14);
        add(c, "minecraft:warped_nylium", 16, 14);
        add(c, "minecraft:crimson_fungus", 8, 10);
        add(c, "minecraft:warped_fungus", 8, 10);
        add(c, "minecraft:crimson_roots", 16, 8);
        add(c, "minecraft:warped_roots", 16, 8);
        add(c, "minecraft:nether_sprouts", 16, 8);
        add(c, "minecraft:weeping_vines", 16, 10);
        add(c, "minecraft:twisting_vines", 16, 10);
        // Brick and blackstone trim.
        add(c, "minecraft:cracked_nether_bricks", 32, 15);
        add(c, "minecraft:nether_brick_fence", 16, 12);
        add(c, "minecraft:chiseled_polished_blackstone", 32, 12);
        add(c, "minecraft:cracked_polished_blackstone_bricks", 32, 12);
        add(c, "minecraft:quartz_pillar", 16, 22);
        // Ore, priced above what smelting it returns.
        add(c, "minecraft:nether_gold_ore", 16, 26);
        add(c, "minecraft:nether_quartz_ore", 16, 18);
        add(c, "minecraft:lodestone", 1, 10);

        // The shapes, at their block's price. Everything a bastion is built
        // of, which is what people come back through the portal wanting.
        shapes(c, "nether_brick", 32, 14, true);
        shapes(c, "red_nether_brick", 32, 16, true);
        shapes(c, "blackstone", 32, 9, true);
        shapes(c, "polished_blackstone", 32, 10, true);
        shapes(c, "polished_blackstone_brick", 32, 12, true);
        shapes(c, "crimson", 64, 12, false);
        shapes(c, "warped", 64, 12, false);
        add(c, "minecraft:crimson_fence_gate", 8, 6);
        add(c, "minecraft:warped_fence_gate", 8, 6);
        add(c, "minecraft:crimson_button", 32, 4);
        add(c, "minecraft:warped_button", 32, 4);
        add(c, "minecraft:crimson_pressure_plate", 16, 5);
        add(c, "minecraft:warped_pressure_plate", 16, 5);
        add(c, "minecraft:crimson_sign", 8, 5);
        add(c, "minecraft:warped_sign", 8, 5);
        add(c, "minecraft:crimson_hanging_sign", 8, 8);
        add(c, "minecraft:warped_hanging_sign", 8, 8);
        add(c, "minecraft:stripped_crimson_hyphae", 32, 14);
        add(c, "minecraft:stripped_warped_hyphae", 32, 14);
    }

    private static void seeds() {
        Category c = FARMING;
        add(c, "minecraft:beetroot_seeds", 16, 12);
        add(c, "minecraft:melon_seeds", 8, 8);
        add(c, "minecraft:pumpkin_seeds", 8, 8);
        add(c, "minecraft:torchflower_seeds", 1, 40);
        add(c, "minecraft:pitcher_pod", 1, 40);
        add(c, "minecraft:carrot", 16, 20);
        add(c, "minecraft:potato", 16, 20);
        add(c, "minecraft:wheat", 16, 40);
        // One beetroot per plant, where a carrot patch gives two to five, so
        // it is priced per plant rather than per item like the rest.
        add(c, "minecraft:beetroot", 16, 34);
        add(c, "minecraft:sugar_cane", 16, 20);
        add(c, "minecraft:cocoa_beans", 16, 18);
        add(c, "minecraft:nether_wart", 16, 30);
        add(c, "minecraft:sweet_berries", 16, 16);
        add(c, "minecraft:glow_berries", 16, 22);
        add(c, "minecraft:bone_meal", 32, 9);
        // Nine wheat in a coat. Priced inside the window that stops it being
        // an uncrafting bench: below 9x wheat's sell price and a bale bought
        // here is nine wheat sold back for more.
        add(c, "minecraft:hay_block", 4, 100);
        add(c, "minecraft:egg", 16, 16);
        add(c, "minecraft:wheat_seeds", 16, 12);

        // --- modded crops. Absent ids drop out at startup. ---
        // Same lift as the vanilla crops above, and for the same reason: these
        // are what the kitchen mods actually want you to grow, and they were
        // priced under a quarter of what the dishes made from them fetch.
        add(c, "farmersdelight:tomato_seeds", 8, 12);
        add(c, "farmersdelight:cabbage_seeds", 8, 12);
        add(c, "farmersdelight:rice", 16, 26);
        add(c, "farmersdelight:tomato", 12, 26);
        add(c, "farmersdelight:cabbage", 8, 20);
        add(c, "farmersdelight:onion", 12, 24);
        add(c, "farmersdelight:straw", 16, 10);
        add(c, "culturaldelights:cucumber_seeds", 8, 12);
        add(c, "culturaldelights:eggplant_seeds", 8, 12);
        add(c, "culturaldelights:corn_kernels", 8, 12);
        add(c, "culturaldelights:cucumber", 12, 24);
        add(c, "culturaldelights:eggplant", 12, 24);
        add(c, "culturaldelights:corn_cob", 12, 26);
        add(c, "culturaldelights:avocado", 8, 26);
        add(c, "culturaldelights:avocado_sapling", 4, 14);
        add(c, "rusticdelight:bell_pepper_seeds", 8, 12);
        add(c, "rusticdelight:cotton_seeds", 8, 12);
        add(c, "rusticdelight:coffee_beans", 12, 30);
        add(c, "rusticdelight:bell_pepper_red", 8, 20);
        add(c, "rusticdelight:bell_pepper_green", 8, 20);
        add(c, "rusticdelight:bell_pepper_yellow", 8, 20);
        add(c, "rusticdelight:cotton_boll", 12, 22);
        add(c, "alcocraftplus:hop_seeds", 8, 14);
    }

    /**
     * Food, priced to be worth farming.
     *
     * These sit on {@link TrapMath#foodPrice}, the same curve every food the
     * registry turns up is priced by, so a loaf of bread and a modded loaf of
     * bread cost the same -- which they emphatically did not, by a factor of
     * four, for as long as there were two ways to get on this shelf.
     *
     * They are hand-written anyway, because the curve reads nutrition and
     * nutrition doesn't know what a recipe costs. A bowl of mushroom stew is
     * two mushrooms and a bowl however filling vanilla says it is, and pricing
     * it off its stomach rather than its shopping list turned a mushroom farm
     * into a mint. Every one of these is bounded by check_stock.py against the
     * real vanilla recipe, which is what the exceptions are for.
     *
     * The luxuries -- golden apples, cake -- keep their old prices; they are
     * already anchored against the top shelf.
     */
    private static void kitchen() {
        Category c = FOOD;
        add(c, "minecraft:bread", 4, 24);
        add(c, "minecraft:cooked_beef", 4, 36);
        add(c, "minecraft:cooked_porkchop", 4, 36);
        add(c, "minecraft:cooked_chicken", 4, 28);
        add(c, "minecraft:cooked_mutton", 4, 30);
        add(c, "minecraft:cooked_rabbit", 4, 25);
        add(c, "minecraft:cooked_salmon", 4, 30);
        add(c, "minecraft:cooked_cod", 4, 25);
        add(c, "minecraft:baked_potato", 8, 22);
        add(c, "minecraft:pumpkin_pie", 4, 36);
        add(c, "minecraft:cake", 1, 14);
        // Eight from two wheat and a cocoa bean, which is the cheapest food in
        // the game to mass produce and priced like it. It was 2e each, and a
        // crafting table full of them out-earned the farm they came from.
        add(c, "minecraft:cookie", 8, 13);
        add(c, "minecraft:apple", 8, 40);
        add(c, "minecraft:melon_slice", 8, 28);
        add(c, "minecraft:dried_kelp", 8, 32);
        add(c, "minecraft:mushroom_stew", 2, 16);
        add(c, "minecraft:rabbit_stew", 2, 56);
        add(c, "minecraft:beetroot_soup", 2, 34);
        add(c, "minecraft:suspicious_stew", 2, 20);
        add(c, "minecraft:golden_carrot", 8, 72);
        add(c, "minecraft:milk_bucket", 1, 12);
        add(c, "minecraft:honey_bottle", 2, 36);
        add(c, "minecraft:sugar", 8, 20);

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
        add(c, "minecraft:raw_copper_block", 2, 12);
        add(c, "minecraft:raw_gold_block", 2, 60);
        add(c, "minecraft:iron_block", 2, 40);
        add(c, "minecraft:gold_block", 2, 70);
        add(c, "minecraft:lapis_block", 4, 34);
        add(c, "minecraft:redstone_block", 4, 30);
        add(c, "minecraft:clay", 16, 10);
        add(c, "minecraft:brick", 32, 10);
        add(c, "minecraft:ender_eye", 4, 70);
        add(c, "minecraft:chorus_fruit", 16, 20);
        add(c, "minecraft:popped_chorus_fruit", 16, 24);
        add(c, "minecraft:glow_lichen", 8, 14);
        add(c, "minecraft:sculk", 16, 16);
        add(c, "minecraft:wither_rose", 4, 60);
        add(c, "minecraft:stick", 64, 4);
        add(c, "minecraft:bowl", 32, 6);
        add(c, "minecraft:slime_block", 4, 40);
        add(c, "minecraft:honey_block", 1, 34);
        add(c, "minecraft:dried_kelp_block", 4, 72);
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
        add(c, "minecraft:raw_copper", 32, 22);
        add(c, "minecraft:copper_ingot", 16, 24);
        add(c, "minecraft:raw_gold", 8, 24);
        add(c, "minecraft:gold_ingot", 8, 26);
        add(c, "minecraft:gold_nugget", 32, 12);
        add(c, "minecraft:redstone", 32, 12);
        add(c, "minecraft:lapis_lazuli", 32, 14);
        add(c, "minecraft:quartz", 32, 18);
        add(c, "minecraft:diamond", 1, 42);
        add(c, "minecraft:amethyst_shard", 8, 40);
        add(c, "minecraft:netherite_scrap", 1, 260);
        add(c, "minecraft:ancient_debris", 1, 300);
        add(c, "minecraft:flint", 16, 24);
        add(c, "minecraft:string", 16, 32);
        add(c, "minecraft:leather", 8, 32);
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
        add(c, "minecraft:echo_shard", 2, 90);
        add(c, "minecraft:glowstone_dust", 16, 12);
        add(c, "minecraft:blaze_powder", 8, 24);
        add(c, "minecraft:fermented_spider_eye", 4, 14);
        add(c, "minecraft:brown_mushroom_block", 8, 8);
        add(c, "minecraft:red_mushroom_block", 8, 8);
        add(c, "minecraft:mushroom_stem", 8, 8);
        add(c, "minecraft:sculk_vein", 16, 14);
        add(c, "minecraft:sculk_sensor", 2, 40);
        add(c, "minecraft:calibrated_sculk_sensor", 2, 60);
        add(c, "minecraft:armadillo_scute", 8, 20);
    }

    private static void utility() {
        Category c = UTILITY;
        add(c, "minecraft:crafter", 1, 40);
        add(c, "minecraft:respawn_anchor", 1, 180);
        add(c, "minecraft:hopper_minecart", 1, 45);
        add(c, "minecraft:chest_minecart", 1, 32);
        add(c, "minecraft:tnt_minecart", 1, 50);
        add(c, "minecraft:furnace_minecart", 1, 30);
        // Five planks and a craft. Priced above that and the shop is buying
        // back more than it sold, which is what a boat did for a long time.
        add(c, "minecraft:oak_boat", 4, 12);
        add(c, "minecraft:oak_chest_boat", 4, 18);
        add(c, "minecraft:crossbow", 1, 16);
        add(c, "minecraft:spectral_arrow", 8, 24);
        add(c, "minecraft:carrot_on_a_stick", 1, 20);
        add(c, "minecraft:warped_fungus_on_a_stick", 1, 22);
        add(c, "minecraft:map", 4, 12);
        add(c, "minecraft:filled_map", 1, 10);
        add(c, "minecraft:writable_book", 4, 14);
        add(c, "minecraft:goat_horn", 1, 70);
        add(c, "minecraft:trial_key", 1, 140);
        add(c, "minecraft:ominous_trial_key", 1, 260);
        add(c, "minecraft:ominous_bottle", 2, 90);
        add(c, "minecraft:mace", 1, 900);
        add(c, "minecraft:heavy_core", 1, 700);
        add(c, "minecraft:lightning_rod", 2, 20);
        add(c, "minecraft:daylight_detector", 4, 20);
        add(c, "minecraft:lectern", 2, 16);
        add(c, "minecraft:fletching_table", 1, 8);
        add(c, "minecraft:stonecutter", 1, 7);
        add(c, "minecraft:campfire", 4, 12);
        add(c, "minecraft:beehive", 2, 20);
        add(c, "minecraft:bundle", 1, 13);
        add(c, "minecraft:activator_rail", 8, 26);
        add(c, "minecraft:firework_rocket", 16, 16);
        add(c, "minecraft:bell", 1, 45);
        add(c, "minecraft:torch", 32, 4);
        add(c, "minecraft:lantern", 8, 14);
        add(c, "minecraft:chest", 4, 8);
        add(c, "minecraft:barrel", 4, 9);
        add(c, "minecraft:furnace", 2, 8);
        add(c, "minecraft:blast_furnace", 1, 16);
        add(c, "minecraft:smoker", 1, 14);
        add(c, "minecraft:crafting_table", 4, 6);
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
        add(c, "minecraft:stone_button", 32, 12);
        add(c, "minecraft:stone_pressure_plate", 16, 12);
        add(c, "minecraft:polished_blackstone_button", 32, 10);
        add(c, "minecraft:polished_blackstone_pressure_plate", 16, 10);
        add(c, "minecraft:heavy_weighted_pressure_plate", 8, 40);
        add(c, "minecraft:light_weighted_pressure_plate", 8, 50);
        add(c, "minecraft:trapped_chest", 4, 10);
        add(c, "minecraft:glass_bottle", 16, 6);
        add(c, "minecraft:target", 4, 12);
        add(c, "minecraft:note_block", 4, 10);
        add(c, "minecraft:jukebox", 1, 24);
        add(c, "minecraft:bucket", 1, 10);
        add(c, "minecraft:water_bucket", 1, 12);
        add(c, "minecraft:lava_bucket", 1, 26);
        add(c, "minecraft:shears", 1, 12);
        add(c, "minecraft:flint_and_steel", 1, 8);
        add(c, "minecraft:compass", 1, 20);
        add(c, "minecraft:clock", 1, 22);
        add(c, "minecraft:spyglass", 1, 13);
        add(c, "minecraft:name_tag", 1, 40);
        add(c, "minecraft:lead", 2, 10);
        add(c, "minecraft:saddle", 1, 30);
        add(c, "minecraft:bookshelf", 4, 26);
        add(c, "minecraft:anvil", 1, 70);
        add(c, "minecraft:grindstone", 2, 3);
        add(c, "minecraft:smithing_table", 1, 11);
        add(c, "minecraft:cartography_table", 4, 8);
        add(c, "minecraft:loom", 1, 9);
        add(c, "minecraft:composter", 4, 4);
        add(c, "minecraft:cauldron", 1, 18);
        add(c, "minecraft:brewing_stand", 1, 24);
        add(c, "minecraft:ender_chest", 1, 90);
        add(c, "minecraft:shulker_box", 1, 150);
        add(c, "minecraft:tnt", 4, 30);
        add(c, "minecraft:bow", 1, 13);
        add(c, "minecraft:arrow", 32, 12);
        add(c, "minecraft:fishing_rod", 1, 9);
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
        add(c, "minecraft:end_crystal", 2, 210);

        // The tools the shelf forgot. It sold a netherite pickaxe, a netherite
        // axe and a netherite sword, and left you digging gravel with diamond.
        // Priced against the ingot, which is the whole cost of the upgrade:
        // nobody buys a netherite shovel who couldn't have made one.
        add(c, "minecraft:netherite_shovel", 1, 1200);
        add(c, "minecraft:netherite_hoe", 1, 1180);
        // Their diamond halves, to match the pickaxe and sword already here.
        add(c, "minecraft:diamond_axe", 1, 190);
        add(c, "minecraft:diamond_shovel", 1, 90);
        add(c, "minecraft:diamond_hoe", 1, 150);
        add(c, "minecraft:diamond_horse_armor", 1, 480);

        // Only the market sells these two. A sniffer egg is a dig site you
        // didn't have to find, and reinforced deepslate cannot be mined at
        // all -- there is no price it undercuts, so the price is the flex.
        add(c, "minecraft:sniffer_egg", 1, 600);
        add(c, "minecraft:reinforced_deepslate", 1, 900);

        // Armour trims. The cheap ones are a hole in the ground you already
        // walked past; silence is thirty ancient city chests.
        trim("sentry", 70);
        trim("dune", 70);
        trim("coast", 70);
        trim("wild", 90);
        trim("snout", 180);
        trim("rib", 200);
        trim("tide", 220);
        trim("wayfinder", 240);
        trim("raiser", 240);
        trim("shaper", 240);
        trim("host", 240);
        trim("eye", 260);
        trim("spire", 300);
        trim("flow", 320);
        trim("bolt", 320);
        trim("ward", 380);
        trim("vex", 520);
        trim("silence", 800);

        // The jukebox set. Pigstep and the rest of the deep cuts are already
        // up there at four hundred; these are the ones a creeper hands you for
        // the price of aiming a skeleton, so they're priced like a night out.
        for (String disc : new String[]{"13", "cat", "blocks", "chirp", "far", "mall",
                "mellohi", "stal", "strad", "ward", "wait", "11"}) {
            add(c, "minecraft:music_disc_" + disc, 1, 150);
        }
        add(c, "minecraft:music_disc_precipice", 1, 420);
        add(c, "minecraft:music_disc_creator_music_box", 1, 440);
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
