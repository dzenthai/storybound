package dev.dzenthai.storybound.client.dev;

import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.InputEvent;
import org.jetbrains.annotations.Nullable;

public final class DevWorldPicker {
    private static @Nullable String promptText;
    private static @Nullable Consumer<Entity> entityCallback;
    private static @Nullable BiConsumer<BlockPos, ResourceLocation> blockCallback;
    private static int cooldownTicks;

    private DevWorldPicker() {}

    public static boolean isActive() {
        return entityCallback != null || blockCallback != null;
    }

    public static void startEntityPick(String prompt, Consumer<Entity> onEntityPicked) {
        promptText = prompt;
        entityCallback = onEntityPicked;
        blockCallback = null;
        cooldownTicks = 4;
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(null);
    }

    public static void startBlockPick(String prompt, BiConsumer<BlockPos, ResourceLocation> onBlockPicked) {
        promptText = prompt;
        entityCallback = null;
        blockCallback = onBlockPicked;
        cooldownTicks = 4;
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(null);
    }

    public static void startEntityOrBlockPick(String prompt, Consumer<Entity> onEntityPicked, BiConsumer<BlockPos, ResourceLocation> onBlockPicked) {
        promptText = prompt;
        entityCallback = onEntityPicked;
        blockCallback = onBlockPicked;
        cooldownTicks = 4;
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(null);
    }

    public static void clear() {
        promptText = null;
        entityCallback = null;
        blockCallback = null;
        cooldownTicks = 0;
    }

    public static void tick() {
        if (!isActive()) {
            return;
        }
        if (cooldownTicks > 0) {
            cooldownTicks--;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            clear();
            return;
        }
        if (mc.screen instanceof PauseScreen) {
            clear();
            mc.setScreen(new DevModeScreen(null));
        }
    }

    public static void onInteractionTriggered(InputEvent.InteractionKeyMappingTriggered event) {
        if (!isActive()) {
            return;
        }
        event.setCanceled(true);
        event.setSwingHand(false);
        if (cooldownTicks > 0) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            clear();
            return;
        }

        if (entityCallback != null) {
            Entity target = raycastEntity(mc, 48.0);
            if (target != null) {
                Consumer<Entity> cb = entityCallback;
                clear();
                cb.accept(target);
                mc.execute(() -> mc.setScreen(new DevModeScreen(null)));
                return;
            }
        }

        if (blockCallback != null) {
            BlockPos pos = raycastBlock(mc, 64.0);
            if (pos != null) {
                BiConsumer<BlockPos, ResourceLocation> cb = blockCallback;
                ResourceLocation dim = mc.level.dimension().location();
                clear();
                cb.accept(pos, dim);
                mc.execute(() -> mc.setScreen(new DevModeScreen(null)));
            }
        }
    }

    private static @Nullable Entity raycastEntity(Minecraft mc, double range) {
        if (mc.hitResult instanceof EntityHitResult ehr) {
            return ehr.getEntity();
        }
        Entity camera = mc.getCameraEntity();
        if (camera == null) {
            return null;
        }
        Vec3 eye = camera.getEyePosition(1.0f);
        Vec3 look = camera.getViewVector(1.0f);
        Vec3 end = eye.add(look.scale(range));
        AABB box = camera.getBoundingBox().expandTowards(look.scale(range)).inflate(1.0);
        EntityHitResult result = ProjectileUtil.getEntityHitResult(
                camera, eye, end, box, e -> !e.isSpectator() && e.isPickable(), range * range);
        return result != null ? result.getEntity() : null;
    }

    private static @Nullable BlockPos raycastBlock(Minecraft mc, double range) {
        Entity camera = mc.getCameraEntity();
        if (camera == null || mc.level == null) {
            return null;
        }
        Vec3 eye = camera.getEyePosition(1.0f);
        Vec3 look = camera.getViewVector(1.0f);
        Vec3 end = eye.add(look.scale(range));
        BlockHitResult hit = mc.level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, camera));
        if (hit.getType() == HitResult.Type.BLOCK) {
            return hit.getBlockPos().above();
        }
        return mc.player != null ? mc.player.blockPosition() : null;
    }

    public static void renderHud(GuiGraphics graphics, DeltaTracker deltaTracker) {
        if (!isActive() || promptText == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        int w = graphics.guiWidth();
        int h = graphics.guiHeight();

        Entity hoveredEntity = entityCallback != null ? raycastEntity(mc, 48.0) : null;
        BlockPos hoveredBlock = hoveredEntity == null && blockCallback != null ? raycastBlock(mc, 64.0) : null;

        String line1 = "🎯 World Picker Mode: " + promptText;
        String line2;
        int line2Color;
        if (hoveredEntity != null) {
            String typeId = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(hoveredEntity.getType()).toString();
            line2 = "Targeting mob: " + hoveredEntity.getName().getString() + " (" + typeId + ") — Click LMB / RMB!";
            line2Color = 0xFF69F0AE;
        } else if (hoveredBlock != null) {
            line2 = "Targeting pos: [" + hoveredBlock.getX() + ", " + hoveredBlock.getY() + ", " + hoveredBlock.getZ() + "] — Click LMB / RMB!";
            line2Color = 0xFF4FC3F7;
        } else {
            line2 = "Aim crosshair at target and click LMB / RMB (ESC to cancel)";
            line2Color = 0xFFFFD54F;
        }

        int boxW = Math.max(font.width(line1), font.width(line2)) + 24;
        int boxX = (w - boxW) / 2;
        int boxY = 18;
        graphics.fill(boxX - 1, boxY - 1, boxX + boxW + 1, boxY + 31, 0xFFE8C66A);
        graphics.fill(boxX, boxY, boxX + boxW, boxY + 30, 0xEE16191F);
        graphics.drawString(font, line1, (w - font.width(line1)) / 2, boxY + 5, 0xFFF5F5F5, false);
        graphics.drawString(font, line2, (w - font.width(line2)) / 2, boxY + 17, line2Color, false);

        int cx = w / 2;
        int cy = h / 2;
        int ringCol = hoveredEntity != null ? 0xFF69F0AE : hoveredBlock != null ? 0xFF4FC3F7 : 0xFFE8C66A;
        graphics.fill(cx - 8, cy - 8, cx - 3, cy - 7, ringCol);
        graphics.fill(cx - 8, cy - 8, cx - 7, cy - 3, ringCol);
        graphics.fill(cx + 4, cy - 8, cx + 9, cy - 7, ringCol);
        graphics.fill(cx + 8, cy - 8, cx + 9, cy - 3, ringCol);
        graphics.fill(cx - 8, cy + 8, cx - 3, cy + 9, ringCol);
        graphics.fill(cx - 8, cy + 4, cx - 7, cy + 9, ringCol);
        graphics.fill(cx + 4, cy + 8, cx + 9, cy + 9, ringCol);
        graphics.fill(cx + 8, cy + 4, cx + 9, cy + 9, ringCol);
    }
}

