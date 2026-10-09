package dev.dzenthai.storybound.core.event;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.Event;

/** A {@code custom} trigger was fired for a player by a command, a script or another mod. */
public final class CustomTriggerEvent extends Event {
    private final ServerPlayer player;
    private final ResourceLocation id;

    public CustomTriggerEvent(ServerPlayer player, ResourceLocation id) {
        this.player = player;
        this.id = id;
    }

    public ServerPlayer getPlayer() {
        return player;
    }

    public ResourceLocation getId() {
        return id;
    }
}
