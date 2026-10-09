package dev.dzenthai.storybound.core.state;

import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/** Codecs for a value of any type; the type is stored next to the value. */
public final class StoryValues {
    /** {@code { "type": "int", "value": 5 }}: unambiguous in both JSON and NBT, which has no booleans. */
    public static final Codec<StoryValue> CODEC = StoryValueType.CODEC.dispatch("type", StoryValue::type, type -> type.valueCodec().fieldOf("value"));
    public static final StreamCodec<ByteBuf, StoryValue> STREAM_CODEC = StreamCodec.of(StoryValues::write, StoryValues::read);

    private StoryValues() {}

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
