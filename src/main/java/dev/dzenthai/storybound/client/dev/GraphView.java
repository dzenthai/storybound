package dev.dzenthai.storybound.client.dev;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * One of the dev mode screen's editors, a canvas of nodes: the story or a dialogue's graph. Keeps the canvas's pan
 * and zoom; the screen draws the top bar and the modal windows around it and hands it the input that is left.
 */
abstract class GraphView {
    double panX = 40;
    double panY = 30;
    /**
     * The next frame puts the compass rose in the middle of the canvas, as Home does: set when a canvas opens with no
     * place of its own, as its size is known only once drawn.
     */
    boolean homePending;
    /** The zoom drawn now: on its way to {@link #targetZoom}, see {@link #animateZoom}. */
    float zoom = 1.0f;
    /**
     * Where the sky behind the canvas has moved to: half of every move of the canvas, but nothing of what zooming does
     * to the pan (it keeps the point under the mouse in place), so zooming leaves the sky still (November 19, 2026, the
     * author). The pan and zoom it last saw, to tell the two apart.
     */
    private double skyX;
    private double skyY;
    private double skyPanX = Double.NaN;
    private double skyPanY;
    private float skyZoom;
    /** Where the wheel set the zoom: a whole number of screen pixels a canvas pixel. */
    private float targetZoom = 1.0f;
    /** The screen point that stays put while the zoom glides: where the mouse was at the last wheel turn. */
    private double anchorX;
    private double anchorY;
    private long lastFrame;
    private boolean panning;
    private double panStartX;
    private double panStartY;
    /**
     * The landmark (November 19, 2026, the author: one got lost on the endless canvas): the canvas's origin, its axes
     * brighter than the grid and a compass rose on it; while it is off the canvas, the waypoints' arrow at the edge on its
     * side, which (as Home) glides back to the origin, the compass rose in the middle of the canvas as seen (November 20,
     * 2026, the author; before, near its top left, at 40, 30, where a canvas starts, and the rose was found in a corner).
     */
    private static final double HOME_X = 40;
    private static final double HOME_Y = 30;
    private static final int AXIS = 0xB4B08031;
    /** The arrow back: how near the edge, and how far round it a click takes. */
    private static final int HOME_ARROW = 12;
    /** Where the glide home goes (NaN: not gliding), and its last frame. */
    private double glideX = Double.NaN;
    private double glideY;
    private long lastGlideFrame;
    /** The arrow back's middle on the screen while shown, else NaN; the canvas's right and bottom as last drawn. */
    private double homeArrowX = Double.NaN;
    private double homeArrowY;
    private int drawnRight;
    private int drawnBottom;

    /** The top bar's first button: back to the menu or to the story. */
    abstract Component backLabel();

    abstract void back(DevModeScreen screen);

    abstract Component title();

    /** Texts not yet translated into the active language, for the top bar. */
    abstract int untranslated();

    /** Whether the top bar shows the validator's issues (where the view has no buttons on the right). */
    abstract boolean showsIssues();

    abstract boolean inspectorVisible(DevModeScreen screen);

    /**
     * Escape in this view, one layer a press (November 19, 2026, the author; before, Escape left the editor from
     * anywhere): what is selected, then the view inside the story; false when there is nothing left, and the screen
     * leaves the editor.
     */
    boolean escape(DevModeScreen screen) {
        return false;
    }

    /** The view's own widgets: side panel buttons and the inspector. */
    abstract void addWidgets(DevModeScreen screen);

    /** The canvas, the side panel and the inspector; the screen draws the top bar and the widgets over them. */
    abstract void render(DevModeScreen screen, GuiGraphics graphics, int mouseX, int mouseY);

    /** Re-reads what it shows from the store: after a snapshot, a language switch, undo or redo. */
    abstract void reload();

    /** Before the reload of an undo or redo: edits already saved (or kept as a draft) give way to what it restored. */
    void beforeHistoryReload() {}

    /** Sends what was edited in the inspector. */
    abstract void saveEdits(DevModeScreen screen);

    /** A click the widgets did not take. */
    abstract boolean mouseClicked(DevModeScreen screen, double mouseX, double mouseY, int button);

    abstract boolean mouseDragged(DevModeScreen screen, double mouseX, double mouseY);

    abstract boolean mouseReleased(DevModeScreen screen, double mouseX, double mouseY);

    /** A key the widgets did not take, with no text box focused. */
    abstract boolean keyPressed(DevModeScreen screen, int keyCode);

    /** The mouse wheel over the inspector: scrolls its form. */
    abstract void scrollInspector(double amount);

    /** The mouse wheel over the left panel: scrolls the list under it; false when the view's panel has none. */
    boolean scrollSidePanel(DevModeScreen screen, double mouseX, double mouseY, double amount) {
        return false;
    }

    /** Every client tick while the view is shown. */
    void tick(DevModeScreen screen) {}

    /** The view's own buttons in the top bar, after the stories' button (left) and before the settings (right). */
    List<DevModeScreen.TopButton> topLeft(DevModeScreen screen) {
        return List.of();
    }

    List<DevModeScreen.TopButton> topRight(DevModeScreen screen) {
        return List.of();
    }

    /** One frame of the inspector's scroll glide; whether its rows moved, so the screen lays them out again. */
    boolean animateInspector() {
        return false;
    }

    /** Where the canvas begins: after the view's left panel (the story's is a narrow strip of chapters). */
    int canvasLeft() {
        return DevUi.LEFT_PANEL_W;
    }

    double worldX(double mouseX) {
        return (mouseX - canvasLeft() - panX) / zoom;
    }

    double worldY(double mouseY) {
        return (mouseY - DevUi.TOP_BAR_H - panY) / zoom;
    }

    boolean onCanvas(DevModeScreen screen, double mouseX, double mouseY) {
        return mouseX >= canvasLeft() && mouseX < screen.canvasRight() && mouseY >= DevUi.TOP_BAR_H;
    }

    void startPan(double mouseX, double mouseY) {
        glideX = Double.NaN;
        panning = true;
        panStartX = mouseX - panX;
        panStartY = mouseY - panY;
    }

    /** @return whether the canvas is being panned (and was moved) */
    boolean pan(double mouseX, double mouseY) {
        if (panning) {
            panX = mouseX - panStartX;
            panY = mouseY - panStartY;
        }
        return panning;
    }

    void stopPan() {
        panning = false;
    }

    /** The mouse wheel over the canvas: zooms, or scrolls a view that is a list. */
    void wheel(DevModeScreen screen, double mouseX, double mouseY, double amount) {
        zoomAt(mouseX, mouseY, amount);
    }

    /** What the wheel turned and has not yet made a zoom step (a touchpad sends small amounts). */
    private double wheel;

    /**
     * Zooms around the mouse, so the point under it stays put, to steps where a canvas pixel is a whole number of the
     * screen's pixels (November 18, 2026, the author: before, 10% steps made pixel shapes and icons uneven): the zoom is
     * a multiple of one screen pixel, {@code k / guiScale}, from about 35% to 200%. The wheel sets where it goes; the
     * drawn zoom glides there ({@link #animateZoom}; November 19, 2026, the author: it jumped).
     */
    void zoomAt(double mouseX, double mouseY, double amount) {
        wheel += amount;
        int steps = (int) wheel;
        if (steps == 0) {
            return;
        }
        wheel -= steps;
        targetZoom = zoomStep(targetZoom, steps, Minecraft.getInstance().getWindow().getGuiScale());
        anchorX = mouseX;
        anchorY = mouseY;
    }

    /** Sets the zoom at once, with no glide: opening a view, back to where a canvas was looked at. */
    void setZoom(float value) {
        zoom = value;
        targetZoom = value;
    }

    /**
     * One frame of the zoom's glide to where the wheel set it, about a fifth of a second, frame-rate independent; the
     * anchor stays under the mouse all the way, and the glide ends exactly on the step.
     */
    private void animateZoom() {
        long now = System.nanoTime();
        double seconds = lastFrame == 0 ? 0 : Math.min(0.1, (now - lastFrame) / 1e9);
        lastFrame = now;
        if (zoom == targetZoom) {
            return;
        }
        float old = zoom;
        float next = (float) (zoom + (targetZoom - zoom) * (1 - Math.exp(-seconds * 16)));
        if (Math.abs(targetZoom - next) < 0.002f) {
            next = targetZoom;
        }
        double relX = anchorX - canvasLeft() - panX;
        double relY = anchorY - DevUi.TOP_BAR_H - panY;
        panX -= relX * (next / old - 1.0);
        panY -= relY * (next / old - 1.0);
        zoom = next;
    }

    /** The zoom {@code steps} whole screen pixels from {@code zoom} (rounded to one first), between 35% and 200%. */
    static float zoomStep(float zoom, int steps, double guiScale) {
        int min = Math.max(1, (int) Math.ceil(0.35 * guiScale));
        int max = Math.max(min, (int) Math.floor(2 * guiScale));
        int level = Mth.clamp((int) Math.round(zoom * guiScale) + steps, min, max);
        return (float) (level / guiScale);
    }

    /** Moves the canvas so the point is in the middle. */
    void centerOn(DevModeScreen screen, double worldX, double worldY) {
        glideX = Double.NaN;
        panX = (screen.canvasRight() - canvasLeft()) / 2.0 - worldX * zoom;
        panY = (screen.height - DevUi.TOP_BAR_H) / 2.0 - worldY * zoom;
    }

    /** Starts drawing in canvas coordinates, clipped to the canvas. */
    void beginCanvas(DevModeScreen screen, GuiGraphics graphics, int gridColor) {
        animateZoom();
        animateHome();
        if (homePending) {
            homePending = false;
            // The rose sits half a unit into the origin.
            centerOn(screen, 0.5, 0.5);
        }
        int right = screen.canvasRight();
        drawnRight = right;
        drawnBottom = screen.height;
        graphics.enableScissor(canvasLeft(), DevUi.TOP_BAR_H, right, screen.height);
        // The sky behind, at half the canvas's pace, still while zooming (November 19, 2026).
        if (Double.isNaN(skyPanX)) {
            // At first sight its middle and the grid's origin agree as they would with the origin in the screen's middle,
            // so the sky's mirrored seams lie far from the origin (November 20, 2026, the author saw one in plain view).
            skyX = (canvasLeft() + panX - screen.width / 2.0) * DevBackground.PARALLAX;
            skyY = (DevUi.TOP_BAR_H + panY - screen.height / 2.0) * DevBackground.PARALLAX;
        } else if (zoom == skyZoom) {
            skyX += (panX - skyPanX) * DevBackground.PARALLAX;
            skyY += (panY - skyPanY) * DevBackground.PARALLAX;
        }
        skyPanX = panX;
        skyPanY = panY;
        skyZoom = zoom;
        DevBackground.draw(graphics, canvasLeft(), DevUi.TOP_BAR_H, right, screen.height, screen.width, screen.height, skyX, skyY);
        // The grid and the origin's axes on the screen's own pixels (its compass rose too, endCanvas).
        DevUi.grid(graphics, canvasLeft(), DevUi.TOP_BAR_H, right, screen.height, panX, panY, zoom, gridColor, AXIS);
        graphics.pose().pushPose();
        graphics.pose().translate((float) (canvasLeft() + panX), (float) (DevUi.TOP_BAR_H + panY), 0);
        graphics.pose().scale(zoom, zoom, 1.0f);
    }

    void endCanvas(GuiGraphics graphics) {
        graphics.pose().popPose();
        renderCompass(graphics);
        renderHomeButton(graphics);
        graphics.disableScissor();
    }

    /**
     * The origin's compass rose (November 20, 2026, the author: the diamond before it grew into coarse squares with the
     * zoom and looked like a toy): four faceted points shaded as a pinwheel, a glint in the middle, gold ticks on the
     * diagonals, no edge (the dark edge it had at first the author did not like); on the screen's own pixels, the same
     * size and sharp at every zoom.
     */
    private static final String[] COMPASS = {
            "...................",
            ".........l.........",
            ".........l.........",
            ".........l.........",
            "....g...oll...g....",
            ".....g..oll..g.....",
            "........oll........",
            ".......lolll.......",
            "....llllllloooo....",
            ".llllllllwllllllll.",
            "....oooolllllll....",
            ".......lllol.......",
            "........llo........",
            ".....g..llo..g.....",
            "....g...llo...g....",
            ".........l.........",
            ".........l.........",
            ".........l.........",
            "..................."
    };

    private void renderCompass(GuiGraphics graphics) {
        int size = COMPASS.length;
        int cx = (int) Math.floor(canvasLeft() + panX + zoom * 0.5);
        int cy = (int) Math.floor(DevUi.TOP_BAR_H + panY + zoom * 0.5);
        int left = cx - size / 2;
        int top = cy - size / 2;
        if (left >= drawnRight || top >= drawnBottom || left + size <= canvasLeft() || top + size <= DevUi.TOP_BAR_H) {
            return;
        }
        for (int row = 0; row < size; row++) {
            String line = COMPASS[row];
            for (int column = 0; column < line.length(); column++) {
                int color = switch (line.charAt(column)) {
                    case 'o' -> 0xFFB08031;
                    case 'g' -> StoryView.GOLD;
                    case 'l' -> StoryView.GOLD_LIGHT;
                    case 'w' -> 0xFFFFF4C4;
                    default -> 0;
                };
                if (color != 0) {
                    graphics.fill(left + column, top + row, left + column + 1, top + row + 1, color);
                }
            }
        }
    }

    /**
     * Glides back to the origin (Home, or the arrow at the edge): the compass rose, which sits half a cell into it, in the
     * middle of the canvas as last drawn, at the zoom it has; before the canvas is first drawn, near its top left.
     */
    void goHome() {
        if (drawnRight > canvasLeft() && drawnBottom > DevUi.TOP_BAR_H) {
            glideX = Math.floor((drawnRight - canvasLeft()) / 2.0 - zoom * 0.5);
            glideY = Math.floor((drawnBottom - DevUi.TOP_BAR_H) / 2.0 - zoom * 0.5);
        } else {
            glideX = HOME_X;
            glideY = HOME_Y;
        }
        lastGlideFrame = 0;
    }

    /** One frame of the glide home, about a quarter of a second; any drag stops it. */
    private void animateHome() {
        if (Double.isNaN(glideX)) {
            return;
        }
        long now = System.nanoTime();
        double seconds = lastGlideFrame == 0 ? 0 : Math.min(0.1, (now - lastGlideFrame) / 1e9);
        lastGlideFrame = now;
        double k = 1 - Math.exp(-seconds * 12);
        panX += (glideX - panX) * k;
        panY += (glideY - panY) * k;
        if (Math.abs(glideX - panX) < 0.5 && Math.abs(glideY - panY) < 0.5) {
            panX = glideX;
            panY = glideY;
            glideX = Double.NaN;
        }
    }

    /**
     * While the origin is off the canvas: the waypoints' arrow (the author's, as at the screen's edge in the world;
     * November 20, 2026, the author; the day before, a square button with a target), at the canvas's edge where the
     * line from its middle to the origin leaves it, turned towards the origin, pulsing as the waypoints' does.
     */
    private void renderHomeButton(GuiGraphics graphics) {
        double ox = canvasLeft() + panX;
        double oy = DevUi.TOP_BAR_H + panY;
        boolean inside = ox >= canvasLeft() && ox < drawnRight && oy >= DevUi.TOP_BAR_H && oy < drawnBottom;
        if (inside) {
            homeArrowX = Double.NaN;
            return;
        }
        double inset = HOME_ARROW;
        double midX = (canvasLeft() + drawnRight) / 2.0;
        double midY = (DevUi.TOP_BAR_H + drawnBottom) / 2.0;
        double dx = ox - midX;
        double dy = oy - midY;
        // How far along the line its first edge is, the canvas shrunk by the inset.
        double halfW = Math.max(1, (drawnRight - canvasLeft()) / 2.0 - inset);
        double halfH = Math.max(1, (drawnBottom - DevUi.TOP_BAR_H) / 2.0 - inset);
        double t = Math.min(dx == 0 ? Double.MAX_VALUE : halfW / Math.abs(dx), dy == 0 ? Double.MAX_VALUE : halfH / Math.abs(dy));
        homeArrowX = midX + dx * t;
        homeArrowY = midY + dy * t;
        Minecraft minecraft = Minecraft.getInstance();
        double mouseX = minecraft.mouseHandler.xpos() * minecraft.getWindow().getGuiScaledWidth() / minecraft.getWindow().getScreenWidth();
        double mouseY = minecraft.mouseHandler.ypos() * minecraft.getWindow().getGuiScaledHeight() / minecraft.getWindow().getScreenHeight();
        boolean hover = Math.hypot(mouseX - homeArrowX, mouseY - homeArrowY) <= HOME_ARROW;
        float pulse = hover ? 1f : 0.8f + 0.2f * Mth.sin((net.minecraft.Util.getMillis() % 1600) / 1600f * Mth.TWO_PI);
        int tint = hover ? StoryView.GOLD_LIGHT : StoryView.GOLD;
        // The sprite points up: turned clockwise until it points at the origin.
        float angle = (float) Math.atan2(dx, -dy);
        graphics.setColor(((tint >> 16) & 0xFF) / 255f, ((tint >> 8) & 0xFF) / 255f, (tint & 0xFF) / 255f, pulse);
        graphics.pose().pushPose();
        graphics.pose().translate(homeArrowX, homeArrowY, 150);
        graphics.pose().mulPose(com.mojang.math.Axis.ZP.rotation(angle));
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        graphics.blitSprite(dev.dzenthai.storybound.Storybound.id("waypoint/arrow"), -8, -8, 16, 16);
        graphics.pose().popPose();
        graphics.setColor(1f, 1f, 1f, 1f);
    }

    /** A click on the arrow: glides home. */
    boolean clickHome(double mouseX, double mouseY) {
        if (!Double.isNaN(homeArrowX) && Math.hypot(mouseX - homeArrowX, mouseY - homeArrowY) <= HOME_ARROW) {
            goHome();
            return true;
        }
        return false;
    }
}
