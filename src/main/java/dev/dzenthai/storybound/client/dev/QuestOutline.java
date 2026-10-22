package dev.dzenthai.storybound.client.dev;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.dzenthai.storybound.core.story.StoryFlow;
import dev.dzenthai.storybound.core.story.StoryLinks;
import dev.dzenthai.storybound.core.story.StoryLinks.Edge;
import dev.dzenthai.storybound.core.story.StoryLinks.NodeKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * What a quest is made of, stage by stage, and what each stage touches: what starts the quest, then each stage with
 * its objectives and what belongs to it — the dialogues, documents and scripts its {@code on_enter} runs, its waypoints,
 * what its objectives wait for, and what elsewhere asks for this very stage (an NPC's dialogue entry, a story event on
 * reaching it, a dialogue with a condition on it) — then what follows the quest. Read from the definitions' JSON
 * only, without any GUI, for the editor's "inside the quest" view.
 */
final class QuestOutline {
    /** Something a stage touches, and how: {@code on_enter}, {@code waypoint}, {@code objective}, {@code npc}, {@code event}, {@code asks}. */
    record Attached(NodeKey key, String relation) {}

    /** An objective's line: its id, its text key (or empty) and what it waits for, if that is a node. */
    record Objective(String id, String text, @Nullable NodeKey target) {}

    record Stage(String id, String log, List<Objective> objectives, List<Attached> attached, String next) {}

    /**
     * @param starts what starts the quest (an NPC's dialogue, a document, an event)
     * @param during what asks for the quest while it is active, at any stage
     * @param after  what the quest's completion runs and the quests that follow it
     */
    record Outline(NodeKey quest, List<NodeKey> starts, List<Attached> during, List<Stage> stages, List<Attached> after) {}

    private QuestOutline() {}

    static Outline of(NodeKey quest, Function<ResourceLocation, Map<ResourceLocation, String>> byType, List<Edge> edges) {
        JsonObject json = ClientDevStore.parseObject(byType.apply(StoryLinks.QUEST).get(quest.id()));
        Map<String, NodeKey> hooks = new LinkedHashMap<>();
        byType.apply(StoryLinks.SCRIPT).forEach((id, raw) -> hooks.putIfAbsent(StoryLinks.scriptHook(id, ClientDevStore.parseObject(raw)).toString(),
                new NodeKey(StoryLinks.SCRIPT, id)));

        List<NodeKey> starts = new ArrayList<>(StoryFlow.entries(edges).getOrDefault(quest, List.of()));
        for (StoryFlow.Flow flow : StoryFlow.flows(edges)) {
            if (flow.to().equals(quest) && !starts.contains(flow.from())) {
                starts.add(flow.from());
            }
        }

        List<JsonObject> stageJson = new ArrayList<>();
        if (json.get("stages") instanceof JsonArray stages) {
            stages.forEach(element -> {
                if (element instanceof JsonObject stage && stage.get("id") instanceof JsonPrimitive) {
                    stageJson.add(stage);
                }
            });
        }
        Map<String, List<Attached>> byStage = new LinkedHashMap<>();
        stageJson.forEach(stage -> byStage.put(stage.get("id").getAsString(), new ArrayList<>()));
        List<Attached> during = new ArrayList<>();
        List<Attached> after = new ArrayList<>();

        // Elsewhere: NPC entries, story events and dialogues that ask for this quest (at a stage, or while active, or done).
        byType.apply(StoryLinks.NPC).forEach((id, raw) -> {
            if (ClientDevStore.parseObject(raw).get("dialogues") instanceof JsonArray entries) {
                for (JsonElement entry : entries) {
                    if (entry instanceof JsonObject object) {
                        sortQuestAsk(object.get("conditions"), quest.id(), new NodeKey(StoryLinks.NPC, id), "npc", byStage, during, after);
                    }
                }
            }
        });
        byType.apply(StoryLinks.EVENT).forEach((id, raw) -> {
            JsonObject event = ClientDevStore.parseObject(raw);
            if (event.get("trigger") instanceof JsonObject trigger && isType(trigger, "quest_stage_changed") && names(trigger, "quest", quest.id())
                    && trigger.get("stage") instanceof JsonPrimitive stage && byStage.containsKey(stage.getAsString())) {
                byStage.get(stage.getAsString()).add(new Attached(new NodeKey(StoryLinks.EVENT, id), "event"));
            } else if (event.get("trigger") instanceof JsonObject trigger && isType(trigger, "quest_state_changed") && names(trigger, "quest", quest.id())) {
                after.add(new Attached(new NodeKey(StoryLinks.EVENT, id), "event"));
            }
        });
        byType.apply(StoryLinks.DIALOGUE).forEach((id, raw) -> {
            Set<String> stages = new LinkedHashSet<>();
            collectStageAsks(ClientDevStore.parseObject(raw), quest.id(), stages);
            for (String stage : stages) {
                if (byStage.containsKey(stage)) {
                    add(byStage.get(stage), new Attached(new NodeKey(StoryLinks.DIALOGUE, id), "asks"));
                }
            }
        });

        // The quest's own: what each stage runs and shows, what its objectives wait for.
        byType.apply(StoryLinks.WAYPOINT).forEach((id, raw) -> {
            JsonObject waypoint = ClientDevStore.parseObject(raw);
            if (names(waypoint, "quest", quest.id()) && waypoint.get("stage") instanceof JsonPrimitive stage && byStage.containsKey(stage.getAsString())) {
                add(byStage.get(stage.getAsString()), new Attached(new NodeKey(StoryLinks.WAYPOINT, id), "waypoint"));
            }
        });
        List<Stage> stages = new ArrayList<>();
        for (int i = 0; i < stageJson.size(); i++) {
            JsonObject stage = stageJson.get(i);
            String id = stage.get("id").getAsString();
            List<Attached> attached = byStage.get(id);
            List<Attached> own = new ArrayList<>();
            refs(stage.get("on_enter"), hooks, key -> own.add(new Attached(key, "on_enter")));
            if (stage.get("waypoints") instanceof JsonArray waypoints) {
                waypoints.forEach(waypoint -> id(waypoint).ifPresent(key -> own.add(new Attached(new NodeKey(StoryLinks.WAYPOINT, key), "waypoint"))));
            }
            List<Objective> objectives = new ArrayList<>();
            if (stage.get("objectives") instanceof JsonArray list) {
                for (int o = 0; o < list.size(); o++) {
                    if (!(list.get(o) instanceof JsonObject objective)) {
                        continue;
                    }
                    String objectiveId = objective.get("id") instanceof JsonPrimitive named ? named.getAsString() : String.valueOf(o);
                    String text = objective.get("text") instanceof JsonPrimitive line ? line.getAsString() : "";
                    NodeKey target = objective.get("trigger") instanceof JsonObject trigger ? waitsFor(trigger) : null;
                    objectives.add(new Objective(objectiveId, text, target));
                    if (target != null) {
                        own.add(new Attached(target, "objective"));
                    }
                }
            }
            for (Attached item : own) {
                add(attached, item);
            }
            String next = stage.get("next") instanceof JsonPrimitive explicit ? explicit.getAsString()
                    : i + 1 < stageJson.size() ? stageJson.get(i + 1).get("id").getAsString() : "@complete";
            String log = stage.get("log") instanceof JsonPrimitive line ? line.getAsString() : "";
            stages.add(new Stage(id, log, List.copyOf(objectives), List.copyOf(attached), next));
        }

        List<Attached> completion = new ArrayList<>();
        refs(json.get("on_complete"), hooks, key -> completion.add(new Attached(key, "on_complete")));
        if (json.get("rewards") instanceof JsonObject rewards) {
            refs(rewards.get("actions"), hooks, key -> completion.add(new Attached(key, "on_complete")));
        } else {
            refs(json.get("rewards"), hooks, key -> completion.add(new Attached(key, "on_complete")));
        }
        for (StoryFlow.Flow flow : StoryFlow.flows(edges)) {
            if (flow.from().equals(quest)) {
                completion.add(new Attached(flow.to(), "next"));
            }
        }
        for (Attached item : after) {
            completion.add(item);
        }
        List<Attached> done = new ArrayList<>();
        completion.forEach(item -> add(done, item));
        return new Outline(quest, List.copyOf(starts), List.copyOf(during), List.copyOf(stages), List.copyOf(done));
    }

    /** One per node: the first relation found is kept. */
    private static void add(List<Attached> list, Attached item) {
        if (list.stream().noneMatch(existing -> existing.key().equals(item.key()))) {
            list.add(item);
        }
    }

    /** Where an NPC entry's conditions ask for the quest: at a stage, while active, or once done. */
    private static void sortQuestAsk(@Nullable JsonElement conditions, ResourceLocation quest, NodeKey owner, String relation,
                                     Map<String, List<Attached>> byStage, List<Attached> during, List<Attached> after) {
        List<JsonObject> asks = new ArrayList<>();
        questConditions(conditions, quest, asks);
        for (JsonObject ask : asks) {
            if (ask.get("stage") instanceof JsonPrimitive stage && byStage.containsKey(stage.getAsString())) {
                add(byStage.get(stage.getAsString()), new Attached(owner, relation));
            } else if (ask.get("state") instanceof JsonPrimitive state && state.getAsString().equals("active")) {
                add(during, new Attached(owner, relation));
            } else if (ask.get("state") instanceof JsonPrimitive state && state.getAsString().equals("completed")) {
                add(after, new Attached(owner, relation));
            }
        }
    }

    /** Every {@code quest} condition naming the quest, inside {@code all}, {@code any} and {@code not} too. */
    private static void questConditions(@Nullable JsonElement element, ResourceLocation quest, List<JsonObject> out) {
        if (element instanceof JsonArray array) {
            array.forEach(child -> questConditions(child, quest, out));
        } else if (element instanceof JsonObject object) {
            if (isType(object, "quest") && names(object, "quest", quest)) {
                out.add(object);
            }
            object.entrySet().forEach(entry -> questConditions(entry.getValue(), quest, out));
        }
    }

    /** The stages a dialogue's conditions ask for (options, condition nodes). */
    private static void collectStageAsks(JsonElement element, ResourceLocation quest, Set<String> stages) {
        List<JsonObject> asks = new ArrayList<>();
        questConditions(element, quest, asks);
        for (JsonObject ask : asks) {
            if (ask.get("stage") instanceof JsonPrimitive stage) {
                stages.add(stage.getAsString());
            }
        }
    }

    /** The nodes an action list names: dialogues started, documents given, quests, scripts run, Codex and bosses opened. */
    private static void refs(@Nullable JsonElement element, Map<String, NodeKey> hooks, Consumer<NodeKey> out) {
        if (element instanceof JsonArray array) {
            array.forEach(child -> refs(child, hooks, out));
            return;
        }
        if (!(element instanceof JsonObject object)) {
            return;
        }
        String type = object.get("type") instanceof JsonPrimitive raw ? path(raw.getAsString()) : "";
        switch (type) {
            case "start_dialogue" -> id(object.get("dialogue")).ifPresent(id -> out.accept(new NodeKey(StoryLinks.DIALOGUE, id)));
            case "give_document" -> id(object.get("document")).ifPresent(id -> out.accept(new NodeKey(StoryLinks.DOCUMENT, id)));
            case "set_quest_state", "set_quest_stage" -> id(object.get("quest")).ifPresent(id -> out.accept(new NodeKey(StoryLinks.QUEST, id)));
            case "unlock_codex" -> id(object.get("entry")).ifPresent(id -> out.accept(new NodeKey(StoryLinks.CODEX, id)));
            case "unlock_boss_section" -> id(object.get("boss")).ifPresent(id -> out.accept(new NodeKey(StoryLinks.BOSS, id)));
            case "kubejs" -> {
                if (object.get("id") instanceof JsonPrimitive hook && hooks.containsKey(hook.getAsString())) {
                    out.accept(hooks.get(hook.getAsString()));
                }
            }
            default -> {}
        }
        object.entrySet().forEach(entry -> {
            if (!entry.getKey().equals("type")) {
                refs(entry.getValue(), hooks, out);
            }
        });
    }

    /** What an objective's trigger waits for, when that is a node of the story. */
    private static @Nullable NodeKey waitsFor(JsonObject trigger) {
        String type = trigger.get("type") instanceof JsonPrimitive raw ? path(raw.getAsString()) : "";
        return switch (type) {
            case "dialogue_ended" -> id(trigger.get("dialogue")).map(id -> new NodeKey(StoryLinks.DIALOGUE, id)).orElse(null);
            case "document_read" -> id(trigger.get("document")).map(id -> new NodeKey(StoryLinks.DOCUMENT, id)).orElse(null);
            case "boss_killed" -> id(trigger.get("boss")).map(id -> new NodeKey(StoryLinks.BOSS, id)).orElse(null);
            case "codex_unlocked" -> id(trigger.get("entry")).map(id -> new NodeKey(StoryLinks.CODEX, id)).orElse(null);
            case "quest_state_changed", "quest_stage_changed" -> id(trigger.get("quest")).map(id -> new NodeKey(StoryLinks.QUEST, id)).orElse(null);
            default -> null;
        };
    }

    private static boolean isType(JsonObject object, String type) {
        return object.get("type") instanceof JsonPrimitive raw && path(raw.getAsString()).equals(type);
    }

    private static boolean names(JsonObject object, String field, ResourceLocation id) {
        return id(object.get(field)).map(id::equals).orElse(false);
    }

    private static Optional<ResourceLocation> id(@Nullable JsonElement element) {
        return element instanceof JsonPrimitive raw ? Optional.ofNullable(ResourceLocation.tryParse(raw.getAsString())) : Optional.empty();
    }

    /** {@code storybound:start_dialogue} and {@code start_dialogue} alike. */
    private static String path(String type) {
        int colon = type.indexOf(':');
        return colon >= 0 ? type.substring(colon + 1) : type;
    }
}
