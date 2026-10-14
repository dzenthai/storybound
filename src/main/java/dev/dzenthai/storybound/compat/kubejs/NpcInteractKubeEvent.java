package dev.dzenthai.storybound.compat.kubejs;

import dev.dzenthai.storybound.core.event.NpcInteractEvent;
import dev.latvian.mods.kubejs.event.KubeEvent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/** A right-click on an NPC bound with {@code "custom": true}; the script decides, e.g. which dialogue to start. */
public class NpcInteractKubeEvent implements KubeEvent {
    private final NpcInteractEvent event;

    NpcInteractKubeEvent(NpcInteractEvent event) {
        this.event = event;
    }

    public ServerPlayer getPlayer() {
        return event.getPlayer();
    }

    public Entity getEntity() {
        return event.getEntity();
    }

    public ResourceLocation getBinding() {
        return event.getBinding();
    }
}
