package dev.dzenthai.storybound.core.dev;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.data.ConfigStorySource;
import dev.dzenthai.storybound.core.data.DefinitionTypes;
import dev.dzenthai.storybound.core.data.ProjectSettings;
import dev.dzenthai.storybound.core.data.StoryProjects;
import dev.dzenthai.storybound.core.data.ValidationIssue;
import dev.dzenthai.storybound.core.text.ConfigTexts;
import dev.dzenthai.storybound.network.DevProjectPayloads;
import dev.dzenthai.storybound.network.StoryboundNetwork;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Which story each operator edits, and the stories' actions (November 18, 2026): every editor request comes wrapped
 * with the story it was made for ({@code DevProjectPayloads.Edit}) and is taken only while that is still the operator's
 * story and no reload runs ({@link #accepts}), so an edit never lands in another story. {@code select} opens a story in
 * the editor, {@code create} makes one (off in the game) and opens it, {@code update} renames any story and turns it on or
 * off; turning one on checks first that its files load and none of its ids is another story's, and turning one on or
 * off reloads the server's data (a failed reload puts its settings back). After each action the operator gets a new
 * snapshot and the list of stories ({@link #sendState}).
 */
public final class DevProjects {
    private static final Map<ServerPlayer, String> SELECTED = new WeakHashMap<>();
    private static final Set<MinecraftServer> RELOADING = Collections.newSetFromMap(new WeakHashMap<>());

    private DevProjects() {}

    /**
     * The story the operator edits: the one they opened while it is there (this session's, else the one kept with the
     * world from before, {@link DevSelections}; November 19, 2026: after a restart the editor opened the first story),
     * else the primary one; empty when there is no story.
     */
    public static String selected(ServerPlayer player) {
        String id = SELECTED.get(player);
        if (id == null) {
            id = DevSelections.get(player.server).of(player.getUUID()).orElse(null);
        }
        String chosen = id;
        if (chosen != null && StoryProjects.list(ConfigStorySource.root()).stream().anyMatch(project -> project.id().equals(chosen))) {
            SELECTED.put(player, chosen);
            return chosen;
        }
        SELECTED.remove(player);
        return StoryProjects.primary(ConfigStorySource.root()).map(StoryProjects.Project::id).orElse("");
    }

    /** The operator now edits {@code id}: for this session, and kept with the world for the next. */
    private static void choose(ServerPlayer player, String id) {
        SELECTED.put(player, id);
        DevSelections.get(player.server).set(player.getUUID(), id);
    }

    public static boolean accepts(ServerPlayer player, String project) {
        return player.hasPermissions(2) && !RELOADING.contains(player.server) && selected(player).equals(project);
    }

    public static void sendState(ServerPlayer player) {
        JsonArray projects = new JsonArray();
        for (var project : StoryProjects.list(ConfigStorySource.root())) {
            JsonObject value = new JsonObject();
            value.addProperty("id", project.id());
            value.addProperty("title", project.title());
            value.addProperty("enabled", project.enabled());
            projects.add(value);
        }
        StoryboundNetwork.sendToPlayer(player, new DevProjectPayloads.State(selected(player), projects.toString(),
                ConfigTexts.load(DevModeServer.root(player), new ArrayList<>())));
    }

    public static void action(ServerPlayer player, String action, String id, String title, boolean enabled) {
        if (!player.hasPermissions(2)) {
            StoryboundNetwork.sendToPlayer(player, new DevProjectPayloads.Result(false));
            return;
        }
        if (RELOADING.contains(player.server)) {
            player.sendSystemMessage(Component.translatable("storybound.dev.projects.busy").withStyle(ChatFormatting.GOLD));
            StoryboundNetwork.sendToPlayer(player, new DevProjectPayloads.Result(false));
            return;
        }
        boolean success = true;
        try {
            switch (action) {
                case "select" -> {
                    StoryProjects.get(ConfigStorySource.root(), id);
                    choose(player, id);
                }
                case "create" -> {
                    String language = ProjectSettings.load(DevModeServer.root(player)).sourceLanguage();
                    StoryProjects.create(ConfigStorySource.root(), id, title, language);
                    choose(player, id);
                }
                case "delete" -> {
                    // Its folder goes to .deleted, nothing erased (StoryProjects.delete); one in the game leaves it, so the
                    // server's data reloads; an operator who edited it is moved to the primary story (selected).
                    var before = StoryProjects.get(ConfigStorySource.root(), id);
                    java.nio.file.Path moved = StoryProjects.delete(ConfigStorySource.root(), id);
                    player.sendSystemMessage(Component.translatable("storybound.dev.projects.deleted", before.title(),
                            ConfigStorySource.root().toAbsolutePath().normalize().relativize(moved).toString().replace('\\', '/'))
                            .withStyle(ChatFormatting.GOLD));
                    if (before.enabled()) {
                        reload(player, () -> Storybound.LOGGER.error("Storybound: the data did not reload after deleting the story {}; "
                                + "its folder is at {}", id, moved));
                        return;
                    }
                    player.server.getPlayerList().getPlayers().stream().filter(other -> other != player && other.hasPermissions(2))
                            .forEach(DevModeServer::sendSnapshot);
                }
                case "update" -> {
                    // Any story's title and whether it is in the game, not only the one being edited (November 18,
                    // 2026: its settings opened only after the editor had switched to it, a wait and a jump).
                    var before = StoryProjects.get(ConfigStorySource.root(), id);
                    if (enabled && !before.enabled()) {
                        validateForEnable(player.server, before);
                    }
                    StoryProjects.update(ConfigStorySource.root(), id, title, enabled);
                    if (before.enabled() != enabled) {
                        reload(player, () -> {
                            try {
                                StoryProjects.update(ConfigStorySource.root(), before.id(), before.title(), before.enabled());
                            } catch (Exception restoreError) {
                                Storybound.LOGGER.error("Storybound: could not restore story settings after a failed reload", restoreError);
                            }
                        });
                        return;
                    }
                }
                default -> throw new IllegalArgumentException("Unknown story action");
            }
        } catch (Exception e) {
            success = false;
            player.sendSystemMessage(Component.translatable("storybound.dev.projects.error", e.getMessage()).withStyle(ChatFormatting.RED));
        }
        DevModeServer.sendSnapshot(player);
        StoryboundNetwork.sendToPlayer(player, new DevProjectPayloads.Result(success));
    }

    private static void validateForEnable(MinecraftServer server, StoryProjects.Project project) {
        var ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
        var issues = new ArrayList<ValidationIssue>();
        for (var type : DefinitionTypes.all()) {
            Map<ResourceLocation, JsonElement> files = new LinkedHashMap<>();
            ConfigStorySource.scan(project.root(), type, files, issues);
            files.forEach((id, json) -> {
                StoryProjects.checkWritable(project.root(), type, id);
                type.codec().parse(ops, json).getOrThrow(IllegalArgumentException::new);
            });
        }
        ConfigTexts.load(project.root(), issues);
        issues.stream().filter(ValidationIssue::isError).findFirst().ifPresent(issue -> {
            throw new IllegalArgumentException(issue.describe());
        });
    }

    /** Reloads the server's data after a story went in or out of the game; {@code restore} undoes the change if it fails. */
    private static void reload(ServerPlayer author, Runnable restore) {
        MinecraftServer server = author.server;
        RELOADING.add(server);
        server.reloadResources(server.getPackRepository().getSelectedIds()).whenComplete((result, error) -> server.execute(() -> {
            if (error != null) {
                restore.run();
                server.getPlayerList().getPlayers().stream().filter(player -> player.hasPermissions(2)).forEach(player ->
                        player.sendSystemMessage(Component.translatable("storybound.dev.projects.error", error.getMessage()).withStyle(ChatFormatting.RED)));
            }
            RELOADING.remove(server);
            server.getPlayerList().getPlayers().stream().filter(player -> player.hasPermissions(2)).forEach(DevModeServer::sendSnapshot);
            StoryboundNetwork.sendToPlayer(author, new DevProjectPayloads.Result(error == null));
        }));
    }
}
