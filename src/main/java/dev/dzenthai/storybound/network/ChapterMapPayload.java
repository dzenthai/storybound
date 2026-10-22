package dev.dzenthai.storybound.network;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.client.quest.ClientChapterMap;
import dev.dzenthai.storybound.module.quest.ChapterMap;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** The players' chapter map (empty when the pack has none); on login, after a data load and after the dev mode saved. */
public record ChapterMapPayload(ChapterMap map) implements CustomPacketPayload {
    public static final Type<ChapterMapPayload> TYPE = new Type<>(Storybound.id("chapter_map"));
    public static final StreamCodec<ByteBuf, ChapterMapPayload> STREAM_CODEC = ByteBufCodecs.fromCodec(ChapterMap.CODEC)
            .map(ChapterMapPayload::new, ChapterMapPayload::map);

    @Override
    public Type<ChapterMapPayload> type() {
        return TYPE;
    }

    public static void handleOnClient(ChapterMapPayload payload, IPayloadContext context) {
        ClientChapterMap.set(payload.map());
    }
}
