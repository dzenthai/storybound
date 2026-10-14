package dev.dzenthai.storybound.module.journal;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.dzenthai.storybound.core.text.ResolvedText;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

/** A text entry written by the {@code journal_entry} action; arguments keep the values they had when written. */
public record JournalNote(ResourceLocation id, ResourceLocation category, ResolvedText title, ResolvedText text) {
    public static final Codec<JournalNote> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ResourceLocation.CODEC.fieldOf("id").forGetter(JournalNote::id),
            ResourceLocation.CODEC.fieldOf("category").forGetter(JournalNote::category),
            ResolvedText.CODEC.fieldOf("title").forGetter(JournalNote::title),
            ResolvedText.CODEC.fieldOf("text").forGetter(JournalNote::text)
    ).apply(instance, JournalNote::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, JournalNote> STREAM_CODEC = StreamCodec.composite(
            ResourceLocation.STREAM_CODEC, JournalNote::id,
            ResourceLocation.STREAM_CODEC, JournalNote::category,
            ResolvedText.STREAM_CODEC, JournalNote::title,
            ResolvedText.STREAM_CODEC, JournalNote::text,
            JournalNote::new);
}
