package dev.dzenthai.storybound.network;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.client.ClientQuests;
import io.netty.buffer.ByteBuf;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** The objective hints that show for the player now (keys of {@code QuestHints.key}); replaces what the client had. */
public record QuestHintsPayload(Set<String> hints) implements CustomPacketPayload {
    public static final Type<QuestHintsPayload> TYPE = new Type<>(Storybound.id("quest_hints"));
    public static final StreamCodec<ByteBuf, QuestHintsPayload> STREAM_CODEC = ByteBufCodecs.STRING_UTF8
            .apply(ByteBufCodecs.collection(LinkedHashSet::new))
            .map(QuestHintsPayload::new, payload -> new LinkedHashSet<>(payload.hints()));

    @Override
    public Type<QuestHintsPayload> type() {
        return TYPE;
    }

    public static void handleOnClient(QuestHintsPayload payload, IPayloadContext context) {
        ClientQuests.applyHints(payload.hints());
    }
}
