package dev.dzenthai.storybound.mixin.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.dzenthai.storybound.client.menu.BookTextRendering;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Font.class)
public abstract class BookFontMixin {
    @Inject(method = "renderChar", at = @At("HEAD"))
    private void storybound$continuousBookBold(BakedGlyph glyph, boolean bold, boolean italic,
            float boldOffset, float x, float y, Matrix4f matrix, VertexConsumer buffer,
            float red, float green, float blue, float alpha, int light, CallbackInfo info) {
        if (bold && BookTextRendering.active()) {
            // Bridge sampling gaps between the two vanilla impressions without expanding their bounds.
            glyph.render(italic, x + boldOffset * 0.5f, y, matrix, buffer, red, green, blue, alpha, light);
        }
    }
}
