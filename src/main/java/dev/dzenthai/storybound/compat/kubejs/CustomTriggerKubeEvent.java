package dev.dzenthai.storybound.compat.kubejs;

import dev.latvian.mods.kubejs.player.KubePlayerEvent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

public class CustomTriggerKubeEvent implements KubePlayerEvent {
    private final ServerPlayer player;
    private final ResourceLocation id;

    CustomTriggerKubeEvent(ServerPlayer player, ResourceLocation id) {
        this.player = player;
        this.id = id;
    }

    @Override
    public ServerPlayer getEntity() {
        return player;
    }

    public ResourceLocation getId() {
        return id;
    }
}
