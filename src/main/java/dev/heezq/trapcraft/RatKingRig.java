package dev.heezq.trapcraft;

import eu.pb4.polymer.virtualentity.api.attachment.EntityAttachment;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * The rat king's body: a matted hulk on four legs, a head with a crown
 * that is the only thing in the sewer that shines, ears that twitch, and a
 * tail in three joints that never stops moving.
 *
 * The legs walk from the holder's movement, the head bites, the tail wags
 * harder when it is about to sweep, and the death is a flop onto one side
 * with the crown rolling off the head.
 */
public final class RatKingRig extends DisplayRig {
    private static final int BITE_TICKS = 8;

    private Vec3d lastPos;
    private float stride;
    private int bite;
    private int wag;
    private int twitch;

    private RatKingRig() {
        super("ratking");
        nameplate(Text.literal("KRÓL SZCZURÓW").formatted(Formatting.DARK_GREEN, Formatting.BOLD));
    }

    public static RatKingRig attachTo(Entity boss) {
        RatKingRig rig = new RatKingRig();
        EntityAttachment.ofTicking(rig, boss);
        return rig;
    }

    /** The head snaps forward. */
    public void bite() {
        bite = BITE_TICKS;
    }

    /** The tail goes fast for this many ticks: the sweep's tell. */
    public void wag(int ticks) {
        wag = ticks;
    }

    /** Where the mouth is: for the spit. */
    public Vec3d mouthPos() {
        return getPos().add(0.0, 1.45 + rise, 0.0).add(forward().multiply(2.3));
    }

    @Override
    protected void animate() {
        if (bite > 0) {
            bite--;
        }
        if (wag > 0) {
            wag--;
        }
        if (twitch > 0) {
            twitch--;
        }
        Vec3d now = getPos();
        double moved = lastPos == null ? 0.0 : now.subtract(lastPos).horizontalLength();
        lastPos = now;
        stride += (float) Math.min(0.6, moved * 2.6);
        float walking = Math.min(1.0F, (float) moved * 6.0F);

        float t = tick;
        if (tick % 70 == 0 && (tick / 70) % 3 != 1) {
            twitch = 6;
        }
        float breath = MathHelper.sin(t / 9.0F) * 0.035F;
        float lift = rise + flourishLift();
        float whole = whole();
        boolean flash = hurting();
        glow(flash, 0xff3355);

        float d = death >= 0 ? death : 0.0F;
        float flop = Math.min(85.0F, d * 3.0F);

        for (Part part : layout) {
            Vec3d offset = part.offset();
            float x = (float) offset.x;
            float y = (float) offset.y + lift + breath;
            float z = (float) offset.z;
            float turn = part.yaw();
            float pitch = 0.0F;
            float roll = 0.0F;
            float scale = part.scale() * whole;

            switch (part.name()) {
                case "leg_fl", "leg_br" -> {
                    pitch = MathHelper.sin(stride) * 32.0F * walking;
                    y -= breath;
                }
                case "leg_fr", "leg_bl" -> {
                    pitch = MathHelper.sin(stride + (float) Math.PI) * 32.0F * walking;
                    y -= breath;
                }
                case "head" -> {
                    pitch = MathHelper.sin(t / 13.0F) * 3.0F;
                    if (bite > 0) {
                        float p = MathHelper.sin((1.0F - bite / (float) BITE_TICKS) * (float) Math.PI);
                        z += 0.6F * p;
                        pitch += 22.0F * p;
                    }
                }
                case "ear_l", "ear_r" -> {
                    boolean left = part.name().equals("ear_l");
                    roll = left ? -18.0F : 18.0F;
                    if (twitch > 0) {
                        roll += (left ? -1.0F : 1.0F) * MathHelper.sin(twitch * 1.0F) * 20.0F;
                    }
                    if (bite > 0) {
                        z += 0.3F;
                    }
                }
                case "crown" -> {
                    y += MathHelper.sin(t / 13.0F) * 0.02F;
                    if (bite > 0) {
                        z += 0.4F;
                    }
                }
                case "tail_0", "tail_1", "tail_2" -> {
                    int i = part.name().charAt(5) - '0';
                    float speed = wag > 0 ? 0.9F : 0.22F;
                    float swing = MathHelper.sin(t * speed + i * 0.9F) * (10.0F + 7.0F * i);
                    turn += swing;
                    x += MathHelper.sin(t * speed + i * 0.9F) * 0.12F * (i + 1);
                }
                default -> {
                }
            }

            if (death >= 0) {
                switch (part.name()) {
                    case "crown" -> {
                        // Off the head, up, and down onto the floor, rolling.
                        float f = Math.min(1.0F, d / 40.0F);
                        y += 1.2F * MathHelper.sin(f * (float) Math.PI) - f * 1.9F;
                        x += f * 1.6F;
                        turn += d * 9.0F;
                        roll = f * 70.0F;
                    }
                    case "head", "ear_l", "ear_r" -> {
                        roll += flop;
                        x += d * 0.01F;
                        y -= d * 0.012F;
                    }
                    default -> {
                        roll += flop;
                        x += d * 0.012F;
                        y -= d * 0.01F;
                    }
                }
                if (d > 50) {
                    scale *= Math.max(0.05F, 1.0F - (d - 50) / 30.0F);
                }
            }
            place(part.name(), x, y, z, turn, pitch, roll, scale);
        }
        placeNameplate(3.3F + lift + breath, death >= 0 ? 0.02F : 1.3F * whole);
    }
}
