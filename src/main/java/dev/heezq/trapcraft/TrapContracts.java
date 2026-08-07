package dev.heezq.trapcraft;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LodestoneTrackerComponent;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.StructureTags;
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
    /** Extra seconds granted per 100 blocks to the village. */
    public static final int SECONDS_PER_100 = 45;

    /** Heat carried after taking a job, and how long it lingers. */
    public static final int JOB_HEAT = 2;
    public static final int JOB_HEAT_TICKS = 20 * 60 * 6;

    /** How far from the village centre a delivery is accepted. */
    public static final int DELIVERY_RANGE = 64;

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

    /**
     * Chunk radius for the village search.
     *
     * ponytail: one locateStructure per player per day (see villageFor), so
     * every job on a given day points at the same village. Searching per slot
     * would give three or five destinations at three or five times the cost of
     * an already-blocking call. If variety matters more, search per-slot and
     * cache the whole board rather than just the destination.
     */
    private static final int SEARCH_CHUNKS = 40;

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
            return tryDeliver(actor, entity.getBlockPos()) ? ActionResult.SUCCESS : ActionResult.PASS;
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

        BlockPos village = villageFor(world, player, day);
        if (village == null) {
            return List.of();
        }

        int distance = (int) Math.sqrt(player.getBlockPos().getSquaredDistance(village));
        List<Contract> jobs = new ArrayList<>(BOARD_SIZE);
        for (int slot = 0; slot < BOARD_SIZE; slot++) {
            Strain strain = Strain.values()[random.nextInt(Strain.values().length)];
            // Better rep gets asked for better product in bigger amounts --
            // that is the whole progression, so it has to key off rep.
            int grade = Math.min(Quality.THRESHOLDS.length - 1,
                    1 + random.nextInt(2) + rep / 12);
            int quantity = 4 + random.nextInt(5) + rep / 8;

            int heatTier = TrapHeat.carryingHeat(player);
            int payout = TrapMath.payout(distance, quantity, grade, heatTier, rep);
            int seconds = BASE_SECONDS + distance / 100 * SECONDS_PER_100;

            jobs.add(new Contract(strain.ordinal(), grade, quantity,
                    village.getX(), village.getZ(),
                    world.getTime() + seconds * 20L,
                    payout, 2 + grade));
        }
        return jobs;
    }

    private record Destination(long day, BlockPos village) {
    }

    private static final Map<UUID, Destination> DESTINATIONS = new HashMap<>();

    /**
     * The day's village, looked up at most once per player per day.
     *
     * locateStructure over {@value #SEARCH_CHUNKS} chunks is a blocking
     * main-thread search. Running it on every right-click -- which is what
     * this did before the cache existed -- stalls the whole server each time
     * somebody opens the board, and opening the board is the single most
     * common thing you do with the phone.
     *
     * A null result is cached too: no village in range does not become cheaper
     * if you ask again, and the retry is what would hurt.
     */
    private static BlockPos villageFor(ServerWorld world, ServerPlayerEntity player, long day) {
        Destination cached = DESTINATIONS.get(player.getUuid());
        if (cached != null && cached.day() == day) {
            return cached.village();
        }
        BlockPos village = world.locateStructure(
                StructureTags.VILLAGE, player.getBlockPos(), SEARCH_CHUNKS, false);
        DESTINATIONS.put(player.getUuid(), new Destination(day, village));
        return village;
    }

    // --- accepting ------------------------------------------------------------

    public static void accept(ServerPlayerEntity player, ItemStack phone, Contract contract) {
        // Deadline is stored absolute, so it has to be re-stamped from the
        // moment of acceptance rather than from when the board was built.
        long seconds = Math.max(60, contract.deadlineTick() - player.getWorld().getTime()) / 20;
        Contract live = new Contract(contract.strain(), contract.minGrade(), contract.quantity(),
                contract.destX(), contract.destZ(),
                player.getWorld().getTime() + seconds * 20L,
                contract.payout(), contract.rep());
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

        int left = contract.secondsLeft(now);
        player.sendMessage(Text.literal(String.format("%s x%d  ·  %s+  ·  %d:%02d",
                                contract.strainValue().display(), contract.quantity(),
                                contract.gradeValue().display(), left / 60, left % 60))
                .formatted(left <= 30 ? Formatting.RED : Formatting.GRAY), true);
    }

    private static void fail(ServerPlayerEntity player, ItemStack phone) {
        phone.remove(TrapComponents.contract);
        adjustRep(phone, -FAIL_REP);
        ServerWorld world = player.getWorld();
        world.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), SoundCategory.PLAYERS, 1.0F, 0.5F);
        player.sendMessage(Text.literal("You missed the drop. They won't forget it.")
                .formatted(Formatting.RED), false);
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
            player.sendMessage(Text.literal("They want " + contract.quantity()
                            + ", you've got " + carrying + ".")
                    .formatted(Formatting.RED), true);
            return true;
        }
        takeGoods(player, contract);

        phone.remove(TrapComponents.contract);
        adjustRep(phone, contract.rep());
        player.getInventory().offerOrDrop(new ItemStack(Items.EMERALD, contract.payout()));

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

    /** How much matching product the player is carrying, grade included. */
    private static int countGoods(ServerPlayerEntity player, Contract contract) {
        var inventory = player.getInventory();
        var wanted = TrapContent.driedBud(contract.strainValue());
        int available = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.getItem() == wanted
                    && TrapComponents.get(stack).index() >= contract.minGrade()) {
                available += stack.getCount();
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
        var wanted = TrapContent.driedBud(contract.strainValue());
        int remaining = contract.quantity();
        for (int slot = 0; slot < inventory.size() && remaining > 0; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.getItem() != wanted
                    || TrapComponents.get(stack).index() < contract.minGrade()) {
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
