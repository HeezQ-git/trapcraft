package dev.heezq.trapcraft;

import eu.pb4.polymer.core.api.entity.PolymerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.ActiveTargetGoal;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import xyz.nucleoid.packettweaker.PacketContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What every arena boss shares: the fight's skeleton.
 *
 * A real hostile mob, so every sword, arrow, crit and enchantment in the
 * pack works on it the way it works on a zombie; disguised to clients as a
 * vanilla mob chosen for its hitbox and its voice ({@link #disguise}), and
 * invisible, because the body is a {@link DisplayRig} riding on top.
 *
 * The fight is a state machine over the boss's {@link Ability} list: one
 * cast at a time, each with its own cooldown, more of them unlocked as the
 * bar drops through the phases. The base owns the scheduler, the intro, the
 * phase flourish, the damage policy (capped per hit, counted only from
 * players, ignored from anyone knocked out), the death, and the pit bounds.
 * A subclass owns the abilities themselves, its rig, and its disguise.
 */
public abstract class ArenaBossEntity extends HostileEntity implements PolymerEntity {

    /** How long the rise out of the floor takes; nothing can hurt it until then. */
    public static final int INTRO_TICKS = 100;
    protected static final int PHASE_TICKS = 60;

    /** One thing the boss can do: an id the debug command knows, and its clock. */
    public record Ability(String id, int cooldown, int unlockedAt) {
    }

    /** The phase change, run by the base. */
    protected static final Ability PHASE = new Ability("phase", 0, 1);

    protected DisplayRig rig;
    protected int phase = 1;
    protected boolean shielded = true;
    protected Ability casting;
    protected int castTick;
    protected int nextCastIn = 60;
    protected final Map<Ability, Integer> timers = new HashMap<>();
    private int nextAmbient = 80;

    protected ArenaBossEntity(EntityType<? extends HostileEntity> type, World world) {
        super(type, world);
        setPersistent();
        setInvisible(true);
        for (Ability ability : abilities()) {
            timers.put(ability, ability.cooldown() / 2);
        }
    }

    // --- what a boss has to say about itself -----------------------------------

    public abstract ArenaBoss kind();

    protected abstract List<Ability> abilities();

    protected abstract DisplayRig makeRig();

    protected abstract EntityType<?> disguise();

    /** One tick of one cast; true when it is over. */
    protected abstract boolean cast(ServerWorld world, Ability ability, int tick);

    /** Whether it walks up and hits people. A flying boss says no. */
    protected boolean melee() {
        return true;
    }

    /** The intro's particles and noises; progress 0..1. */
    protected void introTick(ServerWorld world, float progress) {
        Vec3d at = getPos();
        world.spawnParticles(ParticleTypes.REVERSE_PORTAL, at.x, at.y + 0.2, at.z, 12, 1.4, 0.3, 1.4, 0.4);
        if (age == 1) {
            sound(SoundEvents.BLOCK_END_PORTAL_SPAWN, 1.5F, 0.6F);
        }
        if (age % 10 == 0) {
            for (ServerPlayerEntity player : TrapArena.arenaPlayers()) {
                TrapNet.shake(player, 0.35F + 0.5F * progress, 10);
            }
        }
    }

    /** Idle noises and wisps between casts. */
    protected void breathe(ServerWorld world) {
    }

    /** An ambient sound, every few seconds. Null for silence. */
    protected SoundEvent ambient(int roll) {
        return null;
    }

    /** The phase just ticked over; the base has already shielded and flourished. */
    protected void onPhaseStart(ServerWorld world, int phase) {
    }

    /** A player just landed a hit for {@code dealt}. */
    protected void onHurt(ServerWorld world, ServerPlayerEntity by, float dealt) {
    }

    /** A melee swing landed on {@code target}. */
    protected void onStrike(ServerWorld world, Entity target) {
    }

    protected void deathSounds() {
        sound(SoundEvents.ENTITY_WITHER_DEATH, 1.6F, 0.6F);
    }

    /** Every tick of the death, 1..DEATH_TICKS. */
    protected void deathTick(ServerWorld world) {
        Vec3d at = getPos().add(0.0, 2.0, 0.0);
        world.spawnParticles(ParticleTypes.SOUL, at.x, at.y, at.z, 4, 0.8, 1.0, 0.8, 0.02);
        if (deathTime % 10 == 0) {
            world.spawnParticles(ParticleTypes.TOTEM_OF_UNDYING, at.x, at.y + 0.5, at.z, 30, 0.6, 0.8, 0.6, 0.4);
            sound(SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, 1.0F, 0.6F + deathTime * 0.01F);
        }
    }

    /**
     * Every point of damage a boss deals to a player goes through here, so a
     * boss can scale it (the bandit's stakes) and stack its own debuff on
     * the way -- one hook for melee, projectiles, waves and puddles alike.
     */
    public void hurtPlayer(ServerWorld world, ServerPlayerEntity player, float amount, DamageSource source) {
        player.damage(world, source, amount * damageMultiplier(player));
        afflict(player, 1);
    }

    /** How hard this boss hits this player right now, 1 = as written. */
    protected float damageMultiplier(ServerPlayerEntity player) {
        return 1.0F;
    }

    /** Put this boss's debuff on somebody it just hurt. */
    protected void afflict(ServerPlayerEntity player, int stacks) {
    }

    /** A parried projectile came home. Bosses that throw parryable things override. */
    public void parried(ServerPlayerEntity player, ArenaProjectileEntity projectile) {
    }

    /** Where thrown things leave from. */
    public Vec3d throwPos() {
        return getPos().add(0.0, getHeight() * 0.8, 0.0).add(rig == null ? Vec3d.ZERO : rig.forward().multiply(0.7));
    }

    // --- vanilla knobs ------------------------------------------------------

    public void sizeFor(int players) {
        getAttributeInstance(EntityAttributes.MAX_HEALTH).setBaseValue(ArenaMath.bossHealth(players));
        setHealth(getMaxHealth());
    }

    @Override
    public EntityType<?> getPolymerEntityType(PacketContext context) {
        return disguise();
    }

    @Override
    protected void initGoals() {
        if (melee()) {
            goalSelector.add(2, new Strike());
        }
        goalSelector.add(7, new LookAtEntityGoal(this, PlayerEntity.class, 24.0F));
        targetSelector.add(1, new ActiveTargetGoal<>(this, PlayerEntity.class, 10, false, false,
                (entity, world) -> TrapArena.isCombatant(entity)));
    }

    /** Melee, except while the body is busy with something bigger. */
    private class Strike extends MeleeAttackGoal {
        Strike() {
            super(ArenaBossEntity.this, 1.0, true);
        }

        @Override
        public boolean canStart() {
            return casting == null && !shielded && super.canStart();
        }

        @Override
        public boolean shouldContinue() {
            return casting == null && !shielded && super.shouldContinue();
        }
    }

    @Override
    public boolean tryAttack(ServerWorld world, Entity target) {
        boolean hit = super.tryAttack(world, target);
        if (hit) {
            onStrike(world, target);
            if (target instanceof ServerPlayerEntity player) {
                TrapNet.shake(player, 0.6F, 6);
            }
        }
        return hit;
    }

    @Override
    public boolean canHaveStatusEffect(StatusEffectInstance effect) {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
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
    public boolean canBeLeashed() {
        return false;
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return null;
    }

    // --- death -------------------------------------------------------------------

    @Override
    protected void updatePostDeath() {
        deathTime++;
        if (deathTime == 1) {
            deathSounds();
        }
        if (getWorld() instanceof ServerWorld world) {
            deathTick(world);
            if (deathTime == 60) {
                Vec3d at = getPos().add(0.0, 2.0, 0.0);
                world.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, at.x, at.y, at.z, 2, 0.5, 0.5, 0.5, 0.0);
                world.spawnParticles(ParticleTypes.FLASH, at.x, at.y, at.z, 3, 0.3, 0.3, 0.3, 0.0);
                sound(SoundEvents.ENTITY_GENERIC_EXPLODE.value(), 2.0F, 0.5F);
                TrapArena.victoryBurst(this);
            }
        }
        if (deathTime >= DisplayRig.DEATH_TICKS) {
            discard();
        }
    }

    /** Called from {@link #onDeath} before the rig starts dying: drop what you are holding. */
    protected void onDying() {
    }

    @Override
    public void onDeath(DamageSource source) {
        if (dead) {
            return;
        }
        // Not super: vanilla would play the disguise's death cry, drop nothing
        // worth having and remove the body in twenty ticks. The rig's own
        // death runs from updatePostDeath and the loot from TrapArena.
        dead = true;
        casting = null;
        getNavigation().stop();
        onDying();
        if (rig != null) {
            rig.startDeath();
        }
        TrapArena.onBossDeath(this);
    }

    @Override
    public void onRemoved() {
        super.onRemoved();
        onDying();
        if (rig != null) {
            rig.destroy();
        }
    }

    // --- damage in ----------------------------------------------------------

    @Override
    public boolean damage(ServerWorld world, DamageSource source, float amount) {
        if (source.isOf(DamageTypes.GENERIC_KILL)) {
            // /kill: the one thing that must work outside a fight, because
            // an op who summoned one to look at it needs a way to unsummon it.
            return super.damage(world, source, amount);
        }
        if (shielded || isDead() || TrapArena.stage() != TrapArena.Stage.FIGHT) {
            return false;
        }
        if (!(source.getAttacker() instanceof ServerPlayerEntity player)) {
            // Fall, fire, cactus, a stray creeper: nothing that is not a
            // person swinging counts, so the fight cannot be cheesed from
            // the stands with a bucket of lava.
            return false;
        }
        if (TrapArena.isKnockedOut(player)) {
            player.sendMessage(kind().knockedOutSwing(), true);
            return false;
        }
        float before = getHealth();
        float capped = Math.min(amount, ArenaMath.hitCap(getMaxHealth()));
        boolean hurt = super.damage(world, source, capped);
        if (!hurt) {
            return false;
        }
        landed(world, player, Math.max(0.0F, before - getHealth()));
        return true;
    }

    /** Bookkeeping for a hit that went through: attribution, flash, phase check. */
    protected void landed(ServerWorld world, ServerPlayerEntity player, float dealt) {
        TrapArena.recordDamage(player, dealt);
        if (rig != null) {
            rig.hurt();
        }
        onHurt(world, player, dealt);
        int now = ArenaMath.phaseOf(getHealth() / getMaxHealth());
        if (now > phase && casting != PHASE && !isDead()) {
            begin(PHASE);
        }
    }

    /** Damage the base cap would otherwise refuse -- for the parry and other rewards. */
    protected void takeUncapped(ServerWorld world, DamageSource source, float amount, ServerPlayerEntity by) {
        if (shielded || isDead()) {
            return;
        }
        float before = getHealth();
        super.damage(world, source, amount);
        landed(world, by, Math.max(0.0F, before - getHealth()));
    }

    // --- the tick -----------------------------------------------------------

    @Override
    protected void mobTick(ServerWorld world) {
        super.mobTick(world);
        if (rig == null) {
            rig = makeRig();
            rig.setRise(-3.6F);
        }
        rig.setYaw(getYaw());
        if (isDead() || TrapArena.stage() != TrapArena.Stage.FIGHT) {
            return;
        }

        if (age < INTRO_TICKS) {
            getNavigation().stop();
            float p = age / (float) INTRO_TICKS;
            float eased = 1.0F - (1.0F - p) * (1.0F - p);
            rig.setRise(-3.6F * (1.0F - eased));
            introTick(world, p);
            return;
        }
        if (age == INTRO_TICKS) {
            shielded = false;
            rig.setRise(0.0F);
            TrapArena.fightBegins(this);
        }

        keepInThePit();
        breathe(world);
        if (--nextAmbient <= 0) {
            nextAmbient = 80 + random.nextInt(80);
            SoundEvent noise = ambient(random.nextInt(3));
            if (noise != null) {
                sound(noise, 1.0F, 0.7F);
            }
        }

        LivingEntity target = getTarget();
        if (target != null && !TrapArena.isCombatant(target)) {
            setTarget(null);
            target = null;
        }
        if (target == null || age % 80 == 0 && random.nextInt(3) == 0) {
            ServerPlayerEntity pick = pickTarget();
            if (pick != null) {
                setTarget(pick);
            }
        }

        if (casting != null) {
            castTick++;
            boolean done = casting == PHASE ? phaseChange(world) : cast(world, casting, castTick);
            if (done) {
                casting = null;
            }
            return;
        }
        for (Map.Entry<Ability, Integer> timer : timers.entrySet()) {
            if (timer.getValue() > 0) {
                timer.setValue(timer.getValue() - 1);
            }
        }
        if (--nextCastIn > 0 || getTarget() == null) {
            return;
        }
        List<Ability> ready = new ArrayList<>();
        for (Ability ability : abilities()) {
            if (ability.unlockedAt() <= phase && timers.getOrDefault(ability, 0) <= 0) {
                ready.add(ability);
            }
        }
        if (!ready.isEmpty()) {
            begin(ready.get(random.nextInt(ready.size())));
        } else {
            nextCastIn = 20;
        }
    }

    /** It fights in the pit. Anything that got it out puts it back. */
    protected void keepInThePit() {
        ArenaBoss kind = kind();
        Vec3d centre = kind.centre();
        double dx = getX() - centre.x;
        double dz = getZ() - centre.z;
        double r = Math.sqrt(dx * dx + dz * dz);
        int floor = kind.origin().getY();
        if (r > kind.pitRadius() - 1.0 || getY() < floor - 1 || getY() > floor + 8) {
            double k = r > 0.001 ? Math.min(1.0, (kind.pitRadius() - 3.0) / r) : 0.0;
            refreshPositionAndAngles(centre.x + dx * k, floor + 1, centre.z + dz * k, getYaw(), getPitch());
        }
    }

    /** A random combatant, weighted a little toward whoever hurt it most. */
    public ServerPlayerEntity pickTarget() {
        List<ServerPlayerEntity> room = TrapArena.combatants();
        if (room.isEmpty()) {
            return null;
        }
        if (random.nextInt(3) == 0) {
            ServerPlayerEntity top = TrapArena.topDamage(room);
            if (top != null) {
                return top;
            }
        }
        return room.get(random.nextInt(room.size()));
    }

    // --- casts ---------------------------------------------------------------

    public void begin(Ability ability) {
        if (isDead()) {
            return;
        }
        if (casting != null && casting != PHASE) {
            interrupted(casting);
        }
        casting = ability;
        castTick = 0;
        timers.put(ability, ability.cooldown());
        nextCastIn = 50 + random.nextInt(50);
        getNavigation().stop();
        onBegin(ability);
    }

    /** The debug command: by id. False when there is no such ability. */
    public boolean begin(String id) {
        if (id.equals(PHASE.id())) {
            begin(PHASE);
            return true;
        }
        for (Ability ability : abilities()) {
            if (ability.id().equals(id)) {
                begin(ability);
                return true;
            }
        }
        return false;
    }

    /** A cast is starting: clear scratch state. */
    protected void onBegin(Ability ability) {
    }

    /** A cast was cut short by the phase change: put the world back. */
    protected void interrupted(Ability ability) {
    }

    public Ability casting() {
        return casting;
    }

    public int phase() {
        return phase;
    }

    public boolean shielded() {
        return shielded;
    }

    /** The phase flourish: untouchable for three seconds, and everything changes. */
    private boolean phaseChange(ServerWorld world) {
        if (castTick == 1) {
            phase++;
            shielded = true;
            rig.setPhase(phase);
            rig.flourish();
            sound(SoundEvents.ENTITY_WITHER_AMBIENT, 1.8F, 0.5F);
            sound(SoundEvents.BLOCK_BEACON_POWER_SELECT, 1.5F, 0.6F);
            Vec3d at = getPos();
            world.spawnParticles(ParticleTypes.SONIC_BOOM, at.x, at.y + 2.0, at.z, 1, 0, 0, 0, 0);
            TrapArena.onPhase(this, phase);
            for (Ability ability : abilities()) {
                if (ability.unlockedAt() == phase) {
                    timers.put(ability, 20 + random.nextInt(40));
                }
            }
            onPhaseStart(world, phase);
            return false;
        }
        Vec3d at = getPos();
        if (castTick % 3 == 0) {
            double angle = castTick * 0.5;
            for (int i = 0; i < 3; i++) {
                double a = angle + i * Math.PI * 2 / 3;
                world.spawnParticles(ParticleTypes.END_ROD, at.x + Math.cos(a) * 2.2, at.y + 0.5 + castTick * 0.05,
                        at.z + Math.sin(a) * 2.2, 1, 0, 0, 0, 0.02);
            }
        }
        if (castTick >= PHASE_TICKS) {
            shielded = false;
            return true;
        }
        return false;
    }

    // --- helpers --------------------------------------------------------------

    protected void sound(SoundEvent event, float volume, float pitch) {
        getWorld().playSound(null, getX(), getY() + 1.5, getZ(), event, SoundCategory.HOSTILE, volume, pitch);
    }

    protected static void sound(ServerPlayerEntity player, SoundEvent event, float volume, float pitch) {
        player.playSoundToPlayer(event, SoundCategory.HOSTILE, volume, pitch);
    }

    /** Facing yaw from here toward {@code to}. */
    protected float yawToward(Vec3d to) {
        return (float) Math.toDegrees(Math.atan2(-(to.x - getX()), to.z - getZ()));
    }

    /** A spot inside the pit at {@code r} blocks from the centre, on the floor. */
    protected Vec3d onFloor(double angle, double r) {
        Vec3d centre = kind().centre();
        return new Vec3d(centre.x + Math.cos(angle) * r, centre.y, centre.z + Math.sin(angle) * r);
    }
}
