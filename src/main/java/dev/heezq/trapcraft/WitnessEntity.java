package dev.heezq.trapcraft;

import eu.pb4.polymer.core.api.entity.PolymerEntity;
import eu.pb4.polymer.core.api.entity.PolymerEntityUtils;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.ai.goal.ActiveTargetGoal;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.DustColorTransitionParticleEffect;
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
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import xyz.nucleoid.packettweaker.PacketContext;
import net.minecraft.block.Blocks;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Świadek. The witness.
 *
 * A real hostile mob, so every sword, arrow, crit and enchantment in the
 * pack works on it the way it works on a zombie; disguised to clients as a
 * vex scaled up four times, so the box they swing at is the box the server
 * checks, and invisible, because the body is the {@link WitnessRig} riding
 * on top. The vex is chosen for its voice: the hurt and death sounds a
 * client plays for a mob come from the disguise, and a vex sounds like
 * something that was never alive.
 *
 * The fight is a state machine over {@link Ability}: one cast at a time,
 * each with its own cooldown, more of them unlocked as the health bar drops
 * through {@link ArenaMath#PHASE_TWO_AT} and {@link ArenaMath#PHASE_THREE_AT}.
 * Every cast is telegraphed and every telegraph has a counter -- see the
 * guide book -- because a boss you cannot read is a damage sponge with
 * particles, and this server has enough of those in the mod list already.
 *
 * Damage in is capped per hit and only counted from players ({@link #damage});
 * damage out goes through {@link TrapArena#dread} so every hit it lands
 * builds Groza. Death is handled here rather than by vanilla, which would
 * play the vex's death cry and drop nothing interesting.
 */
public class WitnessEntity extends HostileEntity implements PolymerEntity {
    public static EntityType<WitnessEntity> TYPE;

    /** How long the rise out of the floor takes; nothing can hurt it until then. */
    public static final int INTRO_TICKS = 100;
    private static final float SCALE = 4.1F;
    private static final int PHASE_TICKS = 60;

    public enum Ability {
        SLAM(240), ORBS(180), STARE(400), LIGHTNING(300), MIRRORS(800), BLINK(160), GRIP(450), PHASE(0);

        final int cooldown;

        Ability(int cooldown) {
            this.cooldown = cooldown;
        }

        /** The phase it first appears in. */
        int unlockedAt() {
            return switch (this) {
                case SLAM, ORBS, PHASE -> 1;
                case STARE, LIGHTNING, MIRRORS -> 2;
                case BLINK, GRIP -> 3;
            };
        }
    }

    private static final DustColorTransitionParticleEffect WAVE =
            new DustColorTransitionParticleEffect(0x8a4fd8, 0x07060d, 1.6F);
    private static final DustParticleEffect MARK = new DustParticleEffect(0xffd54a, 1.3F);
    private static final DustParticleEffect BEAM = new DustParticleEffect(0xff2d55, 1.0F);
    private static final DustParticleEffect CHAIN = new DustParticleEffect(0x9a9aa8, 0.8F);

    private WitnessRig rig;
    private int phase = 1;
    private boolean shielded = true;
    private Ability casting;
    private int castTick;
    private int nextCastIn = 60;
    private final Map<Ability, Integer> timers = new EnumMap<>(Ability.class);

    // scratch for the cast in progress
    private final Set<UUID> slamHit = new HashSet<>();
    private final List<Vec3d> marks = new ArrayList<>();
    private final Set<UUID> looked = new HashSet<>();
    private ServerPlayerEntity held;
    private float gripDamage;
    private int gripNeeded;
    private final List<WitnessRig> mirrors = new ArrayList<>();
    private int mirrorTicks;
    private int nextAmbient = 80;

    public WitnessEntity(EntityType<? extends HostileEntity> type, World world) {
        super(type, world);
        setPersistent();
        setInvisible(true);
        for (Ability ability : Ability.values()) {
            timers.put(ability, ability.cooldown / 2);
        }
    }

    public static void register() {
        RegistryKey<EntityType<?>> key = RegistryKey.of(RegistryKeys.ENTITY_TYPE, TrapCraft.id("witness"));
        TYPE = Registry.register(Registries.ENTITY_TYPE, key,
                EntityType.Builder.<WitnessEntity>create(WitnessEntity::new, SpawnGroup.MONSTER)
                        // A vex's box, before the SCALE attribute: what the client
                        // will compute for the disguise has to match this.
                        .dimensions(0.4F, 0.8F)
                        .eyeHeight(0.65F)
                        .makeFireImmune()
                        // Summonable on purpose: /summon trapcraft:witness is how
                        // an op looks at the body without waiting for an omen.
                        // It never saves, so a forgotten one is gone by the next
                        // restart, and /kill takes it out before that.
                        .disableSaving()
                        .maxTrackingRange(12)
                        .build(key));
        PolymerEntityUtils.registerType(TYPE);
        FabricDefaultAttributeRegistry.register(TYPE, attributes());
    }

    private static DefaultAttributeContainer.Builder attributes() {
        return HostileEntity.createHostileAttributes()
                .add(EntityAttributes.MAX_HEALTH, ArenaMath.BASE_HEALTH)
                .add(EntityAttributes.MOVEMENT_SPEED, 0.30)
                .add(EntityAttributes.ATTACK_DAMAGE, ArenaMath.MELEE_DAMAGE)
                .add(EntityAttributes.FOLLOW_RANGE, 64.0)
                .add(EntityAttributes.KNOCKBACK_RESISTANCE, 1.0)
                .add(EntityAttributes.ARMOR, 4.0)
                .add(EntityAttributes.STEP_HEIGHT, 1.0)
                .add(EntityAttributes.SCALE, SCALE);
    }

    /** Size the bar for the room, and start at full. */
    public void sizeFor(int players) {
        getAttributeInstance(EntityAttributes.MAX_HEALTH).setBaseValue(ArenaMath.bossHealth(players));
        setHealth(getMaxHealth());
    }

    // --- the disguise -------------------------------------------------------

    @Override
    public EntityType<?> getPolymerEntityType(PacketContext context) {
        return EntityType.VEX;
    }

    // --- vanilla knobs ------------------------------------------------------

    @Override
    protected void initGoals() {
        goalSelector.add(2, new Strike());
        goalSelector.add(7, new LookAtEntityGoal(this, PlayerEntity.class, 24.0F));
        targetSelector.add(1, new ActiveTargetGoal<>(this, PlayerEntity.class, 10, false, false,
                (entity, world) -> TrapArena.isCombatant(entity)));
    }

    /** Melee, except while the body is busy with something bigger. */
    private class Strike extends MeleeAttackGoal {
        Strike() {
            super(WitnessEntity.this, 1.0, true);
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
            rig.swing(random.nextBoolean());
            sound(SoundEvents.ENTITY_WARDEN_ATTACK_IMPACT, 1.0F, 1.3F);
            sound(SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, 0.8F, 0.7F);
            Vec3d at = target.getPos().add(0.0, target.getHeight() * 0.5, 0.0);
            world.spawnParticles(ParticleTypes.SWEEP_ATTACK, at.x, at.y, at.z, 2, 0.3, 0.2, 0.3, 0.0);
            if (target instanceof ServerPlayerEntity player) {
                TrapArena.dread(player, 1);
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

    @Override
    protected void updatePostDeath() {
        deathTime++;
        if (deathTime == 1) {
            sound(SoundEvents.ENTITY_WITHER_DEATH, 1.6F, 0.6F);
            sound(SoundEvents.ENTITY_WARDEN_DEATH, 1.2F, 1.4F);
        }
        if (getWorld() instanceof ServerWorld world) {
            Vec3d at = getPos().add(0.0, 2.0, 0.0);
            world.spawnParticles(ParticleTypes.SOUL, at.x, at.y, at.z, 4, 0.8, 1.0, 0.8, 0.02);
            if (deathTime % 10 == 0) {
                world.spawnParticles(ParticleTypes.TOTEM_OF_UNDYING, at.x, at.y + 0.5, at.z, 30, 0.6, 0.8, 0.6, 0.4);
                sound(SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, 1.0F, 0.6F + deathTime * 0.01F);
            }
            if (deathTime == 60) {
                world.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, at.x, at.y, at.z, 2, 0.5, 0.5, 0.5, 0.0);
                world.spawnParticles(ParticleTypes.FLASH, at.x, at.y, at.z, 3, 0.3, 0.3, 0.3, 0.0);
                sound(SoundEvents.ENTITY_GENERIC_EXPLODE.value(), 2.0F, 0.5F);
                TrapArena.victoryBurst(this);
            }
        }
        if (deathTime >= WitnessRig.DEATH_TICKS) {
            discard();
        }
    }

    @Override
    public void onDeath(DamageSource source) {
        if (dead) {
            return;
        }
        // Not super: vanilla would play the vex's death cry, drop nothing worth
        // having and remove the body in twenty ticks. The rig's own death
        // runs from updatePostDeath and the loot from TrapArena.
        dead = true;
        casting = null;
        releaseGrip(false);
        clearMirrors();
        getNavigation().stop();
        if (rig != null) {
            rig.setStare(false);
            rig.startDeath();
        }
        TrapArena.onBossDeath(this);
    }

    @Override
    public void onRemoved() {
        super.onRemoved();
        clearMirrors();
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
            player.sendMessage(Text.literal("Jesteś w nokaucie. Świadek cię nie widzi.")
                    .formatted(Formatting.RED), true);
            return false;
        }
        float before = getHealth();
        float capped = Math.min(amount, ArenaMath.hitCap(getMaxHealth()));
        boolean hurt = super.damage(world, source, capped);
        if (!hurt) {
            return false;
        }
        float dealt = Math.max(0.0F, before - getHealth());
        TrapArena.recordDamage(player, dealt);
        if (held != null) {
            gripDamage += dealt;
        }
        rig.hurt();
        sound(SoundEvents.ENTITY_WARDEN_HURT, 0.7F, 1.5F);
        sound(SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 0.6F, 0.7F + random.nextFloat() * 0.4F);
        Vec3d at = getPos().add(0.0, 2.2, 0.0);
        world.spawnParticles(ParticleTypes.WITCH, at.x, at.y, at.z, 6, 0.5, 0.7, 0.5, 0.05);

        int now = ArenaMath.phaseOf(getHealth() / getMaxHealth());
        if (now > phase && casting != Ability.PHASE && !isDead()) {
            begin(Ability.PHASE);
        }
        return true;
    }

    /** A parried eye came home. Uncapped: this is the best hit in the room. */
    public void parried(ServerPlayerEntity player, WitnessEyeEntity orb) {
        if (shielded || isDead() || player == null) {
            return;
        }
        float before = getHealth();
        super.damage((ServerWorld) getWorld(),
                getWorld().getDamageSources().indirectMagic(orb, player), ArenaMath.PARRY_DAMAGE);
        TrapArena.recordDamage(player, Math.max(0.0F, before - getHealth()));
        if (held != null) {
            gripDamage += Math.max(0.0F, before - getHealth());
        }
        rig.hurt();
        sound(SoundEvents.ENTITY_ELDER_GUARDIAN_HURT, 1.2F, 1.3F);
        sound(SoundEvents.ENTITY_PLAYER_ATTACK_CRIT, 1.0F, 0.8F);
        Vec3d at = getPos().add(0.0, 2.6, 0.0);
        ((ServerWorld) getWorld()).spawnParticles(ParticleTypes.ENCHANTED_HIT, at.x, at.y, at.z, 24,
                0.6, 0.6, 0.6, 0.3);
        TrapNet.shake(player, 0.5F, 6);
        int now = ArenaMath.phaseOf(getHealth() / getMaxHealth());
        if (now > phase && casting != Ability.PHASE && !isDead()) {
            begin(Ability.PHASE);
        }
    }

    // --- the tick -----------------------------------------------------------

    @Override
    protected void mobTick(ServerWorld world) {
        super.mobTick(world);
        if (rig == null) {
            rig = WitnessRig.attachTo(this);
            rig.setRise(-3.6F);
        }
        rig.setYaw(getYaw());
        if (isDead()) {
            return;
        }
        if (TrapArena.stage() != TrapArena.Stage.FIGHT) {
            return;
        }

        if (age < INTRO_TICKS) {
            intro(world);
            return;
        }
        if (age == INTRO_TICKS) {
            shielded = false;
            rig.setRise(0.0F);
            TrapArena.fightBegins(this);
        }

        keepInThePit();
        breathe(world);

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

        tickMirrors(world);

        if (casting != null) {
            tickCast(world);
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
        for (Ability ability : Ability.values()) {
            if (ability != Ability.PHASE && ability.unlockedAt() <= phase && timers.get(ability) <= 0) {
                ready.add(ability);
            }
        }
        if (!ready.isEmpty()) {
            begin(ready.get(random.nextInt(ready.size())));
        } else {
            nextCastIn = 20;
        }
    }

    /** Rising out of the sigil. */
    private void intro(ServerWorld world) {
        getNavigation().stop();
        float p = age / (float) INTRO_TICKS;
        float eased = 1.0F - (1.0F - p) * (1.0F - p);
        rig.setRise(-3.6F * (1.0F - eased));
        Vec3d at = getPos();
        world.spawnParticles(ParticleTypes.REVERSE_PORTAL, at.x, at.y + 0.2, at.z, 12, 1.4, 0.3, 1.4, 0.4);
        world.spawnParticles(ParticleTypes.SOUL_FIRE_FLAME, at.x, at.y + 0.1, at.z, 3, 1.2, 0.1, 1.2, 0.02);
        if (age == 1) {
            sound(SoundEvents.BLOCK_END_PORTAL_SPAWN, 1.5F, 0.6F);
            sound(SoundEvents.ENTITY_WARDEN_EMERGE, 1.5F, 0.7F);
        }
        if (age == 40) {
            sound(SoundEvents.ENTITY_WITHER_SPAWN, 1.5F, 0.55F);
        }
        if (age % 10 == 0) {
            for (ServerPlayerEntity player : TrapArena.arenaPlayers()) {
                TrapNet.shake(player, 0.35F + 0.5F * p, 10);
            }
        }
    }

    /** The idle noises and wisps that make it read as alive between casts. */
    private void breathe(ServerWorld world) {
        Vec3d at = getPos();
        if (age % 2 == 0) {
            world.spawnParticles(ParticleTypes.SOUL, at.x, at.y + 0.3, at.z, 1, 0.7, 0.2, 0.7, 0.01);
        }
        if (age % 7 == 0) {
            world.spawnParticles(ParticleTypes.REVERSE_PORTAL, at.x, at.y + 1.0, at.z, 2, 0.8, 0.8, 0.8, 0.05);
        }
        if (--nextAmbient <= 0) {
            nextAmbient = 80 + random.nextInt(80);
            switch (random.nextInt(3)) {
                case 0 -> sound(SoundEvents.ENTITY_WARDEN_HEARTBEAT, 1.2F, 0.7F);
                case 1 -> sound(SoundEvents.AMBIENT_CAVE.value(), 0.8F, 0.55F);
                default -> sound(SoundEvents.ENTITY_ELDER_GUARDIAN_AMBIENT, 0.5F, 0.6F);
            }
        }
    }

    /** It fights in the pit. Anything that got it out puts it back. */
    private void keepInThePit() {
        Vec3d centre = TrapArena.CENTRE;
        double dx = getX() - centre.x;
        double dz = getZ() - centre.z;
        double r = Math.sqrt(dx * dx + dz * dz);
        if (r > TrapArena.PIT_RADIUS - 1.0 || getY() < TrapArena.ORIGIN.getY() - 1
                || getY() > TrapArena.ORIGIN.getY() + 8) {
            double k = r > 0.001 ? Math.min(1.0, (TrapArena.PIT_RADIUS - 3.0) / r) : 0.0;
            refreshPositionAndAngles(centre.x + dx * k, TrapArena.ORIGIN.getY() + 1, centre.z + dz * k,
                    getYaw(), getPitch());
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
        if (casting == Ability.GRIP) {
            releaseGrip(false);
        }
        casting = ability;
        castTick = 0;
        timers.put(ability, ability.cooldown);
        nextCastIn = 50 + random.nextInt(50);
        getNavigation().stop();
        slamHit.clear();
        marks.clear();
        looked.clear();
    }

    public Ability casting() {
        return casting;
    }

    public int phase() {
        return phase;
    }

    private void tickCast(ServerWorld world) {
        castTick++;
        boolean done = switch (casting) {
            case SLAM -> slam(world);
            case ORBS -> orbs(world);
            case STARE -> stare(world);
            case LIGHTNING -> lightning(world);
            case MIRRORS -> mirrorsCast(world);
            case BLINK -> blink(world);
            case GRIP -> grip(world);
            case PHASE -> phaseChange(world);
        };
        if (done) {
            casting = null;
        }
    }

    /** Fala: rise, slam, a ring that only catches feet on the ground. */
    private boolean slam(ServerWorld world) {
        if (castTick <= 12) {
            rig.setRise(castTick * 0.1F);
            if (castTick == 1) {
                sound(SoundEvents.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.5F, 0.6F);
                sound(SoundEvents.ENTITY_RAVAGER_ROAR, 1.0F, 0.6F);
            }
            return false;
        }
        if (castTick == 13) {
            rig.setRise(0.0F);
            Vec3d at = getPos();
            sound(SoundEvents.ENTITY_GENERIC_EXPLODE.value(), 1.6F, 0.7F);
            sound(SoundEvents.ENTITY_RAVAGER_STUNNED, 1.2F, 0.8F);
            sound(SoundEvents.ITEM_MACE_SMASH_GROUND_HEAVY, 1.5F, 0.8F);
            world.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, at.x, at.y + 0.2, at.z, 1, 0, 0, 0, 0);
            for (ServerPlayerEntity player : TrapArena.arenaPlayers()) {
                TrapNet.shake(player, 1.3F, 12);
            }
            return false;
        }
        double radius = (castTick - 13) * 1.0;
        if (radius > 15.0) {
            return true;
        }
        Vec3d at = getPos();
        int points = (int) (radius * 5.0);
        BlockStateParticleEffect dust = new BlockStateParticleEffect(ParticleTypes.BLOCK,
                Blocks.POLISHED_BLACKSTONE.getDefaultState());
        for (int i = 0; i < points; i++) {
            double angle = i * Math.PI * 2 / points;
            double x = at.x + Math.cos(angle) * radius;
            double z = at.z + Math.sin(angle) * radius;
            world.spawnParticles(WAVE, x, at.y + 0.4, z, 1, 0.1, 0.2, 0.1, 0.0);
            if (i % 3 == 0) {
                world.spawnParticles(dust, x, at.y + 0.3, z, 2, 0.2, 0.2, 0.2, 0.1);
            }
            if (i % 5 == 0) {
                world.spawnParticles(ParticleTypes.CLOUD, x, at.y + 0.2, z, 1, 0.1, 0.1, 0.1, 0.02);
            }
        }
        for (ServerPlayerEntity player : TrapArena.combatants()) {
            if (slamHit.contains(player.getUuid())) {
                continue;
            }
            double d = Math.sqrt(player.squaredDistanceTo(at.x, player.getY(), at.z));
            if (d < radius - 1.1 || d > radius + 0.3) {
                continue;
            }
            slamHit.add(player.getUuid());
            if (!player.isOnGround()) {
                player.sendMessage(Text.literal("Przeskoczone.").formatted(Formatting.AQUA), true);
                continue;
            }
            player.damage(world, world.getDamageSources().mobAttack(this), ArenaMath.SLAM_DAMAGE);
            Vec3d push = player.getPos().subtract(at).normalize();
            player.setVelocity(push.x * 0.6, 0.75, push.z * 0.6);
            player.velocityModified = true;
            TrapArena.dread(player, 1);
            TrapNet.shake(player, 1.0F, 10);
            sound(player, SoundEvents.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.0F, 0.7F);
        }
        return false;
    }

    /** Oczy: three eyes, three targets, punchable. */
    private boolean orbs(ServerWorld world) {
        if (castTick == 1) {
            sound(SoundEvents.ENTITY_ALLAY_ITEM_THROWN, 1.2F, 0.5F);
            rig.hurt();
        }
        if (castTick == 5 || castTick == 12 || castTick == 19) {
            ServerPlayerEntity target = pickTarget();
            if (target == null) {
                return true;
            }
            sound(SoundEvents.ENTITY_SHULKER_SHOOT, 1.0F, 0.8F + castTick * 0.02F);
            WitnessEyeEntity.launch(world, this, rig.eyePos(), target);
            Vec3d eye = rig.eyePos();
            world.spawnParticles(ParticleTypes.WITCH, eye.x, eye.y, eye.z, 8, 0.2, 0.2, 0.2, 0.1);
        }
        return castTick >= 26;
    }

    /** Spojrzenie: don't look. */
    private boolean stare(ServerWorld world) {
        getNavigation().stop();
        if (getTarget() != null) {
            getLookControl().lookAt(getTarget(), 30.0F, 30.0F);
        }
        if (castTick == 1) {
            rig.setStare(true);
            sound(SoundEvents.BLOCK_SCULK_SHRIEKER_SHRIEK, 1.5F, 0.7F);
            sound(SoundEvents.ENTITY_WARDEN_TENDRIL_CLICKS, 1.5F, 0.8F);
            for (ServerPlayerEntity player : TrapArena.arenaPlayers()) {
                TrapArena.title(player, Text.literal("NIE PATRZ").formatted(Formatting.RED, Formatting.BOLD),
                        Text.literal("Odwróć wzrok.").formatted(Formatting.GRAY), 5, 25, 10);
                sound(player, SoundEvents.ENTITY_WARDEN_LISTENING, 1.0F, 0.6F);
            }
            return false;
        }
        int end = 10 + ArenaMath.STARE_TICKS;
        if (castTick >= end) {
            rig.setStare(false);
            for (ServerPlayerEntity player : TrapArena.combatants()) {
                if (!looked.contains(player.getUuid())) {
                    TrapAwards.grant(player, "stare");
                }
            }
            return true;
        }
        if (castTick < 10) {
            return false;
        }
        Vec3d eye = rig.eyePos();
        if (castTick % 4 == 0) {
            world.spawnParticles(BEAM, eye.x, eye.y, eye.z, 6, 0.3, 0.3, 0.3, 0.02);
        }
        for (ServerPlayerEntity player : TrapArena.combatants()) {
            Vec3d toEye = eye.subtract(player.getEyePos());
            double distance = toEye.length();
            if (distance < 0.5) {
                continue;
            }
            double dot = player.getRotationVec(1.0F).dotProduct(toEye.multiply(1.0 / distance));
            if (dot < Math.cos(Math.toRadians(22.0))) {
                continue;
            }
            // Looking. The beam is drawn every tick so it reads as a line, the
            // bite lands every half second.
            for (int i = 1; i < 8; i++) {
                Vec3d p = player.getEyePos().add(toEye.multiply(i / 8.0));
                world.spawnParticles(BEAM, p.x, p.y, p.z, 1, 0.0, 0.0, 0.0, 0.0);
            }
            if (castTick % 10 == 0) {
                looked.add(player.getUuid());
                player.damage(world, world.getDamageSources().create(DamageTypes.MAGIC, this),
                        ArenaMath.STARE_DAMAGE);
                TrapArena.dread(player, 1);
                world.spawnParticles(player, ParticleTypes.ELDER_GUARDIAN, true, false,
                        player.getX(), player.getY(), player.getZ(), 1, 0, 0, 0, 0);
                sound(player, SoundEvents.ENTITY_ELDER_GUARDIAN_CURSE, 0.6F, 1.2F);
                TrapNet.flash(player, 0xff2d55, 8);
            }
        }
        return false;
    }

    /** Piorun: circles, then bolts. Step out. */
    private boolean lightning(ServerWorld world) {
        if (castTick == 1) {
            for (ServerPlayerEntity player : TrapArena.combatants()) {
                marks.add(player.getPos());
            }
            for (int i = 0; i < 2; i++) {
                double angle = random.nextDouble() * Math.PI * 2;
                double r = 3.0 + random.nextDouble() * 14.0;
                marks.add(new Vec3d(TrapArena.CENTRE.x + Math.cos(angle) * r, TrapArena.ORIGIN.getY() + 1,
                        TrapArena.CENTRE.z + Math.sin(angle) * r));
            }
            sound(SoundEvents.ENTITY_EVOKER_PREPARE_ATTACK, 1.5F, 0.7F);
            sound(SoundEvents.BLOCK_BEACON_POWER_SELECT, 1.0F, 0.5F);
            return false;
        }
        if (castTick < 32) {
            for (Vec3d mark : marks) {
                for (int i = 0; i < 14; i++) {
                    double angle = i * Math.PI * 2 / 14 + castTick * 0.15;
                    world.spawnParticles(MARK, mark.x + Math.cos(angle) * 2.5, mark.y + 0.15,
                            mark.z + Math.sin(angle) * 2.5, 1, 0.0, 0.0, 0.0, 0.0);
                }
                if (castTick % 4 == 0) {
                    world.spawnParticles(ParticleTypes.ELECTRIC_SPARK, mark.x, mark.y + 0.5, mark.z, 3,
                            0.8, 0.4, 0.8, 0.1);
                }
            }
            return false;
        }
        if (castTick == 32) {
            for (Vec3d mark : marks) {
                LightningEntity bolt = new LightningEntity(EntityType.LIGHTNING_BOLT, world);
                bolt.setCosmetic(true);
                bolt.refreshPositionAndAngles(mark.x, mark.y, mark.z, 0.0F, 0.0F);
                world.spawnEntity(bolt);
                world.spawnParticles(ParticleTypes.FLASH, mark.x, mark.y + 1.0, mark.z, 1, 0, 0, 0, 0);
                for (ServerPlayerEntity player : TrapArena.combatants()) {
                    double dx = player.getX() - mark.x;
                    double dz = player.getZ() - mark.z;
                    if (dx * dx + dz * dz > 2.6 * 2.6 || Math.abs(player.getY() - mark.y) > 3.0) {
                        continue;
                    }
                    player.damage(world, world.getDamageSources().create(DamageTypes.LIGHTNING_BOLT, this),
                            ArenaMath.LIGHTNING_DAMAGE);
                    TrapArena.dread(player, 1);
                    TrapNet.flash(player, 0xffffff, 6);
                    TrapNet.shake(player, 0.8F, 8);
                }
            }
            return false;
        }
        return castTick >= 40;
    }

    /** Lustra: three of it, one real. The real one has eyes. */
    private boolean mirrorsCast(ServerWorld world) {
        clearMirrors();
        sound(SoundEvents.ENTITY_ILLUSIONER_PREPARE_MIRROR, 1.5F, 0.8F);
        sound(SoundEvents.ENTITY_ILLUSIONER_CAST_SPELL, 1.2F, 0.6F);
        Vec3d at = getPos();
        world.spawnParticles(ParticleTypes.WITCH, at.x, at.y + 1.5, at.z, 40, 0.8, 1.2, 0.8, 0.2);
        double base = random.nextDouble() * Math.PI * 2;
        int real = random.nextInt(3);
        for (int i = 0; i < 3; i++) {
            double angle = base + i * Math.PI * 2 / 3;
            Vec3d spot = new Vec3d(TrapArena.CENTRE.x + Math.cos(angle) * 7.0, TrapArena.ORIGIN.getY() + 1,
                    TrapArena.CENTRE.z + Math.sin(angle) * 7.0);
            float yaw = (float) Math.toDegrees(Math.atan2(-(spot.x - TrapArena.CENTRE.x),
                    spot.z - TrapArena.CENTRE.z)) + 180.0F;
            if (i == real) {
                refreshPositionAndAngles(spot.x, spot.y, spot.z, yaw, 0.0F);
                setBodyYaw(yaw);
                setHeadYaw(yaw);
            } else {
                WitnessRig[] slot = new WitnessRig[1];
                slot[0] = WitnessRig.mirror(world, spot, yaw, () -> shatter(world, slot[0], spot));
                mirrors.add(slot[0]);
            }
            world.spawnParticles(ParticleTypes.REVERSE_PORTAL, spot.x, spot.y + 1.5, spot.z, 30, 0.6, 1.2, 0.6, 0.3);
        }
        mirrorTicks = 20 * 8;
        return true;
    }

    private void shatter(ServerWorld world, WitnessRig mirror, Vec3d spot) {
        if (!mirrors.remove(mirror)) {
            return;
        }
        mirror.destroy();
        world.playSound(null, spot.x, spot.y + 1.5, spot.z, SoundEvents.BLOCK_GLASS_BREAK,
                SoundCategory.HOSTILE, 1.4F, 0.6F);
        world.playSound(null, spot.x, spot.y + 1.5, spot.z, SoundEvents.ENTITY_ILLUSIONER_MIRROR_MOVE,
                SoundCategory.HOSTILE, 1.0F, 1.2F);
        world.spawnParticles(ParticleTypes.WITCH, spot.x, spot.y + 1.5, spot.z, 40, 0.7, 1.4, 0.7, 0.15);
        world.spawnParticles(ParticleTypes.LARGE_SMOKE, spot.x, spot.y + 1.0, spot.z, 20, 0.6, 1.0, 0.6, 0.05);
    }

    private void tickMirrors(ServerWorld world) {
        if (mirrors.isEmpty()) {
            return;
        }
        if (--mirrorTicks <= 0) {
            for (WitnessRig mirror : mirrors) {
                Vec3d spot = mirror.getPos();
                world.spawnParticles(ParticleTypes.LARGE_SMOKE, spot.x, spot.y + 1.0, spot.z, 20, 0.6, 1.0, 0.6, 0.05);
                mirror.destroy();
            }
            mirrors.clear();
            sound(SoundEvents.ENTITY_ILLUSIONER_MIRROR_MOVE, 1.0F, 0.7F);
        }
    }

    private void clearMirrors() {
        for (WitnessRig mirror : mirrors) {
            mirror.destroy();
        }
        mirrors.clear();
    }

    /** Skok: gone, and behind somebody. */
    private boolean blink(ServerWorld world) {
        if (castTick <= 4) {
            rig.setCollapse(castTick / 4.0F);
            if (castTick == 1) {
                sound(SoundEvents.ENTITY_ENDERMAN_TELEPORT, 1.5F, 0.5F);
                Vec3d at = getPos();
                world.spawnParticles(ParticleTypes.REVERSE_PORTAL, at.x, at.y + 1.5, at.z, 40, 0.6, 1.2, 0.6, 0.4);
            }
            return false;
        }
        if (castTick == 5) {
            ServerPlayerEntity mark = pickTarget();
            Vec3d spot;
            float yaw;
            if (mark == null) {
                spot = TrapArena.CENTRE;
                yaw = getYaw();
            } else {
                Vec3d behind = mark.getPos().subtract(mark.getRotationVec(1.0F).multiply(1, 0, 1).normalize().multiply(2.6));
                double dx = behind.x - TrapArena.CENTRE.x;
                double dz = behind.z - TrapArena.CENTRE.z;
                double r = Math.sqrt(dx * dx + dz * dz);
                if (r > TrapArena.PIT_RADIUS - 2.5) {
                    double k = (TrapArena.PIT_RADIUS - 2.5) / r;
                    behind = new Vec3d(TrapArena.CENTRE.x + dx * k, behind.y, TrapArena.CENTRE.z + dz * k);
                }
                spot = new Vec3d(behind.x, TrapArena.ORIGIN.getY() + 1, behind.z);
                yaw = (float) Math.toDegrees(Math.atan2(-(mark.getX() - spot.x), mark.getZ() - spot.z));
                setTarget(mark);
            }
            refreshPositionAndAngles(spot.x, spot.y, spot.z, yaw, 0.0F);
            setBodyYaw(yaw);
            setHeadYaw(yaw);
            sound(SoundEvents.ENTITY_ENDERMAN_TELEPORT, 1.5F, 0.4F);
            world.spawnParticles(ParticleTypes.PORTAL, spot.x, spot.y + 1.5, spot.z, 60, 0.7, 1.4, 0.7, 0.6);
            return false;
        }
        rig.setCollapse(1.0F - (castTick - 5) / 4.0F);
        return castTick >= 9;
    }

    /** Uścisk: one of you in its hand, the rest of you hitting it. */
    private boolean grip(ServerWorld world) {
        if (castTick == 1) {
            List<ServerPlayerEntity> near = new ArrayList<>();
            for (ServerPlayerEntity player : TrapArena.combatants()) {
                if (player.squaredDistanceTo(this) < 12.0 * 12.0) {
                    near.add(player);
                }
            }
            if (near.isEmpty()) {
                return true;
            }
            held = near.get(random.nextInt(near.size()));
            gripDamage = 0.0F;
            gripNeeded = ArenaMath.gripRelease(TrapArena.combatants().size());
            rig.setGripping(true);
            getLookControl().lookAt(held, 30.0F, 30.0F);
            setTarget(held);
            // Levitation, not for the lift -- the pull below does that -- but
            // because the server's flying check disconnects anybody held off
            // the ground for four seconds, and levitation is on its exemption
            // list. Amplifier zero is a fifth of a block a second, invisible
            // under the pull, and it comes off with the hand.
            held.addStatusEffect(new StatusEffectInstance(StatusEffects.LEVITATION,
                    ArenaMath.GRIP_TICKS + 40, 0, true, false, false));
            sound(SoundEvents.ENTITY_PHANTOM_SWOOP, 1.5F, 0.6F);
            sound(SoundEvents.BLOCK_CHAIN_PLACE, 1.5F, 0.5F);
            TrapArena.title(held, Text.literal("UŚCISK").formatted(Formatting.DARK_PURPLE, Formatting.BOLD),
                    Text.literal("Niech cię wyciągną.").formatted(Formatting.GRAY), 5, 30, 10);
            return false;
        }
        if (held == null || !held.isAlive() || TrapArena.isKnockedOut(held) || held.isDisconnected()
                || held.getWorld() != world) {
            releaseGrip(false);
            return true;
        }
        getNavigation().stop();
        getLookControl().lookAt(held, 30.0F, 30.0F);
        Vec3d hand = rig.handPos();
        Vec3d pull = hand.subtract(held.getPos()).multiply(0.45);
        held.setVelocity(pull.x, pull.y, pull.z);
        held.velocityModified = true;
        held.fallDistance = 0.0;
        for (int i = 1; i < 6; i++) {
            Vec3d p = getPos().add(0.0, 1.7, 0.0).add(hand.subtract(getPos().add(0.0, 1.7, 0.0)).multiply(i / 6.0));
            world.spawnParticles(CHAIN, p.x, p.y, p.z, 1, 0, 0, 0, 0);
        }
        if (castTick % 20 == 0) {
            held.damage(world, world.getDamageSources().mobAttack(this), 1.0F);
            sound(SoundEvents.BLOCK_CHAIN_PLACE, 0.8F, 0.4F);
        }
        if (castTick % 5 == 0) {
            Text bar = Text.literal("UWOLNIJ ").formatted(Formatting.LIGHT_PURPLE, Formatting.BOLD)
                    .append(Text.literal(held.getNameForScoreboard() + ": ").formatted(Formatting.WHITE))
                    .append(Text.literal(Math.round(gripDamage) + "/" + gripNeeded).formatted(Formatting.AQUA));
            for (ServerPlayerEntity player : TrapArena.arenaPlayers()) {
                player.sendMessage(bar, true);
            }
        }
        if (gripDamage >= gripNeeded) {
            releaseGrip(true);
            return true;
        }
        if (castTick >= ArenaMath.GRIP_TICKS) {
            held.damage(world, world.getDamageSources().mobAttack(this), ArenaMath.GRIP_SLAM_DAMAGE);
            held.setVelocity(0.0, -0.8, 0.0);
            held.velocityModified = true;
            TrapArena.dread(held, 2);
            TrapNet.shake(held, 1.2F, 10);
            sound(SoundEvents.ENTITY_WARDEN_ATTACK_IMPACT, 1.5F, 0.6F);
            releaseGrip(false);
            return true;
        }
        return false;
    }

    private void releaseGrip(boolean freed) {
        if (rig != null) {
            rig.setGripping(false);
        }
        if (held == null) {
            return;
        }
        ServerPlayerEntity victim = held;
        held = null;
        victim.removeStatusEffect(StatusEffects.LEVITATION);
        if (freed && victim.isAlive() && getWorld() instanceof ServerWorld world) {
            Vec3d away = victim.getPos().subtract(getPos()).multiply(1, 0, 1).normalize();
            victim.setVelocity(away.x * 0.8, 0.45, away.z * 0.8);
            victim.velocityModified = true;
            sound(SoundEvents.BLOCK_CHAIN_BREAK, 1.5F, 0.8F);
            sound(SoundEvents.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.0F, 1.2F);
            world.spawnParticles(ParticleTypes.CRIT, victim.getX(), victim.getEyeY(), victim.getZ(), 20,
                    0.4, 0.4, 0.4, 0.3);
            Text line = Text.literal("Wyrwany. ").formatted(Formatting.AQUA, Formatting.BOLD)
                    .append(Text.literal(victim.getNameForScoreboard() + " jest wolny.").formatted(Formatting.GRAY));
            for (ServerPlayerEntity player : TrapArena.arenaPlayers()) {
                player.sendMessage(line, true);
            }
        }
    }

    /** The phase flourish: untouchable for three seconds, and everything changes. */
    private boolean phaseChange(ServerWorld world) {
        if (castTick == 1) {
            phase++;
            shielded = true;
            releaseGrip(false);
            rig.setPhase(phase);
            rig.flourish();
            sound(SoundEvents.ENTITY_WITHER_AMBIENT, 1.8F, 0.5F);
            sound(SoundEvents.BLOCK_BEACON_POWER_SELECT, 1.5F, 0.6F);
            sound(SoundEvents.ENTITY_WARDEN_ROAR, 1.2F, 0.8F);
            Vec3d at = getPos();
            world.spawnParticles(ParticleTypes.SONIC_BOOM, at.x, at.y + 2.0, at.z, 1, 0, 0, 0, 0);
            TrapArena.onPhase(this, phase);
            for (Ability ability : Ability.values()) {
                if (ability.unlockedAt() == phase) {
                    timers.put(ability, 20 + random.nextInt(40));
                }
            }
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
            if (phase == 2) {
                timers.put(Ability.MIRRORS, 5);
            }
            return true;
        }
        return false;
    }

    // --- helpers --------------------------------------------------------------

    private void sound(SoundEvent event, float volume, float pitch) {
        getWorld().playSound(null, getX(), getY() + 1.5, getZ(), event, SoundCategory.HOSTILE, volume, pitch);
    }

    private static void sound(ServerPlayerEntity player, SoundEvent event, float volume, float pitch) {
        player.playSoundToPlayer(event, SoundCategory.HOSTILE, volume, pitch);
    }

    public boolean shielded() {
        return shielded;
    }
}
