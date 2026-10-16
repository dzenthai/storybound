package dev.dzenthai.storybound.module.dialogue;

import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.StringRepresentable;

/**
 * What the dialogue camera may do for one line or choice. The server only says whether the camera is allowed and
 * which shot the author asked for; the client frames it, because only it knows where walls and entities are drawn.
 *
 * @param enabled the server config, the dialogue and its presentation all allow the camera
 * @param partner the entity the player talks to (the bound participant), or -1; the camera needs both sides
 */
public record CameraCue(boolean enabled, Shot shot, Transition transition, int partner) {
    public static final CameraCue OFF = new CameraCue(false, Shot.AUTO, Transition.CUT, -1);

    public static final StreamCodec<ByteBuf, CameraCue> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, CameraCue::enabled,
            ByteBufCodecs.idMapper(index -> Shot.values()[index], Shot::ordinal), CameraCue::shot,
            ByteBufCodecs.idMapper(index -> Transition.values()[index], Transition::ordinal), CameraCue::transition,
            ByteBufCodecs.VAR_INT, CameraCue::partner,
            CameraCue::new);

    public enum Shot implements StringRepresentable {
        /** Shot-reverse-shot: over the listener's shoulder at whoever speaks; a choice shows the player. */
        AUTO("auto"),
        /** Both speakers from the side. */
        TWO_SHOT("two_shot"),
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
