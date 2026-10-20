package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rung the next session starts on after a phone that restarted with a game running. Every
 * step is one thing the session can stop doing, ordered by how much of the picture it costs.
 */
class RebootGuardLevelTest {

    @Test fun `no restarts means no safe mode`() {
        assertEquals(RebootGuard.LEVEL_OFF, RebootGuard.levelFor(0))
        assertEquals(RebootGuard.LEVEL_OFF, RebootGuard.levelFor(-1))
    }

    @Test fun `each restart steps one rung down, and it stops at the last`() {
        assertEquals(RebootGuard.LEVEL_SAFE_PRESENT, RebootGuard.levelFor(1))
        assertEquals(RebootGuard.LEVEL_NO_SEMA, RebootGuard.levelFor(2))
        assertEquals(RebootGuard.LEVEL_SMALL, RebootGuard.levelFor(3))
        assertEquals(RebootGuard.LEVEL_SMALL, RebootGuard.levelFor(8))
    }

    @Test fun `the rungs only ever add to each other`() {
        assertEquals(1, RebootGuard.LEVEL_SAFE_PRESENT)
        assertEquals(2, RebootGuard.LEVEL_NO_SEMA)
        assertEquals(3, RebootGuard.LEVEL_SMALL)
    }
}
