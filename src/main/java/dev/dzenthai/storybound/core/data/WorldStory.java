package dev.dzenthai.storybound.core.data;

import dev.dzenthai.storybound.Storybound;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameRules;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.Nullable;

/**
 * The story bundled with the mod ({@code storybound_stories/vanilla}) as a setting of the world, not of the story
 * folder: the game rule {@code storyboundStory}, off by default (a world made before it, a dedicated server's), turned on
 * by the world creation screen's "Story: Yes" (its default) or {@code /gamerule storyboundStory true}.
 * <ul>
 * <li>On, the bundled story loads: the story folder's copy when it was installed there (an author's edited one), else
 * the mod's own, read from the jar, nothing copied.</li>
 * <li>Off, it does not load, the copy in the story folder neither: its {@code enabled} in {@code project.json} is
 * the world's rule, not the file's.</li>
 * <li>Changed while the world runs, the server's data reloads (as {@code /reload}); progress stays in the world.</li>
 * </ul>
 * The data loads before the server exists on a world's start, when the rule cannot be read yet: then it loads without
 * the story, and {@link StoryboundDataEvents} loads the story's definitions again once the server is there.
 */
public final class WorldStory {
    public static final String RULE_NAME = "storyboundStory";
    /** The bundled story the rule turns on. */
    public static final String BUNDLED = "vanilla";

    private static GameRules.@Nullable Key<GameRules.BooleanValue> rule;
    private static @Nullable List<String> bundled;

    private WorldStory() {}

    /** At mod construction: the game rule, which reloads the server's data when it changes. */
    public static void register() {
        rule = GameRules.register(RULE_NAME, GameRules.Category.MISC, GameRules.BooleanValue.create(false, (server, value) -> reload(server)));
    }

    public static GameRules.Key<GameRules.BooleanValue> rule() {
        if (rule == null) {
            throw new IllegalStateException("Storybound: the story game rule is not registered");
        }
        return rule;
    }

    /**
     * Whether the running world plays the bundled story; false with no server (a world's first data load, a client of a
     * server). Read from the world's data, which has the rules before the levels are made.
     */
    public static boolean on() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return rule != null && server != null && server.getWorldData().getGameRules().getBoolean(rule);
    }

    /** Whether the story of this id is the one the world's rule decides on. */
    public static boolean isBundled(String id) {
        return BUNDLED.equals(id) && bundledIds().contains(id);
    }

    /** The bundled story's own folder, in the mod's jar (the build's resources in a dev run). */
    public static Optional<Path> bundledRoot() {
        return bundledIds().contains(BUNDLED) ? StoryFolder.bundledRoot(BUNDLED) : Optional.empty();
    }

    /** Sets the rule without the reload its change makes (the caller reloads, as the editor's story switch does). */
    public static void setQuietly(MinecraftServer server, boolean value) {
        server.getWorldData().getGameRules().getRule(rule()).set(value, null);
    }

    private static List<String> bundledIds() {
        if (bundled == null) {
            bundled = StoryFolder.bundledStories();
        }
        return bundled;
    }

    private static void reload(MinecraftServer server) {
        Storybound.LOGGER.info("Storybound: {} is now {}; reloading the data", RULE_NAME, server.getWorldData().getGameRules().getBoolean(rule()));
        server.reloadResources(server.getPackRepository().getSelectedIds()).exceptionally(error -> {
            Storybound.LOGGER.error("Storybound: the data did not reload after {} changed", RULE_NAME, error);
            return null;
        });
    }
}
