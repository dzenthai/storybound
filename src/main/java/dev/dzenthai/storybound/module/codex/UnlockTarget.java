package dev.dzenthai.storybound.module.codex;

import com.mojang.serialization.Codec;
import dev.dzenthai.storybound.core.state.StoryScope;
import dev.dzenthai.storybound.core.state.StoryState;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.StringRepresentable;

/** Who an {@code unlock_codex} / {@code unlock_boss_section} action opens it for. Codex and Bosses stay personal; this only copies the unlock. */
public enum UnlockTarget implements StringRepresentable {
    PLAYER("player"),
    /** Every online member of the player's party (just the player when solo). */
    PARTY("party");

    public static final Codec<UnlockTarget> CODEC = StringRepresentable.fromEnum(UnlockTarget::values);

    private final String name;

    UnlockTarget(String name) {
        this.name = name;
    }

    public List<ServerPlayer> players(ServerPlayer player) {
        return this == PLAYER ? List.of(player)
                : StoryState.onlinePlayersOf(player.server, StoryScope.PARTY, StoryState.owner(player, StoryScope.PARTY));
    }

    @Override
    public String getSerializedName() {
        return name;
    }
}
