package dev.dzenthai.storybound.client;

import dev.dzenthai.storybound.core.CoreDefinitions;
import dev.dzenthai.storybound.core.state.FlagDeclaration;
import dev.dzenthai.storybound.core.state.StoryValue;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

/** Client copy of {@code sync} flags. Other flags are intentionally unavailable on the client. */
public final class ClientStoryState {
    private static volatile Map<ResourceLocation, StoryValue> values = Map.of();

    private ClientStoryState() {}

    /** The value, or the default from the synced declaration. */
    public static Optional<StoryValue> get(ResourceLocation key) {
        return Optional.ofNullable(values.get(key))
                .or(() -> CoreDefinitions.FLAGS.clientStore().get(key).map(FlagDeclaration::defaultValue));
    }

    public static Map<ResourceLocation, StoryValue> stored() {
        return values;
    }

    public static void apply(boolean full, Map<ResourceLocation, Optional<StoryValue>> changes) {
        Map<ResourceLocation, StoryValue> next = full ? new LinkedHashMap<>() : new LinkedHashMap<>(values);
        changes.forEach((key, value) -> value.ifPresentOrElse(present -> next.put(key, present), () -> next.remove(key)));
        values = Collections.unmodifiableMap(next);
    }

    public static void clear() {
        values = Map.of();
    }
}
