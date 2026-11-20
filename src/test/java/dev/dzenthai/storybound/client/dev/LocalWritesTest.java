package dev.dzenthai.storybound.client.dev;

import static org.junit.jupiter.api.Assertions.*;

import dev.dzenthai.storybound.core.text.ConfigTexts;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A write made here survives the snapshots that answer earlier requests, by the count of requests the server had (nodes
 * deleted quickly lined up before vanishing; an undone or redone delete blinked).
 */
class LocalWritesTest {
    private static final ResourceLocation CHAPTER = ResourceLocation.parse("example:one");
    private static final ResourceLocation QUEST = ResourceLocation.parse("example:wolf");
    private static final String OLD = "{\"nodes\":[]}";
    private static final String NEW = "{\"nodes\":[{\"quest\":\"example:wolf\",\"x\":48,\"y\":24}]}";
    private static final String WOLF = "{\"title\":\"Wolf hunt\"}";

    @BeforeEach
    @AfterEach
    void reset() {
        ClientDevStore.clearProjects();
        ClientDevStore.setProject("story", "[]", ConfigTexts.Texts.EMPTY);
    }

    private static void snapshot(int received, Map<ResourceLocation, Map<ResourceLocation, String>> definitions) {
        ClientDevStore.setSnapshot(definitions, List.of(), "en_us", List.of("en_us"), received);
    }

    /** A write, then the request carrying it, as the editor does. */
    private static void write(ResourceLocation type, ResourceLocation id, String json) {
        ClientDevStore.putLocal(type, id, json);
        ClientDevStore.sending();
    }

    private static void delete(ResourceLocation type, ResourceLocation id) {
        ClientDevStore.removeLocal(type, id);
        ClientDevStore.sending();
    }

    private static boolean wolfShown() {
        return ClientDevStore.getJson(ClientDevStore.TYPE_QUEST, QUEST) != null;
    }

    @Test
    void aWriteStaysUntilTheServerHasHadItsRequest() {
        snapshot(0, Map.of(ClientDevStore.TYPE_CHAPTER, Map.of(CHAPTER, OLD)));
        write(ClientDevStore.TYPE_QUEST, QUEST, WOLF);          // request 1
        write(ClientDevStore.TYPE_CHAPTER, CHAPTER, NEW);       // request 2
        // The answer to the node: the server has it, not yet the chapter.
        snapshot(1, Map.of(ClientDevStore.TYPE_QUEST, Map.of(QUEST, WOLF), ClientDevStore.TYPE_CHAPTER, Map.of(CHAPTER, OLD)));
        assertEquals(NEW, ClientDevStore.getJson(ClientDevStore.TYPE_CHAPTER, CHAPTER), "the node keeps its place");
        snapshot(2, Map.of(ClientDevStore.TYPE_QUEST, Map.of(QUEST, WOLF), ClientDevStore.TYPE_CHAPTER, Map.of(CHAPTER, NEW)));
        // Answered: the server's copy decides from now on, whatever it is (here another operator moved it).
        snapshot(2, Map.of(ClientDevStore.TYPE_QUEST, Map.of(QUEST, WOLF), ClientDevStore.TYPE_CHAPTER, Map.of(CHAPTER, OLD)));
        assertEquals(OLD, ClientDevStore.getJson(ClientDevStore.TYPE_CHAPTER, CHAPTER));
    }

    @Test
    void deletingUndoingAndRedoingNeverBlinks() {
        snapshot(0, Map.of(ClientDevStore.TYPE_QUEST, Map.of(QUEST, WOLF)));
        delete(ClientDevStore.TYPE_QUEST, QUEST);                // request 1
        assertFalse(wolfShown());
        snapshot(0, Map.of(ClientDevStore.TYPE_QUEST, Map.of(QUEST, WOLF)));
        assertFalse(wolfShown(), "a snapshot from before the delete does not bring it back");
        write(ClientDevStore.TYPE_QUEST, QUEST, WOLF);          // undo: request 2
        snapshot(1, Map.of());
        assertTrue(wolfShown(), "the snapshot answering the delete does not take the undone node away");
        delete(ClientDevStore.TYPE_QUEST, QUEST);                // redo: request 3
        // The case content cannot tell: an older snapshot without the node, then the one answering the undo with it.
        snapshot(1, Map.of());
        assertFalse(wolfShown());
        snapshot(2, Map.of(ClientDevStore.TYPE_QUEST, Map.of(QUEST, WOLF)));
        assertFalse(wolfShown(), "the snapshot answering the undo does not bring the redone delete back");
        snapshot(3, Map.of());
        assertFalse(wolfShown());
        snapshot(3, Map.of(ClientDevStore.TYPE_QUEST, Map.of(QUEST, WOLF)));
        assertTrue(wolfShown(), "once answered, the server decides again");
    }

    @Test
    void anotherStoryStartsWithNothingOnItsWay() {
        write(ClientDevStore.TYPE_CHAPTER, CHAPTER, NEW);
        ClientDevStore.setProject("other", "[]", ConfigTexts.Texts.EMPTY);
        snapshot(0, Map.of(ClientDevStore.TYPE_CHAPTER, Map.of(CHAPTER, OLD)));
        assertEquals(OLD, ClientDevStore.getJson(ClientDevStore.TYPE_CHAPTER, CHAPTER));
    }
}
