package dev.dzenthai.storybound.client.dev;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * A dialogue's JSON as a graph for the dev mode: its nodes and the arrows between them, new nodes and links, keys
 * for their texts and an automatic layout. Node positions are not part of the dialogue (the game does not keep them);
 * they live in the dialogue's layout, see {@link DialogueView}.
 */
final class DialogueGraph {
    static final String END = "@end";
    static final String EXIT = "@exit";
    static final int LAYER_GAP = 220;
    static final int ROW_GAP = 76;
    static final int MARGIN = 40;

    /** What an arrow means; the view colours and labels it by this. */
    enum EdgeKind { NEXT, ACTION, OPTION, TIMEOUT, THEN, ELSE, RANDOM, RETURN, JUMP }

    /** @param label the option's text or the weight; empty for plain arrows */
    record Edge(String from, String to, EdgeKind kind, String label) {}

    private DialogueGraph() {}

    /** The dialogue's {@code nodes}, attached to it. */
    static JsonObject nodes(JsonObject dialogue) {
        if (!dialogue.has("nodes") || !dialogue.get("nodes").isJsonObject()) {
            dialogue.add("nodes", new JsonObject());
        }
        return dialogue.getAsJsonObject("nodes");
    }

    static String start(JsonObject dialogue) {
        return dialogue.has("start") ? dialogue.get("start").getAsString() : "";
    }

    static String type(JsonObject node) {
        return node.has("type") ? node.get("type").getAsString() : "line";
    }

    static String next(JsonObject node) {
        return node.has("next") ? node.get("next").getAsString() : END;
    }

    static @Nullable JsonObject node(JsonObject dialogue, String id) {
        JsonObject nodes = nodes(dialogue);
        return nodes.has(id) && nodes.get(id).isJsonObject() ? nodes.getAsJsonObject(id) : null;
    }

    static String lineKey(ResourceLocation dialogue, String node) {
        return ClientDevStore.canonicalKey(ClientDevStore.TYPE_DIALOGUE, dialogue, "node." + node + ".text");
    }

    static String optionKey(ResourceLocation dialogue, String node, int option) {
        return ClientDevStore.canonicalKey(ClientDevStore.TYPE_DIALOGUE, dialogue, "node." + node + ".opt." + option + ".text");
    }

    /** The options of a choice (or a random node), attached to it: call it only for such nodes. */
    static JsonArray options(JsonObject node) {
        return StoryEdits.array(node, "options");
    }

    /** How many options the node has; 0 for nodes without options. Changes nothing. */
    static int optionCount(@Nullable JsonObject node) {
        return node != null && node.has("options") && node.get("options").isJsonArray() ? node.getAsJsonArray("options").size() : 0;
    }

    /**
     * Gives every line and option its own translation key. Texts without a key, with a key another text uses too,
     * or with a key from the prototype's shared-key bug get the canonical key; the text in the active language moves
     * with them.
     */
    static void sanitizeKeys(ResourceLocation dialogueId, JsonObject dialogue) {
        Set<String> seen = new HashSet<>();
        for (Map.Entry<String, JsonElement> entry : nodes(dialogue).entrySet()) {
            if (!entry.getValue().isJsonObject()) {
                continue;
            }
            JsonObject node = entry.getValue().getAsJsonObject();
            switch (type(node)) {
                case "line" -> ownKey(node, lineKey(dialogueId, entry.getKey()), seen);
                case "choice" -> {
                    JsonArray options = options(node);
                    for (int i = 0; i < options.size(); i++) {
                        if (options.get(i).isJsonObject()) {
                            ownKey(options.get(i).getAsJsonObject(), optionKey(dialogueId, entry.getKey(), i), seen);
                        }
                    }
                }
                default -> {}
            }
        }
    }

    /** Gives the text the canonical key unless it already has a key of its own; returns the key it ends up with. */
    static String ownKey(JsonObject owner, String canonical, Set<String> seen) {
        String key = ClientDevStore.getKey(owner, "text");
        if (key == null || key.isBlank() || ClientDevStore.isBuggySharedKey(key) || seen.contains(key)) {
            String text = ClientDevStore.readStoryText(owner, "text", "", ClientDevStore.activeLanguage());
            owner.addProperty("text", canonical);
            if (!text.isBlank()) {
                ClientDevStore.writeStoryText(owner, "text", text, canonical);
            }
            key = canonical;
        }
        seen.add(key);
        return key;
    }

    /** Every node id an arrow of this node leads to (including {@code @end} and other dialogues' ids). */
    static void targets(JsonObject node, Consumer<String> sink) {
        for (Edge edge : edgesOf("", node, "")) {
            sink.accept(edge.to());
        }
    }

    /** The arrows of every node; option arrows are labelled with the option's text in that language. */
    static List<Edge> edges(JsonObject dialogue, String language) {
        List<Edge> edges = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : nodes(dialogue).entrySet()) {
            if (entry.getValue().isJsonObject()) {
                edges.addAll(edgesOf(entry.getKey(), entry.getValue().getAsJsonObject(), language));
            }
        }
        return edges;
    }

    private static List<Edge> edgesOf(String from, JsonObject node, String language) {
        List<Edge> edges = new ArrayList<>();
        switch (type(node)) {
            case "line" -> edges.add(new Edge(from, next(node), EdgeKind.NEXT, ""));
            case "action" -> edges.add(new Edge(from, next(node), EdgeKind.ACTION, ""));
            case "call" -> edges.add(new Edge(from, next(node), EdgeKind.RETURN, ""));
            case "jump" -> {
                if (node.has("node")) {
                    edges.add(new Edge(from, node.get("node").getAsString(), EdgeKind.JUMP, ""));
                }
            }
            case "condition" -> {
                edges.add(new Edge(from, node.has("then") ? node.get("then").getAsString() : END, EdgeKind.THEN, ""));
                edges.add(new Edge(from, node.has("else") ? node.get("else").getAsString() : END, EdgeKind.ELSE, ""));
            }
            case "choice" -> {
                int index = 0;
                for (JsonElement element : options(node)) {
                    if (element.isJsonObject()) {
                        JsonObject option = element.getAsJsonObject();
                        String text = language.isEmpty() ? "" : ClientDevStore.readStoryText(option, "text", "#" + (index + 1), language);
                        edges.add(new Edge(from, next(option), EdgeKind.OPTION, text.length() > 16 ? text.substring(0, 14) + ".." : text));
                        index++;
                    }
                }
                if (node.has("timeout") && node.get("timeout").isJsonObject()) {
                    edges.add(new Edge(from, next(node.getAsJsonObject("timeout")), EdgeKind.TIMEOUT, ""));
                }
            }
            case "random" -> {
                for (JsonElement element : options(node)) {
                    if (element.isJsonObject()) {
                        JsonObject option = element.getAsJsonObject();
                        int weight = option.has("weight") ? option.get("weight").getAsInt() : 1;
                        edges.add(new Edge(from, next(option), EdgeKind.RANDOM, "w=" + weight));
                    }
                }
            }
            default -> {}
        }
        return edges;
    }

    /**
     * Layers by distance from the start node (breadth first), one column per layer; nodes the start cannot reach
     * get columns of their own from the left. A dialogue without a start gets its first node as the start.
     */
    static Map<String, int[]> autoLayout(JsonObject dialogue) {
        JsonObject nodes = nodes(dialogue);
        Map<String, int[]> positions = new LinkedHashMap<>();
        if (nodes.isEmpty()) {
            return positions;
        }
        String start = start(dialogue);
        if (start.isEmpty()) {
            start = nodes.keySet().iterator().next();
            dialogue.addProperty("start", start);
        }
        Map<String, Integer> layers = new LinkedHashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        if (nodes.has(start)) {
            layers.put(start, 0);
            queue.add(start);
        }
        while (!queue.isEmpty()) {
            String current = queue.poll();
            JsonObject node = node(dialogue, current);
            if (node == null) {
                continue;
            }
            int layer = layers.get(current);
            targets(node, target -> {
                if (nodes.has(target) && !layers.containsKey(target)) {
                    layers.put(target, layer + 1);
                    queue.add(target);
                }
            });
        }
        int unreached = 0;
        for (String id : nodes.keySet()) {
            if (!layers.containsKey(id)) {
                layers.put(id, unreached++);
            }
        }
        Map<Integer, Integer> rows = new HashMap<>();
        layers.forEach((id, layer) -> {
            int row = rows.merge(layer, 1, Integer::sum) - 1;
            positions.put(id, new int[]{MARGIN + layer * LAYER_GAP, MARGIN + row * ROW_GAP});
        });
        return positions;
    }

    /**
     * Adds a node with a free id ({@code line_1}, {@code choice_2}...) and default text; the first node of an empty
     * dialogue becomes its start.
     *
     * @param speaker for lines; {@code npc} when null
     * @return the new node's id
     */
    static String createNode(JsonObject dialogue, ResourceLocation dialogueId, String type, @Nullable String speaker, String lineText, String optionText) {
        JsonObject nodes = nodes(dialogue);
        int number = 1;
        while (nodes.has(type + "_" + number)) {
            number++;
        }
        String id = type + "_" + number;
        JsonObject node = new JsonObject();
        node.addProperty("type", type);
        switch (type) {
            case "line" -> {
                node.addProperty("speaker", speaker != null ? speaker : "npc");
                ClientDevStore.writeStoryText(node, "text", lineText, lineKey(dialogueId, id));
                node.addProperty("next", END);
            }
            case "choice" -> {
                JsonObject option = new JsonObject();
                ClientDevStore.writeStoryText(option, "text", optionText, optionKey(dialogueId, id, 0));
                option.addProperty("next", END);
                options(node).add(option);
            }
            case "condition" -> {
                // Always true until the author writes the condition: a dialogue never fails to load over a new node.
                node.add("conditions", new JsonArray());
                node.addProperty("then", END);
                node.addProperty("else", END);
            }
            case "action" -> {
                node.add("actions", new JsonArray());
                node.addProperty("next", END);
            }
            default -> {}
        }
        nodes.add(id, node);
        if (start(dialogue).isBlank()) {
            dialogue.addProperty("start", id);
        }
        return id;
    }

    /**
     * Points the node at another: a line, action or call continues there, a jump goes there, a choice's first free
     * option (one that ends the dialogue) or else a new option leads there, a condition's {@code then} (or, when
     * that is taken, {@code else}) does.
     */
    static void connect(JsonObject dialogue, ResourceLocation dialogueId, String from, String to, String optionText) {
        JsonObject node = node(dialogue, from);
        if (node == null) {
            return;
        }
        switch (type(node)) {
            case "line", "action", "call" -> node.addProperty("next", to);
            case "jump" -> node.addProperty("node", to);
            case "condition" -> node.addProperty(END.equals(node.has("then") ? node.get("then").getAsString() : END) ? "then" : "else", to);
            case "choice" -> {
                JsonArray options = options(node);
                for (JsonElement element : options) {
                    if (element.isJsonObject()) {
                        JsonObject option = element.getAsJsonObject();
                        String next = next(option);
                        if (END.equals(next) || next.isBlank()) {
                            option.addProperty("next", to);
                            return;
                        }
                    }
                }
                JsonObject option = new JsonObject();
                ClientDevStore.writeStoryText(option, "text", optionText, optionKey(dialogueId, from, options.size()));
                option.addProperty("next", to);
                options.add(option);
            }
            default -> {}
        }
    }

    /** Removes the node; when it was the start, the first remaining node becomes the start. */
    static void deleteNode(JsonObject dialogue, String id) {
        JsonObject nodes = nodes(dialogue);
        nodes.remove(id);
        if (id.equals(start(dialogue))) {
            dialogue.addProperty("start", nodes.isEmpty() ? "" : nodes.keySet().iterator().next());
        }
    }

    /** The target after {@code current} among the dialogue's nodes, {@code @end} and {@code @exit}, round and round. */
    static String cycleTarget(JsonObject dialogue, String current) {
        List<String> targets = new ArrayList<>(nodes(dialogue).keySet());
        targets.add(END);
        targets.add(EXIT);
        return targets.get((targets.indexOf(current) + 1) % targets.size());
    }
}
