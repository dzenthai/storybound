package dev.dzenthai.storybound.compat.kubejs;

import dev.dzenthai.storybound.core.event.StoryValueChangedEvent;
import dev.latvian.mods.kubejs.event.KubeEvent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

public class FlagChangedKubeEvent implements KubeEvent {
    private final StoryValueChangedEvent event;

    FlagChangedKubeEvent(StoryValueChangedEvent event) {
        this.event = event;
    }

    public ResourceLocation getKey() {
        return event.getKey();
    }

    /** {@code player}, {@code party} or {@code world}. */
    public String getScope() {
        return event.getScope().getSerializedName();
    }

    public @Nullable Object getOldValue() {
        return ScriptValues.toScript(event.getOldValue().orElse(null));
    }

    /** Null if the value was cleared. */
    public @Nullable Object getNewValue() {
        return ScriptValues.toScript(event.getNewValue().orElse(null));
    }

    /** The player whose action changed the value; null for changes from the console or scripts. */
    public @Nullable ServerPlayer getPlayer() {
        return event.getActor();
    }

    public MinecraftServer getServer() {
        return event.getServer();
    }
}
