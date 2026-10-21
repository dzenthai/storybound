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
        return definition.codec().parse(RegistryOps.create(JsonOps.INSTANCE, minecraft.level.registryAccess()), json).error()
                .map(error -> error.message());
    }

    /** A codec's message fits the status line: its first part. */
    static String shorten(String message) {
        String first = message.split(";")[0].trim();
        return first.length() > 120 ? first.substring(0, 117) + "…" : first;
    }
}
