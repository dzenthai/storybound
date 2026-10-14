package dev.dzenthai.storybound.network;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.client.dialogue.ClientDialogue;
import dev.dzenthai.storybound.core.text.ResolvedText;
import dev.dzenthai.storybound.module.dialogue.DialogueDefinition;
import dev.dzenthai.storybound.module.dialogue.ThinkTime;
import io.netty.buffer.ByteBuf;
import java.util.Optional;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * One line of a dialogue. The first line or choice of a new {@code session} opens the dialogue on the client.
 *
 * @param step  identifies this line; the client's "next" must name it, so a late click cannot skip a later line
 * @param timed the server moves on by itself ({@code duration}); the client does not auto-advance HUD subtitles
 * @param think a {@link ThinkTime} code: the pause before showing a line that answers the option the player just chose
 */
public record DialogueLinePayload(int session, int step, DialogueDefinition.Presentation presentation, boolean closable,
                                  Speaker speaker, ResolvedText text, boolean timed, int think) implements CustomPacketPayload {
    public static final Type<DialogueLinePayload> TYPE = new Type<>(Storybound.id("dialogue_line"));
    public static final StreamCodec<ByteBuf, DialogueDefinition.Presentation> PRESENTATION_CODEC =
            ByteBufCodecs.idMapper(index -> DialogueDefinition.Presentation.values()[index], DialogueDefinition.Presentation::ordinal);
    // More fields than StreamCodec.composite takes.
    public static final StreamCodec<RegistryFriendlyByteBuf, DialogueLinePayload> STREAM_CODEC =
            StreamCodec.ofMember(DialogueLinePayload::write, DialogueLinePayload::read);

    private static DialogueLinePayload read(RegistryFriendlyByteBuf buf) {
        return new DialogueLinePayload(ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_INT.decode(buf), PRESENTATION_CODEC.decode(buf),
                ByteBufCodecs.BOOL.decode(buf), Speaker.STREAM_CODEC.decode(buf), ResolvedText.STREAM_CODEC.decode(buf), ByteBufCodecs.BOOL.decode(buf), ByteBufCodecs.VAR_INT.decode(buf));
    }

    private void write(RegistryFriendlyByteBuf buf) {
        ByteBufCodecs.VAR_INT.encode(buf, session);
        ByteBufCodecs.VAR_INT.encode(buf, step);
        PRESENTATION_CODEC.encode(buf, presentation);
        ByteBufCodecs.BOOL.encode(buf, closable);
        Speaker.STREAM_CODEC.encode(buf, speaker);
        ResolvedText.STREAM_CODEC.encode(buf, text);
        ByteBufCodecs.BOOL.encode(buf, timed);
        ByteBufCodecs.VAR_INT.encode(buf, think);
    }

    @Override
    public Type<DialogueLinePayload> type() {
        return TYPE;
    }

    public static void handleOnClient(DialogueLinePayload payload, IPayloadContext context) {
        ClientDialogue.onLine(payload);
    }

    /**
     * @param name       the participant's own name, if the dialogue gives one
     * @param entityName the speaking entity's name, used when there is no {@code name}
     * @param entityId   the speaking entity (for the portrait), or -1
     * @param portrait   a texture for speakers without an entity
     */
    public record Speaker(Optional<ResolvedText> name, Optional<Component> entityName, int entityId, Optional<ResourceLocation> portrait) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Speaker> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.optional(ResolvedText.STREAM_CODEC), Speaker::name,
                ByteBufCodecs.optional(ComponentSerialization.STREAM_CODEC), Speaker::entityName,
                ByteBufCodecs.VAR_INT, Speaker::entityId,
                ByteBufCodecs.optional(ResourceLocation.STREAM_CODEC), Speaker::portrait,
                Speaker::new);
    }
}
