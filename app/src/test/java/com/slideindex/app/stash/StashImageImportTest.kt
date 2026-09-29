package com.slideindex.app.stash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StashImageImportTest {

    @Test
    fun scaledSize_smallImage_isLeftAlone() {
        assertNull(StashImageImport.scaledSize(800, 600, 1600))
        assertNull(StashImageImport.scaledSize(1600, 1600, 1600))
    }

    @Test
    fun scaledSize_landscape_limitsWidthKeepingRatio() {
        assertEquals(1600 to 900, StashImageImport.scaledSize(3200, 1800, 1600))
    }

    @Test
    fun scaledSize_portrait_limitsHeightKeepingRatio() {
        assertEquals(900 to 1600, StashImageImport.scaledSize(1800, 3200, 1600))
    }

    @Test
    fun scaledSize_extremeRatio_neverCollapsesToZero() {
        assertEquals(1600 to 1, StashImageImport.scaledSize(16000, 3, 1600))
    }

    @Test
    fun scaledSize_nonPositiveLimit_isIgnored() {
        assertNull(StashImageImport.scaledSize(4000, 3000, 0))
    }
}
