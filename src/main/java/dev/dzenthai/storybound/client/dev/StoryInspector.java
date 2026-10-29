package dev.dzenthai.storybound.client.dev;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.dzenthai.storybound.client.document.DocumentTexts;
import dev.dzenthai.storybound.core.dev.DevScripts;
import dev.dzenthai.storybound.core.story.StoryLinks.NodeKey;
import dev.dzenthai.storybound.core.story.StoryLinks;
import dev.dzenthai.storybound.core.story.StoryRenames;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

/**
 * The story canvas's right panel: quick tests of the selected definition, its fields as a form built from its schema
 * ({@link FormView}), a document's text, and the node's links. Buttons save at once; text is saved on Enter, when
 * another card is selected or the screen closes.
 */
final class StoryInspector {
    /** Newlines of a document's text, shown in the one-line box as this sign and turned back on saving. */
    private static final int LINK_H = 12;
    private static final int MAX_LINKS = 4;

    /**
     * A link of the selected node to another, either way.
     *
     * @param missing   the other node does not exist (deleted, or a typo in hand-written JSON)
     * @param removable {@link StoryEdits#unlink} can take it out of the selected node's JSON
     */
    private record Link(NodeKey partner, boolean outgoing, String label, boolean missing, boolean removable) {}

    /** A row of the quest giver's block: its phase, where its label is drawn. */
    private record GiverRow(QuestGiver.Phase phase, int y) {}

    private final StoryView view;
    private final FormView form;
    private @Nullable DevModeScreen screen;
    /** The node the form shows; another one starts the form from its first page. */
    private @Nullable NodeKey shown;
    /**
     * The document's text as edited in the text window, for {@link #pendingFor}; null while untouched, so a save never
     * rewrites a text it did not show. Kept here, not in a widget, so rebuilding the panel loses nothing.
     */
    private @Nullable String pendingText;
    private @Nullable NodeKey pendingFor;
    /** The label above the long texts' buttons and where it is drawn; null without long texts. */
    private @Nullable Component textLabel;
    private int textLabelY;
    private final List<Link> links = new ArrayList<>();
    private int linksTop;
    /** The script block's state line, drawn where its panel starts; null for other nodes. */
    private @Nullable ClientDevStore.ScriptState scriptState;
    private int scriptStateY;
    private final List<GiverRow> giverRows = new ArrayList<>();
    private @Nullable String giverTitle;
    /** The name being typed for {@link #renameFor}, kept here so rebuilding the panel loses nothing. */
    private @Nullable String renameText;
    private @Nullable NodeKey renameFor;
    private int giverY;

    StoryInspector(StoryView view) {
        this.view = view;
        this.form = new FormView(new FormView.Host() {
            @Override
            public JsonObject root() {
                return view.editing != null ? view.editing : new JsonObject();
            }

            @Override
            public String textKey(String path, String field) {
                NodeKey key = view.selected;
                return key != null ? ClientDevStore.canonicalKey(key.type(), key.id(), path + field) : ClientDevStore.generateUniqueKey(field);
            }

            @Override
            public void edited() {
                view.dirty = true;
            }

            @Override
            public void changed() {
                if (screen != null) {
                    view.changed(screen);
                }
            }

            @Override
            public @Nullable ResourceLocation quest() {
                NodeKey key = view.selected;
                return key != null && key.type().equals(ClientDevStore.TYPE_QUEST) ? key.id() : null;
            }

            @Override
            public void renameInQuest(String stage, String objective, String to) {
                NodeKey key = view.selected;
                if (screen != null && key != null) {
                    view.renameInQuest(screen, key, stage, objective, to);
                }
            }

            @Override
            public @Nullable ResourceLocation npcBinding() {
                NodeKey key = view.selected;
                return key != null && key.type().equals(ClientDevStore.TYPE_NPC) ? key.id() : null;
            }
        });
    }

    /** The document's text as edited, or empty when it was not touched. */
    Optional<String> documentText() {
        return pendingText != null && pendingFor != null && pendingFor.equals(view.selected) ? Optional.of(pendingText) : Optional.empty();
    }

    /** The edited text went with the definition. */
    void textSaved() {
        pendingText = null;
    }

    void scroll(double amount) {
        form.scroll(amount);
    }

    /** Fields edited elsewhere (the canvas, a dialogue's graph) or kept out of the form. */
    private static Set<String> hidden(ResourceLocation type) {
        if (type.equals(ClientDevStore.TYPE_DIALOGUE)) {
            return Set.of("nodes", "start");
        }
        return type.equals(ClientDevStore.TYPE_CHAPTER) ? Set.of("nodes") : Set.of();
    }

    void addWidgets(DevModeScreen screen) {
        this.screen = screen;
        JsonObject json = view.editing;
        NodeKey key = view.selected;
        if (json == null || key == null) {
            return;
        }
        if (!key.equals(pendingFor)) {
            pendingFor = key;
            pendingText = null;
        }
        textLabel = null;
        if (!key.equals(shown)) {
            shown = key;
            form.reset(Schemas.definition(key.type()), key.id().getPath(), hidden(key.type()));
        }
        int x = screen.width - DevUi.RIGHT_PANEL_W + 8;
        int w = DevUi.RIGHT_PANEL_W - 16;
        int y = DevUi.TOP_BAR_H + 30;
        if (renameable(key)) {
            nameRow(screen, key, x, DevUi.TOP_BAR_H + 16, w);
            y = DevUi.TOP_BAR_H + 46;
        }
        y = quickTests(screen, key, x, y, w);
        giverRows.clear();
        giverTitle = null;
        if (key.type().equals(ClientDevStore.TYPE_QUEST)) {
            y = giver(screen, key, x, y, w);
        }
        if (key.type().equals(ClientDevStore.TYPE_DOCUMENT)) {
            y = documentText(screen, key, json, x, y + 10, w);
        }
        if (key.type().equals(ClientDevStore.TYPE_CODEX) || key.type().equals(ClientDevStore.TYPE_BOSS)) {
            y = sectionTexts(screen, key, json, x, y + 6, w);
        }
        scriptState = null;
        if (key.type().equals(ClientDevStore.TYPE_SCRIPT)) {
            y = script(screen, key, json, x, y, w);
        }
        addLinks(screen, key, json, x, w);
        int formBottom = links.isEmpty() ? screen.height - 34 : linksTop - 16;
        form.addWidgets(screen, x, y, w, formBottom);
        screen.add(Button.builder(Component.literal("💾 ").append(DevUi.text("inspector.save")), b -> view.save(screen))
                .bounds(x, screen.height - 26, w - 54, 20).build());
        screen.add(Button.builder(Component.literal("🗑"), b -> view.deleteSelected(screen))
                .tooltip(Tooltip.create(DevUi.text("inspector.delete"))).bounds(x + w - 50, screen.height - 26, 50, 20).build());
    }

    private static boolean renameable(NodeKey key) {
        return StoryRenames.RENAMEABLE.contains(key.type());
    }

    /**
     * The node's name, at the very top: other nodes, texts and players' progress name it by it. Only the name: the
     * namespace is the story's, set in the top bar. A new name is checked as it is typed (one word, not taken by the
     * same kind), and ✓ renames the node with everything that names it.
     */
    private void nameRow(DevModeScreen screen, NodeKey key, int x, int y, int w) {
        if (!key.equals(renameFor)) {
            renameFor = key;
            renameText = key.id().getPath();
        }
        EditBox box = new EditBox(screen.font(), x, y + 1, w - 22, 14, DevUi.text("rename.label"));
        box.setMaxLength(256);
        box.setValue(renameText != null ? renameText : key.id().getPath());
        Button apply = Button.builder(Component.literal("✓"), b -> {
            Optional<ResourceLocation> to = StoryRenames.parse(renameText != null ? renameText : "", key.id());
            if (to.isPresent() && renameProblem(key).isEmpty()) {
                view.rename(screen, key, to.get());
            }
        }).tooltip(Tooltip.create(DevUi.text("rename.apply"))).bounds(x + w - 20, y, 20, 16).build();
        box.setResponder(text -> {
            renameText = text;
            Optional<StoryRenames.Problem> problem = renameProblem(key);
            box.setTextColor(problem.isEmpty() || problem.get() == StoryRenames.Problem.SAME ? 0xFFE0E0E0 : DevUi.ERROR);
            apply.active = problem.isEmpty();
        });
        box.setTooltip(Tooltip.create(DevUi.text("rename.tooltip", key.id().getNamespace())));
        Optional<StoryRenames.Problem> problem = renameProblem(key);
        box.setTextColor(problem.isEmpty() || problem.get() == StoryRenames.Problem.SAME ? 0xFFE0E0E0 : DevUi.ERROR);
        apply.active = problem.isEmpty();
        screen.add(box);
        screen.add(apply);
    }

    private Optional<StoryRenames.Problem> renameProblem(NodeKey key) {
        return StoryRenames.problem(key.type(), key.id(), renameText != null ? renameText : key.id().getPath(),
                (type, id) -> ClientDevStore.getJson(type, id) != null);
    }

    /** Buttons that try the definition in game at once (they save it first). */
    private int quickTests(DevModeScreen screen, NodeKey key, int x, int y, int w) {
        List<Button> buttons = new ArrayList<>();
        ResourceLocation type = key.type();
        if (type.equals(ClientDevStore.TYPE_QUEST)) {
            buttons.add(test(screen, "▶ ", "quest.start", "quest_start", key));
            buttons.add(test(screen, "✓ ", "quest.complete", "quest_complete", key));
            buttons.add(test(screen, "↺ ", "quest.reset", "quest_reset", key));
        } else if (type.equals(ClientDevStore.TYPE_DIALOGUE)) {
            buttons.add(test(screen, "▶ ", "dialogue.start_now", "dialogue_start", key));
            buttons.add(Button.builder(Component.literal("⤢ ").append(DevUi.text("dialogue.open_graph")), b -> screen.openDialogue(key.id())).build());
        } else if (type.equals(ClientDevStore.TYPE_DOCUMENT)) {
            buttons.add(test(screen, "🎒 ", "document.give", "document_give", key));
        } else if (type.equals(ClientDevStore.TYPE_WAYPOINT)) {
            buttons.add(test(screen, "⚡ ", "waypoint.teleport", "waypoint_tp", key));
        }
        if (buttons.isEmpty()) {
            return y;
        }
        int each = (w - (buttons.size() - 1) * 2) / buttons.size();
        for (int i = 0; i < buttons.size(); i++) {
            Button button = buttons.get(i);
            button.setRectangle(each, 18, x + i * (each + 2), y);
            screen.add(button);
        }
        return y + 24;
    }

    private static Button test(DevModeScreen screen, String icon, String label, String action, NodeKey key) {
        return Button.builder(Component.literal(icon).append(DevUi.text(label)), b -> screen.playtest(action, key.id()))
                .tooltip(Tooltip.create(DevUi.text(label))).build();
    }

    /**
     * The document's long text: not part of the definition's JSON, so outside the form. A button with its first line
     * opens the text window; the text goes with the definition when it is saved, as one undoable step.
     */
    private int documentText(DevModeScreen screen, NodeKey key, JsonObject json, int x, int y, int w) {
        ResourceLocation textId = documentTextId(key.id(), json);
        textLabel = DevUi.text("document.text");
        textLabelY = y - 10;
        String current = pendingText != null ? pendingText : text(textId, ClientDevStore.activeLanguage()).orElse("");
        screen.add(textButton(screen, current, DevUi.text("document.text_hint"), () -> screen.textEditor().open(
                DevUi.text("text.title_document", key.id().toString()), current, hint(textId),
                text -> {
                    pendingFor = key;
                    pendingText = text;
                    view.dirty = true;
                    view.save(screen);
                },
                text -> screen.preview(key.id(), text)))
                .tooltip(Tooltip.create(DevUi.text("text.edit_tooltip", textId.toString()))).bounds(x, y, w, 18).build());
        return y + 24;
    }

    /** A Codex entry's sections or a boss's lore and tactics: a row each, saved on their own. */
    private int sectionTexts(DevModeScreen screen, NodeKey key, JsonObject json, int x, int y, int w) {
        Map<String, ResourceLocation> texts = new LinkedHashMap<>();
        textLabel = DevUi.text("text.sections");
        textLabelY = y;
        y += 10;
        if (key.type().equals(ClientDevStore.TYPE_CODEX)) {
            if (json.get("sections") instanceof JsonArray sections) {
                for (JsonElement element : sections) {
                    if (element instanceof JsonObject section && section.get("id") instanceof JsonPrimitive id) {
                        texts.put(id.getAsString(), ownText(section).orElse(ResourceLocation.fromNamespaceAndPath(key.id().getNamespace(),
                                "codex/" + key.id().getPath() + "/" + id.getAsString())));
                    }
                }
            }
        } else {
            for (String section : List.of("lore", "tactics")) {
                texts.put(section, (json.get(section) instanceof JsonObject object ? ownText(object) : Optional.<ResourceLocation>empty())
                        .orElse(ResourceLocation.fromNamespaceAndPath(key.id().getNamespace(), "bosses/" + key.id().getPath() + "/" + section)));
            }
        }
        for (Map.Entry<String, ResourceLocation> entry : texts.entrySet()) {
            ResourceLocation textId = entry.getValue();
            String current = text(textId, ClientDevStore.activeLanguage()).orElse("");
            String first = firstLine(current);
            Component label = Component.literal(entry.getKey() + ": ").append(first.isEmpty() ? DevUi.text("text.empty") : Component.literal(first));
            screen.add(textButton(screen, label, () -> screen.textEditor().open(
                    DevUi.text("text.title_section", key.id().toString(), entry.getKey()), current, hint(textId),
                    text -> DevRequests.saveText(textId, text), null))
                    .tooltip(Tooltip.create(DevUi.text("text.edit_tooltip", textId.toString()))).bounds(x, y, w, 18).build());
            y += 20;
        }
        return y + 4;
    }

    /** A button "✎ <the text's first line>", or the hint while there is none. */
    private static Button.Builder textButton(DevModeScreen screen, String text, Component empty, Runnable open) {
        String first = firstLine(text);
        return textButton(screen, first.isEmpty() ? empty : Component.literal(first), open);
    }

    private static Button.Builder textButton(DevModeScreen screen, Component label, Runnable open) {
        String shown = screen.font().plainSubstrByWidth("✎ " + label.getString(), DevUi.RIGHT_PANEL_W - 30);
        return Button.builder(Component.literal(shown), b -> open.run());
    }

    private static String firstLine(String text) {
        return text.lines().filter(line -> !line.isBlank()).findFirst().orElse("");
    }

    /** What the window shows while the text is empty: the source language's text when another language is edited. */
    private static Component hint(ResourceLocation textId) {
        String source = ClientDevStore.sourceLanguage();
        if (!ClientDevStore.activeLanguage().equalsIgnoreCase(source)) {
            Optional<String> original = text(textId, source);
            if (original.isPresent()) {
                return Component.literal(original.get());
            }
        }
        return DevUi.text("text.hint");
    }

    /** A text as the story folder has it, else a resource pack, in exactly that language. */
    private static Optional<String> text(ResourceLocation textId, String language) {
        return DocumentTexts.load(textId, language);
    }

    private static Optional<ResourceLocation> ownText(JsonObject object) {
        return object.get("text") instanceof JsonPrimitive text ? Optional.ofNullable(ResourceLocation.tryParse(text.getAsString())) : Optional.empty();
    }

    /** A document's text file: its {@code content}, else the document's own id. */
    static ResourceLocation documentTextId(ResourceLocation document, JsonObject json) {
        ResourceLocation content = json.get("content") instanceof JsonPrimitive text ? ResourceLocation.tryParse(text.getAsString()) : null;
        return content != null ? content : document;
    }

    // --- the quest giver

    /**
     * What the NPC giving the quest says before, during and after it (see {@link QuestGiver}): a row per phase, its
     * dialogue (opens the graph) or "+ New". Shown only when an NPC's dialogue gives the quest.
     */
    private int giver(DevModeScreen screen, NodeKey quest, int x, int y, int w) {
        QuestGiver.Giver giver = QuestGiver.find(quest.id());
        if (giver == null) {
            return y;
        }
        giverTitle = DevUi.str("giver.title", StoryView.title(giver.npc()));
        giverY = y;
        y += 12;
        int labelW = 64;
        for (QuestGiver.Phase phase : QuestGiver.Phase.values()) {
            ResourceLocation dialogue = giver.dialogues().get(phase);
            Button button = dialogue != null
                    ? Button.builder(Component.literal("💬 " + StoryView.title(new NodeKey(ClientDevStore.TYPE_DIALOGUE, dialogue))),
                            b -> screen.openDialogue(dialogue)).tooltip(Tooltip.create(DevUi.text("giver.open", dialogue.toString()))).build()
                    : Button.builder(DevUi.text("giver.create"), b -> view.addGiverDialogue(screen, quest.id(), giver, phase))
                            .tooltip(Tooltip.create(DevUi.text("giver.create_tooltip." + phase.name().toLowerCase(Locale.ROOT)))).build();
            button.setRectangle(w - labelW, 18, x + labelW, y);
            screen.add(button);
            giverRows.add(new GiverRow(phase, y));
            y += 20;
        }
        return y + 4;
    }

    private void renderGiver(Font font, GuiGraphics graphics, int left) {
        if (giverTitle == null) {
            return;
        }
        graphics.drawString(font, font.plainSubstrByWidth(giverTitle, DevUi.RIGHT_PANEL_W - 16), left + 8, giverY + 1, DevUi.ACCENT, false);
        for (GiverRow row : giverRows) {
            graphics.drawString(font, DevUi.text("giver." + row.phase().name().toLowerCase(Locale.ROOT)), left + 8, row.y() + 5, DevUi.LABEL, false);
        }
    }

    // --- script blocks

    /**
     * The script block's file and handler: where they stand on the server, picking the file (then the handler in it)
     * from the server's scripts, opening it, and making a new one from a template.
     */
    private int script(DevModeScreen screen, NodeKey key, JsonObject json, int x, int y, int w) {
        ClientDevStore.ScriptState state = ClientDevStore.scriptState(key.id());
        scriptState = state;
        scriptStateY = y;
        y += ClientDevStore.isDerivedScript(key.id()) ? 24 : 12;
        int third = (w - 4) / 3;
        screen.add(Button.builder(Component.literal("📂 ").append(DevUi.text("script.pick_file")), b -> pickScriptFile(screen, json))
                .tooltip(Tooltip.create(DevUi.text("script.pick_file_tooltip"))).bounds(x, y, third, 18).build());
        Button open = Button.builder(Component.literal("↗ ").append(DevUi.text("script.open")), b -> openScript(screen, key))
                .tooltip(Tooltip.create(DevUi.text("script.open_tooltip"))).bounds(x + third + 2, y, third, 18).build();
        open.active = ClientDevStore.scriptFile(key.id()) != null;
        screen.add(open);
        screen.add(Button.builder(Component.literal("↻ ").append(DevUi.text("script.refresh")), b -> DevRequests.requestScripts())
                .tooltip(Tooltip.create(DevUi.text("script.refresh_tooltip"))).bounds(x + (third + 2) * 2, y, third, 18).build());
        y += 20;
        if (state == ClientDevStore.ScriptState.NO_FILE || state == ClientDevStore.ScriptState.FILE_MISSING) {
            int half = (w - 2) / 2;
            screen.add(Button.builder(DevUi.text("script.create_action"), b -> createScript(screen, key, json, false))
                    .tooltip(Tooltip.create(DevUi.text("script.create_tooltip", DevScripts.fileFor(ClientDevStore.scriptHook(key.id()))))).bounds(x, y, half, 18).build());
            screen.add(Button.builder(DevUi.text("script.create_condition"), b -> createScript(screen, key, json, true))
                    .tooltip(Tooltip.create(DevUi.text("script.create_tooltip", DevScripts.fileFor(ClientDevStore.scriptHook(key.id()))))).bounds(x + half + 2, y, half, 18).build());
            y += 20;
        }
        return y + 4;
    }

    private void pickScriptFile(DevModeScreen screen, JsonObject json) {
        List<Catalog.Entry> entries = new ArrayList<>();
        for (StoryLinks.ScriptFile file : ClientDevStore.scripts().files().values()) {
            int handlers = file.actions().size() + file.conditions().size();
            entries.add(new Catalog.Entry(file.path(), handlers > 0 ? DevUi.str("script.file_handlers", file.path(), handlers) : file.path(),
                    new ItemStack(Items.PAPER)));
        }
        screen.catalog().openList(DevUi.text("script.pick_file_title"), entries, (kind, entry) -> {
            json.addProperty("file", entry.id());
            StoryLinks.ScriptFile file = ClientDevStore.scripts().files().get(entry.id());
            List<String> handlers = new ArrayList<>();
            if (file != null) {
                handlers.addAll(file.actions());
                handlers.addAll(file.conditions());
            }
            if (handlers.size() == 1) {
                setHook(json, handlers.getFirst());
            } else if (handlers.size() > 1) {
                List<Catalog.Entry> choices = new ArrayList<>();
                file.actions().forEach(hook -> choices.add(new Catalog.Entry(hook, DevUi.str("script.handler_action", hook), new ItemStack(Items.REDSTONE_TORCH))));
                file.conditions().forEach(hook -> choices.add(new Catalog.Entry(hook, DevUi.str("script.handler_condition", hook), new ItemStack(Items.COMPARATOR))));
                screen.catalog().openList(DevUi.text("script.pick_handler_title"), choices, (k, handler) -> {
                    setHook(json, handler.id());
                    view.changed(screen);
                });
            }
            view.changed(screen);
        });
        screen.rebuild();
    }

    /** The handler; the block's own id needs no {@code hook}. */
    private void setHook(JsonObject json, String hook) {
        NodeKey key = view.selected;
        if (key != null && key.id().toString().equals(hook)) {
            json.remove("hook");
        } else {
            json.addProperty("hook", hook);
        }
    }

    private void createScript(DevModeScreen screen, NodeKey key, JsonObject json, boolean condition) {
        ResourceLocation hook = ClientDevStore.scriptHook(key.id());
        json.addProperty("file", DevScripts.fileFor(hook));
        view.changed(screen);
        DevRequests.createScript(hook, condition);
        screen.status(DevUi.text("status.script_creating", DevScripts.fileFor(hook)), DevUi.OK);
    }

    /** In singleplayer the server's files are on this computer: they open in the system's editor for .js files. */
    private static void openScript(DevModeScreen screen, NodeKey key) {
        String file = ClientDevStore.scriptFile(key.id());
        if (file == null) {
            return;
        }
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) {
            screen.status(DevUi.text("status.script_on_server", ClientDevStore.scripts().folder() + "/" + file), DevUi.WARN);
            return;
        }
        Path path = DevScripts.folder(server).resolve(file).normalize();
        if (!path.startsWith(DevScripts.folder(server)) || !Files.isRegularFile(path)) {
            screen.status(DevUi.text("status.script_missing", file), DevUi.BAD);
            return;
        }
        Util.getPlatform().openFile(path.toFile());
    }

    private void renderScriptState(Font font, GuiGraphics graphics, int left) {
        if (scriptState == null) {
            return;
        }
        NodeKey key = view.selected;
        String hook = key != null ? ClientDevStore.scriptHook(key.id()).toString() : "";
        int color = switch (scriptState) {
            case READY -> DevUi.GOOD;
            case FILE_MISSING, NOT_REGISTERED -> DevUi.ERROR;
            default -> DevUi.WARN;
        };
        String line = DevUi.str("script.state." + scriptState.name().toLowerCase(Locale.ROOT), hook);
        graphics.drawString(font, font.plainSubstrByWidth(line, DevUi.RIGHT_PANEL_W - 16), left + 8, scriptStateY + 1, color, false);
        if (key != null && ClientDevStore.isDerivedScript(key.id())) {
            // No block file: the block comes from the script itself.
            graphics.drawString(font, font.plainSubstrByWidth(DevUi.str("script.derived"), DevUi.RIGHT_PANEL_W - 16), left + 8, scriptStateY + 13, DevUi.LABEL, false);
        }
    }

    // --- links

    /** The node's links, listed above the save button; a link that can be taken out gets a ✕. */
    private void addLinks(DevModeScreen screen, NodeKey key, JsonObject json, int x, int w) {
        links.clear();
        Set<String> listed = new HashSet<>();
        for (StoryLinks.Edge edge : ClientDevStore.collectEdges()) {
            boolean outgoing = edge.from().equals(key);
            if (!outgoing && !edge.to().equals(key)) {
                continue;
            }
            NodeKey partner = outgoing ? edge.to() : edge.from();
            if (listed.add(outgoing + " " + partner)) {
                boolean missing = ClientDevStore.getJson(partner.type(), partner.id()) == null;
                links.add(new Link(partner, outgoing, StoryView.edgeLabel(edge.label()), missing, StoryEdits.unlink(json.deepCopy(), partner)));
            }
        }
        // Broken links first: they are the ones to fix.
        links.sort(Comparator.comparing(link -> !link.missing()));
        int rows = Math.min(links.size(), MAX_LINKS);
        linksTop = screen.height - 34 - rows * LINK_H - (links.size() > rows ? LINK_H : 0);
        for (int i = 0; i < rows; i++) {
            Link link = links.get(i);
            if (link.removable()) {
                screen.add(Button.builder(Component.literal("✕"), b -> view.unlinkSelected(screen, link.partner()))
                        .tooltip(Tooltip.create(DevUi.text("links.remove"))).bounds(x + w - 12, linksTop + i * LINK_H, 12, 11).build());
            }
        }
    }

    private void renderLinks(Font font, GuiGraphics graphics, int left) {
        if (links.isEmpty()) {
            return;
        }
        int width = DevUi.RIGHT_PANEL_W - 30;
        graphics.fill(left + 6, linksTop - 14, left + DevUi.RIGHT_PANEL_W - 6, linksTop - 13, DevUi.PANEL_EDGE);
        graphics.drawString(font, DevUi.text("links.title", links.size()), left + 8, linksTop - 11, DevUi.ACCENT, false);
        int rows = Math.min(links.size(), MAX_LINKS);
        for (int i = 0; i < rows; i++) {
            Link link = links.get(i);
            String line = (link.outgoing() ? "→ " : "← ") + link.partner().id().getPath() + " (" + link.label() + ")"
                    + (link.missing() ? " — " + DevUi.str("links.missing") : "");
            graphics.drawString(font, font.plainSubstrByWidth(line, width), left + 8, linksTop + i * LINK_H + 2, link.missing() ? DevUi.ERROR : DevUi.LABEL, false);
        }
        if (links.size() > rows) {
            graphics.drawString(font, DevUi.text("links.more", links.size() - rows), left + 8, linksTop + rows * LINK_H + 2, DevUi.MUTED, false);
        }
    }

    // --- drawing

    void render(DevModeScreen screen, GuiGraphics graphics) {
        Font font = screen.font();
        NodeKey key = view.selected;
        int left = screen.width - DevUi.RIGHT_PANEL_W;
        DevUi.sidePanel(graphics, left, screen.width, screen.height, false);
        if (key == null || view.editing == null) {
            return;
        }
        // The label of the name below; before, the panel's own title "Inspector", which said nothing.
        Component header = view.selection.size() > 1 ? DevUi.text("inspector.header_many", view.selection.size()) : DevUi.text("rename.label");
        graphics.drawString(font, header, left + 8, DevUi.TOP_BAR_H + 6, DevUi.ACCENT, false);
        List<String> issues = ClientDevStore.issuesFor(key.type(), key.id());
        if (renameable(key)) {
            // Under the name: why the typed one cannot be, else the validator's first word on the node.
            Optional<StoryRenames.Problem> problem = key.equals(renameFor) ? renameProblem(key) : Optional.empty();
            String line = problem.isPresent() && problem.get() != StoryRenames.Problem.SAME
                    ? DevUi.str("rename.problem." + problem.get().name().toLowerCase(Locale.ROOT),
                    StoryRenames.parse(renameText != null ? renameText : "", key.id()).map(StoryRenames::relationshipScale).map(Object::toString).orElse(""))
                    : issues.isEmpty() ? "" : "⚠ " + issues.getFirst();
            graphics.drawString(font, font.plainSubstrByWidth(line, DevUi.RIGHT_PANEL_W - 16), left + 8, DevUi.TOP_BAR_H + 34, DevUi.BAD, false);
        } else {
            // The id line turns into the first issue, in red, when the validator has something to say about this node.
            String second = issues.isEmpty() ? key.id().toString() : "⚠ " + issues.getFirst();
            graphics.drawString(font, font.plainSubstrByWidth(second, DevUi.RIGHT_PANEL_W - 16), left + 8, DevUi.TOP_BAR_H + 18,
                    issues.isEmpty() ? DevUi.MUTED : DevUi.BAD, false);
        }
        if (textLabel != null) {
            graphics.drawString(font, textLabel, left + 8, textLabelY, DevUi.LABEL, false);
        }
        renderScriptState(font, graphics, left);
        renderGiver(font, graphics, left);
        form.render(graphics, font);
        renderLinks(font, graphics, left);
    }
}
