package com.clma.app.ui.components.message

internal fun serverToolLoading(generationActive: Boolean, isFinished: Boolean): Boolean =
    generationActive && !isFinished
