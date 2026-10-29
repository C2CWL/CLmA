package com.clma.app.ui.pages.chat

import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DrawerDensityTest {

    @Test
    fun `font scale change does not change the drawer density`() {
        val normal = drawerAnchorDensity(Density(density = 2.75f, fontScale = 1.0f))
        val larger = drawerAnchorDensity(Density(density = 2.75f, fontScale = 1.3f))
        assertEquals(normal, larger)
    }

    @Test
    fun `drawer density keeps the pixel density`() {
        val result = drawerAnchorDensity(Density(density = 2.75f, fontScale = 1.3f))
        assertEquals(2.75f, result.density, 0f)
        assertEquals(1f, result.fontScale, 0f)
    }

    @Test
    fun `pixel density change still changes the drawer density`() {
        assertNotEquals(
            drawerAnchorDensity(Density(density = 2.75f, fontScale = 1.0f)),
            drawerAnchorDensity(Density(density = 3.0f, fontScale = 1.0f)),
        )
    }
}
