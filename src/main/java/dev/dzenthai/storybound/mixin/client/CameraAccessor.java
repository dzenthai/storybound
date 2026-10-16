package dev.dzenthai.storybound.mixin.client;

import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The dialogue camera places the frame through Camera's own protected setters. */
@Mixin(Camera.class)
public interface CameraAccessor {
    @Invoker("setPosition")
    void storybound$setPosition(Vec3 pos);

    @Invoker("setRotation")
    void storybound$setRotation(float yRot, float xRot, float roll);

    /** Detached: the player's own model is drawn, as in third person. */
    @Accessor("detached")
    void storybound$setDetached(boolean detached);
}
