package dev.dzenthai.storybound.client.dev;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.client.dev.Schemas.Logic;
import dev.dzenthai.storybound.client.dev.Schemas.Node;
import dev.dzenthai.storybound.core.data.DefinitionType;
import dev.dzenthai.storybound.core.data.DefinitionTypes;
import dev.dzenthai.storybound.core.data.DialogueLayout;
import dev.dzenthai.storybound.core.logic.LogicCodecs;
import dev.dzenthai.storybound.module.dialogue.DialogueDefinition;
import dev.dzenthai.storybound.module.dialogue.NpcBinding;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The dev mode's forms are built from the JSON schemas, so the schemas must say what the game's codecs read: every
 * condition, action and trigger type and every kind of definition, filled in as a form would fill its required fields,
 * loads. Also every template and a dialogue edited on its graph. Lives in the editor's package to reach its helpers.
 */
@GameTestHolder(Storybound.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DevEditorGameTests {
    private static final ResourceLocation DIALOGUE = Storybound.id("editor_test");

    private DevEditorGameTests() {}

    @GameTest(template = "empty")
    public static void everyLogicTypeOfTheSchemaLoads(GameTestHelper helper) {
        for (Logic logic : Logic.values()) {
            Node node = Schemas.logic(logic.name);
            List<String> types = Schemas.logicTypes(logic);
            helper.assertTrue(types.size() > 10, logic + " types found in the schema: " + types);
            for (String type : types) {
                JsonObject element = sampleObject(Schemas.typed(node, type));
                element.addProperty("type", type);
                Codec<?> codec = switch (logic) {
                    case CONDITION -> LogicCodecs.CONDITION;
                    case ACTION -> LogicCodecs.ACTIONS;
                    case TRIGGER -> LogicCodecs.TRIGGER;
                };
                parse(helper, codec, element, logic.name + " " + type);
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void everyDefinitionSchemaLoads(GameTestHelper helper) {
        for (DefinitionType<?> type : DefinitionTypes.all()) {
            Node schema = Schemas.definition(type.id());
            helper.assertTrue(schema != null, "a schema for " + type.id());
            parse(helper, type.codec(), sampleObject(schema), "a new " + type.id() + " with its required fields");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void everyTemplateLoads(GameTestHelper helper) {
        NodeTemplates.Place place = new NodeTemplates.Place(new double[]{10.4, 64, -3.6}, "minecraft:overworld");
        for (NodeTemplates.Template template : NodeTemplates.Template.values()) {
            for (NodeTemplates.Created created : NodeTemplates.create(template, 7, place, (key, args) -> key)) {
                DefinitionType<?> type = DefinitionTypes.all().stream().filter(t -> t.id().equals(created.key().type())).findFirst()
                        .orElseThrow(() -> new GameTestAssertException("no definition type " + created.key().type()));
                // A node that asks its one question at once is a draft until answered (October 30, 2026); answered as
                // the editor's question does, it loads.
                NodeTemplates.Ask ask = NodeTemplates.ask(template);
                if (ask == NodeTemplates.Ask.ENTITY || ask == NodeTemplates.Ask.TRIGGER) {
                    helper.assertTrue(type.codec().parse(RegistryOps.create(JsonOps.INSTANCE, helper.getLevel().registryAccess()), created.json()).error().isPresent(),
                            template + " is a draft until its question is answered");
                    answer(created.json(), ask);
                }
                parse(helper, type.codec(), created.json(), template + " " + created.key());
            }
        }
        helper.succeed();
    }

    /** What the author answers to a new node's question: who it is, when it fires. */
    private static void answer(JsonObject json, NodeTemplates.Ask ask) {
        if (ask == NodeTemplates.Ask.ENTITY) {
            JsonObject entity = new JsonObject();
            entity.addProperty("type", "minecraft:villager");
            json.add("entity", entity);
        } else if (ask == NodeTemplates.Ask.TRIGGER) {
            JsonObject trigger = new JsonObject();
            trigger.addProperty("type", "logged_in");
            json.add("trigger", trigger);
        }
    }

    @GameTest(template = "empty")
    public static void questGiverEntriesLoad(GameTestHelper helper) {
        JsonObject binding = NodeTemplates.create(NodeTemplates.Template.NPC, 1, new NodeTemplates.Place(null, ""), (key, args) -> key).getFirst().json();
        answer(binding, NodeTemplates.Ask.ENTITY);
        ResourceLocation quest = ResourceLocation.fromNamespaceAndPath("gametest", "given");
        for (QuestGiver.Phase phase : QuestGiver.Phase.values()) {
            QuestGiver.addEntry(binding, ResourceLocation.fromNamespaceAndPath("gametest", "say_" + phase.name().toLowerCase(Locale.ROOT)), quest, phase);
        }
        parse(helper, NpcBinding.CODEC, binding, "an NPC binding with the quest giver's entries");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void editedDialoguesLoad(GameTestHelper helper) {
        JsonObject dialogue = NodeTemplates.create(NodeTemplates.Template.DIALOGUE_SCREEN, 1, new NodeTemplates.Place(null, ""), (key, args) -> key)
                .getFirst().json();
        String greet = DialogueGraph.start(dialogue);
        String choice = DialogueGraph.createNode(dialogue, DIALOGUE, "choice", null, "line", "option");
        String condition = DialogueGraph.createNode(dialogue, DIALOGUE, "condition", null, "line", "option");
        String action = DialogueGraph.createNode(dialogue, DIALOGUE, "action", null, "line", "option");
        String reply = DialogueGraph.createNode(dialogue, DIALOGUE, "line", "player", "reply", "option");
        String end = DialogueGraph.createNode(dialogue, DIALOGUE, "end", null, "line", "option");
        DialogueGraph.connect(dialogue, DIALOGUE, greet, choice, "option");
        DialogueGraph.connect(dialogue, DIALOGUE, choice, condition, "option 2");
        DialogueGraph.connect(dialogue, DIALOGUE, choice, reply, "option 3");
        DialogueGraph.connect(dialogue, DIALOGUE, condition, action, "option");
        DialogueGraph.connect(dialogue, DIALOGUE, action, end, "option");
        // The kinds the menu makes since October 29, 2026: a random answer drawn to two nodes, a jump, a call once its
        // dialogue is picked.
        String random = DialogueGraph.createNode(dialogue, DIALOGUE, "random", null, "line", "option");
        DialogueGraph.connect(dialogue, DIALOGUE, random, reply, "option");
        DialogueGraph.connect(dialogue, DIALOGUE, random, end, "option");
        DialogueGraph.connect(dialogue, DIALOGUE, random, greet, "option");
        helper.assertTrue(DialogueGraph.node(dialogue, random).getAsJsonArray("options").size() == 3, "two branches drawn, a third added");
        DialogueGraph.createNode(dialogue, DIALOGUE, "jump", null, "line", "option");
        String call = DialogueGraph.createNode(dialogue, DIALOGUE, "call", null, "line", "option");
        DialogueGraph.node(dialogue, call).addProperty("dialogue", Storybound.id("other").toString());
        StoryEdits.addQuestStart(dialogue, Storybound.id("edited_quest"));
        StoryEdits.addGiveDocument(dialogue, Storybound.id("letter"));
        DialogueGraph.sanitizeKeys(DIALOGUE, dialogue);
        parse(helper, DialogueDefinition.CODEC, dialogue, "dialogue with every node, a quest start and a document");

        JsonObject layout = new JsonObject();
        JsonObject nodes = new JsonObject();
        DialogueGraph.autoLayout(dialogue).forEach((node, pos) -> {
            JsonObject at = new JsonObject();
            at.addProperty("x", pos[0]);
            at.addProperty("y", pos[1]);
            nodes.add(node, at);
        });
        layout.add("nodes", nodes);
        parse(helper, DialogueLayout.CODEC, layout, "dialogue layout");
        helper.succeed();
    }

    // --- what a form fills in

    /** The object with its required fields (and the first of either-or requirements) filled with plausible values. */
    private static JsonObject sampleObject(Node schema) {
        JsonObject object = new JsonObject();
        Node resolved = Schemas.resolve(schema);
        Map<String, Node> properties = Schemas.properties(resolved);
        Set<String> required = new LinkedHashSet<>(Schemas.required(resolved));
        for (String alternatives : List.of("anyOf", "oneOf")) {
            JsonArray choices = resolved.json().getAsJsonArray(alternatives);
            if (choices != null && !choices.isEmpty() && choices.get(0).getAsJsonObject().has("required")) {
                choices.get(0).getAsJsonObject().getAsJsonArray("required").forEach(field -> required.add(field.getAsString()));
            }
        }
        if (required.isEmpty() && resolved.json().has("minProperties") && !properties.isEmpty()) {
            required.add(properties.keySet().iterator().next());
        }
        for (String field : required) {
            Node property = properties.get(field);
            if (property != null && field.equals("type") && "entity_selector".equals(resolved.ref())) {
                object.addProperty(field, "minecraft:villager");
            } else if (property != null) {
                // A logic element's own type is set by the caller afterwards.
                object.add(field, sample(property, field));
            }
        }
        return object;
    }

    private static JsonElement sample(Node node, String field) {
        Node resolved = Schemas.resolve(node);
        JsonObject json = resolved.json();
        return switch (Schemas.kind(node)) {
            case TEXT -> new JsonPrimitive("gametest.text");
            case ID, ID_LIST -> new JsonPrimitive(switch (field) {
                case "item" -> "minecraft:bread";
                case "entity" -> "minecraft:zombie";
                case "biome" -> "minecraft:plains";
                case "structure" -> "#minecraft:village";
                case "poi" -> "minecraft:meeting";
                case "sound" -> "minecraft:block.note_block.bell";
                case "effect" -> "minecraft:speed";
                case "stat" -> "minecraft:jump";
                case "stat_type" -> "minecraft:custom";
                case "dimension" -> "minecraft:overworld";
                default -> "gametest:sample";
            });
            case STRING -> new JsonPrimitive(switch (field) {
                case "speaker" -> "player";
                case "next", "then", "else" -> DialogueGraph.END;
                case "color" -> "#E8C66A";
                case "uuid", "entity" -> "00000000-0000-0000-0000-000000000001";
                default -> field.equals("on") ? "seen" : "sample";
            });
            case ENUM -> new JsonPrimitive(Schemas.enumValues(node).getFirst());
            case BOOLEAN -> new JsonPrimitive(true);
            case INTEGER, NUMBER -> new JsonPrimitive(json.has("minimum") ? json.get("minimum").getAsNumber() : 1);
            case LITERAL -> new JsonPrimitive(1);
            case POSITION -> {
                JsonArray pos = new JsonArray();
                pos.add(0);
                pos.add(64);
                pos.add(0);
                yield pos;
            }
            case LIST -> {
                JsonArray list = new JsonArray();
                Node items = Schemas.items(resolved);
                if (json.has("minItems") && json.get("minItems").getAsInt() > 0 && items != null) {
                    list.add(sample(items, field));
                }
                yield list;
            }
            case CONDITIONS, ACTIONS -> new JsonArray();
            case TRIGGER -> {
                JsonObject trigger = new JsonObject();
                trigger.addProperty("type", "custom");
                trigger.addProperty("id", "gametest:sample");
                yield trigger;
            }
            case CONDITION -> {
                JsonObject condition = new JsonObject();
                condition.addProperty("type", "chance");
                condition.addProperty("value", 0.5);
                yield condition;
            }
            case ACTION -> {
                JsonObject action = new JsonObject();
                action.addProperty("type", "end_dialogue");
                yield action;
            }
            case OBJECT -> sampleObject(node);
            case VARIANTS -> sampleObject(Schemas.variants(node).getFirst());
            // One thing or a list of them (an NPC binding's entity): the one thing.
            case RAW -> Schemas.variants(node).isEmpty() ? new JsonObject() : sample(Schemas.variants(node).getFirst(), field);
            case MAP -> new JsonObject();
        };
    }

    private static <T> void parse(GameTestHelper helper, Codec<T> codec, JsonElement json, String what) {
        codec.parse(RegistryOps.create(JsonOps.INSTANCE, helper.getLevel().registryAccess()), json)
                .getOrThrow(message -> new GameTestAssertException(what + " does not load: " + message + "\n" + json));
    }
}
