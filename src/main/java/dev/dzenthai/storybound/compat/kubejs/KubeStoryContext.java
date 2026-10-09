package dev.dzenthai.storybound.compat.kubejs;

import dev.dzenthai.storybound.core.logic.StoryContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

/** {@link StoryContext} with bean getters, so scripts can write {@code ctx.player} and {@code ctx.level}. */
public final class KubeStoryContext {
    private final StoryContext context;

    KubeStoryContext(StoryContext context) {
        this.context = context;
    }

    public ServerPlayer getPlayer() {
        return context.player();
    }

    /** The killed mob, the interacted NPC or the dialogue partner, if any. */
    public @Nullable Entity getEntity() {
        return context.entity();
    }

    public ServerLevel getLevel() {
        return context.level();
    }

    public MinecraftServer getServer() {
        return context.server();
    }
}
