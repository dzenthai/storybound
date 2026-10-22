package dev.dzenthai.storybound.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.StoryException;
import dev.dzenthai.storybound.core.data.ConfigStorySource;
import dev.dzenthai.storybound.core.data.ValidationIssue;
import dev.dzenthai.storybound.core.data.ValidationReport;
import dev.dzenthai.storybound.core.data.ValidationService;
import dev.dzenthai.storybound.core.dev.LangExtract;
import dev.dzenthai.storybound.core.dev.StoryCaptures;
import dev.dzenthai.storybound.core.dev.StoryDump;
import dev.dzenthai.storybound.core.dev.StoryExport;
import dev.dzenthai.storybound.core.state.StoryScope;
import dev.dzenthai.storybound.core.state.StoryState;
import dev.dzenthai.storybound.core.state.StoryValue;
import dev.dzenthai.storybound.module.quest.QuestDefinition;
import dev.dzenthai.storybound.module.quest.QuestRegistries;
import dev.dzenthai.storybound.module.quest.Quests;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** The files written for authors who read instead of watching: the player dump and the export of every name. */
@SuppressWarnings("removal")
@GameTestHolder(Storybound.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AuthorToolsGameTests {
    private static final ResourceLocation QUEST = ResourceLocation.fromNamespaceAndPath("gametest", "tools_quest");
    private static final String QUEST_JSON = """
            { "title": { "literal": "Tools" },
              "stages": [
                { "id": "gather", "objectives": [ { "id": "wood", "trigger": { "type": "custom", "id": "gametest:wood" }, "count": 3 } ] },
                { "id": "return", "objectives": [ { "trigger": { "type": "custom", "id": "gametest:return" } } ] } ] }
            """;

    private AuthorToolsGameTests() {}

    @GameTest(template = "empty")
    public static void dumpHasTheValuesAndQuestsOfThePlayer(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        withQuest(helper, () -> {
            try {
                StoryState.set(player, StoryScope.PLAYER, ResourceLocation.fromNamespaceAndPath("gametest", "tools_gold"), new StoryValue.Int(7));
            } catch (StoryException e) {
                throw new GameTestAssertException(e.getMessage());
            }
            Quests.start(player, QUEST);
            Path file = io(() -> StoryDump.write(player));
            JsonObject dump = JsonParser.parseString(io(() -> Files.readString(file, StandardCharsets.UTF_8))).getAsJsonObject();
            helper.assertTrue(dump.get("player").getAsString().equals(player.getGameProfile().getName()), "the player");
            helper.assertTrue(dump.getAsJsonObject("values").getAsJsonObject("player").get("gametest:tools_gold").getAsInt() == 7,
                    "a variable as written in JSON: " + dump.get("values"));
            JsonObject quest = dump.getAsJsonObject("quests").getAsJsonObject(QUEST.toString());
            helper.assertTrue(quest != null && quest.get("status").getAsString().equals("active") && quest.get("stage").getAsString().equals("gather")
                    && quest.get("scope").getAsString().equals("player") && quest.get("defined").getAsBoolean(), "the quest: " + quest);
            helper.assertTrue(dump.has("codex") && dump.has("bosses") && dump.has("journal"), "the modules' data");
            io(() -> {
                Files.deleteIfExists(file);
                return null;
            });
        });
        helper.getLevel().getServer().getPlayerList().remove(player);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void exportNamesTypesDefinitionsAndStages(GameTestHelper helper) {
        withQuest(helper, () -> {
            JsonObject export = StoryExport.export(helper.getLevel().getServer());
            helper.assertTrue(export.getAsJsonArray("conditions").toString().contains("\"storybound:flag\""), "condition types");
            helper.assertTrue(export.getAsJsonArray("actions").toString().contains("\"storybound:set_quest_state\""), "action types");
            helper.assertTrue(export.getAsJsonArray("triggers").toString().contains("\"storybound:custom\""), "trigger types");
            helper.assertTrue(export.getAsJsonObject("definitions").getAsJsonArray("storybound:quest").toString().contains(QUEST.toString()),
                    "definition ids by kind: " + export.get("definitions"));
            JsonObject stages = export.getAsJsonObject("quests").getAsJsonObject(QUEST.toString()).getAsJsonObject("stages");
            helper.assertTrue(stages.getAsJsonArray("gather").get(0).getAsString().equals("wood") && stages.has("return"),
                    "stages with their objectives: " + stages);
            helper.assertTrue(export.has("kubejs") && export.has("flags") && export.has("flags_undeclared"), "scripts and flags");
        });
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void validationReportAsJsonNamesFileAndField(GameTestHelper helper) {
        ValidationReport report = new ValidationReport(1, List.of(
                ValidationIssue.error(QuestRegistries.QUESTS.id(), ResourceLocation.fromNamespaceAndPath("gametest", "report/broken"), "No key title in MapLike[{}]"),
                ValidationIssue.warning(Storybound.id("validator"), null, "something general")));
        Path file = io(() -> Files.createTempFile("storybound-validation", ".json"));
        try {
            ValidationService.writeJson(report, file);
            JsonObject json = JsonParser.parseString(io(() -> Files.readString(file, StandardCharsets.UTF_8))).getAsJsonObject();
            helper.assertTrue(json.get("errors").getAsInt() == 1 && json.get("warnings").getAsInt() == 1, "counts: " + json);
            JsonObject broken = json.getAsJsonArray("issues").get(0).getAsJsonObject();
            helper.assertTrue(broken.get("severity").getAsString().equals("error") && broken.get("id").getAsString().equals("gametest:report/broken")
                    && broken.get("file").getAsString().equals("data/gametest/storybound/quests/report/broken.json")
                    && broken.get("field").getAsString().equals("title"), "an issue with its file and the missing field: " + broken);
            JsonObject general = json.getAsJsonArray("issues").get(1).getAsJsonObject();
            helper.assertTrue(!general.has("file") && !general.has("id"), "an issue of no definition has no file: " + general);
        } finally {
            io(() -> Files.deleteIfExists(file));
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void langPreviewFindsTextsWrittenInTheJson(GameTestHelper helper) {
        Path file = ConfigStorySource.root().resolve("quests").resolve("gametest").resolve("lang_preview.json");
        String json = "{ \"title\": { \"literal\": \"Written in the JSON\" }, \"stages\": [ { \"id\": \"a\" } ] }\n";
        try {
            io(() -> Files.createDirectories(file.getParent()));
            io(() -> Files.writeString(file, json, StandardCharsets.UTF_8));
            LangExtract.Result result = io(() -> LangExtract.run("zz_zz", false));
            helper.assertTrue(result.moved() >= 1 && result.files().contains("quests/gametest/lang_preview.json"), "the file with a literal: " + result);
            helper.assertTrue(io(() -> Files.readString(file, StandardCharsets.UTF_8)).equals(json), "a preview writes nothing");
            helper.assertFalse(Files.exists(ConfigStorySource.root().resolve("lang").resolve("zz_zz.json")), "nor a lang file");
        } finally {
            io(() -> Files.deleteIfExists(file));
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void capturesKeepEveryNameAndReplaceTheSameOne(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        Pig pig = helper.spawn(EntityType.PIG, new BlockPos(1, 2, 1));
        pig.addTag("gametest_capture");
        Path file = StoryCaptures.file();
        String before = io(() -> Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : null);
        try {
            io(() -> StoryCaptures.save("gametest_spot", StoryCaptures.position(player)));
            io(() -> StoryCaptures.save("gametest_pig", StoryCaptures.entity(player, pig)));
            io(() -> StoryCaptures.save("gametest_spot", StoryCaptures.position(player)));
            JsonObject all = JsonParser.parseString(io(() -> Files.readString(file, StandardCharsets.UTF_8))).getAsJsonObject();
            JsonObject spot = all.getAsJsonObject("gametest_spot");
            JsonObject captured = all.getAsJsonObject("gametest_pig");
            helper.assertTrue(spot.get("kind").getAsString().equals("pos") && spot.getAsJsonArray("pos").size() == 3, "a place: " + spot);
            helper.assertTrue(captured.get("uuid").getAsString().equals(pig.getUUID().toString())
                    && captured.get("type").getAsString().equals("minecraft:pig")
                    && captured.getAsJsonArray("tags").toString().contains("gametest_capture"), "an entity: " + captured);
            helper.assertTrue(all.keySet().stream().filter(name -> name.startsWith("gametest_")).count() == 2, "one entry per name: " + all.keySet());
        } finally {
            io(() -> {
                if (before != null) {
                    Files.writeString(file, before, StandardCharsets.UTF_8);
                } else {
                    Files.deleteIfExists(file);
                }
                return null;
            });
        }
        helper.getLevel().getServer().getPlayerList().remove(player);
        helper.succeed();
    }

    private static void withQuest(GameTestHelper helper, Runnable body) {
        Map<ResourceLocation, QuestDefinition> previous = QuestRegistries.QUESTS.serverStore().all();
        Map<ResourceLocation, QuestDefinition> installed = new LinkedHashMap<>(previous);
        installed.put(QUEST, QuestDefinition.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, helper.getLevel().registryAccess()),
                JsonParser.parseString(QUEST_JSON)).getOrThrow(GameTestAssertException::new));
        QuestRegistries.QUESTS.serverStore().replace(installed);
        try {
            body.run();
        } finally {
            QuestRegistries.QUESTS.serverStore().replace(previous);
        }
    }

    @FunctionalInterface
    private interface IoSupplier<T> {
        T get() throws IOException;
    }

    private static <T> T io(IoSupplier<T> supplier) {
        try {
            return supplier.get();
        } catch (IOException e) {
            throw new GameTestAssertException("Unexpected I/O failure: " + e.getMessage());
        }
    }
}
