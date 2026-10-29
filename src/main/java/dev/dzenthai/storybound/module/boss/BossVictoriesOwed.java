package dev.dzenthai.storybound.module.boss;

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
 * Bosses won while a player who fought them was away (SavedData {@code storybound_owed_boss_victories} of the
 * overworld): they dealt their share of the damage and left before the end; the victory is theirs when they next
 * join. Kept for a boss that is missing at that moment (a file that failed to load), so the victory is not lost.
 */
public final class BossVictoriesOwed extends SavedData {
    private static final String NAME = "storybound_owed_boss_victories";
    private static final Factory<BossVictoriesOwed> FACTORY = new Factory<>(BossVictoriesOwed::new, BossVictoriesOwed::load, null);

    /**
     * @param ticks   how long the fight lasted
     * @param counted owners of shared progress that counted the victory when it happened (the party of a player who
     *                was there, the world), so it is not counted for them again
     */
    public record Owed(ResourceLocation boss, long ticks, List<UUID> counted) {
        public Owed {
            counted = List.copyOf(counted);
        }
    }

    private final Map<UUID, List<Owed>> owed = new HashMap<>();

    public static BossVictoriesOwed get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    void owe(UUID player, Owed victory) {
        owed.computeIfAbsent(player, id -> new ArrayList<>()).add(victory);
        setDirty();
    }

    public List<Owed> of(UUID player) {
        return List.copyOf(owed.getOrDefault(player, List.of()));
    }

    /** Takes the player's victories over bosses that exist now; the others stay. */
    List<Owed> take(UUID player) {
        List<Owed> entries = owed.get(player);
        if (entries == null) {
            return List.of();
        }
        List<Owed> taken = new ArrayList<>();
        entries.removeIf(victory -> Bosses.definition(victory.boss()) != null && taken.add(victory));
        if (entries.isEmpty()) {
            owed.remove(player);
        }
        if (!taken.isEmpty()) {
            setDirty();
        }
        return taken;
    }

    /** Renamed bosses (migrations). */
    int migrate(StoryMigrations.Renames renames) {
        int moved = 0;
        for (List<Owed> victories : owed.values()) {
            for (int i = 0; i < victories.size(); i++) {
                Owed victory = victories.get(i);
                ResourceLocation renamed = renames.id(StoryMigrations.Kind.BOSSES, victory.boss());
                if (!renamed.equals(victory.boss())) {
                    victories.set(i, new Owed(renamed, victory.ticks(), victory.counted()));
                    moved++;
                }
            }
        }
        if (moved > 0) {
            setDirty();
        }
        return moved;
    }

    private static BossVictoriesOwed load(CompoundTag tag, HolderLookup.Provider registries) {
        BossVictoriesOwed data = new BossVictoriesOwed();
        for (String key : tag.getAllKeys()) {
            List<Owed> victories = new ArrayList<>();
            for (Tag entry : tag.getList(key, Tag.TAG_COMPOUND)) {
                CompoundTag victory = (CompoundTag) entry;
                ResourceLocation boss = ResourceLocation.tryParse(victory.getString("boss"));
                if (boss == null) {
                    continue;
                }
                List<UUID> counted = new ArrayList<>();
                for (Tag owner : victory.getList("counted", Tag.TAG_STRING)) {
                    counted.add(UUID.fromString(owner.getAsString()));
                }
                victories.add(new Owed(boss, victory.getLong("ticks"), counted));
            }
            if (!victories.isEmpty()) {
                data.owed.put(UUID.fromString(key), victories);
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        owed.forEach((player, victories) -> {
            ListTag list = new ListTag();
            for (Owed victory : victories) {
                CompoundTag entry = new CompoundTag();
                entry.putString("boss", victory.boss().toString());
                entry.putLong("ticks", victory.ticks());
                ListTag counted = new ListTag();
                victory.counted().forEach(owner -> counted.add(StringTag.valueOf(owner.toString())));
                entry.put("counted", counted);
                list.add(entry);
            }
            tag.put(player.toString(), list);
        });
        return tag;
    }
}
