package dev.heezq.trapcraft;

import eu.pb4.polymer.core.api.item.PolymerItem;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import xyz.nucleoid.packettweaker.PacketContext;

import java.util.List;

/**
 * Serce Burzy -- what was in the middle of the storm.
 *
 * Right-click and the storm is under you for a moment: a gust that throws
 * you upward and everything near you outward, then eight seconds of slow
 * falling to come down wherever you like. A way up a wall, off a roof, or
 * out of a crowd; not a weapon, since the push does no damage.
 */
public class StormHeartItem extends Item implements PolymerItem {
    private static final int GLIDE_TICKS = 20 * 8;
    private final Identifier model;

    public StormHeartItem(Settings settings, Identifier model) {
        super(settings);
        this.model = model;
    }

    @Override
    public Item getPolymerItem(ItemStack stack, PacketContext context) {
        return Items.PAPER;
    }

    @Override
    public Identifier getPolymerItemModel(ItemStack stack, PacketContext context) {
        return model;
    }

    @Override
    public void inventoryTick(ItemStack stack, ServerWorld world, Entity holder, EquipmentSlot slot) {
        if (stack.get(DataComponentTypes.LORE) == null) {
            stack.set(DataComponentTypes.LORE, new LoreComponent(lore()));
        }
    }

    public static List<Text> lore() {
        return List.of(
                line("Środek burzy. Dalej wieje.", Formatting.AQUA),
                line("PPM: podmuch w górę, wszystko wokół na boki, potem "
                        + GLIDE_TICKS / 20 + " s powolnego opadania.", Formatting.GRAY),
                line("Raz na " + ArenaMath.HEART_COOLDOWN_TICKS / 20 + " s.", Formatting.DARK_GRAY));
    }

    private static Text line(String text, Formatting colour) {
        return Text.literal(text).formatted(colour).styled(style -> style.withItalic(false));
    }

    @Override
    public ActionResult use(World world, PlayerEntity user, Hand hand) {
        if (!(world instanceof ServerWorld server) || !(user instanceof ServerPlayerEntity player)) {
            return ActionResult.SUCCESS;
        }
        ItemStack stack = player.getStackInHand(hand);
        if (player.getItemCooldownManager().isCoolingDown(stack)) {
            return ActionResult.PASS;
        }
        Vec3d at = player.getPos();
        Vec3d look = player.getRotationVec(1.0F);
        player.setVelocity(look.x * 0.4, 1.5, look.z * 0.4);
        player.velocityModified = true;
        player.fallDistance = 0.0;
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOW_FALLING, GLIDE_TICKS, 0, false, true, true));
        for (LivingEntity other : server.getEntitiesByClass(LivingEntity.class,
                player.getBoundingBox().expand(4.0, 2.0, 4.0), e -> e != player && e.isAlive())) {
            Vec3d away = other.getPos().subtract(at);
            Vec3d flat = new Vec3d(away.x, 0.0, away.z);
            if (flat.lengthSquared() < 0.01) {
                continue;
            }
            flat = flat.normalize();
            other.addVelocity(flat.x * 0.9, 0.35, flat.z * 0.9);
            other.velocityModified = true;
        }
        player.getItemCooldownManager().set(stack, ArenaMath.HEART_COOLDOWN_TICKS);
        server.spawnParticles(ParticleTypes.GUST_EMITTER_LARGE, at.x, at.y + 0.5, at.z, 1, 0, 0, 0, 0);
        server.spawnParticles(ParticleTypes.CLOUD, at.x, at.y + 0.3, at.z, 30, 0.5, 0.2, 0.5, 0.3);
        server.playSound(null, at.x, at.y, at.z, SoundEvents.ENTITY_BREEZE_WIND_BURST.value(),
                SoundCategory.PLAYERS, 1.5F, 0.9F);
        server.playSound(null, at.x, at.y, at.z, SoundEvents.ENTITY_WIND_CHARGE_THROW, SoundCategory.PLAYERS, 1.0F, 0.7F);
        player.sendMessage(Text.literal("W górę.").formatted(Formatting.AQUA), true);
        return ActionResult.SUCCESS;
    }
}
