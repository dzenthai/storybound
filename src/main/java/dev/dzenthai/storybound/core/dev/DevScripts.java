package dev.dzenthai.storybound.core.dev;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.logic.ScriptHooks;
import dev.dzenthai.storybound.core.story.StoryLinks;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;

/**
 * The server's KubeJS scripts as the dev mode sees them: the files in {@code kubejs/server_scripts/} read with
 * {@link ScriptScanner}, the handlers the scripts registered this time they loaded, and new files from a template.
 * Nothing outside that folder is read or written, and no existing file is ever changed.
 */
public final class DevScripts {
    /** Files bigger than this are listed but not read. */
    private static final long MAX_SIZE = 256 * 1024;
    private static final int MAX_FILES = 1000;

    public enum Kind { ACTION, CONDITION }

    private DevScripts() {}

    public static Path folder(MinecraftServer server) {
        return server.getServerDirectory().resolve("kubejs").resolve("server_scripts").toAbsolutePath().normalize();
    }

    /**
     * Everything the editor needs, as JSON: {@code kubejs} (installed), {@code folder} (for showing where files are),
     * {@code actions} / {@code conditions} (registered now), {@code files} (path, handlers, listeners, ids, {@code @storybound leads_to} comments).
     */
    public static JsonObject describe(MinecraftServer server) {
        JsonObject out = new JsonObject();
        Path folder = folder(server);
        out.addProperty("kubejs", ModList.get().isLoaded("kubejs"));
        out.addProperty("folder", folder.toString());
        out.add("actions", strings(ScriptHooks.actions().stream().map(ResourceLocation::toString).sorted().toList()));
        out.add("conditions", strings(ScriptHooks.conditions().stream().map(ResourceLocation::toString).sorted().toList()));
        JsonArray files = new JsonArray();
        if (Files.isDirectory(folder)) {
            try (Stream<Path> walk = Files.walk(folder)) {
                for (Path file : walk.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".js"))
                        .sorted(Comparator.comparing(Path::toString)).limit(MAX_FILES).toList()) {
                    files.add(describeFile(folder, file));
                }
            } catch (IOException e) {
                Storybound.LOGGER.warn("Storybound: cannot list {}: {}", folder, e.getMessage());
            }
        }
        out.add("files", files);
        return out;
    }

    /** A script under {@code kubejs/server_scripts/} as read, for {@link StoryLinks}; {@code null} when it is not there or not readable. */
    public static @Nullable StoryLinks.ScriptFile read(MinecraftServer server, String path) {
        Path folder = folder(server).toAbsolutePath().normalize();
        Path file = folder.resolve(path).normalize();
        try {
            if (!file.startsWith(folder) || !Files.isRegularFile(file) || Files.size(file) > MAX_SIZE) {
                return null;
            }
            ScriptScanner.Result result = ScriptScanner.scan(Files.readString(file, StandardCharsets.UTF_8));
            return new StoryLinks.ScriptFile(path, result.actions(), result.conditions(),
                    result.listeners().stream().map(listener -> new StoryLinks.ScriptListener(listener.event(), listener.id())).toList(),
                    result.ids(), result.leadsTo().stream().map(lead -> new StoryLinks.ScriptListener(lead.type(), lead.id())).toList());
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static JsonObject describeFile(Path folder, Path file) {
        JsonObject out = new JsonObject();
        out.addProperty("path", folder.relativize(file).toString().replace('\\', '/'));
        try {
            if (Files.size(file) <= MAX_SIZE) {
                ScriptScanner.Result result = ScriptScanner.scan(Files.readString(file, StandardCharsets.UTF_8));
                out.add("actions", strings(result.actions()));
                out.add("conditions", strings(result.conditions()));
                JsonArray listeners = new JsonArray();
                for (ScriptScanner.Listener listener : result.listeners()) {
                    JsonObject entry = new JsonObject();
                    entry.addProperty("event", listener.event());
                    entry.addProperty("id", listener.id());
                    listeners.add(entry);
                }
                out.add("listeners", listeners);
                out.add("ids", strings(result.ids()));
                JsonArray leadsTo = new JsonArray();
                for (ScriptScanner.Lead lead : result.leadsTo()) {
                    JsonObject entry = new JsonObject();
                    entry.addProperty("type", lead.type());
                    entry.addProperty("id", lead.id());
                    leadsTo.add(entry);
                }
                out.add("leads_to", leadsTo);
            }
        } catch (IOException | RuntimeException e) {
            out.addProperty("error", e.getMessage());
        }
        return out;
    }

    private static JsonArray strings(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    /** {@code storybound/<namespace>/<path>.js}, where new files for a handler go. */
    public static String fileFor(ResourceLocation hook) {
        return "storybound/" + hook.getNamespace() + "/" + hook.getPath() + ".js";
    }

    /**
     * Writes a new script registering the handler, to fill in.
     *
     * @return the file, relative to the folder
     * @throws IllegalArgumentException for a handler id that is not a file path inside the folder
     * @throws IOException when the file exists already (it is never overwritten) or cannot be written
     */
    public static String createStub(MinecraftServer server, ResourceLocation hook, Kind kind) throws IOException {
        for (String segment : hook.getPath().split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("Not a file path: " + hook);
            }
        }
        Path folder = folder(server);
        String relative = fileFor(hook);
        Path file = folder.resolve(relative).normalize();
        if (!file.startsWith(folder)) {
            throw new IllegalArgumentException("Not a file path inside " + folder + ": " + hook);
        }
        if (Files.exists(file)) {
            throw new IOException(relative + " exists already");
        }
        Files.createDirectories(file.getParent());
        Files.writeString(file, template(hook, kind), StandardCharsets.UTF_8);
        return relative;
    }

    static String template(ResourceLocation hook, Kind kind) {
        return kind == Kind.ACTION ? """
                // The story runs this as the action "Script" with the id %1$s (made by the Storybound dev mode).
                StoryboundEvents.registerActions(e => {
                  e.add('%1$s', ctx => {
                    // ctx.player is the player; ctx.entity the NPC or mob of the moment, if any. For example:
                    // Storybound.flags(ctx.player).set('%2$s:done', true)
                    // Storybound.quests(ctx.player).start('%2$s:next_quest')
                  })
                })
                """.formatted(hook, hook.getNamespace()) : """
                // The story checks this as the condition "Script" with the id %1$s (made by the Storybound dev mode).
                StoryboundEvents.registerConditions(e => {
                  e.add('%1$s', ctx => {
                    // ctx.player is the player; ctx.entity the NPC or mob of the moment, if any.
                    return true
                  })
                })
                """.formatted(hook);
    }
}
