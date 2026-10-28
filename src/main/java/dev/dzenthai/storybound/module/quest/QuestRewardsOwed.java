package dev.dzenthai.storybound.module.quest;

import dev.dzenthai.storybound.core.state.StoryMigrations;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Party quests completed while a member was away (SavedData {@code storybound_owed_rewards} of the overworld): the
 * member is paid when they next join, as if they had been there, one entry per completion. Kept for a quest that is
 * missing at that moment (a file that failed to load), so the rewards are not lost; paid once it is back.
 */
public final class QuestRewardsOwed extends SavedData {
    private static final String NAME = "storybound_owed_rewards";
    private static final Factory<QuestRewardsOwed> FACTORY = new Factory<>(QuestRewardsOwed::new, QuestRewardsOwed::load, null);

    private final Map<UUID, List<ResourceLocation>> owed = new HashMap<>();

    public static QuestRewardsOwed get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    void owe(UUID player, ResourceLocation quest) {
        owed.computeIfAbsent(player, id -> new ArrayList<>()).add(quest);
        setDirty();
    }

    public List<ResourceLocation> of(UUID player) {
        return List.copyOf(owed.getOrDefault(player, List.of()));
    }

    /** Takes the player's entries for quests that exist now; the others stay. */
    List<ResourceLocation> take(UUID player) {
        List<ResourceLocation> entries = owed.get(player);
        if (entries == null) {
            return List.of();
        }
        List<ResourceLocation> taken = new ArrayList<>();
        entries.removeIf(quest -> Quests.definition(quest) != null && taken.add(quest));
        if (entries.isEmpty()) {
            owed.remove(player);
        }
        if (!taken.isEmpty()) {
            setDirty();
        }
        return taken;
    }

    /** Renamed quests (migrations). */
    int migrate(StoryMigrations.Renames renames) {
        int moved = 0;
        for (List<ResourceLocation> quests : owed.values()) {
            for (int i = 0; i < quests.size(); i++) {
                ResourceLocation renamed = renames.id(StoryMigrations.Kind.QUESTS, quests.get(i));
                if (!renamed.equals(quests.get(i))) {
                    quests.set(i, renamed);
                    moved++;
                }
            }
        }
        if (moved > 0) {
            setDirty();
        }
        return moved;
    }

    private static QuestRewardsOwed load(CompoundTag tag, HolderLookup.Provider registries) {
        QuestRewardsOwed data = new QuestRewardsOwed();
        for (String key : tag.getAllKeys()) {
            List<ResourceLocation> quests = new ArrayList<>();
            for (Tag entry : tag.getList(key, Tag.TAG_STRING)) {
                ResourceLocation quest = ResourceLocation.tryParse(entry.getAsString());
                if (quest != null) {
                    quests.add(quest);
                }
            }
            if (!quests.isEmpty()) {
                data.owed.put(UUID.fromString(key), quests);
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        owed.forEach((player, quests) -> {
            ListTag list = new ListTag();
            quests.forEach(quest -> list.add(StringTag.valueOf(quest.toString())));
            tag.put(player.toString(), list);
        });
        return tag;
    }
}
