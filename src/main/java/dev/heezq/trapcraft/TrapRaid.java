package dev.heezq.trapcraft;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.List;

/**
 * What the raiders came for.
 *
 * A patrol that only swings axes is a mob spawner with a story attached: you
 * kill four pillagers, collect the drops, and the grow is untouched. The whole
 * point of heat is that being seen should cost you something, so the raid now
 * searches -- it walks to your containers, opens them, and takes product out.
 *
 * Which makes a lot of the rest of the mod suddenly matter. Hiding a stash
 * underground is worth doing. Splitting it across two buildings is worth
 * doing. So is standing between them and the chest, because a dead raider
 * searches nothing.
 */
public final class TrapRaid {
    /** How far from the raid site a container can be and still get turned over. */
    private static final int SEARCH_RADIUS = 24;
    /** Close enough to have their hands in it. */
    private static final double REACH = 2.6;
    /** Ticks between search steps. Slow -- this is a ransack, not a vacuum. */
    private static final int STEP_TICKS = 40;
    /** How long a raid keeps looking before the trail goes cold. */
    private static final int SEARCH_TICKS = 20 * 60 * 4;
    /** Most items taken from one container in one go. */
    private static final int HAUL = 16;

    /** One raid in progress, and what it is working through. */
    private record Search(ServerWorld world, BlockPos site, List<MobEntity> raiders,
                          int[] left, int[] clean) {
    }

    private static final List<Search> RUNNING = new ArrayList<>();

    private TrapRaid() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (RUNNING.isEmpty() || server.getTicks() % STEP_TICKS != 0) {
                return;
            }
            RUNNING.removeIf(search -> !step(search));
        });
    }

    /** Called when a patrol lands, with the mobs it brought. */
    public static void begin(ServerWorld world, BlockPos site, List<MobEntity> raiders) {
        if (raiders.isEmpty()) {
            return;
        }
        RUNNING.add(new Search(world, site, raiders, new int[]{SEARCH_TICKS},
                new int[]{1}));
    }

    /**
     * One step of the ransack.
     *
     * @return false when this raid is finished with and should be forgotten
     */
    private static boolean step(Search search) {
        search.left()[0] -= STEP_TICKS;
        search.raiders().removeIf(raider -> !raider.isAlive());
        if (search.left()[0] <= 0 || search.raiders().isEmpty()) {
            // Saw the whole thing out with nothing taken. Worth marking --
            // it means the stash was somewhere they couldn't reach.
            if (search.clean()[0] == 1) {
                for (ServerPlayerEntity player : search.world().getPlayers()) {
                    if (player.getBlockPos().isWithinDistance(search.site(), 96)) {
                        TrapAwards.grant(player, "clean");
                    }
                }
            }
            return false;
        }

        BlockPos target = nearestStash(search.world(), search.site());
        if (target == null) {
            search.clean()[0] = 1;   // nothing to find, so far
            return true;             // keep patrolling anyway
        }
        search.clean()[0] = 0;

        for (MobEntity raider : search.raiders()) {
            if (raider.squaredDistanceTo(target.getX() + 0.5, target.getY() + 0.5,
                    target.getZ() + 0.5) <= REACH * REACH) {
                ransack(search.world(), target, raider);
                return true;   // one container per step, so it reads as searching
            }
            // Steering by navigation rather than by adding an AI goal: the
            // goal selector is protected and would need a mixin, and all this
            // needs is "walk over there", which the navigator does publicly.
            raider.getNavigation().startMovingTo(
                    target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 1.0);
        }
        return true;
    }

    /** The closest container within reach of the site that has product in it. */
    private static BlockPos nearestStash(ServerWorld world, BlockPos site) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;

        for (int cx = (site.getX() - SEARCH_RADIUS) >> 4;
             cx <= (site.getX() + SEARCH_RADIUS) >> 4; cx++) {
            for (int cz = (site.getZ() - SEARCH_RADIUS) >> 4;
                 cz <= (site.getZ() + SEARCH_RADIUS) >> 4; cz++) {
                WorldChunk chunk = world.getChunkManager().getWorldChunk(cx, cz);
                if (chunk == null) {
                    continue;
                }
                for (var entry : chunk.getBlockEntities().entrySet()) {
                    BlockPos pos = entry.getKey();
                    double distance = site.getSquaredDistance(pos);
                    if (distance >= bestDistance || distance > SEARCH_RADIUS * SEARCH_RADIUS) {
                        continue;
                    }
                    if (entry.getValue() instanceof Inventory box && holdsProduct(box)) {
                        best = pos;
                        bestDistance = distance;
                    }
                }
            }
        }
        return best;
    }

    private static boolean holdsProduct(Inventory box) {
        for (int slot = 0; slot < box.size(); slot++) {
            if (TrapContent.isContraband(box.getStack(slot))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Take what's in it.
     *
     * Deliberately not everything. A raid that empties a double chest is a
     * disaster you never recover from and stops anybody keeping a stash at
     * all; a raid that takes an armful is a reason to move it somewhere else.
     */
    private static void ransack(ServerWorld world, BlockPos pos, MobEntity raider) {
        BlockEntity block = world.getBlockEntity(pos);
        if (!(block instanceof Inventory box)) {
            return;
        }
        int owed = HAUL;
        int taken = 0;
        Text what = null;
        for (int slot = 0; slot < box.size() && owed > 0; slot++) {
            ItemStack stack = box.getStack(slot);
            if (!TrapContent.isContraband(stack)) {
                continue;
            }
            if (what == null) {
                what = stack.getName();
            }
            int grab = Math.min(owed, stack.getCount());
            stack.decrement(grab);
            owed -= grab;
            taken += grab;
        }
        if (taken <= 0) {
            return;
        }
        box.markDirty();

        world.playSound(null, pos, SoundEvents.BLOCK_CHEST_OPEN, SoundCategory.BLOCKS, 0.9F, 0.8F);
        world.playSound(null, pos, SoundEvents.ENTITY_ILLUSIONER_PREPARE_MIRROR,
                SoundCategory.HOSTILE, 0.7F, 1.1F);
        world.spawnParticles(ParticleTypes.ANGRY_VILLAGER,
                pos.getX() + 0.5, pos.getY() + 1.1, pos.getZ() + 0.5, 12, 0.3, 0.3, 0.3, 0.02);
        raider.swingHand(net.minecraft.util.Hand.MAIN_HAND);

        Text line = Text.literal("They found it. ").formatted(Formatting.RED, Formatting.BOLD)
                .append(Text.literal(taken + "x ").formatted(Formatting.WHITE))
                .append(what == null ? Text.literal("product") : what)
                .append(Text.literal(" gone from a chest at " + pos.getX() + " "
                        + pos.getY() + " " + pos.getZ()).formatted(Formatting.GRAY));
        for (ServerPlayerEntity player : world.getPlayers()) {
            if (player.getBlockPos().isWithinDistance(pos, 96)) {
                player.sendMessage(line, false);
                TrapAwards.grant(player, "raided");
            }
        }
    }

    /** Entities the raid brought, for {@link #begin}. */
    public static List<MobEntity> mobsAround(ServerWorld world, BlockPos site, int radius) {
        List<MobEntity> found = new ArrayList<>();
        for (Entity entity : world.getEntitiesByClass(MobEntity.class,
                new Box(site).expand(radius), mob -> mob.isAlive())) {
            found.add((MobEntity) entity);
        }
        return found;
    }
}
