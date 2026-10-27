package dev.dzenthai.storybound.client.document;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.dzenthai.storybound.Storybound;
import java.io.IOException;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.ShaderInstance;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import org.jetbrains.annotations.Nullable;

/** Gives printed reading surfaces a solid pixel contour without translucent cutout fringe. */
@EventBusSubscriber(modid = Storybound.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class BookTextureRendering {
    private static @Nullable ShaderInstance cutout;

    private BookTextureRendering() {}

    @SubscribeEvent
    public static void registerShaders(RegisterShadersEvent event) throws IOException {
        event.registerShader(new ShaderInstance(event.getResourceProvider(), Storybound.id("book_cutout"),
                DefaultVertexFormat.POSITION_TEX), shader -> cutout = shader);
    }

    static void draw(GuiGraphics graphics, DocumentStyle style) {
        if (cutout == null) {
            graphics.blit(style.texture(), 0, 0, style.width(), style.height(), 0, 0,
                    style.textureWidthOrSize(), style.textureHeightOrSize(), style.textureWidthOrSize(), style.textureHeightOrSize());
            return;
        }
        graphics.flush();
        ShaderInstance previous = RenderSystem.getShader();
        RenderSystem.setShaderTexture(0, style.texture());
        RenderSystem.setShader(() -> cutout);
        var matrix = graphics.pose().last().pose();
        var buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        buffer.addVertex(matrix, 0, 0, 0).setUv(0, 0);
        buffer.addVertex(matrix, 0, style.height(), 0).setUv(0, 1);
        buffer.addVertex(matrix, style.width(), style.height(), 0).setUv(1, 1);
        buffer.addVertex(matrix, style.width(), 0, 0).setUv(1, 0);
        BufferUploader.drawWithShader(buffer.buildOrThrow());
        if (previous != null) RenderSystem.setShader(() -> previous);
    }
}
