package com.yawac.client.ui;

import org.junit.Test;
import static org.junit.Assert.*;

public class MorphSpringTest {
    @Test public void settlesWithoutOvershootInBothDirections() {
        MorphSpring motion = new MorphSpring();
        for (boolean joined : new boolean[]{true, false}) {
            motion.target(joined);
            double previous = motion.position;
            for (int i = 0; i < 90 && motion.moving(); i++) {
                motion.advance(1d / 60);
                assertTrue(joined ? motion.position >= previous : motion.position <= previous);
                assertTrue(motion.position >= 0 && motion.position <= 1);
                previous = motion.position;
            }
            assertFalse(motion.moving());
            assertEquals(joined ? 1 : 0, motion.position, 0);
        }
    }

    @Test public void reversingRetainsPositionAndVelocity() {
        MorphSpring motion = new MorphSpring(); motion.target(true);
        for (int i = 0; i < 6; i++) motion.advance(1d / 60);
        double position = motion.position, velocity = motion.velocity;
        motion.target(false);
        assertEquals(position, motion.position, 0);
        assertEquals(velocity, motion.velocity, 0);
        motion.advance(1d / 60);
        assertTrue(Math.abs(motion.position - position) < .08);
        for (int i = 0; i < 90; i++) motion.advance(1d / 60);
        assertEquals(0, motion.position, 0);
    }

    @Test public void equalTimeHasEqualMotionAtThirtyAndSixtyHz() {
        MorphSpring a = new MorphSpring(), b = new MorphSpring();
        a.target(true); b.target(true);
        for (int i = 0; i < 6; i++) a.advance(1d / 30);
        for (int i = 0; i < 12; i++) b.advance(1d / 60);
        assertEquals(a.position, b.position, .000001);
        assertEquals(a.velocity, b.velocity, .000001);
    }

    @Test public void delayedFrameCannotSwallowTheTransition() {
        MorphSpring motion = new MorphSpring(); motion.target(true);
        motion.advance(2);
        assertTrue(motion.position > 0 && motion.position < .3);
        assertTrue(motion.moving());
    }

    @Test public void repeatedTargetsKeepMomentumAndRapidReversalsStayBounded() {
        MorphSpring motion = new MorphSpring();
        for (int i = 0; i < 300; i++) {
            double p = motion.position, v = motion.velocity;
            motion.target((i / 4) % 2 == 0);
            assertEquals(p, motion.position, 0); assertEquals(v, motion.velocity, 0);
            motion.advance(1d / 60);
            assertTrue(motion.position >= 0 && motion.position <= 1);
        }
    }

    @Test public void explicitSnapHonoursReducedMotion() {
        MorphSpring motion = new MorphSpring(); motion.target(true); motion.snap();
        assertEquals(1, motion.position, 0); assertFalse(motion.moving());
        motion.target(false); motion.snap();
        assertEquals(0, motion.position, 0); assertFalse(motion.moving());
    }
}
