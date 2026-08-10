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
        for (TrapShops.Shelf shelf : TrapShops.all()) {
            if (shelf.owner().equals(who.getUuid())) {
                limit += shelf.sold() * 24;
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

    /** @return why it didn't happen, or null if it did */
    public static String wash(ServerPlayerEntity who, int amount) {
        if (!TrapCity.founded()) {
            return "There's no city, so there's nobody to hide it from.";
        }
        if (amount <= 0) {
            return "How much?";
        }
        int room = washLimit(who);
        if (room <= 0) {
            return "You've no business that could explain it. Open a shop, or a floor.";
        }
        int going = Math.min(amount, room);
        int cut = Math.max(1, Math.round(going * WASH_CUT));
        if (TrapMarket.wealthOf(who) < cut) {
            return "Washing " + going + "e costs " + cut + "e and you haven't got it.";
        }
        TrapMarket.collect(who, cut);
        TrapCity.receive(cut, TrapCity.Duty.INCOME);
        WASHED.merge(who.getGameProfile().getName(), going, Integer::sum);
        TrapLedger.record(who, TrapLedger.Source.TAX, -cut);
        save();
        who.sendMessage(Text.literal("Through the books. ")
                .formatted(Formatting.GREEN, Formatting.BOLD)
                .append(Text.literal(going + "e is now takings, and it cost " + cut + "e.")
                        .formatted(Formatting.GRAY))
                .append(Text.literal("\n  " + Math.max(0, room - going)
                        + "e of cover left today.").formatted(Formatting.DARK_GRAY)), false);
        return null;
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

        MutableText works = title("PUBLIC WORKS\n\n");
        for (TrapCity.Work work : TrapCity.Work.values()) {
            works.append(body(work.display() + (TrapCity.built(work) ? "  BUILT" : "  "
                    + work.cost() + "e") + "\n"));
        }
        pages.add(page(works.append(Text.literal("\n"))
                .append(hint("The purse holds " + TrapCity.treasury() + "e."))));

        pages.add(page(title("THE REVENUE OFFICE\n\n")
                .append(body("It reads what came in and what you declared.\n\n"))
                .append(body("Over " + LOOKS_AWAY + "e a day unexplained and it "
                        + "assesses you.\n\n"))
                .append(warn("Owe it and you are watched until you pay."))));

        pages.add(page(title("THE WASH\n\n")
                .append(body("/wash <amount>\n\n"))
                .append(body("Runs dirty money through your own till at "
                        + Math.round(WASH_CUT * 100) + "%.\n\n"))
                .append(hint("Capped by what that till really turned over."))));

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
                    dispatcher.register(net.minecraft.server.command.CommandManager
                            .literal("wash")
                            .executes(context -> {
                                ServerPlayerEntity who = context.getSource().getPlayer();
                                if (who == null) {
                                    return 0;
                                }
                                who.sendMessage(Text.literal("Cover available today: ")
                                        .formatted(Formatting.GRAY)
                                        .append(Text.literal(Math.max(0, washLimit(who)) + "e")
                                                .formatted(Formatting.WHITE))
                                        .append(Text.literal("   /wash <amount>")
                                                .formatted(Formatting.GREEN)), false);
                                return 1;
                            })
                            .then(net.minecraft.server.command.CommandManager
                                    .argument("amount", com.mojang.brigadier.arguments
                                            .IntegerArgumentType.integer(1))
                                    .executes(context -> {
                                        ServerPlayerEntity who = context.getSource().getPlayer();
                                        if (who == null) {
                                            return 0;
                                        }
                                        String no = wash(who, com.mojang.brigadier.arguments
                                                .IntegerArgumentType.getInteger(context,
                                                        "amount"));
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
