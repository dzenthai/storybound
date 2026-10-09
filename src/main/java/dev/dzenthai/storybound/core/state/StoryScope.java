package dev.dzenthai.storybound.core.state;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

/** Where a value is stored: on the player, on their party or on the whole world. */
public enum StoryScope implements StringRepresentable {
    PLAYER("player"),
    PARTY("party"),
    WORLD("world");

    public static final Codec<StoryScope> CODEC = StringRepresentable.fromEnum(StoryScope::values);

    private final String name;

    StoryScope(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }
}
