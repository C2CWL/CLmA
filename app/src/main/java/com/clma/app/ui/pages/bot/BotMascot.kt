package com.clma.app.ui.pages.bot

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.clma.app.R
import com.clma.app.data.botmode.settings.BotActivity
import com.clma.app.data.model.BotMascotChoice

internal enum class BotMascotState(val cat: Int, val dog: Int) {
    IDLE(R.drawable.bot_mascot_cat_idle, R.drawable.bot_mascot_dog_idle),
    THINKING(R.drawable.bot_mascot_cat_thinking, R.drawable.bot_mascot_dog_thinking),
    HAPPY(R.drawable.bot_mascot_cat_happy, R.drawable.bot_mascot_dog_happy),
    WORKING(R.drawable.bot_mascot_cat_working, R.drawable.bot_mascot_dog_working),
    WAITING(R.drawable.bot_mascot_cat_waiting, R.drawable.bot_mascot_dog_waiting),
    BLOCKED(R.drawable.bot_mascot_cat_blocked, R.drawable.bot_mascot_dog_blocked),
    DONE(R.drawable.bot_mascot_cat_done, R.drawable.bot_mascot_dog_done),
}

internal fun BotActivity.mascotState(): BotMascotState = when (this) {
    BotActivity.WORKING -> BotMascotState.WORKING
    BotActivity.WAITING -> BotMascotState.WAITING
    BotActivity.IDLE -> BotMascotState.IDLE
}

@Composable
internal fun BotMascot(
    state: BotMascotState,
    choice: BotMascotChoice,
    modifier: Modifier = Modifier.size(32.dp),
    bob: Boolean = false,
) {
    val drawable = when (choice) {
        BotMascotChoice.CAT -> state.cat
        BotMascotChoice.DOG -> state.dog
        BotMascotChoice.NONE -> return
    }
    // Compose honors the system animator scale. At scale zero the bob rests at its target, 0.
    val animatedModifier = if (bob) {
        val offset = rememberInfiniteTransition(label = "Bot mascot").animateFloat(
            initialValue = -2f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "Bob",
        )
        modifier.graphicsLayer { translationY = offset.value.dp.toPx() }
    } else modifier

    Crossfade(targetState = drawable, modifier = animatedModifier, animationSpec = tween(240), label = "Bot mascot state") {
        Image(painterResource(it), contentDescription = null)
    }
}
