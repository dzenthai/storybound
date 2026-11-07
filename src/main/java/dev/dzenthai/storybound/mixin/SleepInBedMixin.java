package dev.dzenthai.storybound.mixin;

import dev.dzenthai.storybound.module.dialogue.NpcBindings;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.SleepInBed;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A villager talking (in a dialogue, or its phrase still over its head) does not go to bed until it is done (November 7,
 * 2026; before, it lay down in the middle of a conversation next to its bed). Vanilla tries again a moment later, so it
 * lies down once the talk is over.
 */
@Mixin(SleepInBed.class)
public abstract class SleepInBedMixin {
    @Inject(method = "checkExtraStartConditions", at = @At("HEAD"), cancellable = true)
    private void storybound$notWhileTalking(ServerLevel level, LivingEntity owner, CallbackInfoReturnable<Boolean> cir) {
        if (NpcBindings.keepsAwake(owner)) {
            cir.setReturnValue(false);
        }
    }
}
