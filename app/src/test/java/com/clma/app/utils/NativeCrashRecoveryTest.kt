package com.clma.app.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeCrashRecoveryTest {
    @Test fun newNativeCrashEntersSafeMode() {
        assertTrue(shouldRecoverNativeCrash(200L, 100L, true))
        assertTrue(shouldRecoverNativeCrash(200L, 0L, true))
    }

    @Test fun consumedCrashDoesNotRepeatAfterLeavingSafeMode() {
        assertFalse(shouldRecoverNativeCrash(200L, 200L, true))
        assertFalse(shouldRecoverNativeCrash(100L, 200L, true))
    }

    @Test fun normalStopsAndLowMemoryKillsDoNotTriggerRecovery() {
        assertFalse(shouldRecoverNativeCrash(200L, 100L, false))
    }
}
