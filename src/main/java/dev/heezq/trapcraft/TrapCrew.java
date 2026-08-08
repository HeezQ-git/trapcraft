package dev.heezq.trapcraft;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.CropBlock;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.WorldSavePath;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Somebody to do the picking.
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
 */
public final class TrapCrew {
    /** How far a hand works from where you hired them. */
    public static final int REACH = 12;
    /** Ticks between a hand doing anything. Roughly ten seconds. */
    private static final int WORK_TICKS = 200;
    /** Ticks between wage packets. Five minutes. */
    private static final int WAGE_TICKS = 20 * 60 * 5;
    /** What one hand costs per packet. */
    public static final int WAGE = 24;
    /** What it costs to take somebody on. */
    public static final int HIRE_COST = 120;

    /** One hire: who they work for, where, and when they were last paid. */
    private record Hand(UUID boss, UUID mob, String dimension, BlockPos patch) {
    }

    private static final List<Hand> CREW = new ArrayList<>();
    private static Path saveFile;

    private TrapCrew() {
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(TrapCrew::load);
        registerCommand();
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTicks() % WORK_TICKS == 0) {
                work(server);
            }
            if (server.getTicks() % WAGE_TICKS == 0) {
                payday(server);
            }
        });
    }

    /** How many hands this player is carrying. */
    public static int sizeOf(ServerPlayerEntity boss) {
        return (int) CREW.stream().filter(hand -> hand.boss().equals(boss.getUuid())).count();
    }

    /**
     * Take somebody on at this spot.
     *
     * @return why it didn't happen, or null if it did
     */
    public static String hire(ServerPlayerEntity boss, BlockPos patch) {
        if (sizeOf(boss) >= 3) {
            return "Three hands is all one operation will carry.";
        }
        if (TrapMarket.wealthOf(boss) < HIRE_COST) {
            return "Taking somebody on costs " + HIRE_COST + "e.";
        }
        ServerWorld world = boss.getWorld();
        VillagerEntity hand = EntityType.VILLAGER.create(world, SpawnReason.EVENT);
        if (hand == null) {
            return "Nobody's about.";
        }
        TrapMarket.take(boss, HIRE_COST);

        hand.refreshPositionAndAngles(patch.up(), boss.getYaw(), 0.0F);
        hand.setPersistent();
        hand.setAiDisabled(false);
        hand.setCustomName(Text.literal("Hand").formatted(Formatting.YELLOW));
        hand.setCustomNameVisible(true);
        // No trades: this is staff, not a shop. A villager you can barter with
        // would undercut the market stall by accident.
        hand.setInvulnerable(false);
        world.spawnEntity(hand);

        CREW.add(new Hand(boss.getUuid(), hand.getUuid(),
                world.getRegistryKey().getValue().toString(), patch));
        save();

        world.playSound(null, patch, SoundEvents.ENTITY_VILLAGER_YES,
                SoundCategory.NEUTRAL, 0.9F, 1.1F);
        TrapAwards.grant(boss, "crew");
        boss.sendMessage(Text.literal("Hired. ").formatted(Formatting.GREEN, Formatting.BOLD)
                .append(Text.literal("They'll work " + REACH + " blocks around here for "
                        + WAGE + "e every five minutes.").formatted(Formatting.GRAY)), false);
        return null;
    }

    /** Let somebody go, nearest first. */
    public static String fire(ServerPlayerEntity boss) {
        for (int i = CREW.size() - 1; i >= 0; i--) {
            Hand hand = CREW.get(i);
            if (!hand.boss().equals(boss.getUuid())) {
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
     * /crew hire, /crew fire, /crew.
     *
     * A command rather than an item because hiring is about a PLACE -- where
     * you are standing is the patch they work -- and an item you right-click
     * on the ground would need a model, a recipe and a texture to say the same
     * thing a word already says.
     */
    private static void registerCommand() {
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register(
                (dispatcher, access, env) -> dispatcher.register(
                        net.minecraft.server.command.CommandManager.literal("crew")
                                .executes(context -> report(context.getSource()))
                                .then(net.minecraft.server.command.CommandManager.literal("hire")
                                        .executes(context -> {
                                            ServerPlayerEntity boss = context.getSource().getPlayer();
                                            if (boss == null) {
                                                return 0;
                                            }
                                            String no = hire(boss, boss.getBlockPos());
                                            if (no != null) {
                                                boss.sendMessage(Text.literal(no)
                                                        .formatted(Formatting.GRAY), false);
                                            }
                                            return 1;
                                        }))
                                .then(net.minecraft.server.command.CommandManager.literal("fire")
                                        .executes(context -> {
                                            ServerPlayerEntity boss = context.getSource().getPlayer();
                                            if (boss == null) {
                                                return 0;
                                            }
                                            String no = fire(boss);
                                            if (no != null) {
                                                boss.sendMessage(Text.literal(no)
                                                        .formatted(Formatting.GRAY), false);
                                            }
                                            return 1;
                                        }))));
    }

    private static int report(net.minecraft.server.command.ServerCommandSource source) {
        ServerPlayerEntity boss = source.getPlayer();
        if (boss == null) {
            return 0;
        }
        int on = sizeOf(boss);
        boss.sendMessage(Text.literal("Crew  ").formatted(Formatting.GOLD, Formatting.BOLD)
                .append(Text.literal(on == 0 ? "Nobody on the books."
                                : on + " on, " + on * WAGE + "e every five minutes.")
                        .formatted(Formatting.GRAY))
                .append(Text.literal("\n  /crew hire").formatted(Formatting.GREEN))
                .append(Text.literal("  " + HIRE_COST + "e, works " + REACH
                        + " blocks around where you stand").formatted(Formatting.DARK_GRAY))
                .append(Text.literal("\n  /crew fire").formatted(Formatting.GREEN))
                .append(Text.literal("  let the last one go").formatted(Formatting.DARK_GRAY)),
                false);
        return 1;
    }

    // --- the work -------------------------------------------------------------

    /**
     * Each hand picks one mature plant and puts it away.
     *
     * One per pass, not a sweep. A hand that cleared a field every ten seconds
     * would make the whole growing half of the mod a formality; one that brings
     * in a plant at a time is somebody helping rather than a machine replacing
     * you.
     */
    private static void work(MinecraftServer server) {
        for (Hand hand : CREW) {
            VillagerEntity mob = find(server, hand);
            if (mob == null) {
                continue;
            }
            ServerWorld world = (ServerWorld) mob.getWorld();
            BlockPos ripe = findRipe(world, hand.patch());
            if (ripe == null) {
                continue;
            }
            mob.getNavigation().startMovingTo(ripe.getX() + 0.5, ripe.getY(), ripe.getZ() + 0.5, 0.6);
            if (!mob.getBlockPos().isWithinDistance(ripe, 3.0)) {
                continue;   // still walking over; pick it next pass
            }

            List<ItemStack> picked = net.minecraft.block.Block.getDroppedStacks(
                    world.getBlockState(ripe), world, ripe, null);
            world.breakBlock(ripe, false);
            for (ItemStack drop : picked) {
                if (!store(world, hand.patch(), drop)) {
                    net.minecraft.block.Block.dropStack(world, ripe, drop);
                }
            }
            world.spawnParticles(ParticleTypes.HAPPY_VILLAGER,
                    ripe.getX() + 0.5, ripe.getY() + 0.6, ripe.getZ() + 0.5, 6, 0.3, 0.3, 0.3, 0.01);
            world.playSound(null, ripe, SoundEvents.BLOCK_CROP_BREAK,
                    SoundCategory.NEUTRAL, 0.6F, 1.0F);
        }
    }

    /** A mature crop of ours within reach of the patch. */
    private static BlockPos findRipe(ServerWorld world, BlockPos patch) {
        for (BlockPos pos : BlockPos.iterateOutwards(patch, REACH, 4, REACH)) {
            BlockState state = world.getBlockState(pos);
            if (!(state.getBlock() instanceof CannabisCropBlock)
                    && !(state.getBlock() instanceof CocaCropBlock)) {
                continue;
            }
            if (state.getBlock() instanceof CropBlock crop && crop.isMature(state)) {
                return pos.toImmutable();
            }
        }
        return null;
    }

    /** Put a drop in the nearest container. False if there's nowhere to put it. */
    private static boolean store(ServerWorld world, BlockPos patch, ItemStack drop) {
        for (BlockPos pos : BlockPos.iterateOutwards(patch, REACH, 4, REACH)) {
            if (!(world.getBlockEntity(pos) instanceof net.minecraft.inventory.Inventory box)) {
                continue;
            }
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
        }
        return false;
    }

    // --- payday ---------------------------------------------------------------

    /**
     * Wages, and what happens when you can't cover them.
     *
     * They walk. Not a warning, not a debt -- a hand who isn't paid stops being
     * your hand, which is the only consequence that makes the wage a real cost
     * rather than a number that accrues somewhere you never look.
     */
    private static void payday(MinecraftServer server) {
        List<Hand> quit = new ArrayList<>();
        for (Hand hand : CREW) {
            ServerPlayerEntity boss = server.getPlayerManager().getPlayer(hand.boss());
            if (boss == null) {
                continue;   // nobody home; wages wait until they log in
            }
            if (TrapMarket.wealthOf(boss) < WAGE) {
                quit.add(hand);
                boss.sendMessage(Text.literal("You couldn't make wages. ")
                        .formatted(Formatting.RED)
                        .append(Text.literal("They walked.").formatted(Formatting.GRAY)), false);
                continue;
            }
            TrapMarket.take(boss, WAGE);
            boss.sendMessage(Text.literal("Wages: ").formatted(Formatting.DARK_GRAY)
                    .append(Text.literal("-" + WAGE + "e").formatted(Formatting.RED)), true);
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
        for (ServerWorld world : server.getWorlds()) {
            if (!world.getRegistryKey().getValue().toString().equals(hand.dimension())) {
                continue;
            }
            // getEntity by uuid only finds loaded entities, which is what we
            // want: an unloaded hand is one nobody is watching work.
            if (world.getEntity(hand.mob()) instanceof VillagerEntity found) {
                return found;
            }
        }
        return null;
    }

    /** Anything of ours standing around here, for the hire command's sanity check. */
    public static boolean crowded(ServerWorld world, BlockPos patch) {
        return !world.getEntitiesByClass(VillagerEntity.class,
                new Box(patch).expand(4), villager -> villager.hasCustomName()).isEmpty();
    }

    // --- persistence ----------------------------------------------------------

    private static void load(MinecraftServer server) {
        saveFile = server.getSavePath(WorldSavePath.ROOT).resolve("trapcraft-crew.txt");
        CREW.clear();
        try {
            if (!Files.exists(saveFile)) {
                return;
            }
            for (String line : Files.readAllLines(saveFile)) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length == 6) {
                    CREW.add(new Hand(UUID.fromString(parts[0]), UUID.fromString(parts[1]),
                            parts[2], new BlockPos(Integer.parseInt(parts[3]),
                            Integer.parseInt(parts[4]), Integer.parseInt(parts[5]))));
                }
            }
        } catch (Exception failure) {
            TrapCraft.LOGGER.warn("couldn't read the crew list: {}", failure.toString());
        }
    }

    private static void save() {
        if (saveFile == null) {
            return;
        }
        try {
            StringBuilder out = new StringBuilder();
            for (Hand hand : CREW) {
                out.append(hand.boss()).append(' ').append(hand.mob()).append(' ')
                        .append(hand.dimension()).append(' ')
                        .append(hand.patch().getX()).append(' ')
                        .append(hand.patch().getY()).append(' ')
                        .append(hand.patch().getZ()).append('\n');
            }
            Files.writeString(saveFile, out.toString());
        } catch (Exception failure) {
            TrapCraft.LOGGER.warn("couldn't write the crew list: {}", failure.toString());
        }
    }
}
