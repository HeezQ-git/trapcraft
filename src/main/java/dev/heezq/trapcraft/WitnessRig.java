package dev.heezq.trapcraft;

import eu.pb4.polymer.virtualentity.api.attachment.ChunkAttachment;
import eu.pb4.polymer.virtualentity.api.attachment.EntityAttachment;
import eu.pb4.polymer.virtualentity.api.elements.InteractionElement;
import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;
import eu.pb4.polymer.virtualentity.api.elements.VirtualElement;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.Brightness;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.item.ItemDisplayContext;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * The watcher's body: a hood with one eye too many, a cloak with lit
 * seams, floating hands, a crown of shards, and six small eyes orbiting the
 * lot. The same rig anchored to a chunk with a hittable box and no eyes is
 * a mirror image -- see {@link #mirror}.
 */
public final class WitnessRig extends DisplayRig {
    public static final int EYES = 6;
    private static final float BOB = 0.06F;
    private static final int SWING_TICKS = 10;

    private final List<ItemDisplayElement> eyes = new ArrayList<>();
    private int swingLeft;
    private int swingRight;
    private boolean stare;
    private boolean gripping;
    private float haloAngle;

    private WitnessRig(boolean mirror) {
        super("witness");
        if (!mirror) {
            for (int i = 0; i < EYES; i++) {
                ItemDisplayElement eye = new ItemDisplayElement(modelStack(TrapCraft.id("witness_eye_orbit")));
                eye.setItemDisplayContext(ItemDisplayContext.NONE);
                eye.setBillboardMode(DisplayEntity.BillboardMode.CENTER);
                eye.setBrightness(Brightness.FULL);
                eye.setInterpolationDuration(2);
                eye.setTeleportDuration(2);
                eye.setViewRange(2.0F);
                eyes.add(eye);
                addElement(eye);
            }
        }
        nameplate(Text.literal("OBSERWATOR").formatted(Formatting.DARK_PURPLE, Formatting.BOLD));
    }

    /** The body, following a boss around. */
    public static WitnessRig attachTo(Entity boss) {
        WitnessRig rig = new WitnessRig(false);
        EntityAttachment.ofTicking(rig, boss);
        return rig;
    }

    /**
     * A copy with nothing behind it.
     *
     * Same parts, same idle, no orbiting eyes -- that is the tell, and the
     * only one. A hit on the box shatters it through {@code onHit}.
     */
    public static WitnessRig mirror(ServerWorld world, Vec3d at, float yaw, Runnable onHit) {
        WitnessRig rig = new WitnessRig(true);
        rig.yaw = yaw;
        InteractionElement box = new InteractionElement(new VirtualElement.InteractionHandler() {
            @Override
            public void attack(ServerPlayerEntity player) {
                onHit.run();
            }
        });
        box.setSize(1.6F, 3.3F);
        rig.addElement(box);
        ChunkAttachment.ofTicking(rig, world, at);
        return rig;
    }

    // --- what the fight tells the body -------------------------------------

    public void swing(boolean left) {
        if (left) {
            swingLeft = SWING_TICKS;
        } else {
            swingRight = SWING_TICKS;
        }
    }

    public void setStare(boolean on) {
        if (stare != on) {
            stare = on;
            swap("face", TrapCraft.id(on ? "witness_face_red" : "witness_face"));
            for (ItemDisplayElement eye : eyes) {
                eye.setItem(modelStack(TrapCraft.id(on ? "witness_eye_orbit_red" : "witness_eye_orbit")));
            }
        }
    }

    public void setGripping(boolean on) {
        gripping = on;
    }

    /** Where the big eye is, in the world: for orbs and the stare. */
    public Vec3d eyePos() {
        return getPos().add(0.0, 2.65 + rise, 0.0).add(forward().multiply(0.75));
    }

    /** Where the left hand is, for the grip. */
    public Vec3d handPos() {
        return getPos().add(0.0, 1.7 + rise, 0.0).add(forward().multiply(gripping ? 1.6 : 0.55))
                .add(right().multiply(1.3));
    }

    // --- the animation ------------------------------------------------------

    @Override
    protected void animate() {
        if (swingLeft > 0) {
            swingLeft--;
        }
        if (swingRight > 0) {
            swingRight--;
        }
        float t = tick;
        float bob = MathHelper.sin(t / 14.0F) * BOB;
        float sway = MathHelper.sin(t / 23.0F) * 3.0F;
        float lift = rise + flourishLift();
        float whole = whole();
        float haloSpeed = stare ? 14.0F : flourish > 0 ? 10.0F : 1.5F + phase;
        haloAngle += haloSpeed;

        boolean flash = hurting();
        glow(flash, flash ? 0xff3355 : stare ? 0xff2d55 : 0x8a4fd8);
        if (!stare) {
            swap("face", TrapCraft.id(flash ? "witness_face_red" : "witness_face"));
        }

        for (Part part : layout) {
            Vec3d offset = part.offset();
            float x = (float) offset.x;
            float y = (float) offset.y + lift + bob;
            float z = (float) offset.z;
            float pitch = 0.0F;
            float turn = part.yaw();
            float scale = part.scale() * whole;

            switch (part.name()) {
                case "hood", "face" -> {
                    y += bob * 0.3F;
                    turn += sway * 0.5F;
                    pitch = -MathHelper.sin(t / 31.0F) * 2.0F;
                    if (stare) {
                        scale *= 1.0F + 0.12F * MathHelper.sin(t * 0.9F);
                    }
                }
                case "hand_l", "hand_r" -> {
                    boolean left = part.name().equals("hand_l");
                    y += MathHelper.sin(t / 9.0F + (left ? 0.0F : (float) Math.PI)) * 0.08F;
                    int swing = left ? swingLeft : swingRight;
                    if (swing > 0) {
                        float p = MathHelper.sin((1.0F - swing / (float) SWING_TICKS) * (float) Math.PI);
                        z += 0.9F * p;
                        y -= 0.55F * p;
                        pitch = -70.0F * p;
                    }
                    if (gripping && left) {
                        z += 1.05F;
                        y += 0.15F;
                        pitch = -35.0F;
                    }
                }
                case "halo" -> {
                    y += bob * 0.5F;
                    turn += haloAngle;
                }
                default -> turn += sway;
            }

            if (death >= 0) {
                float d = death;
                switch (part.name()) {
                    case "hood", "face" -> {
                        y += d * 0.02F;
                        pitch += d * 1.5F;
                    }
                    case "hand_l", "hand_r" -> y -= d * 0.03F;
                    case "halo" -> y += d * 0.06F;
                    default -> {
                        y -= d * 0.012F;
                        scale *= Math.max(0.05F, 1.0F - d / (float) DEATH_TICKS);
                    }
                }
            }
            place(part.name(), x, y, z, turn, pitch, scale);
        }

        for (int i = 0; i < eyes.size(); i++) {
            ItemDisplayElement eye = eyes.get(i);
            float speed = (0.035F + 0.012F * phase) * (i % 2 == 0 ? 1.0F : -1.0F);
            float angle = t * speed + i * (float) (Math.PI * 2 / EYES);
            float radius = 1.35F + 0.25F * (i % 3);
            float height = 1.5F + 0.9F * MathHelper.sin(t / 17.0F + i * 1.1F);
            float eyeScale = 0.9F * whole;
            if (death >= 0) {
                radius += death * 0.12F;
                height += death * 0.03F;
                eyeScale = death >= 40 ? 0.02F : eyeScale;
            }
            eye.setOffset(new Vec3d(radius * MathHelper.cos(angle), height + lift, radius * MathHelper.sin(angle)));
            eye.setTransformation(new Matrix4f().scale(eyeScale));
            eye.startInterpolationIfDirty();
        }
        placeNameplate(4.35F + lift + bob, death >= 0 ? 0.02F : 1.4F * whole);
    }
}
