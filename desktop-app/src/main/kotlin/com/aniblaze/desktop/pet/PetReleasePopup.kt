package com.aniblaze.desktop.pet

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.aniblaze.aggregator.model.Anime
import com.aniblaze.desktop.AppSettings
import com.aniblaze.desktop.toAnime
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.key

/** Что показать: тайтл из избранного и номер новой серии. */
data class PetRelease(val anime: Anime, val episode: Int, val at: Long)

/**
 * Окошки «вышла новая серия» — от питомца, поверх ЛЮБОГО экрана, включая плеер.
 *
 * Раньше об этом говорил только питомец в углу, а в плеере угла нет — и новости
 * терялись на весь сеанс просмотра. Окна живут на уровне окна приложения
 * (App.kt), поверх слоя плеера, и берут события у того же трекера релизов, что и
 * уведомления в трее (petFreshRelease → claimPetEvent): один раз на серию.
 *
 * Несколько серий за раз (утро после ночи релизов) — стопка до [RELEASE_STACK]
 * окошек, каждое гаснет само: 25 с в фокусе, 10 с — без фокуса, чтобы не висеть
 * по возвращении. «Смотреть» открывает именно эту серию; «Позже» напомнит через
 * [com.aniblaze.desktop.RELEASE_LATER_MS]; «Не напоминать» глушит тайтл и здесь, и в
 * трее (см. [com.aniblaze.desktop.NewEpisodeNotifier]).
 */
@Composable
fun PetReleasePopup(
    settings: AppSettings,
    onWatch: (Anime, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by settings.state.collectAsState()
    if (!state.petEnabled) return
    val pet = remember(state.petCharacter) { PetDef.of(state.petCharacter) }
    var shown by remember { mutableStateOf<List<PetRelease>>(emptyList()) }
    val focused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused

    LaunchedEffect(Unit) {
        while (true) {
            if (shown.size < RELEASE_STACK) {
                val now = System.currentTimeMillis()
                val snapshot = settings.state.value
                val fresh = petFreshRelease(snapshot, now)
                val episode = fresh?.let { snapshot.newEpisodes[it.id] } ?: 0
                if (fresh != null && episode > 0 && settings.claimPetEvent("new:${fresh.id}:$episode")) {
                    shown = shown + PetRelease(fresh.toAnime(), episode, now)
                    PetSounds.play(PetSounds.Sound.RELEASE)
                    com.aniblaze.desktop.player.PlayerDiagnostics.log("pet.release", "id=${fresh.id}; episode=$episode")
                } else {
                    // «Позже» — срок пришёл: показываем снова, если тайтл всё ещё в избранном.
                    settings.claimDueReminders().forEach { key ->
                        val id = key.removePrefix("new:").substringBeforeLast(':')
                        val ep = key.substringAfterLast(':').toIntOrNull() ?: 0
                        val card = snapshot.favorites.firstOrNull { it.id == id }
                        if (card != null && ep > 0 && !settings.isReleaseMuted(id) && shown.none { it.anime.id == id }) {
                            shown = shown + PetRelease(card.toAnime(), ep, now)
                            com.aniblaze.desktop.player.PlayerDiagnostics.log("pet.release.later", "id=$id; episode=$ep")
                        }
                    }
                }
            }
            delay(RELEASE_POLL_MS)
        }
    }

    if (shown.isEmpty()) return
    val accent = Color(pet.accent)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.End) {
        shown.forEach { release ->
            key(release.anime.id, release.episode, release.at) {
                // Свой таймер у каждого окошка — стопка тает по одному, а не разом.
                LaunchedEffect(focused) {
                    delay(if (focused) RELEASE_SHOW_MS else RELEASE_SHOW_UNFOCUSED_MS)
                    shown = shown - release
                }
                ReleaseCard(
                    pet = pet,
                    accent = accent,
                    release = release,
                    onWatch = { onWatch(release.anime, release.episode); shown = shown - release },
                    onLater = { settings.remindLater("new:${release.anime.id}:${release.episode}"); shown = shown - release },
                    onMute = { settings.setReleaseMuted(release.anime.id, true); shown = shown.filter { it.anime.id != release.anime.id } },
                    onClose = { shown = shown - release },
                )
            }
        }
    }
}

@Composable
private fun ReleaseCard(
    pet: PetDef,
    accent: Color,
    release: PetRelease,
    onWatch: () -> Unit,
    onLater: () -> Unit,
    onMute: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        Modifier.width(360.dp)
            .clip(com.aniblaze.desktop.ui.Shapes.card)
            .background(com.aniblaze.desktop.ui.Surface2)
            .border(BorderStroke(1.dp, accent.copy(alpha = 0.55f)), com.aniblaze.desktop.ui.Shapes.card)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PetSprite(pet = pet, action = PetAction.HAPPY, modifier = Modifier.size(width = 44.dp, height = 48.dp))
        AsyncImage(
            model = com.aniblaze.desktop.ui.posterUrl(release.anime.poster, 160),
            contentDescription = null,
            modifier = Modifier.padding(start = 8.dp).size(width = 40.dp, height = 56.dp).clip(RoundedCornerShape(6.dp)),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        )
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Вышла новая серия", color = accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Закрыть",
                    tint = com.aniblaze.desktop.ui.TextTertiary,
                    modifier = Modifier.size(16.dp).clickable(onClick = onClose),
                )
            }
            Text(
                release.anime.title,
                color = com.aniblaze.desktop.ui.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text("Серия ${release.episode} · в избранном", color = com.aniblaze.desktop.ui.TextSecondary, fontSize = 11.sp)
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    Modifier.clip(RoundedCornerShape(8.dp)).background(accent)
                        .clickable(onClick = onWatch)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.Black, modifier = Modifier.size(15.dp))
                    Text("Смотреть", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 3.dp))
                }
                Text(
                    "Позже", color = com.aniblaze.desktop.ui.TextSecondary, fontSize = 12.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onLater).padding(horizontal = 8.dp, vertical = 5.dp),
                )
                Text(
                    "Не напоминать", color = com.aniblaze.desktop.ui.TextTertiary, fontSize = 12.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onMute).padding(horizontal = 8.dp, vertical = 5.dp),
                )
            }
        }
    }
}

/** Сколько окошек о сериях висит одновременно; остальные дождутся своей очереди. */
const val RELEASE_STACK = 3

/** Как часто сверяться с трекером релизов. */
const val RELEASE_POLL_MS = 5_000L

/** Сколько висит окно в фокусе и без фокуса. */
const val RELEASE_SHOW_MS = 25_000L
const val RELEASE_SHOW_UNFOCUSED_MS = 10_000L
