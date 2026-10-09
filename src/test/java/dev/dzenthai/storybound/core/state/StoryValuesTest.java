package dev.dzenthai.storybound.core.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.List;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

class StoryValuesTest {
    private static final List<StoryValue> SAMPLES = List.of(
            new StoryValue.Bool(true), new StoryValue.Int(-42), new StoryValue.Str("Каэр Морхен"));

    @Test
    void nbtRoundTripKeepsType() {
        for (StoryValue value : SAMPLES) {
            Tag tag = StoryValues.CODEC.encodeStart(NbtOps.INSTANCE, value).getOrThrow();
            assertEquals(value, StoryValues.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow());
        }
    }

    @Test
    void networkRoundTrip() {
        ByteBuf buf = Unpooled.buffer();
        SAMPLES.forEach(value -> StoryValues.STREAM_CODEC.encode(buf, value));

        for (StoryValue value : SAMPLES) {
            assertEquals(value, StoryValues.STREAM_CODEC.decode(buf));
        }
    }

    @Test
    void rejectsValueOfWrongType() {
        assertTrue(StoryValues.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("{ \"type\": \"int\", \"value\": \"x\" }")).isError());
    }
}
