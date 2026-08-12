package dev.heezq.trapcraft;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.ItemScatterer;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Somebody owns the floor now.
 *
 * A casino is a bankroll and a set of machines wired to it. The bankroll lives
 * here, in the ledger, not on the card -- the card is the KEY, not the vault.
 * That distinction is the whole design: a machine has to be able to take a
 * loser's money and pay a winner at four in the morning while the owner is
 * offline and their card is in a chest three thousand blocks away. A balance
 * that rode on the itemstack could not be reached, so the machines would have
 * had to invent the money, and inventing money is the one thing an economy
 * this careful about {@link TrapMarket#circulate} cannot survive.
 *
 * What the card carries is the casino's id, and whoever holds it is the owner
 * -- exactly as asked. It is a bearer instrument. Steal one and you have
 * stolen a business. It is fireproof, because the difference between "somebody
 * took my casino" and "I dropped it in lava" is the difference between a story
 * and a bug report, and only one of those is fun.
 *
 * <h2>Where the money goes</h2>
 *
 * Bets at an OWNED machine move emeralds from one player to another, so they
 * use {@link TrapMarket#collect} and {@link TrapMarket#handOver} rather than
 * take/pay: nothing is created and nothing is destroyed, and the vault is
 * counted as part of the money supply the same way a chest full of emeralds
 * is. Gambling at a player's casino is zero-sum for the world, which is the
 * truth of it.
 *
 * An UNOWNED machine keeps the old behaviour -- take/pay, money in and out of
 * the world -- because there is nobody on the other side of the table.
 *
 * <h2>Breaking the bank</h2>
 *
 * A machine will not accept a stake its house cannot cover at that game's top
 * multiple (see {@link #covers}). That is a real table limit and the reason to
 * keep a float. The slot machine can still, very rarely, beat its own limit by
 * landing several lines at once; when a payout runs the vault dry the player
 * is paid every emerald that was in it and the casino goes dark until somebody
 * puts money back. Nobody is short-changed and no emerald is minted.
 */
public final class TrapHouse {

    /** One casino. */
    public static final class House {
        public final UUID id;
        /** Whoever founded it, for the plaque. Ownership is the card, not this. */
        public final String founder;
        public String name;
        public long balance;
        /** Everything ever staked at its machines. */
        public long handle;
        /** Everything ever paid out. */
        public long paid;
        public int plays;
        /**
         * How well known the place is, 0..100.
         *
         * Bought with payouts and lost by turning people away. See
         * {@link TrapMath#floorPull}.
         */
        public int rep;
        /** How hooked the regulars are, 0..100. Built by play, decays quietly. */
        public int addiction;
        /** Counted between beats, then folded into the two stats and reset. */
        int roundsThisBeat;
        int turnedAwayThisBeat;
        long handleThisBeat;
        /** Everything the floor has ever paid to stay open: upkeep and the cut. */
        public long costs;
        /**
         * What the owner has personally won or lost at their own machines.
         *
         * Kept out of the takings entirely. It is their money going round in a
         * circle, and folding it into the handle made a floor's margin read
         * at more than twice what the trade was actually paying.
         */
        public long ownPlay;
        /** Beats running with the cut unpaid. Somebody comes for the third. */
        int owing;
        /** Somebody watching the floor. Stops the skim and spots the cheats. */
        public boolean pitBoss;
        /** Beats left of running generous. See TrapMath.LOOSE_BEATS. */
        public int looseBeats;
        /** Beats until another spell can be called, or another round stood. */
        public int looseCooldown;
        public int compCooldown;
        /**
         * What's behind each counter, keyed by that bar's own wire.
         *
         * One shelf per bar, not one per casino. A second counter used to be
         * four blocks of decoration sharing the first one's eighteen stacks,
         * so "we run dry every night" had no answer except a bigger farm.
         * Four bars is four shelves and four times the room -- and they all
         * feed the same floor, because a punter is handed the best thing
         * standing on any of them.
         *
         * Held on the house rather than in block entities for the same reason
         * a dealer's stock is: a text ledger anybody can read beats a binary
         * blob.
         */
        public final Map<String, List<ItemStack>> bars = new LinkedHashMap<>();

        /** The shelf behind one counter, made the first time it is stocked. */
        public List<ItemStack> shelf(String wire) {
            return bars.computeIfAbsent(wire, made -> new ArrayList<>());
        }

        /** Is there anything left to hand anybody, on any counter? */
        public boolean dryBar() {
            for (List<ItemStack> shelf : bars.values()) {
                for (ItemStack stack : shelf) {
                    if (!stack.isEmpty()) {
                        return false;
                    }
                }
            }
            return true;
        }

        House(UUID id, String founder, String name) {
            this.id = id;
            this.founder = founder;
            this.name = name;
        }

        /**
         * What the house has actually kept, after what it costs to be open.
         *
         * Net, not gross. The gross figure flattered a ten-machine floor by a
         * third -- it read "kept 4112e" on a night that had quietly spent
         * 1101e keeping the lights on -- and a business you are judging by the
         * wrong number is a business you cannot make decisions about.
         */
        public long profit() {
            return handle - paid - costs;
        }

        /** What it has kept before its running costs, which is not the same thing. */
        public long grossProfit() {
            return handle - paid;
        }

        /** The margin it has actually run at, in percent. */
        public int edge() {
            return handle <= 0 ? 0 : Math.round(profit() * 100.0f / handle);
        }

        /** How hard this floor pulls people in, as a multiplier. */
        public float pull() {
            return TrapMath.floorPull(rep, addiction, TrapHomes.population());
        }

        /** Is the floor running generous right now? */
        public boolean loose() {
            return looseBeats > 0;
        }

        void nudge(int repDelta, int addictionDelta) {
            rep = Math.max(0, Math.min(TrapMath.HOUSE_STAT_MAX, rep + repDelta));
            addiction = Math.max(0, Math.min(TrapMath.HOUSE_STAT_MAX,
                    addiction + addictionDelta));
        }
    }

    private static final Map<UUID, House> HOUSES = new LinkedHashMap<>();
    /** "dimension x y z" -> casino id. */
    private static final Map<String, UUID> WIRES = new HashMap<>();
    /**
     * How worn each machine is, keyed the same way as a wire.
     *
     * On the MACHINE rather than the house, because a floor's condition is the
     * condition of the particular cabinets on it -- the one in the corner
     * nobody plays stays fresh while the one by the door falls apart, and
     * walking round finding out which is which is the job.
     */
    private static final Map<String, Integer> WEAR = new HashMap<>();
    private static Path saveFile;

    private TrapHouse() {
    }

    // --- top multiples --------------------------------------------------------
    //
    // What each game can pay per emerald staked, used as the table limit. These
    // are the realistic tops, not the theoretical ones: a 5x5 grid of one
    // symbol pays 150x and would put the limit somewhere absurd, so the slot
    // is limited at its single-line top and the rare multi-line monster is
    // allowed to break the bank instead.

    public static final int TOP_SLOT = (int) TrapMath.PAY_RUN5;
    public static final int TOP_ROULETTE = TrapMath.ROULETTE_STRAIGHT;
    public static final int TOP_DROP = 26;
    public static final int TOP_CLIMB = 12;
    public static final int TOP_TOSS = (int) TrapMath.TOSS_EDGE_PAY;
    public static final int TOP_BLACKJACK = 5;
    /**
     * Scratchers, at a card that actually turns up.
     *
     * A card can theoretically reach 210x. About one in six thousand beats
     * this and empties the vault, which is the same bargain the slot makes
     * and for the same reason: limiting at the ceiling would put a 32e card
     * out of reach of any casino anybody will actually build.
     */
    public static final int TOP_SCRATCH = 60;

    // --- lookups --------------------------------------------------------------

    public static House byId(UUID id) {
        return id == null ? null : HOUSES.get(id);
    }

    /** The casino a card is the key to, or null if it hasn't been signed. */
    public static House of(ItemStack card) {
        String id = card.get(TrapComponents.casino);
        if (id == null) {
            return null;
        }
        try {
            return HOUSES.get(UUID.fromString(id));
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    /** The casino this machine pays into, or null if it stands on its own. */
    public static House at(World world, BlockPos pos) {
        return HOUSES.get(WIRES.get(key(world, floorOf(world, pos))));
    }

    public static Collection<House> all() {
        return HOUSES.values();
    }

    /** Every emerald sitting in every vault. Counted as money by the market. */
    public static long floatHeld() {
        long total = 0;
        for (House house : HOUSES.values()) {
            total += house.balance;
        }
        return total;
    }

    /** The wire key for whatever machine is at this position. */
    public static String wireAt(World world, BlockPos pos) {
        return key(world, floorOf(world, pos));
    }

    /** How worn the machine at this wire is, 0 fresh .. 100 out of order. */
    public static int wearAt(String wire) {
        return WEAR.getOrDefault(wire, 0);
    }

    public static int wearAt(World world, BlockPos pos) {
        return wearAt(key(world, floorOf(world, pos)));
    }

    public static boolean broken(World world, BlockPos pos) {
        return wearAt(world, pos) >= TrapMath.WEAR_BROKEN;
    }

    /** One round's worth of use. Returns true if that was the one that broke it. */
    public static boolean wearOne(String wire) {
        int worn = Math.min(TrapMath.WEAR_BROKEN, wearAt(wire) + 1);
        WEAR.put(wire, worn);
        save();
        return worn >= TrapMath.WEAR_BROKEN;
    }

    /** What putting this one right would cost. */
    public static int repairCost(World world, BlockPos pos) {
        return wearAt(world, pos) * TrapMath.REPAIR_COST_PER_POINT;
    }

    /**
     * Put a machine right, on the house.
     *
     * Paid out of the vault rather than the mechanic's pocket, because it is a
     * business expense and because charging whoever happens to be holding the
     * hammer would mean the sensible move is never to pick one up.
     *
     * @return what it cost, or -1 if the vault couldn't cover it
     */
    public static int repair(World world, BlockPos pos) {
        String wire = key(world, floorOf(world, pos));
        House house = HOUSES.get(WIRES.get(wire));
        int cost = wearAt(wire) * TrapMath.REPAIR_COST_PER_POINT;
        if (cost <= 0) {
            return 0;
        }
        if (house != null) {
            if (house.balance < cost) {
                return -1;
            }
            house.balance -= cost;
            house.costs += cost;
        }
        WEAR.remove(wire);
        save();
        return cost;
    }

    /**
     * Drop wires whose machine is no longer there.
     *
     * The break hook only fires when a PLAYER mines it. A creeper, a piston, a
     * /fill, or a room rebuilt somewhere else left the wire behind -- and a
     * wire with nothing on it was a machine forever: billed upkeep every beat,
     * counted as capacity the floor's name is judged against, and listed OUT
     * OF ORDER at coordinates with nothing at them. Swept from the beat, which
     * already looks at every wired block once every thirty seconds.
     */
    public static void forget(Collection<String> gone) {
        boolean any = false;
        for (String wire : gone) {
            // Whatever was on that counter went with it. Leaving the shelf
            // behind would keep serving punters out of a bar nobody can see,
            // let alone restock.
            for (House house : HOUSES.values()) {
                house.bars.remove(wire);
            }
            WEAR.remove(wire);
            if (WIRES.remove(wire) != null) {
                any = true;
                TrapCraft.LOGGER.info("unwired {}: nothing there any more", wire);
            }
        }
        if (any) {
            save();
        }
    }

    /** The shabbiest cabinet on this floor, 0 fresh .. 100 in pieces. */
    public static int worstWear(House house) {
        int worst = 0;
        for (var wire : WIRES.entrySet()) {
            if (wire.getValue().equals(house.id)) {
                worst = Math.max(worst, wearAt(wire.getKey()));
            }
        }
        return worst;
    }

    /** Average condition of this floor, 0 fresh .. 100 in pieces. */
    public static int averageWear(House house) {
        int total = 0;
        int machines = 0;
        for (Map.Entry<String, UUID> wire : WIRES.entrySet()) {
            if (wire.getValue().equals(house.id)) {
                total += wearAt(wire.getKey());
                machines++;
            }
        }
        return machines == 0 ? 0 : total / machines;
    }

    /** Every wired machine, as "dimension x y z" -> casino id. Read-only. */
    public static Map<String, UUID> wires() {
        return java.util.Collections.unmodifiableMap(WIRES);
    }

    /** Turn a wire key back into a position. Null if it isn't one. */
    public static BlockPos posOf(String wire) {
        String[] parts = wire.split(" ");
        if (parts.length != 4) {
            return null;
        }
        try {
            return new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
                    Integer.parseInt(parts[3]));
        } catch (NumberFormatException malformed) {
            return null;
        }
    }

    /** The dimension half of a wire key. */
    public static String worldOf(String wire) {
        int space = wire.indexOf(' ');
        return space < 0 ? wire : wire.substring(0, space);
    }

    /**
     * Money a punter brought in from outside, or took away with them.
     *
     * Deliberately NOT routed through TrapMarket. A punter's emeralds were
     * never in a player's pocket, so there is nothing to collect and nothing
     * to hand over -- the vault simply grows, and the census in
     * {@link TrapMarket#resample} sees it grow because a casino float is
     * counted as money. Which makes a busy floor mildly inflationary, exactly
     * as a real one is, and the moving anchor absorbs it.
     */
    public static void punterStaked(House house, int amount) {
        house.balance += amount;
        house.handle += amount;
        house.plays++;
        house.roundsThisBeat++;
        house.handleThisBeat += amount;
        save();
    }

    /**
     * What a punter walked out with. Never more than the vault holds.
     *
     * A winner is the only advertising this place has, so paying one is what
     * buys the reputation that brings the next four in. Which is the loop that
     * stops a casino being a machine for hoarding: hold on to everything and
     * the room empties.
     */
    public static int punterWon(House house, int amount) {
        int given = (int) Math.min(Math.max(0, amount), house.balance);
        house.balance -= given;
        house.paid += given;
        save();
        return given;
    }

    /**
     * Somebody walked up and couldn't play -- no free machine, or a vault too
     * thin to cover the smallest bet there is.
     *
     * The single most expensive thing that can happen to a floor's name, and
     * deliberately so: a queue at the door is what a room that has outgrown
     * itself looks like from outside, and it is the one problem the owner can
     * always fix by building another cabinet or putting money behind the ones
     * they have.
     */
    public static void turnedAway(House house) {
        house.turnedAwayThisBeat++;
        house.nudge(-3, 0);
        save();
    }

    /**
     * One beat of running a casino.
     *
     * The lights, the felt and whoever sweeps up cost money whether or not
     * anybody plays, so an over-built floor with no trade bleeds and a floor
     * nobody visits in the daytime runs at a loss until the evening. That
     * standing cost is what makes the number of machines a DECISION rather
     * than something to maximise.
     *
     * Then both stats are moved towards what the floor currently deserves --
     * see {@link TrapMath#houseRepTarget} and {@link TrapMath#addictionAfter}.
     *
     * @param free how many of this house's machines are standing empty
     */
    public static void beat(House house, int varieties, int machines, int free) {
        int bill = machines * TrapMath.MACHINE_UPKEEP
                + TrapMath.protectionOn(house.handleThisBeat)
                + (house.pitBoss ? TrapMath.PIT_BOSS_WAGE : 0);
        house.handleThisBeat = 0;
        if (house.balance >= bill) {
            house.balance -= bill;
            house.costs += bill;
            house.owing = 0;
        } else {
            // Couldn't pay. The room goes dark, word travels, and the tab is
            // remembered -- see TrapFloor, which sends somebody round for it.
            house.costs += house.balance;
            house.balance = 0;
            house.owing++;
            house.nudge(-6, 0);
        }
        house.rep = TrapMath.repAfter(house.rep, TrapMath.houseRepTarget(
                varieties, machines, house.balance, free, house.turnedAwayThisBeat,
                averageWear(house), house.loose(), house.dryBar()));
        // A loose spell is worth double to the regulars, which is most of why
        // anybody would ever call one.
        house.addiction = TrapMath.addictionAfter(house.addiction,
                house.loose() ? house.roundsThisBeat * 2 : house.roundsThisBeat);
        house.roundsThisBeat = 0;
        house.turnedAwayThisBeat = 0;
        if (house.looseBeats > 0) {
            house.looseBeats--;
        }
        if (house.looseCooldown > 0) {
            house.looseCooldown--;
        }
        if (house.compCooldown > 0) {
            house.compCooldown--;
        }
        save();
    }

    /** Where this casino's machines are, for the plaque. */
    public static List<String> machinesOf(House house) {
        List<String> found = new ArrayList<>();
        WIRES.forEach((where, id) -> {
            if (id.equals(house.id)) {
                found.add(where);
            }
        });
        return found;
    }

    public static int machineCount(House house) {
        int count = 0;
        for (UUID id : WIRES.values()) {
            if (id.equals(house.id)) {
                count++;
            }
        }
        return count;
    }

    // --- founding -------------------------------------------------------------

    /**
     * Sign a blank card and open a casino.
     *
     * The name comes off the card, so an anvil is the naming screen -- there
     * is no way to type text into a chest GUI, and adding a whole sign-editing
     * dance for one string would be more machinery than the feature is worth.
     */
    public static House found(ServerPlayerEntity owner, ItemStack card, String name) {
        House house = new House(UUID.randomUUID(), owner.getGameProfile().getName(), name);
        HOUSES.put(house.id, house);
        card.set(TrapComponents.casino, house.id.toString());
        save();
        return house;
    }

    // --- wiring ---------------------------------------------------------------

    /**
     * Is this something a card can be wired to?
     *
     * The bar is not a machine -- nobody bets at it and it holds no seat --
     * but it belongs to a house and is wired the same way, so the card, the
     * break hook and the wire ledger all treat it alike.
     */
    public static boolean isFitting(Block block) {
        return isMachine(block) || block == TrapContent.casinoBar;
    }

    /** Is this a thing that takes bets? */
    public static boolean isMachine(Block block) {
        return block == TrapContent.slotMachine || block == TrapContent.roulette
                || block == TrapContent.plinko || block == TrapContent.climb
                || block == TrapContent.toss || block == TrapContent.blackjack
                || block == TrapContent.scratch;
    }

    /**
     * The block a machine is wired by.
     *
     * The slot machine is two blocks tall, and a wire on the upper half would
     * be a second, invisible machine that never pays anybody.
     */
    public static BlockPos floorOf(World world, BlockPos pos) {
        return floorOf(world.getBlockState(pos), pos);
    }

    private static BlockPos floorOf(BlockState state, BlockPos pos) {
        if (state.isOf(TrapContent.slotMachine)
                && state.get(SlotMachineBlock.HALF) == DoubleBlockHalf.UPPER) {
            return pos.down();
        }
        return pos;
    }

    private static String key(World world, BlockPos pos) {
        return world.getRegistryKey().getValue() + " "
                + pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    /**
     * Put the owner's name over the door.
     *
     * The title bar is the only place a player standing at a machine can learn
     * whose money they are playing against, and knowing that is half of why
     * owning one is interesting.
     */
    public static Text sign(MutableText game, House house) {
        return house == null ? game
                : game.copy().append(plain("  at  ").formatted(Formatting.DARK_GRAY))
                .append(plain(house.name).formatted(Formatting.WHITE));
    }

    /**
     * The two lines every machine prints about whose table it is.
     *
     * Empty for an unowned machine, so a floor that nobody has claimed reads
     * exactly as it always did rather than growing a row of "House: nobody".
     */
    public static List<Text> tableNote(House house, int topMultiple) {
        if (house == null) {
            return List.of();
        }
        int most = limit(house, topMultiple);
        return List.of(
                Text.empty(),
                plain("House  ").formatted(Formatting.DARK_GRAY)
                        .append(plain(house.name).formatted(Formatting.GOLD)),
                plain("Table limit  ").formatted(Formatting.DARK_GRAY)
                        .append(plain(most + "e").formatted(
                                most > 0 ? Formatting.WHITE : Formatting.RED)));
    }

    // --- the money ------------------------------------------------------------

    /**
     * Can this house stand a bet of this size?
     *
     * An unowned machine always can -- it is playing with the world's money,
     * not anybody's.
     */
    public static boolean covers(House house, int stake, int topMultiple) {
        return house == null || TrapMath.houseCovers(house.balance, stake, topMultiple);
    }

    /** The biggest bet this house will take on a game paying this much. */
    public static int limit(House house, int topMultiple) {
        return house == null ? Integer.MAX_VALUE
                : TrapMath.houseLimit(house.balance, topMultiple);
    }

    /**
     * Take a bet.
     *
     * Callers must already have checked {@link TrapMarket#wealthOf} and
     * {@link #covers}; this assumes the bet is good, exactly as
     * {@link TrapMarket#take} does.
     */
    public static void stake(ServerPlayerEntity player, House house, int amount) {
        TrapLedger.record(player, TrapLedger.Source.CASINO, -amount);
        // Gaming duty is on the HANDLE -- every stake laid, win or lose --
        // which is how every real one works and is the only version that
        // cannot be dodged by a lucky night. Winnings are not taxed again.
        TrapCity.charge(player, amount, TrapCity.Duty.GAMING);
        if (house == null) {
            TrapMarket.take(player, amount);
            return;
        }
        TrapMarket.collect(player, amount);
        house.balance += amount;
        house.plays++;
        // The owner playing their own machine is not trade. It moves emeralds
        // from their pocket into their own vault and back out again, and
        // counting it as takings is what made a floor look like it was earning
        // seven percent when the villagers were handing over three. A business
        // you are reading the wrong number off is one you cannot run.
        if (owns(player, house)) {
            house.ownPlay += amount;
        } else {
            house.handle += amount;
            house.handleThisBeat += amount;
        }
        save();
    }

    /** Is this player carrying the deed to this house? */
    public static boolean owns(ServerPlayerEntity player, House house) {
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isOf(TrapContent.casinoCard) && house.equals(of(stack))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Pay a winner.
     *
     * If the vault cannot cover it the player gets everything that was in it
     * and the house is left on nothing -- see the class note. Returns what was
     * actually handed over, which is only ever less than asked for when the
     * bank has been broken.
     */
    public static int payout(ServerPlayerEntity player, House house, int amount) {
        if (amount <= 0) {
            return 0;
        }
        if (house == null) {
            TrapMarket.pay(player, amount);
            TrapLedger.record(player, TrapLedger.Source.CASINO, amount);
            return amount;
        }
        int given = (int) Math.min(amount, house.balance);
        house.balance -= given;
        TrapLedger.record(player, TrapLedger.Source.CASINO, given);
        if (owns(player, house)) {
            house.ownPlay -= given;
        } else {
            house.paid += given;
        }
        TrapMarket.handOver(player, given);
        save();
        if (given < amount) {
            brokeTheBank(player, house, amount);
        }
        return given;
    }

    /**
     * Give a bet back -- a cleared roulette board, a hand that pushed.
     *
     * Not a payout: the money never left the player as a wager in any sense
     * that matters, so it must not count towards what the house has paid or it
     * would report an edge it never ran at.
     */
    public static void refund(ServerPlayerEntity player, House house, int amount) {
        if (amount <= 0) {
            return;
        }
        if (house == null) {
            TrapMarket.pay(player, amount);
            TrapLedger.record(player, TrapLedger.Source.CASINO, amount);
            return;
        }
        int given = (int) Math.min(amount, house.balance);
        house.balance -= given;
        house.handle -= given;
        TrapLedger.record(player, TrapLedger.Source.CASINO, given);
        TrapMarket.handOver(player, given);
        save();
    }

    /** Somebody hit harder than the vault could stand. Tell everybody. */
    private static void brokeTheBank(ServerPlayerEntity winner, House house, int owed) {
        TrapAwards.grant(winner, "broke_the_bank");
        ServerWorld world = winner.getWorld();
        world.spawnParticles(ParticleTypes.TOTEM_OF_UNDYING,
                winner.getX(), winner.getY() + 1.2, winner.getZ(), 160, 0.8, 1.0, 0.8, 0.6);
        world.playSound(null, winner.getBlockPos(), SoundEvents.ENTITY_ENDER_DRAGON_DEATH,
                SoundCategory.PLAYERS, 0.5F, 1.6F);

        MinecraftServer server = winner.getServer();
        if (server == null) {
            return;
        }
        Text line = plain("").append(plain("BROKE THE BANK  ")
                        .formatted(Formatting.GOLD, Formatting.BOLD))
                .append(plain(winner.getGameProfile().getName()).formatted(Formatting.WHITE))
                .append(plain(" cleaned out ").formatted(Formatting.GRAY))
                .append(plain(house.name).formatted(Formatting.LIGHT_PURPLE))
                .append(plain(" for every emerald it had. It was good for "
                        + owed + "e.").formatted(Formatting.GRAY));
        for (ServerPlayerEntity everyone : server.getPlayerManager().getPlayerList()) {
            everyone.sendMessage(line, false);
        }
    }

    /**
     * Sweep the owner's emeralds into the vault. Returns what went in.
     *
     * Logged to the ledger as CASINO, negative, and the plan said it would not
     * be -- on the grounds that moving your own money into your own vault is
     * carrying rather than earning. That is true of a wallet and wrong here.
     * A floor's profit only ever becomes the owner's when they withdraw it, so
     * deposits out and withdrawals in is the ONLY arrangement whose running
     * total is what the casino actually made them: put 1,000 in, take 1,200
     * out, and the ledger reads +200, which is the truth.
     */
    public static int deposit(ServerPlayerEntity owner, House house) {
        int found = TrapMarket.wealthOf(owner);
        if (found <= 0) {
            return 0;
        }
        TrapMarket.collect(owner, found);
        TrapLedger.record(owner, TrapLedger.Source.CASINO, -found);
        house.balance += found;
        save();
        return found;
    }

    /** Take money out. Returns what came out, which may be less than asked. */
    public static int withdraw(ServerPlayerEntity owner, House house, long wanted) {
        int taken = (int) Math.max(0, Math.min(Math.min(wanted, house.balance), Integer.MAX_VALUE));
        if (taken <= 0) {
            return 0;
        }
        house.balance -= taken;
        TrapMarket.handOver(owner, taken);
        TrapLedger.record(owner, TrapLedger.Source.CASINO, taken);
        save();
        return taken;
    }

    // --- hooks ----------------------------------------------------------------

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(TrapHouse::load);
        // Five seconds of exposure, which is the same bargain the player's own
        // inventory makes: a crash rolls back their emeralds too, so the two
        // sides of every bet fall over together rather than one of them
        // surviving and minting money.
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (dirty && server.getTicks() % 100 == 0) {
                flush();
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> flush());

        // Fires before the block's own onUse, which is the entire reason this
        // is an event and not six overrides: by the time SlotMachineBlock.onUse
        // runs, the gambling screen is already opening.
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world.isClient() || hand != Hand.MAIN_HAND
                    || !(player instanceof ServerPlayerEntity owner)) {
                return ActionResult.PASS;
            }
            ItemStack held = player.getStackInHand(hand);
            if (!held.isOf(TrapContent.casinoCard)) {
                return ActionResult.PASS;
            }
            BlockPos pos = hit.getBlockPos();
            if (!isFitting(world.getBlockState(pos).getBlock())) {
                return ActionResult.PASS;
            }
            wire(owner, held, world, floorOf(world, pos));
            return ActionResult.SUCCESS;
        });

        // A machine that has been mined is not a machine. Without this the wire
        // outlives it and the next thing built on that spot inherits a casino.
        PlayerBlockBreakEvents.AFTER.register((world, breaker, pos, state, entity) -> {
            if (!isFitting(state.getBlock())) {
                return;
            }
            // floorOf(world, pos) would read AIR here -- the block is already
            // gone by AFTER. The state we were handed is the one that broke,
            // so an upper half still resolves to the machine's own square.
            String wire = key(world, floorOf(state, pos));
            // A counter with stock on it hands it back rather than eating it.
            // The shelf belongs to this block now, so mining one is the same
            // as mining a chest.
            House house = HOUSES.get(WIRES.get(wire));
            if (house != null) {
                List<ItemStack> shelf = house.bars.remove(wire);
                if (shelf != null) {
                    for (ItemStack stack : shelf) {
                        ItemScatterer.spawn(world, pos.getX() + 0.5, pos.getY() + 0.5,
                                pos.getZ() + 0.5, stack);
                    }
                }
            }
            WEAR.remove(wire);
            if (WIRES.remove(wire) != null) {
                save();
            }
        });
    }

    /** Right-clicking a machine with a card: connect it, or disconnect it. */
    private static void wire(ServerPlayerEntity owner, ItemStack card,
                             World world, BlockPos pos) {
        House house = of(card);
        if (house == null) {
            owner.sendMessage(plain("That card hasn't been signed. Right-click the air "
                    + "with it first.").formatted(Formatting.GRAY), false);
            note(owner, SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), 0.6F);
            return;
        }
        String where = key(world, pos);
        UUID already = WIRES.get(where);

        if (house.id.equals(already)) {
            WIRES.remove(where);
            // A counter cut loose is not this casino's any more, so its stock
            // comes back over the bar rather than sitting in a shelf the floor
            // still quietly serves out of.
            List<ItemStack> shelf = house.bars.remove(where);
            if (shelf != null) {
                for (ItemStack stack : shelf) {
                    ItemScatterer.spawn(world, pos.getX() + 0.5, pos.getY() + 1.0,
                            pos.getZ() + 0.5, stack);
                }
            }
            save();
            owner.sendMessage(plain("Cut loose. That machine keeps its own takings again.")
                    .formatted(Formatting.GRAY), false);
            note(owner, SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), 0.8F);
            spark(world, pos, ParticleTypes.SMOKE);
            return;
        }

        WIRES.put(where, house.id);
        save();
        int count = machineCount(house);
        owner.sendMessage(plain("Wired to ").formatted(Formatting.GRAY)
                .append(plain(house.name).formatted(Formatting.GOLD, Formatting.BOLD))
                .append(plain(".  " + count + (count == 1 ? " machine" : " machines")
                        + " on the floor.").formatted(Formatting.GRAY)), false);
        if (house.balance <= 0) {
            owner.sendMessage(plain("The vault is empty, so it won't take a bet. "
                            + "Right-click the air with the card to put money in.")
                    .formatted(Formatting.RED), false);
        }
        note(owner, SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), 1.5F);
        spark(world, pos, ParticleTypes.HAPPY_VILLAGER);
    }

    private static void spark(World world, BlockPos pos,
                              net.minecraft.particle.ParticleEffect effect) {
        if (world instanceof ServerWorld server) {
            server.spawnParticles(effect, pos.getX() + 0.5, pos.getY() + 1.1, pos.getZ() + 0.5,
                    18, 0.4, 0.4, 0.4, 0.03);
        }
    }

    private static void note(ServerPlayerEntity player,
                             net.minecraft.sound.SoundEvent sound, float pitch) {
        player.getWorld().playSound(null, player.getBlockPos(), sound,
                SoundCategory.PLAYERS, 0.7F, pitch);
    }

    private static MutableText plain(String text) {
        return Text.literal(text).styled(style -> style.withItalic(false));
    }

    // --- persistence ----------------------------------------------------------

    /**
     * Two record types in one flat file, same as the market's.
     *
     *   house &lt;id&gt; &lt;balance&gt; &lt;handle&gt; &lt;paid&gt; &lt;plays&gt; &lt;founder&gt; &lt;name...&gt;
     *   wire  &lt;dimension&gt; &lt;x&gt; &lt;y&gt; &lt;z&gt; &lt;id&gt;
     *
     * The name runs to the end of the line and is read back by joining what's
     * left, rather than by swapping spaces for underscores the way the dealer
     * book does. Dealers have generated one-word names; a casino is called
     * whatever its owner typed into an anvil, and "The Lucky Streak" must not
     * come back as "The_Lucky_Streak" -- nor may "HeezQ_1" come back as
     * "HeezQ 1", which is what the underscore trick does to real names.
     */
    /**
     * Note that the ledger has moved, and let the tick loop write it.
     *
     * Every chip on a roulette board is a separate stake, and a player laying
     * a spread is a dozen calls in as many ticks. Writing the file on each one
     * is a dozen syscalls for a state nobody has looked at yet.
     */
    private static void save() {
        dirty = true;
    }

    private static boolean dirty;

    private static void flush() {
        dirty = false;
        if (saveFile == null) {
            return;
        }
        try {
            List<String> lines = new ArrayList<>();
            for (House house : HOUSES.values()) {
                lines.add("house2 " + house.id + " " + house.balance + " " + house.handle
                        + " " + house.paid + " " + house.plays + " " + house.rep
                        + " " + house.addiction + " "
                        + house.founder + " " + house.name);
            }
            for (House house : HOUSES.values()) {
                lines.add("costs " + house.id + " " + house.costs
                        + " " + house.ownPlay);
                // One line per counter. The wire goes in comma-separated so
                // the key stays a single token and the item list can keep its
                // fixed position after it.
                for (Map.Entry<String, List<ItemStack>> counter : house.bars.entrySet()) {
                    StringBuilder bar = new StringBuilder("shelf ").append(house.id)
                            .append(' ').append(counter.getKey().replace(' ', ','));
                    for (ItemStack stack : counter.getValue()) {
                        bar.append(' ').append(Registries.ITEM.getId(stack.getItem()))
                                .append('|').append(stack.getCount())
                                .append('|').append(TrapComponents.gradeTag(stack));
                    }
                    lines.add(bar.toString());
                }
                lines.add("staff " + house.id + " " + (house.pitBoss ? 1 : 0)
                        + " " + house.looseBeats + " " + house.looseCooldown
                        + " " + house.compCooldown);
            }
            WIRES.forEach((where, id) -> lines.add("wire " + where + " " + id));
            WEAR.forEach((where, worn) -> lines.add("wear " + where + " " + worn));
            Files.write(saveFile, lines);
        } catch (Exception failure) {
            TrapCraft.LOGGER.error("couldn't save the casinos: {}", failure.toString());
        }
    }

    /** item|count|grade, from `at` to the end of the line, onto one shelf. */
    private static void readShelf(List<ItemStack> shelf, String[] parts, int at) {
        shelf.clear();
        for (int i = at; i < parts.length; i++) {
            String[] bits = parts[i].split("\\|");
            if (bits.length != 3) {
                continue;
            }
            Item item = Registries.ITEM.getOptionalValue(
                    Identifier.of(bits[0])).orElse(null);
            if (item == null) {
                continue;
            }
            shelf.add(TrapComponents.applyGradeTag(
                    new ItemStack(item, Integer.parseInt(bits[1])), bits[2]));
        }
    }

    private static void load(MinecraftServer server) {
        saveFile = server.getSavePath(WorldSavePath.ROOT).resolve("trapcraft-houses.txt");
        HOUSES.clear();
        WIRES.clear();
        WEAR.clear();
        try {
            if (!Files.exists(saveFile)) {
                return;
            }
            for (String line : Files.readAllLines(saveFile)) {
                String[] parts = line.trim().split("\\s+");
                // A new tag rather than a longer line, because the founder
                // field is a player name and a player name may legally be all
                // digits -- so "is parts[6] a number?" is not a safe way to
                // ask which format this is.
                if (parts.length >= 10 && parts[0].equals("house2")) {
                    House house = new House(UUID.fromString(parts[1]), parts[8],
                            String.join(" ", java.util.Arrays.copyOfRange(
                                    parts, 9, parts.length)));
                    house.balance = Long.parseLong(parts[2]);
                    house.handle = Long.parseLong(parts[3]);
                    house.paid = Long.parseLong(parts[4]);
                    house.plays = Integer.parseInt(parts[5]);
                    house.rep = Integer.parseInt(parts[6]);
                    house.addiction = Integer.parseInt(parts[7]);
                    HOUSES.put(house.id, house);
                } else if (parts.length >= 8 && parts[0].equals("house")) {
                    House house = new House(UUID.fromString(parts[1]), parts[6],
                            String.join(" ", java.util.Arrays.copyOfRange(
                                    parts, 7, parts.length)));
                    house.balance = Long.parseLong(parts[2]);
                    house.handle = Long.parseLong(parts[3]);
                    house.paid = Long.parseLong(parts[4]);
                    house.plays = Integer.parseInt(parts[5]);
                    // A floor from before any of this was measured opens on
                    // the strength of what it has already paid out, rather
                    // than as though nobody had ever heard of it.
                    house.rep = (int) Math.min(TrapMath.HOUSE_STAT_MAX, house.paid / 400);
                    HOUSES.put(house.id, house);
                } else if (parts.length >= 3 && parts[0].equals("costs")) {
                    // Its own record rather than another field on the house
                    // line: that line ends with a name that may contain
                    // spaces, so anything appended to it is unparseable.
                    House house = HOUSES.get(UUID.fromString(parts[1]));
                    if (house != null) {
                        house.costs = Long.parseLong(parts[2]);
                        if (parts.length > 3) {
                            house.ownPlay = Long.parseLong(parts[3]);
                        }
                    }
                } else if (parts.length >= 3 && parts[0].equals("shelf")) {
                    House house = HOUSES.get(UUID.fromString(parts[1]));
                    if (house != null) {
                        readShelf(house.shelf(parts[2].replace(',', ' ')), parts, 3);
                    }
                } else if (parts.length >= 2 && parts[0].equals("bar")) {
                    // The single house-wide shelf, from before every counter
                    // had its own. See adoptOldStock.
                    House house = HOUSES.get(UUID.fromString(parts[1]));
                    if (house != null) {
                        readShelf(house.shelf(OLD_STOCK), parts, 2);
                    }
                } else if (parts.length == 6 && parts[0].equals("staff")) {
                    House house = HOUSES.get(UUID.fromString(parts[1]));
                    if (house != null) {
                        house.pitBoss = "1".equals(parts[2]);
                        house.looseBeats = Integer.parseInt(parts[3]);
                        house.looseCooldown = Integer.parseInt(parts[4]);
                        house.compCooldown = Integer.parseInt(parts[5]);
                    }
                } else if (parts.length == 6 && parts[0].equals("wear")) {
                    WEAR.put(parts[1] + " " + parts[2] + " " + parts[3] + " " + parts[4],
                            Integer.parseInt(parts[5]));
                } else if (parts.length == 6 && parts[0].equals("wire")) {
                    WIRES.put(parts[1] + " " + parts[2] + " " + parts[3] + " " + parts[4],
                            UUID.fromString(parts[5]));
                }
            }
            TrapCraft.LOGGER.info("casinos: {} houses, {} machines wired, {}e in vaults",
                    HOUSES.size(), WIRES.size(), floatHeld());
            for (House house : HOUSES.values()) {
                TrapCraft.LOGGER.info("  {} -- {}e, rep {}, addiction {}, pull {}",
                        house.name, house.balance, house.rep, house.addiction,
                        String.format("%.2f", house.pull()));
            }
        } catch (Exception failure) {
            TrapCraft.LOGGER.error("couldn't read the casinos -- vaults may be lost: {}",
                    failure.toString());
        }
    }

    /**
     * Take somebody on to watch the floor.
     *
     * @return why not, or null if they start tonight
     */
    public static String hirePitBoss(House house) {
        if (house.pitBoss) {
            return "You've already got somebody on the floor.";
        }
        if (house.balance < TrapMath.PIT_BOSS_HIRE) {
            return "That's " + TrapMath.PIT_BOSS_HIRE + "e up front and the vault's short.";
        }
        house.balance -= TrapMath.PIT_BOSS_HIRE;
        house.costs += TrapMath.PIT_BOSS_HIRE;
        house.pitBoss = true;
        save();
        return null;
    }

    public static void sackPitBoss(House house) {
        house.pitBoss = false;
        save();
    }

    /**
     * Stand the room a round.
     *
     * Money straight out of the vault for nothing you can point at, which is
     * exactly what a comp is. What it buys is the regulars staying regular.
     */
    public static String comp(House house, int machines) {
        if (house.compCooldown > 0) {
            return "You've only just stood one. Give it "
                    + house.compCooldown / 2 + " minutes.";
        }
        int cost = Math.max(TrapMath.COMP_COST_PER_MACHINE,
                machines * TrapMath.COMP_COST_PER_MACHINE);
        if (house.balance < cost) {
            return "A round for this lot is " + cost + "e. The vault's short.";
        }
        house.balance -= cost;
        house.costs += cost;
        house.nudge(0, TrapMath.COMP_ADDICTION);
        house.compCooldown = TrapMath.COMP_COOLDOWN_BEATS;
        save();
        return null;
    }

    /**
     * Run the floor generous for a while.
     *
     * A deliberate loss. The machines pay over the odds, the vault goes
     * backwards, and in exchange the room fills and the name climbs. Which is
     * the most business-like decision in the whole thing: spending money on
     * something that only pays back later, and only if you keep the rest of
     * the place worth coming to.
     */
    public static String runLoose(House house) {
        if (house.loose()) {
            return "It's already running loose.";
        }
        if (house.looseCooldown > 0) {
            return "Not yet. Another " + house.looseCooldown / 2 + " minutes.";
        }
        if (house.balance < TrapMath.FLOAT_PER_MACHINE) {
            return "Not on a vault this thin. It's a loss on purpose.";
        }
        house.looseBeats = TrapMath.LOOSE_BEATS;
        house.looseCooldown = TrapMath.LOOSE_COOLDOWN_BEATS;
        save();
        return null;
    }

    /**
     * Serve the next one through the door.
     *
     * Product first, then food -- so the good stuff goes to somebody rather
     * than sitting behind the counter while bread gets handed out. Returns
     * what tier they were served: 2 product, 1 food, 0 nothing.
     *
     * The serving is FREE. It is a comp, and what it buys is the punter
     * staying: see {@link TrapMath#punterRoundsServed}. Charging for it would
     * make the bar a second income and miss the point, which is that a casino
     * has to eat what you grow.
     *
     * Everybody gets served, but only about one in
     * {@link TrapMath#SERVINGS_PER_ITEM} of them empties something: one item
     * pours several rounds. Rolled rather than counted so the bar keeps no
     * state of its own -- a half-poured bottle would be another number to
     * save, and over an evening the roll comes out at the same rate.
     */
    public static int serve(House house, java.util.Random rng) {
        List<ItemStack> from = null;
        int best = -1;
        int tier = 0;
        // Every counter, not just the nearest one: which shelf a punter's
        // drink comes off is nobody's decision, and a floor with four bars
        // should not go dry because the product all went on one of them.
        for (List<ItemStack> shelf : house.bars.values()) {
            for (int slot = 0; slot < shelf.size(); slot++) {
                ItemStack stack = shelf.get(slot);
                if (stack.isEmpty()) {
                    continue;
                }
                int worth = TrapContent.isContraband(stack) ? 2 : 1;
                if (worth > tier) {
                    tier = worth;
                    from = shelf;
                    best = slot;
                }
            }
        }
        if (best < 0) {
            return 0;
        }
        if (rng.nextInt(TrapMath.SERVINGS_PER_ITEM) == 0) {
            from.get(best).decrement(1);
            from.removeIf(ItemStack::isEmpty);
        }
        house.nudge(0, tier == 2
                ? TrapMath.BAR_ADDICTION_PRODUCT : TrapMath.BAR_ADDICTION_FOOD);
        save();
        return tier;
    }

    /** How much is left behind the counters, in items, across all of them. */
    public static int barStock(House house) {
        int total = 0;
        for (List<ItemStack> shelf : house.bars.values()) {
            for (ItemStack stack : shelf) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /**
     * The one shelf a house used to keep, before every counter had its own.
     *
     * Kept under a key no wire can have, so it goes on serving punters until
     * somebody opens a bar -- at which point that counter inherits it. Losing
     * a stocked bar to an update would be losing a season of somebody's farm.
     */
    static final String OLD_STOCK = "-";

    /** Hand the old house-wide shelf to the first counter anybody opens. */
    public static void adoptOldStock(House house, String wire) {
        List<ItemStack> old = house.bars.get(OLD_STOCK);
        if (old == null || wire.equals(OLD_STOCK)) {
            return;
        }
        List<ItemStack> shelf = house.shelf(wire);
        while (!old.isEmpty() && shelf.size() < TrapMath.BAR_SLOTS) {
            shelf.add(old.remove(0));
        }
        if (old.isEmpty()) {
            house.bars.remove(OLD_STOCK);
        }
        save();
    }

    /** How many beats this floor has been behind on what it owes. */
    public static int owing(House house) {
        return house.owing;
    }

    public static void settled(House house) {
        house.owing = 0;
        save();
    }

    /** Called from the counting room when a balance changes outside a bet. */
    public static void touch() {
        save();
    }
}
