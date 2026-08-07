package dev.heezq.trapcraft.client.mixin;

import dev.heezq.trapcraft.client.TrapCraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Slow FOV breathe while Baked -- the part you feel more than notice. */
@Mixin(GameRenderer.class)
public class GameRendererMixin {

    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void trapcraft$fov(Camera camera, float tickProgress, boolean changingFov,
                               CallbackInfoReturnable<Float> cir) {
        if (!TrapCraftClient.anyLook()) {
            return;
        }
        cir.setReturnValue(cir.getReturnValue() * TrapCraftClient.fovScale(tickProgress));
    }
}
