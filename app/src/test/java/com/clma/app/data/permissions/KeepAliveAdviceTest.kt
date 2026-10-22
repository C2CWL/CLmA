package me.rerere.rikkahub.data.permissions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the vendor classification that decides whether we proactively show the "keep alive in
 * the background" guidance. The names are the real `Build.MANUFACTURER` / `Build.BRAND` values
 * reported by these devices (e.g. vivo reports manufacturer `vivo`, brand `vivo`; iQOO reports
 * brand `iQOO`; Xiaomi sub-brands report brand `Redmi`/`POCO`).
 */
class KeepAliveAdviceTest {

    @Test
    fun vivo_and_iqoo_map_to_vivo() {
        assertEquals(KeepAliveVendor.VIVO, keepAliveVendorOf("vivo", "vivo"))
        assertEquals(KeepAliveVendor.VIVO, keepAliveVendorOf("vivo", "iQOO"))
        assertEquals(KeepAliveVendor.VIVO, keepAliveVendorOf(null, "iQOO"))
    }

    @Test
    fun xiaomi_family_maps_to_xiaomi() {
        assertEquals(KeepAliveVendor.XIAOMI, keepAliveVendorOf("Xiaomi", "Redmi"))
        assertEquals(KeepAliveVendor.XIAOMI, keepAliveVendorOf("Xiaomi", "POCO"))
        assertEquals(KeepAliveVendor.XIAOMI, keepAliveVendorOf("xiaomi", null))
    }

    @Test
    fun huawei_family_maps_to_huawei() {
        assertEquals(KeepAliveVendor.HUAWEI, keepAliveVendorOf("HUAWEI", "HUAWEI"))
        assertEquals(KeepAliveVendor.HUAWEI, keepAliveVendorOf("HONOR", "HONOR"))
    }

    @Test
    fun oppo_family_maps_to_oppo() {
        assertEquals(KeepAliveVendor.OPPO, keepAliveVendorOf("OPPO", null))
        assertEquals(KeepAliveVendor.OPPO, keepAliveVendorOf("OnePlus", "OnePlus"))
        assertEquals(KeepAliveVendor.OPPO, keepAliveVendorOf(null, "realme"))
    }

    @Test
    fun unknown_or_missing_values_fall_back_to_generic() {
        assertEquals(KeepAliveVendor.GENERIC, keepAliveVendorOf("Google", "Pixel"))
        assertEquals(KeepAliveVendor.GENERIC, keepAliveVendorOf("samsung", "samsung"))
        assertEquals(KeepAliveVendor.GENERIC, keepAliveVendorOf(null, null))
        assertEquals(KeepAliveVendor.GENERIC, keepAliveVendorOf("", ""))
    }

    @Test
    fun matching_is_case_insensitive() {
        assertEquals(KeepAliveVendor.VIVO, keepAliveVendorOf("ViVo", "VIVO"))
        assertEquals(KeepAliveVendor.XIAOMI, keepAliveVendorOf("XIAOMI", "redmi"))
    }

    @Test
    fun only_known_oems_are_flagged_aggressive() {
        assertTrue(KeepAliveVendor.VIVO.isAggressive)
        assertTrue(KeepAliveVendor.XIAOMI.isAggressive)
        assertTrue(KeepAliveVendor.HUAWEI.isAggressive)
        assertTrue(KeepAliveVendor.OPPO.isAggressive)
        assertFalse(KeepAliveVendor.GENERIC.isAggressive)
    }
}
