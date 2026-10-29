package dev.dzenthai.storybound.core.dev;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.CoreDefinitions;
import dev.dzenthai.storybound.core.data.ConfigStorySource;
import dev.dzenthai.storybound.core.data.DefinitionType;
import dev.dzenthai.storybound.core.data.DefinitionTypes;
import dev.dzenthai.storybound.core.data.ValidationIssue;
import dev.dzenthai.storybound.core.state.StoryMigrations;
import dev.dzenthai.storybound.core.story.StoryLinks;
import dev.dzenthai.storybound.core.story.StoryRenames;
import dev.dzenthai.storybound.core.story.StoryRenames.Rename;
import dev.dzenthai.storybound.core.text.ConfigTexts;
import dev.dzenthai.storybound.module.boss.BossDefinition;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * The server side of renaming a node in the dev mode (see {@link StoryRenames} for the rules): the file moves, what
 * belongs to it by name moves along (a dialogue's layout, a document's text, Codex and boss section texts, an NPC's
 * relationship scale), every story file that names it is rewritten, flags also in the lang files, and the rename is
 * added to {@code migrations/dev_renames.json} so players' saved progress follows it, now and when they join. KubeJS
 * scripts are not rewritten: the author is told which name the old id.
 */
final class DevRenames {
    /** The migrations file the editor keeps: {@code storybound:dev_renames}. */
    static final ResourceLocation MIGRATIONS = Storybound.id("dev_renames");

    private DevRenames() {}

    static void rename(ServerPlayer player, ResourceLocation typeId, ResourceLocation from, ResourceLocation to) {
        MinecraftServer server = player.server;
        DefinitionType<?> type = DevModeServer.findType(typeId);
        if (type == null || !StoryRenames.RENAMEABLE.contains(typeId)) {
            refuse(player, "Cannot rename a " + typeId);
            return;
        }
        Optional<StoryRenames.Problem> problem = StoryRenames.problem(typeId, from, to.toString(), DevRenames::exists);
        if (problem.isPresent()) {
            refuse(player, "Cannot rename " + from + " to " + to + ": " + switch (problem.get()) {
                case SAME -> "it is the same id";
                case FORMAT -> "not a name (one word: lowercase letters, digits, _ - .)";
                case NAMESPACE -> "a node keeps its namespace; the story's is set in the project";
                case TAKEN -> "there is already one";
                case SCALE_TAKEN -> "the relationship scale " + StoryRenames.relationshipScale(to) + " already exists";
            });
            return;
        }
        Path root = ConfigStorySource.root();
        List<Rename> renames = new ArrayList<>();
        for (Rename rename : StoryRenames.cascade(new Rename(typeId, from, to), DevRenames::exists)) {
            if (Files.isRegularFile(ConfigStorySource.fileFor(root, DevModeServer.findType(rename.type()), rename.from()))) {
                renames.add(rename);
            } else if (rename.type().equals(typeId)) {
                // A datapack's: its file is not ours to move, and the old id would stay defined next to the new one.
                refuse(player, "Cannot rename " + from + ": it comes from a datapack, not from the story folder");
                return;
            } else {
                player.sendSystemMessage(Component.literal(rename.from() + " comes from a datapack and keeps its name").withStyle(ChatFormatting.GOLD));
            }
        }
        try {
            int references = apply(root, renames);
            List<String> scripts = scriptsNaming(server, renames);
            reload(server, renames);
            player.sendSystemMessage(Component.literal("Renamed " + from + " to " + to + (references > 0 ? "; " + references + " references updated" : ""))
                    .withStyle(ChatFormatting.GREEN));
            if (!scripts.isEmpty()) {
                player.sendSystemMessage(Component.literal("KubeJS scripts still name " + from + ": " + String.join(", ", scripts)
                        + ". Change them by hand").withStyle(ChatFormatting.GOLD));
            }
        } catch (IOException | RuntimeException e) {
            Storybound.LOGGER.error("Storybound: renaming {} to {} failed", from, to, e);
            refuse(player, "Renaming " + from + " failed: " + e.getMessage());
        }
    }

    /**
     * Moves every node of the story folder in one namespace to another, as many renames at once (the story's namespace
     * changed in the project, October 29, 2026): what names them, their texts and players' progress follow. Refused as a
     * whole when a node of the same kind already has a new id; {@code demo} and {@code minecraft} are never moved.
     */
    static void moveNamespace(ServerPlayer player, String from, String to) {
        if (!StoryRenames.isStoryNamespace(to) && !to.equals(dev.dzenthai.storybound.core.data.ProjectSettings.DEFAULT_NAMESPACE) || from.equals(to) || from.equals("demo") || from.equals("minecraft")) {
            refuse(player, "Cannot move the nodes of " + from + " to " + to);
            return;
        }
        Path root = ConfigStorySource.root();
        List<String> taken = new ArrayList<>();
        List<Rename> renames = namespaceRenames(root, from, to, DevRenames::exists, taken);
        if (!taken.isEmpty()) {
            refuse(player, "Cannot move the nodes of " + from + " to " + to + ": " + String.join(", ", taken.subList(0, Math.min(5, taken.size())))
                    + (taken.size() > 5 ? " and " + (taken.size() - 5) + " more" : "") + " already exist");
            return;
        }
        if (renames.isEmpty()) {
            player.sendSystemMessage(Component.literal("No nodes of the story folder are in " + from).withStyle(ChatFormatting.GOLD));
            DevModeServer.sendSnapshot(player);
            return;
        }
        try {
            int references = apply(root, renames);
            List<String> scripts = scriptsNaming(player.server, renames);
            reload(player.server, renames);
            player.sendSystemMessage(Component.literal("Moved " + renames.size() + " nodes from " + from + " to " + to
                    + (references > 0 ? "; " + references + " references updated" : "")).withStyle(ChatFormatting.GREEN));
            if (!scripts.isEmpty()) {
                player.sendSystemMessage(Component.literal("KubeJS scripts still name ids of " + from + ": " + String.join(", ", scripts)
                        + ". Change them by hand").withStyle(ChatFormatting.GOLD));
            }
        } catch (IOException | RuntimeException e) {
            Storybound.LOGGER.error("Storybound: moving {} to {} failed", from, to, e);
            refuse(player, "Moving the nodes of " + from + " failed: " + e.getMessage());
        }
    }

    /**
     * The renames that move the story folder's nodes of one namespace to another; a new id a node of the same kind
     * already has goes to {@code taken} ("npc_binding my_story:elder"). Package-private for tests.
     */
    static List<Rename> namespaceRenames(Path root, String from, String to, java.util.function.BiPredicate<ResourceLocation, ResourceLocation> exists, List<String> taken) {
        List<Rename> renames = new ArrayList<>();
        for (DefinitionType<?> type : DefinitionTypes.all()) {
            if (!StoryRenames.RENAMEABLE.contains(type.id())) {
                continue;
            }
            Map<ResourceLocation, JsonElement> files = new LinkedHashMap<>();
            ConfigStorySource.scan(root, type, files, new ArrayList<ValidationIssue>());
            for (ResourceLocation id : files.keySet()) {
                if (!id.getNamespace().equals(from)) {
                    continue;
                }
                ResourceLocation target = ResourceLocation.fromNamespaceAndPath(to, id.getPath());
                if (exists.test(type.id(), target)) {
                    taken.add(type.id().getPath() + " " + target);
                } else {
                    renames.add(new Rename(type.id(), id, target));
                }
            }
        }
        return renames;
    }

    /**
     * Renames a node of a dialogue: the dialogue's file and layout, what names the node elsewhere (another dialogue's
     * jump or call, the trigger "dialogue ended at a node") and, through {@code flags} migrations, players' marks of
     * its options chosen once (their key carries the node's name).
     */
    static void renameInDialogue(ServerPlayer player, ResourceLocation dialogue, String from, String to) {
        Path root = ConfigStorySource.root();
        Path file = ConfigStorySource.fileFor(root, DevModeServer.findType(StoryLinks.DIALOGUE), dialogue);
        if (!Files.isRegularFile(file)) {
            refuse(player, "Cannot rename the node " + from + ": " + dialogue + " is not in the story folder");
            return;
        }
        try {
            JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            Optional<StoryRenames.Problem> problem = StoryRenames.nodeProblem(json, from, to);
            if (problem.isPresent() || !(json.get("nodes") instanceof JsonObject nodes) || !nodes.has(from)) {
                refuse(player, "Cannot rename the node " + from + " of " + dialogue + " to " + to + ": " + problem.map(Enum::name).orElse("no such node"));
                return;
            }
            int references = applyInDialogue(root, dialogue, json, from, to.trim());
            reload(player.server, List.of());
            player.sendSystemMessage(Component.literal("Renamed the node " + from + " of " + dialogue + " to " + to.trim()
                    + (references > 0 ? "; " + references + " references updated" : "")).withStyle(ChatFormatting.GREEN));
        } catch (IOException | RuntimeException e) {
            Storybound.LOGGER.error("Storybound: renaming the node {} of {} failed", from, dialogue, e);
            refuse(player, "Renaming the node " + from + " failed: " + e.getMessage());
        }
    }

    /** The file work of {@link #renameInDialogue}; returns how many arrows and references changed. Package-private for tests. */
    static int applyInDialogue(Path root, ResourceLocation dialogue, JsonObject json, String from, String to) throws IOException {
        // The options chosen once are remembered per player under a key with the node's name.
        List<Rename> marks = new ArrayList<>();
        if (json.getAsJsonObject("nodes").get(from) instanceof JsonObject node && node.get("options") instanceof JsonArray options) {
            for (int i = 0; i < options.size(); i++) {
                if (options.get(i) instanceof JsonObject option && option.get("once") instanceof JsonPrimitive once && once.getAsBoolean()) {
                    String id = option.get("id") instanceof JsonPrimitive own ? own.getAsString() : String.valueOf(i);
                    marks.add(new Rename(StoryRenames.FLAG, dev.dzenthai.storybound.module.dialogue.Dialogues.onceKey(dialogue, from, id),
                            dev.dzenthai.storybound.module.dialogue.Dialogues.onceKey(dialogue, to, id)));
                }
            }
        }
        int references = StoryRenames.renameNode(json, dialogue, from, to) - 1;
        write(ConfigStorySource.fileFor(root, DevModeServer.findType(StoryLinks.DIALOGUE), dialogue), json);
        Path layoutFile = ConfigStorySource.fileFor(root, CoreDefinitions.DIALOGUE_LAYOUTS, dialogue);
        if (Files.isRegularFile(layoutFile)) {
            JsonObject layout = JsonParser.parseString(Files.readString(layoutFile, StandardCharsets.UTF_8)).getAsJsonObject();
            if (StoryRenames.renameLayoutNode(layout, from, to)) {
                write(layoutFile, layout);
            }
        }
        for (DefinitionType<?> type : DefinitionTypes.all()) {
            if (!StoryRenames.REFERRING.contains(type.id())) {
                continue;
            }
            Map<ResourceLocation, JsonElement> files = new LinkedHashMap<>();
            ConfigStorySource.scan(root, type, files, new ArrayList<ValidationIssue>());
            for (Map.Entry<ResourceLocation, JsonElement> entry : files.entrySet()) {
                int changed = StoryRenames.rewriteNode(entry.getValue(), dialogue, from, to);
                if (changed > 0) {
                    DevModeServer.writeJson(ConfigStorySource.fileFor(root, type, entry.getKey()), entry.getValue());
                    references += changed;
                }
            }
        }
        if (!marks.isEmpty()) {
            JsonObject migrations = readMigrations(root);
            marks.forEach(mark -> StoryRenames.addMigration(migrations, "flags", mark.from(), mark.to()));
            write(ConfigStorySource.fileFor(root, CoreDefinitions.MIGRATIONS, MIGRATIONS), migrations);
        }
        return references;
    }

    /**
     * Renames a stage of a quest ({@code objective} empty) or an objective of a stage: the quest's file, what names the
     * stage elsewhere, and the migrations ({@code stages} / {@code objectives}) that move players' place and counters.
     */
    static void renameInQuest(ServerPlayer player, ResourceLocation quest, String stage, String objective, String to) {
        Path root = ConfigStorySource.root();
        DefinitionType<?> quests = DevModeServer.findType(StoryLinks.QUEST);
        Path file = ConfigStorySource.fileFor(root, quests, quest);
        String what = objective.isEmpty() ? "stage " + stage : "objective " + objective + " of stage " + stage;
        if (!Files.isRegularFile(file)) {
            refuse(player, "Cannot rename the " + what + ": " + quest + " is not in the story folder");
            return;
        }
        try {
            JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            Optional<StoryRenames.Problem> problem = objective.isEmpty() ? StoryRenames.stageProblem(json, stage, to)
                    : StoryRenames.objectiveProblem(json, stage, objective, to);
            if (problem.isPresent() || StoryRenames.stage(json, stage) == null) {
                refuse(player, "Cannot rename the " + what + " of " + quest + " to " + to + ": " + problem.map(Enum::name).orElse("no such stage"));
                return;
            }
            int references = applyInQuest(root, quest, json, stage, objective, to.trim());
            reload(player.server, List.of());
            player.sendSystemMessage(Component.literal("Renamed the " + what + " of " + quest + " to " + to.trim()
                    + (references > 0 ? "; " + references + " references updated" : "")).withStyle(ChatFormatting.GREEN));
        } catch (IOException | RuntimeException e) {
            Storybound.LOGGER.error("Storybound: renaming the {} of {} failed", what, quest, e);
            refuse(player, "Renaming the " + what + " failed: " + e.getMessage());
        }
    }

    /** The file work of {@link #renameInQuest}; returns how many references outside the renamed name changed. Package-private for tests. */
    static int applyInQuest(Path root, ResourceLocation quest, JsonObject json, String stage, String objective, String to) throws IOException {
        DefinitionType<?> quests = DevModeServer.findType(StoryLinks.QUEST);
        int references = 0;
        if (objective.isEmpty()) {
            references += StoryRenames.renameStage(json, stage, to) - 1;
        } else {
            StoryRenames.renameObjective(json, stage, objective, to);
        }
        write(ConfigStorySource.fileFor(root, quests, quest), json);
        if (objective.isEmpty()) {
            for (DefinitionType<?> type : DefinitionTypes.all()) {
                if (!StoryRenames.REFERRING.contains(type.id())) {
                    continue;
                }
                Map<ResourceLocation, JsonElement> files = new LinkedHashMap<>();
                ConfigStorySource.scan(root, type, files, new ArrayList<ValidationIssue>());
                for (Map.Entry<ResourceLocation, JsonElement> entry : files.entrySet()) {
                    int changed = StoryRenames.rewriteStage(entry.getValue(), quest, stage, to);
                    if (changed > 0) {
                        DevModeServer.writeJson(ConfigStorySource.fileFor(root, type, entry.getKey()), entry.getValue());
                        references += changed;
                    }
                }
            }
        }
        JsonObject migrations = readMigrations(root);
        StoryRenames.addInsideMigration(migrations, objective.isEmpty() ? "stages" : "objectives", quest, objective.isEmpty() ? stage : objective, to);
        write(ConfigStorySource.fileFor(root, CoreDefinitions.MIGRATIONS, MIGRATIONS), migrations);
        return references;
    }

    private static void refuse(ServerPlayer player, String message) {
        player.sendSystemMessage(Component.literal(message).withStyle(ChatFormatting.RED));
        // The editor showed the new name at once: this puts the old one back.
        DevModeServer.sendSnapshot(player);
    }

    private static boolean exists(ResourceLocation type, ResourceLocation id) {
        DefinitionType<?> definitions = DevModeServer.findType(type);
        return definitions != null && definitions.serverStore().getOrNull(id) != null;
    }

    /**
     * Moves the files and rewrites what names them; returns how many references changed. Public through
     * {@link #rename}; package-private for tests.
     */
    static int apply(Path root, List<Rename> renames) throws IOException {
        for (Rename rename : renames) {
            DefinitionType<?> type = DevModeServer.findType(rename.type());
            Path source = ConfigStorySource.fileFor(root, type, rename.from());
            JsonElement json = JsonParser.parseString(Files.readString(source, StandardCharsets.UTF_8));
            if (rename.type().equals(StoryLinks.SCRIPT) && json instanceof JsonObject script && !script.has("hook")) {
                // The handler keeps its name: the scripts register it under the block's old id.
                script.addProperty("hook", rename.from().toString());
            }
            moveTexts(root, rename, json);
            write(ConfigStorySource.fileFor(root, type, rename.to()), json);
            Files.delete(source);
            if (rename.type().equals(StoryLinks.DIALOGUE)) {
                move(ConfigStorySource.fileFor(root, CoreDefinitions.DIALOGUE_LAYOUTS, rename.from()),
                        ConfigStorySource.fileFor(root, CoreDefinitions.DIALOGUE_LAYOUTS, rename.to()));
            }
        }
        int references = 0;
        for (DefinitionType<?> type : DefinitionTypes.all()) {
            if (!StoryRenames.REFERRING.contains(type.id())) {
                continue;
            }
            Map<ResourceLocation, JsonElement> files = new LinkedHashMap<>();
            ConfigStorySource.scan(root, type, files, new ArrayList<ValidationIssue>());
            for (Map.Entry<ResourceLocation, JsonElement> file : files.entrySet()) {
                int changed = StoryRenames.rewrite(type.id(), file.getValue(), renames);
                if (changed > 0) {
                    DevModeServer.writeJson(ConfigStorySource.fileFor(root, type, file.getKey()), file.getValue());
                    references += changed;
                }
            }
        }
        references += rewriteLang(root, renames);
        addMigrations(root, renames);
        return references;
    }

    /** A document's text, Codex sections' and a boss's lore and tactics, when they are found by the id, in every language. */
    private static void moveTexts(Path root, Rename rename, JsonElement json) throws IOException {
        Map<ResourceLocation, ResourceLocation> texts = new LinkedHashMap<>();
        JsonObject object = json instanceof JsonObject value ? value : new JsonObject();
        if (rename.type().equals(StoryLinks.DOCUMENT) && !object.has("content")) {
            texts.put(rename.from(), rename.to());
        } else if (rename.type().equals(StoryLinks.CODEX) && object.get("sections") instanceof JsonArray sections) {
            for (JsonElement element : sections) {
                if (element instanceof JsonObject section && !section.has("text") && section.get("id") instanceof JsonPrimitive id) {
                    texts.put(codexText(rename.from(), id.getAsString()), codexText(rename.to(), id.getAsString()));
                }
            }
        } else if (rename.type().equals(StoryLinks.BOSS)) {
            for (String section : List.of("lore", "tactics")) {
                if (!(object.get(section) instanceof JsonObject part && part.has("text"))) {
                    texts.put(BossDefinition.defaultText(rename.from(), section), BossDefinition.defaultText(rename.to(), section));
                }
            }
        }
        Path folder = root.resolve("texts");
        if (texts.isEmpty() || !Files.isDirectory(folder)) {
            return;
        }
        try (Stream<Path> languages = Files.list(folder)) {
            for (Path language : languages.filter(Files::isDirectory).toList()) {
                String code = language.getFileName().toString();
                if (!ConfigStorySource.isLanguageCode(code)) {
                    continue;
                }
                for (Map.Entry<ResourceLocation, ResourceLocation> text : texts.entrySet()) {
                    move(ConfigStorySource.sbtFileFor(root, code, text.getKey()), ConfigStorySource.sbtFileFor(root, code, text.getValue()));
                }
            }
        }
    }

    private static ResourceLocation codexText(ResourceLocation entry, String section) {
        return ResourceLocation.fromNamespaceAndPath(entry.getNamespace(), "codex/" + entry.getPath() + "/" + section);
    }

    /** Moves a file when it is there; one already at the destination is replaced. */
    private static void move(Path source, Path target) throws IOException {
        if (Files.isRegularFile(source)) {
            Files.createDirectories(target.getParent());
            Files.move(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void write(Path file, JsonElement json) throws IOException {
        Files.createDirectories(file.getParent());
        DevModeServer.writeJson(file, json);
    }

    /** {@code {var:}} and {@code {flag:}} of renamed flags in every lang file of the story folder. */
    private static int rewriteLang(Path root, List<Rename> renames) throws IOException {
        if (renames.stream().noneMatch(rename -> rename.type().equals(StoryRenames.FLAG)) || !Files.isDirectory(root.resolve("lang"))) {
            return 0;
        }
        int changed = 0;
        try (Stream<Path> files = Files.list(root.resolve("lang"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".json")).toList()) {
                Map<String, String> texts = DevModeServer.readLang(file);
                int before = changed;
                for (Map.Entry<String, String> entry : texts.entrySet()) {
                    String text = StoryRenames.rewriteText(entry.getValue(), renames);
                    if (!text.equals(entry.getValue())) {
                        entry.setValue(text);
                        changed++;
                    }
                }
                if (changed > before) {
                    DevModeServer.writeLang(file, texts);
                }
            }
        }
        return changed;
    }

    private static void addMigrations(Path root, List<Rename> renames) throws IOException {
        JsonObject migrations = readMigrations(root);
        boolean any = false;
        for (Rename rename : renames) {
            String field = StoryRenames.migrationField(rename.type());
            if (field != null) {
                StoryRenames.addMigration(migrations, field, rename.from(), rename.to());
                any = true;
            }
            if (rename.type().equals(StoryLinks.QUEST)) {
                // Stages and objectives are named by the quest's new id.
                StoryRenames.moveInsideMigrations(migrations, rename.from(), rename.to());
            }
        }
        if (any) {
            write(ConfigStorySource.fileFor(root, CoreDefinitions.MIGRATIONS, MIGRATIONS), migrations);
        }
    }

    private static JsonObject readMigrations(Path root) throws IOException {
        Path file = ConfigStorySource.fileFor(root, CoreDefinitions.MIGRATIONS, MIGRATIONS);
        if (!Files.isRegularFile(file)) {
            return new JsonObject();
        }
        try {
            return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            throw new IOException(file.getFileName() + " is broken: " + e.getMessage(), e);
        }
    }

    /** The KubeJS script files that name an old id, by their path in the scripts folder. */
    private static List<String> scriptsNaming(MinecraftServer server, List<Rename> renames) {
        Path folder = DevScripts.folder(server);
        Set<String> found = new LinkedHashSet<>();
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(folder)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".js")).toList()) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                if (renames.stream().anyMatch(rename -> text.contains("'" + rename.from() + "'") || text.contains("\"" + rename.from() + "\"")
                        || text.contains("`" + rename.from() + "`"))) {
                    found.add(folder.relativize(file).toString().replace('\\', '/'));
                }
            }
        } catch (IOException e) {
            Storybound.LOGGER.warn("Storybound: could not look through the scripts for renamed ids: {}", e.getMessage());
        }
        return List.copyOf(found);
    }

    /**
     * Loads what changed: every story type from the folder again (the renamed files, the files that named them), the
     * texts and the migrations; then moves saved progress and tells every client.
     */
    private static void reload(MinecraftServer server, List<Rename> renames) {
        Path root = ConfigStorySource.root();
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
        for (DefinitionType<?> type : DefinitionTypes.all()) {
            boolean renamed = renames.stream().anyMatch(rename -> rename.type().equals(type.id()));
            if (StoryRenames.REFERRING.contains(type.id()) || renamed || type == CoreDefinitions.MIGRATIONS || type == CoreDefinitions.DIALOGUE_LAYOUTS) {
                reloadType(root, type, ops, renames);
            }
        }
        ConfigTexts.reload();
        StoryMigrations.reload();
        StoryMigrations.migrate(server);
        server.getPlayerList().getPlayers().forEach(StoryMigrations::migrate);
        DevModeServer.rebuildAndSync(server);
        server.getPlayerList().getPlayers().stream().filter(online -> online.hasPermissions(2)).forEach(DevModeServer::sendSnapshot);
    }

    /** The type's story-folder files over what it has: the old ids of renamed ones dropped, datapack ones kept. */
    private static <T> void reloadType(Path root, DefinitionType<T> type, RegistryOps<JsonElement> ops, List<Rename> renames) {
        Map<ResourceLocation, T> updated = new LinkedHashMap<>(type.serverStore().all());
        for (Rename rename : renames) {
            if (rename.type().equals(type.id()) || type == CoreDefinitions.DIALOGUE_LAYOUTS && rename.type().equals(StoryLinks.DIALOGUE)) {
                updated.remove(rename.from());
            }
        }
        Map<ResourceLocation, JsonElement> files = new LinkedHashMap<>();
        ConfigStorySource.scan(root, type, files, new ArrayList<>());
        files.forEach((id, json) -> type.codec().parse(ops, json).resultOrPartial(error ->
                Storybound.LOGGER.warn("Storybound: {} {} does not load after a rename: {}", type.id(), id, error)).ifPresent(value -> updated.put(id, value)));
        type.serverStore().replace(updated);
    }
}
