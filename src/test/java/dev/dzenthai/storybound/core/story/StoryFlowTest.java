package dev.dzenthai.storybound.core.story;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.client.dev.ClientDevStore;
import dev.dzenthai.storybound.core.story.StoryLinks.Edge;
import dev.dzenthai.storybound.core.story.StoryLinks.NodeKey;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class StoryFlowTest {
    private static NodeKey key(ResourceLocation type, String id) {
        return new NodeKey(type, Storybound.id(id));
    }

    private static final NodeKey WOLVES = key(StoryLinks.QUEST, "wolves");
    private static final NodeKey ALPHA = key(StoryLinks.QUEST, "alpha");
    private static final NodeKey FIRE = key(StoryLinks.QUEST, "fire");
    private static final NodeKey ELDER = key(StoryLinks.NPC, "elder");
    private static final NodeKey ELDER_TALK = key(StoryLinks.DIALOGUE, "elder_talk");
    private static final NodeKey REPORT = key(StoryLinks.DIALOGUE, "report");
    private static final NodeKey BURN = key(StoryLinks.SCRIPT, "burn");
    private static final NodeKey DEN = key(StoryLinks.WAYPOINT, "den");
    private static final NodeKey BEAST = key(StoryLinks.BOSS, "beast");

    @Test
    void aQuestNamingTheNextLeadsStraightToIt() {
        List<StoryFlow.Flow> flows = StoryFlow.flows(List.of(new Edge(WOLVES, ALPHA, "set_quest_state")));
        assertEquals(List.of(new StoryFlow.Flow(WOLVES, ALPHA, List.of())), flows);
    }

    @Test
    void nodesBetweenTwoQuestsFoldIntoTheArrow() {
        // The wolves quest starts the report dialogue, which runs the script, which starts the fire quest.
        List<StoryFlow.Flow> flows = StoryFlow.flows(List.of(
                new Edge(WOLVES, REPORT, "start"),
                new Edge(REPORT, BURN, "script"),
                new Edge(BURN, FIRE, "leads")));
        assertEquals(List.of(new StoryFlow.Flow(WOLVES, FIRE, List.of(REPORT, BURN))), flows);
    }

    @Test
    void aQuestWaitingForAnotherFollowsIt() {
        // "after" points from the quest waited for; "boss_killed" is written in the one that waits.
        List<StoryFlow.Flow> flows = StoryFlow.flows(List.of(
                new Edge(WOLVES, ALPHA, "after"),
                new Edge(FIRE, BEAST, "boss_killed"),
                new Edge(ALPHA, BEAST, "unlock_boss_section")));
        assertTrue(flows.contains(new StoryFlow.Flow(WOLVES, ALPHA, List.of())));
        assertTrue(flows.contains(new StoryFlow.Flow(ALPHA, FIRE, List.of(BEAST))));
    }

    @Test
    void questsEndAChainAndWaypointsLeadNowhere() {
        List<StoryFlow.Flow> flows = StoryFlow.flows(List.of(
                new Edge(WOLVES, ALPHA, "set_quest_state"),
                new Edge(ALPHA, FIRE, "set_quest_state"),
                new Edge(WOLVES, DEN, "stage"),
                new Edge(FIRE, DEN, "quest")));
        assertEquals(2, flows.size());
        assertFalse(flows.stream().anyMatch(flow -> flow.from().equals(WOLVES) && flow.to().equals(FIRE)));
    }

    @Test
    void aQuestStartedByAnNpcSaysSo() {
        List<Edge> edges = List.of(
                new Edge(ELDER, ELDER_TALK, "talk"),
                new Edge(ELDER_TALK, WOLVES, "set_quest_state"),
                new Edge(WOLVES, ALPHA, "set_quest_state"));
        Map<NodeKey, List<NodeKey>> entries = StoryFlow.entries(edges);
        assertEquals(List.of(ELDER, ELDER_TALK), entries.get(WOLVES));
        assertFalse(entries.containsKey(ALPHA));
    }

    @Test
    void columnsFollowTheStoryAndSurviveALoop() {
        List<StoryFlow.Flow> flows = List.of(
                new StoryFlow.Flow(WOLVES, ALPHA, List.of()),
                new StoryFlow.Flow(ALPHA, FIRE, List.of()),
                new StoryFlow.Flow(WOLVES, FIRE, List.of()),
                new StoryFlow.Flow(FIRE, WOLVES, List.of()));
        Map<NodeKey, Integer> columns = StoryFlow.columns(List.of(WOLVES, ALPHA, FIRE), flows);
        assertTrue(columns.get(ALPHA) > columns.get(WOLVES) || columns.get(WOLVES) > columns.get(FIRE));
        assertTrue(columns.values().stream().allMatch(column -> column >= 0 && column < 3));
        Map<NodeKey, Integer> straight = StoryFlow.columns(List.of(WOLVES, ALPHA, FIRE), flows.subList(0, 3));
        assertEquals(Map.of(WOLVES, 0, ALPHA, 1, FIRE, 2), straight);
    }

    @Test
    void aConditionOnAnotherQuestIsALink() {
        ClientDevStore.setSnapshot(Map.of(StoryLinks.QUEST, Map.of(
                ALPHA.id(), """
                        {"start": {"conditions": [
                          {"type": "quest", "quest": "storybound:wolves", "state": "completed"},
                          {"type": "quest", "quest": "storybound:alpha", "state": "unknown"}]}}""",
                FIRE.id(), """
                        {"start": {"trigger": {"type": "storybound:quest_state_changed", "quest": "storybound:alpha", "state": "completed"}}}"""
        )), List.of(), "en_us", List.of("en_us"));
        List<Edge> edges = ClientDevStore.collectEdges();
        assertTrue(edges.contains(new Edge(WOLVES, ALPHA, "after")));
        assertTrue(edges.contains(new Edge(ALPHA, FIRE, "after")));
        assertFalse(edges.stream().anyMatch(edge -> edge.from().equals(edge.to())));
        ClientDevStore.setSnapshot(Map.of(), List.of(), "en_us", List.of("en_us"));
    }

    @Test
    void aQuestPlayingASceneAndTheSceneMovingTheQuestAreLinks() {
        NodeKey arrival = key(StoryLinks.SCENE, "arrival");
        ClientDevStore.setSnapshot(Map.of(
                StoryLinks.QUEST, Map.of(ALPHA.id(), """
                        {"on_start": [{"type": "play_scene", "scene": "storybound:arrival"}]}"""),
                StoryLinks.SCENE, Map.of(arrival.id(), """
                        {"shots": [{"camera": {"from": [0, 1, 0], "duration": 2}}],
                         "on_end": {"type": "set_quest_stage", "quest": "storybound:fire", "stage": "talk"}}""")
        ), List.of(), "en_us", List.of("en_us"));
        List<Edge> edges = ClientDevStore.collectEdges();
        assertTrue(edges.contains(new Edge(ALPHA, arrival, "plays")), "the quest plays the scene");
        assertTrue(edges.contains(new Edge(arrival, FIRE, "set_quest_stage")), "the scene's end moves the other quest");
        ClientDevStore.setSnapshot(Map.of(), List.of(), "en_us", List.of("en_us"));
    }

    @Test
    void sceneAnchorsTalksAndTriggersAreLinked() {
        NodeKey arrival = key(StoryLinks.SCENE, "arrival");
        ClientDevStore.setSnapshot(Map.of(
                StoryLinks.DIALOGUE, Map.of(ELDER_TALK.id(), """
                        {"nodes": {"cut": {"type": "scene", "scene": "storybound:arrival", "next": "@end"}}}"""),
                StoryLinks.EVENT, Map.of(FESTIVAL.id(), """
                        {"trigger": {"type": "scene_finished", "scene": "storybound:arrival"},
                         "conditions": [{"type": "in_scene", "scene": "storybound:arrival"}]}"""),
                StoryLinks.SCENE, Map.of(arrival.id(), """
                        {"shots": [
                          {"anchor": {"npc": "storybound:elder"}, "camera": {"from": [0, 1, 0], "duration": 2, "look": {"waypoint": "storybound:den"}}},
                          {"dialogue": "storybound:report"}
                        ]}""")
        ), List.of(), "en_us", List.of("en_us"));
        List<Edge> edges = ClientDevStore.collectEdges();
        assertTrue(edges.contains(new Edge(ELDER_TALK, arrival, "plays")), "dialogue scene node links to scene");
        assertTrue(edges.contains(new Edge(arrival, FESTIVAL, "scene_finished")), "scene_finished links from scene to event");
        assertTrue(edges.contains(new Edge(arrival, FESTIVAL, "in_scene")), "in_scene links from scene to event");
        assertTrue(edges.contains(new Edge(arrival, ELDER, "anchor")), "scene anchor links to NPC");
        assertTrue(edges.contains(new Edge(arrival, DEN, "look")), "scene look target links to waypoint");
        assertTrue(edges.contains(new Edge(arrival, REPORT, "talk")), "scene talk shot links to dialogue");
        ClientDevStore.setSnapshot(Map.of(), List.of(), "en_us", List.of("en_us"));
    }

    private static final NodeKey FESTIVAL = key(StoryLinks.EVENT, "festival");

    /** The editor's canvas: quests and story events are its nodes, the rest badges under them. */
    private static boolean spine(NodeKey key) {
        return key.type().equals(StoryLinks.QUEST) || key.type().equals(StoryLinks.EVENT);
    }

    @Test
    void aStoryEventIsANodeOfTheCanvasNotPartOfAnArrow() {
        // The wolves quest's end runs the festival event, which starts the fire quest.
        List<Edge> edges = List.of(new Edge(WOLVES, FESTIVAL, "leads"), new Edge(FESTIVAL, FIRE, "set_quest_state"));
        assertEquals(List.of(new StoryFlow.Flow(WOLVES, FIRE, List.of(FESTIVAL))), StoryFlow.flows(edges), "the players' map folds it");
        List<StoryFlow.Flow> canvas = StoryFlow.flows(edges, StoryFlowTest::spine);
        assertTrue(canvas.contains(new StoryFlow.Flow(WOLVES, FESTIVAL, List.of())));
        assertTrue(canvas.contains(new StoryFlow.Flow(FESTIVAL, FIRE, List.of())));
    }

    @Test
    void aQuestsOwnNodesAreItsBadges() {
        // The elder gives the wolves quest through his dialogue; the quest shows the den, its end starts the report
        // dialogue leading to the fire quest, and the beast is its boss.
        List<Edge> edges = List.of(
                new Edge(ELDER, ELDER_TALK, "talk"),
                new Edge(ELDER_TALK, WOLVES, "set_quest_state"),
                new Edge(WOLVES, DEN, "stage"),
                new Edge(WOLVES, REPORT, "start"),
                new Edge(REPORT, FIRE, "set_quest_state"),
                new Edge(WOLVES, BEAST, "unlock_boss_section"),
                new Edge(ELDER, FIRE, "after"));
        Map<NodeKey, List<NodeKey>> badges = StoryFlow.attached(edges, StoryFlowTest::spine);
        assertEquals(List.of(ELDER, ELDER_TALK, REPORT, DEN, BEAST), badges.get(WOLVES), "giver first, then what is between, then the rest");
        assertTrue(badges.get(FIRE).contains(REPORT), "a node between two quests is under both");
        assertFalse(badges.get(WOLVES).contains(FIRE), "never another quest");
    }
}
