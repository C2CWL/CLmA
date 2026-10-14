package me.rerere.rikkahub.ui.pages.setting

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Earth
import me.rerere.hugeicons.stroke.InLove
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.openUrl
import me.rerere.rikkahub.utils.plus

/**
 * One way a user can support the project. The page renders the list below generically, so a new
 * funding channel is one entry here plus its string resources - no UI change required.
 */
private sealed interface SponsorMethod {
    @get:StringRes
    val titleRes: Int

    @get:StringRes
    val descRes: Int
}

/** A donation QR code (WeChat / Alipay). Tapping the row opens a large, scannable preview. */
private data class SponsorQr(
    @param:StringRes override val titleRes: Int,
    @param:StringRes override val descRes: Int,
    @param:DrawableRes val imageRes: Int,
) : SponsorMethod

/** An external funding link (Ko-fi, GitHub Sponsors, ...). Tapping opens it in the browser. */
private data class SponsorLink(
    @param:StringRes override val titleRes: Int,
    @param:StringRes override val descRes: Int,
    val url: String,
) : SponsorMethod

/**
 * QR-code channels. The drawables are the real payment codes: to change one, replace
 * `res/drawable/sponsor_qr_wechat.png` / `sponsor_qr_alipay.png` with a new image of the same
 * resource name - the format does not matter.
 */
private val SPONSOR_QR_METHODS: List<SponsorQr> = listOf(
    SponsorQr(
        titleRes = R.string.setting_sponsor_wechat,
        descRes = R.string.setting_sponsor_wechat_desc,
        imageRes = R.drawable.sponsor_qr_wechat,
    ),
    SponsorQr(
        titleRes = R.string.setting_sponsor_alipay,
        descRes = R.string.setting_sponsor_alipay_desc,
        imageRes = R.drawable.sponsor_qr_alipay,
    ),
)

/**
 * Link channels. Empty by default - add an entry (plus its title/desc strings) and it shows up
 * as a row automatically. Kept separate from the QR list so the two sections can be styled apart.
 */
private val SPONSOR_LINK_METHODS: List<SponsorLink> = emptyList()

@Composable
fun SettingSponsorPage() {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val context = LocalContext.current
    var preview by remember { mutableStateOf<SponsorQr?>(null) }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_sponsor_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.setting_sponsor_page_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            if (SPONSOR_QR_METHODS.isNotEmpty()) {
                item {
                    CardGroup(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        title = { Text(stringResource(R.string.setting_sponsor_qr_section)) },
                    ) {
                        SPONSOR_QR_METHODS.forEach { qr ->
                            item(
                                onClick = { preview = qr },
                                leadingContent = { Icon(HugeIcons.InLove, null) },
                                supportingContent = { Text(stringResource(qr.descRes)) },
                                headlineContent = { Text(stringResource(qr.titleRes)) },
                            )
                        }
                    }
                }
            }

            if (SPONSOR_LINK_METHODS.isNotEmpty()) {
                item {
                    CardGroup(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        title = { Text(stringResource(R.string.setting_sponsor_link_section)) },
                    ) {
                        SPONSOR_LINK_METHODS.forEach { link ->
                            item(
                                onClick = { context.openUrl(link.url) },
                                leadingContent = { Icon(HugeIcons.Earth, null) },
                                supportingContent = { Text(link.url) },
                                headlineContent = { Text(stringResource(link.titleRes)) },
                            )
                        }
                    }
                }
            }
        }
    }

    preview?.let { qr ->
        QrPreviewDialog(
            title = stringResource(qr.titleRes),
            imageRes = qr.imageRes,
            onDismiss = { preview = null },
        )
    }
}

@Composable
private fun QrPreviewDialog(
    title: String,
    @DrawableRes imageRes: Int,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 360.dp)
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                // Force a white plate under the code so it stays scannable in dark theme (a
                // transparent or dark QR background reads as noise to a scanner).
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.White,
                ) {
                    Image(
                        painter = painterResource(imageRes),
                        contentDescription = title,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                    )
                }
                Text(
                    text = stringResource(R.string.setting_sponsor_qr_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.setting_sponsor_qr_close))
                }
            }
        }
    }
}
