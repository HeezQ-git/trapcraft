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
     * One command, three books.
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
                                .executes(context -> give(context.getSource(), createStreet())))));
    }

    /** Bare /guide lists the three rather than erroring at you. */
    private static int menu(net.minecraft.server.command.ServerCommandSource source) {
        source.sendFeedback(() -> Text.empty()
                .append(Text.literal("The Trap House\n").formatted(Formatting.DARK_GREEN, Formatting.BOLD))
                .append(pick("grower", "growing, curing, rolling, heat"))
                .append(pick("refiner", "the coca line"))
                .append(pick("street", "paranoia, the ledger, contracts")), false);
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
                .append(title("4i. THE HOUSE\n\n"))
                .append(body("About " + Math.round(TrapMath.SLOT_MEASURED_WIN_RATE * 100)
                        + " spins in 100 pay, and never less than "
                        + "your stake.\n\n"))
                .append(body("Rainbow panes and fireworks mean a big one.\n\n"))
                .append(warn("The house still keeps about "
                        + Math.round((1.0f - TrapMath.SLOT_MEASURED_RTP) * 100)
                        + "%. It always does."))));
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
                        + TrapContracts.BOARD_SIZE + " jobs a day.\n\n"))
                .append(body("Deliver before the clock runs out. A compass "
                        + "points the way.\n\n"))
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
                .append(body("Right-click a villager within "
                        + TrapContracts.DELIVERY_RANGE + " blocks of the drop.\n\n"))
                .append(body("Emeralds and rep.\n\n"))
                .append(warn("Miss it: -" + TrapContracts.FAIL_REP + " rep.\n\n"))
                .append(hint("Rep rides the phone."))));
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
                .append(body("Four stages. Water speeds it up.\n\n"))
                .append(hint("It won't uproot itself if the farmland dries out."))));

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
                .append(body("A grow in the open gets noticed. You get a warning first.\n\n"))
                .append(body("Bigger plot, bigger crew, sooner they return.\n\n"))
                .append(hint("Grow indoors, or split it up."))));

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
                .append(body("A longer chain, worth far more.\n\n"))
                .append(body("Bush -> leaves -> press -> refiner -> powder.\n\n"))
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
