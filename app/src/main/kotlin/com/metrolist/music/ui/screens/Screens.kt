/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.metrolist.music.R

@Immutable
sealed class Screens(
    @StringRes val titleId: Int,
    @DrawableRes val iconIdInactive: Int,
    @DrawableRes val iconIdActive: Int,
    val route: String,
) {
    object Home : Screens(
        titleId = R.string.home,
        iconIdInactive = R.drawable.home_outlined,
        iconIdActive = R.drawable.home_filled,
        route = "home"
    )

    object Playlists : Screens(
        titleId = R.string.filter_playlists,
        iconIdInactive = R.drawable.queue_music,
        iconIdActive = R.drawable.queue_music,
        route = "playlists"
    )

    object Intelligence : Screens(
        titleId = R.string.intelligence_tab,
        iconIdInactive = R.drawable.auto_awesome_outlined,
        iconIdActive = R.drawable.auto_awesome_filled,
        route = "intelligence"
    )

    object Search : Screens(
        titleId = R.string.search,
        iconIdInactive = R.drawable.search,
        iconIdActive = R.drawable.search,
        route = "search_input"
    )

    object ListenTogether : Screens(
        titleId = R.string.together,
        iconIdInactive = R.drawable.group_outlined,
        iconIdActive = R.drawable.group_filled,
        route = "listen_together"
    )

    object Library : Screens(
        titleId = R.string.filter_library,
        iconIdInactive = R.drawable.library_music_outlined,
        iconIdActive = R.drawable.library_music_filled,
        route = "library"
    )

    companion object {
        /** Every top-level destination, in tab order. Listen Together comes last when it is a tab. */
        val MainScreens: List<Screens> by lazy { listOf(Home, Playlists, Intelligence, Search, Library, ListenTogether) }

        /**
         * The tabs shown in the bottom bar or rail. Listen Together lives in the top bar by default
         * and is a tab only when the user turned that setting off.
         */
        fun navigationItems(listenTogetherInTopBar: Boolean): List<Screens> =
            if (listenTogetherInTopBar) MainScreens.filter { it != ListenTogether } else MainScreens
    }
}
