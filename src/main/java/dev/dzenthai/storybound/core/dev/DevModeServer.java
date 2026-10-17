package dev.dzenthai.storybound.core.dev;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.data.ConfigStorySource;
import dev.dzenthai.storybound.core.data.DefinitionType;
import dev.dzenthai.storybound.core.data.DefinitionTypes;
import dev.dzenthai.storybound.core.data.StoryFolder;
import dev.dzenthai.storybound.core.data.ValidationIssue;
import dev.dzenthai.storybound.core.data.ValidationReport;
import dev.dzenthai.storybound.core.data.ValidationService;
import dev.dzenthai.storybound.core.logic.StoryEvents;
import dev.dzenthai.storybound.core.state.QuestStatus;
import dev.dzenthai.storybound.core.text.ConfigTexts;
import dev.dzenthai.storybound.module.boss.BossEvents;
import dev.dzenthai.storybound.module.codex.CodexEvents;
import dev.dzenthai.storybound.module.dialogue.Dialogues;
import dev.dzenthai.storybound.module.dialogue.NpcBindings;
import dev.dzenthai.storybound.module.document.DocumentDefinition;
import dev.dzenthai.storybound.module.document.DocumentRegistries;
import dev.dzenthai.storybound.module.document.Documents;
import dev.dzenthai.storybound.module.quest.QuestEvents;
import dev.dzenthai.storybound.module.quest.Quests;
import dev.dzenthai.storybound.module.waypoint.WaypointDefinition;
import dev.dzenthai.storybound.module.waypoint.WaypointRegistries;
import dev.dzenthai.storybound.module.waypoint.WaypointTarget;
import dev.dzenthai.storybound.module.waypoint.Waypoints;
import dev.dzenthai.storybound.network.ConfigTextsPayload;
import dev.dzenthai.storybound.network.DefinitionSync;
import dev.dzenthai.storybound.network.DevModePayloads;
import dev.dzenthai.storybound.network.StoryboundNetwork;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

public final class DevModeServer {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private DevModeServer() {}

    public static void sendSnapshot(ServerPlayer player) {
        if (!player.hasPermissions(2)) {
            return;
        }
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, player.server.registryAccess());
        Map<ResourceLocation, Map<ResourceLocation, String>> definitions = new LinkedHashMap<>();
        for (DefinitionType<?> type : DefinitionTypes.all()) {
            definitions.put(type.id(), encodeStore(type, ops));
        }
        ValidationReport report = ValidationService.validate();
        ValidationService.publish(report);
        List<String> issues = report.issues().stream().map(ValidationIssue::describe).toList();
        StoryboundNetwork.sendToPlayer(player, new DevModePayloads.Snapshot(definitions, issues));
    }

    private static <T> Map<ResourceLocation, String> encodeStore(DefinitionType<T> type, RegistryOps<JsonElement> ops) {
        Map<ResourceLocation, String> encoded = new LinkedHashMap<>();
        type.serverStore().all().forEach((id, value) -> type.codec().encodeStart(ops, value)
                .result()
                .ifPresent(json -> encoded.put(id, GSON.toJson(json))));
        return encoded;
    }

    public static void saveDefinition(ServerPlayer player, ResourceLocation typeId, ResourceLocation id, String json, Optional<String> sbtText) {
        if (!player.hasPermissions(2)) {
            return;
        }
        DefinitionType<?> type = findType(typeId);
        if (type == null) {
            player.sendSystemMessage(Component.literal("Unknown definition type: " + typeId).withStyle(ChatFormatting.RED));
            return;
        }
        try {
            parseAndStore(type, id, json, player.server.registryAccess());
        } catch (Exception e) {
            player.sendSystemMessage(Component.literal("Save failed for " + id + ": " + e.getMessage()).withStyle(ChatFormatting.RED));
            return;
        }
        if (sbtText.isPresent()) {
            ResourceLocation textId = id;
            if (type == DocumentRegistries.DOCUMENTS) {
                DocumentDefinition doc = DocumentRegistries.DOCUMENTS.serverStore().getOrNull(id);
                if (doc != null) {
                    textId = doc.contentId(id);
                }
            }
            writeSbt(textId, sbtText.get());
        }
        rebuildAndSync(player.server);
        sendSnapshot(player);
    }

    private static <T> void parseAndStore(DefinitionType<T> type, ResourceLocation id, String rawJson, HolderLookup.Provider registries) throws IOException {
        JsonElement element;
        try {
            element = JsonParser.parseString(rawJson);
        } catch (JsonParseException e) {
            throw new IOException("Malformed JSON: " + e.getMessage(), e);
        }
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, registries);
        DataResult<T> parsed = type.codec().parse(ops, element);
        T value = parsed.getOrThrow(IOException::new);
        JsonElement canonical = type.codec().encodeStart(ops, value).getOrThrow(IOException::new);
        Path target = ConfigStorySource.fileFor(ConfigStorySource.root(), type, id);
        Files.createDirectories(target.getParent());
        Files.writeString(target, GSON.toJson(canonical) + "\n", StandardCharsets.UTF_8);
        Map<ResourceLocation, T> updated = new LinkedHashMap<>(type.serverStore().all());
        updated.put(id, value);
        type.serverStore().replace(updated);
    }

    private static void writeSbt(ResourceLocation textId, String content) {
        Path root = ConfigStorySource.root();
        for (String lang : List.of("en_us", "ru_ru")) {
            try {
                Path file = ConfigStorySource.sbtFileFor(root, lang, textId);
                Files.createDirectories(file.getParent());
                Files.writeString(file, content, StandardCharsets.UTF_8);
            } catch (IOException e) {
                Storybound.LOGGER.warn("Storybound: could not write .sbt file for {}: {}", textId, e.getMessage());
            }
        }
        ConfigTexts.reload();
    }

    public static void deleteDefinition(ServerPlayer player, ResourceLocation typeId, ResourceLocation id) {
        if (!player.hasPermissions(2)) {
            return;
        }
        DefinitionType<?> type = findType(typeId);
        if (type == null) {
            return;
        }
        removeAndDelete(type, id);
        rebuildAndSync(player.server);
        sendSnapshot(player);
    }

    private static <T> void removeAndDelete(DefinitionType<T> type, ResourceLocation id) {
        Path target = ConfigStorySource.fileFor(ConfigStorySource.root(), type, id);
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            Storybound.LOGGER.warn("Storybound: could not delete {}: {}", target, e.getMessage());
        }
        Map<ResourceLocation, T> updated = new LinkedHashMap<>(type.serverStore().all());
        updated.remove(id);
        type.serverStore().replace(updated);
    }

    public static void quickAction(ServerPlayer player, String action, ResourceLocation targetId) {
        if (!player.hasPermissions(2)) {
            return;
        }
        switch (action) {
            case "quest_start" -> Quests.setStatus(player, targetId, QuestStatus.ACTIVE);
            case "quest_complete" -> Quests.setStatus(player, targetId, QuestStatus.COMPLETED);
            case "quest_reset" -> Quests.setStatus(player, targetId, null);
            case "dialogue_start" -> Dialogues.start(player, targetId, player);
            case "document_give" -> Documents.give(player, targetId, 1);
            case "waypoint_tp" -> {
                if (!Waypoints.teleportTo(player, targetId)) {
                    player.sendSystemMessage(Component.literal("Waypoint target not found (or out of range): " + targetId).withStyle(ChatFormatting.YELLOW));
                }
            }
            case "install_example" -> {
                try {
                    StoryFolder.installExample(ConfigStorySource.root());
                    player.server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), "reload");
                    sendSnapshot(player);
                } catch (IOException e) {
                    player.sendSystemMessage(Component.literal("Could not install example: " + e.getMessage()).withStyle(ChatFormatting.RED));
                }
            }
            default -> {
                if (action.startsWith("tag_entity:")) {
                    String[] parts = action.split(":", 3);
                    if (parts.length == 3) {
                        try {
                            int entityId = Integer.parseInt(parts[1]);
                            String tag = parts[2];
                            net.minecraft.world.entity.Entity entity = player.serverLevel().getEntity(entityId);
                            if (entity != null && !tag.isBlank()) {
                                entity.addTag(tag);
                                player.sendSystemMessage(Component.literal("✓ Tagged entity " + entity.getName().getString() + " with: " + tag).withStyle(ChatFormatting.GREEN));
                            }
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }
        }
    }

    private static void rebuildAndSync(MinecraftServer server) {
        StoryEvents.rebuild();
        QuestEvents.rebuild();
        CodexEvents.rebuild();
        BossEvents.rebuild();
        Waypoints.rebuild();
        NpcBindings.rebuild();
        ValidationService.publish(ValidationService.validate());
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            DefinitionSync.sendTo(online);
            StoryboundNetwork.sendToPlayer(online, new ConfigTextsPayload(ConfigTexts.current()));
            Waypoints.syncFull(online);
        }
    }

    private static DefinitionType<?> findType(ResourceLocation typeId) {
        for (DefinitionType<?> type : DefinitionTypes.all()) {
            if (type.id().equals(typeId)) {
                return type;
            }
        }
        return null;
    }
}

