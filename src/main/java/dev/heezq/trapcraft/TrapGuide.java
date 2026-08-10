package dev.heezq.trapcraft;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.WrittenBookContentComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.RawFilteredPair;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

/**
 * The in-game reference, as a vanilla written book -- no client mod, no
 * textures, and it survives being dropped in a chest for the next person.
 *
 * Every NUMBER on these pages is read from the constant that actually governs
 * it: grades from Quality, the curing window from DryingRackBlock, tolerance
 * from ToleranceStatusEffect, heat from TrapHeat, breeding pairs from
 * Strain.hybridOf. Prose is hand-written, figures are not. Retune anything and
 * the book retunes with it, so it can never quietly start lying -- which is the
 * whole point of it being a knowledge base rather than a leaflet.
 */
public final class TrapGuide {
    private TrapGuide() {
    }

    /**
     * One command, five books.
     *
     * Plain Brigadier literals rather than an argument with a
     * SuggestionProvider: literals tab-complete and produce a sensible error
     * on a typo for free, and three separate top-level commands was already
     * two too many to remember.
     */
    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, access, env) ->
                dispatcher.register(CommandManager.literal("guide")
                        .executes(context -> menu(context.getSource()))
                        .then(CommandManager.literal("grower")
                                .executes(context -> give(context.getSource(), createWeed())))
                        .then(CommandManager.literal("refiner")
                                .executes(context -> give(context.getSource(), createCoca())))
                        .then(CommandManager.literal("street")
                                .executes(context -> give(context.getSource(), createStreet())))
                        .then(CommandManager.literal("crew")
                                .executes(context -> give(context.getSource(), createCrew())))
                        .then(CommandManager.literal("casino")
                                .executes(context -> give(context.getSource(), createCasino())))));
    }

    /** Bare /guide lists them rather than erroring at you. */
    private static int menu(net.minecraft.server.command.ServerCommandSource source) {
        source.sendFeedback(() -> Text.empty()
                .append(Text.literal("The Trap House\n").formatted(Formatting.DARK_GREEN, Formatting.BOLD))
                .append(pick("grower", "growing, curing, rolling, heat"))
                .append(pick("refiner", "the coca line"))
                .append(pick("street", "paranoia, the ledger, contracts"))
                .append(pick("crew", "hiring hands, and what they cost"))
                .append(pick("casino", "running a floor")), false);
        return 1;
    }

    private static MutableText pick(String type, String blurb) {
        return Text.literal("  /guide " + type)
                .formatted(Formatting.GREEN)
                .styled(style -> style
                        .withClickEvent(new net.minecraft.text.ClickEvent.RunCommand("/guide " + type))
                        .withHoverEvent(new net.minecraft.text.HoverEvent.ShowText(
                                Text.literal("Get the " + type + "'s handbook"))))
                .append(Text.literal("  " + blurb + "\n").formatted(Formatting.DARK_GRAY));
    }

    private static int give(net.minecraft.server.command.ServerCommandSource source, ItemStack book) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            source.sendFeedback(() -> Text.literal("players only"), false);
            return 0;
        }
        if (!player.giveItemStack(book)) {
            player.dropItem(book, false);
        }
        return 1;
    }

    /**
     * Two books, not one.
     *
     * They're separate product lines with separate mechanics, and a single
     * volume had grown to 19 pages where half was irrelevant to whatever you
     * were actually doing. Splitting also means each stays inside a length
     * anybody will actually read.
     */
    public static ItemStack createWeed() {
        List<RawFilteredPair<Text>> pages = new ArrayList<>();
        cover(pages);
        growing(pages);
        grading(pages);
        curing(pages);
        rolling(pages);
        methods(pages);
        baked(pages);
        strains(pages);
        breeding(pages);
        heat(pages);
        checking(pages);
        crew(pages);
        network(pages);
        supply(pages);
        return book("Grower's Handbook", pages);
    }

    public static ItemStack createCoca() {
        List<RawFilteredPair<Text>> pages = new ArrayList<>();
        cocaCover(pages);
        coca(pages);
        return book("Refiner's Handbook", pages);
    }

    /**
     * The fourth book: the people who work for you.
     *
     * Split out for the same reason the casino was. The crew used to be three
     * pages in the middle of the grower's handbook, back when a hand did one
     * thing and cost one number. It is now a ladder, a patch and five jobs
     * that each move the wage, and that is a decision worth ten pages -- but
     * only if somebody can find them.
     */
    public static ItemStack createCrew() {
        List<RawFilteredPair<Text>> pages = new ArrayList<>();
        pages.add(page(Text.empty()
                .append(title("THE CREW"))
                .append(Text.literal("\nforeman's handbook\n\n")
                        .formatted(Formatting.DARK_GRAY, Formatting.ITALIC))
                .append(body("Hands work a patch, take a wage, and walk if you "
                        + "can't make it.\n\n"))
                .append(hint("Growing: /guide grower"))));
        crewBook(pages);
        return book("The Crew", pages);
    }

    /**
     * The fifth book: running a floor.
     *
     * Split out because the casino stopped being a machine you place and
     * became a business with staff, running costs, a reputation and a
     * maintenance schedule -- and eleven pages of that buried in the middle of
     * the street handbook is eleven pages nobody finds.
     */
    public static ItemStack createCasino() {
        List<RawFilteredPair<Text>> pages = new ArrayList<>();
        pages.add(page(Text.empty()
                .append(title("THE HOUSE"))
                .append(Text.literal("\ncasino handbook\n\n")
                        .formatted(Formatting.DARK_GRAY, Formatting.ITALIC))
                .append(body("Seven games, a vault, and a name you can lose "
                        + "in an evening.\n\n"))
                .append(hint("Machines: /guide street"))));
        casino(pages);
        return book("The House", pages);
    }

    private static void casino(List<RawFilteredPair<Text>> pages) {
        pages.add(page(Text.empty()
                .append(title("1. OPENING UP\n\n"))
                .append(body("Craft a "))
                .append(item("Casino Licence"))
                .append(body(" and right-click the air.\n\n"))
                .append(body("Name it in an anvil first and the house takes "
                        + "that name.\n\n"))
                .append(hint("Whoever holds it owns the place."))));

        pages.add(page(Text.empty()
                .append(title("2. WIRING UP\n\n"))
                .append(body("Right-click any machine holding the card and it "
                        + "pays into your vault.\n\n"))
                .append(body("Every loss lands there. Every win comes out of "
                        + "it.\n\n"))
                .append(hint("Right-click again to cut it loose."))));

        pages.add(page(Text.empty()
                .append(title("3. THE FLOAT\n\n"))
                .append(body("A machine won't take a bet it can't pay off.\n\n"))
                .append(body("Keep " + TrapMath.FLOAT_PER_MACHINE
                        + "e a machine behind them. It's most of your "
                        + "name as well as your table limit.\n\n"))
                .append(warn("Thin vault, small tables, no trade."))));

        pages.add(page(Text.empty()
                .append(title("4. WHAT IT COSTS\n\n"))
                .append(body("Every machine: " + TrapMath.MACHINE_UPKEEP
                        + "e every 30s, lit or not.\n\n"))
                .append(body("Somebody takes "
                        + Math.round(TrapMath.PROTECTION_RATE * 100)
                        + "% of everything played, win or lose.\n\n"))
                .append(warn("Miss the cut three times and they visit."))));

        pages.add(page(Text.empty()
                .append(title("4b. THE BAR\n\n"))
                .append(body("Wire a "))
                .append(item("Bar"))
                .append(body(" to the house and stock it. "
                        + "Everybody through the door gets one.\n\n"))
                .append(warn("Dry bar: one go each and out."))));

        pages.add(page(Text.empty()
                .append(title("4b2. WHAT TO STOCK\n\n"))
                .append(body("Anything edible. Anything you grew.\n\n"))
                .append(body("Your own product keeps them at the machines "
                        + "far longer than bread does.\n\n"))
                .append(hint("This is what the farm is for."))));

        pages.add(page(Text.empty()
                .append(title("5. THE PUNTERS\n\n"))
                .append(body("Villagers wander in and play with their own "
                        + "money.\n\n"))
                .append(body("Busy after dark. At noon they're at work.\n\n"))
                .append(hint("/floor shows the room."))));

        pages.add(page(Text.empty()
                .append(title("5b. THE ROOM\n\n"))
                .append(body("Quiet floor, big bets -- up to "
                        + TrapMath.PUNTER_MAX_STAKE + "e.\n\n"))
                .append(body("Packed floor, " + TrapMath.PUNTER_MIN_STAKE
                        + "e a go. But a lot of them.\n\n"))
                .append(hint("One machine, one player."))));

        pages.add(page(Text.empty()
                .append(title("6. YOUR NAME\n\n"))
                .append(body("Different games. A full vault. A machine free "
                        + "when somebody walks in.\n\n"))
                .append(warn("A queue at the door costs you most.\n\n"))
                .append(hint("It falls twice as fast as it climbs."))));

        pages.add(page(Text.empty()
                .append(title("6b. THE REGULARS\n\n"))
                .append(body("Held up by trade, forgotten in the quiet.\n\n"))
                .append(body("Half an hour of nothing and they're gone.\n\n"))
                .append(hint("Nobody keeps it at 100."))));

        pages.add(page(Text.empty()
                .append(title("7. WEAR\n\n"))
                .append(body("Machines break. A broken one takes no bets.\n\n"))
                .append(body("Hit it with a "))
                .append(item("Miner's Hammer"))
                .append(body(". The house pays.\n\n"))
                .append(hint("Cheaper before it goes."))));

        pages.add(page(Text.empty()
                .append(title("8. A PIT BOSS\n\n"))
                .append(body(TrapMath.PIT_BOSS_HIRE + "e up front, "
                        + TrapMath.PIT_BOSS_WAGE + "e a beat.\n\n"))
                .append(body("Without one the staff skim and about one punter "
                        + "in " + Math.round(1 / TrapMath.CHEAT_CHANCE)
                        + " is counting.\n\n"))
                .append(hint("A wage is flat. A cut isn't."))));

        pages.add(page(Text.empty()
                .append(title("9. STANDING A ROUND\n\n"))
                .append(body(TrapMath.COMP_COST_PER_MACHINE + "e a machine, "
                        + "straight out of the vault.\n\n"))
                .append(body("Buys +" + TrapMath.COMP_ADDICTION
                        + " regulars and nothing else.\n\n"))
                .append(hint("That is what a comp is."))));

        pages.add(page(Text.empty()
                .append(title("10. RUNNING LOOSE\n\n"))
                .append(body("The machines pay over the odds for "
                        + TrapMath.LOOSE_BEATS / 2 + " minutes.\n\n"))
                .append(body("+" + TrapMath.LOOSE_REP_BONUS + " to your name, "
                        + "and the regulars build twice as fast.\n\n"))
                .append(warn("You lose money. That's the point."))));

        pages.add(page(Text.empty()
                .append(title("11. THE NUMBERS\n\n"))
                .append(body("The villagers hand over about 3%. The lights "
                        + "eat most of it.\n\n"))
                .append(body("Your own play is kept out of the books. It's "
                        + "your money in a circle.\n\n"))
                .append(warn("A bad night really can lose money."))));
    }

    /**
     * The third book: everything that isn't a product line.
     *
     * Paranoia, the Ledger and Contracts all cut across weed and coca both, so
     * putting them in either handbook would have meant writing them twice or
     * hiding them in the wrong one.
     */
    public static ItemStack createStreet() {
        List<RawFilteredPair<Text>> pages = new ArrayList<>();
        streetCover(pages);
        paranoia(pages);
        ledger(pages);
        contracts(pages);
        market(pages);
        street(pages);
        return book("Street Handbook", pages);
    }

    private static void streetCover(List<RawFilteredPair<Text>> pages) {
        pages.add(page(Text.empty()
                .append(title("THE TRAP HOUSE"))
                .append(Text.literal("\nstreet handbook\n\n")
                        .formatted(Formatting.DARK_GRAY, Formatting.ITALIC))
                .append(body("Nerves, inventory, and work. None of it cares which "
                        + "product you're moving.\n\n"))
                .append(hint("Growing: /guide grower\nRefining: /guide refiner"))));
    }

    private static void paranoia(List<RawFilteredPair<Text>> pages) {
        pages.add(page(Text.empty()
                .append(title("1. PARANOIA\n\n"))
                .append(body("Heat brings more than pillagers. It gets in your head.\n\n"))
                .append(body("Watch the Nerves bar at the top of the screen.\n\n"))
                .append(hint("None of it is real."))));

        // Thresholds read from the code, so retuning the meter retunes the book.
        MutableText tiers = Text.empty().append(title("1b. HOW BAD\n\n"));
        String[] words = {"noises behind you", "footsteps, phantom light",
                "blocks that aren't", "someone watching"};
        for (int tier = 0; tier < TrapParanoia.TIERS.length; tier++) {
            tiers.append(body(TrapParanoia.TIERS[tier] + ": " + words[tier] + "\n"));
        }
        pages.add(page(tiers.append(body("\n"))
                .append(hint("Out of " + (int) TrapParanoia.MAX + "."))));

        pages.add(page(Text.empty()
                .append(title("1c. WHAT HELPS\n\n"))
                .append(body("Daylight. Torches. Sobriety.\n\n"))
                .append(body("Better: a friend within "
                        + TrapParanoia.COMPANY_RANGE + " blocks.\n\n"))
                .append(item("Nerve Tonic"))
                .append(body(" clears it "
                        + TrapContent.NerveTonicItem.CALM_TICKS / 20 + "s.\n\n"))
                .append(hint("Honey, sugar, flower."))));

        pages.add(page(Text.empty()
                .append(title("1d. ENOUGH\n\n"))
                .append(body("Not for you? Turn it off:\n\n"))
                .append(item("/paranoia\n\n"))
                .append(body("Per player. Nobody else.\n\n"))
                .append(hint("Also clear for a minute after respawn."))));
    }

    private static void market(List<RawFilteredPair<Text>> pages) {
        pages.add(page(Text.empty()
                .append(title("4. THE MARKET\n\n"))
                .append(body("Build a stall and somebody will trade with you.\n\n"))
                .append(item("Wool Wool Wool\nLog  Emrld Log\nLog  Log  Log\n\n"))
                .append(hint("Emerald block in the middle."))));

        pages.add(page(Text.empty()
                .append(title("4z. THE BOOKS\n\n"))
                .append(body("Every emerald earned is written down, tagged "
                        + "with the job it came from.\n\n"))
                .append(body("/earnings  -  today, everybody.\n\n"))
                .append(hint("Full history in the world folder."))));

        pages.add(page(Text.empty()
                .append(title("4a. YOUR STALL\n\n"))
                .append(body("Put a chest UNDER the one you placed. The "
                        + "contents go on sale.\n\n"))
                .append(body("Others pay " + Math.round(TrapMath.STALL_RATE * 100)
                        + "%. Right-click yours for the till.\n\n"))
                .append(hint("/stalls finds everybody else's."))));

        pages.add(page(Text.empty()
                .append(title("4a2. WHY BOTHER\n\n"))
                .append(body("The counter pays you "
                        + Math.round(TrapMath.SELL_RATE * 100)
                        + "% and charges them 100%.\n\n"))
                .append(body("Through a stall they pay "
                        + Math.round(TrapMath.STALL_RATE * 100) + "% and you keep "
                        + Math.round((TrapMath.STALL_RATE
                        - TrapMath.STALL_RATE * TrapMath.STALL_FEE) * 100) + "%.\n\n"))
                .append(hint("You both do better. Nobody loses."))));

        pages.add(page(Text.empty()
                .append(title("4b. PRICES\n\n"))
                .append(body("They step every 30 seconds. Watch and you'll "
                        + "see them move.\n\n"))
                .append(body("Each item walks its own path.\n\n"))
                .append(hint("Nobody is quoted a different price to you."))));

        pages.add(page(Text.empty()
                .append(title("4c. THE INDEX\n\n"))
                .append(body("More emeralds about means dearer everything.\n\n"))
                .append(body("Spending and losing take them out. Getting "
                        + "paid puts them in.\n\n"))
                .append(hint("Chests count. Hoarding is not hiding."))));

        pages.add(page(Text.empty()
                .append(title("4c2. THE WALLET\n\n"))
                .append(item("String Nugget String\nLeather Emrld Leather\nLeather Leather Leather\n\n"))
                .append(body("Holds any amount in one slot.\n\n"))
                .append(hint("Right-click it to open."))));

        pages.add(page(Text.empty()
                .append(title("4c3. BANKING\n\n"))
                .append(body("One button puts every emerald you're carrying "
                        + "away. Buttons take it back out.\n\n"))
                .append(body("Blocks count as nine, both ways.\n\n"))
                .append(warn("Money in it still spends. Shops take it."))));

        pages.add(page(Text.empty()
                .append(title("4d. ORDER FLOW\n\n"))
                .append(body("Buying pushes that price up. Selling pushes "
                        + "it down. At once.\n\n"))
                .append(body("Clear a shelf and the last lot costs more "
                        + "than the first.\n\n"))
                .append(warn("It fades. Come back later."))));

        pages.add(page(Text.empty()
                .append(title("4d2. THE COUNTER\n\n"))
                .append(body("The hopper on the shopfront takes anything, "
                        + "not just what's listed.\n\n"))
                .append(body("Tip it all in, sell in one go.\n\n"))
                .append(hint("Shift-click fills it fast."))));

        pages.add(page(Text.empty()
                .append(title("4d3. WHAT IT PAYS\n\n"))
                .append(body("Listed goods fetch the market price.\n\n"))
                .append(body("Anything else is valued on the spot, at "
                        + Math.round(TrapMath.SCRAP_RATE * 100) + "%.\n\n"))
                .append(warn("Junk is worth pennies. Sell it by the stack."))));

        pages.add(page(Text.empty()
                .append(title("4d4. REFUSALS\n\n"))
                .append(body("It won't take money, a full wallet, a loaded "
                        + "shulker, or damaged gear.\n\n"))
                .append(body("Those come straight back.\n\n"))
                .append(hint("It tells you why in chat."))));

        pages.add(page(Text.empty()
                .append(title("4e. DEALING\n\n"))
                .append(body("Click to buy a lot. Shift for four.\n\n"))
                .append(body("Right-click to sell one back.\n\n"))
                .append(warn("They buy at about a third of what they sell for."))));

        pages.add(page(Text.empty()
                .append(title("4f. THE EXCHANGE\n\n"))
                .append(body("Put emeralds away for a day, three, or a week.\n\n"))
                .append(body("You get more back if the index rose while you waited.\n\n"))
                .append(warn("And less if it fell. No early withdrawals."))));

        pages.add(page(Text.empty()
                .append(title("4g. LUCKY STREAK\n\n"))
                .append(body("A two-block cabinet. Needs headroom.\n\n"))
                .append(body("Lines, blocks, crosses, stars, Zs, corners.\n\n"))
                .append(hint("Winning squares glow. No glow, no win."))));

        pages.add(page(Text.empty()
                .append(title("4g2. COMBOS\n\n"))
                .append(body("Separate wins on one board both pay.\n\n"))
                .append(body("Three diamonds across and three stars down is "
                        + "two wins, not one.\n\n"))
                .append(hint("A shape pays once, not for its own lines too."))));

        pages.add(page(Text.empty()
                .append(title("4h. ROULETTE\n\n"))
                .append(item("Gold  Iron  Gold\nGreen Green Green\nPlank Plank Plank\n\n"))
                .append(body("A table, not a cabinet. Green wool.\n\n"))
                .append(hint("Any planks will do."))));

        pages.add(page(Text.empty()
                .append(title("4h2. THE FELT\n\n"))
                .append(body("Click a number or an outside bet to put a chip "
                        + "down. As many as you like.\n\n"))
                .append(body("Right-click takes one back.\n\n"))
                .append(hint("The chip button sets how much each click is."))));

        pages.add(page(Text.empty()
                .append(title("4h3. ONE ZERO\n\n"))
                .append(body("A number pays " + (TrapMath.ROULETTE_STRAIGHT - 1)
                        + " to 1. Red, black, odd, even and the halves pay "
                        + "even money.\n\n"))
                .append(warn("Zero takes every outside bet. That is the "
                        + "whole edge."))));

        pages.add(page(Text.empty()
                .append(title("4h4. THE SAME BET\n\n"))
                .append(body("Every bet on the table returns "
                        + Math.round(TrapMath.rouletteReturnToPlayer("red") * 100)
                        + "%.\n\n"))
                .append(body("Straight up or flat on red, the edge is "
                        + "identical.\n\n"))
                .append(hint("Shift-click SPIN repeats your last bet."))));

        pages.add(page(Text.empty()
                .append(title("4i. THE DROP\n\n"))
                .append(item("Plank Iron  Plank\nGlass Dmnd  Glass\nPlank Iron  Plank\n\n"))
                .append(body("Two blocks tall. Needs headroom.\n\n"))
                .append(hint("A ball, some pegs, nine slots."))));

        pages.add(page(Text.empty()
                .append(title("4i2. THE ODDS\n\n"))
                .append(body("Eight bounces, all coin flips. Nothing "
                        + "is decided first.\n\n"))
                .append(body("The middle catches 70 in 256 and pays "
                        + "least. Each edge catches one.\n\n"))
                .append(warn("You can see the odds. That's the point."))));

        pages.add(page(Text.empty()
                .append(title("4j. THE CLIMB\n\n"))
                .append(item("Iron  Iron  Iron\nGold  Hook  Gold\nIron  Iron  Iron\n\n"))
                .append(body("A strongbox with three locks.\n\n"))
                .append(hint("Six rungs. One bad door on each."))));

        pages.add(page(Text.empty()
                .append(title("4j2. WHEN TO STOP\n\n"))
                .append(body("Open a door. Survive and you may climb "
                        + "again, or take the money.\n\n"))
                .append(body("Every rung has the same edge.\n\n"))
                .append(warn("So there is no clever height. Only nerve."))));

        pages.add(page(Text.empty()
                .append(title("4j3. TWO LADDERS\n\n"))
                .append(body("Steady: four doors, tops out at "
                        + Math.round(TrapMath.climbMultiplier(0, TrapMath.CLIMB_RUNGS))
                        + "x.\n\n"))
                .append(body("Reckless: three doors, tops out at "
                        + Math.round(TrapMath.climbMultiplier(1, TrapMath.CLIMB_RUNGS))
                        + "x.\n\n"))
                .append(hint("Same bet either way. Wilder, not better."))));

        pages.add(page(Text.empty()
                .append(title("4k. COIN TOSS\n\n"))
                .append(item("  -  Gold   -\nGreen Green Green\nPlank Plank Plank\n\n"))
                .append(body("Heads, tails, or the rim.\n\n"))
                .append(hint("The rim pays " + (int) TrapMath.TOSS_EDGE_PAY
                        + "x and lands about "
                        + Math.round(TrapMath.TOSS_EDGE_CHANCE * 1000) / 10.0 + "%."))));

        pages.add(page(Text.empty()
                .append(title("4l. BLACKJACK\n\n"))
                .append(item("Paper Plank Paper\nGreen Green Green\nPlank Plank Plank\n\n"))
                .append(body("Hit, stand or double. Dealer stands on "
                        + TrapMath.DEALER_STANDS + ".\n\n"))
                .append(warn("Blackjack pays six to five here, not three to two."))));

        pages.add(page(Text.empty()
                .append(title("4l2. SCRATCHERS\n\n"))
                .append(body("Buy a card. Click the nine panels in any "
                        + "order.\n\n"))
                .append(body("Three of a kind pays. Four pays x"
                        + (int) TrapMath.SCRATCH_SIZES[4] + ", five x"
                        + (int) TrapMath.SCRATCH_SIZES[5] + ".\n\n"))
                .append(hint("Three in a line pays double."))));

        pages.add(page(Text.empty()
                .append(title("4l3. THE ODDS\n\n"))
                .append(body(Math.round(TrapMath.SCRATCH_MEASURED_WIN_RATE * 100)
                        + " cards in 100 pay something.\n\n"))
                .append(warn("Most of those pay back less than the card "
                        + "cost.\n\n"))
                .append(hint("One prize a card. The best on it."))));

        pages.add(page(Text.empty()
                .append(title("4m. COINS\n\n"))
                .append(body("The exchange has a second window: six coins "
                        + "you can buy and sell whenever.\n\n"))
                .append(body("Steady, Swingy, Degenerate.\n\n"))
                .append(warn("The wild ones do go to zero. Permanently."))));

        pages.add(page(Text.empty()
                .append(title("4m2. LOCKING\n\n"))
                .append(body("Buy locked and you can't sell for "
                        + TrapCoins.LOCK_BEATS / 2 + " minutes.\n\n"))
                .append(body("It pays " + Math.round(TrapCoins.LOCK_BONUS * 100)
                        + "% extra when the term is up.\n\n"))
                .append(warn("A rug doesn't care that you locked it."))));

        pages.add(page(Text.empty()
                .append(title("4n. THE HOUSE\n\n"))
                .append(body("About " + Math.round(TrapMath.slotWinRate(5) * 100)
                        + " spins in 100 pay, and never less than "
                        + "your stake.\n\n"))
                .append(body("Rainbow panes and fireworks mean a big one.\n\n"))
                .append(warn("The house still keeps about "
                        + Math.round((1.0f - TrapMath.slotRtp(5)) * 100)
                        + "%. It always does."))));

        pages.add(page(Text.empty()
                .append(title("4n1. FOUR CABINETS\n\n"))
                .append(body("The button by the stake swaps the window: "
                        + "2x2, 3x3, 4x4, 5x5.\n\n"))
                .append(body("Small is quick and pays pairs. Big holds "
                        + "every shape there is.\n\n"))
                .append(hint("Each has its own reels and odds."))));

    }


    private static void street(List<RawFilteredPair<Text>> pages) {
        pages.add(page(Text.empty()
                .append(title("5. HANDING OVER\n\n"))
                .append(body("Sell to a customer or a buyer yourself and "
                        + "somebody may follow the money back.\n\n"))
                .append(warn("Four of them at worst luck. Eleven at your "
                        + "worst."))));

        pages.add(page(Text.empty()
                .append(title("5b. WHAT DECIDES IT\n\n"))
                .append(body("Your name, mostly. Then heat, how much you "
                        + "hand over at once, and how good it is.\n\n"))
                .append(body("Daylight helps. A friend within "
                        + TrapParanoia.COMPANY_RANGE + " blocks helps more."))));

        pages.add(page(Text.empty()
                .append(title("5c. OR DON'T\n\n"))
                .append(body("A dealer selling for you never brings one.\n\n"))
                .append(body("That is what the cut buys.\n\n"))
                .append(hint("/heat shows your odds."))));
    }

    private static void ledger(List<RawFilteredPair<Text>> pages) {
        pages.add(page(Text.empty()
                .append(title("2. THE LEDGER\n\n"))
                .append(body("Where did you put the iron.\n\n"))
                .append(body("Right-click to read every container within "
                        + LedgerItem.RADIUS_H + " blocks across and "
                        + LedgerItem.RADIUS_V + " up or down.\n\n"))
                .append(hint("Book + compass + 2 amethyst."))));

        pages.add(page(Text.empty()
                .append(title("2b. TRACING\n\n"))
                .append(body("Click any row and it draws a line of light through "
                        + "the air to the chests holding it.\n\n"))
                .append(body("Up to " + LedgerItem.MAX_PINGS + " at once.\n\n"))
                .append(hint("It looks inside shulker boxes too."))));
    }

    private static void contracts(List<RawFilteredPair<Text>> pages) {
        pages.add(page(Text.empty()
                .append(title("3. CONTRACTS\n\n"))
                .append(body("A burner phone gets work. "
                        + TrapContracts.BOARD_SIZE + " jobs a day, each to its "
                        + "own drop " + TrapContracts.MIN_DROP + "-"
                        + TrapContracts.MAX_DROP + " out.\n\n"))
                .append(body("A compass points the way. Beat the clock.\n\n"))
                .append(hint("Copper + amethyst + redstone."))));

        pages.add(page(Text.empty()
                .append(title("3b. THE CATCH\n\n"))
                .append(warn("A job puts heat on you.\n\n"))
                .append(body("Heat feeds paranoia the whole run. It is also "
                        + "why the job pays.\n\n"))
                .append(hint("Wears off in "
                        + TrapContracts.JOB_HEAT_TICKS / 20 / 60 + " min."))));

        pages.add(page(Text.empty()
                .append(title("3c. GETTING PAID\n\n"))
                .append(body("Right-click your buyer, within "
                        + TrapContracts.DELIVERY_RANGE + " blocks of the drop.\n\n"))
                .append(body("Emeralds and rep.\n\n"))
                .append(warn("Miss it: -" + TrapContracts.FAIL_REP + " rep.\n\n"))
                .append(hint("Rep rides the phone."))));
        pages.add(page(Text.empty()
                .append(title("3c2. THE DROP\n\n"))
                .append(body("A compass, and a waypoint to click.\n\n"))
                .append(body("Get close and your buyer turns up, glowing. "
                        + "Right-click to hand over.\n\n"))
                .append(hint("Empty-handed, they'll remind you."))));

        pages.add(page(Text.empty()
                .append(title("3d. WHAT THEY TAKE\n\n"))
                .append(body("Each job says: cured buds only, rolled joints "
                        + "only, or either.\n\n"))
                .append(body("A joint counts as one bud.\n\n"))
                .append(hint("Check before you roll the batch.")))); 
    }

    private static ItemStack book(String title, List<RawFilteredPair<Text>> pages) {
        WrittenBookContentComponent content = new WrittenBookContentComponent(
                RawFilteredPair.of(title), "The Trap House", 0, pages, true);
        ItemStack stack = new ItemStack(Items.WRITTEN_BOOK);
        stack.set(DataComponentTypes.WRITTEN_BOOK_CONTENT, content);
        return stack;
    }

    private static void cocaCover(List<RawFilteredPair<Text>> pages) {
        pages.add(page(Text.empty()
                .append(title("THE TRAP HOUSE"))
                .append(Text.literal("\nrefiner's handbook\n\n")
                        .formatted(Formatting.DARK_GRAY, Formatting.ITALIC))
                .append(body("The coca line. Longer than weed, worth far more.\n\n"))
                .append(hint("Weed: /guide grower\nStreet: /guide street"))));
    }

    // --- pages ----------------------------------------------------------------

    private static void cover(List<RawFilteredPair<Text>> pages) {
        pages.add(page(Text.empty()
                .append(title("THE TRAP HOUSE"))
                .append(Text.literal("\ngrower's handbook\n\n").formatted(Formatting.DARK_GRAY, Formatting.ITALIC))
                // Paired per line: nine on their own lines plus the header and
                // footer came to ~16, over the 14-line page limit.
                .append(body("1 Growing  2 Grade\n3 Curing   4 Rolling\n5 Baked    6 Strains\n7 Breeding 8 Heat\n9 Supply\n"))
                .append(hint("Coca: /guide refiner\nStreet: /guide street"))));
    }

    private static void growing(List<RawFilteredPair<Text>> pages) {
        pages.add(page(Text.empty()
                .append(title("1. GROWING\n\n"))
                .append(body("Plant seeds on farmland. Dirt and grass work too, just slower.\n\n"))
                .append(body("Four stages. Watered: ~" + Math.round(TrapMath.stageMinutes(
                        TrapMath.WEED_GROWTH_ROLLS_WET, 3) * 3) + " min. Dry: ~"
                        + Math.round(TrapMath.stageMinutes(
                        TrapMath.WEED_GROWTH_ROLLS_DRY, 3) * 3) + " min.\n\n"))
                .append(hint("Water is worth 3 quality points AND half the wait."))));

        pages.add(page(Text.empty()
                .append(title("1b. HARVEST\n\n"))
                .append(body("Right-click a full-grown plant with an empty hand.\n\n"))
                .append(body("You get buds, sometimes a seed, and the plant stays and regrows.\n\n"))
                .append(hint("Breaking it also works and returns your seed."))));
    }

    private static void grading(List<RawFilteredPair<Text>> pages) {
        pages.add(page(Text.empty()
                .append(title("2. GRADE\n\n"))
                .append(body("Set the moment you pick it, from how it grew:\n\n"))
                .append(body("+3 wet farmland\n+2 light 12+\n+1 light 9-11\n+2 open sky\n+1 no bone meal\n\n"))
                .append(hint("8 points possible."))));

        MutableText table = Text.empty().append(title("2b. WHAT IT'S WORTH\n\n"));
        for (Quality grade : Quality.values()) {
            table.append(Text.literal(pad(grade.display(), 6)).formatted(grade.bookColour()))
                    .append(body(String.format("%.1fx  %de\n", grade.potency(), grade.emeralds())));
        }
        table.append(body("\n"))
                .append(hint("Points needed: " + joinInts(Quality.THRESHOLDS)));
        pages.add(page(table));
    }

    private static void curing(List<RawFilteredPair<Text>> pages) {
        pages.add(page(Text.empty()
                .append(title("3. CURING\n\n"))
                .append(body("Craft a rack: 8 sticks, 2 string.\n\n"))
                .append(body("Right-click holding fresh buds. Buds darken as they cure. When the tips go "))
                .append(Text.literal("gold").formatted(Formatting.GOLD))
                .append(body(" it's ready."))));

        MutableText window = Text.empty().append(title("3b. THE WINDOW\n\n"));
        for (int stage = 0; stage <= DryingRackBlock.MAX_DRYNESS; stage++) {
            String label;
            if (stage == 0) {
                label = "too wet";
            } else if (stage == DryingRackBlock.READY_DRYNESS) {
                label = "READY";
            } else if (stage == DryingRackBlock.MAX_DRYNESS) {
                label = "-1 grade";
            } else {
                label = "-" + (DryingRackBlock.READY_DRYNESS - stage) + " grade";
            }
            window.append(body("stage " + stage + "  "))
                    .append(stage == DryingRackBlock.READY_DRYNESS
                            ? Text.literal(label + "\n").formatted(Formatting.GOLD)
                            : body(label + "\n"));
        }
        window.append(body("\n")).append(hint("Peak also gives 2 buds instead of 1."));
        pages.add(page(window));
    }

    private static void rolling(List<RawFilteredPair<Text>> pages) {
        pages.add(page(Text.empty()
                .append(title("4. ROLLING\n\n"))
                .append(body("Cured bud + paper = joint. The grade carries over.\n\n"))
                .append(body("Hold right-click to smoke. You raise it to your mouth and trail smoke.\n\n"))
                .append(hint("Everyone nearby sees it."))));
    }

    /** Three ways to smoke, in order of setup cost. Numbers from the blocks. */
    private static void methods(List<RawFilteredPair<Text>> pages) {
        pages.add(page(Text.empty()
                .append(title("4b. METHODS\n\n"))
                .append(body(String.format("joint   1.0x\nbong    %.1fx\ntlok    %.1fx\n\n",
                        BongBlock.POTENCY, GravityBongBlock.POTENCY)))
                .append(body("Stronger hits raise Tolerance faster, so none of them is free.\n\n"))
                .append(hint("Joints travel. The rest don't."))));

        pages.add(page(Text.empty()
                .append(title("4c. BONG\n\n"))
                .append(body("Glass and bamboo.\n\n"))
                .append(body("Water bucket once, then a cured bud per hit, then right-click.\n\n"))
                .append(hint("Water stays. The bowl doesn't."))));

        pages.add(page(Text.empty()
                .append(title("4d. TLOK\n\n"))
                .append(body("Bottle, bucket, bamboo, paper.\n\n"))
                .append(body("Water, bud, flint and steel, then pull.\n\n"))
                .append(warn("Pull soon after lighting. Leave it and the smoke goes stale."))));

        mixing(pages);
    }

    /**
     * Kept to three pages. The mixing station has a lot going on, but the book
     * truncates silently past ~14 lines a page -- so the named blends get their
     * own page rather than being crammed in as a list.
     */
    private static void mixing(List<RawFilteredPair<Text>> pages) {
        pages.add(page(Text.empty()
                .append(title("4e. MIXING\n\n"))
                .append(item("Bottle Bowl Bottle\nCopper Iron Copper\nLog    Log  Log\n\n"))
                .append(body("Buds in the four slots, then click the jar.\n\n"))
                .append(hint("Any log will do."))));

        pages.add(page(Text.empty()
                .append(title("4e2. THE JAR\n\n"))
                .append(body("The jar on the right shows what you'd get before "
                        + "you commit.\n\n"))
                .append(body("It says why when it can't.\n\n"))
                .append(hint("Close it and your buds come back."))));

        pages.add(page(Text.empty()
                .append(title("4f. BLENDS\n\n"))
                .append(body("A mix does what all its parts do, each cut to its share.\n\n"))
                .append(body("More different strains = stronger, and much wilder to look at.\n\n"))
                .append(warn("Grade is your WORST bud, not the average."))));

        pages.add(page(Text.empty()
                .append(title("4g. KNOWN\n\n"))
                .append(body("Some mixes do more than the sum:\n\n"))
                .append(body("Trinity  K+H+P\nVoid  M+M+P\nDaybreak  H+S\nTurbo  D+D+H\nTar  K+K+M\nKaleidoscope  P+S+H+D\n"))));
    }

    private static void baked(List<RawFilteredPair<Text>> pages) {
        pages.add(page(Text.empty()
                .append(title("5. BAKED\n\n"))
                .append(body("Burns food. Well fed, it heals you back.\n\n"))
                .append(warn("On an empty stomach it hurts. Eat first.\n\n"))
                .append(hint("Each strain adds its own effects on top."))));

        pages.add(page(Text.empty()
                .append(title("5b. TOLERANCE\n\n"))
                .append(body("Every joint raises it a level. Each level cuts "))
                .append(body(Math.round(ToleranceStatusEffect.PER_LEVEL * 100) + "% "))
                .append(body("off the next high.\n\n"))
                .append(body("Floor is " + Math.round(ToleranceStatusEffect.FLOOR * 100) + "%. "))
                .append(body("A level wears off in " + (ToleranceStatusEffect.DURATION_TICKS / 20 / 60) + " min.\n\n"))
                .append(hint("Pace yourself."))));
    }

    private static void strains(List<RawFilteredPair<Text>> pages) {
        for (Strain strain : Strain.values()) {
            pages.add(page(Text.empty()
                    .append(title("6. STRAINS\n\n"))
                    .append(Text.literal(strain.display() + "\n").withColor(strain.bookColour()))
                    .append(Text.literal(strain.isHybrid() ? "hybrid\n\n" : "wild\n\n")
                            .formatted(Formatting.DARK_GRAY, Formatting.ITALIC))
                    .append(body(strain.describe()))));
        }
    }

    private static void breeding(List<RawFilteredPair<Text>> pages) {
        MutableText text = Text.empty()
                .append(title("7. BREEDING\n\n"))
                .append(body("Two different strains, full grown, side by side.\n\n"));

        // Read straight off hybridOf, so a new pairing can't be missed here.
        for (Strain a : Strain.values()) {
            for (Strain b : Strain.values()) {
                if (a.ordinal() >= b.ordinal()) {
                    continue;
                }
                Strain hybrid = Strain.hybridOf(a, b);
                if (hybrid != null) {
                    text.append(Text.literal(a.display()).withColor(a.bookColour()))
                            .append(body("+"))
                            .append(Text.literal(b.display()).withColor(b.bookColour()))
                            .append(body("="))
                            .append(Text.literal(hybrid.display() + "\n").withColor(hybrid.bookColour()));
                }
            }
        }
        text.append(body("\n")).append(hint("Hybrids don't breed further."));
        pages.add(page(text));
    }

    private static void heat(List<RawFilteredPair<Text>> pages) {
        pages.add(page(Text.empty()
                .append(title("8. HEAT\n\n"))
                .append(body("A grow in the open gets noticed. You get a warning.\n\n"))
                .append(body("Bigger plot, sooner they come back.\n\n"))
                .append(hint("Past the top tier, sooner still."))));

        pages.add(page(Text.empty()
                .append(title("8a. BOTH LINES\n\n"))
                .append(body("Coca counts now. So do presses and refiners.\n\n"))
                .append(body("Weed AND coca in one place is worth "
                        + Math.round((TrapHeat.MIXED_TRADE - 1) * 100)
                        + "% more heat than the two apart.\n\n"))
                .append(hint("Two sheds beat one shed."))));

        pages.add(page(Text.empty()
                .append(title("8a3. WALLS\n\n"))
                .append(body("Sealing the grow in buys you time, not safety.\n\n"))
                .append(body("If they can't find a way round, they come "
                        + "through the wall.\n\n"))
                .append(warn("Obsidian stops them. Dirt does not."))));

        pages.add(page(Text.empty()
                .append(title("8b. THE NUMBERS\n\n"))
                .append(body("Counts mature plants within " + TrapHeat.RADIUS + " blocks.\n\n"))
                .append(body("Open sky = 2 each\nRoofed = 1 each\n\n"))
                .append(hint("A fully roofed grow trips at half the size."))));

        // Built from the arrays rather than written out, so the book can't
        // drift from the tiers the way the old fixed numbers did.
        net.minecraft.text.MutableText tiers =
                Text.empty().append(title("8c. WHO COMES\n\n"));
        for (int tier = 0; tier < TrapHeat.THRESHOLDS.length; tier++) {
            tiers.append(body(TrapHeat.THRESHOLDS[tier] + ": " + TrapHeat.squadOf(tier)
                    + "  (" + TrapHeat.cooldownMinutes(tier) + "m)\n"));
        }
        pages.add(page(tiers.append(body("\n"))
                .append(hint("Heat, not plants: 15 in the open is the first tier."))));
    }

    /** The second product line. Numbers read from Purity and RefinerBlock. */
    private static void coca(List<RawFilteredPair<Text>> pages) {
        pages.add(page(Text.empty()
                .append(title("9. COCA\n\n"))
                .append(body("Bush -> leaves -> press -> refiner -> powder.\n\n"))
                .append(body("~" + Math.round(TrapMath.stageMinutes(
                        TrapMath.COCA_GROWTH_ROLLS, 3) * 3) + " min to ripen. "
                        + "Any dirt, but it needs light.\n\n"))
                .append(hint("Seeds: warm structures, or a trader."))));

        pages.add(page(Text.empty()
                .append(title("9b. PRESSING\n\n"))
                .append(body("Craft a "))
                .append(item("Leaf Press"))
                .append(body(": smooth stone, logs, iron.\n\n"))
                .append(body("Right-click with " + LeafPressBlock.LEAVES_PER_BATCH
                        + " leaves. Wait, then right-click for paste.\n\n"))
                .append(hint("No window here. It just takes time."))));

        MutableText refining = Text.empty()
                .append(title("9c. REFINING\n\n"))
                .append(body("Paste + blaze powder in the "))
                .append(item("Refiner"))
                .append(body(".\n\n"))
                .append(body("Purity is all in the timing:\n\n"));
        for (int step = 1; step <= RefinerBlock.BURNT; step++) {
            Purity grade = RefinerBlock.purityFor(step);
            boolean peak = step == RefinerBlock.PEAK;
            refining.append(body("stage " + step + "  "))
                    .append(Text.literal(grade.display() + (peak ? " *\n" : "\n"))
                            .formatted(grade.bookColour()));
        }
        pages.add(page(refining));

        MutableText worth = Text.empty().append(title("9d. WORTH IT\n\n"));
        for (Purity grade : Purity.values()) {
            worth.append(Text.literal(pad(grade.display(), 7)).formatted(grade.bookColour()))
                    .append(body(String.format("%.1fx  %de\n", grade.potency(), grade.emeralds())));
        }
        worth.append(body("\n"))
                .append(warn("Wired costs nothing until it ends. Then it all lands at once."));
        pages.add(page(worth));
    }

    private static void checking(List<RawFilteredPair<Text>> pages) {
        pages.add(page(Text.empty()
                .append(title("8a2. /HEAT\n\n"))
                .append(body("How hot this spot is, and what it brings.\n\n"))
                .append(body("Ripe 3, hidden 2, growing 1, rack 1.\n\n"))
                .append(body("Coca the same. Machines 2.\n\n"))
                .append(hint("Reads 22 across, 10 tall."))));
    }

    /**
     * What stayed in the grower's handbook once the crew moved out.
     *
     * The search is a RAID page, not a crew page -- it only ever sat next to
     * them because both were about your farm being looked after or looked at.
     */
    private static void crew(List<RawFilteredPair<Text>> pages) {
        pages.add(page(Text.empty()
                .append(title("8b. THE CREW\n\n"))
                .append(body("Hire hands to work a patch for you: picking, "
                        + "farming, curing, sowing.\n\n"))
                .append(body(TrapCrew.HIRE_COST + "e each, then wages.\n\n"))
                .append(hint("The lot of it: /guide crew"))));

        pages.add(page(Text.empty()
                .append(title("8c. THE SEARCH\n\n"))
                .append(body("A raid doesn't just swing axes. They walk to "
                        + "your chests and take product.\n\n"))
                .append(body("Only product. Seeds and gear are safe.\n\n"))
                .append(hint("Bury it, split it, or stand in the way."))));
    }

    // --- the crew handbook ----------------------------------------------------

    /**
     * Every page below reads its numbers off {@link TrapCrew}.
     *
     * Which matters more here than anywhere else in this book, because the
     * crew is the one system in the mod where the whole decision IS the
     * numbers: what a rung costs, what it does to the wage, and whether the
     * two are worth it. A handbook quoting a pace ladder somebody has since
     * retuned would be worse than no handbook at all.
     */
    private static void crewBook(List<RawFilteredPair<Text>> pages) {
        int top = TrapCrew.PACE_TICKS.length - 1;
        int wide = TrapCrew.REACH_BLOCKS.length - 1;

        pages.add(page(Text.empty()
                .append(title("1. HIRING\n\n"))
                .append(body("Stand where you want the work done:\n\n"))
                .append(body("/crew hire  -  " + TrapCrew.HIRE_COST + "e\n\n"))
                .append(body("That spot is their patch. They never leave it.\n\n"))
                .append(hint(TrapCrew.MAX_HANDS + " hands is the most you can carry."))));

        pages.add(page(Text.empty()
                .append(title("2. THE CHEST\n\n"))
                .append(body("Everything they pick goes in the nearest "
                        + "container to the patch.\n\n"))
                .append(body("They take seeds and bone meal back out of that "
                        + "same chest.\n\n"))
                .append(warn("No chest, and the drops land on the floor."))));

        pages.add(page(Text.empty()
                .append(title("3. THE BOARD\n\n"))
                .append(body("/crew\n\n"))
                .append(body("Top row is your hands. Click one, then buy pace, "
                        + "patch or a job for them.\n\n"))
                .append(hint("Each hand is trained on its own."))));

        pages.add(page(Text.empty()
                .append(title("4. PACE\n\n"))
                .append(body(TrapCrew.PACE_NAME[0] + ": a job every "
                        + TrapCrew.paceLabel(0) + ".\n\n"))
                .append(body(TrapCrew.PACE_NAME[top] + ": every "
                        + TrapCrew.paceLabel(top) + ", and they walk quicker.\n\n"))
                .append(hint(top + " rungs, " + TrapCrew.PACE_COST[1] + "e to "
                        + TrapCrew.PACE_COST[top] + "e."))));

        pages.add(page(Text.empty()
                .append(title("5. THE PATCH\n\n"))
                .append(body("They work a box " + TrapCrew.REACH_BLOCKS[0]
                        + " blocks around the spot, up to " + TrapCrew.REACH_BLOCKS[wide]
                        + ".\n\n"))
                .append(body("Wider is more ground and more wage, not more "
                        + "speed.\n\n"))
                .append(hint("Buy pace first. An idle hand still eats."))));

        jobs(pages);

        pages.add(page(Text.empty()
                .append(title("8. WAGES\n\n"))
                .append(body(TrapCrew.WAGE + "e each, every five minutes, "
                        + "harvest or no harvest.\n\n"))
                .append(body("Every rung and job puts that up.\n\n"))
                .append(warn("Miss a payday and they walk, and take what you "
                        + "taught them with them."))));

        pages.add(page(Text.empty()
                .append(title("9. HOURS\n\n"))
                .append(body("Daylight only.\n\n"))
                .append(body("At dusk they find a bed in the patch and turn "
                        + "in. Build them one.\n\n"))
                .append(hint("They take breathers, too."))));

        pages.add(page(Text.empty()
                .append(title("9b. WHAT THEY WON'T DO\n\n"))
                .append(body("Pull a rack early. That costs a grade.\n\n"))
                .append(body("Bone meal YOUR crops. Same reason.\n\n"))
                .append(body("Tread farmland back into dirt.\n\n"))
                .append(hint("Nor wander off."))));

        pages.add(page(Text.empty()
                .append(title("10. IS IT WORTH IT?\n\n"))
                .append(body("A hand you can't keep busy is a hand losing you "
                        + "money.\n\n"))
                .append(body("Work out what the patch brings in per five "
                        + "minutes, then read the wage.\n\n"))
                .append(hint("/crew shows the payroll, and what you're carrying."))));
    }

    /** The five you can teach, priced off the enum so the board can't disagree. */
    private static void jobs(List<RawFilteredPair<Text>> pages) {
        pages.add(page(Text.empty()
                .append(title("6. WHAT THEY DO\n\n"))
                .append(body(TrapCrew.Job.PICK.display() + " comes free: your "
                        + "ripe plants, into the chest.\n\n"))
                .append(body("The rest is taught, one hand at a time.\n\n"))
                .append(hint("A job costs once, then every packet after."))));

        MutableText list = Text.empty().append(title("7. THE JOBS\n\n"));
        for (TrapCrew.Job job : TrapCrew.Job.values()) {
            if (job.free()) {
                continue;
            }
            list.append(body(job.display() + "  " + job.cost() + "e  +"
                    + job.wage() + "e\n"));
        }
        pages.add(page(list.append(Text.literal("\n"))
                .append(hint("Cost, then what it adds to the wage."))));
    }

    private static void network(List<RawFilteredPair<Text>> pages) {
        pages.add(page(Text.empty()
                .append(title("8e. THE NETWORK\n\n"))
                .append(body("Sneak + right-click the phone.\n\n"))
                .append(body("Hire up to " + TrapDealers.MAX_DEALERS
                        + " dealers. They sell while you're away.\n\n"))
                .append(hint("Click one to call them in."))));

        pages.add(page(Text.empty()
                .append(title("8f. LOADING UP\n\n"))
                .append(body("Right-click a called dealer to open their "
                        + "book.\n\n"))
                .append(body("Drop product in. Take the money out.\n\n"))
                .append(warn("They sell nothing while stood in front of you."))));

        pages.add(page(Text.empty()
                .append(title("8g. THE HOURS\n\n"))
                .append(body("They shift about three times as much at "
                        + "midnight as at noon.\n\n"))
                .append(body("Load them in the evening, collect in the "
                        + "morning.\n\n"))
                .append(hint("Heat slows them. It doesn't stop them."))));

        pages.add(page(Text.empty()
                .append(title("8h. LEVELS\n\n"))
                .append(body("Eight of them. Higher = more slots, faster, "
                        + "robbed less, bigger cut.\n\n"))
                .append(body("The throughput covers the cut.\n\n"))
                .append(hint("Level 8 carries 18 slots."))));

        pages.add(page(Text.empty()
                .append(title("8h2. YOUR NAME\n\n"))
                .append(body("Contract rep cuts hiring costs, up to 40%.\n\n"))
                .append(body("It also gets better people on the board, and "
                        + "your dealers learn faster.\n\n"))
                .append(hint("Courier work pays twice."))));

        pages.add(page(Text.empty()
                .append(title("8h3. THE BOARD\n\n"))
                .append(body("Turns over on its own every ten minutes.\n\n"))
                .append(body("Or pay " + TrapDealers.REROLL_COST
                        + "e to ask around now.\n\n"))
                .append(warn("Product on the street brings raids forward."))));

        pages.add(page(Text.empty()
                .append(title("8i. CROWDING\n\n"))
                .append(body("Every extra dealer on the same patch sells "
                        + "less than the last.\n\n"))
                .append(warn("Four are worth less than four times one."))));
    }

    private static void supply(List<RawFilteredPair<Text>> pages) {
        pages.add(page(Text.empty()
                .append(title("9. SUPPLY\n\n"))
                .append(body("Seeds: grass, chests, wild patches in plains, savanna and jungle.\n\n"))
                .append(body("Wandering traders sell one strain each, 5 emeralds.\n\n"))
                .append(hint("Farmers sell seeds and buy fresh buds."))));

        pages.add(page(Text.empty()
                .append(title("9b. SELLING\n\n"))
                .append(body("Traders pay by grade, and they check:\n\n"))
                .append(body("4 cured buds, or 2 joints, per sale.\n\n"))
                .append(body(Quality.SWILL.display() + " pays " + Quality.SWILL.emeralds() + "e. "))
                .append(Text.literal(Quality.FIRE.display()).formatted(Quality.FIRE.colour()))
                .append(body(" pays " + Quality.FIRE.emeralds() + "e.\n\n"))
                .append(hint("Same work, " + (Quality.FIRE.emeralds() / Quality.SWILL.emeralds()) + "x the money."))));

        pages.add(page(Text.empty()
                .append(title("9c. CUSTOMERS\n\n"))
                .append(body("Carry product and somebody walks up wanting it.\n\n"))
                .append(body("A strain, powder, or a mix.\n\n"))
                .append(hint("Their name says what they're after."))));

        pages.add(page(Text.empty()
                .append(title("9c1. THE FORM\n\n"))
                .append(body("Their name says the strain AND whether they "
                        + "want bud, joints, or any.\n\n"))
                .append(body("They only ask for what you're carrying.\n\n"))
                .append(hint("Right-click with the wrong form does nothing."))));

        pages.add(page(Text.empty()
                .append(title("9c2. MIXES\n\n"))
                .append(body("Some ask for any mix. Some ask for a known "
                        + "blend by name.\n\n"))
                .append(body("Both pay more than a single strain. Named pays "
                        + "most.\n\n"))
                .append(hint("More parts, more money."))));

        pages.add(page(Text.empty()
                .append(title("9d. DEALING\n\n"))
                .append(body("Hold it and right-click them. No menu.\n\n"))
                .append(body("They take up to " + TrapDealing.UNITS_WANTED
                        + ", paying per item as you go.\n\n"))
                .append(hint("Sneak-click to send them off.\nThey leave on their own after "
                        + TrapDealing.LIFETIME_TICKS / 20 / 60 + " min."))));
    }

    // --- text helpers ---------------------------------------------------------

    private static String pad(String text, int width) {
        return text.length() >= width ? text : text + " ".repeat(width - text.length());
    }

    private static String joinInts(int[] values) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            out.append(values[i]);
            if (i < values.length - 1) {
                out.append('/');
            }
        }
        return out.toString();
    }

    private static RawFilteredPair<Text> page(Text text) {
        return RawFilteredPair.of(text);
    }

    private static MutableText title(String s) {
        return Text.literal(s).formatted(Formatting.DARK_GREEN, Formatting.BOLD);
    }

    private static MutableText body(String s) {
        return Text.literal(s).formatted(Formatting.BLACK);
    }

    private static MutableText item(String s) {
        return Text.literal(s).formatted(Formatting.DARK_PURPLE);
    }

    private static MutableText hint(String s) {
        return Text.literal(s).formatted(Formatting.DARK_GRAY, Formatting.ITALIC);
    }

    private static MutableText warn(String s) {
        return Text.literal(s).formatted(Formatting.DARK_RED);
    }
}
