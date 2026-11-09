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
        Optional<String> plain = type.equals(ClientDevStore.TYPE_WAYPOINT) ? waypointTarget(json) : Optional.empty();
        if (plain.isPresent()) {
            return plain;
        }
        return definition.codec().parse(RegistryOps.create(JsonOps.INSTANCE, minecraft.level.registryAccess()), json).error()
                .map(error -> error.message());
    }

    /**
     * What a waypoint's target still lacks, in the author's words (November 9, 2026: before, the codec's "Failed to parse
     * either. First: No key pos in MapLike"); empty when it has it, the codec then says the rest.
     */
    private static Optional<String> waypointTarget(JsonObject waypoint) {
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
        // Nothing written yet: the form shows a point, so its position is what to fill in (November 9, 2026: before, "Choose
        // where the marker points" beside a form that showed a point).
        return Optional.of(DevUi.str("check.waypoint.pos"));
    }

    /** A codec's message fits the status line: its first part. */
    static String shorten(String message) {
        String first = message.split(";")[0].trim();
        return first.length() > 120 ? first.substring(0, 117) + "…" : first;
    }
}
