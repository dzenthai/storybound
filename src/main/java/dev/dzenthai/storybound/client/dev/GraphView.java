package dev.dzenthai.storybound.client.dev;

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
    float zoom = 1.0f;
    private boolean panning;
    private double panStartX;
    private double panStartY;

    /** The top bar's first button: back to the menu or to the story. */
    abstract Component backLabel();

    abstract void back(DevModeScreen screen);

    abstract Component title();

    /** Texts not yet translated into the active language, for the top bar. */
    abstract int untranslated();

    /** Whether the top bar shows the validator's issues (where the view has no buttons on the right). */
    abstract boolean showsIssues();

    abstract boolean inspectorVisible(DevModeScreen screen);

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
     * Zooms around the mouse, so the point under it stays put, in steps where a canvas pixel is a whole number of the
     * screen's pixels (November 18, 2026, the author: before, 10% steps made pixel shapes and icons uneven): the zoom is
     * a multiple of one screen pixel, {@code k / guiScale}, from about 35% to 200%.
     */
    void zoomAt(double mouseX, double mouseY, double amount) {
        wheel += amount;
        int steps = (int) wheel;
        if (steps == 0) {
            return;
        }
        wheel -= steps;
        float old = zoom;
        zoom = zoomStep(zoom, steps, Minecraft.getInstance().getWindow().getGuiScale());
        if (zoom == old) {
            return;
        }
        double relX = mouseX - canvasLeft() - panX;
        double relY = mouseY - DevUi.TOP_BAR_H - panY;
        panX -= relX * (zoom / old - 1.0);
        panY -= relY * (zoom / old - 1.0);
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
        panX = (screen.canvasRight() - canvasLeft()) / 2.0 - worldX * zoom;
        panY = (screen.height - DevUi.TOP_BAR_H) / 2.0 - worldY * zoom;
    }

    /** Starts drawing in canvas coordinates, clipped to the canvas. */
    void beginCanvas(DevModeScreen screen, GuiGraphics graphics, int gridColor) {
        int right = screen.canvasRight();
        graphics.enableScissor(canvasLeft(), DevUi.TOP_BAR_H, right, screen.height);
        graphics.pose().pushPose();
        graphics.pose().translate((float) (canvasLeft() + panX), (float) (DevUi.TOP_BAR_H + panY), 0);
        graphics.pose().scale(zoom, zoom, 1.0f);
        DevUi.grid(graphics, -panX / zoom, -panY / zoom, (right - canvasLeft() - panX) / zoom,
                (screen.height - DevUi.TOP_BAR_H - panY) / zoom, zoom, gridColor);
    }

    void endCanvas(GuiGraphics graphics) {
        graphics.pose().popPose();
        graphics.disableScissor();
    }
}
