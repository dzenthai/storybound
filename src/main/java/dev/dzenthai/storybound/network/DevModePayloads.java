package dev.dzenthai.storybound.network;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.client.dev.ClientDevStore;
import dev.dzenthai.storybound.core.dev.DevModeServer;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public final class DevModePayloads {
    private static final int MAX_JSON = 1 << 20;
    private static final StreamCodec<ByteBuf, String> LONG_STRING = ByteBufCodecs.stringUtf8(MAX_JSON);
    private static final StreamCodec<ByteBuf, Map<ResourceLocation, String>> DEFINITION_MAP =
            ByteBufCodecs.map(HashMap::new, ResourceLocation.STREAM_CODEC, LONG_STRING);
    private static final StreamCodec<ByteBuf, Map<String, String>> TRANSLATIONS_MAP =
            ByteBufCodecs.map(HashMap::new, ByteBufCodecs.STRING_UTF8, LONG_STRING);

    private DevModePayloads() {}

    public record RequestSnapshot() implements CustomPacketPayload {
        public static final Type<RequestSnapshot> TYPE = new Type<>(Storybound.id("dev_request_snapshot"));
        public static final StreamCodec<ByteBuf, RequestSnapshot> STREAM_CODEC = StreamCodec.unit(new RequestSnapshot());

        @Override
        public Type<RequestSnapshot> type() {
            return TYPE;
        }

        public static void handleOnServer(RequestSnapshot payload, IPayloadContext context) {
            if (context.player() instanceof ServerPlayer player) {
                DevModeServer.sendSnapshot(player);
            }
        }
    }

    public record Snapshot(Map<ResourceLocation, Map<ResourceLocation, String>> definitions, List<String> issues, String sourceLanguage, List<String> languages) implements CustomPacketPayload {
        public static final Type<Snapshot> TYPE = new Type<>(Storybound.id("dev_snapshot"));
        public static final StreamCodec<ByteBuf, Snapshot> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.map(HashMap::new, ResourceLocation.STREAM_CODEC, DEFINITION_MAP), Snapshot::definitions,
                ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.STRING_UTF8), Snapshot::issues,
                ByteBufCodecs.STRING_UTF8, Snapshot::sourceLanguage,
                ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.STRING_UTF8), Snapshot::languages,
                Snapshot::new);

        @Override
        public Type<Snapshot> type() {
            return TYPE;
        }

        public static void handleOnClient(Snapshot payload, IPayloadContext context) {
            ClientDevStore.setSnapshot(payload.definitions(), payload.issues(), payload.sourceLanguage(), payload.languages());
        }
    }

    public record SaveDefinition(ResourceLocation typeId, ResourceLocation id, String json, Optional<String> sbtText, String language, Map<String, String> translations) implements CustomPacketPayload {
        public static final Type<SaveDefinition> TYPE = new Type<>(Storybound.id("dev_save_definition"));
        public static final StreamCodec<ByteBuf, SaveDefinition> STREAM_CODEC = StreamCodec.composite(
                ResourceLocation.STREAM_CODEC, SaveDefinition::typeId,
                ResourceLocation.STREAM_CODEC, SaveDefinition::id,
                LONG_STRING, SaveDefinition::json,
                ByteBufCodecs.optional(LONG_STRING), SaveDefinition::sbtText,
                ByteBufCodecs.STRING_UTF8, SaveDefinition::language,
                TRANSLATIONS_MAP, SaveDefinition::translations,
                SaveDefinition::new);

        @Override
        public Type<SaveDefinition> type() {
            return TYPE;
        }

        public static void handleOnServer(SaveDefinition payload, IPayloadContext context) {
            if (context.player() instanceof ServerPlayer player) {
                DevModeServer.saveDefinition(player, payload.typeId(), payload.id(), payload.json(), payload.sbtText(), payload.language(), payload.translations());
            }
        }
    }

    public record SaveProjectSettings(String sourceLanguage, List<String> languages) implements CustomPacketPayload {
        public static final Type<SaveProjectSettings> TYPE = new Type<>(Storybound.id("dev_save_project_settings"));
        public static final StreamCodec<ByteBuf, SaveProjectSettings> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, SaveProjectSettings::sourceLanguage,
                ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.STRING_UTF8), SaveProjectSettings::languages,
                SaveProjectSettings::new);

        @Override
        public Type<SaveProjectSettings> type() {
            return TYPE;
        }

        public static void handleOnServer(SaveProjectSettings payload, IPayloadContext context) {
            if (context.player() instanceof ServerPlayer player) {
                DevModeServer.saveProjectSettings(player, payload.sourceLanguage(), payload.languages());
            }
        }
    }

    public record SaveTranslations(String language, Map<String, String> translations) implements CustomPacketPayload {
        public static final Type<SaveTranslations> TYPE = new Type<>(Storybound.id("dev_save_translations"));
        public static final StreamCodec<ByteBuf, SaveTranslations> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, SaveTranslations::language,
                TRANSLATIONS_MAP, SaveTranslations::translations,
                SaveTranslations::new);

        @Override
        public Type<SaveTranslations> type() {
            return TYPE;
        }

        public static void handleOnServer(SaveTranslations payload, IPayloadContext context) {
            if (context.player() instanceof ServerPlayer player) {
                DevModeServer.saveTranslations(player, payload.language(), payload.translations());
            }
        }
    }

    public record DeleteDefinition(ResourceLocation typeId, ResourceLocation id) implements CustomPacketPayload {
        public static final Type<DeleteDefinition> TYPE = new Type<>(Storybound.id("dev_delete_definition"));
        public static final StreamCodec<ByteBuf, DeleteDefinition> STREAM_CODEC = StreamCodec.composite(
                ResourceLocation.STREAM_CODEC, DeleteDefinition::typeId,
                ResourceLocation.STREAM_CODEC, DeleteDefinition::id,
                DeleteDefinition::new);

        @Override
        public Type<DeleteDefinition> type() {
            return TYPE;
        }

        public static void handleOnServer(DeleteDefinition payload, IPayloadContext context) {
            if (context.player() instanceof ServerPlayer player) {
                DevModeServer.deleteDefinition(player, payload.typeId(), payload.id());
            }
        }
    }

    public record QuickAction(String action, ResourceLocation targetId) implements CustomPacketPayload {
        public static final Type<QuickAction> TYPE = new Type<>(Storybound.id("dev_quick_action"));
        public static final StreamCodec<ByteBuf, QuickAction> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, QuickAction::action,
                ResourceLocation.STREAM_CODEC, QuickAction::targetId,
                QuickAction::new);

        @Override
        public Type<QuickAction> type() {
            return TYPE;
        }

        public static void handleOnServer(QuickAction payload, IPayloadContext context) {
            if (context.player() instanceof ServerPlayer player) {
                DevModeServer.quickAction(player, payload.action(), payload.targetId());
            }
        }
    }

    /** The editor asks for the server's KubeJS scripts (see {@link Scripts}). */
    public record RequestScripts() implements CustomPacketPayload {
        public static final Type<RequestScripts> TYPE = new Type<>(Storybound.id("dev_request_scripts"));
        public static final StreamCodec<ByteBuf, RequestScripts> STREAM_CODEC = StreamCodec.unit(new RequestScripts());

        @Override
        public Type<RequestScripts> type() {
            return TYPE;
        }

        public static void handleOnServer(RequestScripts payload, IPayloadContext context) {
            if (context.player() instanceof ServerPlayer player) {
                DevModeServer.sendScripts(player);
            }
        }
    }

    /** The server's scripts, their handlers and what they mention, as JSON ({@code DevScripts.describe}). */
    public record Scripts(String json) implements CustomPacketPayload {
        public static final Type<Scripts> TYPE = new Type<>(Storybound.id("dev_scripts"));
        public static final StreamCodec<ByteBuf, Scripts> STREAM_CODEC = LONG_STRING.map(Scripts::new, Scripts::json);

        @Override
        public Type<Scripts> type() {
            return TYPE;
        }

        public static void handleOnClient(Scripts payload, IPayloadContext context) {
            ClientDevStore.setScripts(payload.json());
        }
    }

    /** A new script file registering the handler, made from a template; {@code condition} or else an action. */
    public record CreateScript(ResourceLocation hook, boolean condition) implements CustomPacketPayload {
        public static final Type<CreateScript> TYPE = new Type<>(Storybound.id("dev_create_script"));
        public static final StreamCodec<ByteBuf, CreateScript> STREAM_CODEC = StreamCodec.composite(
                ResourceLocation.STREAM_CODEC, CreateScript::hook,
                ByteBufCodecs.BOOL, CreateScript::condition,
                CreateScript::new);

        @Override
        public Type<CreateScript> type() {
            return TYPE;
        }

        public static void handleOnServer(CreateScript payload, IPayloadContext context) {
            if (context.player() instanceof ServerPlayer player) {
                DevModeServer.createScript(player, payload.hook(), payload.condition());
            }
        }
    }
}
