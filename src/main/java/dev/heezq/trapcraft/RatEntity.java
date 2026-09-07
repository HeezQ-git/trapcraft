package dev.heezq.trapcraft;

import eu.pb4.polymer.core.api.entity.PolymerEntity;
import eu.pb4.polymer.core.api.entity.PolymerEntityUtils;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.ai.goal.ActiveTargetGoal;
import net.minecraft.entity.ai.goal.LookAroundGoal;
import net.minecraft.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.entity.ai.goal.WanderAroundGoal;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.World;
import xyz.nucleoid.packettweaker.PacketContext;

/**
 * Szczur. The rat king's subjects, and the fight's real pressure.
 *
 * A silverfish half again as big: same scuttle, same box, same voice,
 * which is the noise a sewer should make. Eight hearts, three on a bite
 * that also carries a stack of Zaraza, and a minute to live, so a swarm
 * nobody deals with still thins out by itself. Tracked by the arena, so
 * the event's end takes them with it.
 */
public class RatEntity extends HostileEntity implements PolymerEntity {
    public static EntityType<RatEntity> TYPE;
    private static final float SCALE = 1.5F;

    private RatKingEntity king;

    public RatEntity(EntityType<? extends HostileEntity> type, World world) {
        super(type, world);
        setPersistent();
        setCustomName(Text.literal("Szczur").formatted(Formatting.DARK_GREEN));
    }

    public static void register() {
        RegistryKey<EntityType<?>> key = RegistryKey.of(RegistryKeys.ENTITY_TYPE, TrapCraft.id("rat"));
        TYPE = Registry.register(Registries.ENTITY_TYPE, key,
                EntityType.Builder.<RatEntity>create(RatEntity::new, SpawnGroup.MONSTER)
                        // A silverfish's box, before the SCALE attribute.
                        .dimensions(0.4F, 0.3F)
                        .eyeHeight(0.13F)
                        .disableSaving()
                        .maxTrackingRange(10)
                        .build(key));
        PolymerEntityUtils.registerType(TYPE);
        FabricDefaultAttributeRegistry.register(TYPE, attributes());
    }

    private static DefaultAttributeContainer.Builder attributes() {
        return HostileEntity.createHostileAttributes()
                .add(EntityAttributes.MAX_HEALTH, ArenaMath.RAT_HEALTH)
                .add(EntityAttributes.MOVEMENT_SPEED, 0.38)
                .add(EntityAttributes.ATTACK_DAMAGE, ArenaMath.RAT_DAMAGE)
                .add(EntityAttributes.FOLLOW_RANGE, 40.0)
                .add(EntityAttributes.STEP_HEIGHT, 1.0)
                .add(EntityAttributes.SCALE, SCALE);
    }

    public void setKing(RatKingEntity king) {
        this.king = king;
    }

    @Override
    public EntityType<?> getPolymerEntityType(PacketContext context) {
        return EntityType.SILVERFISH;
    }

    @Override
    protected void initGoals() {
        goalSelector.add(2, new MeleeAttackGoal(this, 1.2, true));
        goalSelector.add(6, new WanderAroundGoal(this, 0.9));
        goalSelector.add(7, new LookAroundGoal(this));
        targetSelector.add(1, new ActiveTargetGoal<>(this, PlayerEntity.class, 10, false, false,
                (entity, world) -> TrapArena.isCombatant(entity)));
    }

    @Override
    protected void mobTick(ServerWorld world) {
        super.mobTick(world);
        if (age > ArenaMath.RAT_LIFE_TICKS || TrapArena.stage() != TrapArena.Stage.FIGHT) {
            world.spawnParticles(ParticleTypes.POOF, getX(), getY() + 0.3, getZ(), 8, 0.3, 0.2, 0.3, 0.02);
            discard();
        }
    }

    /** A bite carries Zaraza, through the king so the same rule applies to every source. */
    @Override
    public boolean tryAttack(ServerWorld world, net.minecraft.entity.Entity target) {
        boolean hit = super.tryAttack(world, target);
        if (hit && target instanceof ServerPlayerEntity player) {
            RatKingBoss.plague(player, 1);
            world.spawnParticles(ParticleTypes.ITEM_SLIME, player.getX(), player.getBodyY(0.5), player.getZ(),
                    6, 0.3, 0.3, 0.3, 0.05);
        }
        return hit;
    }

    @Override
    public boolean damage(ServerWorld world, DamageSource source, float amount) {
        // The king's own casts sweep the floor; his subjects are exempt.
        if (source.getAttacker() == king) {
            return false;
        }
        return super.damage(world, source, amount);
    }

    @Override
    public boolean handleFallDamage(double fallDistance, float damageMultiplier, DamageSource damageSource) {
        return false;
    }

    @Override
    protected boolean isDisallowedInPeaceful() {
        return false;
    }

    @Override
    public boolean canImmediatelyDespawn(double distanceSquared) {
        return false;
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.ENTITY_SILVERFISH_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.ENTITY_SILVERFISH_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.ENTITY_SILVERFISH_DEATH;
    }

    @Override
    public void onDeath(DamageSource source) {
        super.onDeath(source);
        if (getWorld() instanceof ServerWorld world) {
            world.spawnParticles(ParticleTypes.ITEM_SLIME, getX(), getY() + 0.3, getZ(), 10, 0.3, 0.2, 0.3, 0.05);
            world.playSound(null, getX(), getY(), getZ(), SoundEvents.BLOCK_MUD_BREAK,
                    net.minecraft.sound.SoundCategory.HOSTILE, 1.0F, 1.3F);
        }
    }
}
