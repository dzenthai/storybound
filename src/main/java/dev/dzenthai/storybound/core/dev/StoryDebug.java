package dev.dzenthai.storybound.core.dev;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.dzenthai.storybound.core.logic.StoryTrace;
import dev.dzenthai.storybound.core.state.QuestProgress;
import dev.dzenthai.storybound.core.state.QuestStatus;
import dev.dzenthai.storybound.module.dialogue.Dialogues;
import dev.dzenthai.storybound.module.quest.QuestDefinition;
import dev.dzenthai.storybound.module.quest.QuestRegistries;
import dev.dzenthai.storybound.module.quest.Quests;
import dev.dzenthai.storybound.network.DebugPayload;
import dev.dzenthai.storybound.network.StoryboundNetwork;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;

/**
 * {@code /storybound debug}: an operator watches a player's story live, on a panel of their own screen — the dialogue
 * and node the player is at, their active quests with stages and counts, and the last things the story logic did for
 * them with the reasons (the trace's lines, see {@link StoryTrace#tap}), whether the file trace is on or not.
 */
public final class StoryDebug {
    /** Lines of recent logic kept per watched player. */
    private static final int KEPT = 12;
    /** How often the panel is sent again (only when it changed), in ticks. */
    private static final int INTERVAL = 10;
    private static final int MAX_QUESTS = 6;

    /** Viewer → the player they watch. */
    private static final Map<UUID, UUID> WATCHING = new HashMap<>();
    private static final Map<UUID, Deque<String>> RECENT = new HashMap<>();
    /** Viewer → the panel last sent, to send only changes. */
    private static final Map<UUID, DebugPayload> SENT = new HashMap<>();

    private StoryDebug() {}

    public static void register(IEventBus gameBus) {
        StoryTrace.tap(player -> WATCHING.containsValue(player), StoryDebug::record);
        gameBus.addListener(StoryDebug::onServerTick);
        gameBus.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> {
            UUID left = event.getEntity().getUUID();
            WATCHING.remove(left);
            SENT.remove(left);
        });
        gameBus.addListener((ServerStoppedEvent event) -> {
            WATCHING.clear();
            RECENT.clear();
            SENT.clear();
        });
    }

    /** Starts watching {@code target}, or stops with null. */
    public static void watch(ServerPlayer viewer, @Nullable ServerPlayer target) {
        SENT.remove(viewer.getUUID());
        if (target == null) {
            WATCHING.remove(viewer.getUUID());
            StoryboundNetwork.sendToPlayer(viewer, DebugPayload.OFF);
            return;
        }
        StoryTrace.useServer(viewer.server);
        WATCHING.put(viewer.getUUID(), target.getUUID());
        RECENT.computeIfAbsent(target.getUUID(), id -> new ArrayDeque<>());
        send(viewer, target);
    }

    /** The recent logic lines kept for a watched player, oldest first. Public for tests. */
    public static List<String> recent(UUID player) {
        return List.copyOf(RECENT.getOrDefault(player, new ArrayDeque<>()));
    }

    private static void record(UUID player, JsonObject line) {
        Deque<String> recent = RECENT.computeIfAbsent(player, id -> new ArrayDeque<>());
        recent.addLast(summary(line));
        while (recent.size() > KEPT) {
            recent.removeFirst();
        }
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        if (WATCHING.isEmpty() || event.getServer().getTickCount() % INTERVAL != 0) {
            return;
        }
        MinecraftServer server = event.getServer();
        for (Map.Entry<UUID, UUID> entry : List.copyOf(WATCHING.entrySet())) {
            ServerPlayer viewer = server.getPlayerList().getPlayer(entry.getKey());
            ServerPlayer target = server.getPlayerList().getPlayer(entry.getValue());
            if (viewer != null && target != null) {
                send(viewer, target);
            }
        }
    }

    private static void send(ServerPlayer viewer, ServerPlayer target) {
        DebugPayload panel = new DebugPayload(target.getGameProfile().getName(), state(target),
                List.copyOf(RECENT.getOrDefault(target.getUUID(), new ArrayDeque<>())));
        if (!Objects.equals(SENT.get(viewer.getUUID()), panel)) {
            SENT.put(viewer.getUUID(), panel);
            StoryboundNetwork.sendToPlayer(viewer, panel);
        }
    }

    /** The dialogue the player is in and their active quests, a line each. */
    private static List<String> state(ServerPlayer player) {
        List<String> lines = new ArrayList<>();
        Dialogues.state(player).ifPresent(dialogue -> lines.add("💬 " + dialogue.dialogue() + " @ " + dialogue.node()
                + (dialogue.waitingForChoice() ? " (choice)" : "")));
        int shown = 0;
        for (Map.Entry<ResourceLocation, QuestProgress> entry : Quests.known(player).entrySet()) {
            QuestProgress progress = entry.getValue();
            if (progress.status() != QuestStatus.ACTIVE) {
                continue;
            }
            if (++shown > MAX_QUESTS) {
                break;
            }
            StringBuilder line = new StringBuilder("📜 " + entry.getKey() + " · " + progress.stage().orElse("?"));
            QuestDefinition definition = QuestRegistries.QUESTS.serverStore().getOrNull(entry.getKey());
            QuestDefinition.Stage stage = definition != null ? progress.stage().flatMap(definition::stage).orElse(null) : null;
            if (stage != null) {
                for (int i = 0; i < stage.objectives().size(); i++) {
                    String id = stage.objectiveId(i);
                    line.append("  ").append(id).append(' ').append(progress.count(id)).append('/').append(stage.objectives().get(i).count());
                }
            }
            lines.add(line.toString());
        }
        return lines;
    }

    /** A trace line in a few words: what decided, and why when something failed. Public for tests. */
    public static String summary(JsonObject line) {
        String kind = text(line, "kind");
        String source = text(line, "source");
        return switch (kind) {
            case "trigger" -> "⚡ " + source;
            case "conditions" -> line.has("passed") && line.get("passed").getAsBoolean() ? "✔ " + source : "✘ " + source + why(line);
            case "action" -> ("ok".equals(text(line, "result")) ? "▶ " : "✘ ") + text(line, "type") + " — " + source
                    + (line.has("message") ? ": " + text(line, "message") : "");
            case "note" -> "· " + source + ": " + text(line, "message");
            case "quest" -> "📜 " + text(line, "quest") + ": " + text(line, "from") + " → " + text(line, "to");
            case "objective" -> "◎ " + text(line, "quest") + " " + text(line, "objective") + " " + text(line, "count");
            case "dialogue_started" -> "💬 " + text(line, "dialogue");
            case "dialogue_ended" -> "💬 ✕ " + text(line, "dialogue") + " (" + text(line, "reason") + ")";
            default -> kind + (source.isEmpty() ? "" : " " + source);
        };
    }

    /** The first failed part's reason, or its condition's type. */
    private static String why(JsonObject line) {
        if (line.get("failed") instanceof JsonArray failed && !failed.isEmpty() && failed.get(0) instanceof JsonObject part) {
            if (part.has("why")) {
                return ": " + text(part, "why");
            }
            if (part.get("condition") instanceof JsonObject condition) {
                return ": " + text(condition, "type");
            }
        }
        return "";
    }

    private static String text(JsonObject object, String field) {
        JsonElement value = object.get(field);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }
}
