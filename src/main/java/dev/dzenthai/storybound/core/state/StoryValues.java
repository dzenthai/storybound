package dev.dzenthai.storybound.core.state;

import com.google.gson.JsonPrimitive;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/** Codecs for a value of any type; the type is stored next to the value. */
public final class StoryValues {
    /** {@code { "type": "int", "value": 5 }}: unambiguous in both JSON and NBT, which has no booleans. */
    public static final Codec<StoryValue> CODEC = StoryValueType.CODEC.dispatch("type", StoryValue::type, type -> type.valueCodec().fieldOf("value"));
    public static final StreamCodec<ByteBuf, StoryValue> STREAM_CODEC = StreamCodec.of(StoryValues::write, StoryValues::read);

    /** A plain literal for authoring ({@code true}, {@code 5}, {@code "north"}); the JSON type picks the value type. */
    public static final Codec<StoryValue> LOOSE_CODEC = new Codec<>() {
        @Override
        public <T> DataResult<Pair<StoryValue, T>> decode(DynamicOps<T> ops, T input) {
            return decodeLoose(ops, input).map(value -> Pair.of(value, ops.empty()));
        }

        @Override
        public <T> DataResult<T> encode(StoryValue value, DynamicOps<T> ops, T prefix) {
            return DataResult.success(switch (value) {
                case StoryValue.Bool bool -> ops.createBoolean(bool.value());
                case StoryValue.Int integer -> ops.createInt(integer.value());
                case StoryValue.Str string -> ops.createString(string.value());
            });
        }
    };

    private StoryValues() {}

    private static <T> DataResult<StoryValue> decodeLoose(DynamicOps<T> ops, T input) {
        // JsonOps silently converts numbers and booleans into each other, so look at the JSON type itself.
        if (input instanceof JsonPrimitive primitive) {
            if (primitive.isBoolean()) {
                return DataResult.success(new StoryValue.Bool(primitive.getAsBoolean()));
            }
            if (primitive.isNumber()) {
                double number = primitive.getAsDouble();
                if (number != Math.rint(number) || number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
                    return DataResult.error(() -> "Story values must be whole numbers: " + primitive);
                }
                return DataResult.success(new StoryValue.Int((int) number));
            }
            return DataResult.success(new StoryValue.Str(primitive.getAsString()));
        }
        // Other formats, e.g. NBT without booleans: a number, otherwise a string.
        DataResult<StoryValue> number = ops.getNumberValue(input).map(value -> new StoryValue.Int(value.intValue()));
        return number.isSuccess() ? number : ops.getStringValue(input).map(StoryValue.Str::new);
    }

    public static String display(StoryValue value) {
        return switch (value) {
            case StoryValue.Bool bool -> String.valueOf(bool.value());
            case StoryValue.Int integer -> String.valueOf(integer.value());
            case StoryValue.Str string -> string.value();
        };
    }

    private static void write(ByteBuf buf, StoryValue value) {
        buf.writeByte(value.type().ordinal());
        switch (value) {
            case StoryValue.Bool bool -> ByteBufCodecs.BOOL.encode(buf, bool.value());
            case StoryValue.Int integer -> ByteBufCodecs.VAR_INT.encode(buf, integer.value());
            case StoryValue.Str string -> ByteBufCodecs.STRING_UTF8.encode(buf, string.value());
        }
    }

    private static StoryValue read(ByteBuf buf) {
        int ordinal = buf.readByte();
        StoryValueType[] types = StoryValueType.values();
        if (ordinal < 0 || ordinal >= types.length) {
            throw new DecoderException("Unknown story value type: " + ordinal);
        }
        return switch (types[ordinal]) {
            case BOOL -> new StoryValue.Bool(ByteBufCodecs.BOOL.decode(buf));
            case INT -> new StoryValue.Int(ByteBufCodecs.VAR_INT.decode(buf));
            case STRING -> new StoryValue.Str(ByteBufCodecs.STRING_UTF8.decode(buf));
        };
    }
}
