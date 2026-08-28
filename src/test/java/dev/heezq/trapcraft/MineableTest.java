package dev.heezq.trapcraft;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * requiresTool() without a mineable tag is a block that cannot be mined.
 *
 * The two halves live in different languages and neither complains about the
 * other. requiresTool() promises "the correct tool drops this", but nothing in
 * Java decides what correct means -- the pickaxe's tool component does, and it
 * only claims blocks listed in minecraft:mineable/pickaxe. A block that
 * requires a tool and appears in no mineable tag therefore has no correct tool
 * anywhere in the game: it mines at bare-hand speed and drops nothing, however
 * you break it, with no error on either side.
 *
 * Six blocks shipped that way before a player put a pickaxe through a block of
 * dirty emeralds and watched a diamond's worth of money disappear. This reads
 * the source the way {@link WardTest} does, so the next block registered with
 * requiresTool() fails a build instead of a player.
 */
class MineableTest {

    private static final String CALL = "registerBlock(\"";

    @Test
    void everyToolRequiredBlockIsMineableBySomething() throws Exception {
        String src = Files.readString(
                Path.of("src/main/java/dev/heezq/trapcraft/TrapContent.java"));
        Path dir = Path.of("src/main/resources/data/minecraft/tags/block/mineable");
        String tags;
        try (Stream<Path> files = Files.list(dir)) {
            tags = files.map(p -> {
                try {
                    return Files.readString(p);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).collect(Collectors.joining());
        }

        List<String> missing = new ArrayList<>();
        for (int i = src.indexOf("requiresTool()"); i >= 0;
                i = src.indexOf("requiresTool()", i + 1)) {
            int call = src.lastIndexOf(CALL, i);
            if (call < 0) continue;
            int start = call + CALL.length();
            String name = src.substring(start, src.indexOf('"', start));
            if (!tags.contains("\"trapcraft:" + name + "\"")) missing.add(name);
        }

        assertTrue(missing.isEmpty(), "requiresTool() but in no mineable tag, "
                + "so nothing can harvest them: " + missing);
    }
}
