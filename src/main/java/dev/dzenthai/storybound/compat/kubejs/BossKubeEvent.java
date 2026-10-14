package dev.dzenthai.storybound.compat.kubejs;

import dev.dzenthai.storybound.core.event.BossDefeatedEvent;
import dev.latvian.mods.kubejs.event.KubeEvent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

/** {@code bossDefeated}: posted for every player credited with the victory. */
public class BossKubeEvent implements KubeEvent {
    private final BossDefeatedEvent event;

    BossKubeEvent(BossDefeatedEvent event) {
        this.event = event;
    }

    public ServerPlayer getPlayer() {
        return event.getPlayer();
    }

    public ResourceLocation getBoss() {
        return event.getBoss();
    }

    public LivingEntity getEntity() {
        return event.getEntity();
    }

    /** The player's first victory over this boss. */
    public boolean isFirst() {
        return event.isFirst();
    }

    public double getFightSeconds() {
        return event.getFightTicks() / 20.0;
    }
}
