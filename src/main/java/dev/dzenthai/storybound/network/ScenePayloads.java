package dev.dzenthai.storybound.network;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.client.scene.ClientScene;
import io.netty.buffer.ByteBuf;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** A scene on the client: its shots worked out on the server, its lines as they come, its end. */
public final class ScenePayloads {
    private ScenePayloads() {}

    static final StreamCodec<ByteBuf, Vec3> VEC3 = StreamCodec.of((buf, vec) -> {
        buf.writeDouble(vec.x);
        buf.writeDouble(vec.y);
        buf.writeDouble(vec.z);
    }, buf -> new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()));

    /** How a shot's camera looks: at a point, at an entity (followed as it moves; the point while it is not there), along its way. */
    public static final int LOOK_POINT = 0;
    public static final int LOOK_ENTITY = 1;
    public static final int LOOK_PATH = 2;

    /**
     * One shot as the client flies it: absolute points, {@code start} and {@code ticks} on the scene's clock, the ease
     * and the transition by their ordinal.
     */
    public record Shot(Vec3 from, Vec3 to, int look, Vec3 lookPoint, int lookEntity, int start, int ticks, int ease, int transition) {
        public static final StreamCodec<ByteBuf, Shot> STREAM_CODEC = StreamCodec.of((buf, shot) -> {
            VEC3.encode(buf, shot.from);
            VEC3.encode(buf, shot.to);
            buf.writeByte(shot.look);
            VEC3.encode(buf, shot.lookPoint);
            ByteBufCodecs.VAR_INT.encode(buf, shot.lookEntity);
            ByteBufCodecs.VAR_INT.encode(buf, shot.start);
            ByteBufCodecs.VAR_INT.encode(buf, shot.ticks);
            buf.writeByte(shot.ease);
            buf.writeByte(shot.transition);
        }, buf -> new Shot(VEC3.decode(buf), VEC3.decode(buf), buf.readByte(), VEC3.decode(buf), ByteBufCodecs.VAR_INT.decode(buf),
                ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_INT.decode(buf), buf.readByte(), buf.readByte()));

        /** The same shot at its place on the scene's clock. */
        public Shot withTime(int at, int length) {
            return new Shot(from, to, look, lookPoint, lookEntity, at, length, ease, transition);
        }
    }

    /** A scene starts: its shots, end to end; with {@code letterbox} the black bars. */
    public record Play(int session, boolean letterbox, List<Shot> shots) implements CustomPacketPayload {
        public static final Type<Play> TYPE = new Type<>(Storybound.id("scene_play"));
        public static final StreamCodec<ByteBuf, Play> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Play::session,
                ByteBufCodecs.BOOL, Play::letterbox,
                Shot.STREAM_CODEC.apply(ByteBufCodecs.list()), Play::shots,
                Play::new);

        @Override
        public Type<Play> type() {
            return TYPE;
        }

        public static void handleOnClient(Play payload, IPayloadContext context) {
            ClientScene.play(payload.session(), payload.letterbox(), payload.shots());
        }
    }

    /** A line of the scene in subtitles, for {@code ticks}. */
    public record Line(int session, Optional<Component> speaker, Component text, int ticks) implements CustomPacketPayload {
        public static final Type<Line> TYPE = new Type<>(Storybound.id("scene_line"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Line> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Line::session,
                ByteBufCodecs.optional(ComponentSerialization.STREAM_CODEC), Line::speaker,
                ComponentSerialization.STREAM_CODEC, Line::text,
                ByteBufCodecs.VAR_INT, Line::ticks,
                Line::new);

        @Override
        public Type<Line> type() {
            return TYPE;
        }

        public static void handleOnClient(Line payload, IPayloadContext context) {
            ClientScene.line(payload.session(), payload.speaker().orElse(null), payload.text(), payload.ticks());
        }
    }

    /** The scene is over (or cut short): the camera, the controls and the HUD come back. */
    public record End(int session) implements CustomPacketPayload {
        public static final Type<End> TYPE = new Type<>(Storybound.id("scene_end"));
        public static final StreamCodec<ByteBuf, End> STREAM_CODEC = ByteBufCodecs.VAR_INT.map(End::new, End::session);

        @Override
        public Type<End> type() {
            return TYPE;
        }

        public static void handleOnClient(End payload, IPayloadContext context) {
            ClientScene.end(payload.session());
        }
    }
}
