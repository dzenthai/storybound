package dev.dzenthai.storybound.client.dev;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.math.Axis;
import dev.dzenthai.storybound.Storybound;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * The canvases' node shapes: a diamond, a circle, a hexagon, a square with rounded corners and a map pin (a round head,
 * its point down), smooth, each a texture of its fill, its one-pixel ring and its glow, white, tinted here
 * ({@code textures/gui/dev/node/<shape>_<radius>_*.png}, 4 texels a GUI pixel, linear filtering, so they stay smooth at
 * every zoom). A shape is centred on a pixel's middle; links are thin textured lines. The geometry here
 * ({@link #distance}) is the textures' own.
 */
final class NodeShapes {
    enum Shape { DIAMOND, CIRCLE, HEXAGON, SQUARE, PIN }

    /** GUI pixels round a shape in its textures, for its glow. */
    private static final int PAD = 5;
    /**
     * How many times larger than their textures' names the shapes are drawn, the items in them too. The textures are
     * four times finer than a pixel, so twice as large stays sharp.
     */
    static final int SCALE = 2;
    private static final ResourceLocation LINK = Storybound.id("textures/gui/dev/node/link.png");
    /** A link's width in canvas units: its solid middle half of it, its soft edges the rest. */
    private static final int LINK_WIDTH = 3;
    private static final double SIN60 = Math.sqrt(3) / 2;

    private NodeShapes() {}

    /**
     * How far {@code x, y} (from the shape's centre) is outside its edge, in GUI pixels; negative inside. The same
     * formulas drew the textures.
     */
    static double distance(Shape shape, int r, double x, double y) {
        return switch (shape) {
            case CIRCLE -> Math.hypot(x, y) - r;
            case DIAMOND -> (Math.abs(x) + Math.abs(y) - r) / Math.sqrt(2);
            case SQUARE -> {
                double k = 3;
                double qx = Math.abs(x) - (r - k);
                double qy = Math.abs(y) - (r - k);
                yield Math.hypot(Math.max(qx, 0), Math.max(qy, 0)) + Math.min(Math.max(qx, qy), 0) - k;
            }
            case HEXAGON -> Math.max(Math.abs(x) - r * SIN60, 0.5 * Math.abs(x) + SIN60 * Math.abs(y) - r * SIN60);
            case PIN -> {
                // A round head above its centre, and a point below where the lines from it touch the head.
                double c = r * 0.15;
                double q = r * 0.75;
                double t = r * 1.1;
                double head = Math.hypot(x, y + c) - q;
                double cos = q / (t + c);
                double sin = Math.sqrt(1 - cos * cos);
                double px = q * sin;
                double py = -c + q * cos;
                yield Math.min(head, triangle(x, y, 0, t, px, py, -px, py));
            }
        };
    }

    /** How far {@code x, y} is outside the triangle {@code a b c}; negative inside. */
    private static double triangle(double x, double y, double ax, double ay, double bx, double by, double cx, double cy) {
        double d = Math.min(segment(x, y, ax, ay, bx, by), Math.min(segment(x, y, bx, by, cx, cy), segment(x, y, cx, cy, ax, ay)));
        double s1 = (bx - ax) * (y - ay) - (by - ay) * (x - ax);
        double s2 = (cx - bx) * (y - by) - (cy - by) * (x - bx);
        double s3 = (ax - cx) * (y - cy) - (ay - cy) * (x - cx);
        boolean inside = s1 >= 0 && s2 >= 0 && s3 >= 0 || s1 <= 0 && s2 <= 0 && s3 <= 0;
        return inside ? -d : d;
    }

    private static double segment(double x, double y, double ax, double ay, double bx, double by) {
        double ex = bx - ax;
        double ey = by - ay;
        double h = Math.max(0, Math.min(1, ((x - ax) * ex + (y - ay) * ey) / (ex * ex + ey * ey)));
        return Math.hypot(x - ax - ex * h, y - ay - ey * h);
    }

    static boolean contains(Shape shape, int r, double x, double y) {
        return distance(shape, r, x, y) <= 0;
    }

    /** How far up a pin's icon sits from its centre: in the middle of its head. */
    static int iconLift(Shape shape, int r) {
        return shape == Shape.PIN ? Math.round(r * 0.15f) : 0;
    }

    /** Draws the shape centred on the pixel {@code cx, cy}: its glow ({@code glow}, ARGB; 0 none), its fill and its ring. */
    static void draw(GuiGraphics graphics, Shape shape, int r, int cx, int cy, int glow, int fill, int ring) {
        String name = name(shape, r);
        int pad = PAD * SCALE;
        int size = 2 * (r + pad) + 1;
        int x = cx - r - pad;
        int y = cy - r - pad;
        RenderSystem.enableBlend();
        if (glow != 0) {
            tinted(graphics, Storybound.id(name + "glow.png"), x, y, size, size, glow);
        }
        tinted(graphics, Storybound.id(name + "fill.png"), x, y, size, size, fill);
        tinted(graphics, Storybound.id(name + "ring.png"), x, y, size, size, ring);
        graphics.setColor(1, 1, 1, 1);
        RenderSystem.disableBlend();
    }

    /** The shape's area in {@code argb} over what is drawn: the hovered node's light, a dimmed node's shade. */
    static void overlay(GuiGraphics graphics, Shape shape, int r, int cx, int cy, int argb) {
        int pad = PAD * SCALE;
        int size = 2 * (r + pad) + 1;
        RenderSystem.enableBlend();
        tinted(graphics, Storybound.id(name(shape, r) + "fill.png"), cx - r - pad, cy - r - pad, size, size, argb);
        graphics.setColor(1, 1, 1, 1);
        RenderSystem.disableBlend();
    }

    /** The textures of the shape drawn at radius {@code r}: named by the radius before {@link #SCALE}. */
    private static String name(Shape shape, int r) {
        return "textures/gui/dev/node/" + shape.name().toLowerCase(java.util.Locale.ROOT) + "_" + r / SCALE + "_";
    }

    /** A link from {@code x1, y1} to {@code x2, y2} (canvas units): a line {@link #LINK_WIDTH} wide with soft edges. */
    static void link(GuiGraphics graphics, double x1, double y1, double x2, double y2, int color) {
        double length = Math.hypot(x2 - x1, y2 - y1);
        if (length < 1) {
            return;
        }
        float angle = (float) Math.atan2(y2 - y1, x2 - x1);
        RenderSystem.enableBlend();
        graphics.pose().pushPose();
        graphics.pose().translate(x1, y1, 0);
        graphics.pose().mulPose(Axis.ZP.rotation(angle));
        graphics.pose().translate(0, -LINK_WIDTH / 2f, 0);
        tinted(graphics, LINK, 0, 0, (int) Math.round(length), LINK_WIDTH, color);
        graphics.pose().popPose();
        graphics.setColor(1, 1, 1, 1);
        RenderSystem.disableBlend();
    }

    private static void tinted(GuiGraphics graphics, ResourceLocation texture, int x, int y, int w, int h, int argb) {
        graphics.setColor(((argb >> 16) & 255) / 255f, ((argb >> 8) & 255) / 255f, (argb & 255) / 255f, ((argb >>> 24) & 255) / 255f);
        // The whole texture over the quad, whatever its size in texels (linear filtering smooths the scale).
        graphics.blit(texture, x, y, w, h, 0, 0, 1, 1, 1, 1);
    }
}
