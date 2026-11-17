package com.clma.app.utils

/** Exit records are persisted by Android and may be returned again after recovery. */
internal fun shouldRecoverNativeCrash(
    timestamp: Long,
    lastSeenTimestamp: Long,
    isNativeCrash: Boolean,
): Boolean = isNativeCrash && timestamp > lastSeenTimestamp
