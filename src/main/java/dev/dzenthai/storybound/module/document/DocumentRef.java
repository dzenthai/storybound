package dev.dzenthai.storybound.module.document;

import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

/** The {@code storybound:document} data component: which document an item is. Everything else comes from the definition. */
public record DocumentRef(ResourceLocation id) {
    public static final Codec<DocumentRef> CODEC = ResourceLocation.CODEC.xmap(DocumentRef::new, DocumentRef::id);
    public static final StreamCodec<ByteBuf, DocumentRef> STREAM_CODEC = ResourceLocation.STREAM_CODEC.map(DocumentRef::new, DocumentRef::id);
}
