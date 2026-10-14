package dev.dzenthai.storybound.core.event;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.Event;

/** A section of a Codex entry opened for a player. Posted on {@code NeoForge.EVENT_BUS}, once per section. */
public final class CodexUnlockedEvent extends Event {
    private final ServerPlayer player;
    private final ResourceLocation entry;
    private final String section;
    private final boolean newEntry;

    public CodexUnlockedEvent(ServerPlayer player, ResourceLocation entry, String section, boolean newEntry) {
        this.player = player;
        this.entry = entry;
        this.section = section;
        this.newEntry = newEntry;
    }

    public ServerPlayer getPlayer() {
        return player;
    }

    public ResourceLocation getEntry() {
        return entry;
    }

    public String getSection() {
        return section;
    }

    /** The entry had no open section before: it just appeared in the Codex. */
    public boolean isNewEntry() {
        return newEntry;
    }
}
