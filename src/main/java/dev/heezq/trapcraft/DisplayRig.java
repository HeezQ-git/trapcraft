package dev.heezq.trapcraft;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;
import eu.pb4.polymer.virtualentity.api.elements.TextDisplayElement;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.decoration.Brightness;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.item.ItemDisplayContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
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
 * A body made of item displays, animated a tick at a time.
 *
 * Nothing about a rig is an item, a block, or a client mod. Every part is a
 * paper stack wearing an {@code item_model} component -- exactly what
 * Polymer does for the mod's real items -- so the served pack already knows
 * how to draw it. Bright parts glow in the dark; one part carries the shadow.
 *
 * Where each part sits comes out of {@code data/trapcraft/arena/<id>_rig.json},
 * generated next to the models, so the desk previewer and the server agree
 * to the centimetre. Facing is the elements' own yaw, which a display entity
 * interpolates; the transformation is placement plus whatever the animation
 * wants this tick, interpolated over two ticks so nothing snaps.
 *
 * Subclasses own {@link #animate()}: the common state here -- rise, collapse,
 * hurt flash, flourish, death -- is what every boss body needs, and
 * {@link #place} is how a part is put where the animation wants it.
 */
public abstract class DisplayRig extends ElementHolder {

    /** One line of a rig file. */
    protected record Part(String name, Identifier model, Vec3d offset, float scale, boolean bright,
                          float shadow, float yaw, boolean billboard) {
    }

    private static final Map<String, List<Part>> LAYOUTS = new HashMap<>();

    public static final int DEATH_TICKS = 80;
    protected static final int HURT_TICKS = 6;

    protected final Map<String, ItemDisplayElement> parts = new HashMap<>();
    protected final List<Part> layout;
    protected TextDisplayElement nameplate;

    protected int tick;
    protected float yaw;
    /** Vertical offset of the whole body: the intro rises from below. */
    protected float rise;
    /** 0 = whole, 1 = gone. */
    protected float collapse;
    protected int hurt;
    protected int flourish;
    protected int phase = 1;
    protected int death = -1;

    protected DisplayRig(String rigId) {
        layout = layout(rigId);
        for (Part part : layout) {
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
            if (part.billboard()) {
                element.setBillboardMode(DisplayEntity.BillboardMode.CENTER);
            }
            parts.put(part.name(), element);
            addElement(element);
        }
    }

    /** A floating name over the body. */
    protected void nameplate(Text text) {
        nameplate = new TextDisplayElement(text);
        nameplate.setBillboardMode(DisplayEntity.BillboardMode.CENTER);
        nameplate.setBackground(0);
        nameplate.setShadow(true);
        nameplate.setViewRange(2.0F);
        nameplate.setBrightness(Brightness.FULL);
        nameplate.setInterpolationDuration(2);
        nameplate.setTeleportDuration(2);
        addElement(nameplate);
    }

    // --- what the fight tells the body -------------------------------------

    public void setYaw(float yaw) {
        this.yaw = yaw;
    }

    public float yaw() {
        return yaw;
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

    public boolean hurting() {
        return hurt > 0;
    }

    public void setPhase(int phase) {
        this.phase = phase;
    }

    /** The phase-change flourish: sixty ticks of swell. */
    public void flourish() {
        flourish = 60;
    }

    public void startDeath() {
        death = 0;
    }

    public boolean dying() {
        return death >= 0;
    }

    /** Which way the body faces. */
    public Vec3d forward() {
        return Vec3d.fromPolar(0.0F, yaw);
    }

    /** To the body's right. */
    public Vec3d right() {
        return forward().rotateY((float) (-Math.PI / 2));
    }

    // --- the animation ------------------------------------------------------

    @Override
    protected void onTick() {
        tick++;
        if (hurt > 0) {
            hurt--;
        }
        if (flourish > 0) {
            flourish--;
        }
        if (death >= 0) {
            death++;
        }
        animate();
    }

    protected abstract void animate();

    /** The whole-body scale this tick: collapse, flourish swell. */
    protected float whole() {
        float swell = 1.0F;
        if (flourish > 0) {
            swell += 0.15F * MathHelper.sin((60 - flourish) * 0.35F);
        }
        return (1.0F - collapse) * swell;
    }

    /** The flourish's lift this tick. */
    protected float flourishLift() {
        return flourish > 0 ? 0.4F * MathHelper.sin((60 - flourish) / 60.0F * (float) Math.PI) : 0.0F;
    }

    /**
     * Put a part where the animation wants it, relative to the body's feet,
     * in its own frame: the element's yaw is the body's, so x is to the
     * body's right and z forward.
     */
    protected void place(String name, float x, float y, float z, float turn, float pitch, float scale) {
        ItemDisplayElement element = parts.get(name);
        if (element == null) {
            return;
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

    /** The same, with a roll about the part's own z. */
    protected void place(String name, float x, float y, float z, float turn, float pitch, float roll, float scale) {
        ItemDisplayElement element = parts.get(name);
        if (element == null) {
            return;
        }
        Matrix4f matrix = new Matrix4f()
                .translate(x, y, z)
                .rotateY((float) Math.toRadians(turn))
                .rotateX((float) Math.toRadians(pitch))
                .rotateZ((float) Math.toRadians(roll))
                .scale(Math.max(0.02F, scale));
        element.setTransformation(matrix);
        element.setYaw(yaw);
        element.startInterpolationIfDirty();
    }

    /** A billboarded part: position is world-relative to the feet, no facing. */
    protected void float_(String name, Vec3d offset, float scale) {
        ItemDisplayElement element = parts.get(name);
        if (element == null) {
            return;
        }
        element.setOffset(offset);
        element.setTransformation(new Matrix4f().scale(Math.max(0.02F, scale)));
        element.startInterpolationIfDirty();
    }

    protected Part part(String name) {
        for (Part part : layout) {
            if (part.name().equals(name)) {
                return part;
            }
        }
        return null;
    }

    protected void swap(String name, Identifier model) {
        ItemDisplayElement element = parts.get(name);
        if (element != null) {
            element.setItem(modelStack(model));
        }
    }

    /** Outline every part, or none. */
    protected void glow(boolean on, int colour) {
        for (ItemDisplayElement element : parts.values()) {
            element.setGlowing(on);
            element.setGlowColorOverride(colour);
        }
    }

    protected void placeNameplate(float height, float scale) {
        if (nameplate == null) {
            return;
        }
        nameplate.setOffset(new Vec3d(0.0, height, 0.0));
        nameplate.setTransformation(new Matrix4f().scale(Math.max(0.02F, scale)));
        nameplate.startInterpolationIfDirty();
    }

    // --- resources ----------------------------------------------------------

    /** A paper stack the client will draw as one of our models. */
    public static ItemStack modelStack(Identifier model) {
        ItemStack stack = new ItemStack(Items.PAPER);
        stack.set(DataComponentTypes.ITEM_MODEL, model);
        return stack;
    }

    protected static synchronized List<Part> layout(String rigId) {
        List<Part> cached = LAYOUTS.get(rigId);
        if (cached != null) {
            return cached;
        }
        List<Part> read = new ArrayList<>();
        String path = "/data/trapcraft/arena/" + rigId + "_rig.json";
        try (var stream = TrapCraft.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException(path + " missing from the jar");
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
                        part.has("yaw") ? part.get("yaw").getAsFloat() : 0.0F,
                        part.has("billboard") && part.get("billboard").getAsBoolean()));
            }
        } catch (Exception e) {
            // A rig that fails to load is a boss you cannot see, which is
            // worse than no boss: say so loudly and give the fight nothing.
            TrapCraft.LOGGER.error("rig {} unreadable -- the boss will be invisible", rigId, e);
        }
        LAYOUTS.put(rigId, read);
        return read;
    }
}
