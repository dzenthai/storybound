package dev.dzenthai.storybound.client.dev;

import com.google.gson.JsonObject;
import dev.dzenthai.storybound.client.dev.ClientDevStore.NodeKey;
import dev.dzenthai.storybound.client.text.ClientConfigTexts;
import dev.dzenthai.storybound.core.dev.DevScripts;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
    private static final String NEWLINE = "¶";
    private static final int LINK_H = 12;
    private static final int MAX_LINKS = 4;

    /**
     * A link of the selected node to another, either way.
     *
     * @param missing   the other node does not exist (deleted, or a typo in hand-written JSON)
     * @param removable {@link StoryEdits#unlink} can take it out of the selected node's JSON
     */
    private record Link(NodeKey partner, boolean outgoing, String label, boolean missing, boolean removable) {}

    private final StoryView view;
    private final FormView form;
    private @Nullable DevModeScreen screen;
    /** The node the form shows; another one starts the form from its first page. */
    private @Nullable NodeKey shown;
    private @Nullable EditBox textBox;
    /** Whether the document's text was edited; only then is it sent, so a save never rewrites a text it did not show. */
    private boolean textEdited;
    private final List<Link> links = new ArrayList<>();
    private int linksTop;
    /** The script block's state line, drawn where its panel starts; null for other nodes. */
    private @Nullable ClientDevStore.ScriptState scriptState;
    private int scriptStateY;

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
        });
    }

    /** The document's text as edited, or empty when it was not touched. */
    Optional<String> documentText() {
        return textBox != null && textEdited ? Optional.of(textBox.getValue().replace(NEWLINE, "\n")) : Optional.empty();
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
        textBox = null;
        textEdited = false;
        JsonObject json = view.editing;
        NodeKey key = view.selected;
        if (json == null || key == null) {
            return;
        }
        if (!key.equals(shown)) {
            shown = key;
            form.reset(Schemas.definition(key.type()), key.id().getPath(), hidden(key.type()));
        }
        int x = screen.width - DevUi.RIGHT_PANEL_W + 8;
        int w = DevUi.RIGHT_PANEL_W - 16;
        int y = DevUi.TOP_BAR_H + 30;
        y = quickTests(screen, key, x, y, w);
        if (key.type().equals(ClientDevStore.TYPE_DOCUMENT)) {
            y = documentText(screen, key, x, y + 10, w);
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

    /** The document's long text: not part of the definition's JSON, so outside the form. */
    private int documentText(DevModeScreen screen, NodeKey key, int x, int y, int w) {
        textBox = new EditBox(screen.font(), x, y, w, 16, DevUi.text("document.text"));
        textBox.setMaxLength(65536);
        Optional<String> current = StoryView.documentText(key.id());
        textBox.setValue(current.orElse("").replace("\r", "").replace("\n", NEWLINE));
        if (current.isEmpty()) {
            textBox.setHint(DevUi.text("document.text_hint"));
        } else if (!ClientDevStore.activeLanguage().equalsIgnoreCase(ClientDevStore.sourceLanguage())) {
            textBox.setHint(Component.literal(ClientConfigTexts.document(key.id(), ClientDevStore.sourceLanguage()).orElse("").replace("\n", NEWLINE)));
        }
        textBox.setResponder(value -> {
            textEdited = true;
            view.dirty = true;
        });
        screen.add(textBox);
        return y + 22;
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
        y += 12;
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
        for (ClientDevStore.ScriptFile file : ClientDevStore.scripts().files().values()) {
            int handlers = file.actions().size() + file.conditions().size();
            entries.add(new Catalog.Entry(file.path(), handlers > 0 ? DevUi.str("script.file_handlers", file.path(), handlers) : file.path(),
                    new ItemStack(Items.PAPER)));
        }
        screen.catalog().openList(DevUi.text("script.pick_file_title"), entries, (kind, entry) -> {
            json.addProperty("file", entry.id());
            ClientDevStore.ScriptFile file = ClientDevStore.scripts().files().get(entry.id());
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
    }

    // --- links

    /** The node's links, listed above the save button; a link that can be taken out gets a ✕. */
    private void addLinks(DevModeScreen screen, NodeKey key, JsonObject json, int x, int w) {
        links.clear();
        Set<String> listed = new HashSet<>();
        for (ClientDevStore.Edge edge : ClientDevStore.collectEdges()) {
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
        Component header = view.selection.size() > 1 ? DevUi.text("inspector.header_many", view.selection.size()) : DevUi.text("inspector.header");
        graphics.drawString(font, header, left + 8, DevUi.TOP_BAR_H + 6, DevUi.ACCENT, false);
        List<String> issues = ClientDevStore.issuesFor(key.type(), key.id());
        // The id line turns into the first issue, in red, when the validator has something to say about this node.
        String second = issues.isEmpty() ? key.id().toString() : "⚠ " + issues.getFirst();
        graphics.drawString(font, font.plainSubstrByWidth(second, DevUi.RIGHT_PANEL_W - 16), left + 8, DevUi.TOP_BAR_H + 18,
                issues.isEmpty() ? DevUi.MUTED : DevUi.BAD, false);
        if (textBox != null) {
            graphics.drawString(font, DevUi.text("document.text"), left + 8, textBox.getY() - 10, DevUi.LABEL, false);
        }
        renderScriptState(font, graphics, left);
        form.render(graphics, font);
        renderLinks(font, graphics, left);
    }
}
