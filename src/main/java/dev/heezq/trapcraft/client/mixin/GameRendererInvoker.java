package dev.heezq.trapcraft.client.mixin;

import net.minecraft.client.render.GameRenderer;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Opens up GameRenderer.setPostProcessor, which is private.
 *
 * Separate from GameRendererMixin because only an INTERFACE mixin can be cast
 * to from ordinary code -- a class mixin isn't a real supertype at runtime.
 */
@Mixin(GameRenderer.class)
public interface GameRendererInvoker {
    @Invoker("setPostProcessor")
    void trapcraft$setPostProcessor(Identifier id);
}
