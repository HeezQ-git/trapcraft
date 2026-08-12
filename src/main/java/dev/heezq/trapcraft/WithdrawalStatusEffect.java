package dev.heezq.trapcraft;

import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectCategory;

/**
 * Being short of something, as an icon.
 *
 * Purely a marker, like {@link ToleranceStatusEffect}. Everything withdrawal
 * actually DOES is applied by {@link TrapAddiction} as ordinary vanilla effects,
 * because those are the ones the player already knows how to read -- Slowness is
 * Slowness whether it came from a cobweb or a habit.
 *
 * What this adds is the one thing those cannot say: that the pile of debuffs has
 * a cause and a name. The amplifier is the band, so the HUD shows I, II or III
 * for itching, craving and properly sick, and the icon disappears the moment the
 * meter drops out of the bands rather than ticking down like a timer.
 */
public class WithdrawalStatusEffect extends StatusEffect {
    public WithdrawalStatusEffect() {
        super(StatusEffectCategory.HARMFUL, 0x6b4a2a);
    }

    @Override
    public boolean canApplyUpdateEffect(int duration, int amplifier) {
        return false; // marker only
    }
}
