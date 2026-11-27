package dev.dzenthai.storybound.module.dialogue;

import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import java.util.List;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.StringRepresentable;

/**
 * What the dialogue camera may do for one line or choice. The server only says whether the camera is allowed and
 * which shot the author asked for; the client frames it, because only it knows where walls and entities are drawn.
 *
 * @param enabled  the server config, the dialogue and its presentation all allow the camera
 * @param partner  the active partner NPC entity id (speaking NPC, addressee, or bound participant), or -1
 * @param listener the entity id the speaker is addressing (the player or another NPC), or -1 to default
 * @param cast     entity ids of all live participants currently in the conversation (for {@link Shot#GROUP_SHOT})
 * @param look     what the line looks at instead of the speakers ({@code look_at}), or {@link Look#NONE}
 */
public record CameraCue(boolean enabled, Shot shot, Transition transition, int partner, int listener, List<Integer> cast, Look look) {
    public static final CameraCue OFF = new CameraCue(false, Shot.AUTO, Transition.CUT, -1, -1, List.of(), Look.NONE);

    public CameraCue(boolean enabled, Shot shot, Transition transition, int partner, Look look) {
        this(enabled, shot, transition, partner, -1, List.of(), look);
    }

    /**
     * A line's {@code look_at} as found on the server: an entity the camera follows, or a fixed point.
     *
     * @param entity the entity's network id, or -1
     */
    public record Look(int entity, java.util.Optional<net.minecraft.world.phys.Vec3> pos) {
        public static final Look NONE = new Look(-1, java.util.Optional.empty());
        private static final StreamCodec<ByteBuf, net.minecraft.world.phys.Vec3> VEC = StreamCodec.composite(
                ByteBufCodecs.DOUBLE, net.minecraft.world.phys.Vec3::x,
                ByteBufCodecs.DOUBLE, net.minecraft.world.phys.Vec3::y,
                ByteBufCodecs.DOUBLE, net.minecraft.world.phys.Vec3::z,
                net.minecraft.world.phys.Vec3::new);
        public static final StreamCodec<ByteBuf, Look> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Look::entity,
                ByteBufCodecs.optional(VEC), Look::pos,
                Look::new);

        public boolean any() {
            return entity >= 0 || pos.isPresent();
        }
    }

    public static final StreamCodec<ByteBuf, CameraCue> STREAM_CODEC = StreamCodec.ofMember(CameraCue::write, CameraCue::read);

    private static CameraCue read(ByteBuf buf) {
        boolean enabled = ByteBufCodecs.BOOL.decode(buf);
        Shot shot = Shot.values()[ByteBufCodecs.VAR_INT.decode(buf)];
        Transition transition = Transition.values()[ByteBufCodecs.VAR_INT.decode(buf)];
        int partner = ByteBufCodecs.VAR_INT.decode(buf);
        int listener = ByteBufCodecs.VAR_INT.decode(buf);
        List<Integer> cast = ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()).decode(buf);
        Look look = Look.STREAM_CODEC.decode(buf);
        return new CameraCue(enabled, shot, transition, partner, listener, cast, look);
    }

    private void write(ByteBuf buf) {
        ByteBufCodecs.BOOL.encode(buf, enabled);
        ByteBufCodecs.VAR_INT.encode(buf, shot.ordinal());
        ByteBufCodecs.VAR_INT.encode(buf, transition.ordinal());
        ByteBufCodecs.VAR_INT.encode(buf, partner);
        ByteBufCodecs.VAR_INT.encode(buf, listener);
        ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()).encode(buf, cast);
        Look.STREAM_CODEC.encode(buf, look);
    }

    public enum Shot implements StringRepresentable {
        /** Shot-reverse-shot: over the listener's shoulder at whoever speaks; a choice shows the player. */
        AUTO("auto"),
        /** Speaker and listener from the side. */
        TWO_SHOT("two_shot"),
        /** Wide shot framing all live participants together. */
        GROUP_SHOT("group_shot"),
        /** The speaker's face from the front. */
        CLOSE_UP("close_up"),
        /** No camera movement for this line. */
        FIRST_PERSON("first_person");

        public static final Codec<Shot> CODEC = StringRepresentable.fromEnum(Shot::values);

        private final String name;

        Shot(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    public enum Transition implements StringRepresentable {
        CUT("cut"),
        /** Glides from the previous shot over a moment. */
        EASE("ease");

        public static final Codec<Transition> CODEC = StringRepresentable.fromEnum(Transition::values);

        private final String name;

        Transition(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }
}
