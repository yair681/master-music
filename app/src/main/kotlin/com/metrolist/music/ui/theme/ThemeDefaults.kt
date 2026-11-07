/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.theme

import androidx.compose.ui.graphics.Color
import com.metrolist.music.ui.screens.settings.DarkMode

/**
 * Theme values for users who never changed the theme settings. Every place that reads
 * [com.metrolist.music.constants.DarkModeKey], [com.metrolist.music.constants.DynamicThemeKey],
 * [com.metrolist.music.constants.PureBlackKey] or [com.metrolist.music.constants.SelectedThemeColorKey]
 * uses these as the default, so a stored choice always wins and an untouched install looks the
 * same on every screen: dark, a fixed deep teal accent, no wallpaper or album-art colors, and
 * pure black only when the user asks for it.
 */
object ThemeDefaults {
    val darkMode: DarkMode = DarkMode.ON

    /** Album-art colors while playing (the "dynamic theme" switch). */
    const val DYNAMIC_THEME: Boolean = false

    const val PURE_BLACK: Boolean = false

    /**
     * Deep teal seed color. It must stay different from [DefaultThemeColor], which is the stored
     * marker for "use the system wallpaper colors".
     */
    val seedColor: Color = Color(SEED_COLOR_ARGB)

    const val SEED_COLOR_ARGB: Long = 0xFF1E6F6A
}
