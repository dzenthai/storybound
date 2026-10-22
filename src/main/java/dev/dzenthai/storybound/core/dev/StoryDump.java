package dev.dzenthai.storybound.core.dev;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import dev.dzenthai.storybound.core.state.PartyData;
import dev.dzenthai.storybound.core.state.QuestProgress;
import dev.dzenthai.storybound.core.state.StoryContainer;
import dev.dzenthai.storybound.core.state.StoryScope;
import dev.dzenthai.storybound.core.state.StoryState;
import dev.dzenthai.storybound.core.state.StoryValue;
import dev.dzenthai.storybound.module.boss.BossData;
import dev.dzenthai.storybound.module.boss.Bosses;
import dev.dzenthai.storybound.module.codex.Codex;
import dev.dzenthai.storybound.module.codex.CodexData;
import dev.dzenthai.storybound.module.dialogue.Dialogues;
import dev.dzenthai.storybound.module.journal.Journal;
import dev.dzenthai.storybound.module.journal.JournalData;
import dev.dzenthai.storybound.module.quest.QuestRewardData;
import dev.dzenthai.storybound.module.quest.Quests;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /storybound dump <player>}: everything the story knows about a player as one JSON file, so it can be read
 * instead of the save's NBT: flags and variables of every scope, quests with stages, counts and log, the tracked
 * quest, Codex, bosses, the Journal, the party and an ongoing dialogue.
 */
public final class StoryDump {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private StoryDump() {}

    /** {@code logs/storybound-dump-<name>.json}; a new dump of the same player replaces it. */
    public static Path file(MinecraftServer server, String player) {
        return server.getServerDirectory().resolve("logs").resolve("storybound-dump-" + player + ".json");
    }

    public static Path write(ServerPlayer player) throws IOException {
        Path file = file(player.server, player.getGameProfile().getName());
        Files.createDirectories(file.getParent());
        Files.writeString(file, GSON.toJson(dump(player)) + "\n", StandardCharsets.UTF_8);
        return file;
    }

    public static JsonObject dump(ServerPlayer player) {
        MinecraftServer server = player.server;
        JsonObject root = new JsonObject();
        root.addProperty("player", player.getGameProfile().getName());
        root.addProperty("uuid", player.getUUID().toString());
        root.addProperty("written", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        root.addProperty("tick", server.getTickCount());
        root.addProperty("dimension", player.level().dimension().location().toString());
        JsonArray pos = new JsonArray();
        pos.add(Math.round(player.getX() * 10) / 10.0);
        pos.add(Math.round(player.getY() * 10) / 10.0);
        pos.add(Math.round(player.getZ() * 10) / 10.0);
        root.add("pos", pos);
        party(server, player).ifPresent(party -> root.add("party", party));

        JsonObject values = new JsonObject();
        JsonObject quests = new JsonObject();
        for (StoryScope scope : StoryScope.values()) {
            StoryContainer stored = StoryState.stored(player, scope);
            JsonObject scoped = new JsonObject();
            stored.values().forEach((key, value) -> scoped.add(key.toString(), value(value)));
            values.add(scope.getSerializedName(), scoped);
            stored.quests().forEach((quest, progress) -> quests.add(quest.toString(), quest(quest, scope, progress)));
        }
        root.add("values", values);
        root.add("quests", quests);
        JsonArray pinned = new JsonArray();
        Quests.pinned(player).forEach(quest -> pinned.add(quest.toString()));
        root.add("pinned", pinned);
        root.add("rewards_waiting", encode(QuestRewardData.CODEC, QuestRewardData.of(player), RegistryOps.create(JsonOps.INSTANCE, server.registryAccess())));

        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
        root.add("codex", encode(CodexData.CODEC, Codex.data(player), ops));
        root.add("bosses", encode(BossData.CODEC, Bosses.data(player), ops));
        root.add("journal", encode(JournalData.CODEC, Journal.of(player), ops));
        Dialogues.state(player).ifPresent(state -> {
            JsonObject dialogue = new JsonObject();
            dialogue.addProperty("dialogue", state.dialogue().toString());
            dialogue.addProperty("node", state.node());
            dialogue.addProperty("waiting_for_choice", state.waitingForChoice());
            root.add("dialogue", dialogue);
        });
        return root;
    }

    private static JsonObject quest(ResourceLocation quest, StoryScope scope, QuestProgress progress) {
        JsonObject json = new JsonObject();
        json.addProperty("scope", scope.getSerializedName());
        json.addProperty("status", progress.status().name().toLowerCase(Locale.ROOT));
        progress.stage().ifPresent(stage -> json.addProperty("stage", stage));
        JsonObject objectives = new JsonObject();
        progress.objectives().forEach(objectives::addProperty);
        json.add("objectives", objectives);
        JsonArray log = new JsonArray();
        progress.log().forEach(log::add);
        json.add("log", log);
        // Progress of a quest no longer in the data stays in the save (see Migrations).
        json.addProperty("defined", Quests.definition(quest) != null);
        return json;
    }

    private static Optional<JsonObject> party(MinecraftServer server, ServerPlayer player) {
        PartyData parties = PartyData.get(server);
        return parties.partyOf(player.getUUID()).map(party -> {
            JsonObject json = new JsonObject();
            json.addProperty("id", party.id().toString());
            party.name().ifPresent(name -> json.addProperty("name", name));
            json.addProperty("leader", name(server, party.leader()));
            JsonArray members = new JsonArray();
            for (UUID member : party.members()) {
                members.add(name(server, member));
            }
            json.add("members", members);
            return json;
        });
    }

    /** An online player's name, else the uuid. */
    private static String name(MinecraftServer server, UUID id) {
        ServerPlayer online = server.getPlayerList().getPlayer(id);
        return online != null ? online.getGameProfile().getName() : id.toString();
    }

    /** As written in JSON: {@code true}, {@code 5}, {@code "north"}. */
    private static JsonElement value(StoryValue value) {
        return switch (value) {
            case StoryValue.Bool bool -> new JsonPrimitive(bool.value());
            case StoryValue.Int integer -> new JsonPrimitive(integer.value());
            case StoryValue.Str string -> new JsonPrimitive(string.value());
        };
    }

    private static <T> JsonElement encode(Codec<T> codec, T value, RegistryOps<JsonElement> ops) {
        return codec.encodeStart(ops, value).result().orElse(JsonNull.INSTANCE);
    }
}
