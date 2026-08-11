package dev.heezq.trapcraft;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.WrittenBookContentComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.RawFilteredPair;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.WorldSavePath;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The revenue office, the wash, and the book that keeps up.
 *
 * This is the piece that makes the untaxed black market a problem worth
 * having. Everything legal in this mod pays duty and everything illegal pays
 * nothing, which for six versions simply meant drugs were better. The office
 * is the other end of that: money you earned that the city cannot account for
 * gets noticed, and the only way to stop it being noticed is to run it through
 * a business you actually own.
 *
 * <h2>How the office knows</h2>
 *
 * Off {@link TrapLedger}, which has flagged every source declared or otherwise
 * since the day it shipped. It reads the day's book rather than guessing from
 * how much somebody is carrying: wealth swings when you empty a chest, and an
 * office that assessed you for going to the bank would be noise wearing a
 * uniform.
 *
 * <h2>The wash</h2>
 *
 * Dirty money becomes clean by going through your own till, at a cut, and
 * capped by how much that till actually turned over. A real business is the
 * licence to launder and the size of it is the limit -- which is why the
 * casino and the market shelf are worth owning for a reason other than what
 * they earn.
 */
public final class TrapLaw {

    /** Undeclared money in a day the office will overlook. */
    public static final int LOOKS_AWAY = 250;
    /** Share of the unexplained the office asks for. */
    public static final float ASSESSMENT = 0.35f;
    /** And under the Revenue Drive. */
    public static final float ASSESSMENT_DRIVE = 0.55f;
    /** What the wash costs, as a share of what goes through it. */
    public static final float WASH_CUT = 0.20f;
    /** Heat somebody carries while they owe the office. */
    public static final int DEBT_HEAT = 2;

    /** Cleaned today, by player name. Cleared when the day turns. */
    private static final Map<String, Integer> WASHED = new HashMap<>();
    /** Assessments nobody could pay, by player uuid string. Persists. */
    private static final Map<String, Integer> OWED = new HashMap<>();
    private static long day = -1;
    private static Path saveFile;

    private TrapLaw() {
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(TrapLaw::load);
        registerCommands();
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTicks() % 200 != 0) {
                return;
            }
            long now = server.getOverworld().getTimeOfDay() / 24000L;
            if (day < 0) {
                day = now;
                return;
            }
            if (now != day) {
                day = now;
                office(server);
                WASHED.clear();
            }
            nag(server);
        });
    }

    // --- what the office can see ----------------------------------------------

    /** Undeclared income across everybody today. Feeds the Revenue Drive. */
    public static int undeclaredToday() {
        int total = 0;
        for (String who : names()) {
            total += TrapLedger.undeclaredOf(TrapLedger.today(who));
        }
        return total;
    }

    public static int declaredToday() {
        int total = 0;
        for (String who : names()) {
            total += TrapLedger.declaredOf(TrapLedger.today(who));
        }
        return total;
    }

    public static int washedToday() {
        int total = 0;
        for (int amount : WASHED.values()) {
            total += amount;
        }
        return total;
    }

    public static int owedTotal() {
        int total = 0;
        for (int amount : OWED.values()) {
            total += amount;
        }
        return total;
    }

    /**
     * Heat anybody is carrying, summed. Stands in for "how hot is the server".
     *
     * A crude number on purpose: the alternative is scanning every grow on the
     * map twice a day to answer a question the constitution asks once.
     */
    public static int serverHeat() {
        MinecraftServer server = SERVER;
        if (server == null) {
            return 0;
        }
        int total = 0;
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            total += TrapHeat.carryingHeat(player) * 60;
        }
        return total;
    }

    private static MinecraftServer SERVER;

    private static List<String> names() {
        List<String> out = new ArrayList<>();
        if (SERVER != null) {
            for (ServerPlayerEntity player : SERVER.getPlayerManager().getPlayerList()) {
                out.add(player.getGameProfile().getName());
            }
        }
        return out;
    }

    // --- the assessment -------------------------------------------------------

    /**
     * The day's reckoning, per player.
     *
     * Exposure is undeclared income LESS whatever was washed, so somebody who
     * put the day's takings through their own shop is square with the office
     * having paid for the privilege -- which is the whole counterplay and the
     * reason the wash exists.
     */
    private static void office(MinecraftServer server) {
        if (!TrapCity.founded()) {
            return;   // no city, no revenue office, no questions
        }
        float share = TrapCity.inForce(TrapCity.Act.DRIVE) ? ASSESSMENT_DRIVE : ASSESSMENT;
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            String name = player.getGameProfile().getName();
            int undeclared = TrapLedger.undeclaredOf(TrapLedger.today(name));
            int exposure = undeclared - WASHED.getOrDefault(name, 0);
            if (exposure <= LOOKS_AWAY) {
                continue;
            }
            int bill = Math.round((exposure - LOOKS_AWAY) * share);
            if (bill <= 0) {
                continue;
            }
            int paid = Math.min(bill, TrapMarket.wealthOf(player));
            if (paid > 0) {
                TrapMarket.collect(player, paid);
                TrapCity.receive(paid, TrapCity.Duty.INCOME);
                TrapLedger.record(player, TrapLedger.Source.TAX, -paid);
            }
            int short_ = bill - paid;
            if (short_ > 0) {
                OWED.merge(player.getUuidAsString(), short_, Integer::sum);
            }
            save();
            player.sendMessage(Text.literal("THE REVENUE OFFICE")
                    .formatted(Formatting.RED, Formatting.BOLD)
                    .append(Text.literal("\n  " + exposure + "e came in yesterday that the "
                            + "city cannot account for.").formatted(Formatting.GRAY))
                    .append(Text.literal("\n  Assessed " + bill + "e"
                            + (paid > 0 ? ", took " + paid + "e" : "")
                            + (short_ > 0 ? ", " + short_ + "e outstanding." : "."))
                            .formatted(Formatting.WHITE))
                    .append(Text.literal(short_ > 0
                                    ? "\n  You are being watched until it is paid."
                                    : "\n  Run it through a shop next time: /wash")
                            .formatted(short_ > 0 ? Formatting.RED : Formatting.DARK_GRAY)),
                    false);
        }
    }

    /** Owing the office is its own punishment: somebody is always looking. */
    private static void nag(MinecraftServer server) {
        if (OWED.isEmpty()) {
            return;
        }
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (OWED.getOrDefault(player.getUuidAsString(), 0) > 0) {
                TrapHeat.addCarriedHeat(player, DEBT_HEAT, 220);
            }
        }
    }

    public static int owedBy(ServerPlayerEntity who) {
        return OWED.getOrDefault(who.getUuidAsString(), 0);
    }

    /** Settle up. @return why it didn't happen, or null if it did */
    public static String settle(ServerPlayerEntity who) {
        int owed = owedBy(who);
        if (owed <= 0) {
            return "You don't owe the office anything.";
        }
        int paid = Math.min(owed, TrapMarket.wealthOf(who));
        if (paid <= 0) {
            return "You haven't got it. They can wait; they always can.";
        }
        TrapMarket.collect(who, paid);
        TrapCity.receive(paid, TrapCity.Duty.INCOME);
        TrapLedger.record(who, TrapLedger.Source.TAX, -paid);
        if (paid >= owed) {
            OWED.remove(who.getUuidAsString());
        } else {
            OWED.put(who.getUuidAsString(), owed - paid);
        }
        save();
        who.sendMessage(Text.literal(paid >= owed ? "Square with the office. "
                        : "Paid " + paid + "e. ").formatted(Formatting.GREEN)
                .append(Text.literal(paid >= owed ? "Nobody's watching you."
                                : (owed - paid) + "e still outstanding.")
                        .formatted(Formatting.GRAY)), false);
        return null;
    }

    // --- the wash -------------------------------------------------------------

    /**
     * How much dirty money a player's businesses could plausibly have taken.
     *
     * Turnover, not profit. A shop that sold four hundred emeralds of bread can
     * explain four hundred emeralds; a shop with an empty shelf explains
     * nothing, however much its owner would like it to.
     */
    public static int washLimit(ServerPlayerEntity who) {
        int limit = 0;
        for (TrapShops.Shop shop : TrapShops.shops()) {
            if (shop.owner().equals(who.getUuid())) {
                // Real turnover, in emeralds, rather than a count of sales.
                // A shop that shifted four hundred emeralds of bread can
                // explain four hundred emeralds.
                limit += shop.turnover();
            }
        }
        for (TrapHouse.House house : TrapHouse.all()) {
            // By founder name rather than uuid: a casino card is a bearer
            // instrument and whoever holds it runs the floor, but the office
            // asks whose NAME is over the door.
            if (who.getGameProfile().getName().equals(house.founder)) {
                limit += (int) Math.min(Integer.MAX_VALUE, house.handle / 8);
            }
        }
        return limit - WASHED.getOrDefault(who.getGameProfile().getName(), 0);
    }

    /**
     * Pay somebody in money the city has never heard of.
     *
     * NOT through {@link TrapMarket#pay}. Dirty emeralds are an item, not a
     * balance: the market does not count them, no shop takes them, and no wage
     * comes out of them. They enter the money supply at the drum and nowhere
     * else, which is what makes the wash a real step rather than a formality.
     */
    public static void payDirty(ServerPlayerEntity who, int amount) {
        if (amount <= 0) {
            return;
        }
        // In stacks the item can actually hold. A single ItemStack of 226 is
        // accepted by every server-side check and then fails to ENCODE to the
        // client -- "Value must be within range [1;99]" -- once a tick, for as
        // long as it exists. A big payday left one lying in a field spamming
        // the log 3000 times before anybody read it. LaundryBlock got this
        // right on the way out; this is the way in.
        int left = amount;
        int most = TrapContent.dirtyEmerald.getMaxCount();
        while (left > 0) {
            int lot = Math.min(left, most);
            who.getInventory().offerOrDrop(new ItemStack(TrapContent.dirtyEmerald, lot));
            left -= lot;
        }
    }

    /**
     * A drum finished. Clear what it can of the day's exposure.
     *
     * Only up to what the owner's businesses could plausibly have taken --
     * washing is passing money off as takings, and takings need a till that
     * really turned over. Beyond that the money comes out clean but the office
     * still has questions, which is the tension the whole step was for.
     */
    public static void washed(ServerPlayerEntity who, int gross, int cut) {
        String name = who.getGameProfile().getName();
        int cover = Math.max(0, washLimit(who));
        WASHED.merge(name, Math.min(gross, cover), Integer::sum);
        if (cut > 0) {
            TrapCity.receive(cut, TrapCity.Duty.INCOME);
        }
        save();
        if (gross > cover) {
            who.sendMessage(Text.literal("  " + (gross - cover) + "e of that has nothing "
                    + "behind it. Open a shop, or expect a letter.")
                    .formatted(Formatting.DARK_GRAY), false);
        }
    }

    // --- the constitution -----------------------------------------------------

    /**
     * The law, as a book, built fresh every time anybody asks for one.
     *
     * There is no stale copy to sweep because there is no stored copy at all:
     * {@code /law} hands you a book written a millisecond ago. The design
     * called for rewriting inventories on a law change and this is the same
     * promise with none of the machinery -- a book in a chest is a newspaper,
     * and nobody expects a newspaper to update itself.
     */
    public static ItemStack constitution() {
        List<RawFilteredPair<Text>> pages = new ArrayList<>();
        pages.add(page(title("THE CONSTITUTION\n\n")
                .append(body(TrapCity.founded()
                        ? "The law of this city, as it stands today.\n\n"
                        : "There is no city. Nothing below is in force.\n\n"))
                .append(hint("Ask again after a budget."))));

        MutableText rates = title("DUTIES\n\n");
        for (TrapCity.Duty duty : TrapCity.Duty.values()) {
            rates.append(body(duty.display() + "  " + TrapCity.chargedRate(duty) + "%\n"));
        }
        pages.add(page(rates.append(Text.literal("\n"))
                .append(hint("Nothing sold to customers or dealers is taxed."))));

        MutableText acts = title("ACTS IN FORCE\n\n");
        boolean any = false;
        for (TrapCity.Act act : TrapCity.Act.values()) {
            if (TrapCity.inForce(act)) {
                any = true;
                acts.append(body(act.display() + "\n"))
                        .append(hint("  " + act.blurb() + ". Day "
                                + TrapCity.passedOn(act) + ".\n"));
            }
        }
        pages.add(page(any ? acts : acts.append(body("None. The council is quiet.\n\n"))
                .append(hint("They pass themselves when the city needs them."))));

        // One page per work. Four names and four prices crammed onto one page
        // wrapped into an unreadable column and, worse, never said what any of
        // them DID or where you buy them -- which is the only thing somebody
        // reading a constitution about public works wants to know.
        pages.add(page(title("PUBLIC WORKS\n\n")
                .append(body("Things the purse buys for the whole town.\n\n"))
                .append(body("Anybody may buy one, at the vault. Everybody is "
                        + "told who did.\n\n"))
                .append(hint("The purse holds " + TrapCity.treasury() + "e."))));
        for (TrapCity.Work work : TrapCity.Work.values()) {
            pages.add(page(title(work.display().toUpperCase(java.util.Locale.ROOT) + "\n\n")
                    .append(body(work.blurb() + ".\n\n"))
                    .append(TrapCity.built(work)
                            ? body("BUILT. Nothing more to pay.\n\n")
                            : body(work.cost() + "e out of the purse.\n\n"))
                    .append(hint(TrapCity.built(work) ? "In force."
                            : TrapCity.treasury() >= work.cost()
                            ? "The purse could cover it today."
                            : "The purse is " + (work.cost() - TrapCity.treasury())
                            + "e short."))));
        }

        pages.add(page(title("THE REVENUE OFFICE\n\n")
                .append(body("It reads what came in and what you declared.\n\n"))
                .append(body("Over " + LOOKS_AWAY + "e a day unexplained and it "
                        + "assesses you.\n\n"))
                .append(warn("Owe it and you are watched until you pay."))));

        pages.add(page(title("DIRTY MONEY\n\n")
                .append(body("The street pays in dirty emeralds.\n\n"))
                .append(body("No shop takes them. No wage comes out of "
                        + "them. They are not money yet.\n\n"))
                .append(hint("They become money in a laundry drum."))));

        pages.add(page(title("THE DRUM\n\n")
                .append(body("Put them in, wait, take them out.\n\n"))
                .append(body(Math.round(WASH_CUT * 100) + "% goes down the "
                        + "drain.\n\n"))
                .append(warn("Wash more than your shops could have taken and "
                        + "the office still asks."))));

        return book("The Constitution", pages);
    }

    // --- commands -------------------------------------------------------------

    private static void registerCommands() {
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register(
                (dispatcher, access, env) -> {
                    dispatcher.register(net.minecraft.server.command.CommandManager
                            .literal("law")
                            .executes(context -> {
                                ServerPlayerEntity who = context.getSource().getPlayer();
                                if (who == null) {
                                    return 0;
                                }
                                who.getInventory().offerOrDrop(constitution());
                                who.sendMessage(Text.literal("The law, as it stands.")
                                        .formatted(Formatting.GRAY), true);
                                return 1;
                            })
                            .then(net.minecraft.server.command.CommandManager.literal("pay")
                                    .executes(context -> {
                                        ServerPlayerEntity who = context.getSource().getPlayer();
                                        if (who == null) {
                                            return 0;
                                        }
                                        String no = settle(who);
                                        if (no != null) {
                                            who.sendMessage(Text.literal(no)
                                                    .formatted(Formatting.GRAY), false);
                                        }
                                        return 1;
                                    })));
                });
    }

    /** The council changed something; anybody holding a book gets told. */
    public static void lawChanged(MinecraftServer server) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            player.sendMessage(Text.literal("  The constitution has moved. ")
                    .formatted(Formatting.DARK_GRAY)
                    .append(Text.literal("/law").formatted(Formatting.GREEN)
                            .styled(style -> style.withClickEvent(
                                    new net.minecraft.text.ClickEvent.RunCommand("/law")))), false);
        }
    }

    // --- persistence ----------------------------------------------------------

    private static void load(MinecraftServer server) {
        SERVER = server;
        saveFile = server.getSavePath(WorldSavePath.ROOT).resolve("trapcraft-law.txt");
        OWED.clear();
        WASHED.clear();
        day = -1;
        try {
            if (!Files.exists(saveFile)) {
                return;
            }
            for (String line : Files.readAllLines(saveFile)) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 3 && parts[0].equals("owed")) {
                    OWED.put(parts[1], Integer.parseInt(parts[2]));
                }
            }
        } catch (Exception failure) {
            TrapCraft.LOGGER.warn("couldn't read the revenue book: {}", failure.toString());
        }
    }

    private static void save() {
        if (saveFile == null) {
            return;
        }
        try {
            StringBuilder out = new StringBuilder();
            OWED.forEach((who, amount) -> out.append("owed ").append(who).append(' ')
                    .append(amount).append('\n'));
            Files.writeString(saveFile, out.toString());
        } catch (Exception failure) {
            TrapCraft.LOGGER.warn("couldn't save the revenue book: {}", failure.toString());
        }
    }

    // --- book plumbing --------------------------------------------------------

    private static ItemStack book(String name, List<RawFilteredPair<Text>> pages) {
        ItemStack stack = new ItemStack(Items.WRITTEN_BOOK);
        stack.set(DataComponentTypes.WRITTEN_BOOK_CONTENT, new WrittenBookContentComponent(
                RawFilteredPair.of(name), "The City", 0, pages, true));
        return stack;
    }

    private static RawFilteredPair<Text> page(Text text) {
        return RawFilteredPair.of(text);
    }

    private static MutableText title(String text) {
        return Text.literal(text).formatted(Formatting.DARK_RED, Formatting.BOLD);
    }

    private static MutableText body(String text) {
        return Text.literal(text).formatted(Formatting.BLACK);
    }

    private static MutableText hint(String text) {
        return Text.literal(text).formatted(Formatting.DARK_GRAY, Formatting.ITALIC);
    }

    private static MutableText warn(String text) {
        return Text.literal(text).formatted(Formatting.DARK_RED);
    }
}
