package me.rerere.rikkahub.ui.pages.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class CleanTopBarTokenFormatTest {

    @Test
    fun `chinese keeps the plain number below ten thousand`() {
        assertEquals("0", formatTokensChinese(0L))
        assertEquals("1234", formatTokensChinese(1234L))
        assertEquals("9876", formatTokensChinese(9876L))
        assertEquals("9999", formatTokensChinese(9999L))
    }

    @Test
    fun `chinese counts in wan with up to two decimals and no trailing zeros`() {
        assertEquals("1万", formatTokensChinese(10_000L))
        assertEquals("14.38万", formatTokensChinese(143_793L))
        assertEquals("217.78万", formatTokensChinese(2_177_838L))
        assertEquals("10万", formatTokensChinese(100_000L))
        assertEquals("10.5万", formatTokensChinese(105_000L))
        assertEquals("1.05万", formatTokensChinese(10_500L))
        // Half-up: 1.005万 -> 1.01万, 1.0049万 -> 1万.
        assertEquals("1.01万", formatTokensChinese(10_050L))
        assertEquals("1万", formatTokensChinese(10_049L))
        assertEquals("9999.99万", formatTokensChinese(99_999_900L))
    }

    @Test
    fun `wan that rounds up to ten thousand becomes yi`() {
        assertEquals("1亿", formatTokensChinese(99_999_950L))
        assertEquals("1亿", formatTokensChinese(99_999_999L))
        assertEquals("1億", formatTokensChinese(99_999_999L, traditional = true))
    }

    @Test
    fun `chinese counts in yi from one hundred million`() {
        assertEquals("1亿", formatTokensChinese(100_000_000L))
        assertEquals("1.23亿", formatTokensChinese(123_456_789L))
        assertEquals("1.24亿", formatTokensChinese(123_500_000L))
        assertEquals("25亿", formatTokensChinese(2_500_000_000L))
    }

    @Test
    fun `traditional chinese uses wan and yi in traditional characters`() {
        assertEquals("14.38萬", formatTokensChinese(143_793L, traditional = true))
        assertEquals("1億", formatTokensChinese(100_000_000L, traditional = true))
        assertTrue(isTraditionalChineseLocale(Locale.TRADITIONAL_CHINESE))
        assertTrue(isTraditionalChineseLocale(Locale.forLanguageTag("zh-HK")))
        assertFalse(isTraditionalChineseLocale(Locale.SIMPLIFIED_CHINESE))
        assertTrue(isChineseLocale(Locale.SIMPLIFIED_CHINESE))
        assertFalse(isChineseLocale(Locale.US))
    }

    @Test
    fun `token text keeps the unit word and uses compact form outside chinese`() {
        assertEquals(" / 14.38万 tokens", cleanTopBarTokenText(143_793L, chinese = true))
        assertEquals(" / 217.78万 tokens", cleanTopBarTokenText(2_177_838L, chinese = true))
        assertEquals(" / 1亿 tokens", cleanTopBarTokenText(100_000_000L, chinese = true))
        assertEquals(" / 14.38萬 tokens", cleanTopBarTokenText(143_793L, chinese = true, traditional = true))
        assertEquals(" / 29.2K tokens", cleanTopBarTokenText(29_200L, chinese = false))
        assertEquals(" / 512 tokens", cleanTopBarTokenText(512L, chinese = false))
        assertEquals(" / 9876 tokens", cleanTopBarTokenText(9876L, chinese = true))
        assertEquals(" / 0 tokens", cleanTopBarTokenText(0L, chinese = true))
        assertEquals(" / 1 token", cleanTopBarTokenText(1L, chinese = true))
        assertEquals(" / 1 token", cleanTopBarTokenText(1L, chinese = false))
    }
}
