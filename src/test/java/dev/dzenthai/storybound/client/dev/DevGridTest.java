package dev.dzenthai.storybound.client.dev;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The canvas grid: nodes snap to lines and cell middles, and every size and step is on it. */
class DevGridTest {
    @Test
    void positionsSnapToLinesAndCellMiddles() {
        assertEquals(0, DevUi.snap(5.9));
        assertEquals(12, DevUi.snap(6.0), "half way goes on");
        assertEquals(12, DevUi.snap(17.9));
        assertEquals(24, DevUi.snap(18));
        assertEquals(-12, DevUi.snap(-7), "left of the origin the same way");
        assertEquals(-24, DevUi.snap(-19));
    }

    /** Zoom steps of whole screen pixels. */
    @Test
    void zoomStepsByWholeScreenPixels() {
        assertEquals(1.25f, GraphView.zoomStep(1f, 1, 4));
        assertEquals(0.75f, GraphView.zoomStep(1f, -1, 4));
        assertEquals(1.5f, GraphView.zoomStep(1f, 1, 2));
        assertEquals(0.5f, GraphView.zoomStep(0.5f, -1, 2), "not below one screen pixel's step over 35%");
        assertEquals(2f, GraphView.zoomStep(2f, 3, 3), "not over 200%");
        assertEquals(4f / 3, GraphView.zoomStep(1.1f, 1, 3), 1e-6, "a zoom between steps is rounded first");
    }

    /** The node shapes: a click is on them only inside, the same left and right. */
    @Test
    void nodeShapesHitInsideAndSymmetric() {
        for (NodeShapes.Shape shape : NodeShapes.Shape.values()) {
            org.junit.jupiter.api.Assertions.assertTrue(NodeShapes.contains(shape, 13, 0, 0), shape + " centre");
            org.junit.jupiter.api.Assertions.assertFalse(NodeShapes.contains(shape, 13, 0, 20), shape + " below");
            for (double x = 0; x <= 14; x += 0.5) {
                for (double y = -14; y <= 14; y += 0.5) {
                    assertEquals(NodeShapes.contains(shape, 13, x, y), NodeShapes.contains(shape, 13, -x, y), shape + " mirrored at " + x + "," + y);
                }
            }
        }
        org.junit.jupiter.api.Assertions.assertFalse(NodeShapes.contains(NodeShapes.Shape.DIAMOND, 15, 9, 9), "the empty corner by a diamond");
        org.junit.jupiter.api.Assertions.assertTrue(NodeShapes.contains(NodeShapes.Shape.CIRCLE, 13, 8, 8));
        org.junit.jupiter.api.Assertions.assertTrue(NodeShapes.contains(NodeShapes.Shape.TRIANGLE, 12, -9, -8), "a waypoint's triangle is wide at the top");
        org.junit.jupiter.api.Assertions.assertFalse(NodeShapes.contains(NodeShapes.Shape.TRIANGLE, 12, -9, 8), "and narrow at the bottom");
    }

    @Test
    void cardsAndLayoutsAreOnTheGrid() {
        for (int value : new int[]{StoryView.NODE_W, StoryView.NODE_H, DialogueView.NODE_W, DialogueView.NODE_H}) {
            assertEquals(0, value % DevUi.GRID, "a card's edges on lines: " + value);
        }
        for (int value : new int[]{DialogueGraph.LAYER_GAP, DialogueGraph.ROW_GAP, DialogueGraph.MARGIN}) {
            assertEquals(0, value % DevUi.SNAP, "an automatic place on the grid: " + value);
        }
    }
}
