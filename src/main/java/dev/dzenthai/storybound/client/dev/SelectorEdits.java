package dev.dzenthai.storybound.client.dev;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.jetbrains.annotations.Nullable;

/**
 * The editor's changes to an entity selector and to an NPC's entity, as JSON (November 9, 2026), apart from the screen
 * so each is tested: a mob's variants (one object, or a list of them: a man smith and a woman farmer of one NPC),
 * values of its {@code data}, the rule by hand / dynamic. Each is one change the form makes on a click; none is made
 * on showing a node, which leaves its file as it is.
 */
final class SelectorEdits {
    /** What makes an entity one "by hand": the parts naming particular entities. */
    static final List<String> HAND_PARTS = List.of("tag", "name", "uuid");

    private SelectorEdits() {}

    /** The variants an entity holds: one object, or the objects of a list; none for nothing written. */
    static List<JsonObject> variants(@Nullable JsonElement entity) {
        List<JsonObject> out = new ArrayList<>();
        if (entity instanceof JsonObject one) {
            out.add(one);
        } else if (entity instanceof JsonArray list) {
            list.forEach(item -> {
                if (item instanceof JsonObject object) {
                    out.add(object);
                }
            });
        }
        return out;
    }

    /** Whether an entity is by hand: every selector names particular entities (a tag, a name, a uuid). */
    static boolean byHand(@Nullable JsonElement entity) {
        List<JsonObject> variants = variants(entity);
        return !variants.isEmpty() && variants.stream().allMatch(selector -> HAND_PARTS.stream().anyMatch(selector::has));
    }

    /** The entity with a variant added: the one alone, a list of two, or the list one longer. */
    static JsonElement withVariant(@Nullable JsonElement entity, JsonObject variant) {
        if (entity instanceof JsonArray list) {
            JsonArray next = list.deepCopy();
            next.add(variant);
            return next;
        }
        if (entity instanceof JsonObject one && !one.isEmpty()) {
            JsonArray next = new JsonArray();
            next.add(one.deepCopy());
            next.add(variant);
            return next;
        }
        return variant;
    }

    /** The entity without the variant at the index: a list of one written as that one; null when none is left. */
    static @Nullable JsonElement withoutVariant(@Nullable JsonElement entity, int index) {
        if (entity instanceof JsonArray list && index >= 0 && index < list.size()) {
            JsonArray next = list.deepCopy();
            next.remove(index);
            return next.isEmpty() ? null : next.size() == 1 ? next.get(0) : next;
        }
        return entity instanceof JsonObject && index == 0 ? null : entity;
    }

    /** The values a field of {@code data} holds, as written (one, or the items of a list). */
    static List<JsonPrimitive> dataValues(@Nullable JsonObject selector, String path) {
        List<JsonPrimitive> out = new ArrayList<>();
        JsonElement written = selector != null && selector.get("data") instanceof JsonObject data ? data.get(path) : null;
        if (written instanceof JsonPrimitive one) {
            out.add(one);
        } else if (written instanceof JsonArray list) {
            list.forEach(item -> {
                if (item instanceof JsonPrimitive value) {
                    out.add(value);
                }
            });
        }
        return out;
    }

    /**
     * A value of a field taken in, or out when it is in already (the same as the game compares: {@code same}); the
     * field goes when it holds none, {@code data} when it has no field.
     */
    static void toggleData(JsonObject selector, String path, JsonPrimitive value, Predicate<JsonPrimitive> same) {
        List<JsonPrimitive> values = dataValues(selector, path);
        if (!values.removeIf(same)) {
            values.add(value);
        }
        writeData(selector, path, values);
    }

    /** The field taken out of {@code data}: any value. */
    static void clearData(JsonObject selector, String path) {
        writeData(selector, path, List.of());
    }

    private static void writeData(JsonObject selector, String path, List<JsonPrimitive> values) {
        JsonObject data = selector.get("data") instanceof JsonObject written ? written : new JsonObject();
        if (values.isEmpty()) {
            data.remove(path);
        } else if (values.size() == 1) {
            data.add(path, values.getFirst());
        } else {
            JsonArray list = new JsonArray();
            values.forEach(list::add);
            data.add(path, list);
        }
        if (data.isEmpty()) {
            selector.remove("data");
        } else {
            selector.add("data", data);
        }
    }

    /**
     * The entity after "Rule" switched (by hand ↔ dynamic): the one the other rule had while this node is open
     * ({@code back}), else by hand an entity with the node's tag (and the kind of the first variant), dynamic one of the
     * kind the entity by hand had (or nothing yet). The rules ask different things, so nothing is mixed: a filter would
     * narrow an NPC by hand unseen, a tag would make a dynamic one by hand.
     */
    static JsonElement switchedRule(@Nullable JsonElement entity, @Nullable JsonElement back, String tag) {
        if (back != null) {
            return back.deepCopy();
        }
        boolean toHand = !byHand(entity);
        JsonObject next = new JsonObject();
        List<JsonObject> variants = variants(entity);
        JsonElement type = variants.isEmpty() ? null : variants.getFirst().get("type");
        if (type != null) {
            next.add("type", type.deepCopy());
        }
        if (toHand) {
            next.addProperty("tag", tag);
        }
        return next;
    }
}
