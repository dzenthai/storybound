package dev.dzenthai.storybound.module.waypoint;

import com.mojang.serialization.MapCodec;
import dev.dzenthai.storybound.Storybound;
import java.util.function.Supplier;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The trail's particles, each in a colour, the waypoint's, which the client draws glowing: {@code storybound:trail}, a
 * mote rising from a step (the client's {@code TrailParticle}), and {@code storybound:trail_wisp}, a puff drifting
 * along the trail ({@code WispParticle}). Registered on both sides, as every particle type is, though only clients
 * make them.
 */
public final class WaypointParticles {
    private static final DeferredRegister<ParticleType<?>> PARTICLES = DeferredRegister.create(Registries.PARTICLE_TYPE, Storybound.MOD_ID);

    public static final Supplier<ParticleType<ColorParticleOption>> TRAIL = PARTICLES.register("trail", WaypointParticles::coloured);
    public static final Supplier<ParticleType<ColorParticleOption>> WISP = PARTICLES.register("trail_wisp", WaypointParticles::coloured);

    private static ParticleType<ColorParticleOption> coloured() {
        return new ParticleType<>(true) {
            @Override
            public MapCodec<ColorParticleOption> codec() {
                return ColorParticleOption.codec(this);
            }

            @Override
            public StreamCodec<? super RegistryFriendlyByteBuf, ColorParticleOption> streamCodec() {
                return ColorParticleOption.streamCodec(this);
            }
        };
    }

    private WaypointParticles() {}

    public static void register(IEventBus modBus) {
        PARTICLES.register(modBus);
    }
}
