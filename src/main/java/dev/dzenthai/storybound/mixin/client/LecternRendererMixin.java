package dev.dzenthai.storybound.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.dzenthai.storybound.client.StoryboundClient;
import dev.dzenthai.storybound.module.document.DocumentRegistries;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.blockentity.LecternRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LecternRenderer.class)
public final class LecternRendererMixin {
    @Inject(method = "render(Lnet/minecraft/world/level/block/entity/LecternBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V", at = @At("HEAD"), cancellable = true)
    private void storybound$renderBook(LecternBlockEntity blockEntity, float partialTick, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, int packedOverlay, CallbackInfo ci) {
        BlockState state = blockEntity.getBlockState();
        if (!state.getValue(LecternBlock.HAS_BOOK) || !blockEntity.getBook().is(DocumentRegistries.DOCUMENT_ITEM.get())) {
            return;
        }
        poseStack.pushPose();
        poseStack.translate(0.5F, 1.0625F, 0.5F);
        float yRot = state.getValue(LecternBlock.FACING).getClockWise().toYRot();
        poseStack.mulPose(Axis.YP.rotationDegrees(-yRot));
        poseStack.mulPose(Axis.ZP.rotationDegrees(67.5F));
        poseStack.translate(0.0F, -0.125F, 0.0F);
        poseStack.mulPose(Axis.XP.rotationDegrees(90.0F));
        poseStack.translate(-6.0F / 16.0F, -2.0F / 16.0F, -8.0F / 16.0F);
        Minecraft minecraft = Minecraft.getInstance();
        BakedModel model = minecraft.getModelManager().getModel(StoryboundClient.OPEN_BOOK_MODEL);
        VertexConsumer buffer = bufferSource.getBuffer(Sheets.cutoutBlockSheet());
        minecraft.getBlockRenderer().getModelRenderer().renderModel(
                poseStack.last(), buffer, null, model, 1.0F, 1.0F, 1.0F, packedLight, packedOverlay, ModelData.EMPTY, null);
        poseStack.popPose();
        ci.cancel();
    }
}

