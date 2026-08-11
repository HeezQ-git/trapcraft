package dev.heezq.trapcraft;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * What the town has to spend, and the one place it comes from.
 *
 * Before this, three separate systems minted emeralds out of nothing: a
 * tenant's rent, a shelf sale, and every stake laid on a casino floor. The
 * town was a fountain -- it had no income, so it had no budget, so nothing a
 * player built could make it richer or poorer, and "more residents means more
 * trade" was true only because the spawn rate happened to read the population.
 *
 * Now there is exactly one mint, at payday, and everything downstream moves
 * money that already exists. That is not bookkeeping pedantry. It is the
 * difference between a town whose custom you can grow and a town whose custom
 * is a constant you are decorating.
 *
 * <h2>Why the purse is one number and not a wallet each</h2>
 *
 * Because a wallet each is a schedule each, a job each, and a save file that
 * grows with the population. The town is modelled the way a council models it
 * -- in aggregate -- and the townspeople you watch walk to a shelf are a
 * SAMPLE of that, not the thing itself. Nothing about the economy depends on
 * one of them reaching its destination, which is the only reason the visible
 * half is affordable at all.
 *
 * @see HomeSurvey#wageDue for what a household earns
 * @see TrapMath#townDemand for what the purse does to trade
 */
public final class TrapPayroll {

    private static long purse;
    /** Running totals for the city log. Reset with the world, not the day. */
    private static long wagesPaid;
    private static long incomeTax;
    private static Path saveFile;

    private TrapPayroll() {
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(TrapPayroll::load);
    }

    public static long purse() {
        return purse;
    }

    public static long wagesPaid() {
        return wagesPaid;
    }

    public static long incomeTax() {
        return incomeTax;
    }

    /**
     * A household is paid: the only money this mod still makes on the town's
     * behalf.
     *
     * Income tax comes off the top rather than being billed later, because a
     * wage is taxed before it is in anybody's hand -- and because the
     * alternative is a second place the purse can be emptied by something that
     * is not a purchase, which is a much harder thing to reason about when the
     * shops go quiet.
     */
    public static void earned(int gross) {
        if (gross <= 0) {
            return;
        }
        TrapMarket.minted(gross);
        int duty = TrapCity.dutyOn(gross, TrapCity.Duty.INCOME);
        TrapCity.receive(duty, TrapCity.Duty.INCOME);
        purse += gross - duty;
        wagesPaid += gross;
        incomeTax += duty;
        save();
    }

    /** Could the town cover this? A pure read -- see {@link #spend}. */
    public static boolean afford(int amount) {
        return amount <= 0 || purse >= amount;
    }

    /**
     * Take it out of the purse, or refuse.
     *
     * A boolean rather than a clamp, on purpose. Every caller must fail CLOSED
     * -- no sale, no stake, no rent -- because a half-paid transaction is a
     * duplication bug wearing a hat. Check {@link #afford} BEFORE you take
     * goods off a shelf, not after: a town that turns out to be broke one line
     * later has already walked off with the shopping.
     */
    public static boolean spend(int amount) {
        if (amount <= 0) {
            return true;
        }
        if (purse < amount) {
            return false;
        }
        purse -= amount;
        save();
        return true;
    }

    /**
     * Money coming back to the town: a casino payout, mostly.
     *
     * Not a mint. A punter who wins is handed money the house already had,
     * which the house got out of this purse in the first place. Without this
     * the town leaks its entire wage bill into casino balances and quietly
     * stops shopping -- which presents as "the shops are broken", three
     * systems away from the floor that actually ate the money.
     */
    public static void credit(int amount) {
        if (amount <= 0) {
            return;
        }
        purse += amount;
        save();
    }

    // --- persistence ----------------------------------------------------------

    private static void load(MinecraftServer server) {
        saveFile = server.getSavePath(WorldSavePath.ROOT).resolve("trapcraft-town.txt");
        purse = 0;
        wagesPaid = 0;
        incomeTax = 0;
        try {
            if (!Files.exists(saveFile)) {
                return;
            }
            for (String row : Files.readAllLines(saveFile)) {
                String[] parts = row.trim().split("\\s+");
                if (parts.length < 2) {
                    continue;
                }
                switch (parts[0]) {
                    case "purse" -> purse = Long.parseLong(parts[1]);
                    case "wages" -> wagesPaid = Long.parseLong(parts[1]);
                    case "tax" -> incomeTax = Long.parseLong(parts[1]);
                    default -> {
                    }
                }
            }
        } catch (Exception failure) {
            TrapCraft.LOGGER.warn("couldn't read the town purse: {}", failure.toString());
        }
    }

    private static void save() {
        if (saveFile == null) {
            return;
        }
        try {
            Files.writeString(saveFile, "purse " + purse + "\nwages " + wagesPaid
                    + "\ntax " + incomeTax + '\n');
        } catch (Exception failure) {
            TrapCraft.LOGGER.warn("couldn't save the town purse: {}", failure.toString());
        }
    }
}
