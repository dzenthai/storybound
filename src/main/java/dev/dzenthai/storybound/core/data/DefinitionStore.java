package dev.dzenthai.storybound.core.data;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/** Immutable snapshot of loaded definitions; replaced as a whole on reload. */
public final class DefinitionStore<T> {
    private volatile Map<ResourceLocation, T> values = Map.of();

    public Optional<T> get(ResourceLocation id) {
        return Optional.ofNullable(values.get(id));
    }

    public @Nullable T getOrNull(ResourceLocation id) {
        return values.get(id);
    }

    public boolean contains(ResourceLocation id) {
        return values.containsKey(id);
    }

    public Map<ResourceLocation, T> all() {
        return values;
    }

    public int size() {
        return values.size();
    }

    public void replace(Map<ResourceLocation, T> newValues) {
        values = Collections.unmodifiableMap(new LinkedHashMap<>(newValues));
    }

    public void clear() {
        values = Map.of();
    }
}
