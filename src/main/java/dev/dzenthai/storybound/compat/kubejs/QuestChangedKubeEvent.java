package dev.dzenthai.storybound.compat.kubejs;

import dev.dzenthai.storybound.core.event.QuestChangedEvent;
import dev.dzenthai.storybound.module.quest.QuestLogic;
import dev.latvian.mods.kubejs.event.KubeEvent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/** States are {@code unknown}, {@code active}, {@code completed} or {@code failed}; stages are null when the quest has none. */
public class QuestChangedKubeEvent implements KubeEvent {
    private final QuestChangedEvent event;

    QuestChangedKubeEvent(QuestChangedEvent event) {
        this.event = event;
    }

    public ResourceLocation getQuest() {
        return event.getQuest();
    }

    public String getOldState() {
        return QuestLogic.State.of(event.getOldStatus()).getSerializedName();
    }

    public String getNewState() {
        return QuestLogic.State.of(event.getNewStatus()).getSerializedName();
    }

    public @Nullable String getOldStage() {
        return event.getOldStage().orElse(null);
    }

    public @Nullable String getNewStage() {
        return event.getNewStage().orElse(null);
    }

    /** {@code player}, {@code party} or {@code world}. */
    public String getScope() {
        return event.getScope().getSerializedName();
    }

    /** The player in whose context the change happened. */
    public @Nullable ServerPlayer getPlayer() {
        return event.getActor();
    }

    public MinecraftServer getServer() {
        return event.getServer();
    }
}
