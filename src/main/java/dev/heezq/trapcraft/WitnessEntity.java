package dev.heezq.trapcraft;

import eu.pb4.polymer.core.api.entity.PolymerEntityUtils;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.HostileEntity;
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
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Obserwator. The watcher.
 *
 * Disguised as a vex scaled up four times, for its box and its voice: the
 * hurt and death sounds a client plays for a mob come from the disguise, and
 * a vex sounds like something that was never alive.
 *
 * Seven casts across three phases, every one telegraphed and every one with
 * a counter the guide book spells out: jump the wave, punch the eyes back,
 * look away from the stare, step out of the circle, find the one with the
 * orbiting eyes, keep moving, and pull your friend out of its hand.
 */
public class WitnessEntity extends ArenaBossEntity {
    public static EntityType<WitnessEntity> TYPE;

    private static final float SCALE = 4.1F;

    static final Ability SLAM = new Ability("slam", 240, 1);
    static final Ability ORBS = new Ability("orbs", 180, 1);
    static final Ability STARE = new Ability("stare", 400, 2);
    static final Ability LIGHTNING = new Ability("lightning", 300, 2);
    static final Ability MIRRORS = new Ability("mirrors", 800, 2);
    static final Ability BLINK = new Ability("blink", 160, 3);
    static final Ability GRIP = new Ability("grip", 450, 3);
    private static final List<Ability> ABILITIES = List.of(SLAM, ORBS, STARE, LIGHTNING, MIRRORS, BLINK, GRIP);
    public static final List<String> ABILITY_IDS = ABILITIES.stream().map(Ability::id).toList();

    private static final DustColorTransitionParticleEffect WAVE =
            new DustColorTransitionParticleEffect(0x8a4fd8, 0x07060d, 1.6F);
    private static final DustParticleEffect MARK = new DustParticleEffect(0xffd54a, 1.3F);
    private static final DustParticleEffect BEAM = new DustParticleEffect(0xff2d55, 1.0F);
    private static final DustParticleEffect CHAIN = new DustParticleEffect(0x9a9aa8, 0.8F);

    private static final ArenaProjectileEntity.Spec EYE = new ArenaProjectileEntity.Spec(
            TrapCraft.id("witness_orb"), TrapCraft.id("witness_orb_cyan"), 0.30F, true, true,
            ArenaMath.ORB_DAMAGE, 0x8a4fd8, 140, SoundEvents.ENTITY_PHANTOM_BITE);

    private final Set<UUID> slamHit = new HashSet<>();
    private final List<Vec3d> marks = new ArrayList<>();
    private final Set<UUID> looked = new HashSet<>();
    private ServerPlayerEntity held;
    private float gripDamage;
    private int gripNeeded;
    private final List<WitnessRig> mirrors = new ArrayList<>();
    private int mirrorTicks;

    public WitnessEntity(EntityType<? extends HostileEntity> type, World world) {
        super(type, world);
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

    // --- the base's questions -------------------------------------------------

    @Override
    public ArenaBoss kind() {
        return WitnessBoss.INSTANCE;
    }

    @Override
    protected List<Ability> abilities() {
        return ABILITIES;
    }

    @Override
    protected DisplayRig makeRig() {
        return WitnessRig.attachTo(this);
    }

    private WitnessRig body() {
        return (WitnessRig) rig;
    }

    @Override
    protected EntityType<?> disguise() {
        return EntityType.VEX;
    }

    @Override
    protected void afflict(ServerPlayerEntity player, int stacks) {
        WitnessBoss.dread(player, stacks);
    }

    @Override
    protected void introTick(ServerWorld world, float progress) {
        super.introTick(world, progress);
        Vec3d at = getPos();
        world.spawnParticles(ParticleTypes.SOUL_FIRE_FLAME, at.x, at.y + 0.1, at.z, 3, 1.2, 0.1, 1.2, 0.02);
        if (age == 1) {
            sound(SoundEvents.ENTITY_WARDEN_EMERGE, 1.5F, 0.7F);
        }
        if (age == 40) {
            sound(SoundEvents.ENTITY_WITHER_SPAWN, 1.5F, 0.55F);
        }
    }

    @Override
    protected void breathe(ServerWorld world) {
        Vec3d at = getPos();
        if (age % 2 == 0) {
            world.spawnParticles(ParticleTypes.SOUL, at.x, at.y + 0.3, at.z, 1, 0.7, 0.2, 0.7, 0.01);
        }
        if (age % 7 == 0) {
            world.spawnParticles(ParticleTypes.REVERSE_PORTAL, at.x, at.y + 1.0, at.z, 2, 0.8, 0.8, 0.8, 0.05);
        }
        tickMirrors(world);
    }

    @Override
    protected SoundEvent ambient(int roll) {
        return switch (roll) {
            case 0 -> SoundEvents.ENTITY_WARDEN_HEARTBEAT;
            case 1 -> SoundEvents.AMBIENT_CAVE.value();
            default -> SoundEvents.ENTITY_ELDER_GUARDIAN_AMBIENT;
        };
    }

    @Override
    protected void onStrike(ServerWorld world, Entity target) {
        body().swing(random.nextBoolean());
        sound(SoundEvents.ENTITY_WARDEN_ATTACK_IMPACT, 1.0F, 1.3F);
        sound(SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, 0.8F, 0.7F);
        Vec3d at = target.getPos().add(0.0, target.getHeight() * 0.5, 0.0);
        world.spawnParticles(ParticleTypes.SWEEP_ATTACK, at.x, at.y, at.z, 2, 0.3, 0.2, 0.3, 0.0);
        if (target instanceof ServerPlayerEntity player) {
            afflict(player, 1);
        }
    }

    @Override
    protected void onHurt(ServerWorld world, ServerPlayerEntity by, float dealt) {
        if (held != null) {
            gripDamage += dealt;
        }
        sound(SoundEvents.ENTITY_WARDEN_HURT, 0.7F, 1.5F);
        sound(SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 0.6F, 0.7F + random.nextFloat() * 0.4F);
        Vec3d at = getPos().add(0.0, 2.2, 0.0);
        world.spawnParticles(ParticleTypes.WITCH, at.x, at.y, at.z, 6, 0.5, 0.7, 0.5, 0.05);
    }

    /** A parried eye came home. Uncapped: this is the best hit in the room. */
    @Override
    public void parried(ServerPlayerEntity player, ArenaProjectileEntity orb) {
        if (player == null || !(getWorld() instanceof ServerWorld world)) {
            return;
        }
        takeUncapped(world, world.getDamageSources().indirectMagic(orb, player), ArenaMath.PARRY_DAMAGE, player);
        sound(SoundEvents.ENTITY_ELDER_GUARDIAN_HURT, 1.2F, 1.3F);
        sound(SoundEvents.ENTITY_PLAYER_ATTACK_CRIT, 1.0F, 0.8F);
        Vec3d at = getPos().add(0.0, 2.6, 0.0);
        world.spawnParticles(ParticleTypes.ENCHANTED_HIT, at.x, at.y, at.z, 24, 0.6, 0.6, 0.6, 0.3);
        TrapNet.shake(player, 0.5F, 6);
    }

    @Override
    protected void deathSounds() {
        sound(SoundEvents.ENTITY_WITHER_DEATH, 1.6F, 0.6F);
        sound(SoundEvents.ENTITY_WARDEN_DEATH, 1.2F, 1.4F);
    }

    @Override
    protected void onDying() {
        releaseGrip(false);
        clearMirrors();
        if (rig != null) {
            body().setStare(false);
        }
    }

    @Override
    protected void onBegin(Ability ability) {
        if (held != null) {
            releaseGrip(false);
        }
        slamHit.clear();
        marks.clear();
        looked.clear();
    }

    @Override
    protected void interrupted(Ability ability) {
        if (ability == STARE) {
            body().setStare(false);
        }
        if (ability == GRIP) {
            releaseGrip(false);
        }
        if (ability == BLINK) {
            rig.setCollapse(0.0F);
        }
        rig.setRise(0.0F);
    }

    @Override
    protected void onPhaseStart(ServerWorld world, int phase) {
        if (phase == 2) {
            timers.put(MIRRORS, 5);
        }
    }

    @Override
    protected boolean cast(ServerWorld world, Ability ability, int tick) {
        if (ability == SLAM) {
            return slam(world, tick);
        }
        if (ability == ORBS) {
            return orbs(world, tick);
        }
        if (ability == STARE) {
            return stare(world, tick);
        }
        if (ability == LIGHTNING) {
            return lightning(world, tick);
        }
        if (ability == MIRRORS) {
            return mirrorsCast(world);
        }
        if (ability == BLINK) {
            return blink(world, tick);
        }
        if (ability == GRIP) {
            return grip(world, tick);
        }
        return true;
    }

    // --- casts ---------------------------------------------------------------

    /** Fala: rise, slam, a ring that only catches feet on the ground. */
    private boolean slam(ServerWorld world, int tick) {
        if (tick <= 12) {
            rig.setRise(tick * 0.1F);
            if (tick == 1) {
                sound(SoundEvents.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.5F, 0.6F);
                sound(SoundEvents.ENTITY_RAVAGER_ROAR, 1.0F, 0.6F);
            }
            return false;
        }
        if (tick == 13) {
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
        double radius = (tick - 13) * 1.0;
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
            hurtPlayer(world, player, ArenaMath.SLAM_DAMAGE, world.getDamageSources().mobAttack(this));
            Vec3d push = player.getPos().subtract(at).normalize();
            player.setVelocity(push.x * 0.6, 0.75, push.z * 0.6);
            player.velocityModified = true;
            TrapNet.shake(player, 1.0F, 10);
            sound(player, SoundEvents.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.0F, 0.7F);
        }
        return false;
    }

    /** Oczy: three eyes, three targets, punchable. */
    private boolean orbs(ServerWorld world, int tick) {
        if (tick == 1) {
            sound(SoundEvents.ENTITY_ALLAY_ITEM_THROWN, 1.2F, 0.5F);
            rig.hurt();
        }
        if (tick == 5 || tick == 12 || tick == 19) {
            ServerPlayerEntity target = pickTarget();
            if (target == null) {
                return true;
            }
            sound(SoundEvents.ENTITY_SHULKER_SHOOT, 1.0F, 0.8F + tick * 0.02F);
            Vec3d eye = body().eyePos();
            ArenaProjectileEntity.launch(world, this, eye, target, EYE);
            world.spawnParticles(ParticleTypes.WITCH, eye.x, eye.y, eye.z, 8, 0.2, 0.2, 0.2, 0.1);
        }
        return tick >= 26;
    }

    /** Spojrzenie: don't look. */
    private boolean stare(ServerWorld world, int tick) {
        getNavigation().stop();
        if (getTarget() != null) {
            getLookControl().lookAt(getTarget(), 30.0F, 30.0F);
        }
        if (tick == 1) {
            body().setStare(true);
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
        if (tick >= end) {
            body().setStare(false);
            for (ServerPlayerEntity player : TrapArena.combatants()) {
                if (!looked.contains(player.getUuid())) {
                    TrapAwards.grant(player, "stare");
                }
            }
            return true;
        }
        if (tick < 10) {
            return false;
        }
        Vec3d eye = body().eyePos();
        if (tick % 4 == 0) {
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
            for (int i = 1; i < 8; i++) {
                Vec3d p = player.getEyePos().add(toEye.multiply(i / 8.0));
                world.spawnParticles(BEAM, p.x, p.y, p.z, 1, 0.0, 0.0, 0.0, 0.0);
            }
            if (tick % 10 == 0) {
                looked.add(player.getUuid());
                hurtPlayer(world, player, ArenaMath.STARE_DAMAGE,
                        world.getDamageSources().create(DamageTypes.MAGIC, this));
                world.spawnParticles(player, ParticleTypes.ELDER_GUARDIAN, true, false,
                        player.getX(), player.getY(), player.getZ(), 1, 0, 0, 0, 0);
                sound(player, SoundEvents.ENTITY_ELDER_GUARDIAN_CURSE, 0.6F, 1.2F);
                TrapNet.flash(player, 0xff2d55, 8);
            }
        }
        return false;
    }

    /** Piorun: circles, then bolts. Step out. */
    private boolean lightning(ServerWorld world, int tick) {
        if (tick == 1) {
            for (ServerPlayerEntity player : TrapArena.combatants()) {
                marks.add(player.getPos());
            }
            for (int i = 0; i < 2; i++) {
                marks.add(onFloor(random.nextDouble() * Math.PI * 2, 3.0 + random.nextDouble() * 14.0));
            }
            sound(SoundEvents.ENTITY_EVOKER_PREPARE_ATTACK, 1.5F, 0.7F);
            sound(SoundEvents.BLOCK_BEACON_POWER_SELECT, 1.0F, 0.5F);
            return false;
        }
        if (tick < 32) {
            for (Vec3d mark : marks) {
                for (int i = 0; i < 14; i++) {
                    double angle = i * Math.PI * 2 / 14 + tick * 0.15;
                    world.spawnParticles(MARK, mark.x + Math.cos(angle) * 2.5, mark.y + 0.15,
                            mark.z + Math.sin(angle) * 2.5, 1, 0.0, 0.0, 0.0, 0.0);
                }
                if (tick % 4 == 0) {
                    world.spawnParticles(ParticleTypes.ELECTRIC_SPARK, mark.x, mark.y + 0.5, mark.z, 3,
                            0.8, 0.4, 0.8, 0.1);
                }
            }
            return false;
        }
        if (tick == 32) {
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
                    hurtPlayer(world, player, ArenaMath.LIGHTNING_DAMAGE,
                            world.getDamageSources().create(DamageTypes.LIGHTNING_BOLT, this));
                    TrapNet.flash(player, 0xffffff, 6);
                    TrapNet.shake(player, 0.8F, 8);
                }
            }
            return false;
        }
        return tick >= 40;
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
        Vec3d centre = kind().centre();
        for (int i = 0; i < 3; i++) {
            Vec3d spot = onFloor(base + i * Math.PI * 2 / 3, 7.0);
            float yaw = (float) Math.toDegrees(Math.atan2(-(spot.x - centre.x), spot.z - centre.z)) + 180.0F;
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
    private boolean blink(ServerWorld world, int tick) {
        if (tick <= 4) {
            rig.setCollapse(tick / 4.0F);
            if (tick == 1) {
                sound(SoundEvents.ENTITY_ENDERMAN_TELEPORT, 1.5F, 0.5F);
                Vec3d at = getPos();
                world.spawnParticles(ParticleTypes.REVERSE_PORTAL, at.x, at.y + 1.5, at.z, 40, 0.6, 1.2, 0.6, 0.4);
            }
            return false;
        }
        if (tick == 5) {
            ServerPlayerEntity mark = pickTarget();
            Vec3d centre = kind().centre();
            Vec3d spot;
            float yaw;
            if (mark == null) {
                spot = centre;
                yaw = getYaw();
            } else {
                Vec3d behind = mark.getPos().subtract(mark.getRotationVec(1.0F).multiply(1, 0, 1).normalize().multiply(2.6));
                double dx = behind.x - centre.x;
                double dz = behind.z - centre.z;
                double r = Math.sqrt(dx * dx + dz * dz);
                double limit = kind().pitRadius() - 2.5;
                if (r > limit) {
                    double k = limit / r;
                    behind = new Vec3d(centre.x + dx * k, behind.y, centre.z + dz * k);
                }
                spot = new Vec3d(behind.x, centre.y, behind.z);
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
        rig.setCollapse(1.0F - (tick - 5) / 4.0F);
        return tick >= 9;
    }

    /** Uścisk: one of you in its hand, the rest of you hitting it. */
    private boolean grip(ServerWorld world, int tick) {
        if (tick == 1) {
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
            body().setGripping(true);
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
        Vec3d hand = body().handPos();
        Vec3d pull = hand.subtract(held.getPos()).multiply(0.45);
        held.setVelocity(pull.x, pull.y, pull.z);
        held.velocityModified = true;
        held.fallDistance = 0.0;
        Vec3d shoulder = getPos().add(0.0, 1.7, 0.0);
        for (int i = 1; i < 6; i++) {
            Vec3d p = shoulder.add(hand.subtract(shoulder).multiply(i / 6.0));
            world.spawnParticles(CHAIN, p.x, p.y, p.z, 1, 0, 0, 0, 0);
        }
        if (tick % 20 == 0) {
            held.damage(world, world.getDamageSources().mobAttack(this), 1.0F);
            sound(SoundEvents.BLOCK_CHAIN_PLACE, 0.8F, 0.4F);
        }
        if (tick % 5 == 0) {
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
        if (tick >= ArenaMath.GRIP_TICKS) {
            hurtPlayer(world, held, ArenaMath.GRIP_SLAM_DAMAGE, world.getDamageSources().mobAttack(this));
            afflict(held, 1);
            held.setVelocity(0.0, -0.8, 0.0);
            held.velocityModified = true;
            TrapNet.shake(held, 1.2F, 10);
            sound(SoundEvents.ENTITY_WARDEN_ATTACK_IMPACT, 1.5F, 0.6F);
            releaseGrip(false);
            return true;
        }
        return false;
    }

    private void releaseGrip(boolean freed) {
        if (rig != null) {
            body().setGripping(false);
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
}
