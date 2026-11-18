package dev.dzenthai.storybound.gametest;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.data.ConfigStorySource;
import dev.dzenthai.storybound.core.data.StoryProjects;
import dev.dzenthai.storybound.core.dev.DevModeServer;
import dev.dzenthai.storybound.core.dev.DevProjects;
import dev.dzenthai.storybound.module.quest.QuestRegistries;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.players.ServerOpListEntry;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@SuppressWarnings("removal")
@GameTestHolder(Storybound.MOD_ID)
@PrefixGameTestTemplate(false)
public final class StoryProjectsGameTests {
    private static final String QUEST = "{\"title\":\"Project quest\",\"category\":\"side\"}";
    /** The GameTests' own story (build/gametest-story/gametest/), the one the editor opens first. */
    private static final String PRIMARY = "gametest";

    @GameTest(template = "empty")
    public static void authorsSwitchIndependentlyAndDisabledStoriesStayEditable(GameTestHelper helper) throws Exception {
        var author = helper.makeMockServerPlayerInLevel();
        var other = helper.makeMockServerPlayerInLevel();
        var guest = helper.makeMockServerPlayerInLevel();
        var players = helper.getLevel().getServer().getPlayerList();
        players.getOps().add(new ServerOpListEntry(author.getGameProfile(), 4, false));
        players.getOps().add(new ServerOpListEntry(other.getGameProfile(), 4, false));
        Path root = StoryProjects.resolve(ConfigStorySource.root(), "project_edit_test");
        var id = ResourceLocation.parse("project_edit_test:first");
        var renamed = ResourceLocation.parse("project_edit_test:renamed");
        try {
            DevProjects.action(guest, "create", "project_edit_test", "Forbidden", false);
            helper.assertFalse(Files.exists(root), "guests cannot create stories");
            DevProjects.action(author, "create", "project_edit_test", "Second story", false);
            helper.assertTrue(DevProjects.selected(author).equals("project_edit_test"), "creator opens the new story");
            helper.assertTrue(DevProjects.selected(other).equals(PRIMARY), "the other author keeps their story");
            helper.assertFalse(DevProjects.accepts(author, PRIMARY), "late writes from the previous story are rejected");
            helper.assertFalse(DevProjects.accepts(guest, PRIMARY), "guests cannot send editor requests");
            DevModeServer.saveDefinition(author, QuestRegistries.QUESTS.id(), id, QUEST, Optional.empty(), "en_us", Map.of());
            helper.assertTrue(Files.exists(ConfigStorySource.fileFor(root, QuestRegistries.QUESTS, id)), "disabled stories can be saved");
            helper.assertFalse(QuestRegistries.QUESTS.serverStore().contains(id), "saving a disabled story does not activate its quests");
            DevModeServer.renameDefinition(author, QuestRegistries.QUESTS.id(), id, renamed);
            helper.assertTrue(Files.exists(ConfigStorySource.fileFor(root, QuestRegistries.QUESTS, renamed)), "disabled definitions can be renamed");
            DevProjects.action(author, "select", PRIMARY, "", false);
            helper.assertFalse(DevProjects.accepts(author, "project_edit_test"), "old project requests are now rejected");
            DevModeServer.saveDefinition(author, QuestRegistries.QUESTS.id(), renamed, QUEST, Optional.empty(), "en_us", Map.of());
            helper.assertFalse(Files.exists(ConfigStorySource.fileFor(StoryProjects.primaryRoot(), QuestRegistries.QUESTS, renamed)), "another story cannot overwrite an owned definition");
            DevProjects.action(author, "select", "project_edit_test", "", false);
            DevModeServer.deleteDefinition(author, QuestRegistries.QUESTS.id(), renamed);
            helper.assertFalse(Files.exists(ConfigStorySource.fileFor(root, QuestRegistries.QUESTS, renamed)), "delete stays inside the selected story");
        } finally {
            DevProjects.action(author, "select", PRIMARY, "", false);
            players.getOps().remove(author.getGameProfile());
            players.getOps().remove(other.getGameProfile());
            removeProject(root);
            players.remove(author);
            players.remove(other);
            players.remove(guest);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "story_projects_runtime", timeoutTicks = 600)
    public static void togglingReloadsTheRuntimeWithoutChangingSelection(GameTestHelper helper) throws Exception {
        var author = helper.makeMockServerPlayerInLevel();
        var players = helper.getLevel().getServer().getPlayerList();
        players.getOps().add(new ServerOpListEntry(author.getGameProfile(), 4, false));
        Path root = StoryProjects.resolve(ConfigStorySource.root(), "project_runtime_test");
        var id = ResourceLocation.parse("project_runtime_test:first");
        DevProjects.action(author, "create", "project_runtime_test", "Runtime story", false);
        DevModeServer.saveDefinition(author, QuestRegistries.QUESTS.id(), id, QUEST, Optional.empty(), "en_us", Map.of());
        DevProjects.action(author, "update", "project_runtime_test", "Runtime story", true);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(DevProjects.accepts(author, "project_runtime_test")
                        && QuestRegistries.QUESTS.serverStore().contains(id), "enabled quest loads"))
                .thenExecute(() -> DevProjects.action(author, "update", "project_runtime_test", "Runtime story", false))
                .thenWaitUntil(() -> helper.assertTrue(DevProjects.accepts(author, "project_runtime_test")
                        && !QuestRegistries.QUESTS.serverStore().contains(id), "disabled quest unloads and editor selection stays"))
                .thenExecute(() -> {
                    DevProjects.action(author, "select", PRIMARY, "", false);
                    players.getOps().remove(author.getGameProfile());
                    players.remove(author);
                    try {
                        removeProject(root);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }).thenSucceed();
    }

    private static void removeProject(Path root) throws Exception {
        Path workspace = ConfigStorySource.root().toAbsolutePath().normalize();
        if (!root.toAbsolutePath().normalize().getParent().equals(workspace) || !root.getFileName().toString().startsWith("project_")) {
            throw new IllegalArgumentException("Not a test story folder");
        }
        if (Files.exists(root)) {
            try (var files = Files.walk(root)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(file);
                }
            }
        }
    }
}
