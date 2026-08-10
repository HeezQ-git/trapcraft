package dev.heezq.trapcraft.mixin;

import net.minecraft.entity.ai.goal.GoalSelector;
import net.minecraft.entity.mob.MobEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reach a mob's goal list so a goal can be taken off it.
 *
 * The one thing this is for: a customer is a wandering trader, and a wandering
 * trader carries two {@code HoldInHandsGoal}s -- invisibility at dusk, milk at
 * dawn. A customer who is supposed to walk up to you and be sold to has no
 * business turning invisible, and the previous answer was to strip the effect
 * and the bottle out of their hands every tick.
 *
 * That produced the noise. {@code HoldInHandsGoal.stop()} plays a sound, and
 * its {@code shouldContinue()} is just "am I still using the item" -- so
 * clearing the hand every tick made the goal stop and restart every tick, and
 * every one of those stops played the sound. A drinking noise once a tick,
 * forever, from something we were doing to prevent a visual.
 *
 * {@code GoalSelector.clear(Predicate)} is public and does the job properly.
 * Only the field is out of reach, hence six lines of accessor rather than a
 * mixin that rewrites any behaviour.
 */
@Mixin(MobEntity.class)
public interface MobEntityAccessor {
    @Accessor("goalSelector")
    GoalSelector getGoalSelector();
}
