package dev.dzenthai.storybound.client.dev;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import dev.dzenthai.storybound.core.data.DefinitionType;
import dev.dzenthai.storybound.core.data.DefinitionTypes;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;

/**
 * Reads a definition with the game's own codec on the client before it is sent: a form being filled in (a new list
 * element without its required fields yet) is kept until it is complete, instead of the server refusing it.
 */
final class DevChecks {
    private DevChecks() {}

    /** @return why the definition would not load, or empty when it would (or cannot be checked here) */
    static Optional<String> problem(ResourceLocation type, JsonObject json) {
        Minecraft minecraft = Minecraft.getInstance();
        DefinitionType<?> definition = DefinitionTypes.all().stream().filter(candidate -> candidate.id().equals(type)).findFirst().orElse(null);
        if (definition == null || minecraft.level == null) {
            return Optional.empty();
        }
        Optional<String> plain = type.equals(ClientDevStore.TYPE_WAYPOINT) ? waypointTarget(json)
                : type.equals(ClientDevStore.TYPE_WORLD_RULE) ? worldRuleProblem(json)
                : Optional.empty();
        if (plain.isPresent()) {
            return plain;
        }
        return definition.codec().parse(RegistryOps.create(JsonOps.INSTANCE, minecraft.level.registryAccess()), json).error()
                .map(error -> error.message());
    }

    /** Friendly localized validation messages for incomplete world rules before falling back to raw codec errors. */
    private static Optional<String> worldRuleProblem(JsonObject rule) {
        String type = rule.get("type") instanceof com.google.gson.JsonPrimitive p ? p.getAsString() : "";
        return switch (type) {
            case "item" -> isEmptyListOrBlank(rule.get("items"))
                    ? Optional.of(DevUi.str("check.world.items")) : Optional.empty();
            case "mob" -> isEmptyListOrBlank(rule.get("entities"))
                    ? Optional.of(DevUi.str("check.world.entities")) : Optional.empty();
            case "place" -> isEmptyListOrBlank(rule.get("dimension"))
                    && isEmptyListOrBlank(rule.get("biome"))
                    && isEmptyListOrBlank(rule.get("structure"))
                    ? Optional.of(DevUi.str("check.world.place")) : Optional.empty();
            case "loot" -> {
                String op = rule.get("operation") instanceof com.google.gson.JsonPrimitive p ? p.getAsString() : "add";
                if ((op.equals("replace") || op.equals("remove")) && isEmptyListOrBlank(rule.get("target"))) {
                    yield Optional.of(DevUi.str("check.world.loot_target"));
                }
                if ((op.equals("add") || op.equals("replace")) && isEmptyListOrBlank(rule.get("item"))) {
                    yield Optional.of(DevUi.str("check.world.loot_item"));
                }
                if (rule.get("min_count") instanceof com.google.gson.JsonPrimitive min && min.isNumber()
                        && rule.get("max_count") instanceof com.google.gson.JsonPrimitive max && max.isNumber()
                        && max.getAsInt() < min.getAsInt()) {
                    yield Optional.of(DevUi.str("check.world.loot_count"));
                }
                yield Optional.empty();
            }
            default -> Optional.empty();
        };
    }

    private static boolean isEmptyListOrBlank(com.google.gson.JsonElement el) {
        if (el == null || el.isJsonNull()) {
            return true;
        }
        if (el instanceof com.google.gson.JsonPrimitive p) {
            return p.getAsString().isBlank();
        }
        if (el instanceof com.google.gson.JsonArray arr) {
            return arr.isEmpty();
        }
        return false;
    }

    /**
     * What a waypoint's target still lacks, in the author's words; empty when it has it, the codec then says the rest.
     */
    private static Optional<String> waypointTarget(JsonObject waypoint) {
        if (waypoint.get("trace") instanceof com.google.gson.JsonArray points && points.size() < 2) {
            return Optional.of(DevUi.str("check.waypoint.trace"));
        }
        if (waypoint.get("trace") instanceof JsonObject) {
            JsonObject target = waypoint.get("target") instanceof JsonObject written ? written : new JsonObject();
            if (target.has("pos") || target.has("structure") || target.isEmpty()) {
                return Optional.of(DevUi.str("check.waypoint.trace_dynamic"));
            }
        }
        if (!(waypoint.get("target") instanceof JsonObject target)) {
            return Optional.of(DevUi.str("check.waypoint.target"));
        }
        for (String kind : java.util.List.of("pos", "entity", "structure", "nearest", "npc")) {
            if (target.has(kind)) {
                boolean empty = target.get(kind) instanceof com.google.gson.JsonPrimitive value && value.getAsString().isBlank()
                        || target.get(kind) instanceof com.google.gson.JsonArray list && list.isEmpty()
                        || target.get(kind) instanceof JsonObject selector && selector.isEmpty();
                return empty ? Optional.of(DevUi.str("check.waypoint." + kind)) : Optional.empty();
            }
        }
        // Nothing written yet: the form shows a point, so its position is what to fill in.
        return Optional.of(DevUi.str("check.waypoint.pos"));
    }

    /** A codec's message fits the status line: its first part. */
    static String shorten(String message) {
        String first = message.split(";")[0].trim();
        return first.length() > 120 ? first.substring(0, 117) + "…" : first;
    }
}
