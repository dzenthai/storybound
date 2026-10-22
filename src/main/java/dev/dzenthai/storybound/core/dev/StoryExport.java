package dev.dzenthai.storybound.core.dev;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.dzenthai.storybound.core.CoreDefinitions;
import dev.dzenthai.storybound.core.data.DefinitionTypes;
import dev.dzenthai.storybound.core.logic.LogicOwners;
import dev.dzenthai.storybound.core.logic.LogicRegistries;
import dev.dzenthai.storybound.core.logic.References;
import dev.dzenthai.storybound.core.logic.ScriptHooks;
import dev.dzenthai.storybound.core.state.StoryValues;
import dev.dzenthai.storybound.module.codex.CodexDefinition;
import dev.dzenthai.storybound.module.codex.CodexRegistries;
import dev.dzenthai.storybound.module.dialogue.DialogueRegistries;
import dev.dzenthai.storybound.module.quest.QuestDefinition;
import dev.dzenthai.storybound.module.quest.QuestRegistries;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Comparator;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

/**
 * {@code /storybound export}: every name a story can use, as one JSON file, so references are right the first time:
 * the condition, action and trigger types (other mods' included), the handlers scripts registered, every definition id
 * of every kind with what can be named inside it (a quest's stages and objectives, a dialogue's nodes, a Codex
 * entry's sections), the declared flags and the flags used without a declaration.
 */
public final class StoryExport {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private StoryExport() {}

    public static Path file(MinecraftServer server) {
        return server.getServerDirectory().resolve("logs").resolve("storybound-export.json");
    }

    public static Path write(MinecraftServer server) throws IOException {
        Path file = file(server);
        Files.createDirectories(file.getParent());
        Files.writeString(file, GSON.toJson(export(server)) + "\n", StandardCharsets.UTF_8);
        return file;
    }

    public static JsonObject export(MinecraftServer server) {
        JsonObject root = new JsonObject();
        root.addProperty("written", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        root.add("conditions", ids(LogicRegistries.CONDITION_TYPES));
        root.add("actions", ids(LogicRegistries.ACTION_TYPES));
        root.add("triggers", ids(LogicRegistries.TRIGGER_TYPES));
        JsonObject scripts = new JsonObject();
        scripts.add("actions", sorted(ScriptHooks.actions()));
        scripts.add("conditions", sorted(ScriptHooks.conditions()));
        root.add("kubejs", scripts);

        JsonObject definitions = new JsonObject();
        DefinitionTypes.all().stream().sorted(Comparator.comparing(type -> type.id().toString()))
                .forEach(type -> definitions.add(type.id().toString(), sorted(type.serverStore().all().keySet())));
        root.add("definitions", definitions);

        JsonObject quests = new JsonObject();
        QuestRegistries.QUESTS.serverStore().all().forEach((id, quest) -> quests.add(id.toString(), quest(quest)));
        root.add("quests", quests);
        JsonObject dialogues = new JsonObject();
        DialogueRegistries.DIALOGUES.serverStore().all().forEach((id, dialogue) -> {
            JsonObject json = new JsonObject();
            json.addProperty("start", dialogue.start());
            json.add("nodes", sorted(dialogue.nodes().keySet()));
            json.add("participants", sorted(dialogue.participants().keySet()));
            dialogues.add(id.toString(), json);
        });
        root.add("dialogues", dialogues);
        JsonObject codex = new JsonObject();
        CodexRegistries.CODEX.serverStore().all().forEach((id, entry) ->
                codex.add(id.toString(), sorted(entry.sections().stream().map(CodexDefinition.Section::id).toList())));
        root.add("codex", codex);

        JsonObject flags = new JsonObject();
        CoreDefinitions.FLAGS.serverStore().all().forEach((id, flag) -> {
            JsonObject json = new JsonObject();
            json.addProperty("type", flag.type().getSerializedName());
            json.addProperty("scope", flag.scope().getSerializedName());
            json.addProperty("default", StoryValues.display(flag.defaultValue()));
            json.addProperty("sync", flag.sync());
            flag.description().ifPresent(description -> json.addProperty("description", description));
            flags.add(id.toString(), json);
        });
        root.add("flags", flags);
        root.add("flags_undeclared", sorted(undeclaredFlags()));
        return root;
    }

    private static JsonObject quest(QuestDefinition quest) {
        JsonObject json = new JsonObject();
        json.addProperty("scope", quest.scope().getSerializedName());
        JsonObject stages = new JsonObject();
        for (QuestDefinition.Stage stage : quest.stages()) {
            JsonArray objectives = new JsonArray();
            for (int i = 0; i < stage.objectives().size(); i++) {
                objectives.add(stage.objectiveId(i));
            }
            stages.add(stage.id(), objectives);
        }
        json.add("stages", stages);
        return json;
    }

    /** Flags the logic uses that no flag file declares (the validator warns about them too). */
    private static Set<ResourceLocation> undeclaredFlags() {
        Set<ResourceLocation> used = new TreeSet<>();
        LogicOwners.walkAll((type, id) -> new References() {
            @Override
            public void flag(ResourceLocation key) {
                used.add(key);
            }
        });
        used.removeIf(CoreDefinitions.FLAGS.serverStore()::contains);
        return used;
    }

    private static JsonArray ids(Registry<?> registry) {
        return sorted(registry.keySet());
    }

    private static JsonArray sorted(Collection<?> values) {
        JsonArray array = new JsonArray();
        values.stream().map(String::valueOf).sorted().forEach(array::add);
        return array;
    }
}
