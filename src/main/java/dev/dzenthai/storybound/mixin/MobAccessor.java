package dev.dzenthai.storybound.mixin;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Mob;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** A mob's own voice, for an NPC that talks with no voice of its binding (see NpcVoice). */
@Mixin(Mob.class)
public interface MobAccessor {
    @Invoker("getAmbientSound")
    @Nullable SoundEvent storybound$ambientSound();
}
