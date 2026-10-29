package dev.dzenthai.storybound.module.waypoint;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.StoryException;
import dev.dzenthai.storybound.core.data.ColorCodec;
import dev.dzenthai.storybound.core.logic.Action;
import dev.dzenthai.storybound.core.logic.ActionType;
import dev.dzenthai.storybound.core.logic.LogicRegistries;
import dev.dzenthai.storybound.core.logic.StoryContext;
import dev.dzenthai.storybound.core.logic.StructureRef;
import dev.dzenthai.storybound.core.state.StoryScope;
import dev.dzenthai.storybound.core.state.StoryState;
import dev.dzenthai.storybound.core.text.StoryText;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** The Waypoints module's additions to the logic language. */
public final class WaypointLogic {
    static final DeferredRegister<ActionType<?>> REGISTER_ACTIONS = DeferredRegister.create(LogicRegistries.ACTION_TYPE_KEY, Storybound.MOD_ID);

    public static final DeferredHolder<ActionType<?>, ActionType<AddWaypoint>> ADD_WAYPOINT =
            REGISTER_ACTIONS.register("add_waypoint", () -> new ActionType<>(AddWaypoint.CODEC));
    public static final DeferredHolder<ActionType<?>, ActionType<RemoveWaypoint>> REMOVE_WAYPOINT =
            REGISTER_ACTIONS.register("remove_waypoint", () -> new ActionType<>(RemoveWaypoint.CODEC));

    private WaypointLogic() {}

    /**
     * Adds a waypoint for the player, their party or everyone ({@code visibility}). It points at {@code pos}
     * (in {@code dimension}, by default the player's), or follows the context entity with {@code "follow": true},
     * or marks the nearest {@code structure} (searched in the background near each player who sees it, within
     * {@code search_radius} chunks, and again as they travel), or marks where the context entity (else the player) stands now. The same id and
     * visibility replaces it.
     */
    public record AddWaypoint(ResourceLocation id, StoryText name, Optional<Vec3> pos, Optional<ResourceKey<Level>> dimension, boolean follow,
                              Optional<StructureRef> structure, int searchRadius,
                              int color, float radius, AreaLook look, StoryScope visibility) implements Action {
        public static final MapCodec<AddWaypoint> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                ResourceLocation.CODEC.fieldOf("id").forGetter(AddWaypoint::id),
                StoryText.CODEC.fieldOf("name").forGetter(AddWaypoint::name),
                Vec3.CODEC.optionalFieldOf("pos").forGetter(AddWaypoint::pos),
                Level.RESOURCE_KEY_CODEC.optionalFieldOf("dimension").forGetter(AddWaypoint::dimension),
                Codec.BOOL.optionalFieldOf("follow", false).forGetter(AddWaypoint::follow),
                StructureRef.CODEC.optionalFieldOf("structure").forGetter(AddWaypoint::structure),
                Codec.intRange(1, 512).optionalFieldOf("search_radius", StructureRef.DEFAULT_SEARCH_RADIUS).forGetter(AddWaypoint::searchRadius),
                ColorCodec.CODEC.optionalFieldOf("color", WaypointDefinition.DEFAULT_COLOR).forGetter(AddWaypoint::color),
                Codec.floatRange(0f, 1024f).optionalFieldOf("radius", 0f).forGetter(AddWaypoint::radius),
                AreaLook.MAP_CODEC.forGetter(AddWaypoint::look),
                StoryScope.CODEC.optionalFieldOf("visibility", StoryScope.PLAYER).forGetter(AddWaypoint::visibility)
        ).apply(instance, AddWaypoint::new));

        @Override
        public ActionType<?> type() {
            return ADD_WAYPOINT.get();
        }

        @Override
        public void run(StoryContext context) throws StoryException {
            Entity anchor = context.entity() != null ? context.entity() : context.player();
            WaypointTarget target;
            if (pos.isPresent()) {
                target = new WaypointTarget.Fixed(pos.get(), dimension.orElse(context.player().level().dimension()));
            } else if (structure.isPresent()) {
                ServerLevel level = dimension.map(context.server()::getLevel).orElse((ServerLevel) anchor.level());
                if (level == null) {
                    throw new StoryException(Component.translatableWithFallback("storybound.waypoint.no_dimension", "No dimension %s", dimension.get().location()));
                }
                if (!structure.get().exists(level)) {
                    throw new StoryException(Component.translatableWithFallback("storybound.waypoint.unknown_structure", "Unknown structure %s",
                            structure.get().toString()));
                }
                // Searched in the background near each player who sees it; it shows once found (never, if none is in range).
                target = new WaypointTarget.Structure(structure.get(), level.dimension(), searchRadius, Optional.empty());
            } else if (follow && context.entity() != null) {
                target = new WaypointTarget.Follow(context.entity().getUUID());
            } else {
                target = new WaypointTarget.Fixed(anchor.position(), anchor.level().dimension());
            }
            if (!Waypoints.add(context.player(), id, name, target, color, radius, look, visibility)) {
                throw disabled();
            }
        }
    }

    /** Removes a waypoint added with the same id and visibility. Does nothing when there is none. */
    public record RemoveWaypoint(ResourceLocation id, StoryScope visibility) implements Action {
        public static final MapCodec<RemoveWaypoint> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                ResourceLocation.CODEC.fieldOf("id").forGetter(RemoveWaypoint::id),
                StoryScope.CODEC.optionalFieldOf("visibility", StoryScope.PLAYER).forGetter(RemoveWaypoint::visibility)
        ).apply(instance, RemoveWaypoint::new));

        @Override
        public ActionType<?> type() {
            return REMOVE_WAYPOINT.get();
        }

        @Override
        public void run(StoryContext context) {
            Waypoints.remove(context.server(), id, visibility, StoryState.owner(context.player(), visibility));
        }
    }

    private static StoryException disabled() {
        return new StoryException(Component.translatableWithFallback("storybound.waypoint.disabled", "The waypoints module is disabled"));
    }
}
