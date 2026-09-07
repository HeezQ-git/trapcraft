package dev.heezq.trapcraft;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.command.argument.BlockArgumentParser;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * An arena, as data.
 *
 * {@code tools/gen_arena.py} writes every block of each arena into
 * {@code data/trapcraft/arena/<id>.blocks.gz}, one per line, relative to
 * the origin; this reads it back and stamps it into the world. Built from
 * a blueprint rather than a loop of {@code setBlockState} calls so the
 * preview on the desk and the pit on the server are the same object, and so
 * a pillar can be moved without touching Java.
 *
 * Tagged lines are the blocks a fight switches: {@link #tagged} hands them
 * to the boss and {@link #original} remembers what to put back. Positions
 * out of here are absolute; the blueprint knows its own origin.
 */
public final class ArenaBlueprint {
    /** Anything not in the blueprint inside this cylinder is cleared on a build. */
    private static final int CLEAR_RADIUS = 36;
    private static final int CLEAR_BELOW = 6;
    private static final int CLEAR_ABOVE = 30;

    private record Entry(BlockPos pos, BlockState state, String tag) {
    }

    private final String id;
    private final BlockPos origin;
    private final List<Entry> entries = new ArrayList<>();
    private final Map<String, List<BlockPos>> tags = new HashMap<>();
    private final Map<BlockPos, BlockState> states = new HashMap<>();

    private ArenaBlueprint(String id, BlockPos origin) {
        this.id = id;
        this.origin = origin;
    }

    public static ArenaBlueprint load(String id, BlockPos origin) {
        ArenaBlueprint blueprint = new ArenaBlueprint(id, origin);
        String path = "/data/trapcraft/arena/" + id + ".blocks.gz";
        try (var raw = TrapCraft.class.getResourceAsStream(path)) {
            if (raw == null) {
                throw new IllegalStateException(path + " missing from the jar");
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new GZIPInputStream(raw), StandardCharsets.UTF_8));
            String line;
            int bad = 0;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.trim().split(" ");
                if (parts.length < 4) {
                    continue;
                }
                BlockPos pos = new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[2]));
                BlockState state;
                try {
                    state = BlockArgumentParser.block(Registries.BLOCK, parts[3], false).blockState();
                } catch (Exception e) {
                    if (bad++ < 5) {
                        TrapCraft.LOGGER.warn("arena {}: cannot parse '{}' ({})", id, parts[3], e.getMessage());
                    }
                    continue;
                }
                String tag = parts.length > 4 ? parts[4] : null;
                blueprint.entries.add(new Entry(pos, state, tag));
                blueprint.states.put(pos, state);
                if (tag != null) {
                    blueprint.tags.computeIfAbsent(tag, key -> new ArrayList<>()).add(pos);
                }
            }
            if (bad > 0) {
                TrapCraft.LOGGER.warn("arena {}: {} lines skipped", id, bad);
            }
        } catch (Exception e) {
            TrapCraft.LOGGER.error("arena {} unreadable -- it will be a void", id, e);
        }
        TrapCraft.LOGGER.info("arena {}: {} blocks, {} tagged", id, blueprint.entries.size(),
                blueprint.tags.values().stream().mapToInt(List::size).sum());
        return blueprint;
    }

    public String id() {
        return id;
    }

    public BlockPos origin() {
        return origin;
    }

    public int size() {
        return entries.size();
    }

    /**
     * Stamp the arena, clearing whatever else is in the way.
     *
     * The clear pass is what makes a rebuild a reset: a block somebody left
     * on the floor last time is gone, and a pillar somebody chipped is back.
     * No neighbour updates -- nothing here needs them and twenty thousand
     * of them in one tick is a stall for no reason.
     */
    public void build(ServerWorld world) {
        int flags = Block.NOTIFY_LISTENERS | Block.SKIP_DROPS;
        int chunks = (CLEAR_RADIUS >> 4) + 1;
        for (int cx = -chunks; cx <= chunks; cx++) {
            for (int cz = -chunks; cz <= chunks; cz++) {
                world.getChunk((origin.getX() >> 4) + cx, (origin.getZ() >> 4) + cz);
            }
        }
        BlockState air = Blocks.AIR.getDefaultState();
        BlockPos.Mutable cursor = new BlockPos.Mutable();
        for (int x = -CLEAR_RADIUS; x <= CLEAR_RADIUS; x++) {
            for (int z = -CLEAR_RADIUS; z <= CLEAR_RADIUS; z++) {
                if (x * x + z * z > CLEAR_RADIUS * CLEAR_RADIUS) {
                    continue;
                }
                for (int y = -CLEAR_BELOW; y <= CLEAR_ABOVE; y++) {
                    if (states.containsKey(new BlockPos(x, y, z))) {
                        continue;
                    }
                    cursor.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    if (!world.getBlockState(cursor).isAir()) {
                        world.setBlockState(cursor, air, flags);
                    }
                }
            }
        }
        for (Entry entry : entries) {
            world.setBlockState(origin.add(entry.pos()), entry.state(), flags);
        }
    }

    /** World positions of every block carrying {@code tag}. */
    public List<BlockPos> tagged(String tag) {
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos pos : tags.getOrDefault(tag, List.of())) {
            out.add(origin.add(pos));
        }
        return out;
    }

    /** What the blueprint puts at a world position, or air. */
    public BlockState original(BlockPos world) {
        return states.getOrDefault(world.subtract(origin), Blocks.AIR.getDefaultState());
    }
}
