package dev.heezq.trapcraft;

import eu.pb4.polymer.virtualentity.api.attachment.EntityAttachment;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * The bandit's body: a slot machine cabinet on two chrome legs, three
 * reels in its face, a marquee for a head and a lever for an arm.
 *
 * It walks -- the legs swing when the holder moves -- and everything the
 * fight does to it is a mechanical gesture: the lever pulls, the reels
 * spin, the cabinet door opens and rocks when it pays out, and it dies by
 * falling on its face.
 */
public final class BanditRig extends DisplayRig {
    private static final int PULL_TICKS = 14;

    private int pull;
    private int spin;
    private float reelAngle;
    private boolean open;
    private Vec3d lastPos;
    private float stride;

    private BanditRig() {
        super("bandit");
        nameplate(Text.literal("BANDYTA").formatted(Formatting.GOLD, Formatting.BOLD));
    }

    public static BanditRig attachTo(Entity boss) {
        BanditRig rig = new BanditRig();
        EntityAttachment.ofTicking(rig, boss);
        return rig;
    }

    /** The lever comes down. */
    public void pull() {
        pull = PULL_TICKS;
    }

    /** The reels turn for this many ticks. */
    public void spin(int ticks) {
        spin = ticks;
    }

    /** The cabinet opens and pays; false shuts it. */
    public void setOpen(boolean open) {
        this.open = open;
    }

    /** Where coins leave from: the tray. */
    public Vec3d trayPos() {
        return getPos().add(0.0, 0.95 + rise, 0.0).add(forward().multiply(0.9));
    }

    @Override
    protected void animate() {
        if (pull > 0) {
            pull--;
        }
        if (spin > 0) {
            spin--;
            reelAngle += 40.0F;
        }
        Vec3d now = getPos();
        double moved = lastPos == null ? 0.0 : now.subtract(lastPos).horizontalLength();
        lastPos = now;
        stride += (float) Math.min(0.5, moved * 2.4);

        float t = tick;
        float bob = MathHelper.sin(t / 11.0F) * 0.03F;
        float lift = rise + flourishLift();
        float whole = whole();
        boolean flash = hurting();
        glow(flash, flash ? 0xff3355 : 0xffc24a);

        float fall = death >= 0 ? Math.min(80.0F, death * 1.1F) : 0.0F;
        float sink = death >= 0 ? death * 0.01F : 0.0F;

        for (Part part : layout) {
            Vec3d offset = part.offset();
            float x = (float) offset.x;
            float y = (float) offset.y + lift + bob - sink;
            float z = (float) offset.z;
            float turn = part.yaw();
            float pitch = fall;
            float roll = 0.0F;
            float scale = part.scale() * whole;

            switch (part.name()) {
                case "leg_l", "leg_r" -> {
                    boolean left = part.name().equals("leg_l");
                    pitch += MathHelper.sin(stride + (left ? 0.0F : (float) Math.PI)) * 28.0F * Math.min(1.0F, (float) moved * 6.0F);
                    y -= bob;
                }
                case "arm" -> {
                    roll = -22.0F;
                    if (pull > 0) {
                        float p = MathHelper.sin((1.0F - pull / (float) PULL_TICKS) * (float) Math.PI);
                        pitch = -115.0F * p;
                    }
                }
                case "reel_0", "reel_1", "reel_2" -> {
                    int i = part.name().charAt(5) - '0';
                    pitch += spin > 0 || death >= 0 ? reelAngle + i * 37.0F : 0.0F;
                }
                case "marquee" -> {
                    y += bob * 0.8F;
                    if (flourish > 0) {
                        turn += MathHelper.sin(t * 0.8F) * 6.0F;
                    }
                }
                case "tray" -> {
                    if (open) {
                        z += 0.25F;
                        pitch += -25.0F;
                    }
                }
                default -> {
                }
            }
            if (death >= 0) {
                // It tips forward onto its face and the marquee comes off.
                z += death * 0.012F;
                if (part.name().equals("marquee")) {
                    y += death * 0.02F;
                    turn += death * 6.0F;
                }
            }
            place(part.name(), x, y, z, turn, pitch, roll, scale);
        }
        placeNameplate(3.95F + lift + bob, death >= 0 ? 0.02F : 1.3F * whole);
    }
}
