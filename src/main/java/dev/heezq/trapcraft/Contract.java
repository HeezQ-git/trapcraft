package dev.heezq.trapcraft;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.math.BlockPos;

/**
 * One job: this much of this, at least this good, over there, by then.
 *
 * Rides on the burner phone as a data component, so an accepted job survives
 * relogs and restarts without any persistence code of its own -- and is lost
 * with the phone, which is the intended stake.
 */
public record Contract(int strain, int minGrade, int quantity,
                       int destX, int destZ, long deadlineTick,
                       int payout, int rep) {

    public static final Codec<Contract> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("strain").forGetter(Contract::strain),
            Codec.INT.fieldOf("min_grade").forGetter(Contract::minGrade),
            Codec.INT.fieldOf("quantity").forGetter(Contract::quantity),
            Codec.INT.fieldOf("dest_x").forGetter(Contract::destX),
            Codec.INT.fieldOf("dest_z").forGetter(Contract::destZ),
            Codec.LONG.fieldOf("deadline").forGetter(Contract::deadlineTick),
            Codec.INT.fieldOf("payout").forGetter(Contract::payout),
            Codec.INT.fieldOf("rep").forGetter(Contract::rep)
    ).apply(instance, Contract::new));

    public Strain strainValue() {
        return Strain.values()[Math.floorMod(strain, Strain.values().length)];
    }

    public Quality gradeValue() {
        return Quality.byIndex(minGrade);
    }

    /** Y is deliberately absent: villages are found on the surface anyway. */
    public BlockPos destination() {
        return new BlockPos(destX, 0, destZ);
    }

    public boolean expired(long now) {
        return now >= deadlineTick;
    }

    public int secondsLeft(long now) {
        return (int) Math.max(0, (deadlineTick - now) / 20);
    }
}
