package dev.dzenthai.storybound.core.event;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.Event;

/**
 * A player right-clicked an NPC whose binding has {@code "custom": true}; a listener (usually a KubeJS
 * script) decides what happens. A dialogue it starts with that NPC also cancels the vanilla interaction.
 */
public final class NpcInteractEvent extends Event {
    private final ServerPlayer player;
    private final Entity entity;
    private final ResourceLocation binding;

    public NpcInteractEvent(ServerPlayer player, Entity entity, ResourceLocation binding) {
        this.player = player;
        this.entity = entity;
        this.binding = binding;
    }

    public ServerPlayer getPlayer() {
        return player;
    }

    public Entity getEntity() {
        return entity;
    }

    /** The binding file's id. */
    public ResourceLocation getBinding() {
        return binding;
    }
}
