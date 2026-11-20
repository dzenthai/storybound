package dev.dzenthai.storybound.module.dialogue;

import dev.dzenthai.storybound.mixin.MobAccessor;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.jetbrains.annotations.Nullable;

/**
 * An NPC's voice as it speaks: a dialogue's line with no sound of its own, a phrase in a bubble. Its binding's
 * {@code voice} (a sound, a pitch), else the creature's own sound at its own pitch (a villager's hum, a piglin's grunt);
 * a creature with none (an iron golem) says its lines in silence.
 */
public final class NpcVoice {
    private NpcVoice() {}

    private record Voice(SoundEvent event, float pitch) {}

    private static @Nullable Voice of(Entity speaker) {
        NpcBinding.Voice written = NpcBindings.find(speaker).flatMap(found -> found.binding().voice()).orElse(null);
        float natural = speaker instanceof LivingEntity living ? living.getVoicePitch() : 1.0f;
        if (written != null && written.sound().isPresent()) {
            return new Voice(SoundEvent.createVariableRangeEvent(written.sound().get()), written.pitch());
        }
        SoundEvent own = speaker instanceof Mob mob ? ((MobAccessor) mob).storybound$ambientSound() : null;
        if (own == null) {
            return null;
        }
        return new Voice(own, written != null ? written.pitch() : natural);
    }

    /** The speaker says it: heard by {@code only} when given, else by everyone near. */
    static void speak(Entity speaker, @Nullable ServerPlayer only) {
        Voice voice = of(speaker);
        if (voice == null) {
            return;
        }
        if (only != null) {
            only.connection.send(new ClientboundSoundPacket(Holder.direct(voice.event()), SoundSource.NEUTRAL, speaker.getX(), speaker.getEyeY(),
                    speaker.getZ(), 1.0f, voice.pitch(), speaker.getRandom().nextLong()));
        } else {
            speaker.level().playSound(null, speaker.getX(), speaker.getEyeY(), speaker.getZ(), voice.event(), SoundSource.NEUTRAL, 1.0f, voice.pitch());
        }
    }
}
