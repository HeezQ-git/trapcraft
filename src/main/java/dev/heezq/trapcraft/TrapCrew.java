package dev.heezq.trapcraft;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.block.FarmlandBlock;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.ai.brain.BlockPosLookTarget;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.brain.WalkTarget;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.WorldSavePath;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Somebody to do the picking, and what happens when you train them.
 *
 * A grow big enough to be worth raiding is a grow too big to harvest by hand,
 * and the answer a trap house reaches for is not a hopper -- it is people. Hire
 * one and they work a patch around wherever you put them: mature plants get
 * picked, the drop goes in the nearest chest, and they take a wage out of your
 * pocket every few minutes whether the harvest was good or not.
 *
 * The wage is the point. This mod had no recurring cost at all, so money only
 * ever went one way once you were established. A crew is the first thing you
 * own that can lose you money by existing, which is what makes deciding to
 * hire one an actual decision.
 *
 * <h2>What was wrong with the first one</h2>
 *
 * It picked about one plant every two minutes, which is not a worker, it is an
 * ornament. Three separate causes, all fixed here:
 *
 * <ul>
 *   <li>It asked the pathfinder to walk somewhere and then let the villager
 *       BRAIN immediately overrule it. A villager decides where to go from
 *       {@code WALK_TARGET}; going round it via the navigator means every
 *       stroll task cancels you. Now the target goes in the memory, which both
 *       moves them and -- because the stroll tasks require that memory to be
 *       ABSENT -- stops them wandering at the same time. One fix, two bugs.
 *   <li>It looked for work from the middle of the patch every time, so the
 *       hand walked past ripe plants to reach the one nearest a fixed point.
 *       Work is now whatever is nearest the HAND.
 *   <li>Ten seconds a pass with the arrival check failing most passes. Pace is
 *       now something you buy, down to a pass and a half.
 * </ul>
 *
 * <h2>Why they are slightly small</h2>
 *
 * Farmland is trampled by {@code FarmlandBlock.onLandedUpon}, which fires for
 * any living entity whose {@code width * width * height} clears 0.512. A
 * villager is 0.6 x 1.95, which is 0.70 -- so every jump was undoing somebody's
 * hoe work and knocking the buds down a grade. Scaled to 0.85 the same sum is
 * 0.43 and the game simply never considers them heavy enough to break
 * anything. No mixin, no gamerule, and it reads as a lad rather than a bug.
 */
public final class TrapCrew {
    /** Ticks between wage packets. Five minutes. */
    private static final int WAGE_TICKS = 20 * 60 * 5;
    /** What one untrained hand costs per packet. */
    public static final int WAGE = 24;
    /** What it costs to take somebody on. */
    public static final int HIRE_COST = 120;
    /** Three is all one operation will carry. */
    public static final int MAX_HANDS = 3;
    /** How close a hand has to be to a job to do it. */
    private static final int ARM = 4;
    /** Passes of getting nowhere before we accept they can't path there. */
    private static final int STUCK_PASSES = 8;
    /**
     * Positions one pass will look at before giving up on finding work.
     *
     * ponytail: a flat cap rather than a block index. The scan runs outward
     * from the hand and stops at the first job of each kind, so the common
     * case is a few dozen lookups; this only bites on a big empty patch. If
     * a maxed crew ever shows up in a tick profile, cache the job list per
     * patch and invalidate on block update.
     */
    private static final int SCAN_BUDGET = 3000;

    /** Small enough that the game never counts them heavy enough to trample. */
    private static final double HAND_SCALE = 0.85;

    // --- what a hand can be taught -------------------------------------------

    /**
     * One thing a hand can be sent to do.
     *
     * Picking is free and is why you hired them. Everything else is bought,
     * and every one of them puts the wage up -- so a fully trained hand is a
     * real payroll line rather than a switch you flip once and forget. That is
     * the same reason the wage exists at all.
     */
    public enum Job {
        // Declaration order IS priority order -- findWork walks values() and
        // takes the first thing it found. Your own crop first because it is
        // what the wage is really for, then racks because a bud left hanging
        // past peak loses a grade, and the ground work last because dirt can
        // wait and a ripe plant can't.
        PICK("Picking", "minecraft:wheat", 0, 0,
                "Your mature plants, into the nearest chest."),
        CURE("Curing", "trapcraft:drying_rack", 480, 8,
                "Loads the racks and pulls them at peak."),
        FARM("Farmhand", "minecraft:carrot", 260, 5,
                "Wheat, carrots, anything else that ripens."),
        FEED("Fertilising", "minecraft:bone_meal", 400, 6,
                "Bone meal on food crops. Never on yours."),
        SOW("Sowing", "minecraft:wheat_seeds", 340, 6,
                "Plants seeds out of the chest into empty rows."),
        TILL("Tilling", "minecraft:iron_hoe", 220, 4,
                "Turns bare ground near water into farmland.");

        private final String display;
        private final String iconId;
        private final int cost;
        private final int wage;
        private final String blurb;

        Job(String display, String iconId, int cost, int wage, String blurb) {
            this.display = display;
            this.iconId = iconId;
            this.cost = cost;
            this.wage = wage;
            this.blurb = blurb;
        }

        public String display() {
            return display;
        }

        public String iconId() {
            return iconId;
        }

        public int cost() {
            return cost;
        }

        public int wage() {
            return wage;
        }

        public String blurb() {
            return blurb;
        }

        /** Picking comes with the hire; the rest have to be taught. */
        public boolean free() {
            return cost == 0;
        }
    }

    /**
     * Pace and patch size, as ladders you climb one rung at a time.
     *
     * The wage numbers are cumulative, not per rung, because what a hand costs
     * should be readable off what they are rather than off their history.
     */
    public static final int[] PACE_TICKS = {200, 120, 80, 50, 30};
    public static final int[] PACE_COST = {0, 150, 320, 700, 1400};
    public static final int[] PACE_WAGE = {0, 6, 14, 26, 44};
    public static final String[] PACE_NAME = {"Plodding", "Steady", "Brisk", "Quick", "Flat out"};

    public static final int[] REACH_BLOCKS = {12, 16, 20, 26};
    public static final int[] REACH_COST = {0, 200, 450, 900};
    public static final int[] REACH_WAGE = {0, 4, 9, 16};

    /** Kept for the guide book and anything that wants the starting patch. */
    public static final int REACH = REACH_BLOCKS[0];

    // --- one hire -------------------------------------------------------------

    /** Who they work for, where, what they've been taught, and how it's going. */
    private static final class Hand {
        final UUID boss;
        final UUID mob;
        final String dimension;
        final BlockPos patch;
        int pace;
        int reach;
        /** Bit per {@link Job} ordinal. PICK is always set. */
        int jobs = 1 << Job.PICK.ordinal();
        /** Passes since anything actually got done. Not saved -- it's a mood. */
        int idle;
        /**
         * Where the chest was last time. Not saved either.
         *
         * Finding it is a scan of up to fourteen thousand positions and the
         * answer almost never changes, so it is worth one hash lookup a pass
         * to check the remembered one is still a container before doing that
         * again. Somebody who moves their chest costs themselves one pass.
         */
        BlockPos box;

        Hand(UUID boss, UUID mob, String dimension, BlockPos patch) {
            this.boss = boss;
            this.mob = mob;
            this.dimension = dimension;
            this.patch = patch;
        }

        boolean can(Job job) {
            return (jobs & (1 << job.ordinal())) != 0;
        }

        void teach(Job job) {
            jobs |= 1 << job.ordinal();
        }

        int interval() {
            return PACE_TICKS[pace];
        }

        int reachBlocks() {
            return REACH_BLOCKS[reach];
        }

        int wage() {
            int total = WAGE + PACE_WAGE[pace] + REACH_WAGE[reach];
            for (Job job : Job.values()) {
                if (!job.free() && can(job)) {
                    total += job.wage();
                }
            }
            return total;
        }

        boolean maxed() {
            if (pace < PACE_TICKS.length - 1 || reach < REACH_BLOCKS.length - 1) {
                return false;
            }
            for (Job job : Job.values()) {
                if (!can(job)) {
                    return false;
                }
            }
            return true;
        }
    }

    private static final List<Hand> CREW = new ArrayList<>();
    private static Path saveFile;

    private TrapCrew() {
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(TrapCrew::load);
        registerCommand();
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            int tick = server.getTicks();
            // Per hand rather than one global beat: pace is bought per hand, so
            // a hand somebody paid 1400e to speed up has to actually run faster
            // than the one next to it.
            for (Hand hand : CREW) {
                if (tick % hand.interval() == 0) {
                    work(server, hand);
                }
            }
            if (tick % WAGE_TICKS == 0) {
                payday(server);
            }
        });
    }

    /** How many hands this player is carrying. */
    public static int sizeOf(ServerPlayerEntity boss) {
        return (int) CREW.stream().filter(hand -> hand.boss.equals(boss.getUuid())).count();
    }

    /** What this player's whole payroll comes to per packet. */
    public static int payrollOf(ServerPlayerEntity boss) {
        return CREW.stream().filter(hand -> hand.boss.equals(boss.getUuid()))
                .mapToInt(Hand::wage).sum();
    }

    // --- the screen's view of a hand ------------------------------------------
    //
    // The GUI needs to read and change hands without holding one, because a
    // Hand is mutable state this class owns and handing one out would mean two
    // places that can put a crew member in an impossible condition.

    /** One row of the crew screen. */
    public record Card(int index, int pace, int reach, int reachBlocks, int wage,
                       int seconds, boolean present, List<Job> taught) {
    }

    public static List<Card> cardsFor(ServerPlayerEntity boss) {
        List<Card> out = new ArrayList<>();
        for (int i = 0; i < CREW.size(); i++) {
            Hand hand = CREW.get(i);
            if (!hand.boss.equals(boss.getUuid())) {
                continue;
            }
            List<Job> taught = new ArrayList<>();
            for (Job job : Job.values()) {
                if (hand.can(job)) {
                    taught.add(job);
                }
            }
            out.add(new Card(i, hand.pace, hand.reach, hand.reachBlocks(), hand.wage(),
                    hand.interval() / 20, find(boss.getServer(), hand) != null, taught));
        }
        return out;
    }

    /**
     * Buy the next rung of something, or teach a job.
     *
     * One method for all three because they are one decision -- "spend on this
     * hand" -- and three near-identical ones would drift the moment somebody
     * added a fourth thing to buy.
     *
     * @param job null to buy pace or reach, named by {@code pace}
     * @return why it didn't happen, or null if it did
     */
    public static String buy(ServerPlayerEntity boss, int index, Job job, boolean pace) {
        if (index < 0 || index >= CREW.size()) {
            return "They're not on the books any more.";
        }
        Hand hand = CREW.get(index);
        if (!hand.boss.equals(boss.getUuid())) {
            return "That's not your hand.";
        }

        int cost;
        String bought;
        if (job != null) {
            if (hand.can(job)) {
                return "They already know that one.";
            }
            cost = job.cost();
            bought = job.display();
        } else if (pace) {
            if (hand.pace >= PACE_TICKS.length - 1) {
                return "They're already going flat out.";
            }
            cost = PACE_COST[hand.pace + 1];
            bought = PACE_NAME[hand.pace + 1];
        } else {
            if (hand.reach >= REACH_BLOCKS.length - 1) {
                return "That's as much ground as anybody can cover.";
            }
            cost = REACH_COST[hand.reach + 1];
            bought = REACH_BLOCKS[hand.reach + 1] + " blocks";
        }

        if (TrapMarket.wealthOf(boss) < cost) {
            return "That's " + cost + "e, and you haven't got it.";
        }
        // Through the market, like every other emerald this mod moves. A wage
        // that skipped circulate() would be a hole in the money supply the
        // index could never see.
        TrapMarket.take(boss, cost);
        if (job != null) {
            hand.teach(job);
        } else if (pace) {
            hand.pace++;
        } else {
            hand.reach++;
        }
        save();

        VillagerEntity mob = find(boss.getServer(), hand);
        if (mob != null) {
            equip(mob, hand);
            ServerWorld world = (ServerWorld) mob.getWorld();
            world.playSound(null, mob.getBlockPos(), SoundEvents.ENTITY_VILLAGER_CELEBRATE,
                    SoundCategory.NEUTRAL, 0.9F, 1.0F);
            world.spawnParticles(ParticleTypes.HAPPY_VILLAGER, mob.getX(), mob.getY() + 1.4,
                    mob.getZ(), 12, 0.35, 0.35, 0.35, 0.02);
        }
        boss.sendMessage(Text.literal("Taught them ").formatted(Formatting.GREEN)
                .append(Text.literal(bought).formatted(Formatting.WHITE))
                .append(Text.literal(". Wages now " + hand.wage() + "e.")
                        .formatted(Formatting.GRAY)), false);
        if (hand.maxed()) {
            TrapAwards.grant(boss, "foreman");
        }
        return null;
    }

    // --- hiring and firing ----------------------------------------------------

    /**
     * Take somebody on at this spot.
     *
     * @return why it didn't happen, or null if it did
     */
    public static String hire(ServerPlayerEntity boss, BlockPos patch) {
        if (sizeOf(boss) >= MAX_HANDS) {
            return MAX_HANDS + " hands is all one operation will carry.";
        }
        if (TrapMarket.wealthOf(boss) < HIRE_COST) {
            return "Taking somebody on costs " + HIRE_COST + "e.";
        }
        ServerWorld world = boss.getWorld();
        VillagerEntity mob = EntityType.VILLAGER.create(world, SpawnReason.EVENT);
        if (mob == null) {
            return "Nobody's about.";
        }
        TrapMarket.take(boss, HIRE_COST);

        mob.refreshPositionAndAngles(patch.up(), boss.getYaw(), 0.0F);
        mob.setPersistent();
        mob.setAiDisabled(false);
        mob.setCustomName(Text.literal("Hand").formatted(Formatting.YELLOW));
        mob.setCustomNameVisible(true);
        // NITWIT, and not merely "no profession". A professionless villager
        // takes a job from any workstation it wanders past and becomes a
        // trader -- which would undercut the market stall by accident, exactly
        // what this was supposed to avoid. A nitwit never takes one.
        mob.setVillagerData(mob.getVillagerData().withProfession(
                world.getRegistryManager()
                        .getOrThrow(net.minecraft.registry.RegistryKeys.VILLAGER_PROFESSION)
                        .getOrThrow(net.minecraft.village.VillagerProfession.NITWIT)));
        world.spawnEntity(mob);

        Hand hand = new Hand(boss.getUuid(), mob.getUuid(),
                world.getRegistryKey().getValue().toString(), patch);
        equip(mob, hand);
        CREW.add(hand);
        save();

        world.playSound(null, patch, SoundEvents.ENTITY_VILLAGER_YES,
                SoundCategory.NEUTRAL, 0.9F, 1.1F);
        TrapAwards.grant(boss, "crew");
        boss.sendMessage(Text.literal("Hired. ").formatted(Formatting.GREEN, Formatting.BOLD)
                .append(Text.literal("They'll work " + hand.reachBlocks()
                        + " blocks around here for " + hand.wage()
                        + "e every five minutes.").formatted(Formatting.GRAY))
                .append(Text.literal("\n  /crew").formatted(Formatting.GREEN))
                .append(Text.literal("  to teach them something").formatted(Formatting.DARK_GRAY)),
                false);
        return null;
    }

    /** Let somebody go. */
    public static String fire(ServerPlayerEntity boss, int index) {
        for (int i = CREW.size() - 1; i >= 0; i--) {
            Hand hand = CREW.get(i);
            if (!hand.boss.equals(boss.getUuid()) || (index >= 0 && i != index)) {
                continue;
            }
            VillagerEntity mob = find(boss.getServer(), hand);
            if (mob != null) {
                mob.getWorld().playSound(null, mob.getBlockPos(), SoundEvents.ENTITY_VILLAGER_NO,
                        SoundCategory.NEUTRAL, 0.9F, 0.8F);
                mob.discard();
            }
            CREW.remove(i);
            save();
            boss.sendMessage(Text.literal("Let them go.").formatted(Formatting.GRAY), false);
            return null;
        }
        return "You haven't got anybody on.";
    }

    /**
     * Everything about the villager that follows from what they've been taught.
     *
     * Re-applied on every work pass rather than only on hire, so an upgrade
     * bought while they're asleep in an unloaded chunk still takes hold the
     * moment somebody walks back into range, and so a hand from a save written
     * before any of this existed quietly acquires it.
     */
    private static void equip(VillagerEntity mob, Hand hand) {
        var scale = mob.getAttributeInstance(EntityAttributes.SCALE);
        if (scale != null && scale.getBaseValue() != HAND_SCALE) {
            scale.setBaseValue(HAND_SCALE);
        }
        var speed = mob.getAttributeInstance(EntityAttributes.MOVEMENT_SPEED);
        double wanted = 0.5 + 0.055 * hand.pace;
        if (speed != null && speed.getBaseValue() != wanted) {
            speed.setBaseValue(wanted);
        }
    }

    // --- the command ----------------------------------------------------------

    /**
     * /crew, /crew hire, /crew fire.
     *
     * A command rather than an item because hiring is about a PLACE -- where
     * you are standing is the patch they work -- and an item you right-click
     * on the ground would need a model, a recipe and a texture to say the same
     * thing a word already says. Bare /crew now opens the board, because a
     * chat readout can't be clicked and everything worth doing to a hand is a
     * purchase.
     */
    private static void registerCommand() {
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register(
                (dispatcher, access, env) -> dispatcher.register(
                        net.minecraft.server.command.CommandManager.literal("crew")
                                .executes(context -> open(context.getSource()))
                                .then(net.minecraft.server.command.CommandManager.literal("hire")
                                        .executes(context -> {
                                            ServerPlayerEntity boss = context.getSource().getPlayer();
                                            if (boss == null) {
                                                return 0;
                                            }
                                            say(boss, hire(boss, boss.getBlockPos()));
                                            return 1;
                                        }))
                                .then(net.minecraft.server.command.CommandManager.literal("fire")
                                        .executes(context -> {
                                            ServerPlayerEntity boss = context.getSource().getPlayer();
                                            if (boss == null) {
                                                return 0;
                                            }
                                            say(boss, fire(boss, -1));
                                            return 1;
                                        }))));
    }

    private static void say(ServerPlayerEntity boss, String no) {
        if (no != null) {
            boss.sendMessage(Text.literal(no).formatted(Formatting.GRAY), false);
        }
    }

    private static int open(net.minecraft.server.command.ServerCommandSource source) {
        ServerPlayerEntity boss = source.getPlayer();
        if (boss == null) {
            return 0;
        }
        boss.openHandledScreen(new net.minecraft.screen.SimpleNamedScreenHandlerFactory(
                (syncId, inventory, p) -> new CrewScreenHandler(syncId, inventory),
                Text.literal("The Crew").formatted(Formatting.GOLD)));
        return 1;
    }

    // --- the work -------------------------------------------------------------

    /**
     * One pass of one hand: keep them home, find the nearest job, do it.
     *
     * Still one job per pass, not a sweep. A hand that cleared a field in one
     * tick would make the whole growing half of the mod a formality; one that
     * brings in a plant at a time is somebody helping rather than a machine
     * replacing you. Pace is what you buy if you want more, and pace costs
     * wages.
     */
    private static void work(MinecraftServer server, Hand hand) {
        VillagerEntity mob = find(server, hand);
        if (mob == null) {
            return;
        }
        ServerWorld world = (ServerWorld) mob.getWorld();
        equip(mob, hand);

        // Keep them on the job. The old one wandered out of the field and came
        // back minutes later because nothing ever told it not to: a villager
        // with no walk target goes and finds one. Handing it a target every
        // pass both moves it and starves the stroll tasks, which only run when
        // that memory is empty.
        int reach = hand.reachBlocks();
        double strayed = Math.sqrt(mob.getBlockPos().getSquaredDistance(hand.patch));
        if (strayed > reach + 12) {
            // Far enough that walking back is its own five-minute errand, and
            // usually means they got pushed, boated or shut out by a door.
            mob.refreshPositionAndAngles(hand.patch.up(), mob.getYaw(), 0.0F);
            mob.getNavigation().stop();
            world.spawnParticles(ParticleTypes.POOF, hand.patch.getX() + 0.5,
                    hand.patch.getY() + 1.0, hand.patch.getZ() + 0.5, 6, 0.3, 0.3, 0.3, 0.01);
            return;
        }
        if (strayed > reach) {
            walkTo(mob, hand.patch, hand);
            return;
        }

        net.minecraft.inventory.Inventory box = nearestBox(world, hand);
        BlockPos job = findWork(world, mob, hand, box);
        if (job == null) {
            hand.idle = 0;
            // Nothing to do, so stand about in the middle of the patch rather
            // than at whatever edge they finished on -- which is also what
            // stops them drifting towards the door over a quiet hour.
            if (strayed > 3) {
                walkTo(mob, hand.patch, hand);
            }
            return;
        }
        if (!mob.getBlockPos().isWithinDistance(job, ARM)) {
            walkTo(mob, job, hand);
            if (++hand.idle >= STUCK_PASSES) {
                // Eight passes and no closer: there is a fence, a wall or a
                // drop between them and it. Rather than stall on that square
                // forever, put them next to it and let them get on.
                hand.idle = 0;
                mob.refreshPositionAndAngles(job.up(), mob.getYaw(), 0.0F);
                mob.getNavigation().stop();
            }
            return;
        }
        hand.idle = 0;
        doWork(world, mob, hand, job, box);
    }

    private static void walkTo(VillagerEntity mob, BlockPos target, Hand hand) {
        float speed = 0.55F + 0.08F * hand.pace;
        mob.getBrain().remember(MemoryModuleType.WALK_TARGET,
                new WalkTarget(target, speed, 1));
        mob.getBrain().remember(MemoryModuleType.LOOK_TARGET, new BlockPosLookTarget(target));
    }

    // --- finding something to do ----------------------------------------------

    /**
     * The nearest square that wants doing, best job first.
     *
     * Scanned outward from the HAND, not from the patch, which is the whole
     * difference between a worker and a tourist: the old one walked back to
     * the same corner every pass because that is where the search started.
     *
     * The first hit of each kind is remembered rather than the first hit
     * overall, so a rack that has finished curing still wins over a square of
     * dirt the hand happens to be standing on -- but the scan still stops the
     * moment it has an answer for the job that outranks everything.
     */
    private static BlockPos findWork(ServerWorld world, VillagerEntity mob, Hand hand,
                                     net.minecraft.inventory.Inventory box) {
        int reach = hand.reachBlocks();
        // Three of the jobs are things the CHEST has to agree to. Read once
        // here rather than at every square, and read at all because a hand
        // that walks to a plant it has no bone meal for does nothing for a
        // whole pass and looks exactly like a hand that is broken.
        Supplies stock = new Supplies(holds(box, Items.BONE_MEAL), holdsSeed(box),
                holdsRawBud(box));

        Map<Job, BlockPos> found = new EnumMap<>(Job.class);
        int looked = 0;
        for (BlockPos pos : BlockPos.iterateOutwards(mob.getBlockPos(), reach, 5, reach)) {
            // Top priority found, or we've looked at enough dirt for one pass.
            if (++looked > SCAN_BUDGET || found.containsKey(Job.values()[0])) {
                break;
            }
            if (!within(pos, hand.patch, reach)) {
                continue;
            }
            Job job = jobAt(world, pos, hand, stock);
            if (job != null) {
                found.putIfAbsent(job, pos.toImmutable());
            }
        }
        for (Job job : Job.values()) {
            BlockPos at = found.get(job);
            if (at != null) {
                return at;
            }
        }
        return null;
    }

    private static boolean holds(net.minecraft.inventory.Inventory box, net.minecraft.item.Item want) {
        if (box == null) {
            return false;
        }
        for (int slot = 0; slot < box.size(); slot++) {
            if (box.getStack(slot).isOf(want)) {
                return true;
            }
        }
        return false;
    }

    private static boolean holdsSeed(net.minecraft.inventory.Inventory box) {
        if (box == null) {
            return false;
        }
        for (int slot = 0; slot < box.size(); slot++) {
            if (box.getStack(slot).getItem() instanceof BlockItem seed
                    && seed.getBlock() instanceof CropBlock) {
                return true;
            }
        }
        return false;
    }

    private static boolean holdsRawBud(net.minecraft.inventory.Inventory box) {
        if (box == null) {
            return false;
        }
        for (int slot = 0; slot < box.size(); slot++) {
            if (TrapContent.strainOfRawBud(box.getStack(slot).getItem()) != null) {
                return true;
            }
        }
        return false;
    }

    /** What the chest can back up this pass. */
    private record Supplies(boolean boneMeal, boolean seeds, boolean rawBuds) {
    }

    /** Inside the box the hand was hired to work. */
    private static boolean within(BlockPos pos, BlockPos patch, int reach) {
        return Math.abs(pos.getX() - patch.getX()) <= reach
                && Math.abs(pos.getZ() - patch.getZ()) <= reach
                && Math.abs(pos.getY() - patch.getY()) <= 5;
    }

    /** What, if anything, this square is asking for. */
    private static Job jobAt(ServerWorld world, BlockPos pos, Hand hand, Supplies stock) {
        BlockState state = world.getBlockState(pos);
        Block block = state.getBlock();

        if (block instanceof DryingRackBlock && hand.can(Job.CURE)) {
            // Ready to come off, or empty with something to hang on it.
            // Anything mid-cure is left strictly alone -- pulling early costs
            // a grade, and a hand that did that would be worse than no hand.
            if (state.get(DryingRackBlock.OCCUPIED)) {
                return state.get(DryingRackBlock.DRYNESS) >= DryingRackBlock.READY_DRYNESS
                        ? Job.CURE : null;
            }
            return stock.rawBuds() ? Job.CURE : null;
        }
        if (block instanceof CannabisCropBlock || block instanceof CocaCropBlock) {
            return block instanceof CropBlock crop && crop.isMature(state) ? Job.PICK : null;
        }
        if (block instanceof CropBlock crop) {
            if (crop.isMature(state)) {
                return hand.can(Job.FARM) ? Job.FARM : null;
            }
            return hand.can(Job.FEED) && stock.boneMeal() ? Job.FEED : null;
        }
        if (block instanceof FarmlandBlock && world.getBlockState(pos.up()).isAir()) {
            return hand.can(Job.SOW) && stock.seeds() ? Job.SOW : null;
        }
        if (hand.can(Job.TILL) && tillable(world, pos, state)) {
            return Job.TILL;
        }
        return null;
    }

    /**
     * Ground worth turning over: bare earth, open sky above it, water in reach.
     *
     * The water clause is what stops a hired hand ploughing up your lawn. It
     * is also the same four blocks vanilla farmland uses to decide whether it
     * stays wet, so a hand only ever makes farmland that will actually work.
     */
    private static boolean tillable(ServerWorld world, BlockPos pos, BlockState state) {
        if (!state.isOf(Blocks.GRASS_BLOCK) && !state.isOf(Blocks.DIRT)
                && !state.isOf(Blocks.COARSE_DIRT) && !state.isOf(Blocks.ROOTED_DIRT)
                && !state.isOf(Blocks.DIRT_PATH)) {
            return false;
        }
        if (!world.getBlockState(pos.up()).isAir()) {
            return false;
        }
        for (BlockPos near : BlockPos.iterate(pos.add(-4, 0, -4), pos.add(4, 1, 4))) {
            if (world.getFluidState(near).isIn(net.minecraft.registry.tag.FluidTags.WATER)) {
                return true;
            }
        }
        return false;
    }

    // --- doing it -------------------------------------------------------------

    private static void doWork(ServerWorld world, VillagerEntity mob, Hand hand, BlockPos at,
                               net.minecraft.inventory.Inventory box) {
        BlockState state = world.getBlockState(at);
        Block block = state.getBlock();
        mob.getBrain().remember(MemoryModuleType.LOOK_TARGET, new BlockPosLookTarget(at));

        if (block instanceof DryingRackBlock) {
            rack(world, hand, box, at, state);
            return;
        }
        if (block instanceof CannabisCropBlock || block instanceof CocaCropBlock) {
            // Through the block's own harvest, not getDroppedStacks: breaking
            // one of these runs the loot table and returns a SEED. The buds
            // only come off a right-click, and a hand that broke the plant was
            // demolishing the farm and stashing seeds.
            List<ItemStack> picked = block instanceof CannabisCropBlock weed
                    ? weed.harvest(world, at, state)
                    : ((CocaCropBlock) block).harvest(world, at, state);
            stow(world, box, at, picked);
            cheer(world, at, SoundEvents.BLOCK_CROP_BREAK, 1.0F);
            return;
        }
        if (block instanceof CropBlock crop) {
            if (crop.isMature(state)) {
                // Back to age zero rather than broken, exactly like our own
                // crops: the hand harvests the field, it doesn't dismantle it.
                stow(world, box, at, Block.getDroppedStacks(state, world, at, null));
                world.setBlockState(at, crop.withAge(0));
                cheer(world, at, SoundEvents.BLOCK_CROP_BREAK, 1.0F);
            } else if (spend(box, Items.BONE_MEAL)) {
                // Never our own crops. Bone meal sets RUSHED on those and
                // costs a grade at harvest, so a hand doing this unasked would
                // quietly turn a Fire field into a Mids one -- which is why
                // jobAt only ever offers FEED for a plain CropBlock.
                crop.grow(world, world.random, at, state);
                cheer(world, at, SoundEvents.ITEM_BONE_MEAL_USE, 1.2F);
            }
            return;
        }
        if (block instanceof FarmlandBlock) {
            sow(world, box, at);
            return;
        }
        // Re-checked rather than assumed. Several seconds can pass between
        // spotting a square of dirt and standing on it, and turning whatever
        // somebody built there in the meantime into farmland is the one way
        // this could destroy something.
        if (tillable(world, at, state)) {
            world.setBlockState(at, Blocks.FARMLAND.getDefaultState());
            cheer(world, at, SoundEvents.ITEM_HOE_TILL, 1.0F);
        }
    }

    /** Pull a finished rack into the chest, or hang a fresh bud on an empty one. */
    private static void rack(ServerWorld world, Hand hand, net.minecraft.inventory.Inventory box,
                             BlockPos at, BlockState state) {
        if (state.get(DryingRackBlock.OCCUPIED)) {
            ItemStack out = DryingRackBlock.take(state, world, at);
            if (!out.isEmpty()) {
                stow(world, box, at, List.of(out));
                cheer(world, at, SoundEvents.ITEM_CROP_PLANT, 1.2F);
            }
            return;
        }
        if (box == null) {
            return;
        }
        for (int slot = 0; slot < box.size(); slot++) {
            ItemStack stack = box.getStack(slot);
            if (TrapContent.strainOfRawBud(stack.getItem()) == null) {
                continue;
            }
            if (DryingRackBlock.hang(state, world, at, stack)) {
                box.markDirty();
                cheer(world, at, SoundEvents.BLOCK_GRASS_PLACE, 1.0F);
            }
            return;
        }
    }

    /** Put a seed from the chest into empty farmland. */
    private static void sow(ServerWorld world, net.minecraft.inventory.Inventory box, BlockPos at) {
        if (box == null) {
            return;
        }
        for (int slot = 0; slot < box.size(); slot++) {
            ItemStack stack = box.getStack(slot);
            // Every seed in the game is a BlockItem for the crop it plants --
            // vanilla, ours and Farmer's Delight alike -- so this needs no
            // list of ids and works for mods nobody has heard of.
            if (!(stack.getItem() instanceof BlockItem seed)
                    || !(seed.getBlock() instanceof CropBlock crop)) {
                continue;
            }
            if (!crop.getDefaultState().canPlaceAt(world, at.up())) {
                continue;
            }
            world.setBlockState(at.up(), crop.getDefaultState());
            stack.decrement(1);
            box.markDirty();
            cheer(world, at.up(), SoundEvents.ITEM_CROP_PLANT, 1.0F);
            return;
        }
    }

    /** Take one of something out of the chest. False if there wasn't one. */
    private static boolean spend(net.minecraft.inventory.Inventory box, net.minecraft.item.Item want) {
        if (box == null) {
            return false;
        }
        for (int slot = 0; slot < box.size(); slot++) {
            ItemStack stack = box.getStack(slot);
            if (stack.isOf(want)) {
                stack.decrement(1);
                box.markDirty();
                return true;
            }
        }
        return false;
    }

    private static void stow(ServerWorld world, net.minecraft.inventory.Inventory box,
                             BlockPos at, List<ItemStack> drops) {
        for (ItemStack drop : drops) {
            if (box == null || !store(box, drop)) {
                Block.dropStack(world, at, drop);
            }
        }
    }

    private static void cheer(ServerWorld world, BlockPos at, net.minecraft.sound.SoundEvent sound,
                              float pitch) {
        world.spawnParticles(ParticleTypes.HAPPY_VILLAGER,
                at.getX() + 0.5, at.getY() + 0.6, at.getZ() + 0.5, 6, 0.3, 0.3, 0.3, 0.01);
        world.playSound(null, at, sound, SoundCategory.NEUTRAL, 0.6F, pitch);
    }

    /** The closest container to the patch, or null if there isn't one. */
    private static net.minecraft.inventory.Inventory nearestBox(ServerWorld world, Hand hand) {
        if (hand.box != null
                && world.getBlockEntity(hand.box) instanceof net.minecraft.inventory.Inventory known) {
            return known;
        }
        for (BlockPos pos : BlockPos.iterateOutwards(hand.patch, hand.reachBlocks(), 4,
                hand.reachBlocks())) {
            if (world.getBlockEntity(pos) instanceof net.minecraft.inventory.Inventory box) {
                hand.box = pos.toImmutable();
                return box;
            }
        }
        hand.box = null;
        return null;
    }

    /** Put a drop away. False if it wouldn't fit. */
    private static boolean store(net.minecraft.inventory.Inventory box, ItemStack drop) {
        for (int slot = 0; slot < box.size(); slot++) {
            ItemStack there = box.getStack(slot);
            if (there.isEmpty()) {
                box.setStack(slot, drop.copy());
                box.markDirty();
                return true;
            }
            if (ItemStack.areItemsAndComponentsEqual(there, drop)
                    && there.getCount() + drop.getCount() <= there.getMaxCount()) {
                there.increment(drop.getCount());
                box.markDirty();
                return true;
            }
        }
        return false;
    }

    // --- payday ---------------------------------------------------------------

    /**
     * Wages, and what happens when you can't cover them.
     *
     * They walk. Not a warning, not a debt -- a hand who isn't paid stops being
     * your hand, which is the only consequence that makes the wage a real cost
     * rather than a number that accrues somewhere you never look. Everything
     * you taught them walks with them, which is what stops a trained hand being
     * a one-off purchase you can stop feeding.
     */
    private static void payday(MinecraftServer server) {
        List<Hand> quit = new ArrayList<>();
        for (Hand hand : CREW) {
            ServerPlayerEntity boss = server.getPlayerManager().getPlayer(hand.boss);
            if (boss == null) {
                continue;   // nobody home; wages wait until they log in
            }
            if (find(server, hand) == null) {
                // Dead, or in a chunk nobody is loading. Either way they did no
                // work this shift, so they don't get paid for it -- and a hand
                // a zombie got was otherwise charging wages forever with
                // nothing to show and no way to notice.
                continue;
            }
            int wage = hand.wage();
            if (TrapMarket.wealthOf(boss) < wage) {
                quit.add(hand);
                boss.sendMessage(Text.literal("You couldn't make wages. ")
                        .formatted(Formatting.RED)
                        .append(Text.literal("They walked, and took what you taught them.")
                                .formatted(Formatting.GRAY)), false);
                continue;
            }
            TrapMarket.take(boss, wage);
            boss.sendMessage(Text.literal("Wages: ").formatted(Formatting.DARK_GRAY)
                    .append(Text.literal("-" + wage + "e").formatted(Formatting.RED)), true);
        }
        for (Hand hand : quit) {
            VillagerEntity mob = find(server, hand);
            if (mob != null) {
                mob.discard();
            }
            CREW.remove(hand);
        }
        if (!quit.isEmpty()) {
            save();
        }
    }

    private static VillagerEntity find(MinecraftServer server, Hand hand) {
        if (server == null) {
            return null;
        }
        for (ServerWorld world : server.getWorlds()) {
            if (!world.getRegistryKey().getValue().toString().equals(hand.dimension)) {
                continue;
            }
            // getEntity by uuid only finds loaded entities, which is what we
            // want: an unloaded hand is one nobody is watching work.
            if (world.getEntity(hand.mob) instanceof VillagerEntity found) {
                return found;
            }
        }
        return null;
    }

    // --- persistence ----------------------------------------------------------

    /**
     * One line a hand: who, which villager, where, and what they know.
     *
     * The three training fields are optional on read. A file written before
     * any of this existed is six fields long and loads as an untrained hand,
     * which is exactly what those hands are -- nobody had bought them anything
     * yet, so there is nothing to migrate.
     */
    private static void load(MinecraftServer server) {
        saveFile = server.getSavePath(WorldSavePath.ROOT).resolve("trapcraft-crew.txt");
        CREW.clear();
        try {
            if (!Files.exists(saveFile)) {
                return;
            }
            for (String line : Files.readAllLines(saveFile)) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 6) {
                    continue;
                }
                Hand hand = new Hand(UUID.fromString(parts[0]), UUID.fromString(parts[1]),
                        parts[2], new BlockPos(Integer.parseInt(parts[3]),
                        Integer.parseInt(parts[4]), Integer.parseInt(parts[5])));
                if (parts.length >= 9) {
                    hand.pace = clamp(Integer.parseInt(parts[6]), PACE_TICKS.length);
                    hand.reach = clamp(Integer.parseInt(parts[7]), REACH_BLOCKS.length);
                    hand.jobs = Integer.parseInt(parts[8]) | (1 << Job.PICK.ordinal());
                }
                CREW.add(hand);
            }
        } catch (Exception failure) {
            TrapCraft.LOGGER.warn("couldn't read the crew list: {}", failure.toString());
        }
    }

    private static int clamp(int value, int rungs) {
        return Math.max(0, Math.min(value, rungs - 1));
    }

    private static void save() {
        if (saveFile == null) {
            return;
        }
        try {
            StringBuilder out = new StringBuilder();
            for (Hand hand : CREW) {
                out.append(hand.boss).append(' ').append(hand.mob).append(' ')
                        .append(hand.dimension).append(' ')
                        .append(hand.patch.getX()).append(' ')
                        .append(hand.patch.getY()).append(' ')
                        .append(hand.patch.getZ()).append(' ')
                        .append(hand.pace).append(' ')
                        .append(hand.reach).append(' ')
                        .append(hand.jobs).append('\n');
            }
            Files.writeString(saveFile, out.toString());
        } catch (Exception failure) {
            TrapCraft.LOGGER.warn("couldn't write the crew list: {}", failure.toString());
        }
    }
}
