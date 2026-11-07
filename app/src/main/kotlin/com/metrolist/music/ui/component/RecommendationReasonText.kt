/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.metrolist.music.R
import com.metrolist.music.taste.RecommendationReason

@Composable
fun recommendationReasonText(reason: RecommendationReason): String =
    when (reason) {
        RecommendationReason.Liked -> stringResource(R.string.reason_liked)
        is RecommendationReason.OnRepeat -> stringResource(R.string.reason_on_repeat, reason.listensThisMonth)
        is RecommendationReason.FavoriteArtist -> stringResource(R.string.reason_favorite_artist, reason.artistName)
        is RecommendationReason.SimilarTo ->
            if (reason.seedLiked) {
                stringResource(R.string.reason_similar_to_liked, reason.songTitle)
            } else {
                stringResource(R.string.reason_similar_to_played, reason.songTitle)
            }
        is RecommendationReason.SameArtist -> stringResource(R.string.reason_same_artist, reason.artistName)
        RecommendationReason.BasedOnListening -> stringResource(R.string.reason_based_on_listening)
    }
