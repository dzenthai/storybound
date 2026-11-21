package dev.dzenthai.storybound.module.dialogue;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.dzenthai.storybound.core.logic.EntitySelector;
import dev.dzenthai.storybound.module.waypoint.ClientWaypoint;
import dev.dzenthai.storybound.module.waypoint.Waypoints;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * What a line's camera looks at instead of the speakers ({@code look_at}), found as the line shows: the nearest living
 * entity matching a selector within {@link #RANGE} blocks of the player ({@code entity}, followed as it moves), a
 * waypoint the player sees ({@code waypoint}: a structure it found, a place), or a point beside the speaker or the
 * player ({@code offset} as right, up, forward of where they face). A world is generated anew each time, so a story
 * never names coordinates. Nothing found, another dimension: the line keeps its usual shot.
 */
public record LookAt(Optional<EntitySelector> entity, Optional<ResourceLocation> waypoint, Optional<Vec3> offset, From from) {
    public static final double RANGE = 64;

    public enum From implements StringRepresentable {
        SPEAKER("speaker"), PLAYER("player");

        public static final Codec<From> CODEC = StringRepresentable.fromEnum(From::values);
        private final String name;

        From(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    public static final Codec<LookAt> CODEC = RecordCodecBuilder.<LookAt>mapCodec(instance -> instance.group(
            EntitySelector.CODEC.optionalFieldOf("entity").forGetter(LookAt::entity),
            ResourceLocation.CODEC.optionalFieldOf("waypoint").forGetter(LookAt::waypoint),
            Vec3.CODEC.optionalFieldOf("offset").forGetter(LookAt::offset),
            From.CODEC.optionalFieldOf("from", From.SPEAKER).forGetter(LookAt::from)
    ).apply(instance, LookAt::new)).validate(look -> (look.entity.isPresent() ? 1 : 0) + (look.waypoint.isPresent() ? 1 : 0)
            + (look.offset.isPresent() ? 1 : 0) == 1
            ? DataResult.success(look)
            : DataResult.error(() -> "look_at needs exactly one of 'entity', 'waypoint' or 'offset'")).codec();

    /** Where the camera looks for this player now; {@link CameraCue.Look#NONE} when nothing is found. */
    public CameraCue.Look resolve(ServerPlayer player, @Nullable Entity speaker) {
        if (entity.isPresent()) {
            Entity best = null;
            double bestDistance = RANGE * RANGE;
            for (Entity candidate : player.serverLevel().getEntities(player, player.getBoundingBox().inflate(RANGE),
                    candidate -> candidate.isAlive() && entity.get().matches(candidate))) {
                double distance = candidate.distanceToSqr(player);
                if (distance <= bestDistance) {
                    best = candidate;
                    bestDistance = distance;
                }
            }
            return best != null ? new CameraCue.Look(best.getId(), Optional.empty()) : CameraCue.Look.NONE;
        }
        if (waypoint.isPresent()) {
            ClientWaypoint shown = Waypoints.visible(player).get(waypoint.get().toString());
            return shown != null && shown.placed() && shown.dimension().equals(player.level().dimension().location())
                    ? new CameraCue.Look(-1, Optional.of(shown.pos())) : CameraCue.Look.NONE;
        }
        Entity anchor = from == From.SPEAKER && speaker != null ? speaker : player;
        double yaw = Math.toRadians(anchor.getYRot());
        Vec3 forward = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
        Vec3 right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
        Vec3 at = offset.get();
        return new CameraCue.Look(-1, Optional.of(anchor.position().add(right.scale(at.x)).add(0, at.y, 0).add(forward.scale(at.z))));
    }
}
