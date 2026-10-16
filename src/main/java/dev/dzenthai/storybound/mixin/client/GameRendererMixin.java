package dev.dzenthai.storybound.mixin.client;

import dev.dzenthai.storybound.client.camera.DialogueCamera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Right after the camera is set up for a frame, the dialogue camera may move it. Injected here rather than into
 * {@code Camera.setup}: camera mods may cancel {@code setup} (Epic Fight does with its own camera), and nothing
 * else moves the camera between this point and drawing. The high priority puts our call after other mods'
 * injections at the same point. {@code require = 0}: if another mod rewrites this method, the camera is simply off.
 */
@Mixin(value = GameRenderer.class, priority = 1500)
public abstract class GameRendererMixin {
    @Inject(method = "renderLevel",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/Camera;setup(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/world/entity/Entity;ZZF)V",
                    shift = At.Shift.AFTER),
            require = 0)
    private void storybound$dialogueCamera(DeltaTracker deltaTracker, CallbackInfo info) {
        DialogueCamera.apply(((GameRenderer) (Object) this).getMainCamera());
    }
}
