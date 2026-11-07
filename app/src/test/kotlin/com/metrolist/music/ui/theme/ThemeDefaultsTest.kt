/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.theme

import androidx.compose.ui.graphics.toArgb
import com.metrolist.music.ui.screens.settings.DarkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ThemeDefaultsTest {
    @Test
    fun untouchedInstallIsDarkWithAFixedAccent() {
        assertEquals(DarkMode.ON, ThemeDefaults.darkMode)
        assertFalse(ThemeDefaults.DYNAMIC_THEME)
        assertFalse(ThemeDefaults.PURE_BLACK)
        assertEquals(0xFF1E6F6A.toInt(), ThemeDefaults.seedColor.toArgb())
    }

    /** The stored [DefaultThemeColor] means "system wallpaper colors"; the default seed must not collide with it. */
    @Test
    fun defaultSeedIsNotTheWallpaperMarker() {
        assertNotEquals(DefaultThemeColor.toArgb(), ThemeDefaults.seedColor.toArgb())
    }
}
