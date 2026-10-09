package dev.dzenthai.storybound.core.state;

import com.mojang.serialization.Codec;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * Story values of one scope: a player, a party or the world. It may only be changed
 * through {@link StoryState}, so every change produces an event and a sync.
 */
public final class StoryContainer {
    public static final Codec<StoryContainer> CODEC = Codec.unboundedMap(ResourceLocation.CODEC, StoryValues.CODEC)
            .xmap(StoryContainer::new, container -> container.values);

    private final Map<ResourceLocation, StoryValue> values;
    private Runnable onChange = () -> {};

    public StoryContainer() {
        this(Map.of());
    }

    private StoryContainer(Map<ResourceLocation, StoryValue> initial) {
        this.values = new LinkedHashMap<>(initial);
    }

    public Optional<StoryValue> get(ResourceLocation key) {
        return Optional.ofNullable(values.get(key));
    }

    public Map<ResourceLocation, StoryValue> values() {
        return Collections.unmodifiableMap(values);
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    @Nullable
    StoryValue put(ResourceLocation key, StoryValue value) {
        StoryValue old = values.put(key, value);
        onChange.run();
        return old;
    }

    @Nullable
    StoryValue remove(ResourceLocation key) {
        StoryValue old = values.remove(key);
        if (old != null) {
            onChange.run();
        }
        return old;
    }

    void copyFrom(StoryContainer other) {
        values.clear();
        values.putAll(other.values);
        onChange.run();
    }

    /** Called on every change; SavedData uses it to mark itself dirty. */
    void setOnChange(Runnable onChange) {
        this.onChange = onChange;
    }
}
