package dev.dzenthai.storybound.client.dev;

import dev.dzenthai.storybound.client.dev.ClientDevStore.Edge;
import dev.dzenthai.storybound.client.dev.ClientDevStore.NodeKey;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The story map's view of the links: which quest follows which, with the nodes between them folded into the arrow (a
 * quest starts a dialogue that starts the next quest: one arrow "through the dialogue"), and where a quest no other
 * quest leads to starts (an NPC's dialogue, a document found in the world). Works on {@link ClientDevStore#collectEdges}
 * and nothing else, so it needs no game.
 */
final class StoryFlow {
    /** How far a chain of nodes between two quests is followed. */
    private static final int MAX_VIA = 6;
    /** Links that say nothing about what comes next: a quest shows a waypoint, tracks a quest on the HUD. */
    private static final Set<String> NOT_FLOW = Set.of("stage", "quest", "track_quest");
    /** Links written in the node that waits (a trigger or condition), pointing at what it waits for. */
    private static final Set<String> WAITS = Set.of("boss_killed", "codex_unlocked");

    /**
     * One quest leads to another.
     *
     * @param via the nodes between them, in order; empty when the quest names the next one itself
     */
    record Flow(NodeKey from, NodeKey to, List<NodeKey> via) {}

    private StoryFlow() {}

    /** The links in the order things happen: from the cause to what it causes. */
    static Map<NodeKey, List<NodeKey>> forward(Collection<Edge> edges) {
        Map<NodeKey, List<NodeKey>> next = new LinkedHashMap<>();
        for (Edge edge : edges) {
            if (NOT_FLOW.contains(edge.label()) || edge.from().equals(edge.to())) {
                continue;
            }
            boolean reversed = WAITS.contains(edge.label());
            NodeKey cause = reversed ? edge.to() : edge.from();
            NodeKey effect = reversed ? edge.from() : edge.to();
            List<NodeKey> targets = next.computeIfAbsent(cause, key -> new ArrayList<>());
            if (!targets.contains(effect)) {
                targets.add(effect);
            }
        }
        return next;
    }

    private static Map<NodeKey, List<NodeKey>> backward(Map<NodeKey, List<NodeKey>> forward) {
        Map<NodeKey, List<NodeKey>> previous = new LinkedHashMap<>();
        forward.forEach((cause, effects) -> effects.forEach(effect -> previous.computeIfAbsent(effect, key -> new ArrayList<>()).add(cause)));
        return previous;
    }

    private static boolean isQuest(NodeKey key) {
        return key.type().equals(ClientDevStore.TYPE_QUEST);
    }

    /** Every quest that follows another, by the shortest chain of other nodes between them. */
    static List<Flow> flows(Collection<Edge> edges) {
        Map<NodeKey, List<NodeKey>> next = forward(edges);
        Set<NodeKey> quests = new LinkedHashSet<>();
        next.forEach((cause, effects) -> {
            if (isQuest(cause)) {
                quests.add(cause);
            }
        });
        List<Flow> flows = new ArrayList<>();
        for (NodeKey quest : quests) {
            // Breadth first, so the arrow names the shortest way; quests end a chain, they are not passed through.
            Map<NodeKey, NodeKey> cameFrom = new HashMap<>();
            Deque<NodeKey> queue = new ArrayDeque<>();
            Map<NodeKey, Integer> depth = new HashMap<>();
            queue.add(quest);
            depth.put(quest, 0);
            Set<NodeKey> reached = new LinkedHashSet<>();
            while (!queue.isEmpty()) {
                NodeKey node = queue.poll();
                int d = depth.get(node);
                for (NodeKey effect : next.getOrDefault(node, List.of())) {
                    if (depth.containsKey(effect)) {
                        continue;
                    }
                    depth.put(effect, d + 1);
                    cameFrom.put(effect, node);
                    if (isQuest(effect)) {
                        reached.add(effect);
                    } else if (d + 1 <= MAX_VIA) {
                        queue.add(effect);
                    }
                }
            }
            for (NodeKey target : reached) {
                List<NodeKey> via = new ArrayList<>();
                for (NodeKey step = cameFrom.get(target); step != null && !step.equals(quest); step = cameFrom.get(step)) {
                    via.addFirst(step);
                }
                flows.add(new Flow(quest, target, List.copyOf(via)));
            }
        }
        return flows;
    }

    /**
     * Where each quest starts outside the quests: the chain from a node nothing leads to (an NPC, a document
     * found in the world, a story event) to the quest, that node first. Quests only other quests lead to, and quests
     * nothing starts, have none.
     */
    static Map<NodeKey, List<NodeKey>> entries(Collection<Edge> edges) {
        Map<NodeKey, List<NodeKey>> previous = backward(forward(edges));
        Map<NodeKey, List<NodeKey>> entries = new LinkedHashMap<>();
        Set<NodeKey> quests = new LinkedHashSet<>();
        previous.keySet().stream().filter(StoryFlow::isQuest).forEach(quests::add);
        for (NodeKey quest : quests) {
            Map<NodeKey, NodeKey> towards = new HashMap<>();
            Map<NodeKey, Integer> depth = new HashMap<>();
            Deque<NodeKey> queue = new ArrayDeque<>();
            queue.add(quest);
            depth.put(quest, 0);
            NodeKey root = null;
            while (!queue.isEmpty() && root == null) {
                NodeKey node = queue.poll();
                int d = depth.get(node);
                for (NodeKey cause : previous.getOrDefault(node, List.of())) {
                    // A quest is no beginning: the map draws it as an arrow.
                    if (isQuest(cause) || depth.containsKey(cause) || d + 1 > MAX_VIA) {
                        continue;
                    }
                    depth.put(cause, d + 1);
                    towards.put(cause, node);
                    if (previous.getOrDefault(cause, List.of()).isEmpty()) {
                        root = cause;
                        break;
                    }
                    queue.add(cause);
                }
            }
            if (root == null) {
                // Only from other quests, or from a loop of nodes with no beginning.
                continue;
            }
            List<NodeKey> chain = new ArrayList<>();
            for (NodeKey step = root; step != null && !step.equals(quest); step = towards.get(step)) {
                chain.add(step);
            }
            entries.put(quest, List.copyOf(chain));
        }
        return entries;
    }

    /**
     * The map's columns: a quest stands one column after the farthest quest leading to it, so the story reads left to
     * right. A loop (a repeatable quest leading back) is cut where it closes.
     */
    static Map<NodeKey, Integer> columns(Collection<NodeKey> quests, Collection<Flow> flows) {
        Map<NodeKey, Integer> columns = new LinkedHashMap<>();
        quests.forEach(quest -> columns.put(quest, 0));
        // Longest path by relaxation; more rounds than quests would only go round a loop.
        for (int round = 0; round < quests.size(); round++) {
            boolean moved = false;
            for (Flow flow : flows) {
                Integer from = columns.get(flow.from());
                Integer to = columns.get(flow.to());
                if (from != null && to != null && to < from + 1 && from + 1 < quests.size()) {
                    columns.put(flow.to(), from + 1);
                    moved = true;
                }
            }
            if (!moved) {
                break;
            }
        }
        return columns;
    }
}
