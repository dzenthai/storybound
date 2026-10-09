package dev.dzenthai.storybound.core.event;

import dev.dzenthai.storybound.core.state.StoryScope;
import dev.dzenthai.storybound.core.state.StoryValue;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.Event;
import org.jetbrains.annotations.Nullable;

/**
 * A story flag or variable changed. Posted on {@code NeoForge.EVENT_BUS}
 * on the server thread; the {@code flag_changed} trigger and KubeJS events build on it.
 */
public final class StoryValueChangedEvent extends Event {
    private final MinecraftServer server;
    private final StoryScope scope;
    private final @Nullable UUID owner;
    private final ResourceLocation key;
    private final Optional<StoryValue> oldValue;
    private final Optional<StoryValue> newValue;
    private final @Nullable ServerPlayer actor;

    public StoryValueChangedEvent(MinecraftServer server, StoryScope scope, @Nullable UUID owner, ResourceLocation key,
                                  Optional<StoryValue> oldValue, Optional<StoryValue> newValue, @Nullable ServerPlayer actor) {
        this.server = server;
        this.scope = scope;
        this.owner = owner;
        this.key = key;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.actor = actor;
    }

    public MinecraftServer getServer() {
        return server;
    }

    public StoryScope getScope() {
        return scope;
    }

    /** Player UUID for {@code player}, party (or solo party) id for {@code party}, null for {@code world}. */
    public @Nullable UUID getOwner() {
        return owner;
    }

    public ResourceLocation getKey() {
        return key;
    }

    public Optional<StoryValue> getOldValue() {
        return oldValue;
    }

    /** Empty if the value was cleared. */
    public Optional<StoryValue> getNewValue() {
        return newValue;
    }

    /** The player in whose context the change happened. */
    public @Nullable ServerPlayer getActor() {
        return actor;
    }
}
