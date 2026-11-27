package dev.dzenthai.storybound.mixin;

import dev.dzenthai.storybound.module.world.WorldRules;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.FurnaceResultSlot;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Prevents taking craft-locked items out of crafting, furnace, smithing, stonecutter, anvil, loom, grindstone,
 * and cartography result slots until the player passes the rule's unlock conditions.
 */
@Mixin(Slot.class)
public abstract class SlotMixin {
    @Shadow
    @Final
    public Container container;

    @Shadow
    public abstract ItemStack getItem();

    @Inject(method = "mayPickup", at = @At("HEAD"), cancellable = true)
    private void storybound$blockLockedCraft(Player player, CallbackInfoReturnable<Boolean> cir) {
        if ((((Object) this instanceof ResultSlot)
                || ((Object) this instanceof FurnaceResultSlot)
                || (container instanceof ResultContainer))
                && WorldRules.isCraftBlocked(player, getItem())) {
            cir.setReturnValue(false);
        }
    }
}

