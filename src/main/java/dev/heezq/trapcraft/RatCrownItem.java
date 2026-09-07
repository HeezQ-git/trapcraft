package dev.heezq.trapcraft;

import eu.pb4.polymer.core.api.item.PolymerItem;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import xyz.nucleoid.packettweaker.PacketContext;

import java.util.List;
import java.util.Set;

/**
 * Korona Szczurów -- the crown off the rat king.
 *
 * Ucieczka: right-click and you go the way a rat goes, {@link ArenaMath#CROWN_BLINK}
 * blocks along your look, walls included, and come out running. The
 * landing is the furthest spot along the line with room for a body; if
 * there is none, nothing happens and the cooldown is not spent. No damage,
 * no loot: an escape is worth more than either to someone with a bounty
 * on them.
 */
public class RatCrownItem extends Item implements PolymerItem {
    private final Identifier model;

    private static final BlockStateParticleEffect DIRT =
            new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.MUD.getDefaultState());

    public RatCrownItem(Settings settings, Identifier model) {
        super(settings);
        this.model = model;
    }

    @Override
    public Item getPolymerItem(ItemStack stack, PacketContext context) {
        return Items.PAPER;
    }

    @Override
    public Identifier getPolymerItemModel(ItemStack stack, PacketContext context) {
        return model;
    }

    @Override
    public void inventoryTick(ItemStack stack, ServerWorld world, Entity holder, EquipmentSlot slot) {
        if (stack.get(DataComponentTypes.LORE) == null) {
            stack.set(DataComponentTypes.LORE, new LoreComponent(lore()));
        }
    }

    public static List<Text> lore() {
        return List.of(
                line("Spadła z jego głowy. Dalej pachnie kanałem.", Formatting.DARK_GREEN),
                line("PPM: " + ArenaMath.CROWN_BLINK + " bloków w stronę, w którą patrzysz. Przez ściany.",
                        Formatting.GRAY),
                line("Po drodze Szybkość II na 10 s. Raz na " + ArenaMath.CROWN_COOLDOWN_TICKS / 20 + " s.",
                        Formatting.DARK_GRAY));
    }

    private static Text line(String text, Formatting colour) {
        return Text.literal(text).formatted(colour).styled(style -> style.withItalic(false));
    }

    @Override
    public ActionResult use(World world, PlayerEntity user, Hand hand) {
        if (!(world instanceof ServerWorld server) || !(user instanceof ServerPlayerEntity player)) {
            return ActionResult.SUCCESS;
        }
        ItemStack stack = player.getStackInHand(hand);
        if (player.getItemCooldownManager().isCoolingDown(stack)) {
            return ActionResult.PASS;
        }
        Vec3d look = player.getRotationVec(1.0F);
        Vec3d flat = new Vec3d(look.x, 0.0, look.z);
        if (flat.lengthSquared() < 0.01) {
            flat = Vec3d.fromPolar(0.0F, player.getYaw());
        }
        flat = flat.normalize();
        Vec3d landing = null;
        for (int d = ArenaMath.CROWN_BLINK; d >= 2; d--) {
            Vec3d spot = player.getPos().add(flat.multiply(d));
            for (int dy = 0; dy >= -2; dy--) {
                BlockPos feet = BlockPos.ofFloored(spot.x, spot.y + dy, spot.z);
                if (roomAt(server, feet)) {
                    landing = new Vec3d(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
                    break;
                }
            }
            if (landing != null) {
                break;
            }
        }
        if (landing == null) {
            player.sendMessage(Text.literal("Nie ma którędy.").formatted(Formatting.GRAY), true);
            return ActionResult.PASS;
        }
        Vec3d from = player.getPos();
        server.spawnParticles(DIRT, from.x, from.y + 0.5, from.z, 30, 0.4, 0.4, 0.4, 0.1);
        server.playSound(null, from.x, from.y, from.z, SoundEvents.BLOCK_ROOTED_DIRT_BREAK, SoundCategory.PLAYERS,
                1.0F, 0.7F);
        player.teleport(server, landing.x, landing.y, landing.z, Set.of(), player.getYaw(), player.getPitch(), true);
        player.fallDistance = 0.0;
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 20 * 10, 1, false, true, true));
        player.getItemCooldownManager().set(stack, ArenaMath.CROWN_COOLDOWN_TICKS);
        server.spawnParticles(DIRT, landing.x, landing.y + 0.5, landing.z, 30, 0.4, 0.4, 0.4, 0.1);
        server.playSound(null, landing.x, landing.y, landing.z, SoundEvents.ENTITY_SILVERFISH_AMBIENT,
                SoundCategory.PLAYERS, 1.0F, 0.6F);
        player.playSoundToPlayer(SoundEvents.BLOCK_MUD_BREAK, SoundCategory.PLAYERS, 1.0F, 0.8F);
        player.sendMessage(Text.literal("Kanałami.").formatted(Formatting.DARK_GREEN), true);
        return ActionResult.SUCCESS;
    }

    /** Two blocks of not-solid to stand in, and something under them. */
    private static boolean roomAt(ServerWorld world, BlockPos feet) {
        BlockState a = world.getBlockState(feet);
        BlockState b = world.getBlockState(feet.up());
        BlockState under = world.getBlockState(feet.down());
        return !a.blocksMovement() && !b.blocksMovement() && a.getFluidState().isEmpty()
                && under.blocksMovement();
    }
}
