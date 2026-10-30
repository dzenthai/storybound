package dev.dzenthai.storybound.core.dev;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.CoreDefinitions;
import dev.dzenthai.storybound.core.data.ConfigStorySource;
import dev.dzenthai.storybound.core.data.DefinitionType;
import dev.dzenthai.storybound.core.story.StoryLinks;
import dev.dzenthai.storybound.core.story.StoryRenames;
import dev.dzenthai.storybound.core.story.StoryRenames.Rename;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Renaming a node in a story folder of its own (October 29, 2026): the files move with what belongs to them by name,
 * what names them is rewritten, and the migrations follow. Lives in the package of {@link DevRenames} to reach it.
 */
@GameTestHolder(Storybound.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DevRenamesGameTests {
    private DevRenamesGameTests() {}

    private static ResourceLocation id(String id) {
        return ResourceLocation.parse(id);
    }

    private static void write(Path root, ResourceLocation type, String id, String json) throws IOException {
        Path file = ConfigStorySource.fileFor(root, DevModeServer.findType(type), id(id));
        Files.createDirectories(file.getParent());
        Files.writeString(file, json, StandardCharsets.UTF_8);
    }

    private static JsonObject read(Path root, ResourceLocation type, String id) throws IOException {
        return JsonParser.parseString(Files.readString(ConfigStorySource.fileFor(root, DevModeServer.findType(type), id(id)))).getAsJsonObject();
    }

    private static boolean exists(Path root, ResourceLocation type, String id) {
        DefinitionType<?> definitions = DevModeServer.findType(type);
        return Files.isRegularFile(ConfigStorySource.fileFor(root, definitions, id(id)));
    }

    @GameTest(template = "empty")
    public static void aRenamedDialogueNodeTakesItsPlaceAndMarksAlong(GameTestHelper helper) {
        try {
            Path root = Files.createTempDirectory("storybound-rename-node");
            write(root, StoryLinks.DIALOGUE, "demo:talk", """
                    { "start": "ask", "nodes": { "ask": { "type": "choice", "options": [
                        { "text": "Gift", "once": true, "next": "@end" }, { "id": "bye", "text": "Bye", "once": true }, { "text": "Hm" } ] } } }""");
            write(root, CoreDefinitions.DIALOGUE_LAYOUTS.id(), "demo:talk", """
                    { "nodes": { "@start": { "x": 0, "y": 0 }, "ask": { "x": 200, "y": 40 } } }""");
            write(root, StoryLinks.QUEST, "demo:chat", """
                    { "stages": [ { "id": "talk", "objectives": [ { "trigger": { "type": "dialogue_ended", "dialogue": "demo:talk", "node": "ask" } } ] } ] }""");

            int references = DevRenames.applyInDialogue(root, id("demo:talk"), read(root, StoryLinks.DIALOGUE, "demo:talk"), "ask", "offer");
            helper.assertTrue(references == 2, "start and the quest's trigger; got " + references);
            helper.assertTrue(read(root, StoryLinks.DIALOGUE, "demo:talk").getAsJsonObject("nodes").has("offer"), "renamed");
            helper.assertTrue(read(root, CoreDefinitions.DIALOGUE_LAYOUTS.id(), "demo:talk").toString().contains("\"offer\":{\"x\":200,\"y\":40}"), "in its place");
            helper.assertTrue(read(root, StoryLinks.QUEST, "demo:chat").toString().contains("\"node\":\"offer\""), "the quest's objective still counts");
            JsonObject flags = read(root, CoreDefinitions.MIGRATIONS.id(), DevRenames.MIGRATIONS.toString()).getAsJsonObject("flags");
            helper.assertTrue(flags.size() == 2 && flags.get("storybound:dialogue_once/demo/talk/ask/0").getAsString().equals("storybound:dialogue_once/demo/talk/offer/0")
                    && flags.has("storybound:dialogue_once/demo/talk/ask/bye"), "the options chosen once stay chosen: " + flags);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        helper.succeed();
    }

    /** The story's namespace changed: its nodes move to the new one; the demo's stay; a name taken there stops the move. */
    @GameTest(template = "empty")
    public static void theStorysNodesMoveToItsNewNamespace(GameTestHelper helper) {
        try {
            Path root = Files.createTempDirectory("storybound-namespace");
            write(root, StoryLinks.NPC, "storybound:chelovek", """
                    { "entity": { "type": "minecraft:villager", "tag": "chelovek" }, "dialogues": [ { "dialogue": "storybound:hello" } ] }""");
            write(root, StoryLinks.DIALOGUE, "storybound:hello", """
                    { "start": "hi", "nodes": { "hi": { "type": "say", "text": "Hi" } } }""");
            write(root, StoryLinks.NPC, "demo:elder", """
                    { "entity": { "type": "minecraft:villager", "tag": "elder" }, "dialogues": [ { "dialogue": "storybound:hello" } ] }""");

            List<String> taken = new java.util.ArrayList<>();
            List<Rename> renames = DevRenames.namespaceRenames(root, "storybound", "my_story", (type, id) -> exists(root, type, id.toString()), taken);
            helper.assertTrue(renames.size() == 2 && taken.isEmpty(), "the NPC and the dialogue, not the demo's: " + renames);
            DevRenames.apply(root, renames);
            helper.assertTrue(exists(root, StoryLinks.NPC, "my_story:chelovek") && exists(root, StoryLinks.DIALOGUE, "my_story:hello"), "moved");
            helper.assertTrue(read(root, StoryLinks.NPC, "my_story:chelovek").toString().contains("\"dialogue\":\"my_story:hello\"")
                    && read(root, StoryLinks.NPC, "demo:elder").toString().contains("\"dialogue\":\"my_story:hello\""), "both NPCs still talk");
            helper.assertTrue(exists(root, StoryLinks.NPC, "demo:elder"), "the demo's NPC stays where it is");

            // Back to storybound, where a node of that name was made meanwhile: nothing moves.
            write(root, StoryLinks.DIALOGUE, "storybound:hello", """
                    { "start": "hi", "nodes": { "hi": { "type": "say", "text": "Another" } } }""");
            taken.clear();
            DevRenames.namespaceRenames(root, "my_story", "storybound", (type, id) -> exists(root, type, id.toString()), taken);
            helper.assertTrue(taken.equals(List.of("dialogue storybound:hello")), "the taken name is named: " + taken);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aRenamedStageOrObjectiveTakesWhatNamesItAlong(GameTestHelper helper) {
        try {
            Path root = Files.createTempDirectory("storybound-rename-stage");
            write(root, StoryLinks.QUEST, "demo:hunt", """
                    { "stages": [ { "id": "find_tracks", "next": "kill", "objectives": [ { "count": 1 } ] },
                                  { "id": "kill", "next": "find_tracks" } ] }""");
            write(root, StoryLinks.WAYPOINT, "demo:den", """
                    { "quest": "demo:hunt", "stage": "find_tracks", "target": { "pos": [0, 64, 0] } }""");
            write(root, StoryLinks.DIALOGUE, "demo:ranger", """
                    { "start": "hi", "nodes": { "hi": { "type": "say", "text": "Hi",
                      "conditions": [ { "type": "quest", "quest": "demo:hunt", "stage": "find_tracks" },
                                      { "type": "quest", "quest": "demo:fishing", "stage": "find_tracks" } ] } } }""");

            JsonObject quest = read(root, StoryLinks.QUEST, "demo:hunt");
            int references = DevRenames.applyInQuest(root, id("demo:hunt"), quest, "find_tracks", "", "tracks");
            helper.assertTrue(references == 3, "the kill stage's next, the waypoint, the ranger's condition; got " + references);
            helper.assertTrue(read(root, StoryLinks.QUEST, "demo:hunt").toString().contains("{\"id\":\"tracks\",\"next\":\"kill\""), "the stage has its new name");
            helper.assertTrue(read(root, StoryLinks.WAYPOINT, "demo:den").get("stage").getAsString().equals("tracks"), "the waypoint shows at the renamed stage");
            String ranger = read(root, StoryLinks.DIALOGUE, "demo:ranger").toString();
            helper.assertTrue(ranger.contains("\"quest\":\"demo:hunt\",\"stage\":\"tracks\"") && ranger.contains("\"quest\":\"demo:fishing\",\"stage\":\"find_tracks\""),
                    "only the hunt's stage is renamed: " + ranger);

            DevRenames.applyInQuest(root, id("demo:hunt"), read(root, StoryLinks.QUEST, "demo:hunt"), "tracks", "0", "prints");
            helper.assertTrue(read(root, StoryLinks.QUEST, "demo:hunt").toString().contains("\"id\":\"prints\""), "the objective got a name of its own");
            JsonObject migrations = read(root, CoreDefinitions.MIGRATIONS.id(), DevRenames.MIGRATIONS.toString());
            helper.assertTrue(migrations.getAsJsonObject("stages").getAsJsonObject("demo:hunt").get("find_tracks").getAsString().equals("tracks")
                    && migrations.getAsJsonObject("objectives").getAsJsonObject("demo:hunt").get("0").getAsString().equals("prints"), "saves follow: " + migrations);

            // The quest itself renamed afterwards: its stages' renames are named by its new id.
            write(root, StoryLinks.QUEST, "demo:hunt", read(root, StoryLinks.QUEST, "demo:hunt").toString());
            DevRenames.apply(root, List.of(new Rename(StoryLinks.QUEST, id("demo:hunt"), id("demo:wolf_hunt"))));
            JsonObject moved = read(root, CoreDefinitions.MIGRATIONS.id(), DevRenames.MIGRATIONS.toString());
            helper.assertTrue(moved.getAsJsonObject("stages").has("demo:wolf_hunt") && !moved.getAsJsonObject("stages").has("demo:hunt"), "moved: " + moved);
            helper.assertTrue(read(root, StoryLinks.WAYPOINT, "demo:den").get("quest").getAsString().equals("demo:wolf_hunt"), "the waypoint names the new quest");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void theEditorGetsTheIdsOnlyTheServerKnows(GameTestHelper helper) {
        java.util.Map<String, List<String>> lists = DevModeServer.serverLists(helper.getLevel().getServer());
        List<String> advancements = lists.get("advancement");
        List<String> lootTables = lists.get("loot_table");
        helper.assertTrue(advancements.contains("minecraft:story/root"), "vanilla advancements are listed");
        helper.assertTrue(advancements.stream().noneMatch(id -> id.contains(":recipes/")), "without the recipe book's");
        helper.assertTrue(lootTables.contains("minecraft:chests/simple_dungeon"), "loot tables are listed");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aRenamedCodexSectionTakesItsTextAndReferencesAlong(GameTestHelper helper) {
        try {
            Path root = Files.createTempDirectory("storybound-rename-section");
            write(root, StoryLinks.CODEX, "demo:zombie", """
                    { "title": "Zombie", "sections": [ { "id": "habits", "unlock": { "on": ["killed"] } },
                                                       { "id": "notes", "text": "demo:diary", "unlock": { "on": ["manual"] } } ] }""");
            write(root, StoryLinks.QUEST, "demo:hunt", """
                    { "stages": [ { "id": "go" } ], "on_complete": [ { "type": "unlock_codex", "entry": "demo:zombie", "section": "habits" },
                                                                    { "type": "unlock_codex", "entry": "demo:skeleton", "section": "habits" } ] }""");
            for (String language : List.of("en_us", "ru_ru")) {
                for (String text : List.of("demo:codex/zombie/habits", "demo:diary")) {
                    Path file = ConfigStorySource.sbtFileFor(root, language, id(text));
                    Files.createDirectories(file.getParent());
                    Files.writeString(file, text + " in " + language);
                }
            }

            int references = DevRenames.applyCodexSection(root, id("demo:zombie"), read(root, StoryLinks.CODEX, "demo:zombie"), "habits", "povadki");
            helper.assertTrue(references == 1, "the hunt's action; got " + references);
            helper.assertTrue(read(root, StoryLinks.CODEX, "demo:zombie").toString().contains("\"id\":\"povadki\""), "the section has its new name");
            String hunt = read(root, StoryLinks.QUEST, "demo:hunt").toString();
            helper.assertTrue(hunt.contains("\"entry\":\"demo:zombie\",\"section\":\"povadki\"") && hunt.contains("\"entry\":\"demo:skeleton\",\"section\":\"habits\""),
                    "only the zombie's section is renamed: " + hunt);
            for (String language : List.of("en_us", "ru_ru")) {
                helper.assertTrue(Files.isRegularFile(ConfigStorySource.sbtFileFor(root, language, id("demo:codex/zombie/povadki")))
                        && !Files.exists(ConfigStorySource.sbtFileFor(root, language, id("demo:codex/zombie/habits"))), "the text moved in " + language);
            }

            // A section naming its own text keeps it where it is.
            DevRenames.applyCodexSection(root, id("demo:zombie"), read(root, StoryLinks.CODEX, "demo:zombie"), "notes", "diary_notes");
            helper.assertTrue(Files.isRegularFile(ConfigStorySource.sbtFileFor(root, "en_us", id("demo:diary"))), "its own text stays");
            JsonObject migrations = read(root, CoreDefinitions.MIGRATIONS.id(), DevRenames.MIGRATIONS.toString());
            JsonObject sections = migrations.getAsJsonObject("codex_sections").getAsJsonObject("demo:zombie");
            helper.assertTrue(sections.get("habits").getAsString().equals("povadki") && sections.get("notes").getAsString().equals("diary_notes"),
                    "saves follow: " + migrations);

            // The entry renamed afterwards: its sections' renames are named by its new id.
            DevRenames.apply(root, List.of(new Rename(StoryLinks.CODEX, id("demo:zombie"), id("demo:undead"))));
            JsonObject moved = read(root, CoreDefinitions.MIGRATIONS.id(), DevRenames.MIGRATIONS.toString()).getAsJsonObject("codex_sections");
            helper.assertTrue(moved.has("demo:undead") && !moved.has("demo:zombie"), "moved: " + moved);
            helper.assertTrue(Files.isRegularFile(ConfigStorySource.sbtFileFor(root, "ru_ru", id("demo:codex/undead/povadki"))), "the renamed section's text moved with the entry");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aRenamedNodeTakesItsFilesAndReferencesAlong(GameTestHelper helper) {
        try {
            Path root = Files.createTempDirectory("storybound-rename");
            write(root, StoryLinks.NPC, "demo:elder", """
                    { "entity": { "type": "minecraft:villager", "tag": "elder" }, "dialogues": [ { "dialogue": "demo:talk" } ] }""");
            write(root, StoryRenames.FLAG, "demo:rep/elder", """
                    { "type": "int", "default": 0, "scope": "player" }""");
            write(root, StoryLinks.DIALOGUE, "demo:talk", """
                    { "start": "hi", "nodes": { "hi": { "type": "say", "text": "Hello",
                      "actions": [ { "type": "add_var", "key": "demo:rep/elder", "value": 10 } ] } } }""");
            write(root, CoreDefinitions.DIALOGUE_LAYOUTS.id(), "demo:talk", """
                    { "nodes": { "hi": { "x": 10, "y": 20 } } }""");
            write(root, StoryRenames.CHAPTER, "demo:act1", """
                    { "title": "Act 1", "nodes": [ { "type": "npc_binding", "id": "demo:elder", "x": 5, "y": 6 } ] }""");
            write(root, StoryLinks.DOCUMENT, "demo:note", """
                    { "kind": "note", "title": "A note" }""");
            write(root, StoryLinks.SCRIPT, "demo:burn", """
                    { "kind": "action" }""");
            for (String language : List.of("en_us", "ru_ru")) {
                Path text = ConfigStorySource.sbtFileFor(root, language, id("demo:note"));
                Files.createDirectories(text.getParent());
                Files.writeString(text, "Text in " + language);
            }
            Files.createDirectories(root.resolve("lang"));
            Files.writeString(root.resolve("lang/en_us.json"), "{ \"demo.say\": \"Friendship {var:demo:rep/elder}\" }");

            // The NPC: its scale goes along (as the editor's cascade says), the dialogue's +10 and the text follow.
            List<Rename> npc = StoryRenames.cascade(new Rename(StoryLinks.NPC, id("demo:elder"), id("mystory:old_man")),
                    (type, id) -> exists(root, type, id.toString()));
            helper.assertTrue(npc.size() == 2, "the NPC and its relationship scale: " + npc);
            int references = DevRenames.apply(root, npc);
            helper.assertTrue(!exists(root, StoryLinks.NPC, "demo:elder") && exists(root, StoryLinks.NPC, "mystory:old_man"), "the NPC's file moved to its namespace");
            helper.assertTrue(exists(root, StoryRenames.FLAG, "mystory:rep/old_man") && !exists(root, StoryRenames.FLAG, "demo:rep/elder"), "its scale moved too");
            helper.assertTrue(read(root, StoryLinks.DIALOGUE, "demo:talk").toString().contains("\"key\":\"mystory:rep/old_man\""), "the dialogue's +10 names the new scale");
            helper.assertTrue(read(root, StoryRenames.CHAPTER, "demo:act1").toString().contains("\"id\":\"mystory:old_man\""), "the card keeps its place");
            helper.assertTrue(Files.readString(root.resolve("lang/en_us.json")).contains("{var:mystory:rep/old_man}"), "the text shows the new scale");
            helper.assertTrue(references == 3, "three references: the +10, the card, the text; got " + references);
            JsonObject migrations = read(root, CoreDefinitions.MIGRATIONS.id(), DevRenames.MIGRATIONS.toString());
            helper.assertTrue(migrations.getAsJsonObject("npc_bindings").get("demo:elder").getAsString().equals("mystory:old_man")
                    && migrations.getAsJsonObject("flags").get("demo:rep/elder").getAsString().equals("mystory:rep/old_man"), "saves follow: " + migrations);

            // The dialogue: its layout goes along and the NPC that talks through it is rewritten.
            DevRenames.apply(root, List.of(new Rename(StoryLinks.DIALOGUE, id("demo:talk"), id("demo:greeting"))));
            helper.assertTrue(exists(root, CoreDefinitions.DIALOGUE_LAYOUTS.id(), "demo:greeting") && !exists(root, CoreDefinitions.DIALOGUE_LAYOUTS.id(), "demo:talk"),
                    "the graph keeps its layout");
            helper.assertTrue(read(root, StoryLinks.NPC, "mystory:old_man").toString().contains("\"dialogue\":\"demo:greeting\""), "the NPC still talks");

            // The document's text, in every language; the script block keeps the handler its scripts register.
            DevRenames.apply(root, List.of(new Rename(StoryLinks.DOCUMENT, id("demo:note"), id("demo:letter"))));
            helper.assertTrue(Files.readString(ConfigStorySource.sbtFileFor(root, "ru_ru", id("demo:letter"))).equals("Text in ru_ru")
                    && !Files.exists(ConfigStorySource.sbtFileFor(root, "en_us", id("demo:note"))), "the text moved in both languages");
            DevRenames.apply(root, List.of(new Rename(StoryLinks.SCRIPT, id("demo:burn"), id("demo:fire"))));
            helper.assertTrue(read(root, StoryLinks.SCRIPT, "demo:fire").get("hook").getAsString().equals("demo:burn"), "the handler keeps its name");

            // Renamed back: the old name is a name again, and the migrations do not loop.
            DevRenames.apply(root, List.of(new Rename(StoryLinks.DIALOGUE, id("demo:greeting"), id("demo:talk"))));
            JsonObject dialogues = read(root, CoreDefinitions.MIGRATIONS.id(), DevRenames.MIGRATIONS.toString()).getAsJsonObject("dialogues");
            helper.assertTrue(!dialogues.has("demo:talk") && dialogues.get("demo:greeting").getAsString().equals("demo:talk"), "back again: " + dialogues);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        helper.succeed();
    }
}
