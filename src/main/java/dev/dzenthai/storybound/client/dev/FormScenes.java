package dev.dzenthai.storybound.client.dev;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.dzenthai.storybound.client.dev.Schemas.Node;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * A scene's own fields, asked as the author asks them rather than as the JSON writes them.
 * <ul>
 * <li>What a shot is placed against: "Placed against: the player / an NPC / an entity / a structure / a waypoint ▸",
 * then that one's own field with its picker (the story's NPCs, the structures, a selector's page, the waypoints), as a
 * waypoint's target is asked. Where the camera looks, the same way: at the anchor, the way it flies, the player, or
 * another anchor asked as above.</li>
 * <li>What a shot is: "A camera flight / A talk ▸"; a flight shows its camera, what happens and the transition, a talk
 * its dialogue.</li>
 * <li>What happens in a shot: "What happens: a chapter title / a caption / a line / a sound / music / a fade / actions ▸",
 * then that one's fields only.</li>
 * </ul>
 * What a switch took away comes back on switching to it again, while the node is open.
 */
abstract class FormScenes extends FormStory {
    private static final List<String> ANCHOR_KINDS = List.of("player", "npc", "entity", "structure", "waypoint");
    private static final List<String> LOOK_KINDS = List.of("anchor", "path", "player", "other");
    static final List<String> CUE_KINDS = List.of("title", "caption", "line", "sound", "music", "fade", "actions");
    /** In a page's skip set: the scene's own page was drawn already, its fields follow as usual. */
    static final String SCENE_PAGE = "\u0000scene";

    FormScenes(Host host) {
        super(host);
    }

    static boolean isSceneShot(Node schema) {
        Map<String, Node> own = Schemas.properties(schema);
        return own.containsKey("camera") && own.containsKey("dialogue") && own.containsKey("transition");
    }

    static boolean isSceneCue(Node schema) {
        Map<String, Node> own = Schemas.properties(schema);
        return own.containsKey("at") && own.containsKey("caption") && own.containsKey("line");
    }

    static boolean isSceneAnchor(String name, Node owner) {
        return name.equals("anchor") && isSceneShot(owner);
    }

    static boolean isSceneLook(String name, Node owner) {
        Map<String, Node> own = Schemas.properties(owner);
        return name.equals("look") && own.containsKey("from") && own.containsKey("speed");
    }

    private static String anchorKind(@Nullable JsonElement value) {
        if (value instanceof JsonObject object) {
            for (String kind : ANCHOR_KINDS) {
                if (object.has(kind)) {
                    return kind;
                }
            }
        }
        return "player";
    }

    /** The object form of an anchor: the variant with fields, its own or behind a reference. */
    private static @Nullable Node objectVariant(Node node) {
        for (Node variant : Schemas.variants(node)) {
            if (!Schemas.properties(variant).isEmpty()) {
                return variant;
            }
            Node nested = Schemas.variants(variant).isEmpty() ? null : objectVariant(variant);
            if (nested != null) {
                return nested;
            }
        }
        return Schemas.properties(node).isEmpty() ? null : node;
    }

    /** A shot's anchor, or (without the player) the other anchor a camera looks at. */
    void sceneAnchorField(List<Object> path, Node node, boolean withPlayer, String label) {
        String kind = anchorKind(get(path));
        if (!withPlayer && kind.equals("player")) {
            kind = "npc";
        }
        List<String> kinds = withPlayer ? ANCHOR_KINDS : ANCHOR_KINDS.subList(1, ANCHOR_KINDS.size());
        String current = kind;
        switchRow(DevUi.text(label), DevUi.text("scene.anchor." + kind),
                Component.empty().append(DevUi.text("scene.anchor." + kind + ".tooltip")).append(" ").append(DevUi.text("form.click_to_switch")),
                () -> switchAnchor(path, current, kinds.get((kinds.indexOf(current) + 1) % kinds.size())));
        if (kind.equals("player")) {
            return;
        }
        Node object = objectVariant(node);
        if (object != null) {
            Set<String> skip = new HashSet<>(ANCHOR_KINDS);
            skip.remove(kind);
            objectPage(path, object, skip, null);
        }
        if (kind.equals("npc") || kind.equals("structure")) {
            text(DevUi.text("scene.anchor." + kind + ".note"), DevUi.MUTED);
        }
    }

    private void switchAnchor(List<Object> path, String from, String to) {
        JsonElement now = get(path);
        if (now != null) {
            switchedAway.put(path + "#anchor:" + from, now.deepCopy());
        }
        JsonElement back = switchedAway.remove(path + "#anchor:" + to);
        JsonElement next = back;
        if (next == null) {
            if (to.equals("player")) {
                next = new JsonPrimitive("player");
            } else {
                JsonObject fresh = new JsonObject();
                fresh.add(to, to.equals("entity") ? new JsonObject() : new JsonPrimitive(""));
                next = fresh;
            }
        }
        put(ensureParent(path), path, path.size() - 1, next);
        host.changed();
    }

    /** Where a shot's camera looks: at its anchor, the way it flies, the player, or another anchor. */
    void sceneLookField(List<Object> path, Node node) {
        JsonElement value = get(path);
        String kind = value == null ? "anchor" : value instanceof JsonPrimitive text ? text.getAsString() : "other";
        if (!LOOK_KINDS.contains(kind)) {
            kind = "anchor";
        }
        String current = kind;
        switchRow(DevUi.text("scene.look"), DevUi.text("scene.look." + kind),
                Component.empty().append(DevUi.text("scene.look." + kind + ".tooltip")).append(" ").append(DevUi.text("form.click_to_switch")),
                () -> {
                    String next = LOOK_KINDS.get((LOOK_KINDS.indexOf(current) + 1) % LOOK_KINDS.size());
                    JsonElement now = get(path);
                    if (now != null) {
                        switchedAway.put(path + "#look:" + current, now.deepCopy());
                    }
                    JsonElement back = switchedAway.remove(path + "#look:" + next);
                    if (back == null) {
                        if (next.equals("other")) {
                            JsonObject fresh = new JsonObject();
                            fresh.addProperty("npc", "");
                            back = fresh;
                        } else {
                            back = new JsonPrimitive(next);
                        }
                    }
                    put(ensureParent(path), path, path.size() - 1, back);
                    host.changed();
                });
        if (kind.equals("other")) {
            Node anchor = Schemas.variants(node).stream().filter(variant -> !variant.json().has("enum")).findFirst().orElse(null);
            if (anchor != null) {
                sceneAnchorField(path, anchor, false, "scene.look.target");
            }
        }
    }

    /** A shot: a camera flight (its camera, what happens, the transition) or a talk (its dialogue). */
    void sceneShotPage(List<Object> path, Node schema, Set<String> skip) {
        JsonObject object = objectAt(path);
        boolean talk = object != null && object.has("dialogue");
        switchRow(DevUi.text("scene.shot_kind"), DevUi.text(talk ? "scene.shot_kind.talk" : "scene.shot_kind.flight"),
                Component.empty().append(DevUi.text("scene.shot_kind.tooltip")).append(" ").append(DevUi.text("form.click_to_switch")),
                () -> switchShotKind(path, talk));
        Set<String> hidden = new HashSet<>(skip);
        hidden.add(SCENE_PAGE);
        if (talk) {
            hidden.addAll(Set.of("camera", "during", "transition"));
        } else {
            hidden.add("dialogue");
        }
        objectPage(path, schema, hidden, null);
    }

    private void switchShotKind(List<Object> path, boolean talk) {
        JsonObject object = ensureObject(path);
        String key = path + "#shot:";
        if (talk) {
            switchedAway.put(key + "talk", object.remove("dialogue"));
            JsonElement camera = switchedAway.remove(key + "camera");
            if (camera == null) {
                JsonObject fresh = new JsonObject();
                fresh.add("from", vec(-4, 2.5, -4));
                fresh.add("to", vec(4, 2.5, -4));
                fresh.addProperty("duration", 5);
                camera = fresh;
            }
            object.add("camera", camera);
            JsonElement during = switchedAway.remove(key + "during");
            if (during != null) {
                object.add("during", during);
            }
        } else {
            JsonElement camera = object.remove("camera");
            if (camera != null) {
                switchedAway.put(key + "camera", camera);
            }
            JsonElement during = object.remove("during");
            if (during != null) {
                switchedAway.put(key + "during", during);
            }
            object.remove("transition");
            JsonElement dialogue = switchedAway.remove(key + "talk");
            object.add("dialogue", dialogue != null ? dialogue : new JsonPrimitive(""));
        }
        host.changed();
    }

    private static JsonArray vec(double x, double y, double z) {
        JsonArray array = new JsonArray();
        array.add(x);
        array.add(y);
        array.add(z);
        return array;
    }

    /** One thing in a shot: what it is, then only that one's fields, and its second. */
    void sceneCuePage(List<Object> path, Node schema, Set<String> skip) {
        JsonObject object = objectAt(path);
        String kind = CUE_KINDS.stream().filter(candidate -> object != null && object.has(candidate)).findFirst().orElse("none");
        switchRow(DevUi.text("scene.cue"), DevUi.text("scene.cue." + kind),
                Component.empty().append(DevUi.text("scene.cue.tooltip")).append(" ").append(DevUi.text("form.click_to_switch")),
                () -> switchCue(path, kind, kind.equals("none") ? CUE_KINDS.getFirst() : CUE_KINDS.get((CUE_KINDS.indexOf(kind) + 1) % CUE_KINDS.size())));
        Set<String> hidden = new HashSet<>(skip);
        hidden.add(SCENE_PAGE);
        CUE_KINDS.stream().filter(candidate -> !candidate.equals(kind)).forEach(hidden::add);
        objectPage(path, schema, hidden, null);
    }

    private void switchCue(List<Object> path, String from, String to) {
        JsonObject object = ensureObject(path);
        String key = path + "#cue:";
        JsonElement now = object.remove(from);
        if (now != null) {
            switchedAway.put(key + from, now);
        }
        JsonElement back = switchedAway.remove(key + to);
        if (back == null) {
            back = switch (to) {
                case "music", "actions" -> to.equals("music") ? new JsonPrimitive("") : new JsonArray();
                case "sound" -> {
                    JsonObject sound = new JsonObject();
                    sound.addProperty("sound", "");
                    yield sound;
                }
                case "fade" -> {
                    JsonObject fade = new JsonObject();
                    fade.addProperty("in", 0.5);
                    fade.addProperty("stay", 1);
                    fade.addProperty("out", 0.5);
                    yield fade;
                }
                default -> {
                    JsonObject text = new JsonObject();
                    text.addProperty("text", "");
                    yield text;
                }
            };
        }
        object.add(to, back);
        host.changed();
    }
}
