package dev.dzenthai.storybound.core.state;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import java.util.function.Function;
import net.minecraft.util.StringRepresentable;

public enum StoryValueType implements StringRepresentable {
    BOOL("bool", typed(Codec.BOOL, StoryValue.Bool::new, StoryValue.Bool.class, StoryValue.Bool::value), new StoryValue.Bool(false)),
    INT("int", typed(Codec.INT, StoryValue.Int::new, StoryValue.Int.class, StoryValue.Int::value), new StoryValue.Int(0)),
    STRING("string", typed(Codec.STRING, StoryValue.Str::new, StoryValue.Str.class, StoryValue.Str::value), new StoryValue.Str(""));

    public static final Codec<StoryValueType> CODEC = StringRepresentable.fromEnum(StoryValueType::values);

    private final String name;
    private final Codec<StoryValue> valueCodec;
    private final StoryValue zero;

    StoryValueType(String name, Codec<StoryValue> valueCodec, StoryValue zero) {
        this.name = name;
        this.valueCodec = valueCodec;
        this.zero = zero;
    }

    /** Codec for values of exactly this type: INT rejects the string {@code "5"}. */
    public Codec<StoryValue> valueCodec() {
        return valueCodec;
    }

    /** Default value: false, 0 or an empty string. */
    public StoryValue zero() {
        return zero;
    }

    @Override
    public String getSerializedName() {
        return name;
    }

    private static <A, V extends StoryValue> Codec<StoryValue> typed(Codec<A> raw, Function<A, V> wrap, Class<V> type, Function<V, A> unwrap) {
        return raw.flatComapMap(wrap::apply, value -> type.isInstance(value)
                ? DataResult.success(unwrap.apply(type.cast(value)))
                : DataResult.error(() -> "Expected " + type.getSimpleName() + " value, got " + value));
    }
}
