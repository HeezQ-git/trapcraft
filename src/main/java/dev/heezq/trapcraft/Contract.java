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
                       int payout, int rep, int form) {

    /**
     * What shape they want it in.
     *
     * BUDS is 0 on purpose. The field is optional in the codec and defaults to
     * it, so a contract already sitting on somebody's phone from before this
     * existed decodes cleanly and still means exactly what it meant when they
     * accepted it. A required field would have failed to parse and quietly
     * taken the job -- and the phone -- with it.
     *
     * POWDER is appended for the same reason, and appending is the whole
     * discipline here: these are stored as their ordinal on an item in
     * somebody's pocket, so inserting a value anywhere but the end would
     * rewrite every live job on the server into a different one.
     */
    public enum Form {
        BUDS("Cured buds only"),
        JOINTS("Rolled joints only"),
        EITHER("Buds or joints"),
        POWDER("Refined powder only");

        public final String label;

        Form(String label) {
            this.label = label;
        }
    }

    public static final Codec<Contract> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("strain").forGetter(Contract::strain),
            Codec.INT.fieldOf("min_grade").forGetter(Contract::minGrade),
            Codec.INT.fieldOf("quantity").forGetter(Contract::quantity),
            Codec.INT.fieldOf("dest_x").forGetter(Contract::destX),
            Codec.INT.fieldOf("dest_z").forGetter(Contract::destZ),
            Codec.LONG.fieldOf("deadline").forGetter(Contract::deadlineTick),
            Codec.INT.fieldOf("payout").forGetter(Contract::payout),
            Codec.INT.fieldOf("rep").forGetter(Contract::rep),
            Codec.INT.optionalFieldOf("form", 0).forGetter(Contract::form)
    ).apply(instance, Contract::new));

    public Form formValue() {
        return Form.values()[Math.floorMod(form, Form.values().length)];
    }

    // Named rather than negated. "Anything that isn't JOINTS" was true for
    // every form there was when it was written, and the day a fourth one
    // arrived it quietly meant a powder job would also accept buds.
    public boolean takesBuds() {
        return formValue() == Form.BUDS || formValue() == Form.EITHER;
    }

    public boolean takesJoints() {
        return formValue() == Form.JOINTS || formValue() == Form.EITHER;
    }

    public boolean takesPowder() {
        return formValue() == Form.POWDER;
    }

    public Strain strainValue() {
        return Strain.values()[Math.floorMod(strain, Strain.values().length)];
    }

    public Quality gradeValue() {
        return Quality.byIndex(minGrade);
    }

    /**
     * The same minGrade, read as purity.
     *
     * Quality and Purity are both four rungs, which is the whole reason a
     * powder job can ride on the existing field: "at least LOUD" and "at
     * least CLEAN" are the same number. A second field would have meant a
     * codec change, and a codec change means every job on every phone stops
     * parsing -- which costs people work they had already done.
     */
    public Purity purityValue() {
        return Purity.values()[Math.floorMod(minGrade, Purity.values().length)];
    }

    /**
     * What to call this job, and what colour to say it in.
     *
     * A powder job has no strain -- there is one coca line, not six -- so the
     * strain field on it is meaningless and nothing may print it. Everything
     * that used to reach for strainValue() asks here instead, which is what
     * stops a cocaine job being advertised as Purple Haze.
     */
    public String productName() {
        return takesPowder() ? "Powder" : strainValue().display();
    }

    public int productColour() {
        return takesPowder() ? POWDER_WHITE : strainValue().colour();
    }

    /** The floor grade, on whichever of the two scales this job is measured in. */
    public String gradeName() {
        return takesPowder() ? purityValue().display() : gradeValue().display();
    }

    public net.minecraft.util.Formatting gradeColour() {
        return takesPowder() ? purityValue().colour() : gradeValue().colour();
    }

    /** Powder is the one product with no strain colour of its own. */
    private static final int POWDER_WHITE = 0xF2F6FF;

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
