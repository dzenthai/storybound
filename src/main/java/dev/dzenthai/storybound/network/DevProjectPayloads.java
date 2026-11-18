package dev.dzenthai.storybound.network;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.client.dev.ClientDevStore;
import dev.dzenthai.storybound.core.dev.DevProjects;
import dev.dzenthai.storybound.core.text.ConfigTexts;
import io.netty.buffer.ByteBuf;
import java.util.List;
import java.util.function.BiConsumer;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * The packets of the editor's stories (November 18, 2026): {@link Edit} carries any editor request with the story it
 * was made for, {@link Action} a story's action (select, create, update), {@link State} the stories and the edited
 * one's texts to the editor, {@link Result} whether an action went through.
 */
public final class DevProjectPayloads {
    private DevProjectPayloads() {}

    private record Entry<T extends CustomPacketPayload>(CustomPacketPayload.Type<T> type, StreamCodec<ByteBuf, T> codec,
                                                       BiConsumer<T, IPayloadContext> handler) {
        @SuppressWarnings("unchecked")
        void encode(ByteBuf buffer, CustomPacketPayload value) {
            codec.encode(buffer, (T) value);
        }

        @SuppressWarnings("unchecked")
        void handle(CustomPacketPayload value, IPayloadContext context) {
            handler.accept((T) value, context);
        }
    }

    private static <T extends CustomPacketPayload> Entry<T> entry(CustomPacketPayload.Type<T> type,
            StreamCodec<ByteBuf, T> codec, BiConsumer<T, IPayloadContext> handler) {
        return new Entry<>(type, codec, handler);
    }

    private static final List<Entry<?>> ENTRIES = List.of(
        entry(DevModePayloads.RequestSnapshot.TYPE, DevModePayloads.RequestSnapshot.STREAM_CODEC, DevModePayloads.RequestSnapshot::handleOnServer),
        entry(DevModePayloads.SaveDefinition.TYPE, DevModePayloads.SaveDefinition.STREAM_CODEC, DevModePayloads.SaveDefinition::handleOnServer),
        entry(DevModePayloads.SaveProjectSettings.TYPE, DevModePayloads.SaveProjectSettings.STREAM_CODEC, DevModePayloads.SaveProjectSettings::handleOnServer),
        entry(DevModePayloads.SaveProjectOptions.TYPE, DevModePayloads.SaveProjectOptions.STREAM_CODEC, DevModePayloads.SaveProjectOptions::handleOnServer),
        entry(DevModePayloads.SaveTranslations.TYPE, DevModePayloads.SaveTranslations.STREAM_CODEC, DevModePayloads.SaveTranslations::handleOnServer),
        entry(DevModePayloads.SaveText.TYPE, DevModePayloads.SaveText.STREAM_CODEC, DevModePayloads.SaveText::handleOnServer),
        entry(DevModePayloads.DeleteDefinition.TYPE, DevModePayloads.DeleteDefinition.STREAM_CODEC, DevModePayloads.DeleteDefinition::handleOnServer),
        entry(DevModePayloads.RenameDefinition.TYPE, DevModePayloads.RenameDefinition.STREAM_CODEC, DevModePayloads.RenameDefinition::handleOnServer),
        entry(DevModePayloads.RenameInQuest.TYPE, DevModePayloads.RenameInQuest.STREAM_CODEC, DevModePayloads.RenameInQuest::handleOnServer),
        entry(DevModePayloads.RenameCodexSection.TYPE, DevModePayloads.RenameCodexSection.STREAM_CODEC, DevModePayloads.RenameCodexSection::handleOnServer),
        entry(DevModePayloads.MoveNamespace.TYPE, DevModePayloads.MoveNamespace.STREAM_CODEC, DevModePayloads.MoveNamespace::handleOnServer),
        entry(DevModePayloads.RenameInDialogue.TYPE, DevModePayloads.RenameInDialogue.STREAM_CODEC, DevModePayloads.RenameInDialogue::handleOnServer),
        entry(DevModePayloads.QuickAction.TYPE, DevModePayloads.QuickAction.STREAM_CODEC, DevModePayloads.QuickAction::handleOnServer),
        entry(DevModePayloads.RequestProgress.TYPE, DevModePayloads.RequestProgress.STREAM_CODEC, DevModePayloads.RequestProgress::handleOnServer),
        entry(DevModePayloads.RequestScripts.TYPE, DevModePayloads.RequestScripts.STREAM_CODEC, DevModePayloads.RequestScripts::handleOnServer),
        entry(DevModePayloads.CreateScript.TYPE, DevModePayloads.CreateScript.STREAM_CODEC, DevModePayloads.CreateScript::handleOnServer)
    );

    private static Entry<?> find(ResourceLocation id) {
        return ENTRIES.stream().filter(entry -> entry.type().id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown editor request: " + id));
    }

    public static void register(PayloadRegistrar registrar) {
        registrar.playToServer(Edit.TYPE, Edit.STREAM_CODEC, Edit::handleOnServer);
        registrar.playToServer(Action.TYPE, Action.STREAM_CODEC, Action::handleOnServer);
        registrar.playToClient(State.TYPE, State.STREAM_CODEC, State::handleOnClient);
        registrar.playToClient(Result.TYPE, Result.STREAM_CODEC, Result::handleOnClient);
        registrar.playToServer(DevModePayloads.RequestSnapshot.TYPE, DevModePayloads.RequestSnapshot.STREAM_CODEC,
                DevModePayloads.RequestSnapshot::handleOnServer);
    }

    public record Edit(String project, CustomPacketPayload request) implements CustomPacketPayload {
        public static final Type<Edit> TYPE = new Type<>(Storybound.id("dev_project_edit"));
        public static final StreamCodec<ByteBuf, Edit> STREAM_CODEC = new StreamCodec<>() {
            @Override
            public Edit decode(ByteBuf buffer) {
                String project = ByteBufCodecs.STRING_UTF8.decode(buffer);
                Entry<?> entry = find(ResourceLocation.STREAM_CODEC.decode(buffer));
                return new Edit(project, entry.codec().decode(buffer));
            }

            @Override
            public void encode(ByteBuf buffer, Edit value) {
                ByteBufCodecs.STRING_UTF8.encode(buffer, value.project());
                ResourceLocation.STREAM_CODEC.encode(buffer, value.request().type().id());
                find(value.request().type().id()).encode(buffer, value.request());
            }
        };

        @Override
        public Type<Edit> type() { return TYPE; }

        public static void handleOnServer(Edit payload, IPayloadContext context) {
            if (context.player() instanceof ServerPlayer player && DevProjects.accepts(player, payload.project())) {
                find(payload.request().type().id()).handle(payload.request(), context);
            }
        }
    }

    public record Action(String action, String project, String title, boolean enabled) implements CustomPacketPayload {
        public static final Type<Action> TYPE = new Type<>(Storybound.id("dev_project_action"));
        public static final StreamCodec<ByteBuf, Action> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Action::action,
                ByteBufCodecs.STRING_UTF8, Action::project,
                ByteBufCodecs.STRING_UTF8, Action::title,
                ByteBufCodecs.BOOL, Action::enabled, Action::new);

        @Override
        public Type<Action> type() { return TYPE; }

        public static void handleOnServer(Action payload, IPayloadContext context) {
            if (context.player() instanceof ServerPlayer player) {
                DevProjects.action(player, payload.action(), payload.project(), payload.title(), payload.enabled());
            }
        }
    }

    public record Result(boolean success) implements CustomPacketPayload {
        public static final Type<Result> TYPE = new Type<>(Storybound.id("dev_project_result"));
        public static final StreamCodec<ByteBuf, Result> STREAM_CODEC = ByteBufCodecs.BOOL.map(Result::new, Result::success);

        @Override
        public Type<Result> type() { return TYPE; }

        public static void handleOnClient(Result payload, IPayloadContext context) {
            ClientDevStore.projectActionFinished(payload.success());
        }
    }

    public record State(String selected, String projects, ConfigTexts.Texts texts) implements CustomPacketPayload {
        public static final Type<State> TYPE = new Type<>(Storybound.id("dev_projects"));
        public static final StreamCodec<ByteBuf, State> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, State::selected,
                ByteBufCodecs.stringUtf8(1 << 20), State::projects,
                ConfigTexts.Texts.STREAM_CODEC, State::texts, State::new);

        @Override
        public Type<State> type() { return TYPE; }

        public static void handleOnClient(State payload, IPayloadContext context) {
            ClientDevStore.setProject(payload.selected(), payload.projects(), payload.texts());
        }
    }
}
