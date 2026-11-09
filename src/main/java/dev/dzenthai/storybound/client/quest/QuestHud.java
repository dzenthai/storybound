package dev.dzenthai.storybound.client.quest;

import dev.dzenthai.storybound.client.ClientModules;
import dev.dzenthai.storybound.client.ClientQuests;
import dev.dzenthai.storybound.client.HudScale;
import dev.dzenthai.storybound.client.StoryboundClientConfig;
import dev.dzenthai.storybound.client.menu.MenuTheme;
import dev.dzenthai.storybound.client.menu.MenuThemes;
import dev.dzenthai.storybound.compat.cutscene.CutsceneClient;
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

/** The HUD layer with the pinned quests, the main one first: each one's title, time left and open objectives of the current stage. */
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
                || !config.questTracker.get() || !ClientModules.isEnabled("quests") || CutsceneClient.playing()) {
            return;
        }
        MenuTheme theme = MenuThemes.INSTANCE.theme();
        Font font = minecraft.font;
        List<Line> lines = new ArrayList<>();
        for (ResourceLocation quest : ClientQuests.pinned()) {
            QuestDefinition definition = QuestRegistries.QUESTS.clientStore().getOrNull(quest);
            QuestProgress progress = ClientQuests.get(quest).orElse(null);
            if (definition != null && progress != null && progress.status() == QuestStatus.ACTIVE) {
                if (!lines.isEmpty()) {
                    lines.add(Line.GAP);
                }
                addQuest(lines, font, theme, quest, definition, progress);
            }
        }
        if (lines.isEmpty()) {
            return;
        }
        render(graphics, config, font, theme, lines);
    }

    private static void addQuest(List<Line> lines, Font font, MenuTheme theme, ResourceLocation quest, QuestDefinition definition, QuestProgress progress) {
        for (FormattedCharSequence line : font.split(definition.title().asComponent().copy().withStyle(ChatFormatting.BOLD), MAX_WIDTH)) {
            lines.add(new Line(line, theme.hudTitle()));
        }
        QuestsView.secondsLeft(definition, progress).ifPresent(left -> lines.add(new Line(
                Component.translatable("storybound.quests.time_left", QuestsView.clock(left)).getVisualOrderText(),
                left < 60 ? 0xFFD9534F : theme.hudText())));
        Component elsewhere = elsewhere(quest);
        if (elsewhere != null) {
            for (FormattedCharSequence line : font.split(elsewhere, MAX_WIDTH)) {
                lines.add(new Line(line, theme.hudText()));
            }
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
                // Quieter than in the Journal: the hint under its objective, dimmed.
                for (Component hint : QuestsView.shownHints(quest, stage, i)) {
                    for (FormattedCharSequence line : font.split(Component.literal("  » ").append(hint).withStyle(ChatFormatting.ITALIC), MAX_WIDTH)) {
                        lines.add(new Line(line, theme.hudText() & 0x00FFFFFF | 0xA0000000));
                    }
                }
            }
        });
    }

    /**
     * "📍 The Nether": the dimensions the quest's waypoints are in other than the player's (November 9, 2026: before,
     * nothing said where to go, as no marker is drawn in another dimension); null when they are all here or it has none.
     */
    static @org.jetbrains.annotations.Nullable Component elsewhere(ResourceLocation quest) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return null;
        }
        ResourceLocation here = minecraft.level.dimension().location();
        java.util.Set<ResourceLocation> dimensions = new java.util.LinkedHashSet<>();
        for (dev.dzenthai.storybound.module.waypoint.ClientWaypoint waypoint : dev.dzenthai.storybound.client.waypoint.ClientWaypoints.all()) {
            if (waypoint.quest().filter(quest::equals).isPresent() && !waypoint.dimension().equals(here)) {
                dimensions.add(waypoint.dimension());
            }
        }
        if (dimensions.isEmpty()) {
            return null;
        }
        List<String> names = dimensions.stream().map(QuestHud::dimensionName).toList();
        return Component.translatable("storybound.quests.elsewhere", String.join(", ", names));
    }

    /**
     * A dimension's name: ours for the vanilla ones, a mod's own under {@code dimension.<ns>.<path>} (a common way), else
     * its id made readable ("twilightforest:twilight_forest" → "Twilight Forest").
     */
    public static String dimensionName(ResourceLocation dimension) {
        net.minecraft.locale.Language language = net.minecraft.locale.Language.getInstance();
        for (String key : List.of("storybound.dimension." + dimension.getNamespace() + "." + dimension.getPath(),
                "dimension." + dimension.getNamespace() + "." + dimension.getPath())) {
            if (language.has(key)) {
                return language.getOrDefault(key);
            }
        }
        String path = dimension.getPath();
        String last = path.substring(path.lastIndexOf('/') + 1);
        StringBuilder readable = new StringBuilder();
        for (String word : last.split("_")) {
            if (!word.isEmpty()) {
                readable.append(readable.isEmpty() ? "" : " ").append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
        }
        return readable.toString();
    }

    private static void render(GuiGraphics graphics, StoryboundClientConfig config, Font font, MenuTheme theme, List<Line> lines) {
        int width = lines.stream().mapToInt(line -> font.width(line.text())).max().orElse(0) + 2 * PADDING;
        int height = lines.stream().mapToInt(Line::height).sum() + 2 * PADDING - 1;
        StoryboundClientConfig.Corner corner = config.questTrackerCorner.get();
        int offsetY = config.questTrackerOffsetY.get();
        // In HUD pixels, whatever the GUI scale (HudScale).
        HudScale.push(graphics);
        int x = corner.right() ? HudScale.width() - width - MARGIN : MARGIN;
        int y = corner.bottom() ? HudScale.height() - height - offsetY : offsetY;
        graphics.fill(x, y, x + width, y + height, theme.hudBackground());
        int lineY = y + PADDING;
        for (Line line : lines) {
            graphics.drawString(font, line.text(), x + PADDING, lineY, line.color(), true);
            lineY += line.height();
        }
        HudScale.pop(graphics);
    }

    /** @param height {@link #LINE_HEIGHT}, or a small gap between two quests */
    private record Line(FormattedCharSequence text, int color, int height) {
        static final Line GAP = new Line(FormattedCharSequence.EMPTY, 0, 4);

        Line(FormattedCharSequence text, int color) {
            this(text, color, LINE_HEIGHT);
        }
    }
}
