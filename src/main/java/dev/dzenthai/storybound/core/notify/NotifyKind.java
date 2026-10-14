package dev.dzenthai.storybound.core.notify;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

/** What a toast is about; picks its header and sound on the client. */
public enum NotifyKind implements StringRepresentable {
    INFO("info"),
    QUEST_STARTED("quest_started"),
    QUEST_UPDATED("quest_updated"),
    QUEST_COMPLETED("quest_completed"),
    QUEST_FAILED("quest_failed"),
    CODEX_NEW("codex_new"),
    CODEX_UPDATED("codex_updated"),
    BOSS_ENCOUNTERED("boss_encountered"),
    BOSS_DEFEATED("boss_defeated");

    public static final Codec<NotifyKind> CODEC = StringRepresentable.fromEnum(NotifyKind::values);

    private final String name;

    NotifyKind(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }
}
