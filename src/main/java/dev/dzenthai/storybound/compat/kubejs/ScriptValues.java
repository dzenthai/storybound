package dev.dzenthai.storybound.compat.kubejs;

import dev.dzenthai.storybound.core.state.StoryScope;
import dev.dzenthai.storybound.core.state.StoryValue;
import org.jetbrains.annotations.Nullable;

/** Converts between story values and what JavaScript sees: boolean, number and string. */
final class ScriptValues {
    private ScriptValues() {}

    static @Nullable Object toScript(@Nullable StoryValue value) {
        if (value == null) {
            return null;
        }
        return switch (value) {
            case StoryValue.Bool bool -> bool.value();
            case StoryValue.Int integer -> integer.value();
            case StoryValue.Str string -> string.value();
        };
    }

    static StoryValue fromScript(Object value) {
        if (value instanceof Boolean bool) {
            return new StoryValue.Bool(bool);
        }
        if (value instanceof Number number) {
            double asDouble = number.doubleValue();
            if (asDouble != Math.rint(asDouble) || asDouble < Integer.MIN_VALUE || asDouble > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Story values must be whole numbers, got " + number);
            }
            return new StoryValue.Int(number.intValue());
        }
        if (value instanceof CharSequence text) {
            return new StoryValue.Str(text.toString());
        }
        throw new IllegalArgumentException("Story values must be a boolean, a number or a string, got " + value);
    }

    static StoryScope scope(String name) {
        for (StoryScope scope : StoryScope.values()) {
            if (scope.getSerializedName().equals(name)) {
                return scope;
            }
        }
        throw new IllegalArgumentException("Unknown scope '" + name + "', expected player, party or world");
    }
}
