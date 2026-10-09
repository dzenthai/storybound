package dev.dzenthai.storybound.core.logic;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

/**
 * Everything a condition or action may look at. Always server-side.
 *
 * @param player the player the story logic runs for
 * @param entity the entity involved, if any: the killed mob, the interacted NPC, the dialogue partner
 */
public record StoryContext(ServerPlayer player, @Nullable Entity entity) {
    public static StoryContext of(ServerPlayer player) {
        return new StoryContext(player, null);
    }

    public ServerLevel level() {
        return player.serverLevel();
    }

    public MinecraftServer server() {
        return player.server;
    }
}
