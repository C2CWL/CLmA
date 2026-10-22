package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelPricing
import me.rerere.ai.provider.OffPeakRates
import me.rerere.ai.provider.PeakWindow
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.usage.PriceEntrySpec
import me.rerere.rikkahub.data.usage.PriceTableSpec
import me.rerere.rikkahub.data.usage.PriceWindowSpec
import me.rerere.rikkahub.data.usage.UsagePeakWindowText
import me.rerere.rikkahub.data.usage.UsagePriceTable

/**
 * P2-11d-4 - the price editor for one model, as the fourth tab of the model form.
 *
 * The numbers are kept as text so a half-typed "2." never overwrites a stored rate; a write
 * only happens once the draft parses. The window line is read by the same parser the agent
 * tool path uses, and the note under it is the same sentence UsagePriceTable would reject,
 * so what the form refuses is exactly what the ledger would refuse.
 */
@Composable
fun ModelPricingPage(
    model: Model,
    onModelChange: (Model) -> Unit,
) {
    val stored = model.pricing
    var draft by remember(stored) { mutableStateOf(RateDraft.of(stored)) }
    val storedSpec = remember(stored) {
        stored?.let {
            UsagePriceTable.fromModelPricing(
                providerName = model.modelId.ifBlank { "model" },
                modelId = model.modelId.ifBlank { "model" },
                pricing = it,
            )
        }
    }
    var windowsText by remember(stored) {
        mutableStateOf(UsagePeakWindowText.format(storedSpec?.peakWindows.orEmpty()))
    }

    val read = draft.read()
    val windows = UsagePeakWindowText.parse(windowsText)

    fun commit(next: ModelPricing?) {
        val value = next?.takeUnless { it.isUnpriced() }
        if (value != stored) onModelChange(model.copy(pricing = value))
    }

    fun edit(update: (RateDraft) -> RateDraft) {
        draft = update(draft)
        val edited = draft.read()
        if (edited.error == null && windows.error == null) {
            commit(edited.pricing?.withWindows(windows.windows))
        }
    }

    fun editWindows(text: String) {
        windowsText = text
        val reread = UsagePeakWindowText.parse(text)
        if (reread.error == null && read.error == null) {
            commit(read.pricing?.withWindows(reread.windows))
        }
    }

    val candidate = read.pricing?.withWindows(windows.windows)

    val note = when {
        read.error != null -> read.error
        windows.error != null -> windows.error
        candidate == null -> stringResource(R.string.setting_provider_page_pricing_unpriced)
        else -> UsagePriceTable.validate(
            PriceTableSpec(
                entries = listOf(
                    UsagePriceTable.fromModelPricing(
                        providerName = model.modelId.ifBlank { "model" },
                        modelId = model.modelId.ifBlank { "model" },
                        pricing = candidate,
                    )
                )
            )
        ).rejected.firstOrNull()?.reason
    }
    val noteIsError = read.error != null || windows.error != null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.setting_provider_page_pricing_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        RateField(
            label = stringResource(R.string.setting_provider_page_pricing_input),
            value = draft.input,
            onValueChange = { edit { current -> current.copy(input = it) } },
        )
        RateField(
            label = stringResource(R.string.setting_provider_page_pricing_output),
            value = draft.output,
            onValueChange = { edit { current -> current.copy(output = it) } },
        )
        RateField(
            label = stringResource(R.string.setting_provider_page_pricing_cache_hit),
            value = draft.cacheHit,
            onValueChange = { edit { current -> current.copy(cacheHit = it) } },
        )
        RateField(
            label = stringResource(R.string.setting_provider_page_pricing_cache_write),
            value = draft.cacheWrite,
            onValueChange = { edit { current -> current.copy(cacheWrite = it) } },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.setting_provider_page_pricing_off_peak),
                style = MaterialTheme.typography.bodyLarge,
            )
            Switch(
                checked = draft.hasOffPeak,
                onCheckedChange = { on ->
                    edit { current -> if (on) current else current.withoutOffPeak() }
                },
            )
        }

        if (draft.hasOffPeak) {
            RateField(
                label = stringResource(R.string.setting_provider_page_pricing_input),
                value = draft.offInput,
                onValueChange = { edit { current -> current.copy(offInput = it) } },
            )
            RateField(
                label = stringResource(R.string.setting_provider_page_pricing_output),
                value = draft.offOutput,
                onValueChange = { edit { current -> current.copy(offOutput = it) } },
            )
            RateField(
                label = stringResource(R.string.setting_provider_page_pricing_cache_hit),
                value = draft.offCacheHit,
                onValueChange = { edit { current -> current.copy(offCacheHit = it) } },
            )
            RateField(
                label = stringResource(R.string.setting_provider_page_pricing_cache_write),
                value = draft.offCacheWrite,
                onValueChange = { edit { current -> current.copy(offCacheWrite = it) } },
            )
        }

        OutlinedTextField(
            value = windowsText,
            onValueChange = { editWindows(it) },
            label = { Text(stringResource(R.string.setting_provider_page_pricing_windows)) },
            placeholder = { Text(UsagePeakWindowText.EXAMPLE) },
            singleLine = true,
            isError = windows.error != null,
            modifier = Modifier.fillMaxWidth(),
        )

        if (note != null) {
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = if (noteIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        TextButton(
            onClick = {
                draft = RateDraft.of(null)
                windowsText = ""
                commit(null)
            },
        ) {
            Text(stringResource(R.string.setting_provider_page_pricing_clear))
        }
    }
}

@Composable
private fun RateField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** The eight rates as text, so a partly typed number is not read as a value. */
private data class RateDraft(
    val input: String = "",
    val output: String = "",
    val cacheHit: String = "",
    val cacheWrite: String = "",
    val offInput: String = "",
    val offOutput: String = "",
    val offCacheHit: String = "",
    val offCacheWrite: String = "",
) {
    val hasOffPeak: Boolean
        get() = listOf(offInput, offOutput, offCacheHit, offCacheWrite).any { it.isNotBlank() }

    fun withoutOffPeak() = copy(offInput = "", offOutput = "", offCacheHit = "", offCacheWrite = "")

    /** Null pricing when nothing is filled in; error names the first field that is not a number. */
    fun read(): RateRead {
        val values = mutableListOf<Double?>()
        var error: String? = null
        listOf(input, output, cacheHit, cacheWrite, offInput, offOutput, offCacheHit, offCacheWrite).forEach { raw ->
            val text = raw.trim()
            if (text.isEmpty()) {
                values += null
                return@forEach
            }
            val parsed = text.toDoubleOrNull()
            if (parsed == null || !parsed.isFinite() || parsed < 0) {
                if (error == null) error = "rate must be a non-negative number (got: $text)"
                values += null
            } else {
                values += parsed
            }
        }
        if (values.all { it == null }) return RateRead(null, error)
        val offPeak = if (values[4] != null || values[5] != null || values[6] != null || values[7] != null) {
            OffPeakRates(values[4], values[5], values[6], values[7])
        } else {
            null
        }
        return RateRead(
            ModelPricing(
                inputPerMillion = values[0],
                outputPerMillion = values[1],
                cacheHitPerMillion = values[2],
                cacheWritePerMillion = values[3],
                offPeak = offPeak,
                peakWindows = emptyList(),
            ),
            error,
        )
    }

    companion object {
        fun of(pricing: ModelPricing?) = RateDraft(
            input = text(pricing?.inputPerMillion),
            output = text(pricing?.outputPerMillion),
            cacheHit = text(pricing?.cacheHitPerMillion),
            cacheWrite = text(pricing?.cacheWritePerMillion),
            offInput = text(pricing?.offPeak?.inputPerMillion),
            offOutput = text(pricing?.offPeak?.outputPerMillion),
            offCacheHit = text(pricing?.offPeak?.cacheHitPerMillion),
            offCacheWrite = text(pricing?.offPeak?.cacheWritePerMillion),
        )

        private fun text(value: Double?): String = when (value) {
            null -> ""
            else -> if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
        }
    }
}

private data class RateRead(
    val pricing: ModelPricing?,
    val error: String?,
)

private fun ModelPricing.isUnpriced(): Boolean =
    inputPerMillion == null && outputPerMillion == null &&
        cacheHitPerMillion == null && cacheWritePerMillion == null && offPeak == null

private fun ModelPricing.withWindows(windows: List<PriceWindowSpec>): ModelPricing =
    copy(
        peakWindows = windows.map { w ->
            PeakWindow(
                startMinute = w.startMinute,
                endMinute = w.endMinute,
                zoneOffsetMinutes = w.zoneOffsetMinutes,
                daysOfWeek = w.daysOfWeek.toSet(),
            )
        }
    )
