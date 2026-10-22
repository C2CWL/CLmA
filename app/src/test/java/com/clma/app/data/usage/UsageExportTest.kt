package me.rerere.rikkahub.data.usage

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageExportTest {

    private fun row(
        id: String = "row-1",
        modelId: String? = "deepseek-chat",
        purpose: String = "MAIN",
        cachedTokens: Int = 800,
        cachedReported: Boolean = true,
        costMicros: Long? = 1232,
    ) = UsageRecordEntity(
        id = id,
        createdAtMs = 1_700_000_000_000,
        purpose = purpose,
        providerId = "DeepSeek",
        modelId = modelId,
        conversationId = "conv-1",
        inputTokens = 1000,
        outputTokens = 100,
        totalTokens = 1100,
        cachedTokens = cachedTokens,
        cachedTokensReported = cachedReported,
        costMicros = costMicros,
        priceVersionId = "abcd1234",
    )

    @Test
    fun `csv starts with the documented header and one line per row`() {
        val csv = UsageExport.toCsv(listOf(row(), row(id = "row-2")))
        val lines = csv.trim().split("\n")
        assertEquals(UsageExport.CSV_HEADER.joinToString(","), lines[0])
        assertEquals(3, lines.size)
    }

    @Test
    fun `csv fields are column aligned with the header`() {
        val csv = UsageExport.toCsv(listOf(row()))
        val header = csv.trim().split("\n")[0].split(",")
        val values = csv.trim().split("\n")[1].split(",")
        assertEquals(header.size, values.size)
        val modelIndex = header.indexOf("model_id")
        assertEquals("deepseek-chat", values[modelIndex])
        assertEquals("true", values[header.indexOf("cached_tokens_reported")])
    }

    @Test
    fun `a field containing a comma or a quote is escaped, not silently shifted`() {
        val csv = UsageExport.toCsv(listOf(row(modelId = "vendor,model \"x\"")))
        val values = csv.trim().split("\n")[1]
        assertTrue(values.contains("\"vendor,model \"\"x\"\"\""))
    }

    @Test
    fun `null columns export as empty cells instead of the word null`() {
        val csv = UsageExport.toCsv(listOf(row(modelId = null, costMicros = null)))
        val header = csv.trim().split("\n")[0].split(",")
        val values = csv.trim().split("\n")[1].split(",")
        assertEquals("", values[header.indexOf("model_id")])
        assertEquals("", values[header.indexOf("cost_micros")])
    }

    @Test
    fun `json round trips every field including the provenance flags`() {
        val encoded = UsageExport.toJson(listOf(row(cachedReported = false, costMicros = null)))
        val decoded = Json.decodeFromString<List<UsageExportRow>>(encoded)
        assertEquals(1, decoded.size)
        val first = decoded.first()
        assertEquals("row-1", first.id)
        assertEquals("deepseek-chat", first.modelId)
        assertEquals(800, first.cachedTokens)
        assertEquals(false, first.cachedTokensReported)
        assertEquals(null, first.costMicros)
        assertEquals(1232, decoded.first().costMicros ?: 1232)
    }

    @Test
    fun `an unreported cache stays distinguishable in the exported json`() {
        val decoded = Json.decodeFromString<List<UsageExportRow>>(
            UsageExport.toJson(listOf(row(cachedTokens = 0, cachedReported = false)))
        ).first()
        assertEquals(false, decoded.cachedTokensReported)
        assertEquals(0, decoded.cachedTokens)
    }

    @Test
    fun `the file name is deterministic and free of characters a shell would choke on`() {
        val at = java.time.LocalDateTime.of(2026, 10, 2, 13, 30, 0)
            .toInstant(java.time.ZoneOffset.UTC)
            .toEpochMilli()
        assertEquals("usage-ledger-20261002T133000Z.csv", UsageExport.defaultFileName(UsageExportFormat.CSV, at))
        assertEquals("usage-ledger-20261002T133000Z.json", UsageExport.defaultFileName(UsageExportFormat.JSON, at))
    }

    @Test
    fun `format parsing accepts the documented words and rejects the rest`() {
        assertEquals(UsageExportFormat.BOTH, UsageExportFormat.fromWire("both"))
        assertEquals(UsageExportFormat.CSV, UsageExportFormat.fromWire(" CSv "))
        assertEquals(null, UsageExportFormat.fromWire("xml"))
        assertEquals(null, UsageExportFormat.fromWire(null))
    }
}
