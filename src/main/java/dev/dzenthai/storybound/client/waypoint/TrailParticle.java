package dev.dzenthai.storybound.client.waypoint;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.util.Mth;

/**
 * A mote of a trail ({@code storybound:trail}): a small spark in the waypoint's colour, with Minecraft's own sparks
 * (the dust's pictures), rising slowly from where a step was and drifting a little, fading in and out, lit fully so it
 * glows in a dark cave as in daylight.
 */
public final class TrailParticle extends TextureSheetParticle {
    private final SpriteSet sprites;

    private TrailParticle(ClientLevel level, double x, double y, double z, ColorParticleOption color, SpriteSet sprites) {
        super(level, x, y, z);
        this.sprites = sprites;
        this.rCol = color.getRed();
        this.gCol = color.getGreen();
        this.bCol = color.getBlue();
        this.alpha = 0;
        this.lifetime = 30 + random.nextInt(20);
        this.quadSize = 0.07f + random.nextFloat() * 0.035f;
        this.gravity = -0.01f;
        this.friction = 0.94f;
        this.hasPhysics = false;
        this.xd = (random.nextDouble() - 0.5) * 0.01;
        this.yd = 0.008 + random.nextDouble() * 0.006;
        this.zd = (random.nextDouble() - 0.5) * 0.01;
        setSpriteFromAge(sprites);
    }

    @Override
    public void tick() {
        super.tick();
        setSpriteFromAge(sprites);
        float life = age / (float) lifetime;
        alpha = Mth.clamp(life < 0.2f ? life / 0.2f : (1 - life) / 0.5f, 0, 1);
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }

    @Override
    public int getLightColor(float partialTick) {
        return 0xF000F0;
    }

    public record Provider(SpriteSet sprites) implements ParticleProvider<ColorParticleOption> {
        @Override
        public Particle createParticle(ColorParticleOption color, ClientLevel level, double x, double y, double z, double dx, double dy, double dz) {
            return new TrailParticle(level, x, y, z, color, sprites);
        }
    }
}
