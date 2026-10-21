package dev.dzenthai.storybound.client.dev;

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

    double worldX(double mouseX) {
        return (mouseX - DevUi.LEFT_PANEL_W - panX) / zoom;
    }

    double worldY(double mouseY) {
        return (mouseY - DevUi.TOP_BAR_H - panY) / zoom;
    }

    boolean onCanvas(DevModeScreen screen, double mouseX, double mouseY) {
        return mouseX >= DevUi.LEFT_PANEL_W && mouseX < screen.canvasRight() && mouseY >= DevUi.TOP_BAR_H;
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

    /** Zooms around the mouse, so the point under it stays put. */
    void zoomAt(double mouseX, double mouseY, double amount) {
        float old = zoom;
        zoom = Mth.clamp(zoom + (float) amount * 0.1f, 0.35f, 2.0f);
        double relX = mouseX - DevUi.LEFT_PANEL_W - panX;
        double relY = mouseY - DevUi.TOP_BAR_H - panY;
        panX -= relX * (zoom / old - 1.0);
        panY -= relY * (zoom / old - 1.0);
    }

    /** Moves the canvas so the point is in the middle. */
    void centerOn(DevModeScreen screen, double worldX, double worldY) {
        panX = (screen.canvasRight() - DevUi.LEFT_PANEL_W) / 2.0 - worldX * zoom;
        panY = (screen.height - DevUi.TOP_BAR_H) / 2.0 - worldY * zoom;
    }

    /** Starts drawing in canvas coordinates, clipped to the canvas. */
    void beginCanvas(DevModeScreen screen, GuiGraphics graphics, int gridColor) {
        int right = screen.canvasRight();
        graphics.enableScissor(DevUi.LEFT_PANEL_W, DevUi.TOP_BAR_H, right, screen.height);
        DevUi.grid(graphics, DevUi.LEFT_PANEL_W, DevUi.TOP_BAR_H, right, screen.height, panX, panY, zoom, gridColor);
        graphics.pose().pushPose();
        graphics.pose().translate((float) (DevUi.LEFT_PANEL_W + panX), (float) (DevUi.TOP_BAR_H + panY), 0);
        graphics.pose().scale(zoom, zoom, 1.0f);
    }

    void endCanvas(GuiGraphics graphics) {
        graphics.pose().popPose();
        graphics.disableScissor();
    }
}
