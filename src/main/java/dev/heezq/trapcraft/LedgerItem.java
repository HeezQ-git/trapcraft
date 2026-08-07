package dev.heezq.trapcraft;

import eu.pb4.polymer.core.api.item.PolymerItem;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;
import xyz.nucleoid.packettweaker.PacketContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where did I put the iron.
 *
 * Answers that without a client mod: it reads every container around you,
 * shows the totals in a vanilla chest screen, and then draws a line of
 * particles through the air to whichever pile you picked. The line is the
 * point -- a list of coordinates is a debug command, a trail you can follow is
 * a tool.
 */
public class LedgerItem extends Item implements PolymerItem {
    /** How far the index reaches. Horizontal is generous, vertical is not. */
    public static final int RADIUS_H = 32;
    public static final int RADIUS_V = 16;

    /** Containers pinged per click. More than this and the air is soup. */
    public static final int MAX_PINGS = 6;

    private final Identifier model;

    public LedgerItem(Settings settings, Identifier model) {
        super(settings);
        this.model = model;
    }

    @Override
    public Item getPolymerItem(ItemStack stack, PacketContext context) {
        return Items.BOOK;
    }

    @Override
    public Identifier getPolymerItemModel(ItemStack stack, PacketContext context) {
        return model;
    }

    @Override
    public ActionResult use(World world, PlayerEntity user, Hand hand) {
        if (!(world instanceof ServerWorld server) || !(user instanceof ServerPlayerEntity player)) {
            return ActionResult.SUCCESS;
        }

        Scan scan = scan(server, player.getBlockPos());
        if (scan.rows().isEmpty()) {
            player.sendMessage(Text.literal("Nothing stored nearby.")
                    .formatted(Formatting.GRAY), true);
            server.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), SoundCategory.PLAYERS, 0.5F, 0.7F);
            return ActionResult.SUCCESS;
        }

        server.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.ITEM_BOOK_PAGE_TURN, SoundCategory.PLAYERS, 0.9F, 1.0F);
        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, inventory, ignored) -> new LedgerScreenHandler(syncId, inventory, scan),
                Text.literal("The Ledger").formatted(Formatting.DARK_GRAY)));
        return ActionResult.SUCCESS;
    }

    // --- scanning -------------------------------------------------------------

    /** What the search found: display rows, plus where each item actually is. */
    public record Scan(List<TrapMath.Tally<Item>> rows, Map<Item, List<BlockPos>> where,
                       BlockPos origin) {
    }

    /**
     * Read every loaded container in range.
     *
     * Iterates each chunk's block-entity map rather than the ~139k block
     * positions the volume contains, and uses getWorldChunk so an unloaded
     * chunk is skipped instead of being dragged into memory by a search.
     */
    public static Scan scan(ServerWorld world, BlockPos centre) {
        List<Map.Entry<Item, Integer>> found = new ArrayList<>();
        Map<Item, List<BlockPos>> where = new HashMap<>();

        int minY = centre.getY() - RADIUS_V;
        int maxY = centre.getY() + RADIUS_V;

        for (int cx = (centre.getX() - RADIUS_H) >> 4; cx <= (centre.getX() + RADIUS_H) >> 4; cx++) {
            for (int cz = (centre.getZ() - RADIUS_H) >> 4; cz <= (centre.getZ() + RADIUS_H) >> 4; cz++) {
                WorldChunk chunk = world.getChunkManager().getWorldChunk(cx, cz);
                if (chunk == null) {
                    continue;
                }
                for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
                    BlockPos pos = entry.getKey();
                    if (pos.getY() < minY || pos.getY() > maxY
                            || centre.getSquaredDistance(pos) > (double) RADIUS_H * RADIUS_H) {
                        continue;
                    }
                    if (!(entry.getValue() instanceof Inventory container)) {
                        continue;
                    }
                    readContainer(container, pos, found, where);
                }
            }
        }
        return new Scan(TrapMath.aggregate(found), where, centre);
    }

    /**
     * Sum one container into the running tally.
     *
     * Stacks are merged per container before being handed to aggregate(),
     * because the container count there is "how many entries carried this key"
     * -- four stacks of iron in one chest must not read as four chests.
     */
    private static void readContainer(Inventory container, BlockPos pos,
                                      List<Map.Entry<Item, Integer>> found,
                                      Map<Item, List<BlockPos>> where) {
        Map<Item, Integer> here = new LinkedHashMap<>();
        for (int slot = 0; slot < container.size(); slot++) {
            ItemStack stack = container.getStack(slot);
            if (stack.isEmpty()) {
                continue;
            }
            here.merge(stack.getItem(), stack.getCount(), Integer::sum);
            // One level into shulker boxes: people store their good stuff in a
            // box inside a chest, and a ledger that can't see it is useless
            // exactly when you need it.
            ContainerComponent nested = stack.get(DataComponentTypes.CONTAINER);
            if (nested != null) {
                nested.iterateNonEmpty().forEach(inner ->
                        here.merge(inner.getItem(), inner.getCount(), Integer::sum));
            }
        }
        here.forEach((item, count) -> {
            found.add(Map.entry(item, count));
            where.computeIfAbsent(item, key -> new ArrayList<>()).add(pos);
        });
    }

    // --- the trail ------------------------------------------------------------

    private record Trail(ServerPlayerEntity player, List<Vec3d> points, int cursor) {
    }

    private static final List<Trail> TRAILS = new ArrayList<>();

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> advanceTrails());
    }

    /**
     * Draw a line of particles from the player to each holding container.
     *
     * Advanced a few points per tick rather than dumped at once, so it reads
     * as the ledger drawing you a route instead of a wall of sparks appearing.
     */
    public static void ping(ServerPlayerEntity player, List<BlockPos> targets) {
        Vec3d from = player.getEyePos();
        int pinged = 0;
        for (BlockPos target : targets) {
            if (pinged >= MAX_PINGS) {
                // Never truncate silently: "3 more" is information, an empty
                // trail to a chest you own is a bug report.
                // Chat, not the actionbar: a contract countdown may already be
                // living there, and one would silently replace the other.
                player.sendMessage(Text.literal("+" + (targets.size() - MAX_PINGS)
                                + " more containers not traced")
                        .formatted(Formatting.DARK_GRAY), false);
                break;
            }
            Vec3d to = Vec3d.ofCenter(target);
            int steps = Math.max(4, (int) from.distanceTo(to));
            List<Vec3d> points = new ArrayList<>(steps);
            for (int i = 0; i <= steps; i++) {
                points.add(from.lerp(to, i / (double) steps));
            }
            TRAILS.add(new Trail(player, points, 0));
            pinged++;
        }
    }

    private static void advanceTrails() {
        if (TRAILS.isEmpty()) {
            return;
        }
        // A trail outlives the search that started it, so a player who logs
        // out mid-draw would leave one behind pushing packets at a dead
        // connection every tick until it finished.
        TRAILS.removeIf(trail -> trail.player().isRemoved()
                || trail.player().networkHandler == null);
        TRAILS.replaceAll(trail -> {
            int next = Math.min(trail.points().size(), trail.cursor() + 3);
            for (int i = trail.cursor(); i < next; i++) {
                TrapPhantom.particles(trail.player(), ParticleTypes.END_ROD,
                        trail.points().get(i), 1, 0.02, 0.0);
            }
            if (next >= trail.points().size()) {
                Vec3d end = trail.points().get(trail.points().size() - 1);
                TrapPhantom.particles(trail.player(), ParticleTypes.GLOW, end, 12, 0.3, 0.02);
                TrapPhantom.sound(trail.player(), end,
                        SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 0.7F, 1.4F);
            }
            return new Trail(trail.player(), trail.points(), next);
        });
        TRAILS.removeIf(trail -> trail.cursor() >= trail.points().size());
    }
}
