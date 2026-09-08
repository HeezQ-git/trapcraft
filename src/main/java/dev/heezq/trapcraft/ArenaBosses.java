package dev.heezq.trapcraft;

import net.minecraft.util.math.random.Random;

import java.util.ArrayList;
import java.util.List;

/**
 * Every boss the arena can put up, and the draw.
 *
 * The draw never repeats the last one: with four in the pool a run of the
 * same boss twice is a coin toss worth of nights lost, and the whole point
 * of a second boss was that the omen might be somebody else.
 */
public final class ArenaBosses {
    private static final List<ArenaBoss> ALL = new ArrayList<>();

    private ArenaBosses() {
    }

    public static void register(ArenaBoss boss) {
        ALL.add(boss);
    }

    public static List<ArenaBoss> all() {
        return List.copyOf(ALL);
    }

    public static ArenaBoss byId(String id) {
        for (ArenaBoss boss : ALL) {
            if (boss.id().equals(id)) {
                return boss;
            }
        }
        return null;
    }

    /** A random boss, never the one that came last if there is a choice. */
    public static ArenaBoss draw(Random random, String lastId) {
        List<ArenaBoss> pool = new ArrayList<>(ALL);
        if (pool.size() > 1 && lastId != null) {
            pool.removeIf(boss -> boss.id().equals(lastId));
        }
        return pool.isEmpty() ? null : pool.get(random.nextInt(pool.size()));
    }
}
