package dev.dzenthai.storybound.client.dev;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.story.StoryLinks.NodeKey;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/** Starting points for new nodes on the story canvas: a definition with sensible defaults and texts to rewrite. */
final class NodeTemplates {
    /** Default texts of new definitions, in the author's language; the game passes {@link DevUi#str}. */
    @FunctionalInterface
    interface Texts {
        String get(String key, Object... args);
    }

    enum Template {
        QUEST_MAIN, QUEST_SIDE, CONTRACT, DIALOGUE_SCREEN, DIALOGUE_BUBBLE, NPC, DOCUMENT, WAYPOINT, BOSS, SCRIPT
    }

    /**
     * A new definition.
     *
     * @param offsetX where it goes relative to the place the author picked
     * @param text    the document's text, for documents
     */
    record Created(NodeKey key, JsonObject json, Optional<String> text, int offsetX) {}

    /**
     * Where the author stands, for waypoints.
     *
     * @param pos null outside a world
     */
    record Place(double @Nullable [] pos, String dimension) {}

    private NodeTemplates() {}

    /**
     * The definitions a template makes; a contract makes two, the notice and its quest (the notice comes last: it is
     * the one the inspector shows).
     *
     * @param number makes the ids and default titles unique
     */
    static List<Created> create(Template template, int number, Place place, Texts texts) {
        return switch (template) {
            case QUEST_MAIN, QUEST_SIDE -> {
                boolean main = template == Template.QUEST_MAIN;
                ResourceLocation id = Storybound.id("quest_" + number);
                yield List.of(new Created(new NodeKey(ClientDevStore.TYPE_QUEST, id),
                        quest(id, main ? "main" : "side", texts.get(main ? "template.quest_main" : "template.quest_side", number),
                                texts.get("template.quest_description"), "start", texts.get("template.quest_log")),
                        Optional.empty(), 0));
            }
            case CONTRACT -> {
                ResourceLocation questId = Storybound.id("contract_quest_" + number);
                JsonObject quest = quest(questId, "contract", texts.get("template.contract_quest", number),
                        texts.get("template.contract_description"), "hunt", texts.get("template.contract_log"));
                ResourceLocation noticeId = Storybound.id("contract_note_" + number);
                JsonObject notice = new JsonObject();
                notice.addProperty("kind", "contract");
                ClientDevStore.writeStoryText(notice, "title", texts.get("template.contract_notice", number), key(ClientDevStore.TYPE_DOCUMENT, noticeId, "title"));
                JsonObject contract = new JsonObject();
                contract.addProperty("quest", questId.toString());
                ClientDevStore.writeStoryText(contract, "conditions", texts.get("template.contract_conditions"), key(ClientDevStore.TYPE_DOCUMENT, noticeId, "contract.conditions"));
                ClientDevStore.writeStoryText(contract, "reward", texts.get("template.contract_reward"), key(ClientDevStore.TYPE_DOCUMENT, noticeId, "contract.reward"));
                ClientDevStore.writeStoryText(contract, "hint", texts.get("template.contract_hint"), key(ClientDevStore.TYPE_DOCUMENT, noticeId, "contract.hint"));
                notice.add("contract", contract);
                yield List.of(
                        new Created(new NodeKey(ClientDevStore.TYPE_QUEST, questId), quest, Optional.empty(), 190),
                        new Created(new NodeKey(ClientDevStore.TYPE_DOCUMENT, noticeId), notice, Optional.of(texts.get("template.contract_text")), 0));
            }
            case DIALOGUE_SCREEN, DIALOGUE_BUBBLE -> {
                boolean bubble = template == Template.DIALOGUE_BUBBLE;
                ResourceLocation id = Storybound.id("dialogue_" + number);
                JsonObject dialogue = new JsonObject();
                dialogue.addProperty("presentation", bubble ? "bubble" : "screen");
                dialogue.addProperty("camera", bubble ? "off" : "auto");
                dialogue.addProperty("start", "greet");
                JsonObject npc = new JsonObject();
                npc.addProperty("type", "bound");
                ClientDevStore.writeStoryText(npc, "name", texts.get("template.villager"), key(ClientDevStore.TYPE_DIALOGUE, id, "participant.npc.name"));
                JsonObject participants = new JsonObject();
                participants.add("npc", npc);
                dialogue.add("participants", participants);
                JsonObject greet = new JsonObject();
                greet.addProperty("type", "line");
                greet.addProperty("speaker", "npc");
                ClientDevStore.writeStoryText(greet, "text", texts.get(bubble ? "template.bubble_line" : "template.greeting"), DialogueGraph.lineKey(id, "greet"));
                greet.addProperty("next", DialogueGraph.END);
                DialogueGraph.nodes(dialogue).add("greet", greet);
                yield List.of(new Created(new NodeKey(ClientDevStore.TYPE_DIALOGUE, id), dialogue, Optional.empty(), 0));
            }
            case NPC -> {
                ResourceLocation id = Storybound.id("npc_" + number);
                JsonObject npc = new JsonObject();
                StoryEdits.setEntityType(npc, "minecraft:villager", "npc_" + number);
                npc.addProperty("interact", true);
                npc.addProperty("override_interact", true);
                npc.add("dialogues", new JsonArray());
                yield List.of(new Created(new NodeKey(ClientDevStore.TYPE_NPC, id), npc, Optional.empty(), 0));
            }
            case DOCUMENT -> {
                ResourceLocation id = Storybound.id("document_" + number);
                JsonObject document = new JsonObject();
                document.addProperty("kind", "scroll");
                ClientDevStore.writeStoryText(document, "title", texts.get("template.scroll", number), key(ClientDevStore.TYPE_DOCUMENT, id, "title"));
                yield List.of(new Created(new NodeKey(ClientDevStore.TYPE_DOCUMENT, id), document, Optional.of(texts.get("template.scroll_text")), 0));
            }
            case WAYPOINT -> {
                ResourceLocation id = Storybound.id("waypoint_" + number);
                JsonObject waypoint = new JsonObject();
                ClientDevStore.writeStoryText(waypoint, "name", texts.get("template.search_area", number), key(ClientDevStore.TYPE_WAYPOINT, id, "name"));
                JsonObject target = new JsonObject();
                target.add("pos", StoryEdits.position(place.pos()));
                if (place.pos() != null) {
                    target.addProperty("dimension", place.dimension());
                }
                waypoint.add("target", target);
                waypoint.addProperty("radius", 30.0);
                yield List.of(new Created(new NodeKey(ClientDevStore.TYPE_WAYPOINT, id), waypoint, Optional.empty(), 0));
            }
            case BOSS -> {
                ResourceLocation id = Storybound.id("boss_" + number);
                JsonObject boss = new JsonObject();
                ClientDevStore.writeStoryText(boss, "title", texts.get("template.boss", number), key(ClientDevStore.TYPE_BOSS, id, "title"));
                StoryEdits.setEntityType(boss, "minecraft:wolf", "boss_" + number);
                // Sections with their default long texts (texts/<lang>/<ns>/bosses/<path>/lore.sbt...) and unlock rules.
                boss.add("lore", new JsonObject());
                boss.add("tactics", new JsonObject());
                yield List.of(new Created(new NodeKey(ClientDevStore.TYPE_BOSS, id), boss, Optional.empty(), 0));
            }
            case SCRIPT -> {
                // Its handler is its own id until a file is picked; "create the file" writes one registering it.
                ResourceLocation id = Storybound.id("script_" + number);
                JsonObject script = new JsonObject();
                ClientDevStore.writeStoryText(script, "title", texts.get("template.script", number), key(ClientDevStore.TYPE_SCRIPT, id, "title"));
                yield List.of(new Created(new NodeKey(ClientDevStore.TYPE_SCRIPT, id), script, Optional.empty(), 0));
            }
        };
    }

    private static JsonObject quest(ResourceLocation id, String category, String title, String description, String stageId, String log) {
        JsonObject quest = new JsonObject();
        quest.addProperty("category", "storybound:" + category);
        ClientDevStore.writeStoryText(quest, "title", title, key(ClientDevStore.TYPE_QUEST, id, "title"));
        ClientDevStore.writeStoryText(quest, "description", description, key(ClientDevStore.TYPE_QUEST, id, "description"));
        JsonObject stage = new JsonObject();
        stage.addProperty("id", stageId);
        ClientDevStore.writeStoryText(stage, "log", log, key(ClientDevStore.TYPE_QUEST, id, "stage." + stageId + ".log"));
        JsonArray stages = new JsonArray();
        stages.add(stage);
        quest.add("stages", stages);
        return quest;
    }

    private static String key(ResourceLocation type, ResourceLocation id, String field) {
        return ClientDevStore.canonicalKey(type, id, field);
    }
}
