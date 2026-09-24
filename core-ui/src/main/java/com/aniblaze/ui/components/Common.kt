package com.aniblaze.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aniblaze.ui.theme.AccentOrange
import com.aniblaze.ui.theme.TextPrimary
import com.aniblaze.ui.theme.TextSecondary

/**
 * Routes a poster through the weserv image CDN: it fetches the original on its own
 * fast, un-throttled backend and serves a small WebP from Cloudflare — the same
 * "small image from a fast CDN" trick that makes TMDB cinema posters load instantly.
 * Anixart's own CDN (s.anixmirai.com) serves full-size JPEGs that saturate a slow
 * connection and time out into grey cards; this fixes that at the root. TMDB URLs are
 * already sized/fast and pass through untouched.
 *
 * На телефоне выигрыш больше, чем на ПК: мобильный канал уже, а память под битмапы
 * жёстче ограничена — полноразмерный JPEG раскладывается в несколько мегабайт на
 * карточку.
 */
fun posterUrl(raw: String, width: Int = 320): String {
    if (raw.isBlank() || raw.contains("image.tmdb.org")) return raw
    val origin = when {
        raw.startsWith("https://") -> "ssl:" + raw.removePrefix("https://")
        raw.startsWith("http://") -> raw.removePrefix("http://")
        else -> return raw
    }
    val enc = java.net.URLEncoder.encode(origin, "UTF-8")
    return "https://images.weserv.nl/?url=$enc&w=${posterStep(width)}&output=webp&q=72&we"
}

/**
 * Ширина, которую реально просим у CDN, — округлённая вверх до ступени.
 *
 * Просить точный размер ячейки нельзя: он зависит от ширины экрана, и каждый поворот
 * телефона (или изменение числа колонок, или split-screen) порождал бы новый адрес,
 * то есть промах кэша и повторную загрузку уже скачанного. Ступени 160/240/320/480/640
 * покрывают всё от восьми колонок на планшете до двух на узком телефоне, а адресов
 * остаётся пять.
 */
internal fun posterStep(width: Int): Int =
    POSTER_STEPS.firstOrNull { it >= width } ?: POSTER_STEPS.last()

private val POSTER_STEPS = intArrayOf(160, 240, 320, 480, 640)

/** The brand's section marker: a short gradient bar that opens every row title. */
@Composable
fun AccentBar(modifier: Modifier = Modifier) {
    Box(
        modifier
            .width(4.dp)
            .height(18.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(BrandGradient),
    )
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AccentBar()
        Spacer(Modifier.width(10.dp))
        Text(
            text = title,
            color = TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = true,
        enter = fadeIn(tween(300)),
    ) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = AccentOrange)
        }
    }
}

@Composable
fun ErrorState(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    AnimatedVisibility(
        visible = true,
        enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 4 },
    ) {
        Column(
            modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                message,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                fontSize = 15.sp,
            )
            if (onRetry != null) {
                Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
                    Text("Повторить")
                }
            }
        }
    }
}

@Composable
fun EmptyState(message: String, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = true,
        enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 4 },
    ) {
        Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(message, color = TextSecondary, textAlign = TextAlign.Center, fontSize = 15.sp)
        }
    }
}
