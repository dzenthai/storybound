package dev.dzenthai.storybound.mixin.compat;

import dev.dzenthai.storybound.compat.xaero.XaeroWaypointRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xaero.common.minimap.waypoints.Waypoint;
import xaero.hud.minimap.element.render.MinimapElementRenderInfo;

/**
 * Xaero's Minimap has no per-waypoint "not in the world" setting (its world-map-only visibility also hides a
 * waypoint from the minimap), so its in-world renderer skips Storybound's waypoints here: they keep their place on
 * the minimap and the world map, and Storybound draws its own markers in the world. {@code @Pseudo} and an optional
 * config: without Xaero this does nothing; {@code require = 0}: if Xaero changes the method, Xaero simply draws them.
 */
@Pseudo
@Mixin(targets = "xaero.hud.minimap.waypoint.render.world.WaypointWorldRenderer", remap = false)
public abstract class XaeroWaypointWorldRendererMixin {
    @Inject(method = "renderElement(Lxaero/common/minimap/waypoints/Waypoint;ZZDFDDLxaero/hud/minimap/element/render/MinimapElementRenderInfo;Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;)Z",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void storybound$skipOurWaypoints(Waypoint waypoint, boolean highlighted, boolean outOfBounds, double optionalDepth, float optionalScale,
                                             double partialX, double partialY, MinimapElementRenderInfo renderInfo, GuiGraphics graphics,
                                             MultiBufferSource.BufferSource buffers, CallbackInfoReturnable<Boolean> info) {
        if (XaeroWaypointRenderer.isOurs(waypoint)) {
            info.setReturnValue(false);
        }
    }
}
