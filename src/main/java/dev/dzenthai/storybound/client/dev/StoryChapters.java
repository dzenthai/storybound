package dev.dzenthai.storybound.client.dev;

import com.google.gson.JsonObject;
import dev.dzenthai.storybound.core.story.StoryLinks.NodeKey;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * The story editor's chapters ({@link StoryView}): putting nodes in a chapter, making, renaming, opening and
 * deleting chapters, revealing a node, dropping one on the canvas.
 * <p>
 * One layer of the story editor, split by topic, each seeing the ones below it; the rest of the editor uses StoryView:
 * StoryCards &larr; StoryChapters &larr; StoryEditing &larr; StoryDrawing &larr; StoryView.
 */
abstract class StoryChapters extends StoryCards {
    // --- chapters

    void addToChapter(NodeKey key, int x, int y) {
        if (!shows(key)) {
            return;
        }
        ResourceLocation open = chapter();
        JsonObject json = ClientDevStore.parseObject(ClientDevStore.getJson(ClientDevStore.TYPE_CHAPTER, open));
        Chapters.place(open, json, key, x, y);
        chapterNodes.add(key);
        saveChapterJson(open, json);
    }

    /**
     * Writes where the open canvas's cards are now. Places of nodes this canvas does not show (a dialogue in a
     * chapter made before the map showed only quests) stay as they were.
     */
    void saveChapter() {
        ResourceLocation open = chapter();
        JsonObject json = ClientDevStore.parseObject(ClientDevStore.getJson(ClientDevStore.TYPE_CHAPTER, open));
        Map<NodeKey, int[]> placed = Chapters.positions(json);
        if (!UNSORTED.equals(storyChapter) || tab != Tab.STORY) {
            // "Not in a chapter" shows some of the whole story's places: the others stay as they are.
            placed.keySet().removeIf(this::shows);
        }
        for (Card card : cards) {
            placed.put(card.key(), new int[]{card.x(), card.y()});
        }
        Chapters.setPositions(open, json, placed);
        saveChapterJson(open, json);
    }

    private static void saveChapterJson(ResourceLocation id, JsonObject json) {
        String raw = DevUi.GSON.toJson(json);
        ClientDevStore.putLocal(ClientDevStore.TYPE_CHAPTER, id, raw);
        DevRequests.save(ClientDevStore.TYPE_CHAPTER, id, raw, Optional.empty());
    }

    /** A new chapter of the map with the selected quests on it (at their places), opened at once. */
    void createChapter(DevModeScreen screen) {
        ResourceLocation id = Chapters.nextId();
        String number = id.getPath().substring("chapter_".length());
        JsonObject json = new JsonObject();
        ClientDevStore.writeStoryText(json, "title", DevUi.str("chapter.default_title", number),
                ClientDevStore.canonicalKey(ClientDevStore.TYPE_CHAPTER, id, "title"));
        json.addProperty("order", Integer.parseInt(number));
        Map<NodeKey, int[]> placed = new LinkedHashMap<>();
        for (Card card : cards) {
            if (selection.contains(card.key())) {
                placed.put(card.key(), new int[]{card.x(), card.y()});
            }
        }
        Chapters.setPositions(id, json, placed);
        String raw = DevUi.GSON.toJson(json);
        ResourceLocation previous = storyChapter;
        ClientDevStore.putLocal(ClientDevStore.TYPE_CHAPTER, id, raw);
        DevRequests.save(ClientDevStore.TYPE_CHAPTER, id, raw, Optional.empty());
        openChapter(id);
        screen.remember(DevUi.str("history.create_chapter", id.getPath()),
                () -> {
                    storyChapter = previous;
                    DevRequests.delete(ClientDevStore.TYPE_CHAPTER, id);
                },
                () -> {
                    storyChapter = id;
                    DevRequests.restore(ClientDevStore.TYPE_CHAPTER, id, raw, Optional.empty());
                });
        screen.status(DevUi.text("status.chapter_created", DevUi.str("chapter.default_title", number)), DevUi.OK);
    }

    void deleteChapter(DevModeScreen screen, ResourceLocation id) {
        String before = ClientDevStore.getJson(ClientDevStore.TYPE_CHAPTER, id);
        if (before == null) {
            return;
        }
        if (id.equals(storyChapter)) {
            openChapter(Chapters.quests());
        }
        DevRequests.delete(ClientDevStore.TYPE_CHAPTER, id);
        screen.remember(DevUi.str("history.delete_chapter", id.getPath()),
                () -> {
                    storyChapter = id;
                    DevRequests.restore(ClientDevStore.TYPE_CHAPTER, id, before, Optional.empty());
                },
                () -> {
                    if (id.equals(storyChapter)) {
                        storyChapter = Chapters.quests();
                    }
                    DevRequests.delete(ClientDevStore.TYPE_CHAPTER, id);
                });
        rebuildCards();
        screen.status(DevUi.text("status.chapter_deleted", id.getPath()), DevUi.BAD);
    }

    void openChapter(ResourceLocation id) {
        storyChapter = id;
        loadChapter();
        rebuildCards();
    }

    /** Puts the quest on the chapter, or takes it off; one undoable step. */
    void setInChapter(DevModeScreen screen, ResourceLocation chapterId, NodeKey quest, boolean in) {
        String before = ClientDevStore.getJson(ClientDevStore.TYPE_CHAPTER, chapterId);
        if (before == null) {
            return;
        }
        JsonObject json = ClientDevStore.parseObject(before);
        if (in) {
            int[] pos = positions.getOrDefault(quest, new int[]{DevUi.GRID, DevUi.GRID});
            Chapters.place(chapterId, json, quest, pos[0], pos[1]);
        } else {
            Map<NodeKey, int[]> placed = Chapters.positions(json);
            placed.remove(quest);
            Chapters.setPositions(chapterId, json, placed);
        }
        String after = DevUi.GSON.toJson(json);
        saveChapterJson(chapterId, json);
        if (chapterId.equals(storyChapter)) {
            loadChapter();
            rebuildCards();
        }
        screen.remember(DevUi.str("history.chapter_nodes", chapterName(chapterId)),
                () -> DevRequests.restore(ClientDevStore.TYPE_CHAPTER, chapterId, before, Optional.empty()),
                () -> DevRequests.restore(ClientDevStore.TYPE_CHAPTER, chapterId, after, Optional.empty()));
        screen.status(DevUi.text(in ? "status.to_chapter" : "status.out_of_chapter", title(quest), chapterName(chapterId)), DevUi.OK);
    }

    static String chapterName(ResourceLocation id) {
        return ClientDevStore.readStoryText(ClientDevStore.parseObject(ClientDevStore.getJson(ClientDevStore.TYPE_CHAPTER, id)), "title", id.getPath());
    }

    /** Selects the chapter itself, so the inspector edits its title. */
    /**
     * The chapter's icon from the strip's menu: every item, "the title's first letter" first (no icon); the pick goes
     * into the chapter's {@code icon} at once, and can be undone.
     */
    void pickChapterIcon(DevModeScreen screen, ResourceLocation id) {
        String before = ClientDevStore.getJson(ClientDevStore.TYPE_CHAPTER, id);
        if (before == null) {
            return;
        }
        screen.catalog().openList(DevUi.text("chapter.icon"), StoryInspector.iconEntries(DevUi.str("chapter.icon_letter"), ItemStack.EMPTY),
                (kind, entry) -> {
                    String now = ClientDevStore.getJson(ClientDevStore.TYPE_CHAPTER, id);
                    if (now == null) {
                        return;
                    }
                    JsonObject json = ClientDevStore.parseObject(now);
                    if (entry.id().isEmpty()) {
                        json.remove("icon");
                    } else {
                        json.addProperty("icon", entry.id());
                    }
                    String after = DevUi.GSON.toJson(json);
                    NodeKey key = new NodeKey(ClientDevStore.TYPE_CHAPTER, id);
                    restore(key, after);
                    screen.remember(DevUi.str("history.chapter_icon"), () -> restore(key, now), () -> restore(key, after));
                    screen.rebuild();
                });
    }

    void renameChapter(DevModeScreen screen, ResourceLocation id) {
        openChapter(id);
        select(screen, new NodeKey(ClientDevStore.TYPE_CHAPTER, id));
        screen.rebuild();
    }

    // --- tabs

    void openTab(DevModeScreen screen, Tab next) {
        // Each canvas keeps where it was looked at.
        views.put(tab, new double[]{panX, panY, zoom});
        double[] view = views.get(next);
        if (view != null) {
            panX = view[0];
            panY = view[1];
            setZoom((float) view[2]);
        } else {
            // Not looked at yet: its compass rose in the middle, at the zoom it starts with.
            setZoom(1.0f);
            homePending = true;
        }
        tab = next;
        loadChapter();
        rebuildCards();
        screen.rebuild();
    }

    /** Opens the tab with the node in the middle of its canvas, selected; on the map, in "All quests". */
    void reveal(DevModeScreen screen, Tab target, NodeKey key) {
        if (target == Tab.STORY && !chapterNodes.contains(key)) {
            storyChapter = Chapters.quests();
        }
        openTab(screen, target);
        select(screen, key);
        for (Card card : cards) {
            if (card.key().equals(key)) {
                centerOn(screen, card.x() + NODE_W / 2.0, card.y() + NODE_H / 2.0);
            }
        }
        screen.rebuild();
    }

    /**
     * Shows a node wherever it is (a search row, a problem): a quest or a story event on the map, in a chapter that has
     * it, centred and selected; anything else selected in the inspector, the map centred on a node it belongs to.
     */
    void revealAnywhere(DevModeScreen screen, NodeKey key) {
        if (tab == Tab.ALL) {
            reveal(screen, Tab.ALL, key);
            return;
        }
        if (spine(key) && !chapterNodes.contains(key) && !Chapters.quests().equals(storyChapter)) {
            ResourceLocation home = unsorted().contains(key) ? UNSORTED : Chapters.quests();
            for (Map.Entry<ResourceLocation, JsonObject> entry : Chapters.sorted()) {
                if (Chapters.positions(entry.getValue()).containsKey(key)) {
                    home = entry.getKey();
                    break;
                }
            }
            openChapter(home);
        }
        select(screen, key);
        for (Card card : cards) {
            if (card.key().equals(key) || card.own().contains(key)) {
                centerOn(screen, card.x() + NODE_W / 2.0, card.y() + NODE_H / 2.0);
                break;
            }
        }
        screen.rebuild();
    }

    /**
     * A search row let go on the canvas: a quest or a story event goes on the open chapter there, or moves there when
     * the map shows it already; anything else is a badge, never a node of the map, and is only shown.
     */
    void dropOnCanvas(DevModeScreen screen, NodeKey key, double mouseX, double mouseY) {
        if (tab != Tab.STORY || !spine(key)) {
            screen.status(DevUi.text("status.drop_not_spine"), DevUi.WARN);
            return;
        }
        int x = DevUi.snap(worldX(mouseX) - NODE_W / 2.0);
        int y = DevUi.snap(worldY(mouseY) - NODE_H / 2.0);
        boolean realChapter = !Chapters.hidden(storyChapter) && !UNSORTED.equals(storyChapter);
        if (realChapter && !chapterNodes.contains(key)) {
            positions.put(key, new int[]{x, y});
            setInChapter(screen, storyChapter, key, true);
        } else if (cards.stream().anyMatch(card -> card.key().equals(key))) {
            int[] before = positions.getOrDefault(key, new int[]{x, y}).clone();
            positions.put(key, new int[]{x, y});
            rebuildCards();
            saveChapter();
            ResourceLocation moved = chapter();
            screen.remember(DevUi.str("history.move", 1), () -> moveBack(moved, Map.of(key, before)), () -> moveBack(moved, Map.of(key, new int[]{x, y})));
        } else {
            screen.status(DevUi.text("status.drop_in_chapter", title(key)), DevUi.WARN);
            return;
        }
        select(screen, key);
        rebuildCards();
        screen.rebuild();
    }

    /** Puts the cards where they were on that chapter (undo and redo), even when another chapter is open now. */
    void moveBack(ResourceLocation chapterId, Map<NodeKey, int[]> places) {
        JsonObject json = ClientDevStore.parseObject(ClientDevStore.getJson(ClientDevStore.TYPE_CHAPTER, chapterId));
        Map<NodeKey, int[]> saved = Chapters.positions(json);
        places.forEach((key, pos) -> saved.put(key, pos.clone()));
        Chapters.setPositions(chapterId, json, saved);
        saveChapterJson(chapterId, json);
        if (chapterId.equals(chapter())) {
            loadChapter();
            rebuildCards();
        }
    }
}
