package me.rerere.rikkahub.ui.pages.stats

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.columnModel
import com.patrykandpatrick.vico.compose.cartesian.layer.ColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarkerController
import com.patrykandpatrick.vico.compose.cartesian.marker.DefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.marker.rememberDefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.Insets
import com.patrykandpatrick.vico.compose.common.MarkerCornerBasedShape
import com.patrykandpatrick.vico.compose.common.component.TextComponent
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.common.component.rememberShapeComponent
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import com.patrykandpatrick.vico.compose.common.data.ExtraStore
import me.rerere.rikkahub.data.usage.UsageStatBucket

/** Maps an _x_-index back to its ISO date, so the bottom axis can label days. */
private val DayLabelKey = ExtraStore.Key<List<String>>()

/**
 * Vico-backed daily token chart: one stacked column per day (input under output), newest on the
 * right. Tapping a column shows a marker with that day's totals. Replaces the hand-drawn canvas
 * that used to live in [DailyUsageChartCard].
 *
 * [days] is oldest-first, matching the chart's left-to-right time axis.
 */
@Composable
fun StatsDailyChart(
    days: List<UsageStatBucket>,
    inputColor: Color,
    outputColor: Color,
    modifier: Modifier = Modifier,
) {
    val modelProducer = remember { CartesianChartModelProducer() }

    LaunchedEffect(days) {
        modelProducer.runTransaction {
            columnModel {
                series(days.map { it.inputTokens.toDouble() })
                series(days.map { it.outputTokens.toDouble() })
            }
            extras { it[DayLabelKey] = days.map { it.key } }
        }
    }

    val bottomFormatter = CartesianValueFormatter { context, x, _ ->
        val labels = context.model.extraStore[DayLabelKey]
        val index = x.toInt()
        if (index in labels.indices) labels[index].takeLast(5) else ""
    }

    // A fixed 14.dp column overflows the card on the 90-day window (~90 columns into ~350dp).
    val columnThickness = when {
        days.size > 62 -> 4.dp
        days.size > 31 -> 8.dp
        else -> 14.dp
    }

    CartesianChartHost(
        chart = rememberCartesianChart(
            rememberColumnCartesianLayer(
                columnProvider = ColumnCartesianLayer.ColumnProvider.series(
                    listOf(
                        rememberLineComponent(fill = Fill(inputColor), thickness = columnThickness),
                        rememberLineComponent(fill = Fill(outputColor), thickness = columnThickness),
                    )
                ),
                mergeMode = { ColumnCartesianLayer.MergeMode.Stacked },
            ),
            startAxis = VerticalAxis.rememberStart(
                // Compact tokens ("1.2M") instead of the default raw numbers ("1200000").
                valueFormatter = CartesianValueFormatter { _, value, _ ->
                    formatCompactTokens(value.toLong())
                },
            ),
            bottomAxis = HorizontalAxis.rememberBottom(
                valueFormatter = bottomFormatter,
                itemPlacer = remember { HorizontalAxis.ItemPlacer.segmented() },
            ),
            marker = rememberValueMarker(),
            markerController = CartesianMarkerController.rememberToggleOnTap(),
        ),
        modelProducer = modelProducer,
        modifier = modifier
            .fillMaxWidth()
            .height(160.dp),
        zoomState = rememberVicoZoomState(zoomEnabled = false),
    )
}

/** A rounded label above a vertical guideline, shown when a column is tapped. */
@Composable
private fun rememberValueMarker(): CartesianMarker {
    val labelBackground = rememberShapeComponent(
        fill = Fill(MaterialTheme.colorScheme.surface),
        shape = MarkerCornerBasedShape(CircleShape),
        strokeFill = Fill(MaterialTheme.colorScheme.outline),
        strokeThickness = 1.dp,
    )
    val label = rememberTextComponent(
        style = TextStyle(
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            fontSize = 12.sp,
        ),
        padding = Insets(8.dp, 4.dp),
        background = labelBackground,
        minWidth = TextComponent.MinWidth.fixed(40.dp),
    )
    return rememberDefaultCartesianMarker(
        label = label,
        valueFormatter = DefaultCartesianMarker.ValueFormatter.default(),
    )
}
