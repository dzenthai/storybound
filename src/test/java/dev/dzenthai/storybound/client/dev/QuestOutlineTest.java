package dev.dzenthai.storybound.client.dev;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.dzenthai.storybound.core.story.StoryLinks;
import dev.dzenthai.storybound.core.story.StoryLinks.NodeKey;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/** The "inside the quest" view's model: stages and what each one touches, read from the JSON alone. */
class QuestOutlineTest {
    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("mypack", path);
    }

    @Test
    void stagesWithWhatTheyTouch() {
        Map<ResourceLocation, Map<ResourceLocation, String>> data = Map.of(
                StoryLinks.QUEST, Map.of(
                        id("hunt"), """
                                { "title": "t", "stages": [
                                    { "id": "track", "log": "mypack.log.track",
                                      "on_enter": { "type": "give_document", "document": "mypack:oath" },
                                      "waypoints": [ "mypack:den" ],
                                      "objectives": [ { "id": "wolves", "text": "mypack.obj.wolves", "trigger": { "type": "entity_killed", "entity": "minecraft:wolf" } } ] },
                                    { "id": "report",
                                      "objectives": [ { "trigger": { "type": "dialogue_ended", "dialogue": "mypack:report" } } ] } ],
                                  "on_complete": { "type": "set_quest_state", "quest": "mypack:next", "state": "active" } }
                                """,
                        id("next"), "{ \"title\": \"n\" }"),
                StoryLinks.NPC, Map.of(id("elder"), """
                        { "entity": { "tag": "elder" }, "dialogues": [
                            { "dialogue": "mypack:give", "conditions": { "type": "quest", "quest": "mypack:hunt", "state": "unknown" }, "actions": { "type": "set_quest_state", "quest": "mypack:hunt", "state": "active" } },
                            { "dialogue": "mypack:hurry", "priority": 10, "conditions": { "type": "quest", "quest": "mypack:hunt", "state": "active" } },
                            { "dialogue": "mypack:report", "priority": 15, "conditions": { "type": "quest", "quest": "mypack:hunt", "stage": "report" } },
                            { "dialogue": "mypack:thanks", "priority": 20, "conditions": { "type": "quest", "quest": "mypack:hunt", "state": "completed" } } ] }
                        """),
                StoryLinks.EVENT, Map.of(id("howl"), """
                        { "trigger": { "type": "quest_stage_changed", "quest": "mypack:hunt", "stage": "track" }, "actions": { "type": "notify", "title": "x" } }
                        """),
                StoryLinks.DIALOGUE, Map.of(id("report"), "{ \"start\": \"a\", \"nodes\": { \"a\": { \"type\": \"end\" } } }",
                        id("innkeeper"), """
                        { "start": "a", "nodes": { "a": { "type": "choice", "options": [
                            { "text": "x", "conditions": { "type": "quest", "quest": "mypack:hunt", "stage": "track" } } ] } } }
                        """),
                StoryLinks.WAYPOINT, Map.of(id("den"), "{ \"name\": \"d\", \"target\": { \"pos\": [0, 0, 0] } }"),
                StoryLinks.DOCUMENT, Map.of(id("oath"), "{ \"kind\": \"note\", \"title\": \"o\" }"));
        ClientDevStore.setSnapshot(data, List.of(), "en_us", List.of("en_us"));
        try {
            NodeKey hunt = new NodeKey(StoryLinks.QUEST, id("hunt"));
            QuestOutline.Outline outline = QuestOutline.of(hunt, type -> data.getOrDefault(type, Map.of()), ClientDevStore.collectEdges());

            assertEquals(List.of("track", "report"), outline.stages().stream().map(QuestOutline.Stage::id).toList());
            QuestOutline.Stage track = outline.stages().getFirst();
            assertEquals("report", track.next());
            assertEquals("@complete", outline.stages().get(1).next());
            assertEquals("mypack.obj.wolves", track.objectives().getFirst().text());
            assertTrue(track.attached().contains(new QuestOutline.Attached(new NodeKey(StoryLinks.DOCUMENT, id("oath")), "on_enter")), "on_enter: " + track.attached());
            assertTrue(track.attached().contains(new QuestOutline.Attached(new NodeKey(StoryLinks.WAYPOINT, id("den")), "waypoint")));
            assertTrue(track.attached().contains(new QuestOutline.Attached(new NodeKey(StoryLinks.EVENT, id("howl")), "event")), "a story event on reaching the stage");
            assertTrue(track.attached().contains(new QuestOutline.Attached(new NodeKey(StoryLinks.DIALOGUE, id("innkeeper")), "asks")), "a dialogue asking for the stage");

            QuestOutline.Stage report = outline.stages().get(1);
            assertTrue(report.attached().contains(new QuestOutline.Attached(new NodeKey(StoryLinks.NPC, id("elder")), "npc")), "the NPC's entry for this stage: " + report.attached());
            assertEquals(new NodeKey(StoryLinks.DIALOGUE, id("report")), report.objectives().getFirst().target());

            assertTrue(outline.starts().contains(new NodeKey(StoryLinks.NPC, id("elder"))), "started by the elder: " + outline.starts());
            assertTrue(outline.during().contains(new QuestOutline.Attached(new NodeKey(StoryLinks.NPC, id("elder")), "npc")), "the elder while it is active");
            assertTrue(outline.after().stream().anyMatch(item -> item.key().equals(new NodeKey(StoryLinks.QUEST, id("next")))), "the quest that follows: " + outline.after());
        } finally {
            ClientDevStore.setSnapshot(Map.of(), List.of(), "en_us", List.of("en_us"));
        }
    }
}
