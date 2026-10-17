package dev.dzenthai.storybound.gametest;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.CoreDefinitions;
import dev.dzenthai.storybound.core.StoryException;
import dev.dzenthai.storybound.core.data.DefinitionStore;
import dev.dzenthai.storybound.core.data.StoryFolder;
import dev.dzenthai.storybound.core.logic.Actions;
import dev.dzenthai.storybound.core.logic.Condition;
import dev.dzenthai.storybound.core.logic.LogicCodecs;
import dev.dzenthai.storybound.core.logic.OnFail;
import dev.dzenthai.storybound.core.logic.StoryContext;
import dev.dzenthai.storybound.core.logic.StoryEvent;
import dev.dzenthai.storybound.core.logic.StoryEvents;
import dev.dzenthai.storybound.core.state.StoryScope;
import dev.dzenthai.storybound.core.state.StoryState;
import dev.dzenthai.storybound.core.state.StoryValue;
import dev.dzenthai.storybound.core.text.ConfigTexts;
import dev.dzenthai.storybound.module.document.DocumentDefinition;
import dev.dzenthai.storybound.module.document.DocumentRef;
import dev.dzenthai.storybound.module.document.DocumentRegistries;
import dev.dzenthai.storybound.module.document.Documents;
import dev.dzenthai.storybound.module.journal.Journal;
import dev.dzenthai.storybound.module.journal.JournalNote;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Reading, archiving and issuing documents, and Journal notes, on a real server. */
@SuppressWarnings("removal")
@GameTestHolder(Storybound.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DocumentGameTests {
    private static final ResourceLocation LETTER = key("letter");

    private DocumentGameTests() {}

    /** The story folder is created when the mod starts, so its blank documents are there with no datapack at all. */
    @GameTest(template = "empty")
    public static void storyFolderBlankDocumentsLoadWithTheirTexts(GameTestHelper helper) {
        for (String kind : new String[] {"book", "scroll", "note", "letter"}) {
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath("storybound", kind);
            DocumentDefinition definition = DocumentRegistries.DOCUMENTS.serverStore().getOrNull(id);
            helper.assertTrue(definition != null, "the blank " + kind + " is loaded from config/storybound/documents");
            expect(helper, definition.kind().getSerializedName(), kind, "of its kind");
            ConfigTexts.Texts texts = ConfigTexts.current();
            helper.assertTrue(texts.documents().getOrDefault("en_us", Map.of()).containsKey(id) && texts.documents().getOrDefault("ru_ru", Map.of()).containsKey(id),
                    "its text in English and Russian comes from config/storybound/texts");
            helper.assertTrue(texts.translations().getOrDefault("en_us", Map.of()).containsKey("starter.document." + kind + ".title"),
                    "its title from config/storybound/lang");
        }
        helper.succeed();
    }

    /** Into a folder of its own: the installed example must not reach the data the other tests run with. */
    @GameTest(template = "empty")
    public static void exampleInstallsInTheStoryFolderLayout(GameTestHelper helper) {
        try {
            Path root = Files.createTempDirectory("storybound-example");
            int written = StoryFolder.installExample(root);
            helper.assertTrue(written > 20, "the example's files are written, got " + written);
            helper.assertTrue(Files.isRegularFile(root.resolve("quests/example/wolf_hunt.json")), "a quest under quests/<namespace>/");
            helper.assertTrue(Files.isRegularFile(root.resolve("texts/en_us/example/elder_letter.sbt")), "texts under texts/<language>/<namespace>/");
            helper.assertTrue(Files.readString(root.resolve("lang/ru_ru.json")).contains("example.quest.wolf_hunt.title"), "translations merged into lang/");
            helper.assertFalse(Files.exists(root.resolve("pack.mcmeta")), "no pack files");
            StoryFolder.installExample(root);
            helper.assertTrue(Files.readString(root.resolve("lang/en_us.json")).contains("example.quest.wolf_hunt.title"), "installing again keeps a valid lang file");
        } catch (IOException e) {
            throw new GameTestAssertException("Install failed: " + e);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void readingRecordsArchivesAndRunsOnReadOnce(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        with(helper, DocumentRegistries.DOCUMENTS.serverStore(), DocumentDefinition.CODEC, Map.of(LETTER, """
                { "kind": "letter", "title": "gametest.letter.title",
                  "on_read": { "type": "add_var", "key": "gametest:on_read_runs", "amount": 1 } }
                """), () -> with(helper, CoreDefinitions.EVENTS.serverStore(), StoryEvent.CODEC, Map.of(
                key("first_read"), "{ \"trigger\": { \"type\": \"document_read\", \"document\": \"gametest:letter\", \"first_time\": true },"
                        + " \"actions\": { \"type\": \"add_var\", \"key\": \"gametest:first_reads\", \"amount\": 1 } }",
                key("any_read"), "{ \"trigger\": { \"type\": \"document_read\" },"
                        + " \"actions\": { \"type\": \"add_var\", \"key\": \"gametest:reads\", \"amount\": 1 } }"
        ), () -> {
            StoryEvents.rebuild();
            helper.assertTrue(Documents.read(player, LETTER, false), "known document");
            helper.assertTrue(Documents.read(player, LETTER, false), "read again");

            helper.assertTrue(Journal.of(player).hasRead(LETTER), "marked as read");
            helper.assertTrue(Journal.of(player).documents().contains(LETTER), "archived in the Journal");
            expect(helper, stored(player, "on_read_runs"), Optional.of(new StoryValue.Int(1)), "on_read runs on the first read only");
            expect(helper, stored(player, "first_reads"), Optional.of(new StoryValue.Int(1)), "first_time trigger");
            expect(helper, stored(player, "reads"), Optional.of(new StoryValue.Int(2)), "document_read fires on every read");
            helper.assertTrue(condition(helper, "{ \"type\": \"document_read\", \"document\": \"gametest:letter\" }").test(StoryContext.of(player)),
                    "document_read condition");
        }));
        StoryEvents.rebuild();
        remove(helper, player);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void documentsCanStayOutOfTheJournal(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        with(helper, DocumentRegistries.DOCUMENTS.serverStore(), DocumentDefinition.CODEC, Map.of(LETTER, """
                { "kind": "note", "title": "gametest.note.title", "journal": { "add_on_read": false } }
                """), () -> {
            Documents.read(player, LETTER, false);
            helper.assertTrue(Journal.of(player).hasRead(LETTER), "still marked as read");
            helper.assertTrue(!Journal.of(player).documents().contains(LETTER), "not archived");
            helper.assertTrue(!Documents.read(player, key("missing"), false), "unknown document is refused");
        });
        remove(helper, player);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void giveDocumentSetsComponentModelAndName(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        with(helper, DocumentRegistries.DOCUMENTS.serverStore(), DocumentDefinition.CODEC, Map.of(LETTER, """
                { "kind": "book", "title": "gametest.book.title", "custom_model_data": 7 }
                """), () -> {
            run(helper, player, "{ \"type\": \"give_document\", \"document\": \"gametest:letter\", \"count\": 2 }");
            ItemStack stack = player.getInventory().items.stream()
                    .filter(item -> item.is(DocumentRegistries.DOCUMENT_ITEM.get())).findFirst().orElse(ItemStack.EMPTY);

            expect(helper, stack.getCount(), 2, "count");
            expect(helper, stack.get(DocumentRegistries.DOCUMENT_COMPONENT.get()), new DocumentRef(LETTER), "document component");
            expect(helper, Optional.ofNullable(stack.get(DataComponents.CUSTOM_MODEL_DATA)).map(data -> data.value()), Optional.of(7), "custom model data");
            expect(helper, stack.getHoverName(), Component.translatable("gametest.book.title"), "item name is the document title");
        });
        remove(helper, player);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void journalEntryResolvesArgumentsWhenWritten(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        try {
            StoryState.set(player, StoryScope.PLAYER, key("gold"), new StoryValue.Int(12));
            StoryState.set(player, StoryScope.PLAYER, key("gender"), new StoryValue.Str("f"));
        } catch (StoryException e) {
            throw new GameTestAssertException(e.getMessage());
        }
        run(helper, player, """
                { "type": "journal_entry", "id": "gametest:wolves", "title": "gametest.note.title",
                  "text": { "key": "gametest.note.text", "args": ["{player}", "{var:gametest:gold}"], "variant_by": "gametest:gender" } }
                """);

        JournalNote note = Journal.of(player).notes().stream().findFirst().orElseThrow(() -> new GameTestAssertException("note written"));
        expect(helper, note.category(), Storybound.id("notes"), "default category");
        expect(helper, note.text().args().get(1), Component.literal("12"), "variable resolved at write time");
        expect(helper, note.text().variant(), Optional.of("f"), "variant value");
        remove(helper, player);
        helper.succeed();
    }

    private static ResourceLocation key(String path) {
        return ResourceLocation.fromNamespaceAndPath("gametest", path);
    }

    private static Optional<StoryValue> stored(ServerPlayer player, String path) {
        return StoryState.getStored(player, StoryScope.PLAYER, key(path));
    }

    private static RegistryOps<JsonElement> ops(GameTestHelper helper) {
        return RegistryOps.create(JsonOps.INSTANCE, helper.getLevel().registryAccess());
    }

    private static Condition condition(GameTestHelper helper, String json) {
        return LogicCodecs.CONDITION.parse(ops(helper), JsonParser.parseString(json)).getOrThrow(GameTestAssertException::new);
    }

    private static void run(GameTestHelper helper, ServerPlayer player, String json) {
        Actions.runAll(LogicCodecs.ACTIONS.parse(ops(helper), JsonParser.parseString(json)).getOrThrow(GameTestAssertException::new),
                StoryContext.of(player), "gametest", OnFail.ABORT);
    }

    /** Adds definitions to a store for the duration of {@code body}, then restores what was loaded. */
    private static <T> void with(GameTestHelper helper, DefinitionStore<T> store, Codec<T> codec, Map<ResourceLocation, String> definitions, Runnable body) {
        Map<ResourceLocation, T> previous = store.all();
        Map<ResourceLocation, T> installed = new LinkedHashMap<>(previous);
        definitions.forEach((id, json) -> installed.put(id, codec.parse(ops(helper), JsonParser.parseString(json)).getOrThrow(GameTestAssertException::new)));
        store.replace(installed);
        try {
            body.run();
        } finally {
            store.replace(previous);
        }
    }

    private static void expect(GameTestHelper helper, Object actual, Object expected, String what) {
        helper.assertTrue(Objects.equals(actual, expected), what + ": expected " + expected + ", got " + actual);
    }

    private static void remove(GameTestHelper helper, ServerPlayer player) {
        helper.getLevel().getServer().getPlayerList().remove(player);
    }
}
