package dev.dzenthai.storybound.mixin;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * An NPC that does not react to a player's hit forgets it was hurt: a villager's brain reads the last damage for two
 * seconds after a hit and panics from it, which nothing else turns off (see NpcReactions).
 */
@Mixin(LivingEntity.class)
public interface LivingEntityAccessor {
    @Accessor("lastDamageSource")
    void storybound$setLastDamageSource(@Nullable DamageSource source);
}
