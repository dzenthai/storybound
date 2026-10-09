package dev.dzenthai.storybound.core.state;

import com.mojang.serialization.DynamicOps;
import dev.dzenthai.storybound.Storybound;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/** The {@code world} scope: {@code data/storybound_world.dat} in the overworld. */
public final class WorldStoryData extends SavedData {
    private static final String NAME = "storybound_world";
    private static final Factory<WorldStoryData> FACTORY = new Factory<>(() -> new WorldStoryData(new StoryContainer()), WorldStoryData::load, null);

    private final StoryContainer container;

    private WorldStoryData(StoryContainer container) {
        this.container = container;
        container.setOnChange(this::setDirty);
    }

    public static WorldStoryData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    public StoryContainer container() {
        return container;
    }

    private static WorldStoryData load(CompoundTag tag, HolderLookup.Provider registries) {
        DynamicOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, registries);
        StoryContainer container = StoryContainer.CODEC.parse(ops, tag.get("values"))
                .resultOrPartial(error -> Storybound.LOGGER.error("Storybound: failed to load world story state: {}", error))
                .orElseGet(StoryContainer::new);
        return new WorldStoryData(container);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        StoryContainer.CODEC.encodeStart(RegistryOps.create(NbtOps.INSTANCE, registries), container)
                .resultOrPartial(error -> Storybound.LOGGER.error("Storybound: failed to save world story state: {}", error))
                .ifPresent(values -> tag.put("values", values));
        return tag;
    }
}
