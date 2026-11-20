package dev.dzenthai.storybound.client.dev;

import com.google.gson.JsonObject;
import dev.dzenthai.storybound.client.dev.DevModeScreen.MenuItem;
import dev.dzenthai.storybound.client.dev.NodeTemplates.Template;
import dev.dzenthai.storybound.core.story.StoryFlow;
import dev.dzenthai.storybound.core.story.StoryLinks.NodeKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * The story editor: one workspace (November 16, 2026, stage 1 of the workspace redesign; before, three tabs, Story,
 * Library and All links): the left panel ({@link StoryPanel}: chapters, search, problems), the canvas, and the selected
 * definition's inspector on the right ({@link StoryInspector}). The canvas is the story's spine as in FTB Quests: the
 * quests and story events of the open chapter, an arrow from each to what it leads to with the nodes between folded
 * into it ({@link StoryFlow}) and its words on it ("after it is completed", "through 💬"); what belongs to a quest or
 * an event (its giver, dialogues, documents, waypoints, boss) is badges under it ({@link StoryFlow#attached}).
 * Hovering a node lights its links and dims the rest. "Show all links" ({@link Tab#ALL}) shows every definition as a
 * card with every link as an arrow. On both, Shift selects several cards; dragging from a card's port to another card
 * writes the link into the first one's JSON ({@link StoryEdits#link}).

 * <p>
 * One layer of the story editor, split by topic, each seeing the ones below it; the rest of the editor uses StoryView:
 * StoryCards &larr; StoryChapters &larr; StoryEditing &larr; StoryDrawing &larr; StoryView.
 */
final class StoryView extends StoryDrawing {
    /**
     * Escape: the selection (and its inspector); then nothing, and the editor closes. "All links" is a way of showing
     * the map, not a level inside it: it stays for the next opening (November 20, 2026, the author; for a day Escape
     * took it back to the chapters first, so the editor always reopened on them).
     */
    @Override
    boolean escape(DevModeScreen screen) {
        if (selected != null || !selection.isEmpty()) {
            select(screen, null);
            screen.rebuild();
            return true;
        }
        return false;
    }

    @Override
    boolean mouseClicked(DevModeScreen screen, double mouseX, double mouseY, int button) {
        if (stripHolds(screen, mouseX, mouseY)) {
            return clickLeftPanel(screen, mouseX, mouseY, button);
        }
        if (!onCanvas(screen, mouseX, mouseY)) {
            return false;
        }
        double worldX = worldX(mouseX);
        double worldY = worldY(mouseY);
        Badge badge = badgeAt(worldX, worldY, screen.font());
        if (badge != null && button != GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
            clickBadge(screen, badge, mouseX, mouseY);
            return true;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            menuWorldX = (int) Math.round(worldX);
            menuWorldY = (int) Math.round(worldY);
            screen.openMenu((int) mouseX, (int) mouseY, menu(screen, cardAt(worldX, worldY)));
            return true;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
            startPan(mouseX, mouseY);
            return true;
        }
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return false;
        }
        for (int i = cards.size() - 1; i >= 0; i--) {
            Card card = cards.get(i);
            if (card.onPort(worldX, worldY)) {
                linkingFrom = card.key();
                return true;
            }
            if (card.holds(worldX, worldY)) {
                clickCard(screen, card, worldX, worldY);
                return true;
            }
        }
        if (Screen.hasShiftDown()) {
            boxSelecting = true;
            boxStartX = boxEndX = worldX;
            boxStartY = boxEndY = worldY;
            return true;
        }
        if (selection.size() > 1) {
            selection.clear();
            if (selected != null) {
                selection.add(selected);
            }
            screen.rebuild();
        }
        startPan(mouseX, mouseY);
        return true;
    }

    private boolean clickLeftPanel(DevModeScreen screen, double mouseX, double mouseY, int button) {
        return panel.mouseClicked(screen, mouseX, mouseY, button);
    }

    /**
     * A badge: its node shows in the inspector (a double click opens a dialogue's graph); "+N" lists the rest to pick
     * from; ⚡ only says what it means.
     */
    private void clickBadge(DevModeScreen screen, Badge badge, double mouseX, double mouseY) {
        if (badge.node() == null) {
            if (!badge.rest().isEmpty()) {
                List<MenuItem> items = new ArrayList<>();
                for (NodeKey key : badge.rest()) {
                    items.add(MenuItem.of(Component.literal(glyph(key.type()) + " " + title(key)), () -> {
                        select(screen, key);
                        screen.rebuild();
                    }));
                }
                screen.openMenu((int) mouseX, (int) mouseY, items);
            }
            return;
        }
        NodeKey key = badge.node();
        long now = System.currentTimeMillis();
        if (key.equals(lastClicked) && now - lastClickTime < DOUBLE_CLICK_MS && key.type().equals(ClientDevStore.TYPE_DIALOGUE)) {
            lastClicked = null;
            screen.openDialogue(key.id());
            return;
        }
        lastClickTime = now;
        lastClicked = key;
        select(screen, key);
        screen.rebuild();
    }

    private void clickCard(DevModeScreen screen, Card card, double worldX, double worldY) {
        NodeKey key = card.key();
        long now = System.currentTimeMillis();
        if (key.equals(lastClicked) && now - lastClickTime < DOUBLE_CLICK_MS && key.type().equals(ClientDevStore.TYPE_DIALOGUE)) {
            lastClicked = null;
            screen.openDialogue(key.id());
            return;
        }
        if (key.equals(lastClicked) && now - lastClickTime < DOUBLE_CLICK_MS && key.type().equals(ClientDevStore.TYPE_QUEST)) {
            lastClicked = null;
            screen.openQuest(key.id());
            return;
        }
        lastClickTime = now;
        lastClicked = key;
        screen.showInspector();
        if (Screen.hasShiftDown()) {
            if (selection.remove(key)) {
                selectFromSet(screen, key.equals(selected) ? (selection.isEmpty() ? null : selection.iterator().next()) : selected);
            } else {
                selection.add(key);
                selectFromSet(screen, key);
            }
            screen.rebuild();
            return;
        }
        if (!selection.contains(key)) {
            select(screen, key);
        } else if (!key.equals(selected)) {
            selectFromSet(screen, key);
        }
        dragging = key;
        dragOffsetX = worldX - card.x();
        dragOffsetY = worldY - card.y();
        dragStart.clear();
        for (NodeKey moved : selection) {
            int[] pos = positions.get(moved);
            if (pos != null) {
                dragStart.put(moved, pos.clone());
            }
        }
        screen.rebuild();
    }

    List<MenuItem> menu(DevModeScreen screen, @Nullable Card clicked) {
        List<MenuItem> items = new ArrayList<>();
        if (selection.size() > 1) {
            items.add(MenuItem.danger(Component.literal("🗑 ").append(DevUi.text("menu.delete_selected", selection.size())), () -> deleteSelection(screen)));
            items.add(MenuItem.of(Component.literal("✕ ").append(DevUi.text("menu.clear_selection")), () -> {
                select(screen, null);
                screen.rebuild();
            }));
            return items;
        }
        if (clicked == null) {
            // The map makes its own nodes, quests and story events; what they use is made from a node's menu or the search's "+".
            return templateMenu(screen, tab == Tab.STORY ? Set.of(ClientDevStore.TYPE_QUEST, ClientDevStore.TYPE_EVENT) : null, true);
        }
        NodeKey target = clicked.key();
        select(screen, target);
        screen.rebuild();
        String path = target.id().getPath();
        items.add(MenuItem.danger(Component.literal("🗑 ").append(DevUi.text("menu.delete", path)), () -> deleteSelection(screen)));
        items.add(MenuItem.of(Component.literal("📋 ").append(DevUi.text("menu.duplicate", path)), () -> duplicate(screen, target)));
        if (tab == Tab.STORY) {
            items.add(MenuItem.of(Component.literal("🕸 ").append(DevUi.text("menu.show_links")), () -> reveal(screen, Tab.ALL, target)));
            if (!Chapters.hidden(storyChapter)) {
                ResourceLocation open = storyChapter;
                items.add(MenuItem.of(Component.literal("📤 ").append(DevUi.text("menu.out_of_chapter")), () -> setInChapter(screen, open, target, false)));
            }
            for (Map.Entry<ResourceLocation, JsonObject> entry : Chapters.sorted()) {
                if (!entry.getKey().equals(storyChapter) && !Chapters.positions(entry.getValue()).containsKey(target)) {
                    ResourceLocation id = entry.getKey();
                    items.add(MenuItem.of(Component.literal("📁 ").append(DevUi.text("menu.to_chapter", chapterName(id))),
                            () -> setInChapter(screen, id, target, true)));
                }
            }
        } else if (target.type().equals(ClientDevStore.TYPE_QUEST)) {
            items.add(MenuItem.of(Component.literal("📜 ").append(DevUi.text("menu.show_on_map")), () -> reveal(screen, Tab.STORY, target)));
            items.add(MenuItem.of(Component.literal("🔎 ").append(DevUi.text("menu.inside_quest")), () -> screen.openQuest(target.id())));
        }
        ResourceLocation type = target.type();
        if (type.equals(ClientDevStore.TYPE_NPC)) {
            items.add(linked(screen, target, "💬", "menu.link.dialogue", Template.DIALOGUE_SCREEN, false));
            items.add(linked(screen, target, "💭", "menu.link.bubble", Template.DIALOGUE_BUBBLE, false));
            items.add(linked(screen, target, "🗨", "menu.link.phrases", Template.PHRASES, false));
        } else if (type.equals(ClientDevStore.TYPE_DIALOGUE)) {
            items.add(MenuItem.of(Component.literal("⤢ ").append(DevUi.text("menu.open_dialogue")), () -> screen.openDialogue(target.id())));
            items.add(linked(screen, target, "📜", "menu.link.quest_main", Template.QUEST_MAIN, false));
            items.add(linked(screen, target, "🗡", "menu.link.quest_side", Template.QUEST_SIDE, false));
            items.add(linked(screen, target, "📖", "menu.link.give_document", Template.DOCUMENT, false));
            items.add(linked(screen, target, "🧑", "menu.link.new_npc", Template.NPC, true));
            items.add(linked(screen, target, "⚙", "menu.link.runs_script", Template.SCRIPT, false));
        } else if (type.equals(ClientDevStore.TYPE_DOCUMENT)) {
            items.add(linked(screen, target, "📜", "menu.link.quest_main", Template.QUEST_MAIN, false));
            items.add(linked(screen, target, "🗡", "menu.link.quest_side", Template.QUEST_SIDE, false));
            items.add(linked(screen, target, "💬", "menu.link.from_dialogue", Template.DIALOGUE_SCREEN, true));
        } else if (type.equals(ClientDevStore.TYPE_QUEST)) {
            items.add(linked(screen, target, "📍", "menu.link.waypoint", Template.WAYPOINT, false));
            items.add(linked(screen, target, "📜", "menu.link.next_quest", Template.QUEST_MAIN, false));
            items.add(linked(screen, target, "🗡", "menu.link.quest_side", Template.QUEST_SIDE, false));
            items.add(linked(screen, target, "🐉", "menu.link.boss", Template.BOSS, false));
            items.add(linked(screen, target, "💬", "menu.link.started_by_dialogue", Template.DIALOGUE_SCREEN, true));
            items.add(linked(screen, target, "📖", "menu.link.started_by_document", Template.DOCUMENT, true));
            items.add(linked(screen, target, "⚙", "menu.link.runs_script_on_complete", Template.SCRIPT, false));
        } else if (type.equals(ClientDevStore.TYPE_WAYPOINT)) {
            items.add(linked(screen, target, "📜", "menu.link.new_quest", Template.QUEST_MAIN, true));
        } else if (type.equals(ClientDevStore.TYPE_BOSS)) {
            items.add(linked(screen, target, "🗡", "menu.link.hunt_quest", Template.QUEST_SIDE, true));
        } else if (type.equals(ClientDevStore.TYPE_SCRIPT)) {
            items.add(linked(screen, target, "📜", "menu.link.script_leads_to_quest", Template.QUEST_MAIN, false));
            items.add(linked(screen, target, "💬", "menu.link.script_run_by_dialogue", Template.DIALOGUE_SCREEN, true));
        }
        return items;
    }

    /** The templates making definitions of the kind; all of them without one. */
    static List<Template> templatesFor(@Nullable ResourceLocation kind) {
        return TEMPLATES.stream().filter(item -> kind == null || item.kind().equals(kind)).map(TemplateItem::template).toList();
    }

    /**
     * "New …" lines for the kinds (all kinds without them): on the canvas at the menu's place, from the search's "+"
     * nowhere (shown in the inspector; a quest or a story event also goes on the open chapter).
     */
    List<MenuItem> templateMenu(DevModeScreen screen, @Nullable Set<ResourceLocation> kinds, boolean onCanvas) {
        List<MenuItem> items = new ArrayList<>();
        for (TemplateItem item : TEMPLATES) {
            if (kinds == null || kinds.contains(item.kind())) {
                items.add(MenuItem.of(Component.literal(item.icon() + " ").append(DevUi.text(item.label())), () -> {
                    if (onCanvas) {
                        create(screen, item.template());
                        screen.rebuild();
                    } else {
                        createFromList(screen, item.template());
                    }
                }));
            }
        }
        return items;
    }

    MenuItem linked(DevModeScreen screen, NodeKey source, String icon, String label, Template template, boolean reverse) {
        return MenuItem.of(Component.literal(icon + " ").append(DevUi.text(label)), () -> {
            createLinked(screen, source, template, reverse);
            screen.rebuild();
        });
    }

    @Override
    boolean mouseDragged(DevModeScreen screen, double mouseX, double mouseY) {
        if (boxSelecting) {
            boxEndX = worldX(mouseX);
            boxEndY = worldY(mouseY);
            double minX = Math.min(boxStartX, boxEndX);
            double minY = Math.min(boxStartY, boxEndY);
            double maxX = Math.max(boxStartX, boxEndX);
            double maxY = Math.max(boxStartY, boxEndY);
            selection.clear();
            for (Card card : cards) {
                if (card.x() + NODE_W >= minX && card.x() <= maxX && card.y() + NODE_H >= minY && card.y() <= maxY) {
                    selection.add(card.key());
                }
            }
            NodeKey primary = selection.isEmpty() ? null : selection.iterator().next();
            if (primary == null ? selected != null : !primary.equals(selected)) {
                selectFromSet(screen, primary);
            }
            return true;
        }
        if (dragging != null) {
            int[] anchor = dragStart.get(dragging);
            if (anchor != null) {
                // The grabbed card snaps to the grid and the others move with it, each onto the grid too (one placed
                // off it before November 18, 2026 lands on it at the first move).
                int dx = DevUi.snap(worldX(mouseX) - dragOffsetX) - anchor[0];
                int dy = DevUi.snap(worldY(mouseY) - dragOffsetY) - anchor[1];
                dragStart.forEach((key, start) -> positions.put(key, new int[]{DevUi.snap(start[0] + dx), DevUi.snap(start[1] + dy)}));
            }
            rebuildCards();
            return true;
        }
        return pan(mouseX, mouseY);
    }

    @Override
    boolean mouseReleased(DevModeScreen screen, double mouseX, double mouseY) {
        stopPan();
        if (boxSelecting) {
            boxSelecting = false;
            screen.rebuild();
            return true;
        }
        if (linkingFrom != null) {
            Card target = cardAt(worldX(mouseX), worldY(mouseY));
            NodeKey from = linkingFrom;
            linkingFrom = null;
            if (target != null) {
                link(screen, from, target.key());
            }
            return true;
        }
        if (dragging != null) {
            Map<NodeKey, int[]> before = new LinkedHashMap<>();
            Map<NodeKey, int[]> after = new LinkedHashMap<>();
            dragStart.forEach((key, start) -> {
                int[] now = positions.get(key);
                if (now != null && (now[0] != start[0] || now[1] != start[1])) {
                    before.put(key, start.clone());
                    after.put(key, now.clone());
                }
            });
            dragging = null;
            dragStart.clear();
            if (!after.isEmpty()) {
                saveChapter();
                ResourceLocation moved = chapter();
                screen.remember(DevUi.str("history.move", after.size()), () -> moveBack(moved, before), () -> moveBack(moved, after));
            }
            return true;
        }
        return false;
    }

    @Override
    void wheel(DevModeScreen screen, double mouseX, double mouseY, double amount) {
        zoomAt(mouseX, mouseY, amount);
    }

    @Override
    boolean scrollSidePanel(DevModeScreen screen, double mouseX, double mouseY, double amount) {
        return tab == Tab.STORY && panel.scroll(screen, mouseX, mouseY, amount);
    }

    @Override
    void scrollInspector(double amount) {
        inspector.scroll(amount);
    }

    @Override
    boolean animateInspector() {
        return inspector.animateScroll();
    }

    @Override
    boolean keyPressed(DevModeScreen screen, int keyCode) {
        if (keyCode == GLFW.GLFW_KEY_F && Screen.hasControlDown()) {
            screen.openFinder(StoryFinder.Mode.SEARCH);
            return true;
        }
        if ((keyCode == GLFW.GLFW_KEY_DELETE || keyCode == GLFW.GLFW_KEY_BACKSPACE) && (selected != null || !selection.isEmpty())) {
            deleteSelection(screen);
            return true;
        }
        return false;
    }
}
