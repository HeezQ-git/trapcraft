package dev.heezq.trapcraft;

import eu.pb4.polymer.core.api.entity.PolymerEntityUtils;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.mob.BreezeEntity;
import net.minecraft.entity.mob.HostileEntity;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sztorm. The storm.
 *
 * Disguised as a ghast, scaled down: a big soft box in the air, and a
 * voice that was already a cry. It never lands unless it means to; it
 * hovers over the disc, circling whoever it wants, so the lent bow is the
 * comfortable answer and a jump with a sword the brave one.
 *
 * Seven casts. Wind pushes you toward the edge unless you are at a copper
 * pillar; bolts go for two of you and take the lightning rod instead if
 * one is close; the descent is the melee window, with a shock for standing
 * too close; hail freezes, and the braziers thaw; a twister wanders the
 * floor lifting and throwing; two breezes turn up for a while; and the
 * tempest is wind and bolts together for ten seconds, which is what the
 * pillars are for.
 */
public class StormEntity extends ArenaBossEntity {
    public static EntityType<StormEntity> TYPE;

    /** How high above the floor it hovers, and how low the descent goes. */
    public static final float HOVER = 4.5F;
    private static final float LOW = 1.2F;
    private static final float SCALE = 0.8F;

    static final Ability WIND = new Ability("wind", 260, 1);
    static final Ability BOLT = new Ability("bolt", 220, 1);
    static final Ability DESCEND = new Ability("descend", 500, 1);
    static final Ability HAIL = new Ability("hail", 400, 2);
    static final Ability TWISTER = new Ability("twister", 450, 2);
    static final Ability GALES = new Ability("gales", 700, 2);
    static final Ability TEMPEST = new Ability("tempest", 600, 3);
    private static final List<Ability> ABILITIES = List.of(WIND, BOLT, DESCEND, HAIL, TWISTER, GALES, TEMPEST);
    public static final List<String> ABILITY_IDS = ABILITIES.stream().map(Ability::id).toList();

    private static final DustParticleEffect WHITE = new DustParticleEffect(0xf4f8ff, 1.2F);
    private static final DustParticleEffect ICE = new DustParticleEffect(0xbfe8ff, 1.0F);

    /** Somebody a bolt is coming for, and when. */
    private record Mark(ServerPlayerEntity who, int at) {
    }

    /** A hailstone on its way down: where it lands, and when. */
    private record Hail(Vec3d at, int land) {
    }

    private float hoverHeight = HOVER;
    private float orbit;
    private final List<Mark> marks = new ArrayList<>();
    private final List<Hail> hail = new ArrayList<>();
    private Vec3d twister;
    private int twisterUntil;
    private final Map<UUID, Integer> caught = new HashMap<>();
    private final List<BreezeEntity> gales = new ArrayList<>();
    private int galesUntil;

    public StormEntity(EntityType<? extends HostileEntity> type, World world) {
        super(type, world);
        setNoGravity(true);
    }

    public static void register() {
        RegistryKey<EntityType<?>> key = RegistryKey.of(RegistryKeys.ENTITY_TYPE, TrapCraft.id("storm"));
        TYPE = Registry.register(Registries.ENTITY_TYPE, key,
                EntityType.Builder.<StormEntity>create(StormEntity::new, SpawnGroup.MONSTER)
                        // A ghast's box, before the SCALE attribute.
                        .dimensions(4.0F, 4.0F)
                        .eyeHeight(2.6F)
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
                .add(EntityAttributes.MOVEMENT_SPEED, 0.1)
                .add(EntityAttributes.ATTACK_DAMAGE, ArenaMath.SHOCK_DAMAGE)
                .add(EntityAttributes.FOLLOW_RANGE, 64.0)
                .add(EntityAttributes.KNOCKBACK_RESISTANCE, 1.0)
                .add(EntityAttributes.ARMOR, 4.0)
                .add(EntityAttributes.SCALE, SCALE);
    }

    // --- the base's questions -------------------------------------------------

    @Override
    public ArenaBoss kind() {
        return StormBoss.INSTANCE;
    }

    @Override
    protected List<Ability> abilities() {
        return ABILITIES;
    }

    @Override
    protected DisplayRig makeRig() {
        return StormRig.attachTo(this);
    }

    @Override
    protected EntityType<?> disguise() {
        return EntityType.GHAST;
    }

    @Override
    protected boolean melee() {
        return false;
    }

    /** It flies: the pit's ceiling is higher for it, and the floor is where it goes on purpose. */
    @Override
    protected void keepInThePit() {
        ArenaBoss kind = kind();
        Vec3d centre = kind.centre();
        double dx = getX() - centre.x;
        double dz = getZ() - centre.z;
        double r = Math.sqrt(dx * dx + dz * dz);
        int floor = kind.origin().getY();
        if (r > kind.pitRadius() - 2.0 || getY() < floor + 0.5 || getY() > floor + 13) {
            double k = r > 0.001 ? Math.min(1.0, (kind.pitRadius() - 4.0) / r) : 0.0;
            refreshPositionAndAngles(centre.x + dx * k, floor + hoverHeight, centre.z + dz * k, getYaw(), getPitch());
        }
    }

    /** The melee window takes more. */
    @Override
    public boolean damage(ServerWorld world, DamageSource source, float amount) {
        if (casting == DESCEND && castTick > 25 && castTick < 145) {
            amount *= ArenaMath.DESCEND_TAKEN;
        }
        return super.damage(world, source, amount);
    }

    // --- noise and light ------------------------------------------------------

    @Override
    protected void introTick(ServerWorld world, float progress) {
        Vec3d at = getPos();
        world.spawnParticles(ParticleTypes.CLOUD, at.x, at.y + 2.0 + (1.0F - progress) * 7.0, at.z, 6, 2.0, 1.0, 2.0, 0.02);
        world.spawnParticles(ParticleTypes.ELECTRIC_SPARK, at.x, at.y + 2.0, at.z, 2, 2.0, 2.0, 2.0, 0.1);
        world.spawnParticles(ParticleTypes.RAIN, at.x, at.y + 6.0, at.z, 8, 8.0, 1.0, 8.0, 0.0);
        if (age == 1) {
            sound(SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, 2.0F, 0.6F);
            sound(SoundEvents.WEATHER_RAIN, 1.5F, 0.8F);
        }
        if (age % 25 == 0) {
            sound(SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, 0.8F, 1.2F);
            for (ServerPlayerEntity player : TrapArena.arenaPlayers()) {
                TrapNet.shake(player, 0.3F + 0.5F * progress, 10);
                TrapNet.flash(player, 0xffffff, 3);
            }
        }
        if (age == INTRO_TICKS - 10) {
            sound(SoundEvents.ENTITY_GHAST_WARN, 1.5F, 0.7F);
        }
    }

    @Override
    protected void breathe(ServerWorld world) {
        hover();
        Vec3d at = getPos();
        world.spawnParticles(ParticleTypes.RAIN, at.x, at.y + 3.0, at.z, 2, 4.0, 0.5, 4.0, 0.0);
        if (age % 3 == 0) {
            world.spawnParticles(ParticleTypes.CLOUD, at.x, at.y + 1.6, at.z, 1, 1.5, 1.0, 1.5, 0.01);
        }
        if (age % 40 == 0 && random.nextInt(3) == 0) {
            world.spawnParticles(ParticleTypes.ELECTRIC_SPARK, at.x, at.y + 1.6, at.z, 10, 1.5, 1.0, 1.5, 0.1);
        }
        tickMarks(world);
        tickHail(world);
        tickTwister(world);
        if (!gales.isEmpty() && age >= galesUntil) {
            for (BreezeEntity breeze : gales) {
                if (!breeze.isRemoved()) {
                    world.spawnParticles(ParticleTypes.POOF, breeze.getX(), breeze.getY() + 1.0, breeze.getZ(),
                            20, 0.4, 0.6, 0.4, 0.05);
                    breeze.discard();
                }
            }
            gales.clear();
        }
    }

    /** Over the disc, circling whoever it wants, at whatever height the cast asks. */
    private void hover() {
        LivingEntity target = getTarget();
        Vec3d centre = kind().centre();
        Vec3d anchor = target != null ? target.getPos() : centre;
        orbit += 0.02F;
        double radius = casting == DESCEND ? 2.5 : 5.5;
        Vec3d want = new Vec3d(anchor.x + Math.cos(orbit) * radius, centre.y + hoverHeight,
                anchor.z + Math.sin(orbit) * radius);
        double dx = want.x - centre.x;
        double dz = want.z - centre.z;
        double r = Math.sqrt(dx * dx + dz * dz);
        double limit = kind().pitRadius() - 4.0;
        if (r > limit) {
            want = new Vec3d(centre.x + dx * limit / r, want.y, centre.z + dz * limit / r);
        }
        Vec3d v = want.subtract(getPos()).multiply(0.07);
        double speed = v.length();
        if (speed > 0.32) {
            v = v.multiply(0.32 / speed);
        }
        setVelocity(v);
        if (target != null) {
            float yaw = yawToward(target.getPos());
            setYaw(yaw);
            setBodyYaw(yaw);
            setHeadYaw(yaw);
        }
    }

    @Override
    protected SoundEvent ambient(int roll) {
        return switch (roll) {
            case 0 -> SoundEvents.ENTITY_GHAST_AMBIENT;
            case 1 -> SoundEvents.ENTITY_BREEZE_IDLE_AIR;
            default -> SoundEvents.WEATHER_RAIN;
        };
    }

    @Override
    protected void onHurt(ServerWorld world, ServerPlayerEntity by, float dealt) {
        sound(SoundEvents.ENTITY_GHAST_HURT, 0.7F, 1.1F);
        sound(SoundEvents.ENTITY_LIGHTNING_BOLT_IMPACT, 0.3F, 1.8F);
        Vec3d at = getPos().add(0.0, 1.6, 0.0);
        world.spawnParticles(ParticleTypes.ELECTRIC_SPARK, at.x, at.y, at.z, 8, 0.8, 0.8, 0.8, 0.15);
    }

    @Override
    protected void deathSounds() {
        sound(SoundEvents.ENTITY_GHAST_DEATH, 1.6F, 0.7F);
        sound(SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, 2.0F, 0.5F);
    }

    @Override
    protected void deathTick(ServerWorld world) {
        Vec3d at = getPos().add(0.0, 1.6, 0.0);
        world.spawnParticles(ParticleTypes.RAIN, at.x, at.y + 2.0, at.z, 10, 3.0, 1.0, 3.0, 0.0);
        world.spawnParticles(ParticleTypes.CLOUD, at.x, at.y, at.z, 4, 1.5, 1.0, 1.5, 0.05);
        world.spawnParticles(ParticleTypes.ELECTRIC_SPARK, at.x, at.y, at.z, 3, 1.5, 1.5, 1.5, 0.1);
        if (deathTime % 10 == 0) {
            sound(SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, 0.8F, 0.8F + deathTime * 0.01F);
            sound(SoundEvents.WEATHER_RAIN, 0.6F, 1.2F);
        }
    }

    @Override
    protected void onDying() {
        marks.clear();
        hail.clear();
        twister = null;
        caught.clear();
    }

    @Override
    protected void interrupted(Ability ability) {
        if (ability == DESCEND) {
            hoverHeight = HOVER;
        }
    }

    @Override
    protected boolean cast(ServerWorld world, Ability ability, int tick) {
        if (ability == WIND) {
            return wind(world, tick);
        }
        if (ability == BOLT) {
            return bolt(world, tick);
        }
        if (ability == DESCEND) {
            return descend(world, tick);
        }
        if (ability == HAIL) {
            return hailCast(world, tick);
        }
        if (ability == TWISTER) {
            return twisterCast(world, tick);
        }
        if (ability == GALES) {
            return galesCast(world, tick);
        }
        if (ability == TEMPEST) {
            return tempest(world, tick);
        }
        return true;
    }

    // --- casts ---------------------------------------------------------------

    /** Wiatr: four seconds of push toward the edge. At a copper pillar, none. */
    private boolean wind(ServerWorld world, int tick) {
        if (tick == 1) {
            sound(SoundEvents.ITEM_ELYTRA_FLYING, 1.5F, 0.6F);
            sound(SoundEvents.ENTITY_GHAST_WARN, 1.2F, 0.5F);
            for (ServerPlayerEntity player : TrapArena.arenaPlayers()) {
                TrapArena.title(player, Text.literal("WIATR").formatted(Formatting.AQUA, Formatting.BOLD),
                        Text.literal("Trzymaj się miedzi.").formatted(Formatting.GRAY), 5, 30, 10);
            }
        }
        push(world, ArenaMath.WIND_PUSH);
        gust(world, 5);
        if (tick % 20 == 0) {
            sound(SoundEvents.ITEM_ELYTRA_FLYING, 1.0F, 0.5F + random.nextFloat() * 0.2F);
        }
        return tick >= 80;
    }

    private void push(ServerWorld world, double strength) {
        Vec3d at = getPos();
        for (ServerPlayerEntity player : TrapArena.combatants()) {
            if (sheltered(player)) {
                continue;
            }
            Vec3d away = new Vec3d(player.getX() - at.x, 0.0, player.getZ() - at.z);
            if (away.lengthSquared() < 0.01) {
                continue;
            }
            away = away.normalize();
            double s = player.isSneaking() ? strength * 0.5 : strength;
            player.addVelocity(away.x * s, 0.0, away.z * s);
            player.velocityModified = true;
        }
    }

    /** Cloud streaming outward across the floor. */
    private void gust(ServerWorld world, int count) {
        Vec3d at = getPos();
        double floor = kind().centre().y;
        for (int i = 0; i < count; i++) {
            double a = random.nextDouble() * Math.PI * 2;
            double r = 2.0 + random.nextDouble() * 13.0;
            world.spawnParticles(ParticleTypes.CLOUD, at.x + Math.cos(a) * r, floor + 0.5 + random.nextDouble() * 2.0,
                    at.z + Math.sin(a) * r, 0, Math.cos(a), 0.02, Math.sin(a), 0.35);
        }
    }

    private boolean sheltered(ServerPlayerEntity player) {
        for (BlockPos rod : StormBoss.INSTANCE.rods()) {
            double dx = rod.getX() + 0.5 - player.getX();
            double dz = rod.getZ() + 0.5 - player.getZ();
            if (dx * dx + dz * dz <= 2.0 * 2.0) {
                return true;
            }
        }
        return false;
    }

    private BlockPos nearestRod(Vec3d pos) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos rod : StormBoss.INSTANCE.rods()) {
            double dx = rod.getX() + 0.5 - pos.x;
            double dz = rod.getZ() + 0.5 - pos.z;
            double d = dx * dx + dz * dz;
            if (d < bestD) {
                bestD = d;
                best = rod;
            }
        }
        return best;
    }

    /** Piorun: two of you marked; a rod within reach takes it instead. */
    private boolean bolt(ServerWorld world, int tick) {
        if (tick == 1) {
            List<ServerPlayerEntity> room = new ArrayList<>(TrapArena.combatants());
            if (room.isEmpty()) {
                return true;
            }
            Collections.shuffle(room, new java.util.Random(random.nextLong()));
            for (int i = 0; i < Math.min(2, room.size()); i++) {
                mark(room.get(i), 30);
            }
            sound(SoundEvents.BLOCK_BEACON_POWER_SELECT, 1.5F, 0.8F);
            sound(SoundEvents.ENTITY_GHAST_WARN, 1.0F, 1.4F);
        }
        return tick >= 36;
    }

    private void mark(ServerPlayerEntity who, int delay) {
        marks.add(new Mark(who, age + delay));
        TrapArena.title(who, Text.literal("PIORUN").formatted(Formatting.WHITE, Formatting.BOLD),
                Text.literal("Do piorunochronu.").formatted(Formatting.GRAY), 0, 25, 5);
        sound(who, SoundEvents.BLOCK_COPPER_BULB_TURN_ON, 1.0F, 1.5F);
    }

    private void tickMarks(ServerWorld world) {
        if (marks.isEmpty()) {
            return;
        }
        List<Mark> done = new ArrayList<>();
        for (Mark mark : marks) {
            ServerPlayerEntity who = mark.who();
            if (!who.isAlive() || !TrapArena.inArena(who)) {
                done.add(mark);
                continue;
            }
            int left = mark.at() - age;
            if (left > 0) {
                world.spawnParticles(ParticleTypes.ELECTRIC_SPARK, who.getX(), who.getY() + 1.0 + random.nextDouble() * 7.0,
                        who.getZ(), 3, 0.3, 0.5, 0.3, 0.05);
                for (int i = 0; i < 8; i++) {
                    double a = i * Math.PI / 4 + age * 0.2;
                    world.spawnParticles(WHITE, who.getX() + Math.cos(a) * 1.5, who.getY() + 0.1,
                            who.getZ() + Math.sin(a) * 1.5, 1, 0, 0, 0, 0);
                }
                if (left % 6 == 0) {
                    sound(who, SoundEvents.BLOCK_COPPER_BULB_TURN_ON, 0.8F, 1.2F + (30 - left) * 0.02F);
                }
                continue;
            }
            strike(world, who);
            done.add(mark);
        }
        marks.removeAll(done);
    }

    private void strike(ServerWorld world, ServerPlayerEntity who) {
        BlockPos rod = nearestRod(who.getPos());
        if (rod != null) {
            double dx = rod.getX() + 0.5 - who.getX();
            double dz = rod.getZ() + 0.5 - who.getZ();
            if (dx * dx + dz * dz <= ArenaMath.ROD_RANGE * ArenaMath.ROD_RANGE) {
                Vec3d at = new Vec3d(rod.getX() + 0.5, rod.getY(), rod.getZ() + 0.5);
                lightning(world, at);
                world.spawnParticles(ParticleTypes.ELECTRIC_SPARK, at.x, at.y + 1.0, at.z, 25, 0.4, 0.6, 0.4, 0.2);
                who.sendMessage(Text.literal("Piorunochron.").formatted(Formatting.AQUA), true);
                sound(who, SoundEvents.BLOCK_COPPER_BULB_TURN_ON, 1.0F, 0.8F);
                return;
            }
        }
        Vec3d at = who.getPos();
        lightning(world, at);
        for (ServerPlayerEntity player : TrapArena.combatants()) {
            double dx = player.getX() - at.x;
            double dz = player.getZ() - at.z;
            if (dx * dx + dz * dz > 2.6 * 2.6 || Math.abs(player.getY() - at.y) > 3.0) {
                continue;
            }
            hurtPlayer(world, player, ArenaMath.BOLT_DAMAGE, world.getDamageSources().create(DamageTypes.LIGHTNING_BOLT, this));
            TrapNet.flash(player, 0xffffff, 6);
            TrapNet.shake(player, 0.8F, 8);
        }
    }

    private void lightning(ServerWorld world, Vec3d at) {
        LightningEntity bolt = new LightningEntity(EntityType.LIGHTNING_BOLT, world);
        bolt.setCosmetic(true);
        bolt.refreshPositionAndAngles(at.x, at.y, at.z, 0.0F, 0.0F);
        world.spawnEntity(bolt);
        world.spawnParticles(ParticleTypes.FLASH, at.x, at.y + 1.0, at.z, 1, 0, 0, 0, 0);
    }

    /** Zejście: down to the floor for six seconds. The window, with a shock for hugging it. */
    private boolean descend(ServerWorld world, int tick) {
        Vec3d at = getPos();
        if (tick == 1) {
            hoverHeight = LOW;
            sound(SoundEvents.ENTITY_ENDER_DRAGON_FLAP, 1.5F, 0.5F);
            sound(SoundEvents.ENTITY_GHAST_WARN, 1.5F, 0.8F);
            for (ServerPlayerEntity player : TrapArena.arenaPlayers()) {
                TrapArena.title(player, Text.literal("SCHODZI").formatted(Formatting.AQUA, Formatting.BOLD),
                        Text.literal("Teraz. Bij.").formatted(Formatting.YELLOW), 5, 30, 10);
            }
        }
        if (tick == 25) {
            sound(SoundEvents.ENTITY_GENERIC_EXPLODE.value(), 1.5F, 0.8F);
            sound(SoundEvents.ENTITY_LIGHTNING_BOLT_IMPACT, 1.5F, 1.0F);
            world.spawnParticles(ParticleTypes.GUST_EMITTER_LARGE, at.x, at.y + 0.2, at.z, 1, 0, 0, 0, 0);
            world.spawnParticles(ParticleTypes.CLOUD, at.x, at.y + 0.3, at.z, 40, 1.5, 0.3, 1.5, 0.2);
            for (ServerPlayerEntity player : TrapArena.combatants()) {
                double dx = player.getX() - at.x;
                double dz = player.getZ() - at.z;
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > 3.5 || Math.abs(player.getY() - at.y) > 3.0) {
                    continue;
                }
                hurtPlayer(world, player, ArenaMath.SHOCK_DAMAGE, world.getDamageSources().create(DamageTypes.LIGHTNING_BOLT, this));
                Vec3d away = d > 0.01 ? new Vec3d(dx / d, 0.0, dz / d) : rig.forward();
                player.setVelocity(away.x * 0.8, 0.5, away.z * 0.8);
                player.velocityModified = true;
                TrapNet.shake(player, 1.0F, 8);
            }
            for (ServerPlayerEntity player : TrapArena.arenaPlayers()) {
                TrapNet.shake(player, 0.5F, 8);
            }
        }
        if (tick > 25 && tick < 145) {
            if (tick % 4 == 0) {
                for (int i = 0; i < 6; i++) {
                    double a = i * Math.PI / 3 + tick * 0.15;
                    world.spawnParticles(ParticleTypes.ELECTRIC_SPARK, at.x + Math.cos(a) * 3.2, at.y + 0.3,
                            at.z + Math.sin(a) * 3.2, 1, 0.1, 0.2, 0.1, 0.02);
                }
            }
            if (tick % 30 == 0) {
                sound(SoundEvents.BLOCK_COPPER_BULB_TURN_OFF, 1.2F, 0.6F);
                for (ServerPlayerEntity player : TrapArena.combatants()) {
                    double dx = player.getX() - at.x;
                    double dz = player.getZ() - at.z;
                    if (dx * dx + dz * dz > 3.2 * 3.2 || Math.abs(player.getY() - at.y) > 3.0) {
                        continue;
                    }
                    hurtPlayer(world, player, ArenaMath.STATIC_DAMAGE, world.getDamageSources().create(DamageTypes.LIGHTNING_BOLT, this));
                    world.spawnParticles(ParticleTypes.ELECTRIC_SPARK, player.getX(), player.getEyeY(), player.getZ(),
                            10, 0.3, 0.4, 0.3, 0.1);
                    TrapNet.flash(player, 0xffffff, 4);
                }
            }
        }
        if (tick == 145) {
            hoverHeight = HOVER;
            sound(SoundEvents.ENTITY_GHAST_SHOOT, 1.2F, 0.6F);
            sound(SoundEvents.ENTITY_ENDER_DRAGON_FLAP, 1.2F, 0.7F);
        }
        return tick >= 165;
    }

    /** Grad: six seconds of stones. Near a brazier, or keep moving. */
    private boolean hailCast(ServerWorld world, int tick) {
        if (tick == 1) {
            sound(SoundEvents.BLOCK_POWDER_SNOW_BREAK, 1.5F, 0.6F);
            sound(SoundEvents.WEATHER_RAIN_ABOVE, 1.5F, 0.5F);
            for (ServerPlayerEntity player : TrapArena.arenaPlayers()) {
                TrapArena.title(player, Text.literal("GRAD").formatted(Formatting.WHITE, Formatting.BOLD),
                        Text.literal("Przy ogniu, i w ruchu.").formatted(Formatting.GRAY), 5, 30, 10);
            }
        }
        if (tick % 4 == 0 && tick < 120) {
            List<ServerPlayerEntity> room = TrapArena.combatants();
            Vec3d spot;
            if (!room.isEmpty() && random.nextInt(10) < 6) {
                ServerPlayerEntity who = room.get(random.nextInt(room.size()));
                double a = random.nextDouble() * Math.PI * 2;
                double r = random.nextDouble() * 3.0;
                spot = who.getPos().add(Math.cos(a) * r, 0.0, Math.sin(a) * r);
            } else {
                spot = onFloor(random.nextDouble() * Math.PI * 2, random.nextDouble() * (kind().pitRadius() - 3.0));
            }
            hail.add(new Hail(new Vec3d(spot.x, kind().centre().y, spot.z), age + 15));
        }
        return tick >= 130;
    }

    private void tickHail(ServerWorld world) {
        if (hail.isEmpty()) {
            return;
        }
        List<Hail> landed = new ArrayList<>();
        for (Hail stone : hail) {
            Vec3d at = stone.at();
            int left = stone.land() - age;
            if (left > 0) {
                double y = at.y + 0.3 + left * 0.75;
                world.spawnParticles(ParticleTypes.SNOWFLAKE, at.x, y, at.z, 3, 0.2, 0.2, 0.2, 0.0);
                if (left % 3 == 0) {
                    for (int i = 0; i < 6; i++) {
                        double a = i * Math.PI / 3;
                        world.spawnParticles(ICE, at.x + Math.cos(a) * 1.6, at.y + 0.1, at.z + Math.sin(a) * 1.6,
                                1, 0, 0, 0, 0);
                    }
                }
                continue;
            }
            landed.add(stone);
            world.playSound(null, at.x, at.y, at.z, SoundEvents.BLOCK_POWDER_SNOW_BREAK, SoundCategory.HOSTILE,
                    1.0F, 0.8F + random.nextFloat() * 0.4F);
            world.spawnParticles(ParticleTypes.SNOWFLAKE, at.x, at.y + 0.5, at.z, 25, 0.6, 0.3, 0.6, 0.1);
            world.spawnParticles(ParticleTypes.ITEM_SNOWBALL, at.x, at.y + 0.5, at.z, 12, 0.4, 0.3, 0.4, 0.1);
            for (ServerPlayerEntity player : TrapArena.combatants()) {
                double dx = player.getX() - at.x;
                double dz = player.getZ() - at.z;
                if (dx * dx + dz * dz > 1.6 * 1.6 || Math.abs(player.getY() - at.y) > 2.5) {
                    continue;
                }
                hurtPlayer(world, player, ArenaMath.HAIL_DAMAGE, world.getDamageSources().freeze());
                player.setFrozenTicks(Math.min(300, player.getFrozenTicks() + ArenaMath.HAIL_FREEZE_TICKS));
                sound(player, SoundEvents.ENTITY_PLAYER_HURT_FREEZE, 1.0F, 1.0F);
                TrapNet.flash(player, 0xbfe8ff, 8);
            }
        }
        hail.removeAll(landed);
    }

    /** Trąba: a column that wanders toward the nearest of you, lifts, and throws. */
    private boolean twisterCast(ServerWorld world, int tick) {
        if (tick == 1) {
            twister = new Vec3d(getX(), kind().centre().y, getZ());
            twisterUntil = age + 160;
            caught.clear();
            sound(SoundEvents.ENTITY_BREEZE_IDLE_GROUND, 1.5F, 0.5F);
            sound(SoundEvents.ITEM_ELYTRA_FLYING, 1.0F, 0.4F);
            for (ServerPlayerEntity player : TrapArena.arenaPlayers()) {
                TrapArena.title(player, Text.literal("TRĄBA").formatted(Formatting.AQUA, Formatting.BOLD),
                        Text.literal("Zejdź jej z drogi.").formatted(Formatting.GRAY), 5, 30, 10);
            }
        }
        return tick >= 10;
    }

    private void tickTwister(ServerWorld world) {
        if (twister == null) {
            return;
        }
        Vec3d centre = kind().centre();
        if (age >= twisterUntil) {
            world.spawnParticles(ParticleTypes.POOF, twister.x, twister.y + 1.0, twister.z, 20, 1.0, 1.5, 1.0, 0.05);
            world.playSound(null, twister.x, twister.y, twister.z, SoundEvents.ITEM_ELYTRA_FLYING, SoundCategory.HOSTILE,
                    0.6F, 0.4F);
            twister = null;
            caught.clear();
            return;
        }
        ServerPlayerEntity nearest = null;
        double best = Double.MAX_VALUE;
        for (ServerPlayerEntity player : TrapArena.combatants()) {
            double dx = player.getX() - twister.x;
            double dz = player.getZ() - twister.z;
            double d = dx * dx + dz * dz;
            if (d < best) {
                best = d;
                nearest = player;
            }
        }
        if (nearest != null) {
            Vec3d to = new Vec3d(nearest.getX() - twister.x, 0.0, nearest.getZ() - twister.z);
            double d = to.length();
            if (d > 0.3) {
                twister = twister.add(to.normalize().multiply(Math.min(0.12, d)));
            }
        }
        double cx = twister.x - centre.x;
        double cz = twister.z - centre.z;
        double cr = Math.sqrt(cx * cx + cz * cz);
        double limit = kind().pitRadius() - 2.5;
        if (cr > limit) {
            twister = new Vec3d(centre.x + cx * limit / cr, twister.y, centre.z + cz * limit / cr);
        }
        for (int k = 0; k < 6; k++) {
            double h = (age * 0.15 + k * 1.2) % 7.0;
            double rr = 1.0 + h * 0.25;
            double a = age * 0.45 + k * 1.05 + h * 0.5;
            world.spawnParticles(ParticleTypes.CLOUD, twister.x + Math.cos(a) * rr, twister.y + h,
                    twister.z + Math.sin(a) * rr, 1, 0.0, 0.0, 0.0, 0.0);
        }
        if (age % 5 == 0) {
            world.spawnParticles(ParticleTypes.SMALL_GUST, twister.x, twister.y + 0.5, twister.z, 1, 0.5, 0.3, 0.5, 0.0);
        }
        if (age % 20 == 0) {
            world.playSound(null, twister.x, twister.y + 2.0, twister.z, SoundEvents.ENTITY_BREEZE_IDLE_GROUND,
                    SoundCategory.HOSTILE, 1.2F, 0.5F);
        }
        for (ServerPlayerEntity player : TrapArena.combatants()) {
            double dx = player.getX() - twister.x;
            double dz = player.getZ() - twister.z;
            double d2 = dx * dx + dz * dz;
            Integer since = caught.get(player.getUuid());
            boolean inside = d2 <= 2.2 * 2.2 && player.getY() < twister.y + 8.0;
            if (!inside) {
                if (since != null && age - since > 60) {
                    caught.remove(player.getUuid());
                }
                continue;
            }
            if (since == null) {
                since = age;
                caught.put(player.getUuid(), since);
                hurtPlayer(world, player, ArenaMath.TWISTER_DAMAGE,
                        world.getDamageSources().create(DamageTypes.WIND_CHARGE, this));
                TrapArena.title(player, Text.literal("W GÓRĘ").formatted(Formatting.AQUA, Formatting.BOLD),
                        Text.empty(), 0, 15, 5);
                sound(player, SoundEvents.ENTITY_BREEZE_WIND_BURST.value(), 1.0F, 1.2F);
            }
            int t = age - since;
            if (t < 25) {
                double a = age * 0.5;
                player.setVelocity(-Math.sin(a) * 0.35, 0.55, Math.cos(a) * 0.35);
                player.velocityModified = true;
                player.fallDistance = 0.0;
            } else if (t == 25) {
                Vec3d away = new Vec3d(dx, 0.0, dz);
                away = away.lengthSquared() < 0.01 ? rig.forward() : away.normalize();
                player.setVelocity(away.x, 0.35, away.z);
                player.velocityModified = true;
                sound(player, SoundEvents.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.0F, 0.6F);
            }
        }
    }

    /** Bryzy: two breezes for forty seconds. They throw, and the edge is right there. */
    private boolean galesCast(ServerWorld world, int tick) {
        if (tick == 1) {
            double base = random.nextDouble() * Math.PI * 2;
            for (int i = 0; i < 2; i++) {
                Vec3d spot = onFloor(base + i * Math.PI, 13.0);
                BreezeEntity breeze = EntityType.BREEZE.create(world, SpawnReason.EVENT);
                if (breeze == null) {
                    return true;
                }
                breeze.refreshPositionAndAngles(spot.x, spot.y, spot.z, yawToward(spot) + 180.0F, 0.0F);
                breeze.initialize(world, world.getLocalDifficulty(BlockPos.ofFloored(spot)), SpawnReason.EVENT, null);
                breeze.setCustomName(Text.literal("Podmuch").formatted(Formatting.AQUA));
                breeze.setCustomNameVisible(true);
                breeze.setPersistent();
                world.spawnEntity(breeze);
                TrapArena.track(breeze);
                gales.add(breeze);
                world.spawnParticles(ParticleTypes.GUST_EMITTER_SMALL, spot.x, spot.y + 0.5, spot.z, 1, 0, 0, 0, 0);
                world.spawnParticles(ParticleTypes.CLOUD, spot.x, spot.y + 1.0, spot.z, 20, 0.5, 0.8, 0.5, 0.05);
            }
            galesUntil = age + 800;
            sound(SoundEvents.ENTITY_BREEZE_WIND_BURST.value(), 1.5F, 0.8F);
            sound(SoundEvents.ENTITY_BREEZE_IDLE_AIR, 1.2F, 0.7F);
            for (ServerPlayerEntity player : TrapArena.arenaPlayers()) {
                TrapArena.title(player, Text.literal("BRYZY").formatted(Formatting.AQUA, Formatting.BOLD),
                        Text.literal("Dwie. Zwiewają z platformy.").formatted(Formatting.GRAY), 5, 30, 10);
            }
        }
        return tick >= 20;
    }

    /** Nawałnica: ten seconds of wind and bolts together. The pillars, to the end. */
    private boolean tempest(ServerWorld world, int tick) {
        if (tick == 1) {
            sound(SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, 2.0F, 0.5F);
            sound(SoundEvents.WEATHER_RAIN_ABOVE, 2.0F, 0.4F);
            for (ServerPlayerEntity player : TrapArena.arenaPlayers()) {
                TrapArena.title(player, Text.literal("NAWAŁNICA").formatted(Formatting.DARK_AQUA, Formatting.BOLD),
                        Text.literal("Przy piorunochronach. Do końca.").formatted(Formatting.GRAY), 5, 40, 10);
            }
        }
        push(world, ArenaMath.WIND_PUSH * 0.7);
        gust(world, 3);
        if (tick % 30 == 0) {
            ServerPlayerEntity pick = pickTarget();
            if (pick != null) {
                mark(pick, 15);
            }
        }
        if (tick % 15 == 0) {
            sound(SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, 0.6F, 1.0F + random.nextFloat() * 0.5F);
        }
        Vec3d centre = kind().centre();
        world.spawnParticles(ParticleTypes.RAIN, centre.x, centre.y + 8.0, centre.z, 20, 15.0, 1.0, 15.0, 0.0);
        return tick >= 200;
    }
}
