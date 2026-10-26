package dev.dzenthai.storybound.client.menu;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.Nullable;

/** Keeps clipping and tooltip placement in the same space as book rendering. */
public final class BookGuiGraphics extends GuiGraphics {
    private final BookViewport viewport;

    public BookGuiGraphics(Minecraft minecraft, GuiGraphics source, BookViewport viewport) {
        super(minecraft, source.bufferSource());
        this.viewport = viewport;
        pose().translate(viewport.x(), viewport.y(), 0);
        pose().scale((float) viewport.scale(), (float) viewport.scale(), 1);
    }

    @Override public int guiWidth() { return BookViewport.WIDTH; }
    @Override public int guiHeight() { return BookViewport.HEIGHT; }

    @Override
    public int drawString(Font font, @Nullable String text, int x, int y, int color, boolean shadow) {
        if (text == null) return 0;
        return drawString(font, Component.literal(text).getVisualOrderText(), x, y, color, shadow);
    }

    @Override
    public int drawString(Font font, Component text, int x, int y, int color, boolean shadow) {
        return drawString(font, text.getVisualOrderText(), x, y, color, shadow);
    }

    @Override
    public int drawString(Font font, FormattedCharSequence text, int x, int y, int color, boolean shadow) {
        if (shadow) return super.drawString(font, text, x, y, color, true);
        boolean[] bold = {false};
        text.accept((index, style, codePoint) -> {
            bold[0] |= style.isBold();
            return true;
        });
        // Keep both vanilla impressions and bridge their subpixel sampling gaps inside the same bounds.
        if (bold[0]) {
            BookTextRendering.begin();
            try {
                return super.drawString(font, text, x, y, color, false);
            } finally {
                BookTextRendering.end();
            }
        }
        FormattedCharSequence underprint = sink -> text.accept((index, style, codePoint) ->
                sink.accept(index, style.withColor(0x725136), codePoint));
        float localScale = Math.max(1, pose().last().pose().m00() / (float) viewport.scale());
        pose().pushPose();
        pose().translate(0.35f / localScale, 0.35f / localScale, 0);
        super.drawString(font, underprint, x, y, 0x38725136, false);
        pose().popPose();
        pose().pushPose();
        pose().translate(0, 0, 0.03f);
        int end = super.drawString(font, text, x, y, color, false);
        pose().popPose();
        return end;
    }

    @Override
    public void enableScissor(int left, int top, int right, int bottom) {
        super.enableScissor((int) Math.floor(viewport.toGuiX(left)), (int) Math.floor(viewport.toGuiY(top)),
                (int) Math.ceil(viewport.toGuiX(right)), (int) Math.ceil(viewport.toGuiY(bottom)));
    }

    @Override
    public boolean containsPointInScissor(int x, int y) {
        return super.containsPointInScissor((int) viewport.toGuiX(x), (int) viewport.toGuiY(y));
    }
}
