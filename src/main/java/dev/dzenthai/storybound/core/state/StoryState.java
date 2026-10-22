package dev.dzenthai.storybound.core.state;

import dev.dzenthai.storybound.core.CoreDefinitions;
import dev.dzenthai.storybound.core.StoryException;
import dev.dzenthai.storybound.core.event.StoryValueChangedEvent;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;

/**
 * The single entry point for reading and writing story values. Modules do not know
 * where a scope's data physically lives; every change posts a
 * {@link StoryValueChangedEvent} and sends {@code sync} flags to clients.
 */
public final class StoryState {
    private StoryState() {}

    /** The flag's declared scope, or {@code player} for undeclared flags. */
    public static StoryScope declaredScope(ResourceLocation key) {
        FlagDeclaration declaration = declaration(key);
        return declaration != null ? declaration.scope() : StoryScope.PLAYER;
    }

    /** Only a value that was actually written. */
    public static Optional<StoryValue> getStored(ServerPlayer player, StoryScope scope, ResourceLocation key) {
        return container(player, scope).get(key);
    }

    /** The written value, or the declared default. */
    public static Optional<StoryValue> get(ServerPlayer player, StoryScope scope, ResourceLocation key) {
        return getStored(player, scope, key).or(() -> Optional.ofNullable(declaration(key)).map(FlagDeclaration::defaultValue));
    }

    public static void set(ServerPlayer player, StoryScope scope, ResourceLocation key, StoryValue value) throws StoryException {
        FlagDeclaration declaration = checkScope(key, scope);
        if (declaration != null && declaration.type() != value.type()) {
            throw new StoryException(Component.translatable("storybound.state.wrong_type",
                    key.toString(), declaration.type().getSerializedName(), value.type().getSerializedName()));
        }
        StoryValue old = container(player, scope).put(key, value);
        if (!value.equals(old)) {
            changed(player, scope, key, Optional.ofNullable(old), Optional.of(value));
        }
    }

    public static void clear(ServerPlayer player, StoryScope scope, ResourceLocation key) throws StoryException {
        checkScope(key, scope);
        StoryValue old = container(player, scope).remove(key);
        if (old != null) {
            changed(player, scope, key, Optional.of(old), Optional.empty());
        }
    }

    /** Adds to a numeric variable (a missing one counts as its default). */
    public static int add(ServerPlayer player, StoryScope scope, ResourceLocation key, int delta) throws StoryException {
        StoryValue current = get(player, scope, key).orElse(StoryValueType.INT.zero());
        if (!(current instanceof StoryValue.Int(int value))) {
            throw new StoryException(Component.translatable("storybound.state.not_int", key.toString()));
        }
        int result = value + delta;
        set(player, scope, key, new StoryValue.Int(result));
        return result;
    }

    /** Everything stored for the player in a scope, read only: its values and quests (for dumps and tools). */
    public static StoryContainer stored(ServerPlayer player, StoryScope scope) {
        return container(player, scope);
    }

    /** A quest's progress in a scope; empty means the quest is unknown there. */
    public static Optional<QuestProgress> getQuest(ServerPlayer player, StoryScope scope, ResourceLocation quest) {
        return container(player, scope).quest(quest);
    }

    /**
     * Raw write of a quest's progress ({@code null} forgets the quest). The quest module owns the
     * rules, events and sync; other code should go through it.
     */
    public static void setQuest(ServerPlayer player, StoryScope scope, ResourceLocation quest, @Nullable QuestProgress progress) {
        container(player, scope).putQuest(quest, progress);
    }

    /** Player UUID, party (or solo party) id, or null for the {@code world} scope. */
    public static @Nullable UUID owner(ServerPlayer player, StoryScope scope) {
        return switch (scope) {
            case PLAYER -> player.getUUID();
            case PARTY -> PartyData.get(player.server).ownerOf(player.getUUID());
            case WORLD -> null;
        };
    }

    /** Online players who share the storage identified by {@code scope} and {@code owner}. */
    public static List<ServerPlayer> onlinePlayersOf(MinecraftServer server, StoryScope scope, @Nullable UUID owner) {
        List<ServerPlayer> online = server.getPlayerList().getPlayers();
        return switch (scope) {
            case PLAYER -> online.stream().filter(player -> player.getUUID().equals(owner)).toList();
            case PARTY -> {
                PartyData parties = PartyData.get(server);
                yield online.stream().filter(player -> parties.ownerOf(player.getUUID()).equals(owner)).toList();
            }
            case WORLD -> List.copyOf(online);
        };
    }

    static StoryContainer container(ServerPlayer player, StoryScope scope) {
        return switch (scope) {
            case PLAYER -> player.getData(StoryAttachments.PLAYER_STATE);
            case PARTY -> {
                PartyData parties = PartyData.get(player.server);
                yield parties.container(parties.ownerOf(player.getUUID()));
            }
            case WORLD -> WorldStoryData.get(player.server).container();
        };
    }

    static @Nullable FlagDeclaration declaration(ResourceLocation key) {
        return CoreDefinitions.FLAGS.serverStore().getOrNull(key);
    }

    // A declared flag has a fixed scope; otherwise the client and conditions could read different values.
    private static @Nullable FlagDeclaration checkScope(ResourceLocation key, StoryScope scope) throws StoryException {
        FlagDeclaration declaration = declaration(key);
        if (declaration != null && declaration.scope() != scope) {
            throw new StoryException(Component.translatable("storybound.state.wrong_scope",
                    key.toString(), declaration.scope().getSerializedName(), scope.getSerializedName()));
        }
        return declaration;
    }

    private static void changed(ServerPlayer player, StoryScope scope, ResourceLocation key, Optional<StoryValue> oldValue, Optional<StoryValue> newValue) {
        UUID owner = owner(player, scope);
        NeoForge.EVENT_BUS.post(new StoryValueChangedEvent(player.server, scope, owner, key, oldValue, newValue, player));
        StateSync.onChanged(player.server, scope, owner, key, newValue);
    }
}
