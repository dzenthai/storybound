package dev.dzenthai.storybound.core.logic;

import dev.dzenthai.storybound.config.StoryboundConfig;
import dev.dzenthai.storybound.core.state.StoryScope;
import dev.dzenthai.storybound.core.state.StoryState;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/** Who else gets credit for a kill: the killer's party members near the victim. */
public final class KillCredit {
    private KillCredit() {}

    /** Online party members of {@code player} within {@code detection.killCreditRadius} of the victim, without {@code player}. */
    public static List<ServerPlayer> partyNear(ServerPlayer player, Entity victim) {
        double radius = StoryboundConfig.COMMON.killCreditRadius.get();
        return StoryState.onlinePlayersOf(player.server, StoryScope.PARTY, StoryState.owner(player, StoryScope.PARTY)).stream()
                .filter(member -> member != player && member.isAlive() && member.level() == victim.level()
                        && member.distanceToSqr(victim) <= radius * radius)
                .toList();
    }
}
