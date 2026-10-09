package dev.dzenthai.storybound.core.logic;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

/** What an action list does when one of its actions fails, e.g. {@code take_item} without enough items. */
public enum OnFail implements StringRepresentable {
    /** Stop at the failed action. The default: a reward must not be paid if its price could not be taken. */
    ABORT("abort"),
    /** Log the failure and run the remaining actions. */
    CONTINUE("continue");

    public static final Codec<OnFail> CODEC = StringRepresentable.fromEnum(OnFail::values);

    private final String name;

    OnFail(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }
}
