package dev.dzenthai.storybound.module.waypoint;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * The entities waypoints have followed, and which of them died or were removed since (SavedData
 * {@code storybound_waypoint_targets} of the overworld). A player's saved sighting ({@link WaypointSightings}) of an
 * entity that is gone is dropped, even when nobody keeps the place loaded: a player far away or offline when someone
 * else killed it does not see the area of a dead NPC. An entity that comes back with the same uuid (an NPC that
 * respawns) is followed again. Only followed entities are recorded, and both sets are capped, oldest first.
 */
public final class WaypointTargets extends SavedData {
    private static final String NAME = "storybound_waypoint_targets";
    private static final int MAX = 4096;
    private static final Factory<WaypointTargets> FACTORY = new Factory<>(WaypointTargets::new, WaypointTargets::load, null);

    private final Set<UUID> followed = new LinkedHashSet<>();
    private final Set<UUID> gone = new LinkedHashSet<>();

    public static WaypointTargets get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    /** A waypoint shows this entity: it is followed, and alive. */
    void seen(UUID entity) {
        boolean changed = gone.remove(entity);
        if (followed.add(entity)) {
            trim(followed);
            changed = true;
        }
        if (changed) {
            setDirty();
        }
    }

    public boolean isGone(UUID entity) {
        return gone.contains(entity);
    }

    /** The entity died or was removed for good; recorded only when a waypoint followed it. */
    void left(UUID entity) {
        if (followed.remove(entity)) {
            gone.add(entity);
            trim(gone);
            setDirty();
        }
    }

    /** The entity is in a level again (it respawned with its uuid). */
    void back(UUID entity) {
        if (gone.remove(entity)) {
            followed.add(entity);
            setDirty();
        }
    }

    private static void trim(Set<UUID> set) {
        Iterator<UUID> oldest = set.iterator();
        while (set.size() > MAX && oldest.hasNext()) {
            oldest.next();
            oldest.remove();
        }
    }

    private static WaypointTargets load(CompoundTag tag, HolderLookup.Provider registries) {
        WaypointTargets data = new WaypointTargets();
        read(tag.getList("followed", Tag.TAG_INT_ARRAY), data.followed);
        read(tag.getList("gone", Tag.TAG_INT_ARRAY), data.gone);
        return data;
    }

    private static void read(ListTag list, Set<UUID> into) {
        for (Tag entry : list) {
            into.add(NbtUtils.loadUUID(entry));
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.put("followed", write(followed));
        tag.put("gone", write(gone));
        return tag;
    }

    private static ListTag write(Set<UUID> set) {
        ListTag list = new ListTag();
        for (UUID id : set) {
            list.add(NbtUtils.createUUID(id));
        }
        return list;
    }
}
