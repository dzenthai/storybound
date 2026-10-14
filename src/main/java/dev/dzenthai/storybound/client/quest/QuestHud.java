package dev.dzenthai.storybound.client.quest;

import dev.dzenthai.storybound.client.ClientModules;
import dev.dzenthai.storybound.client.ClientQuests;
import dev.dzenthai.storybound.client.StoryboundClientConfig;
import dev.dzenthai.storybound.client.menu.MenuTheme;
import dev.dzenthai.storybound.client.menu.MenuThemes;
import dev.dzenthai.storybound.core.state.QuestProgress;
import dev.dzenthai.storybound.core.state.QuestStatus;
import dev.dzenthai.storybound.module.quest.QuestDefinition;
import dev.dzenthai.storybound.module.quest.QuestRegistries;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;

/** The HUD layer with the tracked quest: its title and the open objectives of the current stage. */
public final class QuestHud {
    private static final int MAX_WIDTH = 170;
    private static final int PADDING = 4;
    private static final int MARGIN = 4;
    private static final int LINE_HEIGHT = 10;

    private QuestHud() {}

    public static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        StoryboundClientConfig config = StoryboundClientConfig.INSTANCE;
        if (minecraft.options.hideGui || minecraft.player == null || minecraft.gui.getDebugOverlay().showDebugScreen()
                || !config.questTracker.get() || !ClientModules.isEnabled("quests")) {
            return;
        }
        ResourceLocation quest = ClientQuests.tracked().orElse(null);
        QuestDefinition definition = quest != null ? QuestRegistries.QUESTS.clientStore().getOrNull(quest) : null;
        QuestProgress progress = quest != null ? ClientQuests.get(quest).orElse(null) : null;
        if (definition == null || progress == null || progress.status() != QuestStatus.ACTIVE) {
            return;
        }

        MenuTheme theme = MenuThemes.INSTANCE.theme();
        Font font = minecraft.font;
        List<Line> lines = new ArrayList<>();
        for (FormattedCharSequence line : font.split(definition.title().asComponent().copy().withStyle(ChatFormatting.BOLD), MAX_WIDTH)) {
            lines.add(new Line(line, theme.hudTitle()));
        }
        progress.stage().flatMap(definition::stage).ifPresent(stage -> {
            for (int i = 0; i < stage.objectives().size(); i++) {
                QuestDefinition.Objective objective = stage.objectives().get(i);
                int count = progress.count(stage.objectiveId(i));
                if (objective.hidden() || objective.text().isEmpty() || count >= objective.count()) {
                    continue;
                }
                Component text = Component.literal(QuestsView.objectiveMark(objective, count) + " ").append(QuestsView.objectiveText(objective, count));
                for (FormattedCharSequence line : font.split(text, MAX_WIDTH)) {
                    lines.add(new Line(line, theme.hudText()));
                }
            }
        });

        int width = lines.stream().mapToInt(line -> font.width(line.text())).max().orElse(0) + 2 * PADDING;
        int height = lines.size() * LINE_HEIGHT + 2 * PADDING - 1;
        StoryboundClientConfig.Corner corner = config.questTrackerCorner.get();
        int offsetY = config.questTrackerOffsetY.get();
        int x = corner.right() ? graphics.guiWidth() - width - MARGIN : MARGIN;
        int y = corner.bottom() ? graphics.guiHeight() - height - offsetY : offsetY;
        graphics.fill(x, y, x + width, y + height, theme.hudBackground());
        for (int i = 0; i < lines.size(); i++) {
            graphics.drawString(font, lines.get(i).text(), x + PADDING, y + PADDING + i * LINE_HEIGHT, lines.get(i).color(), true);
        }
    }

    private record Line(FormattedCharSequence text, int color) {}
}
