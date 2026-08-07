package dev.heezq.trapcraft.client.mixin;

import dev.heezq.trapcraft.client.TrapCraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drifts the camera while Baked.
 *
 * Deliberately offsets the CAMERA and not the player's rotation: your crosshair
 * still points where you aimed it, so building and fighting stay fair. Rotating
 * the player instead would also fight the server over look direction.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Invoker("setRotation")
    abstract void trapcraft$setRotation(float yaw, float pitch);

    @Inject(method = "update", at = @At("TAIL"))
    private void trapcraft$sway(BlockView area, Entity focusedEntity, boolean thirdPerson,
                                boolean inverseView, float tickProgress, CallbackInfo ci) {
        if (!TrapCraftClient.anyLook()) {
            return;
        }
        Camera self = (Camera) (Object) this;
        trapcraft$setRotation(
                self.getYaw() + TrapCraftClient.swayYaw(tickProgress),
                self.getPitch() + TrapCraftClient.swayPitch(tickProgress));

        // Roll. Camera has no roll of its own -- setRotation builds the
        // quaternion with rotationYXZ(yaw, pitch, 0), that last zero being the
        // Z angle -- so we spin it in afterwards. Must come after setRotation,
        // which rebuilds the quaternion from scratch and would wipe this.
        float roll = TrapCraftClient.swayRoll(tickProgress);
        if (roll != 0.0F) {
            float rad = roll * MathHelper.RADIANS_PER_DEGREE;
            self.getRotation().rotateZ(rad);
            // The three plane vectors are what particles billboard against, and
            // setRotation derived them from the unrolled quaternion. Left alone
            // they'd stay upright while the world tipped, and every particle in
            // view would visibly counter-rotate.
            self.getHorizontalPlane().rotateZ(rad);
            self.getVerticalPlane().rotateZ(rad);
            self.getDiagonalPlane().rotateZ(rad);
        }
    }
}
