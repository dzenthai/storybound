package dev.dzenthai.storybound.client;

import dev.dzenthai.storybound.core.notify.NotifyKind;
import java.util.Locale;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.Nullable;

/** A quest update or a {@code notify} action: a header line (or the title) above the text. */
public final class StoryToast implements Toast {
    private static final ResourceLocation BACKGROUND = ResourceLocation.withDefaultNamespace("toast/advancement");
    private static final long DISPLAY_TIME = 5000L;
    private static final int TEXT_WIDTH = 140;

    private final Component header;
    private final int headerColor;
    private final Component body;
    private @Nullable Language cachedLanguage;
    private @Nullable FormattedCharSequence headerLine;
    private @Nullable FormattedCharSequence bodyLine;
    private boolean bodyEmpty;

    public StoryToast(NotifyKind kind, Component title, @Nullable Component text) {
        if (kind == NotifyKind.INFO) {
            this.header = title;
            this.body = text == null ? Component.empty() : text;
        } else {
            this.header = Component.translatable("storybound.toast." + kind.name().toLowerCase(Locale.ROOT));
            this.body = title;
        }
        this.headerColor = switch (kind) {
            case QUEST_COMPLETED, BOSS_DEFEATED -> 0xFF7ED957;
            case QUEST_FAILED, BOSS_ENCOUNTERED -> 0xFFE05A47;
            case INFO -> 0xFFFFFFFF;
            default -> 0xFFE8C66A;
        };
    }

    @Override
    public Visibility render(GuiGraphics graphics, ToastComponent component, long timeSinceLastVisible) {
        Font font = component.getMinecraft().font;
        Language language = Language.getInstance();
        if (language != cachedLanguage || headerLine == null) {
            cachedLanguage = language;
            bodyEmpty = body.getString().isEmpty();
            headerLine = language.getVisualOrder(font.substrByWidth(header, TEXT_WIDTH));
            bodyLine = bodyEmpty ? null : language.getVisualOrder(font.substrByWidth(body, TEXT_WIDTH));
        }
        graphics.blitSprite(BACKGROUND, 0, 0, width(), height());
        FormattedCharSequence h = headerLine;
        FormattedCharSequence b = bodyLine;
        graphics.drawManaged(() -> {
            if (bodyEmpty || b == null) {
                graphics.drawString(font, h, 10, 12, headerColor, false);
            } else {
                graphics.drawString(font, h, 10, 7, headerColor, false);
                graphics.drawString(font, b, 10, 18, 0xFFFFFFFF, false);
            }
        });
        return timeSinceLastVisible >= DISPLAY_TIME * component.getNotificationDisplayTimeMultiplier() ? Visibility.HIDE : Visibility.SHOW;
    }
}
