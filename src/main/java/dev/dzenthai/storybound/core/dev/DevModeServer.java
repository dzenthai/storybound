package dev.dzenthai.storybound.core.dev;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonWriter;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.CoreDefinitions;
import dev.dzenthai.storybound.core.data.ConfigStorySource;
import dev.dzenthai.storybound.core.data.DefinitionType;
import dev.dzenthai.storybound.core.data.DefinitionTypes;
import dev.dzenthai.storybound.core.data.ProjectSettings;
import dev.dzenthai.storybound.core.data.ValidationIssue;
import dev.dzenthai.storybound.core.data.ValidationReport;
import dev.dzenthai.storybound.core.data.ValidationService;
import dev.dzenthai.storybound.core.logic.StoryEvents;
import dev.dzenthai.storybound.core.state.QuestStatus;
import dev.dzenthai.storybound.core.text.ConfigTexts;
import dev.dzenthai.storybound.module.boss.BossEvents;
import dev.dzenthai.storybound.module.codex.CodexEvents;
import dev.dzenthai.storybound.module.dialogue.DialogueRegistries;
import dev.dzenthai.storybound.module.dialogue.Dialogues;
import dev.dzenthai.storybound.module.dialogue.NpcBindings;
import dev.dzenthai.storybound.module.document.DocumentDefinition;
import dev.dzenthai.storybound.module.document.DocumentRegistries;
import dev.dzenthai.storybound.module.document.Documents;
import dev.dzenthai.storybound.module.quest.ChapterMap;
import dev.dzenthai.storybound.module.quest.QuestEvents;
import dev.dzenthai.storybound.module.quest.Quests;
import dev.dzenthai.storybound.module.waypoint.Waypoints;
import dev.dzenthai.storybound.network.ConfigTextsPayload;
import dev.dzenthai.storybound.network.DefinitionSync;
import dev.dzenthai.storybound.network.DevModePayloads;
import dev.dzenthai.storybound.network.StoryboundNetwork;
import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

/**
 * The server side of the dev mode editor. Every request needs permission level 2. A save writes the story folder
 * ({@code config/storybound/}), replaces the definition in the server store, rebuilds what a data load rebuilds,
 * re-validates and syncs every online player; nothing outside the story folder is ever written.
 */
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
        // The report as of the last data load or save (both validate); opening the editor does not re-run the checks.
        ValidationReport report = ValidationService.current();
        List<String> issues = report.issues().stream().map(ValidationIssue::describe).toList();
        ProjectSettings settings = ProjectSettings.load(ConfigStorySource.root());
        StoryboundNetwork.sendToPlayer(player, new DevModePayloads.Snapshot(definitions, issues, settings.sourceLanguage(), settings.languages()));
    }

    /**
     * Every definition as the editor edits it: the story folder's file as the author wrote it (field order, defaults
     * written out, fields the game does not know), else, for a datapack's definition, as the codec writes it.
     */
    private static <T> Map<ResourceLocation, String> encodeStore(DefinitionType<T> type, RegistryOps<JsonElement> ops) {
        Map<ResourceLocation, String> encoded = new LinkedHashMap<>();
        type.serverStore().all().forEach((id, value) -> {
            JsonElement written = storyFile(type, id, ops);
            if (written != null) {
                encoded.put(id, GSON.toJson(written));
            } else {
                type.codec().encodeStart(ops, value).result().ifPresent(json -> encoded.put(id, GSON.toJson(json)));
            }
        });
        return encoded;
    }

    /** The definition's file in the story folder, when there is one that loads; null otherwise (a datapack's, a broken one). */
    private static <T> @Nullable JsonElement storyFile(DefinitionType<T> type, ResourceLocation id, RegistryOps<JsonElement> ops) {
        try {
            Path file = ConfigStorySource.fileFor(ConfigStorySource.root(), type, id);
            if (!Files.isRegularFile(file)) {
                return null;
            }
            JsonElement json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            return type.codec().parse(ops, json).isSuccess() ? json : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    public static void saveDefinition(ServerPlayer player, ResourceLocation typeId, ResourceLocation id, String json, Optional<String> sbtText, String language, Map<String, String> translations) {
        if (!player.hasPermissions(2)) {
            return;
        }
        DefinitionType<?> type = findType(typeId);
        if (type == null) {
            player.sendSystemMessage(Component.literal("Unknown definition type: " + typeId).withStyle(ChatFormatting.RED));
            return;
        }
        if ((sbtText.isPresent() || !translations.isEmpty()) && !ConfigStorySource.isLanguageCode(language)) {
            player.sendSystemMessage(Component.literal("Not a language code: " + language).withStyle(ChatFormatting.RED));
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
            writeSbt(textId, language, sbtText.get());
        }
        if (!translations.isEmpty() && !writeTranslations(player, language, translations)) {
            return;
        }
        ConfigTexts.reload();
        rebuildAndSync(player.server);
        sendSnapshot(player);
    }

    public static void saveProjectSettings(ServerPlayer player, String sourceLanguage, List<String> languages) {
        if (!player.hasPermissions(2)) {
            return;
        }
        if (!ConfigStorySource.isLanguageCode(sourceLanguage) || !languages.stream().allMatch(ConfigStorySource::isLanguageCode)) {
            player.sendSystemMessage(Component.literal("Not a language code in " + sourceLanguage + " / " + languages).withStyle(ChatFormatting.RED));
            return;
        }
        try {
            ProjectSettings.save(ConfigStorySource.root(), new ProjectSettings(sourceLanguage, languages));
        } catch (IOException e) {
            player.sendSystemMessage(Component.literal("Could not save project settings: " + e.getMessage()).withStyle(ChatFormatting.RED));
            return;
        }
        ConfigTexts.reload();
        rebuildAndSync(player.server);
        sendSnapshot(player);
    }

    /** Where an online player stands: every quest they know, for the editor's progress layer. */
    public static void sendProgress(ServerPlayer requester, String name) {
        if (!requester.hasPermissions(2)) {
            return;
        }
        ServerPlayer player = requester.server.getPlayerList().getPlayerByName(name);
        StoryboundNetwork.sendToPlayer(requester, new DevModePayloads.Progress(name, player != null ? Quests.known(player) : Map.of()));
    }

    /** Writes one long text of the story folder (a Codex section, a boss's lore) and sends the texts to everyone. */
    public static void saveText(ServerPlayer player, ResourceLocation textId, String language, String content) {
        if (!player.hasPermissions(2)) {
            return;
        }
        if (!ConfigStorySource.isLanguageCode(language)) {
            player.sendSystemMessage(Component.literal("Not a language code: " + language).withStyle(ChatFormatting.RED));
            return;
        }
        try {
            ConfigStorySource.sbtFileFor(ConfigStorySource.root(), language, textId);
        } catch (IllegalArgumentException e) {
            player.sendSystemMessage(Component.literal(e.getMessage()).withStyle(ChatFormatting.RED));
            return;
        }
        writeSbt(textId, language, content);
        ConfigTexts.reload();
        for (ServerPlayer online : player.server.getPlayerList().getPlayers()) {
            StoryboundNetwork.sendToPlayer(online, new ConfigTextsPayload(ConfigTexts.current()));
        }
    }

    public static void saveTranslations(ServerPlayer player, String language, Map<String, String> translations) {
        if (!player.hasPermissions(2)) {
            return;
        }
        if (!ConfigStorySource.isLanguageCode(language)) {
            player.sendSystemMessage(Component.literal("Not a language code: " + language).withStyle(ChatFormatting.RED));
            return;
        }
        if (!writeTranslations(player, language, translations)) {
            return;
        }
        ConfigTexts.reload();
        rebuildAndSync(player.server);
        sendSnapshot(player);
    }

    /**
     * Merges the translations into {@code lang/<language>.json}. A lang file that does not parse is left alone (and
     * the save refused): writing it would drop every translation the author had in it.
     *
     * @param language already checked with {@link ConfigStorySource#isLanguageCode}
     */
    private static boolean writeTranslations(ServerPlayer player, String language, Map<String, String> newTranslations) {
        Path file = langFile(language);
        Map<String, String> map;
        try {
            map = readLang(file);
        } catch (IOException e) {
            player.sendSystemMessage(Component.literal("config/storybound/lang/" + language + ".json is broken (" + e.getMessage()
                    + "); fix it first, translations were not saved").withStyle(ChatFormatting.RED));
            return false;
        }
        map.putAll(newTranslations);
        try {
            writeLang(file, map);
            return true;
        } catch (IOException e) {
            Storybound.LOGGER.warn("Storybound: could not write translations to {}: {}", file, e.getMessage());
            player.sendSystemMessage(Component.literal("Could not write " + file.getFileName() + ": " + e.getMessage()).withStyle(ChatFormatting.RED));
            return false;
        }
    }

    static Path langFile(String language) {
        return ConfigStorySource.root().resolve("lang").resolve(language + ".json");
    }

    /** A lang file's keys and texts, sorted; empty when there is none. Throws when it is there but does not parse. */
    static Map<String, String> readLang(Path file) throws IOException {
        Map<String, String> map = new TreeMap<>();
        if (Files.isRegularFile(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                    if (entry.getValue().isJsonPrimitive()) {
                        map.put(entry.getKey(), entry.getValue().getAsString());
                    }
                }
            } catch (JsonParseException | IllegalStateException e) {
                throw new IOException(e.getMessage(), e);
            }
        }
        return map;
    }

    /** Writes a lang file, keys sorted. */
    static void writeLang(Path file, Map<String, String> translations) throws IOException {
        JsonObject output = new JsonObject();
        new TreeMap<>(translations).forEach(output::addProperty);
        Files.createDirectories(file.getParent());
        Files.writeString(file, GSON.toJson(output) + "\n", StandardCharsets.UTF_8);
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
        // The codec only checks it: the file keeps what was sent, so the author's field order, written-out defaults
        // and fields the game does not know survive a save from the editor.
        Path target = ConfigStorySource.fileFor(ConfigStorySource.root(), type, id);
        Files.createDirectories(target.getParent());
        writeJson(target, element);
        Map<ResourceLocation, T> updated = new LinkedHashMap<>(type.serverStore().all());
        updated.put(id, value);
        type.serverStore().replace(updated);
    }

    /**
     * Writes the JSON in the file's own style (its indent and line ends, two spaces and {@code \n} for a new file).
     * A file that already holds the same JSON is left alone, so git sees no change for a definition nobody edited.
     */
    static void writeJson(Path file, JsonElement json) throws IOException {
        String indent = "  ";
        String newline = "\n";
        if (Files.isRegularFile(file)) {
            String before = Files.readString(file, StandardCharsets.UTF_8);
            try {
                if (JsonParser.parseString(before).equals(json)) {
                    return;
                }
            } catch (JsonParseException e) {
                // A broken file is replaced by the definition that loads.
            }
            newline = before.contains("\r\n") ? "\r\n" : "\n";
            Matcher first = Pattern.compile("\\n([ \\t]+)\\S").matcher(before);
            if (first.find()) {
                indent = first.group(1);
            }
        }
        StringWriter out = new StringWriter();
        JsonWriter writer = new JsonWriter(out);
        writer.setIndent(indent);
        GSON.toJson(json, writer);
        writer.flush();
        Files.writeString(file, out.toString().replace("\n", newline) + newline, StandardCharsets.UTF_8);
    }

    private static void writeSbt(ResourceLocation textId, String language, String content) {
        Path root = ConfigStorySource.root();
        try {
            Path file = ConfigStorySource.sbtFileFor(root, language, textId);
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            Storybound.LOGGER.warn("Storybound: could not write .sbt file for {} in {}: {}", textId, language, e.getMessage());
        }
    }

    public static void deleteDefinition(ServerPlayer player, ResourceLocation typeId, ResourceLocation id) {
        if (!player.hasPermissions(2)) {
            return;
        }
        DefinitionType<?> type = findType(typeId);
        if (type == null) {
            return;
        }
        try {
            removeAndDelete(type, id);
            if (type == DialogueRegistries.DIALOGUES) {
                removeAndDelete(CoreDefinitions.DIALOGUE_LAYOUTS, id);
            }
        } catch (IllegalArgumentException e) {
            player.sendSystemMessage(Component.literal(e.getMessage()).withStyle(ChatFormatting.RED));
            return;
        }
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

    /** The server's KubeJS scripts as the editor shows them (see {@link DevScripts#describe}). */
    public static void sendScripts(ServerPlayer player) {
        if (player.hasPermissions(2)) {
            StoryboundNetwork.sendToPlayer(player, new DevModePayloads.Scripts(DevScripts.describe(player.server).toString()));
        }
    }

    /**
     * Writes a new script file for the handler and reloads, so KubeJS registers it at once; then the editor gets the
     * scripts and the definitions again. An existing file is never touched.
     */
    public static void createScript(ServerPlayer player, ResourceLocation hook, boolean condition) {
        if (!player.hasPermissions(2)) {
            return;
        }
        MinecraftServer server = player.server;
        String file;
        try {
            file = DevScripts.createStub(server, hook, condition ? DevScripts.Kind.CONDITION : DevScripts.Kind.ACTION);
        } catch (IOException | IllegalArgumentException e) {
            player.sendSystemMessage(Component.literal("Could not create the script: " + e.getMessage()).withStyle(ChatFormatting.RED));
            return;
        }
        player.sendSystemMessage(Component.literal("Created kubejs/server_scripts/" + file + "; reloading so KubeJS loads it").withStyle(ChatFormatting.GREEN));
        server.reloadResources(server.getPackRepository().getSelectedIds()).whenComplete((result, error) -> server.execute(() -> {
            sendScripts(player);
            sendSnapshot(player);
        }));
    }

    public static void quickAction(ServerPlayer player, String action, ResourceLocation targetId) {
        if (!player.hasPermissions(2)) {
            return;
        }
        switch (action) {
            case "quest_start" -> Quests.setStatus(player, targetId, QuestStatus.ACTIVE);
            case "quest_complete" -> Quests.setStatus(player, targetId, QuestStatus.COMPLETED);
            case "quest_reset" -> Quests.setStatus(player, targetId, null);
            // No speaker: the dialogue's own participants play it out; the player cannot be its bound NPC.
            case "dialogue_start" -> Dialogues.start(player, targetId, null);
            case "document_give" -> Documents.give(player, targetId, 1);
            case "waypoint_tp" -> {
                switch (Waypoints.teleportTo(player, targetId)) {
                    case SEARCHING -> player.sendSystemMessage(Component.literal("Searching for the structure of " + targetId + " in the background; try again in a moment")
                            .withStyle(ChatFormatting.YELLOW));
                    case NOT_FOUND -> player.sendSystemMessage(Component.literal("Waypoint target not found (or out of range): " + targetId).withStyle(ChatFormatting.YELLOW));
                    case DONE -> {}
                }
            }
            default -> {
                if (action.startsWith("tag_entity:")) {
                    String[] parts = action.split(":", 3);
                    if (parts.length == 3) {
                        try {
                            int entityId = Integer.parseInt(parts[1]);
                            String tag = parts[2];
                            Entity entity = player.serverLevel().getEntity(entityId);
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
        // After the definitions: the map refers to them.
        ChapterMap.refresh(server);
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

