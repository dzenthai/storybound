package dev.dzenthai.storybound.client.dev;

import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * The window for a long text (an {@code .sbt} file): a document's text, a Codex section, a boss's lore or tactics.
 * Several lines as they will be laid out, the markup at hand below, and for documents a preview on the real reading
 * screen. What is typed lives here, so the window survives the editor being rebuilt (a snapshot, a preview).
 */
final class TextEditor {
    private static final int MAX_WIDTH = 460;
    private static final int MAX_HEIGHT = 320;
    private static final int MAX_LENGTH = 65536;

    private boolean open;
    private Component title = Component.empty();
    private String value = "";
    private Component hint = Component.empty();
    private @Nullable Consumer<String> onSave;
    private @Nullable Consumer<String> preview;

    boolean isOpen() {
        return open;
    }

    /**
     * @param hint    shown while the text is empty: what to write, or the source language's text
     * @param preview opens the text on its reading screen; null when there is none (Codex, bosses)
     */
    /** Whether the documents' markup line is shown under the box. */
    private boolean markup = true;

    void open(Component title, String text, Component hint, Consumer<String> onSave, @Nullable Consumer<String> preview) {
        this.markup = true;
        show(title, text, hint, onSave, preview);
    }

    /** A text without the documents' markup (a dialogue line, a quest's description): the markup line is not shown. */
    void openPlain(Component title, String text, Component hint, Consumer<String> onSave) {
        this.markup = false;
        show(title, text, hint, onSave, null);
    }

    private void show(Component title, String text, Component hint, Consumer<String> onSave, @Nullable Consumer<String> preview) {
        this.open = true;
        this.title = title;
        this.value = text;
        this.hint = hint;
        this.onSave = onSave;
        this.preview = preview;
    }

    void close() {
        open = false;
        onSave = null;
        preview = null;
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
        int left = left(screen);
        int top = top(screen);
        int w = width(screen);
        int h = height(screen);
        MultiLineEditBox box = new MultiLineEditBox(screen.font(), left + 8, top + 22, w - 16, h - 22 - 46, hint, title);
        box.setCharacterLimit(MAX_LENGTH);
        box.setValue(value);
        box.setValueListener(text -> value = text);
        screen.add(box);
        screen.setFocused(box);
        int buttonY = top + h - 22;
        int buttonW = 90;
        screen.add(Button.builder(Component.literal("💾 ").append(DevUi.text("text.save")), b -> {
            Consumer<String> save = onSave;
            String text = value;
            close();
            if (save != null) {
                save.accept(text);
            }
            screen.rebuild();
        }).bounds(left + w - 8 - buttonW, buttonY, buttonW, 18).build());
        screen.add(Button.builder(DevUi.text("text.cancel"), b -> {
            close();
            screen.rebuild();
        }).bounds(left + w - 8 - buttonW * 2 - 4, buttonY, buttonW, 18).build());
        if (preview != null) {
            Consumer<String> show = preview;
            screen.add(Button.builder(Component.literal("👁 ").append(DevUi.text("text.preview")), b -> show.accept(value))
                    .bounds(left + 8, buttonY, buttonW, 18).build());
        }
    }

    void render(DevModeScreen screen, GuiGraphics graphics) {
        int left = left(screen);
        int top = top(screen);
        int w = width(screen);
        int h = height(screen);
        graphics.fill(0, 0, screen.width, screen.height, 0x88000000);
        graphics.fill(left - 1, top - 1, left + w + 1, top + h + 1, DevUi.ACCENT);
        graphics.fill(left, top, left + w, top + h, DevUi.PANEL);
        graphics.drawString(screen.font(), screen.font().plainSubstrByWidth(title.getString(), w - 16), left + 8, top + 8, DevUi.ACCENT, false);
        // The markup, so the author does not have to remember it.
        if (markup) {
            graphics.drawString(screen.font(), screen.font().plainSubstrByWidth(DevUi.str("text.markup"), w - 16), left + 8, top + h - 36, DevUi.HINT, false);
        }
    }

    /** ESC: give up the edit. */
    boolean escape(DevModeScreen screen) {
        close();
        screen.rebuild();
        return true;
    }
}
