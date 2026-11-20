package dev.dzenthai.storybound.client;

import dev.dzenthai.storybound.core.data.StoryFolder;
import dev.dzenthai.storybound.core.data.WorldStory;
import java.util.Comparator;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.GameRules;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * "Story: Yes / No" on the world creation screen's Game tab, under "Allow Commands": the new world's
 * {@link WorldStory} rule, Yes unless the player says no. It shows only on the Game tab (that tab's widgets leave the
 * screen on another one) and follows the rule as the Game Rules screen may have changed it.
 */
public final class WorldStoryButton {
    /** Below the tab's last row, as its rows are spaced: 20 high, 8 apart. */
    private static final int ROW = 28;
    private static final int WIDTH = 210;
    /** The screens whose world got its default already: a resize builds the screen again and must not undo a "No". */
    private static final Map<Screen, Boolean> DEFAULTED = new WeakHashMap<>();
    private static final Map<Screen, Placed> PLACED = new WeakHashMap<>();

    private record Placed(AbstractWidget anchor, CycleButton<Boolean> button) {}

    private WorldStoryButton() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(WorldStoryButton::onInit);
        NeoForge.EVENT_BUS.addListener(WorldStoryButton::onRender);
    }

    private static void onInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof CreateWorldScreen screen) || WorldStory.bundledRoot().isEmpty()) {
            return;
        }
        GameRules rules = screen.getUiState().getGameRules();
        if (DEFAULTED.put(screen, true) == null) {
            // A new world plays the story unless the player says no.
            rules.getRule(WorldStory.rule()).set(true, null);
        }
        // The Game tab's last row, "Allow Commands": the lowest of its buttons as wide as this one.
        AbstractWidget anchor = event.getListenersList().stream()
                .filter(listener -> listener instanceof CycleButton<?> cycle && cycle.getWidth() == WIDTH)
                .map(listener -> (AbstractWidget) listener)
                .max(Comparator.comparingInt(AbstractWidget::getY)).orElse(null);
        if (anchor == null) {
            return;
        }
        Component tooltip = Component.translatable("storybound.create_world.story.tooltip", StoryFolder.bundledTitle(WorldStory.BUNDLED));
        CycleButton<Boolean> button = CycleButton.booleanBuilder(CommonComponents.GUI_YES, CommonComponents.GUI_NO)
                .withInitialValue(rules.getBoolean(WorldStory.rule()))
                .withTooltip(value -> Tooltip.create(tooltip))
                .create(anchor.getX(), anchor.getY() + ROW, WIDTH, 20, Component.translatable("storybound.create_world.story"),
                        (cycle, value) -> screen.getUiState().getGameRules().getRule(WorldStory.rule()).set(value, null));
        event.addListener(button);
        PLACED.put(screen, new Placed(anchor, button));
    }

    private static void onRender(ScreenEvent.Render.Pre event) {
        Placed placed = PLACED.get(event.getScreen());
        if (placed == null || !(event.getScreen() instanceof CreateWorldScreen screen)) {
            return;
        }
        placed.button().visible = screen.children().contains(placed.anchor());
        placed.button().setPosition(placed.anchor().getX(), placed.anchor().getY() + ROW);
        boolean value = screen.getUiState().getGameRules().getBoolean(WorldStory.rule());
        if (placed.button().getValue() != value) {
            placed.button().setValue(value);
        }
    }
}
