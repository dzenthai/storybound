package dev.dzenthai.storybound.client.dev;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.client.dev.ClientDevStore.NodeKey;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The dev mode's forms (from the schemas) and its edits of definition JSON, without the screen. */
class DevEditsTest {
    private static final ResourceLocation QUEST = Storybound.id("hunt");
    private static final ResourceLocation DIALOGUE = Storybound.id("elder");

    @BeforeEach
    void setUp() {
        ClientDevStore.setActiveLanguage("en_us");
        ClientDevStore.clearPendingTranslations("en_us");
    }

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    @Test
    void everyDefinitionHasASchemaForItsForm() {
        for (String type : List.of("quest", "dialogue", "npc_binding", "document", "waypoint", "boss", "codex", "event", "flag", "chapter", "dialogue_layout")) {
            assertNotNull(Schemas.definition(Storybound.id(type)), type);
        }
    }

    @Test
    void fieldsBecomeTheRightControls() {
        Map<String, Schemas.Node> quest = Schemas.properties(Schemas.definition(ClientDevStore.TYPE_QUEST));
        assertEquals(Schemas.Kind.TEXT, Schemas.kind(quest.get("title")));
        assertEquals(Schemas.Kind.ENUM, Schemas.kind(quest.get("scope")));
        assertEquals(Schemas.Kind.BOOLEAN, Schemas.kind(quest.get("notify")));
        assertEquals(Schemas.Kind.OBJECT, Schemas.kind(quest.get("start")));
        assertEquals(Schemas.Kind.LIST, Schemas.kind(quest.get("stages")));
        assertEquals(Schemas.Kind.CONDITIONS, Schemas.kind(quest.get("fail_when")));
        assertEquals(Schemas.Kind.ACTIONS, Schemas.kind(quest.get("rewards")));
        Schemas.Node objective = Schemas.items(Schemas.properties(Schemas.items(quest.get("stages"))).get("objectives"));
        assertEquals(Schemas.Kind.TRIGGER, Schemas.kind(Schemas.properties(objective).get("trigger")));
        assertEquals(Schemas.Kind.INTEGER, Schemas.kind(Schemas.properties(objective).get("count")));
        assertEquals(Schemas.Kind.VARIANTS, Schemas.kind(Schemas.properties(Schemas.definition(ClientDevStore.TYPE_WAYPOINT)).get("target")));

        Schemas.Node killed = Schemas.typed(Schemas.logic("trigger"), "storybound:entity_killed");
        assertEquals(Schemas.Kind.ID_LIST, Schemas.kind(Schemas.properties(killed).get("entity")));
        Schemas.Node waypoint = Schemas.typed(Schemas.logic("action"), "add_waypoint");
        assertEquals(Schemas.Kind.POSITION, Schemas.kind(Schemas.properties(waypoint).get("pos")));
        assertTrue(Schemas.logicTypes(Schemas.Logic.CONDITION).containsAll(List.of("flag", "time", "kubejs")));
        assertTrue(Schemas.logicTypes(Schemas.Logic.TRIGGER).contains("custom"));
        assertNull(Schemas.typed(Schemas.logic("action"), "othermod:dance"), "another mod's type has no schema here");
    }

    @Test
    void variantsAreToldApartByTheirFields() {
        Schemas.Node target = Schemas.properties(Schemas.definition(ClientDevStore.TYPE_WAYPOINT)).get("target");
        assertEquals(2, Schemas.variantOf(target, json("{ \"structure\": \"#minecraft:village\" }")));
        JsonObject point = Schemas.skeleton(Schemas.variants(target).getFirst());
        assertEquals("[0,64,0]", point.get("pos").toString(), "a new point starts at sea level");
    }

    @Test
    void formsWriteIntoListsWithoutBreakingThem() {
        JsonObject root = json("{ \"leads_to\": [ {} ], \"on_complete\": { \"type\": \"notify\" } }");
        FormView form = new FormView(new FormView.Host() {
            @Override
            public JsonObject root() {
                return root;
            }

            @Override
            public String textKey(String path, String field) {
                return path + field;
            }

            @Override
            public void edited() {}

            @Override
            public void changed() {}
        });
        form.ensureObject(List.of("leads_to", 0)).addProperty("type", "quest");
        assertEquals("[{\"type\":\"quest\"}]", root.get("leads_to").toString(), "a field of a list element: the list stays a list");
        form.ensureObject(List.of("on_complete", 1)).addProperty("type", "end_dialogue");
        assertEquals("[{\"type\":\"notify\"},{\"type\":\"end_dialogue\"}]", root.get("on_complete").toString(),
                "a single action becomes the first element of a list");
        form.ensureObject(List.of("start", "trigger")).addProperty("type", "custom");
        assertEquals("{\"trigger\":{\"type\":\"custom\"}}", root.get("start").toString(), "missing objects are made");
        JsonElement rewards = form.ensureParent(List.of("on_complete", 1));
        assertTrue(rewards.isJsonArray() && rewards.getAsJsonArray().size() == 2, "replacing one action of a list goes through the list, which keeps the others");
    }

    @Test
    void typedTextBecomesTheRightValue() {
        assertEquals("[\"a\",\"b\"]", FormView.parse(Schemas.Kind.ID_LIST, "a, b").toString());
        assertEquals("\"a\"", FormView.parse(Schemas.Kind.ID_LIST, "a").toString());
        assertEquals("[1,2.5,3]", FormView.parse(Schemas.Kind.POSITION, "1 2.5 3").toString());
        assertNull(FormView.parse(Schemas.Kind.POSITION, "1 2"));
        assertNull(FormView.parse(Schemas.Kind.INTEGER, "x"), "not a number: the box turns red, the value stays");
        assertEquals("true", FormView.parse(Schemas.Kind.LITERAL, "true").toString());
        assertEquals("5", FormView.parse(Schemas.Kind.LITERAL, "5").toString());
        assertEquals("\"north\"", FormView.parse(Schemas.Kind.LITERAL, "north").toString());
        assertEquals("\"minecraft:wolf\"", FormView.parse(Schemas.Kind.RAW, "minecraft:wolf").toString(), "no quotes needed");
        assertEquals("{\"type\":\"minecraft:wolf\"}", FormView.parse(Schemas.Kind.RAW, "{\"type\":\"minecraft:wolf\"}").toString());
    }

    @Test
    void linksAreWrittenIntoTheFirstNode() {
        NodeKey npc = new NodeKey(ClientDevStore.TYPE_NPC, Storybound.id("smith"));
        NodeKey dialogue = new NodeKey(ClientDevStore.TYPE_DIALOGUE, DIALOGUE);
        NodeKey quest = new NodeKey(ClientDevStore.TYPE_QUEST, QUEST);
        NodeKey next = new NodeKey(ClientDevStore.TYPE_QUEST, Storybound.id("next"));
        JsonObject binding = new JsonObject();
        assertTrue(StoryEdits.link(binding, npc, dialogue));
        assertTrue(StoryEdits.link(binding, npc, dialogue));
        assertEquals(10, binding.getAsJsonArray("dialogues").get(1).getAsJsonObject().get("priority").getAsInt());

        JsonObject first = json("{ \"on_complete\": { \"type\": \"set_flag\", \"key\": \"x:done\" } }");
        assertTrue(StoryEdits.link(first, quest, next));
        JsonArray onComplete = first.getAsJsonArray("on_complete");
        assertEquals(2, onComplete.size(), "an action written as a single object is kept");
        assertEquals("set_flag", onComplete.get(0).getAsJsonObject().get("type").getAsString());

        assertFalse(StoryEdits.link(new JsonObject(), dialogue, npc), "no such link");
    }

    @Test
    void unlinkingRemovesWhatCanGoAndKeepsTheRest() {
        NodeKey waypoint = new NodeKey(ClientDevStore.TYPE_WAYPOINT, Storybound.id("den"));
        NodeKey dialogue = new NodeKey(ClientDevStore.TYPE_DIALOGUE, DIALOGUE);
        NodeKey document = new NodeKey(ClientDevStore.TYPE_DOCUMENT, Storybound.id("letter"));
        JsonObject quest = json("""
                { "start": { "trigger": { "type": "dialogue_ended", "dialogue": "storybound:elder" } },
                  "stages": [ { "id": "a", "waypoints": ["storybound:den", "storybound:camp"], "objectives": [
                      { "id": "talk", "trigger": { "type": "dialogue_ended", "dialogue": "storybound:elder" } },
                      { "id": "kill", "trigger": { "type": "entity_killed", "entity": "minecraft:wolf" } } ] } ],
                  "on_complete": { "type": "give_document", "document": "storybound:letter" } }
                """);
        assertTrue(StoryEdits.unlink(quest, waypoint));
        JsonObject stage = quest.getAsJsonArray("stages").get(0).getAsJsonObject();
        assertEquals("[\"storybound:camp\"]", stage.getAsJsonArray("waypoints").toString(), "only the deleted waypoint goes");
        assertTrue(StoryEdits.unlink(quest, dialogue));
        assertFalse(quest.has("start"), "a start waiting for the dialogue goes");
        assertEquals(1, stage.getAsJsonArray("objectives").size(), "and so does the objective waiting for it");
        assertTrue(StoryEdits.unlink(quest, document));
        assertEquals("[]", quest.get("on_complete").toString(), "a single action becomes an empty list");
        assertFalse(StoryEdits.unlink(quest, document), "nothing left to remove");

        JsonObject npc = json("{ \"dialogues\": [ { \"dialogue\": \"storybound:elder\" }, { \"dialogue\": \"storybound:other\" } ] }");
        assertTrue(StoryEdits.unlink(npc, dialogue));
        assertEquals(1, npc.getAsJsonArray("dialogues").size());

        JsonObject event = json("{ \"trigger\": { \"type\": \"document_read\", \"document\": \"storybound:letter\" }, \"actions\": [] }");
        assertFalse(StoryEdits.unlink(event, document), "a story event's trigger stays: removing it would break the event");
        assertTrue(StoryEdits.refersTo(event, document), "and it still counts as a reference");
    }

    @Test
    void scriptBlocksAreLinkedByTheirHandler() {
        ResourceLocation block = Storybound.id("burn_block");
        ClientDevStore.setSnapshot(Map.of(ClientDevStore.TYPE_SCRIPT, Map.of(block, "{ \"hook\": \"mypack:burn_village\" }")), List.of(), "en_us", List.of("en_us"));
        NodeKey script = new NodeKey(ClientDevStore.TYPE_SCRIPT, block);
        NodeKey dialogue = new NodeKey(ClientDevStore.TYPE_DIALOGUE, DIALOGUE);
        NodeKey quest = new NodeKey(ClientDevStore.TYPE_QUEST, QUEST);

        JsonObject talk = json("{ \"start\": \"a\", \"nodes\": { \"a\": { \"type\": \"line\", \"speaker\": \"npc\", \"text\": \"x\" } },"
                + " \"on_abort\": { \"type\": \"kubejs\", \"id\": \"mypack:burn_village\" } }");
        assertTrue(StoryEdits.link(talk, dialogue, script));
        JsonObject ending = DialogueGraph.node(talk, "script_burn_village");
        assertEquals("mypack:burn_village", ending.getAsJsonArray("actions").get(0).getAsJsonObject().get("id").getAsString(),
                "the dialogue ends by running the handler, not the block's id");

        JsonObject block1 = new JsonObject();
        assertTrue(StoryEdits.link(block1, script, quest));
        assertTrue(StoryEdits.link(block1, script, quest), "linking twice");
        assertEquals("[{\"type\":\"quest\",\"id\":\"storybound:hunt\"}]", block1.get("leads_to").toString(), "is kept once");
        assertTrue(StoryEdits.unlink(block1, quest));
        assertEquals("[]", block1.get("leads_to").toString());

        JsonObject guarded = json("{ \"conditions\": { \"type\": \"kubejs\", \"id\": \"mypack:burn_village\" },"
                + " \"actions\": [ { \"type\": \"kubejs\", \"id\": \"mypack:burn_village\" } ] }");
        assertTrue(StoryEdits.unlink(guarded, script));
        assertEquals("[]", guarded.get("actions").toString(), "the action running it goes");
        assertTrue(guarded.has("conditions"), "a condition asking it stays");
        assertTrue(StoryEdits.unlink(talk, script));
        assertEquals("[]", talk.get("on_abort").toString());
        ClientDevStore.setSnapshot(Map.of(), List.of(), "en_us", List.of("en_us"));
    }

    @Test
    void aQuestStartContinuesTheConversationInsteadOfCuttingIt() {
        JsonObject dialogue = json("""
                { "start": "greet", "nodes": {
                    "greet": { "type": "line", "speaker": "npc", "text": "a", "next": "ask" },
                    "ask": { "type": "line", "speaker": "npc", "text": "b" } } }
                """);
        StoryEdits.addQuestStart(dialogue, QUEST);
        JsonObject nodes = dialogue.getAsJsonObject("nodes");
        assertEquals("ask", nodes.getAsJsonObject("greet").get("next").getAsString(), "the start still leads on");
        assertEquals("start_quest_hunt", nodes.getAsJsonObject("ask").get("next").getAsString(), "the last line leads to the quest");
        assertEquals("end", nodes.getAsJsonObject("start_quest_hunt").get("type").getAsString());
    }

    @Test
    void aChoiceButtonGetsPastItsDefault() {
        // The server leaves the default out, so unset is "screen": the next press must give "hud", not "screen" again.
        List<String> presentation = List.of("", "screen", "hud", "bubble");
        assertEquals("hud", FormView.nextChoice(presentation, "", "screen"));
        assertEquals("bubble", FormView.nextChoice(presentation, "hud", "screen"));
        assertEquals("", FormView.nextChoice(presentation, "bubble", "screen"), "then back to unset (the default)");
        assertEquals("hud", FormView.nextChoice(presentation, "screen", "screen"), "a default written by hand moves on too");
        assertEquals("a", FormView.nextChoice(List.of("", "a", "b"), "", null), "without a default unset is its own step");
        assertEquals("off", FormView.nextChoice(List.of("auto", "off"), "", "auto"), "a required field with a default");
    }

    @Test
    void secondLinkAddsToTheEndingInsteadOfReplacingIt() {
        // The bug: each link pointed the start at a new end node, so the earlier endings were never reached.
        JsonObject dialogue = json("""
                { "start": "greet", "nodes": {
                    "greet": { "type": "line", "speaker": "npc", "text": "a" } } }
                """);
        StoryEdits.addQuestStart(dialogue, QUEST);
        StoryEdits.addGiveDocument(dialogue, Storybound.id("letter"));
        StoryEdits.addQuestStart(dialogue, QUEST);
        JsonObject nodes = dialogue.getAsJsonObject("nodes");
        assertEquals("start_quest_hunt", nodes.getAsJsonObject("greet").get("next").getAsString());
        assertEquals(2, nodes.size(), "one ending, no unreachable nodes");
        JsonArray actions = nodes.getAsJsonObject("start_quest_hunt").getAsJsonArray("actions");
        assertEquals(2, actions.size(), "the quest start and the document, the quest only once");
        assertEquals("give_document", actions.get(1).getAsJsonObject().get("type").getAsString());
    }

    @Test
    void anEndingWrittenByHandGetsTheAction() {
        JsonObject dialogue = json("""
                { "start": "greet", "nodes": {
                    "greet": { "type": "line", "speaker": "npc", "text": "a", "next": "bye" },
                    "bye": { "type": "end", "actions": { "type": "storybound:kubejs", "id": "mypack:burn" } } } }
                """);
        StoryEdits.addQuestStart(dialogue, QUEST);
        JsonObject nodes = dialogue.getAsJsonObject("nodes");
        assertEquals(2, nodes.size());
        assertEquals(2, nodes.getAsJsonObject("bye").getAsJsonArray("actions").size(), "the single action is kept as the first");
    }

    @Test
    void newDialogueNodesAreCompleteEnoughToLoad() {
        JsonObject dialogue = new JsonObject();
        String condition = DialogueGraph.createNode(dialogue, DIALOGUE, "condition", null, "line", "option");
        String action = DialogueGraph.createNode(dialogue, DIALOGUE, "action", null, "line", "option");
        String line = DialogueGraph.createNode(dialogue, DIALOGUE, "line", "elder", "Hello", "option");
        assertEquals(condition, DialogueGraph.start(dialogue), "the first node is the start");
        JsonObject nodes = dialogue.getAsJsonObject("nodes");
        assertTrue(nodes.getAsJsonObject(condition).has("conditions"), "a condition needs conditions");
        assertTrue(nodes.getAsJsonObject(action).has("actions"), "an action needs actions");
        assertEquals("elder", nodes.getAsJsonObject(line).get("speaker").getAsString());
        assertEquals("storybound.dialogue.elder.node.line_1.text", nodes.getAsJsonObject(line).get("text").getAsString());
        assertFalse(nodes.getAsJsonObject(line).has("options"), "lines get no options");
        assertFalse(nodes.getAsJsonObject(line).has("x"), "positions are not part of the dialogue");
    }

    @Test
    void connectingFillsAFreeOptionThenAddsOne() {
        JsonObject dialogue = new JsonObject();
        String choice = DialogueGraph.createNode(dialogue, DIALOGUE, "choice", null, "line", "Option 1");
        String a = DialogueGraph.createNode(dialogue, DIALOGUE, "line", null, "A", "");
        String b = DialogueGraph.createNode(dialogue, DIALOGUE, "line", null, "B", "");
        DialogueGraph.connect(dialogue, DIALOGUE, choice, a, "Option 2");
        DialogueGraph.connect(dialogue, DIALOGUE, choice, b, "Option 2");
        JsonArray options = DialogueGraph.nodes(dialogue).getAsJsonObject(choice).getAsJsonArray("options");
        assertEquals(2, options.size());
        assertEquals(List.of(a, b), options.asList().stream().map(o -> o.getAsJsonObject().get("next").getAsString()).toList());
        assertEquals(List.of(a, b), DialogueGraph.edges(dialogue, "").stream().filter(e -> e.from().equals(choice)).map(DialogueGraph.Edge::to).toList());

        DialogueGraph.deleteNode(dialogue, choice);
        assertEquals(a, DialogueGraph.start(dialogue), "the next node becomes the start");
        assertEquals(b, DialogueGraph.cycleTarget(dialogue, a));
        assertEquals(DialogueGraph.END, DialogueGraph.cycleTarget(dialogue, b));
        assertEquals(a, DialogueGraph.cycleTarget(dialogue, DialogueGraph.EXIT));
    }

    @Test
    void autoLayoutPutsEachStepInTheNextColumn() {
        JsonObject dialogue = json("""
                { "start": "a", "nodes": {
                    "a": { "type": "choice", "options": [ { "text": "x", "next": "b" }, { "text": "y", "next": "c" } ] },
                    "b": { "type": "line", "speaker": "npc", "text": "b" },
                    "c": { "type": "line", "speaker": "npc", "text": "c" },
                    "lonely": { "type": "end" } } }
                """);
        Map<String, int[]> positions = DialogueGraph.autoLayout(dialogue);
        assertEquals(DialogueGraph.MARGIN, positions.get("a")[0]);
        assertEquals(positions.get("b")[0], positions.get("c")[0], "both answers in the second column");
        assertNotEquals(positions.get("b")[1], positions.get("c")[1], "one under the other");
        assertEquals(DialogueGraph.MARGIN, positions.get("lonely")[0], "an unreachable node gets a column from the left");
    }

    @Test
    void sharedKeysAreSplit() {
        JsonObject dialogue = json("""
                { "nodes": {
                    "a": { "type": "line", "speaker": "npc", "text": "same.key" },
                    "b": { "type": "line", "speaker": "npc", "text": "same.key" } } }
                """);
        DialogueGraph.sanitizeKeys(DIALOGUE, dialogue);
        Set<String> keys = DialogueGraph.nodes(dialogue).entrySet().stream()
                .map(entry -> entry.getValue().getAsJsonObject().get("text").getAsString()).collect(Collectors.toSet());
        assertEquals(2, keys.size(), "every line has its own key");
    }

    @Test
    void contractTemplateMakesTheNoticeAndItsQuest() {
        List<NodeTemplates.Created> created = NodeTemplates.create(NodeTemplates.Template.CONTRACT, 3,
                new NodeTemplates.Place(null, "minecraft:overworld"), (key, args) -> key);
        assertEquals(2, created.size());
        NodeTemplates.Created quest = created.get(0);
        NodeTemplates.Created notice = created.get(1);
        assertEquals(ClientDevStore.TYPE_QUEST, quest.key().type());
        assertEquals(quest.key().id().toString(), notice.json().getAsJsonObject("contract").get("quest").getAsString());
        assertTrue(notice.text().isPresent());
        assertEquals("storybound.document.contract_note_3.title", notice.json().get("title").getAsString());
    }
}
