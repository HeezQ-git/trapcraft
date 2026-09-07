package dev.heezq.trapcraft;

import eu.pb4.polymer.core.api.entity.PolymerEntity;
import eu.pb4.polymer.core.api.entity.PolymerEntityUtils;
import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.attachment.EntityAttachment;
import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;
import eu.pb4.polymer.virtualentity.api.tracker.InteractionTrackedData;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.decoration.Brightness;
import net.minecraft.item.ItemDisplayContext;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.joml.Matrix4f;
import xyz.nucleoid.packettweaker.PacketContext;

import java.util.List;

/**
 * An eye the witness throws.
 *
 * Slow, homing, and the one thing in the fight a player can hit back. To a
 * client it is an {@code interaction} entity -- an invisible box that takes
 * a swing -- wearing an item display of the eye, so punching it works the
 * way punching anything works. A hit turns it cyan and sends it back at the
 * boss for {@link ArenaMath#PARRY_DAMAGE}, which is more than any sword gets
 * through the hit cap: the parry is the best damage in the room, on purpose.
 *
 * Plain Entity rather than a projectile class: the packet handler refuses
 * attacks on {@code PersistentProjectileEntity} outright (it disconnects the
 * player as a cheater), and a thrown-item entity would render the base item.
 */
public class WitnessEyeEntity extends Entity implements PolymerEntity {
    public static EntityType<WitnessEyeEntity> TYPE;

    private static final float SPEED = 0.30F;
    private static final float PARRIED_SPEED = 0.85F;
    private static final int LIFE_TICKS = 140;
    private static final float SIZE = 0.8F;
    private static final DustParticleEffect TRAIL = new DustParticleEffect(0x8a4fd8, 1.1F);
    private static final DustParticleEffect TRAIL_PARRIED = new DustParticleEffect(0x34d8ea, 1.3F);

    private WitnessEntity owner;
    private Entity target;
    private ServerPlayerEntity parrier;
    private boolean parried;
    private int life;
    private ElementHolder holder;
    private ItemDisplayElement look;

    public WitnessEyeEntity(EntityType<? extends WitnessEyeEntity> type, World world) {
        super(type, world);
        setNoGravity(true);
        noClip = true;
    }

    public static void register() {
        RegistryKey<EntityType<?>> key = RegistryKey.of(RegistryKeys.ENTITY_TYPE, TrapCraft.id("witness_orb"));
        TYPE = Registry.register(Registries.ENTITY_TYPE, key,
                EntityType.Builder.<WitnessEyeEntity>create(WitnessEyeEntity::new, SpawnGroup.MISC)
                        .dimensions(SIZE, SIZE)
                        .disableSaving()
                        .disableSummon()
                        .maxTrackingRange(10)
                        .build(key));
        // ENTITY_TYPE is a synced registry; this keeps ours out of the sync
        // and lets the disguise below stand in for it.
        PolymerEntityUtils.registerType(TYPE);
    }

    /** Throw one from {@code from} at {@code target}. */
    public static WitnessEyeEntity launch(ServerWorld world, WitnessEntity owner, Vec3d from, Entity target) {
        WitnessEyeEntity orb = new WitnessEyeEntity(TYPE, world);
        orb.owner = owner;
        orb.target = target;
        // An entity's position is its feet; the eye's centre is half a box up.
        orb.setPosition(from.x, from.y - SIZE / 2.0, from.z);
        Vec3d aim = target.getEyePos().subtract(from).normalize();
        orb.setVelocity(aim.multiply(SPEED));
        world.spawnEntity(orb);
        return orb;
    }

    // --- the disguise -------------------------------------------------------

    @Override
    public EntityType<?> getPolymerEntityType(PacketContext context) {
        return EntityType.INTERACTION;
    }

    @Override
    public void modifyRawTrackedData(List<DataTracker.SerializedEntry<?>> data, ServerPlayerEntity player,
                                     boolean initial) {
        if (initial) {
            data.add(DataTracker.SerializedEntry.of(InteractionTrackedData.WIDTH, SIZE));
            data.add(DataTracker.SerializedEntry.of(InteractionTrackedData.HEIGHT, SIZE));
            data.add(DataTracker.SerializedEntry.of(InteractionTrackedData.RESPONSE, true));
        }
    }

    // --- flight -------------------------------------------------------------

    @Override
    public void tick() {
        super.tick();
        if (!(getWorld() instanceof ServerWorld world)) {
            return;
        }
        if (holder == null) {
            dress();
        }
        life++;
        if (life > LIFE_TICKS || owner == null || owner.isRemoved() || TrapArena.stage() != TrapArena.Stage.FIGHT) {
            pop(world);
            return;
        }
        if (target == null || !target.isAlive() || (!parried && !TrapArena.isCombatant(target))) {
            target = parried ? owner : owner.pickTarget();
            if (target == null) {
                pop(world);
                return;
            }
        }

        // Steer: blend the current heading toward the target, so it curves
        // rather than snaps -- a curve is what makes it look for you.
        Vec3d centre = getPos().add(0.0, SIZE / 2.0, 0.0);
        Vec3d aimAt = parried ? owner.getPos().add(0.0, 2.2, 0.0) : target.getEyePos();
        Vec3d want = aimAt.subtract(centre).normalize().multiply(parried ? PARRIED_SPEED : SPEED);
        setVelocity(getVelocity().multiply(0.82).add(want.multiply(0.18)));
        setPosition(getPos().add(getVelocity()));

        if (parried) {
            world.spawnParticles(TRAIL_PARRIED, centre.x, centre.y, centre.z, 2, 0.1, 0.1, 0.1, 0.0);
            world.spawnParticles(ParticleTypes.CRIT, centre.x, centre.y, centre.z, 1, 0.1, 0.1, 0.1, 0.02);
            if (centre.squaredDistanceTo(aimAt) < 1.6 * 1.6) {
                owner.parried(parrier, this);
                world.spawnParticles(ParticleTypes.EXPLOSION, centre.x, centre.y, centre.z, 1, 0, 0, 0, 0);
                discard();
            }
            return;
        }

        world.spawnParticles(TRAIL, centre.x, centre.y, centre.z, 2, 0.12, 0.12, 0.12, 0.0);
        if (life % 3 == 0) {
            world.spawnParticles(ParticleTypes.END_ROD, centre.x, centre.y, centre.z, 1, 0.05, 0.05, 0.05, 0.01);
        }
        for (ServerPlayerEntity player : world.getPlayers(p -> TrapArena.isCombatant(p)
                && p.getBoundingBox().expand(0.35).intersects(getBoundingBox()))) {
            bite(world, player);
            return;
        }
        if (getY() < TrapArena.ORIGIN.getY() - 2 || getY() > TrapArena.ORIGIN.getY() + 30) {
            pop(world);
        }
    }

    private void dress() {
        holder = new ElementHolder();
        look = new ItemDisplayElement(WitnessRig.modelStack(TrapCraft.id("witness_orb")));
        look.setItemDisplayContext(ItemDisplayContext.NONE);
        look.setBrightness(Brightness.FULL);
        look.setInterpolationDuration(2);
        look.setTeleportDuration(2);
        look.setViewRange(2.0F);
        holder.addElement(look);
        EntityAttachment.ofTicking(holder, this);
        spin();
    }

    private void spin() {
        float t = life;
        look.setTransformation(new Matrix4f()
                .translate(0.0F, SIZE / 2.0F, 0.0F)
                .rotateY(t * 0.22F)
                .rotateX(t * 0.13F)
                .scale(0.62F));
        look.startInterpolationIfDirty();
    }

    @Override
    public void baseTick() {
        super.baseTick();
        if (look != null) {
            spin();
        }
    }

    private void bite(ServerWorld world, ServerPlayerEntity player) {
        player.damage(world, world.getDamageSources().mobProjectile(this, owner), ArenaMath.ORB_DAMAGE);
        TrapArena.dread(player, 1);
        Vec3d at = player.getEyePos();
        world.playSound(null, at.x, at.y, at.z, SoundEvents.ENTITY_PHANTOM_BITE, SoundCategory.HOSTILE, 1.0F, 0.7F);
        world.spawnParticles(ParticleTypes.WITCH, at.x, at.y, at.z, 16, 0.4, 0.4, 0.4, 0.1);
        TrapNet.flash(player, 0x5a2d9c, 8);
        discard();
    }

    private void pop(ServerWorld world) {
        Vec3d centre = getPos().add(0.0, SIZE / 2.0, 0.0);
        world.spawnParticles(ParticleTypes.POOF, centre.x, centre.y, centre.z, 6, 0.2, 0.2, 0.2, 0.02);
        discard();
    }

    // --- the parry ----------------------------------------------------------

    /** A swing at it: the client sent an attack, the server routes it here. */
    @Override
    public boolean damage(ServerWorld world, DamageSource source, float amount) {
        if (parried || !(source.getAttacker() instanceof ServerPlayerEntity player)) {
            return false;
        }
        parried = true;
        parrier = player;
        target = owner;
        life = 0;
        look.setItem(WitnessRig.modelStack(TrapCraft.id("witness_orb_cyan")));
        look.setGlowing(true);
        look.setGlowColorOverride(0x34d8ea);

        Vec3d centre = getPos().add(0.0, SIZE / 2.0, 0.0);
        world.playSound(null, centre.x, centre.y, centre.z, SoundEvents.ITEM_TRIDENT_RETURN,
                SoundCategory.PLAYERS, 1.0F, 1.4F);
        world.playSound(null, centre.x, centre.y, centre.z, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME,
                SoundCategory.PLAYERS, 1.2F, 1.2F);
        world.spawnParticles(ParticleTypes.CRIT, centre.x, centre.y, centre.z, 14, 0.3, 0.3, 0.3, 0.3);
        player.sendMessage(Text.literal("ODBITE!").formatted(Formatting.AQUA, Formatting.BOLD), true);
        TrapAwards.grant(player, "parry");
        return true;
    }

    @Override
    public boolean isAttackable() {
        return true;
    }

    @Override
    public boolean canHit() {
        return true;
    }

    @Override
    public boolean isCollidable(Entity entity) {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public void onRemoved() {
        super.onRemoved();
        if (holder != null) {
            holder.destroy();
        }
    }

    // --- nothing to save --------------------------------------------------------

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
    }

    @Override
    protected void readCustomData(ReadView view) {
    }

    @Override
    protected void writeCustomData(WriteView view) {
    }

    /** For the boss: how far along its life, 0..1, so a late orb reads as fading. */
    public float age() {
        return MathHelper.clamp(life / (float) LIFE_TICKS, 0.0F, 1.0F);
    }
}
