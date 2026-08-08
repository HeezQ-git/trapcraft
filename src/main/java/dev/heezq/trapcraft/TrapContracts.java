package dev.heezq.trapcraft;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LodestoneTrackerComponent;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.math.random.Random;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Work, and the trouble that comes with it.
 *
 * The board is the loop's engine: accepting a job puts heat on you, heat feeds
 * Paranoia, and the payout scales with the heat you are already carrying. That
 * coupling is the whole reason this exists rather than being a fetch quest --
 * the moment you are worth robbing is the moment the work starts paying.
 */
public final class TrapContracts {
    /** How long a delivery run is allowed to take, before distance. */
    public static final int BASE_SECONDS = 240;
    /** Extra seconds granted per 100 blocks to the drop. */
    public static final int SECONDS_PER_100 = 45;

    /** Heat carried after taking a job, and how long it lingers. */
    public static final int JOB_HEAT = 2;
    public static final int JOB_HEAT_TICKS = 20 * 60 * 6;

    /** How close to the drop a delivery is accepted. */
    public static final int DELIVERY_RANGE = 64;

    /**
     * How far a drop can be from where you took the job.
     *
     * A minimum because a buyer standing in your own farm is not a delivery,
     * it is a shop -- and every job pointing at the same place made it one
     * shop, which you could stand next to and empty your stash into. A maximum
     * because the deadline has to be survivable without an elytra.
     */
    public static final int MIN_DROP = 250;
    public static final int MAX_DROP = 800;

    /** Rep lost by letting a job run out. */
    public static final int FAIL_REP = 2;

    /**
     * Jobs on the board at once.
     *
     * Five because the board is drawn as a hopper and a hopper is five slots.
     * Raising this needs a different container type first, or the extra jobs
     * have nowhere to be shown.
     */
    public static final int BOARD_SIZE = 5;

    private TrapContracts() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getOverworld().getTime() % 20 != 0) {
                return;
            }
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                tickActive(player);
            }
        });

        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (world.isClient() || !(entity instanceof VillagerEntity)
                    || !(player instanceof ServerPlayerEntity actor) || hand != Hand.MAIN_HAND) {
                return ActionResult.PASS;
            }
            boolean isContact = entity.getCommandTags().contains(CONTACT_TAG);
            if (tryDeliver(actor, entity.getBlockPos())) {
                return ActionResult.SUCCESS;
            }
            // The contact has no trades to fall back on, so a click that isn't
            // a delivery has to say something or it reads as a broken villager.
            if (isContact) {
                sayWhatTheyWant(actor);
                return ActionResult.SUCCESS;
            }
            return ActionResult.PASS;
        });
    }

    // --- the board ------------------------------------------------------------

    /**
     * Five jobs, seeded by the world day so the board is stable until tomorrow
     * and identical across relogs. A board that reshuffles every time you open
     * it is a slot machine, not a job list.
     */
    public static List<Contract> board(ServerWorld world, ServerPlayerEntity player, int rep) {
        long day = world.getTimeOfDay() / 24000L;
        Random random = Random.create(day * 8191L + world.getSeed());

        BlockPos anchor = anchorFor(player, day);

        List<Contract> jobs = new ArrayList<>(BOARD_SIZE);
        for (int slot = 0; slot < BOARD_SIZE; slot++) {
            // Its own place, its own distance, its own money and its own
            // clock. Five jobs used to be five orders for one address.
            BlockPos drop = dropFor(anchor, day, player, slot);
            int distance = (int) Math.sqrt(anchor.getSquaredDistance(drop));
            Strain strain = Strain.values()[random.nextInt(Strain.values().length)];
            // Better rep gets asked for better product in bigger amounts --
            // that is the whole progression, so it has to key off rep.
            int grade = Math.min(Quality.THRESHOLDS.length - 1,
                    1 + random.nextInt(2) + rep / 12);
            int quantity = 4 + random.nextInt(5) + rep / 8;
            // Half the board takes either, so most jobs are flexible, and the
            // two strict kinds split the rest. A board of nothing but "either"
            // would make the field noise, and a board of nothing but strict
            // ones would mean rolling a joint at the wrong moment loses a job
            // you had already earned.
            int form = switch (random.nextInt(4)) {
                case 0 -> Contract.Form.BUDS.ordinal();
                case 1 -> Contract.Form.JOINTS.ordinal();
                default -> Contract.Form.EITHER.ordinal();
            };

            int heatTier = TrapHeat.carryingHeat(player);
            int payout = TrapMath.payout(distance, quantity, grade, heatTier, rep);
            int seconds = BASE_SECONDS + distance / 100 * SECONDS_PER_100;

            jobs.add(new Contract(strain.ordinal(), grade, quantity,
                    drop.getX(), drop.getZ(),
                    world.getTime() + seconds * 20L,
                    payout, 2 + grade, form));
        }
        return jobs;
    }

    private record Destination(long day, BlockPos anchor) {
    }

    private static final Map<UUID, Destination> DESTINATIONS = new HashMap<>();

    /**
     * Where today's board was drawn from.
     *
     * The drops are measured from here rather than from wherever you happen to
     * be standing, or the board would slide across the map as you walked and
     * the job you were looking at a second ago would be somewhere else. Cached
     * per player per day, which is the same lifetime the board itself has.
     */
    private static BlockPos anchorFor(ServerPlayerEntity player, long day) {
        Destination cached = DESTINATIONS.get(player.getUuid());
        if (cached != null && cached.day() == day) {
            return cached.anchor();
        }
        BlockPos here = player.getBlockPos();
        DESTINATIONS.put(player.getUuid(), new Destination(day, here));
        return here;
    }

    /**
     * One job's drop: a random bearing, a random distance inside the band.
     *
     * No structure search. It used to find the nearest village and send every
     * job on the board there, which cost a blocking locateStructure and bought
     * a delivery run you could do without moving. A buyer waiting in a clearing
     * a few hundred blocks out is both cheaper and more like the job.
     *
     * Seeded from the day, the player and the slot, so the board is identical
     * across relogs -- a job list that reshuffles when you close it is a slot
     * machine. The Y is thrown away: {@link #placeContact} drops the buyer on
     * whatever the surface turns out to be.
     */
    private static BlockPos dropFor(BlockPos anchor, long day,
                                    ServerPlayerEntity player, int slot) {
        int[] offset = TrapMath.dropOffset(day * 131071L
                        + player.getUuid().getLeastSignificantBits() * 31L + slot * 7919L,
                MIN_DROP, MAX_DROP);
        return new BlockPos(anchor.getX() + offset[0], anchor.getY(),
                anchor.getZ() + offset[1]);
    }

    // --- accepting ------------------------------------------------------------

    public static void accept(ServerPlayerEntity player, ItemStack phone, Contract contract) {
        // Deadline is stored absolute, so it has to be re-stamped from the
        // moment of acceptance rather than from when the board was built.
        long seconds = Math.max(60, contract.deadlineTick() - player.getWorld().getTime()) / 20;
        Contract live = new Contract(contract.strain(), contract.minGrade(), contract.quantity(),
                contract.destX(), contract.destZ(),
                player.getWorld().getTime() + seconds * 20L,
                contract.payout(), contract.rep(), contract.form());
        phone.set(TrapComponents.contract, live);

        ItemStack compass = new ItemStack(Items.COMPASS);
        // tracked=false: there is no lodestone block out there, and a tracked
        // component would spin the needle looking for one.
        compass.set(DataComponentTypes.LODESTONE_TRACKER, new LodestoneTrackerComponent(
                Optional.of(GlobalPos.create(player.getWorld().getRegistryKey(),
                        live.destination())), false));
        compass.set(DataComponentTypes.CUSTOM_NAME,
                Text.literal("Drop-off").formatted(Formatting.GOLD)
                        .styled(style -> style.withItalic(false)));
        player.getInventory().offerOrDrop(compass);

        TrapHeat.addCarriedHeat(player, JOB_HEAT, JOB_HEAT_TICKS);

        ServerWorld world = player.getWorld();
        world.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.BLOCK_NOTE_BLOCK_BIT.value(), SoundCategory.PLAYERS, 0.8F, 1.3F);
        world.spawnParticles(ParticleTypes.WAX_OFF, player.getX(), player.getEyeY(), player.getZ(),
                10, 0.3, 0.3, 0.3, 0.02);
        player.sendMessage(Text.literal("Job on. Clock's running.")
                .formatted(Formatting.GOLD), false);
        // The compass points at it, but a waypoint survives you putting the
        // compass down and shows on the world map as well.
        TrapWaypoints.offer(player, "Drop  " + contract.strainValue().display(),
                contract.destination().withY(player.getBlockPos().getY()), TrapWaypoints.GOLD);
    }

    // --- running --------------------------------------------------------------

    private static void tickActive(ServerPlayerEntity player) {
        ItemStack phone = findPhone(player);
        if (phone == null) {
            return;
        }
        Contract contract = phone.get(TrapComponents.contract);
        if (contract == null) {
            return;
        }
        long now = player.getWorld().getTime();
        if (contract.expired(now)) {
            fail(player, phone);
            return;
        }

        tickContact(player, contract);

        int left = contract.secondsLeft(now);
        player.sendMessage(Text.literal(String.format("%s x%d %s  ·  %s+  ·  %d:%02d",
                                contract.strainValue().display(), contract.quantity(),
                                shortForm(contract), contract.gradeValue().display(),
                                left / 60, left % 60))
                .formatted(left <= 30 ? Formatting.RED : Formatting.GRAY), true);
    }

    /** Three characters for the actionbar, where there is no room for a sentence. */
    private static String shortForm(Contract contract) {
        return switch (contract.formValue()) {
            case BUDS -> "bud";
            case JOINTS -> "joint";
            case EITHER -> "any";
        };
    }

    private static void fail(ServerPlayerEntity player, ItemStack phone) {
        Contract lost = phone.get(TrapComponents.contract);
        phone.remove(TrapComponents.contract);
        dismissContact(player);
        if (lost != null) {
            // Failed jobs leave litter too. A pocket full of compasses
            // pointing at villages you never reached is its own kind of
            // punishment, and not the interesting kind.
            takeCompass(player, lost);
        }
        adjustRep(phone, -FAIL_REP);
        ServerWorld world = player.getWorld();
        world.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), SoundCategory.PLAYERS, 1.0F, 0.5F);
        player.sendMessage(Text.literal("You missed the drop. They won't forget it.")
                .formatted(Formatting.RED), false);
    }

    // --- the contact ----------------------------------------------------------

    /**
     * Somebody is actually waiting for you.
     *
     * The job used to end at "right-click any villager near the village", and
     * two things were wrong with that. Nothing told you a villager was the
     * target -- you arrived, found houses, and stood there. And the village is
     * a STRUCTURE position, so a ruined one, a raided one, or one whose
     * villagers had wandered off left you at a correct address with nobody
     * home.
     *
     * So the contract brings its own contact. Get within sight of the drop and
     * a marked buyer is standing there, lit up, waiting. Right-click them.
     * There is nothing to work out.
     */
    private static final int CONTACT_RANGE = 72;
    private static final int CONTACT_FORGET = 160;
    private static final String CONTACT_TAG = "trapcraft_contact";
    private static final Map<UUID, UUID> CONTACTS = new HashMap<>();

    private static void tickContact(ServerPlayerEntity player, Contract contract) {
        ServerWorld world = player.getWorld();
        BlockPos drop = contract.destination();
        double flat = player.getBlockPos().getSquaredDistance(
                drop.getX(), player.getBlockPos().getY(), drop.getZ());

        VillagerEntity contact = contactOf(world, player);
        if (flat > (double) CONTACT_FORGET * CONTACT_FORGET) {
            if (contact != null) {
                contact.discard();
                CONTACTS.remove(player.getUuid());
            }
            return;
        }
        if (flat > (double) CONTACT_RANGE * CONTACT_RANGE) {
            return;
        }

        if (contact == null) {
            contact = placeContact(world, player, drop, contract);
            if (contact == null) {
                return;
            }
        }
        // A column of light so they can be picked out from across a village.
        // Finding the person is not supposed to be the puzzle.
        if (world.getTime() % 10 == 0) {
            for (int up = 0; up < 6; up++) {
                world.spawnParticles(ParticleTypes.END_ROD,
                        contact.getX(), contact.getY() + 1.2 + up * 0.55, contact.getZ(),
                        1, 0.04, 0.04, 0.04, 0.0);
            }
        }
    }

    private static VillagerEntity contactOf(ServerWorld world, ServerPlayerEntity player) {
        UUID id = CONTACTS.get(player.getUuid());
        if (id == null) {
            return null;
        }
        return world.getEntity(id) instanceof VillagerEntity found && found.isAlive()
                ? found : null;
    }

    private static VillagerEntity placeContact(ServerWorld world, ServerPlayerEntity player,
                                               BlockPos drop, Contract contract) {
        BlockPos spot = world.getTopPosition(
                net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, drop);
        VillagerEntity contact = net.minecraft.entity.EntityType.VILLAGER.create(
                world, net.minecraft.entity.SpawnReason.EVENT);
        if (contact == null) {
            return null;
        }
        contact.refreshPositionAndAngles(spot, 0.0F, 0.0F);
        contact.setPersistent();
        // Rooted and unkillable: a contact who wanders into a river, or gets
        // shot by the village's own pillagers, is a job you cannot finish.
        contact.setAiDisabled(true);
        contact.setInvulnerable(true);
        contact.setSilent(true);
        contact.setVillagerData(contact.getVillagerData().withProfession(
                world.getRegistryManager().getOrThrow(
                                net.minecraft.registry.RegistryKeys.VILLAGER_PROFESSION)
                        .getOrThrow(net.minecraft.village.VillagerProfession.NITWIT)));
        contact.setCustomName(Text.literal("Buyer  ·  " + contract.quantity() + "x "
                        + contract.strainValue().display())
                .formatted(Formatting.GOLD, Formatting.BOLD));
        contact.setCustomNameVisible(true);
        contact.setGlowing(true);
        contact.addCommandTag(CONTACT_TAG);
        world.spawnEntity(contact);
        CONTACTS.put(player.getUuid(), contact.getUuid());

        world.playSound(null, spot, SoundEvents.ENTITY_VILLAGER_AMBIENT,
                SoundCategory.NEUTRAL, 1.0F, 0.9F);
        player.sendMessage(Text.literal("Your buyer's here. ")
                        .formatted(Formatting.GOLD, Formatting.BOLD)
                        .append(Text.literal("Glowing one. Right-click to hand it over.")
                                .formatted(Formatting.GRAY)), false);
        return contact;
    }

    /**
     * Take back the drop-off compass when the job is over.
     *
     * Matched on where it POINTS, not on its name, so it can only ever eat
     * the compass this job handed out -- a player's own lodestone compass, or
     * one from a different job, points somewhere else and is left alone.
     */
    private static void takeCompass(ServerPlayerEntity player, Contract contract) {
        var inventory = player.getInventory();
        BlockPos drop = contract.destination();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isOf(Items.COMPASS)) {
                continue;
            }
            var tracker = stack.get(DataComponentTypes.LODESTONE_TRACKER);
            if (tracker == null || tracker.target().isEmpty()) {
                continue;
            }
            BlockPos at = tracker.target().get().pos();
            if (at.getX() == drop.getX() && at.getZ() == drop.getZ()) {
                inventory.setStack(slot, ItemStack.EMPTY);
            }
        }
    }

    /** Clicked the buyer without the goods. Tell them why nothing happened. */
    private static void sayWhatTheyWant(ServerPlayerEntity player) {
        ItemStack phone = findPhone(player);
        Contract contract = phone == null ? null : phone.get(TrapComponents.contract);
        if (contract == null) {
            player.sendMessage(Text.literal("They're waiting on somebody else.")
                    .formatted(Formatting.GRAY), true);
            return;
        }
        int carrying = countGoods(player, contract);
        player.sendMessage(Text.literal(contract.quantity() + "x ")
                        .formatted(Formatting.WHITE)
                        .append(Text.literal(contract.strainValue().display())
                                .withColor(contract.strainValue().colour()))
                        .append(Text.literal(", " + contract.formValue().label.toLowerCase(
                                        java.util.Locale.ROOT) + ", "
                                        + contract.gradeValue().display() + " or better. "
                                        + "You've got " + carrying + ".")
                                .formatted(Formatting.GRAY)),
                false);
        player.getWorld().playSound(null, player.getBlockPos(),
                SoundEvents.ENTITY_VILLAGER_NO, SoundCategory.NEUTRAL, 0.7F, 1.0F);
    }

    /** Called when the job ends, however it ends. */
    private static void dismissContact(ServerPlayerEntity player) {
        UUID id = CONTACTS.remove(player.getUuid());
        if (id == null) {
            return;
        }
        for (ServerWorld world : player.getServer().getWorlds()) {
            if (world.getEntity(id) instanceof VillagerEntity contact) {
                world.spawnParticles(ParticleTypes.POOF,
                        contact.getX(), contact.getY() + 0.8, contact.getZ(),
                        10, 0.25, 0.4, 0.25, 0.01);
                contact.discard();
            }
        }
    }

    // --- delivering -----------------------------------------------------------

    private static boolean tryDeliver(ServerPlayerEntity player, BlockPos villagerPos) {
        ItemStack phone = findPhone(player);
        if (phone == null) {
            return false;
        }
        Contract contract = phone.get(TrapComponents.contract);
        if (contract == null) {
            return false;
        }

        BlockPos destination = contract.destination();
        double dx = villagerPos.getX() - destination.getX();
        double dz = villagerPos.getZ() - destination.getZ();
        if (dx * dx + dz * dz > (double) DELIVERY_RANGE * DELIVERY_RANGE) {
            return false;   // wrong village -- let the normal trade screen open
        }

        int carrying = countGoods(player, contract);
        if (carrying == 0) {
            // Carrying none of it means this isn't a delivery attempt at all --
            // it's someone trading in the destination village. Swallowing the
            // click here would make every villager in town unusable for the
            // duration of a job.
            return false;
        }
        if (carrying < contract.quantity()) {
            player.sendMessage(Text.literal("They want " + contract.quantity() + " "
                            + contract.formValue().label.toLowerCase(java.util.Locale.ROOT)
                            + ", you've got " + carrying + ".")
                    .formatted(Formatting.RED), true);
            return true;
        }
        takeGoods(player, contract);

        phone.remove(TrapComponents.contract);
        dismissContact(player);
        takeCompass(player, contract);
        adjustRep(phone, contract.rep());
        TrapMarket.pay(player, contract.payout());

        ServerWorld world = player.getWorld();
        world.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.ENTITY_VILLAGER_YES, SoundCategory.NEUTRAL, 1.0F, 1.0F);
        world.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), SoundCategory.PLAYERS, 0.8F, 1.5F);
        world.spawnParticles(ParticleTypes.HAPPY_VILLAGER,
                player.getX(), player.getEyeY(), player.getZ(), 20, 0.5, 0.5, 0.5, 0.02);
        player.sendMessage(Text.literal("Paid. " + contract.payout() + " emeralds, +"
                        + contract.rep() + " rep.").formatted(Formatting.GREEN), false);
        return true;
    }

    /**
     * Does this stack settle the contract?
     *
     * Right strain, right form, at or above the grade. A joint counts as one
     * bud rather than as anything cleverer: it costs exactly one bud to roll,
     * so counting it as one keeps an EITHER job worth the same either way and
     * leaves rolling a decision about the customers next door rather than a
     * tax or a bonus here.
     */
    private static boolean settles(ItemStack stack, Contract contract) {
        if (stack.isEmpty() || TrapComponents.get(stack).index() < contract.minGrade()) {
            return false;
        }
        var item = stack.getItem();
        if (contract.takesBuds() && item == TrapContent.driedBud(contract.strainValue())) {
            return true;
        }
        return contract.takesJoints() && item == TrapContent.joint(contract.strainValue());
    }

    /** How much matching product the player is carrying, grade and form included. */
    private static int countGoods(ServerPlayerEntity player, Contract contract) {
        var inventory = player.getInventory();
        int available = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (settles(inventory.getStack(slot), contract)) {
                available += inventory.getStack(slot).getCount();
            }
        }
        return available;
    }

    /**
     * Remove exactly the contracted amount.
     *
     * Only ever called once countGoods has confirmed there is enough: a
     * partial take would eat somebody's buds and hand back neither goods nor
     * emeralds.
     */
    private static void takeGoods(ServerPlayerEntity player, Contract contract) {
        var inventory = player.getInventory();
        int remaining = contract.quantity();
        for (int slot = 0; slot < inventory.size() && remaining > 0; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!settles(stack, contract)) {
                continue;
            }
            int taken = Math.min(remaining, stack.getCount());
            stack.decrement(taken);
            remaining -= taken;
        }
    }

    // --- phone helpers --------------------------------------------------------

    public static ItemStack findPhone(ServerPlayerEntity player) {
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.getItem() == TrapContent.burnerPhone) {
                return stack;
            }
        }
        return null;
    }

    public static int repOf(ItemStack phone) {
        Integer value = phone.get(TrapComponents.rep);
        return value == null ? 0 : value;
    }

    public static void adjustRep(ItemStack phone, int delta) {
        phone.set(TrapComponents.rep, Math.max(0, repOf(phone) + delta));
    }
}
