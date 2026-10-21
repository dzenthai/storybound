package dev.dzenthai.storybound.client.dev;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.client.dev.ClientDevStore.Edge;
import dev.dzenthai.storybound.client.dev.ClientDevStore.NodeKey;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class StoryFlowTest {
    private static NodeKey key(ResourceLocation type, String id) {
        return new NodeKey(type, Storybound.id(id));
    }

    private static final NodeKey WOLVES = key(ClientDevStore.TYPE_QUEST, "wolves");
    private static final NodeKey ALPHA = key(ClientDevStore.TYPE_QUEST, "alpha");
    private static final NodeKey FIRE = key(ClientDevStore.TYPE_QUEST, "fire");
    private static final NodeKey ELDER = key(ClientDevStore.TYPE_NPC, "elder");
    private static final NodeKey ELDER_TALK = key(ClientDevStore.TYPE_DIALOGUE, "elder_talk");
    private static final NodeKey REPORT = key(ClientDevStore.TYPE_DIALOGUE, "report");
    private static final NodeKey BURN = key(ClientDevStore.TYPE_SCRIPT, "burn");
    private static final NodeKey DEN = key(ClientDevStore.TYPE_WAYPOINT, "den");
    private static final NodeKey BEAST = key(ClientDevStore.TYPE_BOSS, "beast");

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
        ClientDevStore.setSnapshot(Map.of(ClientDevStore.TYPE_QUEST, Map.of(
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
}
