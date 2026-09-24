package com.aniblaze.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.Surface
import com.aniblaze.aggregator.model.Anime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.aniblaze.desktop.Recommender

/** All metadata comes with the scoring result. Only cached thumbnail image loads. */
@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun RecommendationReason(reason: Recommender.Explanation, onOpen: ((Anime) -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().height(60.dp).padding(top = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        reason.sourceAnime.filter { it.anime.poster.isNotBlank() }.take(3).forEach { source ->
            key(source.anime.id) {
                TooltipArea(tooltip = {
                    Surface(color = Surface2, shape = RoundedCornerShape(5.dp)) {
                        Text(source.anime.title + (source.rating?.let { " · $it/5" } ?: ""),
                            modifier = Modifier.padding(8.dp), color = TextPrimary, fontSize = 12.sp)
                    }
                }, delayMillis = 600) {
                AsyncImage(model = posterUrl(source.anime.poster, 80),
                    contentDescription = "Основание рекомендации: ${source.anime.title}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.width(24.dp).height(36.dp).clip(RoundedCornerShape(3.dp))
                        .then(if (onOpen != null) Modifier.clickable(onClickLabel = "Открыть ${source.anime.title}") {
                            onOpen(source.anime)
                        } else Modifier))
                }
            }
        }
        Text(reason.text, color = AccentOrange, fontSize = 12.sp, lineHeight = 16.sp,
            maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}
