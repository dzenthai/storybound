package dev.dzenthai.storybound.core.dev;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/** Reading a KubeJS script for its handlers, the story events it listens to and the ids it mentions. */
class ScriptScannerTest {
    private static final String SCRIPT = """
            // A comment mentioning 'mypack:commented_out' does not count.
            StoryboundEvents.registerActions(e => {
              e.add('mypack:burn_village', ctx => {
                Storybound.quests(ctx.player).start("mypack:fire_quest")
              })
              e.add("mypack:ring_bell", ctx => {})
            })

            StoryboundEvents.registerConditions(e => {
              e.add('mypack:is_night', ctx => true)
            })

            /* StoryboundEvents.registerActions(e => { e.add('mypack:dead', ctx => {}) }) */
            StoryboundEvents.questStateChanged('mypack:wolf_hunt', e => {
              Storybound.triggers.fire(`mypack:hunt_over`, e.player)
            })
            StoryboundEvents.partyJoined(e => {})
            """;

    @Test
    void readsHandlersListenersAndIds() {
        ScriptScanner.Result result = ScriptScanner.scan(SCRIPT);
        assertEquals(List.of("mypack:burn_village", "mypack:ring_bell"), result.actions());
        assertEquals(List.of("mypack:is_night"), result.conditions(), "an add() belongs to the registration it follows");
        assertEquals(List.of(new ScriptScanner.Listener("questStateChanged", "mypack:wolf_hunt")), result.listeners(),
                "a listener without an id argument is skipped");
        assertTrue(result.ids().containsAll(List.of("mypack:fire_quest", "mypack:hunt_over", "mypack:wolf_hunt")));
        assertFalse(result.ids().contains("mypack:commented_out"), "comments are not read");
        assertFalse(result.actions().contains("mypack:dead"), "nor block comments");
    }

    @Test
    void leadsToCommentsNameWhatTheCodeDoesNotShow() {
        ScriptScanner.Result result = ScriptScanner.scan("""
                // @storybound leads_to quest:calamity:ch1
                /* @storybound leads_to dialogue:calamity:kairen_intro */
                StoryboundEvents.registerActions(e => { e.add('calamity:prologue', ctx => {}) })
                """);
        assertEquals(List.of(new ScriptScanner.Lead("quest", "calamity:ch1"), new ScriptScanner.Lead("dialogue", "calamity:kairen_intro")), result.leadsTo());
        assertFalse(result.ids().contains("calamity:ch1"), "a comment is not a mention");
    }

    @Test
    void newFilesRegisterTheirHandler() {
        ScriptScanner.Result action = ScriptScanner.scan(DevScripts.template(ResourceLocation.parse("mypack:burn"), DevScripts.Kind.ACTION));
        assertEquals(List.of("mypack:burn"), action.actions(), "the template's handler is found by reading it back");
        ScriptScanner.Result condition = ScriptScanner.scan(DevScripts.template(ResourceLocation.parse("mypack:night"), DevScripts.Kind.CONDITION));
        assertEquals(List.of("mypack:night"), condition.conditions());
        assertEquals("storybound/mypack/fire/burn.js", DevScripts.fileFor(ResourceLocation.parse("mypack:fire/burn")));
    }
}
