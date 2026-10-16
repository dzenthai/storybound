package dev.dzenthai.storybound.module.waypoint;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.data.ColorCodec;
import dev.dzenthai.storybound.core.state.StoryScope;
import dev.dzenthai.storybound.core.text.StoryText;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

/** Waypoints added in game (actions, commands, scripts), in SavedData {@code storybound_waypoints} of the overworld. */
public final class WaypointData extends SavedData {
    private static final String NAME = "storybound_waypoints";
    private static final Factory<WaypointData> FACTORY = new Factory<>(() -> new WaypointData(List.of()), WaypointData::load, null);

    private final List<Added> waypoints;

    private WaypointData(List<Added> waypoints) {
        this.waypoints = new ArrayList<>(waypoints);
    }

    public static WaypointData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    public List<Added> all() {
        return List.copyOf(waypoints);
    }

    /** Adds or replaces the waypoint with the same id, scope and owner. */
    void put(Added waypoint) {
        waypoints.removeIf(existing -> existing.sameSlot(waypoint.id(), waypoint.scope(), waypoint.owner().orElse(null)));
        waypoints.add(waypoint);
        setDirty();
    }

    boolean remove(ResourceLocation id, StoryScope scope, @Nullable UUID owner) {
        boolean removed = waypoints.removeIf(existing -> existing.sameSlot(id, scope, owner));
        if (removed) {
            setDirty();
        }
        return removed;
    }

    private static WaypointData load(CompoundTag tag, HolderLookup.Provider registries) {
        DynamicOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, registries);
        List<Added> list = Added.CODEC.listOf().parse(ops, tag.get("waypoints"))
                .resultOrPartial(error -> Storybound.LOGGER.error("Storybound: failed to load waypoints: {}", error))
                .orElse(List.of());
        return new WaypointData(list);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        DynamicOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, registries);
        Added.CODEC.listOf().encodeStart(ops, waypoints)
                .resultOrPartial(error -> Storybound.LOGGER.error("Storybound: failed to save waypoints: {}", error))
                .ifPresent(list -> tag.put("waypoints", list));
        return tag;
    }

    /**
     * @param scope who sees it: the player, their party, or everyone
     * @param owner the player or party it belongs to; empty for {@code world}
     * @param style the client marker style
     */
    public record Added(ResourceLocation id, StoryText name, WaypointTarget target, int color, float radius,
                        StoryScope scope, Optional<UUID> owner, ResourceLocation style) {
        public static final Codec<Added> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ResourceLocation.CODEC.fieldOf("id").forGetter(Added::id),
                StoryText.CODEC.fieldOf("name").forGetter(Added::name),
                WaypointTarget.CODEC.fieldOf("target").forGetter(Added::target),
                ColorCodec.CODEC.optionalFieldOf("color", WaypointDefinition.DEFAULT_COLOR).forGetter(Added::color),
                Codec.FLOAT.optionalFieldOf("radius", 0f).forGetter(Added::radius),
                StoryScope.CODEC.fieldOf("scope").forGetter(Added::scope),
                UUIDUtil.STRING_CODEC.optionalFieldOf("owner").forGetter(Added::owner),
                ResourceLocation.CODEC.optionalFieldOf("style", WaypointDefinition.DEFAULT_STYLE).forGetter(Added::style)
        ).apply(instance, Added::new));

        boolean sameSlot(ResourceLocation id, StoryScope scope, @Nullable UUID owner) {
            return this.id.equals(id) && this.scope == scope && Objects.equals(this.owner.orElse(null), owner);
        }

        /** The key clients know it by; different from static waypoints even with the same id. */
        String key() {
            return scope.getSerializedName() + "/" + id;
        }
    }
}
