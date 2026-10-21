package dev.dzenthai.storybound.client.dev;

import dev.dzenthai.storybound.client.text.ClientConfigTexts;
import dev.dzenthai.storybound.core.data.ConfigStorySource;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * The dev mode editor for operators: the story canvas with its chapters, or one dialogue's graph (see {@link StoryView}
 * and {@link DialogueView}). This class is the frame around them: the top bar with undo, redo and the language, the
 * status line, the context menu and the modal windows (the catalog, adding a language). Every change is sent to the
 * server at once, which writes the story folder and answers with a new snapshot.
 */
public class DevModeScreen extends Screen {
    private static final int MENU_W = 216;
    private static final int MENU_ITEM_H = 16;
    private static final int LANGUAGE_W = 240;
    private static final int LANGUAGE_H = 110;

    // Kept between openings, like the canvas position in FTB Quests.
    private static final StoryView STORY = new StoryView();
    private static final DialogueView DIALOGUE = new DialogueView();
    private static final DevHistory HISTORY = new DevHistory();
    private static boolean inspectorCollapsed;

    /** A context menu line; {@code danger} lines (deleting) are red. */
    record MenuItem(Component label, boolean danger, Runnable action) {
        static MenuItem of(Component label, Runnable action) {
            return new MenuItem(label, false, action);
        }

        static MenuItem danger(Component label, Runnable action) {
            return new MenuItem(label, true, action);
        }
    }

    private final @Nullable Screen parent;
    private final Catalog catalog = new Catalog();
    private Component statusText = Component.empty();
    private int statusColor = DevUi.OK;
    private List<MenuItem> menu = List.of();
    private int menuX;
    private int menuY;
    private boolean languageOpen;
    private @Nullable EditBox languageBox;
    private Component languageError = Component.empty();

    public DevModeScreen(@Nullable Screen parent) {
        super(DevUi.text("title"));
        this.parent = parent;
    }

    private GraphView view() {
        return DIALOGUE.isOpen() ? DIALOGUE : STORY;
    }

    @Override
    protected void init() {
        ClientDevStore.setListener(() -> minecraft.execute(this::onSnapshot));
        DevRequests.requestSnapshot();
        DevRequests.requestScripts();
        view().reload();
        rebuild();
    }

    @Override
    public void removed() {
        view().saveEdits(this);
        ClientDevStore.setListener(null);
        super.removed();
    }

    /** A new snapshot from the server; ignored while the author types, so the text box keeps what is being typed. */
    private void onSnapshot() {
        if (textFocused()) {
            return;
        }
        STORY.reload();
        if (DIALOGUE.isOpen()) {
            DIALOGUE.reload();
            if (!DIALOGUE.isOpen()) {
                status(DevUi.text("status.dialogue_gone"), DevUi.BAD);
            }
        }
        rebuild();
    }

    // --- services for the views

    <T extends GuiEventListener & Renderable & NarratableEntry> T add(T widget) {
        return addRenderableWidget(widget);
    }

    Font font() {
        return font;
    }

    void focus(EditBox box) {
        setFocused(box);
        box.setFocused(true);
    }

    /** Builds the widgets again: those of an open modal window, or the top bar's and the view's. */
    void rebuild() {
        // A removed text box must not keep the keyboard (Delete would go nowhere, snapshots would wait forever).
        setFocused(null);
        clearWidgets();
        languageBox = null;
        if (catalog.isOpen()) {
            catalog.addWidgets(this);
            return;
        }
        if (languageOpen) {
            addLanguageWidgets();
            return;
        }
        addTopBar();
        view().addWidgets(this);
    }

    void status(Component text, int color) {
        statusText = text;
        statusColor = color;
    }

    /** Records an edit that was just made, so it can be undone. */
    void remember(String label, Runnable undo, Runnable redo) {
        HISTORY.push(label, undo, redo);
        rebuild();
    }

    Catalog catalog() {
        return catalog;
    }

    void openMenu(int x, int y, List<MenuItem> items) {
        menuX = x;
        menuY = y;
        menu = items;
    }

    boolean inspectorCollapsed() {
        return inspectorCollapsed;
    }

    void showInspector() {
        inspectorCollapsed = false;
    }

    /** The canvas ends where the inspector begins. */
    int canvasRight() {
        return view().inspectorVisible(this) ? width - DevUi.RIGHT_PANEL_W : width;
    }

    /** Closes the editor and runs a quick test on the server ("start this quest", "give me this document"...). */
    void playtest(String action, ResourceLocation target) {
        view().saveEdits(this);
        minecraft.setScreen(null);
        DevRequests.quickAction(action, target);
    }

    void openDialogue(ResourceLocation dialogue) {
        STORY.saveEdits(this);
        DIALOGUE.open(dialogue, this);
        showInspector();
        rebuild();
        status(DevUi.text("status.editing_dialogue", dialogue.getPath()), DevUi.OK);
    }

    void closeDialogue() {
        DIALOGUE.close(this);
        STORY.reload();
        rebuild();
        status(DevUi.text("status.back_to_story"), DevUi.NEUTRAL);
    }

    // --- top bar

    private void addTopBar() {
        GraphView view = view();
        int x = 4;
        int backW = font.width(view.backLabel()) + 12;
        add(Button.builder(view.backLabel(), b -> view.back(this)).bounds(x, 3, backW, 18).build());
        x += backW + 2;
        x = topButton(DevUi.text("undo"), HISTORY.canUndo(), x, this::undo);
        x = topButton(DevUi.text("redo"), HISTORY.canRedo(), x, this::redo);
        x += 4;
        x = topButton(Component.literal("🌐 " + ClientDevStore.activeLanguage().toUpperCase(Locale.ROOT)), true, x, this::cycleLanguage);
        add(Button.builder(Component.literal("+"), b -> {
            languageOpen = true;
            languageError = Component.empty();
            rebuild();
        }).tooltip(Tooltip.create(DevUi.text("language.add_tooltip"))).bounds(x, 3, 18, 18).build());
    }

    private int topButton(Component label, boolean active, int x, Runnable action) {
        int w = font.width(label) + 12;
        Button button = Button.builder(label, b -> action.run()).bounds(x, 3, w, 18).build();
        button.active = active;
        add(button);
        return x + w + 2;
    }

    /** Where the top bar's text starts: after its buttons. */
    private int topBarTextX() {
        int x = 0;
        for (GuiEventListener child : children()) {
            if (child instanceof Button button && button.getY() == 3 && button.getX() < width / 2) {
                x = Math.max(x, button.getX() + button.getWidth());
            }
        }
        return x + 8;
    }

    private void renderTopBar(GuiGraphics graphics) {
        GraphView view = view();
        graphics.fill(0, 0, width, DevUi.TOP_BAR_H, DevUi.BAR);
        graphics.fill(0, DevUi.TOP_BAR_H - 1, width, DevUi.TOP_BAR_H, DevUi.PANEL_EDGE);
        int x = topBarTextX();
        graphics.drawString(font, view.title(), x, 8, DevUi.ACCENT, false);
        x += font.width(view.title()) + 12;

        Component language;
        int languageColor;
        if (ClientDevStore.activeLanguage().equalsIgnoreCase(ClientDevStore.sourceLanguage())) {
            language = DevUi.text("language.source", ClientDevStore.sourceLanguage());
            languageColor = DevUi.MUTED;
        } else {
            int missing = view.untranslated();
            language = missing == 0 ? DevUi.text("language.complete") : DevUi.text("language.missing", missing);
            languageColor = missing == 0 ? DevUi.GOOD : DevUi.WARN;
        }
        graphics.drawString(font, language, x, 8, languageColor, false);
        x += font.width(language) + 12;

        int right = view.showsIssues() ? issuesX() - 10 : canvasRight() - 106;
        if (view.showsIssues()) {
            List<String> issues = ClientDevStore.issues();
            graphics.drawString(font, issuesLabel(), issuesX(), 8, issues.isEmpty() ? DevUi.GOOD : 0xFFFF7043, false);
        }
        Component line = !statusText.getString().isEmpty() ? statusText : view.showsIssues() && width > 680 ? DevUi.text("hint") : Component.empty();
        int color = !statusText.getString().isEmpty() ? statusColor : DevUi.HINT;
        if (right - x > 20) {
            graphics.drawString(font, font.plainSubstrByWidth(line.getString(), right - x), x, 8, color, false);
        }
    }

    private Component issuesLabel() {
        int count = ClientDevStore.issues().size();
        return count == 0 ? DevUi.text("issues.none") : DevUi.text("issues.count", count);
    }

    private int issuesX() {
        return width - font.width(issuesLabel()) - 10;
    }

    private void renderIssuesTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        List<String> issues = ClientDevStore.issues();
        if (issues.isEmpty() || !view().showsIssues() || mouseX < issuesX() - 4 || mouseY > DevUi.TOP_BAR_H) {
            return;
        }
        List<FormattedCharSequence> lines = new ArrayList<>();
        for (String issue : issues) {
            lines.addAll(font.split(Component.literal(issue).withColor(issue.contains("[ERROR]") ? DevUi.ERROR : DevUi.WARN), 280));
        }
        graphics.renderTooltip(font, lines, mouseX, mouseY);
    }

    private void undo() {
        DevHistory.Step step = HISTORY.undo();
        afterHistory(step, "status.undone", "status.nothing_to_undo", DevUi.WARN);
    }

    private void redo() {
        DevHistory.Step step = HISTORY.redo();
        afterHistory(step, "status.redone", "status.nothing_to_redo", DevUi.LINK);
    }

    private void afterHistory(@Nullable DevHistory.Step step, String done, String nothing, int color) {
        if (step == null) {
            status(DevUi.text(nothing), DevUi.MUTED);
            return;
        }
        view().reload();
        rebuild();
        status(DevUi.text(done, step.label()), color);
    }

    // --- languages

    private void cycleLanguage() {
        List<String> languages = ClientDevStore.languages();
        if (languages.isEmpty()) {
            return;
        }
        view().saveEdits(this);
        String next = languages.get((languages.indexOf(ClientDevStore.activeLanguage()) + 1) % languages.size());
        setLanguage(next);
    }

    private void setLanguage(String language) {
        ClientDevStore.setActiveLanguage(language);
        ClientConfigTexts.setPreviewLanguage(language);
        STORY.reload();
        if (DIALOGUE.isOpen()) {
            DIALOGUE.reload();
        }
        rebuild();
    }

    private int languageLeft() {
        return (width - LANGUAGE_W) / 2;
    }

    private int languageTop() {
        return (height - LANGUAGE_H) / 2;
    }

    private void addLanguageWidgets() {
        int left = languageLeft();
        int top = languageTop();
        add(Button.builder(Component.literal("✕"), b -> closeLanguage()).bounds(left + LANGUAGE_W - 22, top + 4, 18, 16).build());
        languageBox = new EditBox(font, left + 16, top + 34, 208, 18, DevUi.text("language.code"));
        languageBox.setMaxLength(16);
        languageBox.setHint(DevUi.text("language.code_hint"));
        add(languageBox);
        focus(languageBox);
        add(Button.builder(DevUi.text("language.add"), b -> addLanguage()).bounds(left + 16, top + 60, 100, 20).build());
        add(Button.builder(DevUi.text("cancel"), b -> closeLanguage()).bounds(left + 124, top + 60, 100, 20).build());
    }

    private void addLanguage() {
        if (languageBox == null) {
            return;
        }
        String code = languageBox.getValue().trim().toLowerCase(Locale.ROOT);
        // The server takes only codes that are safe as file names; checking here explains the refusal.
        if (!ConfigStorySource.isLanguageCode(code)) {
            languageError = DevUi.text("language.invalid");
            return;
        }
        List<String> languages = new ArrayList<>(ClientDevStore.languages());
        if (!languages.contains(code)) {
            languages.add(code);
            DevRequests.saveLanguages(ClientDevStore.sourceLanguage(), languages);
        }
        languageOpen = false;
        status(DevUi.text("status.language_added", code), DevUi.OK);
        setLanguage(code);
    }

    private void closeLanguage() {
        languageOpen = false;
        rebuild();
    }

    private void renderLanguageWindow(GuiGraphics graphics) {
        int left = languageLeft();
        int top = languageTop();
        graphics.fill(0, 0, width, height, 0x88000000);
        graphics.fill(left - 1, top - 1, left + LANGUAGE_W + 1, top + LANGUAGE_H + 1, 0xFF3A4150);
        graphics.fill(left, top, left + LANGUAGE_W, top + LANGUAGE_H, 0xFF1C2026);
        graphics.fill(left, top, left + LANGUAGE_W, top + 24, DevUi.ROW_HOVER);
        graphics.drawString(font, DevUi.text("language.title"), left + 8, top + 8, DevUi.ACCENT, false);
        graphics.drawString(font, DevUi.text("language.code_label"), left + 16, top + 24, DevUi.HINT, false);
        graphics.drawString(font, languageError, left + 16, top + 84, DevUi.ERROR, false);
    }

    // --- drawing and input

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, DevUi.BACKGROUND);
        GraphView view = view();
        view.render(this, graphics, mouseX, mouseY);
        if (view.inspectorVisible(this)) {
            DevUi.collapseButton(graphics, canvasRight(), collapseY(), mouseX, mouseY);
        }
        renderTopBar(graphics);

        boolean modal = catalog.isOpen() || languageOpen;
        if (modal) {
            graphics.pose().pushPose();
            graphics.pose().translate(0, 0, 250);
            if (catalog.isOpen()) {
                catalog.render(this, graphics, mouseX, mouseY);
            } else {
                renderLanguageWindow(graphics);
            }
        }
        for (Renderable renderable : renderables) {
            renderable.render(graphics, mouseX, mouseY, partialTick);
        }
        if (modal) {
            graphics.pose().popPose();
        }
        if (!menu.isEmpty()) {
            renderMenu(graphics, mouseX, mouseY);
        }
        renderIssuesTooltip(graphics, mouseX, mouseY);
    }

    private int collapseY() {
        return DevUi.TOP_BAR_H + (height - DevUi.TOP_BAR_H) / 2;
    }

    private int menuLeft() {
        return Math.min(menuX, width - MENU_W - 6);
    }

    private int menuTop() {
        return Math.min(menuY, height - (menu.size() * MENU_ITEM_H + 6) - 6);
    }

    private void renderMenu(GuiGraphics graphics, int mouseX, int mouseY) {
        int left = menuLeft();
        int top = menuTop();
        int height = menu.size() * MENU_ITEM_H + 6;
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 300);
        graphics.fill(left - 1, top - 1, left + MENU_W + 1, top + height + 1, DevUi.ACCENT);
        graphics.fill(left, top, left + MENU_W, top + height, DevUi.BAR);
        for (int i = 0; i < menu.size(); i++) {
            int y = top + 3 + i * MENU_ITEM_H;
            boolean hover = DevUi.inside(mouseX, mouseY, left, y, MENU_W, MENU_ITEM_H);
            if (hover) {
                graphics.fill(left + 2, y, left + MENU_W - 2, y + MENU_ITEM_H, DevUi.ROW_SELECTED);
            }
            MenuItem item = menu.get(i);
            int color = item.danger() ? (hover ? DevUi.BAD : DevUi.ERROR) : (hover ? DevUi.WARN : DevUi.TEXT);
            graphics.drawString(font, font.plainSubstrByWidth(item.label().getString(), MENU_W - 10), left + 6, y + 4, color, false);
        }
        graphics.pose().popPose();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (catalog.isOpen()) {
            return super.mouseClicked(mouseX, mouseY, button) || catalog.mouseClicked(this, mouseX, mouseY);
        }
        if (languageOpen) {
            if (!super.mouseClicked(mouseX, mouseY, button)
                    && !DevUi.inside(mouseX, mouseY, languageLeft(), languageTop(), LANGUAGE_W, LANGUAGE_H)) {
                closeLanguage();
            }
            return true;
        }
        if (!menu.isEmpty()) {
            List<MenuItem> items = menu;
            int left = menuLeft();
            int top = menuTop();
            menu = List.of();
            for (int i = 0; i < items.size(); i++) {
                if (DevUi.inside(mouseX, mouseY, left, top + 3 + i * MENU_ITEM_H, MENU_W, MENU_ITEM_H)) {
                    items.get(i).action().run();
                    break;
                }
            }
            return true;
        }
        if (view().inspectorVisible(this) && button == GLFW.GLFW_MOUSE_BUTTON_LEFT
                && DevUi.onCollapseButton(mouseX, mouseY, canvasRight(), collapseY())) {
            inspectorCollapsed = true;
            rebuild();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button) || view().mouseClicked(this, mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (super.mouseDragged(mouseX, mouseY, button, dragX, dragY) || catalog.isOpen() || languageOpen) {
            return true;
        }
        return view().mouseDragged(this, mouseX, mouseY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (catalog.isOpen() || languageOpen) {
            return super.mouseReleased(mouseX, mouseY, button);
        }
        if (view().mouseReleased(this, mouseX, mouseY)) {
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (catalog.isOpen()) {
            catalog.scroll(scrollY);
            return true;
        }
        GraphView view = view();
        if (!languageOpen && view.inspectorVisible(this) && mouseX >= width - DevUi.RIGHT_PANEL_W && mouseY >= DevUi.TOP_BAR_H) {
            view.scrollInspector(scrollY);
            rebuild();
            return true;
        }
        if (!languageOpen && view.onCanvas(this, mouseX, mouseY)) {
            view.wheel(this, mouseX, mouseY, scrollY);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (catalog.isOpen()) {
            return catalog.keyPressed(this, keyCode) || super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (languageOpen) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                closeLanguage();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                addLanguage();
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (!menu.isEmpty() && keyCode == GLFW.GLFW_KEY_ESCAPE) {
            menu = List.of();
            return true;
        }
        if (hasControlDown() && keyCode == GLFW.GLFW_KEY_Z) {
            if (hasShiftDown()) {
                redo();
            } else {
                undo();
            }
            return true;
        }
        if (hasControlDown() && keyCode == GLFW.GLFW_KEY_Y) {
            redo();
            return true;
        }
        if (textFocused()) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                view().saveEdits(this);
                return true;
            }
            if (keyCode != GLFW.GLFW_KEY_ESCAPE) {
                return super.keyPressed(keyCode, scanCode, modifiers);
            }
        }
        return view().keyPressed(this, keyCode) || super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** Whether a text box has the keyboard. */
    boolean textFocused() {
        for (GuiEventListener child : children()) {
            if (child instanceof EditBox box && box.isFocused()) {
                return true;
            }
        }
        return getFocused() instanceof EditBox;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
