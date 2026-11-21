package dev.dzenthai.storybound.client.dev;

import com.google.gson.JsonObject;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.network.DevModePayloads;
import java.util.Set;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/**
 * The story's settings: the preferences of {@code project.json} that are not the languages or the namespace (the top
 * bar edits those), in a window over the editor with the form of {@code project.schema.json}: the difficulty scale,
 * tracking a quest when it is given. Save writes them (the server checks them and reloads the story); Cancel and Esc
 * leave the file as it was. Not in the undo history.
 */
final class SettingsWindow {
    private static final int MAX_WIDTH = 420;
    private static final int MAX_HEIGHT = 360;
    /** Edited by the top bar, never here. */
    private static final Set<String> HIDDEN = Set.of("source_language", "languages", "namespace", "title", "enabled");

    private boolean open;
    private JsonObject editing = new JsonObject();
    private DevModeScreen screen;
    private final FormView form = new FormView(new FormView.Host() {
        @Override
        public JsonObject root() {
            return editing;
        }

        @Override
        public String textKey(String path, String field) {
            return ClientDevStore.generateUniqueKey(field);
        }

        @Override
        public void edited() {
            // Text boxes write into the object as they are typed in; Save sends it.
        }

        @Override
        public void changed() {
            if (screen != null) {
                screen.rebuild();
            }
        }
    });

    boolean isOpen() {
        return open;
    }

    /** Opens on the settings the server sent with the last snapshot. */
    void open() {
        open = true;
        editing = ClientDevStore.projectOptions();
        form.reset(Schemas.definition(Storybound.id("project")), DevUi.str("settings.title"), HIDDEN);
    }

    void close() {
        open = false;
    }

    void scroll(double amount) {
        form.scroll(amount);
    }

    private int width(DevModeScreen screen) {
        return Math.min(MAX_WIDTH, screen.width - 40);
    }

    private int height(DevModeScreen screen) {
        return Math.min(MAX_HEIGHT, screen.height - 40);
    }

    private int left(DevModeScreen screen) {
        return (screen.width - width(screen)) / 2;
    }

    private int top(DevModeScreen screen) {
        return (screen.height - height(screen)) / 2;
    }

    void addWidgets(DevModeScreen screen) {
        this.screen = screen;
        int left = left(screen);
        int top = top(screen);
        int w = width(screen);
        int h = height(screen);
        int buttonY = top + h - 22;
        form.addWidgets(screen, left + 8, top + 58, w - 16, buttonY - 8);
        // The namespace under its grey label, its value on the button (the forms' rule).
        screen.add(Button.builder(Component.literal(ClientDevStore.namespace() + " ▸"), b -> screen.openNamespace())
                .tooltip(Tooltip.create(DevUi.text("projects.namespace.tooltip")))
                .bounds(left + 8, top + 34, w - 16, 18).build());
        int buttonW = 90;
        screen.add(Button.builder(Component.literal("💾 ").append(DevUi.text("settings.save")), b -> {
            DevRequests.send(new DevModePayloads.SaveProjectOptions(editing.toString()));
            close();
            screen.status(DevUi.text("status.settings_saved"), DevUi.OK);
            screen.rebuild();
        }).bounds(left + w - 8 - buttonW, buttonY, buttonW, 18).build());
        screen.add(Button.builder(DevUi.text("text.cancel"), b -> escape(screen))
                .bounds(left + w - 8 - buttonW * 2 - 4, buttonY, buttonW, 18).build());
    }

    void render(DevModeScreen screen, GuiGraphics graphics) {
        int left = left(screen);
        int top = top(screen);
        int w = width(screen);
        int h = height(screen);
        graphics.fill(0, 0, screen.width, screen.height, 0x88000000);
        graphics.fill(left - 1, top - 1, left + w + 1, top + h + 1, DevUi.ACCENT);
        graphics.fill(left, top, left + w, top + h, DevUi.PANEL);
        graphics.drawString(screen.font(), DevUi.text("settings.title"), left + 8, top + 8, DevUi.ACCENT, false);
        graphics.drawString(screen.font(), DevUi.text("projects.namespace"), left + 8, top + 23, DevUi.LABEL, false);
        form.render(graphics, screen.font());
    }

    /** ESC or Cancel: nothing is written. */
    boolean escape(DevModeScreen screen) {
        close();
        screen.rebuild();
        return true;
    }
}
