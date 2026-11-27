package dev.dzenthai.storybound.client.dev;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.dzenthai.storybound.client.dev.Schemas.Node;
import dev.dzenthai.storybound.core.story.StoryLinks.NodeKey;
import dev.dzenthai.storybound.core.story.StoryRenames;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * Dedicated Dev Mode workspace for world-level rules ("World Setup"): item locks, mob spawn locks,
 * dimension/biome/structure locks, and general loot modifiers. Split-pane layout with category tabs,
 * search and rule list on the left and a schema-driven {@link FormView} workbench on the right.
 */
final class WorldView extends GraphView {
    private static final int ROW_H = 28;
    private static final int EDITOR_MAX_W = 460;
    private static final Set<String> HIDDEN_FIELDS = Set.of("type", "enabled");

    enum Section {
        RESTRICTIONS,
        LOOT
    }

    enum Category {
        ITEM("item", "🔒", "item_lock", Section.RESTRICTIONS),
        MOB("mob", "🐉", "mob_lock", Section.RESTRICTIONS),
        PLACE("place", "📍", "place_lock", Section.RESTRICTIONS),
        LOOT("loot", "📦", "loot_rule", Section.LOOT);

        final String type;
        final String glyph;
        final String idPrefix;
        final Section section;

        Category(String type, String glyph, String idPrefix, Section section) {
            this.type = type;
            this.glyph = glyph;
            this.idPrefix = idPrefix;
            this.section = section;
        }

        static Category ofType(String raw) {
            for (Category c : values()) {
                if (c.type.equals(raw)) {
                    return c;
                }
            }
            return ITEM;
        }
    }

    private static final List<Category> RESTRICTION_CATEGORIES = List.of(Category.ITEM, Category.MOB, Category.PLACE);

    private record RuleRow(ResourceLocation id, Category category, String title, String subtitle, boolean enabled, boolean hasIssue) {}

    private boolean open;
    private Section activeSection = Section.RESTRICTIONS;
    private Category activeCategory = Category.ITEM;
    private Category lastRestrictionCategory = Category.ITEM;
    private String search = "";
    private int listScroll;
    private List<RuleRow> rows = List.of();
    private int[] counts = new int[Category.values().length];

    private @Nullable ResourceLocation selected;
    private @Nullable JsonObject editing;
    private boolean dirty;
    private @Nullable String lastSaved;
    private @Nullable ResourceLocation formFor;
    private @Nullable String formType;

    private @Nullable ResourceLocation renameFor;
    private String renameText = "";
    private @Nullable DevModeScreen currentScreen;
    private int sideWidth = 230;
    private int listTop = DevUi.TOP_BAR_H + 98;

    private final FormView form;

    WorldView() {
        this.form = new FormView(new FormView.Host() {
            @Override
            public JsonObject root() {
                return editing != null ? editing : new JsonObject();
            }

            @Override
            public String textKey(String path, String field) {
                return selected != null
                        ? ClientDevStore.canonicalKey(ClientDevStore.TYPE_WORLD_RULE, selected, path + field)
                        : ClientDevStore.generateUniqueKey(field);
            }

            @Override
            public void edited() {
                dirty = true;
            }

            @Override
            public void changed() {
                if (currentScreen != null) {
                    applyChange(currentScreen);
                }
            }

            @Override
            public @Nullable NodeKey node() {
                return selected != null ? new NodeKey(ClientDevStore.TYPE_WORLD_RULE, selected) : null;
            }
        });
    }

    boolean isOpen() {
        return open;
    }

    void openSection(Section section) {
        open = true;
        activeSection = section;
        activeCategory = section == Section.LOOT ? Category.LOOT : lastRestrictionCategory;
        listScroll = 0;
        selected = null;
        editing = null;
        dirty = false;
        lastSaved = null;
        formFor = null;
        renameFor = null;
        reload();
    }

    void open(@Nullable ResourceLocation targetId) {
        open = true;
        if (targetId != null) {
            String raw = ClientDevStore.getJson(ClientDevStore.TYPE_WORLD_RULE, targetId);
            if (raw != null) {
                JsonObject obj = ClientDevStore.parseObject(raw);
                String type = obj.get("type") instanceof JsonPrimitive p ? p.getAsString() : "item";
                Category cat = Category.ofType(type);
                activeCategory = cat;
                activeSection = cat.section;
                if (cat.section == Section.RESTRICTIONS) {
                    lastRestrictionCategory = cat;
                }
                selected = targetId;
                editing = obj;
                dirty = false;
                lastSaved = null;
                formFor = null;
                renameFor = null;
            }
        }
        reload();
    }

    void close(DevModeScreen screen) {
        saveEdits(screen);
        open = false;
    }

    // --- GraphView contract

    @Override
    Component backLabel() {
        return DevUi.text("back.story");
    }

    @Override
    void back(DevModeScreen screen) {
        screen.closeWorld();
    }

    @Override
    Component title() {
        return DevUi.text(activeSection == Section.LOOT ? "world.loot.title" : "world.restrictions.title");
    }

    @Override
    int untranslated() {
        return ClientDevStore.missingTranslationCount(ClientDevStore.activeLanguage());
    }

    @Override
    boolean showsIssues() {
        return false;
    }

    @Override
    boolean inspectorVisible(DevModeScreen screen) {
        return false;
    }

    @Override
    boolean animateInspector() {
        return open && selected != null && form.animateScroll();
    }

    @Override
    List<DevModeScreen.TopButton> topRight(DevModeScreen screen) {
        int problems = ClientDevStore.issues().size();
        boolean errors = ClientDevStore.issues().stream().anyMatch(line -> line.startsWith("[ERROR]"));
        Component label = problems == 0 ? Component.literal("✔") : Component.literal("⚠ " + problems)
                .withColor(errors ? DevUi.ERROR : DevUi.WARN);
        DevModeScreen.TopButton otherSection = activeSection == Section.RESTRICTIONS
                ? new DevModeScreen.TopButton(Component.literal("📦"), DevUi.text("world.loot.tab.tooltip"),
                        () -> switchSection(screen, Section.LOOT))
                : new DevModeScreen.TopButton(Component.literal("🔒"), DevUi.text("world.restrictions.tab.tooltip"),
                        () -> switchSection(screen, Section.RESTRICTIONS));
        return List.of(
                otherSection,
                new DevModeScreen.TopButton(label, DevUi.text(problems == 0 ? "top.problems_none.tooltip" : "top.problems.tooltip"),
                        () -> screen.openFinder(StoryFinder.Mode.PROBLEMS)));
    }

    @Override
    boolean escape(DevModeScreen screen) {
        if (form.popFrame()) {
            screen.rebuild();
            return true;
        }
        screen.closeWorld();
        return true;
    }

    @Override
    void beforeHistoryReload() {
        dirty = false;
        lastSaved = null;
        formFor = null;
    }

    @Override
    void reload() {
        Map<ResourceLocation, String> all = ClientDevStore.byType(ClientDevStore.TYPE_WORLD_RULE);
        counts = new int[Category.values().length];
        List<RuleRow> filtered = new ArrayList<>();
        String needle = search.trim().toLowerCase(Locale.ROOT);

        for (Map.Entry<ResourceLocation, String> entry : all.entrySet()) {
            ResourceLocation id = entry.getKey();
            JsonObject obj = ClientDevStore.parseObject(entry.getValue());
            String type = obj.get("type") instanceof JsonPrimitive p ? p.getAsString() : "item";
            Category category = Category.ofType(type);
            counts[category.ordinal()]++;
            if (category != activeCategory) {
                continue;
            }
            String title = displayTitle(id, category, obj);
            String subtitle = displaySubtitle(id, category, obj);
            if (!needle.isEmpty()
                    && !title.toLowerCase(Locale.ROOT).contains(needle)
                    && !subtitle.toLowerCase(Locale.ROOT).contains(needle)
                    && !id.toString().toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            boolean enabled = !(obj.get("enabled") instanceof JsonPrimitive p && p.isBoolean()) || p.getAsBoolean();
            boolean hasIssue = !ClientDevStore.issuesFor(ClientDevStore.TYPE_WORLD_RULE, id).isEmpty()
                    || DevChecks.problem(ClientDevStore.TYPE_WORLD_RULE, obj).isPresent();
            filtered.add(new RuleRow(id, category, title, subtitle, enabled, hasIssue));
        }
        filtered.sort(Comparator.comparing(RuleRow::title, String.CASE_INSENSITIVE_ORDER).thenComparing(r -> r.id().toString()));
        rows = filtered;

        if (selected != null) {
            String raw = all.get(selected);
            if (raw == null) {
                selected = null;
                editing = null;
                dirty = false;
                formFor = null;
                renameFor = null;
            } else if (!dirty) {
                editing = ClientDevStore.parseObject(raw);
            }
        }
        if (selected == null && !rows.isEmpty()) {
            ResourceLocation first = rows.getFirst().id();
            selected = first;
            editing = ClientDevStore.parseObject(all.get(first));
            dirty = false;
            lastSaved = null;
            formFor = null;
            renameFor = null;
        }
    }

    // --- layout & widgets

    private int sideWidth(DevModeScreen screen) {
        return Math.max(216, Math.min(268, screen.width / 3));
    }

    private int editorLeft(DevModeScreen screen) {
        int areaW = screen.width - sideWidth;
        int colW = editorWidth(screen);
        return sideWidth + Math.max(12, (areaW - colW) / 2);
    }

    private int editorWidth(DevModeScreen screen) {
        return Math.max(220, Math.min(EDITOR_MAX_W, screen.width - sideWidth - 28));
    }

    private Component newRuleLabel() {
        return Component.literal("+ ").append(DevUi.text(activeSection == Section.LOOT ? "world.loot.new" : "world.restrictions.new"));
    }

    private Component newRuleTooltip() {
        return DevUi.text(activeSection == Section.LOOT ? "world.loot.new.tooltip" : "world.restrictions.new.tooltip");
    }

    @Override
    void addWidgets(DevModeScreen screen) {
        this.currentScreen = screen;
        this.sideWidth = sideWidth(screen);
        int pad = 8;
        int fullW = sideWidth - pad * 2;
        int searchY;

        if (activeSection == Section.RESTRICTIONS) {
            // Three full-width restriction tabs: Items, Mobs, Places
            int tabTop = DevUi.TOP_BAR_H + 30;
            for (int i = 0; i < RESTRICTION_CATEGORIES.size(); i++) {
                Category cat = RESTRICTION_CATEGORIES.get(i);
                int by = tabTop + i * 21;
                int count = counts[cat.ordinal()];
                String labelStr = cat.glyph + " " + DevUi.str("world.cat." + cat.type) + (count > 0 ? " (" + count + ")" : "");
                Component label = cat == activeCategory
                        ? Component.literal(labelStr).withColor(DevUi.ACCENT)
                        : Component.literal(labelStr);
                screen.add(Button.builder(label, b -> switchCategory(screen, cat))
                        .bounds(pad, by, fullW, 19).build());
            }
            searchY = tabTop + RESTRICTION_CATEGORIES.size() * 21 + 4;
        } else {
            searchY = DevUi.TOP_BAR_H + 32;
        }

        // Search input
        Component searchHint = DevUi.text(activeSection == Section.LOOT ? "world.loot.search" : "world.restrictions.search");
        EditBox searchBox = new EditBox(screen.font(), pad, searchY, fullW, 16, searchHint);
        searchBox.setMaxLength(128);
        searchBox.setHint(searchHint);
        searchBox.setValue(search);
        searchBox.setResponder(value -> {
            search = value;
            listScroll = 0;
            reload();
        });
        screen.add(searchBox);

        // "+ New ..." button
        int newY = searchY + 20;
        screen.add(Button.builder(newRuleLabel(), b -> createRule(screen))
                .tooltip(Tooltip.create(newRuleTooltip()))
                .bounds(pad, newY, fullW, 18).build());
        this.listTop = newY + 24;

        // Right pane
        int ex = editorLeft(screen);
        int ew = editorWidth(screen);
        if (selected == null || editing == null) {
            int centerY = DevUi.TOP_BAR_H + (screen.height - DevUi.TOP_BAR_H) / 2 + 12;
            int btnW = Math.min(200, ew);
            screen.add(Button.builder(newRuleLabel(), b -> createRule(screen))
                    .bounds(ex + (ew - btnW) / 2, centerY, btnW, 20).build());
            return;
        }

        // Reset form schema when selection or type changes
        String type = editing.get("type") instanceof JsonPrimitive p ? p.getAsString() : activeCategory.type;
        if (!selected.equals(formFor) || !type.equals(formType)) {
            formFor = selected;
            formType = type;
            Node root = Schemas.definition(ClientDevStore.TYPE_WORLD_RULE);
            Node variant = root != null ? Schemas.typed(root, type) : null;
            form.reset(variant, selected.getPath(), HIDDEN_FIELDS);
        }

        // Header: ID rename + Enabled toggle
        int headerY = DevUi.TOP_BAR_H + 10;
        if (!selected.equals(renameFor)) {
            renameFor = selected;
            renameText = selected.getPath();
        }
        boolean enabled = !(editing.get("enabled") instanceof JsonPrimitive p && p.isBoolean()) || p.getAsBoolean();
        int toggleW = 68;
        int renameApplyW = 20;
        int renameBoxW = ew - toggleW - renameApplyW - 8;

        ResourceLocation currentId = selected;
        EditBox idBox = new EditBox(screen.font(), ex, headerY + 1, renameBoxW, 16, DevUi.text("rename.label"));
        idBox.setMaxLength(128);
        idBox.setValue(renameText);
        Button applyRename = Button.builder(Component.literal("✓"), b -> {
            Optional<ResourceLocation> to = StoryRenames.parse(renameText, currentId);
            if (to.isPresent() && renameProblem(currentId).isEmpty()) {
                renameRule(screen, currentId, to.get());
            }
        }).tooltip(Tooltip.create(DevUi.text("rename.apply"))).bounds(ex + renameBoxW + 2, headerY, renameApplyW, 18).build();
        idBox.setResponder(text -> {
            renameText = text;
            Optional<StoryRenames.Problem> problem = renameProblem(currentId);
            boolean bad = problem.isPresent() && problem.get() != StoryRenames.Problem.SAME;
            idBox.setTextColor(bad ? DevUi.ERROR : DevUi.TEXT);
            applyRename.active = problem.isEmpty();
        });
        Optional<StoryRenames.Problem> initProblem = renameProblem(currentId);
        applyRename.active = initProblem.isEmpty();
        idBox.setTooltip(Tooltip.create(DevUi.text("rename.tooltip", currentId.toString())));
        screen.add(idBox);
        screen.add(applyRename);

        Component toggleLabel = enabled
                ? Component.literal("✓ ").append(DevUi.text("world.enabled_on")).withColor(DevUi.GOOD)
                : Component.literal("✕ ").append(DevUi.text("world.enabled_off")).withColor(DevUi.MUTED);
        screen.add(Button.builder(toggleLabel, b -> {
            if (editing != null) {
                if (enabled) {
                    editing.addProperty("enabled", false);
                } else {
                    editing.remove("enabled");
                }
                applyChange(screen);
            }
        }).tooltip(Tooltip.create(DevUi.text("world.enabled.tooltip")))
                .bounds(ex + ew - toggleW, headerY, toggleW, 18).build());

        // Check for active validation/draft banner
        int formTop = headerY + 28;
        if (currentProblem() != null) {
            formTop += 16;
        }
        int formBottom = screen.height - 34;
        form.addWidgets(screen, ex, formTop, ew, formBottom);

        // Footer actions: Save, Duplicate, Delete
        int footerY = screen.height - 26;
        int delW = 36;
        int dupW = 36;
        int saveW = ew - delW - dupW - 8;
        screen.add(Button.builder(Component.literal("💾 ").append(DevUi.text("inspector.save")), b -> saveNow(screen))
                .bounds(ex, footerY, saveW, 20).build());
        screen.add(Button.builder(Component.literal("📋"), b -> duplicateRule(screen))
                .tooltip(Tooltip.create(DevUi.text("world.duplicate.tooltip")))
                .bounds(ex + saveW + 4, footerY, dupW, 20).build());
        screen.add(Button.builder(Component.literal("🗑"), b -> deleteRule(screen))
                .tooltip(Tooltip.create(DevUi.text("world.delete.tooltip")))
                .bounds(ex + ew - delW, footerY, delW, 20).build());
    }

    @Override
    void render(DevModeScreen screen, GuiGraphics graphics, int mouseX, int mouseY) {
        Font font = screen.font();
        // Left sidebar background
        DevUi.sidePanel(graphics, 0, sideWidth, screen.height, true);
        int pad = 8;
        int fullW = sideWidth - pad * 2;
        if (activeSection == Section.RESTRICTIONS) {
            screen.drawCut(graphics, DevUi.str("world.restrictions.title"), pad, DevUi.TOP_BAR_H + 6, fullW, DevUi.ACCENT);
            screen.drawCut(graphics, DevUi.str("world.restrictions.subtitle"), pad, DevUi.TOP_BAR_H + 17, fullW, DevUi.HINT);
            // Active restriction category highlight frame
            int idx = RESTRICTION_CATEGORIES.indexOf(activeCategory);
            if (idx >= 0) {
                int ay = DevUi.TOP_BAR_H + 30 + idx * 21;
                graphics.fill(pad - 1, ay - 1, pad + fullW + 1, ay + 20, DevUi.ACCENT);
            }
        } else {
            int lootCount = counts[Category.LOOT.ordinal()];
            String header = DevUi.str("world.loot.title") + (lootCount > 0 ? " (" + lootCount + ")" : "");
            screen.drawCut(graphics, header, pad, DevUi.TOP_BAR_H + 6, fullW, DevUi.ACCENT);
            screen.drawCut(graphics, DevUi.str("world.loot.subtitle"), pad, DevUi.TOP_BAR_H + 17, fullW, DevUi.HINT);
            graphics.fill(pad, DevUi.TOP_BAR_H + 28, sideWidth - pad, DevUi.TOP_BAR_H + 29, DevUi.PANEL_EDGE);
        }

        // Rule list
        int listBottom = screen.height - 6;
        int visible = Math.max(1, (listBottom - listTop) / ROW_H);
        listScroll = Mth.clamp(listScroll, 0, Math.max(0, rows.size() - visible));

        if (rows.isEmpty()) {
            graphics.drawString(font, DevUi.text("library.empty"), pad + 2, listTop + 6, DevUi.MUTED, false);
        } else {
            graphics.enableScissor(0, listTop, sideWidth - 1, listBottom);
            for (int i = listScroll; i < Math.min(rows.size(), listScroll + visible + 1); i++) {
                RuleRow row = rows.get(i);
                int ry = listTop + (i - listScroll) * ROW_H;
                boolean isSelected = row.id().equals(selected);
                boolean hover = !screen.modalOpen() && !screen.menuOpen()
                        && DevUi.inside(mouseX, mouseY, 4, ry, sideWidth - 9, ROW_H - 2);
                int bg = isSelected ? DevUi.ROW_SELECTED : hover ? DevUi.ROW_HOVER : 0;
                if (bg != 0) {
                    graphics.fill(4, ry, sideWidth - 5, ry + ROW_H - 2, bg);
                }
                if (isSelected) {
                    graphics.fill(4, ry, 6, ry + ROW_H - 2, DevUi.ACCENT);
                }
                // Status dot
                int dotColor = row.hasIssue() ? DevUi.ERROR : row.enabled() ? DevUi.GOOD : DevUi.HINT;
                graphics.fill(10, ry + 6, 14, ry + 10, dotColor);

                int textX = 18;
                int textW = sideWidth - textX - 8;
                int titleColor = !row.enabled() ? DevUi.HINT : isSelected ? DevUi.WARN : hover ? DevUi.TEXT : DevUi.LABEL;
                screen.drawCut(graphics, row.title(), textX, ry + 3, textW, titleColor);
                screen.drawCut(graphics, row.subtitle(), textX, ry + 14, textW, isSelected ? DevUi.LABEL : DevUi.HINT);
            }
            graphics.disableScissor();

            // Sidebar scrollbar if needed
            if (rows.size() > visible) {
                int trackH = listBottom - listTop;
                int thumbH = Math.max(14, trackH * visible / rows.size());
                int maxScroll = Math.max(1, rows.size() - visible);
                int thumbY = listTop + (trackH - thumbH) * listScroll / maxScroll;
                graphics.fill(sideWidth - 3, listTop, sideWidth - 1, listBottom, DevUi.ROW_HOVER);
                graphics.fill(sideWidth - 3, thumbY, sideWidth - 1, thumbY + thumbH, DevUi.PANEL_EDGE);
            }
        }

        // Right editor background
        graphics.fill(sideWidth, DevUi.TOP_BAR_H, screen.width, screen.height, DevUi.BACKGROUND);
        int ex = editorLeft(screen);
        int ew = editorWidth(screen);
        if (selected == null || editing == null) {
            int cy = DevUi.TOP_BAR_H + (screen.height - DevUi.TOP_BAR_H) / 2 - 28;
            var lines = font.split(DevUi.text("world.empty." + activeCategory.type), Math.min(340, ew));
            for (var line : lines) {
                int lw = font.width(line);
                graphics.drawString(font, line, ex + (ew - lw) / 2, cy, DevUi.MUTED, false);
                cy += 11;
            }
            return;
        }

        // Editor panel card framing
        graphics.fill(ex - 10, DevUi.TOP_BAR_H + 4, ex + ew + 10, screen.height - 4, DevUi.PANEL);
        DevUi.frame(graphics, ex - 10, DevUi.TOP_BAR_H + 4, ew + 20, screen.height - DevUi.TOP_BAR_H - 8, DevUi.PANEL_EDGE);
        int dividerY = DevUi.TOP_BAR_H + 33;
        String problem = currentProblem();
        if (problem != null) {
            screen.drawCut(graphics, "⚠ " + problem, ex, dividerY + 2, ew, DevUi.ERROR);
            dividerY += 16;
        }
        graphics.fill(ex - 10, dividerY, ex + ew + 10, dividerY + 1, DevUi.PANEL_EDGE);
        graphics.fill(ex - 10, screen.height - 32, ex + ew + 10, screen.height - 31, DevUi.PANEL_EDGE);

        form.render(graphics, font);
    }

    // --- input

    @Override
    boolean mouseClicked(DevModeScreen screen, double mouseX, double mouseY, int button) {
        if (mouseX < sideWidth && mouseY >= listTop && mouseY < screen.height - 6) {
            int index = listScroll + (int) ((mouseY - listTop) / ROW_H);
            if (index >= 0 && index < rows.size()) {
                RuleRow row = rows.get(index);
                if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                    selectRule(screen, row.id());
                    return true;
                }
                if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                    selectRule(screen, row.id());
                    screen.openMenu((int) mouseX, (int) mouseY, List.of(
                            DevModeScreen.MenuItem.of(Component.literal("📋 ").append(DevUi.text("world.duplicate")), () -> duplicateRule(screen)),
                            DevModeScreen.MenuItem.danger(Component.literal("🗑 ").append(DevUi.text("inspector.delete")), () -> deleteRule(screen))));
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    boolean mouseDragged(DevModeScreen screen, double mouseX, double mouseY) {
        return false;
    }

    @Override
    boolean mouseReleased(DevModeScreen screen, double mouseX, double mouseY) {
        return false;
    }

    @Override
    boolean keyPressed(DevModeScreen screen, int keyCode) {
        if (keyCode == GLFW.GLFW_KEY_DELETE && selected != null) {
            deleteRule(screen);
            return true;
        }
        return false;
    }

    @Override
    boolean scrollSidePanel(DevModeScreen screen, double mouseX, double mouseY, double amount) {
        if (mouseX < sideWidth && mouseY >= listTop) {
            int visible = Math.max(1, (screen.height - 6 - listTop) / ROW_H);
            listScroll = Mth.clamp(listScroll - (int) Math.signum(amount) * 2, 0, Math.max(0, rows.size() - visible));
            return true;
        }
        return false;
    }

    @Override
    boolean onCanvas(DevModeScreen screen, double mouseX, double mouseY) {
        return mouseX >= sideWidth && mouseY >= DevUi.TOP_BAR_H;
    }

    @Override
    void wheel(DevModeScreen screen, double mouseX, double mouseY, double amount) {
        if (selected != null) {
            form.scroll(amount);
        }
    }

    @Override
    void scrollInspector(double amount) {
        if (selected != null) {
            form.scroll(amount);
        }
    }

    // --- editing, persistence & history

    private void switchSection(DevModeScreen screen, Section next) {
        if (next == activeSection) {
            return;
        }
        saveEdits(screen);
        activeSection = next;
        activeCategory = next == Section.LOOT ? Category.LOOT : lastRestrictionCategory;
        search = "";
        listScroll = 0;
        selected = null;
        editing = null;
        dirty = false;
        formFor = null;
        renameFor = null;
        reload();
        screen.rebuild();
    }

    private void switchCategory(DevModeScreen screen, Category next) {
        if (next == activeCategory) {
            return;
        }
        saveEdits(screen);
        activeCategory = next;
        activeSection = next.section;
        if (next.section == Section.RESTRICTIONS) {
            lastRestrictionCategory = next;
        }
        search = "";
        listScroll = 0;
        selected = null;
        editing = null;
        dirty = false;
        formFor = null;
        renameFor = null;
        reload();
        screen.rebuild();
    }

    private void selectRule(DevModeScreen screen, ResourceLocation id) {
        if (id.equals(selected)) {
            return;
        }
        saveEdits(screen);
        selected = id;
        editing = ClientDevStore.parseObject(ClientDevStore.getJson(ClientDevStore.TYPE_WORLD_RULE, id));
        dirty = false;
        lastSaved = null;
        formFor = null;
        renameFor = null;
        screen.rebuild();
    }

    private void applyChange(DevModeScreen screen) {
        dirty = true;
        saveEdits(screen);
        reload();
        screen.rebuild();
    }

    private void saveNow(DevModeScreen screen) {
        dirty = true;
        saveEdits(screen);
        if (selected != null && currentProblem() == null) {
            screen.status(DevUi.text("world.status.saved", selected.getPath()), DevUi.OK);
        }
        reload();
        screen.rebuild();
    }

    @Override
    void saveEdits(DevModeScreen screen) {
        if (!dirty || selected == null || editing == null) {
            return;
        }
        ResourceLocation id = selected;
        String before = lastSaved != null ? lastSaved : ClientDevStore.getJson(ClientDevStore.TYPE_WORLD_RULE, id);
        String after = DevUi.GSON.toJson(editing);
        dirty = false;
        if (after.equals(before)) {
            return;
        }
        Optional<String> problem = DevChecks.problem(ClientDevStore.TYPE_WORLD_RULE, editing);
        if (problem.isPresent()) {
            ClientDevStore.putDraft(ClientDevStore.TYPE_WORLD_RULE, id, after);
            screen.status(DevUi.text("status.incomplete", DevChecks.shorten(problem.get())), DevUi.WARN);
            return;
        }
        lastSaved = after;
        ClientDevStore.dropDraft(ClientDevStore.TYPE_WORLD_RULE, id);
        ClientDevStore.putLocal(ClientDevStore.TYPE_WORLD_RULE, id, after);
        DevRequests.save(ClientDevStore.TYPE_WORLD_RULE, id, after, Optional.empty());
        reload();
        if (before != null) {
            String undoJson = before;
            screen.remember(DevUi.str("world.history.edit", id.getPath()),
                    () -> DevRequests.restore(ClientDevStore.TYPE_WORLD_RULE, id, undoJson, Optional.empty()),
                    () -> DevRequests.restore(ClientDevStore.TYPE_WORLD_RULE, id, after, Optional.empty()));
        }
    }

    private void createRule(DevModeScreen screen) {
        saveEdits(screen);
        ResourceLocation id = freeId(activeCategory.idPrefix);
        JsonObject template = starterTemplate(activeCategory);
        String json = DevUi.GSON.toJson(template);
        ClientDevStore.putLocal(ClientDevStore.TYPE_WORLD_RULE, id, json);
        DevRequests.save(ClientDevStore.TYPE_WORLD_RULE, id, json, Optional.empty());
        selected = id;
        editing = template;
        dirty = false;
        lastSaved = json;
        formFor = null;
        renameFor = null;
        reload();
        screen.remember(DevUi.str("world.history.create", id.getPath()),
                () -> DevRequests.delete(ClientDevStore.TYPE_WORLD_RULE, id),
                () -> DevRequests.restore(ClientDevStore.TYPE_WORLD_RULE, id, json, Optional.empty()));
        screen.status(DevUi.text("world.status.created", id.getPath()), DevUi.OK);
        screen.rebuild();
    }

    private void duplicateRule(DevModeScreen screen) {
        if (selected == null || editing == null) {
            return;
        }
        saveEdits(screen);
        ResourceLocation copyId = freeId(selected.getPath() + "_copy");
        JsonObject copy = editing.deepCopy();
        String json = DevUi.GSON.toJson(copy);
        ClientDevStore.putLocal(ClientDevStore.TYPE_WORLD_RULE, copyId, json);
        DevRequests.save(ClientDevStore.TYPE_WORLD_RULE, copyId, json, Optional.empty());
        selected = copyId;
        editing = copy;
        dirty = false;
        lastSaved = json;
        formFor = null;
        renameFor = null;
        reload();
        screen.remember(DevUi.str("world.history.create", copyId.getPath()),
                () -> DevRequests.delete(ClientDevStore.TYPE_WORLD_RULE, copyId),
                () -> DevRequests.restore(ClientDevStore.TYPE_WORLD_RULE, copyId, json, Optional.empty()));
        screen.status(DevUi.text("world.status.duplicated", copyId.getPath()), DevUi.OK);
        screen.rebuild();
    }

    private void deleteRule(DevModeScreen screen) {
        if (selected == null) {
            return;
        }
        ResourceLocation id = selected;
        String backup = ClientDevStore.getJson(ClientDevStore.TYPE_WORLD_RULE, id);
        DevRequests.delete(ClientDevStore.TYPE_WORLD_RULE, id);
        selected = null;
        editing = null;
        dirty = false;
        lastSaved = null;
        formFor = null;
        renameFor = null;
        reload();
        if (backup != null) {
            screen.remember(DevUi.str("world.history.delete", id.getPath()),
                    () -> DevRequests.restore(ClientDevStore.TYPE_WORLD_RULE, id, backup, Optional.empty()),
                    () -> DevRequests.delete(ClientDevStore.TYPE_WORLD_RULE, id));
        }
        screen.status(DevUi.text("world.status.deleted", id.getPath()), DevUi.WARN);
        screen.rebuild();
    }

    private void renameRule(DevModeScreen screen, ResourceLocation from, ResourceLocation to) {
        saveEdits(screen);
        if (dirty || currentProblem() != null) {
            screen.status(DevUi.text("rename.incomplete"), DevUi.WARN);
            return;
        }
        requestRename(screen, from, to);
        screen.remember(DevUi.str("history.rename", from.toString(), to.toString()),
                () -> requestRename(screen, to, from),
                () -> requestRename(screen, from, to));
    }

    private void requestRename(DevModeScreen screen, ResourceLocation from, ResourceLocation to) {
        String json = ClientDevStore.getJson(ClientDevStore.TYPE_WORLD_RULE, from);
        if (json != null) {
            ClientDevStore.removeLocal(ClientDevStore.TYPE_WORLD_RULE, from);
            ClientDevStore.putLocal(ClientDevStore.TYPE_WORLD_RULE, to, json);
        }
        if (from.equals(selected)) {
            selected = to;
            renameFor = null;
            formFor = null;
        }
        DevRequests.rename(ClientDevStore.TYPE_WORLD_RULE, from, to);
        reload();
        screen.status(DevUi.text("rename.sent", from.toString(), to.toString()), DevUi.OK);
        screen.rebuild();
    }

    private Optional<StoryRenames.Problem> renameProblem(ResourceLocation current) {
        return StoryRenames.problem(ClientDevStore.TYPE_WORLD_RULE, current, renameText,
                (type, id) -> ClientDevStore.getJson(type, id) != null);
    }

    private @Nullable String currentProblem() {
        if (selected == null || editing == null) {
            return null;
        }
        Optional<String> codecProblem = DevChecks.problem(ClientDevStore.TYPE_WORLD_RULE, editing);
        if (codecProblem.isPresent()) {
            return DevChecks.shorten(codecProblem.get());
        }
        List<String> issues = ClientDevStore.issuesFor(ClientDevStore.TYPE_WORLD_RULE, selected);
        if (!issues.isEmpty()) {
            String first = issues.getFirst();
            int idx = first.indexOf(": ");
            return idx >= 0 ? first.substring(idx + 2) : first;
        }
        return null;
    }

    private static ResourceLocation freeId(String prefix) {
        Map<ResourceLocation, String> existing = ClientDevStore.byType(ClientDevStore.TYPE_WORLD_RULE);
        for (int i = 1; i < 10000; i++) {
            ResourceLocation candidate = ClientDevStore.id(prefix + "_" + i);
            if (!existing.containsKey(candidate)) {
                return candidate;
            }
        }
        return ClientDevStore.id(prefix + "_" + System.currentTimeMillis());
    }

    private static JsonObject starterTemplate(Category category) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", category.type);
        switch (category) {
            case ITEM -> obj.addProperty("items", "minecraft:diamond_sword");
            case MOB -> obj.addProperty("entities", "minecraft:creeper");
            case PLACE -> {
                obj.addProperty("dimension", "minecraft:the_nether");
                obj.addProperty("mode", "barrier");
            }
            case LOOT -> {
                obj.addProperty("operation", "add");
                obj.addProperty("item", "minecraft:emerald");
            }
        }
        return obj;
    }

    // --- human-readable row summaries

    static String displayTitle(ResourceLocation id, Category category, JsonObject obj) {
        if (obj.get("title") instanceof JsonPrimitive p && !p.getAsString().isBlank()) {
            return p.getAsString();
        }
        return switch (category) {
            case ITEM -> firstIdLabel(obj.get("items"), true, id.getPath());
            case MOB -> firstIdLabel(obj.get("entities"), false, id.getPath());
            case PLACE -> placeLabel(obj, id.getPath());
            case LOOT -> lootTitle(obj, id.getPath());
        };
    }

    private static String displaySubtitle(ResourceLocation id, Category category, JsonObject obj) {
        List<String> parts = new ArrayList<>();
        switch (category) {
            case ITEM -> {
                if (flag(obj, "block_use", true)) parts.add(Component.translatable("storybound.world.lock.use").getString());
                if (flag(obj, "block_attack", true)) parts.add(Component.translatable("storybound.world.lock.attack").getString());
                if (flag(obj, "block_equip", true)) parts.add(Component.translatable("storybound.world.lock.equip").getString());
                if (flag(obj, "block_craft", false)) parts.add(Component.translatable("storybound.world.lock.craft").getString());
                if (flag(obj, "block_pickup", false)) parts.add(Component.translatable("storybound.world.lock.pickup").getString());
            }
            case MOB -> {
                if (obj.get("replace_with") instanceof JsonPrimitive rep) {
                    parts.add("→ " + shortRegistryName(rep.getAsString(), false));
                }
                if (flag(obj, "block_spawners", false)) {
                    parts.add(DevUi.str("world.sub.spawners"));
                }
                if (!flag(obj, "despawn_existing", true)) {
                    parts.add(DevUi.str("world.sub.no_despawn"));
                }
            }
            case PLACE -> {
                String mode = obj.get("mode") instanceof JsonPrimitive p ? p.getAsString() : "barrier";
                parts.add(FormCore.enumLabel("mode", mode).getString());
            }
            case LOOT -> {
                String op = obj.get("operation") instanceof JsonPrimitive p ? p.getAsString() : "add";
                parts.add(FormCore.enumLabel("operation", op).getString());
                if (obj.get("chance") instanceof JsonPrimitive c && c.isNumber() && c.getAsDouble() < 1.0) {
                    parts.add(FormCore.format(Math.round(c.getAsDouble() * 10000) / 100.0) + "%");
                }
            }
        }
        parts.add(id.getPath());
        return String.join(" · ", parts);
    }

    private static boolean flag(JsonObject obj, String key, boolean fallback) {
        return obj.get(key) instanceof JsonPrimitive p && p.isBoolean() ? p.getAsBoolean() : fallback;
    }

    private static String firstIdLabel(@Nullable JsonElement el, boolean item, String fallback) {
        List<String> ids = FormCore.stringsOf(el instanceof JsonPrimitive one ? FormCore.asList(one) : el);
        if (ids.isEmpty()) {
            return fallback;
        }
        String first = shortRegistryName(ids.getFirst(), item);
        return ids.size() > 1 ? first + " +" + (ids.size() - 1) : first;
    }

    private static String placeLabel(JsonObject obj, String fallback) {
        List<String> parts = new ArrayList<>();
        if (obj.get("dimension") instanceof JsonPrimitive dim) {
            parts.add(FormEntities.dimensionName(dim.getAsString()));
        }
        for (String key : List.of("biome", "structure")) {
            List<String> ids = FormCore.stringsOf(obj.get(key) instanceof JsonPrimitive one ? FormCore.asList(one) : obj.get(key));
            if (!ids.isEmpty()) {
                String s = ids.getFirst();
                parts.add(DevUi.humanize(s.substring(s.indexOf(':') + 1)));
            }
        }
        return parts.isEmpty() ? fallback : String.join(" · ", parts);
    }

    private static String lootTitle(JsonObject obj, String fallback) {
        String op = obj.get("operation") instanceof JsonPrimitive p ? p.getAsString() : "add";
        return switch (op) {
            case "remove" -> "− " + firstIdLabel(obj.get("target"), true, fallback);
            case "replace" -> firstIdLabel(obj.get("target"), true, "?") + " → "
                    + (obj.get("item") instanceof JsonPrimitive it ? shortRegistryName(it.getAsString(), true) : "?");
            default -> "+ " + (obj.get("item") instanceof JsonPrimitive it ? shortRegistryName(it.getAsString(), true) : fallback);
        };
    }

    private static String shortRegistryName(String raw, boolean item) {
        if (raw.startsWith("#")) {
            return raw;
        }
        ResourceLocation id = ResourceLocation.tryParse(raw);
        if (id == null) {
            return raw;
        }
        if (item && BuiltInRegistries.ITEM.containsKey(id)) {
            return BuiltInRegistries.ITEM.get(id).getDescription().getString();
        }
        if (!item && BuiltInRegistries.ENTITY_TYPE.containsKey(id)) {
            return BuiltInRegistries.ENTITY_TYPE.get(id).getDescription().getString();
        }
        return DevUi.humanize(id.getPath());
    }
}

