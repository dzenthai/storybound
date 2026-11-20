package dev.dzenthai.storybound.core.dev;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * The story each operator last opened in the editor, kept with the world. In the world's data, not the story folder:
 * the folder may be a pack's repository, and which story someone edits is not the pack's. {@code DevProjects} reads it
 * when it has no choice of the session yet, and writes it on every choice.
 */
final class DevSelections extends SavedData {
    private static final String NAME = "storybound_dev_selection";
    private static final Factory<DevSelections> FACTORY = new Factory<>(DevSelections::new, DevSelections::load, null);

    private final Map<UUID, String> stories = new HashMap<>();

    static DevSelections get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    Optional<String> of(UUID player) {
        return Optional.ofNullable(stories.get(player));
    }

    void set(UUID player, String story) {
        if (!story.equals(stories.put(player, story))) {
            setDirty();
        }
    }

    private static DevSelections load(CompoundTag tag, HolderLookup.Provider registries) {
        DevSelections data = new DevSelections();
        CompoundTag stories = tag.getCompound("stories");
        for (String key : stories.getAllKeys()) {
            try {
                data.stories.put(UUID.fromString(key), stories.getString(key));
            } catch (IllegalArgumentException ignored) {
                // Not a player's id: left out.
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag stories = new CompoundTag();
        this.stories.forEach((player, story) -> stories.putString(player.toString(), story));
        tag.put("stories", stories);
        return tag;
    }
}
