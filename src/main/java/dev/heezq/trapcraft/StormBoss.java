package dev.heezq.trapcraft;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CampfireBlock;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Unit;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;

import java.util.List;

/**
 * Sztorm. The storm.
 *
 * A cloud that stopped over the town and will not move on, with something
 * lit in the middle of it. It fights on a disc in the sky with no walls:
 * six lightning rods on copper that take the bolts meant for you, four
 * braziers that thaw the hail, and a long drop for anyone the wind gets.
 * Everyone who walks in is lent a bow, because it flies, and hands it back
 * on the way out.
 */
public final class StormBoss extends ArenaBoss {
    public static final StormBoss INSTANCE = new StormBoss();

    private static final BlockPos ORIGIN = new BlockPos(2000, 150, 2000);
    private static final double RADIUS = 17.5;
    private static final String BOW_NAME = "Łuk Sztormowy";
    private static final String ARROW_NAME = "Strzała Sztormowa";
    private static final BlockState DIM = Blocks.BLUE_ICE.getDefaultState();
    private static final BlockState DARK = Blocks.POLISHED_DEEPSLATE.getDefaultState();

    private ArenaBlueprint plan;

    private StormBoss() {
    }

    @Override
    public String id() {
        return "storm";
    }

    @Override
    public String displayName() {
        return "SZTORM";
    }

    @Override
    public Formatting colour() {
        return Formatting.AQUA;
    }

    @Override
    public BossBar.Color barColour(int phase) {
        return phase == 3 ? BossBar.Color.RED : phase == 2 ? BossBar.Color.WHITE : BossBar.Color.BLUE;
    }

    @Override
    public BlockPos origin() {
        return ORIGIN;
    }

    @Override
    public double pitRadius() {
        return RADIUS;
    }

    /** On the bridge, facing the disc. */
    @Override
    public Vec3d gate() {
        return new Vec3d(ORIGIN.getX() + 0.5, ORIGIN.getY() + 1.0, ORIGIN.getZ() + 22.5);
    }

    @Override
    public float gateYaw() {
        return 180.0F;
    }

    /** The spectator cloud at the end of the bridge. */
    @Override
    public Vec3d standsSpot(Random random) {
        double angle = random.nextDouble() * Math.PI * 2;
        double r = random.nextDouble() * 4.0;
        return new Vec3d(ORIGIN.getX() + 0.5 + Math.cos(angle) * r, ORIGIN.getY() + 1.0,
                ORIGIN.getZ() + 32.5 + Math.sin(angle) * r);
    }

    @Override
    public ArenaBossEntity spawn(ServerWorld world, int players) {
        StormEntity boss = new StormEntity(StormEntity.TYPE, world);
        Vec3d centre = centre();
        boss.refreshPositionAndAngles(centre.x, centre.y + StormEntity.HOVER, centre.z, 0.0F, 0.0F);
        boss.sizeFor(players);
        world.spawnEntity(boss);
        return boss;
    }

    @Override
    public List<String> abilities() {
        return StormEntity.ABILITY_IDS;
    }

    /** The six lightning rods. */
    public List<BlockPos> rods() {
        return plan == null ? List.of() : plan.tagged("rod");
    }

    /** The four braziers. */
    public List<BlockPos> fires() {
        return plan == null ? List.of() : plan.tagged("fire");
    }

    // --- words ---------------------------------------------------------------

    @Override
    public Text omen(String clock) {
        return Text.empty()
                .append(Text.literal("Nad miastem stanęła burza, która nie schodzi.\n").formatted(Formatting.AQUA))
                .append(Text.literal("Chmura nad dachami nie idzie dalej. W środku coś świeci.\n")
                        .formatted(Formatting.GRAY))
                .append(Text.literal("Arena otwiera się za " + clock + ". ").formatted(Formatting.WHITE))
                .append(Text.literal("Nikt tam nie ginie: nokaut, trybuny, powrót.\n")
                        .formatted(Formatting.DARK_GRAY, Formatting.ITALIC));
    }

    @Override
    public Text nobodyCame() {
        return Text.literal("Nikt nie przyszedł. ").formatted(Formatting.GRAY, Formatting.ITALIC)
                .append(Text.literal("Burza przeszła bokiem.").formatted(Formatting.DARK_GRAY));
    }

    @Override
    public Line fightStart() {
        return new Line(Text.literal("SZTORM").formatted(Formatting.AQUA, Formatting.BOLD),
                Text.literal("Nie masz gdzie się schować.").formatted(Formatting.WHITE));
    }

    @Override
    public Text fightBroadcast() {
        return Text.literal("Sztorm jest nad platformą. ").formatted(Formatting.AQUA, Formatting.BOLD)
                .append(Text.literal("Dziesięć minut, zanim przejdzie. ").formatted(Formatting.GRAY));
    }

    @Override
    public Line phase(int phase) {
        return new Line(Text.literal(phase == 2 ? "GRAD" : "OKO CYKLONU").formatted(Formatting.AQUA, Formatting.BOLD),
                Text.literal(phase == 2 ? "Przy ogniu." : "Przy piorunochronach.").formatted(Formatting.GRAY));
    }

    @Override
    public Text phaseBroadcast(int phase) {
        return Text.literal("Sztorm: ").formatted(Formatting.AQUA)
                .append(Text.literal(phase == 2 ? "faza II. Grad." : "faza III. Oko cyklonu.")
                        .formatted(Formatting.GRAY));
    }

    @Override
    public Line enrage() {
        return new Line(Text.literal("PRZESZEDŁ").formatted(Formatting.RED, Formatting.BOLD),
                Text.literal("Za wolno.").formatted(Formatting.GRAY));
    }

    @Override
    public Text enrageBroadcast() {
        return Text.literal("Sztorm przeszedł. ").formatted(Formatting.RED, Formatting.BOLD)
                .append(Text.literal("Nikt go nie uciszył na czas.").formatted(Formatting.GRAY));
    }

    @Override
    public Line victory() {
        return new Line(Text.literal("NIEBO CZYSTE").formatted(Formatting.GOLD, Formatting.BOLD),
                Text.literal("Burza przeszła.").formatted(Formatting.YELLOW));
    }

    @Override
    public String victoryHeader() {
        return "✦ NIEBO CZYSTE ✦";
    }

    @Override
    public Text joinRefused() {
        return Text.literal("Nad platformą jest cicho. Burza jeszcze nie przyszła.").formatted(Formatting.RED);
    }

    // --- loot -------------------------------------------------------------------

    @Override
    public Item trophy() {
        return TrapContent.stormHeart;
    }

    @Override
    public String trophyName() {
        return "Serce Burzy";
    }

    @Override
    public String killAward() {
        return "storm";
    }

    @Override
    public int[] showColours() {
        return new int[]{0x7fd8ff, 0xffffff};
    }

    // --- the platform -------------------------------------------------------------

    @Override
    public void onBuilt(ServerWorld world, ArenaBlueprint blueprint) {
        plan = blueprint;
        lights(world, 1);
        sigil(world, false);
    }

    @Override
    public void onFightStart(ServerWorld world, ArenaBlueprint blueprint) {
        plan = blueprint;
        sigil(world, true);
    }

    @Override
    public void onPhase(ServerWorld world, ArenaBlueprint blueprint, int phase) {
        lights(world, phase);
    }

    @Override
    public void onEnd(ServerWorld world, ArenaBlueprint blueprint) {
        plan = blueprint;
        lights(world, 1);
        sigil(world, false);
    }

    private void lights(ServerWorld world, int phase) {
        if (plan == null) {
            return;
        }
        for (BlockPos pos : plan.tagged("vein")) {
            BlockState state = phase == 1 ? plan.original(pos) : phase == 2 ? DIM : DARK;
            world.setBlockState(pos, state, Block.NOTIFY_LISTENERS);
        }
        if (phase > 1) {
            Vec3d centre = centre();
            world.playSound(null, centre.x, centre.y, centre.z, SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER,
                    SoundCategory.HOSTILE, 2.0F, phase == 3 ? 0.5F : 0.8F);
        }
    }

    private void sigil(ServerWorld world, boolean on) {
        if (plan == null) {
            return;
        }
        for (BlockPos pos : plan.tagged("sigil")) {
            world.setBlockState(pos, on ? TrapArena.LIT : plan.original(pos), Block.NOTIFY_LISTENERS);
        }
    }

    // --- the bow ---------------------------------------------------------------------

    /** Everyone who walks in gets a bow that flies, because the boss does. */
    @Override
    public void onEnter(ServerPlayerEntity player) {
        if (!(player.getWorld() instanceof ServerWorld world)) {
            return;
        }
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.size(); i++) {
            if (isLent(inventory.getStack(i))) {
                return;
            }
        }
        var enchantments = world.getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT);
        ItemStack bow = new ItemStack(Items.BOW);
        bow.addEnchantment(enchantments.getOrThrow(Enchantments.POWER), 2);
        bow.addEnchantment(enchantments.getOrThrow(Enchantments.INFINITY), 1);
        bow.set(DataComponentTypes.UNBREAKABLE, Unit.INSTANCE);
        bow.set(DataComponentTypes.CUSTOM_NAME, plain(BOW_NAME, Formatting.AQUA));
        bow.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                plain("Pożyczony. Zostaje na platformie.", Formatting.GRAY))));
        ItemStack arrow = new ItemStack(Items.ARROW);
        arrow.set(DataComponentTypes.CUSTOM_NAME, plain(ARROW_NAME, Formatting.AQUA));
        player.getInventory().offerOrDrop(bow);
        player.getInventory().offerOrDrop(arrow);
        player.playSoundToPlayer(SoundEvents.ENTITY_ARROW_SHOOT, SoundCategory.PLAYERS, 1.0F, 0.8F);
        player.sendMessage(Text.literal("Łuk Sztormowy ").formatted(Formatting.AQUA)
                .append(Text.literal("na czas walki. Wraca z tobą na bramę, nie dalej.").formatted(Formatting.GRAY)), false);
    }

    private static Text plain(String text, Formatting colour) {
        return Text.literal(text).formatted(colour).styled(style -> style.withItalic(false));
    }

    static boolean isLent(ItemStack stack) {
        if (stack.isEmpty() || !(stack.isOf(Items.BOW) || stack.isOf(Items.ARROW))) {
            return false;
        }
        Text name = stack.get(DataComponentTypes.CUSTOM_NAME);
        if (name == null) {
            return false;
        }
        String s = name.getString();
        return s.equals(BOW_NAME) || s.equals(ARROW_NAME);
    }

    @Override
    public void onLeave(ServerPlayerEntity player) {
        reclaim(player.getInventory());
        reclaim(player.getEnderChestInventory());
        if (isLent(player.currentScreenHandler.getCursorStack())) {
            player.currentScreenHandler.setCursorStack(ItemStack.EMPTY);
        }
        player.setFrozenTicks(0);
    }

    private static void reclaim(Inventory inventory) {
        for (int i = 0; i < inventory.size(); i++) {
            if (isLent(inventory.getStack(i))) {
                inventory.setStack(i, ItemStack.EMPTY);
            }
        }
    }

    @Override
    public void onKnockout(ServerPlayerEntity player) {
        player.setFrozenTicks(0);
    }

    // --- fire -----------------------------------------------------------------------------

    /** The braziers thaw whoever stands at them, and relight if a breeze put them out. */
    @Override
    public void debuffTick(ServerWorld world, List<ServerPlayerEntity> here, int now) {
        List<BlockPos> fires = fires();
        for (BlockPos fire : fires) {
            BlockState state = world.getBlockState(fire);
            if (state.isOf(Blocks.CAMPFIRE) && !state.get(CampfireBlock.LIT)) {
                world.setBlockState(fire, state.with(CampfireBlock.LIT, true), Block.NOTIFY_LISTENERS);
            }
        }
        for (ServerPlayerEntity player : here) {
            if (player.getFrozenTicks() <= 0) {
                continue;
            }
            for (BlockPos fire : fires) {
                if (fire.getSquaredDistance(player.getPos()) <= ArenaMath.FIRE_RANGE * ArenaMath.FIRE_RANGE) {
                    player.setFrozenTicks(Math.max(0, player.getFrozenTicks() - 80));
                    world.spawnParticles(ParticleTypes.SMOKE, player.getX(), player.getY() + 1.0, player.getZ(),
                            6, 0.3, 0.4, 0.3, 0.01);
                    player.playSoundToPlayer(SoundEvents.BLOCK_FIRE_EXTINGUISH, SoundCategory.PLAYERS, 0.4F, 1.6F);
                    break;
                }
            }
        }
    }
}
