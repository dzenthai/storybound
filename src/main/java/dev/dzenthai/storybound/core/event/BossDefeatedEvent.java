package dev.dzenthai.storybound.core.event;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.Event;
import org.jetbrains.annotations.Nullable;

/** A boss died and this player shares the victory. Posted on {@code NeoForge.EVENT_BUS}, once per credited player. */
public final class BossDefeatedEvent extends Event {
    private final ServerPlayer player;
    private final ResourceLocation boss;
    private final @Nullable LivingEntity entity;
    private final boolean first;
    private final long fightTicks;

    public BossDefeatedEvent(ServerPlayer player, ResourceLocation boss, @Nullable LivingEntity entity, boolean first, long fightTicks) {
        this.player = player;
        this.boss = boss;
        this.entity = entity;
        this.first = first;
        this.fightTicks = fightTicks;
    }

    public ServerPlayer getPlayer() {
        return player;
    }

    public ResourceLocation getBoss() {
        return boss;
    }

    /** The boss; null for a victory credited to a player who fought it and left before the end, on their joining. */
    public @Nullable LivingEntity getEntity() {
        return entity;
    }

    /** The player's first victory over this boss. */
    public boolean isFirst() {
        return first;
    }

    /** From the first player hit to the death, in ticks. */
    public long getFightTicks() {
        return fightTicks;
    }
}
