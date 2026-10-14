package dev.dzenthai.storybound.core.state;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

/** A known quest's state. An unknown quest has no record at all. */
public enum QuestStatus implements StringRepresentable {
    ACTIVE("active"),
    COMPLETED("completed"),
    FAILED("failed");

    public static final Codec<QuestStatus> CODEC = StringRepresentable.fromEnum(QuestStatus::values);

    private final String name;

    QuestStatus(String name) {
        this.name = name;
    }

    public boolean isFinished() {
        return this != ACTIVE;
    }

    @Override
    public String getSerializedName() {
        return name;
    }
}
