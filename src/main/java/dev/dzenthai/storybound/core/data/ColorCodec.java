package dev.dzenthai.storybound.core.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;

/** Colours in JSON: {@code "#RRGGBB"} (opaque) or {@code "#AARRGGBB"}. */
public final class ColorCodec {
    public static final Codec<Integer> CODEC = Codec.STRING.comapFlatMap(ColorCodec::parse, value -> String.format("#%08X", value));

    private ColorCodec() {}

    public static DataResult<Integer> parse(String value) {
        String hex = value.startsWith("#") ? value.substring(1) : value;
        if (hex.length() != 6 && hex.length() != 8) {
            return DataResult.error(() -> "Colour must be #RRGGBB or #AARRGGBB: " + value);
        }
        try {
            long parsed = Long.parseLong(hex, 16);
            return DataResult.success((int) (hex.length() == 6 ? parsed | 0xFF000000L : parsed));
        } catch (NumberFormatException e) {
            return DataResult.error(() -> "Not a hex colour: " + value);
        }
    }
}
