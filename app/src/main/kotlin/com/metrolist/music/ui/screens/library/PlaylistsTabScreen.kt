/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.library

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import com.metrolist.music.constants.LibraryViewType
import com.metrolist.music.constants.PlaylistViewTypeKey
import com.metrolist.music.utils.rememberEnumPreference

/**
 * The Playlists tab: the same playlists list as the Library's playlists filter, with the same
 * view type preference, but without the Library filter chips.
 */
@Composable
fun PlaylistsTabScreen(navController: NavController) {
    val (viewType, onViewTypeChange) = rememberEnumPreference(PlaylistViewTypeKey, LibraryViewType.GRID)
    LibraryPlaylistsScreen(
        navController = navController,
        filterContent = {},
        viewType = viewType,
        onViewTypeChange = onViewTypeChange,
    )
}
