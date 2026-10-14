package dev.dzenthai.storybound.client;

import dev.dzenthai.storybound.core.notify.NotifyKind;
import java.util.Locale;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/** A quest update or a {@code notify} action: a header line (or the title) above the text. */
public final class StoryToast implements Toast {
    private static final ResourceLocation BACKGROUND = ResourceLocation.withDefaultNamespace("toast/advancement");
    private static final long DISPLAY_TIME = 5000L;
    private static final int TEXT_WIDTH = 140;

    private final Component header;
    private final int headerColor;
    private final Component body;

    public StoryToast(NotifyKind kind, Component title, @Nullable Component text) {
        if (kind == NotifyKind.INFO) {
            this.header = title;
            this.body = text == null ? Component.empty() : text;
        } else {
            this.header = Component.translatable("storybound.toast." + kind.name().toLowerCase(Locale.ROOT));
            this.body = title;
        }
        this.headerColor = switch (kind) {
            case QUEST_COMPLETED -> 0xFF7ED957;
            case QUEST_FAILED -> 0xFFE05A47;
            case INFO -> 0xFFFFFFFF;
            default -> 0xFFE8C66A;
        };
    }

    @Override
    public Visibility render(GuiGraphics graphics, ToastComponent component, long timeSinceLastVisible) {
        Font font = component.getMinecraft().font;
        graphics.blitSprite(BACKGROUND, 0, 0, width(), height());
        if (body.getString().isEmpty()) {
            graphics.drawString(font, font.substrByWidth(header, TEXT_WIDTH).getString(), 10, 12, headerColor, false);
        } else {
            graphics.drawString(font, font.substrByWidth(header, TEXT_WIDTH).getString(), 10, 7, headerColor, false);
            graphics.drawString(font, font.substrByWidth(body, TEXT_WIDTH).getString(), 10, 18, 0xFFFFFFFF, false);
        }
        return timeSinceLastVisible >= DISPLAY_TIME * component.getNotificationDisplayTimeMultiplier() ? Visibility.HIDE : Visibility.SHOW;
    }
}
