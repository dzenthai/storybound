package dev.dzenthai.storybound.core.state;

/** Value of a story flag or variable. */
public sealed interface StoryValue {
    StoryValueType type();

    record Bool(boolean value) implements StoryValue {
        @Override
        public StoryValueType type() {
            return StoryValueType.BOOL;
        }
    }

    record Int(int value) implements StoryValue {
        @Override
        public StoryValueType type() {
            return StoryValueType.INT;
        }
    }

    record Str(String value) implements StoryValue {
        @Override
        public StoryValueType type() {
            return StoryValueType.STRING;
        }
    }
}
