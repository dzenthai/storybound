package dev.dzenthai.storybound.core.state;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * Story values and quest states of one scope: a player, a party or the world. It may only be
 * changed through {@link StoryState}, so every value change produces an event and a sync.
 */
public final class StoryContainer {
    private static final Codec<Map<ResourceLocation, StoryValue>> VALUES_CODEC = Codec.unboundedMap(ResourceLocation.CODEC, StoryValues.CODEC);

    private static final Codec<StoryContainer> OBJECT_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            VALUES_CODEC.optionalFieldOf("values", Map.of()).forGetter(container -> container.values),
            Codec.unboundedMap(ResourceLocation.CODEC, QuestProgress.CODEC).optionalFieldOf("quests", Map.of()).forGetter(container -> container.quests)
    ).apply(instance, StoryContainer::new));

    /**
     * {@code { "values": {...}, "quests": {...} }}. Before quests existed a container was saved as a
     * bare value map; that form is tried first and still loads (its keys always carry a namespace,
     * so it never matches the object form by accident).
     */
    public static final Codec<StoryContainer> CODEC = Codec.either(VALUES_CODEC, OBJECT_CODEC).xmap(
            either -> either.map(values -> new StoryContainer(values, Map.of()), container -> container),
            Either::right);

    private final Map<ResourceLocation, StoryValue> values;
    private final Map<ResourceLocation, QuestProgress> quests;
    private Runnable onChange = () -> {};

    public StoryContainer() {
        this(Map.of(), Map.of());
    }

    private StoryContainer(Map<ResourceLocation, StoryValue> values, Map<ResourceLocation, QuestProgress> quests) {
        this.values = new LinkedHashMap<>(values);
        this.quests = new LinkedHashMap<>(quests);
    }

    public Optional<StoryValue> get(ResourceLocation key) {
        return Optional.ofNullable(values.get(key));
    }

    public Map<ResourceLocation, StoryValue> values() {
        return Collections.unmodifiableMap(values);
    }

    public Optional<QuestProgress> quest(ResourceLocation quest) {
        return Optional.ofNullable(quests.get(quest));
    }

    public Map<ResourceLocation, QuestProgress> quests() {
        return Collections.unmodifiableMap(quests);
    }

    public boolean isEmpty() {
        return values.isEmpty() && quests.isEmpty();
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

    /** {@code null} forgets the quest (back to unknown). */
    void putQuest(ResourceLocation quest, @Nullable QuestProgress progress) {
        if (progress == null) {
            if (quests.remove(quest) == null) {
                return;
            }
        } else {
            quests.put(quest, progress);
        }
        onChange.run();
    }

    void copyFrom(StoryContainer other) {
        values.clear();
        values.putAll(other.values);
        quests.clear();
        quests.putAll(other.quests);
        onChange.run();
    }

    /** Called on every change; SavedData uses it to mark itself dirty. */
    void setOnChange(Runnable onChange) {
        this.onChange = onChange;
    }
}
