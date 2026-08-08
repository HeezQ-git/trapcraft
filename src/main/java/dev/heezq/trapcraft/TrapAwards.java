package dev.heezq.trapcraft;

import net.minecraft.advancement.AdvancementEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

/**
 * The ones the game can't notice on its own.
 *
 * Half the advancements in this mod are "you now own one of these", and vanilla
 * triggers handle those in the datapack with no code at all. The other half are
 * moments -- a jackpot, walking away from the sixth rung, watching a raid carry
 * your stash out of the door -- and nothing in the vanilla trigger set can see
 * those happen.
 *
 * The standard answer is an advancement whose only criterion is
 * `minecraft:impossible`, which by definition never fires, and then granting
 * that criterion from code at the moment it should. The toast, the chat line
 * and the advancement tab all work exactly as normal.
 */
public final class TrapAwards {
    private TrapAwards() {
    }

    /** The criterion name every granted advancement uses. Must match the JSON. */
    private static final String CRITERION = "granted";

    public static void grant(ServerPlayerEntity player, String name) {
        if (player == null || player.getServer() == null) {
            return;
        }
        AdvancementEntry award = player.getServer().getAdvancementLoader()
                .get(Identifier.of(TrapCraft.MOD_ID, name));
        if (award == null) {
            // A renamed or dropped advancement should never take a feature down
            // with it -- the moment still happened, it just goes unremarked.
            return;
        }
        player.getAdvancementTracker().grantCriterion(award, CRITERION);
    }
}
