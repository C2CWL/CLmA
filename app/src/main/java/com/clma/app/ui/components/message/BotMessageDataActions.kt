// Adapted from OpenMausBot (https://github.com/milind-soni/OpenMausBot), src/components/data/DataPanel.tsx, src/components/data/SqlEditor.tsx, src/components/data/DataChart.tsx,
// Copyright 2026 Milind Soni and OpenMausBot contributors, Apache License 2.0
// (third_party/openmausbot/LICENSE). Modified: ported to Kotlin and changed for CLmA.
package com.clma.app.ui.components.message

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.clma.ai.ui.UIMessagePart
import com.clma.app.R
import com.clma.app.data.botmode.data.BotDataCard
import com.clma.app.data.botmode.data.BotDataSql
import com.clma.app.data.botmode.data.BotDataTable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
internal fun BotMessageDataActions(tools: List<UIMessagePart.Tool>) {
    val cards = remember(tools) { tools.mapNotNull { tool -> BotDataCard.fromTool(tool)?.let { tool.toolCallId to it } } }
    cards.forEach { (id, card) ->
        key(id) {
            var open by rememberSaveable { mutableStateOf(false) }
            TextButton(onClick = { open = true }) {
                Text(card.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.bot_data_open))
            }
            if (open) BotDataWorkbench(card) { open = false }
        }
    }
}

@Composable
private fun BotDataWorkbench(card: BotDataCard, dismiss: () -> Unit) {
    var filter by rememberSaveable { mutableStateOf("") }
    var chart by rememberSaveable { mutableStateOf(card.chart) }
    var showSql by rememberSaveable { mutableStateOf(false) }
    var sql by rememberSaveable { mutableStateOf("SELECT * FROM data") }
    var result by remember { mutableStateOf<BotDataTable?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val table = result ?: card.table
    val filtered = remember(table, filter) {
        if (filter.isBlank()) table.rows else table.rows.filter { row ->
            row.any { it?.toString()?.contains(filter, ignoreCase = true) == true }
        }
    }
    val queryFailed = stringResource(R.string.bot_data_query_failed)

    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(
                Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        card.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.bot_data_title),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = dismiss) { Text(stringResource(R.string.bot_data_close)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!chart, { chart = false }, { Text(stringResource(R.string.bot_data_table)) })
                    FilterChip(chart, { chart = true }, { Text(stringResource(R.string.bot_data_chart)) })
                    FilterChip(showSql, { showSql = !showSql }, { Text(stringResource(R.string.bot_data_sql)) })
                }
                if (showSql) {
                    OutlinedTextField(
                        value = sql,
                        onValueChange = { sql = it.take(8_000) },
                        label = { Text(stringResource(R.string.bot_data_sql)) },
                        supportingText = { Text(stringResource(R.string.bot_data_sql_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3,
                    )
                    Row {
                        Button(
                            enabled = running == null && sql.isNotBlank(),
                            onClick = {
                                val statement = sql
                                running = scope.launch {
                                    failure = null
                                    try {
                                        result = BotDataSql.query(card.table, statement)
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (error: Exception) {
                                        failure = error.localizedMessage?.take(300)?.ifBlank { queryFailed } ?: queryFailed
                                    } finally {
                                        running = null
                                    }
                                }
                            },
                        ) { Text(stringResource(R.string.bot_data_run)) }
                        if (running != null) {
                            TextButton(onClick = { running?.cancel() }) {
                                Text(stringResource(R.string.bot_data_cancel))
                            }
                        } else if (result != null) {
                            TextButton(onClick = { result = null; failure = null }) {
                                Text(stringResource(R.string.bot_data_reset))
                            }
                        }
                    }
                    failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    label = { Text(stringResource(R.string.bot_data_filter)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Text(
                    stringResource(R.string.bot_data_counts, filtered.size, table.rows.size, table.columns.size),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (table.truncated) Text(stringResource(R.string.bot_data_truncated))
                if (chart) BotDataChart(table.columns, filtered, Modifier.weight(1f))
                else BotDataGrid(table.columns, filtered, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun BotDataGrid(columns: List<String>, rows: List<List<Any?>>, modifier: Modifier) {
    val cellWidth = 144.dp
    LazyColumn(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).width(cellWidth * columns.size)) {
        item {
            Row(Modifier.fillMaxWidth()) {
                columns.forEach { Text(it, Modifier.width(cellWidth).padding(8.dp), style = MaterialTheme.typography.titleSmall) }
            }
            HorizontalDivider()
        }
        items(rows) { row ->
            Row {
                row.forEach { value ->
                    Text(
                        value?.toString() ?: stringResource(R.string.bot_data_null),
                        Modifier.width(cellWidth).padding(8.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun BotDataChart(columns: List<String>, rows: List<List<Any?>>, modifier: Modifier) {
    val numericColumns = remember(columns, rows) {
        columns.indices.filter { index -> rows.any { (it[index] as? Number)?.toDouble()?.isFinite() == true } }
    }
    var selected by remember(columns) { mutableStateOf<Int?>(null) }
    var expanded by remember { mutableStateOf(false) }
    val index = selected?.takeIf { it in numericColumns }
        ?: numericColumns.firstOrNull { it != 0 } ?: numericColumns.firstOrNull()
    if (index == null) {
        Text(stringResource(R.string.bot_data_chart_empty))
        return
    }
    val points = remember(rows, index) {
        rows.asSequence().mapNotNull { row ->
            val value = (row[index] as? Number)?.toDouble()?.takeIf { it.isFinite() } ?: return@mapNotNull null
            (if (index == 0) row.getOrNull(1)?.toString() else row[0]?.toString()) to value
        }.take(101).toList()
    }
    val minimum = points.take(100).minOf { it.second }.coerceAtMost(0.0)
    val maximum = points.take(100).maxOf { it.second }.coerceAtLeast(0.0)
    val scale = maxOf(-minimum, maximum, Double.MIN_VALUE)
    val range = (maximum / scale - minimum / scale).takeIf { it > 0 } ?: 1.0
    val baseline = (-minimum / scale / range).toFloat()
    val color = MaterialTheme.colorScheme.primary
    val axisColor = MaterialTheme.colorScheme.outlineVariant

    Column(modifier) {
        Box {
            TextButton(onClick = { expanded = true }) {
                Text(stringResource(R.string.bot_data_chart_value, columns[index]))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                numericColumns.forEach { column ->
                    DropdownMenuItem(text = { Text(columns[column]) }, onClick = {
                        selected = column
                        expanded = false
                    })
                }
            }
        }
        if (points.size > 100) Text(stringResource(R.string.bot_data_chart_limit))
        LazyColumn {
            items(points.take(100)) { (label, value) ->
                Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label.orEmpty(), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Canvas(Modifier.weight(2f).height(16.dp)) {
                        val zero = size.width * baseline
                        val end = size.width * ((value / scale - minimum / scale) / range).toFloat()
                        drawLine(axisColor, Offset(zero, 0f), Offset(zero, size.height))
                        drawRect(color, Offset(minOf(zero, end), 0f), Size(kotlin.math.abs(end - zero), size.height))
                    }
                    Text(
                        value.toString(), Modifier.weight(1f).padding(start = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
