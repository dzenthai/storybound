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

    public record Snapshot(Map<ResourceLocation, Map<ResourceLocation, String>> definitions, List<String> issues) implements CustomPacketPayload {
        public static final Type<Snapshot> TYPE = new Type<>(Storybound.id("dev_snapshot"));
        public static final StreamCodec<ByteBuf, Snapshot> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.map(HashMap::new, ResourceLocation.STREAM_CODEC, DEFINITION_MAP), Snapshot::definitions,
                ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.STRING_UTF8), Snapshot::issues,
                Snapshot::new);

        @Override
        public Type<Snapshot> type() {
            return TYPE;
        }

        public static void handleOnClient(Snapshot payload, IPayloadContext context) {
            ClientDevStore.setSnapshot(payload.definitions(), payload.issues());
        }
    }

    public record SaveDefinition(ResourceLocation typeId, ResourceLocation id, String json, Optional<String> sbtText) implements CustomPacketPayload {
        public static final Type<SaveDefinition> TYPE = new Type<>(Storybound.id("dev_save_definition"));
        public static final StreamCodec<ByteBuf, SaveDefinition> STREAM_CODEC = StreamCodec.composite(
                ResourceLocation.STREAM_CODEC, SaveDefinition::typeId,
                ResourceLocation.STREAM_CODEC, SaveDefinition::id,
                LONG_STRING, SaveDefinition::json,
                ByteBufCodecs.optional(LONG_STRING), SaveDefinition::sbtText,
                SaveDefinition::new);

        @Override
        public Type<SaveDefinition> type() {
            return TYPE;
        }

        public static void handleOnServer(SaveDefinition payload, IPayloadContext context) {
            if (context.player() instanceof ServerPlayer player) {
                DevModeServer.saveDefinition(player, payload.typeId(), payload.id(), payload.json(), payload.sbtText());
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
}

