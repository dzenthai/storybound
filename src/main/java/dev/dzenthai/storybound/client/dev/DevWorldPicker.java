package dev.dzenthai.storybound.client.dev;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
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

/**
 * Picking a place or an entity in the world for a field of the editor: the editor steps aside, the author aims and
 * clicks (either mouse button), and the editor comes back — the same screen, so nothing typed is lost — with the value
 * written into the field. ESC gives up and returns. The pick happens on the client, from what it sees: an entity's
 * type, uuid and name, a block's position and the dimension.
 */
public final class DevWorldPicker {
    private static final double ENTITY_RANGE = 48;
    private static final double BLOCK_RANGE = 64;
    /** Ticks after starting during which a click is ignored: the click on the editor's button is still going on. */
    private static final int COOLDOWN = 4;

    private static @Nullable Component prompt;
    private static @Nullable Consumer<Entity> onEntity;
    private static @Nullable BiConsumer<BlockPos, ResourceLocation> onBlock;
    private static @Nullable DevModeScreen returnTo;
    private static int cooldown;

    private DevWorldPicker() {}

    public static boolean isActive() {
        return onEntity != null || onBlock != null;
    }

    /** Aim at an entity; {@code picked} runs once the editor is back. */
    public static void pickEntity(DevModeScreen screen, Component what, Consumer<Entity> picked) {
        start(screen, what);
        onEntity = picked;
    }

    /** Aim at a block (the place above the face looked at); {@code picked} runs once the editor is back. */
    public static void pickBlock(DevModeScreen screen, Component what, BiConsumer<BlockPos, ResourceLocation> picked) {
        start(screen, what);
        onBlock = picked;
    }

    private static void start(DevModeScreen screen, Component what) {
        clear();
        prompt = what;
        returnTo = screen;
        cooldown = COOLDOWN;
        Minecraft.getInstance().setScreen(null);
    }

    public static void clear() {
        prompt = null;
        onEntity = null;
        onBlock = null;
        returnTo = null;
        cooldown = 0;
    }

    /** ESC opens the pause screen: give up and go back to the editor. */
    public static void tick() {
        if (!isActive()) {
            return;
        }
        if (cooldown > 0) {
            cooldown--;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            clear();
            return;
        }
        if (mc.screen instanceof PauseScreen) {
            DevModeScreen screen = returnTo;
            clear();
            mc.setScreen(screen != null ? screen : new DevModeScreen(null));
        }
    }

    public static void onInteractionTriggered(InputEvent.InteractionKeyMappingTriggered event) {
        if (!isActive()) {
            return;
        }
        // While picking, a click picks: it neither hits nor uses anything.
        event.setCanceled(true);
        event.setSwingHand(false);
        Minecraft mc = Minecraft.getInstance();
        if (cooldown > 0 || mc.player == null || mc.level == null) {
            return;
        }
        Consumer<Entity> entityPicked = onEntity;
        BiConsumer<BlockPos, ResourceLocation> blockPicked = onBlock;
        DevModeScreen screen = returnTo;
        if (entityPicked != null) {
            Entity target = aimedEntity(mc);
            if (target == null) {
                return;
            }
            clear();
            back(mc, screen, () -> entityPicked.accept(target));
        } else if (blockPicked != null) {
            BlockPos pos = aimedBlock(mc);
            if (pos == null) {
                return;
            }
            ResourceLocation dimension = mc.level.dimension().location();
            clear();
            back(mc, screen, () -> blockPicked.accept(pos, dimension));
        }
    }

    /** The editor first (its view reloads), then the value: written into what the editor shows now. */
    private static void back(Minecraft mc, @Nullable DevModeScreen screen, Runnable write) {
        mc.execute(() -> {
            mc.setScreen(screen != null ? screen : new DevModeScreen(null));
            write.run();
        });
    }

    private static @Nullable Entity aimedEntity(Minecraft mc) {
        if (mc.hitResult instanceof EntityHitResult hit) {
            return hit.getEntity();
        }
        Entity camera = mc.getCameraEntity();
        if (camera == null) {
            return null;
        }
        Vec3 eye = camera.getEyePosition(1.0f);
        Vec3 look = camera.getViewVector(1.0f);
        AABB box = camera.getBoundingBox().expandTowards(look.scale(ENTITY_RANGE)).inflate(1.0);
        EntityHitResult result = ProjectileUtil.getEntityHitResult(camera, eye, eye.add(look.scale(ENTITY_RANGE)), box,
                entity -> !entity.isSpectator() && entity.isPickable(), ENTITY_RANGE * ENTITY_RANGE);
        return result != null ? result.getEntity() : null;
    }

    private static @Nullable BlockPos aimedBlock(Minecraft mc) {
        Entity camera = mc.getCameraEntity();
        if (camera == null || mc.level == null) {
            return null;
        }
        Vec3 eye = camera.getEyePosition(1.0f);
        Vec3 end = eye.add(camera.getViewVector(1.0f).scale(BLOCK_RANGE));
        BlockHitResult hit = mc.level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, camera));
        return hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos().relative(hit.getDirection()) : null;
    }

    /** What is being picked and what is aimed at, above the crosshair, and a frame around it. */
    public static void renderHud(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Component what = prompt;
        if (!isActive() || what == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        int w = graphics.guiWidth();
        int h = graphics.guiHeight();
        Entity entity = onEntity != null ? aimedEntity(mc) : null;
        BlockPos block = onBlock != null ? aimedBlock(mc) : null;
        Component aimed;
        int color;
        if (entity != null) {
            aimed = DevUi.text("picker.entity", entity.getName(), BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
            color = DevUi.GOOD;
        } else if (block != null) {
            aimed = DevUi.text("picker.block", block.getX(), block.getY(), block.getZ());
            color = DevUi.DETAIL;
        } else {
            aimed = DevUi.text("picker.aim");
            color = DevUi.WARN;
        }
        Component title = Component.literal("🎯 ").append(what);
        int boxW = Math.max(font.width(title), font.width(aimed)) + 24;
        int boxX = (w - boxW) / 2;
        int boxY = 18;
        graphics.fill(boxX - 1, boxY - 1, boxX + boxW + 1, boxY + 31, DevUi.ACCENT);
        graphics.fill(boxX, boxY, boxX + boxW, boxY + 30, 0xEE16191F);
        graphics.drawString(font, title, (w - font.width(title)) / 2, boxY + 5, DevUi.TEXT, false);
        graphics.drawString(font, aimed, (w - font.width(aimed)) / 2, boxY + 17, color, false);
        int cx = w / 2;
        int cy = h / 2;
        // Corners of a frame around the crosshair, in the colour of what is aimed at.
        graphics.fill(cx - 8, cy - 8, cx - 3, cy - 7, color);
        graphics.fill(cx - 8, cy - 8, cx - 7, cy - 3, color);
        graphics.fill(cx + 4, cy - 8, cx + 9, cy - 7, color);
        graphics.fill(cx + 8, cy - 8, cx + 9, cy - 3, color);
        graphics.fill(cx - 8, cy + 8, cx - 3, cy + 9, color);
        graphics.fill(cx - 8, cy + 4, cx - 7, cy + 9, color);
        graphics.fill(cx + 4, cy + 8, cx + 9, cy + 9, color);
        graphics.fill(cx + 8, cy + 4, cx + 9, cy + 9, color);
    }
}
