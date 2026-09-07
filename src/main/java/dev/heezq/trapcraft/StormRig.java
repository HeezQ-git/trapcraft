package dev.heezq.trapcraft;

import eu.pb4.polymer.virtualentity.api.attachment.EntityAttachment;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * The storm's body: seven cloud lumps orbiting a lit core, with four
 * lightning arcs that flicker in and out across it.
 *
 * Nothing here touches the floor, so the rig's rise runs backwards: the
 * intro is a descent, not an emergence. The third phase swaps the lumps
 * for their dark models; the death lets them drift apart around a core
 * that shrinks to nothing.
 */
public final class StormRig extends DisplayRig {
    private StormRig() {
        super("storm");
        nameplate(Text.literal("SZTORM").formatted(Formatting.AQUA, Formatting.BOLD));
    }

    public static StormRig attachTo(Entity boss) {
        StormRig rig = new StormRig();
        EntityAttachment.ofTicking(rig, boss);
        return rig;
    }

    @Override
    public void setPhase(int phase) {
        super.setPhase(phase);
        for (Part part : layout) {
            if (part.name().startsWith("lump_")) {
                swap(part.name(), TrapCraft.id(phase >= 3 ? "storm_lump_dark" : "storm_lump"));
            }
        }
    }

    @Override
    protected void animate() {
        float t = tick;
        // From above: the base rises a body out of the floor, this one comes down.
        float lift = -rise * 2.0F + flourishLift();
        float whole = whole();
        boolean flash = hurting();
        glow(flash, 0xffffff);
        float d = death >= 0 ? death : 0.0F;

        for (Part part : layout) {
            Vec3d offset = part.offset();
            float x = (float) offset.x;
            float y = (float) offset.y + lift;
            float z = (float) offset.z;
            float turn = part.yaw();
            float pitch = 0.0F;
            float scale = part.scale() * whole;

            String name = part.name();
            if (name.startsWith("lump_")) {
                int i = name.charAt(5) - '0';
                float speed = (0.012F + 0.003F * i) * (i % 2 == 0 ? 1.0F : -1.0F) * (1.0F + 0.3F * phase);
                Vec3d spun = offset.rotateY(t * speed);
                float spread = 1.0F + d * 0.03F;
                x = (float) spun.x * spread;
                z = (float) spun.z * spread;
                y = (float) spun.y + lift + MathHelper.sin(t / 11.0F + i * 1.3F) * 0.08F + d * 0.02F;
                turn += t * 0.8F * (i % 2 == 0 ? 1.0F : -1.0F);
                scale *= 1.0F + 0.05F * MathHelper.sin(t / 7.0F + i);
                if (i == 6) {
                    scale *= 1.0F + 0.06F * MathHelper.sin(t / 5.0F);
                }
            } else if (name.equals("core")) {
                turn += t * 4.0F;
                pitch = t * 2.5F;
                scale *= (1.0F + 0.12F * MathHelper.sin(t / 5.0F)) * Math.max(0.02F, 1.0F - d / (float) DEATH_TICKS);
            } else if (name.startsWith("arc_")) {
                int i = name.charAt(4) - '0';
                float flicker = MathHelper.sin(t * 0.7F + i * 1.9F) + MathHelper.sin(t * 1.7F + i * 0.6F) * 0.4F;
                boolean on = flicker > (flourish > 0 ? -0.5F : 0.45F) && death < 0;
                scale = on ? scale * (0.85F + 0.25F * Math.abs(MathHelper.sin(t * 3.0F + i))) : 0.02F;
                turn += t * 2.0F * (i % 2 == 0 ? 1.0F : -1.0F);
            }
            place(name, x, y, z, turn, pitch, scale);
        }
        placeNameplate(4.0F + lift, death >= 0 ? 0.02F : 1.3F * whole);
    }
}
