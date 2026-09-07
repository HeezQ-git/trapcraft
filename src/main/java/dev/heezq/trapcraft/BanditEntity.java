package dev.heezq.trapcraft;

import eu.pb4.polymer.core.api.entity.PolymerEntityUtils;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.VindicatorEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ItemStackParticleEffect;
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
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Bandyta. The one-armed bandit.
 *
 * Disguised as an iron golem: a golem's box is a cabinet's box, and its
 * voice -- iron on iron -- is what a walking slot machine should sound
 * like when hit.
 *
 * Six casts across three phases, and every one of them is a wager. The
 * lever is a telegraphed cone; the reels are a real roll with four
 * outcomes, two of them good for you; the roulette turns the floor under
 * your feet and pays whoever stood on the ball's colour; the bouncers are
 * two vindicators; double-or-nothing picks the top damage and doubles both
 * directions for eight seconds; and the payout opens the cabinet -- it
 * stands still spitting emeralds and takes half again as much.
 */
public class BanditEntity extends ArenaBossEntity {
    public static EntityType<BanditEntity> TYPE;

    private static final float SCALE = 1.3F;

    static final Ability LEVER = new Ability("lever", 200, 1);
    static final Ability REELS = new Ability("reels", 300, 1);
    static final Ability ROULETTE = new Ability("roulette", 400, 2);
    static final Ability BOUNCERS = new Ability("bouncers", 600, 2);
    static final Ability DOUBLE = new Ability("double", 500, 3);
    static final Ability CASH = new Ability("cash", 700, 3);
    private static final List<Ability> ABILITIES = List.of(LEVER, REELS, ROULETTE, BOUNCERS, DOUBLE, CASH);
    public static final List<String> ABILITY_IDS = ABILITIES.stream().map(Ability::id).toList();

    private static final DustParticleEffect GOLD = new DustParticleEffect(0xffc24a, 1.2F);
    private static final DustParticleEffect[] BALL = {
            new DustParticleEffect(0xff3344, 1.6F),
            new DustParticleEffect(0x222226, 1.6F),
            new DustParticleEffect(0x5ee04a, 1.6F)};
    private static final ItemStackParticleEffect COINS =
            new ItemStackParticleEffect(ParticleTypes.ITEM, new ItemStack(Items.GOLD_NUGGET));

    private static final ArenaProjectileEntity.Spec CHIP = new ArenaProjectileEntity.Spec(
            TrapCraft.id("bandit_coin"), TrapCraft.id("bandit_coin"), 0.6F, false, false,
            ArenaMath.CHIP_DAMAGE, 0xffc24a, 60, SoundEvents.ENTITY_ITEM_PICKUP);

    private enum Outcome { CHIPS, JACKPOT, BONUS, BOUNCERS }

    private final Set<UUID> leverHit = new HashSet<>();
    private Outcome outcome = Outcome.CHIPS;
    private int safeColour;
    private int ringShift;
    private ServerPlayerEntity doubled;
    private int doubleUntil;

    public BanditEntity(EntityType<? extends HostileEntity> type, World world) {
        super(type, world);
    }

    public static void register() {
        RegistryKey<EntityType<?>> key = RegistryKey.of(RegistryKeys.ENTITY_TYPE, TrapCraft.id("bandit"));
        TYPE = Registry.register(Registries.ENTITY_TYPE, key,
                EntityType.Builder.<BanditEntity>create(BanditEntity::new, SpawnGroup.MONSTER)
                        // An iron golem's box, before the SCALE attribute.
                        .dimensions(1.4F, 2.7F)
                        .eyeHeight(2.2F)
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
                .add(EntityAttributes.MOVEMENT_SPEED, 0.26)
                .add(EntityAttributes.ATTACK_DAMAGE, ArenaMath.HEAVY_MELEE_DAMAGE)
                .add(EntityAttributes.FOLLOW_RANGE, 64.0)
                .add(EntityAttributes.KNOCKBACK_RESISTANCE, 1.0)
                .add(EntityAttributes.ARMOR, 8.0)
                .add(EntityAttributes.STEP_HEIGHT, 1.0)
                .add(EntityAttributes.SCALE, SCALE);
    }

    // --- the base's questions -------------------------------------------------

    @Override
    public ArenaBoss kind() {
        return BanditBoss.INSTANCE;
    }

    @Override
    protected List<Ability> abilities() {
        return ABILITIES;
    }

    @Override
    protected DisplayRig makeRig() {
        return BanditRig.attachTo(this);
    }

    private BanditRig body() {
        return (BanditRig) rig;
    }

    @Override
    protected EntityType<?> disguise() {
        return EntityType.IRON_GOLEM;
    }

    @Override
    protected void afflict(ServerPlayerEntity player, int stacks) {
        TrapArena.stack(player, TrapContent.stakeEffect, stacks, ArenaMath.STAKE_MAX, ArenaMath.STAKE_TICKS);
    }

    private static int stakes(ServerPlayerEntity player) {
        StatusEffectInstance stake = player.getStatusEffect(TrapContent.stakeEffect);
        return stake == null ? 0 : stake.getAmplifier() + 1;
    }

    @Override
    protected float damageMultiplier(ServerPlayerEntity player) {
        float m = ArenaMath.stakeMultiplier(stakes(player));
        return player == doubled ? m * 2.0F : m;
    }

    /** Both directions of the wager: the doubled player hits for two, and the open cabinet takes more. */
    @Override
    public boolean damage(ServerWorld world, DamageSource source, float amount) {
        if (doubled != null && source.getAttacker() == doubled) {
            amount *= 2.0F;
        }
        if (casting == CASH) {
            amount *= ArenaMath.CASH_TAKEN;
        }
        return super.damage(world, source, amount);
    }

    @Override
    public Vec3d throwPos() {
        return rig == null ? super.throwPos() : body().trayPos();
    }

    // --- noise and light ------------------------------------------------------

    @Override
    protected void introTick(ServerWorld world, float progress) {
        Vec3d at = getPos();
        world.spawnParticles(ParticleTypes.WAX_ON, at.x, at.y + 0.5, at.z, 6, 1.2, 0.5, 1.2, 0.2);
        world.spawnParticles(ParticleTypes.ELECTRIC_SPARK, at.x, at.y + 1.0, at.z, 2, 1.0, 1.0, 1.0, 0.1);
        if (age == 1) {
            sound(SoundEvents.BLOCK_BEACON_ACTIVATE, 1.5F, 1.2F);
            sound(SoundEvents.ENTITY_IRON_GOLEM_REPAIR, 1.5F, 0.5F);
        }
        if (age % 20 == 0) {
            sound(SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), 1.0F, 0.5F + progress);
        }
        if (age % 10 == 0) {
            for (ServerPlayerEntity player : TrapArena.arenaPlayers()) {
                TrapNet.shake(player, 0.3F + 0.4F * progress, 8);
            }
        }
        if (age == INTRO_TICKS - 10) {
            sound(SoundEvents.ENTITY_EVOKER_CELEBRATE, 1.5F, 0.7F);
            sound(SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), 1.5F, 1.5F);
        }
    }

    @Override
    protected void breathe(ServerWorld world) {
        Vec3d at = getPos();
        if (age % 4 == 0) {
            world.spawnParticles(ParticleTypes.WAX_ON, at.x, at.y + 3.6, at.z, 1, 0.6, 0.2, 0.6, 0.02);
        }
        if (age % 120 == 0) {
            body().spin(8);
        }
        if (doubled != null && (age >= doubleUntil || !doubled.isAlive() || !TrapArena.isCombatant(doubled))) {
            endDouble();
        }
    }

    @Override
    protected SoundEvent ambient(int roll) {
        return switch (roll) {
            case 0 -> SoundEvents.BLOCK_NOTE_BLOCK_BIT.value();
            case 1 -> SoundEvents.ENTITY_IRON_GOLEM_REPAIR;
            default -> SoundEvents.BLOCK_BEACON_AMBIENT;
        };
    }

    @Override
    protected void onStrike(ServerWorld world, Entity target) {
        body().pull();
        sound(SoundEvents.ENTITY_IRON_GOLEM_ATTACK, 1.0F, 0.8F);
        sound(SoundEvents.BLOCK_ANVIL_LAND, 0.5F, 1.6F);
        Vec3d at = target.getPos().add(0.0, target.getHeight() * 0.5, 0.0);
        world.spawnParticles(ParticleTypes.CRIT, at.x, at.y, at.z, 8, 0.3, 0.3, 0.3, 0.2);
        if (target instanceof ServerPlayerEntity player) {
            afflict(player, 1);
        }
    }

    @Override
    protected void onHurt(ServerWorld world, ServerPlayerEntity by, float dealt) {
        sound(SoundEvents.ENTITY_IRON_GOLEM_HURT, 0.7F, 1.2F);
        sound(SoundEvents.BLOCK_ANVIL_LAND, 0.3F, 1.8F);
        Vec3d at = getPos().add(0.0, 2.0, 0.0);
        world.spawnParticles(ParticleTypes.WAX_OFF, at.x, at.y, at.z, 5, 0.5, 0.6, 0.5, 0.1);
    }

    @Override
    protected void deathSounds() {
        sound(SoundEvents.ENTITY_IRON_GOLEM_DEATH, 1.6F, 0.6F);
        sound(SoundEvents.BLOCK_ANVIL_LAND, 1.5F, 0.5F);
        sound(SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), 1.2F, 0.5F);
    }

    @Override
    protected void deathTick(ServerWorld world) {
        Vec3d at = getPos().add(0.0, 1.5, 0.0);
        if (deathTime % 5 == 0) {
            world.spawnParticles(COINS, at.x, at.y, at.z, 12, 0.6, 0.6, 0.6, 0.15);
            world.spawnParticles(ParticleTypes.LARGE_SMOKE, at.x, at.y + 1.0, at.z, 4, 0.5, 0.5, 0.5, 0.02);
        }
        if (deathTime % 10 == 0) {
            sound(SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), 1.0F, Math.max(0.5F, 1.2F - deathTime * 0.01F));
            sound(SoundEvents.BLOCK_VAULT_EJECT_ITEM, 0.8F, 0.8F);
        }
    }

    @Override
    protected void onDying() {
        endDouble();
        if (rig != null) {
            body().setOpen(false);
        }
        if (getWorld() instanceof ServerWorld world) {
            BanditBoss.INSTANCE.spinRing(world, 0);
        }
    }

    @Override
    protected void onBegin(Ability ability) {
        leverHit.clear();
    }

    @Override
    protected void interrupted(Ability ability) {
        if (ability == ROULETTE && getWorld() instanceof ServerWorld world) {
            BanditBoss.INSTANCE.spinRing(world, 0);
        }
        if (ability == CASH || ability == REELS) {
            body().setOpen(false);
        }
    }

    @Override
    protected boolean cast(ServerWorld world, Ability ability, int tick) {
        if (ability == LEVER) {
            return lever(world, tick);
        }
        if (ability == REELS) {
            return reels(world, tick);
        }
        if (ability == ROULETTE) {
            return roulette(world, tick);
        }
        if (ability == BOUNCERS) {
            return bouncers(world, tick);
        }
        if (ability == DOUBLE) {
            return doubleOrNothing(world, tick);
        }
        if (ability == CASH) {
            return cash(world, tick);
        }
        return true;
    }

    // --- casts ---------------------------------------------------------------

    /** Dźwignia: the arm comes down across a cone. Not in front of it. */
    private boolean lever(ServerWorld world, int tick) {
        if (tick == 1) {
            if (getTarget() != null) {
                float yaw = yawToward(getTarget().getPos());
                setYaw(yaw);
                setBodyYaw(yaw);
                setHeadYaw(yaw);
            }
            body().pull();
            sound(SoundEvents.ITEM_CROSSBOW_LOADING_END.value(), 1.4F, 0.7F);
            sound(SoundEvents.ENTITY_IRON_GOLEM_ATTACK, 1.2F, 0.6F);
        }
        Vec3d at = getPos();
        Vec3d forward = rig.forward();
        if (tick < 8) {
            for (int i = 0; i <= 8; i++) {
                Vec3d edge = forward.rotateY((float) Math.toRadians(-50 + i * 12.5));
                world.spawnParticles(GOLD, at.x + edge.x * 6.5, at.y + 0.2, at.z + edge.z * 6.5, 1, 0, 0, 0, 0);
            }
            return false;
        }
        if (tick == 8) {
            sound(SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, 1.2F, 0.6F);
            sound(SoundEvents.BLOCK_ANVIL_LAND, 0.8F, 1.5F);
            for (int i = -1; i <= 1; i++) {
                Vec3d p = at.add(forward.rotateY((float) Math.toRadians(i * 30)).multiply(3.5));
                world.spawnParticles(ParticleTypes.SWEEP_ATTACK, p.x, at.y + 1.2, p.z, 1, 0, 0, 0, 0);
            }
            for (ServerPlayerEntity player : TrapArena.combatants()) {
                if (leverHit.contains(player.getUuid())) {
                    continue;
                }
                Vec3d to = player.getPos().subtract(at);
                double d = to.horizontalLength();
                if (d > 6.5 || d < 0.01) {
                    continue;
                }
                Vec3d flat = new Vec3d(to.x, 0.0, to.z).normalize();
                if (forward.dotProduct(flat) < Math.cos(Math.toRadians(50))) {
                    continue;
                }
                leverHit.add(player.getUuid());
                hurtPlayer(world, player, ArenaMath.LEVER_DAMAGE, world.getDamageSources().mobAttack(this));
                player.setVelocity(flat.x * 1.1, 0.5, flat.z * 1.1);
                player.velocityModified = true;
                TrapNet.shake(player, 1.0F, 10);
                sound(player, SoundEvents.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.0F, 0.7F);
            }
            return false;
        }
        return tick >= 20;
    }

    /** Bębny: a real roll. Chips, a jackpot for it, a bonus for you, or the bouncers. */
    private boolean reels(ServerWorld world, int tick) {
        if (tick == 1) {
            body().spin(30);
            outcome = roll();
            sound(SoundEvents.BLOCK_NOTE_BLOCK_BIT.value(), 1.2F, 1.2F);
            for (ServerPlayerEntity player : TrapArena.arenaPlayers()) {
                TrapArena.title(player, Text.literal("BĘBNY").formatted(Formatting.GOLD, Formatting.BOLD),
                        Text.literal("Kręcą się.").formatted(Formatting.GRAY), 5, 20, 10);
            }
        }
        Vec3d at = getPos();
        if (tick <= 30) {
            if (tick % 5 == 0) {
                sound(SoundEvents.BLOCK_NOTE_BLOCK_HAT.value(), 0.8F, 1.0F + tick * 0.02F);
            }
            Vec3d face = at.add(rig.forward().multiply(0.9)).add(0.0, 2.0, 0.0);
            world.spawnParticles(ParticleTypes.WAX_ON, face.x, face.y, face.z, 2, 0.5, 0.3, 0.1, 0.05);
            return false;
        }
        switch (outcome) {
            case CHIPS -> {
                if (tick == 31) {
                    body().setOpen(true);
                    sound(SoundEvents.BLOCK_VAULT_OPEN_SHUTTER, 1.2F, 1.3F);
                    for (ServerPlayerEntity player : TrapArena.arenaPlayers()) {
                        TrapArena.title(player, Text.literal("ŻETONY").formatted(Formatting.YELLOW, Formatting.BOLD),
                                Text.literal("Zbij je albo zejdź z linii.").formatted(Formatting.GRAY), 0, 20, 10);
                    }
                }
                int end = 31 + ArenaMath.CHIPS * 2;
                if (tick < end && tick % 2 == 1) {
                    ServerPlayerEntity target = pickTarget();
                    if (target != null) {
                        Vec3d from = body().trayPos();
                        Vec3d aim = target.getEyePos().subtract(from).normalize()
                                .rotateY((float) Math.toRadians((random.nextFloat() - 0.5F) * 24.0F))
                                .add(0.0, 0.08, 0.0);
                        ArenaProjectileEntity.launch(world, this, from, aim, target, CHIP);
                        sound(SoundEvents.BLOCK_VAULT_EJECT_ITEM, 0.7F, 1.4F + random.nextFloat() * 0.3F);
                        world.spawnParticles(ParticleTypes.WAX_ON, from.x, from.y, from.z, 4, 0.2, 0.2, 0.2, 0.1);
                    }
                }
                if (tick >= end + 5) {
                    body().setOpen(false);
                    return true;
                }
                return false;
            }
            case JACKPOT -> {
                if (tick == 31) {
                    heal(getMaxHealth() * ArenaMath.JACKPOT_HEAL);
                    BanditBoss.INSTANCE.flash(world, world.getServer().getTicks());
                    sound(SoundEvents.ENTITY_VILLAGER_YES, 1.5F, 0.6F);
                    world.spawnParticles(ParticleTypes.TOTEM_OF_UNDYING, at.x, at.y + 2.5, at.z, 60, 0.8, 0.8, 0.8, 0.4);
                    world.spawnParticles(COINS, at.x, at.y + 2.0, at.z, 40, 1.0, 0.8, 1.0, 0.2);
                    for (ServerPlayerEntity player : TrapArena.arenaPlayers()) {
                        TrapArena.title(player, Text.literal("JACKPOT").formatted(Formatting.RED, Formatting.BOLD),
                                Text.literal("Dla niego. +" + Math.round(ArenaMath.JACKPOT_HEAL * 100) + "%.")
                                        .formatted(Formatting.GRAY), 0, 30, 10);
                    }
                }
                if (tick == 31 || tick == 35 || tick == 39) {
                    sound(SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), 1.5F, 0.8F + (tick - 31) * 0.1F);
                }
                return tick >= 50;
            }
            case BONUS -> {
                if (tick == 31) {
                    body().setOpen(true);
                    sound(SoundEvents.ENTITY_PLAYER_LEVELUP, 1.2F, 1.2F);
                    sound(SoundEvents.BLOCK_VAULT_OPEN_SHUTTER, 1.0F, 1.0F);
                    for (ServerPlayerEntity player : TrapArena.combatants()) {
                        player.addStatusEffect(new StatusEffectInstance(TrapContent.adrenalineEffect, 20 * 8, 0,
                                false, true, true));
                        player.heal(4.0F);
                        world.spawnParticles(ParticleTypes.HAPPY_VILLAGER, player.getX(), player.getEyeY(),
                                player.getZ(), 12, 0.4, 0.4, 0.4, 0.1);
                    }
                    for (ServerPlayerEntity player : TrapArena.arenaPlayers()) {
                        TrapArena.title(player, Text.literal("BONUS").formatted(Formatting.GREEN, Formatting.BOLD),
                                Text.literal("Automat sypnął. Dla was.").formatted(Formatting.GRAY), 0, 30, 10);
                    }
                }
                if (tick >= 50) {
                    body().setOpen(false);
                    return true;
                }
                return false;
            }
            case BOUNCERS -> {
                if (tick == 31) {
                    summonBouncers(world);
                }
                return tick >= 40;
            }
        }
        return true;
    }

    private Outcome roll() {
        int r = random.nextInt(100);
        if (r < 40) {
            return Outcome.CHIPS;
        }
        if (r < 60) {
            return Outcome.JACKPOT;
        }
        if (r < 80) {
            return Outcome.BONUS;
        }
        return phase >= 2 ? Outcome.BOUNCERS : Outcome.CHIPS;
    }

    /** Ruletka: the floor turns, the ball says which colour pays. Stand on it, or off the ring. */
    private boolean roulette(ServerWorld world, int tick) {
        BanditBoss roof = BanditBoss.INSTANCE;
        Vec3d centre = kind().centre();
        if (tick == 1) {
            safeColour = random.nextInt(3);
            ringShift = 0;
            sound(SoundEvents.ITEM_TRIDENT_RIPTIDE_3.value(), 1.2F, 0.8F);
            sound(SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), 1.0F, 0.7F);
            Text colour = Text.literal(BanditBoss.COLOUR_NAMES[safeColour])
                    .formatted(BanditBoss.COLOUR_STYLES[safeColour], Formatting.BOLD);
            for (ServerPlayerEntity player : TrapArena.arenaPlayers()) {
                TrapArena.title(player, Text.literal("RULETKA").formatted(Formatting.GOLD, Formatting.BOLD),
                        Text.literal("Kula: ").formatted(Formatting.GRAY).append(colour), 5, 40, 10);
            }
        }
        if (tick <= 40) {
            int period = tick < 16 ? 4 : tick < 30 ? 6 : 10;
            if (tick % period == 0) {
                ringShift = (ringShift + 1) % 3;
                roof.spinRing(world, ringShift);
                sound(SoundEvents.BLOCK_NOTE_BLOCK_HAT.value(), 1.0F, 0.9F + tick * 0.01F);
            }
            double a = tick * (0.55 - tick * 0.011);
            double r = 11.0;
            double x = centre.x + Math.cos(a) * r;
            double z = centre.z + Math.sin(a) * r;
            world.spawnParticles(BALL[safeColour], x, centre.y + 0.7, z, 4, 0.15, 0.1, 0.15, 0.0);
            world.spawnParticles(ParticleTypes.END_ROD, x, centre.y + 0.7, z, 1, 0.0, 0.0, 0.0, 0.0);
            return false;
        }
        if (tick == 41) {
            sound(SoundEvents.ENTITY_EVOKER_CELEBRATE, 1.2F, 0.8F);
            for (ServerPlayerEntity player : TrapArena.combatants()) {
                int under = roof.colourUnder(world, player);
                if (under < 0) {
                    player.sendMessage(Text.literal("Nie grałeś.").formatted(Formatting.GRAY), true);
                    continue;
                }
                if (under == safeColour) {
                    afflict(player, 1);
                    player.sendMessage(Text.literal("Wygrana. ").formatted(Formatting.GREEN, Formatting.BOLD)
                            .append(Text.literal("Stawka rośnie.").formatted(Formatting.GRAY)), true);
                    sound(player, SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), 1.0F, 1.5F);
                    world.spawnParticles(ParticleTypes.HAPPY_VILLAGER, player.getX(), player.getEyeY(), player.getZ(),
                            12, 0.4, 0.4, 0.4, 0.1);
                    continue;
                }
                hurtPlayer(world, player, ArenaMath.ROULETTE_DAMAGE, world.getDamageSources().magic());
                player.setVelocity(player.getVelocity().x, 0.6, player.getVelocity().z);
                player.velocityModified = true;
                player.sendMessage(Text.literal("Przegrana.").formatted(Formatting.RED, Formatting.BOLD), true);
                sound(player, SoundEvents.ENTITY_VILLAGER_NO, 1.0F, 0.8F);
                TrapNet.flash(player, 0xff3344, 8);
                TrapNet.shake(player, 0.8F, 8);
            }
            return false;
        }
        if (tick >= 60) {
            roof.spinRing(world, 0);
            return true;
        }
        return false;
    }

    /** Ochrona: two of them, with axes. */
    private boolean bouncers(ServerWorld world, int tick) {
        if (tick == 1) {
            summonBouncers(world);
        }
        return tick >= 20;
    }

    private void summonBouncers(ServerWorld world) {
        sound(SoundEvents.ENTITY_VINDICATOR_CELEBRATE, 1.5F, 0.8F);
        sound(SoundEvents.ENTITY_PILLAGER_CELEBRATE, 1.0F, 0.7F);
        for (ServerPlayerEntity player : TrapArena.arenaPlayers()) {
            TrapArena.title(player, Text.literal("OCHRONA").formatted(Formatting.RED, Formatting.BOLD),
                    Text.literal("Dwóch. Z siekierami.").formatted(Formatting.GRAY), 0, 25, 10);
        }
        double base = random.nextDouble() * Math.PI * 2;
        for (int i = 0; i < 2; i++) {
            Vec3d spot = onFloor(base + i * Math.PI, 13.0);
            VindicatorEntity bouncer = EntityType.VINDICATOR.create(world, SpawnReason.EVENT);
            if (bouncer == null) {
                return;
            }
            float yaw = yawToward(spot) + 180.0F;
            bouncer.refreshPositionAndAngles(spot.x, spot.y, spot.z, yaw, 0.0F);
            bouncer.initialize(world, world.getLocalDifficulty(BlockPos.ofFloored(spot)), SpawnReason.EVENT, null);
            bouncer.setCustomName(Text.literal("Ochroniarz").formatted(Formatting.GOLD));
            bouncer.setCustomNameVisible(true);
            bouncer.setPersistent();
            world.spawnEntity(bouncer);
            TrapArena.track(bouncer);
            world.spawnParticles(ParticleTypes.POOF, spot.x, spot.y + 1.0, spot.z, 20, 0.4, 0.6, 0.4, 0.05);
            world.spawnParticles(ParticleTypes.WAX_ON, spot.x, spot.y + 1.0, spot.z, 12, 0.4, 0.6, 0.4, 0.1);
        }
    }

    /** Podwójnie albo nic: the top damage, doubled both ways, for eight seconds. */
    private boolean doubleOrNothing(ServerWorld world, int tick) {
        if (tick == 1) {
            List<ServerPlayerEntity> room = TrapArena.combatants();
            if (room.isEmpty()) {
                return true;
            }
            endDouble();
            ServerPlayerEntity pick = TrapArena.topDamage(room);
            doubled = pick == null ? room.get(random.nextInt(room.size())) : pick;
            doubleUntil = age + ArenaMath.DOUBLE_TICKS;
            doubled.setGlowing(true);
            setTarget(doubled);
            sound(SoundEvents.ENTITY_EVOKER_CELEBRATE, 1.5F, 0.6F);
            sound(SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), 1.5F, 0.5F);
            Text title = Text.literal("PODWÓJNIE ALBO NIC").formatted(Formatting.GOLD, Formatting.BOLD);
            String name = doubled.getNameForScoreboard();
            for (ServerPlayerEntity player : TrapArena.arenaPlayers()) {
                Text sub = player == doubled
                        ? Text.literal("Ty i on: ×2 w obie strony przez " + ArenaMath.DOUBLE_TICKS / 20 + " s.")
                        .formatted(Formatting.YELLOW)
                        : Text.literal(name + " gra o podwójną stawkę.").formatted(Formatting.GRAY);
                TrapArena.title(player, title, sub, 5, 40, 10);
            }
            world.spawnParticles(ParticleTypes.WAX_ON, doubled.getX(), doubled.getEyeY(), doubled.getZ(), 30,
                    0.5, 0.6, 0.5, 0.2);
        }
        return tick >= 10;
    }

    private void endDouble() {
        if (doubled == null) {
            return;
        }
        ServerPlayerEntity was = doubled;
        doubled = null;
        was.setGlowing(false);
        if (was.isAlive()) {
            was.sendMessage(Text.literal("Koniec zakładu.").formatted(Formatting.GRAY), true);
        }
    }

    /** Wypłata: the cabinet opens, it stands still, and it pays. Hit it. */
    private boolean cash(ServerWorld world, int tick) {
        getNavigation().stop();
        Vec3d tray = body().trayPos();
        if (tick == 1) {
            body().setOpen(true);
            sound(SoundEvents.BLOCK_VAULT_OPEN_SHUTTER, 1.5F, 0.8F);
            sound(SoundEvents.ENTITY_IRON_GOLEM_REPAIR, 1.2F, 0.6F);
            for (ServerPlayerEntity player : TrapArena.arenaPlayers()) {
                TrapArena.title(player, Text.literal("WYPŁATA").formatted(Formatting.GOLD, Formatting.BOLD),
                        Text.literal("Otwarty. Bij.").formatted(Formatting.YELLOW), 5, 40, 10);
            }
        }
        int every = ArenaMath.CASH_TICKS / ArenaMath.CASH_EMERALDS;
        if (tick % every == 0 && tick <= ArenaMath.CASH_TICKS) {
            ItemEntity drop = new ItemEntity(world, tray.x, tray.y, tray.z, new ItemStack(TrapContent.dirtyEmerald),
                    (random.nextDouble() - 0.5) * 0.5, 0.35, (random.nextDouble() - 0.5) * 0.5);
            drop.setPickupDelay(10);
            world.spawnEntity(drop);
            sound(SoundEvents.BLOCK_VAULT_EJECT_ITEM, 1.0F, 1.2F);
            world.spawnParticles(ParticleTypes.WAX_ON, tray.x, tray.y, tray.z, 8, 0.3, 0.2, 0.3, 0.1);
        }
        if (tick % 3 == 0) {
            world.spawnParticles(ParticleTypes.ELECTRIC_SPARK, tray.x, tray.y + 0.5, tray.z, 2, 0.4, 0.4, 0.4, 0.05);
        }
        if (tick >= ArenaMath.CASH_TICKS) {
            body().setOpen(false);
            sound(SoundEvents.BLOCK_VAULT_OPEN_SHUTTER, 1.0F, 0.6F);
            return true;
        }
        return false;
    }
}
