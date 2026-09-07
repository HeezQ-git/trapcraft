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
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.joml.Matrix4f;
import xyz.nucleoid.packettweaker.PacketContext;

import java.util.List;

/**
 * Something a boss throws.
 *
 * To a client it is an {@code interaction} entity -- an invisible box that
 * takes a swing -- wearing an item display of whatever the boss threw, so
 * punching it works the way punching anything works. A {@link Spec} says
 * how it flies: homing or straight, how fast, how hard it bites, and
 * whether a hit sends it back at the boss for {@link ArenaMath#PARRY_DAMAGE},
 * which is more than any sword gets through the hit cap on purpose.
 *
 * Plain Entity rather than a projectile class: the packet handler refuses
 * attacks on {@code PersistentProjectileEntity} outright (it disconnects the
 * player as a cheater), and a thrown-item entity would render the base item.
 */
public class ArenaProjectileEntity extends Entity implements PolymerEntity {
    public static EntityType<ArenaProjectileEntity> TYPE;
    private static final float SIZE = 0.8F;

    /**
     * How one flies.
     *
     * @param model     the item model to wear, and the one to wear once parried
     * @param speed     blocks a tick
     * @param homing    steer toward the target every tick, or fly straight
     * @param parryable a swing sends it home
     * @param damage    on a player
     * @param trail     dust colour behind it
     * @param life      ticks before it fades
     * @param bite      the sound on a player
     */
    public record Spec(Identifier model, Identifier parriedModel, float speed, boolean homing, boolean parryable,
                       float damage, int trail, int life, SoundEvent bite) {
    }

    private static final float PARRIED_SPEED = 0.85F;
    private static final DustParticleEffect TRAIL_PARRIED = new DustParticleEffect(0x34d8ea, 1.3F);

    private ArenaBossEntity owner;
    private Entity target;
    private Spec spec;
    private Vec3d heading;
    private ServerPlayerEntity parrier;
    private boolean parried;
    private int life;
    private ElementHolder holder;
    private ItemDisplayElement look;
    private DustParticleEffect trail;

    public ArenaProjectileEntity(EntityType<? extends ArenaProjectileEntity> type, World world) {
        super(type, world);
        setNoGravity(true);
        noClip = true;
    }

    public static void register() {
        RegistryKey<EntityType<?>> key = RegistryKey.of(RegistryKeys.ENTITY_TYPE, TrapCraft.id("witness_orb"));
        TYPE = Registry.register(Registries.ENTITY_TYPE, key,
                EntityType.Builder.<ArenaProjectileEntity>create(ArenaProjectileEntity::new, SpawnGroup.MISC)
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
    public static ArenaProjectileEntity launch(ServerWorld world, ArenaBossEntity owner, Vec3d from,
                                               Entity target, Spec spec) {
        Vec3d aim = target.getEyePos().subtract(from).normalize();
        return launch(world, owner, from, aim, target, spec);
    }

    /** Throw one along {@code direction}; the target only matters for homing. */
    public static ArenaProjectileEntity launch(ServerWorld world, ArenaBossEntity owner, Vec3d from,
                                               Vec3d direction, Entity target, Spec spec) {
        ArenaProjectileEntity shot = new ArenaProjectileEntity(TYPE, world);
        shot.owner = owner;
        shot.target = target;
        shot.spec = spec;
        shot.trail = new DustParticleEffect(spec.trail(), 1.1F);
        shot.heading = direction.normalize();
        // An entity's position is its feet; the eye's centre is half a box up.
        shot.setPosition(from.x, from.y - SIZE / 2.0, from.z);
        shot.setVelocity(shot.heading.multiply(spec.speed()));
        world.spawnEntity(shot);
        return shot;
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
        if (spec == null) {
            discard();
            return;
        }
        if (holder == null) {
            dress();
        }
        life++;
        if (life > spec.life() || owner == null || owner.isRemoved() || TrapArena.stage() != TrapArena.Stage.FIGHT) {
            pop(world);
            return;
        }
        Vec3d centre = getPos().add(0.0, SIZE / 2.0, 0.0);
        if (parried) {
            Vec3d aimAt = owner.getPos().add(0.0, owner.getHeight() * 0.6, 0.0);
            Vec3d want = aimAt.subtract(centre).normalize().multiply(PARRIED_SPEED);
            setVelocity(getVelocity().multiply(0.82).add(want.multiply(0.18)));
            setPosition(getPos().add(getVelocity()));
            world.spawnParticles(TRAIL_PARRIED, centre.x, centre.y, centre.z, 2, 0.1, 0.1, 0.1, 0.0);
            world.spawnParticles(ParticleTypes.CRIT, centre.x, centre.y, centre.z, 1, 0.1, 0.1, 0.1, 0.02);
            if (centre.squaredDistanceTo(aimAt) < 1.8 * 1.8) {
                owner.parried(parrier, this);
                world.spawnParticles(ParticleTypes.EXPLOSION, centre.x, centre.y, centre.z, 1, 0, 0, 0, 0);
                discard();
            }
            return;
        }

        if (spec.homing()) {
            if (target == null || !target.isAlive() || !TrapArena.isCombatant(target)) {
                target = owner.pickTarget();
                if (target == null) {
                    pop(world);
                    return;
                }
            }
            // Steer: blend the current heading toward the target, so it
            // curves rather than snaps -- a curve is what makes it look for you.
            Vec3d want = target.getEyePos().subtract(centre).normalize().multiply(spec.speed());
            setVelocity(getVelocity().multiply(0.82).add(want.multiply(0.18)));
        }
        setPosition(getPos().add(getVelocity()));

        world.spawnParticles(trail, centre.x, centre.y, centre.z, 2, 0.12, 0.12, 0.12, 0.0);
        if (life % 3 == 0) {
            world.spawnParticles(ParticleTypes.END_ROD, centre.x, centre.y, centre.z, 1, 0.05, 0.05, 0.05, 0.01);
        }
        for (ServerPlayerEntity player : world.getPlayers(p -> TrapArena.isCombatant(p)
                && p.getBoundingBox().expand(0.35).intersects(getBoundingBox()))) {
            bite(world, player);
            return;
        }
        ArenaBoss kind = owner.kind();
        if (getY() < kind.origin().getY() - 2 || getY() > kind.origin().getY() + 30
                || getPos().squaredDistanceTo(kind.centre()) > (kind.pitRadius() + 6) * (kind.pitRadius() + 6)
                || (!spec.homing() && !world.getBlockState(getBlockPos()).isAir())) {
            pop(world);
        }
    }

    private void dress() {
        holder = new ElementHolder();
        look = new ItemDisplayElement(DisplayRig.modelStack(spec.model()));
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
        owner.hurtPlayer(world, player, spec.damage(), world.getDamageSources().mobProjectile(this, owner));
        Vec3d at = player.getEyePos();
        world.playSound(null, at.x, at.y, at.z, spec.bite(), SoundCategory.HOSTILE, 1.0F, 0.7F);
        world.spawnParticles(ParticleTypes.WITCH, at.x, at.y, at.z, 16, 0.4, 0.4, 0.4, 0.1);
        TrapNet.flash(player, spec.trail(), 8);
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
        if (parried || spec == null || !(source.getAttacker() instanceof ServerPlayerEntity player)) {
            return false;
        }
        Vec3d centre = getPos().add(0.0, SIZE / 2.0, 0.0);
        if (!spec.parryable()) {
            // Swatted rather than sent back: it still goes away, which is
            // something, and the sound says it was a hit.
            world.playSound(null, centre.x, centre.y, centre.z, SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP,
                    SoundCategory.PLAYERS, 1.0F, 1.2F);
            pop(world);
            return true;
        }
        parried = true;
        parrier = player;
        life = 0;
        look.setItem(DisplayRig.modelStack(spec.parriedModel()));
        look.setGlowing(true);
        look.setGlowColorOverride(0x34d8ea);
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
}
