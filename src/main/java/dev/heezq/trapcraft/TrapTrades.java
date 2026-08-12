package dev.heezq.trapcraft;

import net.fabricmc.fabric.api.object.builder.v1.trade.TradeOfferHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Identifier;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TradeOffers;
import net.minecraft.village.TradedItem;
import net.minecraft.village.VillagerProfession;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The supply chain's two ends.
 *
 * ponytail: no custom villager profession. VillagerProfession is a final record
 * in 1.21.8, so it cannot implement Polymer's PolymerVillagerProfession marker,
 * and pushing an unknown profession id at a vanilla client risks a registry
 * desync on join. Wandering traders and farmers are both supported Fabric APIs
 * and cost nothing on the client. If a dedicated dealer villager ever matters
 * more than guaranteed vanilla joins, that's the trade to revisit.
 */
public final class TrapTrades {
    private TrapTrades() {
    }

    private static TradeOffers.Factory sell(ItemStack result, int emeraldCost, int maxUses, int xp) {
        return (entity, random) -> new TradeOffer(
                new TradedItem(Items.EMERALD, emeraldCost), Optional.empty(),
                result, maxUses, xp, 0.05F);
    }

    private static TradeOffers.Factory buy(net.minecraft.item.Item wanted, int count, int emeralds,
                                           int maxUses, int xp) {
        return (entity, random) -> new TradeOffer(
                new TradedItem(wanted, count), Optional.empty(),
                new ItemStack(Items.EMERALD, emeralds), maxUses, xp, 0.05F);
    }

    /** Buys only stacks carrying exactly this grade, and pays accordingly. */
    private static TradeOffers.Factory buyGraded(net.minecraft.item.Item wanted, Quality grade,
                                                 int count, int emeralds) {
        var predicate = net.minecraft.predicate.component.ComponentMapPredicate.of(
                TrapComponents.quality, grade.index());
        return (entity, random) -> new TradeOffer(
                new TradedItem(net.minecraft.registry.Registries.ITEM.getEntry(wanted), count, predicate),
                Optional.empty(),
                new ItemStack(Items.EMERALD, emeralds), 6, 2, 0.05F);
    }

    /** Same idea as buyGraded, but keyed on the purity component. */
    private static TradeOffers.Factory buyPurity(net.minecraft.item.Item wanted, Purity purity,
                                                 int count, int emeralds) {
        var predicate = net.minecraft.predicate.component.ComponentMapPredicate.of(
                TrapComponents.purity, purity.index());
        return (entity, random) -> new TradeOffer(
                new TradedItem(net.minecraft.registry.Registries.ITEM.getEntry(wanted), count, predicate),
                Optional.empty(),
                new ItemStack(Items.EMERALD, emeralds), 4, 3, 0.05F);
    }

    public static void register() {
        // The plug: wanders in, sells starts, takes finished product off your hands.
        TradeOfferHelper.registerWanderingTraderOffers(builder -> {
            List<TradeOffers.Factory> sells = new ArrayList<>();
            List<TradeOffers.Factory> buys = new ArrayList<>();
            for (Strain strain : Strain.values()) {
                sells.add(sell(new ItemStack(TrapContent.seeds(strain), 2), 5, 3, 1));
                // A separate offer per grade, gated by the quality component:
                // Swill fetches 1 emerald for 4 buds, Fire fetches 7. That
                // spread is the whole economic argument for growing properly.
                for (Quality grade : Quality.values()) {
                    buys.add(buyGraded(TrapContent.driedBud(strain), grade, 4, grade.emeralds()));
                    buys.add(buyGraded(TrapContent.joint(strain), grade, 2, grade.emeralds()));
                }
            }
            // One random seed offer per trader so a single visit can't stock you
            // with every strain -- finding all three should take a few spawns.
            // Coca seeds cost more than any strain: the payoff is far higher,
            // and the chain is long enough that starting it should be a choice.
            sells.add(sell(new ItemStack(TrapContent.cocaSeeds, 1), 14, 2, 2));
            // Poppy seed is the dearest thing on the cart and the scarcest --
            // one per visit, twice. Starting the long line should be a decision
            // you had to save up for, and the plant reseeds itself often enough
            // that you only ever have to make it once.
            sells.add(sell(new ItemStack(TrapContent.poppySeeds, 1), 26, 1, 3));
            for (Purity purity : Purity.values()) {
                buys.add(buyPurity(TrapContent.cocaPowder, purity, 2, purity.emeralds()));
                buys.add(buyPurity(TrapContent.heroin, purity, 1,
                        Math.round(purity.emeralds() * Drug.DOPE.priceScale())));
            }

            builder.pool(Identifier.of(TrapCraft.MOD_ID, "plug_sells"), 1, sells);
            builder.pool(Identifier.of(TrapCraft.MOD_ID, "plug_buys"), 2, buys);
        });

        villagers();
    }

    /**
     * A permanent buyer, at a price.
     *
     * Three ways to move product now, and they're deliberately ranked by how
     * much work they are. A {@link TrapDealing} customer walks to you and pays
     * ~1.9x, but turns up when it feels like it. A wandering trader pays the
     * base rate and you have to find one. A villager pays {@link #VILLAGER_RATE}
     * -- less than either -- but it stands where you put it, restocks twice a
     * day, and never leaves. Convenience costs margin, which is the whole
     * reason to have all three rather than one.
     *
     * Split by profession so the two halves of the chain have different homes:
     * farmers handle the field (seeds in, raw crop out), clerics handle the
     * finished article and the reagents that make it.
     */
    private static void villagers() {
        // Grades are split across villager LEVELS rather than piled into one.
        //
        // Six strains times four grades is 24 combinations, and a villager only
        // ever offers a couple of entries from its level's pool -- so dumping
        // all 24 in at once would mean rerolling a workstation dozens of times
        // to find someone who buys the Fire Purp you actually have. Two grades
        // per level keeps each pool at twelve, and it reads as a career: a
        // novice will take your swill, a master pays for the good stuff.
        Quality[] low = {Quality.SWILL, Quality.MIDS};
        Quality[] high = {Quality.LOUD, Quality.FIRE};

        // --- farmer: the field end -------------------------------------------
        // An entry point that doesn't depend on a wandering trader ever
        // spawning. Finding your first seed shouldn't be down to luck.
        TradeOfferHelper.registerVillagerOffers(VillagerProfession.FARMER, 1, factories -> {
            for (Strain strain : Strain.values()) {
                factories.add(sell(new ItemStack(TrapContent.seeds(strain), 1), 10, 2, 1));
            }
        });
        // Raw buds, graded. They were a flat 8-for-1 regardless of quality,
        // which quietly said "how you grew it doesn't matter until you cure it"
        // -- the opposite of what the grading system is for.
        TradeOfferHelper.registerVillagerOffers(VillagerProfession.FARMER, 2,
                factories -> rawBuds(factories, low));
        TradeOfferHelper.registerVillagerOffers(VillagerProfession.FARMER, 3,
                factories -> rawBuds(factories, high));
        TradeOfferHelper.registerVillagerOffers(VillagerProfession.FARMER, 4, factories -> {
            for (Strain strain : Strain.values()) {
                factories.add(sell(new ItemStack(TrapContent.seeds(strain), 1), 8, 2, 1));
            }
            // Paper by the stack: rolling is the one step with a vanilla input,
            // and running out mid-batch is friction rather than a decision.
            factories.add(sell(new ItemStack(Items.PAPER, 16), 3, 12, 2));
        });

        // --- cleric: the finished article ------------------------------------
        // Thematically the right villager for this, and mechanically the one
        // players already build halls of.
        TradeOfferHelper.registerVillagerOffers(VillagerProfession.CLERIC, 1,
                factories -> curedBuds(factories, low));
        TradeOfferHelper.registerVillagerOffers(VillagerProfession.CLERIC, 2,
                factories -> curedBuds(factories, high));
        TradeOfferHelper.registerVillagerOffers(VillagerProfession.CLERIC, 3, factories -> {
            // Only the good grades get rolled for sale -- nobody bothers
            // papering up swill, and it keeps this pool the same size as the rest.
            for (Strain strain : Strain.values()) {
                for (Quality grade : high) {
                    factories.add(buyGraded(TrapContent.joint(strain), grade, 2,
                            villagerRate(grade.emeralds())));
                }
            }
            // Reagents. Blaze powder otherwise means a nether trip every time
            // you want to refine, which gates the second product line behind an
            // errand rather than behind skill.
            factories.add(sell(new ItemStack(Items.BLAZE_POWDER, 3), 6, 8, 3));
            factories.add(sell(new ItemStack(Items.GLASS_BOTTLE, 6), 2, 12, 2));
        });
        TradeOfferHelper.registerVillagerOffers(VillagerProfession.CLERIC, 4, factories -> {
            for (Purity purity : Purity.values()) {
                factories.add(buyPurity(TrapContent.cocaPowder, purity, 2,
                        villagerRate(purity.emeralds())));
                factories.add(buyPurity(TrapContent.heroin, purity, 1,
                        villagerRate(Math.round(purity.emeralds() * Drug.DOPE.priceScale()))));
            }
            factories.add(buy(TrapContent.cocaPaste, 4, 3, 8, 3));
            factories.add(buy(TrapContent.morphineBase, 1, 6, 6, 3));
            // The acid, for the same reason blaze powder is on the level below:
            // the third line should be gated by skill and patience, not by how
            // recently a spider dropped an eye.
            factories.add(sell(new ItemStack(Items.FERMENTED_SPIDER_EYE, 2), 8, 6, 3));
        });
    }

    private static void rawBuds(List<TradeOffers.Factory> factories, Quality[] grades) {
        for (Strain strain : Strain.values()) {
            for (Quality grade : grades) {
                // Eight raw for what four cured fetch: curing is worth doing.
                factories.add(buyGraded(TrapContent.rawBud(strain), grade, 8,
                        villagerRate(grade.emeralds())));
            }
        }
    }

    private static void curedBuds(List<TradeOffers.Factory> factories, Quality[] grades) {
        for (Strain strain : Strain.values()) {
            for (Quality grade : grades) {
                factories.add(buyGraded(TrapContent.driedBud(strain), grade, 4,
                        villagerRate(grade.emeralds())));
            }
        }
    }

    /** What a stationary, restocking buyer pays: less than anyone you have to find. */
    private static int villagerRate(int base) {
        return Math.max(1, Math.round(base * VILLAGER_RATE));
    }

    private static final float VILLAGER_RATE = 0.8F;
}
