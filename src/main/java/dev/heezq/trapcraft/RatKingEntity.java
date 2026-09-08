package dev.heezq.trapcraft;

import eu.pb4.polymer.core.api.entity.PolymerEntityUtils;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.particle.BlockStateParticleEffect;
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
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Król Szczurów. The rat king.
 *
 * Disguised as a ravager: the box of something that charges, and a voice
 * that already sounds like it came out of a drain.
 *
 * Five casts, and the rats are the fight. The swarm comes out of the
 * tunnels; the tail sweeps a telegraphed circle; from the second phase he
 * goes under the floor and comes up under somebody, or into a tunnel and
 * out of another with company; in the third he spits Zaraza in puddles,
 * and bites harder for every rat still alive -- so the room has to decide
 * who deals with the rats while the rest deal with him.
 */
public class RatKingEntity extends ArenaBossEntity {
    public static EntityType<RatKingEntity> TYPE;

    private static final float SCALE = 1.2F;

    static final Ability SWARM = new Ability("swarm", 350, 1);
    static final Ability TAIL = new Ability("tail", 200, 1);
    static final Ability BURROW = new Ability("burrow", 450, 2);
    static final Ability TUNNEL = new Ability("tunnel", 500, 2);
    static final Ability PLAGUE = new Ability("plague", 300, 3);
    private static final List<Ability> ABILITIES = List.of(SWARM, TAIL, BURROW, TUNNEL, PLAGUE);
    public static final List<String> ABILITY_IDS = ABILITIES.stream().map(Ability::id).toList();

    private static final DustParticleEffect SICK = new DustParticleEffect(0x6bb51a, 1.3F);
    private static final DustParticleEffect TELL = new DustParticleEffect(0xd9b24a, 1.2F);
    private static final BlockStateParticleEffect MUD =
            new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.MUD.getDefaultState());
    private static final BlockStateParticleEffect BRICK =
            new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.MOSSY_STONE_BRICKS.getDefaultState());

    /** A puddle of Zaraza on the floor: where, since when, until when. */
    private record Puddle(Vec3d at, int start, int until) {
    }

    private final List<RatEntity> rats = new ArrayList<>();
    private final List<Puddle> puddles = new ArrayList<>();
    private int swarmLeft;
    private boolean under;
    private BlockPos tunnelMouth;

    public RatKingEntity(EntityType<? extends HostileEntity> type, World world) {
        super(type, world);
    }

    public static void register() {
        RegistryKey<EntityType<?>> key = RegistryKey.of(RegistryKeys.ENTITY_TYPE, TrapCraft.id("ratking"));
        TYPE = Registry.register(Registries.ENTITY_TYPE, key,
                EntityType.Builder.<RatKingEntity>create(RatKingEntity::new, SpawnGroup.MONSTER)
                        // A ravager's box, before the SCALE attribute.
                        .dimensions(1.95F, 2.2F)
                        .eyeHeight(1.9F)
                        .makeFireImmune()
                        .disableSaving()
                        .maxTrackingRange(12)
                        .build(key));
        PolymerEntityUtils.registerType(TYPE);
        FabricDefaultAttributeRegistry.register(TYPE, attributes());
    }

    private static DefaultAttributeContainer.Builder attributes() {
        return HostileEntity.createHostileAttributes()
                .add(EntityAttributes.MAX_HEALTH, ArenaMath.BASE_HEALTH)
                .add(EntityAttributes.MOVEMENT_SPEED, 0.34)
                .add(EntityAttributes.ATTACK_DAMAGE, ArenaMath.HEAVY_MELEE_DAMAGE)
                .add(EntityAttributes.FOLLOW_RANGE, 64.0)
                .add(EntityAttributes.KNOCKBACK_RESISTANCE, 1.0)
                .add(EntityAttributes.ARMOR, 5.0)
                .add(EntityAttributes.STEP_HEIGHT, 1.0)
                .add(EntityAttributes.SCALE, SCALE);
    }

    // --- the base's questions -------------------------------------------------

    @Override
    public ArenaBoss kind() {
        return RatKingBoss.INSTANCE;
    }

    @Override
    protected List<Ability> abilities() {
        return ABILITIES;
    }

    @Override
    protected DisplayRig makeRig() {
        return RatKingRig.attachTo(this);
    }

    private RatKingRig body() {
        return (RatKingRig) rig;
    }

    @Override
    protected EntityType<?> disguise() {
        return EntityType.RAVAGER;
    }

    @Override
    protected void afflict(ServerPlayerEntity player, int stacks) {
        RatKingBoss.plague(player, stacks);
    }

    /** From the third phase, every living rat is a little more bite. */
    @Override
    protected float damageMultiplier(ServerPlayerEntity player) {
        return phase >= 3 ? ArenaMath.ratBite(liveRats()) : 1.0F;
    }

    private int liveRats() {
        rats.removeIf(rat -> rat.isRemoved() || !rat.isAlive());
        return rats.size();
    }

    @Override
    public Vec3d throwPos() {
        return rig == null ? super.throwPos() : body().mouthPos();
    }

    // --- noise and light ------------------------------------------------------

    @Override
    protected void introTick(ServerWorld world, float progress) {
        Vec3d at = getPos();
        world.spawnParticles(MUD, at.x, at.y + 0.2, at.z, 8, 1.4, 0.2, 1.4, 0.1);
        if (age == 1) {
            sound(SoundEvents.ENTITY_WARDEN_EMERGE, 1.5F, 1.2F);
            sound(SoundEvents.BLOCK_ROOTED_DIRT_BREAK, 1.5F, 0.5F);
        }
        List<BlockPos> tunnels = RatKingBoss.INSTANCE.tunnels();
        if (age % 15 == 0 && !tunnels.isEmpty()) {
            BlockPos mouth = tunnels.get(random.nextInt(tunnels.size()));
            world.playSound(null, mouth.getX() + 0.5, mouth.getY() + 1.0, mouth.getZ() + 0.5,
                    SoundEvents.ENTITY_SILVERFISH_AMBIENT, SoundCategory.HOSTILE, 1.2F, 0.6F + random.nextFloat() * 0.6F);
        }
        if (age % 10 == 0) {
            for (ServerPlayerEntity player : TrapArena.arenaPlayers()) {
                TrapNet.shake(player, 0.3F + 0.5F * progress, 8);
            }
        }
        if (age == INTRO_TICKS - 10) {
            sound(SoundEvents.ENTITY_RAVAGER_ROAR, 1.5F, 0.8F);
        }
    }

    @Override
    protected void breathe(ServerWorld world) {
        Vec3d at = getPos();
        if (age % 15 == 0 && !under) {
            world.spawnParticles(ParticleTypes.ITEM_SLIME, at.x, at.y + 1.2, at.z, 1, 0.8, 0.5, 0.8, 0.0);
        }
        if (age % 90 == 0) {
            body().wag(20);
        }
        tickPuddles(world);
    }

    @Override
    protected SoundEvent ambient(int roll) {
        return switch (roll) {
            case 0 -> SoundEvents.ENTITY_RAVAGER_AMBIENT;
            case 1 -> SoundEvents.ENTITY_SILVERFISH_AMBIENT;
            default -> SoundEvents.BLOCK_WATER_AMBIENT;
        };
    }

    @Override
    protected void onStrike(ServerWorld world, Entity target) {
        body().bite();
        sound(SoundEvents.ENTITY_RAVAGER_ATTACK, 1.0F, 0.9F);
        Vec3d at = target.getPos().add(0.0, target.getHeight() * 0.5, 0.0);
        world.spawnParticles(ParticleTypes.ITEM_SLIME, at.x, at.y, at.z, 8, 0.3, 0.3, 0.3, 0.05);
        if (target instanceof ServerPlayerEntity player) {
            afflict(player, 1);
        }
    }

    @Override
    protected void onHurt(ServerWorld world, ServerPlayerEntity by, float dealt) {
        sound(SoundEvents.ENTITY_RAVAGER_HURT, 0.7F, 1.1F);
        Vec3d at = getPos().add(0.0, 1.5, 0.0);
        world.spawnParticles(MUD, at.x, at.y, at.z, 5, 0.6, 0.5, 0.6, 0.05);
    }

    @Override
    protected void deathSounds() {
        sound(SoundEvents.ENTITY_RAVAGER_DEATH, 1.6F, 0.7F);
        sound(SoundEvents.ENTITY_SILVERFISH_DEATH, 1.2F, 0.5F);
        sound(SoundEvents.ENTITY_SILVERFISH_DEATH, 1.2F, 0.8F);
    }

    @Override
    protected void deathTick(ServerWorld world) {
        Vec3d at = getPos().add(0.0, 1.0, 0.0);
        world.spawnParticles(MUD, at.x, at.y, at.z, 3, 0.8, 0.5, 0.8, 0.05);
        if (deathTime % 10 == 0) {
            world.spawnParticles(ParticleTypes.ITEM_SLIME, at.x, at.y + 0.5, at.z, 12, 0.8, 0.6, 0.8, 0.1);
            sound(SoundEvents.ENTITY_SILVERFISH_DEATH, 0.8F, 0.5F + random.nextFloat() * 0.6F);
            sound(SoundEvents.BLOCK_MUD_BREAK, 1.0F, 0.8F);
        }
        if (deathTime == 40) {
            sound(SoundEvents.BLOCK_ANVIL_LAND, 0.6F, 1.9F);
        }
    }

    @Override
    protected void onDying() {
        puddles.clear();
        under = false;
    }

    @Override
    protected void interrupted(Ability ability) {
        if (ability == BURROW || ability == TUNNEL) {
            shielded = false;
            under = false;
            rig.setRise(0.0F);
        }
    }

    @Override
    protected boolean cast(ServerWorld world, Ability ability, int tick) {
        if (ability == SWARM) {
            return swarm(world, tick);
        }
        if (ability == TAIL) {
            return tail(world, tick);
        }
        if (ability == BURROW) {
            return burrow(world, tick);
        }
        if (ability == TUNNEL) {
            return tunnel(world, tick);
        }
        if (ability == PLAGUE) {
            return plague(world, tick);
        }
        return true;
    }

    // --- casts ---------------------------------------------------------------

    /** Rój: rats out of the tunnels, more of them every phase. */
    private boolean swarm(ServerWorld world, int tick) {
        List<BlockPos> tunnels = RatKingBoss.INSTANCE.tunnels();
        if (tunnels.isEmpty()) {
            return true;
        }
        if (tick == 1) {
            swarmLeft = ArenaMath.ratsFor(phase);
            body().wag(40);
            sound(SoundEvents.ENTITY_RAVAGER_ROAR, 1.2F, 1.3F);
            for (ServerPlayerEntity player : TrapArena.arenaPlayers()) {
                TrapArena.title(player, Text.literal("RÓJ").formatted(Formatting.DARK_GREEN, Formatting.BOLD),
                        Text.literal("Z tuneli.").formatted(Formatting.GRAY), 5, 25, 10);
            }
        }
        if (tick >= 5 && tick % 3 == 2 && swarmLeft > 0) {
            swarmLeft--;
            spawnRat(world, tunnels.get(random.nextInt(tunnels.size())));
        }
        return swarmLeft <= 0 && tick > 12;
    }

    private void spawnRat(ServerWorld world, BlockPos mouth) {
        if (liveRats() >= ArenaMath.RATS_MAX + 4) {
            return;
        }
        RatEntity rat = new RatEntity(RatEntity.TYPE, world);
        Vec3d at = new Vec3d(mouth.getX() + 0.5, mouth.getY() + 1.0, mouth.getZ() + 0.5);
        Vec3d centre = kind().centre();
        float yaw = (float) Math.toDegrees(Math.atan2(-(centre.x - at.x), centre.z - at.z));
        rat.refreshPositionAndAngles(at.x, at.y, at.z, yaw, 0.0F);
        rat.setKing(this);
        rat.initialize(world, world.getLocalDifficulty(mouth), SpawnReason.EVENT, null);
        world.spawnEntity(rat);
        TrapArena.track(rat);
        rats.add(rat);
        world.spawnParticles(BRICK, at.x, at.y + 0.5, at.z, 12, 0.4, 0.4, 0.4, 0.1);
        world.playSound(null, at.x, at.y, at.z, SoundEvents.BLOCK_GRAVEL_BREAK, SoundCategory.HOSTILE, 1.0F, 0.8F);
        world.playSound(null, at.x, at.y, at.z, SoundEvents.ENTITY_SILVERFISH_AMBIENT, SoundCategory.HOSTILE, 0.8F,
                1.1F + random.nextFloat() * 0.4F);
    }

    /** Ogon: a circle on the floor, then the tail through it. Out of the circle. */
    private boolean tail(ServerWorld world, int tick) {
        Vec3d at = getPos();
        if (tick == 1) {
            body().wag(12);
            sound(SoundEvents.ENTITY_RAVAGER_AMBIENT, 1.2F, 0.7F);
            sound(SoundEvents.ENTITY_CAT_HISS, 1.0F, 0.6F);
        }
        if (tick <= 10) {
            int points = 28;
            for (int i = 0; i < points; i++) {
                double a = i * Math.PI * 2 / points + tick * 0.1;
                world.spawnParticles(TELL, at.x + Math.cos(a) * ArenaMath.TAIL_RANGE, at.y + 0.15,
                        at.z + Math.sin(a) * ArenaMath.TAIL_RANGE, 1, 0.0, 0.0, 0.0, 0.0);
            }
            return false;
        }
        if (tick == 11) {
            sound(SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, 1.5F, 0.5F);
            sound(SoundEvents.ENTITY_RAVAGER_ATTACK, 1.2F, 0.8F);
            for (int i = 0; i < 8; i++) {
                double a = i * Math.PI / 4;
                world.spawnParticles(ParticleTypes.SWEEP_ATTACK, at.x + Math.cos(a) * 2.5, at.y + 0.8,
                        at.z + Math.sin(a) * 2.5, 1, 0, 0, 0, 0);
            }
            for (ServerPlayerEntity player : TrapArena.combatants()) {
                double dx = player.getX() - at.x;
                double dz = player.getZ() - at.z;
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > ArenaMath.TAIL_RANGE || Math.abs(player.getY() - at.y) > 2.5) {
                    continue;
                }
                hurtPlayer(world, player, ArenaMath.TAIL_DAMAGE, world.getDamageSources().mobAttack(this));
                Vec3d away = d > 0.01 ? new Vec3d(dx / d, 0.0, dz / d) : rig.forward();
                player.setVelocity(away.x * 0.9, 0.45, away.z * 0.9);
                player.velocityModified = true;
                TrapNet.shake(player, 0.9F, 8);
                sound(player, SoundEvents.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.0F, 0.7F);
            }
            return false;
        }
        return tick >= 18;
    }

    /** Nora: under the floor, toward somebody, and up under them. Keep moving. */
    private boolean burrow(ServerWorld world, int tick) {
        Vec3d at = getPos();
        if (tick == 1) {
            shielded = true;
            under = true;
            sound(SoundEvents.ENTITY_WARDEN_DIG, 1.5F, 1.4F);
            sound(SoundEvents.BLOCK_ROOTED_DIRT_BREAK, 1.5F, 0.6F);
            for (ServerPlayerEntity player : TrapArena.arenaPlayers()) {
                TrapArena.title(player, Text.literal("NORA").formatted(Formatting.DARK_GREEN, Formatting.BOLD),
                        Text.literal("Ryje. Zejdź mu z drogi.").formatted(Formatting.GRAY), 5, 30, 10);
            }
        }
        if (tick <= 10) {
            rig.setRise(-0.32F * tick);
            world.spawnParticles(MUD, at.x, at.y + 0.2, at.z, 10, 1.0, 0.2, 1.0, 0.15);
            return false;
        }
        if (tick < 70) {
            LivingEntity target = getTarget();
            if (!(target instanceof ServerPlayerEntity) || !TrapArena.isCombatant(target)) {
                target = pickTarget();
                setTarget(target);
            }
            if (target != null) {
                Vec3d to = target.getPos().subtract(at);
                Vec3d step = new Vec3d(to.x, 0.0, to.z);
                double d = step.length();
                if (d > 0.5) {
                    step = step.normalize().multiply(Math.min(0.28, d));
                    refreshPositionAndAngles(at.x + step.x, kind().centre().y, at.z + step.z,
                            yawToward(target.getPos()), 0.0F);
                }
            }
            world.spawnParticles(MUD, getX(), getY() + 0.1, getZ(), 4, 0.6, 0.1, 0.6, 0.1);
            if (tick % 10 == 0) {
                sound(SoundEvents.BLOCK_GRAVEL_BREAK, 1.0F, 0.6F + random.nextFloat() * 0.3F);
            }
            return false;
        }
        if (tick == 70) {
            shielded = false;
            under = false;
            rig.setRise(0.0F);
            sound(SoundEvents.ENTITY_WARDEN_EMERGE, 1.5F, 1.5F);
            sound(SoundEvents.BLOCK_STONE_BREAK, 1.5F, 0.6F);
            sound(SoundEvents.ENTITY_RAVAGER_ROAR, 1.2F, 0.9F);
            world.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, at.x, at.y + 0.5, at.z, 1, 0, 0, 0, 0);
            world.spawnParticles(BRICK, at.x, at.y + 0.5, at.z, 60, 1.5, 0.5, 1.5, 0.2);
            for (ServerPlayerEntity player : TrapArena.combatants()) {
                double dx = player.getX() - at.x;
                double dz = player.getZ() - at.z;
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > 3.0 || Math.abs(player.getY() - at.y) > 3.0) {
                    continue;
                }
                hurtPlayer(world, player, ArenaMath.ERUPT_DAMAGE, world.getDamageSources().mobAttack(this));
                Vec3d away = d > 0.01 ? new Vec3d(dx / d, 0.0, dz / d) : rig.forward();
                player.setVelocity(away.x * 0.6, 0.9, away.z * 0.6);
                player.velocityModified = true;
                TrapNet.shake(player, 1.2F, 10);
            }
            for (ServerPlayerEntity player : TrapArena.arenaPlayers()) {
                TrapNet.shake(player, 0.6F, 8);
            }
            return false;
        }
        return tick >= 80;
    }

    /** Tunel: into one tunnel, out of another, with friends. */
    private boolean tunnel(ServerWorld world, int tick) {
        List<BlockPos> tunnels = RatKingBoss.INSTANCE.tunnels();
        if (tunnels.isEmpty()) {
            return true;
        }
        Vec3d at = getPos();
        if (tick == 1) {
            shielded = true;
            under = true;
            BlockPos nearest = null;
            double best = Double.MAX_VALUE;
            for (BlockPos mouth : tunnels) {
                double d = mouth.getSquaredDistance(at);
                if (d < best) {
                    best = d;
                    nearest = mouth;
                }
            }
            List<BlockPos> others = new ArrayList<>(tunnels);
            if (others.size() > 1) {
                others.remove(nearest);
            }
            tunnelMouth = others.get(random.nextInt(others.size()));
            sound(SoundEvents.ENTITY_RAVAGER_AMBIENT, 1.0F, 1.2F);
            sound(SoundEvents.BLOCK_ROOTED_DIRT_BREAK, 1.2F, 0.7F);
            for (ServerPlayerEntity player : TrapArena.arenaPlayers()) {
                TrapArena.title(player, Text.literal("TUNEL").formatted(Formatting.DARK_GREEN, Formatting.BOLD),
                        Text.literal("Zniknął w ścianie.").formatted(Formatting.GRAY), 5, 25, 10);
            }
        }
        if (tick <= 8) {
            rig.setRise(-0.4F * tick);
            world.spawnParticles(MUD, at.x, at.y + 0.2, at.z, 8, 1.0, 0.2, 1.0, 0.15);
            return false;
        }
        Vec3d mouth = new Vec3d(tunnelMouth.getX() + 0.5, tunnelMouth.getY() + 1.0, tunnelMouth.getZ() + 0.5);
        if (tick == 9) {
            Vec3d centre = kind().centre();
            Vec3d dir = new Vec3d(mouth.x - centre.x, 0.0, mouth.z - centre.z).normalize();
            Vec3d spot = centre.add(dir.multiply(kind().pitRadius() - 3.5));
            float yaw = (float) Math.toDegrees(Math.atan2(-(centre.x - spot.x), centre.z - spot.z));
            refreshPositionAndAngles(spot.x, centre.y, spot.z, yaw, 0.0F);
            setBodyYaw(yaw);
            setHeadYaw(yaw);
            return false;
        }
        if (tick >= 20 && tick < 60 && tick % 10 == 0) {
            spawnRat(world, tunnelMouth);
        }
        if (tick == 60) {
            world.playSound(null, mouth.x, mouth.y, mouth.z, SoundEvents.ENTITY_RAVAGER_AMBIENT,
                    SoundCategory.HOSTILE, 1.5F, 0.6F);
        }
        if (tick > 72 && tick <= 80) {
            rig.setRise(-3.2F + (tick - 72) * 0.4F);
            world.spawnParticles(BRICK, getX(), getY() + 0.3, getZ(), 6, 0.8, 0.3, 0.8, 0.1);
        }
        if (tick == 81) {
            shielded = false;
            under = false;
            rig.setRise(0.0F);
            sound(SoundEvents.ENTITY_RAVAGER_ROAR, 1.3F, 0.9F);
            sound(SoundEvents.BLOCK_STONE_BREAK, 1.2F, 0.7F);
            world.spawnParticles(BRICK, getX(), getY() + 0.5, getZ(), 40, 1.2, 0.6, 1.2, 0.15);
        }
        return tick >= 90;
    }

    /** Zaraza: puddles where you stood. Not in them; the water washes it. */
    private boolean plague(ServerWorld world, int tick) {
        if (tick == 1) {
            body().bite();
            sound(SoundEvents.ENTITY_SLIME_SQUISH, 1.5F, 0.5F);
            sound(SoundEvents.ENTITY_WITCH_THROW, 1.2F, 0.6F);
            List<ServerPlayerEntity> room = new ArrayList<>(TrapArena.combatants());
            Collections.shuffle(room, new java.util.Random(random.nextLong()));
            for (int i = 0; i < 3; i++) {
                Vec3d spot = i < room.size() ? room.get(i).getPos()
                        : onFloor(random.nextDouble() * Math.PI * 2, 3.0 + random.nextDouble() * 10.0);
                puddles.add(new Puddle(new Vec3d(spot.x, kind().centre().y, spot.z), age, age + ArenaMath.PUDDLE_TICKS));
            }
            for (ServerPlayerEntity player : TrapArena.arenaPlayers()) {
                TrapArena.title(player, Text.literal("ZARAZA").formatted(Formatting.DARK_GREEN, Formatting.BOLD),
                        Text.literal("Nie stój w tym. Woda zmywa.").formatted(Formatting.GRAY), 5, 30, 10);
            }
        }
        if (tick <= 10) {
            Vec3d mouth = body().mouthPos();
            for (Puddle puddle : puddles) {
                Vec3d p = mouth.add(puddle.at().subtract(mouth).multiply(tick / 10.0));
                world.spawnParticles(ParticleTypes.ITEM_SLIME, p.x, p.y + 0.3, p.z, 3, 0.15, 0.15, 0.15, 0.02);
            }
            return false;
        }
        return tick >= 12;
    }

    private void tickPuddles(ServerWorld world) {
        if (puddles.isEmpty()) {
            return;
        }
        List<Puddle> gone = new ArrayList<>();
        for (Puddle puddle : puddles) {
            Vec3d at = puddle.at();
            if (age >= puddle.until()) {
                gone.add(puddle);
                world.spawnParticles(ParticleTypes.POOF, at.x, at.y + 0.3, at.z, 10, 1.0, 0.2, 1.0, 0.02);
                continue;
            }
            for (int i = 0; i < 12; i++) {
                double a = i * Math.PI / 6 + age * 0.07;
                world.spawnParticles(SICK, at.x + Math.cos(a) * 2.5, at.y + 0.1, at.z + Math.sin(a) * 2.5,
                        1, 0.0, 0.0, 0.0, 0.0);
            }
            if (age % 4 == 0) {
                world.spawnParticles(ParticleTypes.SNEEZE, at.x, at.y + 0.2, at.z, 2, 1.2, 0.1, 1.2, 0.01);
            }
            if ((age - puddle.start()) % 20 != 0) {
                continue;
            }
            for (ServerPlayerEntity player : TrapArena.combatants()) {
                double dx = player.getX() - at.x;
                double dz = player.getZ() - at.z;
                if (dx * dx + dz * dz > 2.5 * 2.5 || Math.abs(player.getY() - at.y) > 2.0) {
                    continue;
                }
                hurtPlayer(world, player, ArenaMath.PUDDLE_DAMAGE, world.getDamageSources().magic());
                sound(player, SoundEvents.ENTITY_ZOMBIE_INFECT, 0.6F, 0.8F);
                TrapNet.flash(player, 0x6bb51a, 6);
            }
        }
        puddles.removeAll(gone);
    }
}
