package dev.dzenthai.storybound.compat.kubejs;

import dev.dzenthai.storybound.core.event.CodexUnlockedEvent;
import dev.latvian.mods.kubejs.event.KubeEvent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/** {@code codexUnlocked}: one section of an entry opened for the player. */
public class CodexKubeEvent implements KubeEvent {
    private final CodexUnlockedEvent event;

    CodexKubeEvent(CodexUnlockedEvent event) {
        this.event = event;
    }

    public ServerPlayer getPlayer() {
        return event.getPlayer();
    }

    public ResourceLocation getEntry() {
        return event.getEntry();
    }

    public String getSection() {
        return event.getSection();
    }

    /** The entry just appeared in the Codex. */
    public boolean isNewEntry() {
        return event.isNewEntry();
    }
}
