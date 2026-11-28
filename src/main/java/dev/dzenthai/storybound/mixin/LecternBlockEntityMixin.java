package dev.dzenthai.storybound.mixin;

import dev.dzenthai.storybound.module.document.DocumentRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LecternBlockEntity.class)
public abstract class LecternBlockEntityMixin extends BlockEntity {
    @Shadow
    ItemStack book;

    @org.spongepowered.asm.mixin.Unique
    private boolean storybound$wasDocument;

    protected LecternBlockEntityMixin(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Inject(method = "hasBook", at = @At("HEAD"), cancellable = true)
    private void storybound$hasBook(CallbackInfoReturnable<Boolean> cir) {
        if (this.book.is(DocumentRegistries.DOCUMENT_ITEM.get())) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "setBook(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/player/Player;)V", at = @At("HEAD"))
    private void storybound$rememberBook(ItemStack stack, Player player, CallbackInfo ci) {
        this.storybound$wasDocument = this.book.is(DocumentRegistries.DOCUMENT_ITEM.get());
    }

    @Inject(method = "setBook(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/player/Player;)V", at = @At("TAIL"))
    private void storybound$syncBook(ItemStack stack, Player player, CallbackInfo ci) {
        if (this.level != null && !this.level.isClientSide()
                && (this.storybound$wasDocument || this.book.is(DocumentRegistries.DOCUMENT_ITEM.get()))) {
            this.level.sendBlockUpdated(this.worldPosition, this.getBlockState(), this.getBlockState(), 3);
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        // Only sync Storybound document stacks so vanilla written books do not bloat chunk/update packets.
        if (this.book.is(DocumentRegistries.DOCUMENT_ITEM.get())) {
            return this.saveCustomOnly(registries);
        }
        return new CompoundTag();
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create((LecternBlockEntity) (Object) this);
    }
}

