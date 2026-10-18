package com.ryose.videoplayer

import org.junit.Assert.assertEquals
import org.junit.Test

class SleepTimerTest {
    @Test
    fun 残りの分は切り上げ_最低1分() {
        assertEquals(15, SleepTimer.remainingMinutes(stopAt = 15 * 60_000L, now = 0))
        assertEquals(15, SleepTimer.remainingMinutes(stopAt = 14 * 60_000L + 1, now = 0))
        assertEquals(1, SleepTimer.remainingMinutes(stopAt = 1_000, now = 0))
        assertEquals(1, SleepTimer.remainingMinutes(stopAt = 0, now = 5_000))
    }
}
