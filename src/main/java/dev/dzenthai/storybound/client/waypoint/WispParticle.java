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
 * A wisp of a trail ({@code storybound:trail_wisp}): a soft puff of Minecraft's own campfire smoke in the waypoint's
 * colour, see-through and lit fully, drifting along the trail the way it leads (the speed it is given), rising a
 * little and swelling as it goes, fading in and out: the scent in the air that a hunter's eye or the witcher's senses
 * show beside the tracks.
 */
public final class WispParticle extends TextureSheetParticle {
    /** How see-through a wisp is at its thickest. */
    private static final float MOST = 0.32f;

    private final SpriteSet sprites;
    private final float startSize;

    private WispParticle(ClientLevel level, double x, double y, double z, double dx, double dy, double dz, ColorParticleOption color, SpriteSet sprites) {
        super(level, x, y, z);
        this.sprites = sprites;
        this.rCol = color.getRed();
        this.gCol = color.getGreen();
        this.bCol = color.getBlue();
        this.alpha = 0;
        this.lifetime = 50 + random.nextInt(30);
        this.startSize = 0.22f + random.nextFloat() * 0.12f;
        this.quadSize = startSize;
        this.gravity = -0.002f;
        this.friction = 0.985f;
        this.hasPhysics = false;
        this.xd = dx;
        this.yd = dy;
        this.zd = dz;
        this.roll = random.nextFloat() * Mth.TWO_PI;
        this.oRoll = roll;
        setSpriteFromAge(sprites);
    }

    @Override
    public void tick() {
        super.tick();
        setSpriteFromAge(sprites);
        float life = age / (float) lifetime;
        quadSize = startSize * (1 + 0.8f * life);
        alpha = MOST * Mth.clamp(life < 0.25f ? life / 0.25f : (1 - life) / 0.5f, 0, 1);
        oRoll = roll;
        roll += 0.01f;
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
            return new WispParticle(level, x, y, z, dx, dy, dz, color, sprites);
        }
    }
}
