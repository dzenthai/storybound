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
import net.minecraft.world.phys.Vec2;
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

    static final StreamCodec<ByteBuf, Vec2> VEC2 = StreamCodec.of((buf, vec) -> {
        buf.writeFloat(vec.x);
        buf.writeFloat(vec.y);
    }, buf -> new Vec2(buf.readFloat(), buf.readFloat()));

    /** How a shot's camera looks: at a point, at an entity, along its path, or free per-point yaw/pitch rotations. */
    public static final int LOOK_POINT = 0;
    public static final int LOOK_ENTITY = 1;
    public static final int LOOK_PATH = 2;
    public static final int LOOK_FREE = 3;

    /**
     * One shot as the client flies it: absolute points ({@code from}, optional spline {@code via}, {@code to}),
     * {@code start} and {@code ticks} on the scene's clock, the ease and the transition by their ordinal, optional
     * {@code fov}/{@code fovTo} ({@code 0} for the player's own FOV / no zoom), {@code lookOffset} ({@code [right, up, forward]}),
     * horizon tilt {@code roll}/{@code rollTo} in degrees, subtle handheld {@code shake} intensity (0..1), and optional
     * world-space {@code rotations} ({@code [yaw, pitch]} per control point) for {@link #LOOK_FREE}.
     */
    public record Shot(Vec3 from, List<Vec3> via, Vec3 to, int look, Vec3 lookPoint, int lookEntity, int start, int ticks,
                       int ease, int transition, float fov, float fovTo, Vec3 lookOffset, float roll, float rollTo, float shake,
                       List<Vec2> rotations) {
        public Shot(Vec3 from, List<Vec3> via, Vec3 to, int look, Vec3 lookPoint, int lookEntity, int start, int ticks, int ease, int transition, float fov) {
            this(from, via, to, look, lookPoint, lookEntity, start, ticks, ease, transition, fov, 0f, Vec3.ZERO, 0f, 0f, 0f, List.of());
        }

        public Shot(Vec3 from, List<Vec3> via, Vec3 to, int look, Vec3 lookPoint, int lookEntity, int start, int ticks,
                    int ease, int transition, float fov, float fovTo, Vec3 lookOffset, float roll, float rollTo, float shake) {
            this(from, via, to, look, lookPoint, lookEntity, start, ticks, ease, transition, fov, fovTo, lookOffset, roll, rollTo, shake, List.of());
        }

        private static final StreamCodec<ByteBuf, List<Vec3>> VEC3_LIST = VEC3.apply(ByteBufCodecs.list());
        private static final StreamCodec<ByteBuf, List<Vec2>> VEC2_LIST = VEC2.apply(ByteBufCodecs.list());
        public static final StreamCodec<ByteBuf, Shot> STREAM_CODEC = StreamCodec.of((buf, shot) -> {
            VEC3.encode(buf, shot.from);
            VEC3_LIST.encode(buf, shot.via);
            VEC3.encode(buf, shot.to);
            buf.writeByte(shot.look);
            VEC3.encode(buf, shot.lookPoint);
            ByteBufCodecs.VAR_INT.encode(buf, shot.lookEntity);
            ByteBufCodecs.VAR_INT.encode(buf, shot.start);
            ByteBufCodecs.VAR_INT.encode(buf, shot.ticks);
            buf.writeByte(shot.ease);
            buf.writeByte(shot.transition);
            buf.writeFloat(shot.fov);
            buf.writeFloat(shot.fovTo);
            VEC3.encode(buf, shot.lookOffset);
            buf.writeFloat(shot.roll);
            buf.writeFloat(shot.rollTo);
            buf.writeFloat(shot.shake);
            VEC2_LIST.encode(buf, shot.rotations);
        }, buf -> new Shot(VEC3.decode(buf), List.copyOf(VEC3_LIST.decode(buf)), VEC3.decode(buf), buf.readByte(), VEC3.decode(buf), ByteBufCodecs.VAR_INT.decode(buf),
                ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_INT.decode(buf), buf.readByte(), buf.readByte(), buf.readFloat(), buf.readFloat(),
                VEC3.decode(buf), buf.readFloat(), buf.readFloat(), buf.readFloat(), List.copyOf(VEC2_LIST.decode(buf))));

        /** The same shot at its place on the scene's clock. */
        public Shot withTime(int at, int length) {
            return new Shot(from, via, to, look, lookPoint, lookEntity, at, length, ease, transition, fov, fovTo, lookOffset, roll, rollTo, shake, rotations);
        }
    }

    /** A scene starts: its shots, end to end; with {@code letterbox} the black bars and {@code skippable} whether Space skips it. */
    public record Play(int session, boolean letterbox, boolean skippable, List<Shot> shots) implements CustomPacketPayload {
        public static final Type<Play> TYPE = new Type<>(Storybound.id("scene_play"));
        public static final StreamCodec<ByteBuf, Play> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Play::session,
                ByteBufCodecs.BOOL, Play::letterbox,
                ByteBufCodecs.BOOL, Play::skippable,
                Shot.STREAM_CODEC.apply(ByteBufCodecs.list()), Play::shots,
                Play::new);

        @Override
        public Type<Play> type() {
            return TYPE;
        }

        public static void handleOnClient(Play payload, IPayloadContext context) {
            ClientScene.play(payload.session(), payload.letterbox(), payload.skippable(), payload.shots());
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

    /**
     * A caption of the scene: the words, where they stand ({@code position}: the ordinal of
     * {@link dev.dzenthai.storybound.module.scene.SceneDefinition.Position}), on black or over the picture, for {@code ticks}.
     */
    public record Caption(int session, Component text, int position, boolean black, int ticks) implements CustomPacketPayload {
        public static final Type<Caption> TYPE = new Type<>(Storybound.id("scene_caption"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Caption> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Caption::session,
                ComponentSerialization.STREAM_CODEC, Caption::text,
                ByteBufCodecs.VAR_INT, Caption::position,
                ByteBufCodecs.BOOL, Caption::black,
                ByteBufCodecs.VAR_INT, Caption::ticks,
                Caption::new);

        @Override
        public Type<Caption> type() {
            return TYPE;
        }

        public static void handleOnClient(Caption payload, IPayloadContext context) {
            ClientScene.caption(payload.session(), payload.text(), payload.position(), payload.black(), payload.ticks());
        }
    }

    /** A decaying camera shake impulse during the scene (horn blast, heavy footstep, explosion). */
    public record Shake(int session, float intensity, int ticks) implements CustomPacketPayload {
        public static final Type<Shake> TYPE = new Type<>(Storybound.id("scene_shake"));
        public static final StreamCodec<ByteBuf, Shake> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Shake::session,
                ByteBufCodecs.FLOAT, Shake::intensity,
                ByteBufCodecs.VAR_INT, Shake::ticks,
                Shake::new);

        @Override
        public Type<Shake> type() {
            return TYPE;
        }

        public static void handleOnClient(Shake payload, IPayloadContext context) {
            ClientScene.shake(payload.session(), payload.intensity(), payload.ticks());
        }
    }

    /** A talk of the scene begins: the client lets go of the scene's camera and screen, its clock waits. */
    public record Pause(int session) implements CustomPacketPayload {
        public static final Type<Pause> TYPE = new Type<>(Storybound.id("scene_pause"));
        public static final StreamCodec<ByteBuf, Pause> STREAM_CODEC = ByteBufCodecs.VAR_INT.map(Pause::new, Pause::session);

        @Override
        public Type<Pause> type() {
            return TYPE;
        }

        public static void handleOnClient(Pause payload, IPayloadContext context) {
            ClientScene.pause(payload.session(), true);
        }
    }

    /** The talk is over: the scene's camera, screen and clock go on. */
    public record Resume(int session) implements CustomPacketPayload {
        public static final Type<Resume> TYPE = new Type<>(Storybound.id("scene_resume"));
        public static final StreamCodec<ByteBuf, Resume> STREAM_CODEC = ByteBufCodecs.VAR_INT.map(Resume::new, Resume::session);

        @Override
        public Type<Resume> type() {
            return TYPE;
        }

        public static void handleOnClient(Resume payload, IPayloadContext context) {
            ClientScene.pause(payload.session(), false);
        }
    }

    /**
     * The editor's camera view asks where an anchor is for its author (an operator), as a scene would find it; the
     * answer comes back as {@link AnchorAnswer} with the same {@code request}.
     */
    public record AnchorQuery(int request, String anchor) implements CustomPacketPayload {
        public static final Type<AnchorQuery> TYPE = new Type<>(Storybound.id("scene_anchor_query"));
        public static final StreamCodec<ByteBuf, AnchorQuery> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, AnchorQuery::request,
                ByteBufCodecs.stringUtf8(4096), AnchorQuery::anchor,
                AnchorQuery::new);

        @Override
        public Type<AnchorQuery> type() {
            return TYPE;
        }

        public static void handleOnServer(AnchorQuery payload, IPayloadContext context) {
            if (!(context.player() instanceof net.minecraft.server.level.ServerPlayer player) || !player.hasPermissions(2)) {
                return;
            }
            dev.dzenthai.storybound.module.scene.Scenes.Found found = null;
            try {
                var anchor = dev.dzenthai.storybound.module.scene.SceneDefinition.Anchor.CODEC.parse(
                        net.minecraft.resources.RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE, player.registryAccess()),
                        com.google.gson.JsonParser.parseString(payload.anchor())).result().orElse(null);
                found = anchor != null ? dev.dzenthai.storybound.module.scene.Scenes.find(player, anchor) : null;
            } catch (RuntimeException e) {
                Storybound.LOGGER.debug("Storybound: the camera view asked for an anchor that does not read: {}", payload.anchor());
            }
            StoryboundNetwork.sendToPlayer(player, found == null ? new AnchorAnswer(payload.request(), false, Vec3.ZERO, 0, -1)
                    : new AnchorAnswer(payload.request(), true, found.pos(), found.yaw(), found.entity() != null ? found.entity().getId() : -1));
        }
    }

    /** Where an anchor is for the author: its place, its facing, the entity it is (-1 for none); {@code found} false when it is not near. */
    public record AnchorAnswer(int request, boolean found, Vec3 pos, float yaw, int entity) implements CustomPacketPayload {
        public static final Type<AnchorAnswer> TYPE = new Type<>(Storybound.id("scene_anchor_answer"));
        public static final StreamCodec<ByteBuf, AnchorAnswer> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, AnchorAnswer::request,
                ByteBufCodecs.BOOL, AnchorAnswer::found,
                VEC3, AnchorAnswer::pos,
                ByteBufCodecs.FLOAT, AnchorAnswer::yaw,
                ByteBufCodecs.VAR_INT, AnchorAnswer::entity,
                AnchorAnswer::new);

        @Override
        public Type<AnchorAnswer> type() {
            return TYPE;
        }

        public static void handleOnClient(AnchorAnswer payload, IPayloadContext context) {
            dev.dzenthai.storybound.client.dev.ScenePreview.answer(payload);
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

    /** The watcher pressed Space to skip the rest of the scene. */
    public record Skip(int session) implements CustomPacketPayload {
        public static final Type<Skip> TYPE = new Type<>(Storybound.id("scene_skip"));
        public static final StreamCodec<ByteBuf, Skip> STREAM_CODEC = ByteBufCodecs.VAR_INT.map(Skip::new, Skip::session);

        @Override
        public Type<Skip> type() {
            return TYPE;
        }

        public static void handleOnServer(Skip payload, IPayloadContext context) {
            if (context.player() instanceof net.minecraft.server.level.ServerPlayer player) {
                dev.dzenthai.storybound.module.scene.Scenes.skip(player, payload.session());
            }
        }
    }
}
