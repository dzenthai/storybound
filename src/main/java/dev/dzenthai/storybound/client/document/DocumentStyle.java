package dev.dzenthai.storybound.client.document;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.dzenthai.storybound.Storybound;
import java.util.Optional;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;

/**
 * How a document looks: {@code assets/<ns>/storybound/styles/<id>.json}. The texture is drawn at
 * {@code width × height}; for books it is the whole two-page spread.
 */
public record DocumentStyle(ResourceLocation texture, int width, int height, Optional<Integer> textureWidth, Optional<Integer> textureHeight,
                            Padding padding, ResourceLocation font, int color, int lineHeight, boolean showTitle,
                            Optional<ResourceLocation> openSound, Optional<ResourceLocation> turnSound) {
    private static final Codec<Integer> COLOR_CODEC = Codec.STRING.comapFlatMap(
            value -> TextColor.parseColor(value).map(TextColor::getValue),
            value -> String.format("#%06x", value & 0xFFFFFF));

    public static final Codec<DocumentStyle> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ResourceLocation.CODEC.fieldOf("texture").forGetter(DocumentStyle::texture),
            Codec.intRange(16, 1024).fieldOf("width").forGetter(DocumentStyle::width),
            Codec.intRange(16, 1024).fieldOf("height").forGetter(DocumentStyle::height),
            Codec.intRange(1, 4096).optionalFieldOf("texture_width").forGetter(DocumentStyle::textureWidth),
            Codec.intRange(1, 4096).optionalFieldOf("texture_height").forGetter(DocumentStyle::textureHeight),
            Padding.CODEC.optionalFieldOf("padding", Padding.DEFAULT).forGetter(DocumentStyle::padding),
            ResourceLocation.CODEC.optionalFieldOf("font", ResourceLocation.withDefaultNamespace("default")).forGetter(DocumentStyle::font),
            COLOR_CODEC.optionalFieldOf("color", 0x3B2A1A).forGetter(DocumentStyle::color),
            Codec.intRange(6, 64).optionalFieldOf("line_height", 10).forGetter(DocumentStyle::lineHeight),
            Codec.BOOL.optionalFieldOf("show_title", true).forGetter(DocumentStyle::showTitle),
            ResourceLocation.CODEC.optionalFieldOf("open_sound").forGetter(DocumentStyle::openSound),
            ResourceLocation.CODEC.optionalFieldOf("turn_sound").forGetter(DocumentStyle::turnSound)
    ).apply(instance, DocumentStyle::new));

    /** Used when a document names a style no resource pack provides. */
    /** The letter's sheet, as in {@code styles/parchment.json}, for a document whose style cannot be found. */
    public static final DocumentStyle FALLBACK = new DocumentStyle(Storybound.id("textures/gui/document/parchment.png"), 200, 240,
            Optional.empty(), Optional.empty(), new Padding(26, 24, 26, 52), ResourceLocation.withDefaultNamespace("default"), 0x2A1E14, 10, true,
            Optional.empty(), Optional.empty());

    public int textureWidthOrSize() {
        return textureWidth.orElse(width);
    }

    public int textureHeightOrSize() {
        return textureHeight.orElse(height);
    }

    public record Padding(int left, int top, int right, int bottom) {
        public static final Padding DEFAULT = new Padding(16, 16, 16, 20);
        public static final Codec<Padding> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.intRange(0, 256).optionalFieldOf("left", DEFAULT.left()).forGetter(Padding::left),
                Codec.intRange(0, 256).optionalFieldOf("top", DEFAULT.top()).forGetter(Padding::top),
                Codec.intRange(0, 256).optionalFieldOf("right", DEFAULT.right()).forGetter(Padding::right),
                Codec.intRange(0, 256).optionalFieldOf("bottom", DEFAULT.bottom()).forGetter(Padding::bottom)
        ).apply(instance, Padding::new));
    }
}
