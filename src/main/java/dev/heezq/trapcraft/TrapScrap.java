package dev.heezq.trapcraft;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Rarity;

/**
 * What the counter will pay for something, and what it won't touch.
 *
 * The shelves are a curated list; this is the answer for everything else. A
 * listed item is priced by the market and moves with it. Anything else gets
 * valued from what the game says about it -- how filling, how rare, how much
 * use is in it -- which works for a modded ingredient nobody has heard of as
 * well as for a stick.
 */
public final class TrapScrap {

    private TrapScrap() {
    }

    /**
     * Why the counter is refusing something, or null if it isn't.
     *
     * Returns the reason rather than a bare boolean because the player is owed
     * one: an item handed back with no explanation reads as a bug.
     */
    public static String refusal(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        if (stack.isOf(Items.EMERALD) || stack.isOf(Items.EMERALD_BLOCK)) {
            return "to SĄ pieniądze";
        }
        if (stack.isOf(TrapContent.wallet)) {
            return "w środku są pieniądze";
        }
        if (stack.isOf(TrapContent.burnerPhone)) {
            return "masz tam swoje kontakty";
        }
        // Whoever holds the card owns the casino, so a stall that bought one
        // for scrap would have bought the business -- and then thrown it away.
        if (stack.isOf(TrapContent.casinoCard)) {
            return "to całe kasyno";
        }
        // The wands are the one thing the market sells and will not take back.
        // Every one of them can be crafted from a nether star or a recovery
        // compass, and buying that at 1,400e to sell the wand made from it at
        // half of eighteen thousand is a crafting table with a mint attached.
        if (stack.getItem() instanceof WandItem) {
            return "różdżki nie wracają na półkę";
        }
        // A shulker box or bundle with something inside would sell its contents
        // invisibly at the price of the empty container. Refuse rather than
        // quietly eat whatever is in it.
        var packed = stack.get(DataComponentTypes.CONTAINER);
        if (packed != null && packed.stream().findAny().isPresent()) {
            return "empty it out first";
        }
        if (stack.isDamaged()) {
            return "nobody wants it broken";
        }
        return null;
    }

    /**
     * What this WHOLE STACK fetches, in emeralds.
     *
     * Per stack rather than per item on purpose. Ordinary junk is worth a
     * fraction of an emerald each, and rounding that up to one apiece would
     * make a stick worth more than its share of the log it came from -- eight
     * sticks a log, and an afternoon at a crafting table would out-earn every
     * other way of making money in this mod.
     *
     * A listed item is worth its market sell price, so the shelves and the
     * counter never quote different numbers for the same thing. Everything
     * else falls through to {@link TrapMath#scrapPrice}.
     *
     * @return emeralds for the stack, or 0 if it is worth nothing
     */
    public static int priceOf(MinecraftServer server, ItemStack stack) {
        if (stack.isEmpty() || refusal(stack) != null) {
            return 0;
        }

        ShopStock.Entry listed = ShopStock.matching(stack);
        if (listed != null) {
            // The shelf deals in lots; the counter takes any number, so pro
            // rata. Never MORE than the shelf pays, or the two disagree and
            // the cheaper one becomes a money printer.
            int lot = TrapMarket.sellPrice(server, listed);
            return Math.round(lot * stack.getCount() / (float) Math.max(1, listed.count()));
        }

        var enchanted = stack.get(DataComponentTypes.STORED_ENCHANTMENTS);
        if (enchanted != null && !enchanted.isEmpty()) {
            int levels = 0;
            for (var entry : enchanted.getEnchantmentEntries()) {
                levels += entry.getIntValue();
            }
            return TrapMath.scrapBookPrice(levels, enchanted.getSize()) * stack.getCount();
        }

        FoodComponent food = stack.get(DataComponentTypes.FOOD);
        float each = TrapMath.scrapPrice(
                food == null ? 0 : food.nutrition(),
                food == null ? 0.0f : food.saturation(),
                rarityStep(stack),
                stack.getOrDefault(DataComponentTypes.MAX_DAMAGE, 0),
                stack.getMaxCount());
        return Math.round(each * stack.getCount());
    }

    private static int rarityStep(ItemStack stack) {
        Rarity rarity = stack.getRarity();
        return switch (rarity) {
            case UNCOMMON -> 1;
            case RARE -> 2;
            case EPIC -> 3;
            default -> 0;
        };
    }
}
