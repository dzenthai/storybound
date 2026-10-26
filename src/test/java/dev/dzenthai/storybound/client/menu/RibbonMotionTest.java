package dev.dzenthai.storybound.client.menu;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RibbonMotionTest {
    @Test
    void pullSettlesAndReversesWithoutJumping() {
        var motion = new RibbonMotion();
        assertEquals(0, motion.sample(1, 1000));
        float middle = motion.sample(1, 1110);
        assertTrue(middle > 0.85f && middle < 1);
        assertEquals(middle, motion.sample(0, 1110));
        assertTrue(motion.sample(0, 1220) < middle);
        assertEquals(0, motion.sample(0, 1330), 0.005f);
        motion.sample(1, 1400);
        assertEquals(1, motion.sample(1, 1700), 0.001f);
    }

    @Test
    void frameRateDoesNotChangeThePull() {
        var slow = new RibbonMotion();
        var fast = new RibbonMotion();
        slow.sample(1, 1000);
        fast.sample(1, 1000);
        for (int t = 1001; t < 1120; t++) fast.sample(1, t);
        assertEquals(slow.sample(1, 1120), fast.sample(1, 1120), 0.00001f);
        slow.sample(0, 1120);
        fast.sample(0, 1120);
        for (int t = 1121; t < 1300; t++) fast.sample(0, t);
        assertEquals(slow.sample(0, 1300), fast.sample(0, 1300), 0.00001f);
    }
}
