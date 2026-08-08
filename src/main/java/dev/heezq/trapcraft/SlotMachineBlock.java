package dev.heezq.trapcraft;

import eu.pb4.polymer.blocks.api.BlockModelType;
import eu.pb4.polymer.blocks.api.PolymerBlockModel;
import eu.pb4.polymer.blocks.api.PolymerTexturedBlock;
import eu.pb4.polymer.core.api.block.PolymerBlock;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import xyz.nucleoid.packettweaker.PacketContext;

import java.util.ArrayList;
import java.util.List;

/**
 * A machine that takes your emeralds and occasionally gives some back.
 *
 * Craftable and placeable like anything else here, so a casino is something
 * somebody builds rather than a command. What it pays is in
 * {@link TrapMath#slotPayout}: 85% back over time, and about three spins in
 * four pay nothing.
 */
public class SlotMachineBlock extends Block implements PolymerBlock, PolymerTexturedBlock {
    private final BlockState carrier;

    /**
     * Machines mid-spin, ticked from one shared handler.
     *
     * The reels animate server-side and the screen is updated per tick, so
     * something has to drive them; a screen handler gets no tick of its own.
     */
    private static final List<SlotScreenHandler> SPINNING = new ArrayList<>();

    public SlotMachineBlock(Settings settings) {
        super(settings);
        this.carrier = TrapPolymer.requestOrFallback(
                BlockModelType.FULL_BLOCK,
                PolymerBlockModel.of(Identifier.of("trapcraft:block/slot_machine")),
                () -> Blocks.RED_TERRACOTTA.getDefaultState(), "slot_machine");
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (!SPINNING.isEmpty()) {
                SPINNING.removeIf(machine -> !machine.tick());
            }
        });
    }

    /** Start ticking a machine that has just been pulled. */
    public static void watch(SlotScreenHandler machine) {
        if (!SPINNING.contains(machine)) {
            SPINNING.add(machine);
        }
    }

    @Override
    public BlockState getPolymerBlockState(BlockState state, PacketContext context) {
        return carrier;
    }

    /** Break as metal: it's a machine full of levers and coin. */
    @Override
    public BlockState getPolymerBreakEventBlockState(BlockState state, PacketContext context) {
        return Blocks.IRON_BLOCK.getDefaultState();
    }

    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos,
                                 PlayerEntity player, BlockHitResult hit) {
        if (world.isClient() || !(player instanceof ServerPlayerEntity gambler)) {
            return ActionResult.SUCCESS;
        }
        world.playSound(null, pos, SoundEvents.BLOCK_NOTE_BLOCK_BIT.value(),
                SoundCategory.BLOCKS, 0.7F, 1.5F);
        gambler.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, inventory, ignored) -> new SlotScreenHandler(syncId, inventory),
                Text.literal("Lucky Streak").formatted(Formatting.GOLD)));
        return ActionResult.SUCCESS;
    }
}
