package dev.dzenthai.storybound.network;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.client.ClientModules;
import dev.dzenthai.storybound.config.StoryboundConfig;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** The server's enabled modules; the client hides tabs and HUD parts of disabled ones. */
public record ModulesPayload(List<String> enabled) implements CustomPacketPayload {
    public static final Type<ModulesPayload> TYPE = new Type<>(Storybound.id("modules"));
    public static final StreamCodec<ByteBuf, ModulesPayload> STREAM_CODEC =
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()).map(ModulesPayload::new, ModulesPayload::enabled);

    public static ModulesPayload fromConfig() {
        StoryboundConfig.Common config = StoryboundConfig.COMMON;
        List<String> enabled = new ArrayList<>();
        addIf(enabled, config.quests.get(), "quests");
        addIf(enabled, config.journal.get(), "journal");
        addIf(enabled, config.codex.get(), "codex");
        addIf(enabled, config.bosses.get(), "bosses");
        addIf(enabled, config.waypoints.get(), "waypoints");
        addIf(enabled, config.dialogues.get(), "dialogues");
        return new ModulesPayload(enabled);
    }

    private static void addIf(List<String> list, boolean condition, String module) {
        if (condition) {
            list.add(module);
        }
    }

    @Override
    public Type<ModulesPayload> type() {
        return TYPE;
    }

    public static void handleOnClient(ModulesPayload payload, IPayloadContext context) {
        ClientModules.apply(payload.enabled());
    }
}
