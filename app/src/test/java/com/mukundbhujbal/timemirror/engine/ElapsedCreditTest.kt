package com.mukundbhujbal.timemirror.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * C1 unit tests for the pure timer-credit logic.
 *
 * They lock in the four properties that the service relies on:
 *  1. credited seconds follow measured intervals, not iteration count;
 *  2. the sub-second remainder is carried, never truncated per iteration;
 *  3. intervals that must not count (screen off / no monitored app) credit
 *     nothing and leave no stale remainder behind;
 *  4. a single pathological interval cannot dump a freeze window into the counter.
 */
class ElapsedCreditTest {

    @Test
    fun `credits measured intervals instead of one second per iteration`() {
        val credit = ElapsedCredit()
        var total = 0L
        repeat(10) { total += credit.onInterval(1300L, credit = true) }
        // 10 iterations x 1300 ms = 13 s; the old tick logic credited 10 s.
        assertEquals(13L, total)
    }

    @Test
    fun `carries the sub-second remainder across iterations`() {
        val credit = ElapsedCredit()
        var total = 0L
        repeat(10) { total += credit.onInterval(1100L, credit = true) }
        assertEquals(11L, total)
        assertEquals(0L, credit.pendingMillis())
    }

    @Test
    fun `accumulates fractional intervals instead of truncating each one`() {
        val credit = ElapsedCredit()
        var total = 0L
        repeat(3) { total += credit.onInterval(400L, credit = true) }
        // 3 x 400 ms = 1200 ms -> exactly 1 credited second, 200 ms carried.
        assertEquals(1L, total)
        assertEquals(200L, credit.pendingMillis())
    }

    @Test
    fun `exact one-second intervals credit one second each`() {
        val credit = ElapsedCredit()
        var total = 0L
        repeat(5) { total += credit.onInterval(1000L, credit = true) }
        assertEquals(5L, total)
        assertEquals(0L, credit.pendingMillis())
    }

    @Test
    fun `no credit and no stale remainder when the interval must not count`() {
        val credit = ElapsedCredit()
        // A credited but fractional interval leaves a remainder...
        assertEquals(0L, credit.onInterval(900L, credit = true))
        assertEquals(900L, credit.pendingMillis())
        // ...which a non-credited interval (screen off / not monitored) must drop.
        assertEquals(0L, credit.onInterval(5000L, credit = false))
        assertEquals(0L, credit.pendingMillis())
        // The following interval cannot inherit the discarded remainder.
        assertEquals(0L, credit.onInterval(900L, credit = true))
    }

    @Test
    fun `clamps a single interval to the safety bound`() {
        val credit = ElapsedCredit()
        // A 2-minute freeze must not be credited as 120 tracked seconds.
        assertEquals(5L, credit.onInterval(120_000L, credit = true))
        assertEquals(0L, credit.pendingMillis())
    }

    @Test
    fun `ignores non-positive intervals and leaves state untouched`() {
        val credit = ElapsedCredit()
        assertEquals(0L, credit.onInterval(0L, credit = true))
        assertEquals(0L, credit.onInterval(-1000L, credit = true))
        assertEquals(0L, credit.pendingMillis())
    }

    @Test
    fun `reset clears the carried remainder`() {
        val credit = ElapsedCredit()
        assertEquals(0L, credit.onInterval(700L, credit = true))
        credit.reset()
        assertEquals(0L, credit.pendingMillis())
        assertEquals(0L, credit.onInterval(700L, credit = true))
    }

    @Test
    fun `safety bound is configurable`() {
        val credit = ElapsedCredit(maxCreditMillis = 2500L)
        assertEquals(2L, credit.onInterval(120_000L, credit = true))
        assertEquals(500L, credit.pendingMillis())
    }
}
