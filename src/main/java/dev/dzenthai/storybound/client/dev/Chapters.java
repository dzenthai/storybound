package dev.dzenthai.storybound.client.dev;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.client.dev.ClientDevStore.NodeKey;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;

/**
 * Chapters of the story map ({@code config/storybound/chapters/}): their order and where each quest sits. Two hidden
 * chapters keep the places of the views that show everything: the map's "All quests" ({@code storybound:__quests__})
 * and the "All links" canvas ({@code storybound:__overview__}).
 */
final class Chapters {
    static final ResourceLocation OVERVIEW = Storybound.id("__overview__");
    static final ResourceLocation QUESTS = Storybound.id("__quests__");

    private Chapters() {}

    /** Chapters by {@code order}, then by id; without the hidden ones. */
    static List<Map.Entry<ResourceLocation, JsonObject>> sorted() {
        List<Map.Entry<ResourceLocation, JsonObject>> chapters = new ArrayList<>();
        ClientDevStore.byType(ClientDevStore.TYPE_CHAPTER).forEach((id, raw) -> {
            if (!hidden(id)) {
                chapters.add(Map.entry(id, ClientDevStore.parseObject(raw)));
            }
        });
        chapters.sort(Comparator.<Map.Entry<ResourceLocation, JsonObject>>comparingInt(entry -> order(entry.getValue()))
                .thenComparing(entry -> entry.getKey().toString()));
        return chapters;
    }

    /** A view's own places, never listed as a chapter. */
    static boolean hidden(ResourceLocation id) {
        return OVERVIEW.equals(id) || QUESTS.equals(id);
    }

    static int order(JsonObject chapter) {
        return chapter.has("order") ? chapter.get("order").getAsInt() : 0;
    }

    /** Where the chapter places its nodes; nodes with a type or id that does not parse are skipped. */
    static Map<NodeKey, int[]> positions(JsonObject chapter) {
        Map<NodeKey, int[]> positions = new LinkedHashMap<>();
        if (chapter.has("nodes") && chapter.get("nodes").isJsonArray()) {
            for (JsonElement element : chapter.getAsJsonArray("nodes")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject node = element.getAsJsonObject();
                ResourceLocation type = DevUi.normalizeType(node.has("type") ? node.get("type").getAsString() : "quest");
                ResourceLocation id = ResourceLocation.tryParse(node.has("id") ? node.get("id").getAsString() : "");
                if (type != null && id != null) {
                    positions.put(new NodeKey(type, id), new int[]{intOr(node, "x"), intOr(node, "y")});
                }
            }
        }
        return positions;
    }

    private static int intOr(JsonObject node, String field) {
        return node.has(field) ? node.get(field).getAsInt() : 0;
    }

    /** Replaces the chapter's nodes; a chapter without a title gets its id as the title. */
    static void setPositions(ResourceLocation chapterId, JsonObject chapter, Map<NodeKey, int[]> positions) {
        if (!chapter.has("title") && hidden(chapterId)) {
            // Never shown: a literal, so no translation key is made for it.
            JsonObject literal = new JsonObject();
            literal.addProperty("literal", "Overview");
            chapter.add("title", literal);
        } else if (!chapter.has("title")) {
            ClientDevStore.writeStoryText(chapter, "title", chapterId.getPath(), ClientDevStore.canonicalKey(ClientDevStore.TYPE_CHAPTER, chapterId, "title"));
        }
        JsonArray nodes = new JsonArray();
        positions.forEach((key, pos) -> {
            JsonObject node = new JsonObject();
            node.addProperty("type", key.type().getPath());
            node.addProperty("id", key.id().toString());
            node.addProperty("x", pos[0]);
            node.addProperty("y", pos[1]);
            nodes.add(node);
        });
        chapter.add("nodes", nodes);
    }

    /** Puts the node on the chapter at that place, moving it if it was there already. */
    static void place(ResourceLocation chapterId, JsonObject chapter, NodeKey key, int x, int y) {
        Map<NodeKey, int[]> positions = positions(chapter);
        positions.remove(key);
        positions.put(key, new int[]{x, y});
        setPositions(chapterId, chapter, positions);
    }

    /** A free id for a new chapter, after the one with the highest order. */
    static ResourceLocation nextId() {
        int number = sorted().stream().mapToInt(entry -> order(entry.getValue())).max().orElse(0) + 1;
        while (ClientDevStore.byType(ClientDevStore.TYPE_CHAPTER).containsKey(Storybound.id("chapter_" + number))) {
            number++;
        }
        return Storybound.id("chapter_" + number);
    }
}
