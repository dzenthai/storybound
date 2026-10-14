package dev.dzenthai.storybound.core.event;

import dev.dzenthai.storybound.core.state.QuestStatus;
import dev.dzenthai.storybound.core.state.StoryScope;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.Event;
import org.jetbrains.annotations.Nullable;

/**
 * A quest's status or stage changed in one scope. Posted on {@code NeoForge.EVENT_BUS}; backs the
 * {@code quest_state_changed} / {@code quest_stage_changed} triggers and the KubeJS events.
 * An empty status means the quest became unknown (reset).
 */
public final class QuestChangedEvent extends Event {
    private final MinecraftServer server;
    private final ResourceLocation quest;
    private final StoryScope scope;
    private final @Nullable UUID owner;
    private final Optional<QuestStatus> oldStatus;
    private final Optional<QuestStatus> newStatus;
    private final Optional<String> oldStage;
    private final Optional<String> newStage;
    private final @Nullable ServerPlayer actor;

    public QuestChangedEvent(MinecraftServer server, ResourceLocation quest, StoryScope scope, @Nullable UUID owner,
                             Optional<QuestStatus> oldStatus, Optional<QuestStatus> newStatus,
                             Optional<String> oldStage, Optional<String> newStage, @Nullable ServerPlayer actor) {
        this.server = server;
        this.quest = quest;
        this.scope = scope;
        this.owner = owner;
        this.oldStatus = oldStatus;
        this.newStatus = newStatus;
        this.oldStage = oldStage;
        this.newStage = newStage;
        this.actor = actor;
    }

    public MinecraftServer getServer() {
        return server;
    }

    public ResourceLocation getQuest() {
        return quest;
    }

    public StoryScope getScope() {
        return scope;
    }

    public @Nullable UUID getOwner() {
        return owner;
    }

    public Optional<QuestStatus> getOldStatus() {
        return oldStatus;
    }

    public Optional<QuestStatus> getNewStatus() {
        return newStatus;
    }

    public Optional<String> getOldStage() {
        return oldStage;
    }

    public Optional<String> getNewStage() {
        return newStage;
    }

    public boolean statusChanged() {
        return !oldStatus.equals(newStatus);
    }

    public boolean stageChanged() {
        return !oldStage.equals(newStage);
    }

    /** The player in whose context the change happened. */
    public @Nullable ServerPlayer getActor() {
        return actor;
    }
}
