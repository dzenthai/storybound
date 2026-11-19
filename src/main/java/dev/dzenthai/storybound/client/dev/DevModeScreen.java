package dev.dzenthai.storybound.client.dev;

import com.mojang.serialization.JsonOps;
import dev.dzenthai.storybound.client.document.DocumentScreen;
import dev.dzenthai.storybound.client.text.ClientConfigTexts;
import dev.dzenthai.storybound.core.data.ConfigStorySource;
import dev.dzenthai.storybound.core.story.StoryLinks.NodeKey;
import dev.dzenthai.storybound.core.story.StoryRenames;
import dev.dzenthai.storybound.module.document.DocumentDefinition;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.Util;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
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
    private static StoryView STORY = new StoryView();
    private static DialogueView DIALOGUE = new DialogueView();
    private static QuestView QUEST = new QuestView();
    private static DevHistory HISTORY = new DevHistory();
    private static boolean inspectorCollapsed;
    private record Workspace(StoryView story, DialogueView dialogue, QuestView quest, DevHistory history) {}
    private static final Map<String, Workspace> WORKSPACES = new HashMap<>();
    private static String workspace = "";

    private void selectWorkspace() {
        if (!workspace.equals(ClientDevStore.projectId())) {
            WORKSPACES.put(workspace, new Workspace(STORY, DIALOGUE, QUEST, HISTORY));
            workspace = ClientDevStore.projectId();
            Workspace next = WORKSPACES.computeIfAbsent(workspace,
                    key -> new Workspace(new StoryView(), new DialogueView(), new QuestView(), new DevHistory()));
            STORY = next.story();
            DIALOGUE = next.dialogue();
            QUEST = next.quest();
            HISTORY = next.history();
            finder = new StoryFinder(STORY);
        }
    }

    public static void clearProjects() {
        WORKSPACES.clear();
        workspace = "";
        STORY = new StoryView();
        DIALOGUE = new DialogueView();
        QUEST = new QuestView();
        HISTORY = new DevHistory();
    }


    /** A context menu line; {@code danger} lines (deleting) are red; {@code swatch} a colour shown before it (0: none). */
    record MenuItem(Component label, boolean danger, Runnable action, int swatch) {
        MenuItem(Component label, boolean danger, Runnable action) {
            this(label, danger, action, 0);
        }

        static MenuItem of(Component label, Runnable action) {
            return new MenuItem(label, false, action);
        }

        /** A line with the colour it picks. */
        static MenuItem color(Component label, int color, Runnable action) {
            return new MenuItem(label, false, action, 0xFF000000 | color);
        }

        static MenuItem danger(Component label, Runnable action) {
            return new MenuItem(label, true, action);
        }
    }

    private final @Nullable Screen parent;
    private final Catalog catalog = new Catalog();
    private final TextEditor textEditor = new TextEditor();
    private final SettingsWindow settings = new SettingsWindow();
    private StoryFinder finder = new StoryFinder(STORY);
    private Component statusText = Component.empty();
    private int statusColor = DevUi.OK;
    private List<MenuItem> menu = List.of();
    private int menuX;
    private int menuY;
    private boolean languageOpen;
    private @Nullable EditBox languageBox;
    private Component languageError = Component.empty();
    /**
     * The modal window shows the story's namespace instead of a new language: first its box, then, after a change, the
     * offer to move the nodes left in the old one ({@link #namespaceMoveFrom} set).
     */
    private boolean namespaceWindow;
    private @Nullable String namespaceMoveFrom;
    private int namespaceMoveCount;
    private @Nullable EditBox namespaceBox;

    public DevModeScreen(@Nullable Screen parent) {
        super(DevUi.text("title"));
        this.parent = parent;
        DevBackground.shuffle();
    }

    private GraphView view() {
        return DIALOGUE.isOpen() ? DIALOGUE : QUEST.isOpen() ? QUEST : STORY;
    }

    @Override
    protected void init() {
        DevUi.fitPanels(width);
        selectWorkspace();
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
        selectWorkspace();
        if (textFocused()) {
            return;
        }
        STORY.reload();
        if (QUEST.isOpen()) {
            QUEST.reload();
        }
        if (DIALOGUE.isOpen()) {
            DIALOGUE.reload();
            if (!DIALOGUE.isOpen()) {
                status(DevUi.text("status.dialogue_gone"), DevUi.BAD);
            }
        }
        rebuild();
    }

    // --- services for the views

    /** Adds a widget; a Minecraft button becomes the editor's own, flat with its icon ({@link DevButton}). */
    <T extends GuiEventListener & Renderable & NarratableEntry> T add(T widget) {
        if (widget instanceof Button button && !(widget instanceof DevButton)) {
            addRenderableWidget(new DevButton(button));
            return widget;
        }
        return addRenderableWidget(widget);
    }

    Font font() {
        return font;
    }

    void focus(EditBox box) {
        setFocused(box);
        box.setFocused(true);
    }

    /** Which modal window the widgets were last built for ("" for none): see {@link #render}. */
    private String builtFor = "";

    /**
     * Whether a modal window is open: the panels under it draw only their background, as their widgets are the window's
     * now (before, the inspector's labels stayed without their fields).
     */
    boolean modalOpen() {
        return !modalNow().isEmpty();
    }

    /** Whether a right-click menu is open. */
    boolean menuOpen() {
        return !menu.isEmpty();
    }

    private String modalNow() {
        return catalog.isOpen() ? "catalog" : textEditor.isOpen() ? "text" : settings.isOpen() ? "settings" : finder.isOpen() ? "finder"
                : languageOpen ? (namespaceWindow ? "namespace" : "language") : "";
    }

    /** Builds the widgets again: those of an open modal window, or the top bar's and the view's. */
    void rebuild() {
        builtFor = modalNow();
        // A removed text box must not keep the keyboard (Delete would go nowhere, snapshots would wait forever).
        setFocused(null);
        clearWidgets();
        languageBox = null;
        if (catalog.isOpen()) {
            catalog.addWidgets(this);
            return;
        }
        if (textEditor.isOpen()) {
            textEditor.addWidgets(this);
            return;
        }
        if (settings.isOpen()) {
            settings.addWidgets(this);
            return;
        }
        if (finder.isOpen()) {
            finder.addWidgets(this);
            return;
        }
        if (languageOpen) {
            if (namespaceWindow) {
                addNamespaceWidgets();
            } else {
                addLanguageWidgets();
            }
            return;
        }
        addTopBar();
        view().addWidgets(this);
    }

    void status(Component text, int color) {
        statusText = text;
        statusColor = color;
        statusAt = Util.getMillis();
    }

    /** Records an edit that was just made, so it can be undone. */
    void remember(String label, Runnable undo, Runnable redo) {
        HISTORY.push(label, undo, redo);
        rebuild();
    }

    TextEditor textEditor() {
        return textEditor;
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

    /** Shows a document with a text not saved yet on its real reading screen; closing it comes back here, the text window still open. */
    void preview(ResourceLocation document, String text) {
        var definition = DocumentDefinition.CODEC
                .parse(JsonOps.INSTANCE, ClientDevStore.parseObject(
                        ClientDevStore.byType(ClientDevStore.TYPE_DOCUMENT).getOrDefault(document, "{}"))).result().orElse(null);
        if (definition == null) {
            status(DevUi.text("status.preview_unsaved"), DevUi.WARN);
            return;
        }
        minecraft.setScreen(new DocumentScreen(document, this, text).previewDefinition(definition));
    }

    /** Opens "inside the quest": its stages and what each one touches. */
    void openQuest(ResourceLocation quest) {
        STORY.saveEdits(this);
        QUEST.open(quest);
        rebuild();
        status(DevUi.text("status.inside_quest", quest.getPath()), DevUi.OK);
    }

    void closeQuest() {
        QUEST.close();
        STORY.reload();
        rebuild();
        status(DevUi.text("status.back_to_story"), DevUi.NEUTRAL);
    }

    /** Shows the node selected on the "All links" canvas. */
    void revealAmongLinks(NodeKey key) {
        STORY.reveal(this, StoryView.Tab.ALL, key);
    }

    void openDialogue(ResourceLocation dialogue) {
        STORY.saveEdits(this);
        DIALOGUE.open(dialogue, this);
        showInspector();
        rebuild();
        status(DevUi.text("status.editing_dialogue", dialogue.getPath()), DevUi.OK);
    }

    /** A renamed dialogue stays open under its new id. */
    void dialogueRenamed(ResourceLocation from, ResourceLocation to) {
        DIALOGUE.renamed(from, to);
    }

    void closeDialogue() {
        DIALOGUE.close(this);
        STORY.reload();
        if (QUEST.isOpen()) {
            QUEST.reload();
        }
        rebuild();
        status(DevUi.text("status.back_to_story"), DevUi.NEUTRAL);
    }

    // --- top bar

    private void addTopBar() {
        // Small signs with their words on hover (November 16, 2026, the author: "heavy, not as minimal as FTB"; before,
        // "← Menu", "↶ Undo", "Redo ↷", "🌐 EN_US", "+", "Story: …", "⚙ Settings" written out).
        GraphView view = view();
        int x = 4;
        x = topButton(Component.literal("←"), view.backLabel(), true, x, () -> view.back(this));
        x += 4;
        x = topButton(Component.literal("↶"), DevUi.text("undo"), HISTORY.canUndo(), x, this::undo);
        x = topButton(Component.literal("↷"), DevUi.text("redo"), HISTORY.canRedo(), x, this::redo);
        x += 4;
        // The order the author set (November 19, 2026): at the left ← ↶ ↷, the stories, then the view's own (the story's
        // search and whose progress); in the middle what the map shows; at the right the language, the view's own (the
        // problems), the story's settings at the very end.
        List<TopButton> viewLeft = view.topLeft(this);
        int viewLeftW = viewLeft.stream().mapToInt(button -> DevButton.oddWidth(font, button.label()) + 2).sum();
        int centerWidth = Math.max(DevButton.oddWidth(font, DevUi.text("canvas.show_all_links")),
                DevButton.oddWidth(font, DevUi.text("canvas.show_chapters"))) + 8;
        // The stories' button with the edited one's title, as long as there is room before the middle.
        int room = (width - centerWidth) / 2 - 4 - x - viewLeftW - 4;
        if (room >= 19) {
            String title = font.plainSubstrByWidth(ClientDevStore.projectTitle(), Math.max(0, Math.min(85, room - 26)));
            x = topButton(Component.literal("📁" + (title.isEmpty() ? "" : " " + title)),
                    DevUi.text("projects.title").copy().append(": " + ClientDevStore.projectTitle()), true, x, this::openProjects) + 4;
        }
        for (TopButton button : viewLeft) {
            x = topButton(button.label(), button.tooltip(), true, x, button.action());
        }

        // The story's settings at the bar's right end, straight to their window (November 19, 2026, the author: next to
        // the language nobody would look for them, and their menu's "Stories" was the 📁 button again).
        int right = width - 4 - DevButton.oddWidth(font, Component.literal("⛭"));
        topButton(Component.literal("⛭"), DevUi.text("top.settings.tooltip"), true, right, () -> {
            view.saveEdits(this);
            settings.open();
            rebuild();
        });
        List<TopButton> viewRight = view.topRight(this);
        for (int i = viewRight.size() - 1; i >= 0; i--) {
            TopButton button = viewRight.get(i);
            right -= DevButton.oddWidth(font, button.label()) + 2;
            topButton(button.label(), button.tooltip(), true, right, button.action());
        }
        // The story's languages to pick from and adding one, under the button (November 19, 2026, the author; before, a
        // click went to the next language and adding one was in the settings' menu).
        Component language = Component.literal("🌐 " + ClientDevStore.activeLanguage().split("_")[0].toUpperCase(Locale.ROOT));
        int languageX = right - DevButton.oddWidth(font, language) - 2;
        topButton(language, DevUi.text("top.language.tooltip", ClientDevStore.activeLanguage()), true, languageX,
                () -> openMenu(languageX, DevUi.TOP_BAR_H, languageMenu()));
    }

    /** A button a view puts in the top bar: its sign (and words), what it says on hover, what it does. */
    record TopButton(Component label, Component tooltip, Runnable action) {}


    /** The language button's menu: every language of the story, ✔ the one written in, then adding one. */
    private List<MenuItem> languageMenu() {
        List<String> languages = new ArrayList<>(ClientDevStore.languages());
        if (!languages.contains(ClientDevStore.sourceLanguage())) {
            languages.addFirst(ClientDevStore.sourceLanguage());
        }
        List<MenuItem> items = new ArrayList<>();
        for (String code : languages) {
            var info = minecraft.getLanguageManager().getLanguage(code);
            String name = info == null ? code : info.name() + " (" + code + ")";
            Component label = Component.literal(code.equalsIgnoreCase(ClientDevStore.activeLanguage()) ? "✔ " : "").append(name);
            if (code.equalsIgnoreCase(ClientDevStore.sourceLanguage())) {
                label = label.copy().append(" · ").append(DevUi.text("top.language.source"));
            }
            items.add(MenuItem.of(label, () -> {
                if (!code.equalsIgnoreCase(ClientDevStore.activeLanguage())) {
                    view().saveEdits(this);
                    setLanguage(code);
                }
            }));
        }
        items.add(MenuItem.of(Component.literal("+ ").append(DevUi.text("top.menu.language")), () -> {
            languageOpen = true;
            languageError = Component.empty();
            rebuild();
        }));
        return items;
    }

    private void openProjects() {
        view().saveEdits(this);
        minecraft.setScreen(new ProjectsScreen(this));
    }

    void openNamespace() {
        settings.close();
        languageOpen = true;
        namespaceWindow = true;
        namespaceMoveFrom = null;
        languageError = Component.empty();
        rebuild();
    }

    private int topButton(Component label, Component tooltip, boolean active, int x, Runnable action) {
        // Odd sizes, so a 9-pixel icon sits exactly in the middle (November 17, 2026).
        int w = DevButton.oddWidth(font, label);
        Button button = Button.builder(label, b -> action.run()).tooltip(Tooltip.create(tooltip)).bounds(x, 3, w, 19).build();
        button.active = active;
        add(button);
        return x + w + 2;
    }

    /** Where the top bar's text starts: after its buttons. */
    private int topBarTextX() {
        int x = 0;
        for (GuiEventListener child : children()) {
            if (child instanceof Button button && button.getY() == 3 && button.getX() + button.getWidth() <= width / 2) {
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
        if (!view.title().getString().isEmpty()) {
            graphics.drawString(font, view.title(), x, 8, DevUi.ACCENT, false);
            x += font.width(view.title()) + 12;
        }
        // Writing in the source language needs no word; a translation says how much is left.
        if (!ClientDevStore.activeLanguage().equalsIgnoreCase(ClientDevStore.sourceLanguage())) {
            int missing = view.untranslated();
            Component language = missing == 0 ? DevUi.text("language.complete") : DevUi.text("language.missing", missing);
            graphics.drawString(font, language, x, 8, missing == 0 ? DevUi.GOOD : DevUi.WARN, false);
            x += font.width(language) + 12;
        }
        int right = view.showsIssues() ? issuesX() - 10 : topBarRightButtons() - 8;
        if (view.showsIssues()) {
            List<String> issues = ClientDevStore.issues();
            graphics.drawString(font, issuesLabel(), issuesX(), 8, issues.isEmpty() ? DevUi.GOOD : DevUi.BAD, false);
        }
        // What was just done shows a few seconds, then the bar is quiet again.
        boolean fresh = !statusText.getString().isEmpty() && Util.getMillis() - statusAt < STATUS_MILLIS;
        if (fresh && right - x > 20) {
            drawCut(graphics, statusText.getString(), x, 8, right - x, statusColor);
        }
    }

    /** How long a status line stays in the top bar. */
    private static final long STATUS_MILLIS = 6000;
    private long statusAt;

    /** Where the next buttons after the left ones begin (a view's button in the middle, or those at the right end), else the canvas's right edge. */
    private int topBarRightButtons() {
        int left = topBarTextX();
        int x = Integer.MAX_VALUE;
        for (GuiEventListener child : children()) {
            if (child instanceof Button button && button.getY() == 3 && button.getX() >= left) {
                x = Math.min(x, button.getX());
            }
        }
        return x == Integer.MAX_VALUE ? canvasRight() - 106 : x;
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
        view().beforeHistoryReload();
        view().reload();
        rebuild();
        status(DevUi.text(done, step.label()), color);
    }

    // --- languages

    private void setLanguage(String language) {
        ClientDevStore.setActiveLanguage(language);
        ClientConfigTexts.setPreviewLanguage(language);
        STORY.reload();
        if (QUEST.isOpen()) {
            QUEST.reload();
        }
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
        namespaceWindow = false;
        namespaceMoveFrom = null;
        rebuild();
    }

    // --- the story's namespace

    private void addNamespaceWidgets() {
        int left = languageLeft();
        int top = languageTop();
        add(Button.builder(Component.literal("✕"), b -> closeLanguage()).bounds(left + LANGUAGE_W - 22, top + 4, 18, 16).build());
        if (namespaceMoveFrom != null) {
            String from = namespaceMoveFrom;
            String to = ClientDevStore.namespace();
            add(Button.builder(DevUi.text("namespace.move"), b -> moveNamespace(from, to)).bounds(left + 16, top + 60, 100, 20).build());
            add(Button.builder(DevUi.text("namespace.keep"), b -> closeLanguage()).bounds(left + 124, top + 60, 100, 20).build());
            return;
        }
        namespaceBox = new EditBox(font, left + 16, top + 34, 208, 18, DevUi.text("namespace.title"));
        namespaceBox.setMaxLength(64);
        namespaceBox.setValue(ClientDevStore.namespace());
        add(namespaceBox);
        focus(namespaceBox);
        add(Button.builder(DevUi.text("namespace.save"), b -> saveNamespace()).bounds(left + 16, top + 60, 100, 20).build());
        add(Button.builder(DevUi.text("cancel"), b -> closeLanguage()).bounds(left + 124, top + 60, 100, 20).build());
    }

    /**
     * Makes the story's namespace the typed one: new nodes go there from now on. When nodes are left in the old one
     * (the debug scenarios' never are: they have their own), the window offers to move them.
     */
    private void saveNamespace() {
        if (namespaceBox == null) {
            return;
        }
        String value = namespaceBox.getValue().trim().toLowerCase(Locale.ROOT);
        String old = ClientDevStore.namespace();
        if (value.equals(old)) {
            closeLanguage();
            return;
        }
        if (!StoryRenames.isStoryNamespace(value)) {
            languageError = DevUi.text("namespace.invalid");
            return;
        }
        DevRequests.saveNamespace(value);
        ClientDevStore.setNamespace(value);
        status(DevUi.text("namespace.saved", value), DevUi.OK);
        int left = 0;
        for (ResourceLocation type : StoryRenames.RENAMEABLE) {
            left += (int) ClientDevStore.byType(type).keySet().stream().filter(id -> id.getNamespace().equals(old)).count();
        }
        if (left > 0 && !old.equals(dev.dzenthai.storybound.core.data.StoryFolder.SCENARIO)) {
            namespaceMoveFrom = old;
            namespaceMoveCount = left;
            languageError = Component.empty();
            rebuild();
        } else {
            closeLanguage();
        }
    }

    /** One undoable step: undo moves them back. */
    private void moveNamespace(String from, String to) {
        STORY.saveEdits(this);
        requestMoveNamespace(from, to);
        remember(DevUi.str("history.namespace", from, to), () -> requestMoveNamespace(to, from), () -> requestMoveNamespace(from, to));
        closeLanguage();
    }

    private void requestMoveNamespace(String from, String to) {
        STORY.namespaceMoved(from, to);
        DevRequests.moveNamespace(from, to);
        status(DevUi.text("namespace.moving", from, to), DevUi.OK);
    }

    private void renderNamespaceWindow(GuiGraphics graphics) {
        int left = languageLeft();
        int top = languageTop();
        graphics.fill(0, 0, width, height, 0x88000000);
        graphics.fill(left - 1, top - 1, left + LANGUAGE_W + 1, top + LANGUAGE_H + 1, DevUi.PANEL_EDGE);
        graphics.fill(left, top, left + LANGUAGE_W, top + LANGUAGE_H, DevUi.PANEL);
        graphics.fill(left, top, left + LANGUAGE_W, top + 24, DevUi.ROW_HOVER);
        graphics.drawString(font, DevUi.text("namespace.title"), left + 8, top + 8, DevUi.ACCENT, false);
        if (namespaceMoveFrom != null) {
            List<FormattedCharSequence> lines = font.split(DevUi.text("namespace.move_question", namespaceMoveFrom, namespaceMoveCount, ClientDevStore.namespace()), LANGUAGE_W - 32);
            for (int i = 0; i < Math.min(2, lines.size()); i++) {
                graphics.drawString(font, lines.get(i), left + 16, top + 30 + i * 11, DevUi.LABEL, false);
            }
            graphics.drawString(font, font.plainSubstrByWidth(DevUi.str("namespace.move_hint"), LANGUAGE_W - 32), left + 16, top + 86, DevUi.HINT, false);
            return;
        }
        graphics.drawString(font, DevUi.text("namespace.label"), left + 16, top + 24, DevUi.HINT, false);
        graphics.drawString(font, languageError, left + 16, top + 84, DevUi.ERROR, false);
    }

    private void renderLanguageWindow(GuiGraphics graphics) {
        int left = languageLeft();
        int top = languageTop();
        graphics.fill(0, 0, width, height, 0x88000000);
        graphics.fill(left - 1, top - 1, left + LANGUAGE_W + 1, top + LANGUAGE_H + 1, DevUi.PANEL_EDGE);
        graphics.fill(left, top, left + LANGUAGE_W, top + LANGUAGE_H, DevUi.PANEL);
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
        cuts.clear();
        // A window opened or closed without a rebuild (the text window, the catalog: open() only marks them open) gets
        // its widgets before anything can be clicked. Before (October 29, 2026), the text window showed but took no
        // input, and the inspector's buttons under it stayed live: its Save rebuilt the screen, only then the window worked.
        if (!modalNow().equals(builtFor)) {
            rebuild();
        } else if (view().inspectorVisible(this) && view().animateInspector()) {
            // The inspector's rows glided a pixel: laid out again where they are now.
            rebuild();
        }
        graphics.fill(0, 0, width, height, DevUi.BACKGROUND);
        GraphView view = view();
        view.render(this, graphics, mouseX, mouseY);
        if (view.inspectorVisible(this)) {
            DevUi.collapseButton(graphics, canvasRight(), collapseY(), mouseX, mouseY);
        }
        renderTopBar(graphics);

        boolean modal = catalog.isOpen() || languageOpen || textEditor.isOpen() || settings.isOpen() || finder.isOpen();
        if (modal) {
            graphics.pose().pushPose();
            graphics.pose().translate(0, 0, 250);
            if (catalog.isOpen()) {
                catalog.render(this, graphics, mouseX, mouseY);
            } else if (textEditor.isOpen()) {
                textEditor.render(this, graphics);
            } else if (settings.isOpen()) {
                settings.render(this, graphics);
            } else if (finder.isOpen()) {
                finder.render(this, graphics, mouseX, mouseY);
            } else if (namespaceWindow) {
                renderNamespaceWindow(graphics);
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
        for (Cut cut : cuts) {
            if (mouseX >= cut.x() && mouseX < cut.x() + cut.width() && mouseY >= cut.y() && mouseY < cut.y() + 9) {
                graphics.renderTooltip(font, font.split(Component.literal(cut.text()), 300), mouseX, mouseY);
                break;
            }
        }
    }

    /** A line drawn cut to fit: its whole text, shown on hover (November 9, 2026). */
    private record Cut(int x, int y, int width, String text) {}

    private final List<Cut> cuts = new ArrayList<>();

    /**
     * Draws a line of text cut to the width, its whole text on hover when cut (the status line, the inspector's warning:
     * November 9, 2026; before, what did not fit was lost, "Choose wh").
     */
    void drawCut(GuiGraphics graphics, String text, int x, int y, int width, int color) {
        String shown = font.plainSubstrByWidth(text, width);
        if (shown.length() < text.length()) {
            shown = font.plainSubstrByWidth(text, width - font.width("…")) + "…";
            cuts.add(new Cut(x, y, width, text));
        }
        graphics.drawString(font, shown, x, y, color, false);
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

    /** A click while the context menu is open: runs the line under it, and the menu closes either way. */
    private boolean clickMenu(double mouseX, double mouseY) {
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

    /** Opens the story's search or its problems over the map (the top bar's 🔍 and ⚠, Ctrl+F). */
    void openFinder(StoryFinder.Mode mode) {
        STORY.saveEdits(this);
        finder.open(mode);
        rebuild();
    }

    private void renderMenu(GuiGraphics graphics, int mouseX, int mouseY) {
        int left = menuLeft();
        int top = menuTop();
        int height = menu.size() * MENU_ITEM_H + 6;
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 300);
        graphics.fill(left - 1, top - 1, left + MENU_W + 1, top + height + 1, DevUi.CARD_EDGE);
        graphics.fill(left, top, left + MENU_W, top + height, DevUi.BAR);
        // Words in one column: a line without an icon is indented as if it had one, when others do.
        boolean icons = menu.stream().anyMatch(item -> DevIcons.split(item.label().getString()).icon() != null);
        for (int i = 0; i < menu.size(); i++) {
            int y = top + 3 + i * MENU_ITEM_H;
            boolean hover = DevUi.inside(mouseX, mouseY, left, y, MENU_W, MENU_ITEM_H);
            if (hover) {
                graphics.fill(left + 2, y, left + MENU_W - 2, y + MENU_ITEM_H, DevUi.ROW_SELECTED);
            }
            MenuItem item = menu.get(i);
            int color = item.danger() ? (hover ? DevUi.BAD : DevUi.ERROR) : (hover ? DevUi.WARN : DevUi.TEXT);
            int textX = left + 6;
            if (item.swatch() != 0) {
                graphics.fill(textX, y + 3, textX + 10, y + 13, DevUi.BACKGROUND);
                graphics.fill(textX + 1, y + 4, textX + 9, y + 12, item.swatch());
                textX += 14;
            }
            // A line's glyph is its icon (November 17, 2026): "🗑 Delete" shows the bin, painted as the line's words.
            DevIcons.Split split = DevIcons.split(item.label().getString());
            if (split.icon() != null) {
                DevIcons.draw(graphics, split.icon(), textX, y + (MENU_ITEM_H - DevIcons.SIZE) / 2, item.danger() ? color : hover ? DevUi.ACCENT : DevUi.LABEL);
                textX += DevIcons.SIZE + DevIcons.GAP;
            } else if (icons && item.swatch() == 0) {
                textX += DevIcons.SIZE + DevIcons.GAP;
            }
            graphics.drawString(font, font.plainSubstrByWidth(split.text(), left + MENU_W - 4 - textX), textX, y + 4, color, false);
        }
        graphics.pose().popPose();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (textEditor.isOpen()) {
            super.mouseClicked(mouseX, mouseY, button);
            return true;
        }
        if (catalog.isOpen()) {
            return super.mouseClicked(mouseX, mouseY, button) || catalog.mouseClicked(this, mouseX, mouseY);
        }
        if (settings.isOpen()) {
            super.mouseClicked(mouseX, mouseY, button);
            return true;
        }
        if (finder.isOpen()) {
            if (!menu.isEmpty()) {
                // A row's menu over the window: its line is picked as any menu's is.
                return clickMenu(mouseX, mouseY);
            }
            return super.mouseClicked(mouseX, mouseY, button) || finder.mouseClicked(this, mouseX, mouseY, button);
        }
        if (languageOpen) {
            if (!super.mouseClicked(mouseX, mouseY, button)
                    && !DevUi.inside(mouseX, mouseY, languageLeft(), languageTop(), LANGUAGE_W, LANGUAGE_H)) {
                closeLanguage();
            }
            return true;
        }
        if (!menu.isEmpty()) {
            return clickMenu(mouseX, mouseY);
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && view().onCanvas(this, mouseX, mouseY) && view().clickHome(mouseX, mouseY)) {
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
        if (super.mouseDragged(mouseX, mouseY, button, dragX, dragY) || catalog.isOpen() || languageOpen || textEditor.isOpen() || settings.isOpen() || finder.isOpen()) {
            return true;
        }
        return view().mouseDragged(this, mouseX, mouseY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (catalog.isOpen() || languageOpen || textEditor.isOpen() || settings.isOpen() || finder.isOpen()) {
            return super.mouseReleased(mouseX, mouseY, button);
        }
        if (view().mouseReleased(this, mouseX, mouseY)) {
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (textEditor.isOpen()) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        if (catalog.isOpen()) {
            catalog.scroll(scrollY);
            return true;
        }
        if (settings.isOpen()) {
            settings.scroll(scrollY);
            rebuild();
            return true;
        }
        if (finder.isOpen()) {
            finder.scroll(scrollY);
            return true;
        }
        GraphView view = view();
        if (!languageOpen && mouseY >= DevUi.TOP_BAR_H && view.scrollSidePanel(this, mouseX, mouseY, scrollY)) {
            return true;
        }
        if (!languageOpen && view.inspectorVisible(this) && mouseX >= width - DevUi.RIGHT_PANEL_W && mouseY >= DevUi.TOP_BAR_H) {
            // Where the form goes; it glides there frame by frame (see render).
            view.scrollInspector(scrollY);
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
        if (textEditor.isOpen()) {
            // Enter, Ctrl+Z and the rest belong to the text being written.
            return keyCode == GLFW.GLFW_KEY_ESCAPE ? textEditor.escape(this) : super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (catalog.isOpen()) {
            return catalog.keyPressed(this, keyCode) || super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (settings.isOpen()) {
            return keyCode == GLFW.GLFW_KEY_ESCAPE ? settings.escape(this) : super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (finder.isOpen()) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                if (!menu.isEmpty()) {
                    menu = List.of();
                    return true;
                }
                return finder.escape(this);
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (languageOpen) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                closeLanguage();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                if (!namespaceWindow) {
                    addLanguage();
                } else if (namespaceMoveFrom == null) {
                    saveNamespace();
                }
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
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            // One layer a press (November 19, 2026, the author; before, Escape left the editor from anywhere but a
            // window): a text box lets go of the keyboard (its words stay), then the view's selection and the view inside
            // the story, and only then the editor closes.
            if (textFocused()) {
                for (GuiEventListener child : children()) {
                    if (child instanceof EditBox box && box.isFocused()) {
                        box.setFocused(false);
                    }
                }
                setFocused(null);
                return true;
            }
            if (!view().escape(this)) {
                onClose();
            }
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_HOME) {
            // Back to the canvas's origin, the landmark (November 19, 2026).
            view().goHome();
            return true;
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
        return getFocused() instanceof EditBox || getFocused() instanceof MultiLineEditBox;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public void tick() {
        view().tick(this);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
