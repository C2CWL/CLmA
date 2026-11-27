package com.clma.app.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoLinksTest {
    @Test
    fun `recognizes direct files and preserves signed queries`() {
        val url = "https://example.test/clip.MP4?token=abc"
        assertEquals(listOf(VideoLinks.Link(url, true)), VideoLinks.find("看看（$url）。"))
        val signed = "https://example.test/download?token=abc"
        assertEquals(listOf(VideoLinks.Link(signed, true)), VideoLinks.find("请看视频 $signed"))
        assertEquals(listOf(VideoLinks.Link(signed, false)), VideoLinks.find(signed))
        assertTrue(VideoLinks.find("Reference page $signed").isEmpty())
    }

    @Test
    fun `identifies platform pages by exact host or subdomain`() {
        assertTrue(VideoLinks.isPlatform("www.bilibili.com"))
        assertTrue(VideoLinks.isPlatform("v.douyin.com"))
        assertFalse(VideoLinks.isPlatform("bilibili.com.example.test"))
        assertEquals(listOf(VideoLinks.Link("https://b23.tv/a", true)),
            VideoLinks.find("看看 https://b23.tv/a"))
    }
}
