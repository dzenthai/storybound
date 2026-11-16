package dev.dzenthai.storybound.mixin.client;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The dialogue camera hides the cursor while a line plays (the mouse turns the frame) and gives it back in the middle
 * of the window for the answers, as Minecraft's own release does; the handler must know where it put it.
 */
@Mixin(MouseHandler.class)
public interface MouseHandlerAccessor {
    @Accessor("xpos")
    void storybound$setXpos(double xpos);

    @Accessor("ypos")
    void storybound$setYpos(double ypos);
}
