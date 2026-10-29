package dev.dzenthai.storybound.core.story;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.state.QuestProgress;
import dev.dzenthai.storybound.core.state.QuestStatus;
import dev.dzenthai.storybound.core.state.StoryMigrations;
import dev.dzenthai.storybound.core.story.StoryRenames.Problem;
import dev.dzenthai.storybound.core.story.StoryRenames.Rename;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/** The rules of renaming a node, from the "what if" list of October 29, 2026. */
class StoryRenamesTest {
    private static final ResourceLocation ELDER = id("demo:elder");
    private static final ResourceLocation OLD_MAN = id("demo:old_man");

    private static ResourceLocation id(String id) {
        return ResourceLocation.parse(id);
    }

    /** What exists: a quest and an NPC both named demo:elder, the elder's scale, and an NPC demo:smith with its scale. */
    private static final BiPredicate<ResourceLocation, ResourceLocation> EXISTS = (type, id) -> Set.of(
            StoryLinks.QUEST + " demo:elder", StoryLinks.NPC + " demo:elder", StoryRenames.FLAG + " demo:rep/elder",
            StoryLinks.NPC + " demo:smith", StoryRenames.FLAG + " demo:rep/smith").contains(type + " " + id);

    @Test
    void aTypedNameIsOneWordInTheNodesNamespace() {
        assertEquals(Optional.of(id("demo:elder2")), StoryRenames.parse("elder2", ELDER));
        assertEquals(Optional.of(id("demo:elder2")), StoryRenames.parse(" demo:elder2 ", ELDER), "its own namespace written before it is dropped");
        for (String bad : List.of("Elder", "old man", "", ".", "..", "demo:", "npc/elder", "mystory:elder", "a:b:c", "демо")) {
            assertEquals(Optional.empty(), StoryRenames.parse(bad, ELDER), bad);
        }
        assertEquals(Optional.of(Problem.NAMESPACE), StoryRenames.problem(StoryLinks.NPC, ELDER, "mystory:elder", EXISTS),
                "the namespace is the story's, set in the project");
        assertEquals(Optional.of(Problem.FORMAT), StoryRenames.problem(StoryLinks.NPC, ELDER, "npc/elder", EXISTS), "no folders");
        assertTrue(StoryRenames.isStoryNamespace("my_story") && StoryRenames.isStoryNamespace("storybound"));
        for (String bad : List.of("demo", "minecraft", "My_Story", "my story", "a/b", "a:b", "", "..")) {
            assertTrue(!StoryRenames.isStoryNamespace(bad), bad);
        }
    }

    @Test
    void aNameIsRefusedOnlyWhenTheSameKindHasIt() {
        assertEquals(Optional.of(Problem.SAME), StoryRenames.problem(StoryLinks.NPC, ELDER, "demo:elder", EXISTS));
        assertEquals(Optional.of(Problem.SAME), StoryRenames.problem(StoryLinks.NPC, ELDER, "elder", EXISTS));
        assertEquals(Optional.of(Problem.FORMAT), StoryRenames.problem(StoryLinks.NPC, ELDER, "Old Man", EXISTS));
        assertEquals(Optional.of(Problem.TAKEN), StoryRenames.problem(StoryLinks.NPC, ELDER, "smith", EXISTS));
        // A quest demo:elder is another kind: an NPC may be renamed to that name, and a quest to an NPC's.
        assertEquals(Optional.empty(), StoryRenames.problem(StoryLinks.NPC, id("demo:smith"), "demo:fisher", EXISTS));
        assertEquals(Optional.empty(), StoryRenames.problem(StoryLinks.QUEST, ELDER, "smith", EXISTS));
        assertEquals(Optional.empty(), StoryRenames.problem(StoryLinks.NPC, ELDER, "old_man", EXISTS));
    }

    @Test
    void anNpcTakesItsRelationshipScaleAlong() {
        assertEquals(List.of(new Rename(StoryLinks.NPC, ELDER, OLD_MAN), new Rename(StoryRenames.FLAG, id("demo:rep/elder"), id("demo:rep/old_man"))),
                StoryRenames.cascade(new Rename(StoryLinks.NPC, ELDER, OLD_MAN), EXISTS));
        assertEquals(List.of(new Rename(StoryLinks.QUEST, ELDER, OLD_MAN)), StoryRenames.cascade(new Rename(StoryLinks.QUEST, ELDER, OLD_MAN), EXISTS),
                "a quest has no scale");
        // The scale of the new name is there already (an NPC demo:rep/... flag left behind): refused before anything moves.
        BiPredicate<ResourceLocation, ResourceLocation> leftOver = EXISTS.or((type, id) -> type.equals(StoryRenames.FLAG) && id.equals(id("demo:rep/old_man")));
        assertEquals(Optional.of(Problem.SCALE_TAKEN), StoryRenames.problem(StoryLinks.NPC, ELDER, "old_man", leftOver));
    }

    @Test
    void referencesOfTheRenamedKindChangeAndNothingElse() {
        JsonObject binding = JsonParser.parseString("""
                { "entity": { "type": "minecraft:villager", "tag": "elder" },
                  "dialogues": [ { "dialogue": "demo:elder", "conditions": { "type": "quest", "quest": "demo:elder", "state": "active" } } ],
                  "busy_dialogue": "demo:elder" }
                """).getAsJsonObject();
        int changed = StoryRenames.rewrite(StoryLinks.NPC, binding, List.of(new Rename(StoryLinks.DIALOGUE, ELDER, OLD_MAN)));
        assertEquals(2, changed);
        assertEquals("demo:old_man", binding.getAsJsonArray("dialogues").get(0).getAsJsonObject().get("dialogue").getAsString());
        assertEquals("demo:old_man", binding.get("busy_dialogue").getAsString());
        assertEquals("demo:elder", binding.getAsJsonArray("dialogues").get(0).getAsJsonObject().getAsJsonObject("conditions").get("quest").getAsString(),
                "the quest demo:elder is not the dialogue");
        assertEquals("elder", binding.getAsJsonObject("entity").get("tag").getAsString(), "a scoreboard tag is not an id");
    }

    @Test
    void aFlagChangesInLogicAndPlaceholdersButNotInATextKey() {
        JsonObject dialogue = JsonParser.parseString("""
                { "nodes": { "hi": { "type": "say", "text": { "key": "demo:rep/elder", "args": [ "{var:demo:rep/elder}" ] },
                    "actions": [ { "type": "add_var", "key": "demo:rep/elder", "value": 10 } ],
                    "conditions": { "type": "var", "key": "demo:rep/elder", "min": 20 },
                    "note": "Now {var:demo:rep/elder}, once {flag:demo:rep/elder}, not {var:demo:rep/elderly}" } } }
                """).getAsJsonObject();
        int changed = StoryRenames.rewrite(StoryLinks.DIALOGUE, dialogue, List.of(new Rename(StoryRenames.FLAG, id("demo:rep/elder"), id("demo:rep/old_man"))));
        JsonObject node = dialogue.getAsJsonObject("nodes").getAsJsonObject("hi");
        assertEquals(4, changed);
        assertEquals("demo:rep/elder", node.getAsJsonObject("text").get("key").getAsString(), "a story text's key is a translation key");
        assertEquals("{var:demo:rep/old_man}", node.getAsJsonObject("text").getAsJsonArray("args").get(0).getAsString());
        assertEquals("demo:rep/old_man", node.getAsJsonArray("actions").get(0).getAsJsonObject().get("key").getAsString());
        assertEquals("demo:rep/old_man", node.getAsJsonObject("conditions").get("key").getAsString());
        assertEquals("Now {var:demo:rep/old_man}, once {flag:demo:rep/old_man}, not {var:demo:rep/elderly}", node.get("note").getAsString());
    }

    @Test
    void stagesWaypointsChaptersAndCodexEntriesFollow() {
        JsonObject quest = JsonParser.parseString("""
                { "stages": [ { "id": "go", "waypoints": [ "demo:well", "demo:mill" ] } ],
                  "rewards": { "actions": [ { "type": "unlock_codex", "entry": "demo:well" } ] } }
                """).getAsJsonObject();
        StoryRenames.rewrite(StoryLinks.QUEST, quest, List.of(new Rename(StoryLinks.WAYPOINT, id("demo:well"), id("demo:old_well"))));
        assertEquals("[\"demo:old_well\",\"demo:mill\"]", quest.getAsJsonArray("stages").get(0).getAsJsonObject().get("waypoints").toString());
        assertEquals("demo:well", quest.getAsJsonObject("rewards").getAsJsonArray("actions").get(0).getAsJsonObject().get("entry").getAsString(),
                "the Codex entry demo:well is not the waypoint");
        StoryRenames.rewrite(StoryLinks.QUEST, quest, List.of(new Rename(StoryLinks.CODEX, id("demo:well"), id("demo:wells"))));
        assertEquals("demo:wells", quest.getAsJsonObject("rewards").getAsJsonArray("actions").get(0).getAsJsonObject().get("entry").getAsString());

        JsonObject chapter = JsonParser.parseString("""
                { "title": "Act 1", "nodes": [ { "type": "npc_binding", "id": "demo:elder", "x": 10, "y": 20 },
                  { "type": "storybound:quest", "id": "demo:elder", "x": 30, "y": 40 } ] }
                """).getAsJsonObject();
        assertEquals(1, StoryRenames.rewrite(StoryRenames.CHAPTER, chapter, List.of(new Rename(StoryLinks.NPC, ELDER, OLD_MAN))));
        assertEquals("demo:old_man", chapter.getAsJsonArray("nodes").get(0).getAsJsonObject().get("id").getAsString(), "the NPC's card keeps its place");
        assertEquals("demo:elder", chapter.getAsJsonArray("nodes").get(1).getAsJsonObject().get("id").getAsString(), "the quest's card is another node");
    }

    private static final String HUNT = """
            { "stages": [
                { "id": "find_tracks", "next": "kill", "objectives": [ { "count": 1 }, { "id": "wolves", "count": 3 } ] },
                { "id": "kill", "objectives": [ { "count": 1 } ] },
                { "id": "report", "next": "find_tracks" } ] }
            """;

    @Test
    void stagesAndObjectivesAreNamedWithinTheirQuest() {
        JsonObject quest = JsonParser.parseString(HUNT).getAsJsonObject();
        assertEquals(Optional.of(Problem.SAME), StoryRenames.stageProblem(quest, "kill", " kill "));
        for (String bad : List.of("", "@complete", "Kill", "kill them", "a/b")) {
            assertEquals(Optional.of(Problem.FORMAT), StoryRenames.stageProblem(quest, "kill", bad), bad);
        }
        assertEquals(Optional.of(Problem.TAKEN), StoryRenames.stageProblem(quest, "kill", "report"));
        assertEquals(Optional.empty(), StoryRenames.stageProblem(quest, "kill", "hunt"));

        JsonObject first = StoryRenames.stage(quest, "find_tracks");
        assertEquals(List.of("0", "wolves"), StoryRenames.objectiveIds(first), "an objective without an id is counted by its position");
        assertEquals(Optional.of(Problem.SAME), StoryRenames.objectiveProblem(quest, "find_tracks", "0", ""), "an empty box changes nothing");
        assertEquals(Optional.of(Problem.TAKEN), StoryRenames.objectiveProblem(quest, "find_tracks", "0", "wolves"));
        assertEquals(Optional.of(Problem.TAKEN), StoryRenames.objectiveProblem(quest, "find_tracks", "wolves", "0"), "a position counts as a name");
        assertEquals(Optional.empty(), StoryRenames.objectiveProblem(quest, "find_tracks", "0", "tracks"));
        assertEquals(Optional.empty(), StoryRenames.objectiveProblem(quest, "kill", "0", "wolves"), "another stage may have its own wolves");
    }

    @Test
    void aRenamedStageIsFollowedByNextAndByWhatNamesItsQuest() {
        JsonObject quest = JsonParser.parseString(HUNT).getAsJsonObject();
        assertEquals(2, StoryRenames.renameStage(quest, "find_tracks", "tracks"), "the stage and the next that leads back to it");
        assertEquals("tracks", quest.getAsJsonArray("stages").get(0).getAsJsonObject().get("id").getAsString());
        assertEquals("tracks", quest.getAsJsonArray("stages").get(2).getAsJsonObject().get("next").getAsString());
        assertEquals("kill", quest.getAsJsonArray("stages").get(0).getAsJsonObject().get("next").getAsString());

        JsonObject other = JsonParser.parseString("""
                { "conditions": [ { "type": "quest", "quest": "demo:hunt", "stage": "find_tracks" },
                                  { "type": "quest", "quest": "demo:fishing", "stage": "find_tracks" } ],
                  "actions": [ { "type": "set_quest_stage", "quest": "demo:hunt", "stage": "find_tracks" } ] }
                """).getAsJsonObject();
        assertEquals(2, StoryRenames.rewriteStage(other, id("demo:hunt"), "find_tracks", "tracks"));
        assertTrue(other.toString().contains("\"quest\":\"demo:fishing\",\"stage\":\"find_tracks\""), "another quest's stage of the same name stays");

        assertTrue(StoryRenames.renameObjective(quest, "tracks", "0", "prints"));
        assertEquals(List.of("prints", "wolves"), StoryRenames.objectiveIds(StoryRenames.stage(quest, "tracks")));
        assertEquals(List.of("0"), StoryRenames.objectiveIds(StoryRenames.stage(quest, "kill")), "the next stage's 0 is its own");
    }

    @Test
    void aRenamedDialogueNodeKeepsEveryArrowToIt() {
        ResourceLocation talk = id("demo:talk");
        JsonObject dialogue = JsonParser.parseString("""
                { "start": "line_1", "nodes": {
                    "line_1": { "type": "line", "text": "Hi", "next": "ask" },
                    "ask": { "type": "choice", "options": [ { "text": "Again", "next": "line_1" }, { "text": "Bye" } ],
                             "timeout": { "seconds": 5, "next": "line_1" } },
                    "check": { "type": "condition", "then": "line_1", "else": "@end" },
                    "luck": { "type": "random", "options": [ { "next": "line_1" }, { "next": "ask" } ] },
                    "back": { "type": "jump", "node": "line_1" },
                    "away": { "type": "call", "dialogue": "demo:other", "node": "line_1", "next": "line_1" } } }
                """).getAsJsonObject();
        assertEquals(Optional.of(Problem.TAKEN), StoryRenames.nodeProblem(dialogue, "line_1", "ask"));
        assertEquals(Optional.of(Problem.FORMAT), StoryRenames.nodeProblem(dialogue, "line_1", "@end"));
        assertEquals(Optional.empty(), StoryRenames.nodeProblem(dialogue, "line_1", "greeting"));

        assertEquals(8, StoryRenames.renameNode(dialogue, talk, "line_1", "greeting"),
                "the node, start, ask's option and timeout, then, random, jump, the call's next");
        assertEquals(List.of("greeting", "ask", "check", "luck", "back", "away"), List.copyOf(dialogue.getAsJsonObject("nodes").keySet()), "in its place");
        assertEquals("greeting", dialogue.get("start").getAsString());
        assertEquals("line_1", dialogue.getAsJsonObject("nodes").getAsJsonObject("away").get("node").getAsString(), "the called dialogue's own line_1 stays");

        JsonObject trigger = JsonParser.parseString("""
                { "type": "dialogue_ended", "dialogue": "demo:talk", "node": "line_1" }""").getAsJsonObject();
        assertEquals(1, StoryRenames.rewriteNode(trigger, talk, "line_1", "greeting"));
        JsonObject layout = JsonParser.parseString("{ \"nodes\": { \"@start\": { \"x\": 0, \"y\": 0 }, \"line_1\": { \"x\": 8, \"y\": 8 } } }").getAsJsonObject();
        assertTrue(StoryRenames.renameLayoutNode(layout, "line_1", "greeting"));
        assertEquals("{\"@start\":{\"x\":0,\"y\":0},\"greeting\":{\"x\":8,\"y\":8}}", layout.get("nodes").toString(), "it keeps its place on the graph");
    }

    @Test
    void insideRenamesFollowTheirQuestsNewName() {
        JsonObject migrations = new JsonObject();
        StoryRenames.addInsideMigration(migrations, "stages", id("demo:hunt"), "a", "b");
        StoryRenames.addInsideMigration(migrations, "stages", id("demo:hunt"), "b", "c");
        assertEquals("{\"demo:hunt\":{\"a\":\"c\",\"b\":\"c\"}}", migrations.get("stages").toString());
        StoryRenames.addInsideMigration(migrations, "objectives", id("demo:hunt"), "0", "wolves");
        StoryRenames.moveInsideMigrations(migrations, id("demo:hunt"), id("demo:wolf_hunt"));
        assertEquals("{\"demo:wolf_hunt\":{\"a\":\"c\",\"b\":\"c\"}}", migrations.get("stages").toString(), "named by the quest's new id");
        assertEquals("{\"demo:wolf_hunt\":{\"0\":\"wolves\"}}", migrations.get("objectives").toString());
    }

    @Test
    void savedProgressMovesOnlyWhereTheRenameStillMeansTheSame() {
        ResourceLocation hunt = id("demo:hunt");
        // The quest now: stage tracks (once find_tracks) with objective wolves (once 0); stage kill with its own 0; and a
        // new stage that took the old name report (once renamed to summary).
        Map<String, List<String>> stages = Map.of("tracks", List.of("wolves"), "kill", List.of("0"), "report", List.of(), "summary", List.of());
        StoryMigrations.registerQuestShape(new StoryMigrations.QuestShape() {
            @Override
            public boolean defines(ResourceLocation quest) {
                return quest.equals(hunt);
            }

            @Override
            public boolean hasStage(ResourceLocation quest, String stage) {
                return quest.equals(hunt) && stages.containsKey(stage);
            }

            @Override
            public boolean hasObjective(ResourceLocation quest, String stage, String objective) {
                return hasStage(quest, stage) && stages.get(stage).contains(objective);
            }
        });
        try {
            StoryMigrations.Renames renames = new StoryMigrations.Renames(Map.of(),
                    Map.of(hunt, Map.of("find_tracks", "tracks", "report", "summary")), Map.of(hunt, Map.of("0", "wolves")));
            QuestProgress onFirst = renames.progress(hunt, new QuestProgress(QuestStatus.ACTIVE, Optional.of("find_tracks"), Map.of("0", 2), List.of()));
            assertEquals(Optional.of("tracks"), onFirst.stage());
            assertEquals(Map.of("wolves", 2), onFirst.objectives(), "the renamed objective's counter follows");
            QuestProgress onKill = renames.progress(hunt, new QuestProgress(QuestStatus.ACTIVE, Optional.of("kill"), Map.of("0", 1), List.of("find_tracks")));
            assertEquals(Map.of("0", 1), onKill.objectives(), "the kill stage's own 0 stays");
            assertEquals(List.of("tracks"), onKill.log());
            QuestProgress onReport = renames.progress(hunt, new QuestProgress(QuestStatus.ACTIVE, Optional.of("report"), Map.of(), List.of()));
            assertEquals(Optional.of("report"), onReport.stage(), "a stage that has the old name again keeps its players");
        } finally {
            StoryMigrations.registerQuestShape(null);
        }
    }

    @Test
    void migrationsFollowChainsAndNeverLoop() {
        JsonObject migrations = new JsonObject();
        StoryRenames.addMigration(migrations, "quests", id("demo:a"), id("demo:b"));
        StoryRenames.addMigration(migrations, "quests", id("demo:b"), id("demo:c"));
        assertEquals("{\"demo:a\":\"demo:c\",\"demo:b\":\"demo:c\"}", migrations.get("quests").toString(), "a save from either name reaches c");
        StoryRenames.addMigration(migrations, "quests", id("demo:c"), id("demo:a"));
        assertEquals("{\"demo:b\":\"demo:a\",\"demo:c\":\"demo:a\"}", migrations.get("quests").toString(), "renamed back: a is the name again");
        StoryRenames.addMigration(migrations, "flags", id("demo:rep/elder"), id("demo:rep/old_man"));
        assertTrue(migrations.has("quests") && migrations.has("flags"), "each kind in its own field");
        assertEquals("npc_bindings", StoryRenames.migrationField(StoryLinks.NPC));
        assertEquals(null, StoryRenames.migrationField(StoryLinks.SCRIPT), "players keep nothing of a script block");
        assertEquals(Storybound.id("flag"), StoryRenames.FLAG);
    }
}
