package dev.dzenthai.storybound.core.logic;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** A sphere: {@code { "center": [120, 64, -340], "radius": 24, "dimension": "minecraft:overworld" }}. */
public record Area(Vec3 center, double radius, Optional<ResourceKey<Level>> dimension) {
    public static final Codec<Area> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Vec3.CODEC.fieldOf("center").forGetter(Area::center),
            Codec.doubleRange(0, 4096).fieldOf("radius").forGetter(Area::radius),
            Level.RESOURCE_KEY_CODEC.optionalFieldOf("dimension").forGetter(Area::dimension)
    ).apply(instance, Area::new));

    public boolean contains(ServerPlayer player) {
        return dimension.map(player.level().dimension()::equals).orElse(true)
                && player.position().distanceToSqr(center) <= radius * radius;
    }
}
