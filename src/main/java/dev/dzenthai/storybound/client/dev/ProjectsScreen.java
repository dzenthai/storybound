package dev.dzenthai.storybound.client.dev;

import dev.dzenthai.storybound.core.data.StoryProjects;
import dev.dzenthai.storybound.network.DevProjectPayloads;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The editor's stories (November 18, 2026; before, the top bar's "Story: storybound" only renamed the namespace): opened
 * by the top bar's 📁 with the edited story's title, or "Stories" in the ⚙ menu. A row per story with its title and
 * state ("in the editor", in the game or off), "Open" to edit it and ⚙ for its settings (its title, whether it is in the
 * game, and deleting it: its folder moves to {@code .deleted}, after a page that asks; November 19, 2026); "New story"
 * asks a title and its namespace (made from the title until the author types one) and opens it.
 * Every change goes to the server ({@code DevProjects}) and the screen waits for its answer.
 */
final class ProjectsScreen extends Screen {
    private enum Page { LIST, CREATE, MANAGE, DELETE }
    private enum AfterRequest { EDITOR, LIST }

    private final DevModeScreen parent;
    private Page view = Page.LIST;
    private AfterRequest afterRequest;
    private String requestedProject;
    private String editingProject;
    private String draftTitle = "";
    /** A new story's id (its namespace), suggested from the title until the author edits it. */
    private String draftId = "";
    private boolean idEdited;
    /** The id box is being filled from the title, not typed in. */
    private boolean suggesting;
    private boolean enabled;
    private boolean waiting;
    private boolean failed;
    private int page;
    private int rows;
    private int left;
    private int contentWidth;
    private int formTop;
    private List<ClientDevStore.Project> visibleProjects = List.of();

    ProjectsScreen(DevModeScreen parent) {
        super(DevUi.text("projects.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        ClientDevStore.setListener(() -> minecraft.execute(() -> {
            if (!waiting && view == Page.LIST) {
                rebuildWidgets();
            }
        }));
        ClientDevStore.setProjectActionListener(success -> minecraft.execute(() -> {
            waiting = false;
            failed = !success;
            if (success) {
                if (afterRequest == AfterRequest.EDITOR && requestedProject.equals(ClientDevStore.projectId())) {
                    minecraft.setScreen(parent);
                    return;
                }
                if (afterRequest == AfterRequest.LIST) {
                    view = Page.LIST;
                }
            }
            rebuildWidgets();
        }));
        contentWidth = Math.min(view == Page.LIST ? 460 : 360, width - 32);
        left = (width - contentWidth) / 2;
        if (view == Page.LIST) {
            buildList();
        } else if (view == Page.DELETE) {
            buildDelete();
        } else {
            buildForm();
        }
    }

    /** Whether the story may be asked to be deleted: not the last one, which the editor and the game need. */
    private static boolean deletable() {
        return ClientDevStore.projects().size() > 1;
    }

    /** "Delete «title»?": what happens to its files, and the two answers. */
    private void buildDelete() {
        int half = (contentWidth - 8) / 2;
        button(DevUi.text("text.cancel"), left, height - 30, half, () -> {
            view = Page.MANAGE;
            failed = false;
            rebuildWidgets();
        });
        button(Component.literal("🗑 ").append(DevUi.text("projects.delete")).copy().withColor(DevUi.ERROR), left + half + 8, height - 30,
                contentWidth - half - 8, () -> request("delete", editingProject, "", false, AfterRequest.LIST)).active = !waiting && deletable();
    }

    private void buildList() {
        rows = Math.max(1, Math.min(6, (height - 124) / 44));
        int lastPage = Math.max(0, (ClientDevStore.projects().size() - 1) / rows);
        page = Math.min(page, lastPage);
        visibleProjects = ClientDevStore.projects().stream().skip((long) page * rows).limit(rows).toList();
        int y = 56;
        for (var project : visibleProjects) {
            button(DevUi.text("projects.open"), left + contentWidth - 105, y + 10, 70, () ->
                    request("select", project.id(), "", false, AfterRequest.EDITOR));
            // Its settings at once, for any story, without opening it in the editor (November 18, 2026: before, another
            // story was opened first and its whole snapshot awaited, a wait and a jump).
            Button manage = button(Component.literal("⚙"), left + contentWidth - 29, y + 10, 23, () -> {
                showManagement(project);
                rebuildWidgets();
            });
            manage.setTooltip(Tooltip.create(DevUi.text("projects.manage_named", project.title())));
            y += 44;
        }
        if (lastPage > 0) {
            int pagerY = 60 + rows * 44;
            button(Component.literal("←"), left, pagerY, 26, () -> { page--; rebuildWidgets(); }).active = !waiting && page > 0;
            button(Component.literal("→"), left + contentWidth - 26, pagerY, 26, () -> { page++; rebuildWidgets(); }).active = !waiting && page < lastPage;
        }
        int half = (contentWidth - 8) / 2;
        button(DevUi.text("projects.new").copy().withColor(DevUi.ACCENT), left, height - 30, half, () -> {
            draftTitle = "";
            draftId = StoryProjects.suggestId("", takenIds());
            idEdited = false;
            enabled = false;
            failed = false;
            view = Page.CREATE;
            rebuildWidgets();
        });
        button(DevUi.text("projects.back"), left + half + 8, height - 30, contentWidth - half - 8, this::onClose);
    }

    private void showManagement(ClientDevStore.Project project) {
        editingProject = project.id();
        draftTitle = project.title();
        enabled = project.enabled();
        failed = false;
        view = Page.MANAGE;
    }

    /** The rows of the form, under a grey label each (the forms' rule: a label above, the control shows only its value). */
    private static final int ROW_STEP = 40;
    private static final int LABEL_TO_CONTROL = 12;

    private void buildForm() {
        formTop = Math.max(54, (height - 160) / 2);
        EditBox titleBox = new EditBox(font, left, formTop + LABEL_TO_CONTROL, contentWidth, 20, DevUi.text("projects.name"));
        titleBox.setMaxLength(80);
        titleBox.setValue(draftTitle);
        titleBox.setEditable(!waiting);
        addRenderableWidget(titleBox);
        EditBox idBox = null;
        if (view == Page.MANAGE) {
            // In the game: a switch, its value in words (November 18, 2026; before, "In the game: on" inside the button).
            button(DevUi.text(enabled ? "projects.enabled" : "projects.disabled"), left, formTop + ROW_STEP + LABEL_TO_CONTROL, contentWidth, () -> {
                enabled = !enabled;
                rebuildWidgets();
            }).setTooltip(Tooltip.create(DevUi.text("projects.enabled.tooltip")));
        } else {
            // The new story's namespace, made from its title until the author types one of their own (November 18, 2026;
            // before, a random story_1a2b3c4d that then stood in every id and translation key).
            idBox = new EditBox(font, left, formTop + ROW_STEP + LABEL_TO_CONTROL, contentWidth, 20, DevUi.text("projects.id"));
            idBox.setMaxLength(64);
            idBox.setValue(draftId);
            idBox.setEditable(!waiting);
            idBox.setTextColor(StoryProjects.validId(draftId) ? DevUi.TEXT : DevUi.ERROR);
            addRenderableWidget(idBox);
        }
        // The bottom row: Cancel, then for a story's settings Delete in the middle, then Save (November 19, 2026: Delete in
        // a row of its own above them ran into the hint under the form on a short window).
        boolean manage = view == Page.MANAGE;
        int cell = manage ? (contentWidth - 16) / 3 : (contentWidth - 8) / 2;
        int saveX = left + (manage ? 2 * (cell + 8) : cell + 8);
        Button save = button(DevUi.text(view == Page.CREATE ? "projects.create_open" : "projects.save").copy().withColor(DevUi.ACCENT),
                saveX, height - 30, left + contentWidth - saveX, () -> {
                    if (view == Page.CREATE) {
                        request("create", draftId, draftTitle.trim(), false, AfterRequest.EDITOR);
                    } else {
                        request("update", editingProject, draftTitle.trim(), enabled, AfterRequest.LIST);
                    }
                });
        save.active = canSave();
        EditBox idField = idBox;
        titleBox.setResponder(value -> {
            draftTitle = value;
            if (idField != null && !idEdited) {
                draftId = StoryProjects.suggestId(value, takenIds());
                suggesting = true;
                idField.setValue(draftId);
                suggesting = false;
                idField.setTextColor(StoryProjects.validId(draftId) ? DevUi.TEXT : DevUi.ERROR);
            }
            save.active = canSave();
        });
        if (idField != null) {
            idField.setResponder(value -> {
                draftId = value;
                if (!suggesting) {
                    idEdited = true;
                }
                idField.setTextColor(StoryProjects.validId(value) ? DevUi.TEXT : DevUi.ERROR);
                save.active = canSave();
            });
        }
        button(DevUi.text("text.cancel"), left, height - 30, cell, this::onClose);
        if (manage) {
            Button delete = button(Component.literal("🗑 ").append(DevUi.text("projects.delete")).copy().withColor(DevUi.ERROR),
                    left + cell + 8, height - 30, cell, () -> {
                        view = Page.DELETE;
                        failed = false;
                        rebuildWidgets();
                    });
            delete.active = !waiting && deletable();
            delete.setTooltip(Tooltip.create(DevUi.text(deletable() ? "projects.delete.tooltip" : "projects.delete_last")));
        }
        if (view == Page.CREATE && !waiting) {
            setInitialFocus(titleBox);
        }
    }

    /** A title, and for a new story an id it can have. */
    private boolean canSave() {
        return !waiting && !draftTitle.isBlank() && (view != Page.CREATE || StoryProjects.validId(draftId) && !takenIds().contains(draftId));
    }

    /** The ids of the stories there are: a new one's must differ. */
    private java.util.Set<String> takenIds() {
        java.util.Set<String> ids = new java.util.HashSet<>();
        ClientDevStore.projects().forEach(project -> ids.add(project.id()));
        return ids;
    }

    private Button button(Component text, int x, int y, int w, Runnable action) {
        Button button = Button.builder(text, b -> { if (!waiting) action.run(); }).bounds(x, y, w, 20).build();
        button.active = !waiting;
        addRenderableWidget(new DevButton(button));
        return button;
    }

    private void request(String action, String project, String title, boolean enabled, AfterRequest after) {
        waiting = true;
        failed = false;
        requestedProject = project;
        afterRequest = after;
        PacketDistributor.sendToServer(new DevProjectPayloads.Action(action, project, title, enabled));
        rebuildWidgets();
    }

    /**
     * The screen's own drawing, under its buttons (November 18, 2026: drawn in {@code render} before the buttons, it went
     * under the menu blur Minecraft lays over what is drawn before them, and every text was blurred).
     */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, DevUi.BACKGROUND);
        String heading = switch (view) {
            case LIST -> "projects.title";
            case CREATE -> "projects.new";
            case MANAGE -> "projects.manage";
            case DELETE -> "projects.delete_heading";
        };
        graphics.drawCenteredString(font, DevUi.text(heading), width / 2, 15, DevUi.TEXT);
        if (view == Page.LIST) {
            graphics.drawCenteredString(font, DevUi.text("projects.choose"), width / 2, 33, DevUi.MUTED);
            int y = 56;
            for (var project : visibleProjects) {
                boolean current = project.id().equals(ClientDevStore.projectId());
                graphics.fill(left, y, left + contentWidth, y + 40, current ? DevUi.ROW_SELECTED : DevUi.PANEL);
                DevUi.frame(graphics, left, y, contentWidth, 40, current ? DevUi.ACCENT : DevUi.PANEL_EDGE);
                String name = font.plainSubstrByWidth(project.title(), contentWidth - 125);
                graphics.drawString(font, name, left + 9, y + 7, DevUi.TEXT, false);
                String state = (current ? DevUi.str("projects.current") + " · " : "")
                        + DevUi.str(project.enabled() ? "projects.on" : "projects.off");
                graphics.drawString(font, font.plainSubstrByWidth(state, contentWidth - 125), left + 9, y + 24, DevUi.MUTED, false);
                y += 44;
            }
            int pages = (ClientDevStore.projects().size() + rows - 1) / rows;
            if (pages > 1) {
                graphics.drawCenteredString(font, Component.literal((page + 1) + " / " + pages), width / 2, 66 + rows * 44, DevUi.MUTED);
            }
        } else if (view == Page.DELETE) {
            int y = Math.max(54, height / 2 - 40);
            for (var line : font.split(DevUi.text("projects.delete_question", draftTitle), contentWidth)) {
                graphics.drawCenteredString(font, line, width / 2, y, DevUi.TEXT);
                y += 12;
            }
            y += 8;
            for (var line : font.split(DevUi.text("projects.delete_hint", editingProject), contentWidth)) {
                graphics.drawCenteredString(font, line, width / 2, y, DevUi.MUTED);
                y += 11;
            }
        } else {
            graphics.drawString(font, DevUi.text("projects.name"), left, formTop, DevUi.LABEL, false);
            int y = formTop + ROW_STEP;
            graphics.drawString(font, DevUi.text(view == Page.CREATE ? "projects.id" : "projects.ingame"), left, y, DevUi.LABEL, false);
            y += LABEL_TO_CONTROL + 24;
            if (view == Page.CREATE) {
                // What the id is for, with the node ids it makes; why it is red when it cannot be one.
                boolean valid = StoryProjects.validId(draftId);
                boolean taken = takenIds().contains(draftId);
                Component idHint = !valid ? DevUi.text("projects.id_bad") : taken ? DevUi.text("projects.id_taken")
                        : DevUi.text("projects.id_hint", draftId);
                for (var line : font.split(idHint, contentWidth)) {
                    graphics.drawString(font, line, left, y, valid && !taken ? DevUi.HINT : DevUi.BAD, false);
                    y += 11;
                }
                y += 6;
            }
            String hint = view == Page.CREATE ? "projects.create_hint" : "projects.runtime_hint";
            for (var line : font.split(DevUi.text(hint), contentWidth)) {
                graphics.drawString(font, line, left, y, DevUi.MUTED, false);
                y += 11;
            }
        }
        if (waiting || failed) {
            graphics.drawCenteredString(font, DevUi.text(waiting ? "projects.wait" : "projects.failed"), width / 2, height - 44,
                    waiting ? DevUi.MUTED : DevUi.BAD);
        }
    }

    @Override
    public void onClose() {
        if (!waiting) {
            if (view == Page.DELETE) {
                view = Page.MANAGE;
                failed = false;
                rebuildWidgets();
            } else if (view != Page.LIST) {
                view = Page.LIST;
                failed = false;
                rebuildWidgets();
            } else {
                minecraft.setScreen(parent);
            }
        }
    }

    @Override
    public void removed() {
        ClientDevStore.setListener(null);
        ClientDevStore.setProjectActionListener(null);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
