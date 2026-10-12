package me.rerere.rikkahub.data.usage

import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.local.AgentWorkspace
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import me.rerere.rikkahub.data.agentrun.AgentRunKind
import me.rerere.rikkahub.data.agentrun.AgentRunRepository
import me.rerere.rikkahub.data.agentrun.AgentRunStatus
import me.rerere.rikkahub.data.datastore.SettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import me.rerere.ai.provider.ModelPricing

/** Where an export lands unless the caller says otherwise; ~ is the agent workspace. */
private const val EXPORT_DEFAULT_DIR = "~/exports"

/**
 * P2-11d-2 - hand the accounting ledger to the agent as files.
 *
 * A read of the local ledger plus a write inside the agent workspace, so it is not in the
 * approval set: it cannot reach user data or the network, and the rows it writes are the
 * ones the user is already paying for.
 */
fun usageExportTool(usageRecordDao: UsageRecordDao): Tool = Tool(
    name = "usage_export",
    description = """
        Export the usage ledger to CSV and/or JSON. One row per model round trip: purpose, provider, model, input/output/cached tokens, and the cost frozen when the call was made. Metadata only, never message content. Writes into the agent workspace and replies with the file paths, the row count and the totals.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putJsonObject("format") {
                    put("type", "string")
                    put("description", "csv, json or both. Default both.")
                    put("enum", buildJsonArray {
                        add("csv")
                        add("json")
                        add("both")
                    })
                }
                putJsonObject("since_days") {
                    put("type", "integer")
                    put("description", "Only rows newer than this many days. Default 90, the retention window. 0 means every row still kept.")
                }
                putJsonObject("path") {
                    put("type", "string")
                    put("description", "Destination directory; ~ expands to the agent workspace. Default $EXPORT_DEFAULT_DIR")
                }
            }
        )
    },
    execute = { arguments ->
        val args = arguments as? JsonObject ?: JsonObject(emptyMap())
        val format = UsageExportFormat.fromWire(args["format"]?.jsonPrimitive?.contentOrNull)
            ?: UsageExportFormat.BOTH
        val sinceDays = args["since_days"]?.jsonPrimitive?.intOrNull ?: 90
        val now = System.currentTimeMillis()
        val sinceMs = if (sinceDays <= 0) 0L else now - sinceDays * 86_400_000L

        val rows = usageRecordDao.since(sinceMs, UsageLedgerDefaults.QUERY_LIMIT)
        val dir = File(
            AgentWorkspace.expand(
                args["path"]?.jsonPrimitive?.contentOrNull ?: EXPORT_DEFAULT_DIR
            )
        )
        val written = mutableListOf<File>()
        val failure = runCatching {
            if (!dir.isDirectory && !dir.mkdirs()) {
                error("cannot create directory ${dir.absolutePath}")
            }
            if (format.wantsCsv) {
                val file = File(dir, UsageExport.defaultFileName(UsageExportFormat.CSV, now))
                file.writeText(UsageExport.toCsv(rows))
                written += file
            }
            if (format.wantsJson) {
                val file = File(dir, UsageExport.defaultFileName(UsageExportFormat.JSON, now))
                file.writeText(UsageExport.toJson(rows))
                written += file
            }
        }.exceptionOrNull()

        val payload = if (failure != null) {
            buildJsonObject {
                put("ok", false)
                put("error", "export_failed")
                put("detail", failure.message ?: failure::class.simpleName.orEmpty())
            }
        } else {
            val priced = rows.mapNotNull { it.costMicros }
            buildJsonObject {
                put("ok", true)
                put("rows", rows.size)
                put("truncated_at_limit", rows.size >= UsageLedgerDefaults.QUERY_LIMIT)
                putJsonArray("files") {
                    written.forEach { add(it.absolutePath) }
                }
                putJsonObject("window") {
                    put("since_ms", sinceMs)
                    put("until_ms", now)
                }
                putJsonObject("totals") {
                    put("input_tokens", rows.sumOf { it.inputTokens })
                    put("output_tokens", rows.sumOf { it.outputTokens })
                    put("cached_tokens", rows.sumOf { it.cachedTokens })
                    put("cost_micros", priced.sum())
                    put("costed_rows", priced.size)
                    put("unpriced_rows", rows.size - priced.size)
                }
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    },
)

// ---------------------------------------------------------------- price table (P2-11d-3)

private val priceTableJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** Flatten a JsonObject into the single text part every tool in this file returns. */
private fun usageTextPart(payload: JsonObject): List<UIMessagePart> =
    listOf(UIMessagePart.Text(payload.toString()))

private fun rejectedToJson(items: List<RejectedEntrySpec>): JsonArray = buildJsonArray {
    items.forEach { r ->
        add(
            buildJsonObject {
                put("index", r.index)
                put("provider_name", r.providerName)
                put("model_id", r.modelId)
                put("reason", r.reason)
            }
        )
    }
}

private fun priceEntrySchema(): JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("providerName") {
            put("type", "string")
            put("description", "Provider name as shown in model providers, for example DeepSeek. Matched case-insensitively.")
        }
        putJsonObject("modelId") {
            put("type", "string")
            put("description", "API model id, for example deepseek-chat. Matched case-insensitively.")
        }
        listOf(
            "inputPerMillion",
            "outputPerMillion",
            "cacheHitPerMillion",
            "cacheWritePerMillion",
            "offPeakInputPerMillion",
            "offPeakOutputPerMillion",
            "offPeakCacheHitPerMillion",
            "offPeakCacheWritePerMillion",
        ).forEach { field ->
            putJsonObject(field) {
                put("type", "number")
                put("description", "USD per million tokens. Omit when the provider does not charge for it.")
            }
        }
        putJsonObject("peakWindows") {
            put("type", "array")
            put("description", "Intervals on a local clock where the peak rates above apply. Outside them the off-peak rates apply; with no windows and no off-peak rates the peak rates apply around the clock.")
            putJsonObject("items") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("startMinute") {
                        put("type", "integer")
                        put("description", "Minutes from local midnight, 0..1439, inclusive.")
                    }
                    putJsonObject("endMinute") {
                        put("type", "integer")
                        put("description", "Minutes from local midnight, 0..1439; may be smaller than startMinute to wrap past midnight.")
                    }
                    putJsonObject("zoneOffsetMinutes") {
                        put("type", "integer")
                        put("description", "UTC offset of the priced clock in minutes, for example 480 for Beijing.")
                    }
                    putJsonObject("daysOfWeek") {
                        put("type", "array")
                        put("description", "ISO weekdays 1=Mon..7=Sun. Empty or omitted means every day.")
                        put("items", buildJsonObject { put("type", "integer") })
                    }
                }
            }
        }
    }
    putJsonArray("required") {
        add("providerName")
        add("modelId")
    }
}

/**
 * Read half of the price-table pair. No approval: it reads settings and returns text.
 */
fun usageGetPricesTool(settingsStore: SettingsStore): Tool = Tool(
    name = "usage_get_prices",
    description = """
        Read the model price table used to cost the usage ledger: one entry per priced model with its per-million rates, off-peak rates and peak windows, a content-derived table version, and the models that carry no price yet. Read-only.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(properties = buildJsonObject { })
    },
    execute = {
        val settings = settingsStore.settingsFlow.first()
        val entries = mutableListOf<PriceEntrySpec>()
        val unpriced = mutableListOf<String>()
        var total = 0
        settings.providers.forEach { provider ->
            provider.models.forEach { model ->
                total += 1
                val pricing = model.pricing
                if (pricing == null) {
                    unpriced += "${provider.name}/${model.modelId}"
                } else {
                    entries += UsagePriceTable.fromModelPricing(provider.name, model.modelId, pricing)
                }
            }
        }
        usageTextPart(
            buildJsonObject {
                put("ok", true)
                put("table_version_id", UsagePriceTable.tableVersionId(entries))
                put("models_total", total)
                put("models_priced", entries.size)
                put("models_unpriced", unpriced.size)
                putJsonArray("unpriced_models") { unpriced.take(50).forEach { add(it) } }
                put("table", priceTableJson.encodeToJsonElement(ListSerializer(PriceEntrySpec.serializer()), entries))
            }
        )
    },
)

/**
 * Write half: replaces the whole table, audits the attempt, and refuses anything that did
 * not pass UsagePriceTable.validate. Gated by ToolApprovalDefaults.DANGEROUS_TOOLS.
 */
fun usageSetPricesTool(
    settingsStore: SettingsStore,
    agentRunRepository: AgentRunRepository,
): Tool = Tool(
    name = "usage_set_prices",
    description = """
        Replace the entire model price table. Prices are USD per million tokens; they are used to freeze the cost of every later model call in the usage ledger. The whole table is replaced in one version, so every model not listed loses its price and stops being costed. Requires replace_all_prices=true and user approval, and the attempt is written to the audit log.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putJsonObject("replace_all_prices") {
                    put("type", "boolean")
                    put("description", "Must be true. Confirms the whole table is replaced rather than merged.")
                }
                putJsonObject("version") {
                    put("type", "string")
                    put("description", "Short label for this table, echoed back and stored on the audit row.")
                }
                putJsonObject("note") {
                    put("type", "string")
                    put("description", "Why the table changed. Stored on the audit row.")
                }
                putJsonObject("entries") {
                    put("type", "array")
                    put("description", "The complete price table. An entry with no rate at all is rejected.")
                    put("items", priceEntrySchema())
                }
            },
            required = listOf("replace_all_prices", "entries"),
        )
    },
    execute = { arguments ->
        val args = arguments as? JsonObject ?: JsonObject(emptyMap())
        if (args["replace_all_prices"]?.jsonPrimitive?.booleanOrNull != true) {
            return@Tool usageTextPart(
                buildJsonObject {
                    put("ok", false)
                    put("error", "confirmation_required")
                    put("detail", "Pass replace_all_prices=true. This call replaces the whole table and every model left out loses its price.")
                }
            )
        }
        val spec = runCatching {
            priceTableJson.decodeFromString(PriceTableSpec.serializer(), args.toString())
        }.getOrElse { error ->
            return@Tool usageTextPart(
                buildJsonObject {
                    put("ok", false)
                    put("error", "invalid_payload")
                    put("detail", error.message ?: "could not parse the price table")
                }
            )
        }

        val validation = UsagePriceTable.validate(spec)
        val tableVersion = UsagePriceTable.tableVersionId(validation.accepted)
        val auditId = agentRunRepository.open(
            kind = AgentRunKind.PriceTable,
            domainId = tableVersion,
            metadata = buildJsonObject {
                put("version_label", spec.version.orEmpty().take(80))
                put("table_version_id", tableVersion)
                put("note", spec.note.orEmpty().take(200))
                put("accepted_entries", validation.accepted.size)
                put("rejected_entries", validation.rejected.size)
                put("rejected_reasons", rejectedToJson(validation.rejected.take(20)))
            },
        )

        if (!validation.isClean) {
            agentRunRepository.markTerminal(
                auditId,
                AgentRunStatus.failed,
                "rejected ${validation.rejected.size} entries; nothing written",
            )
            return@Tool usageTextPart(
                buildJsonObject {
                    put("ok", false)
                    put("error", "invalid_table")
                    put("table_version_id", tableVersion)
                    put("audit_run_id", auditId)
                    put("rejected_count", validation.rejected.size)
                    put("rejected", rejectedToJson(validation.rejected))
                    put("detail", "Nothing was written. Fix the listed entries and call again.")
                }
            )
        }

        val outcome = runCatching {
            applyPriceTable(settingsStore, validation.accepted)
        }.getOrElse { error ->
            agentRunRepository.markTerminal(auditId, AgentRunStatus.failed, error.message)
            return@Tool usageTextPart(
                buildJsonObject {
                    put("ok", false)
                    put("error", "write_failed")
                    put("audit_run_id", auditId)
                    put("detail", error.message ?: "unknown failure while writing settings")
                }
            )
        }

        agentRunRepository.markTerminal(auditId, AgentRunStatus.succeeded)
        usageTextPart(
            buildJsonObject {
                put("ok", true)
                put("table_version_id", tableVersion)
                put("audit_run_id", auditId)
                put("applied_count", outcome.applied.size)
                putJsonArray("applied") { outcome.applied.take(50).forEach { add(it) } }
                put("cleared_count", outcome.cleared.size)
                putJsonArray("cleared") { outcome.cleared.take(50).forEach { add(it) } }
                put("unmatched_count", outcome.unmatched.size)
                put("unmatched", rejectedToJson(outcome.unmatched))
            }
        )
    },
)

private class PriceTableOutcome(
    val applied: List<String>,
    val cleared: List<String>,
    val unmatched: List<RejectedEntrySpec>,
)

/**
 * Whole-table replacement against Settings.providers.
 *
 * Keyed by provider and model UUIDs, never by name, so two providers that share a display
 * name cannot receive each other pricing. A model that is absent from [accepted] ends up
 * with pricing = null: unpaid, which the resolver records as "no cost known" rather than 0.
 */
private suspend fun applyPriceTable(
    settingsStore: SettingsStore,
    accepted: List<PriceEntrySpec>,
): PriceTableOutcome {
    val settings = settingsStore.settingsFlow.first()
    val index = settings.providers.flatMap { provider -> provider.models.map { provider to it } }

    val wanted = mutableMapOf<String, ModelPricing>()
    val applied = mutableListOf<String>()
    val unmatched = mutableListOf<RejectedEntrySpec>()

    accepted.forEachIndexed { position, entry ->
        val hits = index.filter { (provider, model) ->
            provider.name.equals(entry.providerName, ignoreCase = true) &&
                model.modelId.equals(entry.modelId, ignoreCase = true)
        }
        if (hits.isEmpty()) {
            unmatched += RejectedEntrySpec(position, entry.providerName, entry.modelId, "no model matches")
            return@forEachIndexed
        }
        val pricing = UsagePriceTable.toModelPricing(entry) ?: return@forEachIndexed
        hits.forEach { (provider, model) ->
            wanted["${provider.id}:${model.id}"] = pricing
            applied += "${provider.name}/${model.modelId}"
        }
    }

    val cleared = mutableListOf<String>()
    var changed = false
    val newProviders = settings.providers.map { provider ->
        var updated = provider
        provider.models.forEach { model ->
            val next = wanted["${provider.id}:${model.id}"]
            if (next == model.pricing) return@forEach
            if (next == null && model.pricing != null) {
                cleared += "${provider.name}/${model.modelId}"
            }
            changed = true
            // ProviderSetting is sealed: only the concrete subclass knows how to rebuild
            // itself around a changed model, and that is what editModel is for.
            updated = updated.editModel(model.copy(pricing = next))
        }
        updated
    }
    if (changed) {
        settingsStore.update { it.copy(providers = newProviders) }
    }
    return PriceTableOutcome(applied, cleared, unmatched)
}
