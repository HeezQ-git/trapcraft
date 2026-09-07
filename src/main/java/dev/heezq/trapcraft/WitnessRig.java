package dev.heezq.trapcraft;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.attachment.ChunkAttachment;
import eu.pb4.polymer.virtualentity.api.attachment.EntityAttachment;
import eu.pb4.polymer.virtualentity.api.elements.InteractionElement;
import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;
import eu.pb4.polymer.virtualentity.api.elements.TextDisplayElement;
import eu.pb4.polymer.virtualentity.api.elements.VirtualElement;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.Brightness;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.item.ItemDisplayContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The witness's body: a rig of item displays that a vanilla client draws
 * off the served resource pack, animated a tick at a time from here.
 *
 * Nothing about the body is an item, a block, or a client mod. Every part is
 * a paper stack wearing an {@code item_model} component, which is exactly
 * what Polymer does for the mod's real items, so the pack already knows how
 * to draw them. Eyes and seams sit on their own elements at full brightness
 * so they glow in a pit lit by soul fire; the cloak carries the shadow.
 *
 * Where each part sits comes out of {@code data/trapcraft/arena/rig.json},
 * generated next to the models, so the desk previewer and this class agree
 * to the centimetre. Facing is the elements' own yaw, which a display entity
 * interpolates; the transformation is placement plus whatever the animation
 * wants this tick, interpolated over two ticks so nothing snaps.
 *
 * The same rig, anchored to a chunk with a hittable box instead of a mob
 * behind it, is a mirror image -- see {@link #mirror}.
 */
public final class WitnessRig extends ElementHolder {

    /** One line of rig.json. */
    private record Part(String name, Identifier model, Vec3d offset, float scale, boolean bright,
                        float shadow, float yaw) {
    }

    private static List<Part> layout;

    public static final int EYES = 6;
    private static final float BOB = 0.06F;
    private static final int SWING_TICKS = 10;
    private static final int HURT_TICKS = 6;
    public static final int DEATH_TICKS = 80;

    private final Map<String, ItemDisplayElement> parts = new HashMap<>();
    private final List<ItemDisplayElement> eyes = new ArrayList<>();
    private final TextDisplayElement nameplate;
    private final boolean mirror;

    private int tick;
    private float yaw;
    /** Vertical offset of the whole body: the intro rises from below, Fala lifts it. */
    private float rise;
    /** 0 = whole, 1 = gone. Blink squeezes it to nothing and back. */
    private float collapse;
    private int hurt;
    private int swingLeft;
    private int swingRight;
    private boolean stare;
    private boolean gripping;
    private int flourish;
    private int phase = 1;
    private int death = -1;
    private float haloAngle;

    private WitnessRig(boolean mirror) {
        this.mirror = mirror;
        for (Part part : layout()) {
            ItemDisplayElement element = new ItemDisplayElement(modelStack(part.model()));
            element.setItemDisplayContext(ItemDisplayContext.NONE);
            element.setInterpolationDuration(2);
            element.setTeleportDuration(2);
            element.setViewRange(2.0F);
            if (part.bright()) {
                element.setBrightness(Brightness.FULL);
            }
            if (part.shadow() > 0.0F) {
                element.setShadowRadius(part.shadow());
                element.setShadowStrength(0.9F);
            }
            parts.put(part.name(), element);
            addElement(element);
        }
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
        nameplate = new TextDisplayElement(Text.literal("OBSERWATOR")
                .formatted(Formatting.DARK_PURPLE, Formatting.BOLD));
        nameplate.setBillboardMode(DisplayEntity.BillboardMode.CENTER);
        nameplate.setBackground(0);
        nameplate.setShadow(true);
        nameplate.setViewRange(2.0F);
        nameplate.setBrightness(Brightness.FULL);
        nameplate.setInterpolationDuration(2);
        nameplate.setTeleportDuration(2);
        addElement(nameplate);
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

    public void setYaw(float yaw) {
        this.yaw = yaw;
    }

    public void setRise(float rise) {
        this.rise = rise;
    }

    public float rise() {
        return rise;
    }

    public void setCollapse(float collapse) {
        this.collapse = MathHelper.clamp(collapse, 0.0F, 0.98F);
    }

    public void hurt() {
        hurt = HURT_TICKS;
    }

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
            parts.get("face").setItem(modelStack(TrapCraft.id(on ? "witness_face_red" : "witness_face")));
            for (ItemDisplayElement eye : eyes) {
                eye.setItem(modelStack(TrapCraft.id(on ? "witness_eye_orbit_red" : "witness_eye_orbit")));
            }
        }
    }

    public void setGripping(boolean on) {
        gripping = on;
    }

    public void setPhase(int phase) {
        this.phase = phase;
    }

    /** The phase-change flourish: a spin and a swell, for sixty ticks. */
    public void flourish() {
        flourish = 60;
    }

    public void startDeath() {
        death = 0;
    }

    public boolean dying() {
        return death >= 0;
    }

    /** Where the big eye is, in the world: for orbs and the stare. */
    public Vec3d eyePos() {
        Vec3d base = getPos();
        Vec3d forward = Vec3d.fromPolar(0.0F, yaw);
        return base.add(0.0, 2.65 + rise, 0.0).add(forward.multiply(0.75));
    }

    /** Where the left hand is, for the grip. */
    public Vec3d handPos() {
        Vec3d base = getPos();
        Vec3d forward = Vec3d.fromPolar(0.0F, yaw);
        Vec3d right = forward.rotateY((float) (-Math.PI / 2));
        return base.add(0.0, 1.7 + rise, 0.0).add(forward.multiply(gripping ? 1.6 : 0.55))
                .add(right.multiply(1.3));
    }

    // --- the animation ------------------------------------------------------

    @Override
    protected void onTick() {
        tick++;
        if (hurt > 0) {
            hurt--;
        }
        if (swingLeft > 0) {
            swingLeft--;
        }
        if (swingRight > 0) {
            swingRight--;
        }
        if (flourish > 0) {
            flourish--;
        }
        if (death >= 0) {
            death++;
        }
        animate();
    }

    private void animate() {
        float t = tick;
        float bob = MathHelper.sin(t / 14.0F) * BOB;
        float sway = MathHelper.sin(t / 23.0F) * 3.0F;
        float swell = 1.0F;
        float lift = rise;
        if (flourish > 0) {
            swell += 0.15F * MathHelper.sin((60 - flourish) * 0.35F);
            lift += 0.4F * MathHelper.sin((60 - flourish) / 60.0F * (float) Math.PI);
        }
        float whole = (1.0F - collapse) * swell;
        float haloSpeed = stare ? 14.0F : flourish > 0 ? 10.0F : 1.5F + phase;
        haloAngle += haloSpeed;

        boolean flash = hurt > 0;
        int glowColour = stare ? 0xff2d55 : 0x8a4fd8;
        for (ItemDisplayElement element : parts.values()) {
            element.setGlowing(flash);
            element.setGlowColorOverride(flash ? 0xff3355 : glowColour);
        }
        if (!stare) {
            parts.get("face").setItem(modelStack(TrapCraft.id(flash ? "witness_face_red" : "witness_face")));
        }

        for (Part part : layout()) {
            ItemDisplayElement element = parts.get(part.name());
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
                    float hover = MathHelper.sin(t / 9.0F + (left ? 0.0F : (float) Math.PI)) * 0.08F;
                    y += hover;
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

            Matrix4f matrix = new Matrix4f()
                    .translate(x, y, z)
                    .rotateY((float) Math.toRadians(turn))
                    .rotateX((float) Math.toRadians(pitch))
                    .scale(Math.max(0.02F, scale));
            element.setTransformation(matrix);
            element.setYaw(yaw);
            element.startInterpolationIfDirty();
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
            eye.setOffset(new Vec3d(radius * MathHelper.cos(angle), height + lift,
                    radius * MathHelper.sin(angle)));
            eye.setTransformation(new Matrix4f().scale(eyeScale));
            eye.startInterpolationIfDirty();
        }

        nameplate.setOffset(new Vec3d(0.0, 4.35 + lift + bob, 0.0));
        nameplate.setTransformation(new Matrix4f().scale(death >= 0 ? 0.02F : 1.4F * whole));
        nameplate.startInterpolationIfDirty();
    }

    // --- resources ----------------------------------------------------------

    /** A paper stack the client will draw as one of our models. */
    public static ItemStack modelStack(Identifier model) {
        ItemStack stack = new ItemStack(Items.PAPER);
        stack.set(DataComponentTypes.ITEM_MODEL, model);
        return stack;
    }

    private static synchronized List<Part> layout() {
        if (layout != null) {
            return layout;
        }
        List<Part> read = new ArrayList<>();
        try (var stream = TrapCraft.class.getResourceAsStream("/data/trapcraft/arena/rig.json")) {
            if (stream == null) {
                throw new IllegalStateException("rig.json missing from the jar");
            }
            JsonObject root = new Gson().fromJson(
                    new InputStreamReader(stream, StandardCharsets.UTF_8), JsonObject.class);
            for (var element : root.getAsJsonArray("parts")) {
                JsonObject part = element.getAsJsonObject();
                JsonArray offset = part.getAsJsonArray("offset");
                read.add(new Part(
                        part.get("name").getAsString(),
                        TrapCraft.id(part.get("model").getAsString()),
                        new Vec3d(offset.get(0).getAsDouble(), offset.get(1).getAsDouble(),
                                offset.get(2).getAsDouble()),
                        part.get("scale").getAsFloat(),
                        part.has("bright") && part.get("bright").getAsBoolean(),
                        part.has("shadow") ? part.get("shadow").getAsFloat() : 0.0F,
                        part.has("yaw") ? part.get("yaw").getAsFloat() : 0.0F));
            }
        } catch (Exception e) {
            // A rig that fails to load is a boss you cannot see, which is
            // worse than no boss: say so loudly and give the fight nothing.
            TrapCraft.LOGGER.error("witness rig unreadable -- the boss will be invisible", e);
        }
        layout = read;
        return layout;
    }
}
