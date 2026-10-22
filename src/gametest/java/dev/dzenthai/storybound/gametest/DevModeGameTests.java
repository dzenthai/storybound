package dev.dzenthai.storybound.gametest;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.CoreDefinitions;
import dev.dzenthai.storybound.core.data.ConfigStorySource;
import dev.dzenthai.storybound.core.data.DefinitionStore;
import dev.dzenthai.storybound.core.dev.DevModeServer;
import dev.dzenthai.storybound.core.dev.DevScripts;
import dev.dzenthai.storybound.module.dialogue.DialogueRegistries;
import dev.dzenthai.storybound.module.quest.QuestDefinition;
import dev.dzenthai.storybound.module.quest.QuestRegistries;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.players.ServerOpListEntry;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** The dev mode editor's server side: saving to and deleting from the story folder, permissions, ids that are not paths, dialogue layouts. */
@SuppressWarnings("removal")
@GameTestHolder(Storybound.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DevModeGameTests {
    private static final String QUEST_JSON = "{ \"title\": \"gametest.dev_saved\", \"category\": \"side\" }";

    private DevModeGameTests() {}

    @GameTest(template = "empty")
    public static void savesAndDeletesInTheStoryFolder(GameTestHelper helper) {
        ServerPlayer author = helper.makeMockServerPlayerInLevel();
        ServerPlayer guest = helper.makeMockServerPlayerInLevel();
        PlayerList players = helper.getLevel().getServer().getPlayerList();
        // GameTest servers give operators level 0, so the author gets an explicit level.
        players.getOps().add(new ServerOpListEntry(author.getGameProfile(), 4, false));
        DefinitionStore<QuestDefinition> store = QuestRegistries.QUESTS.serverStore();
        ResourceLocation type = QuestRegistries.QUESTS.id();
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("gametest", "dev_saved");
        ResourceLocation escape = ResourceLocation.fromNamespaceAndPath("gametest", "../../dev_escape");
        Path root = ConfigStorySource.root();
        Path file = ConfigStorySource.fileFor(root, QuestRegistries.QUESTS, id);
        try {
            helper.assertTrue(author.hasPermissions(2) && !guest.hasPermissions(2), "only the author may edit");

            DevModeServer.saveDefinition(guest, type, id, QUEST_JSON, Optional.empty(), "en_us", Map.of());
            helper.assertFalse(Files.exists(file) || store.contains(id), "a player without permission saves nothing");

            DevModeServer.saveDefinition(author, type, id, QUEST_JSON, Optional.empty(), "en_us", Map.of());
            helper.assertTrue(Files.isRegularFile(file), "saved as quests/gametest/dev_saved.json in the story folder");
            helper.assertTrue(store.contains(id), "and in use without a reload");
            helper.assertTrue(read(file).contains("gametest.dev_saved"), "the file holds the quest");

            DevModeServer.saveDefinition(author, type, id, "{ \"category\": \"side\" }", Optional.empty(), "en_us", Map.of());
            helper.assertTrue(read(file).contains("gametest.dev_saved"), "a definition that does not parse leaves the saved file alone");

            DevModeServer.saveDefinition(author, type, escape, QUEST_JSON, Optional.empty(), "en_us", Map.of());
            helper.assertFalse(store.contains(escape), "an id with .. is not saved");
            helper.assertFalse(Files.exists(root.resolve("dev_escape.json")) || Files.exists(root.getParent().resolve("dev_escape.json")),
                    "and nothing is written outside the quests folder");

            DevModeServer.saveDefinition(author, type, id, QUEST_JSON, Optional.of("text"), "../../en_us", Map.of());
            helper.assertFalse(exists(root, "../../en_us"), "a language that is not a language code writes no text");

            DevModeServer.deleteDefinition(guest, type, id);
            helper.assertTrue(Files.exists(file) && store.contains(id), "a player without permission deletes nothing");
            DevModeServer.deleteDefinition(author, type, id);
            helper.assertFalse(Files.exists(file) || store.contains(id), "deleting removes the file and the quest");
        } finally {
            players.getOps().remove(author.getGameProfile());
            cleanUp(store, id, file);
        }
        players.remove(author);
        players.remove(guest);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void savingKeepsTheAuthorsFile(GameTestHelper helper) {
        ServerPlayer author = helper.makeMockServerPlayerInLevel();
        PlayerList players = helper.getLevel().getServer().getPlayerList();
        players.getOps().add(new ServerOpListEntry(author.getGameProfile(), 4, false));
        DefinitionStore<QuestDefinition> store = QuestRegistries.QUESTS.serverStore();
        ResourceLocation type = QuestRegistries.QUESTS.id();
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("gametest", "dev_as_written");
        Path file = ConfigStorySource.fileFor(ConfigStorySource.root(), QuestRegistries.QUESTS, id);
        try {
            // Written by hand: four spaces, Windows line ends, the category first, a default written out, a note the game does not know.
            String byHand = "{\r\n    \"category\": \"side\",\r\n    \"title\": \"gametest.as_written\",\r\n    \"announce\": true,\r\n"
                    + "    \"x_note\": \"ask the writer\"\r\n}\r\n";
            Files.createDirectories(file.getParent());
            Files.writeString(file, byHand);
            DevModeServer.saveDefinition(author, type, id,
                    "{ \"category\": \"main\", \"title\": \"gametest.as_written\", \"announce\": true, \"x_note\": \"ask the writer\" }",
                    Optional.empty(), "en_us", Map.of());
            String saved = read(file);
            helper.assertTrue(saved.equals(byHand.replace("\"side\"", "\"main\"")), "only the edited value changed:\n" + saved);
            helper.assertTrue(store.contains(id), "and the quest is in use");

            FileTime old = FileTime.fromMillis(1_000_000_000_000L);
            Files.setLastModifiedTime(file, old);
            DevModeServer.saveDefinition(author, type, id,
                    "{\"category\":\"main\",\"title\":\"gametest.as_written\",\"announce\":true,\"x_note\":\"ask the writer\"}",
                    Optional.empty(), "en_us", Map.of());
            helper.assertTrue(Files.getLastModifiedTime(file).equals(old), "the same JSON leaves the file untouched");
        } catch (IOException e) {
            throw new GameTestAssertException(e.getMessage());
        } finally {
            players.getOps().remove(author.getGameProfile());
            cleanUp(store, id, file);
        }
        players.remove(author);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aDialogueTakesItsLayoutAlong(GameTestHelper helper) {
        ServerPlayer author = helper.makeMockServerPlayerInLevel();
        PlayerList players = helper.getLevel().getServer().getPlayerList();
        players.getOps().add(new ServerOpListEntry(author.getGameProfile(), 4, false));
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("gametest", "dev_dialogue");
        Path root = ConfigStorySource.root();
        Path dialogueFile = ConfigStorySource.fileFor(root, DialogueRegistries.DIALOGUES, id);
        Path layoutFile = ConfigStorySource.fileFor(root, CoreDefinitions.DIALOGUE_LAYOUTS, id);
        try {
            DevModeServer.saveDefinition(author, DialogueRegistries.DIALOGUES.id(), id,
                    "{ \"start\": \"bye\", \"nodes\": { \"bye\": { \"type\": \"end\" } } }", Optional.empty(), "en_us", Map.of());
            DevModeServer.saveDefinition(author, CoreDefinitions.DIALOGUE_LAYOUTS.id(), id,
                    "{ \"nodes\": { \"bye\": { \"x\": 40, \"y\": 80 } } }", Optional.empty(), "en_us", Map.of());
            helper.assertTrue(Files.isRegularFile(dialogueFile) && Files.isRegularFile(layoutFile), "the dialogue and its layout are saved");
            helper.assertTrue(read(layoutFile).contains("80"), "the layout keeps the positions");
            DevModeServer.deleteDefinition(author, DialogueRegistries.DIALOGUES.id(), id);
            helper.assertFalse(Files.exists(dialogueFile) || DialogueRegistries.DIALOGUES.serverStore().contains(id), "the dialogue is deleted");
            helper.assertFalse(Files.exists(layoutFile) || CoreDefinitions.DIALOGUE_LAYOUTS.serverStore().contains(id), "and its layout with it");
        } finally {
            players.getOps().remove(author.getGameProfile());
            cleanUp(DialogueRegistries.DIALOGUES.serverStore(), id, dialogueFile);
            cleanUp(CoreDefinitions.DIALOGUE_LAYOUTS.serverStore(), id, layoutFile);
        }
        players.remove(author);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void scriptFilesAreMadeAndReadSafely(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ResourceLocation hook = ResourceLocation.fromNamespaceAndPath("gametest", "dev_script");
        Path folder = DevScripts.folder(server);
        Path file = folder.resolve(DevScripts.fileFor(hook));
        try {
            String relative = DevScripts.createStub(server, hook, DevScripts.Kind.ACTION);
            helper.assertTrue(Files.isRegularFile(file), "the new script is in kubejs/server_scripts/" + relative);
            JsonObject described = DevScripts.describe(server);
            boolean listed = false;
            for (JsonElement element : described.getAsJsonArray("files")) {
                JsonObject entry = element.getAsJsonObject();
                if (entry.get("path").getAsString().equals(relative)) {
                    listed = entry.getAsJsonArray("actions").toString().contains(hook.toString());
                }
            }
            helper.assertTrue(listed, "and reading it finds its handler: " + described.get("files"));
            String before = read(file);
            helper.assertTrue(failsWith(IOException.class, () -> DevScripts.createStub(server, hook, DevScripts.Kind.CONDITION)), "an existing file is never replaced");
            helper.assertTrue(read(file).equals(before), "and stays as it was");
            helper.assertTrue(failsWith(IllegalArgumentException.class,
                    () -> DevScripts.createStub(server, ResourceLocation.fromNamespaceAndPath("gametest", "../../dev_escape"), DevScripts.Kind.ACTION)),
                    "a handler id with .. is not a file name");
            helper.assertFalse(Files.exists(folder.resolve("dev_escape.js")) || Files.exists(folder.getParent().resolve("dev_escape.js")), "and nothing is written");
        } catch (IOException e) {
            throw new GameTestAssertException("Cannot write the script: " + e.getMessage());
        } finally {
            try {
                Files.deleteIfExists(file);
                for (Path dir = file.getParent(); dir.startsWith(folder) && !dir.equals(folder); dir = dir.getParent()) {
                    try (Stream<Path> rest = Files.list(dir)) {
                        if (rest.findAny().isPresent()) {
                            break;
                        }
                    }
                    Files.delete(dir);
                }
            } catch (IOException ignored) {
                // A leftover file in the GameTest run folder harms nothing.
            }
        }
        helper.succeed();
    }

    private static boolean failsWith(Class<? extends Exception> expected, ScriptCall call) {
        try {
            call.run();
            return false;
        } catch (Exception e) {
            return expected.isInstance(e);
        }
    }

    @FunctionalInterface
    private interface ScriptCall {
        void run() throws Exception;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new GameTestAssertException("Cannot read " + file + ": " + e.getMessage());
        }
    }

    /** Whether any .sbt file sits under a texts folder named like {@code language}. */
    private static boolean exists(Path root, String language) {
        Path folder = root.resolve("texts").resolve(language).normalize();
        if (!Files.isDirectory(folder)) {
            return false;
        }
        try (Stream<Path> files = Files.walk(folder)) {
            return files.anyMatch(path -> path.toString().endsWith(".sbt"));
        } catch (IOException e) {
            return true;
        }
    }

    private static <T> void cleanUp(DefinitionStore<T> store, ResourceLocation id, Path file) {
        if (store.contains(id)) {
            Map<ResourceLocation, T> left = new LinkedHashMap<>(store.all());
            left.remove(id);
            store.replace(left);
        }
        try {
            Files.deleteIfExists(file);
            Path folder = file.getParent();
            try (Stream<Path> rest = Files.list(folder)) {
                if (rest.findAny().isEmpty()) {
                    Files.delete(folder);
                }
            }
        } catch (IOException ignored) {
            // A leftover file in the GameTest run folder harms nothing.
        }
    }
}
