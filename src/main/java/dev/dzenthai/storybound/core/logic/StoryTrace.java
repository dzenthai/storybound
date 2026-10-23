package dev.dzenthai.storybound.core.logic;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.logic.trigger.TriggerDispatcher;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * Writes what the story logic did for the traced players to {@code logs/storybound-trace.log}, one JSON object per
 * line, so a story can be debugged without watching the game: which trigger fired, which condition failed and on
 * which part, which action ran or failed, and why something was skipped. Turned on with {@code /storybound trace};
 * kept in memory until the server stops. Server thread only.
 */
public final class StoryTrace {
    /** A longer log moves to {@code storybound-trace.1.log} (the one before is dropped). */
    private static final long MAX_BYTES = 16L * 1024 * 1024;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");

    private static boolean enabled;
    /** The traced players; empty traces everyone. */
    private static final Set<UUID> players = new LinkedHashSet<>();
    private static @Nullable Path file;
    private static @Nullable BufferedWriter writer;
    private static @Nullable MinecraftServer server;
    /** Players someone watches live ({@code /storybound debug}): their lines also go to {@link #tap}, file or not. */
    private static Predicate<UUID> watched = player -> false;
    private static BiConsumer<UUID, JsonObject> tap = (player, line) -> {};

    private StoryTrace() {}

    /**
     * Lets another part of the mod see the lines of the players it watches, whether the file trace is on or not: the
     * debug overlay. {@code sink} gets each line as written (without time and tick).
     */
    public static void tap(Predicate<UUID> watching, BiConsumer<UUID, JsonObject> sink) {
        watched = watching;
        tap = sink;
    }

    /** The server whose registries write conditions and actions as JSON; set by tracing or by the overlay. */
    public static void useServer(MinecraftServer minecraft) {
        server = minecraft;
    }

    public static Path file(MinecraftServer server) {
        return server.getServerDirectory().resolve("logs").resolve("storybound-trace.log");
    }

    /**
     * Starts tracing (or changes who is traced) and writes a line saying so.
     *
     * @param only the players to trace; empty traces everyone
     */
    public static void enable(MinecraftServer minecraft, Set<UUID> only, List<String> names) {
        server = minecraft;
        players.clear();
        players.addAll(only);
        enabled = true;
        JsonObject line = new JsonObject();
        line.addProperty("kind", "trace_on");
        line.addProperty("players", names.isEmpty() ? "*" : String.join(",", names));
        write(line);
    }

    public static void disable() {
        if (enabled) {
            JsonObject line = new JsonObject();
            line.addProperty("kind", "trace_off");
            write(line);
        }
        enabled = false;
        players.clear();
        close();
    }

    public static boolean enabled() {
        return enabled;
    }

    /** The traced players; empty means everyone. */
    public static Set<UUID> players() {
        return Set.copyOf(players);
    }

    /** Whether anything is written for the player: the file trace, or someone watching them live. */
    public static boolean active(ServerPlayer player) {
        return toFile(player) || watched.test(player.getUUID());
    }

    private static boolean toFile(ServerPlayer player) {
        return enabled && (players.isEmpty() || players.contains(player.getUUID()));
    }

    /** A player's line: to the file when it traces them, to the overlay when someone watches them. */
    private static void emit(ServerPlayer player, JsonObject line) {
        if (watched.test(player.getUUID())) {
            tap.accept(player.getUUID(), line.deepCopy());
        }
        if (toFile(player)) {
            write(line);
        }
    }

    /** A trigger matched and its owner is about to run. */
    public static void trigger(StoryContext context, String source, TriggerType<?, ?> type) {
        if (!active(context.player())) {
            return;
        }
        JsonObject line = line(context, "trigger", source);
        line.addProperty("type", String.valueOf(LogicRegistries.TRIGGER_TYPES.getKey(type)));
        emit(context.player(), line);
    }

    /**
     * Conditions were checked.
     *
     * @param failed the parts that made them fail (see {@link Conditions#check})
     */
    public static void conditions(StoryContext context, String source, boolean passed, List<JsonObject> failed) {
        JsonObject line = line(context, "conditions", source);
        line.addProperty("passed", passed);
        if (!failed.isEmpty()) {
            JsonArray parts = new JsonArray();
            failed.forEach(parts::add);
            line.add("failed", parts);
        }
        emit(context.player(), line);
    }

    /**
     * An action ran.
     *
     * @param result {@code ok}, {@code failed} or {@code crashed}
     */
    public static void action(StoryContext context, String source, int index, Action action, String result, @Nullable String message) {
        if (!active(context.player())) {
            return;
        }
        JsonObject line = line(context, "action", source);
        line.addProperty("index", index);
        line.addProperty("type", String.valueOf(LogicRegistries.ACTION_TYPES.getKey(action.type())));
        line.add("action", json(LogicCodecs.ACTION, action));
        line.addProperty("result", result);
        if (message != null) {
            line.addProperty("message", message);
        }
        emit(context.player(), line);
    }

    /** Something the story logic decided without a condition: a quest already started, a once event already run. */
    public static void note(ServerPlayer player, String source, String message) {
        if (!active(player)) {
            return;
        }
        JsonObject line = line(StoryContext.of(player), "note", source);
        line.addProperty("message", message);
        emit(player, line);
    }

    /**
     * Story logic run for a player: a quest's state or stage, a dialogue's start and end.
     *
     * @param fields names and values, in pairs, in the order they are written
     */
    public static void event(ServerPlayer player, String kind, String... fields) {
        if (!active(player)) {
            return;
        }
        JsonObject line = line(StoryContext.of(player), kind, null);
        for (int i = 0; i + 1 < fields.length; i += 2) {
            line.addProperty(fields[i], fields[i + 1]);
        }
        emit(player, line);
    }

    /** The condition or action as it would be written in JSON; its type id when it does not encode. */
    static <T> JsonElement json(Codec<T> codec, T value) {
        MinecraftServer minecraft = server;
        JsonOps ops = JsonOps.INSTANCE;
        try {
            return minecraft != null
                    ? codec.encodeStart(RegistryOps.create(ops, minecraft.registryAccess()), value).result().orElse(JsonNull.INSTANCE)
                    : codec.encodeStart(ops, value).result().orElse(JsonNull.INSTANCE);
        } catch (RuntimeException e) {
            return new JsonPrimitive(value.toString());
        }
    }

    private static JsonObject line(StoryContext context, String kind, @Nullable String source) {
        JsonObject line = new JsonObject();
        line.addProperty("player", context.player().getGameProfile().getName());
        line.addProperty("kind", kind);
        if (source != null) {
            line.addProperty("source", source);
        }
        int depth = TriggerDispatcher.depth();
        if (depth > 0) {
            line.addProperty("depth", depth);
        }
        if (context.entity() != null) {
            line.addProperty("entity", BuiltInRegistries.ENTITY_TYPE.getKey(context.entity().getType()) + " " + context.entity().getUUID());
        }
        return line;
    }

    private static void write(JsonObject body) {
        MinecraftServer minecraft = server;
        if (minecraft == null) {
            return;
        }
        JsonObject line = new JsonObject();
        line.addProperty("time", LocalDateTime.now().format(TIME));
        line.addProperty("tick", minecraft.getTickCount());
        body.entrySet().forEach(entry -> line.add(entry.getKey(), entry.getValue()));
        try {
            BufferedWriter out = open(minecraft);
            out.write(line.toString());
            out.newLine();
            // Flushed every line: the trace is read while the game runs.
            out.flush();
            if (file != null && Files.size(file) > MAX_BYTES) {
                close();
                Files.move(file, file.resolveSibling("storybound-trace.1.log"), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            Storybound.LOGGER.warn("Storybound: could not write the trace: {}", e.getMessage());
            close();
        }
    }

    private static BufferedWriter open(MinecraftServer minecraft) throws IOException {
        if (writer == null) {
            file = file(minecraft);
            Files.createDirectories(file.getParent());
            writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        return writer;
    }

    private static void close() {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException ignored) {
                // Nothing more to write anyway.
            }
        }
        writer = null;
    }

    /** The server stopped: nothing is traced in the next world until asked again. */
    public static void stop() {
        disable();
        server = null;
    }
}
