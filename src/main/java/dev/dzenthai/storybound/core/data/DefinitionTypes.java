package dev.dzenthai.storybound.core.data;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * Registry of definition types. Modules and third-party mods register their types
 * during mod construction, before the first datapack load.
 */
public final class DefinitionTypes {
    private static final Map<ResourceLocation, DefinitionType<?>> TYPES = new LinkedHashMap<>();

    private DefinitionTypes() {}

    public static synchronized <T> DefinitionType<T> register(DefinitionType<T> type) {
        if (TYPES.putIfAbsent(type.id(), type) != null) {
            throw new IllegalStateException("Duplicate Storybound definition type: " + type.id());
        }
        return type;
    }

    public static synchronized @Nullable DefinitionType<?> get(ResourceLocation id) {
        return TYPES.get(id);
    }

    public static synchronized Collection<DefinitionType<?>> all() {
        return List.copyOf(TYPES.values());
    }
}
