package com.clma.app.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoSamplingTest {
    private fun sample(index: Int, shade: Int) =
        VideoSampling.Sample(index.toLong(), IntArray(4) { shade * 0x010101 })

    @Test
    fun `stationary clips use one frame while rapid changes use the budget`() {
        assertEquals(listOf(0L), VideoSampling.select(List(30) { sample(it, 100) }, 12))
        val changing = List(30) { sample(it, if (it % 2 == 0) 20 else 200) }
        val frames = VideoSampling.select(changing, 8)
        assertEquals(8, frames.size)
        assertEquals(frames.sorted().distinct(), frames)
    }

    @Test
    fun `frames cluster near changes and retain an opening still`() {
        val samples = List(30) {
            sample(it, if (it in 10..15 && it % 2 == 0) 240 else 20)
        }
        val frames = VideoSampling.select(samples, 5)
        assertEquals(0L, frames.first())
        assertTrue(frames.count { it in 9..16 } >= 3)
    }

    @Test
    fun `time ranges and duration budget are bounded`() {
        assertEquals(listOf(3, 5, 8, 12), listOf(500L, 4_000L, 30_000L, 1_000_000L).map(VideoSampling::overviewCount))
        assertEquals(listOf(1_000_000L, 1_500_000L, 2_000_000L), VideoSampling.times(
            VideoSampling.detailRange(1.0, 2.0)!!, 3,
        ))
        assertEquals(listOf(10L), VideoSampling.times(10L..10L, 6))
        assertNull(VideoSampling.detailRange(0.0, Double.POSITIVE_INFINITY))
        assertNull(VideoSampling.detailRange(Double.NaN, 10.0))
        assertNull(VideoSampling.detailRange(5.0, 5.0))
        assertNull(VideoSampling.detailRange(-1.0, 1.0))
        assertNull(VideoSampling.detailRange(5.0, 66.0))
    }
}
