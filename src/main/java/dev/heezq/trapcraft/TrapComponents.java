package dev.heezq.trapcraft;

import com.mojang.serialization.Codec;
import eu.pb4.polymer.core.api.other.PolymerComponent;
import net.minecraft.component.ComponentType;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipAppender;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.text.Text;

/**
 * The quality grade rides on the itemstack as a data component, so it survives
 * being dropped, stored in a chest, and -- crucially -- crafted, as long as the
 * recipe is a crafting_transmute (see gen_assets.py). A plain shapeless recipe
 * would silently discard it and every joint would come out Swill.
 */
public final class TrapComponents {
    private TrapComponents() {
    }

    public static ComponentType<Integer> quality;
    public static ComponentType<Integer> purity;
    public static ComponentType<Blend> blend;
    /** The job on the phone, and the standing that got it. */
    public static ComponentType<Contract> contract;
    public static ComponentType<Integer> rep;
    /** What's in a wallet, in emeralds. */
    public static ComponentType<Integer> balance;
    /** Which casino an owner's card is the key to, as a UUID string. */
    public static ComponentType<String> casino;

    public static Blend getBlend(ItemStack stack) {
        return stack.get(blend);
    }

    public static void register() {
        blend = Registry.register(Registries.DATA_COMPONENT_TYPE, TrapCraft.id("blend"),
                ComponentType.<Blend>builder()
                        .codec(Blend.CODEC)
                        // Blends have to survive the trip to the client -- the
                        // bong and tlok read the mix off the stack you feed
                        // them, and without a packet codec it arrives stripped.
                        .packetCodec(PacketCodecs.registryCodec(Blend.CODEC))
                        .build());
        contract = Registry.register(Registries.DATA_COMPONENT_TYPE, TrapCraft.id("contract"),
                ComponentType.<Contract>builder()
                        .codec(Contract.CODEC)
                        // Same reasoning as blend: the stack goes to the client
                        // and an un-syncable component gets stripped in transit.
                        .packetCodec(PacketCodecs.registryCodec(Contract.CODEC))
                        .build());
        rep = Registry.register(Registries.DATA_COMPONENT_TYPE, TrapCraft.id("rep"),
                ComponentType.<Integer>builder()
                        .codec(Codec.INT)
                        .packetCodec(PacketCodecs.VAR_INT)
                        .build());
        quality = Registry.register(Registries.DATA_COMPONENT_TYPE, TrapCraft.id("quality"),
                ComponentType.<Integer>builder()
                        .codec(Codec.INT)
                        .packetCodec(PacketCodecs.VAR_INT)
                        .build());
        purity = Registry.register(Registries.DATA_COMPONENT_TYPE, TrapCraft.id("purity"),
                ComponentType.<Integer>builder()
                        .codec(Codec.INT)
                        .packetCodec(PacketCodecs.VAR_INT)
                        .build());
        balance = Registry.register(Registries.DATA_COMPONENT_TYPE, TrapCraft.id("balance"),
                ComponentType.<Integer>builder()
                        .codec(Codec.INT)
                        .packetCodec(PacketCodecs.VAR_INT)
                        .build());
        casino = Registry.register(Registries.DATA_COMPONENT_TYPE, TrapCraft.id("casino"),
                ComponentType.<String>builder()
                        .codec(Codec.STRING)
                        .packetCodec(PacketCodecs.STRING)
                        .build());

        // DATA_COMPONENT_TYPE is a SYNCED registry: every entry registered here
        // is sent to the client during login, and a client that doesn't know
        // one is kicked with "Received N registry entries that are unknown to
        // this client". Adding `contract` and `rep` in 0.2.0 did exactly that
        // to every client still on the 0.1.0 jar.
        //
        // Telling Polymer about them makes it strip them from the sync, so the
        // server can add components freely without any client needing to
        // match -- which is the promise this mod is built on. All five are
        // registered, not just the new pair: the older three were only ever
        // working because the packwiz pack happened to ship the same jar to
        // everyone, which is luck rather than design.
        PolymerComponent.registerDataComponent(blend, contract, rep, quality, purity, balance,
                casino);
    }

    public static Purity getPurity(ItemStack stack) {
        Integer index = stack.get(purity);
        return index == null ? Purity.STREET : Purity.byIndex(index);
    }

    /** Purity's equivalent of apply() -- grade in the name, readable in a chest. */
    public static ItemStack applyPurity(ItemStack stack, Purity grade) {
        stack.set(purity, grade.index());
        stack.set(net.minecraft.component.DataComponentTypes.CUSTOM_NAME,
                Text.literal(grade.display() + " ")
                        .formatted(grade.colour())
                        .append(stack.getItem().getName().copy().formatted(grade.colour()))
                        .styled(style -> style.withItalic(false)));
        return stack;
    }

    public static Quality get(ItemStack stack) {
        Integer index = stack.get(quality);
        return index == null ? Quality.MIDS : Quality.byIndex(index);
    }

    /** Sets the grade and renames the stack so you can read it in a chest. */
    public static ItemStack apply(ItemStack stack, Quality grade) {
        stack.set(quality, grade.index());
        stack.set(net.minecraft.component.DataComponentTypes.CUSTOM_NAME,
                Text.literal(grade.display() + " ")
                        .formatted(grade.colour())
                        .append(stack.getItem().getName().copy().formatted(grade.colour()))
                        .styled(style -> style.withItalic(false)));
        return stack;
    }
}
