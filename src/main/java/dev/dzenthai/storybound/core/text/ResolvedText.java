package dev.dzenthai.storybound.core.text;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Optional;
import net.minecraft.locale.Language;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * A {@link StoryText} after the server filled in arguments and the variant value. Stored in player
 * data (journal notes keep the values they had when written) and sent to clients, which translate it.
 */
public record ResolvedText(String key, boolean literal, List<Component> args, Optional<String> fallback, Optional<String> variant) {
    public static final Codec<ResolvedText> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("key").forGetter(ResolvedText::key),
            Codec.BOOL.optionalFieldOf("literal", false).forGetter(ResolvedText::literal),
            ComponentSerialization.CODEC.listOf().optionalFieldOf("args", List.of()).forGetter(ResolvedText::args),
            Codec.STRING.optionalFieldOf("fallback").forGetter(ResolvedText::fallback),
            Codec.STRING.optionalFieldOf("variant").forGetter(ResolvedText::variant)
    ).apply(instance, ResolvedText::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, ResolvedText> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, ResolvedText::key,
            ByteBufCodecs.BOOL, ResolvedText::literal,
            ComponentSerialization.STREAM_CODEC.apply(ByteBufCodecs.list()), ResolvedText::args,
            ByteBufCodecs.optional(ByteBufCodecs.STRING_UTF8), ResolvedText::fallback,
            ByteBufCodecs.optional(ByteBufCodecs.STRING_UTF8), ResolvedText::variant,
            ResolvedText::new);

    public static ResolvedText literal(String text) {
        return new ResolvedText(text, true, List.of(), Optional.empty(), Optional.empty());
    }

    /**
     * Client side: uses {@code key.<variant>} when the current language has it, otherwise the key itself
     * (e.g. {@code mypack.npc.greet.f}, then {@code mypack.npc.greet}).
     */
    public Component toComponent() {
        if (literal) {
            return Component.literal(key);
        }
        String chosen = variant.map(value -> key + "." + value).filter(Language.getInstance()::has).orElse(key);
        return Component.translatableWithFallback(chosen, fallback.orElse(null), args.toArray());
    }
}
