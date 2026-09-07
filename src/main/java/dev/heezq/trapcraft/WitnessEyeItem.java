package dev.heezq.trapcraft;

import eu.pb4.polymer.core.api.item.PolymerItem;
import eu.pb4.polymer.virtualentity.api.tracker.EntityTrackedData;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket;
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
import net.minecraft.util.math.Box;
import net.minecraft.world.World;
import xyz.nucleoid.packettweaker.PacketContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Oko Obserwatora -- the trophy off the watcher, and the one real item the arena
 * adds.
 *
 * It saw everything, and now so do you: a right-click makes every creature
 * within {@link ArenaMath#EYE_RANGE} blocks glow for you alone for eight
 * seconds. Handy for finding a raid, a patrol, a courier or a friend in the
 * dark, and worth nothing at a counter -- {@link TrapScrap} refuses it,
 * because a boss drop with a scrap price is a boss with a payout schedule.
 *
 * The glow is a lie told to one client, the same way Paranoia's figures are:
 * a tracker packet with the glowing bit set, sent only to the holder, and
 * the true flags sent back when it expires. Nothing is changed on the
 * server, so nobody else sees anything and no entity is left glowing when
 * the holder logs off mid-reveal.
 */
public class WitnessEyeItem extends Item implements PolymerItem {
    private final Identifier model;

    /** A glow that will need putting back: who sees it, what glows, when it ends. */
    private record Reveal(ServerPlayerEntity viewer, Entity target, long until) {
    }

    private static final List<Reveal> REVEALS = new ArrayList<>();

    public WitnessEyeItem(Settings settings, Identifier model) {
        super(settings);
        this.model = model;
    }

    @Override
    public Item getPolymerItem(ItemStack stack, PacketContext context) {
        // Paper: inert, never placeable, predicts nothing. See CaseItem.
        return Items.PAPER;
    }

    @Override
    public Identifier getPolymerItemModel(ItemStack stack, PacketContext context) {
        return model;
    }

    /** Stamped once, like the cases: a vanilla client only sees what is on the stack. */
    @Override
    public void inventoryTick(ItemStack stack, ServerWorld world, Entity holder, EquipmentSlot slot) {
        if (stack.get(DataComponentTypes.LORE) == null) {
            stack.set(DataComponentTypes.LORE, new LoreComponent(lore()));
        }
    }

    public static List<Text> lore() {
        return List.of(
                line("Patrzyło na wszystkich. Teraz patrzy dla ciebie.", Formatting.LIGHT_PURPLE),
                line("PPM: wszystko żywe w " + ArenaMath.EYE_RANGE + " blokach świeci przez "
                        + ArenaMath.EYE_REVEAL_TICKS / 20 + " s.", Formatting.GRAY),
                line("Tylko dla twoich oczu. Raz na " + ArenaMath.EYE_COOLDOWN_TICKS / 20 + " s.",
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
        int range = ArenaMath.EYE_RANGE;
        Box reach = player.getBoundingBox().expand(range, range / 2.0, range);
        List<LivingEntity> seen = server.getEntitiesByClass(LivingEntity.class, reach,
                other -> other != player && other.isAlive());
        long until = server.getTime() + ArenaMath.EYE_REVEAL_TICKS;
        for (LivingEntity target : seen) {
            sendGlow(player, target, true);
            REVEALS.add(new Reveal(player, target, until));
        }
        player.getItemCooldownManager().set(stack, ArenaMath.EYE_COOLDOWN_TICKS);

        server.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.ENTITY_ELDER_GUARDIAN_CURSE, SoundCategory.PLAYERS, 0.6F, 1.6F);
        player.playSoundToPlayer(SoundEvents.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 0.8F, 1.4F);
        server.spawnParticles(ParticleTypes.ENCHANT, player.getX(), player.getEyeY(), player.getZ(),
                40, 0.8, 0.6, 0.8, 0.5);
        player.sendMessage(Text.literal("Oko otwiera się. ").formatted(Formatting.LIGHT_PURPLE)
                .append(Text.literal(seen.size() + " istot w zasięgu.").formatted(Formatting.GRAY)), true);
        return ActionResult.SUCCESS;
    }

    /**
     * The glowing bit, for one viewer. The flag byte is read off the real
     * tracker so every other flag (on fire, sneaking) is carried unchanged,
     * and the revert simply sends the truth.
     */
    private static void sendGlow(ServerPlayerEntity viewer, Entity target, boolean glow) {
        byte flags = target.getDataTracker().get(EntityTrackedData.FLAGS);
        byte shown = glow ? (byte) (flags | (1 << EntityTrackedData.GLOWING_FLAG_INDEX)) : flags;
        viewer.networkHandler.sendPacket(new EntityTrackerUpdateS2CPacket(target.getId(),
                List.of(DataTracker.SerializedEntry.of(EntityTrackedData.FLAGS, shown))));
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (REVEALS.isEmpty()) {
                return;
            }
            long now = server.getOverworld().getTime();
            REVEALS.removeIf(reveal -> {
                if (reveal.viewer().isDisconnected() || reveal.target().isRemoved()) {
                    return true;
                }
                if (now < reveal.until()) {
                    return false;
                }
                sendGlow(reveal.viewer(), reveal.target(), false);
                return true;
            });
        });
    }
}
