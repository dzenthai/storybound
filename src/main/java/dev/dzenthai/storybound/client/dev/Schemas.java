package dev.dzenthai.storybound.client.dev;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.dzenthai.storybound.Storybound;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * The mod's JSON schemas ({@code schemas/}, bundled in the jar as {@code storybound_schemas/}), the source the dev
 * mode builds its forms from: what fields a definition, a condition, an action or a trigger has, of what kind, with
 * which description and default. The subset of JSON Schema the files use is understood: properties, required,
 * $ref (in the file and across files), enum and const, oneOf variants, items, additionalProperties maps.
 */
final class Schemas {
    private static final String FOLDER = "/storybound_schemas/";
    private static final String LOGIC = "logic.schema.json";
    private static final Map<String, JsonObject> FILES = new HashMap<>();

    /** The kind of form field a schema node becomes. */
    enum Kind {
        /** Player-facing text, written in the active language under a translation key. */
        TEXT,
        ID, ID_LIST, STRING, ENUM, BOOLEAN, INTEGER, NUMBER,
        /** Three numbers: a position, with "here". */
        POSITION,
        /** A story value: true/false, a whole number or text. */
        LITERAL,
        OBJECT, LIST, MAP,
        CONDITIONS, ACTIONS, TRIGGER, CONDITION, ACTION,
        /** One of several object shapes, told apart by their required fields. */
        VARIANTS,
        /** Anything else, edited as JSON. */
        RAW
    }

    /** What logic lists hold. */
    enum Logic {
        CONDITION("condition"), ACTION("action"), TRIGGER("trigger");

        final String name;

        Logic(String name) {
            this.name = name;
        }
    }

    /**
     * A schema node in its file.
     *
     * @param ref the definition it was reached through, when that was a {@code $ref} ({@code story_text}...)
     */
    record Node(JsonObject json, String file, @Nullable String ref) {
        String description() {
            return json.has("description") ? json.get("description").getAsString() : "";
        }

        @Nullable JsonElement defaultValue() {
            return json.get("default");
        }
    }

    private Schemas() {}

    private static @Nullable JsonObject file(String name) {
        return FILES.computeIfAbsent(name, n -> {
            try (InputStream in = Schemas.class.getResourceAsStream(FOLDER + n)) {
                if (in == null) {
                    return null;
                }
                return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (IOException | RuntimeException e) {
                Storybound.LOGGER.warn("Storybound: cannot read the schema {}: {}", n, e.getMessage());
                return null;
            }
        });
    }

    /** The schema of a definition type ({@code storybound:quest}...), or null for types without one. */
    static @Nullable Node definition(ResourceLocation type) {
        String name = switch (type.getPath()) {
            case "event" -> "story_event";
            default -> type.getPath();
        };
        JsonObject root = file(name + ".schema.json");
        return root != null ? new Node(root, name + ".schema.json", null) : null;
    }

    /** A shared definition of the logic language: {@code condition}, {@code action}, {@code trigger}... */
    static @Nullable Node logic(String definition) {
        JsonObject root = file(LOGIC);
        if (root == null || !root.getAsJsonObject("definitions").has(definition)) {
            return null;
        }
        return new Node(root.getAsJsonObject("definitions").getAsJsonObject(definition), LOGIC, definition);
    }

    /** A definition of a file ({@code dialogue.schema.json}, {@code node}). */
    static @Nullable Node definition(String file, String definition) {
        JsonObject root = file(file);
        if (root == null || !root.has("definitions") || !root.getAsJsonObject("definitions").has(definition)) {
            return null;
        }
        return new Node(root.getAsJsonObject("definitions").getAsJsonObject(definition), file, definition);
    }

    /** Follows {@code $ref}s; the node keeps the name of the first definition it went through. */
    static Node resolve(Node node) {
        Node current = node;
        String ref = node.ref();
        for (int depth = 0; depth < 16 && current.json().has("$ref"); depth++) {
            String target = current.json().get("$ref").getAsString();
            int hash = target.indexOf('#');
            String file = hash > 0 ? target.substring(0, hash) : hash == 0 ? current.file() : target;
            String pointer = hash >= 0 ? target.substring(hash + 1) : "";
            JsonElement found = file(file);
            for (String part : pointer.split("/")) {
                if (!part.isEmpty() && found != null && found.isJsonObject()) {
                    found = found.getAsJsonObject().get(part);
                }
            }
            if (found == null || !found.isJsonObject()) {
                break;
            }
            if (ref == null) {
                ref = pointer.substring(pointer.lastIndexOf('/') + 1);
            }
            // The field's own description wins over the shared definition's.
            JsonObject merged = found.getAsJsonObject().deepCopy();
            for (String key : List.of("description", "default")) {
                if (current.json().has(key)) {
                    merged.add(key, current.json().get(key));
                }
            }
            current = new Node(merged, file, ref);
        }
        return current.ref() == null && ref != null ? new Node(current.json(), current.file(), ref) : current;
    }

    static Node child(Node parent, JsonElement json) {
        return new Node(json.isJsonObject() ? json.getAsJsonObject() : new JsonObject(), parent.file(), null);
    }

    /** The properties of an object node, in the schema's order. */
    static Map<String, Node> properties(Node node) {
        Node resolved = resolve(node);
        Map<String, Node> properties = new LinkedHashMap<>();
        if (resolved.json().has("properties")) {
            resolved.json().getAsJsonObject("properties").entrySet()
                    .forEach(entry -> properties.put(entry.getKey(), child(resolved, entry.getValue())));
        }
        return properties;
    }

    static Set<String> required(Node node) {
        Set<String> required = new LinkedHashSet<>();
        JsonObject json = resolve(node).json();
        if (json.has("required")) {
            json.getAsJsonArray("required").forEach(element -> required.add(element.getAsString()));
        }
        return required;
    }

    static List<Node> variants(Node node) {
        Node resolved = resolve(node);
        List<Node> variants = new ArrayList<>();
        if (resolved.json().has("oneOf")) {
            resolved.json().getAsJsonArray("oneOf").forEach(variant -> variants.add(resolve(child(resolved, variant))));
        }
        return variants;
    }

    /** The node of a list's items. */
    static @Nullable Node items(Node node) {
        JsonObject json = resolve(node).json();
        return json.has("items") ? child(resolve(node), json.get("items")) : null;
    }

    /** The node of a map's values. */
    static @Nullable Node values(Node node) {
        JsonObject json = resolve(node).json();
        return json.has("additionalProperties") && json.get("additionalProperties").isJsonObject()
                ? child(resolve(node), json.get("additionalProperties")) : null;
    }

    static List<String> enumValues(Node node) {
        JsonObject json = resolve(node).json();
        List<String> values = new ArrayList<>();
        if (json.has("enum")) {
            json.getAsJsonArray("enum").forEach(element -> values.add(element.getAsString()));
        } else if (json.has("const")) {
            values.add(json.get("const").getAsString());
        }
        return values;
    }

    static Kind kind(Node field) {
        Node resolved = resolve(field);
        if (resolved.ref() != null) {
            switch (resolved.ref()) {
                case "story_text" -> {
                    return Kind.TEXT;
                }
                case "id", "structure" -> {
                    return Kind.ID;
                }
                case "id_list" -> {
                    return Kind.ID_LIST;
                }
                case "conditions" -> {
                    return Kind.CONDITIONS;
                }
                case "actions" -> {
                    return Kind.ACTIONS;
                }
                case "trigger" -> {
                    return Kind.TRIGGER;
                }
                case "condition" -> {
                    return Kind.CONDITION;
                }
                case "action" -> {
                    return Kind.ACTION;
                }
                case "literal" -> {
                    return Kind.LITERAL;
                }
                default -> {}
            }
        }
        JsonObject json = resolved.json();
        if (json.has("enum") || json.has("const")) {
            return Kind.ENUM;
        }
        String type = json.has("type") && json.get("type").isJsonPrimitive() ? json.get("type").getAsString() : "";
        switch (type) {
            case "boolean" -> {
                return Kind.BOOLEAN;
            }
            case "integer" -> {
                return Kind.INTEGER;
            }
            case "number" -> {
                return Kind.NUMBER;
            }
            case "string" -> {
                return Kind.STRING;
            }
            case "array" -> {
                Node items = items(resolved);
                String itemType = items != null && resolve(items).json().has("type") ? resolve(items).json().get("type").getAsString() : "";
                boolean triple = json.has("minItems") && json.has("maxItems") && json.get("minItems").getAsInt() == 3 && json.get("maxItems").getAsInt() == 3;
                return triple && (itemType.equals("number") || itemType.equals("integer")) ? Kind.POSITION : Kind.LIST;
            }
            default -> {}
        }
        if (json.has("properties")) {
            return Kind.OBJECT;
        }
        if (json.has("additionalProperties") && json.get("additionalProperties").isJsonObject()) {
            return Kind.MAP;
        }
        if (json.has("oneOf") && variants(resolved).stream().allMatch(variant -> variant.json().has("properties"))) {
            return Kind.VARIANTS;
        }
        return Kind.RAW;
    }

    /** The logic types the schema knows, as short ids for ours ({@code flag}) and full ids for others. */
    static List<String> logicTypes(Logic logic) {
        List<String> types = new ArrayList<>();
        Node node = logic(logic.name);
        if (node != null) {
            for (Node variant : variants(node)) {
                Node type = properties(variant).get("type");
                if (type != null) {
                    for (String value : enumValues(type)) {
                        if (!value.contains(":")) {
                            types.add(value);
                        }
                    }
                }
            }
        }
        return types;
    }

    /** The variant of a logic object or a dialogue node for its {@code type}; null for a type the schema does not know. */
    static @Nullable Node typed(Node node, String type) {
        String shortType = DevUi.shortId(type);
        for (Node variant : variants(node)) {
            Node typeField = properties(variant).get("type");
            if (typeField != null && (enumValues(typeField).contains(type) || enumValues(typeField).contains(shortType))) {
                return variant;
            }
        }
        return null;
    }

    /** For a VARIANTS node: the variant whose required fields the value has, else the first. */
    static int variantOf(Node node, @Nullable JsonElement value) {
        List<Node> variants = variants(node);
        if (value != null && value.isJsonObject()) {
            for (int i = 0; i < variants.size(); i++) {
                if (!required(variants.get(i)).isEmpty() && value.getAsJsonObject().keySet().containsAll(required(variants.get(i)))) {
                    return i;
                }
            }
        }
        return 0;
    }

    /** An empty value of the variant: its required fields with their defaults or blanks. */
    static JsonObject skeleton(Node variant) {
        JsonObject object = new JsonObject();
        Map<String, Node> properties = properties(variant);
        for (String field : required(variant)) {
            Node property = properties.get(field);
            JsonElement value = property != null ? property.defaultValue() : null;
            if (value == null && property != null) {
                value = switch (kind(property)) {
                    case POSITION -> {
                        JsonArray zero = new JsonArray();
                        zero.add(0);
                        zero.add(64);
                        zero.add(0);
                        yield zero;
                    }
                    case LIST, CONDITIONS, ACTIONS -> new JsonArray();
                    case OBJECT, MAP -> new JsonObject();
                    default -> null;
                };
            }
            if (value != null) {
                object.add(field, value.deepCopy());
            }
        }
        return object;
    }
}
