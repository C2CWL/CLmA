package me.rerere.rikkahub.ui.pages.quickchat

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.compose.setSingletonImageLoaderFactory
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.network.cachecontrol.CacheControlCacheStrategy
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import com.dokar.sonner.Toaster
import com.dokar.sonner.rememberToasterState
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.service.FloatingBallService
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.ui.theme.RikkahubTheme
import okhttp3.OkHttpClient
import org.koin.android.ext.android.inject
import org.koin.compose.koinInject

/**
 * Half-screen quick-chat panel opened by the floating ball.
 *
 * Transparent + translucent so whatever app the user is in stays visible behind the panel — the
 * panel looks like it floats over the current screen, and dismissing it (tap outside / close /
 * after sending a screen-action request) returns straight to that app with no task switch.
 *
 * Reuses the app's own theme and Koin singletons, so the panel talks to the same ChatService
 * conversations as the main chat page.
 *
 * The panel is positioned and mirrored from the ball that opened it: it never sits at a fixed
 * bottom, and its controls move to whichever side the ball is on (see [QuickChatPanel]).
 */
class QuickChatActivity : ComponentActivity() {

    companion object {
        /**
         * True when the panel was opened by holding the ball: the panel records for exactly as
         * long as the ball stays held (see [FloatingBallService.voiceHoldActive]).
         */
        const val EXTRA_VOICE = "quick_chat_voice"

        /** Which edge the ball sits on, `"left"` / `"right"`; drives the panel's mirrored layout. */
        const val EXTRA_BALL_SIDE = "quick_chat_ball_side"

        /** Vertical centre of the ball, in screen pixels; the panel anchors itself to it. */
        const val EXTRA_BALL_CENTER_Y = "quick_chat_ball_center_y"

        fun intent(context: Context, voice: Boolean, ballSide: String, ballCenterY: Int): Intent =
            Intent(context, QuickChatActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        // The panel animates itself (it unfolds out of the ball); the platform's
                        // stock open/close transition layered on top was the stutter — worst over
                        // the launcher. Belt-and-braces with the activity's own override below.
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
                putExtra(EXTRA_VOICE, voice)
                putExtra(EXTRA_BALL_SIDE, ballSide)
                putExtra(EXTRA_BALL_CENTER_Y, ballCenterY)
            }
    }

    /** Settings store, published through [LocalSettings] for the components the panel hosts. */
    private val settingsStore: SettingsStore by inject()

    /** Mirror the panel when the ball is on the left, so its controls fall under that thumb. */
    private val mirror = mutableStateOf(false)
    private val ballCenterY = mutableIntStateOf(0)

    /**
     * Whether this window can count on a blurred backdrop.
     *
     * When the OS will blur what is behind us we leave the panel's own surface translucent and let
     * the app underneath show through softened. When it will not — the cross-window blur feature is
     * switched off, or the OEM never implemented it — a translucent panel is just a washed-out slab
     * over a busy app, so the panel paints itself nearly opaque instead. Readability must not depend
     * on a blur the device may not have.
     */
    private val blurredBackdrop = mutableStateOf(false)

    /** Opened by holding the ball: the panel records for as long as the ball stays held. */
    private val holdToTalk = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        noSystemTransition()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val enabled = getSystemService(WindowManager::class.java)
                ?.isCrossWindowBlurEnabled == true
            blurredBackdrop.value = enabled
            if (enabled) {
                // PhoneWindow.setBackgroundBlurRadius accesses mDecor directly without
                // installing it; force creation first or it NPEs before setContent.
                window.decorView
                // The radius is in raw pixels and the panel is a large surface, so scale it with
                // density to keep the amount of diffusion constant across screens.
                window.setBackgroundBlurRadius((24 * resources.displayMetrics.density).toInt())
            }
        }
        readPanelExtras(intent)
        setContent {
            RikkahubTheme {
                val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
                val toaster = rememberToasterState()
                ConfigureImageLoader()
                // The panel hosts real app components (the assistant avatar in its header), and
                // those reach for the app-level CompositionLocals that only RouteActivity used to
                // provide. Without them here AssistantAvatar -> UIAvatar -> useCropLauncher dies on
                // LocalToaster.current — whose default is `error("Not provided")` — the moment the
                // panel composes, so provide the same set the main activity does.
                CompositionLocalProvider(
                    LocalSettings provides settings,
                    LocalToaster provides toaster,
                ) {
                    Toaster(
                        state = toaster,
                        darkTheme = LocalDarkMode.current,
                        richColors = true,
                        alignment = Alignment.TopCenter,
                        showCloseButton = true,
                    )
                    QuickChatPanel(
                        holdToTalk = holdToTalk.value,
                        mirror = mirror.value,
                        ballCenterY = ballCenterY.intValue,
                        blurredBackdrop = blurredBackdrop.value,
                        // Told from the panel's first frame, not from onCreate: the ball folds itself
                        // away exactly as the panel unfolds, and stands the idle countdown down while
                        // the panel owns the screen. It comes back when we finish.
                        onUnfoldStart = { FloatingBallService.notifyPanel(this, open = true) },
                        onClose = { finish() },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readPanelExtras(intent)
    }

    override fun onDestroy() {
        FloatingBallService.notifyPanel(this, open = false)
        super.onDestroy()
    }

    /**
     * The panel runs its own unfold/fold, so the platform transition layered on top of it is pure
     * jitter (and, over the launcher, a visibly half-played one). The launch intent already carries
     * FLAG_ACTIVITY_NO_ANIMATION; this covers the close half and Android 14+ as well.
     */
    private fun noSystemTransition() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }
    }

    override fun finish() {
        super.finish()
        // The panel folds back into the ball itself; the stock close animation would fight it.
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private fun readPanelExtras(intent: Intent?) {
        mirror.value = intent?.getStringExtra(EXTRA_BALL_SIDE) == "left"
        ballCenterY.intValue = intent?.getIntExtra(EXTRA_BALL_CENTER_Y, 0) ?: 0
        holdToTalk.value = intent?.getBooleanExtra(EXTRA_VOICE, false) == true
    }
}

/**
 * Installs the same singleton Coil loader the main activity does. The panel needs it because
 * [me.rerere.rikkahub.ui.components.ui.AssistantAvatar] falls back to the assistant's model brand
 * icon for a default avatar, and those live in `assets/icons` as SVG — with no [SvgDecoder] the
 * header avatar comes up as a blank circle instead. `setSingletonImageLoaderFactory` is a no-op
 * once a singleton exists, so whichever activity gets there first installs an identical loader.
 */
@OptIn(ExperimentalCoilApi::class)
@Composable
private fun ConfigureImageLoader() {
    val okHttpClient = koinInject<OkHttpClient>()
    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context)
            .crossfade(true)
            .components {
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = { okHttpClient },
                        cacheStrategy = { CacheControlCacheStrategy() },
                    )
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(AnimatedImageDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
                add(SvgDecoder.Factory(scaleToDensity = true))
            }
            .build()
    }
}
