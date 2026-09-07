package dev.heezq.trapcraft;

import eu.pb4.polymer.core.api.item.PolymerItem;
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
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import xyz.nucleoid.packettweaker.PacketContext;

import java.util.List;

/**
 * Złota Dźwignia -- the lever off the bandit.
 *
 * Pull it and it spins: one of five buffs for {@link ArenaMath#LEVER_SPIN_TICKS}, drawn
 * blind, with the machine's own bell for a win. No jackpot, no emeralds,
 * because a trophy that pays is a boss with a payout schedule; what it pays
 * is a mood. Cooldown long enough that it is a moment, not a rotation.
 */
public class GoldenLeverItem extends Item implements PolymerItem {
    private final Identifier model;

    private record Prize(RegistryEntry<StatusEffect> effect, int amplifier, String name) {
    }

    private static final List<Prize> PRIZES = List.of(
            new Prize(StatusEffects.SPEED, 1, "SZYBKOŚĆ II"),
            new Prize(StatusEffects.STRENGTH, 0, "SIŁA"),
            new Prize(StatusEffects.HASTE, 1, "POŚPIECH II"),
            new Prize(StatusEffects.RESISTANCE, 0, "ODPORNOŚĆ"),
            new Prize(StatusEffects.LUCK, 2, "SZCZĘŚCIE III"));

    public GoldenLeverItem(Settings settings, Identifier model) {
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
                line("Urwana z Bandyty. Dalej się kręci.", Formatting.GOLD),
                line("PPM: losuje jeden z pięciu efektów na " + ArenaMath.LEVER_SPIN_TICKS / 20 + " s.", Formatting.GRAY),
                line("Raz na " + ArenaMath.LEVER_COOLDOWN_TICKS / 20 / 60 + " min. Kasyno nie płaci szmaragdami.", Formatting.DARK_GRAY));
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
        Prize prize = PRIZES.get(server.getRandom().nextInt(PRIZES.size()));
        player.addStatusEffect(new StatusEffectInstance(prize.effect(), ArenaMath.LEVER_SPIN_TICKS, prize.amplifier(), false, true, true));
        player.getItemCooldownManager().set(stack, ArenaMath.LEVER_COOLDOWN_TICKS);
        player.playSoundToPlayer(SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), SoundCategory.PLAYERS, 1.0F, 1.2F);
        player.playSoundToPlayer(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS, 0.8F, 0.6F);
        server.spawnParticles(ParticleTypes.WAX_ON, player.getX(), player.getEyeY(), player.getZ(), 30, 0.6, 0.5, 0.6, 0.1);
        player.sendMessage(Text.literal("Bębny: ").formatted(Formatting.GOLD)
                .append(Text.literal(prize.name()).formatted(Formatting.YELLOW, Formatting.BOLD)), true);
        return ActionResult.SUCCESS;
    }
}
