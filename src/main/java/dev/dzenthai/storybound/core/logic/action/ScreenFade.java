package dev.dzenthai.storybound.core.logic.action;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.dzenthai.storybound.core.data.ColorCodec;
import dev.dzenthai.storybound.network.FadePayload;
import dev.dzenthai.storybound.network.StoryboundNetwork;
import net.minecraft.server.level.ServerPlayer;

/**
 * A fade of the player's screen to a colour and back: {@code { "in": 0.5, "stay": 1, "out": 0.5, "color": "#000000" }},
 * in seconds. It rises from wherever the screen is (a fade over a fade does not flash), holds, and clears; {@code in} 0
 * is black at once, {@code stay} and {@code out} 0 clear it at once. Drawn over the world and the HUD, under the
 * subtitles and the titles, so text can stand on black. The {@code fade} action and a dialogue line's {@code fade}.
 */
public record ScreenFade(float in, float stay, float out, int color) {
    /** The longest hold: a scene that never comes (the player walked away) never leaves the screen black. */
    public static final float MAX_STAY = 60;
    public static final float MAX_EDGE = 10;
    public static final int BLACK = 0xFF000000;

    public static final MapCodec<ScreenFade> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.floatRange(0, MAX_EDGE).optionalFieldOf("in", 0.5f).forGetter(ScreenFade::in),
            Codec.floatRange(0, MAX_STAY).optionalFieldOf("stay", 1f).forGetter(ScreenFade::stay),
            Codec.floatRange(0, MAX_EDGE).optionalFieldOf("out", 0.5f).forGetter(ScreenFade::out),
            ColorCodec.CODEC.optionalFieldOf("color", BLACK).forGetter(ScreenFade::color)
    ).apply(instance, ScreenFade::new));
    public static final Codec<ScreenFade> CODEC = MAP_CODEC.codec();

    public void send(ServerPlayer player) {
        StoryboundNetwork.sendToPlayer(player, new FadePayload(in, stay, out, color));
    }
}
