/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreensTest {
    @Test
    fun tabsWithListenTogetherInTheTopBar() {
        assertEquals(
            listOf(Screens.Home, Screens.Playlists, Screens.Intelligence, Screens.Search, Screens.Library),
            Screens.navigationItems(listenTogetherInTopBar = true),
        )
    }

    @Test
    fun listenTogetherIsTheLastTabWhenNotInTheTopBar() {
        assertEquals(
            listOf(
                Screens.Home,
                Screens.Playlists,
                Screens.Intelligence,
                Screens.Search,
                Screens.Library,
                Screens.ListenTogether,
            ),
            Screens.navigationItems(listenTogetherInTopBar = false),
        )
    }

    @Test
    fun tabRoutesAreUniqueAndPlaylistsIsNotTheLibraryRoute() {
        val routes = Screens.MainScreens.map { it.route }
        assertEquals(routes.size, routes.toSet().size)
        assertEquals("playlists", Screens.Playlists.route)
        assertEquals("intelligence", Screens.Intelligence.route)
    }
}
